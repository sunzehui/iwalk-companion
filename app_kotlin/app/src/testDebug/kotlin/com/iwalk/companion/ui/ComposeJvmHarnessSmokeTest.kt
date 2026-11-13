package com.iwalk.companion.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 工具链自检：Robolectric 能在 JVM 里驱动 Compose 语义树，且离线缓存里有 API 34 的
 * android-all 镜像。本工单所有 UI 断言都依赖这条前提，所以先把它钉成测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeJvmHarnessSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `compose 语义树在 jvm 可用`() {
        var themed = false
        composeRule.setContent {
            themed = true
            IwTheme { Text("自检") }
        }
        composeRule.onNodeWithText("自检").assertExists()
        assertTrue("IwTheme 未生效", themed)
    }
}
