package com.bitaim.carromaim.cv;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of the detected board state at a single frame.
 * Produced by BoardDetector, consumed by AimOverlayView and TrajectorySimulator.
 */
public class GameState {
    public RectF board;            // detected board bounding box (screen coords)
    public Coin striker;           // detected striker (null if none found)
    public List<Coin> coins = new ArrayList<>();   // all non-striker coins
    public List<PointF> pockets = new ArrayList<>(); // pocket centers (screen coords)
    public long timestampMs;

    public GameState() {
        this.timestampMs = System.currentTimeMillis();
    }

    public boolean isValid() {
        return board != null && striker != null;
    }
}
