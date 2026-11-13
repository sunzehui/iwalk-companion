package com.iwalk.companion.ui.secondary

import androidx.compose.runtime.Composable
import com.iwalk.companion.ui.preview.StaticPreviewScreen
import com.iwalk.companion.ui.reference.ReferenceScreen
import com.iwalk.companion.ui.safety.SafetyScreen

/**
 * 工单 08 的三个纯静态二级页路由。本页签会话无关：不传任何状态对象，
 * 参考页在无会话时以默认 [com.iwalk.companion.ui.reference.ReferenceRawValues] 渲染空值。
 *
 * 后续工单（数据与诊断/标定导入）在 [SettingsTabBody] 增加入口时同步扩展该枚举；
 * 迁移到 navigation-compose 时这里是被替换的唯一分发点。
 */
enum class SecondaryPage { STATIC_PREVIEW, REFERENCE, SAFETY, DIAGNOSTICS }

/** 二级页整屏渲染（对齐 Dart `Navigator.push` 覆盖底部页签的行为）。 */
@Composable
fun SecondaryPageScreen(page: SecondaryPage, onBack: () -> Unit, state: com.iwalk.companion.ui.device.DeviceUiState = com.iwalk.companion.ui.device.DeviceUiState()) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    when (page) {
        SecondaryPage.STATIC_PREVIEW -> StaticPreviewScreen(onBack = onBack)
        SecondaryPage.REFERENCE -> ReferenceScreen(onBack = onBack, rawValues = com.iwalk.companion.ui.reference.ReferenceRawValues(
            hasSession = state.active, isDemo = state.isDemo,
            workModeRaw = state.snapshot.values[com.iwalk.core.model.TelemetryField.workMode]?.raw?.toLong(),
            warningCodeRaw = state.snapshot.values[com.iwalk.core.model.TelemetryField.warningCode]?.raw?.toLong(),
            errorCodeRaw = state.snapshot.values[com.iwalk.core.model.TelemetryField.errorCode]?.raw?.toLong(),
        ))
        SecondaryPage.DIAGNOSTICS -> com.iwalk.companion.ui.settings.DiagnosticsScreen(state, onBack)
        SecondaryPage.SAFETY -> SafetyScreen(onBack = onBack)
    }
}
