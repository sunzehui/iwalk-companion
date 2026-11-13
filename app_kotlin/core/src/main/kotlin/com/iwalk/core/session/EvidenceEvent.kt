package com.iwalk.core.session

import com.iwalk.core.analysis.frameGroupKey
import com.iwalk.core.decode.FieldValue
import com.iwalk.core.model.TelemetryField
import com.iwalk.protocol.toHex
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/** 导出用本地时间戳：与 Dart `toIso8601String()` 同形（6 位小数，无时区后缀）。 */
val EXPORT_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS").withZone(ZoneId.systemDefault())

/**
 * 被动证据时间线上的一条（notes/domain-logic.md §4.2 第 6 步）。
 *
 * 事件只因“收到一帧”而存在；之后导入标定**不会**改写历史（[calibrated] 与 [values]
 * 都是接收时刻的快照）。它只记录车端主动发来的帧，绝不包含应用发出的轮询/锁车帧。
 */
data class EvidenceEvent(
    val at: Instant,
    val cmd: Int,
    val a1: Int,
    val a2: Int,
    val payload: ByteArray,
    /** 接收时刻 (cmd,a1,a2) 是否有标定组匹配。 */
    val calibrated: Boolean,
    /** 本帧解出的字段（只含被这一帧更新的字段）。 */
    val values: Map<TelemetryField, FieldValue>,
) {
    /** 小写零填充的组键 `0xNN/0xNN/0xNN`（§4.2）。 */
    val groupKey: String get() = frameGroupKey(cmd, a1, a2)

    /** 仅 payload 的小写 hex（不含帧头与校验和）。 */
    val payloadHex: String get() = payload.toHex()

    /** 换算前的原始值（四舍五入）；字段未出现时为 null。 */
    fun rawOf(field: TelemetryField): Long? = values[field]?.raw?.roundToLong()

    /** 警告码/错误码任一非零——只是“看到了非零原始码”，不代表故障分类。 */
    val hasNonZeroCode: Boolean
        get() = listOf(TelemetryField.warningCode, TelemetryField.errorCode)
            .any { rawOf(it)?.let { raw -> raw != 0L } == true }

    /** 导出键序：at/group/payload_hex/calibrated/values（§7.3）。 */
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "at" to EXPORT_TIME.format(at),
        "group" to groupKey,
        "payload_hex" to payloadHex,
        "calibrated" to calibrated,
        "values" to values.entries.associate { (field, value) ->
            field.name to linkedMapOf(
                "raw" to value.raw,
                "value" to value.value,
                "profile" to value.profile.name,
                "mapping" to value.mapping.toJson(),
            )
        },
    )

    override fun equals(other: Any?): Boolean = other is EvidenceEvent &&
        at == other.at && cmd == other.cmd && a1 == other.a1 &&
        payload.contentEquals(other.payload) && calibrated == other.calibrated &&
        values == other.values

    override fun hashCode(): Int {
        var result = at.hashCode()
        result = 31 * result + cmd
        result = 31 * result + a1
        result = 31 * result + a2
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + calibrated.hashCode()
        result = 31 * result + values.hashCode()
        return result
    }
}
