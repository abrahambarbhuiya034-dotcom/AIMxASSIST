# BitAim v2.0 — Build Guide

## What's new vs v1.0
- **Auto-detection** of striker, coins (white/black/red queen) and pockets via OpenCV.
- **Screen capture** via MediaProjection (per-session permission).
- **Locked striker** — touching it no longer drags it; the detector decides.
- **Multi-line trajectory simulation** — every shot type renders simultaneously,
  including coin-on-coin chain reactions and 1- or 2-cushion bounces.
- **Pocket prediction** — paths that end in a pocket are highlighted green.

---

## What you need installed on your PC
1. **Node.js** (v18 LTS) → https://nodejs.org
2. **JDK 11** → https://adoptium.net
3. **Android Studio** (Hedgehog or newer) → https://developer.android.com/studio
4. In Android Studio → SDK Manager → install **Android SDK API 34** and **NDK r23+**.

---

## Step-by-step build

### 1. Extract the zip
```
C:\Projects\BitAimApp\
```

### 2. Install JS dependencies
Open a terminal in the project folder:
```
npm install
```
(takes 3–5 min)

### 3. Open the `android/` subfolder in Android Studio
Wait for Gradle sync. **First sync takes 10–15 min** because it has to download
OpenCV from JitPack (~30 MB) plus React Native dependencies.

### 4. Build the APK
**Menu → Build → Build Bundle(s)/APK(s) → Build APK(s)**

Output:
```
android/app/build/outputs/apk/debug/app-debug.apk
```

### 5. Install on your phone
Either copy the APK to your phone manually, or with USB debugging on:
```
npx react-native run-android
```

---

## Permissions the user must grant
1. **Display over other apps** — required once.
2. **Screen capture (MediaProjection)** — Android prompts every time you turn on
   Auto-Detect, by design. You cannot suppress this; it's a system policy.
3. **Notifications** (Android 13+) — for the foreground-service notification.

---

## Common build errors & fixes

| Error | Fix |
|-------|-----|
| `SDK location not found` | Edit `android/local.properties`, set `sdk.dir=...` |
| `Could not find :opencv:4.5.3.0` | JitPack is unreachable. Re-run sync, check internet. |
| `Duplicate libopencv_java4.so` | Already handled by `pickFirst` in `app/build.gradle`. |
| `compileSdkVersion 34 not found` | SDK Manager → install Android 14 (API 34) platform. |
| `JAVA_HOME not set` | Set env var to your JDK 11 install. |
| `NDK not configured` | SDK Manager → SDK Tools tab → install NDK (Side by side). |
| `failed to find Build Tools 34.0.0` | SDK Manager → install Build Tools 34.0.0. |
| App installs but immediately closes | Check `adb logcat | grep BitAim` — usually OpenCV init failure on x86 emulator. Use a real arm64 device. |
| Auto-detect shows no coins | Lower **Detection Sensitivity** slider in the app, or adjust Margin Calibration. |

---

## How auto-detection works (quick explainer)
1. User toggles "Auto-Detect" → Android shows MediaProjection consent dialog.
2. On consent, `ScreenCaptureService` opens a `VirtualDisplay` that mirrors the
   screen into an `ImageReader` at ~30 FPS.
3. Each frame is fed to `BoardDetector`:
   - Downscales to 640px wide
   - Grayscale + median blur
   - `HoughCircles` finds candidate circles
   - Each circle's center HSV pixel is sampled to classify white/black/red
   - Largest white circle on the lower half = striker
   - Bounding box of all detected coins = board, padded ~8 %
   - 4 corners of board = pockets
4. The resulting `GameState` is pushed to `AimOverlayView`.
5. When the user taps an aim point, `TrajectorySimulator` runs a 4-second
   step-based physics sim with elastic collisions, friction, and cushion
   bounces, recording every body's full path.
6. `AimOverlayView` draws each path in its own color.

---

## Project file map (v2)
```
BitAimApp/
├── App.tsx
├── index.js
├── app.json
├── package.json
├── babel.config.js
├── tsconfig.json
└── android/
    ├── build.gradle
    ├── settings.gradle
    ├── gradle.properties
    ├── gradle/wrapper/
    └── app/
        ├── build.gradle              ← OpenCV dependency added here
        ├── proguard-rules.pro
        └── src/main/
            ├── AndroidManifest.xml   ← MediaProjection perms + new services
            ├── java/com/bitaim/carromaim/
            │   ├── MainActivity.java
            │   ├── MainApplication.java   ← OpenCV init
            │   ├── overlay/
            │   │   ├── FloatingOverlayService.java
            │   │   ├── AimOverlayView.java          ← multi-line render
            │   │   ├── OverlayModule.java
            │   │   └── OverlayPackage.java
            │   ├── capture/                          ← NEW
            │   │   ├── MediaProjectionRequestActivity.java
            │   │   └── ScreenCaptureService.java
            │   └── cv/                               ← NEW
            │       ├── BoardDetector.java
            │       ├── Coin.java
            │       ├── GameState.java
            │       └── TrajectorySimulator.java
            └── res/...
```
