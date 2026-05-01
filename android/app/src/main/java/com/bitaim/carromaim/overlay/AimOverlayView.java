package com.bitaim.carromaim.overlay;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.View;

import com.bitaim.carromaim.cv.Coin;
import com.bitaim.carromaim.cv.GameState;
import com.bitaim.carromaim.cv.TrajectorySimulator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * AimOverlayView — v4
 *
 * Major additions vs v3-fixed:
 *  1. Wall-bounce shot candidates via the reflection principle.
 *     For every coin the system now checks direct shots AND up to 4
 *     one-cushion bounce paths (left / right / top / bottom wall).
 *     The best shots by score are drawn — matching the reference style.
 *  2. Two-pass glow rendering: each line is drawn twice — a wide,
 *     blurred halo pass then a sharp bright pass on top — producing the
 *     blue neon glow seen in the reference image.
 *  3. Ghost-ball circle is drawn with a dashed stroke at the contact point.
 *  4. Coin EMA smoothing (from v3-fixed) retained.
 *  5. MAX_LINES raised to 7 so more simultaneous options are visible.
 *  6. Shot mode filter (DIRECT / GOLDEN / LUCKY / AI / ALL) now respected
 *     for both direct and bounce candidates.
 */
public class AimOverlayView extends View {

    public static final String MODE_ALL    = "ALL";
    public static final String MODE_DIRECT = "DIRECT";
    public static final String MODE_AI     = "AI";
    public static final String MODE_GOLDEN = "GOLDEN";
    public static final String MODE_LUCKY  = "LUCKY";

    private static final int   MAX_LINES  = 7;
    private static final float EMA_ALPHA  = 0.20f;

    /**
     * Data class returned to FloatingOverlayService for auto-shoot gesture.
     * strikerX/Y = striker screen position.
     * targetX/Y  = first intermediate aim point (ghost-ball or wall-bounce point).
     */
    public static final class BestShot {
        public final float strikerX, strikerY;
        public final float targetX,  targetY;
        BestShot(float sx, float sy, float tx, float ty) {
            strikerX=sx; strikerY=sy; targetX=tx; targetY=ty;
        }
    }

    private final TrajectorySimulator simulator = new TrajectorySimulator();
    private String    shotMode  = MODE_ALL;
    private GameState detected;
    private GameState smoothed;
    private volatile BestShot lastBestShot = null;
    private final float dp;

    // ── Paints ───────────────────────────────────────────────────────────────

    // Glow (wide, blurred, semi-transparent)
    private final Paint glowBlue, glowGold, glowGreen;
    // Sharp core lines
    private final Paint lineBlue, lineGold, lineGreen, lineOrange, lineMagenta;
    // Dashed ghost circle
    private final Paint ghostCirclePaint;
    // Coin fills
    private final Paint blackFill, whiteFill, redFill;
    // Pocket indicator
    private final Paint pocketFill, pocketRing;
    // Striker ring
    private final Paint strikerRing;
    // Board outline
    private final Paint boardPaint;
    // Watermark
    private final Paint watermarkPaint;
    // Coin outline (subtle)
    private final Paint coinOutlinePaint;

    public AimOverlayView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;

        // Glow paints (blurred halo drawn first)
        glowBlue    = glow(0xBB4499FF, 14 * dp);
        glowGold    = glow(0xBBFFD700, 12 * dp);
        glowGreen   = glow(0xBB22C55E, 12 * dp);

        // Sharp core lines drawn on top of glow
        lineBlue    = line(0xFFAADDFF, 2.8f);
        lineGold    = line(0xFFFFD700, 3.2f);
        lineGreen   = line(0xFF22C55E, 3.5f);
        lineOrange  = line(0xFFFF8A00, 2.8f);
        lineMagenta = line(0xFFD946EF, 2.8f);

        // Dashed ghost-ball circle
        ghostCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ghostCirclePaint.setStyle(Paint.Style.STROKE);
        ghostCirclePaint.setColor(0xCCFFFFFF);
        ghostCirclePaint.setStrokeWidth(1.8f * dp);
        ghostCirclePaint.setPathEffect(new DashPathEffect(new float[]{5*dp, 4*dp}, 0));

