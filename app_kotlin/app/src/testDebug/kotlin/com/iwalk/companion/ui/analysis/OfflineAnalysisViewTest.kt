package com.iwalk.companion.ui.analysis

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.iwalk.companion.ui.IwTheme
import com.iwalk.companion.ui.device.DeviceUiState
import com.iwalk.protocol.IbwalkFrame
import com.iwalk.protocol.hexToBytes
import com.iwalk.protocol.toHex
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 离线分析视图的 JVM 覆盖（Robolectric + Compose），对应 notes/ui-analysis.md §3 / §3.9。
 *
 * 断言里的中文都是笔记的逐字副本（不引用 UI 常量）；所有操作都走宿主
 * [AnalysisTabBody]，顺带验证状态提升后切换分段不丢输入与结果。
 */
@RunWith(RobolectricTestRunner::class)
// 钉 sdk=34：targetSdk 36 的 Robolectric 沙箱要求 Java 21，测试 JVM 是 JDK 17
@Config(sdk = [34], qualifiers = "w400dp-h1200dp-xhdpi")
class OfflineAnalysisViewTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun openOffline() {
        composeRule.setContent {
            IwTheme {
                AnalysisTabBody(state = DeviceUiState(), onOpenReference = {})
            }
        }
        composeRule.onNodeWithText("离线分析").performClick()
    }

    private fun frameHex(cmd: Int, a1: Int, a2: Int, payloadHex: String) =
        IbwalkFrame(cmd, a1, a2, payloadHex.hexToBytes()).encode().toHex()

    private fun typeA(text: String) {
        composeRule.onNodeWithTag(OFFLINE_TAG_A).performScrollTo().performTextReplacement(text)
    }

    private fun parse() {
        composeRule.onNodeWithText("解析").performScrollTo().performClick()
    }

    private fun openCompareCard() {
        composeRule.onNodeWithTag(OFFLINE_TAG_COMPARE_TILE).performScrollTo().performClick()
    }

    private fun typeB(text: String) {
        composeRule.onNodeWithTag(OFFLINE_TAG_B).performScrollTo().performTextReplacement(text)
    }

    private fun compare() {
        composeRule.onNodeWithText("开始对照").performScrollTo().performClick()
    }

    @Test
    fun `空输入与非法输入都有明确提示`() {
        openOffline()

        // 初始态：没有任何统计卡 / 帧组统计 / 对照
        composeRule.onNodeWithText("帧组统计").assertDoesNotExist()
        composeRule.onNodeWithTag(OFFLINE_TAG_STAT_FRAMES).assertDoesNotExist()
        composeRule.onNodeWithText("对照采集 B（可选）").assertExists()
        composeRule.onNodeWithText("与 A 比较逐偏移字节分布；不接触蓝牙").assertExists()

        parse()
        composeRule.onNodeWithText("请输入或粘贴通知 hex").assertExists()

        composeRule.onNodeWithTag(OFFLINE_TAG_A)
            .performTextReplacement("这段文字没有任何十六进制")
        parse()
        composeRule.onNodeWithText("未识别出任何帧：请确认是 55AA 开头的通知数据").assertExists()
        composeRule.onNodeWithText("没有可统计的完整帧。").assertExists()
    }

    @Test
    fun `解析成功展示四项统计与帧组统计`() {
        openOffline()
        val hex = frameHex(0x06, 1, 5, "6400000000")
        typeA("$hex\n$hex")
        parse()

        composeRule.onNodeWithTag(OFFLINE_TAG_STAT_FRAMES).assertTextEquals("2")
        composeRule.onNodeWithTag(OFFLINE_TAG_STAT_REJECTED).assertTextEquals("0")
        composeRule.onNodeWithTag(OFFLINE_TAG_STAT_PENDING).assertTextEquals("0B")
        composeRule.onNodeWithTag(OFFLINE_TAG_STAT_TOTAL).assertTextEquals("26B")
        composeRule.onNodeWithText("识别帧").assertExists()
        composeRule.onNodeWithText("拒收块").assertExists()
        composeRule.onNodeWithText("总字节").assertExists()
        composeRule.onNodeWithTag(OFFLINE_TAG_COPY_REPORT).assertIsEnabled()

        composeRule.onNodeWithText("帧组统计").assertExists()
        composeRule.onNodeWithText("样本 <2 时“恒定”不构成字段证据。").assertExists()
        composeRule.onNodeWithText("2 帧 · 5 B").assertExists()
        composeRule.onNodeWithText("off").assertExists()
        composeRule.onNodeWithText("取值数").assertExists()
        composeRule.onNodeWithText("范围").assertExists()
        composeRule.onAllNodesWithText("u16le").assertCountEquals(1)
        composeRule.onNodeWithText("没有可统计的完整帧。").assertDoesNotExist()

        // 复制报告：写剪贴板并给出口头确认
        composeRule.onNodeWithTag(OFFLINE_TAG_COPY_REPORT).performClick()
        composeRule.onNodeWithText("离线分析报告已复制").assertExists()
    }

    @Test
    fun `全部校验失败时展示警示条并保留拒收字节`() {
        openOffline()
        typeA("55aa02ff0103dead")
        parse()

        composeRule.onNodeWithTag(OFFLINE_TAG_STAT_FRAMES).assertTextEquals("0")
        composeRule.onNodeWithTag(OFFLINE_TAG_STAT_REJECTED).assertTextEquals("1")
        composeRule.onNodeWithText("所有数据都校验失败：可能帧格式不同。原始字节已保留，可在报告中复制。")
            .assertExists()
        composeRule.onNodeWithText("没有可统计的完整帧。").assertExists()
        // 只有拒收块、没有完整帧时仍允许复制报告
        composeRule.onNodeWithTag(OFFLINE_TAG_COPY_REPORT).assertIsEnabled()
        composeRule.onNodeWithText("未识别出任何帧：请确认是 55AA 开头的通知数据").assertExists()
    }

    @Test
    fun `重新解析会作废旧对照`() {
        openOffline()
        val a = frameHex(0x06, 1, 5, "0a00ff")
        typeA(a)
        parse()

        openCompareCard()
        typeB(frameHex(0x06, 1, 5, "1400ff"))
        compare()
        composeRule.onNodeWithText("A / B 字节对照").assertExists()

        // 换一份 A 重新解析：对照、B 统计与对照错误全部作废
        composeRule.onNodeWithTag(OFFLINE_TAG_A)
            .performScrollTo()
            .performTextReplacement(frameHex(0x06, 1, 5, "0b00ff"))
        parse()
        composeRule.onNodeWithText("A / B 字节对照").assertDoesNotExist()
        composeRule.onNodeWithText("B：帧 1 · 拒收块 0 · 不完整 0 B").assertDoesNotExist()
    }

    @Test
    fun `对照未满足前置条件时只给内联错误`() {
        openOffline()
        openCompareCard()

        // A 还没解析
        typeB(frameHex(0x06, 1, 5, "1400ff"))
        compare()
        composeRule.onNodeWithText("先解析采集 A，且 A 至少包含 1 个完整帧").assertExists()

        // 解析 A 后 B 为空
        composeRule.onNodeWithTag(OFFLINE_TAG_A)
            .performScrollTo()
            .performTextReplacement(frameHex(0x06, 1, 5, "0a00ff"))
        parse()
        composeRule.onNodeWithTag(OFFLINE_TAG_B).performScrollTo().performTextReplacement("")
        compare()
        composeRule.onNodeWithText("请输入或粘贴采集 B").assertExists()

        // B 解析不出完整帧
        typeB("没有可用的帧")
        compare()
        composeRule.onNodeWithText("采集 B 未识别出完整帧，无法做偏移对照").assertExists()
        composeRule.onNodeWithText("B：帧 0 · 拒收块 0 · 不完整 0 B").assertExists()
        composeRule.onNodeWithText("A / B 字节对照").assertDoesNotExist()
    }

    @Test
    fun `对照成功展示计数说明与逐偏移差异`() {
        openOffline()
        typeA(frameHex(0x06, 1, 5, "0a00ff"))
        parse()
        openCompareCard()
        typeB(frameHex(0x06, 1, 5, "1400ff"))
        compare()

        composeRule.onNodeWithText("A / B 字节对照").assertExists()
        composeRule.onNodeWithText("逐偏移比较两份采集的分布，不保证逐帧时间对齐，也不代表字段语义。")
            .assertExists()
        composeRule.onNodeWithTag(OFFLINE_TAG_COMPARE_SHARED).assertTextEquals("1")
        composeRule.onNodeWithTag(OFFLINE_TAG_COMPARE_ONLY_A).assertTextEquals("0")
        composeRule.onNodeWithTag(OFFLINE_TAG_COMPARE_ONLY_B).assertTextEquals("0")
        composeRule.onNodeWithTag(OFFLINE_TAG_COMPARE_DIFF_OFFSETS).assertTextEquals("1")
        composeRule.onAllNodesWithText("0x06/0x01/0x05").assertCountEquals(2)
        composeRule.onNodeWithText("偏移").assertExists()
        composeRule.onNodeWithText("A（1 帧）").assertExists()
        composeRule.onNodeWithText("B（1 帧）").assertExists()
        composeRule.onNodeWithText("10..10 · 1 值 · 恒定 · u16 10..10").assertExists()
        composeRule.onNodeWithText("20..20 · 1 值 · 恒定 · u16 20..20").assertExists()
        composeRule.onNodeWithTag(OFFLINE_TAG_COPY_COMPARISON).assertIsEnabled()
    }

    @Test
    fun `切换分段保留离线输入与解析结果`() {
        openOffline()
        val hex = frameHex(0x06, 1, 5, "6400000000")
        typeA("$hex\n$hex")
        parse()
        openCompareCard()
        typeB(frameHex(0x06, 1, 5, "c8000000"))
        compare()
        composeRule.onNodeWithText("A / B 字节对照").assertExists()

        composeRule.onNodeWithText("实时分析").performClick()
        composeRule.onNodeWithText("证据时间线").assertExists()
        composeRule.onNodeWithText("A / B 字节对照").assertDoesNotExist()

        composeRule.onNodeWithText("离线分析").performClick()
        composeRule.onNodeWithTag(OFFLINE_TAG_A).assertTextContains(hex, substring = true)
        composeRule.onNodeWithTag(OFFLINE_TAG_STAT_FRAMES).assertTextEquals("2")
        composeRule.onNodeWithText("帧组统计").assertExists()
        composeRule.onNodeWithText("A / B 字节对照").assertExists()
        composeRule.onNodeWithTag(OFFLINE_TAG_COMPARE_SHARED).assertTextEquals("1")
    }

    @Test
    fun `清空按钮同时清掉两份输入与全部结果`() {
        openOffline()
        val hex = frameHex(0x06, 1, 5, "0a00ff")
        typeA(hex)
        parse()
        openCompareCard()
        typeB(frameHex(0x06, 1, 5, "1400ff"))
        compare()

        composeRule.onNodeWithTag(OFFLINE_TAG_CLEAR).performScrollTo().performClick()
        composeRule.onNodeWithTag(OFFLINE_TAG_STAT_FRAMES).assertDoesNotExist()
        composeRule.onNodeWithText("A / B 字节对照").assertDoesNotExist()
        composeRule.onNodeWithText("帧组统计").assertDoesNotExist()
        val editable = composeRule.onNodeWithTag(OFFLINE_TAG_A)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
        assertEquals("", editable.text)
    }

    @Test
    fun `离线视图不出现任何语义字段名`() {
        openOffline()
        typeA(
            frameHex(0x06, 1, 5, "6400000000") + "\n" +
                frameHex(0x06, 1, 5, "c800000000"),
        )
        parse()
        openCompareCard()
        typeB(frameHex(0x06, 1, 5, "2c01000000"))
        compare()

        for (word in listOf("速度", "电量", "温度", "里程")) {
            composeRule.onAllNodesWithText(word, substring = true).assertCountEquals(0)
        }
        composeRule.onNodeWithContentDescription("复制报告").assertExists()
        // 文案核对：说明与页脚只谈字节
        composeRule.onNodeWithText("离线分析不接触蓝牙；原始字节和拒收块都会保留，不静默丢弃。")
            .assertExists()
    }
}
