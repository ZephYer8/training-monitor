from datetime import datetime
import hmac
import math
import os
from pathlib import Path
from typing import Dict, Literal, Optional
import json

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field


DATA_FILE = Path(os.getenv("TRAINING_MONITOR_STATE_FILE", Path(__file__).with_name("state.json")))
MONITOR_TOKEN = os.getenv("MONITOR_TOKEN", "")
MAX_METRICS_PER_UPDATE = 64
MAX_METRIC_NAME_LENGTH = 64
MAX_RUN_ID_LENGTH = 128
MAX_GPU_ID_LENGTH = 32
MAX_GPU_IDS_PER_UPDATE = 16
MAX_RUNS = 32


def parse_cors_origins(raw: str) -> list[str]:
    return [
        origin
        for origin in (item.strip() for item in raw.split(","))
        if origin and origin != "*"
    ]


CORS_ORIGINS = parse_cors_origins(os.getenv("TRAINING_MONITOR_CORS_ORIGINS", ""))


class TrainingUpdate(BaseModel):
    run_id: Optional[str] = Field(default=None, max_length=MAX_RUN_ID_LENGTH)
    gpu_id: Optional[str] = Field(default=None, max_length=MAX_GPU_ID_LENGTH)
    gpu_ids: list[str] = Field(default_factory=list, max_length=MAX_GPU_IDS_PER_UPDATE)
    epoch: int = Field(ge=0)
    total_epochs: int = Field(ge=1)
    iou: Optional[float] = None
    metric_name: str = Field(default="IoU", max_length=MAX_METRIC_NAME_LENGTH)
    metrics: Dict[str, float] = Field(default_factory=dict)
    loss: Optional[float] = Field(default=None, ge=0.0)
    eta_seconds: Optional[int] = Field(default=None, ge=0)
    status: Literal["training", "finished", "error"] = "training"


def now_text() -> str:
    return datetime.now().isoformat(timespec="microseconds")


def empty_run_state(run_id: Optional[str] = None) -> dict:
    return {
        "status": "idle",
        "run_id": run_id,
        "gpu_id": None,
        "gpu_ids": [],
        "epoch": 0,
        "total_epochs": 0,
        "current_iou": None,
        "best_iou": None,
        "metric_name": "IoU",
        "metrics": {},
        "best_metrics": {},
        "best_epochs": {},
        "available_metrics": [],
        "best_epoch": None,
        "eta_seconds": None,
        "started_at": None,
        "updated_at": None,
        "history": [],
    }


def empty_state() -> dict:
    state = empty_run_state()
    state["runs"] = []
    state["active_run_id"] = None
    state["available_gpus"] = []
    return state


