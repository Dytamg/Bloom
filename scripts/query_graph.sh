#!/usr/bin/env bash
set -e
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_FILE="$PROJECT_ROOT/graphify-out/.graphify_python"

if [ -f "$PYTHON_FILE" ]; then
    PYTHON="$(cat "$PYTHON_FILE")"
else
    PYTHON="python3"
fi

"$PYTHON" -m graphify query "$@"
