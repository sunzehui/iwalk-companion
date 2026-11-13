# iWalk 任务进度文档

> 更新：2026-11-12（真车验收 + 两处连接体验修复）。总目标见 `doc.md`；证据编号（E/U）见 `analysis/evidence_log.md`。
> 原则：已确认事实 / 合理假设 / 未验证猜测 / 未知 严格区分，未验证的一律不写成完成。

## 本轮补记：连接失败重试与断链确认框（2026-11-12 晚）

- 真车窗口期观察到的两条可用性问题已修，未改动任何协议/写入路径：
  1. 连接瞬时失败（`连接初始化已中断` / `connectGatt 返回 null`，且首败在 5 s 内）自动重试一次（800 ms 后），
     仍失败才报错；失败文案改由 `ble/LinkErrorText.kt` 生成，重试过补「· 已自动重试 1 次仍失败」。
     超时类失败（15 s 建链 / 15 s 服务发现）不重试。
  2. 断链后确认框为「退出已断开的会话？/ 链路已断开，不会再有新帧 …/ 退出」；已连接仍是「断开当前会话？/ 断开」。
- 真机复核（同一台 vivo V2241A）：蓝牙重启后首次连接**一次成功**（9 s / 70 帧）；
  连接瞬间关蓝牙得到「系统没有返回 GATT 连接 … · 已自动重试 1 次仍失败」（证明两次尝试都发生）；
  另一次服务发现被打断得到「已连上，但 15 秒内没有拿到服务列表」（不重试）。截图
  `captures/device_acceptance/fix1_connect_error*.png`、`fix2_exit_dialog.png`。
- 测试：新增 `ConnectRetryTest`（4 条），`DeviceScreenTest` 增一条已连接态确认框用例；全仓 259 条 JVM 测试通过。

## 本轮补记：时间线截断提示（2026-11-12 晚）

- `SessionManager` 新增 `isTimelineTruncated`：证据时间线按 2000 条上限裁过即为真（不靠帧数反推），
  经 `DeviceUiState.timelineTruncated` 透传到界面。
- 分析页「证据时间线」下方新增警示文案（保留条数 / 已收帧数 / 导出同样截断），
  并把原来那句「完整时间线随导出写入 evidence_timeline」在截断时换成截断版——原文案在截断后是错的。
- 测试：`SessionManagerTest` 新增「累计 2200 帧 → 标记截断、`frames` 仍 2200 条」；
  `AnalysisTabBodyTest` 新增截断态文案用例（与旧脚注互斥）。全仓 261 条 JVM 测试通过。


## 本轮补记：Kotlin 原生版真车验收（2026-11-12）

- 滑板车上电，vivo V2241A 上首次跑通真车链路：`MiniRobot:IWK1779887`（`EE:F6:9B:2C:98:42`）
  4 s 内命中 → `g1NordicUart` / `6e400002` 写 / `6e400003` 通知 / `mtu_now 23`。
- **出站唯一性首次用 HCI 独立证明**：1843 条 ATT Write 全部校验通过、去重只有
  `55aa030a011426b7ff`（0x14，1650 次）与 `55aa030a01bc0233ff`（0xbc，193 次），
  另仅 1 条 CCCD 订阅写（handle 0x000f ← `0100`）；0 锁帧、0 写类帧。
- 回包率（HCI 口径，197 s）：0x14 1585/1650 = **96.1%**，0xbc 143/193 = **74.1%**，
  入站 1728 帧 0 坏校验；0x14 帧 46 B 在 MTU 23 下拆 20+20+6，与既有分片结论一致。
- 真车读数：导入 E64 电量标定后设备页/诊断页显示 69%（原始值 69.0 · off 28 · u8），
  其余字段仍 `未标定`；HCI 同期 off28 仅 68/69，off26–27 在 116–178 跳动（佐证 E64）。
- 导出两次均通过快照不变量（2474→874、12802→4522，`commands_sent` 空）；
  长会话时间线按 2000 上限截断（1522），非缺陷。新建
  `analysis/tools/evidence_v1_check.py` 做 `iwapp-evidence-v1` 校验，两份真车包 `RESULT: OK`。
- 工具链修复：`decode_capture.py`/`evidence_report.py` 去掉 `/root/iwalk` 硬编码路径；
  `decode_capture.py` 支持线上 XOR 0x55 自动识别（此前流水线 `decoded_frames 0`）；
  `btsnoop_att.py` 记录出站 `# WRITE`。
- **锁车真车实测（2026-11-12 晚，E66）**：Kotlin 版在操作者确认停稳后真车发送关锁/解锁各 1 帧，**车辆均实际响应**；HCI 侧（`captures/20261112_locktest/`）确认两帧各恰好写 1 次、无重试，时间与应用记录差 2–3 ms；仍无车端回执。**缺口已修**：速度未标定（`speedKmh == null`）此前放行，现改为默认阻止「速度未标定，无法证明车辆静止，已阻止操作」——代价是本车（速度未标定，U03）暂时无法使用锁车控制，属刻意取舍。详见 `app_kotlin/docs/acceptance-2026-11-12-real-vehicle.md` §11。
- 未覆盖：车辆侧断电、运动工况速度、iOS、锁状态协议回读（协议本身无回执）。
- 记录：`app_kotlin/docs/acceptance-2026-11-12-real-vehicle.md`；
  报文采集 `captures/20261112_realvehicle/`；截图 `captures/device_acceptance/real_vehicle_20261112/`。

