package com.bitaim.carromaim.cv;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * BoardDetector v10 — Low-End Optimised Build
 *
 * Low-end optimizations added:
 *  – PROC_W is chosen at runtime: 240 px on devices with < 1.5 GB RAM,
 *    360 px otherwise. Smaller proc resolution cuts memory ~2.25× and
 *    speeds up the scan ~55%.
 *  – SCAN_STEP raised from 3 → 4 on low-end, reducing pixel visits ~44%.
 *  – EMA_A raised slightly on low-end so fewer frames needed to track.
 *
 * Detection pipeline (unchanged):
 *  1. detectByOrangeDensity()   — primary
 *  2. detectByPocketQuadrants() — secondary
 *  3. detectByWoodSurface()     — tertiary
 *  4. smartFallback()           — guaranteed
 */
public class BoardDetector {

    private static final String TAG = "BoardDetector";

    private static final float POCKET_INSET   = 0.060f;
    private static final float STRIKER_Y_FRAC = 0.800f;
    private static final float COIN_R_MIN     = 0.018f;
    private static final float COIN_R_MAX     = 0.035f;

    private static final int PX_BOARD_BORDER = 10;
    private static final int PX_BLUE         = 11;

    private final int  procW;
    private final float emaA;
    private final int  scanStep;

    private RectF smoothedBoard = null;
    private int[] pixelBuf      = null;

    public BoardDetector() { this(null); }

    public BoardDetector(Context ctx) {
        boolean lowEnd = isLowEndDevice(ctx);
        procW    = lowEnd ? 240 : 360;
        emaA     = lowEnd ? 0.14f : 0.10f;
        scanStep = lowEnd ? 4 : 3;
        Log.i(TAG, "BoardDetector init: procW=" + procW + " lowEnd=" + lowEnd);
    }

