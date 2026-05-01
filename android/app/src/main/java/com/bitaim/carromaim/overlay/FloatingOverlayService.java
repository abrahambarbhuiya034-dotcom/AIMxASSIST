package com.bitaim.carromaim.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.bitaim.carromaim.MainActivity;
import com.bitaim.carromaim.R;
import com.bitaim.carromaim.auto.AutoShootService;
import com.bitaim.carromaim.cv.GameState;

/**
 * FloatingOverlayService — v5 (autoplay edition)
 *
 * Changes vs v4:
 *  - AutoPlay: when enabled, watches for N consecutive stable frames
 *    (board has stopped moving), then fires an auto-shoot gesture via
 *    AutoShootService.  No root needed — uses Android Accessibility.
 *  - Stability counter resets whenever the striker moves > STABLE_THRESH px.
 *  - A 3-second cooldown prevents double-shooting.
 *  - setAutoPlay(boolean) exposed to React Native via OverlayModule.
 */
public class FloatingOverlayService extends Service {

    private static final String TAG        = "FloatingOverlayService";
    private static final String CHANNEL_ID = "aimxassist_channel";
    private static final int    NOTIF_ID   = 1001;

    // AutoPlay tuning
    private static final int   STABLE_FRAMES_NEEDED = 18;   // ~0.6 s at 30 fps
    private static final float STABLE_THRESH_PX     = 12f;  // px movement to reset counter
    private static final long  SHOOT_COOLDOWN_MS    = 3500; // ms between shots

    public static volatile FloatingOverlayService INSTANCE;

    private WindowManager  windowManager;
    private View           floatingBtnView;
    private AimOverlayView aimOverlayView;
    private View           popupView;

    private WindowManager.LayoutParams floatingBtnParams;
    private WindowManager.LayoutParams overlayParams;
    private WindowManager.LayoutParams popupParams;

    private float   touchStartX, touchStartY;
    private int     viewStartX,  viewStartY;
    private boolean overlayVisible = false;
    private boolean popupShowing   = false;