## 本轮补记：P2 离线电量标定（2026-10-21，E65）

- 已依据 E64 **重建** `downloads/iwk1779887-calibration-partial-v1.json`，仅电量，非找回旧原文件。
- Python 电量配置回归 7/7、协议回归 7/7：官方 HCI 2573 帧全部校验通过，其中主状态 2368 条为 83；E63 原始通知 61 帧全部通过，其中主状态 58 条为 79；旧派生记录 99 样本回放通过，但缺对应原始日志，未重新验证其校验和。
- **本轮未运行 Flutter/Dart、未连接真车、未验证手机导入、未重建 APK。** 历史 0.8.6 验收不能视为当前工作区源码已对齐。
- 下一步：按报告导入电量配置做手机验收；按速度采集清单提供官方录屏与同机 HCI，速度/电压/温度保持未知。
- 报告：`analysis/09_battery_calibration_replay.md`；采集：`analysis/procedure_speed_calibration.md`；记录模板：`captures/TEMPLATE_speed_observations.csv`。

## 一、阶段总览

| 阶段（doc.md） | 状态 | 说明 |
|---|---|---|
| 阶段〇 环境与素材 | ✅ 完成 | APK、Flutter 3.35.4、Android SDK 35、JDK17、tshark 就绪（国内镜像配置见 05 报告） |
| 阶段一 APK 静态分析 | ✅ 完成（静态路线内） | 帧格式/校验和/ACK 规则/命令词汇表已从协议逻辑与用户数据确认；GATT UUID 已由未加壳厂商 APK + 真机实测闭合（U01，E34） |
| 阶段二/三 真机采集与解析 | 🟡 U01 关闭（E34）；**U02 关闭（E47+E52+E57：0x0d 应答目录 20 子帧 + 官方主界面轮询 2 类/周期实证；18 类为 app_v2 自选诊断集，E58）**；**推送模型修正（E52）：车端为请求/应答式，非自主推送——旧“主动推送”结论源于 hci_quicklook 漏解析 ATT Write Command 0x52,已修复**；U03 待骑行/读数对照；**真车通知首证 E55**；**E57 官方 App 主界面实测：仅 0x14@0.11s + 0xbc@1.01s，0x14 off28=83% 与截图一致、锁帧与 E40 golden 一致、0 主动推送（0/2573）**；**E58 归属更正：E52/E54 的“官方 18 类”实为 app_v2 自选诊断集**；**E60 真车双模式验收：官方模式应答仅 0x14/0xbc（1041/1041 校验通过，0x14 回包≈95%）；诊断模式 18 类中 0x0b/0x0c 零回包，主状态回包降至≈41%；0x1d 恒 c818 与 0x14 off18–19 一致，电压候选获独立佐证；0x14 off36–37 非静置时会变化**；**E62 二次短程复测：官方 17.8s（168/168 校验，0x14≈95%、0xbc≈78%，off28=79%）、诊断 5.3s（41/41，0x0b/0x0c 仍零回包，0x14≈22%），快照计数一致**；**E63 0.8.6 真机验收：61/61 校验，sent_by_register 求和==67==sent_this_session，0x14 58/60=96.7%，快照不变量通过** | `captures/20261020/`、`captures/20261021/session.md`、`captures/20261021_official/`（btsnoop+截图+logcat+session.md）、`captures/app_output/`、`captures/app_output2/`（v0.8.4 真车双模式导出）；工具 `analysis/tools/hci_quicklook.py`（双流检测 + 操作码修正 + H4 自动判别） |
| 阶段四 字段/命令语义关联 | 🟡 工具与 App 侧闸门都已就绪，缺一次移动采集 | 电量已落盘（E64）；速度判定器 `analysis/tools/speed_calibration.py` 与 15 条合成回归（E67）已就位，等真实读数；锁闸门判定器 `analysis/tools/lock_gate_check.py` 与 12 条回归（E68）已就位，等标定；App 侧「工具产出的标定文件 → 解码 → 闸门 → `setLock` 零调用」已由 `SpeedCalibrationGateTest` 与夹具漂移测试证明（E69，JVM 271 项 0 失败）；`payload_inspector.py` 仍只给字节视角 |
| 阶段五 APP 开发 | 🟢 双工程：app/(v0.1 取证) + app_v2/(v0.8.6 伴侣应用，MiniRobot 只读信息层 + 离线采集对照 + 只读安全说明 + 静态界面预览 + 实验性锁车 + 数据与诊断 + **只读查询调度器**) | app_v2 静态验证通过（analyze 0 / test 66 全绿）；**v0.8.6+14 release/debug 双包：E50 XOR-0x55 解码 + E54 只读查询调度器 + E56 导出出口 + E59 官方节奏默认 + E60 导出快照一致性修复/app_version + E61 逐寄存器发送计数/诊断文案**；签名/ABI/ZIP/资源已验（E45/E50/E54/E56/E59/E60/E61）；闭环与双模式均已真机首证（E55/E60）；**官方 2 类为默认（0x14@110ms + 0xbc@1.01s），18 类为默认关闭的诊断轮询（E60 实测 16 类有回包，主状态回包率约 95%→41%）**；待骑行采集标定 U03 |
| 阶段六 发布 | 未开始 | 依赖阶段五 |

