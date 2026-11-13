# 真机 BLE 只读采集：完整操作手册

> 版本：2026-10-20。适用于 Android 手机 + iWalk/Lebid 平衡车。
> 配套简明规程：`analysis/procedure_ble_capture.md`；本文是逐步展开版。
> 全程只读：扫描、连接、发现服务、Read、订阅通知；**不发送任何写入命令**。

## 0. 开始前：固定一个终端会话

以下所有命令都在**同一个终端**里执行（`DAY` 变量依赖当前会话）：

```bash
cd /root/iwalk
export DAY=$(date +%Y%m%d)
echo "本次采集目录: captures/$DAY"
mkdir -p captures/$DAY
cp captures/TEMPLATE_session.md captures/$DAY/session.md
```

如果中途换了终端，重新执行 `export DAY=...`。

---

# 采集前准备（一次性，约 15 分钟）

## 1. 手机端设置

1. **开启开发者选项**：设置 → 关于手机 → 连续点击“版本号”7 次
2. **开启 USB 调试**：设置 → 开发者选项 → USB 调试
3. **开启蓝牙 HCI 日志**（名称可能是“蓝牙 HCI 日志”/“蓝牙数据包日志”）
4. **关闭蓝牙再打开**（让日志生效；部分机型需重启）

## 2. 连接电脑并确认

```bash
adb devices        # 必须显示 "device"，不是 "unauthorized"
```

显示 `unauthorized` 时，在手机上点“允许 USB 调试”。

## 3. 平衡车状态

- 开机，**不要**先用官方 APP 连接
- 车放平、不载人、不在坡上（全程只做静置观察）
- 记下铭牌型号并拍照存档（U04 车辆型号至今未知）

---

# 第一轮：HCI 报文采集（官方 APP，核心）

## 4. 一键开启报文采集

```bash
analysis/tools/collect_android_hci.sh start
```

脚本写入 snoop 开关并重启蓝牙。看到 `snoop 已开启` 才算成功。

若提示“未能通过 adb 开启”（MIUI/HyperOS 常见）：
- 回 设置 → 开发者选项，手动打开蓝牙 HCI 日志
- 关闭再打开蓝牙
- 手动开启同样有效，可继续下一步

## 5. 官方 APP 操作（严格按时间线，约 5 分钟）

打开手机秒表或看系统时间，每步记下到秒的时刻，写进 `session.md`：

| 步骤 | 动作 | 停留 | 要记什么 |
|---|---|---|---|
| S1 | 官方 APP 连接平衡车 | — | 连接成功时刻 |
| S2 | 停主界面不动 | **3 分钟** | 起始/结束时刻 |
| S3 | 打开“电量”页 | 30 秒 | 截图 + 显示值（如 87%） |
| S4 | 打开“状态”页 | 30 秒 | 截图 + 显示值（速度/温度等） |
| S5 | 打开“设置”页 | 30 秒 | 截图（版本号/序列号很有用） |
| S6 | 断开 APP | — | 断开时刻 |
| S7 | 车静置 | 1 分钟 | — |

截图用电脑抓，免手点（文件名带时间戳）：

```bash
adb exec-out screencap -p > captures/$DAY/ui_$(date +%H%M%S).png
```

**红线**：只看不骑、不调参、不点校准、不进固件升级。

## 6. 导出并自动解帧

```bash
analysis/tools/collect_android_hci.sh pull $DAY
```

脚本自动完成：
1. 依次尝试 4 个 btsnoop 路径
2. 都失败则 `adb bugreport` 提取（1–3 分钟）
3. 调用 `run_pipeline.sh` 解帧

预期产出（`captures/$DAY/`）：
- `btsnoop_hci.log`
- `att_lines.txt`
- `frames.jsonl`
- `notifications_tshark.csv`（交叉校验）

验收：`frames.jsonl` 中存在 `"parse": "known_frame"` 且 `frame_hex` 以 `55aa` 开头。

---

# 第二轮：APP 内采集（约 3 分钟，补 GATT 树）

本轮产出 U01 的 UUID 清单。官方 APP 的报文采集里虽有服务发现，但 APP 整理好的结构更直接。

## 7. 安装替代 APP

```bash
adb install -r /root/iwalk/app/build/app/outputs/flutter-apk/app-debug.apk
```

