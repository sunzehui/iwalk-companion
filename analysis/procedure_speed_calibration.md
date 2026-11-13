# U03：速度字段标定执行手册

准备于 2026-10-21，改写于 2026-11-12（阶段三）。**这是待执行的真实实验方案，
不是已完成的结论**——到今天为止，本车速度字段仍然未知，代码里也没有速度映射。

目标：在同一台手机、同一段时间内取得「官方 App 界面上的速度读数」与「原始通知报文」，
据此判断主状态帧里**哪一个偏移**是速度、倍率是多少。判定由
`analysis/tools/speed_calibration.py` 机械给出，人只负责读数，不负责猜。

现状（写这份手册时的真实边界）：

- 官方 App `com.loby.balance.minirobot` 5.4.1 只轮询 2 类查询（E57），0x14 应答 38 B 载荷；
- 静止状态下 0x14 里**只有 off16、off18、off22、off26、off27、off36、off37 会变**
  （2026-11-12 真车导出 1405 帧逐字节统计，其余偏移整段恒定）。其中 off18 是电压低字节
  （`0xc818` 候选，E60）、off22 是两态标志（E48）、off16 是小计数值（0–9）；
  真正「高频大幅跳」的只有 off26/27 与 off36/37；
- off26/27 曾在约 1 s 内从 0 跳到 3084 再回 2.5，按 ×0.01 当速度即 30.84→2.5 km/h，
  不符合车辆物理（E64）→ 已排除「平滑速度」解释，但**没有排除「非速度」之外的其它可能**；
- 因此「哪个偏移是速度」目前无解，只能靠一次带界面读数的移动采集。反过来说，如果骑行时
  仍然只有上面这 7 个字节在变，那速度只可能在其中某个跨度里，不可能在别处；

## 1. 采集前

- 车辆停稳，拍摄铭牌（同时回填 U04：车型）。
- 记录官方 App 实际包名与版本，不照抄旧模板（E57 用的是 `com.loby.balance.minirobot` 5.4.1 (47)）。
- 只让官方 App 连接车辆；关闭本仓库的伴侣 App、nRF Connect 等其它 BLE 客户端。
  两个 App 同时连会抢连接，日志会混进别人的写查询。
- 手机开发者选项开启「启用蓝牙 HCI 信息收集日志」，并**在停稳状态下重新连接一次**。
  开关打开不等于记录成功，最终以导出的文件为准。
- 同一台手机录屏，画面必须包含：官方主界面、速度数字与单位、电量、连接状态。
- 记录连接/断开在视频中的位置，供与 HCI 事件交叉对齐。
  **视频第 0 秒不等于 HCI 第 0 秒**；对齐误差要写进 CSV，不能默认 0。
- 与电脑相连时，采集开始/结束各存一次 `adb shell date '+%Y-%m-%dT%H:%M:%S%z'`
  作为手机时钟记录（它不能替代视频锚点）。

## 2. 受控观察序列

由旁人负责手机和记录；操作者不边骑行边看手机。
只在熟悉车辆、场地安全且具备必要防护时做低速观察；否则只做静止检查。
不要架空驱动轮高速空转、绕过保护、写锁车/校准/OTA 指令或追求高速度。

一次会话内做完下面四段，总时长控制在 **2–3 分钟**：

| 段 | phase 取值 | 建议时长 | 要记录的内容 |
|---|---|---|---|
| 静止基线 | `stationary` | 20–30 s | 界面速度（应为 0.0）、电量、连接状态 |
| 稳定低速 | `moving` | 20–30 s | 保持一个**大致恒定**的速度，读数取稳态 |
| 再次停止 | `stopped` | 20–30 s | 读数回落 0.0 的过程 |
| 独立重复 | `repeat` | 20–30 s | 换一个不同速度再走一遍，用于**检验**而非挑选 |

硬性要求（工具会照此拒绝）：

1. **至少 3 个可区分的非零读数**（例如 6.0 / 10.5 / 13.0 km/h）。只有 0.0 或只有一个
   非零值，工具直接退出，不允许定标。
