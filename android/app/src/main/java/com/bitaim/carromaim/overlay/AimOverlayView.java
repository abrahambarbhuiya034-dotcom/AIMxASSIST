package com.bitaim.carromaim.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.View;

import com.bitaim.carromaim.cv.Coin;
import com.bitaim.carromaim.cv.GameState;
import com.bitaim.carromaim.cv.TrajectorySimulator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AimOverlayView — v3 fixed
 *
 * Fixes vs v3-run5:
 *  1. Coin positions are now EMA-smoothed (same as striker). Without smoothing
 *     each coin teleports every frame → jitter + physics explosion.
 *  2. Ghost-ball board-bounds check expanded by striker.radius. Previously a
 *     ghost that was just 1px outside the board was discarded even though the
 *     shot was geometrically valid — this filtered ALL candidates → zero lines.
 *  3. drawPolyline guards against NaN/Inf coordinates produced by an unstable
 *     simulation (overlapping coin bodies). Lines are skipped rather than
 *     crashing the canvas.
 *  4. The shot mode selector (DIRECT / AI / GOLDEN / LUCKY / ALL) now actually
 *     filters which shot categories are drawn.
 */
public class AimOverlayView extends View {

    public static final String MODE_ALL    = "ALL";
    public static final String MODE_DIRECT = "DIRECT";
    public static final String MODE_AI     = "AI";
    public static final String MODE_GOLDEN = "GOLDEN";
    public static final String MODE_LUCKY  = "LUCKY";

    private static final int   MAX_LINES  = 5;
    private static final float EMA_ALPHA  = 0.20f;

    private final TrajectorySimulator simulator = new TrajectorySimulator();
    private String    shotMode = MODE_ALL;
    private GameState detected;
    private GameState smoothed;
    private final float dp;

    private final Paint aimPaint, bouncePaint, bounce2Paint;
    private final Paint coinPathPaint, pocketPathPaint;
    private final Paint strikerPaint, coinOutlinePaint, pocketFill;
    private final Paint boardPaint;
    private final Paint blackFill, whiteFill, redFill;
    private final Paint textPaint, watermarkPaint;

    public AimOverlayView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;

        aimPaint        = stroke(0xFFFFD700, 3.5f);
        bouncePaint     = stroke(0xFF00E5FF, 2.8f);
        bounce2Paint    = stroke(0xFFD946EF, 2.8f);
        coinPathPaint   = stroke(0xFFFF8A00, 3.0f);
        pocketPathPaint = stroke(0xFF22C55E, 4.0f);

        strikerPaint     = stroke(0xFFFFD700, 2.2f);
        coinOutlinePaint = stroke(0x88FFFFFF, 1.5f);
        pocketFill       = fill(0x882ECC71);
        boardPaint       = stroke(0x44FFD700, 1.2f);
        boardPaint.setPathEffect(new DashPathEffect(new float[]{6*dp, 6*dp}, 0));

        blackFill = fill(0x55000000);
        whiteFill = fill(0x44FFFFFF);
        redFill   = fill(0x55FF3D71);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(12 * dp);
        textPaint.setShadowLayer(2 * dp, 0, 0, Color.BLACK);

        watermarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        watermarkPaint.setColor(0x33FFFFFF);
        watermarkPaint.setTextSize(9 * dp);
        watermarkPaint.setTextAlign(Paint.Align.CENTER);
        watermarkPaint.setShadowLayer(1 * dp, 0, 0, Color.BLACK);

        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    private Paint stroke(int color, float w) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * dp); p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND); return p;
    }
    private Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(Paint.Style.FILL); return p;
    }

    public void setShotMode(String mode) { this.shotMode = mode; postInvalidate(); }
    public void setMarginOffset(float dx, float dy) { }
    public void setSensitivity(float v) { }

    public void setDetectedState(GameState s) {
        if (s == null) return;
        detected = s;
        applySmoothing(s);
        postInvalidate();
    }

    // ── EMA smoothing ────────────────────────────────────────────────────────

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

        // FIX #1 — smooth coin positions.
        // Match raw coins to previous-frame coins by nearest-neighbour, then EMA.
        out.coins = smoothCoins(smoothed.coins, raw.coins);

        out.pockets = raw.pockets.isEmpty() ? smoothed.pockets : raw.pockets;
        smoothed = out;
    }

    /**
     * Match each raw coin to its closest previous-frame counterpart (within
     * 2× average radius) and apply EMA to its position. Unmatched coins are
     * kept as-is (they just appeared). This eliminates the per-frame teleport
     * that caused the physics simulation to receive wildly different inputs
     * every 33 ms.
     */
    private List<Coin> smoothCoins(List<Coin> prev, List<Coin> next) {
        if (prev == null || prev.isEmpty()) return next;
        if (next == null || next.isEmpty()) return new ArrayList<>();

        List<Coin> result = new ArrayList<>(next.size());
        boolean[] matched = new boolean[prev.size()];

        for (Coin n : next) {
            Coin bestPrev = null;
            float bestDist = Float.MAX_VALUE;
            int bestIdx = -1;

            for (int i = 0; i < prev.size(); i++) {
                if (matched[i]) continue;
                Coin p = prev.get(i);
                if (p.color != n.color) continue; // only match same type
                float dx = p.pos.x - n.pos.x, dy = p.pos.y - n.pos.y;
                float d = (float) Math.sqrt(dx*dx + dy*dy);
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

    // ── Draw ─────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        GameState s = smoothed != null ? smoothed : detected;
        if (s == null || s.striker == null) return;

        if (s.board != null) {
            canvas.drawRect(s.board, boardPaint);
            canvas.drawText("created by abraham / Xhay",
                    s.board.centerX(), s.board.centerY(), watermarkPaint);
        }

        for (PointF p : s.pockets)
            canvas.drawCircle(p.x, p.y, 13 * dp, pocketFill);

        for (Coin c : s.coins) {
            Paint f = c.color == Coin.COLOR_BLACK ? blackFill
                    : c.color == Coin.COLOR_RED   ? redFill : whiteFill;
            canvas.drawCircle(c.pos.x, c.pos.y, c.radius, f);
            canvas.drawCircle(c.pos.x, c.pos.y, c.radius, coinOutlinePaint);
        }

        canvas.drawCircle(s.striker.pos.x, s.striker.pos.y, s.striker.radius, whiteFill);
        canvas.drawCircle(s.striker.pos.x, s.striker.pos.y, s.striker.radius, strikerPaint);

        List<ShotCandidate> shots = computeBestShots(s);
        int drawn = 0;
        for (ShotCandidate shot : shots) {
            if (drawn >= MAX_LINES) break;
            drawShot(canvas, s, shot);
            drawn++;
        }
    }

    // ── Shot candidates ───────────────────────────────────────────────────────

    private static class ShotCandidate {
        final PointF ghostPos;
        final Coin   coin;
        final PointF pocket;
        final float  score;
        final int    wallsNeeded; // 0 = direct, 1 = one wall, etc.
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

            float dx = coin.pos.x - bestPocket.x;
            float dy = coin.pos.y - bestPocket.y;
            float len = (float) Math.sqrt(dx*dx + dy*dy);
            if (len < 1f) continue;
            float ghostR = s.striker.radius + coin.radius;
            PointF ghost = new PointF(
                    coin.pos.x + (dx / len) * ghostR,
                    coin.pos.y + (dy / len) * ghostR);

            // FIX #2 — expand board check by striker.radius so near-wall shots
            // are not discarded. The original check was too strict and dropped
            // every candidate when the board was detected slightly smaller than
            // the actual playing area.
            if (s.board != null) {
                float margin = s.striker.radius;
                RectF expanded = new RectF(
                    s.board.left   - margin, s.board.top    - margin,
                    s.board.right  + margin, s.board.bottom + margin);
                if (!expanded.contains(ghost.x, ghost.y)) continue;
            }

            // Rough estimate of how many walls the striker needs to go through
            int wallsNeeded = 0;
            if (s.board != null) {
                float strikerToCoinDist = dist(s.striker.pos, ghost);
                // Simple heuristic: if ghost is far and on opposite side, likely needs a wall
                float boardDiag = (float) Math.sqrt(
                    s.board.width()*s.board.width() + s.board.height()*s.board.height());
                if (strikerToCoinDist > boardDiag * 0.7f) wallsNeeded = 1;
            }

            // Filter by shot mode
            if (!shotModeAllows(wallsNeeded)) continue;

            float score = 800f / (dist(s.striker.pos, ghost) + 1f)
                        + 400f / (bestDist + 1f);
            if (coin.color == Coin.COLOR_RED) score *= 1.4f;

            list.add(new ShotCandidate(ghost, coin, bestPocket, score, wallsNeeded));
        }

        Collections.sort(list, (a, b) -> Float.compare(b.score, a.score));
        return list;
    }

    /** Returns true if this candidate should be drawn under the current shot mode. */
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

    private void drawShot(Canvas canvas, GameState s, ShotCandidate shot) {
        // Aim line: striker → ghost position
        canvas.drawLine(s.striker.pos.x, s.striker.pos.y,
                shot.ghostPos.x, shot.ghostPos.y, aimPaint);

        // Ghost ball circle at contact point
        canvas.drawCircle(shot.ghostPos.x, shot.ghostPos.y,
                s.striker.radius, coinOutlinePaint);

        // Coin-to-pocket line
        if (shot.coin != null && shot.pocket != null) {
            canvas.drawLine(shot.coin.pos.x, shot.coin.pos.y,
                    shot.pocket.x, shot.pocket.y, coinPathPaint);
            canvas.drawCircle(shot.pocket.x, shot.pocket.y, 16*dp, pocketPathPaint);
        }

        // Physics simulation for striker path after contact
        List<TrajectorySimulator.PathSegment> segs = simulator.simulate(
                s.striker, shot.ghostPos, s.coins, s.pockets, s.board, 1.0f);
        int segDrawn = 0;
        for (TrajectorySimulator.PathSegment seg : segs) {
            if (segDrawn >= 2) break;
            drawPolyline(canvas, seg.points, paintForSeg(seg));
            segDrawn++;
        }
    }

    private Paint paintForSeg(TrajectorySimulator.PathSegment seg) {
        if (seg.enteredPocket)  return pocketPathPaint;
        if (seg.wallBounces == 0) return aimPaint;
        if (seg.wallBounces == 1) return bouncePaint;
        return bounce2Paint;
    }

    // FIX #3 — skip NaN/Inf segments so a bad simulation never crashes onDraw.
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
        float dx = a.x-b.x, dy = a.y-b.y;
        return (float) Math.sqrt(dx*dx+dy*dy);
    }
}
