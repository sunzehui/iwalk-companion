package com.iwalk.companion.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.content.Context
import com.iwalk.protocol.deviceSerialFromName
import com.iwalk.protocol.isSupportedDeviceName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 扫描页状态（notes/ui-device-scan.md §3）。 */
data class ScanState(
    /** 全部结果，未过滤；UI 按 [onlyWhitelisted] 过滤。 */
    val devices: List<ScannedDevice> = emptyList(),
    val onlyWhitelisted: Boolean = true,
    val scanning: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
) {
    val visibleDevices: List<ScannedDevice>
        get() = if (onlyWhitelisted) devices.filter { it.whitelisted } else devices
}

/**
 * 扫描页控制器：15 s 超时、MAC 去重、按 RSSI 倒序、客户端白名单过滤。
 * 只做发现；连接由 [com.iwalk.companion.ui.device.DeviceSessionController] 负责。
 */
class ScanController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val scanner: BleScanner = BleScanner(context),
    private val timeoutMillis: Long = 15_000,
) {
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val found = LinkedHashMap<String, ScannedDevice>()
    private var timeoutJob: Job? = null

    fun start() {
        if (_state.value.scanning) return
        if (!BlePermissions.granted(context)) {
            _state.update { it.copy(scanning = false, finished = true, error = BlePermissions.DENIED_MESSAGE) }
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.update { it.copy(scanning = false, finished = true, error = BlePermissions.DENIED_MESSAGE) }
            return
        }

        found.clear()
        _state.value = _state.value.copy(
            devices = emptyList(),
            scanning = true,
            finished = false,
            error = null,
        )
        val started = scanner.start(
            onResult = ::onResult,
            onError = { message ->
                timeoutJob?.cancel()
                timeoutJob = null
                _state.update { it.copy(scanning = false, finished = true, error = message) }
            },
        )
        if (!started) {
            _state.update {
                it.copy(scanning = false, finished = true, error = BlePermissions.DENIED_MESSAGE)
            }
            return
        }
        timeoutJob = scope.launch {
            delay(timeoutMillis)
            finish()
        }
    }

    fun stop() {
        timeoutJob?.cancel()
        timeoutJob = null
        scanner.stop()
        _state.update { it.copy(scanning = false, finished = true) }
    }

    fun setOnlyWhitelisted(value: Boolean) {
        _state.update { it.copy(onlyWhitelisted = value) }
    }

    fun close() {
        stop()
    }

    private fun finish() {
        timeoutJob = null
        scanner.stop()
        _state.update { it.copy(scanning = false, finished = true) }
    }

    private fun onResult(result: ScanResult) {
        if (!_state.value.scanning || !BlePermissions.granted(context)) return
        val record = result.scanRecord
        val name = record?.deviceName?.takeIf { it.isNotBlank() }
            ?: try { result.device.name.orEmpty() } catch (_: SecurityException) { "" }
        val whitelisted = isSupportedDeviceName(name)
        val serial = deviceSerialFromName(name)
        val services = record?.serviceUuids?.map { it.toString() }.orEmpty()
        val device = ScannedDevice(
            address = result.device.address,
            name = name,
            rssi = result.rssi,
            whitelisted = whitelisted,
            serial = serial,
            advertisedServices = services,
        )
        val previous = found[device.address]
        // 同一 MAC 以更强 RSSI / 更完整名称覆盖；保持按 RSSI 倒序。
        if (previous == null || device.rssi >= previous.rssi || (previous.name.isEmpty() && name.isNotEmpty())) {
            found[device.address] = device
        }
        val sorted = found.values.sortedByDescending { it.rssi }
        _state.update { it.copy(devices = sorted) }
    }
}
