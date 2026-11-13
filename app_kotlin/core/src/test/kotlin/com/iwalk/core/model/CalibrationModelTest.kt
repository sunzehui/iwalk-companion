package com.iwalk.core.model

import com.iwalk.protocol.hexToBytes
import com.iwalk.protocol.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标定模型的公开边界测试（notes/domain-logic.md §3.1–§3.4、§8 telemetry_test 5/6）。
 * 期望值全部逐字抄自笔记，不由被测代码反推。
 */
class CalibrationModelTest {

    @Test
    fun `14 个遥测字段按 Dart 顺序命名且未知名落到 unknown`() {
        val names = TelemetryField.entries.map { it.name }
        assertEquals(
            listOf(
                "speed", "batteryPercent", "estimatedRange", "batteryVoltage", "temperature",
                "current", "odometer", "workMode", "warningCode", "errorCode", "motorPower",
                "pitch", "roll", "unknown",
            ),
            names,
        )
        assertEquals(TelemetryField.unknown, TelemetryField.fromName("nonsense"))
        assertEquals(TelemetryField.speed, TelemetryField.fromName("speed"))
    }

    @Test
    fun `标量类型字节数`() {
        assertEquals(1, ScalarType.u8.byteLength)
        assertEquals(1, ScalarType.i8.byteLength)
        assertEquals(2, ScalarType.u16le.byteLength)
        assertEquals(2, ScalarType.i16le.byteLength)
        assertEquals(4, ScalarType.u32le.byteLength)
        assertEquals(4, ScalarType.i32le.byteLength)
    }

    @Test
    fun `六种标量类型全部按小端读取并处理符号`() {
        // Dart 的 all-scalar 测试漏了 u16le 与 i32le（notes §10-13），这里补齐
        //      [0]=c8(u8 200) [1]=fe(i8 -2) [2..3]=00 03(u16le 768) [4..5]=00 03(i16le 768)
        //      [6..9]=04 03 02 01(u32le 16909060) [10..13]=ff ff ff ff(i32le -1)
        val payload = "c8fe0003000304030201ffffffffff".hexToBytes()
        assertEquals(200L, ScalarType.u8.read(payload, 0))
        assertEquals(-2L, ScalarType.i8.read(payload, 1))
        assertEquals(768L, ScalarType.u16le.read(payload, 2))
        assertEquals(768L, ScalarType.i16le.read(payload, 4))
        assertEquals(16909060L, ScalarType.u32le.read(payload, 6))
        assertEquals(-1L, ScalarType.i32le.read(payload, 10))
        // 带符号 i16le：6aff → 0xff6a → -150
        assertEquals(-150L, ScalarType.i16le.read("6aff".hexToBytes(), 0))
    }

    @Test
    fun `读取越界或负偏移返回 null 不崩溃`() {
        val tiny = "0102".hexToBytes()
        assertNull(ScalarType.u32le.read(tiny, 4))
        assertNull(ScalarType.u8.read(tiny, 2))
        // notes §10-10：负偏移在 Dart 会抛范围错误；Kotlin 必须跳过而不是崩溃
        assertNull(ScalarType.u8.read(tiny, -1))
    }

    @Test
    fun `fits 校验上下界`() {
        val m = FieldMapping(TelemetryField.speed, 0, ScalarType.i16le, scale = 0.01)
        assertTrue(m.fits(2))
        assertFalse(m.fits(1))
        // 负偏移与溢出偏移都应被拒绝
        assertFalse(FieldMapping(TelemetryField.speed, -1, ScalarType.u8).fits(2))
    }

    @Test
    fun `CalibrationSet 匹配取第一个证据缺失计数`() {
        val a = CalibrationProfile("a", 0x06, 0x01, 0x05, emptyList(), evidence = "")
        val b = CalibrationProfile("b", 0x06, 0x01, 0x05, emptyList(), evidence = "有来源")
        val set = CalibrationSet(listOf(a, b))
        assertEquals("a", set.match(0x06, 0x01, 0x05)?.name) // 第一命中
        assertNull(set.match(0x06, 0x01, 0x06))
        assertEquals(1, set.profilesWithoutEvidence)
        assertFalse(set.hasField(TelemetryField.speed))
        val s2 = CalibrationSet(
            listOf(CalibrationProfile("c", 0x01, 0x02, 0x03, listOf(FieldMapping(TelemetryField.speed, 0, ScalarType.i16le)))),
        )
        assertTrue(s2.hasField(TelemetryField.speed))
        assertNotNull(s2.mappingFor(TelemetryField.speed))
    }

