package com.iwalk.companion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context

/**
 * 原生 BLE 扫描（notes/ui-device-scan.md §3）：无系统过滤器、低延迟、客户端按
 * 33 个名称前缀判定白名单；15 s 超时由 [ScanController] 负责。
 *
 * 只做扫描，不连接、不写入。
 */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {

    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private var callback: ScanCallback? = null

    val isScanning: Boolean get() = callback != null

    /** 开始扫描；返回 false 表示蓝牙关闭/不可用。 */
    fun start(onResult: (ScanResult) -> Unit, onError: (String) -> Unit): Boolean {
        if (callback != null) return true
        val adapter = manager?.adapter ?: return false
        if (!adapter.isEnabled) return false
        val scanner = adapter.bluetoothLeScanner ?: return false
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(onResult)
            }

            override fun onScanFailed(errorCode: Int) {
                callback = null
                onError("扫描失败：errorCode=$errorCode")
            }
        }
        callback = cb
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        return try {
            scanner.startScan(null, settings, cb)
            true
        } catch (e: Exception) {
            callback = null
            onError("扫描失败：$e")
            false
        }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        try {
            manager?.adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {
            // 蓝牙已关闭时 stopScan 可能抛；扫描回调已解除，忽略。
        }
    }
}