def load_state() -> dict:
    if not DATA_FILE.exists():
        return empty_state()
    try:
        loaded = json.loads(DATA_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return empty_state()
    return normalize_state(loaded)


def safe_dict(value: object) -> dict:
    return value if isinstance(value, dict) else {}


def safe_list(value: object) -> list:
    return value if isinstance(value, list) else []


def clean_gpu_id(value: object) -> Optional[str]:
    cleaned = str(value).strip()
    if not cleaned or cleaned.lower() in {"none", "null", "nodevfiles"}:
        return None
    if len(cleaned) > MAX_GPU_ID_LENGTH:
        raise HTTPException(status_code=400, detail="gpu id is too long")
    return cleaned


def normalize_gpu_ids(*groups: object) -> list[str]:
    gpu_ids: list[str] = []
    for group in groups:
        if group is None:
            continue
        values = group if isinstance(group, list) else [group]
        for value in values:
            gpu_id = clean_gpu_id(value)
            if gpu_id and gpu_id not in gpu_ids:
                gpu_ids.append(gpu_id)
    return gpu_ids[:MAX_GPU_IDS_PER_UPDATE]


def normalize_run(loaded: dict) -> dict:
    run = empty_run_state(loaded.get("run_id"))
    if isinstance(loaded, dict):
        run.update(loaded)
    run["metrics"] = safe_dict(run.get("metrics"))
    run["best_metrics"] = safe_dict(run.get("best_metrics"))
    run["best_epochs"] = safe_dict(run.get("best_epochs"))
    run["available_metrics"] = safe_list(run.get("available_metrics"))
    run["history"] = safe_list(run.get("history"))[-500:]
    run["gpu_ids"] = normalize_gpu_ids(run.get("gpu_ids"), run.get("gpu_id"))
    run["gpu_id"] = run["gpu_ids"][0] if run["gpu_ids"] else None
    return run


def legacy_run_from_state(loaded: dict) -> Optional[dict]:
    if not isinstance(loaded, dict):
        return None
    if loaded.get("status") == "idle" and not loaded.get("history"):
        return None
    run = empty_run_state(loaded.get("run_id"))
    for key in run:
        if key in loaded:
            run[key] = loaded[key]
    return normalize_run(run)


def normalize_state(loaded: dict) -> dict:
    state = empty_state()
    if isinstance(loaded, dict):
        runs = [normalize_run(run) for run in safe_list(loaded.get("runs")) if isinstance(run, dict)]
        if not runs:
            legacy = legacy_run_from_state(loaded)
            if legacy:
                runs = [legacy]
        state["runs"] = runs[-MAX_RUNS:]
    sync_root_from_runs(state)
    return state


def save_state(state: dict) -> None:
    DATA_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp_file = DATA_FILE.with_name(f"{DATA_FILE.name}.tmp")
    tmp_file.write_text(
        json.dumps(state, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    tmp_file.replace(DATA_FILE)
    try:
        os.chmod(DATA_FILE, 0o600)
    except OSError:
        pass


def require_token(x_monitor_token: str = Header(default="")) -> None:
    if not MONITOR_TOKEN:
        raise HTTPException(status_code=503, detail="server token is not configured")
    if not hmac.compare_digest(x_monitor_token, MONITOR_TOKEN):
        raise HTTPException(status_code=401, detail="invalid token")


def parse_time(value: object) -> datetime:
    if not value:
        return datetime.min
    try:
        return datetime.fromisoformat(str(value))
    except ValueError:
        return datetime.min


def estimate_eta_seconds(run: dict, epoch: int, total_epochs: int) -> Optional[int]:
    history = run.get("history", [])
    if epoch <= 0 or not history:
        return None

    started_at = run.get("started_at")
    if not started_at:
        return None

    start_time = datetime.fromisoformat(started_at)
    elapsed = (datetime.now() - start_time).total_seconds()
    avg_epoch_seconds = elapsed / max(epoch, 1)
    remaining_epochs = max(total_epochs - epoch, 0)
    return int(avg_epoch_seconds * remaining_epochs)


def clean_metric_name(name: str) -> str:
    cleaned = str(name).strip()
    if not cleaned:
        raise HTTPException(status_code=400, detail="metric name must not be empty")
    if len(cleaned) > MAX_METRIC_NAME_LENGTH:
        raise HTTPException(status_code=400, detail="metric name is too long")
    return cleaned


def finite_float(value: object, label: str) -> float:
    parsed = float(value)
    if not math.isfinite(parsed):
        raise HTTPException(status_code=400, detail=f"{label} must be finite")
    return parsed


def build_metrics(update: TrainingUpdate) -> dict:
    if len(update.metrics) > MAX_METRICS_PER_UPDATE:
        raise HTTPException(status_code=400, detail="too many metrics in one update")

    metrics = {
        clean_metric_name(name): finite_float(value, clean_metric_name(name))
        for name, value in update.metrics.items()
    }
    if update.iou is not None:
        name = clean_metric_name(update.metric_name or "IoU")
        metrics[name] = finite_float(update.iou, name)
    if update.loss is not None:
        metrics["loss"] = finite_float(update.loss, "loss")
    if not metrics:
        raise HTTPException(status_code=400, detail="metrics or iou is required")
    return metrics


def metric_should_minimize(name: str) -> bool:
    lowered = name.lower()
    return any(word in lowered for word in ("loss", "error", "mae", "rmse", "nme", "epe", "fid"))


def better_metric(name: str, value: float, best_value: Optional[float]) -> bool:
    if best_value is None:
        return True
    if metric_should_minimize(name):
        return value < best_value
    return value > best_value


def update_available_metrics(run: dict, metrics: dict) -> None:
    available = list(run.get("available_metrics") or [])
    for name in metrics:
        if name not in available:
            available.append(name)
    run["available_metrics"] = available


def run_identity(update: TrainingUpdate, gpu_ids: list[str]) -> str:
    if update.run_id:
        return update.run_id
    if gpu_ids:
        return "gpu:" + ",".join(gpu_ids)
    return "default"


def find_or_create_run(state: dict, run_id: str) -> dict:
    runs = state.setdefault("runs", [])
    for run in runs:
        if run.get("run_id") == run_id:
            return run
    run = empty_run_state(run_id)
    runs.append(run)
    return run


def update_run(run: dict, update: TrainingUpdate, metrics: dict, primary_name: str, gpu_ids: list[str]) -> None:
    run_changed = bool(update.run_id and update.run_id != run.get("run_id"))
    epoch_restarted = bool(run.get("epoch") and update.epoch < run.get("epoch"))
    should_reset = (
        not run.get("started_at")
        or run.get("status") in {"idle", "finished"}
        or run_changed
        or epoch_restarted
    )

    if should_reset:
        run["started_at"] = now_text()
        run["history"] = []
        run["best_iou"] = None
        run["best_metrics"] = {}
        run["best_epochs"] = {}
        run["available_metrics"] = []
        run["best_epoch"] = None
    run["run_id"] = run_identity(update, gpu_ids)
    if gpu_ids:
        run["gpu_ids"] = gpu_ids
        run["gpu_id"] = gpu_ids[0]

    best_metrics = run.setdefault("best_metrics", {})
    best_epochs = run.setdefault("best_epochs", {})
    for name, value in metrics.items():
        if better_metric(name, value, best_metrics.get(name)):
            best_metrics[name] = value
            best_epochs[name] = update.epoch

    primary_value = metrics[primary_name]
    run["best_iou"] = best_metrics.get(primary_name)
    run["best_epoch"] = best_epochs.get(primary_name)
    update_available_metrics(run, metrics)

    run["status"] = update.status
    run["epoch"] = update.epoch
    run["total_epochs"] = update.total_epochs
    run["current_iou"] = primary_value
    run["metric_name"] = primary_name
    run["metrics"] = metrics
    run["updated_at"] = now_text()
    run["history"].append(
        {
            "epoch": update.epoch,
            "iou": primary_value,
            "metric_name": primary_name,
            "metrics": metrics,
            "updated_at": run["updated_at"],
        }
    )
    run["history"] = run["history"][-500:]
    run["eta_seconds"] = update.eta_seconds
    if run["eta_seconds"] is None:
        run["eta_seconds"] = estimate_eta_seconds(
            run,
            update.epoch,
            update.total_epochs,
        )


def active_run(runs: list[dict]) -> Optional[dict]:
    if not runs:
        return None
    training = [run for run in runs if run.get("status") == "training"]
    candidates = training or runs
    return max(candidates, key=lambda run: parse_time(run.get("updated_at")))


def available_gpus(runs: list[dict]) -> list[str]:
    result: list[str] = []
    for run in runs:
        for gpu_id in normalize_gpu_ids(run.get("gpu_ids"), run.get("gpu_id")):
            if gpu_id not in result:
                result.append(gpu_id)
    return result


def sync_root_from_runs(state: dict) -> None:
    runs = [normalize_run(run) for run in safe_list(state.get("runs")) if isinstance(run, dict)]
    runs.sort(key=lambda run: parse_time(run.get("updated_at")), reverse=True)
    state["runs"] = runs[:MAX_RUNS]
    selected = active_run(state["runs"])
    if selected is None:
        fresh = empty_state()
        state.clear()
        state.update(fresh)
        return

    root_runs = state["runs"]
    for key, value in selected.items():
        state[key] = value
    state["runs"] = root_runs
    state["active_run_id"] = selected.get("run_id")
    state["available_gpus"] = available_gpus(root_runs)


state = load_state()
app = FastAPI(title="Training Monitor")

if CORS_ORIGINS:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=CORS_ORIGINS,
        allow_methods=["GET", "POST", "OPTIONS"],
        allow_headers=["X-Monitor-Token", "Content-Type"],
    )


@app.get("/api/health")
def health() -> dict:
    return {"ok": True}


@app.get("/api/status", dependencies=[Depends(require_token)])
def get_status() -> dict:
    return state


@app.post("/api/status", dependencies=[Depends(require_token)])
def update_status(update: TrainingUpdate) -> dict:
    metrics = build_metrics(update)
    primary_name = update.metric_name if update.metric_name in metrics else next(iter(metrics))
    gpu_ids = normalize_gpu_ids(update.gpu_ids, update.gpu_id)
    run_id = run_identity(update, gpu_ids)
    run = find_or_create_run(state, run_id)
    update_run(run, update, metrics, primary_name, gpu_ids)
    sync_root_from_runs(state)
    save_state(state)
    return state


@app.post("/api/reset", dependencies=[Depends(require_token)])
def reset_status() -> dict:
    state.clear()
    state.update(empty_state())
    save_state(state)
    return state
