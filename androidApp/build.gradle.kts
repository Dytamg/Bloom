import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun releaseCredential(propKey: String, envKey: String): String? =
    (keystoreProperties.getProperty(propKey) ?: System.getenv(envKey))
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

val releaseStoreFilePath = releaseCredential("storeFile", "RELEASE_STORE_FILE")
    ?: releaseCredential("RELEASE_STORE_FILE", "RELEASE_STORE_FILE")
val releaseStorePassword = releaseCredential("storePassword", "RELEASE_STORE_PASSWORD")
    ?: releaseCredential("RELEASE_STORE_PASSWORD", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseCredential("keyAlias", "RELEASE_KEY_ALIAS")
    ?: releaseCredential("RELEASE_KEY_ALIAS", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseCredential("keyPassword", "RELEASE_KEY_PASSWORD")
    ?: releaseCredential("RELEASE_KEY_PASSWORD", "RELEASE_KEY_PASSWORD")

val resolvedReleaseStoreFile = releaseStoreFilePath?.let { path ->
    val candidate = file(path)
    if (candidate.exists()) candidate else rootProject.file(path)
}
val hasReleaseSigning = resolvedReleaseStoreFile != null &&
    resolvedReleaseStoreFile.isFile &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

val localPropertiesFile = rootProject.file("local.properties")
val localPropertiesNdkDir = if (localPropertiesFile.isFile) {
    Properties().apply { localPropertiesFile.inputStream().use { load(it) } }.getProperty("ndk.dir")
} else null

val resolvedNdkDirectory: File? = sequenceOf(
    System.getenv("ANDROID_NDK_HOME"),
    project.findProperty("ndk.dir") as? String,
    localPropertiesNdkDir
).filterNotNull().map { File(it) }.firstOrNull { it.isDirectory }
    ?: run {
        val sdkDirPath = if (localPropertiesFile.isFile) {
            Properties().apply { localPropertiesFile.inputStream().use { load(it) } }.getProperty("sdk.dir")
        } else {
            System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        }
        val sdkDir = sdkDirPath?.let { File(it) } ?: File(System.getProperty("user.home"), "Android/Sdk")
        val ndkRoot = File(sdkDir, "ndk")
        ndkRoot.listFiles()
            ?.filter { it.isDirectory && File(it, "source.properties").isFile }
            ?.sortedByDescending { it.name }
            ?.firstOrNull()
    }

val resolvedNdkVersion: String? = resolvedNdkDirectory?.let { dir ->
    val sourcePropsFile = File(dir, "source.properties")
    if (sourcePropsFile.isFile) {
        Properties().apply { sourcePropsFile.inputStream().use { load(it) } }
            .getProperty("Pkg.Revision")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    } else null
}

android {
    namespace = "com.novarytm.android"
    compileSdk = 36

    if (resolvedNdkDirectory != null && resolvedNdkVersion != null) {
        ndkPath = resolvedNdkDirectory.absolutePath
        ndkVersion = resolvedNdkVersion
    } else {
        ndkVersion = "27.0.12077973"
    }

    defaultConfig {
        applicationId = "com.novarytm.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = resolvedReleaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            ndk {
                debugSymbolLevel = "FULL"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    sourceSets {
        getByName("main").assets.srcDirs("build/generated/composeAssets")
    }
}

val copyComposeAssets = tasks.register<Copy>("copyComposeAssets") {
    dependsOn(":shared:prepareComposeResourcesTaskForCommonMain")
    from(project(":shared").layout.buildDirectory.dir("generated/compose/resourceGenerator/preparedResources/commonMain/composeResources"))
    into(layout.buildDirectory.dir("generated/composeAssets/composeResources/novarytm.shared.generated.resources"))
}

tasks.matching { it.name.startsWith("generate") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(copyComposeAssets)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(projects.shared)
}
