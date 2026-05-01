package com.bitaim.carromaim.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.View;

import com.bitaim.carromaim.cv.CarromAI;
import com.bitaim.carromaim.cv.Coin;
import com.bitaim.carromaim.cv.GameState;
import com.bitaim.carromaim.cv.TrajectorySimulator;

import java.util.ArrayList;
import java.util.List;

/**
 * AimOverlayView — v8.1 FIXED
 *
 * Fixes vs v8.0:
 *   1. Trajectory lines start at the COIN HIT POINT (ghost-ball contact),
 *      NOT at the striker centre. The striker's own path segment (kind==0)
 *      is skipped; only coin-body segments are drawn.
 *   2. Every line is clipped to the board rectangle using Cohen-Sutherland —
 *      nothing is ever rendered outside the board.
 *   3. Trajectory is CACHED: recomputed only when the striker moves more
 *      than CACHE_THRESH_PX pixels — never every frame.
 *   4. Max wall bounces drawn per trajectory capped at MAX_BOUNCES (2).
 *      Each successive bounce segment fades out.
 */
public class AimOverlayView extends View {

    public static final String MODE_ALL    = "ALL";
    public static final String MODE_DIRECT = "DIRECT";
    public static final String MODE_AI     = "AI";
    public static final String MODE_GOLDEN = "GOLDEN";
    public static final String MODE_LUCKY  = "LUCKY";

    private static final int   MAX_LINES       = 5;
    private static final float EMA_ALPHA       = 0.18f;
    /** Pixels the striker must move before trajectories are recomputed. */
    private static final float CACHE_THRESH_PX = 4f;
    /** Max wall-bounce segments drawn per shot. */
    private static final int   MAX_BOUNCES     = 2;

    private static final int[]   LINE_COLORS = {
        0xFFFFD700, 0xFF00E5FF, 0xFFFF8A00, 0xFFD946EF, 0xFF22C55E
    };
    private static final int[]   LINE_ALPHAS = { 255, 178, 127, 89, 51 };
    private static final float[] LINE_WIDTHS = { 3.5f, 3.0f, 2.5f, 2.0f, 1.5f };

    private final TrajectorySimulator simulator = new TrajectorySimulator();
    private String    shotMode   = MODE_ALL;
    private GameState detected;
    private GameState smoothed;
    private boolean   hasLiveData = false;
    private final float dp;

    // ── Trajectory cache ─────────────────────────────────────────────────────
    private float cacheStrikerX = Float.NaN;
    private float cacheStrikerY = Float.NaN;
    private List<CarromAI.AiShot> cachedShots = new ArrayList<>();
    /** Per-rank list of COIN-ONLY path segments (striker path excluded). */
    private List<List<TrajectorySimulator.PathSegment>> cachedTrajectories = new ArrayList<>();

    // ── BestShot (used by FloatingOverlayService for autoplay gesture) ────────
    public static class BestShot {
        public final float strikerX, strikerY;
        public final float targetX,  targetY;
        public final float powerFrac;
        public BestShot(float sx, float sy, float tx, float ty, float pw) {
            strikerX = sx; strikerY = sy; targetX = tx; targetY = ty; powerFrac = pw;
        }
    }

    private volatile BestShot lastBestShot;

    public BestShot getLastBestShot() { return hasLiveData ? lastBestShot : null; }

    public void setPhysicsBestShot(CarromAI.AiShot aiShot, GameState state) {
        if (aiShot == null || state == null || state.striker == null) return;
        float dx = aiShot.ghostPos.x - state.striker.pos.x;
        float dy = aiShot.ghostPos.y - state.striker.pos.y;
        float factor = 1.20f;
        lastBestShot = new BestShot(
            state.striker.pos.x, state.striker.pos.y,
            state.striker.pos.x + dx * factor,
            state.striker.pos.y + dy * factor,
            aiShot.powerFrac);
    }

