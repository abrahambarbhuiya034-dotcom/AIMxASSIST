package com.bitaim.carromaim.overlay;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.bitaim.carromaim.MainActivity;
import com.bitaim.carromaim.R;
import com.bitaim.carromaim.auto.AutoShootService;
import com.bitaim.carromaim.cv.CarromAI;
import com.bitaim.carromaim.cv.Coin;
import com.bitaim.carromaim.cv.GameState;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FloatingOverlayService — v8.4 Low-End Optimised
 *
 * Changes vs v8.3:
 *  § STABLE_FRAMES_NEEDED 10 → 6 (fires ~200 ms sooner after board settles)
 *  § STABLE_THRESH_PX 20 → 40 (CV jitter 5–20 px no longer resets counter)
 *  § dismissPopup uses named Runnable (bug-fix: no longer cancels physics posts)
 *  § Low-end flag forwarded to CarromAI.setLowEndMode()
 */
public class FloatingOverlayService extends Service {

    private static final String TAG        = "FloatingOverlayService";
    private static final String CHANNEL_ID = "aimxassist_channel";
    private static final int    NOTIF_ID   = 1001;

    private static final int   STABLE_FRAMES_NEEDED = 6;
    private static final int   PREFETCH_FRAMES      = 2;
    private static final float STABLE_THRESH_PX     = 40f;
    private static final long  SHOOT_COOLDOWN_MS    = 1800L;

    public static volatile FloatingOverlayService INSTANCE;

    private WindowManager  windowManager;
    private View           floatingBtnView;
    private AimOverlayView aimOverlayView;
    private View           popupView;
    private WindowManager.LayoutParams floatingBtnParams, overlayParams, popupParams;

    private float touchStartX, touchStartY;
    private int   viewStartX,  viewStartY;
    private long  touchDownMs;

    private boolean overlayVisible = true;
    private boolean popupShowing   = false;

    private volatile boolean autoPlayEnabled = false;
    private int     stableFrames    = 0;
    private float   lastStrikerX    = Float.NaN, lastStrikerY = Float.NaN;
    private long    lastShootTimeMs = 0L;
    private int     autoPlayDelayMs = 1800;

    private final ExecutorService physicsThread = Executors.newSingleThreadExecutor();
    private volatile Future<?>    physicsFuture;
    private volatile CarromAI.AiShot precomputedShot;
    private volatile GameState       precomputedState;
    private final AtomicBoolean      computing = new AtomicBoolean(false);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private float dp;
    private int   screenWidth;

    private final Runnable dismissPopupRunnable = this::dismissPopup;

    private float shotSensitivity = 1.0f;

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        dp = getResources().getDisplayMetrics().density;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidth = dm.widthPixels;

