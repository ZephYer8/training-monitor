import argparse
import os
import re
import time
from pathlib import Path
from typing import Dict, Optional, Tuple

from training_monitor import TrainingMonitor


TRAIN_RE = re.compile(r"Epoch\(train\) \[(\d+)\]\[\s*\d+/\d+\].*eta: ([0-9:]+)")
LOSS_RE = re.compile(r"\bloss[:=]\s*([0-9.]+)")
TOTAL_RE = re.compile(r"max_epochs\s*[=:]\s*(\d+)")
METRIC_RE = re.compile(r"([A-Za-z_][A-Za-z0-9_./-]*)\s*[:=]\s*(-?\d+(?:\.\d+)?(?:e[-+]?\d+)?)", re.I)
PRIMARY_METRICS = ("mIoU", "IoU", "mDice", "mAcc", "aAcc", "mAP", "mAP50", "accuracy", "precision", "recall")
SKIP_METRICS = {"eta", "time", "data_time", "memory", "iter", "epoch", "max_epochs"}


def parse_eta_seconds(text: str) -> Optional[int]:
    parts = [int(part) for part in text.split(":")]
    if len(parts) == 3:
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    if len(parts) == 2:
        return parts[0] * 60 + parts[1]
    return None


def parse_latest(log_path: Path) -> Optional[Tuple[int, float, Optional[int]]]:
    latest = parse_latest_metrics(log_path)
    if latest is None or "mIoU" not in latest[1]:
        return None
    return latest[0], latest[1]["mIoU"], latest[2]


def parse_latest_metrics(log_path: Path) -> Optional[Tuple[int, Dict[str, float], Optional[int], str]]:
    latest_train_epoch: Optional[int] = None
    latest_val_epoch: Optional[int] = None
    latest_train_metrics: Dict[str, float] = {}
    latest_val_metrics: Dict[str, float] = {}
    latest_eta: Optional[int] = None

    with log_path.open("r", encoding="utf-8", errors="ignore") as file:
        for line in file:
            epoch_match = re.search(r"Epoch\((val|train)\) \[(\d+)\]\[\s*\d+/\d+\]", line)
            if not epoch_match:
                continue

            mode = epoch_match.group(1)
            epoch = int(epoch_match.group(2))
            metrics = parse_mmseg_line_metrics(line)
            if not metrics:
                continue

            if mode == "val":
                latest_val_epoch = epoch
                latest_val_metrics = metrics
            else:
                latest_train_epoch = epoch
                latest_train_metrics = metrics
                train_match = TRAIN_RE.search(line)
                if train_match:
                    latest_eta = parse_eta_seconds(train_match.group(2))

    if latest_val_epoch is None and not latest_train_metrics:
        return None

    metrics = {}
    metrics.update(latest_train_metrics)
    metrics.update(latest_val_metrics)

    epoch = latest_val_epoch if latest_val_epoch is not None else latest_train_epoch or 0
    primary_metric = choose_primary_metric(metrics)
    return epoch, metrics, latest_eta, primary_metric


def parse_mmseg_line_metrics(line: str) -> Dict[str, float]:
    metrics = {}
    for raw_name, raw_value in METRIC_RE.findall(line):
        name = metric_label(raw_name)
        if name is None:
            continue
        try:
            value = float(raw_value)
        except ValueError:
            continue
        metrics[name] = normalize_metric_value(name, value)

    loss_match = LOSS_RE.search(line)
    if loss_match and "loss" not in metrics:
        metrics["loss"] = float(loss_match.group(1))
    return metrics


def metric_label(name: str) -> Optional[str]:
    clean = name.strip()
    lowered = clean.lower()
    if lowered in SKIP_METRICS or lowered.endswith("/lr") or lowered == "lr":
        return None

    known = {
        "miou": "mIoU",
        "iou": "IoU",
        "mdice": "mDice",
        "mfscore": "mFscore",
        "macc": "mAcc",
        "aacc": "aAcc",
        "accuracy": "accuracy",
        "acc": "accuracy",
        "precision": "precision",
        "recall": "recall",
        "map": "mAP",
        "map50": "mAP50",
    }
    if lowered in known:
        return known[lowered]
    if "loss" in lowered:
        return clean.split("/")[-1]
    return None


def normalize_metric_value(name: str, value: float) -> float:
    if "loss" not in name.lower() and 0 <= value <= 1:
        return value * 100
    return value


def choose_primary_metric(metrics: Dict[str, float]) -> str:
    for name in PRIMARY_METRICS:
        if name in metrics:
            return name
    for name in metrics:
        if "loss" not in name.lower():
            return name
    return next(iter(metrics))


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
                f"mIoU={iou:.4f}, eta_seconds={eta_seconds}",
                flush=True,
            )
            last_sent = latest

        time.sleep(args.interval)


if __name__ == "__main__":
    main()