2. 非零读数尽量取**稳态**：加速过程中读数会被 ±1 s 的对齐误差放大成几 km/h 的残差。
3. `repeat` 段必须存在且与训练段**速度不同**，否则只能得到「暂定」结论，不出标定文件。
4. 推行时如果界面速度始终为 0.0，**记录这个事实并停止该路线**——不能因为轮子在转
   就把某个变化字节认成速度。

复制 `captures/TEMPLATE_speed_observations.csv` 填写：

- `video_time_s`：视频相对秒数（回放视频读时间码）。
- `phone_wall_time_iso8601`：知道绝对时间就填（可留空，改用下面的 `--video-anchor`）。
- `phase`：只填 `stationary` / `moving` / `stopped` / `repeat` 四者之一。
- `official_speed_kmh`：只填画面实际显示值；看不清就留空，**不能填 0 代替未知**。
- `alignment_uncertainty_ms`：你估计的对齐误差。走 §3 的路线 B（两个锚点各 ±1 s）时写 `2000`；
  有人实时对着秒表记录时写 `200`；不知道就不要填 0——填 0 等于声称帧与读数同刻，那是假的。

## 3. 保存与导出：两条路，只有一条现在能走

为每次采集建独立目录，不覆盖历史会话：

```text
captures/YYYYMMDD_speed_01/
  official_screen.mp4
  btsnoop_hci.log
  session.md
  speed_observations.csv
  vehicle_label.jpg
```

**先说清一件事：读数只能来自官方 App，因为只有它显示速度。** E53 那次报文采集里官方 App 与
我们的 App 是**先后两段会话**，从没同时连过——车辆是否允许两个中心同时连接，至今没验证，
所以不能把「两个 App 一起连」当方案：

| 路线 | 谁连着车 | 速度读数 | 帧从哪来 | 能不能用 |
|---|---|---|---|---|
| **B（现在的主路）** | 官方 App | 官方界面 + 录屏 | 手机 HCI 日志（btsnoop） | ✅ 可执行 |
| A（若车辆支持同时两连） | 官方 App + 伴侣 App | 官方界面 + 录屏 | 伴侣 App 导出 `raw_*.jsonl` | ❓ 未验证多连接，别当默认 |

**首选：`speed_sampler.sh`（E70，2026-11-12 新增）**。官方 App 是 Cocos2d 渲染
（uiautomator 只看得到 SurfaceView，抓不到速度文本——真机已验证），所以读数靠
录屏 + OCR：手机端自动分段录屏（每段 ≤170 s，最多 6 段）、设备端墙钟标记，
回电脑后自动 OCR 出 CSV。**骑行全程不需要电脑陪跑**（建议 `adb tcpip 5555` 后
拔线用 Wi-Fi；USB 拔掉录屏会被杀）：

```sh
adb tcpip 5555 && adb connect <手机IP>:5555   # 必须在 start 之前：切换/断开 adb 会杀录屏循环
ADB_TARGET="-s <手机IP>:5555" analysis/tools/speed_sampler.sh start    # 官方 App 连上车之后再执行
analysis/tools/speed_sampler.sh stop     # 骑完收尾（SIGINT 让当前段正常落盘）
analysis/tools/speed_sampler.sh pull 20261113_speed_01
# → captures/20261113_speed_01/speed_observations.csv（墙钟直接来自设备端标记）
```

对齐不确定性因此从 ±2000 ms 收窄到 ±1000 ms；CSV 的 `phase` 列由读数自动标
（<0.5 视为静止），第二段骑行的 `repeat` 用 `--repeat-from <墙钟>` 标注。
OCR 只认主界面那块青色大数字，pull 时若整段解析不出会退出 2 并提示先 `--probe`
——那是「这段根本没连上车/不在主界面」的正确反应，不是工具坏了。

手工录屏仍可用（`adb shell screenrecord` 或系统录屏），但要自己取
creation_time 锚点、自己填 CSV； OCR 也可单独跑：

```sh
python3 analysis/tools/readings_from_video.py --video <mp4> --markers <markers.txt> \
  --csv speed_observations.csv --probe /tmp/probe   # 先看 6 帧裁剪/掩码再信 CSV
```

btsnoop 的提取仍走老脚本（同一个目录，两份产物互相对得上）：

```sh
analysis/tools/collect_android_hci.sh pull 20261113_speed_01   # → btsnoop_hci.log
```

路线 B 的日志提取：

