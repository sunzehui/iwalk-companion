package com.iwalk.companion.ui.scan

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.iwalk.companion.ble.ScanState
import com.iwalk.companion.ble.ScannedDevice
import com.iwalk.companion.ui.IwTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 扫描页正文的 JVM 侧覆盖（notes/ui-device-scan.md §3）。真机扫描链路不在本测试内：
 * `ScanController`/`BleScanner` 依赖系统蓝牙，由真机人工验收补足。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h1200dp-xhdpi")
class ScanScreenContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val mini = ScannedDevice(
        address = "AA:BB:CC:DD:EE:01",
        name = "MiniRobot:IWK1779887",
        rssi = -58,
        whitelisted = true,
        serial = "1779887",
        advertisedServices = listOf("00001800-0000-1000-8000-00805f9b34fb"),
    )
    private val stranger = ScannedDevice(
        address = "AA:BB:CC:DD:EE:02",
        name = "UnknownScooter",
        rssi = -40,
        whitelisted = false,
        serial = null,
        advertisedServices = emptyList(),
    )

    private fun render(
        state: ScanState,
        connectingAddress: String? = null,
        connectError: String? = null,
        onToggle: (Boolean) -> Unit = {},
        onConnect: (ScannedDevice) -> Unit = {},
    ) {
        composeRule.setContent {
            IwTheme {
                ScanScreenContent(
                    state = state,
                    connectingAddress = connectingAddress,
                    connectError = connectError,
                    onClose = {},
                    onStartScan = {},
                    onStopScan = {},
                    onToggleWhitelist = onToggle,
                    onConnect = onConnect,
                    onShowWhitelistHelp = {},
                )
            }
        }
    }

    @Test
    fun `就绪态显示空态与准备扫描`() {
        render(ScanState())

        composeRule.onNodeWithText(SCAN_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(SCAN_STATUS_READY).assertIsDisplayed()
        // 空态标题与状态行共用「没找到 iWalk 设备」字样时可能各出现一次
        composeRule.onAllNodesWithText(SCAN_EMPTY_TITLE_IDLE)[0].assertIsDisplayed()
        composeRule.onAllNodesWithText(SCAN_RESTART)[0].assertIsDisplayed()
        composeRule.onNodeWithText(SCAN_FOOTER).assertIsDisplayed()
    }

    @Test
    fun `白名单开关过滤结果且关闭时给出提示`() {
        render(ScanState(devices = listOf(stranger, mini), onlyWhitelisted = true))

        composeRule.onNodeWithText("MiniRobot").assertIsDisplayed()
        composeRule.onAllNodesWithText("UnknownScooter").assertCountEquals(0)
        composeRule.onNodeWithText("找到 1 台设备 · 按信号强度排序").assertIsDisplayed()
    }

    @Test
    fun `关闭白名单后显示全部广播`() {
        render(ScanState(devices = listOf(stranger, mini), onlyWhitelisted = false))

        composeRule.onNodeWithText("UnknownScooter").assertIsDisplayed()
        composeRule.onNodeWithText("找到 2 台设备 · 按信号强度排序").assertIsDisplayed()
        // 有结果时不渲染空态，警示只出现在空态里（对齐 Dart `_EmptyScanView`）
        composeRule.onAllNodesWithText(SCAN_ALL_BROADCASTS_NOTE).assertCountEquals(0)
    }

    @Test
    fun `关闭白名单且无结果时空态警示出现`() {
        render(ScanState(devices = emptyList(), onlyWhitelisted = false, finished = true))

        composeRule.onNodeWithText(SCAN_ALL_BROADCASTS_NOTE).assertIsDisplayed()
        composeRule.onNodeWithText(SCAN_STATUS_EMPTY_ALL).assertIsDisplayed()
    }

    @Test
    fun `设备卡技术信息含掩码MAC序列号与广播服务`() {
        render(ScanState(devices = listOf(mini)))

        composeRule.onNodeWithText(SCAN_TECH).performClick()
        composeRule.onNodeWithText("AA:BB:CC:…:EE:01").assertIsDisplayed()
        composeRule.onNodeWithText("1779887").assertIsDisplayed()
        composeRule.onNodeWithText("00001800").assertIsDisplayed()
    }

    @Test
    fun `连接期间展示忙碌提示并禁用重扫`() {
        render(
            state = ScanState(devices = listOf(mini)),
            connectingAddress = mini.address,
        )

        composeRule.onNodeWithText(SCAN_CONNECTING).assertIsDisplayed()
        composeRule.onNodeWithText(SCAN_RESTART).assertIsNotEnabled()
    }

    @Test
    fun `连接失败原因整卡展示`() {
        render(
            state = ScanState(devices = listOf(mini)),
            connectError = "连接失败：未匹配到已知传输组（G1–G5），仅展示服务树",
        )

        composeRule
            .onNodeWithText("连接失败：未匹配到已知传输组（G1–G5），仅展示服务树")
            .assertIsDisplayed()
    }

    @Test
    fun `扫描错误与空结果状态走逐字文案`() {
        render(ScanState(scanning = false, finished = true, error = "蓝牙未开启或无权限（需“附近的设备”权限）"))

        composeRule.onNodeWithText("蓝牙未开启或无权限（需“附近的设备”权限）").assertIsDisplayed()
        composeRule.onAllNodesWithText(SCAN_STATUS_EMPTY_WHITELIST)[0].assertIsDisplayed()
    }

    @Test
    fun `白名单 chip 触发回调`() {
        var toggled: Boolean? = null
        render(
            state = ScanState(devices = listOf(mini), onlyWhitelisted = true),
            onToggle = { toggled = it },
        )
        composeRule.onNodeWithText(SCAN_FILTER).performClick()
        composeRule.runOnIdle { assertEquals(false, toggled) }
    }
}
