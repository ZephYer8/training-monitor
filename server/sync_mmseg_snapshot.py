import argparse
import os
import re
from pathlib import Path

from training_monitor import TrainingMonitor
from watch_mmseg_log import infer_total_epochs, parse_eta_seconds


VAL_RE = re.compile(r"Epoch\(val\) \[(\d+)\]\[\s*\d+/\d+\].*mIoU: ([0-9.]+)")
TRAIN_RE = re.compile(r"Epoch\(train\) \[(\d+)\]\[\s*\d+/\d+\].*eta: ([0-9:]+)")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--log-path", required=True)
    parser.add_argument("--server-url", default="http://127.0.0.1:6006")
    parser.add_argument("--token", default=os.getenv("MONITOR_TOKEN", ""))
    parser.add_argument("--total-epochs", type=int, default=0)
    args = parser.parse_args()

    total_epochs = infer_total_epochs(Path(args.log_path), args.total_epochs)
    rows = []
    eta_seconds = None
    for line in Path(args.log_path).read_text(encoding="utf-8", errors="ignore").splitlines():
        val_match = VAL_RE.search(line)
        if val_match:
            rows.append((int(val_match.group(1)), float(val_match.group(2))))

        train_match = TRAIN_RE.search(line)
        if train_match:
            eta_seconds = parse_eta_seconds(train_match.group(2))

    if not rows:
        raise SystemExit("no mIoU rows found")

    best = max(rows, key=lambda item: item[1])
    latest = rows[-1]
    monitor = TrainingMonitor(args.server_url, token=args.token)

    monitor.log(
        run_id=str(Path(args.log_path)),
        epoch=best[0],
        total_epochs=total_epochs,
        iou=best[1],
        metric_name="mIoU",
        eta_seconds=eta_seconds,
    )
    state = monitor.log(
        run_id=str(Path(args.log_path)),
        epoch=latest[0],
        total_epochs=total_epochs,
        iou=latest[1],
        metric_name="mIoU",
        eta_seconds=eta_seconds,
    )

    print(
        f"synced latest={latest[1]:.4f}@{latest[0]}, "
        f"best={best[1]:.4f}@{best[0]}, eta_seconds={eta_seconds}"
    )
    print(state)


if __name__ == "__main__":
    main()
