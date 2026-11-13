# iWalk 伴侣原生 Kotlin 重写设计

- 日期：2026-10-29
- 状态：待用户复核
- 决策来源：用户要求"改为 Kotlin 实现"；经确认采用"全量原生重写 + 新建 `app_kotlin` 与 `app_v2` 并存 + Jetpack Compose + 完全对齐 v0.8.3"

## 1. 背景

`app_v2` 是 Flutter 伴侣应用（v0.8.3+11，Dart 约 11,000 行），已实现设备连接、遥测解码、证据导出、离线分析、标定导入与默认关闭的实验性锁车。用户决定改用 Android 原生 Kotlin 实现，`app_v2` 保持原样作为对照与回归基准。

一个已知的文档/代码不一致：`app_v2/README.md` 声称遥测只读、不发送应用层指令，但 `BleTransport` 实际按官方 App 节奏轮询写入 `0x14`（110ms）与 `0xbc`（1010ms），否则车端不主动通知。本移植以**代码行为**为准，保留轮询查询帧；旧 README 的口径问题不在本次范围内修正（可在交付时顺带更新 `app_kotlin/README.md` 说明清楚）。

## 2. 目标与非目标

### 目标

1. 功能与安全行为对齐 `app_v2` v0.8.3（见第 6 节页面映射、第 7 节链路行为）。
2. 纯逻辑（协议、解码、分析、导出）可在 JVM 上无设备测试。
3. 交付可 sideload 的 arm64 release APK，并在已连接的 vivo（V2241A，Android 16）上完成安装与验收。
4. 体积与启动路径优于 Flutter 版；启动耗时和 APK 体积给出实测数字。

### 非目标

- iOS、后台保活/前台服务、数据库、Hilt、第三方 BLE 库。
- 新增协议指令、控制功能、字段语义结论。
- 修改 `app_v2` 的现有实现（仅在交付说明中记录对照结果）。

## 3. 工程结构

```
hci/app_kotlin/
├── settings.gradle.kts          include :protocol, :core, :app
├── build.gradle.kts             根构建脚本
├── gradle/libs.versions.toml    版本目录
├── gradle/wrapper/              Gradle 8.13 wrapper
├── protocol/                    纯 Kotlin/JVM，无 Android 依赖
│   └── src/main/kotlin/com/iwalk/protocol/
│       ├── IbwalkFrame.kt       帧模型、encode、checksum、decodeFrame
│       ├── StreamDecoder.kt     滑动重组、校验失败保留、XOR 0x55 自动识别
│       ├── TransportGroups.kt   G1–G5、selectTransport、名称白名单、序列号
│       ├── LockProtocol.kt      两条固定锁帧（仅编码）
│       ├── PollQueries.kt       0x14/110ms、0xbc/1010ms 查询表
│       └── LinkBudget.kt        MTU 23、20B/包、250ms 重组超时
├── core/                        纯 Kotlin/JVM，无 Android 依赖
│   └── src/main/kotlin/com/iwalk/core/
│       ├── model/               Calibration、TelemetryField、Snapshot、Evidence、RawBleRecord
│       ├── decode/              TelemetryDecoder
│       ├── session/             SessionManager、Transport 接口、PollScheduler、锁车守卫与记录
│       ├── analysis/            frame stats、离线回放、A/B 对照
│       ├── export/              证据包序列化（schema iwapp-evidence-v1）
│       └── demo/                DemoTransport、DemoProfile
└── app/                         Android 应用
    ├── src/main/kotlin/com/iwalk/companion/
    │   ├── ble/                 BleTransport（原生 GATT）、扫描、权限、GATT 串行队列
    │   ├── data/                SharedPreferences 存储、导出落盘
    │   ├── di/                  AppContainer、ViewModel 工厂
    │   ├── ui/                  theme、components、screens
    │   └── MainActivity.kt
    ├── src/main/res/            图标、splash、字符串
    ├── src/main/assets/         hoverboard.png（从 app_v2 复制）
    ├── src/test/                JVM 单测（ViewModel、存储、导出）
    └── src/androidTest/         Compose UI 测试（真机）
```

