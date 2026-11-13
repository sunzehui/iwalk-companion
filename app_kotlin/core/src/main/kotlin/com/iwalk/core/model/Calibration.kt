package com.iwalk.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 标定模型（notes/domain-logic.md §3）。遥测语义未验证，字段位置只来自标定文件；
 * 未标定的字段界面显示 `—`，绝不猜测（工单 02 验收第 5 条）。
 */

/** 14 个可标定字段（含兜底 `unknown`），JSON 名即枚举名（§3.1）。 */
enum class TelemetryField {
    speed,
    batteryPercent,
    estimatedRange,
    batteryVoltage,
    temperature,
    current,
    odometer,
    workMode,
    warningCode,
    errorCode,
    motorPower,
    pitch,
    roll,
    unknown,
    ;

    companion object {
        /** 未知字符串 → [unknown]，永不抛错（§3.4 导入宽松）。 */
        fun fromName(name: String?): TelemetryField =
            entries.firstOrNull { it.name == name } ?: unknown
    }
}

/** 6 种小端标量类型与字节宽度（§3.1）。 */
enum class ScalarType(val byteLength: Int) {
    u8(1),
    i8(1),
    u16le(2),
    i16le(2),
    u32le(4),
    i32le(4),
    ;

    companion object {
        /** 未知/缺失类型串 → [u8]（§3.2 fromJson 缺省）。 */
        fun fromName(name: String?): ScalarType =
            entries.firstOrNull { it.name == name } ?: u8
    }
}

/**
 * 按类型读取小端整数。累加在 `Long` 上完成（notes §3.1 的 Kotlin 警告、§10-14）。
 * 越界或负偏移返回 null——Dart 对负偏移会抛（§10-10），Kotlin 只跳过，
 * 保证恶意 JSON 不能让会话解码崩溃。
 */
fun ScalarType.read(payload: ByteArray, offset: Int): Long? {
    if (offset < 0 || offset > payload.size - byteLength) return null
    fun u(i: Int): Long = payload[i].toLong() and 0xFF
    return when (this) {
        ScalarType.u8 -> u(offset)
        ScalarType.i8 -> {
            val v = u(offset)
            if (v >= 0x80) v - 0x100 else v
        }
        ScalarType.u16le -> u(offset) or (u(offset + 1) shl 8)
        ScalarType.i16le -> {
            val v = u(offset) or (u(offset + 1) shl 8)
            if (v >= 0x8000) v - 0x10000 else v
        }
        ScalarType.u32le -> u(offset) or (u(offset + 1) shl 8) or (u(offset + 2) shl 16) or (u(offset + 3) shl 24)
        ScalarType.i32le -> {
            val v = u(offset) or (u(offset + 1) shl 8) or (u(offset + 2) shl 16) or (u(offset + 3) shl 24)
            if (v >= 0x80000000L) v - 0x100000000L else v
        }
    }
}

/** 载荷内一个字节的映射：`value = raw * scale + bias`（bias 在缩放之后，§3.2）。 */
data class FieldMapping(
    val field: TelemetryField,
    val offset: Int,
    val type: ScalarType,
    val scale: Double = 1.0,
    val bias: Double = 0.0,
) {
    /** 只做上界检查；负偏移不在这里拒绝（对齐 Dart），读取处才有守卫。 */
    fun fits(payloadLength: Int): Boolean = offset >= 0 && offset <= payloadLength - type.byteLength

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "field" to field.name,
        "offset" to offset,
        "type" to type.name,
        "scale" to scale,
        "bias" to bias,
    )

    companion object {
        /** 必填只有 offset；其余按 §3.2 的缺省宽松落回。 */
        fun fromJson(j: Map<String, Any?>): FieldMapping = FieldMapping(
            field = TelemetryField.fromName(j["field"] as? String),
            offset = requireInteger(j, "offset"),
            type = ScalarType.fromName(j["type"] as? String),
            scale = (j["scale"] as? Number)?.toDouble() ?: 1.0,
            bias = (j["bias"] as? Number)?.toDouble() ?: 0.0,
        )
    }
}

