# Bloom

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-3DDC84.svg)](#)

## Overview

**Bloom** is a privacy-first menstrual cycle and reproductive health tracking application for Android. Built with an offline-first, encrypted-by-default architecture, all sensitive health data stays under your control—stored locally in an encrypted database and optionally synchronized end-to-end encrypted with a partner via your own Google Drive.

The application combines a **Kotlin Multiplatform + Compose Multiplatform** UI and state layer (`shared/` and `androidApp/`) with a **Rust** core (`rust_core/`) that handles predictive cycle calculations and cryptographic operations over JNI.

## Key Features

* **4-Phase Cycle Dial & Predictions:** Interactive 4-phase cycle visualizer and predictive tracking powered by the native Rust core.
* **Comprehensive Logging:** Log period flow (including 0-flow / non-bleeding days), pain levels, symptoms, and notes.
* **Encrypted Offline-First Storage:** Local data is stored in **SQLDelight** backed by **SQLCipher**, with database keys protected by **AndroidKeyStore (AES-256-GCM)** and optional **BiometricPrompt** app lock.
* **End-to-End Encrypted Partner Sync:** Connect with a partner using **Google Sign-In** and **Google Drive** sync (via Ktor & WorkManager), secured with **ChaCha20-Poly1305**, **BLAKE3**, and **Argon2id** in Rust.
* **Owner & Partner Modes:** Asymmetric access with 6-bit granular partner permissions so owners control exactly what cycle details are shared.
* **Local Reminders:** Cycle and logging notifications are scheduled on-device via Android `AlarmManager` with no third-party push servers (no FCM/APNs).
* **11-Language Localization:** Supports English, Arabic, German, Spanish, French, Hindi, Italian, Japanese, Portuguese, Russian, and Chinese.
* **Free & Open Source:** Licensed under GPLv3 with no subscriptions, paywalls, or tracking.

## Technical Architecture

* **Core Engine (`rust_core/`):** Rust library compiled via `cargo-ndk` (JNI) for `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86` with 16 KB page-size alignment. Handles cycle predictions and E2E encryption (`chacha20poly1305`, `blake3`, `argon2`).
* **Shared Module (`shared/`):** Kotlin Multiplatform & Compose Multiplatform shared UI, domain logic, SQLDelight/SQLCipher database, and Ktor networking. *(Currently targets Android; iOS targets are not yet configured.)*
* **Android App (`androidApp/`):** Native Android entry point (`com.bloom.android`, `minSdk 24`, `targetSdk 36`), WorkManager background sync, `AlarmManager` notifications, BiometricPrompt, and R8 release configuration.

## Getting Started

### Prerequisites

* [JDK 17+](https://adoptium.net/)
* [Android Studio](https://developer.android.com/studio) / Android SDK (`compileSdk 36`, NDK `27.0.12077973`)
* [Rust Toolchain](https://rustup.rs/) (`rustup`, `cargo`)
* [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk) and Android Rust targets:
  ```bash
  cargo install cargo-ndk
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
  ```

### Installation & Build

1. **Clone the repository:**
   ```bash
   git clone https://github.com/dytamg/Bloom.git
   cd Bloom
   ```

2. **Build the debug APK:**
   Gradle automatically invokes `buildRustAndroid` (`cargo-ndk`) before compiling Android targets:
   ```bash
   ./gradlew :androidApp:assembleDebug
   ```

3. **Run tests:**
   ```bash
   cargo test --manifest-path rust_core/Cargo.toml
   ./gradlew test
   ```

4. **Build a release bundle (R8 minified):**
   ```bash
   ./gradlew :androidApp:bundleRelease
   ```

## Project Structure

```text
Bloom/
├── androidApp/   # Android application entry point, R8 config, and manifests
├── shared/       # Kotlin Multiplatform & Compose UI, SQLDelight/SQLCipher, Drive sync
├── rust_core/    # Rust cycle prediction engine & E2E crypto (JNI)
├── playstore/    # Play Store listing and release metadata
└── scripts/      # Asset generation and developer utility scripts
```

## License

This project is licensed under the GNU General Public License v3.0 — see the [LICENSE](LICENSE) file for details.