### 依赖方向（强制安全边界）

```
:app  →  :core  →  :protocol
```

`:protocol` 与 `:core` 不依赖 Android，也不含通用写接口。出站能力只有两项：

- `PollScheduler` 的两条固定查询；
- `LockControl.setLock(lock: Boolean)` 的一条固定锁帧。

`:app` 的 BLE 层实现这两个出站通道，不暴露其他写方法。

## 4. 技术栈与构建

| 项 | 选择 |
|---|---|
| 语言 | Kotlin 2.3（JDK 17 toolchain） |
| 构建 | Gradle Wrapper 8.13 + AGP 8.13.1 |
| UI | Jetpack Compose（Material3），单 Activity |
| 状态 | ViewModel + StateFlow；手写 `AppContainer` 注入 |
| 异步 | kotlinx-coroutines |
| 序列化 | kotlinx.serialization（`prettyPrintIndent = "  "`） |
| 持久化 | SharedPreferences（键名与旧版一致） |
| BLE | 原生 `BluetoothLeScanner` + `BluetoothGatt` |
| SDK | `compileSdk 36`、`minSdk 24`、`targetSdk 36`、仅 `arm64-v8a` |
| 包名 / 应用名 | `com.iwalk.companion` / `iWalk 伴侣` |
| 权限 | `BLUETOOTH_SCAN(neverForLocation)`、`BLUETOOTH_CONNECT`；API ≤30 补定位权限；不申请 `INTERNET` |
| 签名 | release 使用本机 debug keystore（仅自用 sideload，与旧版一致） |

宿主环境已具备：JDK 17、Android SDK（platform 36、build-tools 35.0.0）、Gradle 8.12 缓存、AGP 8.13.1 / Kotlin 插件缓存，Maven 可达；vivo 设备 `10AD1Z0Q1Y001R9` 在线。

## 5. 状态与数据流

单会话架构，所有状态集中在 `SessionViewModel`：

```
BleTransport / DemoTransport
        │ IncomingPacket / TransportEvent
        ▼
SessionManager（core）
  ├─ 每特征独立 StreamDecoder → IbwalkFrame
  ├─ TelemetryDecoder（标定驱动）→ TelemetrySnapshot
  ├─ EvidenceEvent 时间线（上限 2000，超出丢前 500）
  ├─ 原始行（最近 N 条供分析页）
  └─ LockCommandRecord 列表
        │ StateFlow
        ▼
SessionViewModel → Compose（device / analysis / settings）
```

- 标定热切换：替换 `CalibrationSet` 后重建解码器并重放已收帧。
- 演示会话使用 `DemoProfile`，不写入用户标定存储。
- 断链/过期只降级展示，不把旧值归零。

## 6. 页面映射

| 旧实现 | 新实现 | 说明 |
|---|---|---|
| `HomeShell`（顶栏 + 状态胶囊 + 3 页签） | `HomeScreen` | 副标题随页签与会话态变化 |
| `device_page`（未连接/已连接） | `DeviceScreen` | 主数字速度、图标标、原始工作模式/告警码、会话行、设备与链路、本次会话能力、数据来源面板 |
| `scan_page` | `ScanScreen` | 进入即扫、停止/重扫、白名单 33 前缀、掩码 MAC/序列号、内联错误 |
| `analysis_page`（实时/离线两段） | `AnalysisScreen` | `SegmentedButton` 两个子视图：实时分析（`diagnostics_page.dart` 的 `LiveAnalysisView`）、离线分析（`replay_page.dart` 的 `OfflineAnalysisView`）；**采集对照不是第三个子视图**，它是离线分析内"对照采集 B（可选）"的 A/B 逐偏移区块；帧组偏移表、最近 100 条通知、证据时间线、字段字典、复制/导出 |
| `settings_page` | `SettingsScreen` | 车辆与链路、预览入口、数据与诊断、参考、安全、标定与证据、会话与导出、锁车控制、关于与边界 |
| 4 个独立二级页 | Compose 独立页面 | 数据与诊断、故障与术语参考、只读与安全、静态界面预览 |
| `calibration_import_sheet` | `CalibrationImportSheet` | JSON 校验、非法输入拒绝 |
| 6 个通用组件 | Compose 组件 | 状态胶囊、帧组卡片、双向滑动锁、来源面板、链路面板、导入面板 |

