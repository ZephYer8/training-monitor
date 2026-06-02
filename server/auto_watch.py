import argparse
import csv
import os
import time
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

from openmmlab_log import (
    best_row as best_openmmlab_row,
    infer_total_epochs,
    parse_openmmlab_history,
    parse_openmmlab_line,
)
from training_monitor import TrainingMonitor


Metric = Tuple[str, int, float, Optional[int], Dict[str, float]]
ParsedFile = Tuple[int, Optional[Metric], Optional[Metric], List[Metric]]


def iter_files(roots: Iterable[str]) -> Iterable[Path]:
    for root in roots:
        paths = Path("/").glob(root.lstrip("/")) if "*" in root else [Path(root)]
        for path in paths:
            if path.is_file() and is_supported(path):
                yield path
            elif path.is_dir():
                yield from (item for item in path.rglob("*") if item.is_file() and is_supported(item))


def is_supported(path: Path) -> bool:
    if path.suffix in {".log", ".json", ".jsonl"}:
        return True
    if path.suffix != ".csv":
        return False
    return any(word in path.name.lower() for word in ("result", "metric", "progress"))


def latest_files(roots: List[str]) -> List[Path]:
    candidates = []
    for path in iter_files(roots):
        try:
            candidates.append((path.stat().st_mtime, path))
        except OSError:
            continue

    if not candidates:
        return []

    return [path for _, path in sorted(candidates, key=lambda item: item[0], reverse=True)]


def parse_file(path: Path, total_epochs_fallback: int) -> ParsedFile:
    total_epochs, rows = parse_history(path, total_epochs_fallback)
    if not rows:
        return total_epochs, None, None, []
    return total_epochs, best_row(rows), rows[-1], rows


def parse_history(path: Path, total_epochs_fallback: int) -> Tuple[int, List[Metric]]:
    if path.suffix == ".csv":
        return parse_csv_history(path, total_epochs_fallback)
    return parse_openmmlab_history(path, total_epochs_fallback)


def parse_mmseg_log(path: Path, total_epochs_fallback: int) -> Tuple[int, Optional[Metric], Optional[Metric]]:
    total_epochs, rows = parse_openmmlab_history(path, total_epochs_fallback)
    if not rows:
        return total_epochs, None, None
    return total_epochs, best_row(rows), rows[-1]


def parse_mmseg_history(path: Path, total_epochs_fallback: int) -> Tuple[int, List[Metric]]:
    return parse_openmmlab_history(path, total_epochs_fallback)


def parse_mmseg_line(line: str) -> Optional[Metric]:
    parsed = parse_openmmlab_line(line)
    if parsed is None:
        return None
    _, epoch, eta_seconds, metrics = parsed
    metric_name = choose_primary_metric(metrics)
    return metric_name, epoch, metrics[metric_name], eta_seconds, metrics


def parse_csv_metrics(path: Path, total_epochs_fallback: int) -> Tuple[int, Optional[Metric], Optional[Metric]]:
    total_epochs, rows = parse_csv_history(path, total_epochs_fallback)
    if not rows:
        return total_epochs, None, None
    return total_epochs, best_row(rows), rows[-1]


def parse_csv_history(path: Path, total_epochs_fallback: int) -> Tuple[int, List[Metric]]:
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
        return total_epochs_fallback or 300, []

    if min(item[1] for item in rows) == 0:
        rows = [(name, epoch + 1, value, eta, metrics) for name, epoch, value, eta, metrics in rows]

    total_epochs = total_epochs_fallback if total_epochs_fallback > 0 else max(item[1] for item in rows)
    return total_epochs, rows


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
    return best_openmmlab_row(rows)


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


def send_history_snapshot(
    monitor: TrainingMonitor,
    run_id: str,
    total_epochs: int,
    rows: List[Metric],
) -> None:
    for row in rows[-500:]:
        monitor.log(
            run_id=run_id,
            epoch=row[1],
            total_epochs=total_epochs,
            iou=row[2],
            metric_name=row[0],
            metrics=row[4],
            eta_seconds=row[3],
            status="finished" if total_epochs > 0 and row[1] >= total_epochs else "training",
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
        default=[
            "/root/mmdetection*",
            "/root/mmsegmentation*",
            "/root/mmclassification*",
            "/root/mmpretrain*",
            "/root/mmpose*",
            "/root/mmrotate*",
            "/root/mmocr*",
            "/root/mmaction*",
            "/root/mmagic*",
            "/root/autodl-tmp",
            "/root/workspace",
            "/root/runs",
        ],
    )
    args = parser.parse_args()

    monitor = TrainingMonitor(args.server_url, token=args.token)
    active_file: Optional[Path] = None
    last_sent: Optional[Tuple[Path, int, Tuple[Tuple[str, float], ...], Optional[int]]] = None

    while True:
        parsed_file = None
        for candidate in latest_files(args.roots):
            total_epochs, best, latest, rows = parse_file(candidate, args.total_epochs)
            if latest is not None:
                parsed_file = candidate, total_epochs, best, latest, rows
                break

        if parsed_file is None:
            print("no supported training log found", flush=True)
            time.sleep(args.interval)
            continue

        path, total_epochs, best, latest, rows = parsed_file

        current = (path, latest[1], tuple(sorted(latest[4].items())), latest[3])
        run_id = str(path)

        if active_file != path:
            print(f"switch to training file: {path}", flush=True)
            send_history_snapshot(monitor, run_id, total_epochs, rows)
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
                status="finished" if total_epochs > 0 and latest[1] >= total_epochs else "training",
            )
            print(
                f"sent epoch={latest[1]}/{total_epochs}, metric={latest[2]:.4f}, file={path}",
                flush=True,
            )
            last_sent = current

        time.sleep(args.interval)


if __name__ == "__main__":
    main()
