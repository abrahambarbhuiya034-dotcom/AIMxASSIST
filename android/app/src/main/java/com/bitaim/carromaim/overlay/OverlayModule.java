package com.bitaim.carromaim.overlay;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.bitaim.carromaim.capture.MediaProjectionRequestActivity;
import com.bitaim.carromaim.capture.ScreenCaptureService;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;

import androidx.annotation.NonNull;

/**
 * OverlayModule — React Native bridge for overlay + screen capture controls.
 */
public class OverlayModule extends ReactContextBaseJavaModule {

    public OverlayModule(ReactApplicationContext ctx) { super(ctx); }

    @NonNull @Override
    public String getName() { return "OverlayModule"; }

    /** Check SYSTEM_ALERT_WINDOW (draw over apps). */
    @ReactMethod
    public void canDrawOverlays(Promise p) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            p.resolve(Settings.canDrawOverlays(getReactApplicationContext()));
        } else p.resolve(true);
    }

    /** Open the system overlay-permission settings page. */
    @ReactMethod
    public void requestOverlayPermission() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getReactApplicationContext().getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getReactApplicationContext().startActivity(i);
    }

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
            // Also stop screen capture since auto-detect makes no sense without overlay
            Intent c = new Intent(getReactApplicationContext(), ScreenCaptureService.class);
            getReactApplicationContext().stopService(c);
            p.resolve(true);
        } catch (Exception e) { p.reject("ERR_STOP", e.getMessage()); }
    }

    /** Launch the system MediaProjection consent dialog (per-session permission). */
    @ReactMethod
    public void requestScreenCapture(Promise p) {
        try {
            Intent i = new Intent(getReactApplicationContext(), MediaProjectionRequestActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getReactApplicationContext().startActivity(i);
            p.resolve(true);
        } catch (Exception e) { p.reject("ERR_CAPTURE", e.getMessage()); }
    }

    /** Stop the auto-detect screen capture loop (overlay keeps running). */
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

    // ── Tunables forwarded to live overlay/detector ──────────────────────────
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
}
