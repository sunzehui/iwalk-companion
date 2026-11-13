# Release 验收记录 · 2026-11-11

工单 10。环境：macOS 14 / JDK 17.0.13 / Gradle 8.13 / AGP 8.13.2 /
Android SDK 36 / build-tools 35.0.0。真机：vivo V2241A（Android 16 / SDK 36，
1080×2400），2026-11-12 通过 ADB 完成安装与交互验收（见 §5）。

## 1. 构建与测试

```bash
./gradlew --offline :protocol:test :core:test :app:testDebugUnitTest
# BUILD SUCCESSFUL
# protocol 44 / core 87 / app 99 = 230 tests, 0 failures, 0 errors

./gradlew --offline :app:lintDebug :app:assembleRelease :app:assembleDebugAndroidTest
# BUILD SUCCESSFUL
# lint: 0 errors, 14 warnings
#   IconLauncherShape 5 / UseKtx 3 / UseTomlInstead 1 / UnusedAttribute 1 /
#   RedundantLabel 1 / ObsoleteSdkInt 1 / MonochromeLauncherIcon 1 / ChromeOsAbiSupport 1
```

`assembleRelease` 与 `assembleDebugAndroidTest` 均产出 APK；本机无模拟器/AVD。

## 2. APK 体检

命令：

```bash
aapt2 dump badging app/build/outputs/apk/release/app-release.apk
aapt2 dump permissions app/build/outputs/apk/release/app-release.apk
apksigner verify --verbose --print-certs <apk>
unzip -l <apk> | grep 'lib/'
```

| 检查项 | 实测 |
|---|---|
| package / version | `com.iwalk.companion` / 0.2.0（versionCode 2） |
| minSdk / targetSdk / compileSdk | 24 / 36 / 36 |
| native-code | `arm64-v8a`（APK 内仅 `lib/arm64-v8a`） |
| 权限 | `BLUETOOTH_SCAN`（neverForLocation）、`BLUETOOTH_CONNECT`；API ≤ 30 追加 `BLUETOOTH`、`BLUETOOTH_ADMIN`、`ACCESS_FINE_LOCATION`；**无 `INTERNET`** |
| 签名证书 | `CN=Android Debug`，RSA 2048，SHA-256 `251a8a…d66016` |
| 签名方案 | v1 + v2（`apksigner verify --min-sdk-version 21` 两项均 true） |
| 字节数 | 9,415,864 B |
| 旧 Flutter 包 | 21,732,801 B；减少 12,316,937 B（-56.67%，约 1/2.31） |
| SHA-256 | `2fb7ac641fb7083af67bfc1c1b42441398704ccbf395c55f1569026c6fbd4b0f` |

签名备注：AGP 8.13 对 minSdk ≥ 24 只写 v2（`signing-config-versions.json` 虽为
`enableV1Signing:true`，产物仍无 `META-INF/*.SF`）。交付包在归档前用 build-tools
35.0.0 `apksigner` 以 v1+v2 重签；`apksigner verify` 默认按 minSdk 24 跳过 v1，
故用 `--min-sdk-version 21` 复核。

交付路径：`../downloads/iwalk-companion-0.2.0-arm64-release.apk`。

## 3. 导出结构与 Flutter 演示导出对照

为做同条件对照，分别用 Flutter `app_v2` 与 Kotlin 版启动演示会话、发送一次关锁、
导出证据包；两侧均为 3 帧以上后导出，临时验收脚本运行后已删除。

- Flutter：`../captures/acceptance_flutter_demo/iwapp_exports/`
- Kotlin：`../captures/acceptance_kotlin_demo/iwapp_exports/`

结构 diff（键树，不比较数值/时间）：

| 对象 | 结果 |
|---|---|
| 顶层键 | 完全相同（schema/app/exported_at/session/transport/frames_total/raw_packets/frame_groups/evidence_timeline/commands_sent/note） |
| `transport` 键 | 完全相同 |
| `frame_groups[*]` 键 | 完全相同 |
| `evidence_timeline[*]` 键 | 完全相同（含 `values.*.mapping`） |
| `commands_sent[*]` 键 | 完全相同；两侧 hex 均为 `55aa040a037001007dff`，`vehicle_state` 均为 `unconfirmed` |
| raw JSONL 键 | 完全相同（13 个键，含 null 的 `service_uuid`/`char_uuid`） |
| `note` / `schema` | 逐字相同 |
| 唯一差异 | `app` 与 raw 行 `source` 的值：`iwapp_v2` → `iwalk_kotlin` |

