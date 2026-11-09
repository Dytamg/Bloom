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

---

## 7. Orchestrator & Multi-Agent Controller Protocol (`[STATE: PLAN_SYNC | GRAPH_SYNC]`)

### 7.1 Persistent State & Command Anchors
- **State Header Anchor (`[STATE: PLAN_SYNC | GRAPH_SYNC]`)**: Every prompt, reasoning trace, subagent dispatch, and orchestrator turn MUST begin with the prefix `[STATE: PLAN_SYNC | GRAPH_SYNC]` to maintain strict state synchronization and graph alignment across turn boundaries.
- **`/plan` Protocol**: All major changes, refactors, and feature implementations follow a strict phased plan:
  - **Phase 0 (Contract Lock & Governance)**: Lock target community IDs, line ranges, Typed Parameter Contracts, test assertions, error conditions, and cryptographic guarantees into documentation and artifacts before writing code.
  - **Phase 1..N (Parallel Execution Batches)**: Group disjoint modules into parallel execution batches executed by specialized subagents. No implementation code may be touched until Phase 0 is approved.
- **`/graphify` Protocol**: Enforces continuous topological graph discovery before edits and automated graph synchronization after edits.

### 7.2 Compact `/graphify` Schema (Token-Optimized)
To maintain minimal token footprint when querying, updating, and reporting graph changes across agent boundaries, subagents and orchestrators must utilize the Compact Graph Schema DSL:
- **Node Definition**:
  `@ID | path/to/file.ext | [Exports/Key Symbols] | Status(OK|WIP|BROKEN)`
- **Edge / Dependency Definition**:
  `@SourceID -> @TargetID : [Imported Symbols or Data Flow]`
- **Diff-Only Reporting**:
  Subagents and engineers must report graph alterations using diff notation:
  - `+ @ID ...` (New node registered)
  - `~ @ID ...` (Existing node modified / status changed)
  - `- @ID ...` (Node pruned or deleted)
  - `+ @SourceID -> @TargetID : [...]` (New dependency edge)
  - `- @SourceID -> @TargetID : [...]` (Removed dependency edge)
- **Strict `[GRAPH_NODE]` Mutation Scoping**: Subagents are strictly restricted to mutating nodes assigned within their explicit `[GRAPH_NODE]` scope parameter. Modifying nodes or files outside the assigned scope is prohibited.

### 7.3 Orchestrator Rules & Kickoff Matrix
- **Zero Heavy Lifting**: The Orchestrator acts purely as a coordinator and gatekeeper. The Orchestrator never performs bulk file edits or manual implementation directly in the primary context thread, coordinating all changes via parallel subagents.
- **Execution Matrix**:
  - `GRAPH_READ`: Query the knowledge graph (`./scripts/query_graph.sh`) to discover relevant communities, nodes, and caller/callee paths.
  - `PHASE_0_CONTRACTS`: Formalize Typed Parameter Contracts, Algorithmic Pseudocode, and verification targets before dispatching implementation.
  - `PARALLEL_BATCHES`: Group disjoint subagent tasks into parallel batches without shared mutable state.
- **Parallel Dispatch (`Model: "flash"`)**: Subagents must be invoked concurrently with `Model: "flash"`, passing explicit `[GRAPH_NODE]`, `[SCOPE]`, line ranges, and contracts.
- **Gatekeeping (`APPROVE` / `DENY`)**: The Orchestrator inspects the 6-field subagent compact reports and verification test outputs. Only changes passing automated gates receive `APPROVE`; failing or incomplete tasks receive `DENY` with actionable revision guidance.
- **Batch Git Push**: After all batch subagents succeed and automated quality gates pass, changes are staged, committed atomically, synchronized across `develop` and `main`, and pushed in batch.

### 7.4 Subagent Runtime Protocol (7 Steps & Compact Reporting)
Subagents operating under the Orchestrator execute the following 7-step lifecycle autonomously:
1. **Context & Graph Ingestion**: Query the graph first using `./scripts/query_graph.sh "<symbol/topic>"`. Read ONLY assigned `[GRAPH_NODE]` and target `[SCOPE]` files and exact line ranges. Never scan or dump entire directories.
2. **Tool & Skill Discovery**: Inspect available MCP tools and Skills (`github-mcp-server`, `chrome-devtools-mcp`, `firebase-mcp-server`, `dart-mcp-server`). Dynamically invoke any required MCP or Skill without asking the Orchestrator.
3. **Design & Implementation**: Follow the approved Typed Parameter Contract and Algorithmic Pseudocode. Write clean, modular code strictly within `[SCOPE]` that honors upstream/downstream `@ID` edge contracts and the 10 God Nodes invariants.
4. **Self-Verification (Mandatory)**: Run local linters, type-checks, or unit tests on modified files (`cargo test --manifest-path rust_core/Cargo.toml`, `./gradlew :shared:testAndroidHostTest`, or `./gradlew :androidApp:assembleDebug` as applicable). Fix errors autonomously before reporting.
5. **Graph Synchronization**: Update `/graphify` (`$(cat graphify-out/.graphify_python) -m graphify update .`) and emit diffs using the Compact Graph Schema. Only mutate nodes assigned in the `[GRAPH_NODE]` parameter.
6. **Git Commit**: Stage only `[SCOPE]` files (when instructed to commit) and create an atomic commit (`feat/fix/chore(scope): concise message`).
7. **Compact Reporting**: Return ONLY this structured block to the Orchestrator via `send_message`:
   ```markdown
   STATUS: [READY_FOR_REVIEW | BLOCKED | FAILED]
   COMMIT: [Short SHA & branch]
   CHANGES: [1-2 bullet summary of files modified]
   GRAPH_DELTA: [+/-/~ lines using Compact Graph Schema]
   TOOLS_USED: [List of MCPs/Skills invoked]
   BLOCKERS: [None, or exact error requiring Orchestrator intervention]
   ```

