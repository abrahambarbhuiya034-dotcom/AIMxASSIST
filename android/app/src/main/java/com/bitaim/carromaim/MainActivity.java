package com.bitaim.carromaim;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bitaim.carromaim.auto.AutoShootService;
import com.bitaim.carromaim.capture.MediaProjectionRequestActivity;
import com.bitaim.carromaim.capture.ScreenCaptureService;
import com.bitaim.carromaim.overlay.FloatingOverlayService;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_OVERLAY = 1001;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);

        Button btnOverlay = findViewById(R.id.btn_overlay);
        btnOverlay.setOnClickListener(v -> toggleOverlay());

        Button btnCapture = findViewById(R.id.btn_capture);
        btnCapture.setOnClickListener(v -> requestCapture());

        Button btnAccessibility = findViewById(R.id.btn_accessibility);
        btnAccessibility.setOnClickListener(v -> openAccessibilitySettings());

        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void toggleOverlay() {
        if (!hasOverlayPermission()) {
            requestOverlayPermission();
            return;
        }
        if (FloatingOverlayService.INSTANCE != null) {
            Intent i = new Intent(this, FloatingOverlayService.class);
            i.setAction("ACTION_STOP");
            startService(i);
            stopService(new Intent(this, ScreenCaptureService.class));
            tvStatus.setText("Overlay stopped.");
        } else {
            Intent i = new Intent(this, FloatingOverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
            tvStatus.setText("Overlay started — tap the floating button on screen.");
        }
    }

    private void requestCapture() {
        if (!hasOverlayPermission()) { requestOverlayPermission(); return; }
        Intent i = new Intent(this, MediaProjectionRequestActivity.class);
        startActivity(i);
    }

    private void openAccessibilitySettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(i, REQ_OVERLAY);
    }

    private void updateStatus() {
        boolean overlay  = FloatingOverlayService.INSTANCE != null;
        boolean capture  = ScreenCaptureService.INSTANCE != null;
        boolean autoplay = AutoShootService.isReady();
        tvStatus.setText("Overlay: " + (overlay ? "ON" : "OFF")
                + "  |  Capture: " + (capture ? "ON" : "OFF")
                + "  |  AutoPlay: " + (autoplay ? "ready" : "disabled"));
    }
}
