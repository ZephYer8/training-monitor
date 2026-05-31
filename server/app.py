from datetime import datetime
import os
from pathlib import Path
from typing import Literal, Optional
import json

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field


DATA_FILE = Path(os.getenv("TRAINING_MONITOR_STATE_FILE", Path(__file__).with_name("state.json")))
MONITOR_TOKEN = os.getenv("MONITOR_TOKEN", "")


class TrainingUpdate(BaseModel):
    run_id: Optional[str] = None
    epoch: int = Field(ge=0)
    total_epochs: int = Field(ge=1)
    iou: float = Field(ge=0.0, le=100.0)
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
        return json.loads(DATA_FILE.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return empty_state()


def save_state(state: dict) -> None:
    DATA_FILE.write_text(
        json.dumps(state, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def require_token(x_monitor_token: str = Header(default="")) -> None:
    if MONITOR_TOKEN and x_monitor_token != MONITOR_TOKEN:
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


state = load_state()
app = FastAPI(title="Training Monitor")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/api/health")
def health() -> dict:
    return {"ok": True}


@app.get("/api/status", dependencies=[Depends(require_token)])
def get_status() -> dict:
    return state


@app.post("/api/status", dependencies=[Depends(require_token)])
def update_status(update: TrainingUpdate) -> dict:
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
        state["best_epoch"] = None
        state["run_id"] = update.run_id
    elif update.run_id:
        state["run_id"] = update.run_id

    best_iou = state.get("best_iou")
    if best_iou is None or update.iou > best_iou:
        state["best_iou"] = update.iou
        state["best_epoch"] = update.epoch

    state["status"] = update.status
    state["epoch"] = update.epoch
    state["total_epochs"] = update.total_epochs
    state["current_iou"] = update.iou
    state["updated_at"] = now_text()
    state["history"].append(
        {
            "epoch": update.epoch,
            "iou": update.iou,
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
