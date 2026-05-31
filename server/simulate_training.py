import os
from random import random
from time import sleep

from training_monitor import TrainingMonitor


monitor = TrainingMonitor(
    os.getenv("SERVER_URL", "http://127.0.0.1:6006"),
    token=os.getenv("MONITOR_TOKEN", ""),
)
total_epochs = 30
best_iou = 0.0

for epoch in range(1, total_epochs + 1):
    sleep(1)
    best_iou = min(0.95, max(best_iou, 0.35 + epoch * 0.015 + random() * 0.04))
    status = "finished" if epoch == total_epochs else "training"
    monitor.log(
        epoch=epoch,
        total_epochs=total_epochs,
        iou=best_iou,
        metric_name="IoU",
        status=status,
    )
    print(f"epoch={epoch}/{total_epochs}, iou={best_iou:.4f}, status={status}")