    // ── AutoPlay swipe listener ───────────────────────────────────────────────
    public interface AutoplaySwipeListener {
        void onPerformSwipe(float fromX, float fromY,
                            float toX,   float toY,
                            int   durationMs, float powerFrac);
    }
    private AutoplaySwipeListener autoplaySwipeListener;
    public void setAutoplaySwipeListener(AutoplaySwipeListener l) {
        autoplaySwipeListener = l;
    }

    // ── Per-rank paints ───────────────────────────────────────────────────────
    private final Paint[] aimPaints      = new Paint[MAX_LINES];
    private final Paint[] bouncePaints   = new Paint[MAX_LINES];
    private final Paint[] coinPathPaints = new Paint[MAX_LINES];

    private final Paint strikerPaint, coinOutlinePaint, pocketFill;
    private final Paint boardPaint, boardDemoPaint;
    private final Paint blackFill, whiteFill, redFill;
    private final Paint watermarkPaint;
    private final Paint ghostPaint, arrowPaint;

    public AimOverlayView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;

        for (int i = 0; i < MAX_LINES; i++) {
            int a = LINE_ALPHAS[i]; float w = LINE_WIDTHS[i];
            aimPaints[i]      = strokeA(LINE_COLORS[i], w,        a);
            bouncePaints[i]   = strokeA(0xFF00E5FF,      w - 0.5f, a);
            coinPathPaints[i] = strokeA(LINE_COLORS[i],  w,        (int)(a * 0.65f));
        }

        strikerPaint     = stroke(0xFFFFD700, 2.2f);
        coinOutlinePaint = stroke(0x88FFFFFF, 1.5f);
        pocketFill       = fill(0x882ECC71);

        boardPaint = stroke(0x66FFD700, 1.2f);
        boardPaint.setPathEffect(new DashPathEffect(new float[]{6*dp, 6*dp}, 0));
        boardDemoPaint = stroke(0x33FFD700, 1.0f);
        boardDemoPaint.setPathEffect(new DashPathEffect(new float[]{4*dp, 8*dp}, 0));

        blackFill = fill(0x55000000);
        whiteFill = fill(0x44FFFFFF);
        redFill   = fill(0x55FF3D71);

        watermarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        watermarkPaint.setColor(0x33FFFFFF);
        watermarkPaint.setTextSize(9 * dp);
        watermarkPaint.setTextAlign(Paint.Align.CENTER);
        watermarkPaint.setShadowLayer(dp, 0, 0, Color.BLACK);

        ghostPaint = stroke(0x99FFFFFF, 1.5f);
        arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setColor(0xFFFF8C00);
        arrowPaint.setStyle(Paint.Style.FILL);

        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setShotMode(String mode) {
        this.shotMode = mode;
        invalidateCache();
        postInvalidate();
    }

    public void setDetectedState(GameState s) { setStateInternal(s, true); }
    public void setDemoState(GameState s)     { setStateInternal(s, false); }

    private void setStateInternal(GameState s, boolean live) {
        if (s == null) return;
        if (live) hasLiveData = true;
        detected = s;
        applySmoothing(s);
        if (live) rebuildCacheIfNeeded();
        postInvalidate();
    }

    // ── Cache management ──────────────────────────────────────────────────────

    private void invalidateCache() {
        cacheStrikerX = Float.NaN;
        cacheStrikerY = Float.NaN;
    }

