package com.iwalk.core.contract

import com.iwalk.core.decode.TelemetryDecoder
import com.iwalk.core.model.CalibrationSet
import com.iwalk.core.model.ScalarType
import com.iwalk.core.model.TelemetryField
import com.iwalk.core.session.lockBlockedReason
import com.iwalk.protocol.IbwalkFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段三的跨语言契约：`analysis/tools/speed_calibration.py` 写出的标定文件，App 能不能吃。
 *
 * 夹具 `app_kotlin/testdata/iwk1779887-speed-v1.sample.json` **不是手写的**，是工具的产物
 * （`--base-calibration downloads/iwk1779887-calibration-partial-v1.json --emit …`）。
 * 手工挑字段的测试只能证明「我们俩想的一样」，证不了「工具真的吐出这个结构」——
 * 而后者才是上车那天唯一的失败点：文件写得出来、App 读不进去，白跑一次采集。
 *
 * 夹具由 `analysis/tools/test_speed_calibration.py` 的 `test_app_fixture_matches_tool_output`
 * 反向盯住：Python 侧重新生成一份并逐字段比对，任一侧改了 schema 都会红。
 * 需要刷新时：`UPDATE_APP_FIXTURE=1 python3 analysis/tools/test_speed_calibration.py`。
 */
class SpeedCalibrationContractTest {

    private val text: String = fixtureText(FIXTURE)

    private val set: CalibrationSet = CalibrationSet.decode(text)

    @Test
    fun `工具写出的文件能被标定模型原样读入`() {
        assertEquals(1, set.profiles.size)
        val p = set.profiles.single()
        assertEquals("iWK1779887-speed-v1", p.name)
        // 帧组是应答侧 0x0d/0x01/0x14；写侧请求组是 0x0a/0x01/0x14，两者不能混用
        assertTrue("帧组应为 13/1/20，实际 ${p.cmd}/${p.a1}/${p.a2}", p.matches(13, 1, 20))

        val speed = set.mappingFor(TelemetryField.speed)
        assertNotNull("工具产出的文件里必须有 speed 映射", speed)
        assertEquals(6, speed!!.offset)
        assertEquals(ScalarType.u16le, speed.type)
        assertEquals(0.1, speed.scale, 1e-9)
        assertEquals(0.0, speed.bias, 1e-9)

        // 追加不是覆盖：原有电量映射必须还在，且仍在 off28
        val battery = set.mappingFor(TelemetryField.batteryPercent)
        assertNotNull("追加速度不得挤掉电量映射", battery)
        assertEquals(28, battery!!.offset)
        assertEquals(ScalarType.u8, battery.type)
    }

    @Test
    fun `证据串里不再保留已被推翻的那句话`() {
        val evidence = set.profiles.single().evidence
        assertFalse(
            "加了速度映射还留着「速度…不映射」就是自相矛盾：$evidence",
            evidence.contains("速度、电压、温度及其他字段不映射"),
        )
        // 只删这一处断言，电量锚点的原始描述要留着
        assertTrue(evidence.contains("电压、温度及其他字段不映射"))
        assertTrue(evidence.contains("速度字段标定"))
        assertTrue("要能看出这是工具拟合的结果", evidence.contains("off6"))
    }

    @Test
    fun `按工具给的映射解出与官方读数一致的速度`() {
        val decoder = TelemetryDecoder(set)
        // 12.0 km/h → raw 120 → off6 小端 i16；电量 83 在 off28
        decoder.ingest(frame(speedRaw = 120, battery = 83))
        val snapshot = decoder.snapshot()

        assertEquals(12.0, snapshot[TelemetryField.speed]!!, 1e-9)
        assertEquals(83.0, snapshot[TelemetryField.batteryPercent]!!, 1e-9)
        // 出处要能追溯到 profile，界面才敢标注「实测」而不是「推测」
        val value = snapshot.values.getValue(TelemetryField.speed)
        assertEquals("iWK1779887-speed-v1", value.profile.name)
        assertEquals(6, value.mapping.offset)
        assertEquals(120.0, value.raw, 1e-9)
    }

