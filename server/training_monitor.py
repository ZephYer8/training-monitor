from dataclasses import dataclass
from typing import Dict, Optional

import requests


@dataclass
class TrainingMonitor:
    server_url: str
    token: str = ""
    timeout: float = 3.0

    def log(
        self,
        *,
        run_id: Optional[str] = None,
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