## 二、已完成并验证（均有记录）

1. **协议核心（已确认事实）**
   - 帧：`55 AA | L | CMD | A1 | A2 | payload(L−2B) | chk_lo chk_hi`，总长 L+6
   - 校验：`(~uint16(sum(bytes[2..L+3]))) & 0xFFFF` 小端
   - ACK 规则：`A2 = CMD ^ 0x05`；发送词汇表 {0x04,0x06,0x0A,0x0B,0x0C,0x20,0x22,0xF1,0xFF}
   - 握手黄金帧：`55aa02ff0103fafe`
   - **线上编码：真车通知整帧 XOR 0x55（线上头 `00 FF`），4032/4032 校验全对（E46）；protocol_dart `StreamDecoder` 已自动双流检测（app_v2 按通知特征各建一个解码器）**
   - 证据：E1–E14（evidence_log.md）、protocol_findings_table.md
2. **离线采集→解帧工具链**（E17）
   - `analysis/tools/run_pipeline.sh`：btsnoop_att.py → decode_capture.py → frames.jsonl
   - btsnoop 记录布局经 tshark 独立交叉验证；`test_pipeline.py` 回归 PIPELINE OK
   - 未识别帧保留原始字节，不丢弃不伪造
3. **APP 骨架静态验证**（E18）
   - `flutter analyze` 0 issues；`flutter test` 10/10；protocol_dart `dart test` 6/6
   - `app/verify.sh` 一键复跑，RESULT: ALL_PASS
4. **安全边界落地（对应 doc.md 禁令）**
   - `app/` v0.1 仍为纯只读：scan/connect/discover/read/subscribe；`write()` 抛 StateError，未接 UI
   - `app_v2` 默认只读遥测；实验性锁车控制**默认关闭**，开启并风险确认后只允许 E40 的两条固定 G1 帧，单次写入、无重试/探测/通用写接口
   - 不发送 0x04+A2=0x1E 一键校准（E15）、不发送 OTA 分块写（E16）；单测含禁止形态守卫
5. **真机采集规程**：`analysis/procedure_ble_capture.md`（nRF Connect 与 HCI snoop 两条路径）
6. **app_v2 v0.3.0 界面重构与最终包**（E39）
   - 按 `~/minirobot` 九号规范改为 `设备 / 分析 / 设置` 三 Tab：超大速度数字、三等宽指标格、状态徽章、底部技术面板、单一主操作
   - 新增设置页（链路/标定/导出/关于）、字段来源面板（原始值→换算→结果，可追溯 profile/offset/时间）
   - 演示标定完全隔离；修复跨会话监听串扰、连接后镜像滞后误断连、停止会话挂起、清空标定误伤演示
   - `flutter analyze` 0 issue；`flutter test` 27/27；arm64-only APK 76,793,535 B，SHA-256 `84330aeb…e5db9`，v1+v2 签名、ZIP OK
   - **尚未真机安装/操作验证**（本机无手机/蓝牙硬件），视觉验收仅覆盖 widget 测试
7. **app_v2 v0.4.0 车辆图 + 实验性滑动锁车**（E40/E41）
   - 首页中间加入平衡车示意图（`assets/images/hoverboard.png`，标注“示意图 · 非实拍”）；连接后显示双向滑动控件：右滑关锁、左滑解锁，手柄回中，不显示持久“已锁”位置
   - 锁帧来自厂商 binary 静态推导：关 `55aa040a037001007dff`、解锁 `55aa040a037101007cff`（E40）；未真机验证
   - 默认关闭；设置页风险确认后持久化（`lock_control_enabled.v1`）。真实发送仅限 G1 `6e400002` 且具备 write 属性，单次、无重试；已标定速度 >1.0 km/h 阻断，未标定速度时依赖“停稳、无人站立”提示
   - 演示会话可模拟滑动并记录 `simulated` 命令，但绝不写设备、不伪造 ACK/锁状态；真实写入成功后只显示“指令已发送 · 车端状态未确认”
   - 导出 evidence JSON 新增 `commands_sent`（时间、action、hex、ok、simulated、error、`vehicle_state: unconfirmed`）；raw JSONL 仍仅入站
   - `flutter analyze` 0 issue；`flutter test` 31/31；clean arm64 构建 525.0s；APK 77,512,856 B，SHA-256 `d6af9bca…a9765`，0.4.0 (4)，v1+v2、ZIP OK，图片已打包
   - **锁帧真机效果与车端锁状态回读均未验证（U06）**；本机无手机/蓝牙，未做真机视觉验收
