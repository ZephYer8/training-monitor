#!/usr/bin/env bash
set -euo pipefail
umask 077

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

ARCHIVE_PATH="$REPO_URL/archive/refs/heads/$REF.tar.gz"
ARCHIVE_URLS="${TRAINING_MONITOR_ARCHIVE_URLS:-$ARCHIVE_PATH}"
ARCHIVE_FILE="$TMP_DIR/source.tar.gz"

download_archive() {
    local url
    for url in $ARCHIVE_URLS; do
        echo "Downloading: $url"
        if command -v curl >/dev/null 2>&1; then
            if curl -fL --connect-timeout 10 --retry 2 --retry-delay 1 "$url" -o "$ARCHIVE_FILE"; then
                return 0
            fi
        elif command -v wget >/dev/null 2>&1; then
            if wget -T 10 -t 2 -O "$ARCHIVE_FILE" "$url"; then
                return 0
            fi
        else
            echo "curl or wget is required"
            exit 1
        fi
    done
    return 1
}

download_archive || {
    echo "Failed to download Training Monitor source archive." >&2
    exit 1
}

tar -xzf "$ARCHIVE_FILE" -C "$TMP_DIR" --strip-components=1

exec bash "$TMP_DIR/scripts/install.sh" "$@"
