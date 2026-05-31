import argparse
import os
import time
from pathlib import Path
from typing import Iterable, List, Optional, Tuple

from training_monitor import TrainingMonitor
from watch_mmseg_log import infer_total_epochs, parse_latest


def iter_logs(roots: Iterable[str]) -> Iterable[Path]:
    for root in roots:
        for path in Path("/").glob(root.lstrip("/")) if "*" in root else Path(root).rglob("*.log"):
            if path.is_file() and path.suffix == ".log":
                yield path
            elif path.is_dir():
                yield from path.rglob("*.log")


def latest_log(roots: List[str]) -> Optional[Path]:
    candidates = []
    for path in iter_logs(roots):
        try:
            candidates.append((path.stat().st_mtime, path))
        except OSError:
            continue

    if not candidates:
        return None

    return max(candidates, key=lambda item: item[0])[1]


def parse_all_values(log_path: Path) -> Tuple[Optional[Tuple[int, float]], Optional[Tuple[int, float, Optional[int]]]]:
    best: Optional[Tuple[int, float]] = None
    latest = parse_latest(log_path)

    with log_path.open("r", encoding="utf-8", errors="ignore") as file:
        for line in file:
            if "mIoU:" not in line:
                continue
            parsed = parse_latest_line(line)
            if parsed and (best is None or parsed[1] > best[1]):
                best = parsed

    return best, latest


def parse_latest_line(line: str) -> Optional[Tuple[int, float]]:
    import re

    match = re.search(r"Epoch\(val\) \[(\d+)\]\[\s*\d+/\d+\].*mIoU: ([0-9.]+)", line)
    if not match:
        return None
    return int(match.group(1)), float(match.group(2))


def send_snapshot(
    monitor: TrainingMonitor,
    log_path: Path,
    total_epochs: int,
    best: Optional[Tuple[int, float]],
    latest: Tuple[int, float, Optional[int]],
) -> None:
    if best is not None:
        monitor.log(
            run_id=str(log_path),
            epoch=best[0],
            total_epochs=total_epochs,
            iou=best[1],
            eta_seconds=latest[2],
        )

    monitor.log(
        run_id=str(log_path),
        epoch=latest[0],
        total_epochs=total_epochs,
        iou=latest[1],
        eta_seconds=latest[2],
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--server-url", default="http://127.0.0.1:6006")
    parser.add_argument("--token", default=os.getenv("MONITOR_TOKEN", ""))
    parser.add_argument("--total-epochs", type=int, default=0)
    parser.add_argument("--interval", type=float, default=10.0)
    parser.add_argument(
        "--roots",
        nargs="*",
        default=["/root/mmsegmentation*", "/root/autodl-tmp", "/root/workspace", "/root"],
    )
    args = parser.parse_args()

    monitor = TrainingMonitor(args.server_url, token=args.token)
    active_log: Optional[Path] = None
    last_sent: Optional[Tuple[Path, int, float, Optional[int]]] = None

    while True:
        log_path = latest_log(args.roots)
        if log_path is None:
            print("no log found", flush=True)
            time.sleep(args.interval)
            continue

        best, latest = parse_all_values(log_path)
        if latest is None:
            print(f"waiting for mIoU in {log_path}", flush=True)
            time.sleep(args.interval)
            continue

        total_epochs = infer_total_epochs(log_path, args.total_epochs)
        current = (log_path, latest[0], latest[1], latest[2])

        if active_log != log_path:
            print(f"switch to log: {log_path}", flush=True)
            send_snapshot(monitor, log_path, total_epochs, best, latest)
            active_log = log_path
            last_sent = current
        elif current != last_sent:
            monitor.log(
                run_id=str(log_path),
                epoch=latest[0],
                total_epochs=total_epochs,
                iou=latest[1],
                eta_seconds=latest[2],
            )
            print(
                f"sent epoch={latest[0]}/{total_epochs}, mIoU={latest[1]:.4f}, log={log_path}",
                flush=True,
            )
            last_sent = current

        time.sleep(args.interval)


if __name__ == "__main__":
    main()
