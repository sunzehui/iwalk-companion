package com.iwalk.companion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import com.iwalk.core.session.BluetoothTransport

/**
 * 把一条扫描结果变成真实链路。抽成接口是为了让 JVM 测试不依赖 Android 的
 * `BluetoothDevice`；生产实现只是按地址取回设备并构造 [BleTransport]。
 */
fun interface RealLinkFactory {
    fun create(device: ScannedDevice): BluetoothTransport
}

@SuppressLint("MissingPermission")
class AndroidRealLinkFactory(private val context: Context) : RealLinkFactory {
    override fun create(device: ScannedDevice): BluetoothTransport {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: error("蓝牙不可用")
        val remote = adapter.getRemoteDevice(device.address)
        return BleTransport(
            context = context,
            device = remote,
            advertisementName = device.name,
            id = device.address,
        )
    }
}
