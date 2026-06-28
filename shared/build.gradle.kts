import java.util.Properties

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
    id("com.android.kotlin.multiplatform.library")
    id("app.cash.sqldelight")
    id("org.jetbrains.compose")
}

kotlin {
    applyDefaultHierarchyTemplate()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.novarytm.shared"
        compileSdk = 36
        minSdk = 24
        
        withHostTest {}
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        packaging {
            jniLibs {
                useLegacyPackaging = false
            }
        }
    }
    
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("app.cash.sqldelight:runtime:2.0.2")
            implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.components.resources)
            
            // Lifecycle
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.0")
            
            // Networking and Serialization for Drive Sync
            val ktorVersion = "3.5.0"
            implementation("io.ktor:ktor-client-core:$ktorVersion")
            implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
            implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            
            // QR Code Generation
            // implementation("io.github.g0dkar:qrcode-kotlin:4.5.0")
        }
        androidMain.dependencies {
            implementation("app.cash.sqldelight:android-driver:2.0.2")
            api("app.cash.sqldelight:sqlite-driver:2.0.2")
            api("net.zetetic:sqlcipher-android:4.6.1")
            implementation("androidx.sqlite:sqlite-framework:2.4.0")
            api("androidx.activity:activity-compose:1.8.1")
            api("androidx.appcompat:appcompat:1.6.1")
            api("androidx.core:core-ktx:1.13.0")
            api("androidx.graphics:graphics-path:1.0.1")
            api("androidx.biometric:biometric:1.2.0-alpha05")
            api("androidx.security:security-crypto:1.1.0-alpha06")
            implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
            implementation("io.ktor:ktor-client-android:3.5.0")
            
            // Google Sign In
            implementation("com.google.android.gms:play-services-auth:21.0.0")
        }
        iosMain.dependencies {
            implementation("app.cash.sqldelight:native-driver:2.0.2")
            implementation("io.ktor:ktor-client-darwin:3.5.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

sqldelight {
    databases.register("NovarytmDatabase") {
        packageName.set("com.novarytm.db")
    }
}

// Rust Integration Tasks
val rustProjectDir = layout.projectDirectory.dir("../rust_core")
val jniLibsDir = layout.projectDirectory.dir("src/androidMain/jniLibs")

val localPropertiesFile = rootProject.file("local.properties")
val localPropertiesNdkDir = if (localPropertiesFile.exists()) {
    val properties = Properties()
    localPropertiesFile.inputStream().use { properties.load(it) }
    properties.getProperty("ndk.dir")
} else null

val defaultNdkDir = File(System.getProperty("user.home"), "Android/Sdk/ndk").let { ndkDir ->
    if (ndkDir.exists()) {
        ndkDir.listFiles()?.filter { it.isDirectory && (it.list()?.isNotEmpty() ?: false) }
            ?.sortedByDescending { it.name }?.firstOrNull()?.absolutePath
    } else null
} ?: File(System.getProperty("user.home"), "Android/Sdk/ndk-bundle").absolutePath

val resolvedNdkHome = System.getenv("ANDROID_NDK_HOME")
    ?: project.findProperty("ndk.dir") as? String
    ?: localPropertiesNdkDir
    ?: defaultNdkDir

tasks.register<Exec>("buildRustAndroid") {
    group = "build"
    description = "Builds Rust core library for Android targets using cargo-ndk"

    inputs.dir(rustProjectDir).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(jniLibsDir)

    environment("ANDROID_NDK_HOME", resolvedNdkHome)
    environment("RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")
    workingDir(rustProjectDir)
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64", "-t", "x86", "-o", jniLibsDir.asFile.absolutePath, "build", "--release")
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("Android") }.configureEach {
    dependsOn("buildRustAndroid")
}

tasks.matching { it.name.contains("JniLibFolders") }.configureEach {
    dependsOn("buildRustAndroid")
}
