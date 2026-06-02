import argparse
import os
from pathlib import Path

from training_monitor import TrainingMonitor
from openmmlab_log import best_row, infer_total_epochs, parse_openmmlab_history


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--log-path", required=True)
    parser.add_argument("--server-url", default="http://127.0.0.1:6006")
    parser.add_argument("--token", default=os.getenv("MONITOR_TOKEN", ""))
    parser.add_argument("--total-epochs", type=int, default=0)
    args = parser.parse_args()

    log_path = Path(args.log_path)
    total_epochs, rows = parse_openmmlab_history(log_path, args.total_epochs)
    if args.total_epochs > 0:
        total_epochs = infer_total_epochs(log_path, args.total_epochs, rows)

    if not rows:
        raise SystemExit("no OpenMMLab metrics found")

    best = best_row(rows)
    latest = rows[-1]
    monitor = TrainingMonitor(args.server_url, token=args.token)

    monitor.log(
        run_id=str(log_path),
        epoch=best[1],
        total_epochs=total_epochs,
        iou=best[2],
        metric_name=best[0],
        metrics=best[4],
        eta_seconds=best[3],
    )
    state = monitor.log(
        run_id=str(log_path),
        epoch=latest[1],
        total_epochs=total_epochs,
        iou=latest[2],
        metric_name=latest[0],
        metrics=latest[4],
        eta_seconds=latest[3],
    )

    print(
        f"synced latest={latest[0]} {latest[2]:.4f}@{latest[1]}, "
        f"best={best[0]} {best[2]:.4f}@{best[1]}, eta_seconds={latest[3]}"
    )
    print(state)


if __name__ == "__main__":
    main()