8. **app_v2 v0.5.0 设置内“数据与诊断”**（E42）
   - 按 `/root/iwalk/设计参考.md` 的信息架构，将技术数据收纳为设置内二级页：车辆基础信息 / 实时状态 / 解析诊断 / 出站指令
   - 10 个字段逐行显示当前值、单位、各自更新时间、原始值与映射；未标定、等待匹配帧、数据过期分别有文字状态，不填默认值
   - 车辆信息中固件版本等不可读项显式写“未获取”；设备名与序列号掩码显示；锁车记录保留失败原因与“车端状态未确认 · 无回读”
   - 保留既有深色 Ninebot 工具型色板，采用参考文档的信息结构与状态规则，不采用其浅色建议值
   - `flutter analyze` 0 issue；`flutter test` 32/32；clean arm64 构建 524.2s；APK 77,532,722 B，SHA-256 `d58877db507877a80d229cf807331ff0bf79010c012ab2584dc04c990d4a7cdd`，0.5.0 (5)，v1+v2 签名、ZIP OK，图片已打包
9. **app_v2 v0.6.0 MiniRobot 只读信息层 + 启动外观**（E43）
   - 按 `minirobot_feature_inventory.md` 只复刻只读信息层：`workMode`/`warningCode` 字段、首位原始码胶囊（0 不等于无故障）、被动的证据时间线（随导出写入 `evidence_timeline`，最多 2000 条）、设备与链路中的“本次会话能力”、分析页字段字典
   - 新增 `故障与术语参考` 页：MiniRobot 11.3.8 的 hxz1–hxz13、按域分组长文案、术语与版本组件；明确不是诊断、数值映射未验证、不主动查询；无控制/校准/OTA/账户/NFC/遥控/地图入口
   - 启动外观：teal 深色自适应图标、深色 `LaunchTheme`/`windowBackground`、Android 12 `windowSplashScreen*` 与各密度 splash mark；改用 arm64 release/AOT 包（debug JIT 的首帧等待显著缩短，但**未在手机计时**）
   - 验证：`flutter analyze` 0 issue；`flutter test` 36/36（新增原始码、会话能力、时间线、字段字典、参考页测试）；release APK 21,731,845 B，SHA-256 `509c7206dfe11a2317d075168ca3854f1f2c44130449dbb2aed87f605f3c70b4`，0.6.0 (6)，arm64-only，v1+v2，`unzip -t`/`zipalign -c 4` OK，splash/icon 资源已打包
   - 构建取舍：本机 1.7 GB 内存下 R8 25 分钟未完成、release lint 卡在 Google SDK 索引，故 `build.gradle.kts` 关闭 R8 收缩与 `lintVitalRelease`；仍是 AOT release，未做代码收缩，使用 debug keystore 签名，仅适合自用 sideload
10. **app_v2 v0.7.0 离线采集对照 + 只读与安全页**（E44）
   - 离线分析新增采集 A/B 对照：按 `(CMD,A1,A2)` 分组，比较各偏移的最小/最大/去重字节数、载荷长度稳定性和仅单侧出现的帧组；拒收块与不完整帧计数分别保留；报告显式声明“不保证逐帧时间对齐、偏移差异不能单独证明字段含义”，界面不出现字段语义标签
   - 新增设置内“只读与安全”页：会做什么/永不执行清单/锁车唯一例外/解码标定≠车辆校准/无账户云同步自动上传；不改变任何运行时行为
   - 修正“本次会话能力”语义：只列已配置映射中本次未收到的字段（`已配置未收到`），不再暗示车辆支持矩阵；未配置字段不推断
   - 标定导入提示改为从 `TelemetryField` 枚举生成，补齐 `workMode`/`warningCode` 的可用字段说明
   - 验证：`flutter analyze` 0 issue；`flutter test` 44/44（新增对照算法 6 项、离线页 A/B UI、只读安全页）；release APK 21,731,982 B，SHA-256 `e05522f189d55483ac04d1a4fc1a5d7647cc38df8ec9ecc050d22e2ced17f932`，0.7.0 (7)，arm64-only，v1+v2，`unzip -t`/`zipalign -c 4` OK，splash/icon/示意图已打包
   - 构建 128.5s（增量，非 clean 计时）；**对照功能未在真机采集上运行过**，真实 BLE 与启动计时仍待用户设备
11. **app_v2 v0.8.0 静态界面预览**（E45）
   - 新增独立 `界面预览` 页：示例车辆数据（速度 24.6 km/h、电量/温度/里程、工作模式/告警原始码）+ 车辆配置占位（前灯/尾灯/氛围灯、驾驶模式、限速、定速巡航、能量回收）
   - 控件全部为静态视觉，无点击回调；页面不读取 provider、不创建会话、不发送 BLE 帧、不改本机或车辆状态。顶部、配置区与页脚三处标注“示例/静态占位、不连接、不读取、不写入”
   - 入口：设备页未连接态的「查看界面预览（静态示例）」、设置 → 界面预览 → 车辆数据与配置
   - 验证：`flutter analyze` 0 issue；`flutter test` 46/46（新增静态页内容与“不创建会话”守卫、设置入口）；release APK 21,732,801 B，SHA-256 `19f9ab31d4a1e1ea7e5b65547ae239dfd173f4aa711c7fb6003d5c85bb653e49`，0.8.0 (8)，arm64-only，v1+v2，`unzip -t`/`zipalign -c 4` OK
   - 构建 Gradle `assembleRelease` 116.2s（总 119.5s，增量）；**静态页仅通过 widget 测试验证，未做真机视觉验收**；配置写入能力仍未实现

## 三、debug APK 构建（已成功，踩坑链记录）

