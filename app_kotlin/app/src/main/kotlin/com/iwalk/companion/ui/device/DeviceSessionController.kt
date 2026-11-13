package com.iwalk.companion.ui.device

import com.iwalk.companion.ble.RealLinkFactory
import com.iwalk.companion.ble.ScannedDevice
import com.iwalk.companion.ble.isTransientLinkFailure
import com.iwalk.companion.ble.linkErrorText
import com.iwalk.companion.data.BuiltinCalibration
import com.iwalk.companion.data.ExportedFiles
import com.iwalk.companion.data.LockSettingsStore
import com.iwalk.companion.data.MemoryLockSettingsStore
import com.iwalk.core.session.LockControl
import com.iwalk.core.session.LockCommandRecord
import com.iwalk.core.session.lockBlockedReason
import com.iwalk.core.model.TelemetryField
import com.iwalk.protocol.LockProtocol
import com.iwalk.protocol.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import com.iwalk.companion.data.ExportStore
import com.iwalk.core.decode.TelemetrySnapshot
import com.iwalk.core.demo.DemoProfile
import com.iwalk.core.demo.DemoSession
import com.iwalk.core.demo.DemoTransport
import com.iwalk.core.model.CalibrationSet
import com.iwalk.core.session.EvidenceEvent
import com.iwalk.core.session.LinkFacts
import com.iwalk.core.session.SessionManager
import com.iwalk.core.session.TransportState
import com.iwalk.core.session.buildEvidenceJson
import com.iwalk.core.session.buildRawJsonLines
import com.iwalk.core.store.CalibrationStore
import com.iwalk.core.store.InMemoryKeyValueStore
import com.iwalk.protocol.IbwalkFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime

/**
 * 设备页渲染所需的会话投影：把 :core 的 [SessionManager] 三条 StateFlow
 * （快照/链路态/链路消息）加上会话元数据折叠成一个不可变 UI 状态。
 *
 * 工单 03 起同时承载真车会话：扫描页拿到 [ScannedDevice] 后调用 [connectReal]，
 * 成功后设备页直接进入分支 B；失败则复位到分支 A 并把失败原因写进
 * [DeviceUiState.connectionMessage]。
 */
data class DeviceUiState(
    /** 有会话即进入分支 B（对齐 Dart `session != null`）。 */
    val active: Boolean = false,
    val isDemo: Boolean = false,
    /** Dart `_startingDemo`：演示入口防重复点击期间的忙碌位。 */
    val startingDemo: Boolean = false,
    val transportState: TransportState = TransportState.idle,
    /** Dart `connectionMessageProvider`；演示启动失败/真车连接失败也落在这里。 */
    val connectionMessage: String? = null,
    val snapshot: TelemetrySnapshot = TelemetrySnapshot.empty,
    /** 会话标定：演示恒为 [DemoProfile.set()]，真车为用户标定。 */
    val calibration: CalibrationSet = CalibrationSet.empty,
    /** 分支 A 的「导入标定以显示读数」入口条件（Dart `userCalibration.profiles.isNotEmpty`）。 */
    val hasUserCalibration: Boolean = false,
    val startedAtMillis: Long? = null,
    val sessionId: String? = null,
    /** 会话已收帧（分析页按 CMD/A1/A2 分组统计）。无会话时为空。 */
    val frames: List<IbwalkFrame> = emptyList(),
    /** 证据时间线（到达顺序，最旧在前；UI 自己取尾部 12 条）。 */
    val evidenceEvents: List<EvidenceEvent> = emptyList(),
    /** 原始通知行 `<ISO-8601> <小写hex>`，按到达顺序。 */
    val rawLines: List<String> = emptyList(),
    /** 时间线是否已被 2000 条上限裁剪（会话自己记录，不由计数反推）。 */
    val timelineTruncated: Boolean = false,
    /** 真车 GATT/传输组事实；演示链路为 null（工单 03 链路详情）。 */
    val linkFacts: LinkFacts? = null,
    val lockAvailable: Boolean = false,
    val lockBusy: Boolean = false,
    val lockCommands: List<LockCommandRecord> = emptyList(),
)

/**
 * 演示与真车会话的唯一装配点（Dart `providers` 的对应物）。
 *
 * 演示标定**只**经 [DemoSession.create] 注入会话自身（规格 §5）：本类没有任何路径
 * 把 [DemoProfile.set()] 交给 [CalibrationStore.save]，用户标定存储对演示会话是只读的，
 * 所以「结束演示后不残留」（工单 02 验收第 4 条）在结构上成立，测试从公开 API 复核。
 *
 * 协程 scope 用 [Dispatchers.Default]：Robolectric 的调度器不驱动真实时间，
 * 演示帧必须在宿主循环之外继续推进，状态经 [StateFlow] 回到组合侧。
 */
