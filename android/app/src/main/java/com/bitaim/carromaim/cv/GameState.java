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
    public RectF board;
    public Coin striker;
    public List<Coin> coins = new ArrayList<>();
    public List<PointF> pockets = new ArrayList<>();
    public long timestampMs;

    public GameState() {
        this.timestampMs = System.currentTimeMillis();
    }

    public boolean isValid() {
        return board != null && striker != null;
    }
}
