# AIMxASSIST v8.4

## Overview
AIMxASSIST is an Android aim-assist overlay app for Carrom Pool (Disc Pool). It uses a pure-Java computer vision board detector plus a ghost-ball physics AI to draw aim lines over any carrom game and optionally auto-shoot via Android Accessibility gestures.

## Architecture

### Web Preview (Replit — what you see in the browser)
- **artifacts/aim-assist/** — React + Vite web app that previews the app's UI
- Runs on port set via `$PORT`, served at path `/`

### Android App
- **Package:** `com.bitaim.carromaim`
- **App name:** AIMxASSIST
- **Min SDK:** 23 (Android 6.0+), Target SDK: 34
- **Version:** 8.4 (versionCode 7)

### Key Android Components
| Component | Role |
|-----------|------|
| `FloatingOverlayService` | Foreground service — shows floating button + aim lines over other apps |
| `AimOverlayView` | Custom canvas view — draws ghost-ball aim lines (up to 5 simultaneous) |
| `ScreenCaptureService` | MediaProjection + VirtualDisplay — captures screen at 30 fps |
| `BoardDetector` | Pure-Java pixel scanner — detects board, coins, striker, pockets |
| `CarromAI` | Ghost-ball geometry + minimax physics — finds best shots |
| `TrajectorySimulator` | Multi-body elastic collision simulator — validates shots physically |
| `AutoShootService` | AccessibilityService — injects swipe gestures to shoot striker |
| `OverlayModule` | React Native bridge — exposes all services to the JS/TSX UI |

### Low-End Device Optimisations
- **BoardDetector:** PROC_W=240px (vs 360) on devices with <1.5 GB RAM; scanStep=4 (vs 3)
- **ScreenCaptureService:** capture width capped at 480px (vs 720px) on low-end
- **CarromAI:** substeps=2 (vs 4); minimax depth=1 (vs 2); topN candidates=30 (vs 60)
- **FloatingOverlayService:** stable board threshold 40px (vs 20px) — fires faster after board settles

### Detection Pipeline
1. `detectByOrangeDensity()` — primary: find orange border density
2. `detectByPocketQuadrants()` — secondary: find dark pocket blobs in quadrants
3. `detectByWoodSurface()` — tertiary: find warm-tone wood pixels
4. `smartFallback()` — guaranteed: layout from screen dimensions + UI insets

## Directory Structure
```
artifacts/aim-assist/        # Web preview (Vite + React)
  src/App.tsx                # Web UI — controls, board canvas preview
  src/index.css              # Dark theme styles
  src/AppNative.tsx          # React Native UI shell
android/
  app/src/main/
    java/com/bitaim/carromaim/
      cv/                    # BoardDetector, CarromAI, TrajectorySimulator, Coin, GameState
      overlay/               # FloatingOverlayService, AimOverlayView, OverlayModule, OverlayPackage
      capture/               # ScreenCaptureService, MediaProjectionRequestActivity
      auto/                  # AutoShootService
    AndroidManifest.xml
    res/
      xml/accessibility_service_config.xml
      layout/view_floating_button.xml
      drawable/floating_btn_bg.xml, ic_launcher_foreground.xml
      values/strings.xml, styles.xml
  app/build.gradle
  build.gradle, settings.gradle, gradle.properties
  app/proguard-rules.pro
index.js, App.js             # React Native entry points
pnpm-workspace.yaml
```

## Permissions Required (Android)
- `SYSTEM_ALERT_WINDOW` — draw over other apps
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` — persistent services
- `WAKE_LOCK` — keep overlay alive during gameplay
- `POST_NOTIFICATIONS` — Android 13+ notification
- Accessibility Service — gesture injection for auto-shoot (user opt-in)

## Running the Web Preview
The workflow `artifacts/aim-assist: web` runs `pnpm dev` inside `artifacts/aim-assist/` on the assigned `$PORT`.

## Building the Android APK
```bash
cd android
./gradlew assembleRelease
# Sign with: /tmp/mykey.keystore (alias: mykey, pass: mypassword)
```
Build tools are at `/tmp/build-tools-extracted/android-14/`.
