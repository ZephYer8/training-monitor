import argparse
import os
import re
import time
from pathlib import Path
from typing import Optional, Tuple

from training_monitor import TrainingMonitor


VAL_RE = re.compile(r"Epoch\(val\) \[(\d+)\]\[\s*\d+/\d+\].*mIoU: ([0-9.]+)")
TRAIN_RE = re.compile(r"Epoch\(train\) \[(\d+)\]\[\s*\d+/\d+\].*eta: ([0-9:]+)")
TOTAL_RE = re.compile(r"max_epochs\s*[=:]\s*(\d+)")


def parse_eta_seconds(text: str) -> Optional[int]:
    parts = [int(part) for part in text.split(":")]
    if len(parts) == 3:
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    if len(parts) == 2:
        return parts[0] * 60 + parts[1]
    return None


def parse_latest(log_path: Path) -> Optional[Tuple[int, float, Optional[int]]]:
    latest_val: Optional[Tuple[int, float]] = None
    latest_eta: Optional[int] = None

    with log_path.open("r", encoding="utf-8", errors="ignore") as file:
        for line in file:
            val_match = VAL_RE.search(line)
            if val_match:
                latest_val = (int(val_match.group(1)), float(val_match.group(2)))

            train_match = TRAIN_RE.search(line)
            if train_match:
                latest_eta = parse_eta_seconds(train_match.group(2))

    if latest_val is None:
        return None

    return latest_val[0], latest_val[1], latest_eta


def infer_total_epochs(log_path: Path, fallback: int) -> int:
    if fallback > 0:
        return fallback

    with log_path.open("r", encoding="utf-8", errors="ignore") as file:
        for line in file:
            match = TOTAL_RE.search(line)
            if match:
                return int(match.group(1))

    return 300


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--log-path", required=True)
    parser.add_argument("--server-url", default="http://127.0.0.1:6006")
    parser.add_argument("--token", default=os.getenv("MONITOR_TOKEN", ""))
    parser.add_argument("--total-epochs", type=int, default=0)
    parser.add_argument("--interval", type=float, default=5.0)
    args = parser.parse_args()

    log_path = Path(args.log_path)
    total_epochs = infer_total_epochs(log_path, args.total_epochs)
    monitor = TrainingMonitor(args.server_url, token=args.token)
    last_sent: Optional[Tuple[int, float, Optional[int]]] = None

    while True:
        latest = parse_latest(log_path)
        if latest is not None and latest != last_sent:
            epoch, iou, eta_seconds = latest
            monitor.log(
                run_id=str(log_path),
                epoch=epoch,
                total_epochs=total_epochs,
                iou=iou,
                metric_name="mIoU",
                eta_seconds=eta_seconds,
                status="training",
            )
            print(
                f"sent epoch={epoch}/{total_epochs}, "
                f"mIoU={iou:.4f}, eta_seconds={eta_seconds}",
                flush=True,
            )
            last_sent = latest

        time.sleep(args.interval)


if __name__ == "__main__":
    main()
