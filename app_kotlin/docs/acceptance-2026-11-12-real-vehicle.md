# 真车验收记录 · 2026-11-12

工单 03 / 04 的真车部分。前一轮（`docs/acceptance-2026-11-11.md`）只做了演示会话与
本机走查，真车链路当时无车可测。本轮滑板车上电并处于广播状态，在 vivo V2241A 上
完成扫描命中、GATT 连接、官方节奏轮询、标定读数、导出快照、断开与重连，并用手机侧
HCI 日志（btsnoop）独立核对了「应用到底往车上写了什么」。

环境：Kotlin 0.2.0（versionCode 2，arm64 release 包，debug keystore）；手机
vivo V2241A `10AD1Z0Q1Y001R9`，Android 16 / SDK 36，1080×2400；车辆
`MiniRobot:IWK1779887`（序列号 1779887，MAC `EE:F6:9B:2C:98:42`，静止上电状态）。
所有操作经 `adb shell input` + `uiautomator dump` 完成，截图与 UI 树在
`captures/device_acceptance/real_vehicle_20261112/`，HCI 日志在
`captures/20261112_realvehicle/`。

## 1. 扫描命中与广播内容

`扫描并连接` → 4 s 内命中 1 台：

| 项 | 实测 |
|---|---|
| 列表行 | `MiniRobot` · `…9887` · −72/−73 dBm |
| 完整名称 | `MiniRobot:IWK1779887` |
| MAC | `EE:F6:9B:…:98:42`（UI 掩码；导出 `transport.id` 为完整值） |
| 序列号 | `1779887` |
| 广播服务 | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |

证据：`rv01_scan.png/.xml`、`rv02_techinfo.png/.xml`。白名单过滤后只剩 1 台，
与 Flutter 版同机型表现一致；扫描页未出现非白名单条目。

## 2. GATT 与 MTU

连接成功后设备页显示真实会话（不再有演示标识），设置 → 数据与诊断给出：

| 项 | 实测 |
|---|---|
| 会话 | `ble-1794480824373`（首连） |
| 链路状态 | `connected` |
| 传输组 | `g1NordicUart` |
| 服务 / 写 / 通知 | `6e400001` / `6e400002` / `6e400003` |
| MTU | `mtu_now 23`，`max_payload_per_packet 20` |
| 标准特征读取 | `2A00 = MiniRobot:IWK1779887`、`2A01 = 3412`、`2A04 = 10003c0000009001` |

`2A04` 是设备自己的首选连接参数（min 20 ms / max 75 ms / latency 0 / timeout 4 s）；
应用不请求提高 MTU，与「MTU 固定 23」的既有结论一致。分片重组在 HCI 侧可直接量到：
0x14 应答帧全长 46 B（`55aa 28 0d 01 14` + 38 B 载荷 + 2 B 校验），在 20 B 上限下被
拆成 20 + 20 + 6 三段 —— 本轮 HCI 里恰好是 1585 个 20 B 头段、1585 个 20 B 中段、
1585 个 6 B 尾段；0xbc 应答 10 B 单段 143 个。整会话未解码字节 0。

## 3. 应用往车上写了什么（HCI 独立核对）

开启开发者选项里的「启用蓝牙 HCI 信息收集日志」后抓取 btsnoop，用
`analysis/tools/collect_android_hci.sh pull` + `hci_quicklook.py` 解码。出站方向
（host→controller）ATT Write Command 共 **1843** 条，全部写到 handle `0x0011`
（`6e400002`），全部通过帧校验（1843/1843），**去重后只有 2 个帧**：

| 帧（明文） | 次数 | 含义 |
|---|---|---|
| `55aa030a011426b7ff` | 1650 | CMD 0x0A / A1 0x01 / A2 0x14，payload `26` |
| `55aa030a01bc0233ff` | 193 | CMD 0x0A / A1 0x01 / A2 0xbc，payload `02` |

与 `protocol/src/main/kotlin/com/iwalk/protocol/PollQueries.kt` 里的
`kOfficialPollQueries` 逐字节相同。**没有第三条出站帧**：无锁车帧（`0x0A/0x03/0x70|0x71`）、
无校准/写类/OTA 帧，也没有重试产生的额外写。