        // Forward low-end flag to CarromAI
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                CarromAI.setLowEndMode(mi.totalMem < 1536L * 1024 * 1024);
            }
        } catch (Throwable t) { /* ignore */ }

        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else
            startForeground(NOTIF_ID, buildNotification());

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        setupAimOverlay();
        setupFloatingButton();
        mainHandler.postDelayed(this::injectDemoState, 400);
    }

    // ── Demo state ────────────────────────────────────────────────────────────

    private void injectDemoState() {
        if (aimOverlayView == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        GameState s = new GameState();

        float uiTop = h * 0.14f, uiBot = h * 0.10f;
        float usableH = h - uiTop - uiBot;
        float side = w * 0.94f;
        side = Math.min(side, usableH * 0.96f);
        float cx = w / 2f, cy = uiTop + usableH * 0.50f;

        s.board = new RectF(cx-side/2f, cy-side/2f, cx+side/2f, cy+side/2f);

        float r = side * 0.022f;
        float strikerY = s.board.top + side * 0.800f;
        s.striker = new Coin(cx, strikerY, r * 1.28f, Coin.COLOR_STRIKER, true);

        float[][] coins = {
            {cx,              cy,              Coin.COLOR_RED},
            {cx-side*.08f,    cy-side*.10f,    Coin.COLOR_WHITE},
            {cx+side*.08f,    cy-side*.10f,    Coin.COLOR_WHITE},
            {cx-side*.16f,    cy-side*.05f,    Coin.COLOR_WHITE},
            {cx+side*.16f,    cy-side*.05f,    Coin.COLOR_WHITE},
            {cx,              cy-side*.17f,    Coin.COLOR_WHITE},
            {cx-side*.08f,    cy+side*.10f,    Coin.COLOR_WHITE},
            {cx+side*.08f,    cy+side*.10f,    Coin.COLOR_WHITE},
            {cx,              cy+side*.17f,    Coin.COLOR_WHITE},
            {cx-side*.16f,    cy+side*.05f,    Coin.COLOR_WHITE},
            {cx+side*.16f,    cy+side*.05f,    Coin.COLOR_BLACK},
            {cx-side*.24f,    cy-side*.12f,    Coin.COLOR_BLACK},
            {cx+side*.24f,    cy-side*.12f,    Coin.COLOR_BLACK},
            {cx-side*.24f,    cy+side*.12f,    Coin.COLOR_BLACK},
            {cx+side*.24f,    cy+side*.12f,    Coin.COLOR_BLACK},
            {cx,              cy+side*.26f,    Coin.COLOR_BLACK},
            {cx-side*.14f,    cy+side*.26f,    Coin.COLOR_BLACK},
            {cx+side*.14f,    cy+side*.26f,    Coin.COLOR_BLACK},
            {cx,              cy-side*.26f,    Coin.COLOR_BLACK},
        };
        for (float[] c : coins) s.coins.add(new Coin(c[0], c[1], r, (int)c[2], false));

        float inset = side * 0.060f;
        s.pockets.add(new PointF(s.board.left  + inset, s.board.top    + inset));
        s.pockets.add(new PointF(s.board.right - inset, s.board.top    + inset));
        s.pockets.add(new PointF(s.board.left  + inset, s.board.bottom - inset));
        s.pockets.add(new PointF(s.board.right - inset, s.board.bottom - inset));

        aimOverlayView.setDemoState(s);
    }

    // ── Floating button ───────────────────────────────────────────────────────

    private void setupFloatingButton() {
        floatingBtnView = LayoutInflater.from(this).inflate(R.layout.view_floating_button, null);
        floatingBtnParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        floatingBtnParams.gravity = Gravity.TOP | Gravity.START;
        floatingBtnParams.x = 16; floatingBtnParams.y = 280;

        floatingBtnView.setOnTouchListener(new View.OnTouchListener() {
            boolean wasDrag;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchStartX=e.getRawX(); touchStartY=e.getRawY();
                        viewStartX=floatingBtnParams.x; viewStartY=floatingBtnParams.y;
                        touchDownMs=System.currentTimeMillis(); wasDrag=false; return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx=e.getRawX()-touchStartX, dy=e.getRawY()-touchStartY;
                        if (Math.abs(dx)>8||Math.abs(dy)>8) wasDrag=true;
                        floatingBtnParams.x=(int)(viewStartX+dx);
                        floatingBtnParams.y=(int)(viewStartY+dy);
                        try{windowManager.updateViewLayout(floatingBtnView,floatingBtnParams);}catch(Exception ignored){}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!wasDrag) { if(popupShowing)dismissPopup();else showTogglePopup(); }
                        else snapToEdge();
                        return true;
                }
                return false;
            }
        });
        windowManager.addView(floatingBtnView, floatingBtnParams);
    }

    private void snapToEdge() {
        if (floatingBtnView == null) return;
        int btnW = floatingBtnView.getWidth(); if (btnW == 0) btnW = (int)(52*dp);
        int currentX = floatingBtnParams.x;
        int targetX = (currentX + btnW/2 < screenWidth/2) ? 8 : (screenWidth - btnW - 8);
        if (currentX == targetX) return;
        ValueAnimator anim = ValueAnimator.ofInt(currentX, targetX);
        anim.setDuration(250); anim.setInterpolator(new DecelerateInterpolator(1.8f));
        anim.addUpdateListener(a -> {
            floatingBtnParams.x = (int) a.getAnimatedValue();
            try{windowManager.updateViewLayout(floatingBtnView,floatingBtnParams);}catch(Exception ignored){}
        });
        anim.start();
    }

    // ── Toggle popup ──────────────────────────────────────────────────────────

    private void showTogglePopup() {
        if (popupShowing) return;
        popupShowing = true;
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackgroundColor(0xF2111122);
        ll.setAlpha(0f);
        int pad = (int)(13*dp);
        ll.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("AIMxASSIST v8.4");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#FFD700"));
        title.setTextSize(14);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, (int)(6*dp));
        ll.addView(title);

        addPopupBtn(ll, pad, overlayVisible ? "Lines OFF" : "Lines ON",
                overlayVisible ? 0xFFFF5555 : 0xFF22DD55,
                v -> { toggleAimOverlay(); dismissPopup(); });

        addPopupBtn(ll, pad,
                autoPlayEnabled ? "AutoPlay OFF" : (AutoShootService.isReady() ? "AutoPlay ON" : "Need Accessibility"),
                autoPlayEnabled ? 0xFFFF5555 : 0xFF6699FF,
                v -> { toggleAutoPlayFromPopup(); dismissPopup(); });

        if (autoPlayEnabled) {
            TextView hint = new TextView(this);
            hint.setText("Physics AI active\nFires on stable board");
            hint.setTextColor(0xFF22DD55);
            hint.setTextSize(11);
            hint.setGravity(Gravity.CENTER_HORIZONTAL);
            hint.setPadding(0, (int)(5*dp), 0, 0);
            ll.addView(hint);
        }

        popupView = ll;
        popupParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        popupParams.gravity = Gravity.TOP | Gravity.START;
        popupParams.x = floatingBtnParams.x + (int)(60*dp);
        popupParams.y = floatingBtnParams.y;
        popupView.setOnTouchListener((v, e) -> { if(e.getAction()==MotionEvent.ACTION_OUTSIDE)dismissPopup(); return false; });
        windowManager.addView(popupView, popupParams);
        ll.animate().alpha(1f).setDuration(180).start();
        mainHandler.postDelayed(dismissPopupRunnable, 6000);
    }

    private void addPopupBtn(LinearLayout parent, int pad, String text, int color, View.OnClickListener click) {
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextColor(color); tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT_BOLD); tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setPadding(pad*2, pad, pad*2, pad); tv.setOnClickListener(click);
        parent.addView(tv);
    }

    private void dismissPopup() {
        if (!popupShowing) return;
        popupShowing = false;
        mainHandler.removeCallbacks(dismissPopupRunnable);
        View pv = popupView;
        if (pv != null) {
            pv.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                try{windowManager.removeView(pv);}catch(Exception ignored){}
            }).start();
        }
        popupView = null;
    }

    // ── Aim overlay ───────────────────────────────────────────────────────────

    private void setupAimOverlay() {
        aimOverlayView = new AimOverlayView(this);
        aimOverlayView.setAutoplaySwipeListener(
            (fromX, fromY, toX, toY, durationMs, powerFrac) -> {
                AutoShootService svc = AutoShootService.INSTANCE;
                if (svc == null) return;
                svc.shoot(fromX, fromY, toX, toY, Math.min(1.0f, Math.max(0.35f, powerFrac)));
            });
        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        |WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        |WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        |WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        aimOverlayView.setVisibility(View.VISIBLE);
        windowManager.addView(aimOverlayView, overlayParams);
    }

    // ── External API ──────────────────────────────────────────────────────────

    public void setShotMode(String mode)        { if (aimOverlayView!=null) aimOverlayView.setShotMode(mode); }
    public void setMarginOffset(float dx,float dy) {}
    public void setSensitivity(float v)         { shotSensitivity = Math.max(0.3f, Math.min(3.0f, v)); }
    public void setAutoPlayDelay(int ms)        { autoPlayDelayMs = Math.max(500, ms); }

    public void onDetectedState(GameState s) {
        if (s == null) return;
        if (aimOverlayView != null) {
            aimOverlayView.setDetectedState(s);
            if (!overlayVisible) { overlayVisible=true; aimOverlayView.setVisibility(View.VISIBLE); }
        }
        if (autoPlayEnabled && s.striker != null) handleAutoPlay(s);
    }

    public void shootNow() {
        AutoShootService acc = AutoShootService.INSTANCE;
        if (acc == null) { Log.w(TAG, "shootNow: accessibility not connected"); return; }
        AimOverlayView.BestShot best = (aimOverlayView!=null)?aimOverlayView.getLastBestShot():null;
        if (best == null) { Log.w(TAG, "shootNow: no live shot cached"); return; }
        Log.i(TAG, "shootNow: dispatching");
        acc.shoot(best.strikerX, best.strikerY, best.targetX, best.targetY, best.powerFrac);
    }

    public void setAutoPlay(boolean enabled) {
        autoPlayEnabled = enabled;
        stableFrames = 0; lastStrikerX = Float.NaN; lastStrikerY = Float.NaN;
        precomputedShot = null;
        if (!enabled && physicsFuture != null) physicsFuture.cancel(true);
        Log.i(TAG, "AutoPlay " + (enabled?"ON":"OFF"));
    }

    public boolean isAutoPlayEnabled() { return autoPlayEnabled; }

    public void toggleAimOverlay() {
        overlayVisible = !overlayVisible;
        if (aimOverlayView != null) {
            aimOverlayView.setVisibility(overlayVisible?View.VISIBLE:View.GONE);
            if (overlayVisible) injectDemoState();
        }
    }

    // ── Stability-based autoplay ──────────────────────────────────────────────

    private void handleAutoPlay(GameState s) {
        if (!AutoShootService.isReady()) return;
        long now = System.currentTimeMillis();
        long minGap = Math.max(SHOOT_COOLDOWN_MS, autoPlayDelayMs);
        if (now - lastShootTimeMs < minGap) return;

        float sx = s.striker.pos.x, sy = s.striker.pos.y;
        if (!Float.isNaN(lastStrikerX)) {
            float moved = (float)Math.sqrt((sx-lastStrikerX)*(sx-lastStrikerX)+(sy-lastStrikerY)*(sy-lastStrikerY));
            if (moved > STABLE_THRESH_PX) {
                stableFrames=0; precomputedShot=null;
                if (physicsFuture!=null) physicsFuture.cancel(true);
                computing.set(false);
            } else { stableFrames++; }
        }
        lastStrikerX = sx; lastStrikerY = sy;

        if (stableFrames == PREFETCH_FRAMES && computing.compareAndSet(false, true)) {
            final GameState snapshot = s;
            physicsFuture = physicsThread.submit(() -> {
                try {
                    CarromAI.AiShot shot = CarromAI.findBestShotPhysics(snapshot);
                    precomputedShot  = shot;
                    precomputedState = snapshot;
                    if (aimOverlayView!=null&&shot!=null)
                        mainHandler.post(()->aimOverlayView.setPhysicsBestShot(shot,snapshot));
                } catch (Exception e) {
                    Log.w(TAG, "Physics computation failed: " + e.getMessage());
                } finally { computing.set(false); }
            });
        }

        if (stableFrames < STABLE_FRAMES_NEEDED) return;

        CarromAI.AiShot physShot = precomputedShot;
        AutoShootService acc = AutoShootService.INSTANCE;
        if (acc == null) return;

        if (physShot != null) {
            float dx = physShot.ghostPos.x - s.striker.pos.x;
            float dy = physShot.ghostPos.y - s.striker.pos.y;
            float toX = s.striker.pos.x + dx * 1.20f;
            float toY = s.striker.pos.y + dy * 1.20f;
            float pwr = Math.min(1.0f, Math.max(0.35f, physShot.powerFrac * shotSensitivity));
            Log.i(TAG, String.format("AutoPlay PHYSICS: stable=%d pwr=%.2f", stableFrames, pwr));
            acc.shoot(s.striker.pos.x, s.striker.pos.y, toX, toY, pwr);
        } else {
            AimOverlayView.BestShot best = (aimOverlayView!=null)?aimOverlayView.getLastBestShot():null;
            if (best == null) return;
            Log.i(TAG, String.format("AutoPlay GEO FALLBACK: stable=%d pwr=%.2f", stableFrames, best.powerFrac));
            acc.shoot(best.strikerX, best.strikerY, best.targetX, best.targetY, best.powerFrac * shotSensitivity);
        }

        lastShootTimeMs = now; stableFrames = 0; precomputedShot = null; precomputedState = null;
    }

    private void toggleAutoPlayFromPopup() {
        if (!AutoShootService.isReady()) { Log.w(TAG,"Accessibility not ready"); return; }
        setAutoPlay(!autoPlayEnabled);
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "AIMxASSIST v8.4 Running", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Aim assist overlay active — physics AI ready");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        Intent stopIntent = new Intent(this, FloatingOverlayService.class); stopIntent.setAction("ACTION_STOP");
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent, piFlags);
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, openIntent, piFlags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AIMxASSIST v8.4 — Physics AI Active")
                .setContentText("Ghost-ball + sub-pixel physics autoplay ready")
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
        super.onDestroy(); INSTANCE = null;
        physicsThread.shutdownNow();
        dismissPopup();
        try{if(floatingBtnView!=null)windowManager.removeView(floatingBtnView);}catch(Exception ignored){}
        try{if(aimOverlayView!=null)windowManager.removeView(aimOverlayView);}catch(Exception ignored){}
    }
}