提示签名冲突/已存在时，先卸载：

```bash
adb uninstall com.example.iwapp
```

## 8. 操作步骤

1. 打开 APP → **扫描**页 → “开始扫描”
2. 列表里找车辆（名字可能带 `LB`/`BleDevice_`/`iW`，取 RSSI 最强）
   - 每项下方显示 `SVC`（服务 UUID）、`MFR`（厂商数据）、`SD`（Service Data）的 hex
3. 点设备连接（**先断开官方 APP**，否则连接被抢占）
4. 等 10 秒（自动完成服务发现 + 所有可读特征 Read 一次）
5. 切到 **诊断**页，确认看到：
   - GATT 服务树（含描述符）
   - Read 结果
   - 原始通知记录
6. 点右上角 **“复制完整证据 JSON”**（`{}` 图标）
7. 粘贴保存为 `captures/$DAY/evidence.json`
   - 手机上复制后可通过微信/邮件发给自己，或直接用 `adb` 拉取临时文件
8. 静置 2 分钟，再复制一次 JSON（可选，多一组样本）
9. 断开

## 9. 生成证据报告

```bash
python3 analysis/tools/evidence_report.py captures/$DAY/evidence.json \
  -o captures/$DAY/evidence_report.md --json captures/$DAY/stats.json
```

预期输出：`report written: ...`，另有 `stats written: ...`。

---

# 第三轮（可选）：nRF Connect 视觉确认

仅当前两轮拿不到完整 GATT 树时使用。

```bash
adb install -r downloads/tools/nRF.Connect.4.29.1.apk
```

- 扫描页找到车辆 → 截图（含 Raw bytes）
- 连接 → 展开每个 Service/Characteristic → **截图完整 GATT 树**
- 对每个 Read 特征点一次 Read
- **不要点 Write，不要做任何写入类操作**

---

# 回来后本地分析（由分析侧执行）

## 10. 检查数据完整性

```bash
ls -lh captures/$DAY/
python3 analysis/tools/payload_inspector.py captures/$DAY/frames.jsonl
```

## 11. 交叉校验工具链

```bash
python3 analysis/tools/test_pipeline.py        # 期望 PIPELINE OK
python3 analysis/tools/test_evidence_report.py # 期望 EVIDENCE REPORT OK
```

## 12. 回填结论

据此更新：
- `analysis/protocol_findings_table.md`：U01 UUID → 确认值；遥测字段 → 实测
- `analysis/evidence_log.md`：新增证据条目
- `app/lib/protocol/scooter_protocol.dart`：把“未验证”注释升级为实测

---

# 验收清单

- [ ] `session.md` 各步骤时间点到秒
- [ ] 至少 3 张界面截图（电量/状态/设置）
- [ ] `btsnoop_hci.log` 非空且含 ATT 通知
- [ ] `frames.jsonl` 解出 `55aa` 帧
- [ ] `evidence.json` 含 GATT 树 + Read + 通知
- [ ] `evidence_report.md` 生成成功

---

# 常见故障排查

| 症状 | 处理 |
|---|---|
| `adb devices` 为空 | 换数据线（有的只能充电）、装厂商 USB 驱动、手机点“允许调试” |
| snoop 开不了 | 手动在开发者选项打开，关开蓝牙；或改用 APP 内采集（第二轮） |
| 扫描不到车 | 确认官方 APP 已断开；车是否开机；手机定位权限是否开启 |
| 连接立刻断开 | 车已被官方 APP 占用，先杀掉官方 APP |
| `btsnoop` 为空 | snoop 未生效就连接了；重来一次，连接前关开蓝牙 |
| APP 报“权限被拒绝” | 手机设置里给 APP 开“附近的设备”权限；Android 12+ 还需定位 |
| `frames.jsonl` 无 known_frame | 可能不是 `55AA` 协议（经典蓝牙？），把 `att_lines.txt` 前 50 行交分析侧 |

---

# 数据安全

- MAC 地址分享时脱敏为 `AA:BB:CC:DD:EE:FF`
- `captures/` 下文件不要提交到公开仓库
- 采集完成后如不再构建，可还原临时环境：
  `swapoff /swapfile && rm /swapfile && sysctl -w vm.swappiness=0`
