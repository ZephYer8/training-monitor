#!/usr/bin/env bash
set -euo pipefail
umask 077

REPO="${TRAINING_MONITOR_REPO_SLUG:-ZephYer8/training-monitor}"
DEFAULT_BUNDLE_URL="https://github.com/$REPO/releases/latest/download/training-monitor-server.tar.gz"
if [ -n "${TRAINING_MONITOR_GITHUB_PROXY:-}" ]; then
    DEFAULT_BUNDLE_URL="${TRAINING_MONITOR_GITHUB_PROXY%/}/$DEFAULT_BUNDLE_URL"
fi
BUNDLE_URLS="${TRAINING_MONITOR_SERVER_BUNDLE_URLS:-${TRAINING_MONITOR_SERVER_BUNDLE_URL:-$DEFAULT_BUNDLE_URL}}"
TMP_DIR="$(mktemp -d)"
ARCHIVE_FILE="$TMP_DIR/training-monitor-server.tar.gz"

cleanup() {
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

download_one() {
    local url="$1"
    echo "[training-monitor] downloading server bundle: $url"
    if command -v curl >/dev/null 2>&1; then
        curl -fL --connect-timeout 10 --retry 2 --retry-delay 1 "$url" -o "$ARCHIVE_FILE"
    elif command -v wget >/dev/null 2>&1; then
        wget -T 10 -t 2 -O "$ARCHIVE_FILE" "$url"
    else
        echo "[training-monitor] curl or wget is required" >&2
        exit 1
    fi
}

download() {
    local url
    for url in $BUNDLE_URLS; do
        if download_one "$url"; then
            return 0
        fi
    done
    return 1
}

download || {
    echo "[training-monitor] failed to download server bundle" >&2
    echo "[training-monitor] if GitHub is slow, use a proxy, for example:" >&2
    echo "[training-monitor] TRAINING_MONITOR_GITHUB_PROXY=https://gh-proxy.com bash training-monitor-install-server.sh" >&2
    exit 1
}
tar -xzf "$ARCHIVE_FILE" -C "$TMP_DIR"

[ -f "$TMP_DIR/scripts/install.sh" ] || {
    echo "[training-monitor] downloaded bundle is incomplete: scripts/install.sh is missing" >&2
    exit 1
}

exec bash "$TMP_DIR/scripts/install.sh" "$@"
