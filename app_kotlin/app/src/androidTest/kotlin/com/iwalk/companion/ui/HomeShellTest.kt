package com.iwalk.companion.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.iwalk.companion.MainActivity
import org.junit.Rule
import org.junit.Test

/**
 * 骨架外壳的真机行为：直接拉起清单里的 [MainActivity]，验证三个页签都在、
 * 默认落在设备页、点击切换会替换正文。断言只走公开语义（可见文本）。
 */
class HomeShellTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun allThreeTabsAreVisible() {
        composeRule.onNodeWithText(TAB_DEVICE).assertIsDisplayed()
        composeRule.onNodeWithText(TAB_ANALYSIS).assertIsDisplayed()
        composeRule.onNodeWithText(TAB_SETTINGS).assertIsDisplayed()
    }

    @Test
    fun deviceTabIsTheDefaultBody() {
        composeRule.onNodeWithText(BODY_DEVICE).assertIsDisplayed()
    }

    @Test
    fun switchingTabReplacesBody() {
        composeRule.onNodeWithText(TAB_ANALYSIS).performClick()
        composeRule.onNodeWithText(BODY_ANALYSIS).assertIsDisplayed()
        composeRule.onNodeWithText(BODY_DEVICE).assertDoesNotExist()

        composeRule.onNodeWithText(TAB_SETTINGS).performClick()
        composeRule.onNodeWithText(BODY_SETTINGS).assertIsDisplayed()
        composeRule.onNodeWithText(BODY_ANALYSIS).assertDoesNotExist()

        composeRule.onNodeWithText(TAB_DEVICE).performClick()
        composeRule.onNodeWithText(BODY_DEVICE).assertIsDisplayed()
        composeRule.onNodeWithText(BODY_SETTINGS).assertDoesNotExist()
    }

    private companion object {
        const val TAB_DEVICE = "设备"
        const val TAB_ANALYSIS = "分析"
        const val TAB_SETTINGS = "设置"
        // 工单 02 实装设备页签正文：无会话时的连接引导（原「设备页占位」随占位移除），
        // JVM 侧同屏断言见 DeviceScreenTest；设置页签的标记同理取自工单 08 的入口标题。
        const val BODY_DEVICE = "连接你的平衡车"
        // 工单 05 实装分析页签正文：两段式实时分析视图里的区块标题（原「分析页占位」随占位移除），
        // JVM 侧同屏断言见 AnalysisTabBodyTest；设置页签的标记同理取自工单 08 的入口标题。
        const val BODY_ANALYSIS = "证据时间线"

        const val BODY_SETTINGS = "车辆数据与配置"
    }
}
