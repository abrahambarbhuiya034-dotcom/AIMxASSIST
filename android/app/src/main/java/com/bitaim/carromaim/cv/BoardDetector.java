package com.bitaim.carromaim.cv;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * BoardDetector v9 — Robust multi-method board detection.
 *
 * Real board measurements used for all ratios:
 *   Full board:         74.0 cm × 74.0 cm
 *   Playing surface:    71.1 cm × 71.1 cm
 *   Frame width:         3.8 cm each side
 *   Pocket diameter:     4.45 cm  → pocket center at 4.45/74 ≈ 6.0 % from corner
 *   Coin diameter:       3.18 cm  → 3.18/74 ≈ 4.3 % of board
 *   Striker diameter:    4.13 cm  → 4.13/74 ≈ 5.6 % of board
 *   Striker baseline:   11.1 cm from bottom inner edge
 *                       = (11.1 + 3.8) / 74 ≈ 20.1 % from bottom outer edge
 *                       = 79.9 % from top  → stored as 0.800f
 *   Striker zone:       23 cm wide, centred on baseline
 *
 * Detection pipeline (tries each in order):
 *   1. Orange/warm-wood border scan   — catches most rendered boards
 *   2. Dark-pocket corner scan         — catches high-contrast game styles
 *   3. Proportion-based smart fallback — always succeeds; uses 88 % of the
 *                                        shortest screen dimension, top-shifted
 *                                        slightly for game-UI bars.
 */
public class BoardDetector {

    private static final String TAG       = "BoardDetector";
    private static final int    PROC_W    = 360;
    /**
     * EMA weight for new observation.
     * Lower = smoother but slower to follow real board movement.
     * 0.10 gives ~10-frame settling time (~330 ms at 30 fps) — smooth enough
     * to absorb per-frame detection jitter.
     */
    private static final float  EMA_A     = 0.10f;
    private static final int    SCAN_STEP = 3;

    // ── Real-board proportions (outer 74 cm reference) ────────────────────────
    /** Pocket centre distance from board corner, as fraction of board width. */
    private static final float POCKET_INSET    = 0.060f;  // 4.45/74
    /** Striker default Y from board top, as fraction of board height. */
    private static final float STRIKER_Y_FRAC  = 0.800f;  // (74-14.9)/74
    /** Half-width of striker movement zone, fraction of board. */
    private static final float STRIKER_HZ_FRAC = 0.155f;  // (23/2)/74
    /** Coin radius: min/max as fraction of board width. */
    private static final float COIN_R_MIN      = 0.020f;  // ~1.5 cm / 74
    private static final float COIN_R_MAX      = 0.040f;  // ~3.0 cm / 74

    private RectF smoothedBoard = null;
    private int[] pixelBuf      = null;

    public void setMinRadiusFrac(float v) {}
    public void setMaxRadiusFrac(float v) {}
    public void setParam2(double v)       {}

    public synchronized GameState detect(Bitmap src) {
        if (src == null) return null;
        try {
            return run(src);
        } catch (Throwable t) {
            Log.e(TAG, "detect error: " + t.getMessage());
            return fallbackState(src.getWidth(), src.getHeight());
        }
    }

    // ── Main pipeline ─────────────────────────────────────────────────────────

    private GameState run(Bitmap src) {
        int srcW = src.getWidth(), srcH = src.getHeight();
        if (srcW == 0 || srcH == 0) return null;

        float scale = Math.min(1f, (float) PROC_W / srcW);
        int   pW    = Math.round(srcW * scale);
        int   pH    = Math.round(srcH * scale);

        Bitmap bmp = (scale < 0.99f)
            ? Bitmap.createScaledBitmap(src, pW, pH, false) : src;

        int total = pW * pH;
        if (pixelBuf == null || pixelBuf.length < total) pixelBuf = new int[total];
        bmp.getPixels(pixelBuf, 0, pW, 0, 0, pW, pH);
        if (bmp != src) bmp.recycle();

        // Multi-method board detection
        RectF rawBoard = detectByBorderColor(pixelBuf, pW, pH);
        if (rawBoard == null) rawBoard = detectByPocketCorners(pixelBuf, pW, pH);
        if (rawBoard == null) rawBoard = smartFallback(pW, pH);

        smoothedBoard = smoothRect(smoothedBoard, rawBoard);
        RectF pb = smoothedBoard;

        float minR = pb.width() * COIN_R_MIN;
        float maxR = pb.width() * COIN_R_MAX;
        List<Coin> coins = detectCoins(pixelBuf, pW, pH, pb, minR, maxR);

        float inv = 1f / scale;
        RectF srcBoard = scaleRect(pb, inv);

        List<Coin> scaled = new ArrayList<>(coins.size());
        for (Coin c : coins)
            scaled.add(new Coin(c.pos.x * inv, c.pos.y * inv,
                                c.radius * inv, c.color, false));

        // Striker = largest white blob in bottom 38 % of board
        float strikerThreshY = (pb.top + pb.height() * 0.62f) * inv;
        Coin striker = null;
        for (Coin c : scaled) {
            if (c.color != Coin.COLOR_WHITE) continue;
            if (c.pos.y < strikerThreshY) continue;
            if (striker == null || c.radius > striker.radius) striker = c;
        }

        GameState s = new GameState();
        s.board = srcBoard;

        if (striker != null) {
            striker.isStriker = true;
            striker.color     = Coin.COLOR_STRIKER;
            s.striker         = striker;
        } else {
            // Fallback striker position: baseline per real measurements
            float defX = srcBoard.centerX();
            float defY = srcBoard.top + srcBoard.height() * STRIKER_Y_FRAC;
            float defR = srcBoard.width() * 0.028f;
            s.striker = new Coin(defX, defY, defR, Coin.COLOR_STRIKER, true);
        }

        for (Coin c : scaled) if (c != striker) s.coins.add(c);
        addPockets(s);
        return s;
    }

