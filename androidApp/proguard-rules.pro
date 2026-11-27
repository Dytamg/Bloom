# ==============================================================================
# Stacktraces & Line Numbers (Play Console Deobfuscation)
# ==============================================================================
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ==============================================================================
# Rust JNI Bridge
# ==============================================================================
-keep class com.bloom.ffi.RustBridge { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.bloom.ffi.RustBridge {
    native <methods>;
}
-keep class com.bloom.ffi.RustBridge$** { *; }

# ==============================================================================
# Official kotlinx.serialization R8 Full-Mode Rules
# ==============================================================================
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Core serialization annotations: keep Companion field and serializer() methods
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Companion of every @Serializable class
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep serializer(...) on Companion of @Serializable classes (non-polymorphic)
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep INSTANCE & descriptor of generated $serializer classes
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1>$serializer {
    static <1>$serializer INSTANCE;
    kotlinx.serialization.descriptors.SerialDescriptor descriptor;
    kotlinx.serialization.descriptors.SerialDescriptor getDescriptor();
}

# Keep serializer() for polymorphic / sealed serializable hierarchies
-keepclassmembers class * extends kotlinx.serialization.internal.GeneratedSerializer {
    *** INSTANCE;
}

# Explicit application serializable domain models (BirthControlInfo, SyncEntry, SyncModels, GoogleDriveManager models, Tracker models)
-keep class com.bloom.ui.BirthControlInfo { *; }
-keep class com.bloom.ui.BirthControlInfo$** { *; }
-keep class com.bloom.sync.** { *; }
-keep class com.bloom.tracker.** { *; }

# ==============================================================================
# SQLCipher & SQLDelight
# ==============================================================================
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**
-keep class app.cash.sqldelight.** { *; }
-keep class com.bloom.db.** { *; }

# ==============================================================================
# Ktor Client ServiceLoader & Coroutines
# ==============================================================================
-keep class io.ktor.client.engine.HttpClientEngineContainer
-keep class * implements io.ktor.client.engine.HttpClientEngineContainer {
    public <init>();
}
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.debug.**
-dontwarn org.slf4j.**

# ==============================================================================
# Tink / AndroidX Security Crypto
# ==============================================================================
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.util.KeysDownloader
-dontwarn com.google.crypto.tink.util.KeysDownloader$*
-dontwarn com.google.api.client.http.**
-dontwarn org.joda.time.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# ==============================================================================
# WorkManager Reflective Workers
# ==============================================================================
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ==============================================================================
# Android BroadcastReceiver & Google Play Services Auth
# ==============================================================================
-keep class com.bloom.notifications.NotificationReceiver { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.auth.** { *; }
