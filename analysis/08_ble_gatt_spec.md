# BLE GATT 传输层规范（U01 补全）

来源：设备制造商公开下载页提供的正式版客户端（本地留存，不入库），以及 nRF Connect 真机实测。
证据性质：**全部来自可观察行为**——公开 GATT 服务树、标准特征读取结果、真机连接日志

| 项 | 值 |
|---|---|
| 包名 / 版本 | `com.loby.balance.car.google` / versionName 11.3.8, versionCode 303 |
| SHA-256 | `cd6f4f61402c0876beddc2a220dba00cc47cd4a52f17b1e67a2dccdd05089a8d` |
| 取证日期 | 2026-10-20 |
| 关键前提 | 该 APK **未加壳**（对照 `downloads/I-WALK_v5.4.2.apk` 的 360 壳），Java 层业务代码完整可读，因此 GATT 层可直接静态确定 |

本文只补 **传输层（U01）**。应用层帧格式沿用 iwalk 既有结论，本次分析对其**既未证实也未推翻**，见 §7。

---

## 1. 服务/特征全集

全部为 16-bit  Assigned Numbers 段 UUID（`0000xxxx-0000-1000-8000-00805f9b34fb`）加一组 Nordic UART。

常量出处：`BluetoothService.java:33-47`、`BluetoothHolder.java:39-57`

| # | 服务 UUID | 写特征（APP→车） | 通知特征（车→APP） | CCCD |
|---|---|---|---|---|
| G1 | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` (Nordic UART) | `6e400002-…` | `6e400003-…` | `00002902-…` |
| G2 | `0000ae00-…` | `0000ae01-…` | `0000ae02-…` | 同 |
| G3 | `0000ffe0-…` | `0000fff3-…` | `0000fff4-…` | 同 |
| G4 | `0000fff0-…` | `0000fff3-…` | `0000fff7-…` | 同 |
| G5 | `0000fff0-…` | `0000fff2-…` | `0000fff1-…` | 同 |

CCC Descriptor = `00002902-0000-1000-8000-00805f9b34fb`（`BluetoothService.java:33`），统一写 `ENABLE_NOTIFICATION_VALUE`（`0x01 0x00`），走 `writeDescriptor`。全 APK 不出现 `ENABLE_INDICATION_VALUE`，即**只用 notification，不用 indication**。

G4 与 G5 共用服务 `fff0`，但收发特征方向不同，属同一服务的两种固件变体。`ffe0` 服务即便同时带 `fff1/fff2` 特征也**只会用 G3**（`fff3` 写 / `fff4` 通知）——升级条件被 `i == 4` 锁死在 `fff0` 分支（见 §2），所以「`ffe0` + `fff1/fff2` 当收发对」这种组合在本 APK 里不存在，若真机见到说明是另一版固件，需回报。

**死代码警告**：`BluetoothHolder.UUID_SERVICE4` 也定义为 `0000ffe0`，与 `UUID_SERVICE` 同值。`searchGattServices()` 是 `if (== UUID_SERVICE) {…} else { if (== UUID_SERVICE2) … else if (== UUID_SERVICE3) … else if (== UUID_SERVICE4) … else if (== UUID_SERVICE5) … }` 结构，`ffe0` 永远先命中第一个 `if`，所以 `UUID_SERVICE4` 分支不可达（且它只置 `_isSmallType=1`、不改 `i`，即使可达也没有独立效果）。其余四条服务判定（`ffe0` / `6e400001` / `ae00` / `fff0`）均有效。移植时**不要照抄这段结构**，改用「扫描所有已知服务、按服务与特征存在性判定」的写法（见 §2 伪码）。

---

## 2. 传输模式选择：appType 状态机

`BluetoothService._appType`（默认 1）决定读写用哪一组 UUID。

appType 由 `BluetoothHolder.searchGattServices()`（`:192-310`）在服务发现时按**主服务**赋值，再由 `_isSmallType` 微调。消费点为 `BluetoothService.enableTXNotification()`（`:172-283`，switch 在 `:179`）与 `BluetoothService.writeCharacteristic(byte[])`（`:357-458`，switch 在 `:362`）。

| 主服务 | `_isSmallType` | 得出 appType | 传输组 | 实测 |
|---|---|---|---|---|
| `0000ffe0` | 1（默认/非 M6） | 3 | G3 | 未见 |
| `0000ffe0` | 0（名以 `M6` 开头） | 0 | G1（落到 default 分支） | 未见 |
| `6e400001` | 强制置 1 | 1 | G1 | ✅ **命中（§10）** |
| `0000ae00` | 强制置 1 | 2 | G2 | 未见 |
| `0000fff0` | 强制置 1 | 4 | G4 | 未见 |
| `0000fff0` 且设备暴露 `fff1` 或 `fff2` | 1 | 5（二次升级） | G5 | 未见 |

`_isSmallType` 初值为 1，仅由 `AppActivity.SetAppType(deviceName)` 设置：名字 `startsWith("M6")` → 0，否则 1（`AppActivity.java:765-771`）。

判定伪码（建议 iwalk 采用）：

```python
def select_transport(services, device_name):
    """services: {service_uuid16: set(char_uuid16)}，来自一次服务发现"""
    small = 0 if device_name.startswith("M6") else 1
    if 0xffe0 in services:                     # 先命中，覆盖所有 ffe0 变体
        return G3 if small else G1             # M6 走 Nordic UART
    if 0x6e400001 in services: return G1
    if 0xae00 in services:     return G2
    if 0xfff0 in services:
        # 只有 fff0 分支会二次升级：特征里出现 fff1 或 fff2 → 5
        return G5 if ({0xfff1, 0xfff2} & services[0xfff0]) else G4
    return None
