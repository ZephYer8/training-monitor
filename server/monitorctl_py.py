import argparse
import json
import os
import shlex
from pathlib import Path
import secrets
import shutil
import signal
import site
import socket
import subprocess
import sys
import time
from typing import Dict, List, Optional

import requests


DEFAULT_LOG_ROOTS = (
    "/root/mmdetection* /root/mmdetection3d* /root/mmdet3d* /root/mmsegmentation* "
    "/root/mmclassification* /root/mmpretrain* /root/mmselfsup* /root/mmyolo* "
    "/root/mmpose* /root/mmrotate* /root/mmocr* /root/mmaction* /root/mmaction2* "
    "/root/mmagic* /root/mmediting* /root/mmgeneration* /root/mmtracking* /root/mmtrack* "
    "/root/mmrazor* /root/mmhuman3d* /root/mmfewshot* /root/mmdeploy* /root/work_dirs "
    "/root/*/work_dirs /root/autodl-tmp/*/work_dirs /root/workspace/*/work_dirs "
    "/root/autodl-tmp /root/workspace /root/runs"
)


def install_home() -> Path:
    configured = os.getenv("TRAINING_MONITOR_HOME")
    if configured:
        return Path(configured).expanduser()
    if os.name != "nt" and os.getuid() == 0 and Path("/root/autodl-tmp").is_dir():
        return Path("/root/autodl-tmp/training-monitor")
    return Path.home() / ".training-monitor"


BASE = install_home()
CONFIG_FILE = BASE / "config.env"
TOKEN_FILE = BASE / "token.txt"
STATE_FILE = BASE / "state.json"
LOG_DIR = BASE / "logs"
BACKEND_PID = BASE / "backend.pid"
WATCHER_PID = BASE / "watcher.pid"


def ensure_base() -> None:
    BASE.mkdir(parents=True, exist_ok=True)
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    for path in (BASE, LOG_DIR):
        try:
            path.chmod(0o700)
        except OSError:
            pass


def default_config() -> Dict[str, str]:
    return {
        "PORT": "6006",
        "PUBLIC_URL": "",
        "LOG_ROOTS": DEFAULT_LOG_ROOTS,
        "LOG_TYPE": "auto",
        "AUTO_WATCH": "1",
        "TOTAL_EPOCHS": "0",
        "SCAN_INTERVAL": "10",
        "CORS_ORIGINS": "",
    }


def write_default_config() -> None:
    ensure_base()
    if CONFIG_FILE.exists():
        return
    save_config(default_config())


def read_config() -> Dict[str, str]:
    write_default_config()
    values = default_config()
    for line in CONFIG_FILE.read_text(encoding="utf-8", errors="ignore").splitlines():
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def save_config(values: Dict[str, str]) -> None:
    ensure_base()
    text = "\n".join(f"{key}={value}" for key, value in values.items()) + "\n"
    CONFIG_FILE.write_text(text, encoding="utf-8")
    try:
        CONFIG_FILE.chmod(0o600)
    except OSError:
        pass


def config_value(key: str, fallback: str = "") -> str:
    return os.getenv(f"TRAINING_MONITOR_{key}", read_config().get(key, fallback))


def port() -> int:
    return int(config_value("PORT", "6006") or "6006")


def token_init() -> str:
    ensure_base()
    if not TOKEN_FILE.exists() or not TOKEN_FILE.read_text(encoding="utf-8", errors="ignore").strip():
        TOKEN_FILE.write_text(secrets.token_urlsafe(24) + "\n", encoding="utf-8")
    try:
        TOKEN_FILE.chmod(0o600)
    except OSError:
        pass
    return TOKEN_FILE.read_text(encoding="utf-8").strip()


def read_pid(path: Path) -> Optional[int]:
    try:
        text = path.read_text(encoding="utf-8").strip()
        return int(text) if text.isdigit() else None
    except OSError:
        return None


def pid_alive(path: Path) -> bool:
    pid = read_pid(path)
    if pid is None:
        return False
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def pid_command_contains(path: Path, needle: str) -> bool:
    pid = read_pid(path)
    if pid is None:
        return False
    proc_cmd = Path("/proc") / str(pid) / "cmdline"
    try:
        cmd = proc_cmd.read_text(encoding="utf-8", errors="ignore").replace("\x00", " ")
    except OSError:
        return True
    return needle in cmd