整个会话里除此之外只有 **1 条** ATT Write Request：handle `0x000f` 写 `0100`
（打开 0x000e 通知的 CCCD），即订阅本身。也就是「1843 条查询 + 1 条订阅」，别无他物。

节奏（相邻两帧时间差，HCI 时间戳）：0x14 中位 119.7 ms（p95 124.9），
0xbc 中位 1019.3 ms（p95 1025.2）。调度目标是 110 / 1010 ms，实测多出的 ~10 ms
是连接间隔（20–75 ms）造成的发送排队，与 Flutter 版同量级。

## 4. 回包率

以 HCI 双向计数为准（同一 ~197 s 连续会话）：

| 查询 | 发送 | 回包 | 回包率 |
|---|---|---|---|
| 0x14（0x0d/0x01/0x14，38 B 载荷） | 1650 | 1585 | **96.1%** |
| 0xbc（0x0d/0x01/0xbc，2 B 载荷恒 `bebe`） | 193 | 143 | **74.1%** |

入站 1585 + 143 帧校验全通过（0 坏帧）。这与 Flutter 0.8.6 真机数据
（0x14 ≈ 95%、0xbc ≈ 78%）一致，说明两条节奏在原生版同样成立。

应用导出里的估算值更低（下表 87.9% / 74.6%），因为那是「按调度周期 × 会话时长」
反推的理论发送量，把连接未就绪、界面切换等停顿也算成了丢失；HCI 口径才是准的。
原生版导出**没有** `read_query.sent_by_*` 统计（对齐的是 Flutter 0.8.3，该字段 0.8.4+
才有），所以目前只能靠 HCI 核对发送量。

## 5. 两次导出的快照不变量

| 项 | 导出 A（未标定，18:55） | 导出 B（已标定，19:02） |
|---|---|---|
| `frames_total` | 874 | 4522 |
| `raw_packets` = raw 文件行数 | 2474 = 2474 | 12802 = 12802 |
| `frame_groups` 帧数之和 | 874 | 4522 |
| `evidence_timeline` 条数 | 874 | 1522 |
| `commands_sent` | `[]` | `[]` |
| `transport` | `is_demo:false`、`EE:F6:9B:2C:98:42`、`mtu_now:23` | 同左 |
| 帧组 | 0x0d/0x01/0x14 800 帧 / 0x0d/0x01/0xbc 74 帧 | 同二组，载荷长度 38 / 2 |

导出 B 的时间线只有 1522 条而帧数 4522，是**文档化的上限行为**：时间线保留最近
2000 条、溢出丢最旧 500，长会话必然出现 `frames_total > timeline`。
所以「四者相等」只适用于短会话（前一轮演示 244 帧即如此），长会话应改为核对
`frames_total == raw_packets` 之外的「组内之和 == frames_total」与「时间线 ≤ 2000」。
`raw_packets`（通知条数）与 `frames_total`（重组后帧数）本就不等：本轮 2474 → 874、
12802 → 4522，比值 ≈2.8，正是 20 B 分片重组的必然结果。

用新加的 `analysis/tools/evidence_v1_check.py` 复核这两份真车导出（它按 `evidence_`/
`raw_` 同名规则配对、自动识别线上 XOR 0x55、逐行重组并校验每帧校验和）：

```
captures/device_acceptance/real_vehicle_export/evidence_ble-…185526189000.json  RESULT: OK
  raw_rows 2474 · group_frames_sum 874 · frames_total 874 · timeline 874
  wire_xor_0x55 true · frames_assembled 874 · bad_frames 0
captures/device_acceptance/real_vehicle_export/evidence_ble-…190226473000.json  RESULT: OK
  raw_rows 12802 · group_frames_sum 4522 · frames_total 4522 · timeline 1522
  wire_xor_0x55 true · frames_assembled 4522 · bad_frames 0
```

（旧 `evidence_report.py` 吃的是取证 APP 的「复制证据 JSON」老格式，对
`iwapp-evidence-v1` 只会输出空报告，所以另写了这个校验器；两份真车包之外，
Kotlin 演示导出与真机演示导出同样 OK。顺带发现 2026-11-11 那次 Flutter 演示导出
`raw_packets=28` 而 raw 只有 26 行——正是 Kotlin 版用「入口一次性快照」修掉的那类不一致。）

