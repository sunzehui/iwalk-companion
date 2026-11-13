package com.iwalk.core.decode

import com.iwalk.core.model.CalibrationProfile
import com.iwalk.core.model.CalibrationSet
import com.iwalk.core.model.FieldMapping
import com.iwalk.core.model.ScalarType
import com.iwalk.core.model.TelemetryField
import com.iwalk.protocol.IbwalkFrame
import com.iwalk.protocol.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标定驱动遥测解码的公开边界（notes/domain-logic.md §3.7、§8 telemetry_test 1–4）。
 * 输入是帧字节、输出是读数——不测私有内部。
 */
class TelemetryDecoderTest {

    private val demoProfile = CalibrationProfile(
        name = "demo-simulated (未验证)",
        cmd = 0x06, a1 = 0x01, a2 = 0x05,
        fields = listOf(
            FieldMapping(TelemetryField.speed, 0, ScalarType.i16le, scale = 0.01),
            FieldMapping(TelemetryField.batteryPercent, 2, ScalarType.u8),
            FieldMapping(TelemetryField.temperature, 3, ScalarType.i16le, scale = 0.1),
            FieldMapping(TelemetryField.workMode, 5, ScalarType.u8),
            FieldMapping(TelemetryField.warningCode, 6, ScalarType.u8),
        ),
    )

    @Test
    fun `真实格式帧按演示标定解出五个字段`() {
        // 黄金 payload d2045704010100：速度 12.34、电量 87、温度 26.0、模式 1、告警 0
        val frame = IbwalkFrame(cmd = 0x06, a1 = 0x01, a2 = 0x05, payload = "d2045704010100".hexToBytes())
        val decoder = TelemetryDecoder(CalibrationSet(listOf(demoProfile)))
        val touched = decoder.ingest(frame)
        assertEquals(
            listOf(
                TelemetryField.speed, TelemetryField.batteryPercent, TelemetryField.temperature,
                TelemetryField.workMode, TelemetryField.warningCode,
            ),
            touched,
        )
        val snap = decoder.snapshot()
        assertEquals(12.34, snap[TelemetryField.speed]!!, 1e-9)
        assertEquals(87.0, snap[TelemetryField.batteryPercent]!!, 1e-9)
        assertEquals(26.0, snap[TelemetryField.temperature]!!, 1e-9)
        assertEquals(1.0, snap[TelemetryField.workMode]!!, 1e-9)
        assertEquals(0.0, snap[TelemetryField.warningCode]!!, 1e-9)
        assertEquals(1, snap.framesDecoded)
        assertEquals(0, snap.uncalibratedFrames)
        // 原始值保留换算前数值：速度 raw = 1234
        assertEquals(1234.0, snap.values.getValue(TelemetryField.speed).raw, 1e-9)
        assertEquals("demo-simulated (未验证)", snap.values.getValue(TelemetryField.speed).profile.name)
    }

    @Test
    fun `负 int16 速度保号`() {
        val frame = IbwalkFrame(0x06, 0x01, 0x05, "6aff4000000000".hexToBytes())
        val decoder = TelemetryDecoder(CalibrationSet(listOf(demoProfile)))
        decoder.ingest(frame)
        // 0xff6a = -150 → ×0.01 = -1.5
        assertEquals(-1.5, decoder.snapshot()[TelemetryField.speed]!!, 1e-9)
    }

    @Test
    fun `空标定不计读数但计入未标定帧`() {
        val frame = IbwalkFrame(0x06, 0x01, 0x05, "d2045704010100".hexToBytes())
        val decoder = TelemetryDecoder(CalibrationSet.empty)
        val touched = decoder.ingest(frame)
        assertEquals(emptyList<TelemetryField>(), touched)
        val snap = decoder.snapshot()
        assertNull(snap[TelemetryField.speed])
        assertEquals(1, snap.uncalibratedFrames)
        assertEquals(1, snap.framesDecoded)
    }

