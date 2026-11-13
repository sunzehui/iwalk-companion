package com.iwalk.companion.ui.device

import com.iwalk.companion.ble.RealLinkFactory
import com.iwalk.companion.ble.ScannedDevice
import com.iwalk.core.model.TelemetryField
import com.iwalk.core.session.BluetoothTransport
import com.iwalk.core.session.IncomingPacket
import com.iwalk.core.session.LockControl
import com.iwalk.core.session.TransportEvent
import com.iwalk.core.session.TransportState
import com.iwalk.core.store.CalibrationStore
import com.iwalk.core.store.InMemoryKeyValueStore
import com.iwalk.protocol.IbwalkFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁闸门 C 段的 App 侧证据：**有速度标定、且车在动时，一条锁帧都不许写出**。
 *
 * 已有的 `SettingsAndLockTest` 只覆盖了「速度未标定 → 阻止」和「速度正好 1.0 → 放行」，
 * 缺的恰好是最危险的那一档（>1.0）。这里用 `analysis/tools/speed_calibration.py` 的真实产物
 * （`testdata/iwk1779887-speed-v1.sample.json`，帧组 0x0d/0x01/0x14、速度在 off6 u16le ×0.1
 * —— 注意 off6 是**未验证的夹具假设**：E71 已证伪真车 0x14 off6（实为 SN ASCII），
 * 这里只测「文件→解码→闸门」链路本身，速度字段的真身见 evidence_log U03）
 * 把整条链串起来：导入文件 → 解码出速度 → 闸门判定 → 是否真的调了 `setLock`。
 *
 * 设备侧还有一半（HCI 里没有锁帧写出）由 `analysis/tools/lock_gate_check.py` 判，
 * 两侧都对上才算通过，见 `docs/lock-gate-real-vehicle-plan.md`。
 */
class SpeedCalibrationGateTest {

    private class CountingTransport : BluetoothTransport, LockControl {
        override val incoming = MutableSharedFlow<IncomingPacket>(replay = 1)
        var writes = 0
        var lastLock: Boolean? = null

        override val id = "speed-gate"
        override val label = id
        override val isDemo = false
        override val state = TransportState.connected
        override val lockControlAvailable = true
        override val events = emptyFlow<TransportEvent>()
        override suspend fun connect() {}
        override suspend fun disconnect() {}
        override suspend fun setLock(lock: Boolean) {
            writes++
            lastLock = lock
        }
    }

    /** 0x0d/0x01/0x14 的 38 B 应答：off6 放速度 raw 只是夹具假设（E71 证伪真车 off6），链路测试用。 */
    private fun frame(speedRaw: Int): IbwalkFrame {
        val p = ByteArray(38)
        p[0] = 0x02; p[1] = 0x43; p[2] = 0x38
        p[6] = (speedRaw and 0xFF).toByte()
        p[7] = ((speedRaw shr 8) and 0xFF).toByte()
        p[28] = 83
        return IbwalkFrame(cmd = 0x0D, a1 = 0x01, a2 = 0x14, payload = p)
    }

    @Test
    fun `导入工具产出的标定后 运动中拦截且零写入 停稳后放行`() = runBlocking {
        val transport = CountingTransport()
        val controller = DeviceSessionController(
            calibrationStore = CalibrationStore(InMemoryKeyValueStore()),
            realLinkFactory = RealLinkFactory { transport },
        )
        try {
            assertNull("工具产出的文件必须能直接导入", controller.importCalibration(fixtureText()))
            controller.setLockControlEnabled(true)
            assertEquals(
                "导入后设置页应看到 speed 映射",
                true,
                controller.settings.value.calibration.hasField(TelemetryField.speed),
            )

            assertTrue(
                controller.connectReal(ScannedDevice("AA:BB:CC:DD:EE:FF", "i.walk:IWK1779887", -50, true, "1", emptyList())),
            )

            // C 段：8.0 km/h 正在推行
            transport.incoming.emit(IncomingPacket(frame(speedRaw = 80).encode()))
            withTimeout(5_000) { while (controller.ui.value.frames.isEmpty()) delay(10) }
            assertEquals(8.0, controller.ui.value.snapshot[TelemetryField.speed]!!, 1e-9)
            val blocked = controller.sendLock(true)
            assertNotNull(blocked)
            assertTrue("文案要写明是速度拦的：$blocked", blocked!!.contains("速度仍高于"))
            assertEquals("闸门必须挡在写之前", 0, transport.writes)
            assertEquals(null, transport.lastLock)

            // B 段：停稳后同一次会话里应当放行
            transport.incoming.emit(IncomingPacket(frame(speedRaw = 0).encode()))
            withTimeout(5_000) { while (controller.ui.value.snapshot[TelemetryField.speed] != 0.0) delay(10) }
            val sent = controller.sendLock(true)
            // sendLock 返回的是给用户看的结果文案（成功也是非 null），失败时才以速度/连接为由
            assertTrue("静止时应允许发送，实际：$sent", sent!!.startsWith("已发送"))
            assertEquals(1, transport.writes)
            assertEquals(true, transport.lastLock)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `标定文件里的映射与工具给出的偏移一致`() = runBlocking {
        // 夹具是工具的产物、Python 侧有漂移测试盯着；这里再从 App 侧确认一次落库的形状，
        // 免得「导入成功但映射被读成 u8/偏移 0」这种静默错误。
        val store = CalibrationStore(InMemoryKeyValueStore())
        val controller = DeviceSessionController(calibrationStore = store)
        try {
            assertNull(controller.importCalibration(fixtureText()))
            val mapping = checkNotNull(store.load()!!.mappingFor(TelemetryField.speed))
            assertEquals(6, mapping.offset)
            assertEquals(0.1, mapping.scale, 1e-9)
            assertEquals("u16le", mapping.type.name)
            assertNotNull("原有电量映射不能被追加速度挤掉", store.load()!!.mappingFor(TelemetryField.batteryPercent))
        } finally {
            controller.close()
        }
    }

    private fun fixtureText(): String {
        val module = java.io.File(System.getProperty("user.dir"))
        return listOf(java.io.File(module, FIXTURE), java.io.File(module.parentFile, FIXTURE))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("缺少测试夹具 $FIXTURE（期望在 app_kotlin/testdata/ 下）")
    }

    private companion object {
        const val FIXTURE = "testdata/iwk1779887-speed-v1.sample.json"
    }
}
