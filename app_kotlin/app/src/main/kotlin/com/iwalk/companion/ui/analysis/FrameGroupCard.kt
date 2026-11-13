package com.iwalk.companion.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iwalk.companion.ui.IwColors
import com.iwalk.core.analysis.FrameGroupStats
import com.iwalk.core.analysis.OffsetStat

/**
 * 一个 (cmd,a1,a2) 帧组的字节统计卡（notes/ui-theme-components.md §4.3）。
 *
 * 只此一份实现：实时分析与离线分析共用，字节表因此不会在两处漂移（笔记 §4 最后一条）。
 * 表头 `off / 取值数 / 范围 / u16le`，列宽权重 0.7 / 1.0 / 1.4 / 1.5，单元格等宽 11。
 */
@Composable
fun FrameGroupCard(group: FrameGroupStats, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Dart `Card(margin: EdgeInsets.only(top: 8))`
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(IwColors.Surface)
            .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = group.key,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.W600,
                color = IwColors.Text,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${group.frames} 帧 · ${group.payloadLength} B",
                fontSize = 11.5.sp,
                color = IwColors.TextMuted,
            )
        }
        if (group.frames < 2) GroupWarning("样本不足，不能据此判断字段")
        if (!group.lengthStable) GroupWarning("payload 长度不一致：可能混有不同消息")
        Spacer(Modifier.height(10.dp))
        GroupTableRow(
            offset = "off",
            distinct = "取值数",
            range = "范围",
            u16le = "u16le",
            header = true,
        )
        group.offsets.forEach { stat -> GroupOffsetRow(stat) }
        if (group.offsets.any { it.constant }) {
            Text(
                text = "C = 当前样本内恒定；它既可能是常量，也可能是样本太少。",
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 10.5.sp,
                color = IwColors.TextMuted,
            )
        }
    }
}

@Composable
private fun GroupWarning(text: String) {
    Row(
        modifier = Modifier.padding(top = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 本 BOM 不透传 material-icons：13dp 圆点占位 warning_amber_rounded
        Spacer(
            Modifier
                .padding(top = 3.dp)
                .size(13.dp)
                .clip(CircleShape)
                .background(IwColors.Warning),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            fontSize = 11.sp,
            lineHeight = 15.4.sp,
            color = IwColors.Warning,
        )
    }
}

@Composable
private fun GroupOffsetRow(stat: OffsetStat) {
    GroupTableRow(
        offset = "${stat.offset}",
        distinct = "${stat.distinct}${if (stat.constant) " C" else ""}",
        range = "${stat.min}..${stat.max}",
        u16le = if (stat.u16leMin == null) "—" else "${stat.u16leMin}..${stat.u16leMax}",
        distinctColor = if (stat.constant) IwColors.TextMuted else IwColors.Primary,
    )
}

@Composable
private fun GroupTableRow(
    offset: String,
    distinct: String,
    range: String,
    u16le: String,
    header: Boolean = false,
    distinctColor: Color? = null,
) {
    val color = if (header) IwColors.TextMuted else IwColors.Text
    Row(modifier = Modifier.fillMaxWidth()) {
        GroupCell(offset, 0.7f, color)
        GroupCell(distinct, 1.0f, distinctColor ?: color)
        GroupCell(range, 1.4f, color)
        GroupCell(u16le, 1.5f, color)
    }
}

@Composable
private fun RowScope.GroupCell(text: String, weight: Float, color: Color) {
    Text(
        text = text,
        modifier = Modifier.weight(weight).padding(horizontal = 2.dp, vertical = 4.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = color,
    )
}
