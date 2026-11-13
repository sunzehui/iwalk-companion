package com.iwalk.companion.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iwalk.companion.ui.device.*
import com.iwalk.companion.ui.secondary.*
import com.iwalk.core.model.TelemetryField
import com.iwalk.core.session.TransportState
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

@Composable
fun DiagnosticsScreen(state: DeviceUiState, onBack: () -> Unit) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = Instant.now() } }
    IwSecondaryPage(title = "数据与诊断", onBack = onBack) {
        IwSectionHeader("车辆基础信息")
        IwLabelValueRow("设备", if (state.isDemo) "演示模式（模拟车）" else state.linkFacts?.advertisementName?.ifBlank { null } ?: "未获取")
        IwLabelValueRow("会话", state.sessionId ?: "未获取")
        IwLabelValueRow("链路状态", state.transportState.name)
        IwLabelValueRow("传输组", state.linkFacts?.transportGroup ?: if (state.isDemo) "模拟传输" else "未获取")
        IwBodyNote("序列号、型号与固件版本仅以已获取的证据为准，未推导未知字段。")
        IwGap(20.dp)
        IwSectionHeader("实时状态")
        TelemetryField.entries.filter { it != TelemetryField.unknown }.forEach { field ->
            val value = state.snapshot.values[field]
            val calibrated = state.calibration.profiles.any { p -> p.fields.any { it.field == field } }
            val status = when {
                !calibrated -> "未标定 · 不猜测数值"
                value == null -> "等待匹配帧"
                state.transportState != TransportState.connected || Duration.between(value.at, now).seconds > 3 -> "数据过期 · 保留上次读数"
                state.isDemo -> "演示数据 · 非真车"
                else -> "已标定 · 已收到数据"
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                IwLabelValueRow(labelOf(field), "${formatFieldValue(value?.value, digitsOf(field))} ${unitOf(field)}".trim())
                Text(status, style = MaterialTheme.typography.bodySmall)
                value?.let {
                    Text("原始值 ${it.raw} · 更新 ${formatAge(it.at, now)}\n${it.profile.name} · off ${it.mapping.offset} · ${it.mapping.type.name}", style = MaterialTheme.typography.bodySmall)
                }
            }
            IwCardDivider()
        }
        IwGap(20.dp)
        IwSectionHeader("解析诊断")
        IwLabelValueRow("已解析帧", "${state.snapshot.framesDecoded}")
        IwLabelValueRow("未标定帧", "${state.snapshot.uncalibratedFrames}")
        IwLabelValueRow("未解码字节", "${state.snapshot.bytesUndecoded}")
        IwBodyNote("标准特征读取：${state.linkFacts?.standardReads?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }?.ifBlank { null } ?: "未获取"}")
        IwGap(20.dp)
        IwSectionHeader("出站指令")
        IwBodyNote("G1 连接后按 110 / 1010 ms 调度两条固定遥测查询。下方仅记录锁帧尝试；车端状态未确认 · 无回读。")
        if (state.lockCommands.isEmpty()) IwBodyNote("本次会话尚无锁帧尝试")
        state.lockCommands.asReversed().forEach {
            IwGap(10.dp)
            Text("${it.actionLabel} · ${if (it.simulated) "模拟" else "真实链路"} · ${if (it.ok) "已发送" else "未成功"}")
            IwBodyNote("${it.at}\n${it.hex}\n${it.error ?: "车端状态未确认 · 无回读"}")
        }
    }
}
