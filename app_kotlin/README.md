# iWalk 伴侣 · Kotlin 原生版

Jetpack Compose 重写，模块依赖固定为 `:app → :core → :protocol`。
当前版本 `0.2.0`（versionCode 2），对齐 `app_v2` v0.8.3 的已证实行为。

## 构建

环境：JDK 17.0.13、Android SDK 36、Gradle 8.13 wrapper、AGP 8.13.2。
SDK 路径写在 `local.properties` 的 `sdk.dir`。

```bash
./gradlew --offline :protocol:test :core:test :app:testDebugUnitTest
./gradlew --offline :app:lintDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

## 交付产物

`../downloads/iwalk-companion-0.2.0-arm64-release.apk`

| 项 | 值 |
|---|---|
| 字节数 | 9,415,864 B |
| 对比旧 Flutter 包 | 21,732,801 B，减少 12,316,937 B（-56.67%，约为旧包的 1/2.31） |
| SHA-256 | `2fb7ac641fb7083af67bfc1c1b42441398704ccbf395c55f1569026c6fbd4b0f` |
| ABI | `arm64-v8a`（仅此一个） |
| SDK | minSdk 24 / targetSdk 36 / compileSdk 36 |
| 签名 | v1 + v2；证书为 Android Debug（`CN=Android Debug`），仅供 sideload |
| 权限 | `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`，API ≤ 30 追加 `BLUETOOTH`、`BLUETOOTH_ADMIN`、`ACCESS_FINE_LOCATION`；**无 `INTERNET`** |

签名说明：AGP 8.13 对 minSdk ≥ 24 的 release 包只写 v2，即使 `enableV1Signing = true`
也不落 v1。交付前用 build-tools 35.0.0 的 `apksigner` 追加 v1 再归档：

```bash
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias androiddebugkey --min-sdk-version 24 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false \
  --out ../downloads/iwalk-companion-0.2.0-arm64-release.apk \
  app/build/outputs/apk/release/app-release.apk