静态预览页的控件无点击回调、不创建会话、不读写任何状态。

## 7. 链路行为（逐条对齐）

1. **扫描**：无设置过滤器，名称前缀在客户端判定；结果展示名称、RSSI、掩码地址；停止/重扫可用。
2. **连接**：15s 超时 → 发现服务 → 记录完整 GATT 树（服务、特征、属性、描述符）→ `selectTransport` 选组；未匹配 G1–G5 时保留服务树并报"未匹配已知传输组"。
3. **标准读取**：`2a00` / `2a01` / `2a04` 一次性读取，失败记 `ERR`，不阻断连接。
4. **订阅**：选中通知特征 + 同服务内所有 `notify/indicate` 特征；CCCD 写 `ENABLE_NOTIFICATION_VALUE`。
5. **GATT 操作串行队列**：所有 read / write / descriptor 操作单线程排队执行，避免并发 GATT 导致的 `133` 错误；连接关闭时清空队列并失败在途操作。
6. **轮询**：G1 且存在写特征时启动；`0x14` 每 110ms、`0xbc` 每 1010ms；一次只写一条；写失败停止调度；不重试。
7. **编码**：所有出站帧 XOR 0x55；入站按特征独立解码器，以首包前两字节（`00 FF` / `55 AA`）判定混淆模式并锁定。
8. **锁车**：G1 + 写特征可用才开放；默认关闭；开启需风险确认并持久化；速度已标定且 `|v| > 1.0` 时拦截；每次滑动只写一次，不重试、不回读；结果只显示"已发送 · 车端状态未确认"或"已模拟发送"。
9. **断开**：停轮询、取消订阅、清写特征、断开 GATT；会话状态置 `disconnected`；有未导出帧时先确认。
10. **MTU**：不协商，固定 23 / 单包 20B；分片由 `StreamDecoder` 重组。

## 8. 持久化与导出

- `calibration.v1`：用户导入的标定 JSON；为空则删除键；损坏值在加载时删除且不阻塞启动。
- `lock_control_enabled.v1`：布尔，默认 `false`。
- 演示标定永不落盘。
- 导出目录 `Documents/iwapp_exports/`；文件 `raw_<session>.jsonl`、`evidence_<session>.json`。
- schema 仍为 `iwapp-evidence-v1`，字段名与结构不变；`app` / `source` 字段值改为 `iwalk_kotlin`（唯一有意差异，用于区分来源）。
- `commands_sent` 的 `vehicle_state` 恒为 `unconfirmed`。

## 9. 测试策略

### `:protocol`（JVM）

- 两条查询帧逐字节黄金比对；两条锁帧黄金值。
- 校验和失败保留、滑动重新同步、跨包分片、尾部不完整帧。
- XOR 0x55 自动识别、模式锁定、明文/混淆混流。
- `selectTransport` 真值表（G1–G5、`ffe0` 遮蔽、M6 分支）。
- 33 个名称前缀、`IWK` 序列号提取、空名/短名边界。

### `:core`（JVM）

- 标定：6 种标量类型、负数、越界跳过、JSON 往返、空标定计数、演示隔离。
- 帧统计：分组排序、单样本、混合长度、`maxOffset`。
- A/B 对照：仅 A/仅 B、分布一致、报告不含语义。
- 回放：空格/冒号/方向 token/散文行、分片重组、pending 保留。
- 轮询：虚拟时间节奏、失败即停。
- 会话：标定热切换重放、原始行/证据事件、锁车守卫矩阵。
- 导出：schema 键名、缩进、`vehicle_state` 黄金比对。

