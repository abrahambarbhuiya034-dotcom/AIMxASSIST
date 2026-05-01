# React Native
-keep class com.facebook.react.** { *; }
-keep class com.facebook.hermes.** { *; }
-keep class com.facebook.jni.** { *; }

# BitAim overlay classes — never obfuscate these
-keep class com.bitaim.carromaim.overlay.** { *; }
-keep class com.bitaim.carromaim.MainActivity { *; }
-keep class com.bitaim.carromaim.MainApplication { *; }