## 6. 标定导入与真车读数

把 E64 的 `downloads/iwk1779887-calibration-partial-v1.json` 按映射原样粘贴进
「导入标定」后：

- 设置页：`1 组` · `iWK1779887-battery-only-v1 · 1 项映射`。
- 数据与诊断：电量 `69 %` · `已标定 · 已收到数据` · 原始值 `69.0` ·
  `iWK1779887-battery-only-v1 · off 28 · u8`；速度/电压/温度/电流等仍为
  `未标定 · 不猜测数值`。
- 设备页：电量 `69 %`，速度 `—`，与诊断页一致（`rv17_calibrated_device.png`）。
- HCI 同期 0x14 载荷 off28 取值只有 `68`(888 次) 与 `69`(697 次)，慢变，符合电量；
  off26–27 在 116–178 间高频跳动（54 个不同取值），再次印证 E64「off26/27 不是平滑速度」。
- 电压候选 off18–19 本轮为 `c8 18`（6344 → ×0.01 = 63.44 V），与 E60 一致；但官方界面
  无电压读数，仍不映射。

粘贴方式说明：`adb shell input text` 无法输入中文（会抛
`NullPointerException`），也无法直接带双引号；本次用设备端脚本
`input text "$(cat cal.json)"` 传入 ASCII 版 JSON，映射/名称与 E64 文件一致，
仅省略了长 evidence 文本（界面因此显示「未提供证据说明」，属正常状态）。
粘贴原文留档：`captures/device_acceptance/real_vehicle_export/pasted_calibration_min.json`。

## 7. 断开、掉线与重连

- `断开连接` → 确认框「断开当前会话？… 原始通知尚未自动保存」→ `断开` → 回到首页扫描态，
  无残留计数。
- 重连后是**新会话** `ble-1794481404036`（与首连 `ble-1794480824373` 不同），
  计数从 0 起（9 s / 71 帧），标定仍为 `1 组`，电量仍显示 69 %（说明标定与链路会话解耦）。
- 主动关开蓝牙模拟掉线：设备页标题变「连接已断开」，提示「链路已断开 · 上次数据保留并置灰」，
  电量保留最后值并把更新时间显示为 `52s` 前后，诊断页链路状态 `disconnected`，
  **无崩溃、无自动重连**。退出会话后回到扫描态。

## 8. 本轮观察到的问题

1. **已修（2026-11-12 晚）**：蓝牙重启后首次点「连接」出现一次
   `连接失败：java.lang.IllegalStateException: 连接初始化已中断`，再点一次成功。
   现在：初始化被打断/`connectGatt` 返回 null 这类**瞬时失败自动重试一次**（间隔 800 ms，
   仅当首次失败发生在 5 s 内），仍失败才报错；文案换成「链路在初始化阶段被中断；常见于刚重启
   蓝牙或车辆离开范围，可直接重试」，重试过会补 `· 已自动重试 1 次仍失败`，不再甩异常类名。
   超时类失败（15 s 建链、15 s 服务发现）**不重试**——再等一轮没有意义。
   实现：`ble/LinkErrorText.kt` + `DeviceSessionController.connectReal`；测试 `ConnectRetryTest`。
   真机复核（2026-11-12 19:40，同一台 vivo）：点「连接」的同时关蓝牙，得到
   `连接失败：系统没有返回 GATT 连接；蓝牙刚重启时常见，可直接重试 · 已自动重试 1 次仍失败`
   ——两次尝试都发生了，文案可读。截图 `captures/device_acceptance/fix1_connect_error.png`。
   同日蓝牙重启后首次点「连接」**一次成功**（70 帧 / 9 s），未再出现初始化中断。
2. **已修（2026-11-12 晚）**：链路已断时按钮是「退出会话」，确认框却写「断开当前会话？」。
   现在断链状态下标题为「退出已断开的会话？」、正文说明「链路已断开，不会再有新帧」、
   确认键为「退出」；已连接时仍是「断开当前会话？/ 断开」。测试 `DeviceScreenTest`
   两条用例分别锁定两种状态。真机两种状态都已截图（`fix2_exit_dialog.png` 为断链态）。