| # | 问题 | 根因 | 处置 | 状态 |
|---|---|---|---|---|
| 1 | gradle 发行包下载极慢 | services.gradle.org / 腾讯源 <0.1MB/s | wrapper 改 `mirrors.huaweicloud.com`（~1.8MB/s）；verify.sh 已内置自动改写 | ✅ 下载成功 |
| 2 | `flutter-plugin-loader` 解析失败 | 全局 `~/.gradle/init.d` 注入仓库命中 flutter_tools 包含构建的 FAIL_ON_PROJECT_REPOS | 删除全局脚本；镜像只写进工程 `android/settings.gradle.kts` + `android/build.gradle.kts`（阿里云 google/public/gradle-plugin，官方源兜底） | ✅ 通过 |
| 3 | Gradle daemon 两次被 OOM kill | ① 默认 -Xmx8G；② **aaPanel 设 `vm.swappiness=0`**，4G swapfile 空置仍触发 global OOM（java 仅 544MB RSS 被杀，dmesg 佐证） | `gradle.properties`: Xmx1536m/Metaspace512m/SerialGC/关并行与文件监听/Kotlin in-process；新建 4G `/swapfile`；临时 `vm.swappiness=30` | ✅ 构建跑完（时长 ~43min） |
| 4 | 构建产物确认 | — | `app/logs/build_apk.log` 第 33–35 行：`assembleDebug` 1419.5s，`✓ Built …/app-debug.apk`，`EXIT=0` | ✅ **成功（E19）**：142,582,332 B，SHA-256 `aee600b7…9ac34`，2026-10-19 20:23 |
| 5 | APK 缺少蓝牙权限（aapt2 仅见 INTERNET）→ 真机扫描必失败 | 模板清单未声明；flutter_blue_plus 只做运行时申请、不声明 | `main/AndroidManifest.xml` 加 SCAN(neverForLocation)/CONNECT/旧版 BLUETOOTH+ADMIN+FINE_LOCATION(≤30)；`ios/Runner/Info.plist` 加 NSBluetooth* 用途描述 | ✅ 增量重建 44.2s，aapt2 复核权限已合并（E20） |
| 6 | 扫描 12s 超时后 UI 永久"扫描中" | `scanResults` 流永不关闭，旧 async* 永不结束 | `DeviceRepository.startScan` 改以 `FlutterBluePlus.isScanning` 为结束信号关闭流 | ✅ analyze 0 issue / 10 测试全绿（E21） |
| 7 | 最终产物 | — | `flutter build apk --debug` 36.8s，`EXIT=0` | ✅ **最终 APK（E22）**：157,863,282 B，SHA-256 `a69a6502…f7052`，权限 4 条 BLUETOOTH* 已复核 |
| 8 | 采集能力增强（替代 nRF 手工操作） | 原 APP 不出广播 AD、不 Read 特征 | 扫描列表显示广播三字段；连接后对可读特征各 Read 一次；诊断页一键复制证据 JSON | ✅ analyze 0 / test 10/10 / 构建成功（E23，APK `19787b29…f34e`） |
| 9 | 一键采集脚本 | 用户操作步骤多 | `analysis/tools/collect_android_hci.sh start|pull`：snoop 开关+btsnoop 多路径/bugreport 兜底+自动解帧 | ✅ 语法/假 adb 冒烟通过，待真机（E24） |
| 10 | arm64 单架构包"减半" | 首轮增量构建体积仍是 142MB | 实为增量打包残留（zip 内容仅 74MB，含 53MB+15MB 零填充）；`flutter clean` 后重建 → **74MB**，native-code 仅 arm64-v8a | ✅ 87.3s `EXIT=0`，ZIP OK，权限 4 条在（E25，SHA-256 `fb85cfbc…d132`） |
| 11 | **解码器校验错误后卡死**（真机数据质量隐患） | `StreamDecoder` 抛异常且缓冲不前进，一条坏帧会让之后所有通知无法解析 | Dart/Python 三端同步修复：坏帧丢候选头并记 `rejected` 供取证，继续重同步；decode_capture 不重复标记 | ✅ 新增回归，Dart 8/8、APP 10/10、Python 7/7、PIPELINE OK（E26） |
| 12 | 通知无法归属特征 | RawBleRecord 设备为常量、无特征 UUID | `IncomingPacket` 携带 service/char UUID，记录真实设备 ID | ✅ analyze 0 issue（E27） |
| 13 | 证据 JSON 落地工具 | 采集后无自动分析 | `evidence_report.py`：UUID 清单/Read/帧分组/偏移视图，未识别字节保留 | ✅ `EVIDENCE REPORT OK`（E28） |
| 14 | 最终 APK 与防回归 | 增量打包两次出现 15–68MB 零填充空洞 | `flutter clean` 重建；verify.sh 新增 apk_check（文件/内容差 ≥1MB 即失败） | ✅ 74,168,363 B，SHA-256 `6915a3e6…89f34e`，arm64 only（E29） |
| 15 | 安装时提示"软件包缺乏开发者证书" | 原包仅 v2 签名（v1=false），部分 ROM 安装器据 v1 缺失判无证书 | debug signingConfig 显式 `enableV1Signing=true`；重建后 v1+v2 均通过（v1 需 `--min-sdk-version 21` 校验，因 app minSdk=24） | ✅ 74,182,063 B，SHA-256 `6bf818cc…d94ca`，verify.sh 增加 signature_check（E30） |
| 16 | app_v2 v0.3 重建后 ABI 标记仍含 v7a/x86_64 | `shared_preferences` 的 datastore AAR 自带各 ABI 4–6KB 桩库，`ndk.abiFilters` 未过滤打包阶段 | `packaging.jniLibs.excludes` 排除 `lib/armeabi-v7a|x86_64|x86/**`；clean 重建 | ✅ `native-code: arm64-v8a` 唯一；76,793,535 B，SHA-256 `84330aeb…e5db9`，v1+v2、ZIP OK（E39） |
| 17 | app_v2 v0.4 新增图片与锁车控件 | 新增 676KB PNG 资源，需确认被打包且不引入填充 | `flutter clean` 重建；aapt2 核对 ABI/权限；unzip 确认 `assets/flutter_assets/assets/images/hoverboard.png` 与 ZIP 内容差 | ✅ 77,512,856 B，SHA-256 `d6af9bca…a9765`，0.4.0 (4)，525.0s，v1+v2、ZIP OK（E41） |
| 18 | app_v2 v0.5 新增设置内数据与诊断页 | 页面为长列表，需确认页面结构、字段状态、出站记录与测试收尾；新增代码后需 clean 重建排除残留 | widget 测试覆盖演示值 / 7 个未标定字段 / 来源面板 / 出站记录；`flutter clean` 重建并核对 ABI、签名、ZIP | clean arm64 构建 524.2s；APK 77,532,722 B，SHA-256 `d58877db507877a80d229cf807331ff0bf79010c012ab2584dc04c990d4a7cdd`，0.5.0 (5)，v1+v2 签名、ZIP OK，图片已打包 |
| 19 | app_v2 v0.6 release/AOT 包 + splash | debug JIT 启动黑屏 2–3 秒；首次 `assembleRelease` 在 1.7 GB 主机上 R8 运行 25 分钟未完成；关闭 R8 后 release lint 又卡在 GooglePlaySdkIndex 10 分钟以上 | 改用 arm64 release/AOT；`isMinifyEnabled=false`、`isShrinkResources=false`、`lint.checkReleaseBuilds=false`（本机/sideload 取舍，已写入 README）；重跑 62.6s 成功 | APK 21,731,845 B，SHA-256 `509c7206…70b4`，0.6.0 (6)，arm64-only，v1+v2，ZIP 完整性/对齐 OK，splash/icon 资源已打包；**启动耗时未在手机实测** |
| 20 | app_v2 v0.7 离线对照 + 安全页 | 新增对照算法/离线页长表单与二级页入口，需要确认不引入字段语义、长页面可滚动、release 资源与 ABI 不回退 | 单测覆盖共享/单侧帧组、偏移差异、长度变化、拒收保留与“报告无字段语义”；`scrollUntilVisible` 覆盖长设置页；增量 release 重建并复核签名/ZIP/ABI | 128.5s；APK 21,731,982 B，SHA-256 `e05522f1…f932`，0.7.0 (7)，arm64-only，v1+v2，ZIP/对齐 OK；**对照未跑真机采集** |
| 21 | app_v2 v0.8 静态界面预览 | 设置页新增一段入口后，原“只读与安全说明”测试的滚动目标被底部导航遮挡；预览页指标在测试字体下横向溢出 30px | 安全页测试在 `scrollUntilVisible` 后补 `ensureVisible`；指标数值行加 `FittedBox(scaleDown)`；新增两条预览测试并断言 `sessionProvider` 仍为空 | analyze 0；test 46/46；assembleRelease 116.2s；APK 21,732,801 B，SHA-256 `19f9ab31…3e49`，0.8.0 (8)，arm64-only，v1+v2，ZIP/对齐 OK |