    /**
     * Rebuilds the shot + trajectory cache only when the striker has moved
     * at least CACHE_THRESH_PX pixels since the last rebuild.
     * Draw calls always use cachedShots / cachedTrajectories — zero per-frame
     * physics cost when the striker is stationary.
     */
    private void rebuildCacheIfNeeded() {
        GameState s = smoothed != null ? smoothed : detected;
        if (s == null || s.striker == null) { invalidateCache(); return; }

        float sx = s.striker.pos.x, sy = s.striker.pos.y;

        // Skip rebuild if striker hasn't moved enough
        if (!Float.isNaN(cacheStrikerX)) {
            float dx = sx - cacheStrikerX, dy = sy - cacheStrikerY;
            if (dx*dx + dy*dy < CACHE_THRESH_PX * CACHE_THRESH_PX) return;
        }

        cacheStrikerX = sx;
        cacheStrikerY = sy;

        // Rebuild shot list
        cachedShots = computeFilteredShots(s);

        // Rebuild trajectory — skip the striker body (kind == 0).
        // We only want coin paths so lines originate at the coin hit point.
        cachedTrajectories = new ArrayList<>();
        int limit = Math.min(3, cachedShots.size());
        for (int rank = 0; rank < limit; rank++) {
            CarromAI.AiShot shot = cachedShots.get(rank);
            List<TrajectorySimulator.PathSegment> all = simulator.simulate(
                s.striker, shot.ghostPos, s.coins, s.pockets, s.board, 1.0f);

            List<TrajectorySimulator.PathSegment> coinOnly = new ArrayList<>();
            for (TrajectorySimulator.PathSegment seg : all) {
                if (seg.kind == 0) continue;          // skip striker's own path
                if (seg.wallBounces > MAX_BOUNCES) continue; // cap bounces
                coinOnly.add(seg);
            }
            cachedTrajectories.add(coinOnly);
        }

        // Update best-shot for autoplay
        if (!cachedShots.isEmpty()) {
            CarromAI.AiShot best = cachedShots.get(0);
            float dx = best.ghostPos.x - sx;
            float dy = best.ghostPos.y - sy;
            float factor = 1.20f;
            lastBestShot = new BestShot(sx, sy,
                sx + dx * factor, sy + dy * factor, best.powerFrac);
        } else {
            lastBestShot = null;
        }
    }

    public void performBestSwipe() {
        if (!hasLiveData) return;
        BestShot bs = lastBestShot;
        if (bs == null || autoplaySwipeListener == null) return;
        autoplaySwipeListener.onPerformSwipe(
            bs.strikerX, bs.strikerY, bs.targetX, bs.targetY,
            70, bs.powerFrac);
    }

    // ── Shot computation ──────────────────────────────────────────────────────

    private List<CarromAI.AiShot> computeFilteredShots(GameState s) {
        List<CarromAI.AiShot> all = CarromAI.findBestShots(s, MAX_LINES * 3);
        List<CarromAI.AiShot> out = new ArrayList<>();
        for (CarromAI.AiShot shot : all) {
            if (modeAllows(shot.wallsNeeded, shot.isBank)) {
                out.add(shot);
                if (out.size() >= MAX_LINES) break;
            }
        }
        return out;
    }

    private boolean modeAllows(int walls, boolean isBank) {
        switch (shotMode) {
            case MODE_DIRECT: return walls == 0 && !isBank;
            case MODE_AI:     return walls == 0;
            case MODE_GOLDEN: return walls <= 1;
            case MODE_LUCKY:  return walls <= 2;
            default:          return true;
        }
    }

    // ── EMA smoothing ─────────────────────────────────────────────────────────

    private void applySmoothing(GameState raw) {
        if (smoothed == null) { smoothed = raw; return; }
        GameState out = new GameState();
        out.board = smoothRect(smoothed.board, raw.board);
        if (raw.striker != null) {
            out.striker = (smoothed.striker != null)
                ? new Coin(ema(smoothed.striker.pos.x, raw.striker.pos.x),
                           ema(smoothed.striker.pos.y, raw.striker.pos.y),
                           ema(smoothed.striker.radius, raw.striker.radius),
                           Coin.COLOR_STRIKER, true)
                : raw.striker;
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
            Coin bestPrev = null; float bestD = Float.MAX_VALUE; int bi = -1;
            for (int i = 0; i < prev.size(); i++) {
                if (matched[i]) continue;
                Coin p = prev.get(i);
                if (p.color != n.color) continue;
                float dx = p.pos.x-n.pos.x, dy = p.pos.y-n.pos.y;
                float d  = (float) Math.sqrt(dx*dx+dy*dy);
                if (d < bestD && d < (p.radius+n.radius)*2f) { bestD=d; bestPrev=p; bi=i; }
            }
            if (bestPrev != null) {
                matched[bi] = true;
                result.add(new Coin(ema(bestPrev.pos.x, n.pos.x),
                                    ema(bestPrev.pos.y, n.pos.y),
                                    ema(bestPrev.radius, n.radius),
                                    n.color, n.isStriker));
            } else { result.add(n); }
        }
        return result;
    }

