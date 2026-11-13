package com.iwalk.companion.ui.safety

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iwalk.companion.ui.IwColors
import com.iwalk.companion.ui.secondary.IwBodyNote
import com.iwalk.companion.ui.secondary.IwCard
import com.iwalk.companion.ui.secondary.IwCardDivider
import com.iwalk.companion.ui.secondary.IwGap
import com.iwalk.companion.ui.secondary.IwNoteBand
import com.iwalk.companion.ui.secondary.IwSectionHeader
import com.iwalk.companion.ui.secondary.IwSecondaryPage

/**
 * 只读与安全页（纯静态，无状态、无回调，除 [onBack] 外）：
 * 逐字对齐 `app_v2/lib/features/safety/read_only_safety_page.dart`（笔记 §6）。
 *
 * 顶栏文案是 `只读与安全`（Dart AppBar 原样），与设置页入口标题 `只读与安全说明` 不同，保留差异。
 */
@Composable
fun SafetyScreen(onBack: () -> Unit) {
    IwSecondaryPage(title = "只读与安全", onBack = onBack) {
        HeroBand(
            title = "默认不改变车辆状态",
            body = "iWalk 是只读遥测与证据工具，不是车辆控制 App。" +
                "G1 使用两条固定查询获取遥测；改变车辆状态的实验性锁车需用户显式开启。",
            accent = IwColors.Success,
        )
        IwGap(24.dp)

        IwSectionHeader(title = "应用会做什么", titleSize = 16.sp)
        IwGap(10.dp)
        Bullet("扫描并连接附近设备，显示服务发现结果与链路信息。")
        Bullet("读取标准 GATT 特征，按官方节奏发送两条固定遥测查询并订阅通知。")
        Bullet("在本地记录原始字节、帧统计与解码快照，并导出证据文件。")
        Bullet("离线解析其它工具抓到的 hex；该过程不接触蓝牙。")
        IwGap(8.dp)
        IwNoteBand(
            text = "唯一例外是默认关闭的实验性锁车：开启并确认风险后，" +
                "只允许发送两条固定帧，单次、无重试，且不会回读锁状态。",
            accent = IwColors.Warning,
        )
        IwGap(24.dp)

        IwSectionHeader(title = "永不执行", titleSize = 16.sp)
        IwGap(10.dp)
        ChipGroup(
            title = "行驶与车身控制",
            items = listOf(
                "电机 / 运动控制", "遥控", "灯光", "限速", "驾驶模式", "定速巡航",
                "零启动", "能量回收", "灵敏度", "跟随",
            ),
        )
        ChipGroup(
            title = "车辆校准与维护",
            items = listOf("姿态 / 平衡点校准", "恢复出厂", "固件 OTA", "版本 / 黑匣子等应用层查询"),
        )
        ChipGroup(
            title = "账户与周边",
            items = listOf("登录 / 绑定", "连接密码", "NFC 卡", "地图 / GPS 功能", "头盔", "商城 / 外链"),
        )
        IwGap(8.dp)
        IwNoteBand(
            text = "应用没有通用写入接口，也没有上述功能的入口。" +
                "不会发送密码、校准、OTA 分块或未知命令来试探车辆。",
            accent = IwColors.Error,
        )
        IwGap(24.dp)

        IwSectionHeader(title = "实验性锁车", titleSize = 16.sp)
        IwGap(10.dp)
        Bullet("锁车控制默认关闭；每次开启都需要阅读风险并确认。")
        Bullet("关锁 / 解锁帧来自厂商 App 静态推导，2026-11-12 真车实测有效（各发一次）；仍无车端回执。")
        Bullet("BLE 写入完成只表示数据已交给链路，不代表车辆已执行。")
        Bullet("速度必须有读数且不超过 1.0 km/h；速度未标定直接阻止，因为无法证明车辆静止。")
        Bullet("应用无法读取锁状态；UI 不显示“已锁”或“已解锁”的持久状态。")
        IwGap(8.dp)
        IwNoteBand(
            text = "仅在车辆停稳、无人站立、周围无障碍时考虑使用；" +
                "不要把该功能用于安全、防盗或骑行中的操作。",
            accent = IwColors.Warning,
        )
        IwGap(24.dp)

        IwSectionHeader(title = "解码标定 ≠ 车辆校准", titleSize = 16.sp)
        IwGap(10.dp)
        IwCard {
            AccentInfoRow(
                title = "解码标定",
                body = "本机的字段映射：offset / type / scale / bias。" +
                    "只影响 App 如何解释已收到的字节，不向车辆写入任何内容。",
                accent = IwColors.Success,
            )
            IwCardDivider()
            AccentInfoRow(
                title = "车辆校准",
                body = "MiniRobot 中的姿态传感器 / 平衡点校准，会改变车辆行为。" +
                    "iWalk 不提供该功能，也不会发送相关命令。",
                accent = IwColors.Warning,
            )
        }
        IwGap(24.dp)

        IwSectionHeader(title = "采集与隐私", titleSize = 16.sp)
        IwGap(10.dp)
        Bullet("原始通知、导出文件与标定默认只保存在本机应用目录。")
        Bullet("应用没有账户、云同步或广告逻辑；证据不会被自动上传。")
        Bullet("演示会话与真实标定完全隔离；演示数值不会写入车辆或全局标定。")
        Bullet("分享文档前请掩码 MAC、序列号和设备名；真车采集不要公开上传。")
        Bullet("导出包含入站通知、链路事实与锁帧尝试记录，不含车端确认。")
        IwGap(24.dp)

        IwBodyNote(
            "本页说明功能边界，不构成对车辆安全、防盗或故障诊断的保证。" +
                "任何涉及车辆运动、负载或骑行的验证都必须另行制定安全措施。",
        )
    }
}

/** 顶部横幅：success α0.08 底 + 18dp 圆角 + 标题/正文（Dart `read_only_safety_page.dart:21-61`）。 */
@Composable
private fun HeroBand(title: String, body: String, accent: Color) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent.copy(alpha = 0.08f))
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(24.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(accent),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.W600, color = IwColors.Text)
            IwGap(6.dp)
            Text(text = body, fontSize = 12.sp, lineHeight = 19.sp, color = IwColors.TextMuted)
        }
    }
}

/** 正文项目符号：5dp 圆点 + 12.5/1.55 text 文本（Dart `_Bullet`）。 */
@Composable
private fun Bullet(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(5.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(IwColors.TextMuted),
        )
        Spacer(modifier = Modifier.width(9.dp))
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            fontSize = 12.5.sp,
            lineHeight = 19.sp,
            color = IwColors.Text,
        )
    }
}

/** 永不执行分组：12 w600 textMuted 小标题 + 芯片流（Dart `_BulletGroup`）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(title: String, items: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.W600, color = IwColors.TextMuted)
        IwGap(6.dp)
        FlowRow {
            items.forEach { item ->
                Text(
                    text = item,
                    modifier = Modifier
                        .padding(end = 6.dp, bottom = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(IwColors.SurfaceHigh)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    fontSize = 11.5.sp,
                    color = IwColors.TextMuted,
                )
            }
        }
    }
}

/** 强调色信息行：8dp 圆点 + 13 w600 标题 + 11.5/1.5 正文（Dart `_InfoRow`）。 */
@Composable
private fun AccentInfoRow(title: String, body: String, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(8.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(accent),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.W600, color = accent)
            IwGap(4.dp)
            Text(text = body, fontSize = 11.5.sp, lineHeight = 17.sp, color = IwColors.TextMuted)
        }
    }
}
