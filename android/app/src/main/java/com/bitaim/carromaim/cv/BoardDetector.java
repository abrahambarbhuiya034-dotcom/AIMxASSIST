package com.bitaim.carromaim.cv;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * BoardDetector — Pure Java, NO OpenCV dependency.
 *
 * Works on every Android device. Detects the carrom board, striker, and coins
 * directly from Bitmap pixel data using color analysis.
 *
 * Detection pipeline:
 *  1. Downsample bitmap to 360px wide for speed.
 *  2. detectBoard()  — finds orange/brown border to get board bounds.
 *  3. detectCoins()  — grid-scans the board, classifies each cell by dominant
 *                      color (WHITE, BLACK, RED), clusters adjacent cells into
 *                      coin blobs, applies NMS.
 *  4. Identify striker = largest white blob in bottom 38% of board.
 *  5. Add 4 corner pockets.
 *
 * Color definitions for Carrom Disc Pool:
 *  Orange border : R>170 && G in [60,145] && B<70   && R > G+40
 *  Wood surface  : R>140 && G>90  && B<130 && R>B+35
 *  White coin    : all channels >150, spread <55
 *  Black coin    : all channels <90,  spread <40
 *  Red queen     : R>140 && R>2.2*G && R>2.2*B
 */
public class BoardDetector {

    private static final String TAG    = "BoardDetector";
    private static final int    DW     = 360;   // working width
    private static final int    STEP   =   6;   // grid scan step (px in downscaled)

    private float  minRadiusFrac = 0.013f;
    private float  maxRadiusFrac = 0.042f;
    @SuppressWarnings("unused")
    private double param2        = 36;           // kept for API compat

    private RectF  smoothedBoard = null;
    private static final float  EMA   = 0.18f;

    public void setMinRadiusFrac(float v) { minRadiusFrac = Math.max(0.005f, Math.min(v, 0.06f)); }
    public void setMaxRadiusFrac(float v) { maxRadiusFrac = Math.max(0.015f, Math.min(v, 0.10f)); }
    public void setParam2(double v)       { param2 = v; }

    // ── Public entry point ────────────────────────────────────────────────────

    public synchronized GameState detect(Bitmap bitmap) {
        if (bitmap == null) return null;
        try {
            return detectInternal(bitmap);
        } catch (Throwable t) {
            return fallbackState(bitmap.getWidth(), bitmap.getHeight());
        }
    }

    // ── Main pipeline ─────────────────────────────────────────────────────────

    private GameState detectInternal(Bitmap src) {
        int W = src.getWidth(), H = src.getHeight();

        // Downsample
        int dh = (int) (H * (DW / (float) W));
        Bitmap small = Bitmap.createScaledBitmap(src, DW, dh, false);
        int[] px = new int[DW * dh];
        small.getPixels(px, 0, DW, 0, 0, DW, dh);
        small.recycle();

        float scale = W / (float) DW;   // multiply downscaled coords × scale → screen coords

        // 1. Board bounds
        RectF rawBoard = detectBoard(px, DW, dh, scale);
        smoothedBoard  = smoothRect(smoothedBoard, rawBoard);
        RectF board    = smoothedBoard != null ? smoothedBoard : fallbackBoardPx(W, H);

        // 2. Coins
        List<Coin> allCoins = detectCoins(px, DW, dh, board, scale);

        // 3. Striker = largest bright blob in bottom 38% of board
        float strikerLineY = board.top + board.height() * 0.62f;  // real px Y threshold
        Coin  striker      = null;
        float strikerScore = -1f;
        for (Coin c : allCoins) {
            if (c.color == Coin.COLOR_WHITE && c.pos.y >= strikerLineY) {
                float score = c.radius * (1f + (c.pos.y - strikerLineY) / board.height());
                if (score > strikerScore) { strikerScore = score; striker = c; }
            }
        }
        if (striker == null) {
            // Fallback: bottom-centre of board
            striker = new Coin(board.centerX(), board.bottom - board.height() * 0.06f,
                    board.width() * 0.028f, Coin.COLOR_STRIKER, true);
        } else {
            striker.isStriker = true;
            striker.color     = Coin.COLOR_STRIKER;
            allCoins.remove(striker);
        }

        // 4. Build state
        GameState s = new GameState();
        s.board   = board;
        s.striker = striker;
        s.coins   = allCoins;
        addPockets(s);
        return s;
    }

