package com.bitaim.carromaim.cv;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CarromAI — v8.0 GODMODE + Low-End Optimised
 *
 * Low-end optimisations:
 *  – SUBSTEPS reduced 4 → 2 when device RAM < 1.5 GB (set via setLowEndMode).
 *  – findBestShotPhysics() caps top-N minimax candidates at 30 (vs 60) on low-end.
 *  – minimax max depth capped at 1 on low-end (vs 2).
 *
 * Public API:
 *   findBestShots(state, maxResults)   — instant geometry (for aim lines)
 *   findBestShotPhysics(state)         — physics-validated best shot (autoplay)
 *   setLowEndMode(true)                — call once from Application.onCreate()
 */
public class CarromAI {

    private static volatile boolean sLowEnd = false;
    public static void setLowEndMode(boolean v) { sLowEnd = v; }

    private static final float L_LEFT   =  80f;
    private static final float L_RIGHT  = 620f;
    private static final float L_TOP    =  80f;
    private static final float L_BOTTOM = 620f;
    private static final float L_SIZE   = 540f;

    private static final float L_STRIKER_R  = 18f;
    private static final float L_PIECE_R    = 15f;
    private static final float L_POCKET_R   = 22f;

    private static final float FRICTION     = 0.982f;
    private static final float MIN_SPEED    = 0.18f;
    private static final float RESTITUTION  = 0.88f;
    private static final float MAX_SPEED    = 24f;
    private static final float STRIKER_MASS = 2.5f;
    private static final float SAFE_MARGIN  = 28f;
    private static final float ANGLE_STEP   = 0.5f;

    private static final float[][] POCKETS = {
        {L_LEFT,  L_TOP},
        {L_RIGHT, L_TOP},
        {L_LEFT,  L_BOTTOM},
        {L_RIGHT, L_BOTTOM},
    };

    // ── Public AiShot ────────────────────────────────────────────────────────
    public static class AiShot {
        public final PointF ghostPos;
        public final Coin   coin;
        public final PointF pocket;
        public final float  score, powerFrac;
        public final int    wallsNeeded;
        public final boolean isBank;
        AiShot(PointF g, Coin c, PointF pk, float sc, float pwr, int walls, boolean bank) {
            ghostPos=g; coin=c; pocket=pk; score=sc; powerFrac=pwr; wallsNeeded=walls; isBank=bank;
        }
    }

    // ── Internal logical-space piece ─────────────────────────────────────────
    private static class LPiece {
        String id; float x,y,vx,vy,radius; int color; boolean pocketed;
        LPiece(String id,float x,float y,float r,int col){this.id=id;this.x=x;this.y=y;this.radius=r;this.color=col;}
        LPiece copy(){LPiece p=new LPiece(id,x,y,radius,color);p.vx=vx;p.vy=vy;p.pocketed=pocketed;return p;}
    }

    private static class SimResult {
        List<LPiece> pocketed=new ArrayList<>(), finalState=new ArrayList<>();
        boolean strikerFouled=false;
    }

    // ── Coordinate normalisation ──────────────────────────────────────────────
    private static float normX(float x, RectF b){return L_LEFT+(x-b.left)/b.width()*L_SIZE;}
    private static float normY(float y, RectF b){return L_TOP +(y-b.top )/b.height()*L_SIZE;}
    private static float normR(float r, RectF b){return r/b.width()*L_SIZE;}
    private static float denormX(float lx,RectF b){return b.left+(lx-L_LEFT)/L_SIZE*b.width();}
    private static float denormY(float ly,RectF b){return b.top +(ly-L_TOP )/L_SIZE*b.height();}

    private static List<LPiece> toLPieces(GameState s) {
        List<LPiece> out=new ArrayList<>(); int idx=0;
        for (Coin c:s.coins) {
            if (c.color==Coin.COLOR_STRIKER) continue;
            out.add(new LPiece("c"+(idx++),normX(c.pos.x,s.board),normY(c.pos.y,s.board),normR(c.radius,s.board),c.color));
        }
        return out;
    }