    // ── Method 1: Border colour scan ──────────────────────────────────────────

    private RectF detectByBorderColor(int[] px, int w, int h) {
        int minX = w, maxX = 0, minY = h, maxY = 0, cnt = 0;
        for (int y = 0; y < h; y += 4) {
            for (int x = 0; x < w; x += 4) {
                if (isBoardBorder(px[y * w + x])) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                    cnt++;
                }
            }
        }
        float minSpan = w * 0.20f;
        if (cnt < 10 || (maxX - minX) < minSpan || (maxY - minY) < minSpan) return null;

        float side = Math.max(maxX - minX, maxY - minY);
        float cx = (minX + maxX) / 2f, cy = (minY + maxY) / 2f;
        return new RectF(Math.max(0, cx - side/2f), Math.max(0, cy - side/2f),
                         Math.min(w, cx + side/2f), Math.min(h, cy + side/2f));
    }

    /**
     * Detects warm-wood tones including:
     *  - Classic orange wood (most apps)
     *  - Lighter natural wood
     *  - Darker reddish/brown wood
     *  - Navy/dark blue game-style borders
     */
    private boolean isBoardBorder(int p) {
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;

        // Orange / bright warm wood
        if (r > 130 && g > 50  && g < 180 && b < 110
                && r > g && g > b && (r - b) > 60) return true;

        // Darker brown / mahogany
        if (r > 90  && r < 180 && g > 40  && g < 120 && b < 80
                && r > g && g > b && (r - b) > 40) return true;

        // Light tan / pale wood
        if (r > 160 && g > 120 && b > 60  && b < 140
                && r > b && g > b && (r - b) > 30 && (g - b) > 20) return true;

        // High-saturation game board green felt (some apps render the border green)
        if (g > 100 && g > r * 1.15f && g > b * 1.15f
                && r < 160 && b < 160) return true;

        return false;
    }

    // ── Method 2: Dark pocket corners ─────────────────────────────────────────

    /**
     * Looks for very dark pixel clusters in the four corner regions.
     * Carrom pockets are always jet-black/very-dark at the 4 corners.
     * If we find 4 dark blobs that form a square, that square IS the board.
     */
    private RectF detectByPocketCorners(int[] px, int w, int h) {
        // Search within the inner 20%–80% of each axis (avoid phone bezels)
        int margin = (int)(w * 0.08f);
        int searchR = (int)(w * 0.28f); // max radius from each corner to look

        PointF tl = findDarkBlob(px, w, h, margin,       margin,       searchR);
        PointF tr = findDarkBlob(px, w, h, w-margin-1,   margin,       searchR);
        PointF bl = findDarkBlob(px, w, h, margin,       h-margin-1,   searchR);
        PointF br = findDarkBlob(px, w, h, w-margin-1,   h-margin-1,   searchR);

        if (tl == null || tr == null || bl == null || br == null) return null;

        // Verify they form an approximate square
        float wSpan = ((tr.x - tl.x) + (br.x - bl.x)) * 0.5f;
        float hSpan = ((bl.y - tl.y) + (br.y - tr.y)) * 0.5f;
        float aspect = Math.max(wSpan, hSpan) / Math.max(1, Math.min(wSpan, hSpan));
        if (aspect > 1.30f) return null; // not square enough

        float cx = (tl.x + tr.x + bl.x + br.x) / 4f;
        float cy = (tl.y + tr.y + bl.y + br.y) / 4f;
        float side = (wSpan + hSpan) * 0.5f;

        return new RectF(cx - side/2f, cy - side/2f, cx + side/2f, cy + side/2f);
    }

    /**
     * Find the centroid of dark pixels near (startX, startY) within radius r.
     */
    private PointF findDarkBlob(int[] px, int w, int h,
                                 int startX, int startY, int radius) {
        int dx = (startX < w/2) ? 1 : -1;
        int dy = (startY < h/2) ? 1 : -1;

        long sumX = 0, sumY = 0, cnt = 0;
        int x0 = Math.max(0, startX - radius), x1 = Math.min(w-1, startX + radius);
        int y0 = Math.max(0, startY - radius), y1 = Math.min(h-1, startY + radius);

        for (int y = y0; y <= y1; y += 3) {
            for (int x = x0; x <= x1; x += 3) {
                int c = px[y * w + x];
                int lum = ((c>>16)&0xFF) + ((c>>8)&0xFF) + (c&0xFF);
                if (lum < 120) { // very dark pixel
                    sumX += x; sumY += y; cnt++;
                }
            }
        }
        if (cnt < 4) return null;
        return new PointF((float)sumX / cnt, (float)sumY / cnt);
    }

    // ── Method 3: Smart fallback ───────────────────────────────────────────────

    /**
     * Proportion-based fallback using the knowledge that:
     *  - In carrom game apps the board fills most of the screen width.
     *  - Game UI bars consume roughly the top 8 % and bottom 5 % of screen.
     *  - The board is always square.
     *
     * Uses 88 % of the usable height (shorter axis after deducting UI bars)
     * capped at 92 % of screen width.
     */
    private RectF smartFallback(int w, int h) {
        int uiTopPx    = (int)(h * 0.08f);  // skip top ~8 %
        int uiBottomPx = (int)(h * 0.05f);  // skip bottom ~5 %
        int usableH    = h - uiTopPx - uiBottomPx;

        float side = Math.min(w * 0.92f, usableH * 0.90f);
        float cx   = w / 2f;
        float cy   = uiTopPx + usableH * 0.50f;   // vertically centred in usable area

        return new RectF(cx - side/2f, cy - side/2f, cx + side/2f, cy + side/2f);
    }

    // ── Coin / pixel detection ────────────────────────────────────────────────

    private List<Coin> detectCoins(int[] px, int w, int h,
                                   RectF board, float minR, float maxR) {
        // Shrink scan area by 5 % on each side to avoid border pixels
        int bL = Math.max(0, (int)(board.left   + board.width()  * 0.05f));
        int bR = Math.min(w, (int)(board.right  - board.width()  * 0.05f));
        int bT = Math.max(0, (int)(board.top    + board.height() * 0.05f));
        int bB = Math.min(h, (int)(board.bottom - board.height() * 0.05f));

        List<float[]> whites = new ArrayList<>(), blacks = new ArrayList<>(),
                      reds   = new ArrayList<>();

        for (int y = bT; y < bB; y += SCAN_STEP) {
            for (int x = bL; x < bR; x += SCAN_STEP) {
                switch (classifyPx(px[y * w + x])) {
                    case Coin.COLOR_WHITE: whites.add(new float[]{x, y}); break;
                    case Coin.COLOR_BLACK: blacks.add(new float[]{x, y}); break;
                    case Coin.COLOR_RED:   reds  .add(new float[]{x, y}); break;
                    default: break;
                }
            }
        }

        List<Coin> out = new ArrayList<>();
        cluster(whites, Coin.COLOR_WHITE, maxR * 1.5f, minR, maxR,      out);
        cluster(blacks, Coin.COLOR_BLACK, maxR * 1.5f, minR, maxR,      out);
        cluster(reds,   Coin.COLOR_RED,   maxR * 1.2f, minR * 0.4f,
                        maxR * 0.80f, out);
        nms(out);
        return out;
    }

    private int classifyPx(int p) {
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
        int lum = (r + g + b) / 3;

        // White coin: bright, balanced RGB
        if (lum > 160 && r > 140 && g > 140 && b > 140
                && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 60)
            return Coin.COLOR_WHITE;

        // Black coin: very dark
        if (lum < 65 && r < 80 && g < 80 && b < 80)
            return Coin.COLOR_BLACK;

        // Red queen: dominant red channel
        if (r > 130 && g < 80 && b < 90 && r > g * 1.8f && r > b * 1.8f)
            return Coin.COLOR_RED;

        return -1;
    }

    private void cluster(List<float[]> pts, int color, float mergeR,
                         float minR, float maxR, List<Coin> out) {
        if (pts.isEmpty()) return;
        List<float[]> cl = new ArrayList<>();
        for (float[] pt : pts) {
            float best = mergeR; int bi = -1;
            for (int i = 0; i < cl.size(); i++) {
                float[] c = cl.get(i);
                float dx = pt[0]-c[0], dy = pt[1]-c[1];
                float d = (float) Math.sqrt(dx*dx + dy*dy);
                if (d < best) { best = d; bi = i; }
            }
            if (bi >= 0) {
                float[] c = cl.get(bi); float n = c[2];
                c[0] = (c[0]*n + pt[0])/(n+1);
                c[1] = (c[1]*n + pt[1])/(n+1);
                c[2] = n + 1;
            } else {
                cl.add(new float[]{pt[0], pt[1], 1});
            }
        }
        int minHits = Math.max(2, (int)(Math.PI*minR*minR/(SCAN_STEP*SCAN_STEP)*0.15f));
        for (float[] c : cl) {
            if (c[2] < minHits) continue;
            float estR = (float) Math.sqrt(c[2] * SCAN_STEP * SCAN_STEP / Math.PI);
            out.add(new Coin(c[0], c[1], Math.max(minR, Math.min(maxR, estR)), color, false));
        }
    }

    private void nms(List<Coin> coins) {
        boolean[] keep = new boolean[coins.size()];
        java.util.Arrays.fill(keep, true);
        for (int i = 0; i < coins.size(); i++) {
            if (!keep[i]) continue;
            Coin a = coins.get(i);
            for (int j = i+1; j < coins.size(); j++) {
                if (!keep[j]) continue;
                Coin b = coins.get(j);
                float dx = a.pos.x-b.pos.x, dy = a.pos.y-b.pos.y;
                float d  = (float) Math.sqrt(dx*dx+dy*dy);
                if (d < (a.radius+b.radius)*0.60f) {
                    if (a.radius >= b.radius) keep[j] = false;
                    else { keep[i] = false; break; }
                }
            }
        }
        Iterator<Coin> it = coins.iterator(); int idx = 0;
        while (it.hasNext()) { it.next(); if (!keep[idx++]) it.remove(); }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private GameState fallbackState(int w, int h) {
        GameState s = new GameState();
        int uiTop = (int)(h * 0.08f);
        float side = Math.min(w * 0.92f, (h - uiTop) * 0.90f);
        float cx = w/2f, cy = uiTop + (h - uiTop) * 0.50f;
        s.board   = new RectF(cx-side/2f, cy-side/2f, cx+side/2f, cy+side/2f);
        float defY = s.board.top + s.board.height() * STRIKER_Y_FRAC;
        s.striker = new Coin(cx, defY, side * 0.028f, Coin.COLOR_STRIKER, true);
        float r = side * 0.022f;
        s.coins.add(new Coin(cx,              cy - side*0.10f, r, Coin.COLOR_WHITE, false));
        s.coins.add(new Coin(cx-side*0.12f,   cy,              r, Coin.COLOR_BLACK, false));
        s.coins.add(new Coin(cx+side*0.12f,   cy,              r, Coin.COLOR_BLACK, false));
        s.coins.add(new Coin(cx,              cy,              r, Coin.COLOR_RED,   false));
        addPockets(s);
        return s;
    }

    /**
     * Pocket positions per official rules:
     *   Centre of hole = 4.45 cm from each corner edge.
     *   4.45 / 74.0 ≈ 6.0 % of full board width (POCKET_INSET).
     */
    private void addPockets(GameState s) {
        if (s.board == null) return;
        float i = s.board.width() * POCKET_INSET;
        s.pockets.add(new PointF(s.board.left  + i, s.board.top    + i));
        s.pockets.add(new PointF(s.board.right - i, s.board.top    + i));
        s.pockets.add(new PointF(s.board.left  + i, s.board.bottom - i));
        s.pockets.add(new PointF(s.board.right - i, s.board.bottom - i));
    }

    private RectF scaleRect(RectF r, float s) {
        return new RectF(r.left*s, r.top*s, r.right*s, r.bottom*s);
    }

    private RectF smoothRect(RectF p, RectF n) {
        if (p == null) return n; if (n == null) return p;
        return new RectF(
            p.left   + EMA_A*(n.left   - p.left),
            p.top    + EMA_A*(n.top    - p.top),
            p.right  + EMA_A*(n.right  - p.right),
            p.bottom + EMA_A*(n.bottom - p.bottom));
    }
}