    private RectF smoothRect(RectF p, RectF n) {
        if (p == null) return n; if (n == null) return p;
        return new RectF(ema(p.left,n.left), ema(p.top,n.top),
                         ema(p.right,n.right), ema(p.bottom,n.bottom));
    }
    private float ema(float p, float n) { return p + EMA_ALPHA*(n-p); }

    // ── Draw ──────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        GameState s = smoothed != null ? smoothed : detected;
        if (s == null || s.striker == null) return;

        RectF board = s.board;

        if (board != null) {
            canvas.drawRect(board, hasLiveData ? boardPaint : boardDemoPaint);
            canvas.drawText("created by abraham / Xhay",
                    board.centerX(), board.centerY(), watermarkPaint);
        }

        for (PointF p : s.pockets)
            canvas.drawCircle(p.x, p.y, 13*dp, pocketFill);

        // Use cached results — no physics recomputation every frame
        List<CarromAI.AiShot> shots = cachedShots;

        // Draw shots back-to-front (rank-1 ends up on top)
        for (int rank = shots.size()-1; rank >= 0; rank--) {
            CarromAI.AiShot shot = shots.get(rank);
            Paint aimP    = aimPaints[rank];
            Paint bounceP = bouncePaints[rank];
            Paint coinP   = coinPathPaints[rank];

            // ── Striker → ghost aim line (clipped to board) ──────────────────
            drawLineSafe(canvas,
                s.striker.pos.x, s.striker.pos.y,
                shot.ghostPos.x,  shot.ghostPos.y,
                aimP, board);

            // Ghost-ball circle at coin contact point
            canvas.drawCircle(shot.ghostPos.x, shot.ghostPos.y,
                              s.striker.radius, rank == 0 ? coinOutlinePaint : ghostPaint);

            // ── Coin → pocket path (clipped to board) ────────────────────────
            if (shot.coin != null && shot.pocket != null)
                drawLineSafe(canvas,
                    shot.coin.pos.x, shot.coin.pos.y,
                    shot.pocket.x,   shot.pocket.y,
                    coinP, board);

            // ── Coin-body trajectory segments (cached, no striker path) ──────
            if (rank < cachedTrajectories.size()) {
                List<TrajectorySimulator.PathSegment> segs = cachedTrajectories.get(rank);
                int bounceCount = 0;
                for (TrajectorySimulator.PathSegment seg : segs) {
                    if (bounceCount >= MAX_BOUNCES) break;
                    // Fade each bounce: alpha decreases by 55 per bounce
                    int alpha = Math.max(30, LINE_ALPHAS[rank] - bounceCount * 55);
                    Paint segPaint = seg.wallBounces > 0 ? bounceP : aimP;
                    segPaint.setAlpha(alpha);
                    drawPolylineClipped(canvas, seg.points, segPaint, board);
                    segPaint.setAlpha(LINE_ALPHAS[rank]); // restore
                    bounceCount++;
                }
            }
        }

        // Orange aim arrow on best shot
        if (!shots.isEmpty())
            drawArrow(canvas, s.striker.pos, shots.get(0).ghostPos);

