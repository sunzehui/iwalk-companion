package com.iwalk.companion.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置页签的导航（用户正式版 0.4.0）：只保留「只读与安全说明」一个静态二级页；
 * 静态预览与故障参考等调试入口已移除，不得复活。
 */
@RunWith(RobolectricTestRunner::class)
// 钉 sdk=34：targetSdk 36 沙箱要求 Java 21，测试 JVM 是 JDK 17
@Config(sdk = [34], qualifiers = "w400dp-h1200dp-xhdpi")
class SecondaryNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun openSettingsTab() {
        composeRule.setContent { IwApp() }
        composeRule.onNodeWithText("设置").performClick()
    }

    @Test
    fun `设置页签只保留安全说明入口`() {
        openSettingsTab()
        composeRule.onNodeWithText("只读与安全说明").assertExists()
        composeRule.onNodeWithText("会做什么、永不执行什么、锁车与隐私边界").assertExists()
        // 用户版：调试二级页入口与旧占位不得复活
        composeRule.onNodeWithText("车辆数据与配置").assertDoesNotExist()
        composeRule.onNodeWithText("故障与术语参考").assertDoesNotExist()
        composeRule.onNodeWithText("设置页占位").assertDoesNotExist()
    }

    @Test
    fun `从设置进入只读与安全说明并返回`() {
        openSettingsTab()
        composeRule.onNodeWithText("只读与安全说明").performClick()
        composeRule.onNodeWithText("默认不改变车辆状态").assertExists()
        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText("只读与安全说明").assertExists()
    }

    @Test
    fun `打开二级页时切页签会退出二级页且正文互斥`() {
        openSettingsTab()
        composeRule.onNodeWithText("只读与安全说明").performClick()
        composeRule.onNodeWithText("默认不改变车辆状态").assertExists()
        // 二级页覆盖整屏（对齐 Dart Navigator.push），底部页签不可见
        composeRule.onNodeWithText("设备").assertDoesNotExist()
        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText("设备").performClick()
        composeRule.onNodeWithText("连接你的平衡车").assertExists()
        composeRule.onNodeWithText("只读与安全说明").assertDoesNotExist()
    }
}
