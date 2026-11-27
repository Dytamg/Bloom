#!/usr/bin/env python3
import os
import sys
import re
import argparse
from difflib import SequenceMatcher

def compute_cc(code_block):
    cc = 1
    keywords = ['if', 'else if', 'when', 'match', 'for', 'while', 'loop', 'catch']
    for kw in keywords:
        pattern = r'\b' + kw + r'\b'
        cc += len(re.findall(pattern, code_block))
    cc += code_block.count('&&')
    cc += code_block.count('||')
    return cc

def extract_functions(filepath):
    with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
        content = f.read()

    funcs = []
    lines = content.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        if ('fn ' in line or 'fun ' in line) and ('{' in line or (i + 1 < len(lines) and '{' in lines[i+1])):
            func_lines = [line]
            brace_count = line.count('{') - line.count('}')
            start_idx = i
            i += 1
            while i < len(lines) and brace_count > 0:
                func_lines.append(lines[i])
                brace_count += lines[i].count('{') - lines[i].count('}')
                i += 1
            body = '\n'.join(func_lines)
            funcs.append((start_idx, body))
        else:
            i += 1
    return funcs

def main():
    parser = argparse.ArgumentParser(description="UB Code Quality Gate")
    parser.add_argument("--max-crap", type=float, default=10.0)
    parser.add_argument("--max-dup", type=float, default=0.80)
    args = parser.parse_args()

    target_files = [
        "shared/src/commonMain/kotlin/com/bloom/sync/GoogleDriveManager.kt",
        "shared/src/androidMain/kotlin/com/bloom/sync/SafBlobClient.kt",
        "shared/src/androidMain/kotlin/com/bloom/sync/SyncWorkerCoordinator.kt",
        "shared/src/androidMain/kotlin/com/bloom/sync/SyncPullWorker.kt",
        "shared/src/androidMain/kotlin/com/bloom/sync/SyncUploadWorker.kt",
        "shared/src/commonMain/kotlin/com/bloom/tracker/CycleTracker.kt",
        "rust_core/src/jni_bridge.rs",
    ]

    violations = 0
    all_functions = []

    for filepath in target_files:
        if not os.path.exists(filepath):
            continue
        funcs = extract_functions(filepath)
        for start_idx, body in funcs:
            if "test" in body.lower() and "fun test_" in body:
                continue
            cc = compute_cc(body)
            if cc > 10:
                print(f"CYCLOMATIC COMPLEXITY VIOLATION: CC {cc} > 10 in {filepath}:{start_idx+1}")
                violations += 1

            normalized_body = re.sub(r'\s+', ' ', body).strip()
            if body.count('\n') >= 9 and len(normalized_body) >= 180:
                all_functions.append((filepath, start_idx, normalized_body))

    for i in range(len(all_functions)):
        for j in range(i + 1, len(all_functions)):
            f1, s1, b1 = all_functions[i]
            f2, s2, b2 = all_functions[j]
            ratio = SequenceMatcher(None, b1, b2).ratio()
            if ratio >= args.max_dup:
                print(f"DUPLICATION VIOLATION: similarity {ratio:.2f} between {f1}:{s1+1} and {f2}:{s2+1}")
                violations += 1

    if violations > 0:
        print(f"Gate FAILED with {violations} violations.")
        sys.exit(1)
    else:
        print("Gate PASSED successfully.")
        sys.exit(0)

if __name__ == "__main__":
    main()