```sh
# 工作区根目录执行；先 pull 日志，再断开/关日志
adb shell date '+%Y-%m-%dT%H:%M:%S%z'      # ← 这个时刻就是下面的 --hci-t1，务必抄下来
analysis/tools/collect_android_hci.sh pull YYYYMMDD_speed_01
```

`collect_android_hci.sh` 打印成功不等于解出了目标车辆的帧（它跑的是旧流程），
只当搬运工；初查可用：

```sh
PYTHONPATH="$PWD/analysis/protocol" PYTHONDONTWRITEBYTECODE=1 \
  python3 analysis/tools/hci_quicklook.py captures/YYYYMMDD_speed_01/btsnoop_hci.log \
  > captures/YYYYMMDD_speed_01/quicklook.txt
```

若真的走了路线 A（伴侣 App 导出），**优先用 `raw_*.jsonl`**：证据包的
`evidence_timeline` 有 2000 条上限，长会话会丢最早条目，raw 文件不截断。

## 4. 对齐：两个锚点，缺一个就判不了

绝对不允许假设「视频第 0 秒 = 日志第 0 秒」。需要两个独立锚点：

**锚点 1 — 视频起点**（视频第 0 秒的手机墙钟）：

```sh
ffprobe -v error -show_entries format_tags=creation_time \
  -of default=noprint_wrappers=1:nk=1 captures/YYYYMMDD_speed_01/official_screen.mp4
# → 2026-11-20T07:02:11.000000Z   （UTC，注意换算时区）
```

只精确到秒，对齐误差按 ±1 s 记。

**锚点 2 — HCI 日志终点**：`adb shell date` 在 pull 之前的那一刻（见 §3），
把日志末条记录对到墙钟上。注意读数 CSV（sampler 路线）与 `--hci-t1` 都用
**设备钟**，两者同源，互相咬合不需要换算。

为什么不能直接用 btsnoop 里的时间戳：**这批真机日志的绝对时间是坏的**。
`20261021_official`、`20261021`、`20261112_realvehicle`、`20261112_locktest` 四份日志
逐份核对，时刻都对得上、日期却整整差 **378 天**（例如锁车日志首条记录算出来是
2027-11-25 11:54 UTC，真实是 2026-11-12 11:54 UTC）。同一个偏移在四份文件、两个不同
日期上都成立，说明是写日志那一侧的纪元问题，不是偶发。工具因此**只用相邻记录的相对
间隔**，把绝对时刻交给 `--hci-t0/--hci-t1` 这两个锚点，并在报告里写明用的是哪个。

两个锚点各带 ±1 s，合起来按 ±2 s 记：CSV 里 `alignment_uncertainty_ms` 写 `2000`，
工具会把采样窗放宽到 ±2 s。**这就是为什么 §2 要求读数取稳态**——2 s 的对齐误差在
加速过程中会被放大成好几 km/h，在稳态段则几乎无影响。

## 5. 分析：由工具判定，不由人挑

路线 B（官方 App + HCI，当前唯一可执行的）。sampler 路线的读数 CSV 已带墙钟，
**不需要 --video-anchor**：

```sh
PYTHONDONTWRITEBYTECODE=1 python3 analysis/tools/speed_calibration.py \
  --btsnoop captures/YYYYMMDD_speed_01/btsnoop_hci.log \
  --observations captures/YYYYMMDD_speed_01/speed_observations.csv \
  --hci-t1 2026-11-20T15:04:40+08:00 \
  --hci-t1 2026-11-20T15:04:40+08:00 \
  --report captures/YYYYMMDD_speed_01/calibration_report.md \
  --json captures/YYYYMMDD_speed_01/calibration_stats.json \
  --base-calibration downloads/iwk1779887-calibration-partial-v1.json \
  --emit downloads/iwk1779887-speed-v1.json
```

路线 A（伴侣 App 导出 raw）把 `--btsnoop … --hci-t1 …` 换成
`--frames captures/…/raw_ble-*.jsonl` 即可，那一路的时间戳是手机自己写的，不需要锚点 2。

工具做的事（对应上面的通过条件）：

