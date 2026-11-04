# Graph Report - Bloom-main  (2026-11-04)

## Corpus Check
- 75 files · ~43,530 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 29 file(s) not represented in the graph (top: .xml 18, (none) 3, .properties 3)

## Summary
- 937 nodes · 1797 edges · 67 communities (24 shown, 43 thin omitted)
- Extraction: 96% EXTRACTED · 4% INFERRED · 0% AMBIGUOUS · INFERRED: 69 edges (avg confidence: 0.86)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `31e31c7e`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Rust Cryptographic & Cycle Core
- createDatabase
- Cycle Tracker & QA Suite
- JNI FFI RustBridge Contract
- iOS Keychain Secure Storage
- Android Encrypted Keystore Storage
- Encrypted Cloud Sync Engine
- Architecture & Play Store Governance
- Android JNI Native Bridge
- Multiplatform Storage Expect API
- Google OAuth2 Authentication
- Health Hub & Contraception UI
- Google Drive REST Client
- App Lifecycle & Theme Root
- Partner Pairing & Localization
- Android Alarm & Notification Engine
- iOS SwiftUI Host Bridge
- Calendar Grid & Period Grouping
- SQLCipher Android Database Driver
- Session State Machine
- Biometric Prompt & LockScreen
- Onboarding Credential Lifecycle
- Google Auth Expect Interface
- Android Launcher Icon Assets
- SessionManager.kt
- Compose Shared Brand Resources
- Navigation Screen Routes
- Android Drawable Brand Mark
- Adaptive Round Launcher Icon
- Android Back Navigation Handler
- Common Notification Scheduler API
- Contraception Reference Library
- Theme Color Palettes
- iOS Notification Scheduler
- Common Back Navigation Expect
- LockScreen.kt
- Recovery Key Input Screen
- Modifier
- iOS Back Navigation Stub
- Welcome & Login Screen
- Rust Cargo Manifest
- Zero-Permission Privacy Model
- ShareSyncCodeScreen.kt
- MainDashboard.kt
- androidMain/kotlin/com/novarytm/auth/BiometricAuthenticator.kt
- MainActivity.kt
- ConnectPartnerScreen.kt
- MainDashboard
- roundedcornershape
- iosMain/kotlin/com/novarytm/auth/BiometricAuthenticator.kt
- iosMain/kotlin/com/novarytm/db/DatabaseHelper.kt
- query_graph.sh

## God Nodes (most connected - your core abstractions)
1. `SecureStorageManager` - 44 edges
2. `SecureStorageManager` - 35 edges
3. `AppContent()` - 35 edges
4. `RustBridge` - 33 edges
5. `SecureStorageManager` - 32 edges
6. `CycleTracker` - 30 edges
7. `SyncManager` - 28 edges
8. `App()` - 22 edges
9. `PartnerPermissions` - 21 edges
10. `GoogleDriveManager` - 20 edges

## Surprising Connections (you probably didn't know these)
- `1. Mandatory Graph-First Discovery (Before Editing)` --references--> `SyncManager`  [INFERRED]
  GEMINI.md → shared/src/commonMain/kotlin/com/novarytm/sync/SyncManager.kt
- `1. Mandatory Graph-First Discovery (Before Editing)` --references--> `CycleTracker`  [INFERRED]
  GEMINI.md → shared/src/commonMain/kotlin/com/novarytm/tracker/CycleTracker.kt
- `1. Mandatory Graph-First Discovery (Before Editing)` --references--> `AppContent()`  [INFERRED]
  GEMINI.md → shared/src/commonMain/kotlin/com/novarytm/ui/App.kt
- `1. Mandatory Graph-First Discovery (Before Editing)` --references--> `GoogleDriveManager`  [INFERRED]
  GEMINI.md → shared/src/commonMain/kotlin/com/novarytm/sync/GoogleDriveManager.kt
- `Asymmetric Partner Access Control` --semantically_similar_to--> `Partner Sync vs Open User Communication Distinction`  [INFERRED] [semantically similar]
  README.md → playstore/content_rating.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Play Store Privacy and Safety Declarations Framework** — playstore_release_checklist_open_beta_procedure, playstore_data_safety_declaration, playstore_content_rating_iarc_assessment, playstore_privacy_policy_zero_cloud_architecture [EXTRACTED 1.00]
