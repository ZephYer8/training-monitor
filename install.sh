#!/usr/bin/env bash
set -euo pipefail

REPO_URL="${TRAINING_MONITOR_REPO:-https://github.com/ZephYer8/training-monitor}"
REF="${TRAINING_MONITOR_REF:-main}"
SCRIPT_PATH="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="$(cd "$(dirname "$SCRIPT_PATH")" 2>/dev/null && pwd || true)"

if [ -n "$SCRIPT_DIR" ] && [ -f "$SCRIPT_DIR/scripts/install.sh" ] && [ -d "$SCRIPT_DIR/server" ]; then
    exec bash "$SCRIPT_DIR/scripts/install.sh" "$@"
fi

if [[ "$REPO_URL" == *"OWNER/"* ]]; then
    echo "Please replace OWNER in install.sh with your GitHub username, or run:"
    echo "curl -fsSL ... | TRAINING_MONITOR_REPO=https://github.com/YOUR_NAME/training-monitor bash"
    exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

ARCHIVE_URL="$REPO_URL/archive/refs/heads/$REF.tar.gz"
if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$ARCHIVE_URL" | tar -xz -C "$TMP_DIR" --strip-components=1
elif command -v wget >/dev/null 2>&1; then
    wget -qO- "$ARCHIVE_URL" | tar -xz -C "$TMP_DIR" --strip-components=1
else
    echo "curl or wget is required"
    exit 1
fi

exec bash "$TMP_DIR/scripts/install.sh" "$@"
