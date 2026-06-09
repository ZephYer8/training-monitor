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
PYTHON_PACKAGES_DIR="$TRAINING_MONITOR_HOME/python-packages"
VENV_PY="$TRAINING_MONITOR_HOME/venv/bin/python"

if [ -z "$TRAINING_MONITOR_HOME" ] || [ "$TRAINING_MONITOR_HOME" = "/" ]; then
    die "Unsafe TRAINING_MONITOR_HOME: $TRAINING_MONITOR_HOME"
fi

[ -d "$ROOT_DIR/server" ] || die "server directory is missing in downloaded package: $ROOT_DIR"
[ -f "$ROOT_DIR/scripts/monitorctl" ] || die "scripts/monitorctl is missing in downloaded package: $ROOT_DIR"

find_python() {
    for candidate in "$PYTHON" python3 python /root/miniconda3/bin/python; do
        [ -n "$candidate" ] || continue
        resolved=""
        if command -v "$candidate" >/dev/null 2>&1; then
            resolved="$(command -v "$candidate")"
        elif [ -x "$candidate" ]; then
            resolved="$candidate"
        fi

        [ -n "$resolved" ] || continue
        if "$resolved" - <<'PY' >/dev/null 2>&1
import sys
raise SystemExit(0 if sys.version_info >= (3, 8) else 1)
PY
        then
            echo "$resolved"
            return
        fi
    done
    die "Python 3.8+ is required"
}

write_python_wrapper() {
    local real_python="$1"
    mkdir -p "$TRAINING_MONITOR_HOME/venv/bin" "$PYTHON_PACKAGES_DIR"
    cat > "$VENV_PY" <<EOF
#!/usr/bin/env bash
export PYTHONPATH="$PYTHON_PACKAGES_DIR\${PYTHONPATH:+:\$PYTHONPATH}"
exec "$real_python" "\$@"
EOF
    sed -i 's/\r$//' "$VENV_PY" 2>/dev/null || true
    chmod +x "$VENV_PY"
}

ensure_pip() {
    if "$VENV_PY" -m pip --version >/dev/null 2>&1; then
        return
    fi

    if "$VENV_PY" -m ensurepip --upgrade >/dev/null 2>&1; then
        return
    fi

    die "Python pip is unavailable. Please install python3-pip, or set TRAINING_MONITOR_PYTHON to a Python/Conda environment with pip."
}

prepare_python_runtime() {
    if [ -x "$VENV_PY" ]; then
        return
    fi

    log "create Python virtual environment"
    rm -rf "$TRAINING_MONITOR_HOME/venv"
    if "$PYTHON_BIN" -m venv "$TRAINING_MONITOR_HOME/venv"; then
        return
    fi

    log "python venv is unavailable; using local package runtime"
    rm -rf "$TRAINING_MONITOR_HOME/venv" "$PYTHON_PACKAGES_DIR"
    write_python_wrapper "$PYTHON_BIN"
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
prepare_python_runtime

log "install Python dependencies"
ensure_pip
if "$VENV_PY" - <<'PY' >/dev/null 2>&1
import sys
raise SystemExit(0 if hasattr(sys, "real_prefix") or sys.prefix != sys.base_prefix else 1)
PY
then
    "$VENV_PY" -m pip install --upgrade pip
    "$VENV_PY" -m pip install -r "$TRAINING_MONITOR_HOME/server/requirements.txt"
else
    "$VENV_PY" -m pip install --upgrade --target "$PYTHON_PACKAGES_DIR" -r "$TRAINING_MONITOR_HOME/server/requirements.txt"
fi

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

    for dir in /usr/local/bin /usr/bin "$HOME/.local/bin" "$HOME/bin"; do
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
        for profile in "$HOME/.bashrc" "$HOME/.profile" "$HOME/.zshrc"; do
            touch "$profile" 2>/dev/null || true
            if ! grep -q 'training-monitor/bin-path' "$profile" 2>/dev/null; then
                cat >> "$profile" <<'EOF'

# training-monitor/bin-path
export PATH="$HOME/bin:$HOME/.local/bin:/usr/local/bin:/usr/bin:$PATH"
EOF
            fi
        done
    fi

    if [ "$(id -u)" = "0" ] && [ -d /etc/profile.d ] && [ -w /etc/profile.d ]; then
        cat > /etc/profile.d/training-monitor.sh <<'EOF'
# training-monitor/bin-path
export PATH="$HOME/bin:$HOME/.local/bin:/usr/local/bin:/usr/bin:$PATH"
EOF
        chmod 644 /etc/profile.d/training-monitor.sh 2>/dev/null || true
    fi

    export PATH="$HOME/bin:$HOME/.local/bin:/usr/local/bin:/usr/bin:$PATH"
}

install_command_entries

log "initialize token"
bash "$MONITORCTL" token-init
log "start monitor service"
bash "$MONITORCTL" start

echo
echo "Training Monitor installed"
echo "Control command: bash $MONITORCTL"
echo "Command: $COMMAND_PATH"
if command -v training-monitor >/dev/null 2>&1; then
    echo "You can run: training-monitor status"
else
    echo "If your shell cannot find it, run: $COMMAND_PATH status"
    echo "Or refresh PATH once: export PATH=\"/usr/local/bin:/usr/bin:\$HOME/.local/bin:\$PATH\""
fi
echo
bash "$MONITORCTL" connection