数值差异仅来自演示帧的时间推进：Flutter 28 帧 / Kotlin 22 帧（10 ms tick 的启动
时机不同），因此 `frames_total`、偏移统计与 payload 数值不同，属于预期。

## 4. 工单 07 / 09 覆盖

| 验收条目 | 证据 |
|---|---|
| 07 合法标定导入后会话内立即重解码 | `SettingsAndLockTest.import immediately decodes retained real notifications and clear removes values`（真车假链路，导入后速度从空变为 1.0） |
| 07 非法 JSON 拒绝且不破坏已有标定 | 同文件 `invalid import preserves calibration…`、`overflow and fractional calibration cannot replace valid settings` |
| 07 重启保留 / 清除 / 演示标定不写入 | 同文件首测：新建 controller 仍见标定；`clearCalibration` 后 demo 读数不受影响；`DemoProfile` 从不进入 `CalibrationStore` |
| 07 诊断页 12 项 + 更新时间/原始值 + 解析诊断 + 出站指令 | `SettingsFlowsTest.diagnostics lists all twelve…`；`diagnostics names waiting stale and uncalibrated states in words`（过期 1 / 等待 4 / 未标定 7） |
| 07 关于与边界：版本、传输权限、锁车状态、待采集项 | `SettingsFlowsTest.about section states version permissions lock state and pending collection` |
| 09 默认关闭、风险确认、持久化 | `SettingsFlowsTest.lock switch requires confirmation…`、`SettingsAndLockTest.lock preference survives controller recreation` |
| 09 守卫矩阵 | `SessionIntegrityTest.lock policy blocks real control but allows connected simulation`：未开启/断开/不支持/±1.01/NaN 阻止；1.0 与未标定速度放行；演示放行开关与速度但仍需连接 |
| 09 单次写入、不重试、不回读 | `SettingsAndLockTest.demo single flight…`（并发第二次返回“发送中”，只记 1 条）；`LockSliderTest.complete gesture emits once…` |
| 09 成功只显示未确认 / 演示只记 simulated | `sendLock` 返回值与 `LockCommandRecord`；导出 `commands_sent` 含 `simulated:true`、`vehicle_state:"unconfirmed"` |
| 09 手柄回中 | `LockSliderTest` 断言 `ProgressBarRangeInfo.current == 0f` |

## 5. 真机验收（vivo V2241A / Android 16）

设备 `10AD1Z0Q1Y001R9`，1080×2400。全部操作经 `adb shell input` 与
`uiautomator dump` 完成，截图存于 `../captures/device_acceptance/`。

### 5.1 安装与启动

- `adb install -r ../downloads/iwalk-companion-0.2.0-arm64-release.apk`：
  `Success`，版本从 0.1.0 升到 0.2.0（versionCode 2），未卸载旧包。
- `am start -W -n com.iwalk.companion/.MainActivity`：`Status: ok`，
  `TotalTime` 依次 415 / 281 / 286 / 264 ms（首次含冷缓存，后三次为
  force-stop 后冷启动）。
- 肉眼：启动后直接显示首页“车辆与链路”卡片，无黑屏（`01_home.png`）。

### 5.2 演示会话

- 进入演示后数值与帧计数随 tick 刷新，仪表区域持续更新（`02_demo.png`）。
- 拖动锁滑杆一次：显示“关锁指令已模拟发送 · 未操作真车”，滑杆回中
  （`03_demo_lock.png`）；单次手势只发一条由 `LockSliderTest` /
  `SettingsAndLockTest` 在 JVM 侧断言。
- 分析页：帧数、帧组（cmd/a1/a2、载荷长度、偏移）与证据时间线均有内容
  （`04_analysis.png`）。
