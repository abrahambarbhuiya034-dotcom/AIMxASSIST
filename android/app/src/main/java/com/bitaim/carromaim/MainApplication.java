package com.bitaim.carromaim;

import android.app.Application;

public class MainApplication extends Application {

    /** Pure-Java BoardDetector — no OpenCV needed. */
    public static final boolean cvReady = true;

    @Override
    public void onCreate() {
        super.onCreate();
    }
}
