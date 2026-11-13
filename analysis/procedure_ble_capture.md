# 真机 BLE 只读采集流程（Android + nRF Connect + HCI snoop）

目标：在不发送任何写入命令的前提下，获取设备广播、GATT 服务/特征、可读数据与通知数据。

## 0. 最快路径（推荐，约 10 分钟人工操作）

> 依据：HCI snoop 在 ATT 层记录 **广播外的全部交互**——服务发现（Read By Type）、
> Read Request/Response、Handle Value Notification，因此 nRF Connect 的手工浏览
> 可以整体省略。nRF 仅在 snoop 无法开启的机型上作为兜底。

1. **装 APK**（本仓库已附）：`downloads/tools/nRF.Connect.4.29.1.apk`（仅兜底用，可选）
2. **建当天目录并开报文采集**：
   ```bash
   cd /root/iwalk
   DAY=$(date +%Y%m%d); mkdir -p captures/$DAY
   cp captures/TEMPLATE_session.md captures/$DAY/session.md
   # 手机 USB 连接、开启 USB 调试后
   analysis/tools/collect_android_hci.sh start
   ```
   脚本自动写 snoop 开关并重启蓝牙；若提示未开启（部分 MIUI/HyperOS 限制 `setprop`），
   到 设置→开发者选项 手动打开“蓝牙 HCI 日志”，再关开一次蓝牙。
3. **只需 3 个人工动作**（官方 APP）：
   - 连接平衡车，静置 **3 分钟**（车不动、不骑）
   - 依次打开 电量 / 状态 / 设置 页，每页停 **30 秒**，各截一张图
   - 断开，再静置 1 分钟
   - 截图可用电脑自动抓，免手点：
     ```bash
     adb exec-out screencap -p > captures/$DAY/ui_$(date +%H%M%S).png
     ```
4. **导出+解帧（一条命令）**：
   ```bash
   analysis/tools/collect_android_hci.sh pull $DAY
   ```
   自动尝试 4 个常见 btsnoop 路径 → 失败则 `adb bugreport` 兜底提取 →
   自动跑 `run_pipeline.sh` 产出 `frames.jsonl` + tshark 交叉校验 CSV。
5. **把 `captures/$DAY/session.md`（时间点+界面状态）补上即可**。

操作量对比：

| 原流程 | 最快路径 |
|---|---|
| nRF 逐服务展开 + 逐特征 Read 手点 | 省略（snoop 已含） |
| 广播 AD 手动抄 hex | 省略（snoop 含 LE Advertising Report）；仅在 snoop 不可用时用 nRF |
| 设置里找开关、重启蓝牙 | 脚本一条命令 |
| 自己找 btsnoop 路径并 pull/解压 | 脚本自动 4 路径 + bugreport 兜底 |
| 时间点靠手记 | 截图时间戳自动对应，只需补一行文字 |

> 注意：官方 APP 连接期间**不要**同时用 nRF 连接（连接会被抢占，报文采集仍有效但
> 交互归属混乱）。nRF 兜底流程见下方 A–C。

### 0.1 替代 nRF 的 APP 内采集（可选，与官方 APP 二选一或先后做）

`app-debug.apk` 已具备以下只读采集能力，可完全替代 nRF 的手工点击：

- 扫描列表直接显示广播中的 `serviceUuids` / `manufacturerData` / `serviceData`（hex）
- 连接后自动对**所有可读特征各执行一次 Read**（上限 32 个，结果含错误信息）
- 全量订阅 Notify/Indicate，原始通知写入 JSONL
- 诊断页“复制完整证据 JSON”一次导出：广播 + GATT 树（含描述符）+ Read 结果 + 通知

若只用该 APP 采集，则不需要 HCI snoop；但 HCI snoop 仍是**官方 APP 行为**的唯一
证据来源（我们要比对官方 APP 到底发了什么），建议两者都做。

采集完成后，把诊断页复制的 JSON 存为 `captures/YYYYMMDD/evidence.json`，运行：

```bash
python3 analysis/tools/evidence_report.py captures/YYYYMMDD/evidence.json \
  -o captures/YYYYMMDD/evidence_report.md --json captures/YYYYMMDD/stats.json
```

输出：U01 的 UUID 清单（含描述符）、Read 值、按特征分组的通知帧统计与偏移视图；
未解出的字节原样保留。回归自测：`python3 analysis/tools/test_evidence_report.py`
（期望 `EVIDENCE REPORT OK`）。

## A. 准备

1. 平衡车开机，官方 APP 不要连接（避免占用连接）。
2. Android 测试手机：设置 → 开发者选项 → 打开 **Bluetooth HCI snoop log**（不同系统叫法略异；开启后需**关闭再打开蓝牙**或重启生效）。
3. 安装 nRF Connect for Mobile（Nordic）。

## B. 广播与连接信息（nRF Connect）

逐项记录到 `captures/YYYYMMDD/session.md`：

1. Scanner 页扫描，找到车辆（名称前缀可能是 `LB`/`BleDevice_`/`iW`，RSSI 最强者）。
   - 记录：广播名、MAC（分享时脱敏为 `AA:BB:CC:DD:EE:FF` 形式）、RSSI、原始 AD structure（点进条目看 Raw bytes，重点：Service UUID 16/128-bit、Manufacturer Specific Data）。
