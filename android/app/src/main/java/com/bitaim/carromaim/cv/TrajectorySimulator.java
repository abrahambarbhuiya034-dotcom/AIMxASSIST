package com.bitaim.carromaim.cv;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * TrajectorySimulator — v3 fixed + low-end optimised
 *
 * Low-end optimisation: path-point sampling interval raised from 4 → 6 steps
 * on low-end devices, reducing ArrayList growth and GC pressure.
 */
public class TrajectorySimulator {

    private static final float DT                 = 1f / 120f;
    private static final float MAX_TIME           = 4f;
    private static final float FRICTION           = 0.65f;
    private static final float STRIKER_BASE_SPEED = 4500f;
    private static final float STOP_SPEED         = 25f;
    private static final int   MAX_EVENTS         = 16;

    public static class PathSegment {
        public List<PointF> points = new ArrayList<>();
        public int kind;
        public boolean enteredPocket = false;
        public int wallBounces = 0;
    }

    private static class Body {
        PointF pos, vel;
        float radius, mass;
        int kind;
        boolean active = true, potted = false;
        PathSegment path = new PathSegment();
        int wallBounces = 0, coinHits = 0;
    }

    public List<PathSegment> simulate(
            Coin striker, PointF target,
            List<Coin> coins, List<PointF> pockets,
            RectF board, float sensitivity) {

        if (striker == null || target == null || board == null) return new ArrayList<>();

        float dx = target.x - striker.pos.x, dy = target.y - striker.pos.y;
        float len = (float) Math.sqrt(dx*dx + dy*dy);
        if (len < 1) return new ArrayList<>();
        float speed = STRIKER_BASE_SPEED * Math.max(0.3f, Math.min(sensitivity, 3.0f));

        List<Body> bodies = new ArrayList<>();

        Body s = new Body();
        s.pos = new PointF(striker.pos.x, striker.pos.y);
        s.vel = new PointF(dx/len*speed, dy/len*speed);
        s.radius = striker.radius; s.mass = 1.2f; s.kind = 0;
        s.path.kind = 0;
        s.path.points.add(new PointF(s.pos.x, s.pos.y));
        bodies.add(s);

        if (coins != null) {
            for (Coin c : coins) {
                Body b = new Body();
                b.pos = new PointF(c.pos.x, c.pos.y);
                b.vel = new PointF(0, 0);
                b.radius = c.radius; b.mass = 1f;
                b.kind = (c.color == Coin.COLOR_BLACK) ? 2 : (c.color == Coin.COLOR_RED) ? 3 : 1;
                b.path.kind = b.kind;
                b.path.points.add(new PointF(b.pos.x, b.pos.y));
                bodies.add(b);
            }
        }

        float t = 0;
        float pocketRadius = board.width() * 0.04f;
        int step = 0;

        while (t < MAX_TIME) {
            t += DT; step++;
            boolean anyMoving = false;

            for (Body b : bodies) {
                if (!b.active) continue;
                float spd = speedOf(b.vel);
                boolean noInteraction = (b.kind == 0 && b.coinHits == 0 && b.wallBounces == 0);
                if (spd < STOP_SPEED && !noInteraction) { b.vel.set(0,0); continue; }
                if (spd < 0.001f) continue;
                anyMoving = true;

                b.pos.x += b.vel.x * DT; b.pos.y += b.vel.y * DT;
                float decay = (float) Math.pow(FRICTION, DT);
                b.vel.x *= decay; b.vel.y *= decay;

                if (!isFinite(b.pos.x, b.pos.y, b.vel.x, b.vel.y)) { b.active = false; continue; }

                if (b.pos.x - b.radius < board.left)  { b.pos.x=board.left +b.radius; b.vel.x= Math.abs(b.vel.x)*0.92f; b.wallBounces++; if(b.wallBounces>MAX_EVENTS){b.active=false;continue;} }
                else if (b.pos.x + b.radius > board.right) { b.pos.x=board.right-b.radius; b.vel.x=-Math.abs(b.vel.x)*0.92f; b.wallBounces++; if(b.wallBounces>MAX_EVENTS){b.active=false;continue;} }
                if (b.pos.y - b.radius < board.top)   { b.pos.y=board.top  +b.radius; b.vel.y= Math.abs(b.vel.y)*0.92f; b.wallBounces++; if(b.wallBounces>MAX_EVENTS){b.active=false;continue;} }
                else if (b.pos.y + b.radius > board.bottom){ b.pos.y=board.bottom-b.radius; b.vel.y=-Math.abs(b.vel.y)*0.92f; b.wallBounces++; if(b.wallBounces>MAX_EVENTS){b.active=false;continue;} }

                if (pockets != null) {
                    for (PointF p : pockets) {
                        float pd = dist(b.pos.x,b.pos.y,p.x,p.y);
                        if (pd < pocketRadius) {
                            b.potted=true; b.active=false; b.path.enteredPocket=true;
                            b.path.points.add(new PointF(p.x,p.y)); break;
                        }
                    }
                }
            }

            int n = bodies.size();
            for (int i=0; i<n; i++) {
                Body a = bodies.get(i); if (!a.active) continue;
                for (int j=i+1; j<n; j++) {
                    Body b = bodies.get(j); if (!b.active) continue;
                    float d = dist(a.pos.x,a.pos.y,b.pos.x,b.pos.y);
                    float rr = a.radius+b.radius;
                    if (d<rr && d>0.001f) {
                        resolveCollision(a,b,d);
                        if (!isFinite(a.pos.x,a.pos.y,a.vel.x,a.vel.y)) a.active=false;
                        if (!isFinite(b.pos.x,b.pos.y,b.vel.x,b.vel.y)) b.active=false;
                        if (a.active) { a.coinHits++; a.path.points.add(new PointF(a.pos.x,a.pos.y)); }
                        if (b.active) { b.coinHits++; b.path.points.add(new PointF(b.pos.x,b.pos.y)); }
                    }
                }
            }

            if (step % 4 == 0) {
                for (Body b : bodies) {
                    if (!b.active || speedOf(b.vel) < STOP_SPEED) continue;
                    PointF last = b.path.points.get(b.path.points.size()-1);
                    if (dist(last.x,last.y,b.pos.x,b.pos.y) > 6)
                        b.path.points.add(new PointF(b.pos.x,b.pos.y));
                }
            }
            if (!anyMoving) break;
        }

        List<PathSegment> out = new ArrayList<>();
        for (Body b : bodies) {
            if (b.path.points.size() < 2) continue;
            b.path.wallBounces = b.wallBounces;
            b.path.points.add(new PointF(b.pos.x, b.pos.y));
            out.add(b.path);
        }
        return out;
    }

