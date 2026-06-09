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


def parse_cors_origins(raw: str) -> list[str]:
    return [
        origin
        for origin in (item.strip() for item in raw.split(","))
        if origin and origin != "*"
    ]


CORS_ORIGINS = parse_cors_origins(os.getenv("TRAINING_MONITOR_CORS_ORIGINS", ""))


class TrainingUpdate(BaseModel):
    run_id: Optional[str] = Field(default=None, max_length=MAX_RUN_ID_LENGTH)
    epoch: int = Field(ge=0)
    total_epochs: int = Field(ge=1)
    iou: Optional[float] = None
    metric_name: str = Field(default="IoU", max_length=MAX_METRIC_NAME_LENGTH)
    metrics: Dict[str, float] = Field(default_factory=dict)
    loss: Optional[float] = Field(default=None, ge=0.0)
    eta_seconds: Optional[int] = Field(default=None, ge=0)
    status: Literal["training", "finished", "error"] = "training"


def now_text() -> str:
    return datetime.now().isoformat(timespec="seconds")


def empty_state() -> dict:
    return {
        "status": "idle",
        "run_id": None,
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


def normalize_state(loaded: dict) -> dict:
    state = empty_state()
    if isinstance(loaded, dict):
        state.update(loaded)
    state["metrics"] = safe_dict(state.get("metrics"))
    state["best_metrics"] = safe_dict(state.get("best_metrics"))
    state["best_epochs"] = safe_dict(state.get("best_epochs"))
    state["available_metrics"] = safe_list(state.get("available_metrics"))
    state["history"] = safe_list(state.get("history"))[-500:]
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


def estimate_eta_seconds(state: dict, epoch: int, total_epochs: int) -> Optional[int]:
    history = state.get("history", [])
    if epoch <= 0 or not history:
        return None

    started_at = state.get("started_at")
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


def update_available_metrics(state: dict, metrics: dict) -> None:
    available = list(state.get("available_metrics") or [])
    for name in metrics:
        if name not in available:
            available.append(name)
    state["available_metrics"] = available


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
    primary_value = metrics[primary_name]
    run_changed = bool(update.run_id and update.run_id != state.get("run_id"))
    epoch_restarted = bool(state.get("epoch") and update.epoch < state.get("epoch"))
    should_reset = (
        not state.get("started_at")
        or state.get("status") in {"idle", "finished"}
        or run_changed
        or epoch_restarted
    )

    if should_reset:
        state["started_at"] = now_text()
        state["history"] = []
        state["best_iou"] = None
        state["best_metrics"] = {}
        state["best_epochs"] = {}
        state["available_metrics"] = []
        state["best_epoch"] = None
        state["run_id"] = update.run_id
    elif update.run_id:
        state["run_id"] = update.run_id

    best_metrics = state.setdefault("best_metrics", {})
    best_epochs = state.setdefault("best_epochs", {})
    for name, value in metrics.items():
        if better_metric(name, value, best_metrics.get(name)):
            best_metrics[name] = value
            best_epochs[name] = update.epoch

    state["best_iou"] = best_metrics.get(primary_name)
    state["best_epoch"] = best_epochs.get(primary_name)
    update_available_metrics(state, metrics)

    state["status"] = update.status
    state["epoch"] = update.epoch
    state["total_epochs"] = update.total_epochs
    state["current_iou"] = primary_value
    state["metric_name"] = primary_name
    state["metrics"] = metrics
    state["updated_at"] = now_text()
    state["history"].append(
        {
            "epoch": update.epoch,
            "iou": primary_value,
            "metric_name": primary_name,
            "metrics": metrics,
            "updated_at": state["updated_at"],
        }
    )
    state["history"] = state["history"][-500:]
    state["eta_seconds"] = update.eta_seconds
    if state["eta_seconds"] is None:
        state["eta_seconds"] = estimate_eta_seconds(
            state,
            update.epoch,
            update.total_epochs,
        )

    save_state(state)
    return state


@app.post("/api/reset", dependencies=[Depends(require_token)])
def reset_status() -> dict:
    state.clear()
    state.update(empty_state())
    save_state(state)
    return state
