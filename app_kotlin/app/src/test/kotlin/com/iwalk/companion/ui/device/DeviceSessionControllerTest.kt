package com.iwalk.companion.ui.device

import com.iwalk.companion.ble.LINK_ERR_CONNECT_TIMEOUT
import com.iwalk.companion.ble.RealLinkFactory
import com.iwalk.companion.ble.ScannedDevice
import com.iwalk.core.model.CalibrationProfile
import com.iwalk.core.model.CalibrationSet
import com.iwalk.core.model.FieldMapping
import com.iwalk.core.model.ScalarType
import com.iwalk.core.model.TelemetryField
import com.iwalk.core.session.BluetoothTransport
import com.iwalk.core.session.IncomingPacket
import com.iwalk.core.session.LinkFacts
import com.iwalk.core.session.TransportEvent
import com.iwalk.core.session.TransportState
import com.iwalk.core.store.CalibrationStore
import com.iwalk.core.store.InMemoryKeyValueStore
import com.iwalk.protocol.IbwalkFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工单 03：真车会话装配（成功/失败/复位/用户标定来源），全部在 JVM 上验证。 */
class DeviceSessionControllerTest {

    private class FakeTransport : BluetoothTransport {
        override val id = "AA:BB:CC:DD:EE:FF"
        override val label = "MiniRobot:IWK1779887"
        override val isDemo = false

        override var state: TransportState = TransportState.idle
        override var lastMessage: String? = null
        override var linkFacts: LinkFacts? = null

        override val incoming = MutableSharedFlow<IncomingPacket>(extraBufferCapacity = 64)
        override val events = MutableSharedFlow<TransportEvent>(replay = 1, extraBufferCapacity = 16)

        var failConnect = false
        var connectCalled = false
        var disconnectCalled = false

        override suspend fun connect() {
            connectCalled = true
            if (failConnect) {
                state = TransportState.error
                lastMessage = "连接超时或失败"
                events.tryEmit(TransportEvent(TransportState.error, lastMessage))
                error("连接超时或失败")
            }
            linkFacts = LinkFacts(
                advertisementName = label,
                transportGroup = "g1NordicUart",
                serviceUuid = "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
                writeUuid = "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
                notifyUuid = "6e400003-b5a3-f393-e0a9-e50e24dcca9e",
                writeCharacteristicAvailable = true,
            )
            state = TransportState.connected
            lastMessage = "分组 g1NordicUart，写 6e400002-b5a3-f393-e0a9-e50e24dcca9e"
            events.tryEmit(TransportEvent(TransportState.connected, lastMessage))
        }

        override suspend fun disconnect() {
            disconnectCalled = true
            state = TransportState.disconnected
        }

        suspend fun emitNotify(bytes: ByteArray) {
            incoming.emit(IncomingPacket(bytes, "svc", "char"))
        }
    }

    private val scanned = ScannedDevice(
        address = "AA:BB:CC:DD:EE:FF",
        name = "MiniRobot:IWK1779887",
        rssi = -60,
        whitelisted = true,
        serial = "1779887",
        advertisedServices = listOf("00001800-0000-1000-8000-00805f9b34fb"),
    )

    private fun controllerWith(transport: FakeTransport, store: CalibrationStore = CalibrationStore(InMemoryKeyValueStore())) =
        DeviceSessionController(
            calibrationStore = store,
            nowMillis = { 1_000_000L },
            realLinkFactory = RealLinkFactory { transport },
        )

    @Test
    fun `真车连接成功进入分支B并带链路事实`() = runTest {
        val transport = FakeTransport()
        val controller = controllerWith(transport)

        val ok = controller.connectReal(scanned)

        assertTrue(ok)
        val ui = controller.ui.value
        assertTrue(ui.active)
        assertFalse(ui.isDemo)
        assertEquals(TransportState.connected, ui.transportState)
        assertEquals("ble-1000000", ui.sessionId)
        assertNotNull(ui.linkFacts)
        assertEquals("g1NordicUart", ui.linkFacts?.transportGroup)
        assertTrue(ui.linkFacts?.writeCharacteristicAvailable == true)
        assertTrue(transport.connectCalled)
    }

    @Test
    fun `真车连接失败复位到分支A并给出原因`() = runTest {
        val transport = FakeTransport().apply { failConnect = true }
        val controller = controllerWith(transport)

        val ok = controller.connectReal(scanned)

        assertFalse(ok)
        val ui = controller.ui.value
        assertFalse(ui.active)
        assertNull(ui.linkFacts)
        // 失败文案：根因保留，但换成用户能读懂的句子（不再甩 `java.lang.IllegalStateException:`）
        assertTrue(ui.connectionMessage?.contains(LINK_ERR_CONNECT_TIMEOUT) == true)
        assertTrue(waitUntil(5_000) { transport.disconnectCalled })
    }

    @Test
    fun `结束真车会话复位且保留用户标定存在标记`() = runTest {
        val store = CalibrationStore(InMemoryKeyValueStore())
        store.save(
            CalibrationSet(
                listOf(
                    CalibrationProfile(
                        name = "battery",
                        cmd = 0x0d,
                        a1 = 0x01,
                        a2 = 0x14,
                        fields = listOf(FieldMapping(TelemetryField.batteryPercent, 28, ScalarType.u8)),
                        evidence = "E64",
                    ),
                ),
            ),
        )
        val transport = FakeTransport()
        val controller = controllerWith(transport, store)

        assertTrue(controller.connectReal(scanned))
        // 会话标定来自用户存储，而不是演示标定
        assertEquals(store.load(), controller.ui.value.calibration)
        assertTrue(controller.ui.value.hasUserCalibration)

        controller.stopSession()

        val ui = controller.ui.value
        assertFalse(ui.active)
        assertTrue(ui.hasUserCalibration)
        // disposeSession 跑在独立 IO scope；轮询等待 GATT 释放
        assertTrue(waitUntil(5_000) { transport.disconnectCalled })
    }

    @Test
    fun `工厂缺失时连接直接失败`() = runTest {
        val controller = DeviceSessionController(
            calibrationStore = CalibrationStore(InMemoryKeyValueStore()),
            nowMillis = { 0L },
        )
        assertFalse(controller.connectReal(scanned))
        assertFalse(controller.ui.value.active)
        assertEquals("真车链路未配置", controller.ui.value.connectionMessage)
    }

    @Test
    fun `真车会话收到通知帧进入帧日志`() = runTest {
        val transport = FakeTransport()
        val controller = controllerWith(transport)
        assertTrue(controller.connectReal(scanned))

        // 合法帧：55 aa 02 f1 05 01 + 校验 06 ff（~(02+f1+05+01) 小端），未标定字段只进帧日志
        val frame = byteArrayOf(
            0x55, 0xaa.toByte(), 0x02, 0xf1.toByte(), 0x05, 0x01, 0x06, 0xff.toByte(),
        )
        // SessionManager 的收集器跑在 Default scope；等它订阅完成再发帧，模拟真实时序
        assertTrue(waitUntil(5_000) { transport.incoming.subscriptionCount.value > 0 })
        transport.emitNotify(frame)
        assertTrue(waitUntil(5_000) { controller.ui.value.frames.isNotEmpty() })

        assertEquals(1, controller.ui.value.frames.size)
        assertEquals(IbwalkFrame(0xF1, 0x05, 0x01), controller.ui.value.frames.first())
        assertEquals(1, controller.ui.value.rawLines.size)
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}
