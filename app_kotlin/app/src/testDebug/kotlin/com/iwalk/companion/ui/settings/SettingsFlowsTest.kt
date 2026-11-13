package com.iwalk.companion.ui.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.iwalk.companion.ui.IwTheme
import com.iwalk.companion.ui.device.DeviceSessionController
import com.iwalk.companion.ui.device.DeviceUiState
import com.iwalk.core.decode.FieldValue
import com.iwalk.core.decode.TelemetrySnapshot
import com.iwalk.core.demo.DemoProfile
import com.iwalk.core.model.TelemetryField
import com.iwalk.core.session.TransportState
import java.time.Duration
import java.time.Instant
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Default display configuration avoids a Robolectric dialog text layout loop.
@Config(sdk = [34])
class SettingsFlowsTest {
    @get:Rule val compose = createComposeRule()
    private val controller = DeviceSessionController()
    @After fun cleanup() { controller.close() }

    @Test fun `invalid calibration remains editable and reports failure`() {
        compose.setContent { IwTheme { CalibrationImportDialog(controller, {}) } }
        compose.onNodeWithTag("calibration-json").performTextInput("{bad}")
        compose.onNodeWithText("导入并应用").performClick()
        compose.onNodeWithText("导入失败：", substring = true).assertExists()
        assertFalse(controller.ui.value.hasUserCalibration)
    }

    @Test fun `lock switch requires confirmation and cancel keeps disabled`() {
        compose.setContent { IwTheme { LockSettings(controller) } }
        compose.onNodeWithTag("lock-switch").assertIsOff().performClick()
        compose.onNodeWithText("开启实验性锁车控制？").assertExists()
        compose.onNodeWithText("保持关闭").performClick()
        compose.onNodeWithTag("lock-switch").assertIsOff().performClick()
        compose.onNodeWithText("我已了解，开启").performClick()
        compose.onNodeWithTag("lock-switch").assertIsOn()
        assertTrue(controller.settings.value.lockControlEnabled)
    }

    @Test fun `diagnostics lists all thirteen unknown fields without inventing values`() {
        compose.setContent { IwTheme { DiagnosticsScreen(DeviceUiState(), {}) } }
        compose.onAllNodesWithText("未标定 · 不猜测数值").assertCountEquals(13)
        compose.onNodeWithText("速度").assertExists()
        compose.onNodeWithText("预计里程").assertExists()
        compose.onNodeWithText("错误码").assertExists()
        compose.onNodeWithText("出站指令").assertExists()
    }

    @Test fun `diagnostics names waiting stale and uncalibrated states in words`() {
        val profile = DemoProfile.profile()
        val mapping = profile.fields.first { it.field == TelemetryField.speed }
        val staleAt = Instant.now() - Duration.ofSeconds(10)
        val stale = FieldValue(TelemetryField.speed, 641.0, 6.41, staleAt, profile, mapping)
        val state = DeviceUiState(
            active = true,
            isDemo = true,
            transportState = TransportState.connected,
            calibration = DemoProfile.set(),
            snapshot = TelemetrySnapshot(updatedAt = staleAt, values = mapOf(TelemetryField.speed to stale)),
        )
        compose.setContent { IwTheme { DiagnosticsScreen(state, {}) } }
        compose.onAllNodesWithText("数据过期 · 保留上次读数").assertCountEquals(1)
        compose.onAllNodesWithText("等待匹配帧").assertCountEquals(5)
        compose.onAllNodesWithText("未标定 · 不猜测数值").assertCountEquals(7)
    }

    @Test fun `about section states version permissions and lock state`() {
        compose.setContent { IwTheme { SessionSettings(controller) } }
        compose.onNodeWithText("iWalk 伴侣 ·", substring = true).assertExists()
        compose.onNodeWithText("传输权限：仅蓝牙扫描/连接", substring = true).assertExists()
        compose.onNodeWithText("锁车状态：默认关闭", substring = true).assertExists()
        compose.runOnIdle { controller.setLockControlEnabled(true) }
        compose.onNodeWithText("锁车状态：已开启（实验性）", substring = true).assertExists()
        // 用户版：调试与待采集信息不再外显
        compose.onNodeWithText("待采集项", substring = true).assertDoesNotExist()
    }
}
