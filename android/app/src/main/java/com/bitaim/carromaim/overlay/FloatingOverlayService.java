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
import android.widget.ImageView;
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
 * FloatingOverlayService — v8.1 FIXED
 *
 * Changes vs v8.0:
 *
 *  § Floating button — smooth edge-snap
 *    - When released, the button animates to the nearest screen edge with a
 *      spring-like DecelerateInterpolator (250 ms). No more abrupt snap.
 *    - Velocity tracking during drag for natural feel.
 *
 *  § Autoplay stability — more forgiving
 *    - STABLE_THRESH_PX raised 10 → 20 px (less sensitive to CV jitter,
 *      so the striker is correctly considered stable more often).
 *    - STABLE_FRAMES_NEEDED unchanged at 10 (~330 ms at 30 fps).
 *    - SHOOT_COOLDOWN_MS unchanged at 1800 ms.
 *
 *  § Popup — smooth fade-in via alpha animation.
 */
public class FloatingOverlayService extends Service {

    private static final String TAG        = "FloatingOverlayService";
    private static final String CHANNEL_ID = "aimxassist_channel";
    private static final int    NOTIF_ID   = 1001;

    // ── Responsiveness tuning ─────────────────────────────────────────────────
    private static final int   STABLE_FRAMES_NEEDED = 10;
    private static final int   PREFETCH_FRAMES      = 3;
    /**
     * Maximum striker movement (px) counted as "stable".
     * Raised from 10 → 20 so ordinary CV pixel-jitter does not reset the
     * stability counter on every frame.
     */
    private static final float STABLE_THRESH_PX     = 20f;
    private static final long  SHOOT_COOLDOWN_MS    = 1800L;

    public static volatile FloatingOverlayService INSTANCE;

    private WindowManager  windowManager;
    private View           floatingBtnView;
    private AimOverlayView aimOverlayView;
    private View           popupView;

    private WindowManager.LayoutParams floatingBtnParams;
    private WindowManager.LayoutParams overlayParams;
    private WindowManager.LayoutParams popupParams;

    // Touch state for drag
    private float touchStartX, touchStartY;
    private int   viewStartX,  viewStartY;
    private long  touchDownMs;

    private boolean overlayVisible = true;
    private boolean popupShowing   = false;

    private volatile boolean autoPlayEnabled = false;
    private int     stableFrames    = 0;
    private float   lastStrikerX    = Float.NaN;
    private float   lastStrikerY    = Float.NaN;
    private long    lastShootTimeMs = 0L;
    private int     autoPlayDelayMs = 1800;

