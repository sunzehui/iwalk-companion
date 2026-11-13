# iWalk 平衡车互操作研究 / 伴侣 App 工作区

> **性质声明**：本项目为**独立第三方互操作性研究**，目的是让自研 App 能与本研究者的自有车辆正常通信。
> 项目与本产品及其商标持有人无任何关联、未获其授权或背书；仓库不含厂商二进制、其源代码或受版权保护的资源文件。
> 所有协议事实均来自**对该车辆自身蓝牙链路流量的观察**与公开可获取的通用技术资料；
> 本项目不提供、不讨论规避任何技术保护措施的方法，也不分发厂商固件或应用。
> 若权利人认为有不妥之处，请通过 issue 联系，我们会及时处理。

## 目录结构

```
TASK_PROGRESS.md             任务进度文档（各阶段状态/踩坑链/阻塞项/下一步，最先看这个）
downloads/                   本车标定文件（JSON）；厂商安装包与二进制均不入库
analysis/
  01_interop_report.md         接口观察报告（事实/假设/猜测分级）
  05_app_build_report.md       阶段五 APP 构建交付文档（验收标准/镜像与命令）
  06_selfbuilt_apk_overview.md 自建 APK 功能说明（包信息/权限/三页面/安全边界）
  protocol_findings_table.md   协议推导表（结论|证据|置信度|下一步）
  procedure_ble_capture.md     真机只读采集流程（通用 BLE 调试工具 + 系统 HCI 日志）
  procedure_ble_capture_detailed.md  逐步操作手册（含故障排查与验收清单）
  08_ble_gatt_spec.md          BLE GATT 传输层规范（U01 已关闭：UUID/appType/MTU/安全属性）
  09_battery_calibration_replay.md   电量标定回放
  procedure_speed_calibration.md     速度标定采集与判定规程
  protocol/                    Python 参考编解码器 + 单元测试（已通过）
  tools/                       离线解帧、标定判定、闸门核对脚本
captures/                    真机实验数据（按 YYYYMMDD/session 组织，本地留存不入库）
protocol_dart/               Dart 协议包（Flutter APP 的 protocol 层，测试已通过）
app_v2/                      Flutter 双端 APP（伴侣应用，已被 app_kotlin 取代）
app_kotlin/                  Kotlin 原生伴侣 App（现役；Compose 重写，含真车验收记录）
```

## 核心结论速览（详见 `analysis/protocol_findings_table.md`）

- 协议帧：`55 AA | L | CMD | A1 | A2 | payload(L-2B) | chk_lo chk_hi`，总长 L+6
- 校验：`(~uint16(sum(bytes[2..L+3]))) & 0xFFFF` 小端（已用帧样本验证）
- ACK 应答帧 A2 = CMD ^ 0x05（真车采集核对）
- GATT（U01 已关闭）：`6e400001` 服务 / `6e400002` 写 / `6e400003` 通知 / MTU 23 / 无配对加密
- 锁车/解锁（E40 推导，E66 真车核对）：`55aa040a037001007dff` / `55aa040a037101007cff`；应用默认关闭该能力，开启后只发这两帧且不回读锁状态
- 只读信息层：条目名与 payload 数值的映射**未验证**；应用只并列展示原始码与文案，不据此诊断
- 离线 A/B 对照：只比较两次采集的帧组/偏移字节分布，不做时间对齐、不识别字段
- 仍未确认（U02/U03）：通知帧完整目录，以及速度/电量/温度等字段的偏移与单位

## 当前阶段与下一步

1. ✅ 阶段一：安装包清单取证 + 静态接口观察 + 帧格式还原
2. ✅ 离线解帧工具链已本地验证（`analysis/tools/run_pipeline.sh`，含交叉校验，见 evidence_log E17）
3. 🟡 阶段二/三：U01 已关闭；真机只读通知采集已完成（照 `analysis/procedure_ble_capture_detailed.md`）
4. 🟢 阶段四：`captures/` 数据已回填 `protocol_findings_table.md`（U02 部分收敛，U03 速度字段仍未定位）
5. 🟢 Flutter 版（`app_v2/`）v0.8 已产出 arm64 release APK；后续由 Kotlin 版取代
6. 🟢 **Kotlin 原生版（`app_kotlin/`）**：Compose 重写，0.4.0 为用户正式版（设备页大电量环 + 预计里程、标定内置、调试信息下架）。行为基线仍是 Flutter v0.8.3

**下一步（按优先级）**

- **阶段三 · 速度标定与锁车闸门**（进行中）：速度标定需要一个「参考客户端 + 录屏 + 链路日志」的受控移动采集，
  判定由 `analysis/tools/speed_calibration.py` 机械给出（唯一候选才出标定），步骤见
  `analysis/procedure_speed_calibration.md`；标定通过后才能验证锁车闸门的两种状态，
  方案见 `app_kotlin/docs/lock-gate-real-vehicle-plan.md`，HCI 侧由
  `analysis/tools/lock_gate_check.py` 判（运动中是否真的一条锁帧都没写出）
- 回填 U02/U03/U04（铭牌、字段语义）；`app_kotlin/README.md` 有最新验证状态
- iOS 编译需 macOS + Xcode（Linux 宿主无法验证）
