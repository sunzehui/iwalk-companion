package com.iwalk.companion.ui.device

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import com.iwalk.companion.ui.IwTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockSliderTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `complete gesture emits once and handle returns to center`() {
        val actions = mutableListOf<Boolean>()
        compose.setContent { IwTheme { LockSlider(enabled = true, onSend = { actions += it }) } }
        compose.onNodeWithTag("lock-slider").performTouchInput { swipe(center, centerRight) }
        compose.runOnIdle { assertEquals(listOf(true), actions) }
        val progress = compose.onNodeWithTag("lock-slider").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0f, progress.current, 0.01f)
    }
    @Test fun `disabled slider cannot issue a command`() {
        val actions = mutableListOf<Boolean>()
        compose.setContent { IwTheme { LockSlider(enabled = false, onSend = { actions += it }) } }
        compose.onNodeWithTag("lock-slider").assertIsNotEnabled().performTouchInput { swipe(center, centerRight) }
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }
}
