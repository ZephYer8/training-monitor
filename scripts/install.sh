#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log() {
    echo "[training-monitor] $1"
}

die() {
    echo "[training-monitor] $1" >&2
    exit 1
}

if [ -z "${TRAINING_MONITOR_HOME:-}" ]; then
    if [ "$(id -u)" = "0" ] && [ -d "/root/autodl-tmp" ]; then
        TRAINING_MONITOR_HOME="/root/autodl-tmp/training-monitor"
    else
        TRAINING_MONITOR_HOME="$HOME/.training-monitor"
    fi
fi

if [ -n "${TRAINING_MONITOR_BIN_DIR:-}" ]; then
    BIN_DIR="$TRAINING_MONITOR_BIN_DIR"
else
    BIN_DIR=""
fi
PYTHON="${TRAINING_MONITOR_PYTHON:-}"

if [ -z "$TRAINING_MONITOR_HOME" ] || [ "$TRAINING_MONITOR_HOME" = "/" ]; then
    die "Unsafe TRAINING_MONITOR_HOME: $TRAINING_MONITOR_HOME"
fi

[ -d "$ROOT_DIR/server" ] || die "server directory is missing in downloaded package: $ROOT_DIR"
[ -f "$ROOT_DIR/scripts/monitorctl" ] || die "scripts/monitorctl is missing in downloaded package: $ROOT_DIR"

find_python() {
    for candidate in "$PYTHON" python3 python /root/miniconda3/bin/python; do
        [ -n "$candidate" ] || continue
        if command -v "$candidate" >/dev/null 2>&1; then
            command -v "$candidate"
            return
        fi
        if [ -x "$candidate" ]; then
            echo "$candidate"
            return
        fi
    done
    die "Python 3.8+ is required"
}

log "install home: $TRAINING_MONITOR_HOME"
mkdir -p "$TRAINING_MONITOR_HOME"
chmod 700 "$TRAINING_MONITOR_HOME" 2>/dev/null || true
if [ "$(cd "$ROOT_DIR" && pwd)" != "$(cd "$TRAINING_MONITOR_HOME" && pwd)" ]; then
    log "copy server files"
    rm -rf "$TRAINING_MONITOR_HOME/server" "$TRAINING_MONITOR_HOME/scripts"
    cp -R "$ROOT_DIR/server" "$TRAINING_MONITOR_HOME/server"
    cp -R "$ROOT_DIR/scripts" "$TRAINING_MONITOR_HOME/scripts"
    cp "$ROOT_DIR/install.sh" "$TRAINING_MONITOR_HOME/install.sh"
fi

MONITORCTL="$TRAINING_MONITOR_HOME/scripts/monitorctl"
[ -f "$MONITORCTL" ] || die "monitor control script is missing after copy: $MONITORCTL"
sed -i 's/\r$//' "$MONITORCTL" "$TRAINING_MONITOR_HOME/scripts/install.sh" "$TRAINING_MONITOR_HOME/install.sh" 2>/dev/null || true

PYTHON_BIN="$(find_python)"
if [ ! -x "$TRAINING_MONITOR_HOME/venv/bin/python" ]; then
    log "create Python virtual environment"
    "$PYTHON_BIN" -m venv "$TRAINING_MONITOR_HOME/venv"
fi

log "install Python dependencies"
"$TRAINING_MONITOR_HOME/venv/bin/python" -m pip install --upgrade pip
"$TRAINING_MONITOR_HOME/venv/bin/python" -m pip install -r "$TRAINING_MONITOR_HOME/server/requirements.txt"

chmod +x "$MONITORCTL"

install_command() {
    local target="$1"
    mkdir -p "$(dirname "$target")" 2>/dev/null || return 1
    cat > "$target" <<EOF
#!/usr/bin/env bash
exec bash "$MONITORCTL" "\$@"
EOF
    sed -i 's/\r$//' "$target" 2>/dev/null || true
    chmod +x "$target"
}

install_command_entries() {
    COMMAND_PATH=""
    if [ -n "$BIN_DIR" ]; then
        install_command "$BIN_DIR/training-monitor" && COMMAND_PATH="$BIN_DIR/training-monitor"
    fi

    for dir in /usr/local/bin /usr/bin "$HOME/.local/bin"; do
        [ -n "$dir" ] || continue
        install_command "$dir/training-monitor" || continue
        [ -z "$COMMAND_PATH" ] && COMMAND_PATH="$dir/training-monitor"
    done

    IFS=':' read -r -a PATH_DIRS <<< "$PATH"
    for dir in "${PATH_DIRS[@]}"; do
        [ -n "$dir" ] || continue
        [ -d "$dir" ] || continue
        [ -w "$dir" ] || continue
        install_command "$dir/training-monitor" || continue
        [ -z "$COMMAND_PATH" ] && COMMAND_PATH="$dir/training-monitor"
    done

    [ -n "$COMMAND_PATH" ] || {
        die "Failed to install training-monitor command. Try running with sudo/root or set TRAINING_MONITOR_BIN_DIR."
    }

    if [ -d "$HOME" ]; then
        touch "$HOME/.bashrc" 2>/dev/null || true
        if ! grep -q 'training-monitor/bin-path' "$HOME/.bashrc" 2>/dev/null; then
            cat >> "$HOME/.bashrc" <<'EOF'

# training-monitor/bin-path
export PATH="/usr/local/bin:/usr/bin:$HOME/.local/bin:$PATH"
EOF
        fi
    fi

    export PATH="/usr/local/bin:/usr/bin:$HOME/.local/bin:$PATH"
}

install_command_entries

log "initialize token"
bash "$MONITORCTL" token-init
log "start monitor service"
bash "$MONITORCTL" start
bash "$MONITORCTL" auto-watch || true

echo
echo "Training Monitor installed"
echo "Control command: bash $MONITORCTL"
echo "Command: $COMMAND_PATH"
if command -v training-monitor >/dev/null 2>&1; then
    echo "You can run: training-monitor status"
else
    echo "If your shell cannot find it, run: $COMMAND_PATH status"
fi
echo
bash "$MONITORCTL" connection