data class UserSettings(val calibration: CalibrationSet, val lockControlEnabled: Boolean)

class DeviceSessionController(
    private val calibrationStore: CalibrationStore = CalibrationStore(InMemoryKeyValueStore()),
    private val lockSettingsStore: LockSettingsStore = MemoryLockSettingsStore(),
    private val demoPeriodMillis: Long = 250,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** 真车链路工厂；未注入时 [connectReal] 直接失败（JVM 测试默认）。 */
    private val realLinkFactory: RealLinkFactory? = null,
    /** 导出落盘；未注入时 [exportSession] 返回不可用（JVM 测试默认）。 */
    private val exportStore: ExportStore? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _ui = MutableStateFlow(
        DeviceUiState(hasUserCalibration = calibrationStore.load() != null),
    )
    val ui: StateFlow<DeviceUiState> = _ui

    private val _settings = MutableStateFlow(UserSettings(userCalibration(), lockSettingsStore.enabled))
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()
    private val _lastExport = MutableStateFlow<ExportedFiles?>(null)
    val lastExport: StateFlow<ExportedFiles?> = _lastExport.asStateFlow()
    private val exportMutex = Mutex()
    private val commandMutex = Mutex()
    private val _exporting = MutableStateFlow(false)

    /** Dart `_exporting`：防重复提交的真正闸门（按钮禁用只是辅助）。 */
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    @Volatile private var session: SessionManager? = null
    private val sessionJobs = mutableListOf<Job>()

    /**
     * 真车会话的标定来源：用户导入优先；未导入时用内置标定
     * （[BuiltinCalibration]，E64/E71 真车证据，2026-11-13 起随包分发）。
     */
    fun userCalibration(): CalibrationSet =
        calibrationStore.load() ?: BuiltinCalibration.set ?: CalibrationSet.empty

    /** Parse first so a bad paste cannot destroy the saved calibration. Demo profiles stay isolated. */
    fun importCalibration(text: String): String? = try {
        val parsed = CalibrationSet.decode(text)
        require(parsed.profiles.isNotEmpty()) { "标定列表不能为空；如需清除，请使用清除标定" }
        parsed.profiles.forEach { profile ->
            require(profile.cmd in 0..255 && profile.a1 in 0..255 && profile.a2 in 0..255) { "帧组字节必须在 0–255 之间" }
            profile.fields.forEach {
                require(it.offset in 0..255 && it.scale.isFinite() && it.bias.isFinite()) { "偏移或换算参数无效" }
            }
        }
        applyUserCalibration(parsed)
        null
    } catch (e: Exception) { "导入失败：${e.message ?: "JSON 格式无效"}" }

    fun clearCalibration() = applyUserCalibration(CalibrationSet.empty)

    private fun applyUserCalibration(value: CalibrationSet) {
        calibrationStore.save(value)
        _settings.update { it.copy(calibration = value) }
        val s = session
        if (s != null && !s.transport.isDemo) s.setCalibration(value)
        _ui.update { it.copy(hasUserCalibration = value.profiles.isNotEmpty(),
            calibration = s?.calibration ?: value,
            snapshot = s?.snapshot?.value ?: it.snapshot) }
    }

    /** The UI calls this only after explicit risk confirmation. */
    fun setLockControlEnabled(enabled: Boolean) {
        lockSettingsStore.enabled = enabled
        _settings.update { it.copy(lockControlEnabled = enabled) }
    }

    suspend fun sendLock(lock: Boolean): String {
        if (!commandMutex.tryLock()) return "指令发送中，请勿重复操作"
        val s = session
        if (s == null) { commandMutex.unlock(); return "连接断开，无法发送指令" }
        _ui.update { it.copy(lockBusy = true) }
        val control = s.transport as? LockControl
        var failure = lockBlockedReason(
            settings.value.lockControlEnabled, s.transport.isDemo,
            s.transport.state == TransportState.connected,
            control?.lockControlAvailable == true,
            s.snapshot.value[TelemetryField.speed],
        )
        val at = Instant.now()
        try {
            if (failure == null) control!!.setLock(lock)
        } catch (e: CancellationException) {
            failure = "发送已取消，车端状态未确认"
            throw e
        } catch (e: Exception) {
            failure = e.message ?: e.toString()
        } finally {
            val record = LockCommandRecord(at, lock, LockProtocol.frame(lock).toHex(),
                failure == null, s.transport.isDemo, failure)
            s.recordLockCommand(record)
            _ui.update { if (session === s) it.copy(lockBusy = false, lockCommands = s.capture().commands) else it }
            commandMutex.unlock()
        }
        return failure ?: if (s.transport.isDemo) "${if (lock) "关锁" else "解锁"}指令已模拟发送 · 未操作真车"
            else "已发送 · 车端状态未确认"
    }

    /**
     * 导出当前会话（Dart `diagnostics_page._export` 对应物）。
     *
     * 返回 SnackBar 文案：成功 `已导出：<目录>`，失败 `导出失败：<原因>`；
     * 无会话或在途时返回空串（调用方静默忽略）。入口处对 frames/rawLines/evidence
     * 各取一次只读快照，保证 `raw_packets` 等于 JSONL 行数、Σ帧组帧数等于
     * `frames_total`（E60 不变量）。
     */
    suspend fun exportSession(): String {
        val s = session
        if (s == null || !exportMutex.tryLock()) return ""
        _exporting.value = true
        try {
            val store = exportStore ?: return "导出失败：导出目录不可用"
            val capture = s.capture()
            val frames = capture.frames
            val rawLines = capture.rawLines
            val events = capture.events
            val commands = capture.commands
            val now = LocalDateTime.now()
            val result = withContext(Dispatchers.IO) { store.write(
                sessionId = s.sessionId,
                rawJsonLines = buildRawJsonLines(
                    rawLines = rawLines,
                    deviceId = s.transport.id,
                    sessionId = s.sessionId,
                    now = Instant.now(),
                ),
                evidenceJson = buildEvidenceJson(
                    sessionId = s.sessionId,
                    transport = s.transport,
                    frames = frames,
                    rawLineCount = rawLines.size,
                    events = events,
                    lockCommands = commands,
                    exportedAt = now,
                ),
                now = now,
            )
            }
            _lastExport.value = result
            return "已导出：${result.directory.path}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return "导出失败：${e.message ?: e.toString()}"
        } finally {
            _exporting.value = false
            exportMutex.unlock()
        }
    }

    /** `使用演示数据（不连接真车）`：防重复启动（Dart `_startingDemo`）。 */
    fun startDemo() {
        if (session != null) return
        _ui.update { it.copy(startingDemo = true) }
        val transport = DemoTransport(scope = scope, periodMillis = demoPeriodMillis)
        val started = DemoSession.create(
            scope = scope,
            transport = transport,
            sessionId = DemoSession.demoSessionId(nowMillis()),
        )
        session = started
        bindSession(started, isDemo = true)
        scope.launch {
            val failure = runCatching { started.start() }
                .exceptionOrNull()?.toString()
            // Dart device_page.dart:88-100：链路未连上或抛错都结束会话并提示启动失败
            if (session !== started) { disposeSession(started); return@launch }
            if (started.transport.state != TransportState.connected || failure != null) {
                stopSession()
                _ui.update { it.copy(connectionMessage = DEMO_START_FAILED) }
            }
        }
    }

    /**
     * 扫描页连接真车：成功返回 true（UI 已在分支 B），失败返回 false 并把原因写进
     * [DeviceUiState.connectionMessage]。连接期间界面由扫描页覆盖，不会先闪一下分支 A。
     *
     * 瞬时失败（初始化被中断、`connectGatt` 返回 null）自动重试一次：真机验收里就是
     * 「首点失败、再点成功」，重试只是把用户那一次点击替掉。超时类失败不重试——
     * 再等 15 s 没有意义，交给用户判断。
     */
    suspend fun connectReal(device: ScannedDevice): Boolean {
        if (session != null) return false
        val factory = realLinkFactory
        if (factory == null) {
            _ui.update { it.copy(connectionMessage = "真车链路未配置") }
            return false
        }
        var attempts = 0
        while (true) {
            attempts++
            val beganAt = nowMillis()
            val transport = runCatching { factory.create(device) }.getOrElse { e ->
                _ui.update { it.copy(connectionMessage = linkErrorText(e)) }
                return false
            }
            _ui.update { it.copy(connectionMessage = null) }
            val started = SessionManager(
                transport = transport,
                calibration = userCalibration(),
                sessionId = DemoSession.bleSessionId(nowMillis()),
                scope = scope,
            )
            session = started
            bindSession(started, isDemo = false)

            val failure = runCatching { started.start() }.exceptionOrNull()
            if (session !== started) { disposeSession(started); return false }
            if (failure == null && transport.state == TransportState.connected) {
                // 收集器可能晚于连接事件启动；连接成功后显式落一次最终链路态与 GATT 事实。
                _ui.update {
                    it.copy(
                        transportState = transport.state,
                        connectionMessage = transport.lastMessage,
                        linkFacts = transport.linkFacts,
                    )
                }
                return true
            }

            val detail = failure?.let(::linkErrorText)
                ?: transport.lastMessage
                ?: UNMATCHED_GROUP_MESSAGE
            stopSession()
            val retryable = attempts < MAX_CONNECT_ATTEMPTS &&
                isTransientLinkFailure(failure) &&
                nowMillis() - beganAt <= RETRY_WINDOW_MILLIS
            if (!retryable) {
                _ui.update { it.copy(connectionMessage = withAttempts(detail, attempts)) }
                return false
            }
            delay(RETRY_DELAY_MILLIS)
        }
    }

    /** 失败文案带上重试次数：让用户知道这不是「点了一次就放弃」。 */
    private fun withAttempts(detail: String, attempts: Int): String =
        if (attempts <= 1) detail else "$detail · 已自动重试 ${attempts - 1} 次仍失败"

    /** 结束会话（演示或真车）：UI 复位到分支 A。真车的断开确认由界面层负责。 */
    fun stopSession() {
        val s = session ?: return
        session = null
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        _ui.value = DeviceUiState(hasUserCalibration = calibrationStore.load() != null)
        disposeSession(s)
    }

    /** 兼容旧调用：演示结束与普通会话结束同一条路径。 */
    fun stopDemo() = stopSession()

    /** 宿主销毁时清理：先收尾会话，再取消整个 scope（演示 tick 随之终止）。 */
    fun close() {
        val s = session
        session = null
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        _ui.value = DeviceUiState(hasUserCalibration = calibrationStore.load() != null)
        disposeSession(s)
        scope.cancel()
    }

    // ——————————————————————— 内部装配 ———————————————————————

    private fun bindSession(started: SessionManager, isDemo: Boolean) {
        _ui.update {
            it.copy(
                startingDemo = false,
                active = true,
                isDemo = isDemo,
                transportState = started.connectionState.value,
                connectionMessage = started.connectionMessage.value,
                snapshot = started.snapshot.value,
                calibration = started.calibration,
                hasUserCalibration = calibrationStore.load() != null,
                startedAtMillis = nowMillis(),
                sessionId = started.sessionId,
                linkFacts = started.transport.linkFacts,
                lockAvailable = (started.transport as? LockControl)?.lockControlAvailable == true,
                frames = started.frames,
                evidenceEvents = started.evidenceEvents,
                rawLines = started.rawLines,
                timelineTruncated = started.isTimelineTruncated,
            )
        }
        sessionJobs += scope.launch {
            started.snapshot.collect {
                val capture = started.capture()
                // UI 与导出使用同一事务快照；旧会话的迟到回调不能修改新会话。
                _ui.update {
                    if (session !== started) it else it.copy(
                        snapshot = capture.snapshot,
                        frames = capture.frames,
                        evidenceEvents = capture.events,
                        rawLines = capture.rawLines,
                        timelineTruncated = started.isTimelineTruncated,
                        calibration = started.calibration,
                        linkFacts = started.transport.linkFacts,
                        lockAvailable = (started.transport as? LockControl)?.lockControlAvailable == true,
                    )
                }
            }
        }
        sessionJobs += scope.launch {
            started.connectionState.collect { st ->
                _ui.update { if (session !== started) it else it.copy(transportState = st, linkFacts = started.transport.linkFacts,
                    lockAvailable = (started.transport as? LockControl)?.lockControlAvailable == true) }
            }
        }
        sessionJobs += scope.launch {
            started.connectionMessage.collect { msg -> _ui.update { if (session !== started) it else it.copy(connectionMessage = msg) } }
        }
    }

    /**
     * 会话收尾不能依赖本类 scope：`close()` 会马上取消 scope。用一个独立的 IO scope
     * 只做 disconnect/收流，保证 Activity 销毁时也能停轮询并释放 GATT。
     */
    private fun disposeSession(s: SessionManager?) {
        if (s == null) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { s.stop() }
    }

    private companion object {
        /** Dart `演示会话启动失败`（未带异常详情时的短文案）。 */
        const val DEMO_START_FAILED = "演示会话启动失败"

        /** 传输组未匹配时的兜底文案（与扫描页一致）。 */
        const val UNMATCHED_GROUP_MESSAGE = "未匹配到已知传输组（G1–G5），仅展示服务树"

        /** 首次失败后最多再试一次：真机上重试一次即可连上。 */
        const val MAX_CONNECT_ATTEMPTS = 2

        /** 重试前留一点时间给上一个 GATT 真正关闭（`close()` 是异步排队的）。 */
        const val RETRY_DELAY_MILLIS = 800L

        /** 只有「快速失败」才重试；超过这个时长说明是超时类问题，重试只是再等一轮。 */
        const val RETRY_WINDOW_MILLIS = 5_000L
    }
}
