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
import com.bitaim.carromaim.cv.GameState;

/**
 * FloatingOverlayService — v3
 *
 * Changes:
 *  - Tapping the floating icon shows a small popup with a "Turn ON / Turn OFF"
 *    button instead of toggling directly.
 *  - The AimOverlayView is fully pass-through (FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE)
 *    so it never intercepts touches to the game.
 *  - App name updated to AIMxASSIST.
 */
public class FloatingOverlayService extends Service {

    private static final String CHANNEL_ID = "aimxassist_channel";
    private static final int    NOTIF_ID   = 1001;

    public static volatile FloatingOverlayService INSTANCE;

    private WindowManager windowManager;
    private View          floatingBtnView;
    private AimOverlayView aimOverlayView;
    private View          popupView;           // small toggle popup

    private WindowManager.LayoutParams floatingBtnParams;
    private WindowManager.LayoutParams overlayParams;
    private WindowManager.LayoutParams popupParams;

    private float   touchStartX, touchStartY;
    private int     viewStartX,  viewStartY;
    private boolean overlayVisible = false;
    private boolean popupShowing   = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float dp;

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

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

        // Build popup programmatically
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

        // Toggle button
        TextView btn = new TextView(this);
        btn.setText(overlayVisible ? "⬛  Turn OFF" : "▶  Turn ON");
        btn.setTextColor(overlayVisible ? 0xFFFF6B6B : 0xFF22C55E);
        btn.setTextSize(15);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER_HORIZONTAL);
        btn.setPadding(pad*2, pad, pad*2, pad);
        btn.setOnClickListener(vv -> {
            toggleAimOverlay();
            dismissPopup();
        });
        ll.addView(btn);

        // Dismiss on outside tap (handled by FLAG_WATCH_OUTSIDE_TOUCH + listener)
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

        // Auto-dismiss after 4 s
        handler.postDelayed(this::dismissPopup, 4000);
    }

    private void dismissPopup() {
        if (!popupShowing) return;
        popupShowing = false;
        handler.removeCallbacksAndMessages(null);
        try {
            if (popupView != null) windowManager.removeView(popupView);
        } catch (Exception ignored) {}
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
                // NOT_TOUCHABLE = overlay never intercepts game touches
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
    public void setMarginOffset(float dx, float dy) { /* auto calibrated */ }
    public void setSensitivity(float value)         { /* removed */ }
    public void onDetectedState(GameState s)        { if (aimOverlayView != null) aimOverlayView.setDetectedState(s); }

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
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent, piFlags);
        Intent openIntent   = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, openIntent, piFlags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AIMxASSIST Active")
                .setContentText("Tap floating icon in game to toggle aim lines")
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