### `:app`（JVM + 真机）

- JVM：ViewModel 状态迁移、存储读写、导出文件写入（临时目录）。
- 真机 `connectedAndroidTest`：三页签骨架、静态预览惰性、锁车默认关+风险确认、非法标定拒绝、演示会话不采纳演示标定、诊断页字段齐全、离线对照。
- 假 GATT 快照：无写特征时 `lockControlAvailable=false`。

### 真机人工验收（代理执行，需滑板车配合的部分标注未实测）

1. `assembleRelease` 产物核对：仅 arm64、v1+v2 签名、`minSdk 24` / `target 36`、清单无 `INTERNET`；记录 APK 字节数并与旧版 21,732,801 B 对比。
2. `adb install -r` 安装；`am start -W` 记录冷启动耗时；肉眼确认无黑屏、首页可用。
3. 演示流程：启动 → 演示数据 → 速度/指标/原始码 → 分析页时间线/字段字典 → 设置导入标定 → 重启后保留 → 锁车开关默认关、开启需确认、滑动只产生 `simulated` 记录。
4. 导出一次证据包，与 Flutter 版演示导出做结构 diff，差异仅允许 `app` / `source`。
5. 真车 BLE（扫描 → 连接 → 轮询 → 解码 → 断开）：需要车通电在手边；否则明确记录"未实测"及已验证的失败路径（无设备、无权限、无写特征）。

## 10. 验收标准

- `./gradlew :protocol:test :core:test` 全部通过，覆盖上述用例。
- `./gradlew :app:connectedDebugAndroidTest` 在 vivo 通过。
- release APK 安装成功，演示流程全通，导出结构与旧版一致（除来源字段）。
- 真机冷启动耗时、APK 体积给出具体数字；肉眼现象与数字成对记录。
- `app_kotlin/README.md` 写明验证状态、未实测项、与 `app_v2` 的差异。

## 11. 已知差异与风险

| 项 | 说明 |
|---|---|
| README 与代码口径 | 旧版称只读但实际轮询；新版文档如实描述"两条固定查询 + 一条固定锁帧" |
| 导出 `app`/`source` | 改为 `iwalk_kotlin`，其余 schema 不变 |
| GATT 并发 | 原生实现必须自带串行队列；Flutter 插件内部已处理，属新增风险点 |
| 后台行为 | 不做前台服务；息屏后通知持续性与旧版一样取决于系统策略 |
| 真车验证 | 锁帧从未在真车发送；本次同样不改变该事实 |
| iOS | 不再支持 |
| 签名 | debug keystore release，仅自用 sideload |
| 演示读数不可跨端复现 | Dart 用 `Random(20261020)` 生成演示序列，Kotlin 的 `kotlin.random.Random` 算法不同，逐值必然不一致。对齐口径只覆盖**结构与字段语义**，不承诺数值相同；导出对照按结构 diff 判定 |
| 基准源码缺口 | 本地 `app_v2` 是 v0.8.3+11 的不完整快照（`ble_transport.dart` 引用了全仓未定义的 `obfuscate()`，且缺 v0.8.6+14 的 `read_queries.dart` 等）。XOR 0x55 行为以取证笔记为准；涉及 v0.8.6+14 新特性的工单（03/04/07/09）等 `app_v2_latest` 拷入后再放行 |
| 启动包体积 | 预期显著小于 21.7 MB，但需实测，不预先承诺具体数值 |

## 12. 实施阶段（每阶段以构建 + 测试为闸门）

1. 工程骨架：Gradle wrapper、三模块、版本目录、空 Compose 壳可构建。
2. `:protocol` + 全部协议测试。
3. `:core` + 解码/会话/分析/导出/演示测试。
4. `:app` 链路层：扫描、权限、GATT 队列、订阅、轮询、锁车、持久化、导出落盘。
5. Compose UI：主题、组件、三个主页面、全部二级页、资源与图标。
6. release 构建 → vivo 安装 → 验收清单逐项记录数字与现象 → README 验证状态。