        // Coin fills (semi-transparent)
        blackFill = fill(0x55000000);
        whiteFill = fill(0x44FFFFFF);
        redFill   = fill(0x55FF3D71);

        coinOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        coinOutlinePaint.setStyle(Paint.Style.STROKE);
        coinOutlinePaint.setColor(0x66FFFFFF);
        coinOutlinePaint.setStrokeWidth(1.2f * dp);

        pocketFill = fill(0x7722C55E);
        pocketRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        pocketRing.setStyle(Paint.Style.STROKE);
        pocketRing.setColor(0xFF22C55E);
        pocketRing.setStrokeWidth(2f * dp);

        strikerRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        strikerRing.setStyle(Paint.Style.STROKE);
        strikerRing.setColor(0xFFFFD700);
        strikerRing.setStrokeWidth(2.5f * dp);

        boardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boardPaint.setStyle(Paint.Style.STROKE);
        boardPaint.setColor(0x33FFD700);
        boardPaint.setStrokeWidth(1.2f * dp);
        boardPaint.setPathEffect(new DashPathEffect(new float[]{6*dp, 6*dp}, 0));

        watermarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        watermarkPaint.setColor(0x22FFFFFF);
        watermarkPaint.setTextSize(8 * dp);
        watermarkPaint.setTextAlign(Paint.Align.CENTER);