        // Coins
        for (Coin c : s.coins) {
            Paint f = c.color == Coin.COLOR_BLACK ? blackFill
                    : c.color == Coin.COLOR_RED   ? redFill : whiteFill;
            canvas.drawCircle(c.pos.x, c.pos.y, c.radius, f);
            canvas.drawCircle(c.pos.x, c.pos.y, c.radius, coinOutlinePaint);
        }
        // Striker drawn last — always on top
        canvas.drawCircle(s.striker.pos.x, s.striker.pos.y, s.striker.radius, whiteFill);
        canvas.drawCircle(s.striker.pos.x, s.striker.pos.y, s.striker.radius, strikerPaint);
    }

    // ── Draw helpers ──────────────────────────────────────────────────────────

    /** Draw a single line, clipped to the board rect if board is non-null. */
    private void drawLineSafe(Canvas canvas,
                               float x0, float y0, float x1, float y1,
                               Paint p, RectF board) {
        if (!isFinite(x0, y0, x1, y1)) return;
        if (board != null) {
            float[] c = clipLineToRect(x0, y0, x1, y1, board);
            if (c != null) canvas.drawLine(c[0], c[1], c[2], c[3], p);
        } else {
            canvas.drawLine(x0, y0, x1, y1, p);
        }
    }

    /** Draw a polyline, clipping every segment to the board rect. */
    private void drawPolylineClipped(Canvas canvas, List<PointF> pts, Paint p, RectF board) {
        for (int i = 1; i < pts.size(); i++) {
            float x0 = pts.get(i-1).x, y0 = pts.get(i-1).y;
            float x1 = pts.get(i).x,   y1 = pts.get(i).y;
            drawLineSafe(canvas, x0, y0, x1, y1, p, board);
        }
    }

    /**
     * Cohen-Sutherland line clipping against a rectangle.
     * Returns {x0, y0, x1, y1} of the visible segment, or null if fully outside.
     */
    private static float[] clipLineToRect(float x0, float y0, float x1, float y1, RectF r) {
        int code0 = outcode(x0, y0, r);
        int code1 = outcode(x1, y1, r);

        while (true) {
            if ((code0 | code1) == 0) return new float[]{x0, y0, x1, y1}; // both inside
            if ((code0 & code1) != 0) return null; // trivially outside

            int codeOut = (code0 != 0) ? code0 : code1;
            float x, y;

            if ((codeOut & 8) != 0) {         // above (y < top)
                x = x0 + (x1-x0) * (r.top - y0) / (y1-y0); y = r.top;
            } else if ((codeOut & 4) != 0) {  // below (y > bottom)
                x = x0 + (x1-x0) * (r.bottom - y0) / (y1-y0); y = r.bottom;
            } else if ((codeOut & 2) != 0) {  // right
                y = y0 + (y1-y0) * (r.right - x0) / (x1-x0); x = r.right;
            } else {                           // left
                y = y0 + (y1-y0) * (r.left - x0) / (x1-x0); x = r.left;
            }

            if (codeOut == code0) { x0 = x; y0 = y; code0 = outcode(x0, y0, r); }
            else                  { x1 = x; y1 = y; code1 = outcode(x1, y1, r); }
        }
    }

    private static int outcode(float x, float y, RectF r) {
        int c = 0;
        if (x < r.left)   c |= 1;
        if (x > r.right)  c |= 2;
        if (y > r.bottom) c |= 4;
        if (y < r.top)    c |= 8;
        return c;
    }

    private void drawArrow(Canvas canvas, PointF from, PointF to) {
        float dx = to.x-from.x, dy = to.y-from.y;
        float len = (float) Math.sqrt(dx*dx+dy*dy);
        if (len < 1f) return;
        float ux = dx/len, uy = dy/len;
        float tipX = from.x + ux*22*dp, tipY = from.y + uy*22*dp;
        float al = 14*dp, aw = 7*dp;
        Path path = new Path();
        path.moveTo(tipX, tipY);
        path.lineTo(tipX - ux*al + uy*aw, tipY - uy*al - ux*aw);
        path.lineTo(tipX - ux*al - uy*aw, tipY - uy*al + ux*aw);
        path.close();
        canvas.drawPath(path, arrowPaint);
    }

    private static boolean isFinite(float... v) {
        for (float f : v) if (Float.isNaN(f)||Float.isInfinite(f)) return false;
        return true;
    }

    // ── Paint helpers ─────────────────────────────────────────────────────────

    private Paint stroke(int color, float w) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w*dp); p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND); return p;
    }
    private Paint strokeA(int color, float w, int alpha) {
        Paint p = stroke(color, w); p.setAlpha(alpha); return p;
    }
    private Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(Paint.Style.FILL); return p;
    }
}
