package com.iwalk.companion.ui.device

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.iwalk.core.model.TelemetryField
import com.iwalk.core.session.TransportState
import com.iwalk.core.session.lockBlockedReason
import kotlinx.coroutines.launch

@Composable
fun LockSlider(enabled: Boolean, onSend: (Boolean) -> Unit) {
    var position by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(enabled) { if (!enabled) position = 0f }
    Column {
        Text("← 解锁                  滑动至边缘                  关锁 →", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = position, onValueChange = { position = it }, valueRange = -1f..1f,
            onValueChangeFinished = {
                val released = position
                position = 0f
                if (enabled && kotlin.math.abs(released) >= 0.85f) onSend(released > 0)
            },
            enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("lock-slider"),
        )
    }
}

@Composable
fun LockControlPanel(controller: DeviceSessionController) {
    val state by controller.ui.collectAsState()
    val settings by controller.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var result by remember(state.sessionId) { mutableStateOf<String?>(null) }
    val blocked = lockBlockedReason(settings.lockControlEnabled, state.isDemo,
        state.transportState == TransportState.connected, state.lockAvailable,
        state.snapshot[TelemetryField.speed])
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (state.isDemo) "模拟锁车操作" else "实验性锁车", style = MaterialTheme.typography.titleMedium)
        Text(if (state.isDemo) "仅模拟发送固定帧，不操作真实车辆。" else "每次滑动只发送一次，车端状态始终未确认。", style = MaterialTheme.typography.bodySmall)
        LockSlider(enabled = blocked == null && !state.lockBusy, onSend = { lock -> scope.launch { result = controller.sendLock(lock) } })
        Text(if (state.lockBusy) "指令发送中…" else blocked ?: result ?: "滑动后手柄自动回中", style = MaterialTheme.typography.bodySmall)
    }
}
