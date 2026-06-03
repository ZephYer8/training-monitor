from pathlib import Path
from contextlib import contextmanager
import shutil

from auto_watch import latest_files, parse_file
from openmmlab_log import best_row, metric_should_minimize, parse_openmmlab_history


def write(path: Path, text: str) -> Path:
    path.write_text(text.strip() + "\n", encoding="utf-8")
    return path


@contextmanager
def test_dir(name: str):
    root = Path(__file__).resolve().parent / ".test-tmp" / name
    if root.exists():
        shutil.rmtree(root, ignore_errors=True)
    root.mkdir(parents=True, exist_ok=True)
    try:
        yield root
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_mmdetection_text_log() -> None:
    with test_dir("mmdetection") as tmp:
        log_path = write(
            tmp / "20260602.log",
            """
            max_epochs = 12
            06/02 20:00:00 - mmengine - INFO - Epoch(train) [2][50/100] lr: 1e-4 eta: 00:10:00 loss_cls: 0.8 loss_bbox: 0.3 loss: 1.1
            06/02 20:01:00 - mmengine - INFO - Epoch(val) [2][100/100] coco/bbox_mAP: 0.376 coco/bbox_mAP_50: 0.587
            """,
        )

        total_epochs, rows = parse_openmmlab_history(log_path, 0)
        assert total_epochs == 12
        assert len(rows) == 1
        assert rows[0][1] == 2
        assert rows[0][0] == "BBox mAP"
        assert round(rows[0][4]["BBox mAP"], 2) == 37.60
        assert round(rows[0][4]["BBox mAP50"], 2) == 58.70
        assert rows[0][4]["loss"] == 1.1


def test_mmengine_json_log() -> None:
    with test_dir("mmengine-json") as tmp:
        json_path = write(
            tmp / "20260602.json",
            """
            {"mode":"train","epoch":1,"iter":50,"loss":1.2,"lr":0.001}
            {"mode":"val","epoch":1,"mIoU":0.756,"aAcc":0.88}
            {"mode":"val","epoch":2,"metrics":{"accuracy/top1":76.3,"accuracy/top5":93.1}}
            """,
        )

        total_epochs, rows = parse_openmmlab_history(json_path, 0)
        assert total_epochs == 2
        assert rows[0][4]["loss"] == 1.2
        assert round(rows[0][4]["mIoU"], 2) == 75.60
        assert round(rows[0][4]["aAcc"], 2) == 88.00
        assert rows[1][0] == "Top1 Acc"
        assert rows[1][4]["Top5 Acc"] == 93.1


def test_mmpose_metrics_and_minimize() -> None:
    with test_dir("mmpose") as tmp:
        log_path = write(
            tmp / "pose.log",
            """
            06/02 20:02:00 - mmengine - INFO - Epoch(val) [3][10/10] coco/AP: 0.721 PCK: 0.887 AUC: 0.665 NME: 0.045
            """,
        )

        _, rows = parse_openmmlab_history(log_path, 0)
        assert rows[0][0] == "AP"
        assert round(rows[0][4]["AP"], 2) == 72.10
        assert round(rows[0][4]["PCK"], 2) == 88.70
        assert metric_should_minimize("NME")
        assert best_row(rows)[0] == "AP"


def test_mmagic_mmocr_mmtracking_metrics() -> None:
    with test_dir("mixed") as tmp:
        log_path = write(
            tmp / "mixed.log",
            """
            Epoch(val) [5][10/10] PSNR: 31.42 SSIM: 0.921 FID: 18.4
            Epoch(val) [6][10/10] hmean: 0.823 word_acc: 0.771 MOTA: 0.668 IDF1: 0.701
            """,
        )

        _, rows = parse_openmmlab_history(log_path, 0)
        assert rows[0][0] == "PSNR"
        assert round(rows[0][4]["SSIM"], 1) == 92.1
        assert metric_should_minimize("FID")
        assert rows[1][0] == "Hmean"
        assert rows[1][4]["Hmean"] == 82.3
        assert rows[1][4]["MOTA"] == 66.8
        assert rows[1][4]["IDF1"] == 70.1


def test_auto_watch_skips_unparseable_latest_file() -> None:
    with test_dir("auto-watch") as root:
        write(root / "train.log", "Epoch(val) [4][5/5] mIoU: 0.79")
        junk = write(root / "latest.json", '{"config": "not metrics"}')
        junk.touch()

        parsed = None
        for candidate in latest_files([str(root)]):
            total_epochs, _, latest, rows = parse_file(candidate, 0)
            if latest is not None:
                parsed = candidate, total_epochs, latest, rows
                break

        assert parsed is not None
        assert parsed[0].name == "train.log"
        assert parsed[2][4]["mIoU"] == 79.0


if __name__ == "__main__":
    test_mmdetection_text_log()
    test_mmengine_json_log()
    test_mmpose_metrics_and_minimize()
    test_mmagic_mmocr_mmtracking_metrics()
    test_auto_watch_skips_unparseable_latest_file()
    print("openmmlab parser tests ok")
