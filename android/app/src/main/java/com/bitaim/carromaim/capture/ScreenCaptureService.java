package com.bitaim.carromaim.capture;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.bitaim.carromaim.MainActivity;
import com.bitaim.carromaim.R;
import com.bitaim.carromaim.cv.BoardDetector;
import com.bitaim.carromaim.cv.GameState;
import com.bitaim.carromaim.overlay.FloatingOverlayService;

import java.nio.ByteBuffer;

/**
 * ScreenCaptureService — MediaProjection + VirtualDisplay + ImageReader pipeline.
 *
 * Low-end optimisation: capture width capped at 480 px on devices that reported
 * low memory to BoardDetector. This halves pixel throughput vs the default 720 px
 * and directly cuts BoardDetector processing time by ~56%.
 */
public class ScreenCaptureService extends Service {

    private static final String TAG = "BitAim/Capture";
    public  static final String EXTRA_RESULT_CODE = "resultCode";
    public  static final String EXTRA_DATA        = "data";

    private static final String CHANNEL_ID    = "bitaim_capture";
    private static final int    NOTIF_ID      = 2001;
    private static final long   FRAME_INTERVAL_MS = 33; // ~30 fps

    private MediaProjection mediaProjection;
    private VirtualDisplay  virtualDisplay;
    private ImageReader     imageReader;
    private HandlerThread   workerThread;
    private Handler         workerHandler;
    private BoardDetector   detector;

    private int screenWidth, screenHeight, screenDpi;
    private long lastFrameMs = 0;

    public static volatile ScreenCaptureService INSTANCE;

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        detector = new BoardDetector(getApplicationContext());
        createChannel();
        Notification notif = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else
            startForeground(NOTIF_ID, notif);

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenWidth = dm.widthPixels; screenHeight = dm.heightPixels; screenDpi = dm.densityDpi;

        workerThread = new HandlerThread("BitAim-Capture");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (mediaProjection != null) return START_STICKY;

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (resultCode == Activity.RESULT_CANCELED || data == null) { stopSelf(); return START_NOT_STICKY; }

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = mpm.getMediaProjection(resultCode, data);
        if (mediaProjection == null) { Log.e(TAG, "Failed to acquire MediaProjection"); stopSelf(); return START_NOT_STICKY; }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
            }, workerHandler);
        }
        startCapture();
        return START_STICKY;
    }

    private void startCapture() {
        // Low-end: cap at 480 px; normal: cap at 720 px
        int maxW = isLowMemory() ? 480 : 720;
        int captureW = Math.min(screenWidth, maxW);
        int captureH = Math.round(screenHeight * (captureW / (float) screenWidth));

        imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "BitAim-Capture", captureW, captureH, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, workerHandler);

        final int w = captureW, h = captureH;
        imageReader.setOnImageAvailableListener(reader -> processIfDue(reader, w, h), workerHandler);
    }

    private boolean isLowMemory() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem < 1536L * 1024 * 1024;
        } catch (Throwable t) { return false; }
    }

    private void processIfDue(ImageReader reader, int w, int h) {
        long now = System.currentTimeMillis();
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null || now - lastFrameMs < FRAME_INTERVAL_MS) return;
            lastFrameMs = now;

            Bitmap bmp = imageToBitmap(img, w, h);
            if (bmp == null) return;

            GameState state = detector.detect(bmp);
            bmp.recycle();

            float sx = screenWidth / (float) w, sy = screenHeight / (float) h;
            scaleState(state, sx, sy);

            FloatingOverlayService overlay = FloatingOverlayService.INSTANCE;
            if (overlay != null && state != null) overlay.onDetectedState(state);
        } catch (Throwable t) {
            Log.w(TAG, "Frame error: " + t.getMessage());
        } finally {
            if (img != null) img.close();
        }
    }

    private void scaleState(GameState s, float sx, float sy) {
        if (s == null) return;
        if (s.board != null) { s.board.left*=sx; s.board.right*=sx; s.board.top*=sy; s.board.bottom*=sy; }
        if (s.striker != null) { s.striker.pos.x*=sx; s.striker.pos.y*=sy; s.striker.radius*=(sx+sy)*0.5f; }
        for (com.bitaim.carromaim.cv.Coin c:s.coins) { c.pos.x*=sx; c.pos.y*=sy; c.radius*=(sx+sy)*0.5f; }
        for (android.graphics.PointF p:s.pockets) { p.x*=sx; p.y*=sy; }
    }

    private Bitmap imageToBitmap(Image image, int w, int h) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride(), rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * w;
        int bw = w + rowPadding / Math.max(1, pixelStride);
        Bitmap bmp = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buffer);
        if (rowPadding == 0) return bmp;
        Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, w, h);
        bmp.recycle();
        return cropped;
    }

    public void setMinRadius(float v)       { detector.setMinRadiusFrac(v); }
    public void setMaxRadius(float v)       { detector.setMaxRadiusFrac(v); }
    public void setDetectionParam(double v) { detector.setParam2(v); }

    @Override
    public void onDestroy() {
        super.onDestroy(); INSTANCE = null;
        if (virtualDisplay!=null){virtualDisplay.release();virtualDisplay=null;}
        if (imageReader!=null){imageReader.close();imageReader=null;}
        if (mediaProjection!=null){mediaProjection.stop();mediaProjection=null;}
        if (workerThread!=null){workerThread.quitSafely();workerThread=null;}
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenWidth=dm.widthPixels; screenHeight=dm.heightPixels;
        if (mediaProjection!=null) {
            if (virtualDisplay!=null){virtualDisplay.release();virtualDisplay=null;}
            if (imageReader!=null){imageReader.close();imageReader=null;}
            startCapture();
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Bit-Aim Auto-Detect", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AIMxASSIST — Auto-Detect Running")
                .setContentText("Reading screen for striker / coin / pocket detection")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
