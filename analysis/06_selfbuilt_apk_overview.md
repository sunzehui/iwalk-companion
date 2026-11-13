# 自建 APK 功能说明（app-debug.apk）

> 整理时间：2026-10-19 21:05（本地时间）。
> 结论来源：本机实际读取源码 + `aapt2 dump badging` + `sha256sum`，均为已执行事实。
> 相关文档：构建过程与踩坑见 `05_app_build_report.md`；协议结论见 `protocol_findings_table.md`。

## 1. 结论

`app/build/app/outputs/flutter-apk/app-debug.apk` 是**我们自己构建的 Flutter 版 iWalk
平衡车只读诊断 / 互操作研究 APP**，与厂商客户端无关（参考客户端为公开渠道获取的正式版应用，本地留存、不入库）。

一句话定位：**一个用 Flutter 写、带 GATT 树导出和原始报文 JSONL 落盘的链路诊断器**，
用于采集本车通信数据（对应 U01–U03），为自研伴侣应用铺路。

## 2. 构建产物信息（已实测）

| 项 | 值 |
|---|---|
| 路径 | `app/build/app/outputs/flutter-apk/app-debug.apk` |
| 大小 / 时间 | 157,863,282 B / 2026-10-19 20:36:58 |
| SHA-256 | `a69a6502b25cffeaf9811d76325637127aded15caf4892483775904f347f7052` |
| SHA-1（构建副产物 `.sha1`） | `8e1920ab7b66bcda5ed60b8dd7d77df03ec2098d` |
| 包名 / 版本 | `com.example.iwapp` / versionName 0.1.0，versionCode 1（**debug 构建**） |
| minSdk / targetSdk / compileSdk | 24 / 36 / 36 |
| 技术栈 | Flutter + Riverpod + flutter_blue_plus 1.32.x + 自研协议包 `ibwalk_protocol`（`protocol_dart/`） |

### 权限（aapt2 复核）

| 权限 | 用途 |
|---|---|
| `BLUETOOTH_SCAN`（`neverForLocation`） | Android 12+ BLE 扫描 |
| `BLUETOOTH_CONNECT` | Android 12+ 连接/读 GATT |
| `BLUETOOTH`、`BLUETOOTH_ADMIN`（maxSdk 30） | 旧版本兼容 |
| `ACCESS_FINE_LOCATION`（maxSdk 30） | Android ≤11 扫描 BLE 所需 |
| `INTERNET` | Flutter 模板默认，当前业务未使用 |

> 说明：README/TASK_PROGRESS 中 E19 记录的 SHA-256 `aee600b7…` 是补权限前的旧包；
> 当前磁盘上的是 E20/E22 增量重建后的产物，以本文档表格为准（与 05 报告第 8 节一致）。

## 3. 实际功能（三个页面）

### 3.1 设备扫描页 `app/lib/features/device_scan/device_scan_page.dart`

- BLE 扫描 12 秒（`device_repository.dart` 默认 `timeout: 12s`），按 RSSI 降序列出设备
  （名称、MAC、RSSI），点击连接。
- 扫描结束以 `FlutterBluePlus.isScanning` 为信号关闭结果流（E21 修复，不会永久"扫描中"）。
- 连接后执行 GATT 服务发现，并**订阅所有 Notify/Indicate 特征**（`BleGattTransport.connect`，
  `ble_gatt_transport.dart:42-80`），不硬编码任何 UUID（U01 未知，静态路线已排除）。

### 3.2 诊断页 `app/lib/features/diagnostics/diagnostics_page.dart`

- 显示完整 **GATT 服务树**（服务/特征 UUID、属性 R/W/w/N/I、handle），即 U01 证据来源；
  可一键复制到剪贴板。
- 实时显示收到的**原始通知字节**（hex，最多显示最近 50 条），可复制。
- 原始记录同时写入 JSONL：`<系统临时目录>/iwapp_<session>.jsonl`
  （字段：ts/device/session/source/direction/hex/step，见 `raw_data_logger.dart`）。

### 3.3 状态页 `app/lib/features/device_status/device_status_page.dart`

- 用 `StreamDecoder` 按帧格式 `55 AA | L | CMD | A1 | A2 | payload | chk` 重组并解析通知流。
- 展示已解码帧（CMD/A1/A2/payload）及人类可读分类；**校验失败或无法识别的字节原样保留、
  不丢弃、不伪造**（`connection_manager.dart:47-53`）。

## 4. 明确的安全边界（有意为之）

- **只读**：`BleGattTransport.write()` 在 `allowWrite=false` 时直接抛 `StateError`
  （`ble_gatt_transport.dart:92-99`），且未接入任何 UI。
- 不含电机控制、一键校准、OTA 升级、参数写入；对应 `doc.md` 安全规则 2 的禁令
  （0x04+A2=0x1E 校准、OTA 分包路径，见 E15/E16）。
- `scooter_protocol.dart` 中的请求构造器全部标注"仅静态分析推导、未真机确认"，且未接入 UI。
- 解码标签统一带"未验证"字样；唯一静态确认的是握手黄金帧 `55 AA 02 FF 01 03 FA FE`。

## 5. 当前状态与下一步

- 状态：debug 包已构建，静态验证通过（analyze 0 issue、测试 10/10、协议包 6/6）；
  **尚未真机验收**。
- 下一步（依赖用户设备）：
  1. 真机安装本 APK → 扫描 → 连接 → 诊断页导出 GATT 树（U01）；
  2. 按 `procedure_ble_capture.md` 采集通知帧 → 回填 U02/U03；
  3. release 包与 iOS 编译未做（iOS 需 macOS + Xcode）。
