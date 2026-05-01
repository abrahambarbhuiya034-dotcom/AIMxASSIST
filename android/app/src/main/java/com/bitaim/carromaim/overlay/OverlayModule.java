package com.bitaim.carromaim.overlay;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.bitaim.carromaim.auto.AutoShootService;
import com.bitaim.carromaim.capture.MediaProjectionRequestActivity;
import com.bitaim.carromaim.capture.ScreenCaptureService;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;

import androidx.annotation.NonNull;

/**
 * OverlayModule — React Native bridge for overlay + screen capture + auto-shoot.
 *
 * Fixed vs previous:
 *  - shootNow()        — was called from App.tsx but didn't exist in Java
 *  - setAutoPlayDelay() — was called from App.tsx but didn't exist in Java
 */
public class OverlayModule extends ReactContextBaseJavaModule {

    public OverlayModule(ReactApplicationContext ctx) { super(ctx); }

    @NonNull @Override
    public String getName() { return "OverlayModule"; }

    // ── Overlay permission ────────────────────────────────────────────────────

    @ReactMethod
    public void canDrawOverlays(Promise p) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            p.resolve(Settings.canDrawOverlays(getReactApplicationContext()));
        } else {
            p.resolve(true);
        }
    }

    @ReactMethod
    public void requestOverlayPermission() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getReactApplicationContext().getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getReactApplicationContext().startActivity(i);
    }

    // ── Overlay service ───────────────────────────────────────────────────────

    @ReactMethod
    public void startOverlay(Promise p) {
        try {
            Intent i = new Intent(getReactApplicationContext(), FloatingOverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getReactApplicationContext().startForegroundService(i);
            } else {
                getReactApplicationContext().startService(i);
            }
            p.resolve(true);
        } catch (Exception e) {
            p.reject("ERR_START", e.getMessage());
        }
    }

    @ReactMethod
    public void stopOverlay(Promise p) {
        try {
            Intent i = new Intent(getReactApplicationContext(), FloatingOverlayService.class);
            i.setAction("ACTION_STOP");
            getReactApplicationContext().startService(i);
            Intent c = new Intent(getReactApplicationContext(), ScreenCaptureService.class);
            getReactApplicationContext().stopService(c);
            p.resolve(true);
        } catch (Exception e) {
            p.reject("ERR_STOP", e.getMessage());
        }
    }

    @ReactMethod
    public void requestScreenCapture(Promise p) {
        try {
            Intent i = new Intent(getReactApplicationContext(), MediaProjectionRequestActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getReactApplicationContext().startActivity(i);
            p.resolve(true);
        } catch (Exception e) {
            p.reject("ERR_CAPTURE", e.getMessage());
        }
    }

    @ReactMethod
    public void stopScreenCapture(Promise p) {
        try {
            Intent c = new Intent(getReactApplicationContext(), ScreenCaptureService.class);
            getReactApplicationContext().stopService(c);
            p.resolve(true);
        } catch (Exception e) {
            p.reject("ERR_STOP_CAPTURE", e.getMessage());
        }
    }

    @ReactMethod
    public void isAutoDetectActive(Promise p) {
        p.resolve(ScreenCaptureService.INSTANCE != null);
    }

    // ── Tunables ──────────────────────────────────────────────────────────────

    @ReactMethod
    public void setShotMode(String m) {
        FloatingOverlayService s = FloatingOverlayService.INSTANCE;
        if (s != null) s.setShotMode(m);
    }

    @ReactMethod
    public void setMarginOffset(float dx, float dy) {
        FloatingOverlayService s = FloatingOverlayService.INSTANCE;
        if (s != null) s.setMarginOffset(dx, dy);
    }

    @ReactMethod
    public void setSensitivity(float v) {
        FloatingOverlayService s = FloatingOverlayService.INSTANCE;
        if (s != null) s.setSensitivity(v);
    }

    @ReactMethod
    public void setDetectionRadius(float minFrac, float maxFrac) {
        ScreenCaptureService c = ScreenCaptureService.INSTANCE;
        if (c != null) { c.setMinRadius(minFrac); c.setMaxRadius(maxFrac); }
    }

    @ReactMethod
    public void setDetectionThreshold(double v) {
        ScreenCaptureService c = ScreenCaptureService.INSTANCE;
        if (c != null) c.setDetectionParam(v);
    }

    // ── AutoPlay ──────────────────────────────────────────────────────────────

    @ReactMethod
    public void setAutoPlay(boolean enabled, Promise p) {
        FloatingOverlayService svc = FloatingOverlayService.INSTANCE;
        if (svc == null) {
            p.reject("ERR_NO_SERVICE", "Overlay not started — start it first");
            return;
        }
        if (enabled && !AutoShootService.isReady()) {
            p.reject("ERR_NO_ACCESSIBILITY",
                    "Enable AIMxASSIST in Settings → Accessibility first");
            return;
        }
        svc.setAutoPlay(enabled);
        p.resolve(enabled);
    }

    @ReactMethod
    public void isAutoPlayEnabled(Promise p) {
        FloatingOverlayService svc = FloatingOverlayService.INSTANCE;
        p.resolve(svc != null && svc.isAutoPlayEnabled());
    }

    /**
     * FIX: was called from App.tsx handleAutoPlayDelayChange() but didn't exist.
     * Sets the milliseconds between auto-shots (min 500 ms).
     */
    @ReactMethod
    public void setAutoPlayDelay(int ms) {
        FloatingOverlayService svc = FloatingOverlayService.INSTANCE;
        if (svc != null) svc.setAutoPlayDelay(ms);
    }

    /**
     * FIX: was called from App.tsx shootNow() but didn't exist.
     * Fires the best cached shot immediately via AccessibilityService gesture.
     */
    @ReactMethod
    public void shootNow(Promise p) {
        FloatingOverlayService svc = FloatingOverlayService.INSTANCE;
        if (svc == null) {
            p.reject("ERR_NO_SERVICE", "Overlay not started");
            return;
        }
        if (!AutoShootService.isReady()) {
            p.reject("ERR_NO_ACCESSIBILITY", "Enable AIMxASSIST in Accessibility Settings first");
            return;
        }
        svc.shootNow();
        p.resolve(true);
    }

    @ReactMethod
    public void isAccessibilityReady(Promise p) {
        p.resolve(AutoShootService.isReady());
    }

    @ReactMethod
    public void requestAccessibilityPermission() {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getReactApplicationContext().startActivity(i);
    }
}
