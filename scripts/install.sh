#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ -z "${TRAINING_MONITOR_HOME:-}" ]; then
    if [ "$(id -u)" = "0" ] && [ -d "/root/autodl-tmp" ]; then
        TRAINING_MONITOR_HOME="/root/autodl-tmp/training-monitor"
    else
        TRAINING_MONITOR_HOME="$HOME/.training-monitor"
    fi
fi

if [ -n "${TRAINING_MONITOR_BIN_DIR:-}" ]; then
    BIN_DIR="$TRAINING_MONITOR_BIN_DIR"
elif [ "$(id -u)" = "0" ] && [ -d "/usr/local/bin" ] && [ -w "/usr/local/bin" ]; then
    BIN_DIR="/usr/local/bin"
else
    BIN_DIR="$HOME/.local/bin"
fi
PYTHON="${TRAINING_MONITOR_PYTHON:-}"

if [ -z "$TRAINING_MONITOR_HOME" ] || [ "$TRAINING_MONITOR_HOME" = "/" ]; then
    echo "Unsafe TRAINING_MONITOR_HOME: $TRAINING_MONITOR_HOME" >&2
    exit 1
fi

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
    echo "Python 3.8+ is required" >&2
    exit 1
}

mkdir -p "$TRAINING_MONITOR_HOME" "$BIN_DIR"
if [ "$(cd "$ROOT_DIR" && pwd)" != "$(cd "$TRAINING_MONITOR_HOME" && pwd)" ]; then
    rm -rf "$TRAINING_MONITOR_HOME/server" "$TRAINING_MONITOR_HOME/scripts"
    cp -R "$ROOT_DIR/server" "$TRAINING_MONITOR_HOME/server"
    cp -R "$ROOT_DIR/scripts" "$TRAINING_MONITOR_HOME/scripts"
    cp "$ROOT_DIR/install.sh" "$TRAINING_MONITOR_HOME/install.sh"
fi

PYTHON_BIN="$(find_python)"
if [ ! -x "$TRAINING_MONITOR_HOME/venv/bin/python" ]; then
    "$PYTHON_BIN" -m venv "$TRAINING_MONITOR_HOME/venv"
fi

"$TRAINING_MONITOR_HOME/venv/bin/python" -m pip install --upgrade pip
"$TRAINING_MONITOR_HOME/venv/bin/python" -m pip install -r "$TRAINING_MONITOR_HOME/server/requirements.txt"

chmod +x "$TRAINING_MONITOR_HOME/scripts/monitorctl"
ln -sf "$TRAINING_MONITOR_HOME/scripts/monitorctl" "$BIN_DIR/training-monitor" 2>/dev/null || true

case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *)
        if [ "$BIN_DIR" = "$HOME/.local/bin" ]; then
            mkdir -p "$HOME"
            touch "$HOME/.bashrc"
            if ! grep -q 'export PATH="$HOME/.local/bin:$PATH"' "$HOME/.bashrc" 2>/dev/null; then
                printf '\nexport PATH="$HOME/.local/bin:$PATH"\n' >> "$HOME/.bashrc"
            fi
            export PATH="$BIN_DIR:$PATH"
        fi
        ;;
esac

"$TRAINING_MONITOR_HOME/scripts/monitorctl" token-init
"$TRAINING_MONITOR_HOME/scripts/monitorctl" start
"$TRAINING_MONITOR_HOME/scripts/monitorctl" auto-watch || true

echo
echo "Training Monitor installed"
echo "Control command: $TRAINING_MONITOR_HOME/scripts/monitorctl"
echo "Command: $BIN_DIR/training-monitor"
if command -v training-monitor >/dev/null 2>&1; then
    echo "You can run: training-monitor status"
else
    echo "If your shell cannot find it, run: $BIN_DIR/training-monitor status"
fi
echo
"$TRAINING_MONITOR_HOME/scripts/monitorctl" connection
