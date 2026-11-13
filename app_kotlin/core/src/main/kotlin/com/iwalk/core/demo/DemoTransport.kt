package com.iwalk.core.demo

import com.iwalk.core.session.BluetoothTransport
import com.iwalk.core.session.IncomingPacket
import com.iwalk.core.session.LockCommandException
import com.iwalk.core.session.LockControl
import com.iwalk.core.session.TransportEvent
import com.iwalk.core.session.TransportState
import com.iwalk.protocol.IbwalkFrame
import com.iwalk.protocol.LockProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * 模拟滑板车（notes/domain-logic.md §4.6）：发出的就是真格式的明文 `55 AA` 帧，
 * 解码器没有任何特殊测试路径。
 *
 * 演示序列的 PRNG 与 Dart（`Random(20261020)`）不同流——跨端只对齐结构与字段
 * 语义，数值按不变式测试（规格 §11"演示读数不可跨端复现"）。
 */
class DemoTransport(
    private val scope: CoroutineScope,
    private val periodMillis: Long = 250,
    private val connectDelayMillis: Long = 300,
    seed: Long = 20261020L,
) : BluetoothTransport, LockControl {

    companion object {
        const val DEMO_SERVICE_UUID = "demo-0001"
        const val DEMO_CHAR_UUID = "demo-0002"

        /** 演示帧形状：CMD 0x06 / A1 0x01 / A2 0x05，载荷 7 字节。 */
        const val DEMO_CMD = 0x06
        const val DEMO_A1 = 0x01
        const val DEMO_A2 = 0x05
    }

    override val id: String get() = "demo"
    override val label: String get() = "演示模式（模拟车）"
    override val isDemo: Boolean get() = true

    private val random = Random(seed)
    private val _incoming = MutableSharedFlow<IncomingPacket>(extraBufferCapacity = 64)
    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 16)
    override val incoming: Flow<IncomingPacket> = _incoming
    override val events: Flow<TransportEvent> = _events

    @Volatile
    override var state: TransportState = TransportState.idle
        private set

    private var ticker: Job? = null
    private var t = 0.0
    private var battery = 87.0
    private var speed = 0.0
    private var temperature = 26.0
    private var estimatedRange = 13.0

    /** 最近一条模拟指令。故意不伪造 ACK、不伪造锁状态（§4.4）。 */
    @Volatile
    var lastSimulatedLockFrame: ByteArray? = null
        private set

    @Volatile
    var lastSimulatedLockValue: Boolean? = null
        private set

    @Volatile
    var simulatedLockCommands: Int = 0
        private set

    override val lockControlAvailable: Boolean get() = state == TransportState.connected

    override suspend fun connect() {
        setState(TransportState.connecting)
        delay(connectDelayMillis)
        setState(TransportState.connected)
        ticker = scope.launch {
            while (true) {
                delay(periodMillis)
                tick()
            }
        }
    }

    override suspend fun disconnect() {
        ticker?.cancel()
        ticker = null
        setState(TransportState.disconnected)
    }

    override suspend fun setLock(lock: Boolean) {
        if (state != TransportState.connected) {
            throw LockCommandException("演示链路未连接")
        }
        delay(220)
        lastSimulatedLockFrame = LockProtocol.frame(lock)
        lastSimulatedLockValue = lock
        simulatedLockCommands++
    }

    private fun setState(next: TransportState, message: String? = null) {
        state = next
        _events.tryEmit(TransportEvent(next, message))
    }

    private fun tick() {
        t += periodMillis / 1000.0
        speed = max(0.0, 6.5 + 6.0 * sin(t / 7.0) - random.nextDouble() * 0.3)
        battery = max(0.0, battery - periodMillis / 1000.0 * 0.002)
        temperature = 26.0 + 1.2 * sin(t / 25.0)
        // 预计里程在静止估算下 ±1 km 波动（E71 真车行为：12/13/14 km）
        estimatedRange = 13.0 + 1.2 * sin(t / 30.0)

        // 载荷 8 字节：速度×100 int16 LE、电量 u8(0..100)、温度×10 int16 LE、
        // 工作模式与告警码只给原始字节——数值到标签的映射从未验证（含 0）、
        // 预计里程 u8 km（结构对齐真车 0x2a off2，E71）。
        val speedRaw = speed.times(100).roundToInt() and 0xFFFF
        val tempRaw = temperature.times(10).roundToInt() and 0xFFFF
        val payload = byteArrayOf(
            (speedRaw and 0xFF).toByte(),
            (speedRaw shr 8 and 0xFF).toByte(),
            battery.roundToInt().coerceIn(0, 100).toByte(),
            (tempRaw and 0xFF).toByte(),
            (tempRaw shr 8 and 0xFF).toByte(),
            if (speed > 0.5) 1 else 0,
            0,
            estimatedRange.roundToInt().coerceIn(0, 255).toByte(),
        )
        val frame = IbwalkFrame(DEMO_CMD, DEMO_A1, DEMO_A2, payload).encode()
        _incoming.tryEmit(IncomingPacket(frame, DEMO_SERVICE_UUID, DEMO_CHAR_UUID))
    }
}
