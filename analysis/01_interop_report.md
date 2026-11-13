# 阶段一/四 接口观察报告（首轮）

日期：2026-10-19
参考客户端：`com.loby.balance.minirobot` v5.4.1（versionCode 47）
说明：本报告只记录**对该应用公开清单与链路流量可直接观察到的行为**，以及由此得到的接口事实；
不含厂商二进制、其源代码或受版权保护的资源。分级：事实 / 假设 / 猜测。

## 1. 已确认事实（有证据）

| # | 事实 | 证据 |
|---|------|------|
| F1 | 包名 `com.loby.balance.minirobot`，versionName 5.4.1 / versionCode 47 | 应用清单（公开元数据） |
| F2 | minSdk 18 / targetSdk 28；权限：BLUETOOTH、BLUETOOTH_ADMIN、INTERNET、FINE/COARSE_LOCATION、VIBRATE | 清单 |
| F3 | `uses-feature bluetooth_le required=true` → **应用强制要求 BLE** | 清单 |
| F4 | 应用主体是 **Cocos2d-x (C++) 引擎**，主 Activity `org.cocos2dx.cpp.AppActivity`，BLE 服务 `org.cocos2dx.cpp.BluetoothLeService`（exported=true） | 清单 |
| F5 | 该构建的 Java 层做了代码保护（仅 380 KB 壳类），业务逻辑位于随包 native 库 `libcpp_empty_test.so`（arm64-v8a 13MB / armeabi-v7a 12MB）；**本项目的协议结论不依赖该库内部实现**，全部由链路流量与公开 GATT 行为核对得到 | 包结构（zip 条目） |
| F6 | ABI 包含 arm64-v8a 与 armeabi-v7a | 包结构 |
| F7 | 产品线标识（公开字符串）：乐行天下/Lebid（lebitec.com），固件更新路径模板含 `BalanceCarUpdate/…`、`M3Update`、`Plus_Updata`、`Scooter_Updata`、`SlideEbikie` | 应用内公开文案与升级 URL |
| F8 | 设备侧协议接口包含：拆帧/合帧、读命令（按地址读 1–3 字节）、写命令、锁车控制、ACK 等待与重传 | 链路流量时序观察（E07/E13/E52） |
| F9 | 数据通路：BLE 通知 → 应用层 → 帧解析 → 遥测字段更新 | 链路流量 + 应用行为 |
| F10 | 界面可见遥测项：车速、电池 1/2 电量与电压、电池容量、电池温度、车体温度、驱动电压、错误码、车体状态、刹车灯、大灯、速度单位、电子版本 | 应用界面（真机截图） |
| F11 | 错误码文案（应用内多语言资源，界面可见）：Hall-A/Hall-E、转向异常、陀螺仪异常、突然掉电、大电流保护、应力片异常、RM-Loss、LM-Loss、倾角速度过大 | 应用界面 |

## 2. 协议帧结构（置信度高，已由真车报文验证）

```
偏移  长度  含义
[0]   1     0x55               帧头
[1]   1     0xAA               帧头
[2]   1     L = payload长度+2   长度字段
[3]   1     CMD                命令字
[4]   1     A1                 地址/子参数
[5]   1     A2                 地址/子参数
[6]   L-2   payload            （可为空）
[L+4] 1     chk_lo  ┐ checksum = (~uint16(sum(bytes[2..L+3]))) & 0xFFFF，小端存放
[L+5] 1     chk_hi  ┘
总帧长 = L + 6
```

证据（均可在真车报文中复核，见 `analysis/evidence_log.md` E07/E08 与 `captures/`）：
- 帧头与长度字段：帧一律以 `55 AA` 起始，第三字节 + 6 等于该帧字节数（滑窗切帧一致）。
- 校验：`byte[2]` 参与累加，累加 `L+1` 字节后取反，与末尾两字节小端比对；不符的帧在采集里从不出现。
- 长度语义：`L = payload + 2`，由 `0x14`（38 B 应答，L=0x26）与 `0x2a`（34 B 应答，L=0x22）两条真车应答互证。
- 两条固定帧样本通过参考实现与单元测试：`analysis/protocol/ibwalk_frame.py`（5 用例全过）。

### 已观察到的命令词汇表（来自真车请求/应答配对，E52/E57/E71）

调用约定：`CMD + A1 + A2 + payload`，帧 = `55 AA (payloadLen+2) CMD A1 A2 payload… chk16`；查询帧的 payload 通常只有一个字节，等于期望应答长度。

| CMD | 用途 | payload | 安全级别 |
|-----|------|---------|----------|
| 0x04 | 一键校准控制（A2=0x1E，1B） | 1B | **禁止发送** |
| 0x06 | 读基本状态 / 写开关 | 1–2B | 读安全；写**勿试** |
| 0x0A | 读扩展状态 / 读黑匣子 / 写 | 1–2B | 读安全；写**勿试** |
| 0x0B | 读（手柄/蓝牙板） | 1B | 读安全 |
| 0x0C | 读命令 3 | 1B | 读安全 |
| 0x20 | HB 写 | 2B | **勿试** |
| 0x22 | HB 读 | 1B | 读安全 |
| 0xF1 | 心跳/短查询（8 字节固定帧） | 0 | 读安全 |
| 0xFF | 测试/握手帧 `55 aa 02 ff 01 03 fa fe` | 0 | 读安全 |
| 0x0A（大载荷） | 固件升级分包 | ≤1KB | **禁止发送** |

### 应答组与已核对字段（E71）

| 应答组 | 长度 | 已核对字段 |
|---|---|---|
| `0x0d/0x01/0x14` | 38 B | off20/21 = 电池容量（5.2 AH）、off28 = 电量 %、off6–7 = SN ASCII |
| `0x0d/0x01/0x1a` | — | off30 u32le = 总里程（米）、off8/9 = 电池容量、off14 = 手持角度候选 |
| `0x0d/0x01/0x2a` | 34 B | off16 u32le = 总运行秒、off2 = 预计剩余里程 km、off6 = 车体温度 ×0.1 |
| `0x0d/0x01/0xbb` / `0xfd` | — | 详细信息页轮询组（语义未完全核对） |

### 未验证变体（猜测级）

- 存在一种整体异或 0x55 的传输编码（线上首字节表现为 `00 FF`）。该编码已在真车采集里被证实并按双假设解码（见 `analysis/tools/btsnoop_att.py`），语义与明文一致。
- 车速字段仍未定位（U03）：静置采集里候选字节自身漂移，不能用「静止数据」推断速度，需受控移动采集（`procedure_speed_calibration.md`）。

## 3. 下一步

1. 已完成：真机只读采集（通用 BLE 调试工具 + 系统 HCI 日志），见 `procedure_ble_capture.md`。
2. 已完成：`ibwalk_frame.py` 的 StreamDecoder 离线解帧回填 `protocol_findings_table.md`。
3. 待做：受控移动采集定位速度字段（U03），随后验证锁车闸门。
