package com.iwalk.core.session

/**
 * 链路事实（notes/domain-logic.md §2.6、§7.3 的 `evidenceFacts()`）。
 *
 * 目前服务于设备页「设备与链路」与后续证据导出；全部是只读事实，不含字段语义。
 * demo 传输没有 GATT 事实，[BluetoothTransport.linkFacts] 保持 null。
 */
data class LinkFacts(
    val kind: String = "ble",
    val advertisementName: String = "",
    val mtuNow: Int = 23,
    val maxPayloadPerPacket: Int = 20,
    val transportGroup: String? = null,
    val serviceUuid: String? = null,
    val writeUuid: String? = null,
    val notifyUuid: String? = null,
    val lockControl: String = "unavailable",
    val writeCharacteristicAvailable: Boolean = false,
    val gattTree: List<String> = emptyList(),
    val standardReads: Map<String, String> = emptyMap(),
) {
    /** 设备页链路行摘要：未知分组时回落「链路待识别」。 */
    val summary: String
        get() = transportGroup?.let { "$it · MTU $mtuNow" } ?: "链路待识别"
}