3. 导出证据不含出站查询统计（见 §4），无法自证「只发了这两条」——需要 HCI 才能证明。
4. **已修（2026-11-12 晚）**：`导出` 只落两份文件（evidence + raw），长会话时间线被截断这一点界面未提示。
   现在 `SessionManager` 自己记录「时间线是否被 2000 条上限裁过」（`isTimelineTruncated`，一旦裁过即为真，
   不靠帧数反推），透传到 `DeviceUiState.timelineTruncated`；分析页在「证据时间线」下方用警示色写出
   「时间线已达上限：只保留最近 N 条，已收 M 帧中更早的条目只剩帧统计与原始通知里有；导出时
   evidence_timeline 同样已截断」，同时把页脚从「完整时间线随导出写入」换成截断版说明——原来那句话
   在截断后是错的。测试 `SessionManagerTest`（2200 帧后标记截断、帧数不丢）、`AnalysisTabBodyTest`
   （截断态文案与旧脚注互斥）。
   真机复核（2026-11-12 19:49，同一台 vivo，2676 帧会话）：分析页显示
   「时间线已达上限：只保留最近 1676 条，已收 2676 帧中更早的条目只剩帧统计与原始通知里有；
   导出时 evidence_timeline 同样已截断。」截图 `captures/device_acceptance/timeline_truncated.png`。
   同会话导出后 `frames_total 3323 = Σ帧组 3323`、`raw_packets 9371 = raw 行 9371`、
   `evidence_timeline 1823`（= 3323 − 3×500，正好三次裁剪）、`commands_sent []`，
   `evidence_v1_check.py` 判 `RESULT: OK` —— 证明提示里的「导出同样截断」不是空话。

## 9. 未覆盖

- 车辆侧断电/远距离断链（本轮只测了手机侧关蓝牙）。
- 骑行/推行等运动工况下的读数与速度标定（车轮未转，速度仍未知；也因此 §11 的
  「速度 >1 km/h 阻止发送」闸门本轮**未被实际检验**）。
  补充（2026-11-12 晚）：判定工具已就位——`analysis/tools/speed_calibration.py`（E67），
  采集步骤见 `analysis/procedure_speed_calibration.md`；闸门的真车验证方案见
  `docs/lock-gate-real-vehicle-plan.md`（B 静止放行 / C 运动拦截时 **HCI 必须零写入** / D 停下恢复）。
- iOS。

## 10. 复现命令

```bash
# 抓 HCI（车辆上电、保持连接）
analysis/tools/collect_android_hci.sh start      # 已开启过则跳过
analysis/tools/collect_android_hci.sh pull 20261112_realvehicle
python3 hci_quicklook.py captures/20261112_realvehicle/btsnoop_hci.log | head

# 从 bugreport 里取到的日志若需重新解帧
python3 analysis/tools/decode_capture.py captures/20261112_realvehicle/att_lines.txt \
  -o captures/20261112_realvehicle/frames.jsonl

# 出站写清点（本轮新增：# WRITE 行 + 解帧自动识别线上 XOR 0x55）
python3 analysis/tools/btsnoop_att.py captures/20261112_realvehicle/btsnoop_hci.log \
  | grep '^# WRITE' | awk '{print $4}' | sort | uniq -c
```

工具链本轮一并修了三处（否则在 macOS + 真车流上跑不通）：

- `decode_capture.py`、`evidence_report.py` 里硬编码的 `/root/iwalk/analysis/protocol`
  改成相对脚本定位，跨机器可跑。
- `decode_capture.py` 新增 `--wire auto|plain|xor55`：真车通知整帧 XOR 0x55，
  原来直接喂给 `StreamDecoder` 只能得到 `unrecognized`（流水线 `decoded_frames 0`）；
  现在按首个数据块自动判定，同一份日志解出 1728 帧、0 坏帧，`test_pipeline.py` 仍 `PIPELINE OK`。
- `btsnoop_att.py` 补记 ATT Write Command/Request（`# WRITE <handle> <hex>` 注释行），
  出站写从此进得了流水线产物，不再是只有 `hci_quicklook.py` 看得到。
