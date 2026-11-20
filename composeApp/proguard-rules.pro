# Smile release R8 — keep minimal; jangan over-keep.

# WebView JavascriptInterface (SmileNative bridge)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class co.id.multifab.smile.bridge.SmileJsBridge { *; }

# Firebase Messaging
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.internal.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# Play Services Location
-keep class com.google.android.gms.location.** { *; }

# Kotlin / serialization used by native config & JSON helpers
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-dontwarn kotlinx.serialization.**
-keep class kotlinx.serialization.** { *; }

# Application & FCM service entry points
-keep class co.id.multifab.smile.SmileApplication { *; }
-keep class co.id.multifab.smile.fcm.SmileFirebaseMessagingService { *; }
-keep class co.id.multifab.MainActivity { *; }

# WorkManager / receivers used by location tracking
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}
-keep class * extends android.content.BroadcastReceiver

# Enums / Parcelable if any
-keepclassmembers enum * { *; }
