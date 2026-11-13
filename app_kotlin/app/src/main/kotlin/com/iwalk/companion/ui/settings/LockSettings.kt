package com.iwalk.companion.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.iwalk.companion.ui.device.DeviceSessionController

@Composable
fun LockSettings(controller: DeviceSessionController) {
    val settings by controller.settings.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("实验性锁车控制", modifier = Modifier.weight(1f))
            Switch(
                checked = settings.lockControlEnabled,
                onCheckedChange = { if (it) confirm = true else controller.setLockControlEnabled(false) },
                modifier = Modifier.testTag("lock-switch"),
            )
        }
        Text("仅支持 G1 固定帧；发送成功不代表车辆执行。", style = MaterialTheme.typography.bodySmall)
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("开启实验性锁车控制？") },
        text = { Text("CMD 0x0A 的关锁/解锁帧仅经静态推导，2026-11-12 真车实测有效，但链路无车端回执、写入成功不代表车辆执行。每次滑动只发送一次，不重试、不回读。速度必须有读数且不超过 1.0 km/h；速度未标定会直接阻止，因为无法证明车辆静止。请确认车辆停稳，后果由操作者判断。") },
        confirmButton = { TextButton(onClick = { controller.setLockControlEnabled(true); confirm = false }) { Text("我已了解，开启") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("保持关闭") } },
    )
}
