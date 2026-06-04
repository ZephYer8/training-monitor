import json
import re
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


Metric = Tuple[str, int, float, Optional[int], Dict[str, float]]

TOTAL_RE = re.compile(r"\bmax_(?:epochs|epoch)\s*[=:]\s*(\d+)")
MAX_ITER_RE = re.compile(r"\bmax_(?:iters|iter)\s*[=:]\s*(\d+)")
METRIC_RE = re.compile(
    r"([A-Za-z_][A-Za-z0-9_./@:-]*)\s*[:=]\s*(-?\d+(?:\.\d+)?(?:e[-+]?\d+)?)",
    re.I,
)
EPOCH_BATCH_RE = re.compile(r"Epoch\((train|val|test)\)\s*\[\s*(\d+)\s*\]\[\s*\d+\s*/\s*\d+\s*\]", re.I)
EPOCH_RE = re.compile(r"Epoch(?:\((train|val|test)\))?\s*\[\s*(\d+)\s*/\s*(\d+)\s*\]", re.I)
ITER_RE = re.compile(r"Iter(?:\((train|val|test)\))?\s*\[\s*(\d+)\s*/\s*(\d+)\s*\]", re.I)
ETA_RE = re.compile(r"\beta\s*[:=]\s*([0-9]+(?::[0-9]+){1,2})", re.I)

PRIMARY_METRICS = (
    "mIoU",
    "IoU",
    "mDice",
    "mAcc",
    "aAcc",
    "mFscore",
    "BBox mAP",
    "BBox mAP50",
    "Segm mAP",
    "Segm mAP50",
    "mAP",
    "mAP50",
    "AP",
    "AP50",
    "AP75",
    "AR",
    "AR@100",
    "NDS",
    "PQ",
    "PCK",
    "Accuracy",
    "Top1 Acc",
    "Precision",
    "Recall",
    "Hmean",
    "PSNR",
    "SSIM",
    "MOTA",
    "IDF1",
)

SKIP_METRICS = {
    "eta",
    "time",
    "data_time",
    "memory",
    "iter",
    "epoch",
    "step",
    "global_step",
    "max_epochs",
    "max_iters",
    "lr",
    "momentum",
    "grad_norm",
}


def parse_eta_seconds(text: str) -> Optional[int]:
    parts = [int(part) for part in text.split(":")]
    if len(parts) == 3:
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    if len(parts) == 2:
        return parts[0] * 60 + parts[1]
    return None


def parse_openmmlab_history(path: Path, total_epochs_fallback: int) -> Tuple[int, List[Metric]]:
    by_epoch: Dict[int, Metric] = {}
    with path.open("r", encoding="utf-8", errors="ignore") as file:
        for line in file:
            parsed = parse_openmmlab_line(line)
            if not parsed:
                continue

            mode, epoch, eta_seconds, metrics = parsed
            existing = by_epoch.get(epoch)
            merged_metrics = dict(existing[4]) if existing else {}
            merged_metrics.update(metrics)
            primary_metric = choose_primary_metric(merged_metrics)
            by_epoch[epoch] = (
                primary_metric,
                epoch,
                merged_metrics[primary_metric],
                eta_seconds if eta_seconds is not None else existing[3] if existing else None,
                merged_metrics,
            )

    rows = [by_epoch[epoch] for epoch in sorted(by_epoch)]
    return infer_total_epochs(path, total_epochs_fallback, rows), rows


def parse_openmmlab_line(line: str) -> Optional[Tuple[str, int, Optional[int], Dict[str, float]]]:
    json_record = parse_json_line(line)
    if json_record is not None:
        return json_record
    return parse_text_line(line)


def parse_json_line(line: str) -> Optional[Tuple[str, int, Optional[int], Dict[str, float]]]:
    text = line.strip()
    if not text.startswith("{") or not text.endswith("}"):
        return None

    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return None
    if not isinstance(data, dict):
        return None

    values = flatten_json(data)
    epoch = first_int(values, ("epoch", "current_epoch", "step", "iter", "global_step"))
    if epoch is None:
        return None

    metrics = pick_metrics(values)
    if not metrics:
        return None

    mode = str(
        data.get("mode")
        or data.get("phase")
        or data.get("split")
        or ("train" if any("loss" in name.lower() for name in metrics) else "val")
    ).lower()
    eta_seconds = parse_eta_from_values(values)
    return mode, epoch, eta_seconds, metrics


