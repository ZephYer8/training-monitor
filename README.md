# Training Monitor

作者：Zephyer

Training Monitor 是一个轻量级深度学习训练监控工具。它由两部分组成：

- 服务器端监控服务：运行在训练服务器上，读取训练日志或接收训练脚本上报。
- 安卓 App：安装到手机上，实时查看训练进度、当前指标、最好指标、最好轮次和预计剩余时间。

当前版本适合这些场景：

- OpenMMLab / MMEngine：自动读取 `.log`、`.json`、`.jsonl` 里的 `loss`、`mIoU`、`BBox mAP`、`NDS`、`PQ`、`Top1 Acc`、`PCK` 等常见指标。
- YOLO / Ultralytics：自动读取 `results.csv` 里的 `mAP`。
- 其他 PyTorch 项目：可以用通用 HTTP 接口或 Python helper 主动上报。
- 任意服务器：只要能运行 Python，并且手机能访问服务器的后端地址即可。

> 说明：不同深度学习框架没有完全统一的日志格式，所以不承诺百分百自动识别所有项目。这个工具的原则是：OpenMMLab / YOLO 常见日志自动识别，特殊项目用统一接口接入。

新版 App 会缓存最后一次成功同步的数据。服务器关机、训练完成后断网、临时网络不稳定时，手机端仍然能看到最后一次同步到本地的训练进度和曲线。

安全默认值：服务端接口必须使用 token；App 会加密保存 token；安装脚本默认只从 GitHub 官方 Release 下载服务端安装包，镜像下载需要你手动开启。

## 1. 快速开始

推荐先用 pip 安装服务器端。学校服务器或国内机房如果直连 GitHub 很慢，优先用镜像 wheel：

```bash
python3 -m pip install --user --upgrade "https://gh-proxy.com/https://github.com/ZephYer8/training-monitor/releases/download/v0.7.25/xunji_training_monitor-0.7.25-py3-none-any.whl"
python3 -m monitorctl_py start
python3 -m monitorctl_py connection
```

如果镜像不可用，可以换一个镜像前缀：

```bash
python3 -m pip install --user --upgrade "https://gh.llkk.cc/https://github.com/ZephYer8/training-monitor/releases/download/v0.7.25/xunji_training_monitor-0.7.25-py3-none-any.whl"
```

如果你的服务器能直接访问 GitHub，也可以安装源码包：

```bash
python3 -m pip install --user --upgrade https://github.com/ZephYer8/training-monitor/releases/latest/download/training-monitor-server.tar.gz
```

注意：`pip -i 清华源` 只加速 PyPI 依赖，不会加速 `https://github.com/...` 这种文件下载。

如果你的服务器已经把 `~/.local/bin` 加入 `PATH`，可以直接运行：

```bash
training-monitor start
training-monitor connection
```

如果出现 `training-monitor: command not found`，说明 pip 已经安装成功，但当前 shell 没加载 `~/.local/bin`。直接运行：

```bash
python3 -m monitorctl_py fix-path
export PATH="$HOME/.local/bin:$PATH"
```

如果服务器没有配置 pip，或者你想直接一键安装，在训练服务器上执行：

```bash
curl -fL https://github.com/ZephYer8/training-monitor/releases/latest/download/training-monitor-install-server.sh | bash
```

这个命令不依赖 `raw.githubusercontent.com`。如果服务器访问 GitHub Release 很慢，可以手动指定服务端安装包地址。注意：镜像下载更快，但需要你信任这个镜像源。

```bash
curl -fL "https://gh-proxy.com/https://github.com/ZephYer8/training-monitor/releases/latest/download/training-monitor-install-server.sh" \
  | TRAINING_MONITOR_GITHUB_PROXY="https://gh-proxy.com" bash
```

安装完成后查看连接信息：

```bash
python3 -m monitorctl_py connection
```

如果需要诊断安装状态：

```bash
python3 -m monitorctl_py doctor
```

root 用户使用一键脚本时，默认会同时创建：

