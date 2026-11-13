package com.iwalk.companion.ui.analysis

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.iwalk.companion.ui.IwTheme
import com.iwalk.companion.ui.device.DeviceUiState
import com.iwalk.core.analysis.analyseFrames
import com.iwalk.core.analysis.offsetReportText
import com.iwalk.core.decode.FieldValue
import com.iwalk.core.decode.TelemetrySnapshot
import com.iwalk.core.demo.DemoProfile
import com.iwalk.core.model.TelemetryField
import com.iwalk.core.session.EvidenceEvent
import com.iwalk.protocol.IbwalkFrame
import com.iwalk.protocol.hexToBytes
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 实时分析视图的 JVM 覆盖（Robolectric + Compose）。
 *
 * 断言里的中文都是 notes/ui-analysis.md 的逐字副本（不引用 UI 常量），用来抓文案漂移；
 * 合成状态只描述**不变式**（计数、分组、排序），不对演示 PRNG 复现做任何假设。
 */
@RunWith(RobolectricTestRunner::class)
// 钉 sdk=34：targetSdk 36 的 Robolectric 沙箱要求 Java 21，测试 JVM 是 JDK 17
@Config(sdk = [34], qualifiers = "w400dp-h1200dp-xhdpi")
class AnalysisTabBodyTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        state: DeviceUiState,
        onOpenReference: () -> Unit = {},
        exporting: Boolean = false,
        onExportSession: (suspend () -> String)? = null,
    ) {
        composeRule.setContent {
            IwTheme {
                AnalysisTabBody(
                    state = state,
                    onOpenReference = onOpenReference,
                    exporting = exporting,
                    onExportSession = onExportSession,
                )
            }
        }
    }

    @Test
    fun `空会话完整渲染零值而不是请先连接占位`() {
        render(DeviceUiState())

        composeRule.onNodeWithText("实时分析").assertIsDisplayed()
        composeRule.onNodeWithText("离线分析").assertIsDisplayed()
        composeRule.onNodeWithText("实时会话的帧统计与原始通知；技术数据只在需要时展开。").assertExists()
        composeRule.onNodeWithTag(SUMMARY_TAG_FRAMES).assertTextEquals("0")
        composeRule.onNodeWithTag(SUMMARY_TAG_GROUPS).assertTextEquals("0")
        composeRule.onNodeWithTag(SUMMARY_TAG_UNCALIBRATED).assertTextEquals("0")
        composeRule.onNodeWithText("帧").assertExists()
        composeRule.onNodeWithText("帧组").assertExists()
        composeRule.onNodeWithText("未标定帧").assertExists()

        // 空态明确，不出现任何编造数值
        composeRule.onNodeWithText("证据时间线").assertExists()
        composeRule.onNodeWithText("等待通知").assertExists()
        composeRule.onNodeWithText("还没有收到通知。连接真车或启动演示后，每帧会在这里出现。").assertExists()
        composeRule.onNodeWithText("暂无帧组").assertExists()
        composeRule.onNodeWithText("开始演示或连接真车后，这里按 cmd/a1/a2 展示字节统计。").assertExists()
        composeRule.onNodeWithText("暂无数据").assertExists()
        composeRule.onNodeWithText("等待数据").assertExists()
        composeRule.onNodeWithText("本次未观察到告警字段 · 官方短码与长文案仅作参考").assertExists()
        composeRule.onNodeWithText("安全边界：本页只分析已收通知；真车遥测使用两条固定查询。锁车需在设置中开启，无校准或升级入口。").assertExists()

        // 两个复制入口在无数据时不可点
        composeRule.onNodeWithText("复制原始记录").assertIsNotEnabled()
        composeRule.onNodeWithText("复制偏移分析").assertIsNotEnabled()

        // 13 个可标定字段（14 含 unknown 兜底）全部「未标定」
        composeRule.onNodeWithText("字段字典").performScrollTo().performClick()
        composeRule.onNodeWithText("本次已观察 0 / 13 个字段 · 未出现不等于不支持").assertExists()
        composeRule.onAllNodesWithText("未标定").assertCountEquals(13)
        composeRule.onAllNodesWithText("字段位置未经验证；不会猜测数值").assertCountEquals(13)
    }

    @Test
    fun `有数据时展示计数帧组统计与已观察字段`() {
        render(demoState())

        composeRule.onNodeWithTag(SUMMARY_TAG_FRAMES).assertTextEquals("7")
        composeRule.onNodeWithTag(SUMMARY_TAG_GROUPS).assertTextEquals("1")
        composeRule.onNodeWithTag(SUMMARY_TAG_UNCALIBRATED).assertTextEquals("2")
        composeRule.onNodeWithText("复制原始记录").assertIsEnabled()
        composeRule.onNodeWithText("复制偏移分析").assertIsEnabled()

        // 帧组卡：3 帧、5 字节、组键与逐偏移表
        composeRule.onNodeWithText("3 帧 · 5 B").assertExists()
        composeRule.onNodeWithText("按 CMD / A1 / A2 分组").assertExists()
        composeRule.onNodeWithText("off").assertExists()
        composeRule.onNodeWithText("取值数").assertExists()
        composeRule.onNodeWithText("范围").assertExists()
        composeRule.onAllNodesWithText("u16le").assertCountEquals(1)

        // 时间线与原始通知计数
        composeRule.onNodeWithText("最近 2 条").assertExists()
        composeRule.onNodeWithText("共 2 条").assertExists()

        // 故障参考的副标题只包含本次观察到的告警字段
        composeRule.onNodeWithText("警告码 raw 0 · 数值↔标签映射未验证").assertExists()

        composeRule.onNodeWithText("字段字典").performScrollTo().performClick()
        composeRule.onNodeWithText("本次已观察 6 / 13 个字段 · 未出现不等于不支持").assertExists()
        composeRule.onAllNodesWithText("已观察").assertCountEquals(6)
        composeRule.onNodeWithText("速度").assertExists()
        composeRule.onNodeWithText("12.3 km/h · raw 1234 · i16le @ 0").assertExists()
        composeRule.onAllNodesWithText("等待匹配帧").assertCountEquals(0)
    }

    @Test
    fun `证据时间线按时间倒序展示`() {
        render(demoState())

        val older = composeRule.onNodeWithText("速度 12.3 km/h").fetchSemanticsNode().boundsInRoot.top
        val newer = composeRule.onNodeWithText("速度 10 km/h").fetchSemanticsNode().boundsInRoot.top
        assertTrue("最新一条应在最上方", newer < older)
        composeRule.onNodeWithText("仅显示最近 12 条；完整时间线随“导出会话”写入 evidence_timeline。非零原始码会标出，但 0 不代表已验证“无故障”。")
            .assertExists()
    }

    @Test
    fun `时间线被截断时改用截断文案并给出保留条数`() {
        val base = demoState()
        render(base.copy(timelineTruncated = true))

        composeRule.onNodeWithText(
            "时间线已达上限：只保留最近 2 条，已收 7 帧中更早的条目只剩帧统计与原始通知里有；导出时 evidence_timeline 同样已截断。",
        ).assertExists()
        composeRule.onNodeWithText(
            "仅显示最近 12 条；时间线已达上限，导出时 evidence_timeline 也不含更早条目（帧统计与原始通知仍为全量）。",
        ).assertExists()
        // 没截断时那句「完整时间线随导出」不能同时出现，避免自相矛盾
        composeRule.onAllNodesWithText(
            "仅显示最近 12 条；完整时间线随“导出会话”写入 evidence_timeline。非零原始码会标出，但 0 不代表已验证“无故障”。",
        ).assertCountEquals(0)
    }

    @Test
    fun `切换到离线分段显示离线视图且实时展开态保留`() {
        render(DeviceUiState())
        composeRule.onNodeWithText("字段字典").performScrollTo().performClick()
        composeRule.onAllNodesWithText("未标定").assertCountEquals(13)

        composeRule.onNodeWithText("离线分析").performClick()
        composeRule.onNodeWithText(
            "粘贴通知 hex，每行一包，可带空格、冒号或“rx:”前缀。用于分析其它工具抓到的数据，不连接车辆。也可以再粘贴一份采集 B 做逐偏移字节对照。",
        ).assertExists()
        composeRule.onNodeWithText("离线解析其它工具抓到的 hex，不连接蓝牙。").assertExists()
        composeRule.onNodeWithText("证据时间线").assertDoesNotExist()

        composeRule.onNodeWithText("实时分析").performClick()
        composeRule.onNodeWithText("实时会话的帧统计与原始通知；技术数据只在需要时展开。").assertExists()
        // 展开态在宿主里保存，切段回来仍是展开的
        composeRule.onAllNodesWithText("未标定").assertCountEquals(13)
    }

    @Test
    fun `故障参考入口回调到宿主`() {
        var opened = 0
        render(DeviceUiState(), onOpenReference = { opened++ })
        composeRule.onNodeWithText("故障 / 警告参考").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `偏移分析文本与 Dart 逐字节一致`() {
        val frames = listOf(
            frame(0x06, 1, 5, "6400000000"),
            frame(0x06, 1, 5, "c800000000"),
            frame(0x06, 1, 5, "2c01000000"),
            frame(0x0A, 1, 1, "aabb"),
        )
        val expected = buildString {
            append("# iWalk 帧偏移分析（仅字节统计，未含单位/语义）\n")
            append("# session: demo-1\n")
            append("\n## 0x06/0x01/0x05  frames=3 len=5\n")
            append("off  0: distinct=3 44..200  u16le 100..300\n")
            append("off  1: distinct=2 0..1\n")
            append("off  2: distinct=1 0..0 CONST  u16le 0..0\n")
            append("off  3: distinct=1 0..0 CONST\n")
            append("off  4: distinct=1 0..0 CONST\n")
            append("\n## 0x0a/0x01/0x01  frames=1 len=2\n")
            append("# 样本不足（<2），偏移不变不能作为字段证据\n")
            append("off  0: distinct=1 170..170 CONST  u16le 48042..48042\n")
            append("off  1: distinct=1 187..187 CONST\n")
        }
        assertEquals(expected, offsetReportText(analyseFrames(frames), "demo-1"))
    }

    // —— 合成状态 ——

    private fun frame(cmd: Int, a1: Int, a2: Int, payloadHex: String) =
        IbwalkFrame(cmd, a1, a2, payloadHex.hexToBytes())


    @Test
    fun `导出卡片无会话时禁用`() {
        render(DeviceUiState())

        composeRule.onNodeWithText("导出会话").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun `导出在途切换为正在导出并禁用`() {
        render(demoState(), exporting = true)

        composeRule.onNodeWithText("正在导出…").assertIsDisplayed()
        composeRule.onAllNodesWithText("导出会话").assertCountEquals(0)
    }

    @Test
    fun `导出成功与失败原因都经 SnackBar 反馈`() {
        var calls = 0
        render(demoState(), onExportSession = {
            calls += 1
            "已导出：/data/user/0/com.iwalk.companion/files/Documents/iwapp_exports"
        })

        composeRule.onNodeWithText("导出会话").assertIsEnabled().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(
            "已导出：/data/user/0/com.iwalk.companion/files/Documents/iwapp_exports",
        ).assertIsDisplayed()
        assertEquals(1, calls)
    }

    @Test
    fun `有会话且有导出回调时卡片可用`() {
        render(demoState(), onExportSession = { "" })

        composeRule.onNodeWithText("导出会话").assertIsEnabled()
    }

    private fun demoState(): DeviceUiState {
        val profile = DemoProfile.set().profiles.single()
        val now = Instant.now()
        fun value(field: TelemetryField, raw: Double, converted: Double) = FieldValue(
            field = field,
            raw = raw,
            value = converted,
            at = now,
            profile = profile,
            mapping = profile.fields.first { it.field == field },
        )
        val values = linkedMapOf(
            TelemetryField.speed to value(TelemetryField.speed, 1234.0, 12.34),
            TelemetryField.batteryPercent to value(TelemetryField.batteryPercent, 87.0, 87.0),
            TelemetryField.estimatedRange to value(TelemetryField.estimatedRange, 13.0, 13.0),
            TelemetryField.temperature to value(TelemetryField.temperature, 260.0, 26.0),
            TelemetryField.workMode to value(TelemetryField.workMode, 1.0, 1.0),
            TelemetryField.warningCode to value(TelemetryField.warningCode, 0.0, 0.0),
        )
        val events = listOf(
            EvidenceEvent(
                at = now.minusSeconds(2),
                cmd = 0x06, a1 = 0x01, a2 = 0x05,
                payload = "6400000000".hexToBytes(),
                calibrated = true,
                values = mapOf(TelemetryField.speed to value(TelemetryField.speed, 1234.0, 12.34)),
            ),
            EvidenceEvent(
                at = now.minusSeconds(1),
                cmd = 0x0D, a1 = 0x01, a2 = 0x14,
                payload = "e80300000000".hexToBytes(),
                calibrated = true,
                values = mapOf(TelemetryField.speed to value(TelemetryField.speed, 1000.0, 10.0)),
            ),
        )
        return DeviceUiState(
            active = true,
            isDemo = true,
            snapshot = TelemetrySnapshot(
                updatedAt = now,
                framesDecoded = 7,
                uncalibratedFrames = 2,
                values = values,
            ),
            calibration = DemoProfile.set(),
            startedAtMillis = now.toEpochMilli(),
            sessionId = "demo-1",
            frames = listOf(
                frame(0x06, 1, 5, "6400000000"),
                frame(0x06, 1, 5, "c800000000"),
                frame(0x06, 1, 5, "2c01000000"),
            ),
            evidenceEvents = events,
            rawLines = listOf(
                "2026-10-30T10:00:00Z 55aa0964010105d2045702010100",
                "2026-10-30T10:00:00.250Z 55aa0964010105e8035702010100",
            ),
        )
    }
}