def stop_one(path: Path, name: str, pattern: str = "") -> None:
    pid = read_pid(path)
    if pid is None:
        path.unlink(missing_ok=True)
        return
    if not pid_alive(path):
        path.unlink(missing_ok=True)
        print(f"{name} pid was stale")
        return
    if pattern and not pid_command_contains(path, pattern):
        path.unlink(missing_ok=True)
        print(f"{name} pid was stale")
        return
    try:
        os.kill(pid, signal.SIGTERM)
    except OSError:
        pass
    path.unlink(missing_ok=True)
    print(f"{name} stopped")


def backend_ready() -> bool:
    try:
        requests.get(f"http://127.0.0.1:{port()}/api/health", timeout=3).raise_for_status()
        return True
    except Exception:
        return False


def backend_running() -> bool:
    return pid_alive(BACKEND_PID) and pid_command_contains(BACKEND_PID, "uvicorn")


def watcher_running() -> bool:
    return pid_alive(WATCHER_PID) and pid_command_contains(WATCHER_PID, "auto_watch")


def spawn(command: List[str], log_path: Path, extra_env: Optional[Dict[str, str]] = None) -> int:
    ensure_base()
    env = os.environ.copy()
    if extra_env:
        env.update(extra_env)
    log_file = log_path.open("ab")
    process = subprocess.Popen(
        command,
        stdout=log_file,
        stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL,
        env=env,
        start_new_session=True,
    )
    return int(process.pid)


def start(_: argparse.Namespace) -> None:
    cfg = read_config()
    current_token = token_init()
    current_port = int(cfg.get("PORT", "6006") or "6006")

    if backend_running() and backend_ready():
        print(f"server already running, pid={read_pid(BACKEND_PID)}")
    else:
        stop_one(BACKEND_PID, "old server", "uvicorn")
        pid = spawn(
            [
                sys.executable,
                "-m",
                "uvicorn",
                "app:app",
                "--host",
                "0.0.0.0",
                "--port",
                str(current_port),
            ],
            LOG_DIR / "backend.log",
            {
                "MONITOR_TOKEN": current_token,
                "TRAINING_MONITOR_STATE_FILE": str(STATE_FILE),
                "TRAINING_MONITOR_CORS_ORIGINS": cfg.get("CORS_ORIGINS", ""),
            },
        )
        BACKEND_PID.write_text(str(pid), encoding="utf-8")
        time.sleep(2)
        if backend_ready():
            print(f"server started, pid={pid}, port={current_port}")
        else:
            print(f"server started but health check is not ready yet, pid={pid}, port={current_port}")
            print("run: python3 -m monitorctl_py logs")

    if cfg.get("AUTO_WATCH", "1") == "1":
        auto_watch(argparse.Namespace())


def stop_all(_: argparse.Namespace) -> None:
    stop_one(WATCHER_PID, "watcher", "auto_watch")
    stop_one(BACKEND_PID, "server", "uvicorn")


def restart(args: argparse.Namespace) -> None:
    stop_all(args)
    start(args)


def auto_watch(_: argparse.Namespace) -> None:
    cfg = read_config()
    current_token = token_init()
    if watcher_running():
        print(f"watcher already running, pid={read_pid(WATCHER_PID)}")
        return
    stop_one(WATCHER_PID, "old watcher", "auto_watch")
    roots = cfg.get("LOG_ROOTS", DEFAULT_LOG_ROOTS).split()
    pid = spawn(
        [
            sys.executable,
            "-m",
            "auto_watch",
            "--server-url",
            f"http://127.0.0.1:{cfg.get('PORT', '6006')}",
            "--total-epochs",
            cfg.get("TOTAL_EPOCHS", "0"),
            "--interval",
            cfg.get("SCAN_INTERVAL", "10"),
            "--roots",
            *roots,
        ],
        LOG_DIR / "watcher.log",
        {"MONITOR_TOKEN": current_token},
    )
    WATCHER_PID.write_text(str(pid), encoding="utf-8")
    print("auto training log detection started")