```text
/usr/local/bin/training-monitor
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

不要把 `access token` 发给别人。如果怀疑泄露，在服务器上执行：

```bash
training-monitor rotate-token
```

然后把 App 里的 `Access Token` 改成新的。

## 2. 服务器安装

### 2.1 基本要求

服务器需要有：

- Linux
- Python 3
- `curl` 或 `wget`
- 手机能访问到服务器开放出来的后端地址

推荐使用 pip 安装。安装脚本会优先创建独立虚拟环境；如果服务器缺少 `python3-venv`，会自动改用本地 Python 包目录，尽量避免卡在 `ensurepip is not available`。

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
TRAINING_MONITOR_REPO_SLUG="你的用户名/training-monitor" \
curl -fL https://github.com/你的用户名/training-monitor/releases/latest/download/training-monitor-install-server.sh | bash
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
Log roots [/root/mmdetection* /root/mmdetection3d* /root/mmdet3d* /root/mmsegmentation* /root/mmclassification* /root/mmpretrain* /root/mmselfsup* /root/mmyolo* /root/mmpose* /root/mmrotate* /root/mmocr* /root/mmaction* /root/mmaction2* /root/mmagic* /root/mmediting* /root/mmgeneration* /root/mmtracking* /root/mmtrack* /root/mmrazor* /root/mmhuman3d* /root/mmfewshot* /root/mmdeploy* /root/work_dirs /root/*/work_dirs /root/autodl-tmp/*/work_dirs /root/workspace/*/work_dirs /root/autodl-tmp /root/workspace /root/runs]:
Log type auto/openmmlab/yolo [auto]:
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

下面都写成 `training-monitor ...`。如果服务器提示 `training-monitor: command not found`，把前缀换成 `python3 -m monitorctl_py ...`，或者先执行 `python3 -m monitorctl_py fix-path`。

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

重新生成 token：

```bash
training-monitor rotate-token
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

如果你要在本地重新构建 APK，需要先安装 JDK 17 和 Android SDK，然后执行：

```bash
cd android
./gradlew :app:assembleDebug
```

Windows 可以执行：

```powershell
cd android
.\gradlew.bat :app:assembleDebug
```

### 5.2 填写连接信息

打开 App 后填写：

- `Backend URL`：例如 `http://region-xx.example.com:12345`
- `Access Token`：服务器 `training-monitor connection` 输出的 token

填写后点击 `Save`。

App 会显示：

- 当前训练状态
- 当前 epoch / 总 epoch
- `Best mIoU` / `Best mAP` / `Best Accuracy`
- `Best Epoch`
- `Current` 当前指标
- ETA 预计剩余时间
- 指标曲线，带横坐标 epoch 和纵坐标数值
- 通知栏和锁屏训练状态，可显示 epoch、Best 指标和 ETA
- 训练完成提醒
- 训练控制台仪表盘：训练中、完成、异常、等待会显示不同状态

指标名称在 App 中优先使用英文，例如 `Loss`、`Decode Loss`、`mIoU`、`mDice`、`mAP`、`Accuracy`，这样更接近训练面板和论文实验记录的习惯。

### 5.3 通知栏、锁屏和手表提醒

在 App 的 `设置` 页面开启 `通知栏训练状态`。

开启后：

- 手机通知栏会常驻显示训练状态。
- 锁屏界面可以看到当前 epoch、Best 指标和 ETA。
- 训练完成时会发送一次完成提醒。
- 通知里最多显示 1-2 个指标，来源是 `显示指标` 中勾选的前两个。

智能手表目前先通过“同步手机通知”的方式联动：在手机或手表管理 App 里允许“训迹”的通知同步到手表即可。这样不需要单独安装手表 App，也最稳定。

如果后续要做原生手表 App 或表盘组件，需要按具体手表平台单独开发并接入对应开发者工具链。

## 6. 离线缓存和历史数据

App 每次成功请求 `/api/status` 后，都会把完整状态缓存在手机本地。

这意味着：

- 服务器临时断网时，App 不会变成空白。
- 服务器关机后，App 仍能显示最后一次同步到手机的数据。
- 已经同步过的 loss / mIoU / mAP 曲线可以继续查看。

限制也很直接：如果训练完成后手机从来没有同步到最终状态，服务器又已经关机，App 不可能凭空拿到服务器上未同步的数据。所以训练时建议保持 App 至少成功连接过一次。

服务器端也会把状态保存到安装目录：

```text
/root/autodl-tmp/training-monitor/state.json
```

如果后端服务暂时没启动，仍可尝试：

```bash
training-monitor status
```

新版命令会在服务不可用时读取本地缓存状态。

