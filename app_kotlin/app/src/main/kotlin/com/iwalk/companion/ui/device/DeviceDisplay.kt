package com.iwalk.companion.ui.device

import java.time.Duration
import java.time.Instant
import java.util.Locale
import com.iwalk.core.model.TelemetryField

/**
 * 设备页显示格式化（notes/ui-device-scan.md §0，对应 Dart `lib/utils/device_display.dart`
 * 与 `lib/utils/telemetry_labels.dart` 的本工单所需子集）。
 *
 * 关键不变式（工单 02 验收第 5 条）：没有值的字段显示 [EM_DASH]，永远不显示猜测值。
 */

/** 无值占位符：与 Dart 逐字一致的全角破折号。 */
const val EM_DASH = "—"

/** 整数位字段（Dart `telemetry_labels.dart`：digits 0），其余 1 位。 */
private val INTEGER_FIELDS = setOf(
    TelemetryField.batteryPercent,
    TelemetryField.estimatedRange,
    TelemetryField.odometer,
    TelemetryField.workMode,
    TelemetryField.warningCode,
    TelemetryField.errorCode,
)

/** 各字段的显示小数位（速度/温度 1 位、电量/工作模式/警告码 0 位）。 */
fun digitsOf(field: TelemetryField): Int = if (field in INTEGER_FIELDS) 0 else 1

/** 字段中文名（Dart `telemetry_labels.dart` 的 labels 表）。 */
fun labelOf(field: TelemetryField): String = when (field) {
    TelemetryField.speed -> "速度"
    TelemetryField.batteryPercent -> "电量"
    TelemetryField.estimatedRange -> "预计里程"
    TelemetryField.batteryVoltage -> "电压"
    TelemetryField.temperature -> "温度"
    TelemetryField.current -> "电流"
    TelemetryField.odometer -> "里程"
    TelemetryField.workMode -> "工作模式"
    TelemetryField.warningCode -> "警告码"
    TelemetryField.errorCode -> "错误码"
    TelemetryField.motorPower -> "电机功率"
    TelemetryField.pitch -> "俯仰角"
    TelemetryField.roll -> "横滚角"
    TelemetryField.unknown -> "未知字段"
}

/** 字段单位（`telemetry_labels.dart`）；工作模式/警告码/错误码/未知字段无单位。 */
fun unitOf(field: TelemetryField): String = when (field) {
    TelemetryField.speed -> "km/h"
    TelemetryField.batteryPercent -> "%"
    TelemetryField.estimatedRange -> "km"
    TelemetryField.batteryVoltage -> "V"
    TelemetryField.temperature -> "°C"
    TelemetryField.current -> "A"
    TelemetryField.odometer -> "km"
    TelemetryField.motorPower -> "W"
    TelemetryField.pitch -> "°"
    TelemetryField.roll -> "°"
    TelemetryField.workMode,
    TelemetryField.warningCode,
    TelemetryField.errorCode,
    TelemetryField.unknown,
    -> ""
}

/** `toStringAsFixed(digits)` 后去掉一个结尾 `.0`（笔记 §0：`12.0`→`12`、`24.63`→`24.6`）。 */
fun formatFieldValue(value: Double?, digits: Int): String {
    if (value == null) return EM_DASH
    val s = String.format(Locale.ROOT, "%.${digits}f", value)
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

/** `刚刚 / N 秒前 / N 分钟前 / N 小时前`；无时间戳或负龄视作刚刚。 */
fun formatAge(at: Instant?, now: Instant = Instant.now()): String {
    at ?: return "刚刚"
    val seconds = Duration.between(at, now).seconds
    if (seconds < 1) return "刚刚"
    if (seconds < 60) return "$seconds 秒前"
    val minutes = seconds / 60
    if (minutes < 60) return "$minutes 分钟前"
    return "${minutes / 60} 小时前"
}

/** 指标条年龄格（Dart `_MetricStrip._ageValue`）：frames==0 → `—`；<1s `刚刚`；<60s `Ns`；其余 `Nm`。 */
fun metricAgeCell(at: Instant?, frames: Int, now: Instant = Instant.now()): String {
    if (frames == 0) return EM_DASH
    at ?: return "刚刚"
    val seconds = Duration.between(at, now).seconds
    if (seconds < 1) return "刚刚"
    if (seconds < 60) return "${seconds}s"
    return "${seconds / 60}m"
}

/** 会话时长（Dart `formatElapsed`）：秒数钳位 0..359999；`h:MM:SS` 或 `MM:SS`。 */
fun formatElapsed(totalSeconds: Long): String {
    val clamped = totalSeconds.coerceIn(0, 359_999)
    val h = clamped / 3600
    val m = (clamped % 3600) / 60
    val s = clamped % 60
    return if (h > 0) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    }
}
