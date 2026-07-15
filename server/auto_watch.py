import argparse
import csv
import os
import time
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

import requests

from openmmlab_log import (
    best_row as best_openmmlab_row,
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
    seen = set()
    for path in iter_files(roots):
        if path in seen:
            continue
        seen.add(path)
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


def safe_parse_file(path: Path, total_epochs_fallback: int) -> Optional[ParsedFile]:
    try:
        return parse_file(path, total_epochs_fallback)
    except Exception as exc:
        print(f"skip unreadable training log: {path} ({exc})", flush=True)
        return None


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

    total_epochs = total_epochs_fallback if total_epochs_fallback > 0 else 0
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
    updates = [
        {
            "run_id": run_id,
            "epoch": row[1],
            "total_epochs": total_epochs,
            "iou": row[2],
            "metric_name": row[0],
            "metrics": row[4],
            "eta_seconds": row[3],
            "status": "finished" if total_epochs > 0 and row[1] >= total_epochs else "training",
        }
        for row in rows[-500:]
    ]
    monitor.log_batch(updates)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--server-url", default="http://127.0.0.1:6006")
    parser.add_argument("--token", default=os.getenv("MONITOR_TOKEN", ""))
    parser.add_argument("--total-epochs", type=int, default=0)
    parser.add_argument("--interval", type=float, default=10.0)
    parser.add_argument("--once", action="store_true", help="scan once and print parse diagnostics")
    parser.add_argument("--max-active-runs", type=int, default=8, help="maximum parsed log files to report each scan")
    parser.add_argument(
        "--roots",
        nargs="*",
        default=[
            "/root/mmdetection*",
            "/root/mmdetection3d*",
            "/root/mmdet3d*",
            "/root/mmsegmentation*",
            "/root/mmclassification*",
            "/root/mmpretrain*",
            "/root/mmselfsup*",
            "/root/mmyolo*",
            "/root/mmpose*",
            "/root/mmrotate*",
            "/root/mmocr*",
            "/root/mmaction*",
            "/root/mmaction2*",
            "/root/mmagic*",
            "/root/mmediting*",
            "/root/mmgeneration*",
            "/root/mmtracking*",
            "/root/mmtrack*",
            "/root/mmrazor*",
            "/root/mmhuman3d*",
            "/root/mmfewshot*",
            "/root/mmdeploy*",
            "/root/work_dirs",
            "/root/*/work_dirs",
            "/root/autodl-tmp/*/work_dirs",
            "/root/workspace/*/work_dirs",
            "/root/autodl-tmp",
            "/root/workspace",
            "/root/runs",
        ],
    )
    args = parser.parse_args()

    if args.once:
        candidates = latest_files(args.roots)[:20]
        if not candidates:
            print("no supported training log found", flush=True)
            return
        for candidate in candidates:
            parsed = safe_parse_file(candidate, args.total_epochs)
            if parsed is None:
                continue
            total_epochs, best, latest, rows = parsed
            if latest is None:
                print(f"found but no metrics: {candidate}", flush=True)
                continue
            print(
                f"ok file={candidate} epoch={latest[1]}/{total_epochs} primary={latest[0]} value={latest[2]:.4f} metrics={sorted(latest[4])}",
                flush=True,
            )
        return

    monitor = TrainingMonitor(args.server_url, token=args.token)
    known_files: set = set()
    last_sent: dict = {}

    while True:
        parsed_files = []
        for candidate in latest_files(args.roots):
            parsed = safe_parse_file(candidate, args.total_epochs)
            if parsed is None:
                continue
            total_epochs, best, latest, rows = parsed
            if latest is not None:
                parsed_files.append((candidate, total_epochs, best, latest, rows))
            if len(parsed_files) >= max(1, args.max_active_runs):
                break

        if not parsed_files:
            print("no supported training log found", flush=True)
            time.sleep(args.interval)
            continue

        active_paths = {item[0] for item in parsed_files}
        for stale_path in list(last_sent):
            if stale_path not in active_paths:
                last_sent.pop(stale_path, None)
                known_files.discard(stale_path)

        for path, total_epochs, best, latest, rows in parsed_files:
            current = (path, latest[1], total_epochs, tuple(sorted(latest[4].items())), latest[3])
            run_id = str(path)

            if path not in known_files:
                print(f"track training file: {path}", flush=True)
                try:
                    send_history_snapshot(monitor, run_id, total_epochs, rows)
                except requests.RequestException as exc:
                    print(f"sync failed, will retry: {path} ({exc})", flush=True)
                    continue
                known_files.add(path)
                last_sent[path] = current
            elif current != last_sent.get(path):
                try:
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
                except requests.RequestException as exc:
                    print(f"update failed, will retry: {path} ({exc})", flush=True)
                    continue
                print(
                    f"sent epoch={latest[1]}/{total_epochs}, metric={latest[2]:.4f}, file={path}",
                    flush=True,
                )
                last_sent[path] = current

        time.sleep(args.interval)


if __name__ == "__main__":
    main()
