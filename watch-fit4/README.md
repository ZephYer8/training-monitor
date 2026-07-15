# Huawei WATCH FIT 4 companion

This directory contains the first slice of the native watch companion for Training Monitor.

The current Android app already supports Huawei WATCH FIT 4 through phone notification sync. That is useful as a fallback, but it is still a notification. The goal of this module is different: provide a watch-side page that users can open at any time while the watch is connected to the phone.

## Architecture

```text
Training server
  -> Android app
  -> Wear Engine P2P message
  -> FIT 4 companion page
```

The phone remains the source of truth. It polls `/api/status`, formats the current `TrainingStatus` with `WatchStatusPayload`, and sends the JSON payload to the watch app through Huawei Wear Engine.

The watch page keeps the latest payload in memory and renders:

- training status and progress
- epoch / total epochs
- current primary metric
- best metric and best epoch
- ETA
- last update time

## Current status

Implemented in this repository:

- `android/app/src/main/java/com/modeltest/monitor/WatchStatusPayload.kt`
  - Converts the Android `TrainingStatus` object into the compact JSON payload used by the watch.
- `protocol/training-status-v1.schema.json`
  - Documents the payload contract between the phone and the watch.
- `lite-wearable/`
  - A Lite Wearable page skeleton for DevEco Studio. It renders mock data and exposes an `updateFromMessage()` entry point for Wear Engine messages.

Still required on a Huawei development machine:

- Import the watch module into DevEco Studio.
- Replace the placeholder message receiver in `index.js` with the actual Huawei wearable-side Wear Engine receiver API for the selected FIT 4 SDK target.
- Add Huawei Wear Engine SDK to the Android phone app and call `WatchStatusPayload.buildString(status, selectedMetricsText)` before sending the P2P message.
- Sign and install both phone and watch apps with matching package configuration.

## Phone-side message shape

Example:

```json
{
  "type": "training_status",
  "version": 1,
  "title": "模迹 训练中 43%",
  "summary": "E13/30 · mIoU 78.42 · Best 79.10@12 · ETA 1h20m",
  "run_id": "segformer-cityscapes",
  "run_name": "segformer-cityscapes",
  "status": "training",
  "epoch": 13,
  "total_epochs": 30,
  "progress_percent": 43,
  "metric_name": "mIoU",
  "current_metric": 78.42,
  "best_metric": 79.1,
  "best_epoch": 12,
  "eta_seconds": 4800,
  "updated_at": "2026-07-07T00:00:00",
  "gpu_ids": ["0"],
  "metrics": [
    {
      "name": "mIoU",
      "display_name": "mIoU",
      "current": 78.42,
      "best": 79.1,
      "best_epoch": 12
    }
  ]
}
```

## Why this is separate from notification sync

Notification sync is controlled by Android, Huawei Health, and the watch notification center. It cannot guarantee that the user can open a dedicated training screen at any time.

This module is the foundation for the dedicated screen. Once Wear Engine is wired in, the user can open the watch app page directly and see the latest status that the phone has pushed.
