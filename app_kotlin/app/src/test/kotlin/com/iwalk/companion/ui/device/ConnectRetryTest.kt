package com.iwalk.companion.ui.device

import com.iwalk.companion.ble.LINK_ERR_INIT_INTERRUPTED
import com.iwalk.companion.ble.LINK_ERR_NO_GATT
import com.iwalk.companion.ble.LINK_ERR_SERVICE_TIMEOUT
import com.iwalk.companion.ble.ScannedDevice
import com.iwalk.companion.ble.linkErrorText
import com.iwalk.core.session.BluetoothTransport
import com.iwalk.core.session.IncomingPacket
import com.iwalk.core.session.TransportEvent
import com.iwalk.core.session.TransportState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连接失败的两个用户可见行为（2026-11-12 真机复盘）：
 * 1. 初始化被中断这类瞬时失败自动重试一次——真机上就是「首点失败、再点成功」；
 * 2. 失败文案是人话，不带 `java.lang.IllegalStateException:` 这类类名。
 */
class ConnectRetryTest {

    private val device = ScannedDevice(
        address = "AA:BB:CC:DD:EE:01",
        name = "MiniRobot:IWK1779887",
        rssi = -60,
        whitelisted = true,
        serial = "1779887",
        advertisedServices = emptyList(),
    )

    /** 按脚本依次表现：每个元素对应一次 connect() 的结果。 */
    private class ScriptedTransport(private val script: List<Result<TransportState>>) : BluetoothTransport {
        override val id = "AA:BB:CC:DD:EE:01"
        override val label = "MiniRobot:IWK1779887"
        override val isDemo = false
        private val _events = MutableSharedFlow<TransportEvent>(replay = 1, extraBufferCapacity = 8)
        private val _incoming = MutableSharedFlow<IncomingPacket>(extraBufferCapacity = 8)
        override val events: Flow<TransportEvent> = _events
        override val incoming: Flow<IncomingPacket> = _incoming

        @Volatile
        override var state: TransportState = TransportState.idle
            private set

        var connectCalls = 0

        override suspend fun connect() {
            val step = script.getOrElse(connectCalls) { script.last() }
            connectCalls++
            step.getOrThrow()
            state = step.getOrNull()!!
            _events.emit(TransportEvent(state, "分组 g1NordicUart"))
        }

        override suspend fun disconnect() {
            state = TransportState.disconnected
        }
    }

    private fun controller(transports: List<ScriptedTransport>): DeviceSessionController {
        var index = 0
        return DeviceSessionController(
            realLinkFactory = { transports[minOf(index++, transports.size - 1)] },
        )
    }

    @Test
    fun `初始化中断自动重试一次并连上`() = runBlocking {
        val first = ScriptedTransport(listOf(Result.failure(IllegalStateException("连接初始化已中断"))))
        val second = ScriptedTransport(listOf(Result.success(TransportState.connected)))
        val controller = controller(listOf(first, second))

        assertTrue(controller.connectReal(device))
        assertEquals(1, first.connectCalls)
        assertEquals(1, second.connectCalls)
        assertTrue(controller.ui.value.active)
        assertEquals(TransportState.connected, controller.ui.value.transportState)
    }

    @Test
    fun `重试后仍失败给出人话文案并注明重试次数`() = runBlocking {
        val transports = List(2) {
            ScriptedTransport(listOf(Result.failure(IllegalStateException("连接初始化已中断"))))
        }
        val controller = controller(transports)

        assertFalse(controller.connectReal(device))
        val message = controller.ui.value.connectionMessage.orEmpty()
        assertEquals("$LINK_ERR_INIT_INTERRUPTED · 已自动重试 1 次仍失败", message)
        assertFalse(message.contains("IllegalStateException"))
        assertFalse(controller.ui.value.active)
    }

    @Test
    fun `超时类失败不重试`() = runBlocking {
        val transport = ScriptedTransport(listOf(Result.failure(IllegalStateException("服务发现超时"))))
        val controller = controller(listOf(transport))

        assertFalse(controller.connectReal(device))
        assertEquals(1, transport.connectCalls)
        assertEquals(LINK_ERR_SERVICE_TIMEOUT, controller.ui.value.connectionMessage)
    }

    @Test
    fun `错误映射不吞掉未知异常的性质`() {
        assertEquals(LINK_ERR_INIT_INTERRUPTED, linkErrorText(IllegalStateException("连接初始化已中断")))
        assertEquals(LINK_ERR_SERVICE_TIMEOUT, linkErrorText(IllegalStateException("服务发现超时")))
        assertEquals(LINK_ERR_NO_GATT, linkErrorText(IllegalStateException("connectGatt 返回 null")))
        assertEquals("冒烟测试失败（RuntimeException）", linkErrorText(RuntimeException("冒烟测试失败")))
        assertEquals("未知原因", linkErrorText(null))
    }
}
