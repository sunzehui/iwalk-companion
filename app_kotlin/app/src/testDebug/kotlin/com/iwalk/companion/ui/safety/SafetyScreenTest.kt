package com.iwalk.companion.ui.safety

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 只读与安全页：纯静态文案页。期望文案逐字取自
 * `app_v2/lib/features/safety/read_only_safety_page.dart`（笔记 §6），不由实现代码反推。
 */
@RunWith(RobolectricTestRunner::class)
// 钉 sdk=34：targetSdk 36 沙箱要求 Java 21，测试 JVM 是 JDK 17
@Config(sdk = [34], qualifiers = "w400dp-h2000dp-xhdpi")
class SafetyScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun showSafetyPage() = composeRule.setContent { SafetyScreen(onBack = {}) }

    @Test
    fun `标题与默认只读横幅`() {
        showSafetyPage()
        composeRule.onNodeWithText("只读与安全").assertExists()
        composeRule.onNodeWithText("默认不改变车辆状态").assertExists()
        composeRule
            .onNodeWithText(
                "iWalk 是只读遥测与证据工具，不是车辆控制 App。" +
                    "G1 使用两条固定查询获取遥测；改变车辆状态的实验性锁车需用户显式开启。",
            )
            .assertExists()
    }

    @Test
    fun `应用会做什么四条与唯一例外说明`() {
        showSafetyPage()
        composeRule.onNodeWithText("应用会做什么").assertExists()
        composeRule.onNodeWithText("扫描并连接附近设备，显示服务发现结果与链路信息。").assertExists()
        composeRule.onNodeWithText("读取标准 GATT 特征，按官方节奏发送两条固定遥测查询并订阅通知。").assertExists()
        composeRule.onNodeWithText("在本地记录原始字节、帧统计与解码快照，并导出证据文件。").assertExists()
        composeRule.onNodeWithText("离线解析其它工具抓到的 hex；该过程不接触蓝牙。").assertExists()
        composeRule
            .onNodeWithText(
                "唯一例外是默认关闭的实验性锁车：开启并确认风险后，" +
                    "只允许发送两条固定帧，单次、无重试，且不会回读锁状态。",
            )
            .assertExists()
    }

    @Test
    fun `永不执行三组芯片与封禁说明`() {
        showSafetyPage()
        composeRule.onNodeWithText("永不执行").assertExists()
        composeRule.onNodeWithText("行驶与车身控制").assertExists()
        composeRule.onNodeWithText("车辆校准与维护").assertExists()
        composeRule.onNodeWithText("账户与周边").assertExists()
        listOf(
            "电机 / 运动控制", "遥控", "灯光", "限速", "驾驶模式", "定速巡航", "零启动",
            "能量回收", "灵敏度", "跟随",
            "姿态 / 平衡点校准", "恢复出厂", "固件 OTA", "版本 / 黑匣子等应用层查询",
            "登录 / 绑定", "连接密码", "NFC 卡", "地图 / GPS 功能", "头盔", "商城 / 外链",
        ).forEach { chip ->
            composeRule.onNodeWithText(chip).assertExists()
        }
        composeRule
            .onNodeWithText(
                "应用没有通用写入接口，也没有上述功能的入口。" +
                    "不会发送密码、校准、OTA 分块或未知命令来试探车辆。",
            )
            .assertExists()
    }

    @Test
    fun `实验性锁车四条与风险提示`() {
        showSafetyPage()
        composeRule.onNodeWithText("锁车控制默认关闭；每次开启都需要阅读风险并确认。").assertExists()
        composeRule.onNodeWithText("关锁 / 解锁帧来自厂商 App 静态推导，2026-11-12 真车实测有效（各发一次）；仍无车端回执。").assertExists()
        composeRule.onNodeWithText("BLE 写入完成只表示数据已交给链路，不代表车辆已执行。").assertExists()
        composeRule.onNodeWithText("应用无法读取锁状态；UI 不显示“已锁”或“已解锁”的持久状态。").assertExists()
        composeRule
            .onNodeWithText(
                "仅在车辆停稳、无人站立、周围无障碍时考虑使用；" +
                    "不要把该功能用于安全、防盗或骑行中的操作。",
            )
            .assertExists()
    }

    @Test
    fun `解码标定与车辆校准区分卡`() {
        showSafetyPage()
        composeRule.onNodeWithText("解码标定 ≠ 车辆校准").assertExists()
        composeRule.onNodeWithText("解码标定").assertExists()
        composeRule
            .onNodeWithText(
                "本机的字段映射：offset / type / scale / bias。" +
                    "只影响 App 如何解释已收到的字节，不向车辆写入任何内容。",
            )
            .assertExists()
        composeRule.onNodeWithText("车辆校准").assertExists()
        composeRule
            .onNodeWithText(
                "MiniRobot 中的姿态传感器 / 平衡点校准，会改变车辆行为。" +
                    "iWalk 不提供该功能，也不会发送相关命令。",
            )
            .assertExists()
    }

    @Test
    fun `采集与隐私五条加页尾边界声明`() {
        showSafetyPage()
        composeRule.onNodeWithText("采集与隐私").assertExists()
        composeRule.onNodeWithText("原始通知、导出文件与标定默认只保存在本机应用目录。").assertExists()
        composeRule.onNodeWithText("应用没有账户、云同步或广告逻辑；证据不会被自动上传。").assertExists()
        composeRule.onNodeWithText("演示会话与真实标定完全隔离；演示数值不会写入车辆或全局标定。").assertExists()
        composeRule.onNodeWithText("分享文档前请掩码 MAC、序列号和设备名；真车采集不要公开上传。").assertExists()
        composeRule.onNodeWithText("导出包含入站通知、链路事实与锁帧尝试记录，不含车端确认。").assertExists()
        composeRule
            .onNodeWithText(
                "本页说明功能边界，不构成对车辆安全、防盗或故障诊断的保证。" +
                    "任何涉及车辆运动、负载或骑行的验证都必须另行制定安全措施。",
            )
            .assertExists()
    }
}
