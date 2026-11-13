package com.iwalk.core.decode

import com.iwalk.core.model.CalibrationProfile
import com.iwalk.core.model.CalibrationSet
import com.iwalk.core.model.FieldMapping
import com.iwalk.core.model.TelemetryField
import com.iwalk.core.model.read
import com.iwalk.protocol.IbwalkFrame
import java.time.Duration
import java.time.Instant

/**
 * 遥测解码（notes/domain-logic.md §3.7）。每个读数带出处（profile + mapping + 时间），
 * 界面据此区分"当前实测 / 过期 / 从未标定"，未标定字段显示 `—` 而不是猜测值。
 */

/** 一个带出处的解码值。[raw] 是换算前的原始整数（保留为 Double，对齐 Dart）。 */
data class FieldValue(
    val field: TelemetryField,
    val raw: Double,
    val value: Double,
    val at: Instant,
    val profile: CalibrationProfile,
    val mapping: FieldMapping,
)

/** 某一刻应用所知的全部读数（§3.7）。 */
data class TelemetrySnapshot(
    val updatedAt: Instant,
    val framesDecoded: Int = 0,
    val bytesUndecoded: Int = 0,
    val values: Map<TelemetryField, FieldValue> = emptyMap(),
    val uncalibratedFrames: Int = 0,
) {
    operator fun get(field: TelemetryField): Double? = values[field]?.value

    /** 设备页用 3 秒判定 live 徽标（§3.7）。 */
    fun isStale(maxAge: Duration, now: Instant = Instant.now()): Boolean =
        Duration.between(updatedAt, now).abs() > maxAge

    companion object {
        val empty = TelemetrySnapshot(updatedAt = Instant.EPOCH)
    }
}

/** 按标定集合把帧解码成字段值；不接触链路，也不做任何持久化。 */
class TelemetryDecoder(private val calibration: CalibrationSet) {

    private val values = mutableMapOf<TelemetryField, FieldValue>()
    private var frames = 0
    private var uncalibrated = 0

    val framesDecoded: Int get() = frames
    val uncalibratedFrames: Int get() = uncalibrated

    fun valueOf(field: TelemetryField): FieldValue? = values[field]

    /**
     * 喂入一帧：任何帧都会计入 [framesDecoded]；(cmd,a1,a2) 没有标定组则计入
     * [uncalibratedFrames] 并返回空；否则逐映射（越界跳过、不猜测），返回被更新的字段。
     */
    fun ingest(frame: IbwalkFrame, at: Instant = Instant.now()): List<TelemetryField> {
        frames++
        val profile = calibration.match(frame.cmd, frame.a1, frame.a2) ?: run {
            uncalibrated++
            return emptyList()
        }
        val touched = mutableListOf<TelemetryField>()
        for (mapping in profile.fields) {
            if (!mapping.fits(frame.payload.size)) continue
            val raw = mapping.type.read(frame.payload, mapping.offset) ?: continue
            values[mapping.field] = FieldValue(
                field = mapping.field,
                raw = raw.toDouble(),
                value = raw * mapping.scale + mapping.bias,
                at = at,
                profile = profile,
                mapping = mapping,
            )
            touched.add(mapping.field)
        }
        return touched
    }

    /** 换算前的原始值（诊断页要同时展示两值）；放不下时为 null。 */
    fun rawOf(frame: IbwalkFrame, mapping: FieldMapping): Int? =
        if (mapping.fits(frame.payload.size)) {
            mapping.type.read(frame.payload, mapping.offset)?.toInt()
        } else {
            null
        }

    fun snapshot(bytesUndecoded: Int = 0, at: Instant = Instant.now()): TelemetrySnapshot =
        TelemetrySnapshot(
            updatedAt = at,
            framesDecoded = frames,
            bytesUndecoded = bytesUndecoded,
            values = values.toMap(),
            uncalibratedFrames = uncalibrated,
        )
}