    // ── Board detection ───────────────────────────────────────────────────────
    //
    // Carrom Disc Pool's board has a bright orange/brown wooden frame.
    // Scan every 3rd pixel; track min/max X and Y of orange-border pixels.
    //
    private RectF detectBoard(int[] px, int w, int h, float scale) {
        int minX = w, maxX = 0, minY = h, maxY = 0;
        boolean found = false;

        for (int y = 0; y < h; y += 3) {
            for (int x = 0; x < w; x += 3) {
                if (isOrangeBorder(px[y * w + x])) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                    found = true;
                }
            }
        }

        if (!found || (maxX - minX) < w * 0.25f || (maxY - minY) < h * 0.25f) {
            return null;
        }

        // Shrink inward a bit to exclude the frame itself
        int inset = 4;
        return new RectF(
                (minX + inset) * scale,
                (minY + inset) * scale,
                (maxX - inset) * scale,
                (maxY - inset) * scale);
    }

    // ── Coin detection ────────────────────────────────────────────────────────
    //
    // 1. Grid-scan the board interior at STEP px intervals.
    // 2. Classify each grid point's colour.
    // 3. Cluster adjacent same-colour points.
    // 4. Each cluster → one Coin (center = centroid, radius = cluster half-size).
    // 5. Non-maximum suppression removes overlapping duplicates.
    //
    private List<Coin> detectCoins(int[] px, int w, int h, RectF board, float scale) {

        int bx1 = Math.max(0, (int)(board.left  / scale));
        int by1 = Math.max(0, (int)(board.top   / scale));
        int bx2 = Math.min(w - 1, (int)(board.right  / scale));
        int by2 = Math.min(h - 1, (int)(board.bottom / scale));

        int gw = (bx2 - bx1) / STEP + 1;
        int gh = (by2 - by1) / STEP + 1;
        if (gw <= 0 || gh <= 0) return new ArrayList<>();

        // Classify each grid cell
        int[] grid   = new int[gw * gh];   // color label
        int[] label  = new int[gw * gh];   // cluster label (0 = unlabelled)
        java.util.Arrays.fill(grid, Coin.COLOR_STRIKER); // re-use as "NONE"

        final int NONE = Coin.COLOR_STRIKER; // sentinel

        for (int gy = 0; gy < gh; gy++) {
            for (int gx = 0; gx < gw; gx++) {
                int px2 = bx1 + gx * STEP;
                int py2 = by1 + gy * STEP;
                int col = classifyPatch(px, w, h, px2, py2, 3);
                grid[gy * gw + gx] = col;
            }
        }

        // BFS clustering
        int nextLabel = 1;
        List<int[]> clusterColors   = new ArrayList<>(); // clusterColors.get(i) = color of cluster i+1
        List<List<int[]>> clusters  = new ArrayList<>();

        for (int gy = 0; gy < gh; gy++) {
            for (int gx = 0; gx < gw; gx++) {
                int idx = gy * gw + gx;
                if (label[idx] != 0 || grid[idx] == NONE) continue;
                int color = grid[idx];

                // BFS
                List<int[]> members = new ArrayList<>();
                java.util.Queue<int[]> q = new java.util.LinkedList<>();
                q.add(new int[]{gx, gy});
                label[idx] = nextLabel;

                while (!q.isEmpty()) {
                    int[] cur = q.poll();
                    members.add(cur);
                    int[] dx4 = {1,-1,0,0};
                    int[] dy4 = {0,0,1,-1};
                    for (int d = 0; d < 4; d++) {
                        int nx = cur[0] + dx4[d];
                        int ny = cur[1] + dy4[d];
                        if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue;
                        int ni = ny * gw + nx;
                        if (label[ni] != 0 || grid[ni] != color) continue;
                        label[ni] = nextLabel;
                        q.add(new int[]{nx, ny});
                    }
                }

                clusters.add(members);
                clusterColors.add(new int[]{color});
                nextLabel++;
            }
        }

        // Convert clusters → Coins
        int minCells = 1;  // minimum grid cells for a valid coin
        int maxCells = (int)(Math.PI * (maxRadiusFrac * DW / STEP) * (maxRadiusFrac * DW / STEP) * 1.5);

        List<Coin> coins = new ArrayList<>();
        for (int ci = 0; ci < clusters.size(); ci++) {
            List<int[]> members = clusters.get(ci);
            if (members.size() < minCells || members.size() > maxCells) continue;

            int color = clusterColors.get(ci)[0];

            // Centroid in grid coords
            float sumX = 0, sumY = 0;
            for (int[] m : members) { sumX += m[0]; sumY += m[1]; }
            float cgx = sumX / members.size();
            float cgy = sumY / members.size();

            // Radius from cluster size
            float area   = members.size() * STEP * STEP;
            float radius = (float) Math.sqrt(area / Math.PI);

            float minR = DW * minRadiusFrac;
            float maxR = DW * maxRadiusFrac;
            if (radius < minR * 0.4f || radius > maxR * 1.6f) continue;
            radius = Math.max(minR * 0.6f, Math.min(radius, maxR * 1.2f));

            // Convert back to screen pixels
            float screenX = (bx1 + cgx * STEP) * scale;
            float screenY = (by1 + cgy * STEP) * scale;
            float screenR = radius * scale;

            coins.add(new Coin(screenX, screenY, screenR, color, false));
        }

        // NMS: remove overlapping coins of same color
        suppressDuplicates(coins);

        return coins;
    }

    // ── Patch color classifier ────────────────────────────────────────────────
    //
    // Samples a square patch and returns the dominant coin color, or NONE.
    //
    private int classifyPatch(int[] px, int w, int h, int cx, int cy, int radius) {
        int wCount = 0, bCount = 0, rCount = 0, total = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int xx = cx + dx, yy = cy + dy;
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                int p = px[yy * w + xx];
                total++;
                if (isWhiteCoin(p)) wCount++;
                else if (isBlackCoin(p)) bCount++;
                else if (isRedCoin(p)) rCount++;
            }
        }
        if (total == 0) return Coin.COLOR_STRIKER; // NONE
        float threshold = total * 0.30f;
        if (wCount > threshold && wCount >= bCount && wCount >= rCount) return Coin.COLOR_WHITE;
        if (bCount > threshold && bCount >= wCount && bCount >= rCount) return Coin.COLOR_BLACK;
        if (rCount > threshold * 0.5f)                                  return Coin.COLOR_RED;
        return Coin.COLOR_STRIKER; // NONE
    }

    // ── NMS ───────────────────────────────────────────────────────────────────

    private void suppressDuplicates(List<Coin> coins) {
        boolean[] keep = new boolean[coins.size()];
        java.util.Arrays.fill(keep, true);
        for (int i = 0; i < coins.size(); i++) {
            if (!keep[i]) continue;
            Coin a = coins.get(i);
            for (int j = i + 1; j < coins.size(); j++) {
                if (!keep[j]) continue;
                Coin b = coins.get(j);
                float dx = a.pos.x - b.pos.x, dy = a.pos.y - b.pos.y;
                float dist = (float) Math.sqrt(dx*dx + dy*dy);
                if (dist < (a.radius + b.radius) * 0.65f) {
                    // Discard the smaller
                    if (a.radius >= b.radius) keep[j] = false;
                    else { keep[i] = false; break; }
                }
            }
        }
        java.util.Iterator<Coin> it = coins.iterator();
        int idx = 0;
        while (it.hasNext()) { it.next(); if (!keep[idx++]) it.remove(); }
    }

    // ── Color predicates ──────────────────────────────────────────────────────

    private static int r(int p) { return (p >> 16) & 0xFF; }
    private static int g(int p) { return (p >>  8) & 0xFF; }
    private static int b(int p) { return  p        & 0xFF; }

    /** Orange/brown wooden frame of the Carrom Disc Pool board. */
    private static boolean isOrangeBorder(int p) {
        int r = r(p), g = g(p), b = b(p);
        return r > 160 && g > 55 && g < 150 && b < 80 && r > g + 35 && r > b + 90;
    }

    /** White/cream coins (also matches striker before role assignment). */
    private static boolean isWhiteCoin(int p) {
        int r = r(p), g = g(p), b = b(p);
        int mn = Math.min(r, Math.min(g, b));
        int mx = Math.max(r, Math.max(g, b));
        return mn > 145 && mx - mn < 60;
    }

    /** Black/dark coins. */
    private static boolean isBlackCoin(int p) {
        int r = r(p), g = g(p), b = b(p);
        return r < 90 && g < 90 && b < 90 && Math.max(r, Math.max(g,b)) - Math.min(r,Math.min(g,b)) < 45;
    }

    /** Red queen. */
    private static boolean isRedCoin(int p) {
        int r = r(p), g = g(p), b = b(p);
        return r > 130 && r > (int)(g * 2.0f) && r > (int)(b * 1.8f) && g < 110;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void addPockets(GameState s) {
        if (s.board == null) return;
        float i = s.board.width() * 0.025f;
        s.pockets.add(new PointF(s.board.left  + i, s.board.top    + i));
        s.pockets.add(new PointF(s.board.right - i, s.board.top    + i));
        s.pockets.add(new PointF(s.board.left  + i, s.board.bottom - i));
        s.pockets.add(new PointF(s.board.right - i, s.board.bottom - i));
    }

    private RectF smoothRect(RectF prev, RectF next) {
        if (prev == null) return next;
        if (next == null) return prev;
        return new RectF(
                prev.left   + EMA * (next.left   - prev.left),
                prev.top    + EMA * (next.top    - prev.top),
                prev.right  + EMA * (next.right  - prev.right),
                prev.bottom + EMA * (next.bottom - prev.bottom));
    }

    private RectF fallbackBoardPx(int w, int h) {
        float side = w * 0.80f;
        float cx = w / 2f, cy = h * 0.46f;
        return new RectF(cx - side/2f, cy - side/2f, cx + side/2f, cy + side/2f);
    }

    private GameState fallbackState(int w, int h) {
        GameState s   = new GameState();
        RectF board   = fallbackBoardPx(w, h);
        s.board       = board;
        float side    = board.width();
        float cx      = board.centerX(), cy = board.centerY();
        float r       = side * 0.023f;
        s.striker     = new Coin(cx, board.bottom - side * 0.07f, r * 1.1f, Coin.COLOR_STRIKER, true);
        s.coins.add(new Coin(cx,               cy,              r, Coin.COLOR_RED,   false));
        s.coins.add(new Coin(cx - side*0.10f,  cy - side*0.06f, r, Coin.COLOR_WHITE, false));
        s.coins.add(new Coin(cx + side*0.10f,  cy - side*0.06f, r, Coin.COLOR_WHITE, false));
        s.coins.add(new Coin(cx - side*0.07f,  cy + side*0.07f, r, Coin.COLOR_BLACK, false));
        s.coins.add(new Coin(cx + side*0.07f,  cy + side*0.07f, r, Coin.COLOR_BLACK, false));
        addPockets(s);
        return s;
    }
}
