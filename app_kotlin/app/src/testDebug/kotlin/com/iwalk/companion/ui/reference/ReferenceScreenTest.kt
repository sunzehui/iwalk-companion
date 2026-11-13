package com.iwalk.companion.ui.reference

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 故障与术语参考页（无会话）的公开语义断言。
 *
 * 期望文案逐字来自 `ui-settings-secondary.md` §7 与 `app_v2/lib/reference/minirobot_reference.dart`，
 * 不由实现代码反推。断言只用 `assertExists`：静态内容在 JVM 侧视口外依然应在组合树里，
 * 而显示效果留给真机（本工单无设备，不做视觉验收）。
 */
@RunWith(RobolectricTestRunner::class)
// 必须钉 sdk=34：targetSdk 36 的沙箱要求 Java 21，本工具链的测试 JVM 是 JDK 17
@Config(sdk = [34], qualifiers = "w400dp-h2000dp-xhdpi")
class ReferenceScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun showReferencePage() =
        composeRule.setContent { ReferenceScreen(onBack = {}) }

    @Test
    fun `标题与三条免责声明都在页面上`() {
        showReferencePage()
        composeRule.onNodeWithText("故障与术语参考").assertExists()
        composeRule
            .onNodeWithText(
                "以下内容来自 MiniRobot 官方资源，不是 iWalk 的诊断结论。" +
                    "官方短码是同一条目表里的名字，不保证等于 payload 中的数值错误码；" +
                    "数值映射与取码逻辑均未验证。",
            )
            .assertExists()
        composeRule
            .onNodeWithText(
                "本页只用于命名与分类。iWalk 不会据此发送故障查询、校准、控制或升级命令；" +
                    "车辆实际发来的原始码始终优先于这里的标签。",
            )
            .assertExists()
    }

    @Test
    fun `来源两行逐字标注官方包名与来源说明`() {
        showReferencePage()
        // Dart 把两行渲染在同一个 Text 里（reference_page.dart:63-72），中间只有一个换行符
        composeRule
            .onNodeWithText(
                "来源：MiniRobot 11.3.8 (303) · com.loby.balance.car.google\n" +
                    "来源：官方 App 内置文案与界面节点。iWalk 只作命名参考，不据此诊断车辆。",
            )
            .assertExists()
    }

    @Test
    fun `无会话时三行原始值都显示无活动会话`() {
        showReferencePage()
        composeRule.onNodeWithText("本次会话原始值").assertExists()
        composeRule.onNodeWithText("无会话").assertExists()
        composeRule
            .onNodeWithText("原始值不会自动匹配下面的条目；即使原始值为 0，也不能据此断言“无故障”。")
            .assertExists()
        composeRule.onAllNodesWithText("无活动会话").assertCountEquals(3)
        // 「工作模式」同时出现在原始值行与术语表，共 2 个节点
        composeRule.onAllNodesWithText("工作模式").assertCountEquals(2)
        composeRule.onNodeWithText("警告码").assertExists()
        composeRule.onNodeWithText("错误码").assertExists()
    }

    @Test
    fun `短故障码表列出 hxz1 到 hxz13 并标注未映射数值`() {
        showReferencePage()
        composeRule.onNodeWithText("短故障码（hxz）").assertExists()
        composeRule.onNodeWithText("13 条 · 未映射数值").assertExists()
        (1..13).forEach { index ->
            composeRule.onNodeWithText("hxz$index").assertExists()
        }
        composeRule.onNodeWithText("转向异常 / Steering Abnormality").assertExists()
        composeRule.onNodeWithText("与 hxz2 重复；为何重复未验证").assertExists()
    }

    @Test
    fun `长故障文案按域分组并展示域名与条数`() {
        showReferencePage()
        composeRule.onNodeWithText("长故障文案（节选）").assertExists()
        composeRule.onNodeWithText("按域分组").assertExists()
        composeRule
            .onNodeWithText("官方 App 的提示文案，用来理解可能的状态词汇；不能由一条文案反推具体硬件故障。")
            .assertExists()
        composeRule.onNodeWithText("温度 / 电源").assertExists()
        // 官方 6 组里 4 组恰为 6 条（minirobot_reference.dart:27-75：6/6/6/6/7/4）
        composeRule.onAllNodesWithText("6 条").assertCountEquals(4)
        composeRule.onNodeWithText("· 电池温度异常（10–50°C）").assertExists()
        composeRule.onNodeWithText("· 车辆序列号错误").assertExists()
    }

    @Test
    fun `术语表与版本组件表齐备且标注不主动查询`() {
        showReferencePage()
        composeRule.onNodeWithText("状态与术语").assertExists()
        composeRule.onNodeWithText("限速警告").assertExists()
        composeRule.onNodeWithText("新手 8 km/h、普通 18 km/h、运动 24 km/h").assertExists()

        composeRule.onNodeWithText("版本组件").assertExists()
        composeRule.onNodeWithText("不主动查询").assertExists()
        composeRule
            .onNodeWithText(
                "标准 GATT 只提供设备名、Appearance 和连接参数；固件版本不在标准 GATT 中。" +
                    "iWalk 不会把设备名当作固件版本，也不会主动查询版本。",
            )
            .assertExists()
        composeRule.onNodeWithText("蓝牙版本").assertExists()
        // 9 个版本组件里 7 个的处理方式相同（minirobot_reference.dart:91-101）
        composeRule.onAllNodesWithText("仅在通知中出现时展示").assertCountEquals(7)
        composeRule.onNodeWithText("卡丁车套件").assertExists()
        // 卡丁车套件与油门蓝牙两行共用同一处理文案
        composeRule.onAllNodesWithText("车型相关；不主动查询").assertCountEquals(2)
    }

    @Test
    fun `有会话时原始值并列显示不匹配官方短码`() {
        composeRule.setContent {
            ReferenceScreen(
                onBack = {},
                rawValues = ReferenceRawValues(
                    hasSession = true,
                    isDemo = false,
                    workModeRaw = 1,
                    warningCodeRaw = 0,
                    errorCodeRaw = null,
                ),
            )
        }
        composeRule.onNodeWithText("真车通知").assertExists()
        // 工作模式 raw 1 → 0 位小数标定值 1；警告码 raw 0 保持 muted；错误码未观察到
        composeRule.onNodeWithText("raw 1 · 标定值 1").assertExists()
        composeRule.onNodeWithText("raw 0 · 标定值 0").assertExists()
        composeRule.onNodeWithText("本次会话未观察到").assertExists()
        composeRule.onNodeWithText("无活动会话").assertDoesNotExist()
    }

    @Test
    fun `真车会话但字段未出现时显示未观察到而不是无活动会话`() {
        composeRule.setContent {
            ReferenceScreen(onBack = {}, rawValues = ReferenceRawValues(hasSession = true))
        }
        composeRule.onNodeWithText("真车通知").assertExists()
        composeRule.onAllNodesWithText("本次会话未观察到").assertCountEquals(3)
        composeRule.onNodeWithText("无活动会话").assertDoesNotExist()
        composeRule.onNodeWithText("无会话").assertDoesNotExist()
    }

    @Test
    fun `演示会话显示演示数据标识`() {
        composeRule.setContent {
            ReferenceScreen(
                onBack = {},
                rawValues = ReferenceRawValues(hasSession = true, isDemo = true, warningCodeRaw = 2),
            )
        }
        composeRule.onNodeWithText("演示数据").assertExists()
        composeRule.onNodeWithText("raw 2 · 标定值 2").assertExists()
        composeRule.onNodeWithText("真车通知").assertDoesNotExist()
    }
}
