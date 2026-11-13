package com.iwalk.companion.ui.device

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 显示格式化子集（notes/ui-device-scan.md §0）：期望值逐条抄自笔记表格与 Dart
 * `lib/utils/device_display.dart`，不在测试里重算。
 */
class DeviceDisplayTest {

    @Test
    fun `formatFieldValue 去掉整数的尾随点零`() {
        // 笔记 §0：12.0 → 12（digits 1）、24.63 → 24.6、null → —（绝不猜值）
        assertEquals("12", formatFieldValue(12.0, 1))
        assertEquals("24.6", formatFieldValue(24.63, 1))
        assertEquals("87", formatFieldValue(87.4, 0))
        assertEquals(EM_DASH, formatFieldValue(null, 1))
    }

    @Test
    fun `formatAge 按秒分级`() {
        val now = Instant.parse("2026-10-29T10:00:00Z")
        // 笔记 §0：负数或不足 1 秒 → 刚刚；<60 秒 → N 秒前；<60 分 → N 分钟前；其余 N 小时前
        assertEquals("刚刚", formatAge(now.minusMillis(500), now))
        assertEquals("刚刚", formatAge(now.plusSeconds(30), now))
        assertEquals("45 秒前", formatAge(now.minusSeconds(45), now))
        assertEquals("12 分钟前", formatAge(now.minusSeconds(12 * 60 + 9), now))
        assertEquals("3 小时前", formatAge(now.minusSeconds(3 * 3600 + 40), now))
        assertEquals("刚刚", formatAge(null, now))
    }

    @Test
    fun `metricAgeCell 是年龄格的短格式`() {
        val now = Instant.parse("2026-10-29T10:00:00Z")
        // Dart _MetricStrip._ageValue：帧数 0 → —；<1s 刚刚；<60s "Ns"；其余整分 "Nm"
        assertEquals(EM_DASH, metricAgeCell(now.minusSeconds(2), 0, now))
        assertEquals("刚刚", metricAgeCell(now, 3, now))
        assertEquals("7s", metricAgeCell(now.minusSeconds(7), 3, now))
        assertEquals("2m", metricAgeCell(now.minusSeconds(2 * 60 + 5), 3, now))
    }

    @Test
    fun `formatElapsed 按时分秒进位`() {
        // 笔记 §0 formatElapsed：钳位 0..359999；h>0 → h:MM:SS，否则 MM:SS
        assertEquals("00:00", formatElapsed(0))
        assertEquals("01:05", formatElapsed(65))
        assertEquals("59:59", formatElapsed(3599))
        assertEquals("1:01:01", formatElapsed(3661))
        assertEquals("99:59:59", formatElapsed(400_000))
        assertEquals("00:00", formatElapsed(-3))
    }
}