    private static boolean isLowEndDevice(Context ctx) {
        if (ctx == null) return false;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem < 1536L * 1024 * 1024;
        } catch (Throwable t) { return false; }
    }

    public void setMinRadiusFrac(float v) {}
    public void setMaxRadiusFrac(float v) {}
    public void setParam2(double v)       {}

    public synchronized GameState detect(Bitmap src) {
        if (src == null) return null;
        try { return run(src); }
        catch (Throwable t) {
            Log.e(TAG, "detect error: " + t.getMessage());
            return fallbackState(src.getWidth(), src.getHeight());
        }
    }

    private GameState run(Bitmap src) {
        int srcW = src.getWidth(), srcH = src.getHeight();
        if (srcW == 0 || srcH == 0) return null;

        float scale = Math.min(1f, (float) procW / srcW);
        int   pW    = Math.round(srcW * scale);
        int   pH    = Math.round(srcH * scale);

        Bitmap bmp = (scale < 0.99f)
                ? Bitmap.createScaledBitmap(src, pW, pH, false) : src;

        int total = pW * pH;
        if (pixelBuf == null || pixelBuf.length < total) pixelBuf = new int[total];
        bmp.getPixels(pixelBuf, 0, pW, 0, 0, pW, pH);
        if (bmp != src) bmp.recycle();

        RectF rawBoard = detectByOrangeDensity(pixelBuf, pW, pH);
        if (rawBoard == null) rawBoard = detectByPocketQuadrants(pixelBuf, pW, pH);
        if (rawBoard == null) rawBoard = detectByWoodSurface(pixelBuf, pW, pH);
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

        float strikerThreshY = (pb.top + pb.height() * 0.62f) * inv;
        float boardCX        = srcBoard.centerX();
        float strikerZoneW   = srcBoard.width() * 0.55f;

        Coin striker = null;
        for (Coin c : scaled) {
            if (c.color != PX_BLUE) continue;
            if (c.pos.y < strikerThreshY) continue;
            if (striker == null || c.radius > striker.radius) striker = c;
        }
        if (striker == null) {
            for (Coin c : scaled) {
                if (c.color != Coin.COLOR_WHITE) continue;
                if (c.pos.y < strikerThreshY) continue;
                if (Math.abs(c.pos.x - boardCX) > strikerZoneW) continue;
                if (striker == null || c.radius > striker.radius) striker = c;
            }
        }
        if (striker != null) { striker.isStriker = true; striker.color = Coin.COLOR_STRIKER; }

        GameState s = new GameState();
        s.board = srcBoard;

        if (striker != null) {
            s.striker = striker;
        } else {
            float defX = srcBoard.centerX();
            float defY = srcBoard.top + srcBoard.height() * STRIKER_Y_FRAC;
            float defR = srcBoard.width() * 0.028f;
            s.striker  = new Coin(defX, defY, defR, Coin.COLOR_STRIKER, true);
        }

        for (Coin c : scaled) {
            if (c == striker) continue;
            if (c.color == PX_BLUE) continue;
            s.coins.add(c);
        }

        addPockets(s);
        return s;
    }

    private RectF detectByOrangeDensity(int[] px, int w, int h) {
        int densityThresh = Math.max(3, w / 30);
        int topRow = -1, bottomRow = -1;
        for (int y = 0; y < h; y += scanStep) {
            int cnt = 0;
            for (int x = 0; x < w; x += scanStep)
                if (isOrangeBorder(px[y * w + x])) cnt++;
            if (cnt >= densityThresh) {
                if (topRow < 0) topRow = y;
                bottomRow = y;
            }
        }
        if (topRow < 0 || (bottomRow - topRow) < h * 0.10f) return null;
        int leftCol = -1, rightCol = -1;
        for (int x = 0; x < w; x += scanStep) {
            int cnt = 0;
            for (int y = topRow; y <= bottomRow; y += scanStep)
                if (isOrangeBorder(px[y * w + x])) cnt++;
            if (cnt >= densityThresh) {
                if (leftCol < 0) leftCol = x;
                rightCol = x;
            }
        }
        if (leftCol < 0 || (rightCol - leftCol) < w * 0.15f) return null;
        float boardW = rightCol - leftCol, boardH = bottomRow - topRow;
        float aspect = Math.max(boardW, boardH) / Math.max(1, Math.min(boardW, boardH));
        if (aspect > 1.60f) return null;
        float side = Math.min(boardW, boardH);
        float cx = (leftCol + rightCol) / 2f, cy = (topRow + bottomRow) / 2f;
        return new RectF(Math.max(0, cx-side/2f), Math.max(0, cy-side/2f),
                Math.min(w, cx+side/2f), Math.min(h, cy+side/2f));
    }

    private boolean isOrangeBorder(int p) {
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
        if (r > 150 && g > 60 && g < 170 && b < 75 && r > g * 1.3f && g > b + 20 && (r-b) > 100) return true;
        if (r > 110 && r < 200 && g > 55 && g < 130 && b < 70 && r > g * 1.2f && (r-b) > 65) return true;
        return false;
    }

    private RectF detectByPocketQuadrants(int[] px, int w, int h) {
        int mX = w/2, mY = h/2;
        PointF tl = darkBlobInRegion(px, w, 0, 0, mX, mY);
        PointF tr = darkBlobInRegion(px, w, mX, 0, w, mY);
        PointF bl = darkBlobInRegion(px, w, 0, mY, mX, h);
        PointF br = darkBlobInRegion(px, w, mX, mY, w, h);
        if (tl == null || tr == null || bl == null || br == null) return null;
        float spanW = ((tr.x-tl.x)+(br.x-bl.x))*0.5f;
        float spanH = ((bl.y-tl.y)+(br.y-tr.y))*0.5f;
        float aspect = Math.max(spanW, spanH) / Math.max(1, Math.min(spanW, spanH));
        if (aspect > 1.40f) return null;
        float side = Math.min(spanW, spanH);
        float cx = (tl.x+tr.x+bl.x+br.x)/4f, cy = (tl.y+tr.y+bl.y+br.y)/4f;
        return new RectF(cx-side/2f, cy-side/2f, cx+side/2f, cy+side/2f);
    }

    private PointF darkBlobInRegion(int[] px, int w, int x0, int y0, int x1, int y1) {
        long sumX=0, sumY=0, cnt=0;
        int mx=(x0+x1)/2, my=(y0+y1)/2;
        long[] qSumX=new long[4], qSumY=new long[4], qCnt=new long[4];
        for (int y=y0; y<y1; y+=scanStep) {
            for (int x=x0; x<x1; x+=scanStep) {
                if (x<0||x>=w||y<0) continue;
                int c = px[y*w+x];
                int lum = ((c>>16)&0xFF)+((c>>8)&0xFF)+(c&0xFF);
                if (lum < 100) {
                    sumX+=x; sumY+=y; cnt++;
                    int qi=(x<mx?0:1)+(y<my?0:2);
                    qSumX[qi]+=x; qSumY[qi]+=y; qCnt[qi]++;
                }
            }
        }
        if (cnt < 5) return null;
        int best=0;
        for (int q=1; q<4; q++) if (qCnt[q]>qCnt[best]) best=q;
        if (qCnt[best] >= cnt/2)
            return new PointF((float)qSumX[best]/qCnt[best], (float)qSumY[best]/qCnt[best]);
        return new PointF((float)sumX/cnt, (float)sumY/cnt);
    }

    private RectF detectByWoodSurface(int[] px, int w, int h) {
        int margin = (int)(w * 0.05f);
        int minX=w, maxX=0, minY=h, maxY=0, cnt=0;
        for (int y=margin; y<h-margin; y+=scanStep) {
            for (int x=margin; x<w-margin; x+=scanStep) {
                if (isWoodSurface(px[y*w+x])) {
                    if (x<minX) minX=x; if (x>maxX) maxX=x;
                    if (y<minY) minY=y; if (y>maxY) maxY=y;
                    cnt++;
                }
            }
        }
        float minSpan = w * 0.25f;
        if (cnt < 20 || (maxX-minX) < minSpan || (maxY-minY) < minSpan) return null;
        float side = Math.min(maxX-minX, maxY-minY) * (1f + 2*0.08f);
        float cx = (minX+maxX)/2f, cy = (minY+maxY)/2f;
        return new RectF(Math.max(0,cx-side/2f), Math.max(0,cy-side/2f),
                Math.min(w,cx+side/2f), Math.min(h,cy+side/2f));
    }

    private boolean isWoodSurface(int p) {
        int r=(p>>16)&0xFF, g=(p>>8)&0xFF, b=p&0xFF;
        int lum=(r+g+b)/3;
        if (lum<110||lum>230) return false;
        if (r<=g||g<=b) return false;
        if ((r-b)>130) return false;
        if ((r-b)<30) return false;
        if (r<150||r>235||g<110||g>195||b<60||b>155) return false;
        return true;
    }

    private RectF smartFallback(int w, int h) {
        float side = w * 0.94f;
        float cx = w/2f;
        float uiTop = h*0.14f, uiBot = h*0.10f;
        float cy = uiTop + (h-uiTop-uiBot)*0.50f;
        side = Math.min(side, (h-uiTop-uiBot)*0.96f);
        return new RectF(cx-side/2f, cy-side/2f, cx+side/2f, cy+side/2f);
    }

    private List<Coin> detectCoins(int[] px, int w, int h, RectF board, float minR, float maxR) {
        int bL=Math.max(0,(int)(board.left  +board.width() *0.04f));
        int bR=Math.min(w,(int)(board.right -board.width() *0.04f));
        int bT=Math.max(0,(int)(board.top   +board.height()*0.04f));
        int bB=Math.min(h,(int)(board.bottom-board.height()*0.04f));

        List<float[]> whites=new ArrayList<>(), blacks=new ArrayList<>(),
                reds=new ArrayList<>(), blues=new ArrayList<>();

        for (int y=bT; y<bB; y+=scanStep) {
            for (int x=bL; x<bR; x+=scanStep) {
                switch (classifyPx(px[y*w+x])) {
                    case Coin.COLOR_WHITE: whites.add(new float[]{x,y}); break;
                    case Coin.COLOR_BLACK: blacks.add(new float[]{x,y}); break;
                    case Coin.COLOR_RED:   reds  .add(new float[]{x,y}); break;
                    case PX_BLUE:          blues .add(new float[]{x,y}); break;
                    default: break;
                }
            }
        }
        List<Coin> out = new ArrayList<>();
        cluster(whites, Coin.COLOR_WHITE, maxR*1.6f, minR, maxR,       out);
        cluster(blacks, Coin.COLOR_BLACK, maxR*1.6f, minR, maxR,       out);
        cluster(reds,   Coin.COLOR_RED,   maxR*1.2f, minR*0.4f, maxR*0.75f, out);
        cluster(blues,  PX_BLUE,          maxR*1.8f, minR, maxR*1.5f, out);
        nms(out);
        return out;
    }

    private int classifyPx(int p) {
        int r=(p>>16)&0xFF, g=(p>>8)&0xFF, b=p&0xFF;
        int lum=(r+g+b)/3;
        if (lum>150&&r>130&&g>130&&b>110&&(Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b)))<70) return Coin.COLOR_WHITE;
        if (lum<70&&r<85&&g<85&&b<85) return Coin.COLOR_BLACK;
        if (r>120&&g<85&&b<95&&r>g*1.7f&&r>b*1.5f) return Coin.COLOR_RED;
        if (b>r+20&&b>g&&b>90&&lum>70&&lum<200&&r<160&&g<170) return PX_BLUE;
        return -1;
    }

    private void cluster(List<float[]> pts, int color, float mergeR,
                         float minR, float maxR, List<Coin> out) {
        if (pts.isEmpty()) return;
        List<float[]> cl = new ArrayList<>();
        for (float[] pt : pts) {
            float bestD=mergeR; int bi=-1;
            for (int i=0; i<cl.size(); i++) {
                float[] c=cl.get(i);
                float dx=pt[0]-c[0], dy=pt[1]-c[1];
                float d=(float)Math.sqrt(dx*dx+dy*dy);
                if (d<bestD) { bestD=d; bi=i; }
            }
            if (bi>=0) {
                float[] c=cl.get(bi); float n=c[2];
                c[0]=(c[0]*n+pt[0])/(n+1);
                c[1]=(c[1]*n+pt[1])/(n+1);
                c[2]=n+1;
            } else cl.add(new float[]{pt[0],pt[1],1});
        }
        int minHits=Math.max(2,(int)(Math.PI*minR*minR/(scanStep*scanStep)*0.12f));
        for (float[] c : cl) {
            if (c[2]<minHits) continue;
            float estR=(float)Math.sqrt(c[2]*scanStep*scanStep/Math.PI);
            out.add(new Coin(c[0],c[1],Math.max(minR,Math.min(maxR,estR)),color,false));
        }
    }

    private void nms(List<Coin> coins) {
        boolean[] keep=new boolean[coins.size()];
        Arrays.fill(keep,true);
        for (int i=0; i<coins.size(); i++) {
            if (!keep[i]) continue;
            Coin a=coins.get(i);
            for (int j=i+1; j<coins.size(); j++) {
                if (!keep[j]) continue;
                Coin b=coins.get(j);
                float dx=a.pos.x-b.pos.x, dy=a.pos.y-b.pos.y;
                float d=(float)Math.sqrt(dx*dx+dy*dy);
                if (d<(a.radius+b.radius)*0.65f) {
                    if (a.radius>=b.radius) keep[j]=false;
                    else { keep[i]=false; break; }
                }
            }
        }
        Iterator<Coin> it=coins.iterator(); int idx=0;
        while (it.hasNext()) { it.next(); if (!keep[idx++]) it.remove(); }
    }

    private GameState fallbackState(int w, int h) {
        GameState s=new GameState();
        float side=w*0.94f;
        float uiTop=h*0.14f, uiBot=h*0.10f;
        float cy=uiTop+(h-uiTop-uiBot)*0.50f;
        side=Math.min(side,(h-uiTop-uiBot)*0.96f);
        float cx=w/2f;
        s.board=new RectF(cx-side/2f,cy-side/2f,cx+side/2f,cy+side/2f);
        float defY=s.board.top+s.board.height()*STRIKER_Y_FRAC;
        s.striker=new Coin(cx,defY,side*0.028f,Coin.COLOR_STRIKER,true);
        float r=side*0.022f;
        s.coins.add(new Coin(cx,cy-side*0.10f,r,Coin.COLOR_WHITE,false));
        s.coins.add(new Coin(cx-side*0.12f,cy,r,Coin.COLOR_BLACK,false));
        s.coins.add(new Coin(cx+side*0.12f,cy,r,Coin.COLOR_BLACK,false));
        s.coins.add(new Coin(cx,cy,r,Coin.COLOR_RED,false));
        addPockets(s);
        return s;
    }

    private void addPockets(GameState s) {
        if (s.board==null) return;
        float i=s.board.width()*POCKET_INSET;
        s.pockets.add(new PointF(s.board.left+i,  s.board.top+i));
        s.pockets.add(new PointF(s.board.right-i, s.board.top+i));
        s.pockets.add(new PointF(s.board.left+i,  s.board.bottom-i));
        s.pockets.add(new PointF(s.board.right-i, s.board.bottom-i));
    }

    private RectF scaleRect(RectF r, float s) {
        return new RectF(r.left*s,r.top*s,r.right*s,r.bottom*s);
    }

    private RectF smoothRect(RectF p, RectF n) {
        if (p==null) return n; if (n==null) return p;
        return new RectF(
                p.left  +emaA*(n.left  -p.left),
                p.top   +emaA*(n.top   -p.top),
                p.right +emaA*(n.right -p.right),
                p.bottom+emaA*(n.bottom-p.bottom));
    }
}
