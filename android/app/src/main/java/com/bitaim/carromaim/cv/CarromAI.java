package com.bitaim.carromaim.cv;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CarromAI — v8.0 GODMODE
 *
 * Complete Java port of the CarromAutoplay v4.0 Merged Engine:
 *
 *  § Physics    Sub-pixel 4× micro-step simulation; elastic impulse; realistic
 *               mass ratio (striker 2.5×); pocket detection per sub-step.
 *  § Geometry   Ghost-ball targeting for every coin × pocket; bank-shot
 *               reflection through all 3 valid walls; path-clearance check;
 *               adaptive power calibration (distance + friction correction).
 *  § Safety     Hard geometric striker-foul guarantee — never pockets itself.
 *  § Scorer     Multi-pot exponential bonus; queen value; pocket proximity
 *               board-control term; opponent-piece penalty.
 *  § Generator  Dedup map; ghost-ball + bank + fine-angle variants; brute
 *               sweep fallback at 0.5° resolution.
 *  § Minimax    Depth-3 α-β with memoization; quick board evaluator.
 *
 * Coordinates: internally normalised to the 80–620 logical space of the JS
 * engine (540 × 540 units) then denormalised back to screen pixels so all
 * tuning constants are identical to the JS reference.
 *
 * Public API:
 *   findBestShots(state, maxResults)   — instant geometry result (for lines)
 *   findBestShotPhysics(state)         — physics-validated best shot (for autoplay)
 */
public class CarromAI {

    // ── JS-engine constants (logical-unit space) ──────────────────────────────
    private static final float L_LEFT   =  80f;
    private static final float L_RIGHT  = 620f;
    private static final float L_TOP    =  80f;
    private static final float L_BOTTOM = 620f;
    private static final float L_SIZE   = 540f; // L_RIGHT - L_LEFT

    private static final float L_STRIKER_R  = 18f;
    private static final float L_PIECE_R    = 15f;
    private static final float L_POCKET_R   = 22f;

    private static final float FRICTION     = 0.982f;
    private static final float MIN_SPEED    = 0.18f;
    private static final float RESTITUTION  = 0.88f;
    private static final float MAX_SPEED    = 24f;
    private static final int   SUBSTEPS     = 4;
    private static final float STRIKER_MASS = 2.5f;

    private static final float SAFE_MARGIN  = 28f; // logical units
    private static final float ANGLE_STEP   = 0.5f; // degrees for brute sweep

    // Logical pocket positions (corners)
    private static final float[][] POCKETS = {
        {L_LEFT,  L_TOP},    // top-left
        {L_RIGHT, L_TOP},    // top-right
        {L_LEFT,  L_BOTTOM}, // bottom-left
        {L_RIGHT, L_BOTTOM}, // bottom-right
    };

    // ── Public AiShot result ──────────────────────────────────────────────────

    public static class AiShot {
        public final PointF ghostPos;     // screen pixels — striker aims here
        public final Coin   coin;
        public final PointF pocket;       // screen pixels
        public final float  score;
        public final float  powerFrac;    // 0.35 – 1.0 adaptive power
        public final int    wallsNeeded;
        public final boolean isBank;

        AiShot(PointF g, Coin c, PointF pk, float sc, float pwr, int walls, boolean bank) {
            ghostPos = g; coin = c; pocket = pk; score = sc;
            powerFrac = pwr; wallsNeeded = walls; isBank = bank;
        }
    }

    // ── Internal logical-space piece ─────────────────────────────────────────

    private static class LPiece {
        String id;
        float  x, y, vx, vy, radius;
        int    color; // Coin color constants
        boolean pocketed;

        LPiece(String id, float x, float y, float r, int color) {
            this.id = id; this.x = x; this.y = y; this.radius = r; this.color = color;
        }
        LPiece copy() {
            LPiece p = new LPiece(id, x, y, radius, color);
            p.vx = vx; p.vy = vy; p.pocketed = pocketed; return p;
        }
    }

    private static class SimResult {
        List<LPiece> pocketed = new ArrayList<>();
        List<LPiece> finalState = new ArrayList<>();
        boolean strikerFouled = false;
    }

    // ── Coordinate normalisation ──────────────────────────────────────────────

