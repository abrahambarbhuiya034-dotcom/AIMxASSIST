package com.bitaim.carromaim.auto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * AutoShootService — Accessibility Service for gesture injection.
 *
 * Injects swipe gestures to automatically shoot the striker in Carrom Disc Pool.
 * No root required — uses Android Accessibility API dispatchGesture().
 *
 * Setup: user enables "AIMxASSIST" in Settings → Accessibility → Installed Services.
 */
public class AutoShootService extends AccessibilityService {

    private static final String TAG = "AutoShootService";

    public static volatile AutoShootService INSTANCE;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        Log.i(TAG, "AutoShootService connected — gesture injection ready");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() { Log.w(TAG, "AutoShootService interrupted"); }

    @Override
    public void onDestroy() {
        super.onDestroy();
        INSTANCE = null;
        Log.i(TAG, "AutoShootService destroyed");
    }

    /**
     * Inject a swipe gesture to shoot the striker.
     *
     * @param strikerX  striker centre X on screen (pixels)
     * @param strikerY  striker centre Y on screen (pixels)
     * @param targetX   ghost-ball aim target X
     * @param targetY   ghost-ball aim target Y
     * @param powerFrac shot power 0.0 (soft) to 1.0 (hard)
     */
    public void shoot(float strikerX, float strikerY,
                      float targetX,  float targetY,
                      float powerFrac) {
        float dx = targetX - strikerX, dy = targetY - strikerY;
        float len = (float) Math.sqrt(dx*dx + dy*dy);
        if (len < 1f) return;
        float nx = dx/len, ny = dy/len;

        // Adaptive swipe distance: 120 px (soft) to 360 px (hard)
        float swipeDist = 120f + powerFrac * 240f;

        Path path = new Path();
        path.moveTo(strikerX, strikerY);
        path.lineTo(strikerX + nx * swipeDist, strikerY + ny * swipeDist);

        long durationMs = (long)(120 - powerFrac * 60); // 60–120 ms flick

        GestureDescription.Builder gb = new GestureDescription.Builder();
        gb.addStroke(new GestureDescription.StrokeDescription(path, 0L, durationMs));

        boolean ok = dispatchGesture(gb.build(), new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                Log.d(TAG, "Gesture completed");
            }
            @Override public void onCancelled(GestureDescription g) {
                Log.w(TAG, "Gesture cancelled");
            }
        }, null);

        if (!ok) Log.e(TAG, "dispatchGesture=false — check gesture permission in accessibility config");
    }

    public static boolean isReady() { return INSTANCE != null; }
}
