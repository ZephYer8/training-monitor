import argparse
import os
import time
from pathlib import Path
from typing import Dict, Optional, Tuple

from openmmlab_log import (
    choose_primary_metric,
    infer_total_epochs,
    parse_eta_seconds,
    parse_line_metrics,
    parse_openmmlab_history,
)
from training_monitor import TrainingMonitor


def parse_latest(log_path: Path) -> Optional[Tuple[int, float, Optional[int]]]:
    latest = parse_latest_metrics(log_path)
    if latest is None or "mIoU" not in latest[1]:
        return None
    return latest[0], latest[1]["mIoU"], latest[2]


def parse_latest_metrics(log_path: Path) -> Optional[Tuple[int, Dict[str, float], Optional[int], str]]:
    total_epochs, rows = parse_openmmlab_history(log_path, 0)
    del total_epochs
    if not rows:
        return None
    latest = rows[-1]
    epoch = latest[1]
    metrics = latest[4]
    latest_eta = latest[3]
    primary_metric = choose_primary_metric(metrics)
    return epoch, metrics, latest_eta, primary_metric


def parse_mmseg_line_metrics(line: str) -> Dict[str, float]:
    return parse_line_metrics(line)


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
    last_sent: Optional[Tuple[int, Dict[str, float], Optional[int]]] = None

    while True:
        latest_metrics = parse_latest_metrics(log_path)
        latest = None
        if latest_metrics is not None:
            epoch, metrics, eta_seconds, primary_metric = latest_metrics
            latest = (epoch, metrics, eta_seconds)
        if latest is not None and latest != last_sent:
            epoch, metrics, eta_seconds = latest
            iou = metrics[primary_metric]
            monitor.log(
                run_id=str(log_path),
                epoch=epoch,
                total_epochs=total_epochs,
                iou=iou,
                metric_name=primary_metric,
                metrics=metrics,
                eta_seconds=eta_seconds,
                status="finished" if total_epochs > 0 and epoch >= total_epochs else "training",
            )
            print(
                f"sent epoch={epoch}/{total_epochs}, "
                f"{primary_metric}={iou:.4f}, eta_seconds={eta_seconds}",
                flush=True,
            )
            last_sent = latest

        time.sleep(args.interval)


if __name__ == "__main__":
    main()
