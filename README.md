# Training Monitor

Training Monitor 是一个轻量级深度学习训练监控工具。它由两部分组成：

- 服务器端监控服务：运行在训练服务器上，读取训练日志或接收训练脚本上报。
- 安卓 App：安装到手机上，实时查看训练进度、当前指标、最好指标、最好轮次和预计剩余时间。

当前版本适合这些场景：

- `mmsegmentation`：自动读取 `.log` 里的 `mIoU`。
- YOLO / Ultralytics：自动读取 `results.csv` 里的 `mAP`。
- 其他 PyTorch 项目：可以用通用 HTTP 接口或 Python helper 主动上报。
- 任意服务器：只要能运行 Python，并且手机能访问服务器的后端地址即可。

> 说明：不同深度学习框架没有统一日志格式，所以不可能百分百自动识别所有项目。这个工具的原则是：常见日志自动识别，特殊项目用统一接口接入。

## 1. 快速开始

在训练服务器上执行：

```bash
curl -fsSL https://raw.githubusercontent.com/ZephYer8/training-monitor/main/install.sh | bash
```

安装完成后查看连接信息：

```bash
training-monitor connection
```

你会看到类似输出：

```text
server port: 6006
backend url: http://xxx.xxx.xxx.xxx:6006
access token: xxxxxxxxxxxxxxxxxxxxxxxx
```

然后在手机上安装 APK：

