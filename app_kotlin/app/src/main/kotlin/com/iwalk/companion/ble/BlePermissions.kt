package com.iwalk.companion.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android 蓝牙运行时权限（notes/ui-device-scan.md §3.4）。
 *
 * - Android 12+：`BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`（扫描声明 neverForLocation）。
 * - Android 11 及以下：`ACCESS_FINE_LOCATION`；`BLUETOOTH`/`BLUETOOTH_ADMIN` 是安装时权限。
 *
 * 清单在工单 01 已声明；本类只负责在扫描/连接前检查与提示，不申请额外权限。
 */
object BlePermissions {

    /** 当前系统版本下需要运行时授予的权限。 */
    fun required(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** 尚未授予的权限；空列表表示可以扫描/连接。 */
    fun missing(context: Context): List<String> = required().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    fun granted(context: Context): Boolean = missing(context).isEmpty()

    /** 权限不足时与 Dart 逐字一致的提示（§3.4）。 */
    const val DENIED_MESSAGE = "蓝牙未开启或无权限（需“附近的设备”权限）"
}