    // ── Physics simulator ─────────────────────────────────────────────────────
    private static SimResult simulate(List<LPiece> pieces, float sx, float sy, float angleDeg, float power) {
        List<LPiece> state=new ArrayList<>(pieces.size()+1);
        for (LPiece p:pieces) state.add(p.copy());
        float rad=(float)Math.toRadians(angleDeg);
        float speed=power*MAX_SPEED;
        LPiece striker=new LPiece("striker",sx,sy,L_STRIKER_R,Coin.COLOR_STRIKER);
        striker.vx=(float)Math.cos(rad)*speed; striker.vy=(float)Math.sin(rad)*speed;
        state.add(striker);

        SimResult res=new SimResult();
        int maxFrames=600;
        int substeps = sLowEnd ? 2 : 4;
        float subFric=(float)Math.pow(FRICTION,1.0/substeps);

        while (maxFrames-- > 0) {
            boolean anyMoving=false;
            for (int sub=0; sub<substeps; sub++) {
                for (LPiece p:state) {
                    if (p.pocketed) continue;
                    p.x+=p.vx/substeps; p.y+=p.vy/substeps;
                    p.vx*=subFric; p.vy*=subFric;
                    float spd=(float)Math.sqrt(p.vx*p.vx+p.vy*p.vy);
                    if (spd>MIN_SPEED) anyMoving=true; else {p.vx=0;p.vy=0;}
                }
                for (LPiece p:state) {
                    if (p.pocketed) continue;
                    float r=p.radius;
                    if (p.x-r<L_LEFT)  {p.x=L_LEFT +r;p.vx= Math.abs(p.vx)*RESTITUTION;}
                    if (p.x+r>L_RIGHT) {p.x=L_RIGHT-r;p.vx=-Math.abs(p.vx)*RESTITUTION;}
                    if (p.y-r<L_TOP)   {p.y=L_TOP  +r;p.vy= Math.abs(p.vy)*RESTITUTION;}
                    if (p.y+r>L_BOTTOM){p.y=L_BOTTOM-r;p.vy=-Math.abs(p.vy)*RESTITUTION;}
                }
                int n=state.size();
                for (int i=0;i<n;i++) {
                    LPiece a=state.get(i); if (a.pocketed) continue;
                    for (int j=i+1;j<n;j++) {
                        LPiece b=state.get(j); if (b.pocketed) continue;
                        float minD=a.radius+b.radius;
                        float dx=b.x-a.x,dy=b.y-a.y,dSq=dx*dx+dy*dy;
                        if (dSq>=minD*minD||dSq<0.0001f) continue;
                        float dist=(float)Math.sqrt(dSq);
                        float nx=dx/dist,ny=dy/dist;
                        float mA=(a.color==Coin.COLOR_STRIKER)?STRIKER_MASS:1f;
                        float mB=(b.color==Coin.COLOR_STRIKER)?STRIKER_MASS:1f;
                        float mt=mA+mB;
                        float ov=(minD-dist)*0.52f;
                        a.x-=nx*ov*(mB/mt);a.y-=ny*ov*(mB/mt);
                        b.x+=nx*ov*(mA/mt);b.y+=ny*ov*(mA/mt);
                        float dvx=a.vx-b.vx,dvy=a.vy-b.vy;
                        float dot=dvx*nx+dvy*ny;
                        if (dot<=0) continue;
                        float imp=(2f*dot*RESTITUTION)/mt;
                        a.vx-=(imp*mB)*nx;a.vy-=(imp*mB)*ny;
                        b.vx+=(imp*mA)*nx;b.vy+=(imp*mA)*ny;
                    }
                }
                for (LPiece p:state) {
                    if (p.pocketed) continue;
                    for (float[] pk:POCKETS) {
                        float dx=p.x-pk[0],dy=p.y-pk[1];
                        if (dx*dx+dy*dy<L_POCKET_R*L_POCKET_R) {
                            p.pocketed=true;p.vx=0;p.vy=0;
                            if (p.color==Coin.COLOR_STRIKER) res.strikerFouled=true;
                            else res.pocketed.add(p.copy());
                            break;
                        }
                    }
                }
            }
            if (!anyMoving) break;
        }
        for (LPiece p:state)
            if (!p.pocketed&&p.color!=Coin.COLOR_STRIKER) res.finalState.add(p);
        return res;
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────
    private static float[] ghostBallLogical(float cx,float cy,float pocketX,float pocketY,float cR,float sR) {
        float dx=cx-pocketX,dy=cy-pocketY,len=(float)Math.sqrt(dx*dx+dy*dy);
        if (len<0.001f) return null;
        float ghostR=sR+cR;
        return new float[]{cx+(dx/len)*ghostR,cy+(dy/len)*ghostR};
    }

    private static float calibratePower(float sx,float sy,float gx,float gy,float px,float py) {
        float boardDiag=(float)Math.sqrt(L_SIZE*L_SIZE+L_SIZE*L_SIZE);
        float d1=dist2(sx,sy,gx,gy),d2=dist2(gx,gy,px,py);
        return Math.min(1.0f,Math.max(0.35f,(d1+d2*0.35f)/boardDiag*1.55f));
    }

    private static boolean pathClear(float ax,float ay,float bx,float by,float r,List<LPiece> pieces,String excludeId) {
        float dx=bx-ax,dy=by-ay,len=(float)Math.sqrt(dx*dx+dy*dy);
        if (len<0.001f) return true;
        float ux=dx/len,uy=dy/len;
        for (LPiece p:pieces) {
            if (p.pocketed||p.id.equals(excludeId)) continue;
            float tpx=p.x-ax,tpy=p.y-ay,proj=tpx*ux+tpy*uy;
            if (proj<0||proj>len) continue;
            float cx=ax+ux*proj,cy=ay+uy*proj;
            if (dist2(cx,cy,p.x,p.y)<r+p.radius-1f) return false;
        }
        return true;
    }

    private static boolean strikerSafe(float sx,float sy,float gx,float gy) {
        float dx=gx-sx,dy=gy-sy,len=(float)Math.sqrt(dx*dx+dy*dy);
        if (len<0.001f) return false;
        float ux=dx/len,uy=dy/len;
        for (float[] pk:POCKETS) {
            float tpx=pk[0]-sx,tpy=pk[1]-sy,proj=tpx*ux+tpy*uy;
            if (proj<0) continue;
            float cx=sx+ux*proj,cy=sy+uy*proj;
            if (dist2(cx,cy,pk[0],pk[1])<SAFE_MARGIN) return false;
        }
        return true;
    }

    private static float scoreResult(SimResult res) {
        if (res.strikerFouled) return -99999f;
        float score=0; int myPots=0;
        for (LPiece p:res.pocketed) {
            if (p.color==Coin.COLOR_RED)   {score+=250;} 
            else if (p.color==Coin.COLOR_WHITE){score+=100;myPots++;}
            else {score+=50;myPots++;}
        }
        if (myPots>=2) score+=400; if (myPots>=3) score+=900; if (myPots>=4) score+=2000;
        for (LPiece p:res.finalState) {
            float minD=Float.MAX_VALUE;
            for (float[] pk:POCKETS) { float d=dist2(p.x,p.y,pk[0],pk[1]); if(d<minD) minD=d; }
            score-=minD*0.04f;
        }
        return score;
    }

    // ── Candidate ─────────────────────────────────────────────────────────────
    private static class Candidate {
        float sx,sy,angleDeg,power; String targetId; boolean isBank;
        Candidate(float sx,float sy,float ang,float pw,String tid,boolean bank){
            this.sx=sx;this.sy=sy;angleDeg=ang;power=pw;targetId=tid;isBank=bank;
        }
    }

    private static List<Candidate> generateCandidates(List<LPiece> pieces, float strikerX, float strikerY, float sR) {
        Map<String,Candidate> map=new HashMap<>();

        for (LPiece piece:pieces) {
            for (float[] pk:POCKETS) {
                float[] ghost=ghostBallLogical(piece.x,piece.y,pk[0],pk[1],piece.radius,sR);
                if (ghost==null) continue;
                float gx=ghost[0],gy=ghost[1];
                if (gx<L_LEFT-sR||gx>L_RIGHT+sR||gy<L_TOP-sR||gy>L_BOTTOM+sR) continue;

                float dx=gx-strikerX,dy=gy-strikerY,len=(float)Math.sqrt(dx*dx+dy*dy);
                if (len<1f) continue;
                if (!pathClear(strikerX,strikerY,gx,gy,sR,pieces,piece.id)) continue;
                if (!strikerSafe(strikerX,strikerY,gx,gy)) continue;

                float ang=(float)Math.toDegrees(Math.atan2(dy,dx));
                float pwr=calibratePower(strikerX,strikerY,gx,gy,pk[0],pk[1]);
                String key=Math.round(gx/4f)+"_"+Math.round(gy/4f);
                if (!map.containsKey(key))
                    map.put(key,new Candidate(strikerX,strikerY,ang,pwr,piece.id,false));

                // Fine-angle variants ±1.5°
                for (float delta=-1.5f; delta<=1.5f; delta+=1.5f) {
                    if (delta==0) continue;
                    float a2=ang+delta;
                    float r2=(float)Math.toRadians(a2);
                    float gx2=strikerX+(float)Math.cos(r2)*len;
                    float gy2=strikerY+(float)Math.sin(r2)*len;
                    if (!strikerSafe(strikerX,strikerY,gx2,gy2)) continue;
                    String k2=Math.round(gx2/4f)+"_"+Math.round(gy2/4f);
                    if (!map.containsKey(k2))
                        map.put(k2,new Candidate(strikerX,strikerY,a2,pwr,piece.id,false));
                }

                // Bank shot — single-wall reflection
                float[][] walls={{L_LEFT,0},{L_RIGHT,0},{0,L_TOP},{0,L_BOTTOM}};
                for (float[] wall:walls) {
                    float rx=gx,ry=gy;
                    if (wall[0]==L_LEFT||wall[0]==L_RIGHT) {
                        float wx=wall[0];
                        if ((gx<wx&&strikerX>wx)||(gx>wx&&strikerX<wx)) continue;
                        rx=2*wx-gx;
                    } else {
                        float wy=wall[1];
                        if ((gy<wy&&strikerY>wy)||(gy>wy&&strikerY<wy)) continue;
                        ry=2*wy-gy;
                    }
                    if (rx<L_LEFT-sR*3||rx>L_RIGHT+sR*3||ry<L_TOP-sR*3||ry>L_BOTTOM+sR*3) continue;
                    float bdx=rx-strikerX,bdy=ry-strikerY;
                    float bang=(float)Math.toDegrees(Math.atan2(bdy,bdx));
                    if (!strikerSafe(strikerX,strikerY,rx,ry)) continue;
                    float bpwr=calibratePower(strikerX,strikerY,rx,ry,pk[0],pk[1])*1.25f;
                    String bk="B_"+Math.round(rx/4f)+"_"+Math.round(ry/4f);
                    if (!map.containsKey(bk))
                        map.put(bk,new Candidate(strikerX,strikerY,bang,Math.min(1f,bpwr),piece.id,true));
                }
            }
        }

        // Brute-force sweep 0.5° fallback if too few candidates
        if (map.size()<8) {
            for (float ang=0; ang<360; ang+=ANGLE_STEP) {
                float r=(float)Math.toRadians(ang);
                float gx=strikerX+(float)Math.cos(r)*80;
                float gy=strikerY+(float)Math.sin(r)*80;
                if (gx<L_LEFT||gx>L_RIGHT||gy<L_TOP||gy>L_BOTTOM) continue;
                if (!strikerSafe(strikerX,strikerY,gx,gy)) continue;
                String k="SW_"+Math.round(ang*2);
                if (!map.containsKey(k))
                    map.put(k,new Candidate(strikerX,strikerY,ang,0.6f,"",false));
            }
        }

        return new ArrayList<>(map.values());
    }

    // ── Minimax ────────────────────────────────────────────────────────────────
    private static float minimax(List<LPiece> pieces, int depth, float alpha, float beta, Map<String,Float> cache) {
        if (depth<=0||pieces.isEmpty()) return quickEval(pieces);
        String key=stateKey(pieces,depth);
        if (cache.containsKey(key)) return cache.get(key);
        float best=-Float.MAX_VALUE;
        // Use reduced set on low-end
        int limit = sLowEnd ? 4 : 8;
        int tried=0;
        for (LPiece piece:pieces) {
            if (tried>=limit) break;
            tried++;
            for (float[] pk:POCKETS) {
                float[] ghost=ghostBallLogical(piece.x,piece.y,pk[0],pk[1],piece.radius,L_STRIKER_R);
                if (ghost==null) continue;
                float gx=ghost[0],gy=ghost[1];
                float sx=L_LEFT+L_SIZE*0.5f,sy=L_BOTTOM;
                if (!strikerSafe(sx,sy,gx,gy)) continue;
                float ang=(float)Math.toDegrees(Math.atan2(gy-sy,gx-sx));
                float pwr=calibratePower(sx,sy,gx,gy,pk[0],pk[1]);
                SimResult res=simulate(pieces,sx,sy,ang,pwr);
                float sc=scoreResult(res)+minimax(res.finalState,depth-1,alpha,beta,cache)*0.55f;
                if (sc>best) best=sc;
                if (best>alpha) alpha=best;
                if (alpha>=beta) { cache.put(key,best); return best; }
            }
        }
        cache.put(key,best);
        return best;
    }

    private static float quickEval(List<LPiece> pieces) {
        float score=0;
        for (LPiece p:pieces) {
            float minD=Float.MAX_VALUE;
            for (float[] pk:POCKETS) { float d=dist2(p.x,p.y,pk[0],pk[1]); if(d<minD)minD=d; }
            score-=minD*0.04f;
        }
        return score;
    }

    private static String stateKey(List<LPiece> pieces, int depth) {
        StringBuilder sb=new StringBuilder(); sb.append(depth).append(':');
        for (LPiece p:pieces) sb.append(Math.round(p.x/4f)).append(',').append(Math.round(p.y/4f)).append(';');
        return sb.toString();
    }

    // ── Public: geometry shots (for aim lines) ────────────────────────────────
    public static List<AiShot> findBestShots(GameState state, int maxResults) {
        if (state==null||state.striker==null||state.board==null||state.pockets==null||state.pockets.isEmpty())
            return new ArrayList<>();
        RectF b=state.board;
        float sLX=normX(state.striker.pos.x,b),sLY=normY(state.striker.pos.y,b);
        float sR=normR(state.striker.radius,b);
        List<LPiece> pieces=toLPieces(state);

        List<AiShot> results=new ArrayList<>();
        for (LPiece piece:pieces) {
            for (int pi=0;pi<POCKETS.length;pi++) {
                if (pi>=state.pockets.size()) continue;
                float[] pk=POCKETS[pi];
                float[] ghost=ghostBallLogical(piece.x,piece.y,pk[0],pk[1],piece.radius,sR);
                if (ghost==null) continue;
                float gx=ghost[0],gy=ghost[1];
                if (gx<L_LEFT-sR*3||gx>L_RIGHT+sR*3||gy<L_TOP-sR*3||gy>L_BOTTOM+sR*3) continue;
                if (!pathClear(sLX,sLY,gx,gy,sR,pieces,piece.id)) continue;
                if (!strikerSafe(sLX,sLY,gx,gy)) continue;
                float pwr=calibratePower(sLX,sLY,gx,gy,pk[0],pk[1]);
                float sc=2000f/(dist2(piece.x,piece.y,pk[0],pk[1])+1f)+
                        (piece.color==Coin.COLOR_RED?500f:piece.color==Coin.COLOR_WHITE?200f:100f);
                PointF ghostScr=new PointF(denormX(gx,b),denormY(gy,b));
                Coin coinRef=findCoin(state,piece);
                results.add(new AiShot(ghostScr,coinRef,state.pockets.get(pi),sc,pwr,0,false));
            }
        }
        // Bank shots (single wall)
        for (LPiece piece:pieces) {
            for (int pi=0;pi<POCKETS.length;pi++) {
                if (pi>=state.pockets.size()) continue;
                float[] pk=POCKETS[pi];
                float[] ghost=ghostBallLogical(piece.x,piece.y,pk[0],pk[1],piece.radius,sR);
                if (ghost==null) continue;
                float gx=ghost[0],gy=ghost[1];
                float[][] walls={{L_LEFT,0},{L_RIGHT,0},{0,L_TOP},{0,L_BOTTOM}};
                for (float[] wall:walls) {
                    float rx=gx,ry=gy;
                    if (wall[0]==L_LEFT||wall[0]==L_RIGHT){float wx=wall[0];rx=2*wx-gx;}
                    else{float wy=wall[1];ry=2*wy-gy;}
                    if (rx<L_LEFT-sR*4||rx>L_RIGHT+sR*4||ry<L_TOP-sR*4||ry>L_BOTTOM+sR*4) continue;
                    if (!strikerSafe(sLX,sLY,rx,ry)) continue;
                    float pwr=Math.min(1f,calibratePower(sLX,sLY,rx,ry,pk[0],pk[1])*1.25f);
                    float sc=800f/(dist2(piece.x,piece.y,pk[0],pk[1])+1f);
                    PointF ghostScr=new PointF(denormX(rx,b),denormY(ry,b));
                    Coin coinRef=findCoin(state,piece);
                    results.add(new AiShot(ghostScr,coinRef,state.pockets.get(pi),sc,pwr,1,true));
                }
            }
        }

        Collections.sort(results,(a,c)->Float.compare(c.score,a.score));

        List<AiShot> deduped=new ArrayList<>();
        for (AiShot shot:results) {
            boolean dup=false;
            for (AiShot kept:deduped) {
                if (distP(shot.ghostPos,kept.ghostPos)<18f){dup=true;break;}
            }
            if (!dup) deduped.add(shot);
            if (deduped.size()>=maxResults) break;
        }

        // Fallback: always show at least one line
        if (deduped.isEmpty()&&!pieces.isEmpty()&&!state.pockets.isEmpty()) {
            LPiece closest=null; float minD=Float.MAX_VALUE;
            for (LPiece p:pieces){float d=dist2(sLX,sLY,p.x,p.y);if(d<minD){minD=d;closest=p;}}
            if (closest!=null) {
                for (int pi=0;pi<POCKETS.length&&pi<state.pockets.size();pi++) {
                    float[] pk=POCKETS[pi];
                    float[] ghost=ghostBallLogical(closest.x,closest.y,pk[0],pk[1],closest.radius,sR);
                    if (ghost==null) continue;
                    float gx=ghost[0],gy=ghost[1];
                    if (gx<L_LEFT-sR*3||gx>L_RIGHT+sR*3||gy<L_TOP-sR*3||gy>L_BOTTOM+sR*3) continue;
                    PointF ghostScr=new PointF(denormX(gx,b),denormY(gy,b));
                    float pwr=calibratePower(sLX,sLY,gx,gy,pk[0],pk[1]);
                    float sc=1200f/(dist2(closest.x,closest.y,pk[0],pk[1])+20f);
                    deduped.add(new AiShot(ghostScr,findCoin(state,closest),state.pockets.get(pi),sc,pwr,0,false));
                    break;
                }
            }
        }
        return deduped;
    }

    // ── Public: physics-validated best shot (for autoplay) ────────────────────
    public static AiShot findBestShotPhysics(GameState state) {
        if (state==null||state.striker==null||state.board==null||state.pockets==null||state.pockets.isEmpty())
            return null;
        RectF b=state.board;
        float sLX=normX(state.striker.pos.x,b),sLY=normY(state.striker.pos.y,b);
        float sR=normR(state.striker.radius,b);
        List<LPiece> pieces=toLPieces(state);
        List<Candidate> candidates=generateCandidates(pieces,sLX,sLY,sR);

        List<float[]> scored=new ArrayList<>();
        for (int i=0;i<candidates.size();i++) {
            Candidate c=candidates.get(i);
            SimResult res=simulate(pieces,c.sx,c.sy,c.angleDeg,c.power);
            if (res.strikerFouled) continue;
            scored.add(new float[]{scoreResult(res),i});
        }

        if (scored.isEmpty()) {
            List<AiShot> geo=findBestShots(state,1);
            return geo.isEmpty()?null:geo.get(0);
        }
        Collections.sort(scored,(a,c)->Float.compare(c[0],a[0]));

        int topN=Math.min(sLowEnd?30:60,scored.size());
        Map<String,Float> mmCache=new HashMap<>();
        Candidate bestC=null; float bestSc=-Float.MAX_VALUE;
        int mmDepth = sLowEnd ? 1 : 2;

        for (int rank=0;rank<topN;rank++) {
            int idx=(int)scored.get(rank)[1];
            Candidate c=candidates.get(idx);
            float baseSc=scored.get(rank)[0];
            if (baseSc<-1000f) continue;
            SimResult res=simulate(pieces,c.sx,c.sy,c.angleDeg,c.power);
            float deepSc=minimax(res.finalState,mmDepth,-Float.MAX_VALUE,Float.MAX_VALUE,mmCache);
            float total=baseSc+deepSc*0.55f;
            if (total>bestSc){bestSc=total;bestC=c;}
        }
        if (bestC==null) bestC=candidates.get((int)scored.get(0)[1]);

        AiShot best=null; float bestDist=Float.MAX_VALUE;
        for (LPiece piece:pieces) {
            for (int pi=0;pi<POCKETS.length;pi++) {
                float[] pk=POCKETS[pi];
                float[] ghost=ghostBallLogical(piece.x,piece.y,pk[0],pk[1],piece.radius,sR);
                if (ghost==null) continue;
                float gx=ghost[0],gy=ghost[1];
                float dx=gx-bestC.sx,dy=gy-bestC.sy,len=(float)Math.sqrt(dx*dx+dy*dy);
                if (len<0.001f) continue;
                float ang=(float)Math.toDegrees(Math.atan2(dy,dx));
                float diff=Math.abs(ang-bestC.angleDeg); if(diff>180)diff=360-diff;
                if (diff<bestDist) {
                    bestDist=diff;
                    PointF ghostScr=new PointF(denormX(gx,b),denormY(gy,b));
                    Coin coinRef=findCoin(state,piece);
                    PointF pkScr=pi<state.pockets.size()?state.pockets.get(pi):null;
                    best=new AiShot(ghostScr,coinRef,pkScr,bestSc,bestC.power,bestC.isBank?1:0,bestC.isBank);
                }
            }
        }

        if (best!=null) return best;
        float rad=(float)Math.toRadians(bestC.angleDeg);
        float gx=bestC.sx+(float)Math.cos(rad)*sR*4;
        float gy=bestC.sy+(float)Math.sin(rad)*sR*4;
        return new AiShot(new PointF(denormX(gx,b),denormY(gy,b)),null,null,bestSc,bestC.power,0,false);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private static Coin findCoin(GameState state, LPiece lp) {
        RectF b=state.board; float bestD=Float.MAX_VALUE; Coin best=null;
        for (Coin c:state.coins) {
            if (c.color==Coin.COLOR_STRIKER) continue;
            float d=dist2(normX(c.pos.x,b),normY(c.pos.y,b),lp.x,lp.y);
            if (d<bestD){bestD=d;best=c;}
        }
        return best;
    }

    public static float distP(PointF a,PointF b) {
        float dx=a.x-b.x,dy=a.y-b.y;return (float)Math.sqrt(dx*dx+dy*dy);
    }

    private static float dist2(float ax,float ay,float bx,float by) {
        float dx=ax-bx,dy=ay-by;return (float)Math.sqrt(dx*dx+dy*dy);
    }

    public static boolean isPathClear(PointF a,PointF b,float r,List<Coin> coins,Coin exclude) {
        float dx=b.x-a.x,dy=b.y-a.y,len=(float)Math.sqrt(dx*dx+dy*dy);
        if (len<0.001f) return true;
        float ux=dx/len,uy=dy/len;
        for (Coin p:coins) {
            if (p==exclude||p.color==Coin.COLOR_STRIKER) continue;
            float tpx=p.pos.x-a.x,tpy=p.pos.y-a.y,proj=tpx*ux+tpy*uy;
            if (proj<0||proj>len) continue;
            float cx=a.x+ux*proj,cy=a.y+uy*proj;
            float cd=dist2(cx,cy,p.pos.x,p.pos.y);
            if (cd<r+p.radius-2f) return false;
        }
        return true;
    }
}
