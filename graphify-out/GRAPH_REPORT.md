# Graph Report - Bloom-main  (2026-11-09)

## Corpus Check
- 77 files · ~49,270 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 29 file(s) not represented in the graph (top: .xml 18, (none) 3, .properties 3)

## Summary
- 996 nodes · 1886 edges · 84 communities (24 shown, 60 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 85 edges (avg confidence: 0.86)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `32f4f396`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- CalendarScreen
- Rust Cryptographic & Cycle Core
- androidMain/kotlin/com/novarytm/auth/BiometricAuthenticator.kt
- CycleTracker
- JNI FFI RustBridge Contract
- iOS Keychain Secure Storage
- Android Encrypted Keystore Storage
- SyncManager
- Architecture & Play Store Governance
- Android JNI Native Bridge
- Multiplatform Storage Expect API
- Google OAuth2 Authentication
- Health Hub & Contraception UI
- Google Drive REST Client
- Android Alarm & Notification Engine
- iOS SwiftUI Host Bridge
- LockScreen.kt
- SQLCipher Android Database Driver
- Session State Machine
- Biometric Prompt & LockScreen
- Onboarding Credential Lifecycle
- Google Auth Expect Interface
- Android Launcher Icon Assets
- Bloom Architecture & Graph-First Development Guidelines
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
- createDatabase
- Recovery Key Input Screen
- Color
- iOS Back Navigation Stub
- AppContent
- Rust Cargo Manifest
- Zero-Permission Privacy Model
- MainActivity.kt
- ShareSyncCodeScreen.kt
- iosMain/kotlin/com/novarytm/db/DatabaseHelper.kt
- query_graph.sh
- generate_brand_assets.py
- alignment
- ConnectPartnerScreen.kt
- LogEntryScreen.kt

## God Nodes (most connected - your core abstractions)
1. `SecureStorageManager` - 44 edges
2. `SecureStorageManager` - 35 edges
3. `AppContent()` - 34 edges
4. `RustBridge` - 33 edges
5. `SecureStorageManager` - 32 edges
6. `CycleTracker` - 32 edges
7. `SyncManager` - 29 edges
8. `App()` - 21 edges
9. `SyncManagerTest` - 21 edges
10. `GoogleDriveManager` - 19 edges

## Surprising Connections (you probably didn't know these)
- `1. Mandatory Graph-First Discovery (Before Editing)` --references--> `CyclePhaseVisualizer()`  [INFERRED]
  GEMINI.md → shared/src/commonMain/kotlin/com/novarytm/ui/CyclePhaseVisualizer.kt
- `1. Mandatory Graph-First Discovery (Before Editing)` --references--> `GoogleDriveManager`  [INFERRED]
  GEMINI.md → shared/src/commonMain/kotlin/com/novarytm/sync/GoogleDriveManager.kt
- `1. Mandatory Graph-First Discovery (Before Editing)` --references--> `SessionManager`  [INFERRED]
  GEMINI.md → shared/src/commonMain/kotlin/com/novarytm/auth/SessionManager.kt
- `1. Mandatory Graph-First Discovery (Before Editing)` --references--> `CycleTracker`  [INFERRED]
  GEMINI.md → shared/src/commonMain/kotlin/com/novarytm/tracker/CycleTracker.kt
- `1. Mandatory Graph-First Discovery (Before Editing)` --references--> `SyncManager`  [INFERRED]
  GEMINI.md → shared/src/commonMain/kotlin/com/novarytm/sync/SyncManager.kt

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Play Store Privacy and Safety Declarations Framework** — playstore_release_checklist_open_beta_procedure, playstore_data_safety_declaration, playstore_content_rating_iarc_assessment, playstore_privacy_policy_zero_cloud_architecture [EXTRACTED 1.00]
- **Secure Partner Synchronization and Storage Architecture** — readme_secure_partner_pairing, playstore_privacy_policy_google_drive_sync, walkthrough_ios_google_auth_lockout_fix, readme_asymmetric_access_control [EXTRACTED 1.00]
- **Database Initialization, Rekeying, and Decryption Sequence** — readme_sqldelight_database, walkthrough_splash_screen_deadlock_fix, walkthrough_sqlcipher_raw_key_bypass_fix, playstore_privacy_policy_cryptographic_suite [INFERRED 0.95]

## Communities (84 total, 60 thin omitted)

### Community 0 - "CalendarScreen"
Cohesion: 0.15
Nodes (14): CalendarGrid(), CalendarHeader(), CalendarLegend(), CalendarScreen(), HistoryGroupItem(), HistoryItem(), LegendItem(), monthForPage() (+6 more)

### Community 1 - "Rust Cryptographic & Cycle Core"
Cohesion: 0.05
Nodes (63): analyze_cycle_history_internal(), calculate_next_cycle_native_internal(), calculate_phase_internal(), decrypt_from_sync_internal(), decrypt_payload_internal(), derive_key_legacy(), derive_key_secure(), encrypt_data() (+55 more)

### Community 2 - "androidMain/kotlin/com/novarytm/auth/BiometricAuthenticator.kt"
Cohesion: 0.14
Nodes (3): BiometricPromptEffect(), AuthenticationCallback, BiometricPromptEffect()

### Community 3 - "CycleTracker"
Cohesion: 0.07
Nodes (13): SyncBirthControl, SyncEntry, SyncPayload, SyncPregnancyTest, SyncReminder, CycleAnalysis, CyclePrediction, CyclePredictionResult (+5 more)

### Community 4 - "JNI FFI RustBridge Contract"
Cohesion: 0.06
Nodes (3): RustBridge, PartnerPermissions, RustBridge

### Community 8 - "Architecture & Play Store Governance"
Cohesion: 0.07
Nodes (25): IARC Content Rating Evaluation, Google Play Data Safety Declaration, Data Safety Encryption in Transit and at Rest, User-Controlled Data Deletion Mechanism, Multi-Layer Cryptographic Security Suite, Client-Side Encrypted Google Drive Sync, Zero Cloud Architecture Policy, Play Store Health App Policy Compliance (+17 more)

### Community 11 - "Google OAuth2 Authentication"
Cohesion: 0.08
Nodes (5): GoogleAuthManager, rememberGoogleAuthManager(), GoogleAuthManager, NSObject, rememberGoogleAuthManager()

### Community 12 - "Health Hub & Contraception UI"
Cohesion: 0.11
Nodes (12): BCOption, BirthControlTab(), ContraceptiveMethodRing(), HealthHubScreen(), InfoChip(), PregnancyTestTab(), PrivacyNote(), QuizOverlay() (+4 more)

### Community 13 - "Google Drive REST Client"
Cohesion: 0.18
Nodes (6): AboutResponse, DriveFileAppFolder, DriveFileListAppFolder, DriveStorageFullException, GoogleDriveManager, StorageQuota

### Community 17 - "iOS SwiftUI Host Bridge"
Cohesion: 0.15
Nodes (7): ComposeView, ContentView, .body, iosApp, .body, shared, SwiftUI

### Community 20 - "Session State Machine"
Cohesion: 0.19
Nodes (5): Locked, Onboarding, SessionManager, SessionState, Unlocked

### Community 21 - "Biometric Prompt & LockScreen"
Cohesion: 0.22
Nodes (3): BiometricPromptEffect(), BiometricsSetupScreen(), LockScreen()

### Community 22 - "Onboarding Credential Lifecycle"
Cohesion: 0.22
Nodes (6): ConnectPartner, CreatePin, OnboardingStage, RecoveryWarning, Restore, Welcome

### Community 24 - "Android Launcher Icon Assets"
Cohesion: 0.33
Nodes (6): Android Launcher Icon (xxxhdpi Density), Blooming Flower Motif, Circular App Icon Presentation, Plum, Cyan, and Pink Color Palette, Hatching Eggshell Motif, Bloom Android Launcher Icon (xxxhdpi)

### Community 25 - "Bloom Architecture & Graph-First Development Guidelines"
Cohesion: 0.17
Nodes (11): 2. Typed Parameter Contracts & Pseudocode Specification Standard, 3. Parallel Subagent Swarm Execution (`Model: "flash"`), 4. MCP Tool Integration Rules, 5. Automated Quality Gates, 6. Continuous Graph Synchronization (After Editing), 7.1 Persistent State & Command Anchors, 7.2 Compact `/graphify` Schema (Token-Optimized), 7.3 Orchestrator Rules & Kickoff Matrix (+3 more)

### Community 27 - "Compose Shared Brand Resources"
Cohesion: 0.38
Nodes (6): Compose Multiplatform Shared Resource Logo Design, LockScreen and Authentication Branding Role, Circular Badge with Plum, Neon Cyan, and Pink Palette, Bloom Compose Multiplatform Logo Asset, App Splash Screen and Startup Identity, Blooming Flower Emerging from Cracked Eggshell Motif

### Community 28 - "Navigation Screen Routes"
Cohesion: 0.33
Nodes (6): Calendar, Home, Hub, PartnerSettings, Screen, ShareSyncCode

### Community 29 - "Android Drawable Brand Mark"
Cohesion: 0.40
Nodes (5): Blooming Flower Motif, Bloom Brand Logo Mark, Circular App Icon / Badge Layout, Logo Color Scheme: Deep Plum, Neon Cyan, and Pink, Hatching Eggshell Motif

### Community 30 - "Adaptive Round Launcher Icon"
Cohesion: 0.83
Nodes (4): Adaptive Icon Circular Masking, Bloom Floral Rebirth Brand Motif, Bloom Round Launcher Icon (xxxhdpi), Round Launcher Icon Design

### Community 39 - "createDatabase"
Cohesion: 0.23
Nodes (4): createDatabase(), ColumnAdapter, ColumnAdapter, DriverFactory

### Community 44 - "AppContent"
Cohesion: 0.20
Nodes (7): 1. Mandatory Graph-First Discovery (Before Editing), App(), AppContent(), BiometricsSetup, SplashScreen(), LoginScreen(), MainViewController()

### Community 71 - "generate_brand_assets.py"
Cohesion: 0.13
Nodes (9): extract_clean_artwork(), extract_original_logo(), generate_legacy_icon(), main(), make_antialiased_mask(), render_artwork_on_canvas(), create_showcase(), ImageChops_multiply_alpha() (+1 more)

### Community 72 - "alignment"
Cohesion: 0.23
Nodes (3): BirthControlQuiz(), PartnerPermissionsScreen(), PermissionItem()

### Community 81 - "LogEntryScreen.kt"
Cohesion: 0.14
Nodes (4): CustomDateRangePickerDialog(), IntensityButton(), LogEntryScreen(), toHumanReadable()

## Knowledge Gaps
- **21 isolated node(s):** `ShareSyncCode`, `Welcome`, `Restore`, `shared`, `rust_core` (+16 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 342 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **60 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `App()` connect `AppContent` to `CycleTracker`, `SyncManager`, `Color`, `SessionManager.kt`, `Google Drive REST Client`, `Session State Machine`, `App.kt`, `MainActivity.kt`?**
  _High betweenness centrality (0.112) - this node is a cross-community bridge._
- **Are the 15 inferred relationships involving `AppContent()` (e.g. with `1. Mandatory Graph-First Discovery (Before Editing)` and `BiometricsSetupScreen()`) actually correct?**
  _`AppContent()` has 15 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ShareSyncCode`, `Welcome`, `Restore` to the rest of the system?**
  _21 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Rust Cryptographic & Cycle Core` be split into smaller, more focused modules?**
  _Cohesion score 0.052795031055900624 - nodes in this community are weakly interconnected._
- **Why does `MainViewController()` connect `AppContent` to `iOS Keychain Secure Storage`, `iosMain/kotlin/com/novarytm/db/DatabaseHelper.kt`, `JNI FFI RustBridge Contract`, `createDatabase`?**
  _High betweenness centrality (0.087) - this node is a cross-community bridge._
- **Should `androidMain/kotlin/com/novarytm/auth/BiometricAuthenticator.kt` be split into smaller, more focused modules?**
  _Cohesion score 0.13970588235294118 - nodes in this community are weakly interconnected._
- **Why does `AppContent()` connect `AppContent` to `CalendarScreen`, `CycleTracker`, `SyncManager`, `Recovery Key Input Screen`, `alignment`, `Color`, `ConnectPartnerScreen.kt`, `Health Hub & Contraception UI`, `LogEntryScreen.kt`, `LockScreen.kt`, `Session State Machine`, `Biometric Prompt & LockScreen`, `Onboarding Credential Lifecycle`, `App.kt`, `ShareSyncCodeScreen.kt`, `Navigation Screen Routes`?**
  _High betweenness centrality (0.074) - this node is a cross-community bridge._