- 导出：文件落在 `Documents/iwapp_exports/`，复制到
  `../captures/device_acceptance/export/` 复核——
  `frames_total` 244、raw JSONL 244 行、`frame_groups[0].frames` 244、
  `evidence_timeline` 244 条，四者相等；`commands_sent` 仅 1 条
  `55aa040a037001007dff`，`simulated:true`、`vehicle_state:"unconfirmed"`；
  `transport.id` 为 `demo`、`is_demo:true`（`05_export.png`）。

### 5.3 标定导入 / 持久化 / 清除

- 通过系统文件选择器导入 1 组标定后，标题显示“1 组”、条目
  “dev · 1 项映射 / 未提供证据说明”（`07_calibration_imported.png`）。
- force-stop 后重启，设置页仍为“1 组”（`window_after_restart.xml`）。
- 点“清除标定”弹确认框（“清除本机标定？”/“取消”“清除”）；确认后变为
  “0 组”“尚未导入标定，真实读数显示 —”，再次 force-stop/重启仍为 0 组。
- 清除确认框写明“原始通知与证据保留，演示读数不受影响”；该行为由
  `SettingsAndLockTest` 断言（清除后演示读数仍来自 DemoProfile）。

### 5.4 锁车开关

- 全新安装后开关 `checked=false`，关于区写“锁车状态：默认关闭”。
- 点开关弹出风险对话框（标题“开启实验性锁车控制？”，正文含 CMD 0x0A
  静态推导、单次发送不重试不回读、>1.0 km/h 阻止、速度未知不能证明静止）；
  点“保持关闭”后开关仍 `checked=false`，关于区文案不变（`09_lock_confirm.png`）。
- 再点开关并选“我已了解，开启”：开关 `checked=true`，关于区变为
  “锁车状态：已开启（实验性）；写入成功也只表示数据已交给链路，车端状态未确认。”
- force-stop 后重启：设置页开关仍 `checked=true`（持久化生效）。
- 再关闭开关：无需对话框，立即 `checked=false`，关于区回到“默认关闭”。

### 5.5 权限拒绝与空扫描

- `pm revoke` 撤销 `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` 后重启（`TotalTime`
  313 ms），首页正常渲染（`10_perm_revoked_home.png`）。
- 进入扫描页弹出系统对话框““iWalk 伴侣”请求查找与连接附近的设备”，
  按钮“允许”“禁止”（`11_perm_dialog.png`）。
- 点“禁止”：不崩溃，扫描页显示“没找到 iWalk 设备”与
  “蓝牙未开启或无权限（需“附近的设备”权限）”，下方 4 条排查提示，
  保留“重新扫描”（`12_perm_denied.png`）。
- 退出扫描页再进入会再次请求权限（第二次对话框按钮变为
  “禁止且不再询问”）；选“允许”后权限为 granted。
- 此时系统蓝牙仍关闭（`settings get global bluetooth_on` = 0），同一句
  提示同时覆盖“蓝牙关闭”与“无权限”两种原因。
- `svc bluetooth enable` 后点“重新扫描”：显示“停止扫描”“正在扫描白名单设备…”
  “正在寻找车辆…”（`15_scanning_with_bt.png`）；15 s 超时后回到
  “没找到 iWalk 设备”与提示列表，无崩溃（`16_scan_done_none.png`）。
- 观察：扫描页内的“重新扫描”不会重新申请权限，退出再进入才会；与
  Flutter 0.8.3 行为一致，提示文案本身可执行，列为已知交互限制。

### 5.6 文件管理器可见性

vivo 文件管理器路径：手机存储 → Android → data → com.iwalk.companion →
files → Documents → `iwapp_exports`，可见
`raw_demo-…jsonl`（68.08 KB）与 `evidence_demo-…json`（421 KB）
（`19_filemanager_exports.png`）。旧条目只做过 `adb shell ls`，本次补上肉眼走查。

### 5.7 未覆盖

- 真车 BLE：扫描命中、GATT 连接、110/1010 ms 轮询、回包率、断链重连——
  验收时滑板车未上电，无法建立链路；相关逻辑仅有 JVM/Robolectric 与
  官方报文采集依据。
- 锁帧的机械效果与车端确认（应用不读回、不渲染锁态）。
- iOS。