def parse_text_line(line: str) -> Optional[Tuple[str, int, Optional[int], Dict[str, float]]]:
    match = EPOCH_BATCH_RE.search(line)
    mode = "train"
    epoch: Optional[int] = None
    if match:
        mode = match.group(1).lower()
        epoch = int(match.group(2))
    else:
        epoch_match = EPOCH_RE.search(line)
        if epoch_match:
            mode = (epoch_match.group(1) or "train").lower()
            epoch = int(epoch_match.group(2))
        else:
            iter_match = ITER_RE.search(line)
            if iter_match:
                mode = (iter_match.group(1) or "train").lower()
                epoch = int(iter_match.group(2))

    if epoch is None:
        return None

    metrics = parse_line_metrics(line)
    if not metrics:
        return None

    eta_match = ETA_RE.search(line)
    eta_seconds = parse_eta_seconds(eta_match.group(1)) if eta_match else None
    return mode, epoch, eta_seconds, metrics


def parse_line_metrics(line: str) -> Dict[str, float]:
    metrics = {}
    for raw_name, raw_value in METRIC_RE.findall(line):
        name = metric_label(raw_name)
        if name is None:
            continue
        value = parse_float(raw_value)
        if value is None:
            continue
        metrics[name] = normalize_metric_value(name, value)
    return metrics


def pick_metrics(values: Dict[str, Any]) -> Dict[str, float]:
    metrics = {}
    for key, raw_value in values.items():
        name = metric_label(key)
        if name is None:
            continue
        value = parse_float(raw_value)
        if value is None:
            continue
        metrics[name] = normalize_metric_value(name, value)
    return metrics


def metric_label(key: str) -> Optional[str]:
    clean = key.strip().strip('"')
    lowered = clean.lower().strip()
    for prefix in ("val/", "train/", "test/"):
        if lowered.startswith(prefix):
            lowered = lowered[len(prefix):]
    lowered = lowered.replace("metrics/", "")
    normalized = lowered.replace("-", "_").replace(".", "_")
    last_lowered = lowered.rsplit("/", 1)[-1]
    last_normalized = normalized.rsplit("/", 1)[-1]

    if normalized in SKIP_METRICS or normalized.endswith("_lr") or normalized.endswith("/lr"):
        return None
    if normalized.startswith(("time_", "eta_", "memory_")):
        return None

    known = {
        "miou": "mIoU",
        "iou": "IoU",
        "mdice": "mDice",
        "mfscore": "mFscore",
        "dice": "Dice",
        "macc": "mAcc",
        "aacc": "aAcc",
        "accuracy": "Accuracy",
        "acc": "Accuracy",
        "top1": "Top1 Acc",
        "top1_acc": "Top1 Acc",
        "accuracy/top1": "Top1 Acc",
        "top5": "Top5 Acc",
        "top5_acc": "Top5 Acc",
        "accuracy/top5": "Top5 Acc",
        "precision": "Precision",
        "recall": "Recall",
        "f1": "F1 Score",
        "f1_score": "F1 Score",
        "fscore": "Fscore",
        "map": "mAP",
        "map50": "mAP50",
        "map_50": "mAP50",
        "mean_average_precision": "mAP",
        "bbox_map": "BBox mAP",
        "bbox_map_50": "BBox mAP50",
        "bbox_map_75": "BBox mAP75",
        "bbox_map_s": "BBox mAP-S",
        "bbox_map_m": "BBox mAP-M",
        "bbox_map_l": "BBox mAP-L",
        "coco/bbox_map": "BBox mAP",
        "coco/bbox_map_50": "BBox mAP50",
        "coco/bbox_map_75": "BBox mAP75",
        "segm_map": "Segm mAP",
        "segm_map_50": "Segm mAP50",
        "segm_map_75": "Segm mAP75",
        "segm_map_s": "Segm mAP-S",
        "segm_map_m": "Segm mAP-M",
        "segm_map_l": "Segm mAP-L",
        "coco/segm_map": "Segm mAP",
        "coco/segm_map_50": "Segm mAP50",
        "coco/segm_map_75": "Segm mAP75",
        "ap": "AP",
        "ap50": "AP50",
        "ap_50": "AP50",
        "ap75": "AP75",
        "ap_75": "AP75",
        "ar": "AR",
        "ar@100": "AR@100",
        "ar_100": "AR@100",
        "pq": "PQ",
        "sq": "SQ",
        "rq": "RQ",
        "panoptic/pq": "PQ",
        "panoptic/sq": "SQ",
        "panoptic/rq": "RQ",
        "pck": "PCK",
        "auc": "AUC",
        "hmean": "Hmean",
        "word_acc": "Word Acc",
        "mean_class_accuracy": "Mean Class Accuracy",
        "psnr": "PSNR",
        "ssim": "SSIM",
        "fid": "FID",
        "is": "IS",
        "mota": "MOTA",
        "motp": "MOTP",
        "idf1": "IDF1",
        "idp": "IDP",
        "idr": "IDR",
        "nds": "NDS",
        "mate": "mATE",
        "mase": "mASE",
        "maoe": "mAOE",
        "mave": "mAVE",
        "maae": "mAAE",
        "mae": "MAE",
        "rmse": "RMSE",
        "nme": "NME",
        "epe": "EPE",
        "mpjpe": "MPJPE",
        "p_mpjpe": "P-MPJPE",
        "pa_mpjpe": "PA-MPJPE",
        "pve": "PVE",
        "minp": "mINP",
        "rank_1": "Rank-1",
        "rank_5": "Rank-5",
        "rank_10": "Rank-10",
    }
    if lowered in known:
        return known[lowered]
    if normalized in known:
        return known[normalized]
    if last_lowered in known:
        return known[last_lowered]
    if last_normalized in known:
        return known[last_normalized]

    if "bbox_map_50" in normalized:
        return "BBox mAP50"
    if "bbox_map_75" in normalized:
        return "BBox mAP75"
    if "bbox_map" in normalized:
        return "BBox mAP"
    if "segm_map_50" in normalized:
        return "Segm mAP50"
    if "segm_map_75" in normalized:
        return "Segm mAP75"
    if "segm_map" in normalized:
        return "Segm mAP"
    if "map_50" in normalized or normalized.endswith("map50"):
        return "mAP50"
    if normalized.endswith("_map") or normalized.endswith("/map"):
        return "mAP"
    if "loss" in normalized:
        return clean.split("/")[-1]
    if normalized.endswith("_ap") or "/ap" in normalized:
        return "AP"
    if normalized.endswith("_acc") or "accuracy" in normalized:
        return clean.split("/")[-1].replace("_", " ").title()
    return None