    @Test
    fun `链条末端是闸门——标定出来的速度真的能让锁车放行或拦截`() {
        // 这是阶段三要证的那条链：标定文件 → 解码 → LockPolicy。
        // 三段分开测都绿、串起来却不接的情况真实存在（帧组 0x0a/0x0d 就混过一次）。
        val decoder = TelemetryDecoder(set)
        decoder.ingest(frame(speedRaw = 0, battery = 83))
        val still = decoder.snapshot()[TelemetryField.speed]!!
        assertNull(
            "静止且已连接时应放行",
            lockBlockedReason(enabled = true, isDemo = false, connected = true, available = true, speedKmh = still),
        )

        decoder.ingest(frame(speedRaw = 80, battery = 83))
        val rolling = decoder.snapshot()[TelemetryField.speed]!!
        assertEquals(8.0, rolling, 1e-9)
        val blocked = lockBlockedReason(
            enabled = true, isDemo = false, connected = true, available = true, speedKmh = rolling,
        )
        assertNotNull("8 km/h 必须被拦", blocked)
        assertTrue("文案要指出速度，而不是笼统拒绝：$blocked", blocked!!.contains("速度"))
    }

    @Test
    fun `没有标定时速度仍然未知而不是 0——否则闸门会以为车停着`() {
        val decoder = TelemetryDecoder(CalibrationSet.empty)
        decoder.ingest(frame(speedRaw = 0, battery = 83))
        assertNull(decoder.snapshot()[TelemetryField.speed])
        assertNotNull(
            "空标定下闸门必须拦，不能把「没读数」当成「速度 0」",
            lockBlockedReason(enabled = true, isDemo = false, connected = true, available = true, speedKmh = null),
        )
    }

    @Test
    fun `再序列化一次后仍然可读——导入导出不会把 speed 丢掉`() {
        val again = CalibrationSet.decode(set.encode())
        val speed = again.mappingFor(TelemetryField.speed)
        assertNotNull(speed)
        assertEquals(6, speed!!.offset)
        assertEquals(0.1, speed.scale, 1e-9)
        assertNotNull(again.mappingFor(TelemetryField.batteryPercent))
    }

    /** 38 B 应答载荷：off6 放速度 raw 只是夹具假设（E71 证伪真车 off6=SN ASCII），off28 电量是真车验证过的。 */
    private fun frame(speedRaw: Int, battery: Int): IbwalkFrame {
        val p = ByteArray(38)
        p[0] = 0x02; p[1] = 0x43; p[2] = 0x38 // 型号前缀，与真机载荷同形即可
        p[6] = (speedRaw and 0xFF).toByte()
        p[7] = ((speedRaw shr 8) and 0xFF).toByte()
        p[28] = battery.toByte()
        return IbwalkFrame(cmd = 0x0D, a1 = 0x01, a2 = 0x14, payload = p)
    }

    private companion object {
        const val FIXTURE = "testdata/iwk1779887-speed-v1.sample.json"

        /**
         * Gradle 测试的 working dir 是模块目录（`core/` 或 `app/`），夹具在 `app_kotlin/testdata/`；
         * 两个模块都要读同一份文件，所以按「模块目录 → 上一级」找，不在任一侧复制副本——
         * 复制出来的第二份迟早会和第一份不一样。
         */
        fun fixtureText(relative: String): String {
            val module = java.io.File(System.getProperty("user.dir"))
            val found = listOf(java.io.File(module, relative), java.io.File(module.parentFile, relative))
                .firstOrNull { it.isFile }
                ?: error("缺少测试夹具 $relative（期望在 app_kotlin/testdata/ 下）")
            return found.readText()
        }
    }
}