def watch_file(args: argparse.Namespace) -> None:
    path = Path(args.path).expanduser()
    if not path.is_file():
        raise SystemExit(f"file not found: {path}")
    start(args)
    stop_one(WATCHER_PID, "old watcher", "auto_watch")
    current_token = token_init()
    pid = spawn(
        [
            sys.executable,
            "-m",
            "auto_watch",
            "--server-url",
            f"http://127.0.0.1:{port()}",
            "--total-epochs",
            str(args.total_epochs or config_value("TOTAL_EPOCHS", "0")),
            "--interval",
            "5",
            "--roots",
            str(path),
        ],
        LOG_DIR / "watcher.log",
        {"MONITOR_TOKEN": current_token},
    )
    WATCHER_PID.write_text(str(pid), encoding="utf-8")
    print(f"watching: {path}")


def status(_: argparse.Namespace) -> None:
    current_token = token_init()
    try:
        response = requests.get(
            f"http://127.0.0.1:{port()}/api/status",
            headers={"X-Monitor-Token": current_token},
            timeout=5,
        )
        print("HTTP", response.status_code)
        print(json.dumps(response.json(), ensure_ascii=False, indent=2))
        return
    except Exception as exc:
        print("server unavailable:", exc)
    if STATE_FILE.exists():
        print("local cached state:")
        print(json.dumps(json.loads(STATE_FILE.read_text(encoding="utf-8")), ensure_ascii=False, indent=2))


def detect_public_url() -> str:
    cfg = read_config()
    if cfg.get("PUBLIC_URL"):
        return cfg["PUBLIC_URL"]
    autodl_key = f"AutoDLService{cfg.get('PORT', '6006')}URL"
    autodl_url = os.getenv(autodl_key) or os.getenv("AutoDLServiceURL")
    if autodl_url:
        return autodl_url
    try:
        ip = subprocess.check_output(
            ["hostname", "-I"],
            text=True,
            timeout=2,
            stderr=subprocess.DEVNULL,
        ).split()[0]
        return f"http://{ip}:{cfg.get('PORT', '6006')}"
    except Exception:
        try:
            return f"http://{socket.gethostbyname(socket.gethostname())}:{cfg.get('PORT', '6006')}"
        except Exception:
            return f"http://SERVER_IP:{cfg.get('PORT', '6006')}"


def connection(_: argparse.Namespace) -> None:
    print(f"server port: {port()}")
    print(f"backend url: {detect_public_url()}")
    print(f"access token: {token_init()}")


def rotate_token(args: argparse.Namespace) -> None:
    ensure_base()
    TOKEN_FILE.write_text(secrets.token_urlsafe(24) + "\n", encoding="utf-8")
    print("token rotated")
    restart(args)
    connection(args)


def config_cmd(args: argparse.Namespace) -> None:
    cfg = read_config()
    if args.config_action == "show":
        print(CONFIG_FILE.read_text(encoding="utf-8"), end="")
    elif args.config_action == "path":
        print(CONFIG_FILE)
    elif args.config_action == "set":
        cfg[args.key] = args.value
        save_config(cfg)
        print(f"saved {args.key}")


def prompt_value(label: str, current: str) -> str:
    value = input(f"{label} [{current}]: ").strip()
    return value or current


def setup(_: argparse.Namespace) -> None:
    cfg = read_config()
    cfg["PORT"] = prompt_value("Port", cfg.get("PORT", "6006"))
    cfg["PUBLIC_URL"] = prompt_value("Public URL", cfg.get("PUBLIC_URL") or detect_public_url())
    cfg["LOG_ROOTS"] = prompt_value("Log roots", cfg.get("LOG_ROOTS", DEFAULT_LOG_ROOTS))
    cfg["LOG_TYPE"] = prompt_value("Log type auto/openmmlab/yolo", cfg.get("LOG_TYPE", "auto"))
    cfg["AUTO_WATCH"] = prompt_value("Auto watch 1/0", cfg.get("AUTO_WATCH", "1"))
    cfg["CORS_ORIGINS"] = prompt_value("CORS origins, usually empty", cfg.get("CORS_ORIGINS", ""))
    save_config(cfg)
    print(f"saved: {CONFIG_FILE}")
    connection(_)