```

升级条件在 Java 里是 `if (i == 4) SetAppType(5)`（`BluetoothHolder.java:226/240/284/298`），`i` 只在 `fff0` 服务下被赋 4，因此 **`ffe0` 分支即便暴露 `fff1/fff2` 也不会升到 G5**——这点必须按上表实现，否则会把非 M6 的 `ffe0` 设备选错收发特征。

> 上表是从 Java 分支静态推导的确定映射；**具体哪款车命中哪一组仍需真机一次服务发现来标定**（见 §6 采集清单），但候选集已闭合——不需要再靠猜。

---

## 3. 广播名白名单（扫描过滤）

`AppActivity.IsMyDevice()` — 声明 `AppActivity.java:727`，前缀链 `:729`（注意有两个名字带前导空格）：

```
M0  H1  M1  Mini  Plus  X1  X3  M6  GoKart  A6  MI  N3MTenbot
miniPLUS_  " MiniPro"  SFSO  V5Robot  "EO STREET"  XRIDER  TECAR
MAX  i10  NEXRIDE  E-WHEELS  E12  E9PRO  T10  KING  oP9G  Mentor
E9G  XT-  SPARK  EROBOT
```

共 33 个前缀。`onLeScan` 里用 `IsMyDevice(name)` 过滤后拼进 `_DevicName`（`*` 分隔）返回给 native（`GetScanBleList()`）。

对 iwalk 的直接用处：**这些前缀 + §1 的 G1–G5 服务 UUID 足够定位设备，无需再依赖加密 DEX**。

✅ **真机命中**：实测设备名 `MiniRobot:IWK1779887`，命中前缀 `Mini`（第 4 条），解释了官方 App 为何能列出并自动连接该设备。名字格式为 `MiniRobot:` + `IWK` + 7 位序列号，iwalk 可据此从广播名直接取设备序列号。

---

## 4. 数据管道与帧长约束

```
车→APP  onCharacteristicChanged(BluetoothService.java:51)
       → broadcastUpdate(ACTION_DATA_AVAILABLE, char)（:104-118）逐字节 &0xFF 后 putExtra(EXTRA_DATA)
       → BluetoothHolder.broadcastReceiver(:110-118) getByteArray(EXTRA_DATA)
       → AppActivity.data(intent, byte[])(:825-827) → native ReadMsg(bArr)

APP→车  native → AppActivity.SendMessage(byte[], int)(:317-325)  ← JNI 可调用静态方法
       → BluetoothHolder.writeCharacteristic(byte[])(:512-545)
       → BluetoothService.writeCharacteristic(byte[])(:357-458) 按 appType 选特征
       → characteristic.setValue(buf); gatt.writeCharacteristic(ch)
