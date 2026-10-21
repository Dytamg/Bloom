# JNI rules
-keep class com.novarytm.ffi.RustBridge { *; }

# SQLCipher rules
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**

# Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keep class *$$serializer { *; }
-keep class com.novarytm.tracker.** { *; }
-keep class com.novarytm.sync.** { *; }

# General Coroutines / Flow
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Ensure SQLDelight classes survive if needed by reflection
-keep class app.cash.sqldelight.** { *; }

# Keep Compose metadata
-keep class androidx.compose.** { *; }
