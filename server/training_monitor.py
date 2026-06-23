from dataclasses import dataclass
import os
from typing import Dict, Optional, Sequence

import requests


def normalize_gpu_ids(*groups: object) -> list[str]:
    gpu_ids: list[str] = []
    for group in groups:
        if group is None:
            continue
        values = group if isinstance(group, (list, tuple, set)) else [group]
        for value in values:
            gpu_id = str(value).strip()
            if gpu_id and gpu_id.lower() not in {"none", "null", "nodevfiles"} and gpu_id not in gpu_ids:
                gpu_ids.append(gpu_id)
    return gpu_ids


def visible_cuda_devices() -> list[str]:
    raw = os.getenv("CUDA_VISIBLE_DEVICES", "").strip()
    if not raw or raw.lower() in {"none", "null", "nodevfiles"}:
        return []
    return [part.strip() for part in raw.split(",") if part.strip()]


@dataclass
class TrainingMonitor:
    server_url: str
    token: str = ""
    timeout: float = 3.0

    def log(
        self,
        *,
        run_id: Optional[str] = None,
        gpu_id: Optional[str] = None,
        gpu_ids: Optional[Sequence[str]] = None,
        epoch: int,
        total_epochs: int,
        iou: Optional[float] = None,
        metric_name: str = "IoU",
        metrics: Optional[Dict[str, float]] = None,
        loss: Optional[float] = None,
        eta_seconds: Optional[int] = None,
        status: str = "training",
    ) -> dict:
        payload = {
            "epoch": epoch,
            "total_epochs": total_epochs,
            "metric_name": metric_name,
            "status": status,
        }
        if iou is not None:
            payload["iou"] = float(iou)
        if metrics is not None:
            payload["metrics"] = {name: float(value) for name, value in metrics.items()}
        if loss is not None:
            payload["loss"] = float(loss)
        if run_id is not None:
            payload["run_id"] = run_id
        resolved_gpu_ids = normalize_gpu_ids(gpu_ids, gpu_id, visible_cuda_devices())
        if resolved_gpu_ids:
            payload["gpu_ids"] = resolved_gpu_ids
            payload["gpu_id"] = resolved_gpu_ids[0]
        if eta_seconds is not None:
            payload["eta_seconds"] = int(eta_seconds)

        response = requests.post(
            f"{self.server_url.rstrip('/')}/api/status",
            headers={"X-Monitor-Token": self.token} if self.token else None,
            json=payload,
            timeout=self.timeout,
        )
        response.raise_for_status()
        return response.json()
