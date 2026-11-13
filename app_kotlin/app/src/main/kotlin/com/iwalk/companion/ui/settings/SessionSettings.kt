package com.iwalk.companion.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import com.iwalk.companion.ui.device.DeviceSessionController
import com.iwalk.companion.ui.secondary.IwSectionHeader

/**
 * 会话相关设置（用户正式版 0.4.0）：只保留锁车控制与关于边界。
 * 数据与诊断、标定与证据（标定已随包内置）、会话导出等调试入口已移除。
 */
@Composable
fun SessionSettings(controller: DeviceSessionController) {
    val settings by controller.settings.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LockSettings(controller)
        IwSectionHeader("关于与边界")
        Text(
            "iWalk 伴侣 · ${com.iwalk.companion.BuildConfig.VERSION_NAME}\n只读车辆状态（电量、预计里程、温度）；标定已内置，读数仅在采集验证过的字段上显示，不猜测数值。",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "传输权限：仅蓝牙扫描/连接（BLUETOOTH_SCAN、BLUETOOTH_CONNECT；Android 11 及以下为蓝牙与位置权限）；不申请 INTERNET，数据只落本机。",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "锁车状态：${if (settings.lockControlEnabled) "已开启（实验性）" else "默认关闭"}；写入成功也只表示数据已交给链路，车端状态未确认。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