- 新增 `evidence_v1_check.py` 校验 `iwapp-evidence-v1` 证据包（见 §5）。


## 11. 锁车/解锁真车实测（2026-11-12 19:56–19:58，晚间补测）

此前 §1–§10 全部为**只读**验证；本节是本项目首次向真车发送改变状态的指令，
在操作者确认车辆停稳、无人站立后执行。

### 操作序列

1. 连接真车（会话 `ble-1794484520802`），设备页显示电量 66%、速度「未标定」、
   「速度字段尚未收到匹配数据」。
2. 设置 → 实验性锁车控制：开关弹出「开启实验性锁车控制？」确认框（正文明确
   「仅静态推导、尚未验证真车效果、速度未知不能证明车辆静止」），点「我已了解，开启」。
   设置页随即显示「锁车状态：已开启（实验性）」。
3. 设备页「实验性锁车」面板：提示「速度未标定，无法判断车辆是否静止。」，
   滑块可用。截图 `captures/device_acceptance/lock1_before.png`（19:56:30）。
4. **19:56:35.825** 滑块滑至右缘 → 关锁。界面 3 s 内变为「已发送 · 车端状态未确认」。
   **操作者观察：车辆实际执行了上锁（有机械/提示反应）。** 静态推导的
   `0x0A/0x03/0x70` 帧由此从「未验证」升级为「真车有效」。
5. **19:58:10.506** 滑块滑至左缘 → 解锁，恢复原状。界面同样返回「已发送 · 车端状态未确认」。

### 证据三重核对（应用记录 / 导出快照 / HCI 独立报文采集）

- 应用侧 `commands_sent`（导出 `evidence_ble-1794484520802_20261112T195821821000.json`）：
  恰好 2 条，明文 `55aa040a037001007dff`（lock）与 `55aa040a037101007cff`（unlock），
  均 `ok=true, simulated=false, vehicle_state="unconfirmed"`。快照不变量：
  `frames_total 1545 = Σ帧组`、`raw_packets 4355 = raw 行 4355`、`timeline 1545`。
- HCI 侧（`captures/20261112_locktest/btsnoop_hci.log`，经 bugreport 提取）：全程出站
  ATT 写 2475 条中，除 0x14 查询 2208 次、0xbc 查询 264 次、CCCD 订阅 1 次外，
  **锁帧线上字节各恰好 1 次**：`00ff515f5625545528aa`（= 明文 ⊕0x55）、
  `00ff515f5624545529aa`。无重试、无其他写类帧。
- 时间对齐：HCI 两条写在 handle `0x11`，时钟时刻 `19:56:35.827` / `19:58:10.509`，
  与应用记录差 2–3 ms，间隔 94.68 s 与应用侧一致。

### 结论与保留意见

- 两条静态推导锁帧**真车有效且各只发一次**；BLE 写入成功 + 车辆有反应，但链路上
  仍无任何车端回执（0x14/0xbc 通知内容不随锁状态变化），「车端状态未确认」的措辞
  依然成立——车辆「已锁」这一结论来自操作者目视，不是协议回读。
- **安全闸门的真实缺口（当晚已修）**：速度未标定时 `lockBlockedReason` 对
  `speedKmh == null` 不拦截，本轮正是在这个状态下发送的——「速度 >1 km/h 阻止发送」
  只对已标定速度生效。
  现已改为 `speedKmh == null` 也阻止，理由文案「速度未标定，无法证明车辆静止，已阻止操作」；
  面板上原来那句独立的「速度未标定，无法判断车辆是否静止」提示随之删除（避免与阻止原因重复）。
  代价：本车速度字段尚未标定（U03 未收敛），**锁车控制在当前车辆上不可用**，这是刻意取舍。
  真机复核：`captures/device_acceptance/lock4_blocked_unknown_speed.png`（滑块 `enabled=false`，
  下方显示该阻止原因）；测试 `SessionIntegrityTest`（策略矩阵）、
  `SettingsAndLockTest`（未标定 → 零写入且记录原因；已标定 1.0 km/h → 走写入失败路径）。
