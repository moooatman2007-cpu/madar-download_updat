# ProGuard / R8 Rules for MIDAR Release Build Optimization

# Preserve source file names and line numbers for stacktraces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ------------------------------------------------------------------------------
# 1. Kotlin Serialization Rules
# ------------------------------------------------------------------------------
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

-keepclassmembers class * {
    public static final **Companion Companion;
}

-keepclassmembers class **$Companion {
    public kotlinx.serialization.KSerializer serializer(...);
}

-keep @kotlinx.serialization.Serializable class com.example.core.model.** { *; }

# ------------------------------------------------------------------------------
# 2. Supabase & Ktor Client Rules
# ------------------------------------------------------------------------------
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }

# Ktor optionally checks Java Desktop JVM Management APIs which are not present on Android Runtime
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# ------------------------------------------------------------------------------
# 3. ML Kit Barcode Scanning & CameraX Rules
# ------------------------------------------------------------------------------
-keep class com.google.mlkit.vision.barcode.** { *; }
-keep class com.google.android.gms.vision.** { *; }

# ------------------------------------------------------------------------------
# 4. PDF Generation
# ------------------------------------------------------------------------------
-keep class android.graphics.pdf.PdfDocument { *; }

# --- Release hardening: strip verbose logging so no ids / raw responses reach logcat ---
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}