### 环境现场（用户侧待决事项）
- `/swapfile`（4G）与 `vm.swappiness=30` 是为构建临时加/改的，**未写入任何持久化配置**
- 若不再需要构建可还原：`swapoff /swapfile && rm /swapfile && sysctl -w vm.swappiness=0`（由你决定是否保留）

## 四、阻塞项（需用户提供硬件/环境）

| 编号 | 内容 | 需要什么 |
|---|---|---|
| U02 | ~~通知帧完整目录~~ **已关闭（E47+E52）**:应答帧目录 20 子帧 + app 侧读查询帧目录（18 类自选诊断集，周期与查询 len=应答载荷长度 963/963 实证；官方主界面仅 2 类，E57/E58）全部来自 `captures/20261021` | 剩余:各 A2 语义与官方界面动作对照(并入 U03) |
| U03 | 速度/电量/温度等字段的偏移与单位 | **电量已部分关闭（E64）**：off28 u8 已写成可导入标定并通过 83%/99%/79% 回放；**速度/电压/温度仍未知**。电压候选为 0x14 off18–19（0x1d 恒 c818，×0.01V=63.44V），但无官方界面电压读数，不映射。速度：官方 UI 仅有 0.0 km/h 静置画面；E62 静置 off26 61…141、off27=0，E63 off26 62…129；20261020 长时 off26/27 在约 1 s 内 0→3084→2.5，E64 判定为电流/电机量而非平滑速度。需官方 App 骑行/推行同刻速度读数 + HCI 日志才能定名。**阶段三已把判定工具化（E67）**：`analysis/tools/speed_calibration.py` 按读数拟合、六条硬条件 + 圆整倍率 + 独立重复段判定，唯一候选才出标定；执行步骤见 `analysis/procedure_speed_calibration.md`。**尚未在真实移动采集上运行过** |
| U04 | 具体车辆型号 | 车身铭牌拍照 |
| U06 | 锁车/解锁固定帧的真实效果与车端锁状态回读（E40 仅静态；UI 不得显示已锁） | **帧本身已真车验证（E66：关锁/解锁各 1 帧、车辆有反应、各只写 1 次）**；仍无车端回执，锁态不可回读。**剩余是闸门**：速度未标定 → 锁车控制本车不可用（`LockPolicy` 已按「未知即阻止」收紧），B/C/D 三种工况的真车验证见 `app_kotlin/docs/lock-gate-real-vehicle-plan.md`，前提是 U03 先收敛 |
| iOS | 编译验证 | macOS + Xcode（Linux 宿主无法验证） |

