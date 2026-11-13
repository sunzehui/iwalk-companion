package com.iwalk.companion.ble

/**
 * 一条扫描结果（notes/ui-device-scan.md §3.3）。
 *
 * 只保留展示/连接需要的字段，不持有 Android 对象：连接时由
 * [AndroidRealLinkFactory] 用地址换回 `BluetoothDevice`，JVM 测试可直接构造。
 */
data class ScannedDevice(
    val address: String,
    /** 广播名或平台名；可能为空。 */
    val name: String,
    val rssi: Int,
    val whitelisted: Boolean,
    val serial: String?,
    val advertisedServices: List<String>,
) {
    /** 列表主标题：冒号前部分；空名回落「未命名设备」（Dart `deviceBaseName`）。 */
    val baseName: String
        get() {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return "未命名设备"
            val colon = trimmed.indexOf(':')
            return if (colon > 0) trimmed.substring(0, colon) else trimmed
        }

    /** MAC 掩码：`AA:BB:CC:…:EE:FF`（Dart `maskMac`）。 */
    val maskedMac: String
        get() {
            val parts = address.split(':')
            if (parts.size != 6) return address
            return "${parts[0]}:${parts[1]}:${parts[2]}:…:${parts[4]}:${parts[5]}"
        }
}

/** 序列号掩码：保留末 4 位；长度 ≤4 时只显示省略号（Dart `maskSerial`）。 */
fun maskSerial(serial: String?): String? {
    val s = serial?.trim().orEmpty()
    if (s.isEmpty()) return null
    if (s.length <= 4) return "…"
    return "…${s.takeLast(4)}"
}

/** 16-bit 标准 UUID 的短写；非标准 UUID 原样返回（Dart `shortUuid`）。 */
fun shortUuid(uuid: String): String {
    val suffix = "-0000-1000-8000-00805f9b34fb"
    return if (uuid.endsWith(suffix)) uuid.substring(0, 8) else uuid
}