```

`ReadMsg2` 只在 .so 里导出（`nm -D` 见 `Java_org_cocos2dx_cpp_AppActivity_ReadMsg2`），**Java 侧没有对应的 `native` 声明**（`AppActivity` 只声明了 `ReadMsg`）。所以它是遗留导出符号，或需要另一版 APK/反射才能触达——本 APK 的数据回传只走 `ReadMsg`。

关键约束，务必写进 iwalk 的实现与文档：

1. **无 MTU 协商**：业务代码（`org/cocos2dx/**`、`com/loby/**`）内 `requestMtu` 0 命中 → ATT MTU 停留在默认 **23 字节，单包有效载荷 20 字节**。任何 >20B 的帧都必须分包。**✅ 真机已确认**：实测连接协商结果 `mtu~23`（§10），即车端不主动求大 MTU、通知包上限 20 B——iwalk 观察到的「拆分帧需重组」（E17）由此得到解释，且**不需要**在真机上另行验证。
2. **不等待写回调，但写是有应答的**（本节已按实测更正）：`writeCharacteristic(char)` 返回 `boolean` 但只用来打日志（`BluetoothService.java:374-379`，其余四个 appType 分支同形：`:394/399`、`:414/419`、`:434/439`、`:452/457`），调用方**不等** `onCharacteristicWrite`、不重试。`BluetoothGattWriteType` 从未设置 → 取 `WRITE_TYPE_DEFAULT`；实测 `6e400002` 属性为 `[Ww]`（Write with Response **和** Write Without Response 都支持），`WRITE_TYPE_DEFAULT` 在有 `WRITE` 位时解析为**有应答 Write Request**，不是我先前写的 no-response。含义变化：写不会静默丢失，但 Android GATT 层**同一时刻只允许一个 pending 写**，连发会拿 `GATT_BUSY`/写失败 → 实现侧必须**串行化并等回调**，而不是盲目节流。iwalk 侧建议：队列化写 + 监听 `onCharacteristicWrite` 成功后再发下一包，失败留原始字节做证据。
3. **通知去重**：`broadcastUpdate` 直接透传原始字节，无序号无时间戳，`ReadMsg` 收到的就是 ATT 包载荷。
4. **byte 有符号陷阱**：`broadcastUpdate` 里 `value[i] = (byte) (value[i] & UByte.MAX_VALUE)`（`BluetoothService.java:109`）——意图是无符号化，但 `UByte.MAX_VALUE` 是 `0xFF` 且强转回 `byte` 后仍为 -128..127，等于没改变语义。跨 JNI 到 native 按 `jbyte` 处理，**帧解析必须先 `& 0xFF`**，否则 `0xAA`/`0xFF` 这类帧头/长度字节会被当负数。
5. 未申请 `BLUETOOTH_PRIVILEGED`，无任何 GATT 缓存绕过手段。

---

## 5. 传输层安全属性（协议文档应记录的负面事实）

`BluetoothService.java:156`（全 APK 唯一的 `connectGatt` 调用点）：

```java
this.bluetoothGatt = remoteDevice.connectGatt(this, false, this.gattCallback);
```

- 未调用 `createBond()`；`connectGatt` 用 3 参重载（**没有** `transport`/`match` 参数，也没传 `TRANSPORT_LE`），无 `CONNECT_PRIVILEGED`。
- 业务代码（`org/cocos2dx/**`、`com/loby/**`）内 `requestMtu`、`WRITE_TYPE_SIGNED`、`setPeerRequiresEncryption`、`createBond`、`TRANSPORT_LE` 五个符号 grep 全部 0 命中；未见任何自定义 challenge / 握手认证帧。
- `onConnectionStateChange`（`BluetoothService.java:71-86`）只识别 `status=2(GATT_SUCCESS)` 与 `state=0`，其余状态码（含 `0x05 INSUFFICIENT_AUTHENTICATION`、`0x85` 等）**静默忽略**、不回抛、不重连。也就是说即使车端将来要求加密，这个 App 也只会表现为「连不上」。

结论：**GATT 层无配对、无加密、无鉴权**。**✅ 已获真机佐证**（§10）：未配对状态即可 Read `2a00/2a01/2a04`，且 `6e400002`(W/w) 与 `6e400003`(N) 均无加密/需鉴权标记——静态推断升级为实测事实。任何在 BLE 范围内、实现了 §1 任一 UUID 组的中央设备都能直接连上并向写特征下发帧。UI 上的密码功能（native 符号 `checkCode=%d,inputPsw=%d`、`UserInterface::CheckSetPassword`）是 App 本地设置页门禁，不构成链路认证。

> 对 iwalk 的影响：安全边界只能靠 App 侧「只读」自律（你们已有的 `write()` 抛 `StateError` 守卫是对的），不能假定链路上有对端认证。同时说明真机采集时**不需要配对即可拿到通知**，nRF Connect 直连即可。

---

## 6. U01 → 拆分为 U01a/U01b/U01c，剩余真机动作

原 U01「GATT 服务/特征 UUID 未知」的**静态不可得**判定需要更正：它只是对加壳的 5.4.2 成立。用本 APK，候选集与选择逻辑已完全确定。

| 新编号 | 内容 | 状态 | 依据 |
|---|---|---|---|
| U01a | UUID 候选集与 appType 选择逻辑 | ✅ 静态闭合 + 真机验证 | 本文 §1/§2 + §10.1 |
| U01b | 这台车命中 G1/G2/G3/G4/G5 中的哪一组 | ✅ **完成 = G1** | §10（仅手机 nRF 采集） |
| U01c | 各特征的真实 properties 位值 | ✅ **完成（本设备）** | §10 原始记录；其余 4 组属"未见到"非"已否定" |
| U02 | 通知帧完整目录 | ⛔ 仍缺报文 | 本 APK 未提供帧语义，需 §10.4 第 2 步 |
| U03 | 速度/电量/温度偏移与单位 | ⛔ 不变 | 已确认**只能靠报文**：设备无 `180F`/`180A` 标准服务（§10.2-2） |

**不要照抄 vendor 的属性判断**：`BluetoothHolder.readCharacteristic()`（`:434-452`，位判断在 `:444` 与 `:452`）与 `BluetoothHolder.writeCharacteristic(byte[])`（`:512-545`，位判断在 `:531` 与 `:538`）写成 `(properties | 2) > 0`、`(properties | 16) > 0`、`(properties | 8) > 0`——按位**或**恒为真，等于对所有特征都试 Read/Notify/Write。这是厂商 bug，iwalk 侧应使用 `(properties & 0x02) != 0` 等正确写法，并在 U01c 里记录真机实际位值。

采集清单（最小闭环）：
1. 扫描：记录设备名、是否命中 §3 前缀、广播里的 16/32-bit service data。
2. 连接（**不配对**）：dump 全部 service UUID → 全部 char UUID + `getProperties()` 十进制 → 全部 descriptor。
3. 按 §2 表反推 appType，与实测收发特征一致性核对（发一帧心跳，看通知从哪个特征回来）。
4. 顺带记录单通知包长是否 =20（验证 §4-1 无 MTU 协商）。

---

## 7. 应用层帧格式：本次交叉验证结果（未提升置信度）

iwalk 既有结论：

```
55 AA | L | CMD | A1 | A2 | payload(L-2 B) | chk_lo chk_hi      总长 = L + 6
chk = (~uint16(sum(bytes[2 .. L+3]))) & 0xFFFF ，小端序
ACK: A2 = CMD ^ 0x05
```

本次在新 APK 上做的事与结果：

- ✅ **校验算法自洽复算通过**：对 iwalk 记录的握手帧 `55 aa 02 ff 01 03 fa fe`（索引 0..7），L=byte[2]=2 → 总长 8 与 `L+6` 吻合；参与校验的区间 `bytes[2 .. L+3]` = `02 FF 01 03`，和 = `0x0105`；`(~0x0105) & 0xFFFF = 0xFFFA` → 小端 `fa fe`，与帧尾 byte[6..7] 一致。**注意这只是自证**：握手帧本身也来自 iwalk 同一条静态推导链（旧版 .so），所以这次复算证明的是「长度规则 + 校验规则 + 该帧三者互相自洽、无内部矛盾」，**不构成新的独立证据**，真机复核（U02）仍然必要。
- ❌ **帧常量未在本 .so 中找到**：`lib/arm64-v8a/libcpp_empty_test.so`（12.9 MB）rodata 内搜 `55 AA 02` 序列 0 命中、握手帧整串 0 命中、加密变体头 `00 FF 57 A4` 0 命中。`55 AA` 二字节散碰 5 处，均为无关数据。**因此不能声称本 APK 证实了帧头/长度/ACK 规则**——帧很可能是运行期按指令立即数构造而非数据段常量，静态搜不到属正常，但也确实无佐证力。
- ❌ 本 APK 无 Lua/JS 脚本层（`assets/` 只有 `.csb` 场景、`.png`、5 个 `gameString*.xml` + `Config.xml`），帧逻辑只能在 stripped 的 C++ 里，静态路线到此为止。

**结论**：§7 的帧格式置信度维持 iwalk 原判定（帧头/长度/校验：高·静态；ACK：高·静态），**不引入新证据也不下调**，U02/U03 仍是真机阻塞。本文的确定增量只在传输层 §1–§5。

---

## 8. 附：同一 APK 中与协议相关的其它确认

- 传输类型：BLE 强制（manifest `<uses-feature android:name="android.hardware.bluetooth_le" required="true">`）。
- 服务生命周期：`BluetoothLeService extends BluetoothService`（`BluetoothLeService.java` 是 5 行空壳，全部逻辑在父类），继承链 `BluetoothService → BaseService extends Service`，用 `BaseService.LocalBinder.getService()` 同进程绑定；`onServiceConnected`（`BluetoothHolder.java:56-70`）里若 `deviceAddress`/`deviceName` 均非空即自动 `connect()`。
- 重连地址持久化：写于 `AppSaveData.getInstance().setDataForString("deviceAddress", …)`（`BluetoothHolder.java:337`），读于 `reConnect()`（`:422-431`），落盘文件即 `SharedPreferences("DATABASE")`——注意该文件因 `allowBackup="true"` 可被 `adb backup` 取出，含配对设备地址。
- 断连/错误状态处理见 §5 最后一条（静默忽略），采集时若看到奇怪 `status` 值请原样记录，vendor 代码不会上报。
- 该 build 的 `AndroidManifest.xml` 全文已确认**不含** `READ_PHONE_STATE`/`WRITE_EXTERNAL_STORAGE`，但保留 `ACCESS_FINE_LOCATION`+`ACCESS_COARSE_LOCATION`（BLE 扫描需要，Android 11 及以下）。

---

## 9. 一句话给 iwalk 的落地建议

把 `analysis/protocol_findings_table.md` 里 `GATT UUID | 未知` 那一行替换为本文 §1 的表 + §2 的状态机 + §10 的实测结论；`protocol_dart/lib/ibwalk_frame.dart` 可据此新增 `Transport` 枚举（G1..G5）与 `selectTransport(services, deviceName)`，纯函数、可先写单测（用 §2 表的 6 种输入，其中 G1 一行已有 §10 的真机输入/输出可作固定测试向量）。剩下只差通知报文，不再差任何传输层信息。

---

## 10. 真机服务树实测（U01b / U01c 关闭）

采集方式：**仅手机**，nRF Connect 手工 scan → connect（**未配对**）→ 服务发现 → 抄录。无 adb、无电脑。
时间 2026-10-20。设备 `EE:F6:9B:**:**:**`（完整 MAC 只留在本地采集记录，分享时脱敏，遵 doc.md 约定）。

原始输出（逐字保留，未识别字段不加工）：

```
mtu~23
ADV serviceUuids=[6e400001-b5a3-f393-e0a9-e50e24dcca9e] manufacturerData={} serviceData={} txPowerLevel=null connectable=true
S 00001800-0000-1000-8000-00805f9b34fb
  C 00002a00-0000-1000-8000-00805f9b34fb [RW]
  C 00002a01-0000-1000-8000-8000-00805f9b34fb [R]
  C 00002a04-0000-1000-8000-00805f9b34fb [R]
S 00001801-0000-1000-8000-00805f9b34fb
  C 00002a05-0000-1000-8000-00805f9b34fb [I] desc=[00002902-...]
S 6e400001-b5a3-f393-e0a9-e50e24dcca9e
  C 6e400003-b5a3-f393-e0a9-e50e24dcca9e [N] desc=[00002902-...]
  C 6e400002-b5a3-f393-e0a9-e50e24dcca9e [Ww]
```

> 注：`2a01` 那行的 UUID 在原始抄录中被手误多打了一处 `8000-`，正确值应为 `00002a01-0000-1000-8000-00805f9b34fb`（标准 Appearance）。已按标准值理解，不改原始记录。

### 10.1 与静态预测的比对（事前写好的结论，事后核对）

| 预测项 | §1/§2 的预测 | 实测 | 结果 |
|---|---|---|---|
| 传输组 | 广播即含 `6e400001` → 走 G1 | 广播与 GATT 均为 `6e400001` | ✅ 命中 |
| appType | `6e400001` 分支强制 `_isSmallType=1` → appType **1** | — | ✅ 推定成立（收发特征与 G1 完全一致，见下） |
| 写特征（APP→车） | `6e400002` | `6e400002 [Ww]` | ✅ |
| 通知特征（车→APP） | `6e400003` | `6e400003 [N]` | ✅ |
| CCCD | `00002902` | `00002902`（挂在 `2a05` 与 `6e400003`） | ✅ |
| 只用 notification 不用 indication | 业务侧无 `ENABLE_INDICATION_VALUE` | 数据通道 `6e400003` = `[N]`（notify）；`[I]` 只出现在标准 `2a05 Service Changed` | ✅ |
| MTU | 无 `requestMtu` → 23（单包 20B） | `mtu~23` | ✅ |
| 链路安全 | 无配对/无加密/无鉴权 | 未配对即可 Read 标准特征；`W/w/N` 无加密标记 | ✅ |

**§1–§5 的传输层结论全部经真机验证成立。** 唯一被实测**推翻**的是我原先在 §4-2 的附注（猜这会是 WRITE_TYPE_NO_RESPONSE）——已就地更正，见该节。

### 10.2 实测新增信息（静态分析给不出的部分）

1. **设备是标准 GATT peripheral 配置**：带 `1800` Generic Access（`2a00` Device Name `[RW]`、`2a01` Appearance `[R]`、`2a04` Preferred Connection Params `[R]`）与 `1801` Generic Attribute（`2a05` Service Changed `[I]`）。iwalk 现有工具链可直接把 `2a04` 当作**连接参数（min/max interval、latency、timeout）的只读来源**——这决定遥测通知的节奏下限，且是纯只读、零风险。
2. **没有 `180F` Battery Service，也没有 `180A` Device Information**。所以电量、型号、固件版本、充电状态**都不走标准 GATT**，只存在于 `6e400003` 通知的 payload 里 → 直接确认 **U03 必须靠报文，GATT 层拿不到**。
3. **广播主包里没有 Local Name**（`manufacturerData={}`、`serviceData={}`、`txPowerLevel=null`），但 nRF 设备页顶部确实显示了名字 → **名字在 SCAN RESPONSE 分包里**（§10.5 已确认值为 `MiniRobot:IWK1779887`）。两条影响：
   - 官方 App 的 `IsMyDevice(getName())` 依赖 Android 缓存的广播名，即来自 scan response。iwalk 采集/复现时**必须同时记录 ADV 与 SCAN RESPONSE**，只抓主包会误判「无名字」。
   - 由于这台车走 `6e400001` 分支，`_isSmallType` 被强制置 1，**名字是否 `M6` 开头对 appType 判定没有影响**——G1 的选定不依赖名字。
4. `6e400002` 同时支持 `W`(Write with Response) 与 `w`(Write Without Response)，`6e400003` 只 `[N]` 不可 Read → **车→APP 只有通知、没有可读回通道**，与 §4 的单向管道描述一致；也意味着任何"读状态"操作都必须靠发帧后等通知，不能靠 GATT Read。

### 10.3 状态变化

| 编号 | 之前 | 现在 |
|---|---|---|
| U01a | 静态闭合 | ✅ 静态闭合 **且真机验证** |
| U01b | ⏳ 待真机 | ✅ **完成** = G1（写 `6e400002` / 通知 `6e400003`） |
| U01c | ⏳ 待真机 | ✅ **完成**（本设备实测属性位见 §10 原始记录；其余 4 组仍属"未见到"，不是"已否定"） |
| 阶段二 | 静态可定约 60% | 基本完成：广播名/型号/服务树/属性/连接参数已实测；**仅剩「连接前后广播变化」** |
| 型号未知(doc.md 二) | 未知 | ✅ `MiniRobot` 产品线，序列号 `IWK1779887` |
| U02 / U03 / U04 | ⛔ |  **未变**，仍缺 `6e400003` 的通知报文（唯一剩余项） |

### 10.5 标准特征实测值（2026-10-20 截图回填）

| 特征 | 属性 | 实测值 | 解读 |
|---|---|---|---|
| `2a00` Device Name | READ, WRITE | `MiniRobot:IWK1779887` | 广播名与 GATT 名一致；前缀 `Mini` **命中 §3 白名单**；`IWK` = iWalk，后 7 位 `1779887` 为序列号 |
| `2a01` Appearance | READ | `[4660] Unknown` | nRF 此处显示十进制，4660 = **`0x1234`**，非 SIG 分配值 → 判定为厂商占位常量（**猜测，未确认**，见下） |
| `2a04` Preferred Conn Params | READ | Interval `20.00–75.00 ms`，Max Latency `0`，Supervision Timeout Multiplier `400` | 见 10.6 的工程推论 |
| `2a05` Service Changed | INDICATE + CCCD `2902` | — | 标准 GATT server 带持久属性库 |

连接状态截图为 **`CONNECTED` / `NOT BONDED`** → §5「无配对、无加密、无鉴权」由静态推断正式转为**实证**（未绑定即可 Read 标准特征、且服务树无加密标记）。

**型号问题落地**：设备自报名 `MiniRobot`，与参考客户端（`com.loby.balance.minirobot` / `com.loby.balance.car.google` 两个发行渠道）**同一产品线**，这是族系推断的第二条独立实证。

> 分享本文件时请一并脱敏序列号 `1779887`（与 MAC 同级处理）。

### 10.6 `2a04` 给出的可执行工程参数

1. **通知节奏上限 = 1/20ms = 50 包/秒**，且 `Max Latency = 0` → 从机不会被允许跳过连接事件，所以车端发帧的实际节拍只会 ≥20 ms 粒度。U02 归类「周期性遥测 vs 事件通知」时，**20 ms 是最小可分辨间隔**，小于它的抖动都属测量噪声，不要据此推断协议结构。
2. **拆帧重组超时必须 > 75 ms**。一条逻辑帧若被 20 B 上限拆成两包，两包间隔最坏是一个 interval 上限 75 ms。iwalk 的 `StreamDecoder` 若用 ≤75 ms 的空闲判定，会把长帧误切成两条并产生假 `rejected`。建议**空闲超时 250–500 ms**（覆盖 3 个 interval），并把该阈值做成可配置项写进采集工具。
3. **Supervision Timeout = 400 × 10 ms = 4 s** → 车断电/走出范围后，App 最长 4 s 才收到链路断开。iwalk 的 UI「断开」判定若快于 4 s，只可能是应用层自己超时，不要误报成链路事件。
4. Interval 范围宽（20–75 ms）意味着**中心端可以协商**；但官方 App 从不设 MTU 也不改参数（§4），所以对照实验时**不要**用 nRF 的 "Configure MTU" 或改连接参数，否则与官方行为不可比。

### 10.7 Appearance `0x1234` 的处理方式

标为**猜测**，理由：`0x1234` 是常见占位值，且 SIG Appearance 表里 `0x4660`/`4660` 都无对应条目。两种待验可能：(a) 固件未初始化该字段，纯占位；(b) 厂商复用它编码硬件版本/配置。**验证成本极低**：若后续能拿到第二台不同型号/批次的车，对比 `2a01` 是否变化即可。在此之前，iwalk 侧不要读 `2a01` 做任何判断。

### 10.4 下一步（仍只需手机）

服务树已经齐了，**唯一还缺的是通知数据**。同一台车、同样操作，多做两步：

1. ~~连上后先 Read `2a00`/`2a01`/`2a04`~~ ✅ **已完成，值见 §10.5**。
2. 点 `6e400003` → Subscribe（= 写 CCCD `0x0001`，只读白名单内），静置抄 **5–10 条**通知 hex 并**保留到达顺序与间隔**；若手推/挪动车身（轮子空转、车离地等安全方式）再抄一批，两批对照即 U03 的受控变量。

判定预期：每包 ≤20 B；若一条逻辑帧被拆成两包，应按 §7 规则可重组，且帧头 `55 AA`、L 字段与总长 `L+6` 自洽。若通知里出现 `55 AA`，则 §7 的帧格式**首次拿到真机证据**，可把置信度从「高·静态」升为「已确认」；若不出现，说明 11.3.8 换了编码（可能有 §7 提到的混淆/加密变体），需要重新推导。