2. Connect → 进入 GATT Configurator：
   - 记录全部 Service UUID；对每个 Service 展开全部 Characteristic：UUID、Properties（Read/Write/WriteNoResp/Notify/Indicate）、Descriptor（重点 0x2902 CCCD）。
   - **只读操作**：对每个 Read 特征点 Read 一次，记录 hex；对每个 Notify 特征开启 Indicate/Notify，观察 60 秒。
3. 记录连接建立时刻、订阅后开始出现的周期性通知（间隔、帧长、前几字节是否 `55 AA`）。
4. 断开重连一次，观察广播是否变化（有些设备连接后停止广播）。

## C. 官方 APP 对照实验

1. 保持 HCI snoop 开启。
2. 用官方 APP 正常连接车辆，停留主界面（只看不骑）：
   - 步骤 1：连接成功后静置 3 分钟；
   - 步骤 2：打开电量/状态/设置等只读页面各 30 秒；
   - 步骤 3：断开 APP，车辆静置 1 分钟；
   - 全程在 session.md 记录每步时间点（手机时间到秒）。
3. 导出：设置 → 开发者选项 → 生成错误报告（bug report 含 btsnoop），或
   ```
   adb pull /data/misc/bluetooth/logs/btsnoop_hci.log captures/YYYYMMDD/
   ```
   （旧系统路径：`/data/misc/bluedroid/btsnoop_hci.log`）
4. Wireshark 打开 btsnoop：过滤器
   ```
   btl2cap.cmd_code == 0x04 || btl2cap.sig_id   # ATT
   att.opcode == 0x1b                            # Handle Value Notification
   ```
   导出 ATT 载荷为 hex（Edit → Select All matching → File → Export Specified Packets，或直接用 tshark）：
   ```
   tshark -r btsnoop_hci.log -Y 'btle.data_header.length>0' -T fields \
     -e frame.time_epoch -e btatt.optype -e btatt.handle -e btatt.value \
     > notifications.csv
   ```

### C.5 离线解帧命令链（已在本地合成样本上验证通过，含 tshark 交叉校验）

```
# 一键（推荐）：
analysis/tools/run_pipeline.sh captures/YYYYMMDD/btsnoop_hci.log captures/YYYYMMDD

# 分步（等价）：
# 方式一：本机纯 Python 解析器（无依赖，实测与 tshark 对同一 ATT 载荷输出一致）
python3 analysis/tools/btsnoop_att.py captures/YYYYMMDD/btsnoop_hci.log > att_lines.txt
python3 analysis/tools/decode_capture.py att_lines.txt -o captures/YYYYMMDD/frames.jsonl

# 方式二：tshark 导出 CSV 后直接喂给 decode_capture（自动识别含 btatt.value 的列）
tshark -r btsnoop_hci.log -Y 'btatt.opcode==0x1b || btatt.opcode==0x1d' -T fields \
  -e frame.time_epoch -e btatt.value > notifications.csv
python3 analysis/tools/decode_capture.py notifications.csv -o frames.jsonl

# 回归自测（合成报文采集 → 解析 → 解帧 → tshark 交叉校验）
python3 analysis/tools/test_pipeline.py   # 期望输出 PIPELINE OK
```

输出 JSONL 每条含 `cmd/a1/a2/payload_hex/frame_hex/parse`；`parse=unrecognized`
的原始字节一律保留、不丢弃（doc.md 规则 6）。

字段回填辅助（阶段四）：按 (CMD,A1,A2) 分组列出每个偏移的取值范围与
u8/i8/u16le/i16le 候选解释，仅呈现字节视角，不下单位结论：
```
python3 analysis/tools/payload_inspector.py captures/YYYYMMDD/frames.jsonl
```

## D. 记录格式（每条数据一行 JSON，文件 `captures/YYYYMMDD/raw_records.jsonl`）

```json
{
  "ts": "2026-10-19T17:50:03.121+08:00",
  "session": "S1",
  "device": {"name": "LB-XXXX", "mac": "AA:BB:CC:DD:EE:FF"},
  "source": "nrf|btsnoop|app-observe",
  "direction": "notify|read|write|indicate|scan",
  "service_uuid": "0000ffff-...",
  "char_uuid": "0000ff02-...",
  "handle": "0x0014",
  "hex": "55aa...",
  "step": "静置观察第2分钟",
  "app_state": "官方APP显示电量87%",
  "complete": true,
  "confidence": "high",
  "note": "周期约 500ms"
}
```

## E. 安全红线

- 只做：扫描、连接、读特征、订阅通知。
- 不做：向任何特征写数据（包括"看起来无害"的 0x00）；不动固件升级相关流程；不在车悬空/载物状态下做骑行实验。
- 若必须验证控制类字段，先讨论风险与防护措施，且优先用静置状态可观察的只读差异。

## 验收标准

- [ ] 广播原始字节（含 AD structure）至少 3 条
- [ ] 完整 GATT 树（所有 service/char/props/desc）截图 + 文字记录
- [ ] ≥ 5 分钟通知数据 hex（官方 APP 连接期间，来自 btsnoop）
- [ ] 每段数据对应 session.md 中的时间戳与界面状态描述
- [ ] 数据回来后运行 `analysis/protocol/` 下解帧脚本可还原 `55 AA` 帧（若帧匹配静态分析结论）