/** 一个 (cmd,a1,a2) 帧组的映射集合，带证据说明（§3.3）。 */
data class CalibrationProfile(
    val name: String,
    val cmd: Int,
    val a1: Int,
    val a2: Int,
    val fields: List<FieldMapping>,
    val evidence: String = "",
) {
    fun matches(c: Int, a1v: Int, a2v: Int): Boolean = cmd == c && a1 == a1v && a2 == a2v

    /** 键序固定 name,cmd,a1,a2,evidence,fields（§3.3，导出结构对照要求）。 */
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "cmd" to cmd,
        "a1" to a1,
        "a2" to a2,
        "evidence" to evidence,
        "fields" to fields.map { it.toJson() },
    )

    companion object {
        fun fromJson(j: Map<String, Any?>): CalibrationProfile = CalibrationProfile(
            name = j["name"] as? String ?: "unnamed",
            cmd = requireInteger(j, "cmd"),
            a1 = requireInteger(j, "a1"),
            a2 = requireInteger(j, "a2"),
            evidence = j["evidence"] as? String ?: "",
            fields = (j["fields"] as? List<*>)
                ?.map { item ->
                    val map = item as? Map<*, *>
                        ?: throw IllegalArgumentException("标定字段必须是对象")
                    FieldMapping.fromJson(map.entries.associate { (k, v) -> k.toString() to v })
                }
                ?: emptyList(),
        )
    }
}

/**
 * 用户标定集合。默认为空：没有任何映射在真车上验证过（§3.4）。
 * `encode()` 与 Dart `JsonEncoder.withIndent('  ')` 同形（两空格缩进）。
 */
data class CalibrationSet(val profiles: List<CalibrationProfile>) {

    fun match(cmd: Int, a1: Int, a2: Int): CalibrationProfile? =
        profiles.firstOrNull { it.matches(cmd, a1, a2) }

    fun hasField(field: TelemetryField): Boolean =
        profiles.any { p -> p.fields.any { it.field == field } }

    /** 第一个包含该字段的映射：按 profile 顺序、再按字段顺序（§3.4）。 */
    fun mappingFor(field: TelemetryField): FieldMapping? =
        profiles.firstOrNull { p -> p.fields.any { it.field == field } }
            ?.fields?.firstOrNull { it.field == field }

    val profilesWithoutEvidence: Int
        get() = profiles.count { it.evidence.isBlank() }

    fun toJson(): Map<String, Any?> = linkedMapOf("profiles" to profiles.map { it.toJson() })

    fun encode(): String = PRETTY.encodeToString(
        JsonElement.serializer(),
        toJson().let { toElement(it) },
    )

    companion object {
        val empty = CalibrationSet(emptyList())

        @OptIn(ExperimentalSerializationApi::class)
        private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }
        private val COMPACT = Json

        /** 从 JSON 文本解码；非对象输入抛错，`profiles` 缺失 → 空集合（§3.4）。 */
        fun decode(text: String): CalibrationSet {
            val root = COMPACT.parseToJsonElement(text)
            val obj = root as? JsonObject ?: throw IllegalArgumentException("标定 JSON 顶层必须是对象")
            return CalibrationSet(
                (obj["profiles"] as? JsonArray ?: JsonArray(emptyList()))
                    .map { CalibrationProfile.fromJson((it as JsonObject).toNaturalMap()) },
            )
        }

        private fun JsonObject.toNaturalMap(): Map<String, Any?> =
            entries.associate { (k, v) -> k to v.toNatural() }

        private fun JsonElement.toNatural(): Any? = when (this) {
            is JsonNull -> null
            is JsonPrimitive -> if (isString) content else {
                val asLong = content.toLongOrNull()
                if (asLong != null) asLong else content.toDoubleOrNull() ?: content
            }
            is JsonArray -> map { it.toNatural() }
            is JsonObject -> toNaturalMap()
        }

        private fun toElement(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toElement(v) })
            is List<*> -> JsonArray(value.map { toElement(it) })
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }
}

/** `cmd/a1/a2/offset` 之类必填数值键：缺失或类型不对即抛（§10-11 的解析失败口径）。 */
private fun requireNumber(j: Map<String, Any?>, key: String): Number =
    j[key] as? Number ?: throw IllegalArgumentException("标定 JSON 缺少必填数值键：$key")

/** Reject overflow and fractions before narrowing; truncation could silently select another frame. */
private fun requireInteger(j: Map<String, Any?>, key: String): Int {
    val number = requireNumber(j, key).toDouble()
    require(number.isFinite() && number >= Int.MIN_VALUE.toDouble() && number <= Int.MAX_VALUE.toDouble() && number % 1.0 == 0.0) {
        "标定 $key 必须是范围内的整数"
    }
    return number.toInt()
}
