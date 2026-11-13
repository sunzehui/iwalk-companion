package com.iwalk.core.session

import com.iwalk.core.demo.DemoProfile
import com.iwalk.core.demo.DemoSession
import com.iwalk.core.demo.DemoTransport
import com.iwalk.core.model.CalibrationSet
import com.iwalk.core.model.TelemetryField
import com.iwalk.core.store.CalibrationStore
import com.iwalk.core.store.InMemoryKeyValueStore
import com.iwalk.protocol.IbwalkFrame
import com.iwalk.protocol.decodeFrame
import com.iwalk.protocol.hexToBytes
import com.iwalk.protocol.obfuscate
import com.iwalk.protocol.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 测试内注入字节用的假链路（不发送任何写指令）。 */
private class FakeTransport : BluetoothTransport {
    override val id = "fake"
    override val label = "假链路"
    override val isDemo = false

    val incomingFlow = MutableSharedFlow<IncomingPacket>(extraBufferCapacity = 256)
    val eventsFlow = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 32)

    override val incoming: Flow<IncomingPacket> = incomingFlow
    override val events: Flow<TransportEvent> = eventsFlow

    @Volatile
    override var state: TransportState = TransportState.idle

    override suspend fun connect() {
        state = TransportState.connecting
        eventsFlow.tryEmit(TransportEvent(TransportState.connecting))
        state = TransportState.connected
        eventsFlow.tryEmit(TransportEvent(TransportState.connected))
    }

    override suspend fun disconnect() {
        state = TransportState.disconnected
        eventsFlow.tryEmit(TransportEvent(TransportState.disconnected))
    }

    fun push(bytes: ByteArray, charUuid: String? = "c1") {
        incomingFlow.tryEmit(IncomingPacket(bytes, serviceUuid = "s1", charUuid = charUuid))
    }
}

