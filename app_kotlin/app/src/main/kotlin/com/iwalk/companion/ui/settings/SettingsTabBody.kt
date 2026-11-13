package com.iwalk.companion.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iwalk.companion.ui.IwColors
import com.iwalk.companion.ui.secondary.SecondaryPage

/**
 * 设置页签正文（用户正式版 0.4.0）：只保留「只读与安全说明」入口与
 * 会话相关分组（锁车控制、关于与边界）。静态预览、故障参考、数据与诊断、
 * 标定与证据、会话导出等调试入口已移除（标定已随包内置）。
 */
@Composable
fun SettingsTabBody(onOpenPage: (SecondaryPage) -> Unit, controller: com.iwalk.companion.ui.device.DeviceSessionController? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 32.dp),
    ) {
        NavEntry(
            title = "只读与安全说明",
            subtitle = "会做什么、永不执行什么、锁车与隐私边界",
            onClick = { onOpenPage(SecondaryPage.SAFETY) },
        )
        if (controller != null) {
            Spacer(Modifier.height(24.dp))
            SessionSettings(controller)
        }
    }
}

/** Dart 标准导航 ListTile 的 Compose 近似：卡片底 + 图标位圆点 + 标题/副标题 + 右侧箭头。 */
@Composable
private fun NavEntry(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = IwColors.Surface,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 本 BOM 不透传 material-icons：用 21dp 圆形占位点近似 Dart 的 leading Icon(size 21, textMuted)
            Box(
                modifier = Modifier
                    .size(21.dp)
                    .clip(CircleShape)
                    .background(IwColors.TextMuted.copy(alpha = 0.35f)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.W600, color = IwColors.Text)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = IwColors.TextMuted,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(text = "›", fontSize = 20.sp, color = IwColors.TextMuted)
        }
    }
}
