package com.bitaim.carromaim.capture;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;

/**
 * Transparent helper Activity used solely to request MediaProjection consent.
 *
 * MediaProjectionManager.createScreenCaptureIntent() can only be launched
 * from an Activity, not a Service. So when the user taps "enable auto-detect"
 * in the React Native UI, OverlayModule launches this activity, which prompts
 * the system dialog and forwards the result to ScreenCaptureService.
 */
public class MediaProjectionRequestActivity extends Activity {

    private static final String TAG = "BitAim/MPRequest";
    private static final int REQ_CAPTURE = 4711;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Pre-O safety: avoid edge-case crashes by skipping anything < M
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            finish();
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            finish();
            return;
        }
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Log.i(TAG, "MediaProjection granted; forwarding to ScreenCaptureService");
                Intent svc = new Intent(this, ScreenCaptureService.class);
                svc.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
                svc.putExtra(ScreenCaptureService.EXTRA_DATA, data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
            } else {
                Log.w(TAG, "MediaProjection denied by user");
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
