package com.bitaim.carromaim.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.bitaim.carromaim.cv.Coin;
import com.bitaim.carromaim.cv.GameState;
import com.bitaim.carromaim.cv.TrajectorySimulator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AimOverlayView — v4 (Autoplay + Multi-Line)
 *
 * NEW vs v3-fixed:
 *  1. AUTOPLAY — automatically fires the best shot every autoplayDelayMs ms.
 *     setAutoplay(true) starts the loop; it calls AutoplaySwipeListener which
 *     OverlayService wires to a GestureDescription/AccessibilityService swipe.
 *
 *  2. MULTIPLE PREDICTION LINES with distinct colours and opacity:
 *       Rank 1 (best) → Gold   #FFD700  100% alpha  3.5 dp
 *       Rank 2        → Cyan   #00E5FF   70% alpha  3.0 dp
 *       Rank 3        → Orange #FF8A00   50% alpha  2.5 dp
 *       Rank 4        → Purple #D946EF   35% alpha  2.0 dp
 *       Rank 5        → Green  #22C55E   20% alpha  1.5 dp
 *
 *  3. segDrawn limit raised 2 → 3 so full striker trajectory is visible.
 *
 * All v3-fixed fixes (EMA smoothing, NaN guards, board-check expansion,
 * shot-mode filter) are kept unchanged.
 */
public class AimOverlayView extends View {

    public static final String MODE_ALL    = "ALL";
    public static final String MODE_DIRECT = "DIRECT";
    public static final String MODE_AI     = "AI";
    public static final String MODE_GOLDEN = "GOLDEN";
    public static final String MODE_LUCKY  = "LUCKY";

    private static final int   MAX_LINES  = 5;
    private static final float EMA_ALPHA  = 0.20f;

    private static final int[]   LINE_COLORS = {
        0xFFFFD700, 0xFF00E5FF, 0xFFFF8A00, 0xFFD946EF, 0xFF22C55E
    };
    private static final int[]   LINE_ALPHAS  = { 255, 178, 127, 89, 51 };
    private static final float[] LINE_WIDTHS  = { 3.5f, 3.0f, 2.5f, 2.0f, 1.5f };

    private final TrajectorySimulator simulator = new TrajectorySimulator();
    private String    shotMode = MODE_ALL;
    private GameState detected;
    private GameState smoothed;
    private final float dp;

    // ── BestShot — used by FloatingOverlayService for AutoPlay gestures ──────

    public static class BestShot {
        public final float strikerX;
        public final float strikerY;
        public final float targetX;
        public final float targetY;
        public BestShot(float sx, float sy, float tx, float ty) {
            strikerX = sx; strikerY = sy; targetX = tx; targetY = ty;
        }
    }

    private volatile BestShot lastBestShot;

    /** Returns the most recently computed best shot, or null if none available. */
    public BestShot getLastBestShot() { return lastBestShot; }

    // ── Autoplay ─────────────────────────────────────────────────────────────
    private boolean autoplayEnabled  = false;
    private int     autoplayDelayMs  = 2000;
    private final Handler   autoplayHandler  = new Handler(Looper.getMainLooper());
    private AutoplaySwipeListener autoplaySwipeListener;

    public interface AutoplaySwipeListener {
        void onPerformSwipe(float fromX, float fromY,
                            float toX,   float toY,
                            int   durationMs);
    }

    private final Runnable autoplayRunnable = new Runnable() {
        @Override public void run() {
            if (!autoplayEnabled) return;
            performBestSwipe();
            autoplayHandler.postDelayed(this, autoplayDelayMs);
        }
    };

    // ── Per-rank paint sets ───────────────────────────────────────────────────
    private final Paint[] aimPaints       = new Paint[MAX_LINES];
    private final Paint[] bouncePaints    = new Paint[MAX_LINES];
    private final Paint[] bounce2Paints   = new Paint[MAX_LINES];
    private final Paint[] coinPathPaints  = new Paint[MAX_LINES];
    private final Paint[] pocketPathPaints= new Paint[MAX_LINES];

    private final Paint strikerPaint, coinOutlinePaint, pocketFill;
    private final Paint boardPaint;
    private final Paint blackFill, whiteFill, redFill;
    private final Paint watermarkPaint;

    // ── Screenshot-style per-coin-color line paints ───────────────────────────
    private final Paint redLinePaint, yellowLinePaint, blackLinePaint;
    private final Paint redDotPaint,  yellowDotPaint,  blackDotPaint;
    private final Paint strikerLinePaint;
    private final Paint cyanLinePaint;
    private final Paint arrowPaint;

