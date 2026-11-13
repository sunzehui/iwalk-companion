package com.iwalk.companion.ui.preview

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 静态界面预览页：期望文案逐字取自 `app_v2/lib/features/preview/static_preview_page.dart`
 * （笔记 §8）。工单第 6 条要求用 Compose 语义断言证明控件不可操作：
 * 全页唯一带点击动作的节点是返回按钮；对示例控件注入真实触摸后语义树不变。
 */
@RunWith(RobolectricTestRunner::class)
// 钉 sdk=34：targetSdk 36 沙箱要求 Java 21，测试 JVM 是 JDK 17
@Config(sdk = [34], qualifiers = "w400dp-h2000dp-xhdpi")
class StaticPreviewScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun showPreviewPage(onBack: () -> Unit = {}) =
        composeRule.setContent { StaticPreviewScreen(onBack = onBack) }

    @Test
    fun `顶部控件区页脚三处免责声明齐备`() {
        showPreviewPage()
        composeRule.onNodeWithText("界面预览").assertExists()
        // 顶部横幅
        composeRule.onNodeWithText("静态界面预览").assertExists()
        composeRule
            .onNodeWithText(
                "以下车辆数据为示例，配置控件为静态占位；本页不连接车辆、不读取数据、不发送任何指令。",
            )
            .assertExists()
        // 控件区声明
        composeRule
            .onNodeWithText(
                "控件不可操作，不会发送 BLE 帧，也不会改变车辆。" +
                    "正式实现配置写入前，必须先采集官方 App 的对应出站帧，完成协议与安全验证。",
            )
            .assertExists()
        // 页脚
        composeRule
            .onNodeWithText(
                "未包含：车辆校准、恢复出厂、OTA、账户、NFC、遥控、地图、头盔。" +
                    "当前只保留只读信息层与默认关闭的实验性锁车。",
            )
            .assertExists()
        // 数据区脚注与分节 trailing
        composeRule.onNodeWithText("示例数值只用于确认信息层级，不代表任何真实车辆读数。").assertExists()
        composeRule.onNodeWithText("示例").assertExists()
        composeRule.onNodeWithText("静态占位").assertExists()
    }

    @Test
    fun `展示示例速度指标与原始码灯光`() {
        showPreviewPage()
        composeRule.onNodeWithText("MiniRobot · …9887").assertExists()
        composeRule.onNodeWithText("G1 · 示例链路").assertExists()
        composeRule.onNodeWithText("已连接（示例）").assertExists()
        composeRule.onNodeWithText("24.6").assertExists()
        composeRule.onNodeWithText("km/h").assertExists()
        composeRule.onNodeWithText("更新于 1 秒前 · 12 帧/秒 · 示例").assertExists()
        composeRule.onNodeWithText("86").assertExists()
        composeRule.onNodeWithText("31").assertExists()
        composeRule.onNodeWithText("12.4").assertExists()
        composeRule.onNodeWithText("工作模式 raw 1").assertExists()
        composeRule.onNodeWithText("警告码 raw 0 · 未收录").assertExists()
    }

    @Test
    fun `配置控件外观齐备`() {
        showPreviewPage()
        listOf(
            "前灯", "照明", "尾灯", "制动与夜间提示", "氛围灯", "灯效与颜色",
            "驾驶模式", "示例：普通", "舒适", "普通", "运动",
            "限速", "示例：已开启 18 km/h", "18 km/h",
            "定速巡航", "示例：关闭", "能量回收", "示例：中", "中",
        ).forEach { text -> composeRule.onNodeWithText(text).assertExists() }
    }

    @Test
    fun `唯一可点击节点是返回按钮其余控件无点击动作`() {
        var backCount = 0
        showPreviewPage(onBack = { backCount++ })
        // 整页只有一个带点击动作的节点，且它就是返回按钮
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
        composeRule.onNode(hasClickAction()).assert(hasText("返回"))
        assertEquals(0, backCount)
        composeRule.onNode(hasClickAction()).performClick()
        assertEquals(1, backCount)

        // 示例开关只以语义标签存在，不是可操作控件
        composeRule.onAllNodes(hasContentDescription("静态占位开关")).assertCountEquals(4)
        composeRule.onNodeWithText("前灯").assert(hasClickAction().not())
        composeRule.onNodeWithText("驾驶模式").assert(hasClickAction().not())
    }

    @Test
    fun `触摸示例控件后开关与模式语义状态不变`() {
        showPreviewPage()
        val on = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On)
        val off = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off)
        composeRule.onAllNodes(on).assertCountEquals(2) // 前灯、尾灯
        composeRule.onAllNodes(off).assertCountEquals(2) // 氛围灯、定速巡航

        // 对开关与模式芯片注入真实触摸事件（不借用语义点击动作）
        composeRule.onAllNodes(hasContentDescription("静态占位开关")).onFirst().performTouchInput {
            down(center)
            up()
        }
        composeRule.onNodeWithText("舒适").performTouchInput {
            down(center)
            up()
        }
        composeRule.onNodeWithText("运动").performTouchInput {
            down(center)
            up()
        }
        composeRule.waitForIdle()

        // 页面没有任何状态可被改变：语义仍逐字对齐 Dart 的 const 视图
        composeRule.onAllNodes(on).assertCountEquals(2)
        composeRule.onAllNodes(off).assertCountEquals(2)
        composeRule.onNodeWithText("示例：普通").assertExists()
        composeRule.onNodeWithText("静态界面预览").assertExists()
    }
}