    @Test
    fun `映射越出载荷时跳过而不猜测`() {
        val profile = CalibrationProfile(
            "wide", 0x0a, 0x01, 0x01,
            fields = listOf(
                FieldMapping(TelemetryField.speed, 0, ScalarType.i16le, scale = 0.01),
                FieldMapping(TelemetryField.odometer, 4, ScalarType.u32le, scale = 0.001),
                FieldMapping(TelemetryField.current, -1, ScalarType.u8),
            ),
        )
        val decoder = TelemetryDecoder(CalibrationSet(listOf(profile)))
        val touched = decoder.ingest(IbwalkFrame(0x0a, 0x01, 0x01, "6400".hexToBytes()))
        // 100 × 0.01 = 1.0；u32le@4 越界跳过；负偏移被读取守卫跳过（notes §10-10）
        assertEquals(listOf(TelemetryField.speed), touched)
        assertEquals(1.0, decoder.snapshot()[TelemetryField.speed]!!, 1e-9)
        assertNull(decoder.snapshot()[TelemetryField.odometer])
        assertNull(decoder.snapshot()[TelemetryField.current])
    }

    @Test
    fun `未匹配标定组的帧累加 uncalibrated 而匹配帧不累加`() {
        val decoder = TelemetryDecoder(CalibrationSet(listOf(demoProfile)))
        decoder.ingest(IbwalkFrame(0x0d, 0x01, 0x14, ByteArray(38)))
        decoder.ingest(IbwalkFrame(0x0d, 0x01, 0x14, ByteArray(38)))
        decoder.ingest(IbwalkFrame(0x06, 0x01, 0x05, "d2045704010100".hexToBytes()))
        val snap = decoder.snapshot()
        assertEquals(3, snap.framesDecoded)
        assertEquals(2, snap.uncalibratedFrames)
    }

    @Test
    fun `同字段后帧覆盖前帧`() {
        val decoder = TelemetryDecoder(CalibrationSet(listOf(demoProfile)))
        decoder.ingest(IbwalkFrame(0x06, 0x01, 0x05, "d2045704010100".hexToBytes()))
        decoder.ingest(IbwalkFrame(0x06, 0x01, 0x05, "e8035704010100".hexToBytes())) // 0x03e8=1000 → 10.0
        assertEquals(10.0, decoder.snapshot()[TelemetryField.speed]!!, 1e-9)
    }

    @Test
    fun `rawOf 给出换算前的原始值且越界为 null`() {
        val frame = IbwalkFrame(0x06, 0x01, 0x05, "d204".hexToBytes())
        val decoder = TelemetryDecoder(CalibrationSet(listOf(demoProfile)))
        val m = demoProfile.fields.first()
        assertEquals(1234, decoder.rawOf(frame, m))
        val wide = FieldMapping(TelemetryField.odometer, 4, ScalarType.u32le)
        assertNull(decoder.rawOf(frame, wide))
    }

    @Test
    fun `快照 isStale 按最大时效判定`() {
        val at = java.time.Instant.parse("2026-10-29T00:00:00Z")
        val snap = TelemetrySnapshot(updatedAt = at)
        assertTrue(snap.isStale(java.time.Duration.ofSeconds(3), at.plusSeconds(4)))
        assertTrue(!snap.isStale(java.time.Duration.ofSeconds(3), at.plusSeconds(2)))
    }

    @Test
    fun `空快照以 epoch 为零点且计数全零`() {
        val snap = TelemetrySnapshot.empty
        assertEquals(0, snap.framesDecoded)
        assertEquals(0, snap.uncalibratedFrames)
        assertEquals(0, snap.bytesUndecoded)
        assertTrue(snap.values.isEmpty())
    }

    @Test
    fun `ingest 带时间戳落到 FieldValue`() {
        val at = java.time.Instant.parse("2026-10-29T08:00:00Z")
        val decoder = TelemetryDecoder(CalibrationSet(listOf(demoProfile)))
        decoder.ingest(IbwalkFrame(0x06, 0x01, 0x05, "d2045704010100".hexToBytes()), at)
        val v = decoder.snapshot().values.getValue(TelemetryField.speed)
        assertNotNull(v)
        assertEquals(at, v.at)
    }
}