> 已关闭：U01（G1 UUID/CCCD/MTU，E34）、U05（无配对/加密/鉴权，E34）。

## 五、下一步（按优先级）

1. **真机验收 app_v2 v0.8.6+14**（需用户操作）：安装 `downloads/iwalk-0.8.6-arm64-release.apk`（arm64，v0.8.6，AOT，含 E50 XOR-0x55 解码 + E54 只读查询调度器 + E56 导出出口 + E59 官方节奏默认 + E60 导出快照修复 + E61 逐寄存器发送计数；调试可用 `iwalk-0.8.6-arm64-debug.apk`）→ 先看设备页「查看界面预览（静态示例）」核对数据/配置布局 → 扫描应命中 `MiniRobot:IWK*` → 连接后设置/设备卡确认 `g1NordicUart`、`6e400002/6e400003`、MTU 23 → **确认设置「数据查询」默认开启且默认只发官方 2 类（0x14@110ms + 0xbc@1.01s）**，诊断轮询保持关闭（E60 实测其会降低主状态回包率） → 分析页确认真实帧持续增加 → **导出一次会话，确认 evidence JSON 含 `app_version=0.8.6 (14)`，且 `raw_packets` 等于 raw 文件行数、frame_groups 帧数之和等于 `frames_total`（E60 快照修复验收）** → **核对 `read_query.sent_by_register`：各寄存器 sent 之和等于 `sent_this_session`，且 0x14/0xbc 的 sent 可与 frame_groups 回包逐项对照（E61 已由 E63 真机验证：0.8.6 导出 60+7=67；后续采集继续按此核对）** → 设置 → 数据与诊断核对字段、更新时间与统计 → 只读与安全核对“永不执行”清单 → **用「分享/保存」与「导出历史」验证文件出口**。**静态预览不能改车；查询只读；锁车控制保持默认关闭**
   备用：`app/`（v0.1 取证工具）仍可导出完整证据 JSON
