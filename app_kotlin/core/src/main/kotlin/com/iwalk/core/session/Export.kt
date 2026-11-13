package com.iwalk.core.session

import com.iwalk.core.analysis.FrameGroupStats
import com.iwalk.core.analysis.analyseFrames
import com.iwalk.protocol.IbwalkFrame
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 导出文件名的时间段：Dart `toIso8601String()` 去掉 `:`/`.`/`-`，保留 `T` 与 6 位小数。 */
private val EXPORT_ID_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSSSSS")

/** 导出键序与 JSON 形状逐字对齐 notes/domain-logic.md §7（iwapp-evidence-v1）。 */

/** 一次出站锁帧尝试（工单 09 产生；导出 schema 现在就要占位）。 */
data class LockCommandRecord(
    val at: Instant,
    val lock: Boolean,
    val hex: String,
    val ok: Boolean,
    val simulated: Boolean,
    val error: String? = null,
) {
    val actionLabel: String get() = if (lock) "关锁" else "解锁"

    /** `vehicle_state` 恒为 `unconfirmed`：BLE 写成功不证明车端执行（§7.3）。 */
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "ts" to EXPORT_TIME.format(at),
        "action" to if (lock) "lock" else "unlock",
        "hex" to hex,
        "ok" to ok,
        "simulated" to simulated,
        "error" to error,
        "vehicle_state" to "unconfirmed",
    )
}

/** 链路事实 → 导出 `transport` 里的扁平键（§2.6 `evidenceFacts()`）。 */
fun LinkFacts.evidenceFacts(): Map<String, Any?> = linkedMapOf(
    "kind" to kind,
    "advertisement_name" to advertisementName,
    "mtu_now" to mtuNow,
    "max_payload_per_packet" to maxPayloadPerPacket,
    "transport_group" to transportGroup,
    "service_uuid" to serviceUuid,
    "write_uuid" to writeUuid,
    "notify_uuid" to notifyUuid,
    "lock_control" to lockControl,
    "write_characteristic_available" to writeCharacteristicAvailable,
    "gatt_tree" to gattTree,
    "standard_reads" to standardReads,
)

/** `<sessionId>_<ts>`；ts 是本地时间且无冒号/点/连字符（§7.1）。 */
fun exportId(sessionId: String, now: LocalDateTime): String =
    "${sessionId}_${EXPORT_ID_TIME.format(now)}"

/**
 * 把会话原始行（`"<iso> <hex>"`）重新包成 procedure D 记录。
 *
 * - 在**第一个空格**处切分；无空格的行整行视为 hex、时间回退 `now`（§7.2）；
 * - 时间解析失败同样回退 `now`；
 * - `source` 恒为 `iwalk_kotlin`（规格 §8 的唯一有意差异），`app_state` 恒为空串；
 * - `service_uuid`/`char_uuid` 恒为 null 且必须写出。
 */
fun buildRawJsonLines(
    rawLines: List<String>,
    deviceId: String,
    sessionId: String,
    now: Instant,
): List<String> = rawLines.map { line ->
    val space = line.indexOf(' ')
    val stamp = if (space > 0) line.substring(0, space) else ""
    val hex = if (space > 0) line.substring(space + 1) else line
    val ts = stamp.ifEmpty { null }?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: now
    val record = linkedMapOf<String, Any?>(
        "ts" to EXPORT_TIME.format(ts),
        "device" to deviceId,
        "session" to sessionId,
        "source" to "iwalk_kotlin",
        "direction" to "notify",
        "service_uuid" to null,
        "char_uuid" to null,
        "hex" to hex,
        "step" to "live",
        "app_state" to "",
        "complete" to true,
        "confidence" to "high",
        "note" to "",
    )
    toCompactJson(record)
}

/** 导出快照一致性（E60）：四份集合在入口取一次只读快照，行数/帧数天然相等。 */
fun buildEvidenceJson(
    sessionId: String,
    transport: BluetoothTransport,
    frames: List<IbwalkFrame>,
    rawLineCount: Int,
    events: List<EvidenceEvent>,
    lockCommands: List<LockCommandRecord>,
    exportedAt: LocalDateTime,
): String {
    val facts = transport.linkFacts?.evidenceFacts() ?: linkedMapOf<String, Any?>("kind" to "unknown")
    val transportMap = linkedMapOf<String, Any?>(
        "id" to transport.id,
        "label" to transport.label,
        "is_demo" to transport.isDemo,
    ).apply { putAll(facts) }

    val groups: List<FrameGroupStats> = analyseFrames(frames)
    val evidence = linkedMapOf<String, Any?>(
        "schema" to "iwapp-evidence-v1",
        "app" to "iwalk_kotlin",
        "exported_at" to EXPORT_TIME.format(exportedAt.atZone(ZoneId.systemDefault()).toInstant()),
        "session" to sessionId,
        "transport" to transportMap,
        "frames_total" to frames.size,
        "raw_packets" to rawLineCount,
        "frame_groups" to groups.map { it.toJson() },
        "evidence_timeline" to events.map { it.toJson() },
        "commands_sent" to lockCommands.map { it.toJson() },
        "note" to EXPORT_NOTE,
    )
    return toPrettyJson(evidence)
}

/** Dart 侧逐字搬来的免责声明（§7.3 note）。 */
const val EXPORT_NOTE =
    "frame_groups 仅含字节统计，不含单位/语义结论；字段映射需与界面读数对照后写入标定配置（U03）。" +
        "evidence_timeline 是被动通知的解码快照，不包含车辆查询或车端状态。" +
        "commands_sent 仅记录应用尝试的固定锁帧；无车端 ACK 或锁态回读，成功不代表车辆已执行。"

// ——————————————————————— JSON 落地 ———————————————————————

/** 紧凑单行（JSONL 用）。`JsonElement.toString()` 保序、紧凑。 */
fun toCompactJson(value: Any?): String = toElement(value).toString()

/** 两空格缩进（evidence 文件用，对齐 Dart `JsonEncoder.withIndent('  ')`）。 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private fun toPrettyJson(value: Any?): String {
    val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
    return pretty.encodeToString(JsonElement.serializer(), toElement(value))
}

private fun toElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toElement(v) })
    is List<*> -> JsonArray(value.map { toElement(it) })
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}
