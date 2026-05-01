package com.bitaim.carromaim.cv;

import android.graphics.PointF;

/**
 * Detected coin on the carrom board.
 * Color: 0 = white, 1 = black, 2 = red (queen), 3 = striker (white, larger).
 */
public class Coin {
    public PointF pos;
    public float radius;
    public int color;
    public boolean isStriker;

    public Coin(float x, float y, float r, int color, boolean isStriker) {
        this.pos = new PointF(x, y);
        this.radius = r;
        this.color = color;
        this.isStriker = isStriker;
    }

    public static final int COLOR_WHITE = 0;
    public static final int COLOR_BLACK = 1;
    public static final int COLOR_RED   = 2;
    public static final int COLOR_STRIKER = 3;
}