def logs(_: argparse.Namespace) -> None:
    for label, path in (("backend log", LOG_DIR / "backend.log"), ("watcher log", LOG_DIR / "watcher.log")):
        print(f"{label}: {path}")
        if path.exists():
            lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()[-80:]
            print("\n".join(lines))
        print()


def diagnose(_: argparse.Namespace) -> None:
    cfg = read_config()
    command = [
        sys.executable,
        "-m",
        "auto_watch",
        "--server-url",
        f"http://127.0.0.1:{cfg.get('PORT', '6006')}",
        "--total-epochs",
        cfg.get("TOTAL_EPOCHS", "0"),
        "--roots",
        *cfg.get("LOG_ROOTS", DEFAULT_LOG_ROOTS).split(),
        "--once",
    ]
    raise SystemExit(subprocess.call(command, env={**os.environ, "MONITOR_TOKEN": token_init()}))


def user_bin_dir() -> Path:
    return Path(site.USER_BASE) / ("Scripts" if os.name == "nt" else "bin")


def shell_profiles() -> List[Path]:
    if os.name == "nt":
        return []
    return [
        Path.home() / ".bashrc",
        Path.home() / ".profile",
        Path.home() / ".bash_profile",
        Path.home() / ".zshrc",
    ]


def path_has_user_bin() -> bool:
    user_bin = str(user_bin_dir())
    path_dirs = os.getenv("PATH", "").split(os.pathsep)
    return user_bin in path_dirs or str(Path.home() / "bin") in path_dirs


def launcher_paths() -> List[Path]:
    paths = [user_bin_dir() / "training-monitor"]
    home_bin = Path.home() / "bin" / "training-monitor"
    if home_bin not in paths:
        paths.append(home_bin)
    if os.name != "nt" and hasattr(os, "geteuid") and os.geteuid() == 0:
        paths.append(Path("/usr/local/bin/training-monitor"))
    return paths


def launcher_script() -> str:
    return (
        "#!/usr/bin/env bash\n"
        f'exec {shlex.quote(sys.executable)} -m monitorctl_py "$@"\n'
    )


def ensure_launcher_files() -> List[Path]:
    written: List[Path] = []
    content = launcher_script()
    for path in launcher_paths():
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            current = path.read_text(encoding="utf-8", errors="ignore") if path.exists() else ""
            if current != content:
                path.write_text(content, encoding="utf-8")
                path.chmod(0o755)
                written.append(path)
        except OSError:
            continue
    return written


def write_shell_profile_exports() -> List[Path]:
    if os.name == "nt":
        return []

    line = 'export PATH="$HOME/bin:$HOME/.local/bin:/usr/local/bin:/usr/bin:$PATH"'
    marker = "training-monitor/bin-path"
    changed: List[Path] = []
    profiles = shell_profiles()
    if hasattr(os, "geteuid") and os.geteuid() == 0:
        profiles = [Path("/etc/profile.d/training-monitor.sh")] + profiles

    for profile in profiles:
        try:
            profile.parent.mkdir(parents=True, exist_ok=True)
            profile.touch(exist_ok=True)
            text = profile.read_text(encoding="utf-8", errors="ignore")
            if marker not in text:
                with profile.open("a", encoding="utf-8") as file:
                    file.write(f"\n# {marker}\n{line}\n")
                changed.append(profile)
        except OSError:
            continue
    return changed


def ensure_command_access() -> None:
    if os.name == "nt":
        return
    if os.getenv("TRAINING_MONITOR_SKIP_PATH_BOOTSTRAP") == "1":
        return
    if shutil.which("training-monitor"):
        return

    written = ensure_launcher_files()
    changed_profiles = write_shell_profile_exports()
    if not written and not changed_profiles:
        return

    print("training-monitor command is not in the current PATH yet.")
    if written:
        print("created launcher:")
        for path in written:
            print(f"- {path}")
    if changed_profiles:
        print("updated shell profiles:")
        for profile in changed_profiles:
            print(f"- {profile}")
    print("open a new shell, or run:")
    print(f'export PATH="$HOME/bin:$HOME/.local/bin:/usr/local/bin:/usr/bin:$PATH"')


