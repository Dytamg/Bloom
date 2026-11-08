#!/usr/bin/env bash
set -e
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_FILE="$PROJECT_ROOT/graphify-out/.graphify_python"

if [ -f "$PYTHON_FILE" ] && [ -x "$(cat "$PYTHON_FILE" 2>/dev/null)" ]; then
    PYTHON="$(cat "$PYTHON_FILE")"
elif [ -x "$PROJECT_ROOT/.venv/bin/python" ]; then
    PYTHON="$PROJECT_ROOT/.venv/bin/python"
else
    PYTHON="python3"
fi

"$PYTHON" -m graphify query "$@"
