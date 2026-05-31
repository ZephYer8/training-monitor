import argparse
import csv
import os
import re
import time
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

from training_monitor import TrainingMonitor
from watch_mmseg_log import infer_total_epochs, parse_eta_seconds, parse_latest_metrics


Metric = Tuple[str, int, float, Optional[int], Dict[str, float]]


def iter_files(roots: Iterable[str]) -> Iterable[Path]:
    for root in roots:
        paths = Path("/").glob(root.lstrip("/")) if "*" in root else [Path(root)]
        for path in paths:
            if path.is_file() and is_supported(path):
                yield path
            elif path.is_dir():
                yield from (item for item in path.rglob("*") if item.is_file() and is_supported(item))


def is_supported(path: Path) -> bool:
    if path.suffix == ".log":
        return True
    if path.suffix != ".csv":
        return False
    return any(word in path.name.lower() for word in ("result", "metric", "progress"))


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
    if path.suffix == ".csv":
        return parse_csv_metrics(path, total_epochs_fallback)
    return parse_mmseg_log(path, total_epochs_fallback)


def parse_mmseg_log(path: Path, total_epochs_fallback: int) -> Tuple[int, Optional[Metric], Optional[Metric]]:
    best: Optional[Metric] = None
    latest_raw = parse_latest_metrics(path)
    latest: Optional[Metric] = None

    with path.open("r", encoding="utf-8", errors="ignore") as file:
        for line in file:
            parsed = parse_mmseg_line(line)
            if parsed and (best is None or parsed[2] > best[2]):
                best = parsed

    if latest_raw is not None:
        epoch, metrics, eta_seconds, primary_metric = latest_raw
        latest = (primary_metric, epoch, metrics[primary_metric], eta_seconds, metrics)

    return infer_total_epochs(path, total_epochs_fallback), best, latest


def parse_mmseg_line(line: str) -> Optional[Metric]:
    match = re.search(r"Epoch\(val\) \[(\d+)\]\[\s*\d+/\d+\].*mIoU: ([0-9.]+)", line)
    if not match:
        return None
    eta_match = re.search(r"eta: ([0-9:]+)", line)
    eta_seconds = parse_eta_seconds(eta_match.group(1)) if eta_match else None
    value = float(match.group(2))
    return "mIoU", int(match.group(1)), value, eta_seconds, {"mIoU": value}


def parse_csv_metrics(path: Path, total_epochs_fallback: int) -> Tuple[int, Optional[Metric], Optional[Metric]]:
    rows = []
    with path.open("r", encoding="utf-8", errors="ignore", newline="") as file:
        reader = csv.DictReader(file)
        for row in reader:
            normalized = {key.strip(): value for key, value in row.items() if key}
            epoch = parse_int(normalized.get("epoch"))
            metrics = pick_metrics(normalized)
            metric_name = choose_primary_metric(metrics)
            if epoch is None or not metrics or metric_name is None:
                continue
            rows.append((metric_name, epoch, metrics[metric_name], None, metrics))

    if not rows:
        return total_epochs_fallback or 300, None, None

    if min(item[1] for item in rows) == 0:
        rows = [(name, epoch + 1, value, eta, metrics) for name, epoch, value, eta, metrics in rows]

    total_epochs = total_epochs_fallback if total_epochs_fallback > 0 else max(item[1] for item in rows)
    best = best_row(rows)
    latest = rows[-1]
    return total_epochs, best, latest


def pick_metrics(row: dict) -> Dict[str, float]:
    metrics = {}
    for key, raw_value in row.items():
        name = metric_label(key)
        if name is None:
            continue
        value = parse_float(raw_value)
        if value is None:
            continue
        metrics[name] = normalize_metric_value(name, value)
    return metrics


def metric_label(key: str) -> Optional[str]:
    clean = key.strip()
    lowered = clean.lower()
    if lowered in {"epoch", "time", "lr"} or lowered.endswith("/lr"):
        return None
    known = {
        "metrics/map50-95(b)": "mAP",
        "metrics/map50-95": "mAP",
        "metrics/map_0.5:0.95": "mAP",
        "metrics/map50(b)": "mAP50",
        "metrics/map50": "mAP50",
        "metrics/precision(b)": "precision",
        "metrics/precision": "precision",
        "metrics/recall(b)": "recall",
        "metrics/recall": "recall",
        "miou": "mIoU",
        "iou": "IoU",
        "map": "mAP",
        "accuracy": "accuracy",
        "acc": "accuracy",
        "top1": "accuracy",
    }
    if lowered in known:
        return known[lowered]
    if "loss" in lowered:
        return clean.split("/")[-1]
    if lowered.startswith("metrics/"):
        return clean.split("/")[-1].replace("(B)", "")
    return None


def choose_primary_metric(metrics: Dict[str, float]) -> Optional[str]:
    for name in ("mIoU", "IoU", "mAP", "mAP50", "accuracy", "precision", "recall"):
        if name in metrics:
            return name
    for name in metrics:
        if "loss" not in name.lower():
            return name
    return next(iter(metrics), None)


def normalize_metric_value(name: str, value: float) -> float:
    if "loss" not in name.lower() and 0 <= value <= 1:
        return value * 100
    return value


def best_row(rows: List[Metric]) -> Metric:
    primary_name = rows[-1][0]
    if "loss" in primary_name.lower():
        return min(rows, key=lambda item: item[2])
    return max(rows, key=lambda item: item[2])


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
            metric_name=best[0],
            metrics=best[4],
            eta_seconds=latest[3],
        )

    monitor.log(
        run_id=run_id,
        epoch=latest[1],
        total_epochs=total_epochs,
        iou=latest[2],
        metric_name=latest[0],
        metrics=latest[4],
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
    last_sent: Optional[Tuple[Path, int, Tuple[Tuple[str, float], ...], Optional[int]]] = None

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

        current = (path, latest[1], tuple(sorted(latest[4].items())), latest[3])
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
                metric_name=latest[0],
                metrics=latest[4],
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