def fix_path(_: argparse.Namespace) -> None:
    user_bin = user_bin_dir()
    user_bin.mkdir(parents=True, exist_ok=True)

    if os.name == "nt":
        print(f"script directory: {user_bin}")
        print("On Windows, add this directory to PATH manually if training-monitor is not found.")
        return

    ensure_launcher_files()
    changed = write_shell_profile_exports()

    print(f"script directory: {user_bin}")
    if changed:
        print("updated shell profiles:")
        for profile in changed:
            print(f"- {profile}")
        print('run now: export PATH="$HOME/bin:$HOME/.local/bin:/usr/local/bin:/usr/bin:$PATH"')
        print("or reopen your terminal.")
    elif path_has_user_bin():
        print("PATH is already configured.")
    else:
        print("shell profile already contains PATH setting, but current terminal has not loaded it.")
        print('run now: export PATH="$HOME/bin:$HOME/.local/bin:/usr/local/bin:/usr/bin:$PATH"')


def doctor(_: argparse.Namespace) -> None:
    command_path = shutil.which("training-monitor")
    user_bin = user_bin_dir()
    home_bin = Path.home() / "bin"
    print("Training Monitor diagnosis")
    print(f"python: {sys.executable}")
    print(f"python version: {sys.version.split()[0]}")
    print(f"install home: {BASE}")
    print(f"script directory: {user_bin}")
    print(f"fallback directory: {home_bin}")
    print(f"training-monitor command: {command_path or 'not found in PATH'}")
    print(f"script directory in PATH: {'yes' if path_has_user_bin() else 'no'}")
    print(f"config file: {CONFIG_FILE}")
    print()
    if not command_path:
        print("recommended fix:")
        print("python3 -m monitorctl_py fix-path")
        print('export PATH="$HOME/bin:$HOME/.local/bin:/usr/local/bin:/usr/bin:$PATH"')
    print("module command always works after pip install:")
    print("python3 -m monitorctl_py connection")


def setup_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="training-monitor")
    sub = parser.add_subparsers(dest="command")
    sub.add_parser("start").set_defaults(func=start)
    sub.add_parser("stop").set_defaults(func=stop_all)
    sub.add_parser("restart").set_defaults(func=restart)
    sub.add_parser("status").set_defaults(func=status)
    sub.add_parser("connection").set_defaults(func=connection)
    sub.add_parser("token-init").set_defaults(func=lambda args: print(token_init()))
    sub.add_parser("token").set_defaults(func=lambda args: print(token_init()))
    sub.add_parser("rotate-token").set_defaults(func=rotate_token)
    sub.add_parser("setup").set_defaults(func=setup)
    sub.add_parser("auto-watch").set_defaults(func=auto_watch)
    sub.add_parser("auto-mmseg").set_defaults(func=auto_watch)
    sub.add_parser("diagnose").set_defaults(func=diagnose)
    sub.add_parser("doctor").set_defaults(func=doctor)
    sub.add_parser("fix-path").set_defaults(func=fix_path)
    sub.add_parser("logs").set_defaults(func=logs)

    watch = sub.add_parser("watch-file")
    watch.add_argument("path")
    watch.add_argument("total_epochs", nargs="?", type=int)
    watch.set_defaults(func=watch_file)

    watch_mmseg = sub.add_parser("watch-mmseg")
    watch_mmseg.add_argument("path")
    watch_mmseg.add_argument("total_epochs", nargs="?", type=int)
    watch_mmseg.set_defaults(func=watch_file)

    config = sub.add_parser("config")
    config_sub = config.add_subparsers(dest="config_action")
    config_sub.add_parser("show").set_defaults(func=config_cmd)
    config_sub.add_parser("path").set_defaults(func=config_cmd)
    config_set = config_sub.add_parser("set")
    config_set.add_argument("key")
    config_set.add_argument("value")
    config_set.set_defaults(func=config_cmd)
    return parser


def main() -> None:
    ensure_command_access()
    parser = setup_parser()
    args = parser.parse_args()
    if not hasattr(args, "func"):
        parser.print_help()
        return
    args.func(args)


if __name__ == "__main__":
    main()
