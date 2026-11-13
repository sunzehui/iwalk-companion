package com.iwalk.companion.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iwalk.companion.ui.IwColors
import com.iwalk.companion.ui.secondary.IwCard
import com.iwalk.companion.ui.secondary.IwCardDivider
import com.iwalk.companion.ui.secondary.IwGap
import com.iwalk.companion.ui.secondary.IwSectionHeader
import com.iwalk.companion.ui.secondary.IwSecondaryPage

/**
 * 静态界面预览页（笔记 §8；Dart `features/preview/static_preview_page.dart` 全树 `const`）：
 * 示例速度/指标/原始码与配置控件**只是外观**——没有任何 `onClick`/`onValueChange`、
 * 不读写会话、不写入状态；唯一交互是返回按钮。
 *
 * 开关/滑块/模式芯片用手绘图元而非 `Switch`/`Slider`（后者强制回调）；
 * 开关仅带 Dart 同款语义节点 `静态占位开关`（toggled 状态），不暴露点击动作。
 */
@Composable
fun StaticPreviewScreen(onBack: () -> Unit) {
    IwSecondaryPage(title = "界面预览", onBack = onBack) {
        PreviewBanner()
        IwGap(24.dp)

        IwSectionHeader(title = "车辆数据", trailing = "示例", titleSize = 13.sp)
        IwGap(10.dp)
        MockTelemetryCard()
        IwGap(10.dp)
        Footnote("示例数值只用于确认信息层级，不代表任何真实车辆读数。")
        IwGap(26.dp)

        IwSectionHeader(title = "车辆配置", trailing = "静态占位", titleSize = 13.sp)
        IwGap(10.dp)
        MockConfigCard()
        IwGap(10.dp)
        Footnote(
            "控件不可操作，不会发送 BLE 帧，也不会改变车辆。" +
                "正式实现配置写入前，必须先采集官方 App 的对应出站帧，完成协议与安全验证。",
        )
        IwGap(16.dp)
        OutOfScopeNote()
    }
}

/** 顶部示例横幅：demo α0.10 底 + demo α0.35 描边（Dart `_PreviewBanner`）。 */
@Composable
private fun PreviewBanner() {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(IwColors.Demo.copy(alpha = 0.10f))
            .border(1.dp, IwColors.Demo.copy(alpha = 0.35f), shape)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(IwColors.Demo),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "静态界面预览", fontSize = 13.sp, fontWeight = FontWeight.W600, color = IwColors.Demo)
            IwGap(4.dp)
            Text(
                text = "以下车辆数据为示例，配置控件为静态占位；本页不连接车辆、不读取数据、不发送任何指令。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = IwColors.Text.copy(alpha = 0.82f),
            )
        }
    }
}

/** 示例遥测卡（Dart `_MockTelemetryCard`，数值为 static_preview_page.dart:147-239 的字面量）。 */
@Composable
private fun MockTelemetryCard() {
    IwCard {
        Column(modifier = Modifier.padding(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(IwColors.SurfaceHigh),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "MiniRobot · …9887", fontSize = 14.sp, fontWeight = FontWeight.W600, color = IwColors.Text)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = "G1 · 示例链路", fontSize = 11.5.sp, color = IwColors.TextMuted)
                }
                DotPill(label = "已连接（示例）", color = IwColors.Success)
            }
            IwGap(18.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "当前速度", fontSize = 12.sp, color = IwColors.TextMuted)
                Spacer(modifier = Modifier.weight(1f))
                DotPill(label = "示例数据", color = IwColors.Demo)
            }
            IwGap(6.dp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(text = "24.6", fontSize = 72.sp, fontWeight = FontWeight.W300, color = IwColors.Text)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "km/h",
                    modifier = Modifier.padding(bottom = 8.dp),
                    fontSize = 14.sp,
                    color = IwColors.TextMuted,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "更新于 1 秒前 · 12 帧/秒 · 示例", fontSize = 12.sp, color = IwColors.TextMuted)
            IwGap(18.dp)
            Row {
                MockMetric(value = "86", unit = "%", label = "电量", modifier = Modifier.weight(1f))
                MetricDivider()
                MockMetric(value = "31", unit = "°C", label = "温度", modifier = Modifier.weight(1f))
                MetricDivider()
                MockMetric(value = "12.4", unit = "km", label = "总里程", modifier = Modifier.weight(1f))
            }
            IwGap(16.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RawPill(label = "工作模式 raw 1")
                RawPill(label = "警告码 raw 0 · 未收录")
            }
        }
    }
}

@Composable
private fun MockMetric(value: String, unit: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = value, fontSize = 25.sp, fontWeight = FontWeight.W500, color = IwColors.Text)
            Spacer(modifier = Modifier.width(3.dp))
            Text(text = unit, fontSize = 11.5.sp, color = IwColors.TextMuted)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, fontSize = 11.5.sp, color = IwColors.TextMuted)
    }
}

@Composable
private fun MetricDivider() {
    Box(
        modifier = Modifier
            .padding(end = 14.dp)
            .width(1.dp)
            .height(34.dp)
            .background(IwColors.Outline),
    )
}

@Composable
private fun RawPill(label: String) {
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(IwColors.SurfaceHigh)
            .border(1.dp, IwColors.Outline, RoundedCornerShape(percent = 50))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = IwColors.TextMuted,
    )
}

