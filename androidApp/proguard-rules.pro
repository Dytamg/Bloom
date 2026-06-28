# JNI rules
-keep class com.novarytm.ffi.RustBridge { *; }

# Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# General Coroutines / Flow
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Ensure SQLDelight classes survive if needed by reflection
-keep class app.cash.sqldelight.** { *; }

# Keep Compose metadata
-keep class androidx.compose.** { *; }
