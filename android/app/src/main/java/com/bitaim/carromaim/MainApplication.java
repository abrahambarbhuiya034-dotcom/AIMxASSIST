package com.bitaim.carromaim;

import android.app.Application;

import com.bitaim.carromaim.overlay.OverlayPackage;
import com.facebook.react.PackageList;
import com.facebook.react.ReactApplication;
import com.facebook.react.ReactNativeHost;
import com.facebook.react.ReactPackage;
import com.facebook.soloader.SoLoader;

import java.util.List;

public class MainApplication extends Application implements ReactApplication {

    /** Pure-Java BoardDetector — no OpenCV needed. */
    public static final boolean cvReady = true;

    private final ReactNativeHost mReactNativeHost = new ReactNativeHost(this) {
        @Override public boolean getUseDeveloperSupport() { return false; }

        @Override
        protected List<ReactPackage> getPackages() {
            List<ReactPackage> packages = new PackageList(this).getPackages();
            packages.add(new OverlayPackage());
            return packages;
        }

        @Override
        protected String getJSMainModuleName() { return "index"; }
    };

    @Override public ReactNativeHost getReactNativeHost() { return mReactNativeHost; }

    @Override
    public void onCreate() {
        super.onCreate();
        SoLoader.init(this, false);
    }
}
