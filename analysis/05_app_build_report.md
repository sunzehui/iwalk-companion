# 阶段五交付文档：Flutter APP 构建与验证

状态：**代码骨架完成，debug APK 构建成功并补齐蓝牙权限**（最终产物 E22，2026-10-19 20:36）；真机验收待用户设备。
本文档不含任何未经执行的结果声称；未执行的条目仍保持"待验证"。

## 1. 目标

- 用 Flutter 重建 iWalk 平衡车 APP 的**只读观察端**：扫描、连接、订阅通知、
  解码 `55 AA` 帧、展示状态与原始报文。
- 协议层独立成包 `protocol_dart/`（纯 Dart，可单测，已 6/6 通过）。
- 蓝牙层保持只读红线：`allowWrite=false` 门控（`ble_gatt_transport.dart`），
  未获真机证据前不发送任何指令（doc.md 安全规则 2）。

## 2. 已确认输入（来自阶段一）

- 帧格式/校验和/ACK 规则：`protocol_findings_table.md`（高置信）。
- 禁止项：CMD 0x04+A2=0x1E（一键校准）、OTA 分包路径（E15、E11）。
- 未知项：GATT UUID（U01，静态路线已排除）→ APP 必须做**全服务动态发现**，
  不硬编码 UUID（已按此实现：`device_scan_page` → connect → `discoverServices`）。

## 3. 构建与验证命令（宿主机）

```bash
export PUB_HOSTED_URL=https://pub.flutter-io.cn          # 国内镜像（本次实测必需）
export FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn
cd /root/iwalk/app
flutter create --platforms=android,ios .                 # 生成平台目录（不覆盖 lib/）
flutter pub get
# 国内镜像下构建 APK 前（本次实测 services.gradle.org 与腾讯 gradle-dist 均 <0.1MB/s）：
#   1) android/gradle/wrapper/gradle-wrapper.properties 的 distributionUrl 改为
#      https://mirrors.huaweicloud.com/gradle/<同名文件>（实测 ~1.8MB/s）
#   2) android/settings.gradle.kts 的 pluginManagement.repositories 与
#      android/build.gradle.kts 的 allprojects.repositories 前置阿里云 maven
#      （gradle-plugin/google/public），官方仓库保留作回退。
#      注意：不要用 ~/.gradle/init.d 全局脚本注入 allprojects 仓库——会命中
#      flutter_tools/gradle 包含构建（其 settings 设了 FAIL_ON_PROJECT_REPOS），
#      导致 flutter-plugin-loader 解析失败（本次实测踩坑并修正）。
# （verify.sh 已内置第 1 步的自动改写）
flutter analyze     # 预期：No issues found
flutter test        # 预期：全部通过（当前含 connection_manager 2 例）
dart test ../protocol_dart   # 预期：6/6（已多次通过）
```

## 4. 预期结果与判定

| 步骤 | 预期输出 | 失败时含义 |
|------|----------|------------|
| flutter analyze | No issues found | 骨架代码有静态错误 |
| flutter test | All tests passed | 状态机/重组逻辑回归 |
| flutter build apk --debug（后续） | 生成 app-debug.apk | Android SDK/插件兼容问题 |

### 4.1 采集能力（2026-10-19 增补，用于替代 nRF 手工操作）

- 扫描列表展示广播 `serviceUuids` / `manufacturerData` / `serviceData`(hex) / txPower / connectable
- 连接后逐一对可读特征 Read 一次（≤32 个，失败记录 error 不中断）
- GATT 树附描述符 UUID；诊断页新增“复制完整证据 JSON”（广播+GATT树+Read+通知）
- 约束不变：不写任何特征；Read 属于只读操作

## 5. 故障排查

| 症状 | 处置 |
|------|------|
| pub get 超时 | 确认 PUB_HOSTED_URL 已导出；镜像见上 |
| `flutter create` 报已有文件冲突 | 加 `--overwrite` 仅针对模板残留；lib/ 自定义文件不受影响 |
| analyze 因插件版本失败 | 锁定 flutter_blue_plus ≥1.32 / riverpod ≥2.5（pubspec 已约束） |
| iOS 构建 | 本机为 Linux，**无法**验证 iOS 编译；需在 macOS 执行，列为遗留项 |
| wrapper 下载卡在 ~35KB/s（腾讯 gradle-dist） | 改用 `mirrors.huaweicloud.com/gradle/`，实测 ~1.8MB/s |
| `Error resolving plugin dev.flutter.flutter-plugin-loader` + `repository 'maven' was added by settings file` | 全局 `~/.gradle/init.d` 注入 allprojects 仓库会命中 flutter_tools/gradle 包含构建（FAIL_ON_PROJECT_REPOS）。镜像改写**只能放在本工程** `android/settings.gradle.kts`(pluginManagement) 与 `android/build.gradle.kts`(allprojects) |
| `Gradle build daemon disappeared unexpectedly`（OOM killer，见 `dmesg`） | 两层原因：① 默认 `-Xmx8G` 超物理内存——`android/gradle.properties` 设 `org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC`、`org.gradle.parallel=false`、`org.gradle.vfs.watch=false`、`kotlin.compiler.execution.strategy=in-process`；② **真正根因**：aaPanel 把 `vm.swappiness` 调为 0，导致 4G swapfile 空置仍触发 global OOM（java 仅 544MB RSS 被杀）。临时 `sysctl -w vm.swappiness=30` 并加 4G swapfile：`fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile`（构建后 `swapoff /swapfile && rm /swapfile` 并按需恢复 swappiness=0） |

## 6. 输出物

- `app/` 源码（已就绪）+ 平台工程目录（SDK 可用后生成）。
- 静态检查与单测结果日志 → 追加到 `evidence_log.md`（执行后）。

## 7. 验收标准（本阶段）

- [x] `flutter analyze` 零 issue（2026-10-19 实测 No issues found，logs/analyze.log，E18）
- [x] `flutter test` 全绿（10/10，logs/test.log，E18）
- [x] `dart test protocol_dart` 全绿（6/6）
- [x] Debug APK 可构建（2026-10-19 20:23 实测成功，E19；20:35 补权限后增量重建成功，E20）
- [x] Android 蓝牙权限已声明并复核合并进 APK（E20）；iOS NSBluetooth* 用途描述已补（编译验证仍待 macOS）
- [x] 扫描超时后 UI 不卡死（E21：以 isScanning 关闭结果流）
- [ ] 真机验收（依赖用户设备）：扫描到车辆 → 连接 → 服务树完整呈现 →
      静置时 JSONL 有连续原始记录且未知帧不被丢弃

## 8. 下一步

1. ~~SDK 就绪后执行第 3 节命令并记录真实输出。~~ 已完成（E18/E19/E20/E21/E22）；产物：`app/build/app/outputs/flutter-apk/app-debug.apk`（157,863,282 B，SHA-256 `a69a6502…f7052`）。
2. 用户按 `procedure_ble_capture.md` 采集 → 得到 UUID → 回填 U01/U02 并让
   APP 的 `scooter_protocol.dart` builders 从"未验证"升级为实测注释。
3. 真机安装 debug APK 做阶段五验收：扫描到车辆 → 连接 → 服务树完整呈现 →
   静置时 JSONL 有连续原始记录且未知帧不被丢弃。
4. iOS 侧 NSBluetooth* 配置（README 已列）需在 macOS 验证，标记为遗留。