    @Test
    fun `JSON 往返保留名称与五个字段并维持匹配语义`() {
        val profile = CalibrationProfile(
            name = "demo-simulated (未验证)",
            cmd = 0x06, a1 = 0x01, a2 = 0x05,
            evidence = "仅用于演示传输；真实字段偏移需真机采集后确定（U03）",
            fields = listOf(
                FieldMapping(TelemetryField.speed, 0, ScalarType.i16le, scale = 0.01),
                FieldMapping(TelemetryField.batteryPercent, 2, ScalarType.u8),
                FieldMapping(TelemetryField.temperature, 3, ScalarType.i16le, scale = 0.1),
                FieldMapping(TelemetryField.workMode, 5, ScalarType.u8),
                FieldMapping(TelemetryField.warningCode, 6, ScalarType.u8),
            ),
        )
        val encoded = CalibrationSet(listOf(profile)).encode()
        val back = CalibrationSet.decode(encoded)
        assertEquals(1, back.profiles.size)
        val p = back.profiles.single()
        assertEquals("demo-simulated (未验证)", p.name)
        assertEquals(5, p.fields.size)
        assertEquals("仅用于演示传输；真实字段偏移需真机采集后确定（U03）", p.evidence)
        assertNotNull(p.matches(0x06, 0x01, 0x05))
        assertNull(back.match(0x06, 0x01, 0x06))
        // 0x26 等 cmd 以十进制数值写 JSON（Dart num），往返后仍为 6
        assertEquals(6, p.cmd)
    }

    @Test
    fun `encode 使用两空格缩进且键序对齐 Dart`() {
        val json = CalibrationSet(
            listOf(CalibrationProfile("t", 0x06, 0x01, 0x05, listOf(FieldMapping(TelemetryField.speed, 0, ScalarType.i16le, 0.01, 0.0)), evidence = "")),
        ).encode()
        val lines = json.lines()
        assertEquals("{", lines[0])
        assertEquals("  \"profiles\": [", lines[1])
        // 缩进为两空格；键序 name,cmd,a1,a2,evidence,fields
        assertTrue(json.contains("    \"name\": \"t\","))
        assertTrue(json.indexOf("\"cmd\"") < json.indexOf("\"a1\""))
        assertTrue(json.indexOf("\"evidence\"") < json.indexOf("\"fields\""))
        // 字段映射总是写全五个键
        assertTrue(json.contains("\"scale\": 0.01"))
        assertTrue(json.contains("\"bias\": 0.0"))
        assertFalse(json.contains("\t"))
    }

    @Test
    fun `fromJson 的缺省与必填语义`() {
        // field 缺失/未知 → unknown；type 未知/缺失 → u8；scale 缺失 → 1.0；bias 缺失 → 0.0
        val m = FieldMapping.fromJson(mapOf("offset" to 3.0))
        assertEquals(TelemetryField.unknown, m.field)
        assertEquals(3, m.offset)
        assertEquals(ScalarType.u8, m.type)
        assertEquals(1.0, m.scale, 1e-9)
        assertEquals(0.0, m.bias, 1e-9)
        // offset 必填：缺失抛错（Dart `(j['offset'] as num)` 口径）
        assertThrows(IllegalArgumentException::class.java) {
            FieldMapping.fromJson(mapOf("field" to "speed"))
        }
        // profile：name 缺失 → unnamed；cmd/a1/a2 必填；evidence 缺失 → ''；fields 缺失 → 空
        val p = CalibrationProfile.fromJson(mapOf("cmd" to 13, "a1" to 1, "a2" to 20))
        assertEquals("unnamed", p.name)
        assertEquals("", p.evidence)
        assertEquals(0, p.fields.size)
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationProfile.fromJson(mapOf("a1" to 1, "a2" to 2))
        }
        // type 未知字符串 → u8；field 未知 → unknown（导入宽松，notes §3.4）
        val m2 = FieldMapping.fromJson(mapOf("offset" to 0, "field" to "watt", "type" to "be32"))
        assertEquals(TelemetryField.unknown, m2.field)
        assertEquals(ScalarType.u8, m2.type)
    }

    @Test
    fun `decode 允许 profiles 缺失但拒绝非对象 JSON`() {
        assertEquals(0, CalibrationSet.decode("{}").profiles.size)
        assertEquals(0, CalibrationSet.decode("{\"profiles\": []}").profiles.size)
        assertThrows(Exception::class.java) { CalibrationSet.decode("[]") }
        assertThrows(Exception::class.java) { CalibrationSet.decode("not json") }
    }

    @Test
    fun `字段名即 JSON 名且类型名可往返`() {
        assertEquals("batteryPercent", TelemetryField.batteryPercent.name)
        assertEquals("i16le", ScalarType.i16le.name)
        assertEquals(ScalarType.i32le, ScalarType.fromName("i32le"))
        assertEquals(ScalarType.u8, ScalarType.fromName(null))
    }

    @Test
    fun `映射序列化写六类类型名`() {
        val json = FieldMapping(TelemetryField.pitch, 4, ScalarType.u16le, 0.5, -1.0).toJson()
        assertEquals("u16le", json["type"])
        assertEquals("pitch", json["field"])
        assertEquals(5, json.keys.size)
    }
}