@Composable
private fun DotPill(label: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.W500, color = color)
    }
}

/** 示例配置卡（Dart `_MockConfigCard`）：行内容只是外观，末尾控件无回调。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MockConfigCard() {
    IwCard {
        ConfigRow(title = "前灯", subtitle = "照明") { StaticSwitch(value = true) }
        IwCardDivider()
        ConfigRow(title = "尾灯", subtitle = "制动与夜间提示") { StaticSwitch(value = true) }
        IwCardDivider()
        ConfigRow(title = "氛围灯", subtitle = "灯效与颜色") { StaticSwitch(value = false) }
        IwCardDivider()
        ConfigRow(title = "驾驶模式", subtitle = "示例：普通") { ModePills() }
        IwCardDivider()
        ConfigRow(title = "限速", subtitle = "示例：已开启 18 km/h") { ValueTag(label = "18 km/h") }
        Box(modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 12.dp)) {
            StaticSlider(value = 0.55f)
        }
        IwCardDivider()
        ConfigRow(title = "定速巡航", subtitle = "示例：关闭") { StaticSwitch(value = false) }
        IwCardDivider()
        ConfigRow(title = "能量回收", subtitle = "示例：中") { ValueTag(label = "中") }
    }
}

@Composable
private fun ConfigRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 本 BOM 不透传 material-icons：用 19dp 圆形占位点近似 Dart 的图标位
        Box(
            modifier = Modifier
                .size(19.dp)
                .clip(CircleShape)
                .background(IwColors.TextMuted.copy(alpha = 0.35f)),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.W500, color = IwColors.Text)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = subtitle, fontSize = 11.5.sp, color = IwColors.TextMuted)
        }
        Spacer(modifier = Modifier.width(12.dp))
        trailing()
    }
}

/** 手绘静态开关：无点击动作；仅保留 Dart 的语义节点 `静态占位开关`（toggled 值）。 */
@Composable
private fun StaticSwitch(value: Boolean) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = Modifier
            .semantics {
                contentDescription = "静态占位开关"
                toggleableState = if (value) ToggleableState.On else ToggleableState.Off
            }
            .size(width = 42.dp, height = 24.dp)
            .clip(shape)
            .background(if (value) IwColors.Primary else IwColors.SurfaceHigh)
            .border(1.dp, if (value) IwColors.Primary else IwColors.Outline, shape)
            .padding(3.dp),
    ) {
        Box(
            modifier = Modifier
                .align(if (value) Alignment.CenterEnd else Alignment.CenterStart)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (value) IwColors.OnPrimary else IwColors.TextMuted),
        )
    }
}

/** 静态驾驶模式芯片：舒适 / 普通（选中）/ 运动，无选择回调。 */
@Composable
private fun ModePills() {
    Row {
        ModePill(label = "舒适", selected = false)
        Spacer(modifier = Modifier.width(4.dp))
        ModePill(label = "普通", selected = true)
        Spacer(modifier = Modifier.width(4.dp))
        ModePill(label = "运动", selected = false)
    }
}

@Composable
private fun ModePill(label: String, selected: Boolean) {
    val shape = RoundedCornerShape(9.dp)
    Text(
        text = label,
        modifier = Modifier
            .clip(shape)
            .then(
                if (selected) {
                    Modifier.background(IwColors.Primary.copy(alpha = 0.16f))
                } else {
                    Modifier
                },
            )
            .border(1.dp, if (selected) IwColors.Primary else IwColors.Outline, shape)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.W600,
        color = if (selected) IwColors.Primary else IwColors.TextMuted,
    )
}

/** 静态滑块：4dp 轨道 + 55% 填充 + 圆点，值固定，不可拖动（几何照 Dart `_StaticSlider`）。 */
@Composable
private fun StaticSlider(value: Float) {
    val clamped = value.coerceIn(0f, 1f)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp),
    ) {
        Box(
            modifier = Modifier
                .offset(y = 7.dp)
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(IwColors.Outline),
        )
        Box(
            modifier = Modifier
                .offset(y = 7.dp)
                .fillMaxWidth(fraction = clamped)
                .height(4.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(IwColors.Primary),
        )
        Box(
            modifier = Modifier
                .offset(x = (maxWidth - 14.dp) * clamped, y = 2.dp)
                .size(14.dp)
                .clip(CircleShape)
                .background(IwColors.Primary),
        )
    }
}

@Composable
private fun ValueTag(label: String) {
    Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.W600, color = IwColors.TextMuted)
}

@Composable
private fun Footnote(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 2.dp),
        fontSize = 11.5.sp,
        lineHeight = 17.sp,
        color = IwColors.TextMuted,
    )
}

/** 页脚未包含清单（Dart `_OutOfScopeNote`）。 */
@Composable
private fun OutOfScopeNote() {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(IwColors.SurfaceHigh)
            .border(1.dp, IwColors.Outline, shape)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(17.dp)
                .clip(CircleShape)
                .background(IwColors.TextMuted),
        )
        Spacer(modifier = Modifier.width(9.dp))
        Text(
            text = "未包含：车辆校准、恢复出厂、OTA、账户、NFC、遥控、地图、头盔。" +
                "当前只保留只读信息层与默认关闭的实验性锁车。",
            modifier = Modifier.weight(1f),
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            color = IwColors.TextMuted,
        )
    }
}
