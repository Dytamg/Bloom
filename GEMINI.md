# Bloom Architecture & Graph-First Development Guidelines

## 1. Mandatory Graph-First Discovery (Before Editing)
Before searching or modifying code files across `rust_core/`, `shared/`, or `androidApp/`:
1. Check `graphify-out/graph.json` or run `./scripts/query_graph.sh "<topic/feature>"` to locate relevant nodes, caller/callee relationships, and community boundaries.
2. Check the 10 God Nodes (`SecureStorageManager`, `AppContent()`, `RustBridge`, `CycleTracker`, `SyncManager`, `PartnerPermissions`, `GoogleDriveManager`, etc.) to evaluate cross-module blast radius.
3. Review `graphify-out/GRAPH_REPORT.md` for known surprising connections or architecture invariants before modifying sync redaction or encryption logic.

## 2. Continuous Graph Synchronization (After Editing)
Whenever code, documentation, or assets are modified:
1. Ensure the post-commit hook is active (`.git/hooks/post-commit`) or run incremental update:
   `$(cat graphify-out/.graphify_python) -m graphify update`
2. If new documentation or architecture changes occur, dispatch `bloom-engineer` subagents (`Model: "flash"`) in parallel to update the semantic clusters.

## 3. Subagent Inheritance
Whenever subagents are invoked for implementation or testing:
- Pass the relevant graph community IDs and neighboring nodes in the prompt so subagents do not waste tokens re-reading the entire codebase.