def normalize_metric_value(name: str, value: float) -> float:
    lowered = name.lower()
    minimize = any(
        word in lowered
        for word in ("loss", "error", "mae", "rmse", "nme", "epe", "fid", "mate", "mase", "maoe", "mave", "maae", "mpjpe", "pve")
    )
    if not minimize and 0 <= value <= 1:
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


def infer_total_epochs(path: Path, fallback: int, rows: Optional[List[Metric]] = None) -> int:
    if fallback > 0:
        return fallback

    with path.open("r", encoding="utf-8", errors="ignore") as file:
        for line in file:
            total = parse_total_from_line(line)
            if total:
                return total

    return 0


def parse_total_from_line(line: str) -> Optional[int]:
    json_total = parse_total_from_json_line(line)
    if json_total:
        return json_total

    match = TOTAL_RE.search(line) or MAX_ITER_RE.search(line)
    if match:
        return int(match.group(1))

    epoch_match = EPOCH_RE.search(line)
    if epoch_match:
        return int(epoch_match.group(3))

    iter_match = ITER_RE.search(line)
    if iter_match:
        return int(iter_match.group(3))

    return None


def parse_total_from_json_line(line: str) -> Optional[int]:
    text = line.strip()
    if not text.startswith("{") or not text.endswith("}"):
        return None
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return None
    if not isinstance(data, dict):
        return None
    values = flatten_json(data)
    return first_int(values, ("max_epochs", "max_epoch", "total_epochs", "max_iters", "max_iter", "total_iters"))


def best_row(rows: List[Metric]) -> Metric:
    quality_rows = [item for item in rows if not metric_should_minimize(item[0])]
    if quality_rows:
        return max(quality_rows, key=lambda item: item[2])
    return min(rows, key=lambda item: item[2])


def metric_should_minimize(name: str) -> bool:
    lowered = name.lower()
    return any(
        word in lowered
        for word in ("loss", "error", "mae", "rmse", "nme", "epe", "fid", "mate", "mase", "maoe", "mave", "maae", "mpjpe", "pve")
    )


def flatten_json(data: Dict[str, Any], prefix: str = "") -> Dict[str, Any]:
    values: Dict[str, Any] = {}
    for key, value in data.items():
        full_key = f"{prefix}/{key}" if prefix else str(key)
        if isinstance(value, dict):
            values.update(flatten_json(value, full_key))
        else:
            values[full_key] = value
            values[str(key)] = value
    return values


def first_int(values: Dict[str, Any], keys: Tuple[str, ...]) -> Optional[int]:
    for key in keys:
        if key in values:
            parsed = parse_int(values[key])
            if parsed is not None:
                return parsed + 1 if key in {"step", "iter", "global_step"} and parsed == 0 else parsed
    return None


def parse_eta_from_values(values: Dict[str, Any]) -> Optional[int]:
    for key, value in values.items():
        if "eta" not in key.lower():
            continue
        if isinstance(value, str):
            return parse_eta_seconds(value)
        parsed = parse_int(value)
        if parsed is not None:
            return parsed
    return None


def parse_int(value: Any) -> Optional[int]:
    try:
        return int(float(str(value).strip()))
    except (TypeError, ValueError):
        return None


def parse_float(value: Any) -> Optional[float]:
    try:
        return float(str(value).strip())
    except (TypeError, ValueError):
        return None
