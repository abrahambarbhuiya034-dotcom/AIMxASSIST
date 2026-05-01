package com.bitaim.carromaim;

  import android.app.Application;
  import android.util.Log;

  import com.bitaim.carromaim.overlay.OverlayPackage;
  import com.facebook.react.PackageList;
  import com.facebook.react.ReactApplication;
  import com.facebook.react.ReactNativeHost;
  import com.facebook.react.ReactPackage;
  import com.facebook.soloader.SoLoader;

  import java.util.List;

  public class MainApplication extends Application implements ReactApplication {

      private static final String TAG = "AIMxASSIST";

      /** True once OpenCV native libs confirmed loaded. Checked by BoardDetector. */
      public static boolean cvReady = false;

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

      @Override
      public ReactNativeHost getReactNativeHost() { return mReactNativeHost; }

      @Override
      public void onCreate() {
          super.onCreate();
          SoLoader.init(this, false);
          initOpenCVSafe();
      }

      private void initOpenCVSafe() {
          try {
              System.loadLibrary("opencv_java4");
              cvReady = true;
              Log.i(TAG, "OpenCV loaded ok");
          } catch (Throwable t1) {
              Log.w(TAG, "Direct OpenCV load failed: " + t1.getMessage());
              try {
                  Class<?> loader = Class.forName("org.opencv.android.OpenCVLoader");
                  java.lang.reflect.Method m = loader.getMethod("initDebug");
                  Object result = m.invoke(null);
                  cvReady = Boolean.TRUE.equals(result);
                  Log.i(TAG, "OpenCVLoader.initDebug() = " + cvReady);
              } catch (Throwable t2) {
                  Log.e(TAG, "OpenCV unavailable — auto-detect disabled: " + t2.getMessage());
                  cvReady = false;
              }
          }
      }
  }
  