- **Secure Partner Synchronization and Storage Architecture** — readme_secure_partner_pairing, playstore_privacy_policy_google_drive_sync, walkthrough_ios_google_auth_lockout_fix, readme_asymmetric_access_control [EXTRACTED 1.00]
- **Database Initialization, Rekeying, and Decryption Sequence** — readme_sqldelight_database, walkthrough_splash_screen_deadlock_fix, walkthrough_sqlcipher_raw_key_bypass_fix, playstore_privacy_policy_cryptographic_suite [INFERRED 0.95]

## Communities (67 total, 43 thin omitted)

### Community 1 - "Rust Cryptographic & Cycle Core"
Cohesion: 0.05
Nodes (63): analyze_cycle_history_internal(), calculate_next_cycle_native_internal(), calculate_phase_internal(), decrypt_from_sync_internal(), decrypt_payload_internal(), derive_key_legacy(), derive_key_secure(), encrypt_data() (+55 more)

### Community 2 - "createDatabase"
Cohesion: 0.23
Nodes (4): createDatabase(), ColumnAdapter, ColumnAdapter, DriverFactory

### Community 3 - "Cycle Tracker & QA Suite"
Cohesion: 0.08
Nodes (6): CycleAnalysis, CyclePrediction, CyclePredictionResult, CycleTracker, SyncManagerTest, ColumnAdapter

### Community 4 - "JNI FFI RustBridge Contract"
Cohesion: 0.06
Nodes (3): RustBridge, PartnerPermissions, RustBridge

### Community 7 - "Encrypted Cloud Sync Engine"
Cohesion: 0.09
Nodes (6): SyncManager, SyncBirthControl, SyncEntry, SyncPayload, SyncPregnancyTest, SyncReminder

### Community 8 - "Architecture & Play Store Governance"
Cohesion: 0.07
Nodes (25): IARC Content Rating Evaluation, Google Play Data Safety Declaration, Data Safety Encryption in Transit and at Rest, User-Controlled Data Deletion Mechanism, Multi-Layer Cryptographic Security Suite, Client-Side Encrypted Google Drive Sync, Zero Cloud Architecture Policy, Play Store Health App Policy Compliance (+17 more)

### Community 11 - "Google OAuth2 Authentication"
Cohesion: 0.08
Nodes (5): GoogleAuthManager, rememberGoogleAuthManager(), GoogleAuthManager, NSObject, rememberGoogleAuthManager()

### Community 12 - "Health Hub & Contraception UI"
Cohesion: 0.13
Nodes (10): BCOption, BirthControlTab(), ContraceptiveMethodRing(), HealthHubScreen(), InfoChip(), PregnancyTestTab(), PrivacyNote(), QuizOverlay() (+2 more)

### Community 13 - "Google Drive REST Client"
Cohesion: 0.14
Nodes (10): 1. Mandatory Graph-First Discovery (Before Editing), 2. Continuous Graph Synchronization (After Editing), 3. Subagent Inheritance, Bloom Architecture & Graph-First Development Guidelines, AboutResponse, DriveFileAppFolder, DriveFileListAppFolder, DriveStorageFullException (+2 more)

### Community 14 - "App Lifecycle & Theme Root"
Cohesion: 0.18
Nodes (3): App(), AppContent(), SplashScreen()

### Community 15 - "Partner Pairing & Localization"
Cohesion: 0.25
Nodes (6): CustomDateRangePickerDialog(), IntensityButton(), LogEntryScreen(), toHumanReadable(), formatDateByRegion(), getCurrentRegionCode()

### Community 17 - "iOS SwiftUI Host Bridge"
Cohesion: 0.15
Nodes (7): ComposeView, ContentView, .body, iosApp, .body, shared, SwiftUI

### Community 18 - "Calendar Grid & Period Grouping"
Cohesion: 0.24
Nodes (10): CalendarGrid(), CalendarHeader(), CalendarLegend(), CalendarScreen(), groupPeriodEntries(), HistoryGroupItem(), HistoryItem(), LegendItem() (+2 more)

### Community 20 - "Session State Machine"
Cohesion: 0.19
Nodes (5): Locked, Onboarding, SessionManager, SessionState, Unlocked

### Community 21 - "Biometric Prompt & LockScreen"
Cohesion: 0.22
Nodes (3): BiometricPromptEffect(), BiometricsSetupScreen(), LockScreen()