        setLayerType(LAYER_TYPE_SOFTWARE, null); // required for BlurMaskFilter
    }

    private Paint glow(int color, float radius) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(radius);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setMaskFilter(new BlurMaskFilter(radius * 0.6f, BlurMaskFilter.Blur.NORMAL));
        return p;
    }

    private Paint line(int color, float widthDp) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(widthDp * dp);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    private Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        return p;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setShotMode(String mode) { this.shotMode = mode; postInvalidate(); }
    public void setMarginOffset(float dx, float dy) { }
    public void setSensitivity(float v) { }

    /**
     * Returns the highest-scoring shot from the last rendered frame.
     * Called by FloatingOverlayService to aim the auto-shoot gesture.
     * Thread-safe (volatile field).
     */
    public BestShot getLastBestShot() { return lastBestShot; }

    public void setDetectedState(GameState s) {
        if (s == null) return;
        detected = s;
        applySmoothing(s);
        postInvalidate();
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
        List<Coin> result = new ArrayList<>(next.size());
        boolean[] matched = new boolean[prev.size()];
        for (Coin n : next) {
            Coin bestPrev = null; float bestDist = Float.MAX_VALUE; int bestIdx = -1;
            for (int i = 0; i < prev.size(); i++) {
                if (matched[i]) continue;
                Coin p = prev.get(i);
                if (p.color != n.color) continue;
                float dx = p.pos.x - n.pos.x, dy = p.pos.y - n.pos.y;
                float d  = (float) Math.sqrt(dx*dx + dy*dy);
                if (d < bestDist && d < (p.radius + n.radius) * 2f) {
                    bestDist = d; bestPrev = p; bestIdx = i;
                }
            }
            if (bestPrev != null) {
                matched[bestIdx] = true;
                result.add(new Coin(ema(bestPrev.pos.x, n.pos.x), ema(bestPrev.pos.y, n.pos.y),
                                    ema(bestPrev.radius, n.radius), n.color, n.isStriker));
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

    // ── onDraw ────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        GameState s = smoothed != null ? smoothed : detected;
        if (s == null || s.striker == null) return;

        // Board outline + watermark
        if (s.board != null) {
            canvas.drawRect(s.board, boardPaint);
            canvas.drawText("created by abraham / Xhay",
                    s.board.centerX(), s.board.centerY(), watermarkPaint);
        }

        // Pockets
        for (PointF p : s.pockets) {
            canvas.drawCircle(p.x, p.y, 13 * dp, pocketFill);
            canvas.drawCircle(p.x, p.y, 13 * dp, pocketRing);
        }

        // Coins
        for (Coin c : s.coins) {
            Paint f = c.color == Coin.COLOR_BLACK ? blackFill
                    : c.color == Coin.COLOR_RED   ? redFill : whiteFill;
            canvas.drawCircle(c.pos.x, c.pos.y, c.radius, f);
            canvas.drawCircle(c.pos.x, c.pos.y, c.radius, coinOutlinePaint);
        }

        // Striker
        canvas.drawCircle(s.striker.pos.x, s.striker.pos.y, s.striker.radius, whiteFill);
        canvas.drawCircle(s.striker.pos.x, s.striker.pos.y, s.striker.radius, strikerRing);

        // Prediction lines — glow pass first, then sharp pass
        List<ShotCandidate> shots = computeBestShots(s);
        int drawn = 0;

        // Pass 1: glow (drawn behind everything)
        for (ShotCandidate shot : shots) {
            if (drawn >= MAX_LINES) break;
            drawShotGlow(canvas, s, shot);
            drawn++;
        }

        // Pass 2: sharp lines on top
        drawn = 0;
        for (ShotCandidate shot : shots) {
            if (drawn >= MAX_LINES) break;
            drawShotLines(canvas, s, shot);
            drawn++;
        }
    }

    // ── Shot candidate model ──────────────────────────────────────────────────

    /**
     * strikerWaypoints: the path the STRIKER takes.
     *  - Direct shot: [strikerPos, ghostPos]
     *  - 1-wall shot: [strikerPos, wallBouncePoint, ghostPos]
     * The last point is always the ghost-ball contact position.
     */
    private static class ShotCandidate {
        final List<PointF> strikerWaypoints;
        final Coin   coin;
        final PointF pocket;
        final float  score;
        final int    wallsNeeded;

        ShotCandidate(List<PointF> wp, Coin c, PointF pk, float sc, int walls) {
            strikerWaypoints = wp; coin = c; pocket = pk; score = sc; wallsNeeded = walls;
        }

        PointF ghostPos() {
            return strikerWaypoints.get(strikerWaypoints.size() - 1);
        }
    }

    // ── Shot computation ──────────────────────────────────────────────────────

    private List<ShotCandidate> computeBestShots(GameState s) {
        List<ShotCandidate> list = new ArrayList<>();
        if (s.pockets.isEmpty() || s.board == null) return list;

        float sR = s.striker.radius;
        // Effective cushion positions for the striker centre
        float wL = s.board.left   + sR;
        float wR = s.board.right  - sR;
        float wT = s.board.top    + sR;
        float wB = s.board.bottom - sR;

        for (Coin coin : s.coins) {
            if (coin.color == Coin.COLOR_STRIKER) continue;

            // Nearest pocket for this coin
            PointF pocket = nearestPocket(coin.pos, s.pockets);
            if (pocket == null) continue;

            // Ghost-ball position (where striker centre must be to pot coin)
            PointF ghost = ghostBallPos(coin, pocket, sR);
            if (ghost == null) continue;

            // ── Direct shot ──────────────────────────────────────────────────
            if (MODE_ALL.equals(shotMode) || MODE_DIRECT.equals(shotMode)
                    || MODE_AI.equals(shotMode)) {
                if (insideBoard(ghost, s.board, sR)) {
                    float score = score(s.striker.pos, ghost, dist(coin.pos, pocket), coin.color);
                    list.add(new ShotCandidate(
                        pts(s.striker.pos, ghost), coin, pocket, score, 0));
                }
            }

            // ── 1-cushion bounce shots ────────────────────────────────────────
            if (MODE_ALL.equals(shotMode) || MODE_GOLDEN.equals(shotMode)
                    || MODE_AI.equals(shotMode)) {
                addWallBounces(list, s.striker.pos, ghost, coin, pocket, sR, s.board,
                               wL, wR, wT, wB, 1);
            }

            // ── 2-cushion bounce shots ────────────────────────────────────────
            if (MODE_ALL.equals(shotMode) || MODE_LUCKY.equals(shotMode)) {
                addDoubleBounces(list, s.striker.pos, ghost, coin, pocket, sR, s.board,
                                 wL, wR, wT, wB);
            }
        }

        Collections.sort(list, (a, b) -> Float.compare(b.score, a.score));

        // Cache the best shot for auto-shoot gesture injection
        if (!list.isEmpty() && s.striker != null) {
            ShotCandidate top = list.get(0);
            // Use the second waypoint as aim target (first step of the shot path)
            PointF aimTarget = top.strikerWaypoints.size() > 1
                    ? top.strikerWaypoints.get(1)
                    : top.ghostPos();
            lastBestShot = new BestShot(
                    s.striker.pos.x, s.striker.pos.y,
                    aimTarget.x, aimTarget.y);
        } else {
            lastBestShot = null;
        }

        return list;
    }

    /**
     * Add all valid 1-cushion bounce candidates for striker→ghost via one wall.
     */
    private void addWallBounces(List<ShotCandidate> out,
                                 PointF striker, PointF ghost,
                                 Coin coin, PointF pocket,
                                 float sR, RectF board,
                                 float wL, float wR, float wT, float wB,
                                 int wallCount) {
        float coinToPocket = dist(coin.pos, pocket);

        // Left wall
        bounceVert(out, striker, ghost, coin, pocket, coinToPocket,
                   wL, true,  wT, wB, sR, board, wallCount);
        // Right wall
        bounceVert(out, striker, ghost, coin, pocket, coinToPocket,
                   wR, false, wT, wB, sR, board, wallCount);
        // Top wall
        bounceHoriz(out, striker, ghost, coin, pocket, coinToPocket,
                    wT, true,  wL, wR, sR, board, wallCount);
        // Bottom wall
        bounceHoriz(out, striker, ghost, coin, pocket, coinToPocket,
                    wB, false, wL, wR, sR, board, wallCount);
    }

    /** 2-cushion: reflect ghost across two different walls. */
    private void addDoubleBounces(List<ShotCandidate> out,
                                   PointF striker, PointF ghost,
                                   Coin coin, PointF pocket,
                                   float sR, RectF board,
                                   float wL, float wR, float wT, float wB) {
        // For 2-bounce we reflect twice and add intermediate bounce waypoint.
        // We try: left+top, left+bottom, right+top, right+bottom.
        float cdp = dist(coin.pos, pocket);

        // Reflect ghost across left wall, then top wall
        tryDoubleVH(out, striker, ghost, coin, pocket, cdp, sR, board, wL, true,  wT, true,  wL, wR, wT, wB);
        tryDoubleVH(out, striker, ghost, coin, pocket, cdp, sR, board, wL, true,  wB, false, wL, wR, wT, wB);
        tryDoubleVH(out, striker, ghost, coin, pocket, cdp, sR, board, wR, false, wT, true,  wL, wR, wT, wB);
        tryDoubleVH(out, striker, ghost, coin, pocket, cdp, sR, board, wR, false, wB, false, wL, wR, wT, wB);
    }

    /** Vertical-wall first, then horizontal-wall second, 2-bounce. */
    private void tryDoubleVH(List<ShotCandidate> out,
                              PointF striker, PointF ghost,
                              Coin coin, PointF pocket, float cdp,
                              float sR, RectF board,
                              float wallX, boolean leftWall,
                              float wallY, boolean topWall,
                              float wL, float wR, float wT, float wB) {
        // Reflect ghost across wallX → g1
        PointF g1 = new PointF(2*wallX - ghost.x, ghost.y);
        // Reflect g1 across wallY → g2
        PointF g2 = new PointF(g1.x, 2*wallY - g1.y);

        // Intersection of (striker→g2) with wallY
        float dY2 = g2.y - striker.y;
        if (Math.abs(dY2) < 0.01f) return;
        float t1 = (wallY - striker.y) / dY2;
        if (t1 <= 0.01f || t1 >= 0.99f) return;
        float b1x = striker.x + t1 * (g2.x - striker.x);
        if (b1x < wL || b1x > wR) return;
        PointF bounce1 = new PointF(b1x, wallY);

        // Intersection of (bounce1→g1) with wallX
        float dX1 = g1.x - bounce1.x;
        if (Math.abs(dX1) < 0.01f) return;
        float t2 = (wallX - bounce1.x) / dX1;
        if (t2 <= 0.01f || t2 >= 0.99f) return;
        float b2y = bounce1.y + t2 * (g1.y - bounce1.y);
        if (b2y < wT || b2y > wB) return;
        PointF bounce2 = new PointF(wallX, b2y);

        if (!insideBoard(ghost, board, sR)) return;

        float score = score(striker, bounce1, cdp, coin.color) * 0.45f;
        out.add(new ShotCandidate(pts(striker, bounce1, bounce2, ghost),
                coin, pocket, score, 2));
    }

    /**
     * Bounce off a vertical wall (constant x).
     * Uses the reflection principle: reflect ghost across the wall,
     * then find where the straight line from striker hits the wall.
     */
    private void bounceVert(List<ShotCandidate> out,
                             PointF striker, PointF ghost,
                             Coin coin, PointF pocket, float cdp,
                             float wallX, boolean isLeftWall,
                             float wT, float wB,
                             float sR, RectF board, int wallCount) {
        // Ghost must be on the SAME side as the striker for a wall-bounce to make sense
        // (you bounce off the OPPOSITE wall and come around).
        // Actually: for a left-wall bounce, ghost can be anywhere as long as
        // the reflected line intersects the wall between striker and ghost.

        // Reflect ghost across wallX
        float reflX = 2 * wallX - ghost.x;
        float reflY = ghost.y;

        float dX = reflX - striker.x;
        if (Math.abs(dX) < 0.01f) return;

        float t = (wallX - striker.x) / dX;
        if (t <= 0.02f || t >= 0.98f) return;  // bounce not in-between

        float bY = striker.y + t * (reflY - striker.y);
        if (bY < wT || bY > wB) return;  // outside cushion

        PointF bp = new PointF(wallX, bY);

        if (!insideBoard(ghost, board, sR)) return;

        // Penalty for longer/more-complex shots
        float score = score(striker, bp, cdp, coin.color) * 0.65f;
        out.add(new ShotCandidate(pts(striker, bp, ghost), coin, pocket, score, wallCount));
    }

    /**
     * Bounce off a horizontal wall (constant y).
     */
    private void bounceHoriz(List<ShotCandidate> out,
                              PointF striker, PointF ghost,
                              Coin coin, PointF pocket, float cdp,
                              float wallY, boolean isTopWall,
                              float wL, float wR,
                              float sR, RectF board, int wallCount) {
        float reflX = ghost.x;
        float reflY = 2 * wallY - ghost.y;

        float dY = reflY - striker.y;
        if (Math.abs(dY) < 0.01f) return;

        float t = (wallY - striker.y) / dY;
        if (t <= 0.02f || t >= 0.98f) return;

        float bX = striker.x + t * (reflX - striker.x);
        if (bX < wL || bX > wR) return;

        PointF bp = new PointF(bX, wallY);

        if (!insideBoard(ghost, board, sR)) return;

        float score = score(striker, bp, cdp, coin.color) * 0.65f;
        out.add(new ShotCandidate(pts(striker, bp, ghost), coin, pocket, score, wallCount));
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    private void drawShotGlow(Canvas canvas, GameState s, ShotCandidate shot) {
        Paint glow = shot.wallsNeeded == 0 ? glowGold
                   : shot.wallsNeeded == 1 ? glowBlue : glowBlue;
        drawWaypoints(canvas, shot.strikerWaypoints, glow);

        // Coin-to-pocket glow
        if (shot.coin != null && shot.pocket != null) {
            canvas.drawLine(shot.coin.pos.x, shot.coin.pos.y,
                    shot.pocket.x, shot.pocket.y, glowGreen);
        }
    }

    private void drawShotLines(Canvas canvas, GameState s, ShotCandidate shot) {
        // Select sharp line color by type
        Paint sharp = shot.wallsNeeded == 0 ? lineGold
                    : shot.wallsNeeded == 1 ? lineBlue : lineMagenta;
        drawWaypoints(canvas, shot.strikerWaypoints, sharp);

        // Ghost ball dashed circle at contact point
        PointF ghost = shot.ghostPos();
        canvas.drawCircle(ghost.x, ghost.y, s.striker.radius, ghostCirclePaint);

        // Coin-to-pocket line
        if (shot.coin != null && shot.pocket != null) {
            canvas.drawLine(shot.coin.pos.x, shot.coin.pos.y,
                    shot.pocket.x, shot.pocket.y, lineGreen);
            canvas.drawCircle(shot.pocket.x, shot.pocket.y, 14*dp, pocketRing);
        }

        // Physics simulation path AFTER contact (striker continues after hit)
        List<TrajectorySimulator.PathSegment> segs = simulator.simulate(
                s.striker, ghost, s.coins, s.pockets, s.board, 1.0f);
        int segDrawn = 0;
        for (TrajectorySimulator.PathSegment seg : segs) {
            if (segDrawn >= 1) break; // only striker path after contact
            if (seg.kind != 0) { segDrawn++; continue; }
            Paint p = seg.wallBounces == 0 ? lineGold
                    : seg.wallBounces == 1 ? lineBlue : lineMagenta;
            drawPolyline(canvas, seg.points, p);
            segDrawn++;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void drawWaypoints(Canvas canvas, List<PointF> pts, Paint paint) {
        for (int i = 1; i < pts.size(); i++) {
            float x0 = pts.get(i-1).x, y0 = pts.get(i-1).y;
            float x1 = pts.get(i).x,   y1 = pts.get(i).y;
            if (!valid(x0, y0, x1, y1)) continue;
            canvas.drawLine(x0, y0, x1, y1, paint);
        }
    }

    private void drawPolyline(Canvas canvas, List<PointF> pts, Paint paint) {
        for (int i = 1; i < pts.size(); i++) {
            float x0 = pts.get(i-1).x, y0 = pts.get(i-1).y;
            float x1 = pts.get(i).x,   y1 = pts.get(i).y;
            if (!valid(x0, y0, x1, y1)) continue;
            canvas.drawLine(x0, y0, x1, y1, paint);
        }
    }

    private static boolean valid(float... v) {
        for (float f : v) if (Float.isNaN(f) || Float.isInfinite(f)) return false;
        return true;
    }

    private PointF nearestPocket(PointF pos, List<PointF> pockets) {
        PointF best = null; float bestD = Float.MAX_VALUE;
        for (PointF p : pockets) {
            float d = dist(pos, p);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    /** Ghost-ball position: where striker centre must be to send coin into pocket. */
    private PointF ghostBallPos(Coin coin, PointF pocket, float strikerR) {
        float dx = coin.pos.x - pocket.x;
        float dy = coin.pos.y - pocket.y;
        float len = (float) Math.sqrt(dx*dx + dy*dy);
        if (len < 1f) return null;
        float gr = strikerR + coin.radius;
        return new PointF(coin.pos.x + (dx/len)*gr, coin.pos.y + (dy/len)*gr);
    }

    private boolean insideBoard(PointF p, RectF board, float margin) {
        if (board == null) return true;
        return p.x >= board.left   - margin && p.x <= board.right  + margin
            && p.y >= board.top    - margin && p.y <= board.bottom + margin;
    }

    private float score(PointF striker, PointF ghost, float coinToPocket, int coinColor) {
        float s = 800f / (dist(striker, ghost) + 1f) + 400f / (coinToPocket + 1f);
        if (coinColor == Coin.COLOR_RED) s *= 1.4f;
        return s;
    }

    /** Convenience: build a List<PointF> from varargs PointF. */
    @SafeVarargs
    private static List<PointF> pts(PointF... points) {
        return new ArrayList<>(Arrays.asList(points));
    }

    private static float dist(PointF a, PointF b) {
        float dx = a.x-b.x, dy = a.y-b.y;
        return (float) Math.sqrt(dx*dx+dy*dy);
    }
    private static float dist(PointF a, float bx, float by) {
        float dx = a.x-bx, dy = a.y-by;
        return (float) Math.sqrt(dx*dx+dy*dy);
    }
}