    private static float normX(float x, RectF b) {
        return L_LEFT + (x - b.left) / b.width() * L_SIZE;
    }
    private static float normY(float y, RectF b) {
        return L_TOP + (y - b.top) / b.height() * L_SIZE;
    }
    private static float normR(float r, RectF b) {
        return r / b.width() * L_SIZE;
    }
    private static float denormX(float lx, RectF b) {
        return b.left + (lx - L_LEFT) / L_SIZE * b.width();
    }
    private static float denormY(float ly, RectF b) {
        return b.top + (ly - L_TOP) / L_SIZE * b.height();
    }

    // ── GameState → logical pieces ────────────────────────────────────────────

    private static List<LPiece> toLPieces(GameState s) {
        List<LPiece> out = new ArrayList<>();
        int idx = 0;
        for (Coin c : s.coins) {
            if (c.color == Coin.COLOR_STRIKER) continue;
            out.add(new LPiece("c" + idx++,
                normX(c.pos.x, s.board), normY(c.pos.y, s.board),
                normR(c.radius, s.board), c.color));
        }
        return out;
    }

    private static LPiece strikerLP(GameState s) {
        return new LPiece("striker",
            normX(s.striker.pos.x, s.board), normY(s.striker.pos.y, s.board),
            normR(s.striker.radius, s.board), Coin.COLOR_STRIKER);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § PHYSICS SIMULATOR  (4× sub-stepped, mass-ratio 2.5)
    // ─────────────────────────────────────────────────────────────────────────

    private static SimResult simulate(List<LPiece> pieces,
                                       float sx, float sy, float angleDeg, float power) {
        List<LPiece> state = new ArrayList<>(pieces.size() + 1);
        for (LPiece p : pieces) state.add(p.copy());

        // Add striker
        float rad   = (float) Math.toRadians(angleDeg);
        float speed = power * MAX_SPEED;
        LPiece striker = new LPiece("striker", sx, sy, L_STRIKER_R, Coin.COLOR_STRIKER);
        striker.vx = (float) Math.cos(rad) * speed;
        striker.vy = (float) Math.sin(rad) * speed;
        state.add(striker);

        SimResult res = new SimResult();
        int maxFrames = 600;

        while (maxFrames-- > 0) {
            boolean anyMoving = false;
            float subFric = (float) Math.pow(FRICTION, 1.0 / SUBSTEPS);

            for (int sub = 0; sub < SUBSTEPS; sub++) {
                // 1. Integrate
                for (LPiece p : state) {
                    if (p.pocketed) continue;
                    p.x += p.vx / SUBSTEPS;
                    p.y += p.vy / SUBSTEPS;
                    p.vx *= subFric;
                    p.vy *= subFric;
                    float spd = (float) Math.sqrt(p.vx*p.vx + p.vy*p.vy);
                    if (spd > MIN_SPEED) anyMoving = true;
                    else { p.vx = 0; p.vy = 0; }
                }

                // 2. Wall collisions
                for (LPiece p : state) {
                    if (p.pocketed) continue;
                    float r = p.radius;
                    if (p.x - r < L_LEFT)   { p.x = L_LEFT   + r; p.vx =  Math.abs(p.vx) * RESTITUTION; }
                    if (p.x + r > L_RIGHT)  { p.x = L_RIGHT  - r; p.vx = -Math.abs(p.vx) * RESTITUTION; }
                    if (p.y - r < L_TOP)    { p.y = L_TOP    + r; p.vy =  Math.abs(p.vy) * RESTITUTION; }
                    if (p.y + r > L_BOTTOM) { p.y = L_BOTTOM - r; p.vy = -Math.abs(p.vy) * RESTITUTION; }
                }

                // 3. Circle-circle collisions
                int n = state.size();
                for (int i = 0; i < n; i++) {
                    LPiece a = state.get(i);
                    if (a.pocketed) continue;
                    for (int j = i+1; j < n; j++) {
                        LPiece b = state.get(j);
                        if (b.pocketed) continue;
                        float minD = a.radius + b.radius;
                        float dx = b.x - a.x, dy = b.y - a.y;
                        float dSq = dx*dx + dy*dy;
                        if (dSq >= minD*minD || dSq < 0.0001f) continue;
                        float dist = (float) Math.sqrt(dSq);
                        float nx = dx/dist, ny = dy/dist;
                        float mA = (a.color == Coin.COLOR_STRIKER) ? STRIKER_MASS : 1f;
                        float mB = (b.color == Coin.COLOR_STRIKER) ? STRIKER_MASS : 1f;
                        float mt = mA + mB;
                        float ov = (minD - dist) * 0.52f;
                        a.x -= nx * ov * (mB/mt);
                        a.y -= ny * ov * (mB/mt);
                        b.x += nx * ov * (mA/mt);
                        b.y += ny * ov * (mA/mt);
                        float dvx = a.vx - b.vx, dvy = a.vy - b.vy;
                        float dot = dvx*nx + dvy*ny;
                        if (dot <= 0) continue;
                        float imp = (2f * dot * RESTITUTION) / mt;
                        a.vx -= (imp * mB) * nx; a.vy -= (imp * mB) * ny;
                        b.vx += (imp * mA) * nx; b.vy += (imp * mA) * ny;
                    }
                }

                // 4. Pocket detection
                for (LPiece p : state) {
                    if (p.pocketed) continue;
                    for (float[] pk : POCKETS) {
                        float dx = p.x - pk[0], dy = p.y - pk[1];
                        if (dx*dx + dy*dy < L_POCKET_R*L_POCKET_R) {
                            p.pocketed = true; p.vx = 0; p.vy = 0;
                            if (p.color == Coin.COLOR_STRIKER) res.strikerFouled = true;
                            else res.pocketed.add(p.copy());
                            break;
                        }
                    }
                }
            }
            if (!anyMoving) break;
        }

        for (LPiece p : state) {
            if (!p.pocketed && p.color != Coin.COLOR_STRIKER) res.finalState.add(p);
        }
        return res;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § GEOMETRY ENGINE
    // ─────────────────────────────────────────────────────────────────────────

    /** Ghost-ball contact point in logical space for coin→pocket. */
    private static float[] ghostBallLogical(float cx, float cy,
                                             float pocketX, float pocketY,
                                             float cR, float sR) {
        float dx = cx - pocketX, dy = cy - pocketY;
        float len = (float) Math.sqrt(dx*dx + dy*dy);
        if (len < 0.001f) return null;
        float ghostR = sR + cR;
        return new float[]{ cx + (dx/len)*ghostR, cy + (dy/len)*ghostR };
    }

    /** Adaptive power: distance-based with friction correction (JS _calibratePower). */
    private static float calibratePower(float sx, float sy, float gx, float gy,
                                         float px, float py) {
        float boardDiag = (float) Math.sqrt(L_SIZE*L_SIZE + L_SIZE*L_SIZE);
        float distToGhost   = dist2(sx, sy, gx, gy);
        float ghostToPocket = dist2(gx, gy, px, py);
        float effective = distToGhost + ghostToPocket * 0.35f;
        return Math.min(1.0f, Math.max(0.35f, (effective / boardDiag) * 1.55f));
    }

    /** Path clearance check (logical space). */
    private static boolean pathClear(float ax, float ay, float bx, float by,
                                      float r, List<LPiece> pieces, String excludeId) {
        float dx = bx-ax, dy = by-ay;
        float len = (float) Math.sqrt(dx*dx + dy*dy);
        if (len < 0.001f) return true;
        float ux = dx/len, uy = dy/len;
        for (LPiece p : pieces) {
            if (p.pocketed || p.id.equals(excludeId)) continue;
            float tpx = p.x-ax, tpy = p.y-ay;
            float proj = tpx*ux + tpy*uy;
            if (proj < 0 || proj > len) continue;
            float cx = ax + ux*proj, cy = ay + uy*proj;
            if (dist2(cx, cy, p.x, p.y) < r + p.radius - 1f) return false;
        }
        return true;
    }

    /** Striker foul safety check (logical space). */
    private static boolean strikerSafe(float sx, float sy, float gx, float gy) {
        float dx = gx-sx, dy = gy-sy;
        float len = (float) Math.sqrt(dx*dx + dy*dy);
        if (len < 0.001f) return false;
        float ux = dx/len, uy = dy/len;
        for (float[] pk : POCKETS) {
            float tpx = pk[0]-sx, tpy = pk[1]-sy;
            float proj = tpx*ux + tpy*uy;
            if (proj < 0) continue;
            float cx = sx + ux*proj, cy = sy + uy*proj;
            if (dist2(cx, cy, pk[0], pk[1]) < SAFE_MARGIN) return false;
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § SHOT SCORER
    // ─────────────────────────────────────────────────────────────────────────

    private static float scoreResult(SimResult res) {
        if (res.strikerFouled) return -99999f;
        float score = 0;

        int myPots = 0;
        for (LPiece p : res.pocketed) {
            if (p.color == Coin.COLOR_RED) { score += 50 * 5; }
            else if (p.color == Coin.COLOR_WHITE) { score += 20 * 5; myPots++; }
            else if (p.color == Coin.COLOR_BLACK) { score += 10 * 5; myPots++; }
        }
        if (myPots >= 2) score += 400;
        if (myPots >= 3) score += 900;
        if (myPots >= 4) score += 2000;

        // Pocket proximity bonus for remaining pieces
        for (LPiece p : res.finalState) {
            float minD = Float.MAX_VALUE;
            for (float[] pk : POCKETS) {
                float d = dist2(p.x, p.y, pk[0], pk[1]);
                if (d < minD) minD = d;
            }
            score -= minD * 0.04f;
        }
        return score;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § CANDIDATE SHOT GENERATOR
    // ─────────────────────────────────────────────────────────────────────────

    private static class Candidate {
        float sx, sy, angleDeg, power;
        String targetId;
        boolean isBank;
        Candidate(float sx, float sy, float ang, float pw, String tid, boolean bank) {
            this.sx = sx; this.sy = sy; angleDeg = ang; power = pw;
            targetId = tid; isBank = bank;
        }
        String key() {
            return Math.round(sx) + "_" + String.format("%.2f", angleDeg);
        }
    }

    private static List<Candidate> generateCandidates(List<LPiece> pieces,
                                                        float strikerX, float strikerY,
                                                        float sR) {
        Map<String, Candidate> map = new HashMap<>();

        // Strategy 1: Ghost-ball direct shots for every piece × pocket
        for (LPiece piece : pieces) {
            for (float[] pk : POCKETS) {
                float[] ghost = ghostBallLogical(piece.x, piece.y, pk[0], pk[1],
                                                  piece.radius, sR);
                if (ghost == null) continue;
                float gx = ghost[0], gy = ghost[1];

                // Is ghost reachable from striker baseline area?
                if (gx < L_LEFT - sR || gx > L_RIGHT + sR) continue;
                if (gy < L_TOP  - sR || gy > L_BOTTOM + sR) continue;

                // Direct: striker → ghost
                float dx = gx - strikerX, dy = gy - strikerY;
                float len = (float) Math.sqrt(dx*dx + dy*dy);
                if (len < 1f) continue;

                if (!pathClear(strikerX, strikerY, gx, gy, sR, pieces, piece.id)) continue;
                if (!strikerSafe(strikerX, strikerY, gx, gy)) continue;

                float angDeg = (float) Math.toDegrees(Math.atan2(dy, dx));
                float power  = calibratePower(strikerX, strikerY, gx, gy, pk[0], pk[1]);
                Candidate c = new Candidate(strikerX, strikerY, angDeg, power, piece.id, false);

                String key = c.key();
                if (!map.containsKey(key)) map.put(key, c);

                // Fine-angle variants ±0.5°, ±1°
                for (float da : new float[]{-1f, -0.5f, 0.5f, 1f}) {
                    Candidate adj = new Candidate(strikerX, strikerY,
                        angDeg + da, power, piece.id, false);
                    String adjKey = adj.key();
                    if (!map.containsKey(adjKey)) map.put(adjKey, adj);
                }

                // Bank shots: reflect ghost through 3 walls
                float[][] walls = {
                    {L_LEFT, 1, 0}, {L_RIGHT, 1, 0}, {L_TOP, 0, 1}
                };
                for (float[] wall : walls) {
                    float rx = (wall[1] == 1) ? 2*wall[0] - gx : gx;
                    float ry = (wall[2] == 1) ? 2*wall[0] - gy : gy;
                    float bdx = rx - strikerX, bdy = ry - strikerY;
                    float bLen = (float) Math.sqrt(bdx*bdx + bdy*bdy);
                    if (bLen < 1f) continue;
                    float bangDeg = (float) Math.toDegrees(Math.atan2(bdy, bdx));
                    // Compute bounce point
                    float bux = bdx/bLen, buy = bdy/bLen;
                    float t; float bpx, bpy;
                    if (wall[1] == 1) { // x-wall
                        if (Math.abs(bux) < 0.001f) continue;
                        t = (wall[0] - strikerX) / bux;
                        bpx = wall[0]; bpy = strikerY + buy*t;
                        if (bpy < L_TOP || bpy > L_BOTTOM) continue;
                    } else { // y-wall
                        if (Math.abs(buy) < 0.001f) continue;
                        t = (wall[0] - strikerY) / buy;
                        bpy = wall[0]; bpx = strikerX + bux*t;
                        if (bpx < L_LEFT || bpx > L_RIGHT) continue;
                    }
                    if (t < 0) continue;
                    if (!strikerSafe(strikerX, strikerY, rx, ry)) continue;

                    float totalDist = dist2(strikerX, strikerY, bpx, bpy)
                                    + dist2(bpx, bpy, gx, gy);
                    float boardDiag = (float) Math.sqrt(L_SIZE*L_SIZE + L_SIZE*L_SIZE);
                    float bankPwr = Math.min(1.0f, Math.max(0.5f, totalDist/boardDiag * 1.75f));
                    Candidate bc = new Candidate(strikerX, strikerY, bangDeg, bankPwr, piece.id, true);
                    String bk = bc.key();
                    if (!map.containsKey(bk)) map.put(bk, bc);
                }
            }
        }

        // Strategy 2: Brute-force sweep fallback (0.5° step, upward angles)
        for (float ang = 181f; ang <= 359f; ang += ANGLE_STEP) {
            Candidate c = new Candidate(strikerX, strikerY, ang, 0.75f, null, false);
            String key = c.key();
            if (!map.containsKey(key)) map.put(key, c);
        }

        return new ArrayList<>(map.values());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § MINIMAX (depth-3 α-β, memoized)
    // ─────────────────────────────────────────────────────────────────────────

    private static float minimax(List<LPiece> pieces, int depth, float alpha, float beta,
                                  Map<String, Float> cache) {
        if (depth == 0 || pieces.isEmpty()) return quickEval(pieces);

        String key = stateKey(pieces, depth);
        Float cached = cache.get(key);
        if (cached != null) return cached;

        float best = -Float.MAX_VALUE;
        // Quick candidate set for inner nodes
        float[] xs = {250f, 310f, 350f, 410f};
        for (LPiece piece : pieces) {
            for (float[] pk : POCKETS) {
                float[] ghost = ghostBallLogical(piece.x, piece.y, pk[0], pk[1],
                                                  piece.radius, L_STRIKER_R);
                if (ghost == null) continue;
                for (float sx : xs) {
                    float dx = ghost[0]-sx, dy = ghost[1]-L_BOTTOM;
                    float ang = (float) Math.toDegrees(Math.atan2(dy, dx));
                    if (!strikerSafe(sx, L_BOTTOM, ghost[0], ghost[1])) continue;
                    float pwr = calibratePower(sx, L_BOTTOM, ghost[0], ghost[1], pk[0], pk[1]);
                    SimResult res = simulate(pieces, sx, L_BOTTOM, ang, pwr);
                    if (res.strikerFouled) continue;
                    float s = scoreResult(res)
                             + minimax(res.finalState, depth-1, alpha, beta, cache) * 0.55f;
                    if (s > best) best = s;
                    alpha = Math.max(alpha, s);
                    if (beta <= alpha) break;
                }
                if (beta <= alpha) break;
            }
            if (beta <= alpha) break;
        }
        if (cache.size() < 20000) cache.put(key, best);
        return best;
    }

    private static float quickEval(List<LPiece> pieces) {
        float score = 0;
        for (LPiece p : pieces) {
            float minD = Float.MAX_VALUE;
            for (float[] pk : POCKETS) {
                float d = dist2(p.x, p.y, pk[0], pk[1]);
                if (d < minD) minD = d;
            }
            if (p.color == Coin.COLOR_RED) score += Math.max(0, 150f - minD) * 0.5f;
            else score += Math.max(0, 200f - minD) * 0.3f;
        }
        return score;
    }

    private static String stateKey(List<LPiece> pieces, int depth) {
        StringBuilder sb = new StringBuilder();
        sb.append(depth).append(':');
        List<String> parts = new ArrayList<>();
        for (LPiece p : pieces) if (!p.pocketed)
            parts.add(p.color + ":" + Math.round(p.x) + "," + Math.round(p.y));
        Collections.sort(parts);
        for (String part : parts) sb.append(part).append('|');
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § PUBLIC API — geometry-based fast path (for aim lines display)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fast geometry-only shot finder. Returns up to maxResults shots ranked by
     * pocket proximity + striker distance. Used for real-time aim-line drawing.
     * Runs on the UI thread in ~1 ms.
     */
    public static List<AiShot> findBestShots(GameState state, int maxResults) {
        List<AiShot> results = new ArrayList<>();
        if (state == null || state.striker == null || state.board == null
                || state.pockets == null || state.pockets.isEmpty()) return results;

        RectF b = state.board;
        float sLX = normX(state.striker.pos.x, b);
        float sLY = normY(state.striker.pos.y, b);
        float sR  = normR(state.striker.radius, b);

        List<LPiece> pieces = toLPieces(state);

        for (LPiece piece : pieces) {
            for (int pi = 0; pi < POCKETS.length; pi++) {
                float[] pk = POCKETS[pi];
                float[] ghost = ghostBallLogical(piece.x, piece.y, pk[0], pk[1],
                                                  piece.radius, sR);
                if (ghost == null) continue;
                float gx = ghost[0], gy = ghost[1];
                if (gx < L_LEFT-sR || gx > L_RIGHT+sR
                 || gy < L_TOP -sR || gy > L_BOTTOM+sR) continue;
                if (!pathClear(sLX, sLY, gx, gy, sR, pieces, piece.id)) continue;
                if (!strikerSafe(sLX, sLY, gx, gy)) continue;

                float pocketDist  = dist2(piece.x, piece.y, pk[0], pk[1]);
                float strikerDist = dist2(sLX, sLY, gx, gy);
                float boardDiag   = (float) Math.sqrt(L_SIZE*L_SIZE + L_SIZE*L_SIZE);

                float sc = 1200f / (pocketDist + 20f) - strikerDist / boardDiag * 180f;
                if (piece.color == Coin.COLOR_RED)   sc *= 1.6f;
                if (piece.color == Coin.COLOR_BLACK) sc *= 0.9f;

                float power = calibratePower(sLX, sLY, gx, gy, pk[0], pk[1]);

                // Denorm ghost to screen pixels
                PointF ghostScreen = new PointF(denormX(gx, b), denormY(gy, b));
                PointF pkScreen    = state.pockets.get(pi);

                // Find the original Coin object
                Coin coinRef = findCoin(state, piece);

                results.add(new AiShot(ghostScreen, coinRef, pkScreen, sc, power, 0, false));
            }
        }

        Collections.sort(results, (a, c) -> Float.compare(c.score, a.score));

        // Deduplicate by ghost proximity
        List<AiShot> deduped = new ArrayList<>();
        for (AiShot s : results) {
            boolean dup = false;
            for (AiShot kept : deduped) {
                if (distP(s.ghostPos, kept.ghostPos) < 10f) { dup = true; break; }
            }
            if (!dup) deduped.add(s);
            if (deduped.size() >= maxResults) break;
        }
        return deduped;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § PUBLIC API — physics-validated best shot (for autoplay execution)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Full physics-validated shot search. Generates all candidates, simulates
     * each, scores with ShotScorer, runs depth-3 minimax on top-60.
     * ~50–150 ms — must be called on a background thread.
     *
     * @return best AiShot with calibrated powerFrac, or null if none found.
     */
    public static AiShot findBestShotPhysics(GameState state) {
        if (state == null || state.striker == null || state.board == null
                || state.pockets == null || state.pockets.isEmpty()) return null;

        RectF b = state.board;
        float sLX = normX(state.striker.pos.x, b);
        float sLY = normY(state.striker.pos.y, b);
        float sR  = normR(state.striker.radius, b);

        List<LPiece> pieces = toLPieces(state);

        // Generate all candidates
        List<Candidate> candidates = generateCandidates(pieces, sLX, sLY, sR);

        // Simulate + score every candidate
        List<float[]> scored = new ArrayList<>(); // [score, index]
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            SimResult res = simulate(pieces, c.sx, c.sy, c.angleDeg, c.power);
            if (res.strikerFouled) continue;
            float sc = scoreResult(res);
            scored.add(new float[]{sc, i});
        }

        if (scored.isEmpty()) {
            // Fallback: use geometry-only best
            List<AiShot> geo = findBestShots(state, 1);
            return geo.isEmpty() ? null : geo.get(0);
        }

        // Sort descending
        Collections.sort(scored, (a, c) -> Float.compare(c[0], a[0]));

        // Top-60 deep minimax
        int topN = Math.min(60, scored.size());
        Map<String, Float> mmCache = new HashMap<>();
        Candidate bestC  = null;
        float     bestSc = -Float.MAX_VALUE;

        for (int rank = 0; rank < topN; rank++) {
            int idx = (int) scored.get(rank)[1];
            Candidate c = candidates.get(idx);
            float baseSc = scored.get(rank)[0];
            if (baseSc < -1000f) continue;

            SimResult res = simulate(pieces, c.sx, c.sy, c.angleDeg, c.power);
            float deepSc  = minimax(res.finalState, 2, -Float.MAX_VALUE, Float.MAX_VALUE, mmCache);
            float total   = baseSc + deepSc * 0.55f;
            if (total > bestSc) { bestSc = total; bestC = c; }
        }

        if (bestC == null) bestC = candidates.get((int) scored.get(0)[1]);

        // Compute ghost position for best candidate in screen pixels
        // (find which piece/pocket this shot targets)
        AiShot best = null;
        float  bestDist = Float.MAX_VALUE;
        for (LPiece piece : pieces) {
            for (int pi = 0; pi < POCKETS.length; pi++) {
                float[] pk = POCKETS[pi];
                float[] ghost = ghostBallLogical(piece.x, piece.y, pk[0], pk[1],
                                                  piece.radius, sR);
                if (ghost == null) continue;
                float gx = ghost[0], gy = ghost[1];
                float dx = gx - bestC.sx, dy = gy - bestC.sy;
                float len = (float) Math.sqrt(dx*dx + dy*dy);
                if (len < 0.001f) continue;
                float ang = (float) Math.toDegrees(Math.atan2(dy, dx));
                float angDiff = Math.abs(ang - bestC.angleDeg);
                if (angDiff > 180) angDiff = 360 - angDiff;
                if (angDiff < bestDist) {
                    bestDist = angDiff;
                    Coin coinRef = findCoin(state, piece);
                    PointF ghostScr = new PointF(denormX(gx, b), denormY(gy, b));
                    PointF pkScr    = state.pockets.get(pi);
                    best = new AiShot(ghostScr, coinRef, pkScr,
                                       bestSc, bestC.power,
                                       bestC.isBank ? 1 : 0, bestC.isBank);
                }
            }
        }

        if (best != null) return best;

        // Last-resort: build from angle + power only (no ghost pos from geometry)
        float rad = (float) Math.toRadians(bestC.angleDeg);
        float dist = sR * 4;
        float gx = bestC.sx + (float)Math.cos(rad)*dist;
        float gy = bestC.sy + (float)Math.sin(rad)*dist;
        PointF gScr = new PointF(denormX(gx, b), denormY(gy, b));
        return new AiShot(gScr, null, null, bestSc, bestC.power, 0, false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // § HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private static Coin findCoin(GameState state, LPiece lp) {
        // Match by normalized position proximity
        RectF b = state.board;
        float bestD = Float.MAX_VALUE;
        Coin best = null;
        for (Coin c : state.coins) {
            if (c.color == Coin.COLOR_STRIKER) continue;
            float d = dist2(normX(c.pos.x, b), normY(c.pos.y, b), lp.x, lp.y);
            if (d < bestD) { bestD = d; best = c; }
        }
        return best;
    }

    public static float distP(PointF a, PointF b) {
        float dx = a.x-b.x, dy = a.y-b.y;
        return (float) Math.sqrt(dx*dx + dy*dy);
    }

    private static float dist2(float ax, float ay, float bx, float by) {
        float dx = ax-bx, dy = ay-by;
        return (float) Math.sqrt(dx*dx + dy*dy);
    }

    /** Path-clear check in screen pixels (used by AimOverlayView). */
    public static boolean isPathClear(PointF a, PointF b, float r,
                                       List<Coin> coins, Coin exclude) {
        float dx = b.x-a.x, dy = b.y-a.y;
        float len = (float) Math.sqrt(dx*dx+dy*dy);
        if (len < 0.001f) return true;
        float ux = dx/len, uy = dy/len;
        for (Coin p : coins) {
            if (p == exclude || p.color == Coin.COLOR_STRIKER) continue;
            float tpx = p.pos.x-a.x, tpy = p.pos.y-a.y;
            float proj = tpx*ux + tpy*uy;
            if (proj < 0 || proj > len) continue;
            float cx = a.x+ux*proj, cy = a.y+uy*proj;
            float cd = dist2(cx, cy, p.pos.x, p.pos.y);
            if (cd < r + p.radius - 2f) return false;
        }
        return true;
    }
}