## 7. 自动检测训练

默认安装后会自动扫描训练日志。

默认扫描目录：

```text
/root/mmdetection*
/root/mmdetection3d*
/root/mmdet3d*
/root/mmsegmentation*
/root/mmclassification*
/root/mmpretrain*
/root/mmselfsup*
/root/mmyolo*
/root/mmpose*
/root/mmrotate*
/root/mmocr*
/root/mmaction*
/root/mmaction2*
/root/mmagic*
/root/mmediting*
/root/mmgeneration*
/root/mmtracking*
/root/mmtrack*
/root/mmrazor*
/root/mmhuman3d*
/root/mmfewshot*
/root/mmdeploy*
/root/work_dirs
/root/*/work_dirs
/root/autodl-tmp/*/work_dirs
/root/workspace/*/work_dirs
/root/autodl-tmp
/root/workspace
/root/runs
```

自动检测逻辑很简单：扫描这些目录里最新的支持文件，然后读取指标。

当前支持：

- OpenMMLab / MMEngine `.log`、`.json`、`.jsonl`：读取 `loss`、`mIoU`、`mDice`、`BBox mAP`、`Segm mAP`、`NDS`、`PQ`、`Top1 Acc`、`PCK`、`Hmean`、`MOTA`、`PSNR` 等常见指标
- `results.csv`：主要用于 YOLO / Ultralytics，读取 `mAP`
- 文件名包含 `result` / `metric` / `progress` 的 `.csv`

CSV 至少需要包含：

- `epoch`
- 一个指标列，例如 `mIoU`、`IoU`、`mAP`、`accuracy`、`acc`、`top1`

如果指标值是 `0.82` 这种 0 到 1 的小数，会自动转成 `82.0` 显示。

## 8. OpenMMLab 接入

大多数情况下不需要改训练代码。

只要你的 OpenMMLab 项目日志在默认扫描目录下，例如：

```text
/root/mmdetection/work_dirs/xxx/20260602_xxxxxx.log
/root/mmsegmentation-1.2.1/work_dirs/xxx/20260531_xxxxxx.log
/root/mmpose/work_dirs/xxx/vis_data/20260602_xxxxxx.json
```

安装服务后会自动识别最新日志。

如果日志目录不在默认位置：

```bash
training-monitor config set LOG_ROOTS "/你的/OpenMMLab/work_dirs"
training-monitor restart
```

如果想手动指定某一个日志文件：

```bash
training-monitor watch-file /path/to/train.log 300
```

最后的 `300` 是总 epoch，可以按你的训练轮数修改。

## 9. YOLO / Ultralytics 接入

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

## 10. 通用 PyTorch 项目接入

如果你的项目不是 OpenMMLab 或 YOLO，推荐主动上报指标。

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

## 11. 新训练会不会自动切换

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

## 12. 手机无法连接怎么办

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

## 13. 安全建议

默认情况下，Training Monitor 只适合保存训练进度、loss、mIoU、mAP 这类实验指标，不要把数据集路径里的隐私信息或账号密码写进训练日志。

推荐做法：

- `access token` 只填在自己的手机 App 里，不要发到聊天、群、笔记或公开仓库。
- 如果 token 泄露，执行 `training-monitor rotate-token` 重新生成。
- 如果平台提供 HTTPS 公网地址，App 里优先填写 `https://...`。
- GitHub 下载慢时可以手动使用镜像，但镜像源不是默认开启的。
- 如果自动扫描范围太大，用 `training-monitor config set LOG_ROOTS "/你的训练输出目录"` 缩小范围。

当前安全边界：

- `/api/status` 和 `/api/reset` 必须带正确 `X-Monitor-Token`。
- token、配置文件、状态文件默认按当前用户私有权限保存。
- App 使用 Android Keystore 加密保存 token，并关闭系统备份。
- App 设置页提供“隐私与权限”说明，并支持清除训练缓存、清除本机 Token。
- 默认不启用浏览器跨域访问；如果你要做 Web 前端，再配置 `CORS_ORIGINS`。

## 14. 更新版本

在服务器上重新执行安装命令即可：

```bash
curl -fL https://github.com/ZephYer8/training-monitor/releases/latest/download/training-monitor-install-server.sh | bash
```

然后重启：

```bash
training-monitor restart
```