apksigner verify --verbose --min-sdk-version 21 <交付包>   # v1/v2 均为 true
```

`apksigner verify` 默认按 APK 的 minSdk 24 校验，会直接跳过 v1 并显示 false；
用 `--min-sdk-version 21` 才能看到 v1 校验结果。v1 仅对 API < 24 有意义，按验收要求保留。

## 验证状态（2026-11-12）

| 项 | 结果 |
|---|---|
| JVM 测试 | 271 个，0 失败（2026-11-12 晚复跑 `:protocol:test :core:test :app:testDebugUnitTest`，XML 汇总 271 tests / 0 failures：protocol 44 / core 94 / app 133） |
| lint | 0 errors，14 warnings（图标形状 5、UseKtx 3、其余 6） |
| `assembleRelease` / `assembleDebugAndroidTest` | 成功 |
| 演示会话导出 vs Flutter 演示导出 | 键树 0 差异；仅 `app` 与 raw 行 `source` 的值不同 |
| APK 体检 | 见上表 |
| 真机安装（vivo V2241A / Android 16） | `adb install -r` 成功，0.1.0 → 0.2.0，未卸载旧包 |
| 冷启动 `am start -W` | `TotalTime` 415 / 281 / 286 / 264 ms，无黑屏、无崩溃 |
| 真机演示流程 | 数值刷新、锁滑杆模拟发送并回中、分析页有帧组/时间线 |
| 真机导出 | 244 帧 = 244 raw 行 = 组内 244 = 时间线 244；1 条 simulated 指令 |
| 标定导入/清除 | 导入后 1 组，重启保留；清除后 0 组，重启仍为 0 |
| 锁车开关 | 默认关闭；风险确认后开启并跨重启保留；关闭无需确认 |
| 权限拒绝与空扫描 | 系统弹窗“查找与连接附近的设备”；拒绝后文字原因+排查提示、不崩溃；蓝牙开启后扫描 15 s 超时显示“没找到 iWalk 设备” |
| 文件管理器可见性 | vivo 文件管理器可浏览到 `Android/data/…/files/Documents/iwapp_exports/`，两个导出文件可见 |
| 真车 BLE（2026-11-12 追加） | `MiniRobot:IWK1779887` 命中并连接；`g1NordicUart`、`6e400002/6e400003`、`mtu_now 23`；官方 2 类轮询实测回包 96.1% / 74.1%；断开重连、关蓝牙掉线、标定读数均通 |
| 真车导出（2026-11-12 追加） | 两次导出 `raw_packets` = raw 行数（2474、12802），组内之和 = `frames_total`（874、4522），`commands_sent` 为空；长会话时间线按上限截断到 1522 |
| 出站唯一性（HCI 核对） | 1843 条 ATT Write 全部校验通过、去重只有 `55aa030a011426b7ff` 与 `55aa030a01bc0233ff` 两帧，无锁帧/写类帧 |

真机证据：`../captures/device_acceptance/`（截图、UI dump、导出包）、
`../captures/device_acceptance/real_vehicle_20261112/`（真车截图与 UI 树）、
`../captures/20261112_realvehicle/`（btsnoop + 解帧结果）。真车部分完整记录见
`docs/acceptance-2026-11-12-real-vehicle.md`。

## 未实测（明确留待后续）

- 车辆侧断电/远距离断链（本轮只测了手机侧关蓝牙）。
- 运动工况下的速度读数与标定（也因此未实测「速度 >1 km/h 阻止发送」闸门本身）。
  步骤已写成可执行手册：`../analysis/procedure_speed_calibration.md`；闸门真车验证方案（含「拦截时 HCI 必须零写入」这条关键证据）见 `docs/lock-gate-real-vehicle-plan.md`。
  闸门的**逻辑层**已离线证明（`SpeedCalibrationGateTest`：标定解出 8.0 km/h 时 `setLock` 调用次数为 0，
  归零后放行），真车剩下要验的只是「速度读数是否真的到达 `snapshot`」。
- iOS。

## 已知限制

- 真车读数依赖导入的标定；未标定字段显示“—”/“未标定”，不猜测单位或语义。
- 演示标定只注入演示会话，永不写入用户存储。
- 证据时间线保留最近 2000 条（超出丢最旧 500）；帧与原始行保留全量。
- 无前台服务/后台保活；无账户、云同步、广告或联网逻辑。
- 锁车控制默认关闭；开启需风险确认；**速度必须有读数且 ≤1.0 km/h 才允许发送**
  （未标定/未知也阻止，不再静默放行）；写入成功只显示「车端状态未确认」。
  锁/解锁帧已于 2026-11-12 真车实测有效（E66，各发一次、无回执），见
  `docs/acceptance-2026-11-12-real-vehicle.md` §11。
- 因此本车（速度字段尚未标定，U03）当前**无法使用锁车控制**：滑块禁用并显示
  「速度未标定，无法证明车辆静止，已阻止操作」。这是 E66 后的刻意取舍。
- 导出文件落在应用外部文档目录 `Documents/iwapp_exports/`，卸载随应用删除。
- 导出证据只记录入站通知（`raw`/`frames`）与锁帧尝试，不含官方查询的发送计数
  （对齐 Flutter 0.8.3；0.8.4+ 才有 `read_query.sent_by_*`）。要证明「只发了两条查询」
  需手机侧 HCI 日志，见 `docs/acceptance-2026-11-12-real-vehicle.md` §3。
- 长会话里 `evidence_timeline` 会被上限截断（2000 条、丢最旧 500），
  此时 `frames_total` 大于时间线条数是预期行为；分析页会显式提示截断及当前保留条数。
- 连接失败只自动重试一次，且仅限瞬时失败（初始化被中断、`connectGatt` 返回 null、
  且首次失败在 5 s 内）；超时类失败不重试，由用户再点一次。失败文案已换成人话
  （`ble/LinkErrorText.kt`），重试过会注明「已自动重试 N 次仍失败」。
