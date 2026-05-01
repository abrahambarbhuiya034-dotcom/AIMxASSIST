package com.bitaim.carromaim.auto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * AutoShootService — Accessibility Service that injects swipe gestures
 * to automatically shoot the striker in Carrom Disc Pool.
 *
 * How it works:
 *  1. FloatingOverlayService detects a stable board state (15+ consecutive
 *     frames with no movement) and calls shoot().
 *  2. shoot() dispatches a swipe gesture starting at the striker position
 *     and moving in the direction of the best shot target.
 *  3. The gesture simulates a human finger flick — identical to a real shot.
 *
 * Setup (one-time, no root needed):
 *  - User enables "AIMxASSIST" in Settings → Accessibility → Installed Services
 *  - The app guides the user there via requestAccessibility() deep link.
 */
public class AutoShootService extends AccessibilityService {

    private static final String TAG = "AutoShootService";

    /** Singleton — set on connection, cleared on destroy. */
    public static volatile AutoShootService INSTANCE;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        Log.i(TAG, "AutoShootService connected — gesture injection ready");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // We don't need to observe events — only inject gestures.
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "AutoShootService interrupted");
    }

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
     * @param targetX   ghost-ball / aim target X on screen
     * @param targetY   ghost-ball / aim target Y on screen
     * @param powerFrac shot power 0.0 (soft) … 1.0 (hard), typically 0.7
     */
    public void shoot(float strikerX, float strikerY,
                      float targetX,  float targetY,
                      float powerFrac) {
        // Direction from striker toward target
        float dx = targetX - strikerX;
        float dy = targetY - strikerY;
        float len = (float) Math.sqrt(dx*dx + dy*dy);
        if (len < 1f) return;
        float nx = dx / len;
        float ny = dy / len;

        // Always shoot at FULL power — longest swipe, fastest flick.
        // swipeDist: 600 px ensures the game registers maximum force.
        // Pull back 40 px first so the game detects the full flick travel.
        float swipeDist = 600f;

        float fromX = strikerX - nx * 40f;   // slight pull-back start
        float fromY = strikerY - ny * 40f;
        float toX   = strikerX + nx * swipeDist;
        float toY   = strikerY + ny * swipeDist;

        Path path = new Path();
        path.moveTo(fromX, fromY);
        path.lineTo(toX, toY);

        // 40 ms — hardest possible flick the game can register
        long durationMs = 40L;

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

        if (!ok) Log.e(TAG, "dispatchGesture returned false — service may not have gesture permission");
    }

    /** @return true if this service is connected and ready. */
    public static boolean isReady() { return INSTANCE != null; }
}
