# Bloom

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Platform: iOS | Android](https://img.shields.io/badge/Platform-iOS%20%7C%20Android-lightgrey.svg)](#)

## Overview

**Bloom** is a privacy-first, cross-platform mobile application designed for comprehensive menstrual cycle and reproductive health tracking. Built with an offline-first architecture, the application ensures maximum data privacy by storing all sensitive user health information locally on the device.

The application utilizes a highly optimized hybrid architecture: **Kotlin Multiplatform (KMP)** handles cross-platform state, UI, and shared business logic, while a **Rust** core powers all "bare-metal" predictive calculations and secure data processing. Furthermore, the app features a unique, zero-cost partner synchronization protocol, allowing users to securely pair their device with a partner using a one-time unique authorization code.

## Key Features

*   **High-Performance Core:** All complex reproductive health calculations and predictive models are executed natively via a secure Rust library.
*   **Offline-First Data Storage:** Core cycle data is stored securely on-device using local SQL (SQLDelight), ensuring users retain complete sovereignty over their data.
*   **Secure Partner Pairing:** Users can generate an ephemeral, unique authorization code to link a partner's device via a secure handshake.
*   **Asymmetric Access Control:** Linked partners are granted read-only access to specific cycle phases and receive automated push notifications regarding relevant cycle events.
*   **Native Cross-Platform:** A unified Kotlin Multiplatform codebase targeting both Android and iOS natively, ensuring smooth performance without the overhead of web-based bridges.
*   **Zero-Cost Model:** Fully functional and completely free without subscriptions, paywalls, or premium tiers.

## Technical Architecture

*   **Core Calculations Engine:** Rust (compiled via JNI for Android and C-FFI for iOS)
*   **Client Framework:** Kotlin Multiplatform (KMP) 
*   **Local Database:** SQLDelight (Cross-platform SQLite driver)
*   **Push Notifications:** Firebase Cloud Messaging (FCM) / APNs (Apple Push Notification service)
*   **Authentication & Sync:** Ephemeral unique-code generation for secure device-to-device synchronization

## Getting Started

### Prerequisites

To build this project, you will need the toolchains for Kotlin/Android, iOS, and Rust:

*   [Rust Toolchain](https://rustup.rs/) (cargo, rustc)
*   [Android Studio](https://developer.android.com/studio) (with latest Android SDK & NDK)
*   [Xcode](https://developer.apple.com/xcode/) (for iOS compilation)
*   [JDK 17+](https://adoptium.net/)

### Installation & Build

1.  **Clone the repository:**
    ```bash
    git clone [https://github.com/dytamg/Bloom.git](https://github.com/dytamg/Bloom.git)
    cd Bloom
