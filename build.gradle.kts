// Root build.gradle.kts
plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.1.10" apply false
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
    id("com.android.kotlin.multiplatform.library") version "9.2.1" apply false
    id("com.android.library") version "9.2.1" apply false
    id("com.android.application") version "9.2.1" apply false
    id("app.cash.sqldelight") version "2.0.2" apply false
    id("org.jetbrains.compose") version "1.7.1" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