安卓 App 下载最新 Release 里的 APK 覆盖安装即可。

## 15. 卸载

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

## 16. 推荐使用方式

最省心的方式：

1. 在每台训练服务器上安装一次 Training Monitor。
2. 执行 `training-monitor setup` 配置公网地址和日志目录。
3. 手机 App 里保存这台服务器的 URL 和 token。
4. OpenMMLab / YOLO 直接靠自动检测。
5. 其他 PyTorch 项目用 HTTP 接口主动上报。

这样换服务器时，只需要重新安装并填写新的 URL 和 token，App 不需要重新开发。

## 17. 上架前清单

当前代码已经具备测试 APK、服务端安装脚本、Token 鉴权、App 本地加密保存 Token、首次使用隐私提示、通知权限说明、离线缓存和 OpenMMLab 常见日志自动识别能力。正式提交应用市场前，还需要补齐这些外部材料：

- 正式签名：在 GitHub Secrets 配置 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`，重新打包后得到正式 release APK。未配置这些密钥时，GitHub Release 会生成测试 APK，并同时上传 `training-monitor-build-info.txt` 标明 `market_ready=false`，不要用于应用市场提交。
- 隐私政策 URL：应用市场通常要求填写可公开访问的隐私政策链接，且 App 内应能方便访问隐私与权限说明。
- App 备案：如果公开向中国大陆用户分发，按应用市场和接入服务商要求完成 APP 备案或相关主体信息提交。
- 主体资料：准备开发者姓名或主体名称、联系方式、应用名称“训迹”、作者“Zephyer”、包名 `com.modeltest.monitor`。
- 权限说明：首次使用时 App 会展示隐私与权限提示；说明只使用网络、通知、前台服务权限；不读取通讯录、定位、相册、麦克风、摄像头。
- 数据说明：说明本机保存服务器地址、加密 Token、刷新间隔、勾选指标和最后一次训练状态缓存。
- 用户权利入口：App 设置页已提供清除训练缓存、清除本机 Token；如果后续增加账号体系，再补注销账号入口。
- 真机测试：至少在一台 Android 13+ 手机和一台 Android 12 或以下手机上测试安装、通知权限、锁屏通知、服务器连接、断网缓存。

参考依据：

- [《中华人民共和国个人信息保护法》](https://www.miit.gov.cn/zwgk/zcwj/flfg/art/2022/art_04a0f1fb5df244e39688fd5372623a8d.html)
- [《App违法违规收集使用个人信息行为认定方法》](https://wap.miit.gov.cn/jgsj/waj/wjfb/art/2020/art_8663d2afe61b40c3beb7c65bf6ec2a64.html)
- [《工业和信息化部关于开展移动互联网应用程序备案工作的通知》解读](https://www.miit.gov.cn/jgsj/xgj/hlwgl/art/2023/art_564bf0759d7e41d5b4aa8ce4996b9e84.html)

## 18. 隐私政策要点模板

正式上架前，把下面内容整理成一个可公开访问的网页，并把网页链接填写到应用市场后台。

- 应用名称：训迹
- 作者：Zephyer
- 包名：`com.modeltest.monitor`
- 功能用途：连接用户自行配置的训练监控后端，展示模型训练进度、指标曲线、最佳指标、预计剩余时间和训练完成提醒。
- 收集的信息：服务器地址、访问 Token、刷新间隔、用户勾选的显示指标、最后一次训练状态缓存。
- 使用目的：用于连接训练监控后端、刷新训练状态、展示指标曲线、发送通知栏/锁屏训练状态提醒。
- 权限使用：网络权限用于访问训练监控后端；通知权限和前台服务用于训练状态常驻通知和训练完成提醒。
- 不收集的信息：不读取通讯录、定位、相册、麦克风、摄像头，不采集身份证号、银行卡号、精确位置等敏感个人信息。
- 存储方式：Token 通过 Android Keystore 加密保存；训练状态缓存保存在本机；服务端 token、配置和状态文件默认按当前用户私有权限保存。
- 共享与第三方：当前 App 不接入广告 SDK，不向第三方共享个人信息。
- 删除方式：用户可在 App 设置页清除训练缓存、清除本机 Token；服务端可执行 `training-monitor rotate-token` 重新生成 token。
- 联系方式：上架前填写你的有效邮箱或其他联系方式。
