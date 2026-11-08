# Bloom Architecture & Graph-First Development Guidelines

## 1. Mandatory Graph-First Discovery (Before Editing)
Before searching or modifying code files across `rust_core/`, `shared/`, or `androidApp/`:
1. **Query Graph First**: Run `./scripts/query_graph.sh "<topic/feature/symbol>"` or inspect `graphify-out/graph.json` to identify target files, line ranges, caller/callee relationships, and community boundaries.
2. **Evaluate Blast Radius Against the 10 God Nodes**:
   - `SecureStorageManager` (Keystore, Monotonic Lockout, AES-256)
   - `AppContent()` (Compose Navigation & Root Router)
   - `RustBridge` (JNI Foreign Function Interface Core)
   - `CycleTracker` (Cycle State Machine & History Prediction)
   - `SyncManager` (E2EE Google Drive Payload Redaction)
   - `App()` (Application Lifecycle & App State)
   - `PartnerPermissions` (Role-Based Sharing Bitmask)
   - `GoogleDriveManager` (Cloud OAuth & REST API Client)
   - `CyclePhaseVisualizer()` (UI Cycle Phase Ring)
   - `SessionManager` (Session State & Auth Machine)
3. **Review Invariants**: Check `graphify-out/GRAPH_REPORT.md` for known surprising connections and cryptographic invariants before modifying encryption or sync logic.

---

## 2. Typed Parameter Contracts & Pseudocode Specification Standard
No subagent or engineer may modify code without an approved specification block containing:
1. **Target Community ID & Line Ranges**: Specific graph community and exact line numbers.
2. **Typed Parameter Contract**: Input types, nullability, validation regex/bounds, output Result types, error states, and zeroization guarantees.
3. **Algorithmic Pseudocode**: Step-by-step logic detailing permission checks, redaction, cryptographic operations, and cleanup.
4. **Verification Test Target**: Specific test case in `cargo test` or `./gradlew :shared:testAndroidHostTest`.

---

## 3. Parallel Subagent Swarm Execution (`Model: "flash"`)
Whenever complex features, audits, or refactors are undertaken:
1. **Subagent Model Constraint**: Always invoke subagents with `Model: "flash"`.
2. **Parallel Dispatching**: Disjoint tasks (e.g. Rust native crypto, Kotlin domain logic, Compose UI, QA simulations, and a11y testing) must run in parallel subagents rather than sequentially in the parent thread.
3. **Graphify Fast-Reading Protocol**: Subagents are strictly prohibited from dumping entire source files. The orchestrator must pass the relevant **Graph Community ID** and line-range targets. Subagents must use `./scripts/query_graph.sh` for any local topological lookups.
4. **Dedicated Orchestrator Subagent**: If multi-step orchestration is run in the background, invoke the dedicated subagent `bloom-graph-coordinator` (`Model: "flash"`), which possesses `EnableSubagentTools: true`, `EnableWriteTools: true`, and `EnableMCPTools: true`.

---

## 4. MCP Tool Integration Rules
Subagents must leverage installed Model Context Protocol (MCP) servers based on domain needs:
- `github-mcp-server`: Issue triage, PR generation, git branch inspection, release documentation.
- `chrome-devtools-mcp`: UI layout debugging, TalkBack accessibility tree audits, contrast ratio verification.
- `firebase-mcp-server`: Cloud Firestore security rules and validation (`firestore-rules-author`).
- `dart-mcp-server` / `flutter_dart-mcp-server`: Cross-platform AST parsing and analysis.

---

## 5. Automated Quality Gates
Before any change is committed or pushed:
1. **Rust Core Tests**: `cargo test --manifest-path rust_core/Cargo.toml` must pass (20/20 tests).
2. **Kotlin Multiplatform Tests**: `./gradlew :shared:testAndroidHostTest` must pass (15/15 tests).
3. **Android Compilation**: `./gradlew :androidApp:assembleDebug` must complete successfully.

---

## 6. Continuous Graph Synchronization (After Editing)
Whenever code, documentation, or assets are modified:
1. **Git Post-Commit Hook**: Verify `.git/hooks/post-commit` executes the automatic graph rebuild (`$(cat graphify-out/.graphify_python) -m graphify update`).
2. **Merge Driver**: Maintain [`.gitattributes`](file:///home/dytamg/Desktop/Bloom-main/.gitattributes) with `graphify-out/graph.json merge=graphify` to prevent JSON conflicts across git branches.
3. **Dual Branch Synchronization**: Keep both `develop` and `main` branches synchronized when pushing to remote.