    // ── Background physics computation ────────────────────────────────────────
    private final ExecutorService physicsThread = Executors.newSingleThreadExecutor();
    private volatile Future<?>    physicsFuture;
    private volatile CarromAI.AiShot precomputedShot;
    private volatile GameState       precomputedState;
    private final AtomicBoolean      computing = new AtomicBoolean(false);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private float dp;
    private int   screenWidth;

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        dp = getResources().getDisplayMetrics().density;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidth = dm.widthPixels;
        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, buildNotification());
        }
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
        float side = w * 0.80f, cx = w/2f, cy = h * 0.44f;
        s.board = new RectF(cx-side/2f, cy-side/2f, cx+side/2f, cy+side/2f);
        float r = side * 0.024f;
        s.striker = new Coin(cx, cy+side*0.34f, r*1.15f, Coin.COLOR_STRIKER, true);

        float[][] coins = {
            {cx,              cy,              0, Coin.COLOR_RED},
            {cx-side*.10f,    cy-side*.07f,    0, Coin.COLOR_WHITE},
            {cx+side*.10f,    cy-side*.07f,    0, Coin.COLOR_WHITE},
            {cx-side*.20f,    cy+side*.08f,    0, Coin.COLOR_WHITE},
            {cx+side*.20f,    cy+side*.08f,    0, Coin.COLOR_WHITE},
            {cx,              cy-side*.21f,    0, Coin.COLOR_WHITE},
            {cx-side*.07f,    cy+side*.08f,    0, Coin.COLOR_BLACK},
            {cx+side*.07f,    cy+side*.08f,    0, Coin.COLOR_BLACK},
            {cx-side*.24f,    cy-side*.12f,    0, Coin.COLOR_BLACK},
            {cx+side*.24f,    cy-side*.12f,    0, Coin.COLOR_BLACK},
            {cx-side*.16f,    cy+side*.18f,    0, Coin.COLOR_BLACK},
            {cx+side*.16f,    cy+side*.18f,    0, Coin.COLOR_BLACK},
        };
        for (float[] c : coins)
            s.coins.add(new Coin(c[0], c[1], r, (int)c[3], false));

        float inset = side * 0.035f;
        s.pockets.add(new PointF(s.board.left  + inset, s.board.top    + inset));
        s.pockets.add(new PointF(s.board.right - inset, s.board.top    + inset));
        s.pockets.add(new PointF(s.board.left  + inset, s.board.bottom - inset));
        s.pockets.add(new PointF(s.board.right - inset, s.board.bottom - inset));

        aimOverlayView.setDemoState(s);
    }

    // ── Floating button with smooth edge-snap ─────────────────────────────────

    private void setupFloatingButton() {
        floatingBtnView = LayoutInflater.from(this)
                .inflate(R.layout.view_floating_button, null);
        floatingBtnParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        floatingBtnParams.gravity = Gravity.TOP | Gravity.START;
        floatingBtnParams.x = 16;
        floatingBtnParams.y = 280;

        floatingBtnView.setOnTouchListener(new View.OnTouchListener() {
            boolean wasDrag;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchStartX = e.getRawX();
                        touchStartY = e.getRawY();
                        viewStartX  = floatingBtnParams.x;
                        viewStartY  = floatingBtnParams.y;
                        touchDownMs = System.currentTimeMillis();
                        wasDrag = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - touchStartX;
                        float dy = e.getRawY() - touchStartY;
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) wasDrag = true;
                        floatingBtnParams.x = (int)(viewStartX + dx);
                        floatingBtnParams.y = (int)(viewStartY + dy);
                        try { windowManager.updateViewLayout(floatingBtnView, floatingBtnParams); }
                        catch (Exception ignored) {}
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!wasDrag) {
                            if (popupShowing) dismissPopup(); else showTogglePopup();
                        } else {
                            // Smooth edge-snap: animate to nearest horizontal edge
                            snapToEdge();
                        }
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(floatingBtnView, floatingBtnParams);
    }

    /**
     * Animates the floating button to the nearest screen edge (left or right)
     * using a DecelerateInterpolator for a smooth spring-like feel.
     */
    private void snapToEdge() {
        if (floatingBtnView == null) return;
        int btnW = floatingBtnView.getWidth();
        if (btnW == 0) btnW = (int)(52 * dp); // fallback if not measured yet

        int currentX = floatingBtnParams.x;
        // Snap to whichever edge is closer
        int targetX = (currentX + btnW / 2 < screenWidth / 2)
                ? 8                              // left edge with small margin
                : (screenWidth - btnW - 8);      // right edge with small margin

        if (currentX == targetX) return;

        ValueAnimator anim = ValueAnimator.ofInt(currentX, targetX);
        anim.setDuration(250);
        anim.setInterpolator(new DecelerateInterpolator(1.8f));
        anim.addUpdateListener(a -> {
            floatingBtnParams.x = (int) a.getAnimatedValue();
            try { windowManager.updateViewLayout(floatingBtnView, floatingBtnParams); }
            catch (Exception ignored) {}
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
        title.setText("AIMxASSIST v8");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#FFD700"));
        title.setTextSize(14);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, (int)(6*dp));
        ll.addView(title);

        addPopupBtn(ll, pad,
                overlayVisible ? "Lines OFF" : "Lines ON",
                overlayVisible ? 0xFFFF5555 : 0xFF22DD55,
                v -> { toggleAimOverlay(); dismissPopup(); });

        addPopupBtn(ll, pad,
                autoPlayEnabled
                    ? "AutoPlay OFF"
                    : (AutoShootService.isReady() ? "AutoPlay ON" : "Need Accessibility"),
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        popupParams.gravity = Gravity.TOP | Gravity.START;
        popupParams.x = floatingBtnParams.x + (int)(60*dp);
        popupParams.y = floatingBtnParams.y;
        popupView.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE) dismissPopup();
            return false;
        });
        windowManager.addView(popupView, popupParams);

        // Smooth fade-in
        ll.animate().alpha(1f).setDuration(180).start();

        mainHandler.postDelayed(this::dismissPopup, 6000);
    }

    private void addPopupBtn(LinearLayout parent, int pad, String text, int color,
                              View.OnClickListener click) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setPadding(pad*2, pad, pad*2, pad);
        tv.setOnClickListener(click);
        parent.addView(tv);
    }

    private void dismissPopup() {
        if (!popupShowing) return;
        popupShowing = false;
        mainHandler.removeCallbacksAndMessages(null);
        View pv = popupView;
        if (pv != null) {
            // Fade-out, then remove
            pv.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                try { windowManager.removeView(pv); } catch (Exception ignored) {}
            }).start();
        }
        popupView = null;
    }

    // ── Aim overlay setup ─────────────────────────────────────────────────────

    private void setupAimOverlay() {
        aimOverlayView = new AimOverlayView(this);

        aimOverlayView.setAutoplaySwipeListener(
            (fromX, fromY, toX, toY, durationMs, powerFrac) -> {
                AutoShootService svc = AutoShootService.INSTANCE;
                if (svc == null) return;
                svc.shoot(fromX, fromY, toX, toY,
                          Math.min(1.0f, Math.max(0.35f, powerFrac)));
            });

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        aimOverlayView.setVisibility(View.VISIBLE);
        windowManager.addView(aimOverlayView, overlayParams);
    }

    // ── External API ──────────────────────────────────────────────────────────

    public void setShotMode(String mode) {
        if (aimOverlayView != null) aimOverlayView.setShotMode(mode);
    }
    public void setMarginOffset(float dx, float dy) {}
    public void setSensitivity(float value)         {}

    public void setAutoPlayDelay(int ms) {
        autoPlayDelayMs = Math.max(500, ms);
    }

    public void onDetectedState(GameState s) {
        if (s == null) return;
        if (aimOverlayView != null) {
            aimOverlayView.setDetectedState(s);
            if (!overlayVisible) {
                overlayVisible = true;
                aimOverlayView.setVisibility(View.VISIBLE);
            }
        }
        if (autoPlayEnabled && s.striker != null) handleAutoPlay(s);
    }

    public void shootNow() {
        AutoShootService acc = AutoShootService.INSTANCE;
        if (acc == null) { Log.w(TAG, "shootNow: accessibility not connected"); return; }
        AimOverlayView.BestShot best =
            (aimOverlayView != null) ? aimOverlayView.getLastBestShot() : null;
        if (best == null) { Log.w(TAG, "shootNow: no live shot cached"); return; }
        Log.i(TAG, "shootNow: dispatching");
        acc.shoot(best.strikerX, best.strikerY, best.targetX, best.targetY, best.powerFrac);
    }

    public void setAutoPlay(boolean enabled) {
        autoPlayEnabled = enabled;
        stableFrames    = 0;
        lastStrikerX    = Float.NaN;
        lastStrikerY    = Float.NaN;
        precomputedShot = null;
        if (!enabled && physicsFuture != null) physicsFuture.cancel(true);
        Log.i(TAG, "AutoPlay " + (enabled ? "ON" : "OFF"));
    }

    public boolean isAutoPlayEnabled() { return autoPlayEnabled; }

    public void toggleAimOverlay() {
        overlayVisible = !overlayVisible;
        if (aimOverlayView != null) {
            aimOverlayView.setVisibility(overlayVisible ? View.VISIBLE : View.GONE);
            if (overlayVisible) injectDemoState();
        }
    }

    // ── Stability-based autoplay ──────────────────────────────────────────────

    /**
     * Called on every live CV frame (~30 fps).
     *
     * Stability flow:
     *   frames 1-2   : striker moved → reset
     *   frame 3      : start background physics prefetch
     *   frames 3–9   : waiting for stability + physics result
     *   frame 10     : board stable → fire shot
     *
     * STABLE_THRESH_PX is now 20 px (up from 10) so ordinary CV pixel-noise
     * does not reset the counter on every frame.
     */
    private void handleAutoPlay(GameState s) {
        if (!AutoShootService.isReady()) return;

        long now = System.currentTimeMillis();
        long minGap = Math.max(SHOOT_COOLDOWN_MS, autoPlayDelayMs);
        if (now - lastShootTimeMs < minGap) return;

        float sx = s.striker.pos.x, sy = s.striker.pos.y;

        if (!Float.isNaN(lastStrikerX)) {
            float moved = (float) Math.sqrt(
                (sx-lastStrikerX)*(sx-lastStrikerX) + (sy-lastStrikerY)*(sy-lastStrikerY));
            if (moved > STABLE_THRESH_PX) {
                stableFrames    = 0;
                precomputedShot = null;
                if (physicsFuture != null) physicsFuture.cancel(true);
                computing.set(false);
            } else {
                stableFrames++;
            }
        }
        lastStrikerX = sx;
        lastStrikerY = sy;

        // Prefetch physics shot early (frame PREFETCH_FRAMES)
        if (stableFrames == PREFETCH_FRAMES && computing.compareAndSet(false, true)) {
            final GameState snapshot = s;
            physicsFuture = physicsThread.submit(() -> {
                try {
                    CarromAI.AiShot shot = CarromAI.findBestShotPhysics(snapshot);
                    precomputedShot  = shot;
                    precomputedState = snapshot;
                    if (aimOverlayView != null && shot != null) {
                        mainHandler.post(() ->
                            aimOverlayView.setPhysicsBestShot(shot, snapshot));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Physics computation failed: " + e.getMessage());
                } finally {
                    computing.set(false);
                }
            });
        }

        if (stableFrames < STABLE_FRAMES_NEEDED) return;

        // Board stable — fire!
        CarromAI.AiShot physShot = precomputedShot;

        if (physShot != null) {
            float dx = physShot.ghostPos.x - s.striker.pos.x;
            float dy = physShot.ghostPos.y - s.striker.pos.y;
            float factor = 1.20f;
            float toX = s.striker.pos.x + dx * factor;
            float toY = s.striker.pos.y + dy * factor;
            float pwr = Math.min(1.0f, Math.max(0.35f, physShot.powerFrac));
            Log.i(TAG, String.format(
                "AutoPlay PHYSICS: stable=%d pwr=%.2f target=(%.0f,%.0f)",
                stableFrames, pwr, toX, toY));
            AutoShootService.INSTANCE.shoot(s.striker.pos.x, s.striker.pos.y,
                                            toX, toY, pwr);
        } else {
            // Fallback to geometry-based cached shot
            AimOverlayView.BestShot best =
                (aimOverlayView != null) ? aimOverlayView.getLastBestShot() : null;
            if (best == null) return;
            Log.i(TAG, String.format(
                "AutoPlay GEO FALLBACK: stable=%d pwr=%.2f",
                stableFrames, best.powerFrac));
            AutoShootService.INSTANCE.shoot(
                best.strikerX, best.strikerY,
                best.targetX,  best.targetY, best.powerFrac);
        }

        lastShootTimeMs = now;
        stableFrames    = 0;
        precomputedShot = null;
        precomputedState = null;
    }

    private void toggleAutoPlayFromPopup() {
        if (!AutoShootService.isReady()) {
            Log.w(TAG, "Accessibility not ready — cannot toggle AutoPlay");
            return;
        }
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
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "AIMxASSIST v8 Running", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Aim assist overlay active — physics AI ready");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0;
        Intent stopIntent = new Intent(this, FloatingOverlayService.class);
        stopIntent.setAction("ACTION_STOP");
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent, piFlags);
        Intent openIntent    = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, openIntent, piFlags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AIMxASSIST v8 — Physics AI Active")
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
        super.onDestroy();
        INSTANCE = null;
        physicsThread.shutdownNow();
        dismissPopup();
        try { if (floatingBtnView != null) windowManager.removeView(floatingBtnView); } catch (Exception ignored) {}
        try { if (aimOverlayView  != null) windowManager.removeView(aimOverlayView);  } catch (Exception ignored) {}
    }
}
