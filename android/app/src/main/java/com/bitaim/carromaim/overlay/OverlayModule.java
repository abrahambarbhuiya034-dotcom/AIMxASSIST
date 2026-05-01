package com.bitaim.carromaim.overlay;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import com.bitaim.carromaim.auto.AutoShootService;
import com.bitaim.carromaim.capture.MediaProjectionRequestActivity;
import com.bitaim.carromaim.capture.ScreenCaptureService;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * OverlayModule — React Native bridge for overlay + screen capture + auto-shoot.
 *
 * v5 additions:
 *  - setAutoPlay(boolean)  — enable/disable auto-shoot
 *  - isAutoPlayEnabled()   — query current state
 *  - isAccessibilityReady()— whether AutoShootService is connected
 *  - requestAccessibilityPermission() — deep-link to Accessibility Settings
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
        } else p.resolve(true);
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
        } catch (Exception e) { p.reject("ERR_START", e.getMessage()); }
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
        } catch (Exception e) { p.reject("ERR_STOP", e.getMessage()); }
    }

    @ReactMethod
    public void requestScreenCapture(Promise p) {
        try {
            Intent i = new Intent(getReactApplicationContext(), MediaProjectionRequestActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getReactApplicationContext().startActivity(i);
            p.resolve(true);
        } catch (Exception e) { p.reject("ERR_CAPTURE", e.getMessage()); }
    }

    @ReactMethod
    public void stopScreenCapture(Promise p) {
        try {
            Intent c = new Intent(getReactApplicationContext(), ScreenCaptureService.class);
            getReactApplicationContext().stopService(c);
            p.resolve(true);
        } catch (Exception e) { p.reject("ERR_STOP_CAPTURE", e.getMessage()); }
    }

    @ReactMethod
    public void isAutoDetectActive(Promise p) {
        p.resolve(ScreenCaptureService.INSTANCE != null);
    }

    // ── Tunables ─────────────────────────────────────────────────────────────

    @ReactMethod public void setShotMode(String m) {
        FloatingOverlayService s = FloatingOverlayService.INSTANCE;
        if (s != null) s.setShotMode(m);
    }
    @ReactMethod public void setMarginOffset(float dx, float dy) {
        FloatingOverlayService s = FloatingOverlayService.INSTANCE;
        if (s != null) s.setMarginOffset(dx, dy);
    }
    @ReactMethod public void setSensitivity(float v) {
        FloatingOverlayService s = FloatingOverlayService.INSTANCE;
        if (s != null) s.setSensitivity(v);
    }
    @ReactMethod public void setDetectionRadius(float minFrac, float maxFrac) {
        ScreenCaptureService c = ScreenCaptureService.INSTANCE;
        if (c != null) { c.setMinRadius(minFrac); c.setMaxRadius(maxFrac); }
    }
    @ReactMethod public void setDetectionThreshold(double v) {
        ScreenCaptureService c = ScreenCaptureService.INSTANCE;
        if (c != null) c.setDetectionParam(v);
    }

    // ── AutoPlay ──────────────────────────────────────────────────────────────

    /**
     * Enable or disable the auto-shoot feature.
     * Requires the Accessibility Service to be enabled first.
     */
    @ReactMethod
    public void setAutoPlay(boolean enabled, Promise p) {
        FloatingOverlayService svc = FloatingOverlayService.INSTANCE;
        if (svc == null) { p.reject("ERR_NO_SERVICE", "Overlay not started"); return; }
        if (enabled && !AutoShootService.isReady()) {
            p.reject("ERR_NO_ACCESSIBILITY",
                    "Enable AIMxASSIST in Settings → Accessibility first");
            return;
        }
        svc.setAutoPlay(enabled);
        p.resolve(enabled);
    }

    /** Returns true if auto-play is currently ON. */
    @ReactMethod
    public void isAutoPlayEnabled(Promise p) {
        FloatingOverlayService svc = FloatingOverlayService.INSTANCE;
        // Read the field via a simple null-safe pattern
        if (svc == null) { p.resolve(false); return; }
        // We expose autoPlayEnabled via a new getter below
        p.resolve(svc.isAutoPlayEnabled());
    }

    /**
     * Returns true if the AutoShootService Accessibility service is connected
     * and ready to dispatch gestures.
     */
    @ReactMethod
    public void isAccessibilityReady(Promise p) {
        p.resolve(AutoShootService.isReady());
    }

    /**
     * Deep-link to the Accessibility Settings page so the user can
     * enable "AIMxASSIST" → AIMxASSIST Autoplay.
     */
    @ReactMethod
    public void requestAccessibilityPermission() {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getReactApplicationContext().startActivity(i);
    }
}