/**
 * 会话端到端：字节进、读数出（notes/domain-logic.md §4.2、§8 session/hotswap/demo 测试目录）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {

    private fun demoPacket(payloadHex: String): ByteArray =
        IbwalkFrame(0x06, 0x01, 0x05, payloadHex.hexToBytes()).encode()

    @Test
    fun `时间线撞上 2000 上限时标记截断`() = runTest {
        val transport = FakeTransport()
        val session = SessionManager(transport, DemoProfile.set(), "ble-1", this)
        session.start()
        advanceUntilIdle()

        val packet = demoPacket("d2045704010100")
        // 每轮 200 条后让收集器排空：SharedFlow 缓冲只有 256，一次灌 2000 条会丢
        repeat(5) {
            repeat(200) { transport.push(packet) }
            advanceUntilIdle()
        }
        assertEquals(1_000, session.evidenceEvents.size)
        assertFalse("未超上限不应标记截断", session.isTimelineTruncated)

        repeat(6) {
            repeat(200) { transport.push(packet) }
            advanceUntilIdle()
        }
        assertEquals(2_200, session.snapshot.value.framesDecoded)
        assertTrue("超过 2000 条后应标记截断", session.isTimelineTruncated)
        assertTrue("时间线按上限收敛", session.evidenceEvents.size <= 2_000)
        // 帧本身不丢：截断只影响时间线视图
        assertEquals(2_200, session.frames.size)
        session.stop()
    }

    @Test
    fun `演示传输发出真格式明文帧且会话解出读数`() = runTest {
        val transport = DemoTransport(scope = this, periodMillis = 250)
        val session = DemoSession.create(this, transport, DemoSession.demoSessionId(123L))
        assertEquals("demo-123", session.sessionId)

        session.start()
        // 300ms 连接延时 + 首个 tick 在 250ms 后
        advanceTimeBy(1_000)
        runCurrent()

        val snap = session.snapshot.value
        assertTrue("至少一条帧", snap.framesDecoded >= 2)
        assertNotNull("速度有读数", snap[TelemetryField.speed])
        assertNotNull("电量有读数", snap[TelemetryField.batteryPercent])
        assertNotNull("温度有读数", snap[TelemetryField.temperature])
        assertNotNull("工作模式 raw", snap[TelemetryField.workMode])
        assertNotNull("告警码 raw", snap[TelemetryField.warningCode])
        assertNotNull("预计里程有读数", snap[TelemetryField.estimatedRange])
        assertEquals(0, snap.uncalibratedFrames)
        assertTrue("原始行保留", session.rawLines.isNotEmpty())
        // 原始行是「时间戳 空格 小写hex」，hex 是真格式明文帧（§4.2 第 2 步）
        val firstLine = session.rawLines.first()
        val firstHex = firstLine.substringAfter(' ')
        assertTrue(firstHex.startsWith("55aa0a"))
        assertEquals(session.frames.first().encode().toHex(), firstHex)

        session.stop()
        advanceUntilIdle()
        assertEquals(TransportState.disconnected, transport.state)
    }

    @Test
    fun `会话状态流按 connecting connected 推进`() = runTest {
        val transport = DemoTransport(scope = this, periodMillis = 250)
        val session = DemoSession.create(this, transport, "demo-1")
        val seen = mutableListOf<TransportState>()
        backgroundScope.launch { session.connectionState.collect { if (it !in seen) seen.add(it) } }
        session.start()
        advanceTimeBy(400)
        runCurrent()
        assertTrue("包含 connected", seen.contains(TransportState.connected))
        session.stop()
    }

    @Test
    fun `每个通知特征独立锁模式且互不污染`() = runTest {
        val transport = FakeTransport()
        val session = SessionManager(transport, DemoProfile.set(), "ble-1", this)
        session.start()
        advanceUntilIdle()

        val plain = demoPacket("d2045704010100")
        val wire = obfuscate(demoPacket("e8035704010100")) // 0x03e8=1000 → 10.0

        transport.push(plain, charUuid = "fff4")
        advanceUntilIdle()
        // 明文特征锁 mode 0，直接解出 12.34
        assertEquals(12.34, session.snapshot.value[TelemetryField.speed]!!, 1e-9)

        transport.push(wire, charUuid = "6e400003")
        advanceUntilIdle()
        // 混淆特征独立锁 mode 0x55，解出 10.0 并覆盖最新值
        assertEquals(10.0, session.snapshot.value[TelemetryField.speed]!!, 1e-9)

        transport.push(plain, charUuid = "fff4")
        transport.push(wire, charUuid = "6e400003")
        advanceUntilIdle()
        assertEquals(4, session.snapshot.value.framesDecoded)
        session.stop()
    }

    @Test
    fun `未标定帧计数且拒收字节计入未解字节`() = runTest {
        val transport = FakeTransport()
        val session = SessionManager(transport, CalibrationSet.empty, "ble-1", this)
        session.start()
        advanceUntilIdle()

        transport.push(demoPacket("d2045704010100"))
        transport.push("55aa02ff0103dead".hexToBytes()) // 校验位被改掉 → 整块拒收
        advanceUntilIdle()

        val snap = session.snapshot.value
        assertEquals(1, snap.framesDecoded)
        assertEquals(1, snap.uncalibratedFrames)
        assertEquals(8, snap.bytesUndecoded)
        assertNull(snap[TelemetryField.speed])
        // 拒收块由会话排空，不会无限增长（notes §10-6）
        transport.push(demoPacket("d2045704010100"))
        advanceUntilIdle()
        assertEquals(8, session.snapshot.value.bytesUndecoded)
        session.stop()
    }

    @Test
    fun `标定热切换重放已收帧`() = runTest {
        // app_v2/test/calibration_hotswap_test.dart 的逐案移植
        val transport = FakeTransport()
        val session = SessionManager(transport, CalibrationSet.empty, "ble-1", this)
        session.start()
        advanceUntilIdle()
        transport.push(demoPacket("d2045704010100"))
        transport.push(demoPacket("d2045704010100"))
        advanceUntilIdle()

        assertNull(session.snapshot.value[TelemetryField.speed])
        assertEquals(2, session.snapshot.value.uncalibratedFrames)

        session.setCalibration(DemoProfile.set())
        assertEquals(12.34, session.snapshot.value[TelemetryField.speed]!!, 1e-9)
        // 计数从 0 重算后重放：仍是 2 帧、0 未标定
        assertEquals(2, session.snapshot.value.framesDecoded)
        assertEquals(0, session.snapshot.value.uncalibratedFrames)

        session.setCalibration(CalibrationSet.empty)
        assertNull(session.snapshot.value[TelemetryField.speed])
        assertEquals(2, session.snapshot.value.framesDecoded)
        assertEquals(2, session.snapshot.value.uncalibratedFrames)
        session.stop()
    }

    @Test
    fun `演示会话绝不写用户标定存储且结束后不残留`() = runTest {
        val kv = InMemoryKeyValueStore()
        val store = CalibrationStore(kv)
        assertEquals(null, store.load())

        val transport = DemoTransport(scope = this, periodMillis = 250)
        val session = DemoSession.create(this, transport, "demo-9")
        session.start()
        advanceTimeBy(1_000)
        runCurrent()
        // 演示读数在会话里可见……
        assertNotNull(session.snapshot.value[TelemetryField.speed])
        // ……但用户存储里没有任何演示标定的痕迹
        assertEquals(emptySet<String>(), kv.keys())
        assertEquals(null, store.load())

        session.stop()
        advanceUntilIdle()
        assertEquals(emptySet<String>(), kv.keys())
        assertEquals(null, store.load())
    }

    @Test
    fun `演示传输的锁能力只有固定帧且断链即抛`() = runTest {
        val transport = DemoTransport(scope = this, periodMillis = 250)
        assertThrowsLike("演示链路未连接") { transport.setLock(lock = true) }

        transport.connect()
        runCurrent()
        assertTrue(transport.lockControlAvailable)
        transport.setLock(lock = true)
        assertEquals("55aa040a037001007dff", transport.lastSimulatedLockFrame?.toHex())
        assertEquals(true, transport.lastSimulatedLockValue)
        assertEquals(1, transport.simulatedLockCommands)
        transport.setLock(lock = false)
        assertEquals(2, transport.simulatedLockCommands)

        transport.disconnect()
        assertThrowsLike("演示链路未连接") { transport.setLock(lock = false) }
    }

    @Test
    fun `演示序列满足结构不变式而不承诺逐值复现`() = runTest {
        // PRNG 与 Dart 不同流（notes §4.6/§10-12）：只按不变式测试
        val transport = DemoTransport(scope = this, periodMillis = 250)
        val packets = mutableListOf<IncomingPacket>()
        backgroundScope.launch { transport.incoming.collect { packets.add(it) } }
        transport.connect()
        advanceTimeBy(60_000)
        runCurrent()
        transport.disconnect()

        assertTrue("60 秒应有很多帧", packets.size >= 200)
        var prevBattery = -1.0
        for (p in packets) {
            val bytes = p.bytes
            assertTrue("明文 55AA 头部", bytes[0].toInt() and 0xFF == 0x55 && bytes[1].toInt() and 0xFF == 0xAA)
            val frame = decodeFrame(bytes).frame
            assertNotNull("校验和自洽", frame)
            assertEquals(0x06, frame!!.cmd)
            assertEquals(0x01, frame.a1)
            assertEquals(0x05, frame.a2)
            assertEquals(8, frame.payload.size)
            val speed = (frame.payload[0].toInt() and 0xFF or ((frame.payload[1].toInt() and 0xFF) shl 8)).let {
                if (it >= 0x8000) it - 0x10000 else it
            } / 100.0
            val battery = (frame.payload[2].toInt() and 0xFF).toDouble()
            val tempRaw = (frame.payload[3].toInt() and 0xFF or ((frame.payload[4].toInt() and 0xFF) shl 8)).let {
                if (it >= 0x8000) it - 0x10000 else it
            } / 10.0
            val workMode = frame.payload[5].toInt() and 0xFF
            assertTrue("速度非负有界", speed >= 0.0 && speed <= 13.0)
            assertTrue("温度在 24.8..27.2", tempRaw in 24.8..27.2)
            // workMode 由四舍五入前的浮点值决定，载荷只保留整百分位：
            // 在 0.5 边界两侧各差 1 个百分位是允许的，按稳健方向断言
            if (workMode == 1) {
                assertTrue("模式 1 ⇒ 速度 ≥ 0.5", speed >= 0.5)
            } else {
                assertTrue("模式 0 ⇒ 速度 ≤ 0.51", speed <= 0.51)
            }
            assertEquals(0, frame.payload[6].toInt())
            // off7 = 预计里程 u8 km（结构对齐真车 0x2a off2，E71）：静止估算 12..14
            val range = frame.payload[7].toInt() and 0xFF
            assertTrue("预计里程在 12..14", range in 12..14)

            assertTrue("电量单调不增", battery <= prevBattery || prevBattery < 0)
            prevBattery = battery
            assertEquals("demo-0001", p.serviceUuid)
            assertEquals("demo-0002", p.charUuid)
        }
    }

    @Test
    fun `证据时间线按到达顺序只记录车辆发来的帧`() = runTest {
        val transport = FakeTransport()
        val session = SessionManager(transport, DemoProfile.set(), "ble-1", this)
        session.start()
        advanceUntilIdle()

        transport.push(demoPacket("d2045704010100")) // speed 1234 → 12.34
        advanceUntilIdle()
        transport.push("55aa02ff0103dead".hexToBytes()) // 校验失败：不进时间线
        advanceUntilIdle()
        transport.push(demoPacket("e8035704010100")) // speed 1000 → 10.0
        advanceUntilIdle()

        // 只记录通知里解出的帧（应用出站轮询不经过本会话的 incoming 流）
        val events = session.evidenceEvents
        assertEquals(2, events.size)
        assertEquals(listOf("d2045704010100", "e8035704010100"), events.map { it.payloadHex })
        assertEquals(listOf("0x06/0x01/0x05", "0x06/0x01/0x05"), events.map { it.groupKey })
        assertTrue(events.all { it.calibrated })
        assertEquals(1234L, events.first().rawOf(TelemetryField.speed))
        assertNotNull(events.first().values[TelemetryField.batteryPercent])
        assertEquals(0L, events.first().rawOf(TelemetryField.warningCode))
        assertTrue(!events.first().hasNonZeroCode)
        assertEquals(2, session.frames.size)

        session.stop()
    }

    @Test
    fun `无标定会话的证据事件标为未标定且没有读数`() = runTest {
        val transport = FakeTransport()
        val session = SessionManager(transport, CalibrationSet.empty, "ble-1", this)
        session.start()
        advanceUntilIdle()

        transport.push(demoPacket("d2045704010100"))
        advanceUntilIdle()

        val event = session.evidenceEvents.single()
        assertTrue(!event.calibrated)
        assertTrue(event.values.isEmpty())
        assertNull(event.rawOf(TelemetryField.speed))
        assertEquals("d2045704010100", event.payloadHex)
        session.stop()
    }

    @Test
    fun `证据时间线超过 2000 条时丢弃最旧 500 条`() = runTest {
        val transport = FakeTransport()
        val session = SessionManager(transport, DemoProfile.set(), "ble-1", this)
        session.start()
        advanceUntilIdle()

        repeat(2001) {
            transport.push(demoPacket("d2045704010100"))
            runCurrent()
        }

        // 上限 2000，超出后丢最旧 500 → 1501 条；帧与快照计数保持全量
        assertEquals(1501, session.evidenceEvents.size)
        assertEquals(2001, session.frames.size)
        assertEquals(2001, session.snapshot.value.framesDecoded)
        session.stop()
    }

    private inline fun assertThrowsLike(message: String, block: () -> Unit) {
        val e = try {
            block()
            null
        } catch (ex: LockCommandException) {
            ex
        } ?: throw AssertionError("期望 LockCommandException：$message")
        assertEquals(message, e.toString())
    }
}