[下载最新版 APK](https://github.com/ZephYer8/training-monitor/releases/latest)

打开 App 后填写：

- `Backend URL`：服务器输出的 `backend url`
- `Access Token`：服务器输出的 `access token`

点 `Save` 后，App 会每 2 秒自动刷新训练状态。

## 2. 服务器安装

### 2.1 基本要求

服务器需要有：

- Linux
- Python 3
- `curl` 或 `wget`
- 手机能访问到服务器开放出来的后端地址

安装脚本会创建独立虚拟环境，不会改你的训练环境。

### 2.2 默认安装位置

如果你是 `root` 用户，并且服务器存在 `/root/autodl-tmp`，默认安装到：

```text
/root/autodl-tmp/training-monitor
```

否则默认安装到：

```text
~/.training-monitor
```

### 2.3 自定义安装仓库

如果你 fork 了这个项目，可以这样安装自己的仓库：

```bash
TRAINING_MONITOR_REPO=https://github.com/你的用户名/training-monitor \
curl -fsSL https://raw.githubusercontent.com/ZephYer8/training-monitor/main/install.sh | bash
```

## 3. 服务器配置

第一次安装后建议执行：

```bash
training-monitor setup
```

它会依次询问几个配置：

```text
Port [6006]:
Public URL [自动检测到的地址]:
Log roots [/root/mmsegmentation* /root/autodl-tmp /root/workspace /root/runs /root]:
Log type auto/mmseg/yolo [auto]:
Auto watch 1/0 [1]:
```

一般情况下直接回车即可。

如果自动检测到的公网地址不对，可以手动设置：

```bash
training-monitor config set PUBLIC_URL http://你的公网地址:端口
training-monitor restart
```

如果你的训练日志不在默认目录，可以设置日志扫描目录：

```bash
training-monitor config set LOG_ROOTS "/root/project1 /root/project2/runs /root/autodl-tmp"
training-monitor restart
```

查看当前配置：

```bash
training-monitor config show
```

配置文件位置：

```bash
training-monitor config path
```

## 4. 常用命令

启动服务：

```bash
training-monitor start
```

停止服务：

```bash
training-monitor stop
```

重启服务：

```bash
training-monitor restart
```

查看当前训练状态：

```bash
training-monitor status
```

查看手机连接信息：

```bash
training-monitor connection
```

查看 token：

```bash
training-monitor token
```

查看后端和自动检测日志：

```bash
training-monitor logs
```

## 5. 安卓 App 使用

### 5.1 下载 APK

进入 Release 页面下载：

[https://github.com/ZephYer8/training-monitor/releases/latest](https://github.com/ZephYer8/training-monitor/releases/latest)

下载 `training-monitor.apk` 后安装到安卓手机。

如果手机提示不允许安装未知来源应用，需要在系统设置里允许当前浏览器或文件管理器安装应用。

### 5.2 填写连接信息

打开 App 后填写：

- `Backend URL`：例如 `http://region-xx.example.com:12345`
- `Access Token`：服务器 `training-monitor connection` 输出的 token

填写后点击 `Save`。

App 会显示：

- 当前训练状态
- 当前 epoch / 总 epoch
- 当前指标，例如 `Current mIoU` 或 `Current mAP`
- 最好指标
- 最好指标所在 epoch
- ETA 预计剩余时间

## 6. 自动检测训练

默认安装后会自动扫描训练日志。

默认扫描目录：

```text
/root/mmsegmentation*
/root/autodl-tmp
/root/workspace
/root/runs
/root
```

自动检测逻辑很简单：扫描这些目录里最新的支持文件，然后读取指标。

当前支持：

- `.log`：主要用于 `mmsegmentation`，读取 `mIoU`
- `results.csv`：主要用于 YOLO / Ultralytics，读取 `mAP`
- 文件名包含 `result` / `metric` / `progress` 的 `.csv`

CSV 至少需要包含：

- `epoch`
- 一个指标列，例如 `mIoU`、`IoU`、`mAP`、`accuracy`、`acc`、`top1`

如果指标值是 `0.82` 这种 0 到 1 的小数，会自动转成 `82.0` 显示。

## 7. mmsegmentation 接入

大多数情况下不需要改训练代码。

只要你的 mmsegmentation 日志在默认扫描目录下，例如：

```text
/root/mmsegmentation-1.2.1/work_dirs/xxx/20260531_xxxxxx.log
```

安装服务后会自动识别最新日志。

如果日志目录不在默认位置：

```bash
training-monitor config set LOG_ROOTS "/你的/mmsegmentation/work_dirs"
training-monitor restart
```

如果想手动指定某一个日志文件：

```bash
training-monitor watch-file /path/to/train.log 300
```

最后的 `300` 是总 epoch，可以按你的训练轮数修改。

## 8. YOLO / Ultralytics 接入

YOLO 通常会生成：

```text
runs/detect/train/results.csv
runs/segment/train/results.csv
```

只要 `runs` 目录在扫描范围内，就会自动识别。

如果你的 YOLO 项目在 `/root/yolo-project`：

```bash
training-monitor config set LOG_ROOTS "/root/yolo-project/runs"
training-monitor restart
```

也可以手动指定：

```bash
training-monitor watch-file /root/yolo-project/runs/detect/train/results.csv 100
```

## 9. 通用 PyTorch 项目接入

如果你的项目不是 mmsegmentation 或 YOLO，推荐主动上报指标。

### 9.1 用 HTTP 接口上报

在训练代码里加：

```python
import requests

SERVER_URL = "http://你的服务器后端地址"
TOKEN = "你的 access token"

def report(epoch, total_epochs, value, metric_name="IoU", eta_seconds=None):
    payload = {
        "run_id": "my-experiment-001",
        "epoch": epoch,
        "total_epochs": total_epochs,
        "iou": float(value),
        "metric_name": metric_name,
        "status": "training",
    }
    if eta_seconds is not None:
        payload["eta_seconds"] = int(eta_seconds)

    requests.post(
        f"{SERVER_URL}/api/status",
        headers={"X-Monitor-Token": TOKEN},
        json=payload,
        timeout=3,
    ).raise_for_status()
```

训练循环里调用：

```python
for epoch in range(1, total_epochs + 1):
    train_one_epoch()
    metric = validate()

    report(
        epoch=epoch,
        total_epochs=total_epochs,
        value=metric,
        metric_name="IoU",
    )
```

最后一轮可以把状态改成 `finished`：

```python
payload["status"] = "finished"
```

### 9.2 用项目自带 helper 上报

如果训练代码和监控服务在同一台服务器上，也可以直接使用项目里的 `TrainingMonitor`：

```python
import sys

sys.path.append("/root/autodl-tmp/training-monitor/server")

from training_monitor import TrainingMonitor

monitor = TrainingMonitor(
    "http://127.0.0.1:6006",
    token="你的 access token",
)

monitor.log(
    run_id="my-experiment-001",
    epoch=1,
    total_epochs=300,
    iou=76.5,
    metric_name="mIoU",
)
```

这里的参数含义：

- `run_id`：训练任务 ID。新任务建议换一个新的 ID。
- `epoch`：当前轮数。
- `total_epochs`：总轮数。
- `iou`：指标值。字段名为了兼容旧版本仍叫 `iou`，实际可以传 `mIoU`、`mAP`、`accuracy` 等指标。
- `metric_name`：App 上显示的指标名称。
- `eta_seconds`：预计剩余秒数，可选。
- `status`：`training`、`finished` 或 `error`。

## 10. 新训练会不会自动切换

会。

后端会根据 `run_id` 判断是不是新的训练任务。自动检测模式下，`run_id` 默认就是日志文件路径。

当检测到新的日志文件或新的 `results.csv` 后，会自动切换到新的训练任务，并重新统计最好指标。

如果自动检测选错了日志，通常是因为旧日志文件被重新写入，导致修改时间变成最新。解决办法是手动指定：

```bash
training-monitor watch-file /path/to/正确的日志文件 300
```

或者缩小扫描目录：

```bash
training-monitor config set LOG_ROOTS "/当前项目的训练输出目录"
training-monitor restart
```

## 11. 手机无法连接怎么办

先在服务器上确认服务是否正常：

```bash
training-monitor status
training-monitor connection
```

再确认手机填写的 `Backend URL` 是公网可访问地址，不一定是 `127.0.0.1` 或服务器内网地址。

常见情况：

- AutoDL / SeetaCloud 等平台一般会给你一个映射后的公网 URL。
- 云服务器需要安全组放行端口。
- 校园网或公司内网可能不能直接从手机流量访问。
- 如果 App 报 `HTTP 401`，说明 token 填错了。
- 如果 App 报连接失败，通常是 URL、端口或公网映射问题。

## 12. 更新版本

在服务器上重新执行安装命令即可：

```bash
curl -fsSL https://raw.githubusercontent.com/ZephYer8/training-monitor/main/install.sh | bash
```

然后重启：

```bash
training-monitor restart
```

安卓 App 下载最新 Release 里的 APK 覆盖安装即可。

## 13. 卸载

先停止服务：

```bash
training-monitor stop
```

删除安装目录。

如果是默认 root + AutoDL 安装：

```bash
rm -rf /root/autodl-tmp/training-monitor
```

如果是普通用户安装：

```bash
rm -rf ~/.training-monitor
```

如果创建了命令软链接，也可以删除：

```bash
rm -f ~/.local/bin/training-monitor
```

## 14. 推荐使用方式

最省心的方式：

1. 在每台训练服务器上安装一次 Training Monitor。
2. 执行 `training-monitor setup` 配置公网地址和日志目录。
3. 手机 App 里保存这台服务器的 URL 和 token。
4. mmsegmentation / YOLO 直接靠自动检测。
5. 其他 PyTorch 项目用 HTTP 接口主动上报。

这样换服务器时，只需要重新安装并填写新的 URL 和 token，App 不需要重新开发。