    // AutoPlay state
    private volatile boolean autoPlayEnabled    = false;
    private int              stableFrames        = 0;
    private float            lastStrikerX        = Float.NaN;
    private float            lastStrikerY        = Float.NaN;
    private long             lastShootTimeMs     = 0L;
    private boolean          cooldownActive      = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float dp;

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        dp = getResources().getDisplayMetrics().density;
        createNotificationChannel();
        Notification notif = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, notif);
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        setupFloatingButton();
        setupAimOverlay();
    }

    // ── Floating button ───────────────────────────────────────────────────────

    private void setupFloatingButton() {
        floatingBtnView = LayoutInflater.from(this)
                .inflate(R.layout.view_floating_button, null);

        int type = overlayType();
        floatingBtnParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        floatingBtnParams.gravity = Gravity.TOP | Gravity.START;
        floatingBtnParams.x = 50;
        floatingBtnParams.y = 300;

        floatingBtnView.setOnTouchListener(new View.OnTouchListener() {
            boolean wasDrag;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchStartX = e.getRawX(); touchStartY = e.getRawY();
                        viewStartX = floatingBtnParams.x; viewStartY = floatingBtnParams.y;
                        wasDrag = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - touchStartX;
                        float dy = e.getRawY() - touchStartY;
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) wasDrag = true;
                        floatingBtnParams.x = (int)(viewStartX + dx);
                        floatingBtnParams.y = (int)(viewStartY + dy);
                        windowManager.updateViewLayout(floatingBtnView, floatingBtnParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!wasDrag) {
                            if (popupShowing) dismissPopup();
                            else showTogglePopup();
                        }
                        return true;
                }
                return false;
            }
        });
        windowManager.addView(floatingBtnView, floatingBtnParams);
    }

    // ── Toggle popup ──────────────────────────────────────────────────────────

    private void showTogglePopup() {
        if (popupShowing) return;
        popupShowing = true;

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackgroundColor(0xDD111111);
        int pad = (int)(12 * dp);
        ll.setPadding(pad, pad, pad, pad);

        // Title
        TextView title = new TextView(this);
        title.setText("AIMxASSIST");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#FFD700"));
        title.setTextSize(13);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        ll.addView(title);

        // Aim overlay toggle
        TextView btnAim = new TextView(this);
        btnAim.setText(overlayVisible ? "⬛  Lines OFF" : "▶  Lines ON");
        btnAim.setTextColor(overlayVisible ? 0xFFFF6B6B : 0xFF22C55E);
        btnAim.setTextSize(15);
        btnAim.setTypeface(Typeface.DEFAULT_BOLD);
        btnAim.setGravity(Gravity.CENTER_HORIZONTAL);
        btnAim.setPadding(pad*2, pad, pad*2, pad);
        btnAim.setOnClickListener(vv -> { toggleAimOverlay(); dismissPopup(); });
        ll.addView(btnAim);

        // AutoPlay toggle
        TextView btnAuto = new TextView(this);
        btnAuto.setText(autoPlayEnabled ? "🤖  AutoPlay OFF" : "🤖  AutoPlay ON");
        btnAuto.setTextColor(autoPlayEnabled ? 0xFFFF6B6B : 0xFF6B99FF);
        btnAuto.setTextSize(15);
        btnAuto.setTypeface(Typeface.DEFAULT_BOLD);
        btnAuto.setGravity(Gravity.CENTER_HORIZONTAL);
        btnAuto.setPadding(pad*2, pad, pad*2, pad);
        btnAuto.setOnClickListener(vv -> {
            setAutoPlay(!autoPlayEnabled);
            dismissPopup();
        });
        ll.addView(btnAuto);

        // AutoPlay status hint
        if (autoPlayEnabled && !AutoShootService.isReady()) {
            TextView hint = new TextView(this);
            hint.setText("⚠ Enable Accessibility\nin Settings first!");
            hint.setTextColor(0xFFFF8A00);
            hint.setTextSize(11);
            hint.setGravity(Gravity.CENTER_HORIZONTAL);
            hint.setPadding(pad, pad/2, pad, 0);
            ll.addView(hint);
        }

        popupView = ll;
        popupParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        popupParams.gravity = Gravity.TOP | Gravity.START;
        popupParams.x = floatingBtnParams.x + (int)(60 * dp);
        popupParams.y = floatingBtnParams.y;
        popupView.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE) dismissPopup();
            return false;
        });
        windowManager.addView(popupView, popupParams);
        handler.postDelayed(this::dismissPopup, 5000);
    }

    private void dismissPopup() {
        if (!popupShowing) return;
        popupShowing = false;
        handler.removeCallbacksAndMessages(null);
        try { if (popupView != null) windowManager.removeView(popupView); } catch (Exception ignored) {}
        popupView = null;
    }

    public void toggleAimOverlay() {
        overlayVisible = !overlayVisible;
        aimOverlayView.setVisibility(overlayVisible ? View.VISIBLE : View.GONE);
        ImageView icon = floatingBtnView.findViewById(R.id.floating_icon);
        if (icon != null) icon.setAlpha(overlayVisible ? 1.0f : 0.5f);
    }

    // ── Aim overlay — fully pass-through ─────────────────────────────────────

    private void setupAimOverlay() {
        aimOverlayView = new AimOverlayView(this);
        overlayParams  = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        aimOverlayView.setVisibility(View.GONE);
        windowManager.addView(aimOverlayView, overlayParams);
    }

    // ── External API ──────────────────────────────────────────────────────────

    public void setShotMode(String mode)            { if (aimOverlayView != null) aimOverlayView.setShotMode(mode); }
    public void setMarginOffset(float dx, float dy) { }
    public void setSensitivity(float value)         { }

    /** Called by ScreenCaptureService on each new captured frame / detected state. */
    public void onDetectedState(GameState s) {
        if (aimOverlayView != null) aimOverlayView.setDetectedState(s);
        if (autoPlayEnabled && s != null && s.striker != null) {
            handleAutoPlay(s);
        }
    }

    /** Toggle AutoPlay from React Native UI or the floating popup. */
    public void setAutoPlay(boolean enabled) {
        autoPlayEnabled = enabled;
        stableFrames    = 0;
        Log.i(TAG, "AutoPlay " + (enabled ? "ON" : "OFF"));
    }

    public boolean isAutoPlayEnabled() { return autoPlayEnabled; }

    // ── AutoPlay logic ────────────────────────────────────────────────────────

    private void handleAutoPlay(GameState s) {
        // Must have accessibility service connected
        if (!AutoShootService.isReady()) return;

        // Cooldown: don't shoot if we just shot recently
        long now = System.currentTimeMillis();
        if (cooldownActive) {
            if (now - lastShootTimeMs < SHOOT_COOLDOWN_MS) return;
            cooldownActive = false;
        }

        float sx = s.striker.pos.x;
        float sy = s.striker.pos.y;

        // Check if striker has moved since last frame
        if (!Float.isNaN(lastStrikerX)) {
            float moved = (float) Math.sqrt((sx - lastStrikerX)*(sx - lastStrikerX)
                                          + (sy - lastStrikerY)*(sy - lastStrikerY));
            if (moved > STABLE_THRESH_PX) {
                // Striker is still moving — board is not ready
                stableFrames = 0;
            } else {
                stableFrames++;
            }
        }
        lastStrikerX = sx;
        lastStrikerY = sy;

        if (stableFrames >= STABLE_FRAMES_NEEDED) {
            // Board has been stable long enough — fire the best shot
            AimOverlayView.BestShot best = (aimOverlayView != null)
                    ? aimOverlayView.getLastBestShot() : null;

            if (best != null) {
                Log.i(TAG, "AutoPlay: shooting ("+ best.strikerX +","+ best.strikerY
                        +") → ("+ best.targetX +","+ best.targetY +")");
                AutoShootService.INSTANCE.shoot(
                        best.strikerX, best.strikerY,
                        best.targetX,  best.targetY,
                        0.72f);   // 72% power — adjust as needed
                lastShootTimeMs = now;
                cooldownActive  = true;
                stableFrames    = 0;
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "AIMxASSIST Running", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Aim assist overlay is active");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, FloatingOverlayService.class);
        stopIntent.setAction("ACTION_STOP");
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent stopPi  = PendingIntent.getService(this, 0, stopIntent, piFlags);
        Intent openIntent     = new Intent(this, MainActivity.class);
        PendingIntent openPi  = PendingIntent.getActivity(this, 1, openIntent, piFlags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AIMxASSIST Active")
                .setContentText("Floating icon → toggle aim / autoplay")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(openPi)
                .addAction(0, "Stop", stopPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "ACTION_STOP".equals(intent.getAction())) stopSelf();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        INSTANCE = null;
        dismissPopup();
        try { if (floatingBtnView != null) windowManager.removeView(floatingBtnView); } catch (Exception ignored) {}
        try { if (aimOverlayView  != null) windowManager.removeView(aimOverlayView);  } catch (Exception ignored) {}
    }
}
