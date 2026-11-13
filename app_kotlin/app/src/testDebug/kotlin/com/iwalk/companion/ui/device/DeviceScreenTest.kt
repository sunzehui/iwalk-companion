package com.iwalk.companion.ui.device

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.iwalk.companion.ui.IwTheme
import com.iwalk.core.decode.FieldValue
import com.iwalk.core.decode.TelemetrySnapshot
import com.iwalk.core.model.CalibrationProfile
import com.iwalk.core.model.CalibrationSet
import com.iwalk.core.model.FieldMapping
import com.iwalk.core.model.ScalarType
import com.iwalk.core.model.TelemetryField
import com.iwalk.core.session.TransportState
import com.iwalk.core.store.CalibrationStore
import com.iwalk.core.store.InMemoryKeyValueStore
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设备页正文的 JVM 侧覆盖（Robolectric + Compose，按约定不跑真机 instrumentation）。
 *
 * 用户正式版（0.4.0）起：标定内置（BuiltinCalibration），调试信息（原始码 chips、
 * 帧数、链路详情、来源面板）不再外显——本文件同时把这些「不可再现」钉进不变式。
 */
@RunWith(RobolectricTestRunner::class)
// 钉 sdk=34：targetSdk 36 的 Robolectric 沙箱要求 Java 21，测试 JVM 是 JDK 17
@Config(sdk = [34], qualifiers = "w400dp-h1200dp-xhdpi")
class DeviceScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var controller: DeviceSessionController? = null

    @After
    fun tearDown() {
        controller?.close()
        controller = null
    }

    private fun render(deviceController: DeviceSessionController) {
        controller = deviceController
        composeRule.setContent {
            IwTheme {
                val state by deviceController.ui.collectAsState()
                DeviceScreen(
                    state = state,
                    onStartDemo = deviceController::startDemo,
                    onStopSession = deviceController::stopSession,
                )
            }
        }
    }

    @Test
    fun `分支A 无会话时显示连接引导与演示入口`() {
        render(DeviceSessionController())

        composeRule.onNodeWithText(TITLE_CONNECT).assertIsDisplayed()
        composeRule.onNodeWithText(SUBTITLE_SCAN).assertIsDisplayed()
        composeRule.onNodeWithText(ACTION_DEMO).assertIsDisplayed()
        composeRule.onNodeWithText(SAFETY_NOTE).assertExists()
        // 示意图占位带「非实拍」标注（笔记 §2.2 第 2 项）
        composeRule.onNodeWithText(VEHICLE_BADGE).assertIsDisplayed()
        // 用户版：不再有界面预览与导入标定入口（标定已内置）
        composeRule.onNodeWithText(ACTION_PREVIEW).assertDoesNotExist()
        composeRule.onNodeWithText(ACTION_IMPORT_CALIBRATION).assertDoesNotExist()
        // 未起会话不出现任何读数区
        composeRule.onNodeWithText(LABEL_BATTERY).assertDoesNotExist()
        composeRule.onNodeWithText(LABEL_RANGE).assertDoesNotExist()
    }

    @Test
    fun `分支A 扫描入口在没有扫描屏时保持禁用`() {
        render(DeviceSessionController())

        // 扫描屏由工单 03 提供：控制器没接线时按钮可见但不可点，不产生任何链路动作
        composeRule.onNodeWithText(ACTION_SCAN).assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun `演示会话显示电量温度预计里程并带演示标识`() {
        val kv = InMemoryKeyValueStore()
        val deviceController = DeviceSessionController(
            calibrationStore = CalibrationStore(kv),
            demoPeriodMillis = 100,
        )
        render(deviceController)

        composeRule.onNodeWithText(ACTION_DEMO).performClick()
        composeRule.waitUntil(15_000) {
            deviceController.ui.value.snapshot.framesDecoded > 0 &&
                composeRule.onAllNodesWithText(BADGE_DEMO).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText(BADGE_DEMO).assertIsDisplayed()
        composeRule.onNodeWithText(LABEL_BATTERY).assertIsDisplayed()
        composeRule.onNodeWithText(LABEL_RANGE).assertIsDisplayed()
        composeRule.onNodeWithText(LABEL_TEMPERATURE).assertIsDisplayed()
        composeRule.onNodeWithText(ACTION_STOP_DEMO).assertIsDisplayed()
        // 用户版：调试信息（原始码 chips）不再外显
        composeRule.onNodeWithText(RAW_WORK_MODE_PREFIX, substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(RAW_WARNING_PREFIX, substring = true).assertDoesNotExist()
        // 演示标定覆盖全部演示字段：界面不应出现任何「—」猜测位
        composeRule.onAllNodesWithText(EM_DASH).assertCountEquals(0)

        // 结束演示：回到分支 A，演示标定从未写入用户存储（验收第 4 条）
        composeRule.onNodeWithText(ACTION_STOP_DEMO).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(TITLE_CONNECT).assertIsDisplayed()
        assertEquals(emptySet<String>(), kv.keys())
    }

    @Test
    fun `未标定电量时显示破折号且不显示帧数调试信息`() {
        // 用户只标了预计里程（0x2a off2，E71）：电量与温度都缺映射 → 两处「—」
        val profile = CalibrationProfile(
            name = "用户导入 (未验证)",
            cmd = 0x06,
            a1 = 0x01,
            a2 = 0x07,
            fields = listOf(FieldMapping(TelemetryField.estimatedRange, 0, ScalarType.u8)),
        )
        val now = Instant.now()
        val snapshot = TelemetrySnapshot(
            updatedAt = now,
            framesDecoded = 4,
            values = mapOf(
                TelemetryField.estimatedRange to FieldValue(
                    field = TelemetryField.estimatedRange,
                    raw = 13.0,
                    value = 13.0,
                    at = now,
                    profile = profile,
                    mapping = profile.fields.first(),
                ),
            ),
        )
        val state = DeviceUiState(
            active = true,
            isDemo = false,
            transportState = TransportState.connected,
            snapshot = snapshot,
            calibration = CalibrationSet(listOf(profile)),
            startedAtMillis = now.toEpochMilli() - 5_000,
        )
        composeRule.setContent {
            IwTheme {
                DeviceScreen(state = state, onStartDemo = {}, onStopSession = {})
            }
        }

        composeRule.onNodeWithText(LABEL_BATTERY).assertIsDisplayed()
        composeRule.onNodeWithText(LABEL_RANGE).assertIsDisplayed()
        // 电量环中央与温度格各一个「—」，绝不显示猜测值
        composeRule.onAllNodesWithText(EM_DASH).assertCountEquals(2)
        // 用户版：帧数统计不再外显
        composeRule.onNodeWithText("帧", substring = true).assertDoesNotExist()
    }

    @Test
    fun `真车已连接不再显示链路详情与GATT调试信息`() {
        val state = DeviceUiState(
            active = true,
            isDemo = false,
            transportState = TransportState.connected,
            startedAtMillis = Instant.now().toEpochMilli() - 5_000,
            linkFacts = com.iwalk.core.session.LinkFacts(
                advertisementName = "MiniRobot:IWK1779887",
                transportGroup = "g1NordicUart",
                serviceUuid = "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
                writeUuid = "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
                notifyUuid = "6e400003-b5a3-f393-e0a9-e50e24dcca9e",
                lockControl = "fixed-g1-smalltype-experimental",
                writeCharacteristicAvailable = true,
                gattTree = listOf("D AA:BB:CC:DD:EE:FF mtu~23", "S 6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
                standardReads = mapOf("00002a00-0000-1000-8000-00805f9b34fb" to "4d696e69"),
            ),
        )
        composeRule.setContent {
            IwTheme {
                DeviceScreen(state = state, onStartDemo = {}, onStopSession = {})
            }
        }

        // 设备名照常外显（会话行），但 UUID/GATT 树等链路详情不再出现
        composeRule.onNodeWithText("MiniRobot:IWK1779887", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("6e400001-b5a3-f393-e0a9-e50e24dcca9e").assertDoesNotExist()
        composeRule.onNodeWithText("AA:BB:CC:DD:EE:FF", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(ACTION_DISCONNECT).assertIsDisplayed()
    }

    @Test
    fun `真车有帧断链时退出前先确认`() {
        var stopped = 0
        val state = DeviceUiState(
            active = true,
            isDemo = false,
            transportState = TransportState.disconnected,
            startedAtMillis = Instant.now().toEpochMilli() - 60_000,
            snapshot = TelemetrySnapshot(
                updatedAt = Instant.now(),
                framesDecoded = 7,
                values = emptyMap(),
            ),
        )
        composeRule.setContent {
            IwTheme {
                DeviceScreen(state = state, onStartDemo = {}, onStopSession = { stopped++ })
            }
        }

        composeRule.onNodeWithText(LINK_ISSUE_TITLE_DISCONNECTED).assertIsDisplayed()
        composeRule.onNodeWithText(ACTION_EXIT_SESSION).assertIsDisplayed()
        composeRule.onNodeWithText(ACTION_EXIT_SESSION).performClick()

        // 链路已断：确认框用退出语义，不能再说「断开当前会话」
        composeRule.onNodeWithText(DIALOG_EXIT_TITLE).assertIsDisplayed()
        composeRule.onAllNodesWithText(DIALOG_STOP_TITLE).assertCountEquals(0)
        composeRule.onNodeWithText(DIALOG_STAY).performClick()
        composeRule.onAllNodesWithText(DIALOG_EXIT_TITLE).assertCountEquals(0)
        assertEquals(0, stopped)

        composeRule.onNodeWithText(ACTION_EXIT_SESSION).performClick()
        composeRule.onNodeWithText(DIALOG_EXIT_CONFIRM).performClick()
        composeRule.waitForIdle()
        assertEquals(1, stopped)
    }

    @Test
    fun `真车已连接断开确认框仍为断开语义`() {
        var stopped = 0
        val state = DeviceUiState(
            active = true,
            isDemo = false,
            transportState = TransportState.connected,
            startedAtMillis = Instant.now().toEpochMilli() - 60_000,
            snapshot = TelemetrySnapshot(updatedAt = Instant.now(), framesDecoded = 7, values = emptyMap()),
        )
        composeRule.setContent {
            IwTheme {
                DeviceScreen(state = state, onStartDemo = {}, onStopSession = { stopped++ })
            }
        }

        composeRule.onNodeWithText(ACTION_DISCONNECT).performClick()
        composeRule.onNodeWithText(DIALOG_STOP_TITLE).assertIsDisplayed()
        composeRule.onAllNodesWithText(DIALOG_EXIT_TITLE).assertCountEquals(0)
        composeRule.onNodeWithText(DIALOG_STOP_CONFIRM).performClick()
        composeRule.waitForIdle()
        assertEquals(1, stopped)
    }

    @Test
    fun `演示会话不出现断开确认`() {
        var stopped = 0
        val state = DeviceUiState(
            active = true,
            isDemo = true,
            transportState = TransportState.connected,
            startedAtMillis = Instant.now().toEpochMilli() - 5_000,
            snapshot = TelemetrySnapshot(updatedAt = Instant.now(), framesDecoded = 3, values = emptyMap()),
        )
        composeRule.setContent {
            IwTheme {
                DeviceScreen(state = state, onStartDemo = {}, onStopSession = { stopped++ })
            }
        }

        composeRule.onNodeWithText(ACTION_STOP_DEMO).performClick()
        composeRule.waitForIdle()
        assertEquals(1, stopped)
        composeRule.onAllNodesWithText(DIALOG_STOP_TITLE).assertCountEquals(0)
    }

    @Test
    fun `内置标定随包生效`() {
        val controller2 = DeviceSessionController()
        try {
            val calib = controller2.userCalibration()
            // E64：0x14 off28 电量；E71：0x2a off2 预计里程——两条内置 profile
            assertEquals(2, calib.profiles.size)
            assertTrue(calib.hasField(TelemetryField.batteryPercent))
            assertTrue(calib.hasField(TelemetryField.estimatedRange))
            // 未被证据支持的字段（速度/温度）不得出现在内置标定里
            assertFalse(calib.hasField(TelemetryField.speed))
            assertFalse(calib.hasField(TelemetryField.temperature))
        } finally {
            controller2.close()
        }
    }

    private companion object {
        const val TITLE_CONNECT = "连接你的平衡车"
        const val SUBTITLE_SCAN =
            "仅扫描 Mini / Plus / M0… 白名单设备\n默认只读；锁车控制需手动开启并确认风险"
        const val ACTION_SCAN = "扫描并连接"
        const val ACTION_DEMO = "使用演示数据（不连接真车）"
        const val ACTION_PREVIEW = "查看界面预览（静态示例）"
        const val ACTION_IMPORT_CALIBRATION = "导入标定以显示读数"
        const val VEHICLE_BADGE = "示意图 · 非实拍"
        const val SAFETY_NOTE =
            "本应用只读取车辆状态（电量、预计里程、温度），不发送任何控制指令。可选的锁车/解锁为实验功能，需在设置中手动开启；开启后仅发送两条固定的锁车/解锁指令，且车辆仍在移动时会被阻止。"
        const val LABEL_RANGE = "预计里程"
        const val LABEL_BATTERY = "电量"
        const val LABEL_TEMPERATURE = "温度"
        const val BADGE_DEMO = "演示数据"
        const val RAW_WORK_MODE_PREFIX = "工作模式 raw "
        const val RAW_WARNING_PREFIX = "警告码 raw "
        const val ACTION_STOP_DEMO = "结束演示"
    }
}
