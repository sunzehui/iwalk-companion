package com.iwalk.protocol

/**
 * BLE GATT 传输组选择（notes/domain-logic.md §2）。
 *
 * 来源：厂商 App `BluetoothHolder.searchGattServices` v11.3.8；
 * 真机实测 G1（Nordic UART）见 `analysis/08_ble_gatt_spec.md` §10。
 *
 * 这里只保留纯函数，不依赖 Android。厂商实现里有一个不可达分支
 * （`UUID_SERVICE4 == ffe0`）和一个恒为真的按位或属性判断，均不复制。
 */

enum class TransportGroup { g1NordicUart, g2Ae00, g3Ffe0, g4Fff0, g5Fff0Alt }

/** 一个传输组的三条 UUID：服务、App→车写入、车→App 通知。 */
data class TransportPair(
    val group: TransportGroup,
    val serviceUuid: String,
    val writeUuid: String,
    val notifyUuid: String,
)

private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

/** 通知开关描述符（CCCD）。 */
const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

val G1_NORDIC_UART = TransportPair(
    group = TransportGroup.g1NordicUart,
    serviceUuid = "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
    writeUuid = "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
    notifyUuid = "6e400003-b5a3-f393-e0a9-e50e24dcca9e",
)

val G2_AE00 = TransportPair(
    group = TransportGroup.g2Ae00,
    serviceUuid = "0000ae00$BASE_SUFFIX",
    writeUuid = "0000ae01$BASE_SUFFIX",
    notifyUuid = "0000ae02$BASE_SUFFIX",
)

val G3_FFE0 = TransportPair(
    group = TransportGroup.g3Ffe0,
    serviceUuid = "0000ffe0$BASE_SUFFIX",
    writeUuid = "0000fff3$BASE_SUFFIX",
    notifyUuid = "0000fff4$BASE_SUFFIX",
)

val G4_FFF0 = TransportPair(
    group = TransportGroup.g4Fff0,
    serviceUuid = "0000fff0$BASE_SUFFIX",
    writeUuid = "0000fff3$BASE_SUFFIX",
    notifyUuid = "0000fff7$BASE_SUFFIX",
)

val G5_FFF0_ALT = TransportPair(
    group = TransportGroup.g5Fff0Alt,
    serviceUuid = "0000fff0$BASE_SUFFIX",
    writeUuid = "0000fff2$BASE_SUFFIX",
    notifyUuid = "0000fff1$BASE_SUFFIX",
)

const val FFF0_SERVICE = "0000fff0$BASE_SUFFIX"
const val FFE0_SERVICE = "0000ffe0$BASE_SUFFIX"
const val FFF1_CHAR = "0000fff1$BASE_SUFFIX"
const val FFF2_CHAR = "0000fff2$BASE_SUFFIX"

/** 官方 App 接受的 33 个设备名前缀（§2.3，顺序与 Dart 常量一致）。 */
val DEVICE_NAME_PREFIXES: List<String> = listOf(
    "M0", "H1", "M1", "Mini", "Plus", "X1", "X3", "M6", "GoKart", "A6", "MI",
    "N3MTenbot", "miniPLUS_", " MiniPro", "SFSO", "V5Robot", "EO STREET",
    "XRIDER", "TECAR", "MAX", "i10", "NEXRIDE", "E-WHEELS", "E12", "E9PRO",
    "T10", "KING", "oP9G", "Mentor", "E9G", "XT-", "SPARK", "EROBOT",
)

/** 大小写不敏感的唯一归一化：只做 lowercase，不做 16-bit 展开（§2.1）。 */
fun normaliseUuid(uuid: String): String = uuid.lowercase()

/** 空名/未知名一律拒绝；大小写敏感、不裁剪（§2.3）。 */
fun isSupportedDeviceName(name: String?): Boolean {
    if (name.isNullOrEmpty()) return false
    return DEVICE_NAME_PREFIXES.any { name.startsWith(it) }
}

/**
 * `MiniRobot:IWK<serial>` → `<serial>`（§2.4）。
 * 没有 `IWK`、`IWK` 后没有字符、或 trim 后为空 → null；不校验长度/数字。
 */
fun deviceSerialFromName(name: String?): String? {
    if (name == null) return null
    val i = name.indexOf("IWK")
    if (i < 0 || i + 3 >= name.length) return null
    val serial = name.substring(i + 3).trim()
    return serial.ifEmpty { null }
}

/**
 * 从一个服务发现结果选择传输组（§2.1）。
 *
 * 复刻两个厂商怪癖，不要“修”：
 * 1. `ffe0` 最先判断，遮蔽 G1/G2/`fff0`；M6 前缀才把它当 G1，其它一律 G3；
 * 2. G5 升级只发生在 `fff0` 分支内。
 */
fun selectTransport(
    services: Map<String, Set<String>>,
    deviceName: String?,
): TransportPair? {
    // 允许调用方传入大小写混合的 map；函数内部统一归一化（§2.1 真值表末行）。
    val normalised: Map<String, Set<String>> = services.entries.associate { (service, chars) ->
        normaliseUuid(service) to chars.mapTo(mutableSetOf()) { normaliseUuid(it) }
    }

    val small = if (deviceName == null || !deviceName.startsWith("M6")) 1 else 0

    if (normalised.containsKey(FFE0_SERVICE)) {
        return if (small == 1) G3_FFE0 else G1_NORDIC_UART
    }
    if (normalised.containsKey(G1_NORDIC_UART.serviceUuid)) return G1_NORDIC_UART
    if (normalised.containsKey(G2_AE00.serviceUuid)) return G2_AE00
    if (normalised.containsKey(FFF0_SERVICE)) {
        val chars = normalised[FFF0_SERVICE].orEmpty()
        // 只有 fff0 分支允许升级到 G5。
        return if (chars.contains(FFF1_CHAR) || chars.contains(FFF2_CHAR)) G5_FFF0_ALT else G4_FFF0
    }
    return null
}

/** 固定 MTU 23、单包 20B，不做 `requestMtu`（§2.5）。 */
object LinkBudget {
    const val defaultMtu: Int = 23
    const val maxPayloadPerPacket: Int = defaultMtu - 3

    /** `2a04` 偏好的连接间隔上限。 */
    const val maxIntervalMillis: Int = 75

    /** 建议的重组空闲上限（仅文档；解码器本身没有定时器）。 */
    const val reassemblyIdleTimeoutMillis: Int = 250
}

/** GATT 树里一行特征的属性缩写，顺序固定 `R W w N I`（§2.6）。 */
fun propertyFlags(
    read: Boolean,
    write: Boolean,
    writeWithoutResponse: Boolean,
    notify: Boolean,
    indicate: Boolean,
): String = buildString {
    if (read) append('R')
    if (write) append('W')
    if (writeWithoutResponse) append('w')
    if (notify) append('N')
    if (indicate) append('I')
}
