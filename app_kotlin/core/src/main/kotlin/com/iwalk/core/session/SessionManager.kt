package com.iwalk.core.session

import com.iwalk.core.decode.TelemetryDecoder
import com.iwalk.core.decode.TelemetrySnapshot
import com.iwalk.core.model.CalibrationSet
import com.iwalk.protocol.IbwalkFrame
import com.iwalk.protocol.StreamDecoder
import com.iwalk.protocol.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 会话装配（notes/domain-logic.md §4.2）：字节 → 每特征独立 [StreamDecoder] →
 * [IbwalkFrame] → 标定驱动 [TelemetryDecoder] → 快照。
 *
 * 三条不变式：
 * 1. 每个通知特征一个解码器——真车把数据特征 ⊕0x55，其它特征可能仍是明文，
 *    共享解码器会锁错模式（E46/E50）；
 * 2. `rejected` 由本会话排空并计入 `bytesUndecoded`（§10-6）；
 * 3. 本类只解码、不持久化。演示标定通过构造注入，天然不会流向用户存储。
 *
 * 有意偏差：Dart 的 `_frames/_rawLines` 无界（§10-8）；这里同样保留全量帧
 * （热切换重放要用）。证据时间线按 §4.2 上限 2000、超出时丢弃最旧 500 条。
 */
class SessionManager(
    val transport: BluetoothTransport,
    calibration: CalibrationSet,
    val sessionId: String,
    private val scope: CoroutineScope,
) {
    private val monitor = Any()
    private val decoders = mutableMapOf<String, StreamDecoder>()
    private val frameLog = mutableListOf<IbwalkFrame>()
    private val receivedAt = mutableListOf<Instant>()
    private val rawLineLog = mutableListOf<String>()
    private val evidenceLog = mutableListOf<EvidenceEvent>()
    private val commandLog = mutableListOf<LockCommandRecord>()
    private var telemetry = TelemetryDecoder(calibration)
    private var currentCalibration = calibration
    private var undecodedBytes = 0
    private var incomingJob: Job? = null
    private var eventsJob: Job? = null

    /**
     * 时间线是否已经按上限裁过（一旦裁过就为 true，不随计数回退）。
     * UI 用它区分「时间线本来就这么长」与「更早的条目已被丢弃」，不靠帧数反推。
     */
    @Volatile
    private var timelineTrimmed = false

    val isTimelineTruncated: Boolean
        get() = synchronized(monitor) { timelineTrimmed }

    private val _snapshotFlow = MutableStateFlow(TelemetrySnapshot.empty)
    private val _stateFlow = MutableStateFlow(transport.state)
    private val _messageFlow = MutableStateFlow(transport.lastMessage)

    val snapshot: StateFlow<TelemetrySnapshot> = _snapshotFlow
    val connectionState: StateFlow<TransportState> = _stateFlow
    val connectionMessage: StateFlow<String?> = _messageFlow

    val calibration: CalibrationSet
        get() = synchronized(monitor) { currentCalibration }

    /**
     * 标定热切换：换一组标定 → 重建解码器 → 按到达顺序重放已收帧
     * （计数从 0 重算，保留原始接收时间，旧值不会因重新标定变成新值）。
     */
    fun setCalibration(value: CalibrationSet) {
        synchronized(monitor) {
            currentCalibration = value
            telemetry = TelemetryDecoder(value)
            frameLog.forEachIndexed { index, frame -> telemetry.ingest(frame, receivedAt[index]) }
            emitSnapshotLocked()
        }
    }

    /** 只读快照（帧与原始行都是拷贝，调用方拿不到可变列表）。 */
    val frames: List<IbwalkFrame> get() = synchronized(monitor) { frameLog.toList() }
    val rawLines: List<String> get() = synchronized(monitor) { rawLineLog.toList() }

    /** 证据时间线（到达顺序，最旧在前；调用方拿到的是拷贝）。 */
    val evidenceEvents: List<EvidenceEvent> get() = synchronized(monitor) { evidenceLog.toList() }

    /** Export and UI consume one transaction, even while BLE notifications arrive. */
    fun recordLockCommand(record: LockCommandRecord) = synchronized(monitor) { commandLog.add(record) }

    fun capture(): SessionCapture = synchronized(monitor) {
        SessionCapture(frameLog.toList(), rawLineLog.toList(), evidenceLog.toList(), _snapshotFlow.value, commandLog.toList())
    }

    init {
        incomingJob = scope.launch {
            transport.incoming.collect { onPacket(it) }
        }
        eventsJob = scope.launch {
            transport.events.collect { event ->
                synchronized(monitor) {
                    _stateFlow.value = event.state
                    _messageFlow.value = event.message
                    emitSnapshotLocked()
                }
            }
        }
    }

    suspend fun start() {
        transport.connect()
        // connecting 事件可能早于订阅建立；状态属性直接兜底
        _stateFlow.value = transport.state
    }

    /** 停会话：断链 + 取消两个收集协程（对齐 Dart `_sub.cancel()`）。 */
    suspend fun stop() {
        transport.disconnect()
        incomingJob?.cancel()
        eventsJob?.cancel()
        incomingJob = null
        eventsJob = null
    }

    private fun onPacket(packet: IncomingPacket) {
        val hex = packet.bytes.toHex()
        val now = Instant.now()
        synchronized(monitor) {
            rawLineLog += "${now} $hex"
            // 每特征一个解码器，键为 charUuid ?: "*"（§4.2 第 4 步）
            val decoder = decoders.getOrPut(packet.charUuid ?: "*") { StreamDecoder() }
            val decoded = decoder.feed(packet.bytes)
            while (decoder.rejected.isNotEmpty()) {
                undecodedBytes += decoder.rejected.removeAt(0).size
            }
            for (frame in decoded) {
                frameLog += frame
                receivedAt += now
                val touched = telemetry.ingest(frame, now)
                // §4.2 第 6 步：每帧一条被动证据，values 只含本帧更新的字段
                evidenceLog += EvidenceEvent(
                    at = now,
                    cmd = frame.cmd,
                    a1 = frame.a1,
                    a2 = frame.a2,
                    payload = frame.payload.copyOf(),
                    calibrated = currentCalibration.match(frame.cmd, frame.a1, frame.a2) != null,
                    values = touched.mapNotNull { field -> telemetry.valueOf(field)?.let { field to it } }.toMap(),
                )
            }
            // §4.2 时间线上限 2000：超出时丢弃最旧 500 条（size 收敛到 1501）
            if (evidenceLog.size > EVIDENCE_LIMIT) {
                evidenceLog.subList(0, EVIDENCE_TRIM).clear()
                timelineTrimmed = true
            }
            emitSnapshotLocked()
        }
    }

    private fun emitSnapshotLocked() {
        _snapshotFlow.value = telemetry.snapshot(bytesUndecoded = undecodedBytes, at = receivedAt.lastOrNull() ?: Instant.EPOCH)
    }

    private companion object {
        /** Dart `_evidence.length > 2000` 的硬上限。 */
        const val EVIDENCE_LIMIT = 2000

        /** 超出上限时一次丢弃的最旧条数（对齐 Dart `removeRange(0, 500)`）。 */
        const val EVIDENCE_TRIM = 500
    }
}

/** Lists are captured together under the session monitor. */
data class SessionCapture(
    val frames: List<IbwalkFrame>,
    val rawLines: List<String>,
    val events: List<EvidenceEvent>,
    val snapshot: TelemetrySnapshot,
    val commands: List<LockCommandRecord>,
)