    public AimOverlayView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;

        for (int i = 0; i < MAX_LINES; i++) {
            int a = LINE_ALPHAS[i]; float w = LINE_WIDTHS[i];
            aimPaints[i]       = strokeA(LINE_COLORS[i], w,        a);
            bouncePaints[i]    = strokeA(0xFF00E5FF,      w - 0.5f, a);
            bounce2Paints[i]   = strokeA(0xFFD946EF,      w - 0.5f, a);
            coinPathPaints[i]  = strokeA(0xFFFF8A00,      w,        a);
            pocketPathPaints[i]= strokeA(0xFF22C55E,      w + 0.5f, a);
        }

        strikerPaint     = stroke(0xFFFFD700, 2.2f);
        coinOutlinePaint = stroke(0x88FFFFFF, 1.5f);
        pocketFill       = fill(0x882ECC71);
        boardPaint       = stroke(0x44FFD700, 1.2f);
        boardPaint.setPathEffect(new DashPathEffect(new float[]{6*dp, 6*dp}, 0));
        blackFill = fill(0x55000000);
        whiteFill = fill(0x44FFFFFF);
        redFill   = fill(0x55FF3D71);

        watermarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        watermarkPaint.setColor(0x33FFFFFF);
        watermarkPaint.setTextSize(9 * dp);
        watermarkPaint.setTextAlign(Paint.Align.CENTER);
        watermarkPaint.setShadowLayer(1 * dp, 0, 0, Color.BLACK);

        // ── Screenshot-style paints ───────────────────────────────────────────
        redLinePaint    = thickLine(0xFFFF2222, 3.5f);
        yellowLinePaint = thickLine(0xFFFFDD00, 3.5f);
        blackLinePaint  = thickLine(0xFF888888, 3.0f);

        redDotPaint     = dotFill(0xFFFF2222);
        yellowDotPaint  = dotFill(0xFFFFDD00);
        blackDotPaint   = dotFill(0xFF888888);

        strikerLinePaint = thickLine(0xFFFFFFFF, 3.5f);
        cyanLinePaint    = thickLine(0xFF00E5FF, 3.0f);

        arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setColor(0xFFFF8C00);
        arrowPaint.setStyle(Paint.Style.FILL);

        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    private Paint thickLine(int color, float w) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * dp);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setShadowLayer(2 * dp, 0, 0, 0x88000000);
        return p;
    }

    private Paint dotFill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        p.setShadowLayer(2 * dp, 0, 0, 0x88000000);
        return p;
    }

    // ── Paint helpers ─────────────────────────────────────────────────────────

    private Paint stroke(int color, float w) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * dp);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    private Paint strokeA(int color, float w, int alpha) {
        Paint p = stroke(color, w);
        p.setAlpha(alpha);
        return p;
    }

    private Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(Paint.Style.FILL);
        return p;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setShotMode(String mode)  { this.shotMode = mode; postInvalidate(); }
    public void setMarginOffset(float dx, float dy) { /* calibration reserved */ }
    public void setSensitivity(float v)   { /* reserved */ }

    /**
     * Enable / disable autoplay. When enabled the overlay picks the best shot
     * and fires the registered AutoplaySwipeListener every autoplayDelayMs ms.
     */
    public void setAutoplay(boolean enabled) {
        autoplayEnabled = enabled;
        autoplayHandler.removeCallbacks(autoplayRunnable);
        if (enabled) {
            autoplayHandler.postDelayed(autoplayRunnable, autoplayDelayMs);
        }
        postInvalidate();
    }

    /** Delay between shots in ms when autoplay is on (default 2000, min 500). */
    public void setAutoplayDelay(int ms) {
        autoplayDelayMs = Math.max(500, ms);
    }

    public boolean isAutoplayEnabled() { return autoplayEnabled; }

    /**
     * Wire in the listener that translates our coordinates to a real device
     * gesture. Typically called by OverlayService which holds the
     * AccessibilityService reference.
     */
    public void setAutoplaySwipeListener(AutoplaySwipeListener l) {
        autoplaySwipeListener = l;
    }

    public void setDetectedState(GameState s) {
        if (s == null) return;
        detected = s;
        applySmoothing(s);
        cacheBestShot();
        postInvalidate();
    }

    private void cacheBestShot() {
        GameState s = smoothed != null ? smoothed : detected;
        if (s == null || s.striker == null) { lastBestShot = null; return; }
        List<ShotCandidate> shots = computeBestShots(s);
        if (shots.isEmpty()) { lastBestShot = null; return; }
        ShotCandidate best = shots.get(0);
        float dx = best.ghostPos.x - s.striker.pos.x;
        float dy = best.ghostPos.y - s.striker.pos.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) { lastBestShot = null; return; }
        float factor = 1.15f;
        lastBestShot = new BestShot(
            s.striker.pos.x, s.striker.pos.y,
            s.striker.pos.x + dx * factor,
            s.striker.pos.y + dy * factor);
    }

    // ── Autoplay ─────────────────────────────────────────────────────────────

    /**
     * Compute best shot and fire the swipe listener. Safe to call manually
     * for a single-shot trigger even when autoplay is off.
     */
    public void performBestSwipe() {
        GameState s = smoothed != null ? smoothed : detected;
        if (s == null || s.striker == null || autoplaySwipeListener == null) return;

        List<ShotCandidate> shots = computeBestShots(s);
        if (shots.isEmpty()) return;

        ShotCandidate best = shots.get(0);

        // Swipe from striker centre toward the ghost contact point.
        // We overshoot slightly (factor 1.15) so the striker reaches the coin.
        float dx   = best.ghostPos.x - s.striker.pos.x;
        float dy   = best.ghostPos.y - s.striker.pos.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) return;

        float factor  = 1.15f;
        float toX     = s.striker.pos.x + dx * factor;
        float toY     = s.striker.pos.y + dy * factor;
        int   duration = 80; // ms — fast swipe like a real player

        lastBestShot = new BestShot(s.striker.pos.x, s.striker.pos.y, toX, toY);
        autoplaySwipeListener.onPerformSwipe(
            s.striker.pos.x, s.striker.pos.y, toX, toY, duration);
    }

    // ── EMA smoothing ─────────────────────────────────────────────────────────

    private void applySmoothing(GameState raw) {
        if (smoothed == null) { smoothed = raw; return; }
        GameState out = new GameState();
        out.board = smoothRect(smoothed.board, raw.board);

        if (raw.striker != null) {
            if (smoothed.striker != null) {
                out.striker = new Coin(
                    ema(smoothed.striker.pos.x, raw.striker.pos.x),
                    ema(smoothed.striker.pos.y, raw.striker.pos.y),
                    ema(smoothed.striker.radius, raw.striker.radius),
                    Coin.COLOR_STRIKER, true);
            } else {
                out.striker = raw.striker;
            }
        }

        out.coins   = smoothCoins(smoothed.coins, raw.coins);
        out.pockets = raw.pockets.isEmpty() ? smoothed.pockets : raw.pockets;
        smoothed = out;
    }

    private List<Coin> smoothCoins(List<Coin> prev, List<Coin> next) {
        if (prev == null || prev.isEmpty()) return next;
        if (next == null || next.isEmpty()) return new ArrayList<>();

        List<Coin> result  = new ArrayList<>(next.size());
        boolean[]  matched = new boolean[prev.size()];

        for (Coin n : next) {
            Coin  bestPrev = null;
            float bestDist = Float.MAX_VALUE;
            int   bestIdx  = -1;

            for (int i = 0; i < prev.size(); i++) {
                if (matched[i]) continue;
                Coin p = prev.get(i);
                if (p.color != n.color) continue;
                float dx = p.pos.x - n.pos.x, dy = p.pos.y - n.pos.y;
                float d  = (float) Math.sqrt(dx*dx + dy*dy);
                float threshold = (p.radius + n.radius) * 2.0f;
                if (d < bestDist && d < threshold) {
                    bestDist = d; bestPrev = p; bestIdx = i;
                }
            }

            if (bestPrev != null) {
                matched[bestIdx] = true;
                result.add(new Coin(
                    ema(bestPrev.pos.x, n.pos.x),
                    ema(bestPrev.pos.y, n.pos.y),
                    ema(bestPrev.radius, n.radius),
                    n.color, n.isStriker));
            } else {
                result.add(n);
            }
        }
        return result;
    }

    private RectF smoothRect(RectF p, RectF n) {
        if (p == null) return n; if (n == null) return p;
        return new RectF(ema(p.left,n.left), ema(p.top,n.top),
                         ema(p.right,n.right), ema(p.bottom,n.bottom));
    }

    private float ema(float p, float n) { return p + EMA_ALPHA * (n - p); }

    // ── Per-coin color paints ─────────────────────────────────────────────────

    private Paint coinLinePaint(int coinColor) {
        switch (coinColor) {
            case Coin.COLOR_RED:    return redLinePaint;
            case Coin.COLOR_BLACK:  return blackLinePaint;
            default:                return yellowLinePaint;
        }
    }

    private Paint coinDotPaint(int coinColor) {
        switch (coinColor) {
            case Coin.COLOR_RED:    return redDotPaint;
            case Coin.COLOR_BLACK:  return blackDotPaint;
            default:                return yellowDotPaint;
        }
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        GameState s = smoothed != null ? smoothed : detected;
        if (s == null || s.striker == null) return;

        // Board outline
        if (s.board != null) {
            canvas.drawRect(s.board, boardPaint);
            canvas.drawText("created by abraham / Xhay",
                    s.board.centerX(), s.board.centerY(), watermarkPaint);
        }

        // Pockets
        for (PointF p : s.pockets)
            canvas.drawCircle(p.x, p.y, 13 * dp, pocketFill);

        if (!s.pockets.isEmpty()) {

            // ── Draw every coin's line to its nearest pocket ──────────────────
            for (Coin c : s.coins) {
                if (c.color == Coin.COLOR_STRIKER) continue;
                PointF nearestPocket = nearestPocket(c.pos, s.pockets);
                if (nearestPocket == null) continue;

                Paint linePaint = coinLinePaint(c.color);
                Paint dotPaint  = coinDotPaint(c.color);

                // Line from coin → pocket
                canvas.drawLine(c.pos.x, c.pos.y,
                        nearestPocket.x, nearestPocket.y, linePaint);

                // Dot at coin end (filled circle)
                canvas.drawCircle(c.pos.x, c.pos.y, 6 * dp, dotPaint);

                // Small circle at pocket end
                canvas.drawCircle(nearestPocket.x, nearestPocket.y, 8 * dp, dotPaint);
            }

            // ── Striker aim line to best shot ─────────────────────────────────
            List<ShotCandidate> shots = computeBestShots(s);
            if (!shots.isEmpty()) {
                ShotCandidate best = shots.get(0);

                // White aim line: striker → ghost contact point
                canvas.drawLine(s.striker.pos.x, s.striker.pos.y,
                        best.ghostPos.x, best.ghostPos.y, strikerLinePaint);

                // Ghost ball circle at contact
                canvas.drawCircle(best.ghostPos.x, best.ghostPos.y,
                        s.striker.radius, coinOutlinePaint);

                // Coin → pocket direction for best shot (cyan)
                if (best.coin != null && best.pocket != null) {
                    canvas.drawLine(best.coin.pos.x, best.coin.pos.y,
                            best.pocket.x, best.pocket.y, cyanLinePaint);
                }

                // Orange arrow at striker showing aim direction
                drawArrow(canvas, s.striker.pos, best.ghostPos);

                // Post-contact trajectory (up to 3 segments)
                List<TrajectorySimulator.PathSegment> segs = simulator.simulate(
                        s.striker, best.ghostPos, s.coins, s.pockets, s.board, 1.0f);
                int segDrawn = 0;
                for (TrajectorySimulator.PathSegment seg : segs) {
                    if (segDrawn >= 3) break;
                    drawPolyline(canvas, seg.points,
                            seg.wallBounces == 0 ? strikerLinePaint : bouncePaints[0]);
                    segDrawn++;
                }
            }
        }

        // ── Draw coins on top of lines ────────────────────────────────────────
        for (Coin c : s.coins) {
            Paint f = c.color == Coin.COLOR_BLACK ? blackFill
                    : c.color == Coin.COLOR_RED   ? redFill : whiteFill;
            canvas.drawCircle(c.pos.x, c.pos.y, c.radius, f);
            canvas.drawCircle(c.pos.x, c.pos.y, c.radius, coinOutlinePaint);
        }

        // Striker
        canvas.drawCircle(s.striker.pos.x, s.striker.pos.y, s.striker.radius, whiteFill);
        canvas.drawCircle(s.striker.pos.x, s.striker.pos.y, s.striker.radius, strikerPaint);
    }

    private PointF nearestPocket(PointF pos, List<PointF> pockets) {
        PointF best = null; float bestD = Float.MAX_VALUE;
        for (PointF p : pockets) {
            float d = dist(pos, p);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    private void drawArrow(Canvas canvas, PointF from, PointF to) {
        float dx = to.x - from.x, dy = to.y - from.y;
        float len = (float) Math.sqrt(dx*dx + dy*dy);
        if (len < 1f) return;
        float ux = dx / len, uy = dy / len;

        // Arrow tip at striker center offset toward target
        float tipX = from.x + ux * 20 * dp;
        float tipY = from.y + uy * 20 * dp;

        float arrowLen = 14 * dp;
        float arrowW   = 7  * dp;

        Path path = new Path();
        path.moveTo(tipX, tipY);
        path.lineTo(tipX - ux * arrowLen + uy * arrowW,
                    tipY - uy * arrowLen - ux * arrowW);
        path.lineTo(tipX - ux * arrowLen - uy * arrowW,
                    tipY - uy * arrowLen + ux * arrowW);
        path.close();
        canvas.drawPath(path, arrowPaint);
    }

    // ── Shot candidates ───────────────────────────────────────────────────────

    private static class ShotCandidate {
        final PointF ghostPos;
        final Coin   coin;
        final PointF pocket;
        final float  score;
        final int    wallsNeeded;
        ShotCandidate(PointF g, Coin c, PointF pk, float sc, int walls) {
            ghostPos = g; coin = c; pocket = pk; score = sc; wallsNeeded = walls;
        }
    }

    private List<ShotCandidate> computeBestShots(GameState s) {
        List<ShotCandidate> list = new ArrayList<>();
        if (s.pockets.isEmpty()) return list;

        for (Coin coin : s.coins) {
            if (coin.color == Coin.COLOR_STRIKER) continue;

            PointF bestPocket = null;
            float  bestDist   = Float.MAX_VALUE;
            for (PointF pk : s.pockets) {
                float d = dist(coin.pos, pk);
                if (d < bestDist) { bestDist = d; bestPocket = pk; }
            }
            if (bestPocket == null) continue;

            float dx  = coin.pos.x - bestPocket.x;
            float dy  = coin.pos.y - bestPocket.y;
            float len = (float) Math.sqrt(dx*dx + dy*dy);
            if (len < 1f) continue;
            float  ghostR = s.striker.radius + coin.radius;
            PointF ghost  = new PointF(
                    coin.pos.x + (dx / len) * ghostR,
                    coin.pos.y + (dy / len) * ghostR);

            if (s.board != null) {
                float margin = s.striker.radius;
                RectF expanded = new RectF(
                    s.board.left - margin, s.board.top    - margin,
                    s.board.right+ margin, s.board.bottom + margin);
                if (!expanded.contains(ghost.x, ghost.y)) continue;
            }

            int wallsNeeded = 0;
            if (s.board != null) {
                float strikerToCoinDist = dist(s.striker.pos, ghost);
                float boardDiag = (float) Math.sqrt(
                    s.board.width()*s.board.width() + s.board.height()*s.board.height());
                if (strikerToCoinDist > boardDiag * 0.7f) wallsNeeded = 1;
            }

            if (!shotModeAllows(wallsNeeded)) continue;

            float score = 800f / (dist(s.striker.pos, ghost) + 1f)
                        + 400f / (bestDist + 1f);
            if (coin.color == Coin.COLOR_RED) score *= 1.4f;

            list.add(new ShotCandidate(ghost, coin, bestPocket, score, wallsNeeded));
        }

        Collections.sort(list, (a, b) -> Float.compare(b.score, a.score));
        return list;
    }

    private boolean shotModeAllows(int wallsNeeded) {
        switch (shotMode) {
            case MODE_DIRECT: return wallsNeeded == 0;
            case MODE_GOLDEN: return wallsNeeded <= 1;
            case MODE_LUCKY:  return wallsNeeded <= 2;
            case MODE_AI:
            case MODE_ALL:
            default:          return true;
        }
    }

    // ── Drawing helpers ───────────────────────────────────────────────────────

    private void drawPolyline(Canvas c, List<PointF> pts, Paint p) {
        for (int i = 1; i < pts.size(); i++) {
            float x0 = pts.get(i-1).x, y0 = pts.get(i-1).y;
            float x1 = pts.get(i).x,   y1 = pts.get(i).y;
            if (Float.isNaN(x0)||Float.isNaN(y0)||Float.isNaN(x1)||Float.isNaN(y1)) continue;
            if (Float.isInfinite(x0)||Float.isInfinite(y0)||Float.isInfinite(x1)||Float.isInfinite(y1)) continue;
            c.drawLine(x0, y0, x1, y1, p);
        }
    }

    private float dist(PointF a, PointF b) {
        float dx = a.x - b.x, dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