1. 解包：btsnoop 取 ATT 通知，raw jsonl 取每行 `hex`；两者都按 20 B 分片重组。
   线上整帧 XOR 0x55 的判定**不用首包猜**，而是「明文 / 异或」两种假设各解一遍、
   取解出合法帧更多的一侧——官方日志的首包落在帧中间，按首包判定会得到 0 帧。
2. 对齐：每行读数取其手机时刻 ±窗口内的帧、窗内取中位数；窗口默认 ±400 ms，
   报了对齐误差就放宽到该值（>3000 ms 直接拒绝）。
3. 枚举候选：每个载荷长度稳定的帧组 × 每个偏移 × `{u8,i8,u16le,i16le}`。
   只枚举，不预设 off26/27、off36/37 是任何东西。
4. 硬性过滤：静止段预测值必须 ≤1.0 km/h；非零读数在变而字节不变 → 该偏移淘汰；
   斜率必须为正。
5. 拟合与检验：非 `repeat` 行拟合 `v = a·raw + b`，`repeat` 行**只做检验、不参与挑选**；
   训练段 R² ≥0.90、重复段 R² ≥0.85、最大残差 ≤ max(2.0, 0.2·最大速度)。
6. 倍率圆整：固件倍率应当是 ×0.1、×0.01 这类圆整值。只能靠 1/256 这类非整倍率
   拟合上的候选（典型是「把相邻常量字节一起读进来」的跨字节伪解）会被淘汰。
7. 唯一性：同一跨度的有/无符号解释合并；只有**两个不同偏移**同样好地解释读数时，
   判为「无法区分」，继续未知。

退出码：`0` 唯一候选通过；`1` 无候选通过或存在歧义；`2` 输入/对齐不可用。
报告（`calibration_report.md`）里只有排名，没有字段名——排名是拟合优度，不是语义。

自检（不需要真车，跑的是合成数据）：

```sh
PYTHONDONTWRITEBYTECODE=1 python3 analysis/tools/test_speed_calibration.py
PYTHONDONTWRITEBYTECODE=1 python3 analysis/tools/test_phase3_flow.py   # 与锁闸门判定器串练
```

**采集时留一段静止收尾**：标定用的四段里最后一段如果还在动，紧接着的锁车实验就没有
「停稳」可比对；把 `repeat` 段之后再加 10 s 静止，同一次采集就能直接接 B/C。

## 6. 通过之后才落标定

只有工具退出码为 0 时，`--emit` 才会写出文件。它把 `speed` 映射追加到
`--base-calibration` 的同一个 profile 里（电量映射保留），并在 `evidence` 字段里写明
R²、残差、读数条数——下次有人要问「这个速度是凭什么定的」，答案就在文件里。

落地后必须复核一次：

1. 把 JSON 导入伴侣 App（设置 → 标定 → 导入），确认设置页显示 `1 组 · N 项映射`；
2. 车辆静止时设备页速度应为 `0.0`，不是 `—`，也不是任何非零值；
3. 推车/骑行一小段，界面速度应与官方 App 同量级（不要求逐帧相等，官方显示有刷新延迟）；
4. 跑一遍 JVM 测试与 `analysis/tools/test_speed_calibration.py`，确认没有回归。

标定落盘之后，锁闸门的 B/C 两态才有东西可判——接着走
`app_kotlin/docs/lock-gate-real-vehicle-plan.md`，HCI 侧由
`analysis/tools/lock_gate_check.py --calibration downloads/iwk1779887-speed-v1.json` 给出结论
（同一份 btsnoop 里既取出站写、又算速度序列，逐条核对「写这一帧时车在不在动」）。

**任何一步对不上就回退到 battery-only**：宁可继续显示「未标定」，也不要留一个
看起来像速度、实际是电流的数字。

## 7. 失败时（同样算交付）

工具判定不通过时，要记录的是**排除了什么**，这在项目里等价于结论：

- 只有 0.0 读数 → 说明该工况下官方界面不给非零速度，需要换场地/换推行方式；
- 有非零读数但所有候选都不通过 → 说明速度可能不在 0x14，而在 0x1a/0x2a 等其它应答组
  （诊断轮询默认关闭，需要临时开启才有这些帧，且会压低 0x14 回包率）；
- 两个偏移无法区分 → 需要一次**速度变化幅度更大**的采集来拉开差异，而不是二选一。
