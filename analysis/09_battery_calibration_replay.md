# P2：电量部分标定重建与离线回放

日期：2026-10-21。范围：i.walk:iWK1779887；只读数据解释，不涉及车辆控制。

## 结果与边界

已按 E64 描述**重建** `downloads/iwk1779887-calibration-partial-v1.json`，
不是找回旧文件，不能声称与旧文件逐字节一致。

仅映射应答组 `CMD=0x0d / A1=0x01 / A2=0x14` 的 payload **零起算偏移 28**：
`u8 × 1 + 0 → batteryPercent`。这不是查询帧 `CMD=0x0a`，也不是整帧偏移 28。
速度、电压、温度和其他字段不映射。

| 数据来源 | 本轮检查 | 结果 | 证据边界 |
|---|---|---|---|
| `captures/20261021_official/btsnoop_hci.log` | 原始 HCI，目标 ACL `0x0202` / 通知特征句柄 `0x000e`，逐包 XOR-0x55 还原并重组、校验 | 2573 帧有效、0 拒绝；2368 条主状态均映射为 83；205 条 `0xbc` 不匹配电量配置 | 人工查看同会话 `shot_03_baseline20s.png`，车辆电量为 83%，速度为 0.0 km/h；不是手机状态栏的 100% |
| `captures/20261020/frames.jsonl` | 4032 条派生记录的 payload 回放 | 1816 条主状态；99×44、95×804、90×170、89×92、88×706；第一条为 99 | `chk_ok=true` 是历史元数据，本轮未重验其原始校验和；99 不是新增的官方截图锚点 |
| `captures/app_output/raw_ble-1792540522129_20261021T075530410030.jsonl` | 177 条原始通知 XOR 还原、重组、校验，并与 evidence JSON 对账 | 61 帧有效、0 拒绝；58 条主状态均映射为 79；3 条 `0xbc` 不匹配；发送计数 60+7=67，原始记录/帧组/时间线计数一致 | 文件自报 App 0.8.6 (14)；79 为回放一致性结果，不是新增官方截图锚点 |

**旧原始日志不能混用：** `20261020/session.md` 引用的旧根目录日志为约 3.1 MB；
当前 `captures/btsnoop_hci.log` 为 269637 B，按同一目标句柄重放仅有 963 帧，
主状态电量字节为 87/88/89，不能充当 4032 条派生记录的对应原件。
本轮未修改这些历史采集文件。

## 自动回归

```sh
PYTHONDONTWRITEBYTECODE=1 python3 analysis/tools/test_battery_calibration.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s analysis/protocol -p 'test_*.py' -v
```

- 新增电量配置回归 7/7：精确 JSON 结构与映射、83 原始 HCI、99 派生 payload、79 原始通知与计数、错误帧组/查询不匹配、短载荷未知而非 0、未证实字段不映射。
- 原 Python 协议回归 7/7。
- 先运行新增测试，因配置文件缺失得到 7 个预期失败；写入配置后 7/7 通过。
- Python 测试中的 `battery_value` 只是这个固定 u8 配置的参考计算。
  格式与当前 Dart `CalibrationSet.decode` / `FieldMapping.fromJson` 做了静态核对；
  **没有运行 Flutter/Dart，没有验证手机 UI 导入、持久化或实际显示。**
- 本轮没有连接车辆、发送 BLE 指令、改动应用运行时代码或重建 APK。

### 额外校验与旧工具环境限制

对临时配置分别注入错误偏移、错误倍率、误用查询 CMD、增加未验证速度字段，
四种变异均被回归测试拒绝；没有修改交付配置。

额外运行旧工具冒烟测试时，直接运行最初均因硬编码
`/root/iwalk/analysis/protocol` 导致 `ModuleNotFoundError`。
未改动这些旧脚本，仅为本次命令指定正确 `PYTHONPATH` 后复跑通过：

```sh
PYTHONPATH="$PWD/analysis/protocol" PYTHONDONTWRITEBYTECODE=1 python3 analysis/tools/test_evidence_report.py
PYTHONPATH="$PWD/analysis/protocol" PYTHONDONTWRITEBYTECODE=1 python3 analysis/tools/test_pipeline.py
```

结果分别为 `EVIDENCE REPORT OK` 和 `PIPELINE OK`。
两项使用合成数据；本机本次未执行 tshark 交叉验证，不能代替上面的真实报文采集回放。
旧脚本硬编码路径本身仍未修复。

## 手机导入与最小验收（待操作）

1. 先保留现有标定原文；导入会**整体替换**，不是合并。没有备份且需要旧标定时先不要导入。
2. 打开 `downloads/iwk1779887-calibration-partial-v1.json`，复制全文。
3. 手机 App：**设置 → 标定与证据 → 导入标定 → 粘贴 → 应用并保存**。
4. 使用该目标车辆的真实连接，不用演示模式验证。保持已有官方节奏，诊断轮询和实验性锁车均关闭。
5. 有新主状态帧后，确认电量出现；速度、电压、温度仍为未知/`—`。
   **不要要求当前电量固定为 79 或 83**，这些是旧样本值，不是车辆此刻读数。
6. 停稳状态下可先用官方 App 截图记录电量，再断开官方 App、连接替代 App 截图对照；
   记录两次时间，不把顺序连接的截图误称为同刻数据，不同时用两个 App 抢占 BLE。
7. 重启替代 App 后复核标定是否保留、是否应用于真车；截图和导出留档。
   如数值明显异常，清除本机标定并保留证据，不猜比例修正。

当前应用只按 `(cmd,a1,a2)` 匹配配置，**profile 名称不是车型或序列号的自动约束**。
请只在目标车辆上使用；更换车辆/固件后需要重新验证。

## 速度标定的下一步

见 `analysis/procedure_speed_calibration.md`。现有官方画面只有 0.0 km/h，
不能据此给出速度的偏移、单位、倍率或正负号。

## SHA-256（本轮输入与输出）

| 文件 | SHA-256 |
|---|---|
| `downloads/iwk1779887-calibration-partial-v1.json` | `67072efe3a1839c670c69c49de375a031a181c337945310860ac87a13d2e2f6e` |
| `captures/20261021_official/btsnoop_hci.log` | `da60e2a8f7cc9d7e94c9389a26dea25e4c489e30104fb40f8c0d28df4a64ebbf` |
| `captures/20261021_official/shot_03_baseline20s.png` | `e6f3645b6e6ded768681577e0dd34865067a89a5fc2cee9ca190a09443ed96eb` |
| `captures/20261020/frames.jsonl` | `058d0c1668a37498f99edddbbd3ee0dc3e2892680873e43dfe093821b5058917` |
| `captures/app_output/raw_ble-1792540522129_20261021T075530410030.jsonl` | `1cea9b570ed99550a333652abd7c80d76384f11b50a851830f3b838ed279763c` |
