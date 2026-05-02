package com.bitaim.carromaim.overlay;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.bitaim.carromaim.auto.AutoShootService;
import com.bitaim.carromaim.capture.MediaProjectionRequestActivity;
import com.bitaim.carromaim.capture.ScreenCaptureService;

/**
 * OverlayModule — pure Android helper (no React Native bridge).
 * Called directly from MainActivity or any Activity context.
 */
public class OverlayModule {

    private final Context ctx;

    public OverlayModule(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(ctx);
    }

    public void requestOverlayPermission() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + ctx.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    public boolean startOverlay() {
        try {
            Intent i = new Intent(ctx, FloatingOverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
            return true;
        } catch (Exception e) { return false; }
    }

    public boolean stopOverlay() {
        try {
            Intent i = new Intent(ctx, FloatingOverlayService.class);
            i.setAction("ACTION_STOP");
            ctx.startService(i);
            ctx.stopService(new Intent(ctx, ScreenCaptureService.class));
            return true;
        } catch (Exception e) { return false; }
    }

    public boolean requestScreenCapture() {
        try {
            Intent i = new Intent(ctx, MediaProjectionRequestActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) { return false; }
    }

    public boolean stopScreenCapture() {
        try {
            ctx.stopService(new Intent(ctx, ScreenCaptureService.class));
            return true;
        } catch (Exception e) { return false; }
    }

    public boolean isAutoDetectActive() { return ScreenCaptureService.INSTANCE != null; }

    public void setShotMode(String m) {
        FloatingOverlayService s = FloatingOverlayService.INSTANCE;
        if (s != null) s.setShotMode(m);
    }

    public void setSensitivity(float v) {
        FloatingOverlayService s = FloatingOverlayService.INSTANCE;
        if (s != null) s.setSensitivity(v);
    }

    public void setDetectionRadius(float minFrac, float maxFrac) {
        ScreenCaptureService c = ScreenCaptureService.INSTANCE;
        if (c != null) { c.setMinRadius(minFrac); c.setMaxRadius(maxFrac); }
    }

    public void setDetectionParam(double v) {
        ScreenCaptureService c = ScreenCaptureService.INSTANCE;
        if (c != null) c.setDetectionParam(v);
    }

    public boolean setAutoPlay(boolean enabled) {
        FloatingOverlayService svc = FloatingOverlayService.INSTANCE;
        if (svc == null) return false;
        if (enabled && !AutoShootService.isReady()) return false;
        svc.setAutoPlay(enabled);
        return true;
    }

    public boolean isAutoPlayEnabled() {
        FloatingOverlayService svc = FloatingOverlayService.INSTANCE;
        return svc != null && svc.isAutoPlayEnabled();
    }

    public void setAutoPlayDelay(int ms) {
        FloatingOverlayService svc = FloatingOverlayService.INSTANCE;
        if (svc != null) svc.setAutoPlayDelay(ms);
    }

    public boolean shootNow() {
        FloatingOverlayService svc = FloatingOverlayService.INSTANCE;
        if (svc == null || !AutoShootService.isReady()) return false;
        svc.shootNow();
        return true;
    }

    public boolean isAccessibilityReady() { return AutoShootService.isReady(); }

    public void requestAccessibilityPermission() {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
}
