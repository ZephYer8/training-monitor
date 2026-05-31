import argparse
import csv
import os
import re
import time
from pathlib import Path
from typing import Iterable, List, Optional, Tuple

from training_monitor import TrainingMonitor
from watch_mmseg_log import infer_total_epochs, parse_eta_seconds, parse_latest


Metric = Tuple[str, int, float, Optional[int]]


def iter_files(roots: Iterable[str]) -> Iterable[Path]:
    for root in roots:
        paths = Path("/").glob(root.lstrip("/")) if "*" in root else [Path(root)]
        for path in paths:
            if path.is_file() and is_supported(path):
                yield path
            elif path.is_dir():
                yield from (item for item in path.rglob("*") if item.is_file() and is_supported(item))


def is_supported(path: Path) -> bool:
    return path.suffix == ".log" or path.name == "results.csv"


def latest_file(roots: List[str]) -> Optional[Path]:
    candidates = []
    for path in iter_files(roots):
        try:
            candidates.append((path.stat().st_mtime, path))
        except OSError:
            continue

    if not candidates:
        return None

    return max(candidates, key=lambda item: item[0])[1]


def parse_file(path: Path, total_epochs_fallback: int) -> Tuple[int, Optional[Metric], Optional[Metric]]:
    if path.name == "results.csv":
        return parse_yolo_results(path, total_epochs_fallback)
    return parse_mmseg_log(path, total_epochs_fallback)


def parse_mmseg_log(path: Path, total_epochs_fallback: int) -> Tuple[int, Optional[Metric], Optional[Metric]]:
    best: Optional[Metric] = None
    latest_raw = parse_latest(path)
    latest: Optional[Metric] = None

    with path.open("r", encoding="utf-8", errors="ignore") as file:
        for line in file:
            parsed = parse_mmseg_line(line)
            if parsed and (best is None or parsed[2] > best[2]):
                best = parsed

    if latest_raw is not None:
        latest = ("mIoU", latest_raw[0], latest_raw[1], latest_raw[2])

    return infer_total_epochs(path, total_epochs_fallback), best, latest


def parse_mmseg_line(line: str) -> Optional[Metric]:
    match = re.search(r"Epoch\(val\) \[(\d+)\]\[\s*\d+/\d+\].*mIoU: ([0-9.]+)", line)
    if not match:
        return None
    eta_match = re.search(r"eta: ([0-9:]+)", line)
    eta_seconds = parse_eta_seconds(eta_match.group(1)) if eta_match else None
    return "mIoU", int(match.group(1)), float(match.group(2)), eta_seconds


def parse_yolo_results(path: Path, total_epochs_fallback: int) -> Tuple[int, Optional[Metric], Optional[Metric]]:
    rows = []
    with path.open("r", encoding="utf-8", errors="ignore", newline="") as file:
        reader = csv.DictReader(file)
        for row in reader:
            normalized = {key.strip(): value for key, value in row.items() if key}
            epoch = parse_int(normalized.get("epoch"))
            value = parse_float(
                normalized.get("metrics/mAP50-95(B)")
                or normalized.get("metrics/mAP50-95")
                or normalized.get("metrics/mAP50(B)")
                or normalized.get("metrics/mAP50")
                or normalized.get("metrics/mAP_0.5:0.95")
            )
            if epoch is None or value is None:
                continue
            rows.append(("mAP", epoch + 1, value * 100 if value <= 1 else value, None))

    if not rows:
        return total_epochs_fallback or 300, None, None

    total_epochs = total_epochs_fallback if total_epochs_fallback > 0 else max(item[1] for item in rows)
    best = max(rows, key=lambda item: item[2])
    latest = rows[-1]
    return total_epochs, best, latest


def parse_int(value: Optional[str]) -> Optional[int]:
    try:
        return int(float(str(value).strip()))
    except (TypeError, ValueError):
        return None


def parse_float(value: Optional[str]) -> Optional[float]:
    try:
        return float(str(value).strip())
    except (TypeError, ValueError):
        return None


def send_snapshot(
    monitor: TrainingMonitor,
    run_id: str,
    total_epochs: int,
    best: Optional[Metric],
    latest: Metric,
) -> None:
    if best is not None:
        monitor.log(
            run_id=run_id,
            epoch=best[1],
            total_epochs=total_epochs,
            iou=best[2],
            eta_seconds=latest[3],
        )

    monitor.log(
        run_id=run_id,
        epoch=latest[1],
        total_epochs=total_epochs,
        iou=latest[2],
        eta_seconds=latest[3],
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
        default=["/root/mmsegmentation*", "/root/autodl-tmp", "/root/workspace", "/root/runs", "/root"],
    )
    args = parser.parse_args()

    monitor = TrainingMonitor(args.server_url, token=args.token)
    active_file: Optional[Path] = None
    last_sent: Optional[Tuple[Path, int, float, Optional[int]]] = None

    while True:
        path = latest_file(args.roots)
        if path is None:
            print("no supported training log found", flush=True)
            time.sleep(args.interval)
            continue

        total_epochs, best, latest = parse_file(path, args.total_epochs)
        if latest is None:
            print(f"waiting for metrics in {path}", flush=True)
            time.sleep(args.interval)
            continue

        current = (path, latest[1], latest[2], latest[3])
        run_id = str(path)

        if active_file != path:
            print(f"switch to training file: {path}", flush=True)
            send_snapshot(monitor, run_id, total_epochs, best, latest)
            active_file = path
            last_sent = current
        elif current != last_sent:
            monitor.log(
                run_id=run_id,
                epoch=latest[1],
                total_epochs=total_epochs,
                iou=latest[2],
                eta_seconds=latest[3],
            )
            print(
                f"sent epoch={latest[1]}/{total_epochs}, metric={latest[2]:.4f}, file={path}",
                flush=True,
            )
            last_sent = current

        time.sleep(args.interval)


if __name__ == "__main__":
    main()
