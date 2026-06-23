# Add project specific ProGuard rules here.
# Dioptimasi untuk mengurangi ukuran APK dan menjaga stabilitas library OpenCSV

# === AGGRESSIVE OPTIMIZATION ===
-dontpreverify
-verbose
-dontwarn **
-ignorewarnings

# === OPTIMIZATION SETTINGS ===
-optimizationpasses 7
-allowaccessmodification
-repackageclasses ''
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable,Signature,EnclosingMethod,InnerClasses,*Annotation*

# === REMOVE DEBUGGING ===
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** println(...);
}

# === ANDROID COMPONENT KEEPS ===
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# === OPENCSV & APACHE COMMONS (CRITICAL) ===
# OpenCSV menggunakan reflection secara intensif
-keep class com.opencsv.** { *; }
-keep class org.apache.commons.beanutils.** { *; }
-keep class org.apache.commons.collections.** { *; }
-keep class org.apache.commons.lang3.** { *; }
-keep class org.apache.commons.logging.** { *; }
-dontwarn com.opencsv.**
-dontwarn org.apache.commons.**

# === PROJECT DATA CLASSES ===
# Menjaga data class agar sinkronisasi CSV ke Object tidak error
-keep class com.edukreasi.Exam.SiswaInfo { *; }
-keep class com.edukreasi.Exam.TokenResponse { *; }

# === WEBVIEW & JAVASCRIPT ===
# Sangat penting agar fungsi di WebViewActivity tetap bisa dipanggil dari JS
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# === GOOGLE & FIREBASE ===
-keep class com.google.android.gms.** { *; }
-keep class com.google.firebase.** { *; }
-dontwarn com.google.android.gms.**
-dontwarn com.google.firebase.**