2. ~~（可选，风险自担）锁车试验~~ **已做（E66）**：两帧真车有效、各写 1 次、无回执。**阶段三剩余部分是闸门的真车验证**，见 `app_kotlin/docs/lock-gate-real-vehicle-plan.md`：B 静止放行 / **C 运动拦截（拦截时 HCI 必须零写入，这是闸门唯一的真证据）** / D 停下恢复。前提是速度标定先收敛；标不出来就保持「锁车不可用」，不退回"发出去但不拦"的旧行为
3. ~~app_v2 只读查询调度器（E52 后的核心修复,待用户批准）~~ **已完成（E54，用户批准）**：`lib/bluetooth/read_queries.dart` 白名单仅 A1=0x01、CMD∈{0x0a,0x0b,0x0c}，18 条读查询逐条 golden 比对并按 XOR-0x55 上线，写类/0x04/0x20 仍禁发；调度器默认开启、可关、连续失败自动停。**E58 更正：该 18 条为自选诊断集，并非官方行为**（官方主界面仅 0x14@0.11s + 0xbc@1.01s）。**E59 已按用户决定实现**：v0.8.4+12 默认仅官方 2 类（0x14@110ms + 0xbc@1.01s，相位错开 55ms），18 类改为默认关闭的「诊断轮询」开关，下次连接生效；证据文件记录 `read_query.mode=official|diagnostic` 与 catalog_size。**E60 真机双模式验收**：官方模式仅 0x14/0xbc 应答、0x14 回包≈95%；诊断模式 18 类中 16 类有回包（0x0b/0x0c 零回包），主状态回包降至≈41%，故官方节奏应保持默认。**E61（v0.8.6+14）**：证据新增 `read_query.sent_by_register` 逐寄存器发送计数，可与回包逐项对账；诊断开关/安全页文案同步 16/18 与回包率事实。**E62**：用户新导出仍是 0.8.5（无 `sent_by_register`），快照不变量与校验均通过；0x0b/0x0c 再次零回包，off28=79%（电量第三点），off26/27 不宜按单一 s16 解读。**E63**：0.8.6 真机导出已出现 `sent_by_register`（0x14 60 + 0xbc 7 = 67 = sent_this_session），61/61 校验通过、快照计数一致 → E61 验收通过。**E64**：已落盘部分标定（仅电量，应答组 0x0d/0x01/0x14 off28 u8；E57 83%/20261020 99%/E63 79% 回放通过）；速度/电压/温度仍不映射
4. ~~官方 App 报文采集补证~~ **已完成（E52+E53+E57）**：请求/应答模型、订阅链路无缺陷、官方主界面 2 类轮询、0x14 的 83% 电量锚点均已实证。**E64 已完成部分标定**：`downloads/iwk1779887-calibration-partial-v1.json` 仅映射电量（应答组 0x0d/0x01/0x14 off28 u8，83%/99%/79% 回放通过）。仍需:**官方 App 骑行/推行各录一段，并同刻记录界面速度/读数**（E60/E62/E63 显示 off26–27、off36–37 高频跳变，单凭报文无法当速度定标；E64 已排除 off26/27 的“平滑速度”解释），并观察官方设置/详情页是否扩大查询集（U02/U03）；无需再开诊断轮询来采标定，官方 2 类已覆盖主状态
4. **阶段三 · 速度标定（需用户执行，无人可代）**：按 `analysis/procedure_speed_calibration.md` 做一次「官方 App + 录屏 + 日志」的**受控移动采集**（四段：静止 / 稳定低速 / 再停 / 独立重复；≥3 个可区分非零读数），回来跑 `python3 analysis/tools/speed_calibration.py --frames <raw.jsonl> --observations <csv> --video-anchor <录屏 creation_time> --base-calibration downloads/iwk1779887-calibration-partial-v1.json --emit downloads/iwk1779887-speed-v1.json`。退出码 0 才算标定出来，1/2 都不出文件（工具只认拟合，不认「看着像」）。顺手拍铭牌回填 U04。**注意：速度只可能在会变化的字节跨度里（静止实测仅 off16/18/22/26/27/36/37 在变，E67）；不要用静置采集去猜速度**。**E71 修正（2026-11-13）：昨晚首次实操采集（20261112_speed_01，240 s 录屏）OCR 全 0.0——车没有移动，不算失败也不算数据；同轮用官方「详细信息」页同刻 HCI 完成字段核对（0x1a/0x2a/0xfd/0xbb 四个新帧组 + 6 个字段确认，含总里程 562081 m ↔ 562.08Km、总运行 565209 s ↔ 157H0M9S），并证伪夹具的「速度 off6」标注（真车 off6 = SN ASCII）。下一次采集务必确认车轮真的在转（页面「本次里程」「本次最高速」应非零）**
5. 采集后运行 `python3 analysis/tools/evidence_report.py captures/YYYYMMDD/evidence.json -o …/evidence_report.md --json …/stats.json`
5.6 **App 0.4.0 用户正式版（E74，2026-11-13）**：标定内置（BuiltinCalibration，E64+E71 两条）+ 调试信息全下架（原始码/帧数/链路详情/来源面板/诊断与导出入口/分析页签 3→2）；交付 `downloads/iwalk-companion-0.4.0-arm64-release.apk`（v5，已装真机验证）；锁闸门未改（速度门槛保留，E66）
5.5 **App 0.3.0 页面重设计已完成（E72，2026-11-13）**：设备页改为九号风格——中央大电量环（232dp 圆环 + 72sp 数字）+ 预计里程卡（0x2a off2，E71 真车字段）+ 时速大字移除（speed 仍解码供锁闸门）。轮询第二条 0xbc→0x2a @1010ms（总线负载不变；真车回包率待下次连接核对，零回包则回退 0xbc）。交付 `downloads/iwalk-companion-0.3.0-arm64-release.apk`（versionCode 3，已装真机）；真车显示预计里程需导入更新后的 `iwk1779887-calibration-partial-v1.json`（新增 0x2a 组）
6. ~~（可选）release APK 构建~~ 现役包 **v0.8.6+14**（`downloads/iwalk-0.8.6-arm64-release.apk`，E61）含 E50 XOR-0x55 解码 + E54 只读查询调度器 + E56 导出历史/系统分享 + E59 官方节奏默认 + E60 导出快照一致性/app_version + E61 逐寄存器发送计数；另有同级 debug 包 `iwalk-0.8.6-arm64-debug.apk`；如需分发给他人，应改为正式 keystore 并恢复 R8/lint 后重建
7. iOS 编译验证需 macOS + Xcode（Linux 宿主无法验证，遗留项）
8. 阶段六发布流程待阶段二/三产物齐备后启动

## 六、文件索引

- 协议结论：`analysis/protocol_findings_table.md`；证据流水：`analysis/evidence_log.md`
- 采集规程与工具：`analysis/procedure_ble_capture_detailed.md`、`analysis/tools/`
- APP：`app_v2/`（v0.8 伴侣应用，使用流程 `app_v2/USAGE.md`，交付说明 `app_v2/README.md`）；`app/`（v0.1 取证工具，一键校验 `app/verify.sh`）
- 构建报告：`analysis/05_app_build_report.md`（含踩坑表与镜像配置）
