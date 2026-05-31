from dataclasses import dataclass
from typing import Optional

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
        iou: float,
        metric_name: str = "IoU",
        eta_seconds: Optional[int] = None,
        status: str = "training",
    ) -> dict:
        payload = {
            "epoch": epoch,
            "total_epochs": total_epochs,
            "iou": float(iou),
            "metric_name": metric_name,
            "status": status,
        }
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