### Community 22 - "Onboarding Credential Lifecycle"
Cohesion: 0.22
Nodes (7): BiometricsSetup, ConnectPartner, CreatePin, OnboardingStage, RecoveryWarning, Restore, Welcome

### Community 24 - "Android Launcher Icon Assets"
Cohesion: 0.33
Nodes (6): Android Launcher Icon (xxxhdpi Density), Blooming Flower Motif, Circular App Icon Presentation, Plum, Cyan, and Pink Color Palette, Hatching Eggshell Motif, Bloom Android Launcher Icon (xxxhdpi)

### Community 27 - "Compose Shared Brand Resources"
Cohesion: 0.38
Nodes (6): Compose Multiplatform Shared Resource Logo Design, LockScreen and Authentication Branding Role, Circular Badge with Plum, Neon Cyan, and Pink Palette, Bloom Compose Multiplatform Logo Asset, App Splash Screen and Startup Identity, Blooming Flower Emerging from Cracked Eggshell Motif

### Community 28 - "Navigation Screen Routes"
Cohesion: 0.29
Nodes (7): Calendar, Home, Hub, PartnerPermissions, PartnerSettings, Screen, ShareSyncCode

### Community 29 - "Android Drawable Brand Mark"
Cohesion: 0.40
Nodes (5): Blooming Flower Motif, Bloom Brand Logo Mark, Circular App Icon / Badge Layout, Logo Color Scheme: Deep Plum, Neon Cyan, and Pink, Hatching Eggshell Motif

### Community 30 - "Adaptive Round Launcher Icon"
Cohesion: 0.83
Nodes (4): Adaptive Icon Circular Masking, Bloom Floral Rebirth Brand Motif, Bloom Round Launcher Icon (xxxhdpi), Round Launcher Icon Design

### Community 41 - "Modifier"
Cohesion: 0.18
Nodes (4): BirthControlQuiz(), PartnerPermissionsScreen(), PermissionItem(), PartnerSettingsScreen()

### Community 57 - "ShareSyncCodeScreen.kt"
Cohesion: 0.17
Nodes (3): ShareSyncCodeScreen(), AppStrings, getCurrentLanguageCode()

### Community 62 - "MainDashboard"
Cohesion: 0.36
Nodes (4): CyclePhaseVisualizer(), MainDashboard(), OwnerStatsGrid(), PartnerStatsGrid()

## Knowledge Gaps
- **21 isolated node(s):** `Restore`, `Welcome`, `ShareSyncCode`, `shared`, `rust_core` (+16 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 306 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **43 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `App()` connect `App Lifecycle & Theme Root` to `Cycle Tracker & QA Suite`, `Encrypted Cloud Sync Engine`, `Google Drive REST Client`, `Session State Machine`, `Biometric Prompt & LockScreen`, `Google Auth Expect Interface`, `SessionManager.kt`, `MainActivity.kt`, `roundedcornershape`?**
  _High betweenness centrality (0.162) - this node is a cross-community bridge._
- **Are the 16 inferred relationships involving `AppContent()` (e.g. with `1. Mandatory Graph-First Discovery (Before Editing)` and `AppBackHandler()`) actually correct?**
  _`AppContent()` has 16 INFERRED edges - model-reasoned connections that need verification._
- **What connects `Restore`, `Welcome`, `ShareSyncCode` to the rest of the system?**
  _21 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `CalendarScreen.kt` be split into smaller, more focused modules?**
  _Cohesion score 0.09259259259259259 - nodes in this community are weakly interconnected._
- **Why does `MainViewController()` connect `SessionManager.kt` to `iosMain/kotlin/com/novarytm/db/DatabaseHelper.kt`, `createDatabase`, `JNI FFI RustBridge Contract`, `iOS Keychain Secure Storage`, `App Lifecycle & Theme Root`?**
  _High betweenness centrality (0.098) - this node is a cross-community bridge._
- **Should `Rust Cryptographic & Cycle Core` be split into smaller, more focused modules?**
  _Cohesion score 0.052795031055900624 - nodes in this community are weakly interconnected._
- **Why does `PartnerPermissions` connect `JNI FFI RustBridge Contract` to `CalendarScreen.kt`, `Encrypted Cloud Sync Engine`, `Android JNI Native Bridge`, `Modifier`, `Health Hub & Contraception UI`, `Calendar Grid & Period Grouping`, `MainDashboard`?**
  _High betweenness centrality (0.083) - this node is a cross-community bridge._