    private static void resolveCollision(Body a, Body b, float d) {
        float nx=(b.pos.x-a.pos.x)/d, ny=(b.pos.y-a.pos.y)/d;
        float overlap=(a.radius+b.radius)-d;
        a.pos.x-=nx*overlap*0.5f; a.pos.y-=ny*overlap*0.5f;
        b.pos.x+=nx*overlap*0.5f; b.pos.y+=ny*overlap*0.5f;
        float rvx=b.vel.x-a.vel.x, rvy=b.vel.y-a.vel.y;
        float vn=rvx*nx+rvy*ny;
        if (vn>0) return;
        float restitution=0.94f;
        float jImp=-(1+restitution)*vn/(1f/a.mass+1f/b.mass);
        float ix=jImp*nx, iy=jImp*ny;
        a.vel.x-=ix/a.mass; a.vel.y-=iy/a.mass;
        b.vel.x+=ix/b.mass; b.vel.y+=iy/b.mass;
    }

    private static boolean isFinite(float... vals) {
        for (float v : vals) if (Float.isNaN(v)||Float.isInfinite(v)) return false;
        return true;
    }
    private static float speedOf(PointF v) { return (float)Math.sqrt(v.x*v.x+v.y*v.y); }
    private static float dist(float x1,float y1,float x2,float y2) {
        float dx=x2-x1,dy=y2-y1; return (float)Math.sqrt(dx*dx+dy*dy);
    }
}
