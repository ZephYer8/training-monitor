from pathlib import Path
from tempfile import TemporaryDirectory

import app as app_module
from app import TrainingSnapshot, TrainingUpdate


def reset_test_state(tmp_path: Path) -> None:
    app_module.DATA_FILE = tmp_path / "state.json"
    app_module.state.clear()
    app_module.state.update(app_module.empty_state())


def test_separate_gpu_runs_are_kept_side_by_side() -> None:
    with TemporaryDirectory() as tmp:
        reset_test_state(Path(tmp))

        app_module.update_status(
            TrainingUpdate(
                run_id="model-a",
                gpu_id="0",
                epoch=1,
                total_epochs=10,
                iou=0.5,
                metric_name="IoU",
            )
        )
        state = app_module.update_status(
            TrainingUpdate(
                run_id="model-b",
                gpu_id="1",
                epoch=2,
                total_epochs=10,
                iou=0.6,
                metric_name="IoU",
            )
        )

        assert state["status"] == "training"
        assert state["available_gpus"] == ["1", "0"]
        assert {run["run_id"] for run in state["runs"]} == {"model-a", "model-b"}
        assert {tuple(run["gpu_ids"]) for run in state["runs"]} == {("0",), ("1",)}


def test_one_run_can_span_multiple_gpus_without_splitting() -> None:
    with TemporaryDirectory() as tmp:
        reset_test_state(Path(tmp))

        state = app_module.update_status(
            TrainingUpdate(
                run_id="ddp-model",
                gpu_ids=["0", "1"],
                epoch=1,
                total_epochs=10,
                iou=0.7,
                metric_name="IoU",
            )
        )

        assert len(state["runs"]) == 1
        assert state["runs"][0]["run_id"] == "ddp-model"
        assert state["runs"][0]["gpu_ids"] == ["0", "1"]
        assert state["available_gpus"] == ["0", "1"]


def test_unknown_total_epochs_is_accepted() -> None:
    with TemporaryDirectory() as tmp:
        reset_test_state(Path(tmp))

        state = app_module.update_status(
            TrainingUpdate(
                run_id="unknown-total",
                epoch=20,
                total_epochs=0,
                metrics={"loss": 0.38},
                metric_name="loss",
            )
        )

        assert state["status"] == "training"
        assert state["total_epochs"] == 0
        assert state["eta_seconds"] is None


def test_same_epoch_updates_replace_history_point() -> None:
    with TemporaryDirectory() as tmp:
        reset_test_state(Path(tmp))

        app_module.update_status(
            TrainingUpdate(
                run_id="same-epoch",
                epoch=3,
                total_epochs=10,
                metrics={"loss": 0.5},
                metric_name="loss",
            )
        )
        state = app_module.update_status(
            TrainingUpdate(
                run_id="same-epoch",
                epoch=3,
                total_epochs=10,
                metrics={"mIoU": 72.0},
                metric_name="mIoU",
            )
        )

        assert len(state["history"]) == 1
        assert state["history"][0]["metrics"] == {"loss": 0.5, "mIoU": 72.0}


def test_batch_snapshot_keeps_history_after_finished_status() -> None:
    with TemporaryDirectory() as tmp:
        reset_test_state(Path(tmp))

        result = app_module.update_snapshot(
            TrainingSnapshot(
                updates=[
                    TrainingUpdate(
                        run_id="batch-run",
                        epoch=1,
                        total_epochs=2,
                        metrics={"mIoU": 60.0},
                        metric_name="mIoU",
                    ),
                    TrainingUpdate(
                        run_id="batch-run",
                        epoch=2,
                        total_epochs=2,
                        metrics={"mIoU": 70.0},
                        metric_name="mIoU",
                        status="finished",
                    ),
                ]
            )
        )
        state = app_module.state_snapshot()

        assert result["updated"] == 2
        assert state["status"] == "finished"
        assert len(state["history"]) == 2
        assert state["best_metrics"]["mIoU"] == 70.0
