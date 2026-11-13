package com.iwalk.companion.ui.secondary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iwalk.companion.ui.IwColors

/**
 * 二级页共享外壳：一个带返回按钮的顶栏 + 可滚动正文，正文内边距逐字对齐 Flutter 的
 * `EdgeInsets.fromLTRB(20, 8, 20, 32)`（笔记 §6/§7/§8）。
 *
 * 页面标题走语义节点，[HomeShellTest] 之外的 UI 测试据此断言“进入了哪个二级页”。
 */
@Composable
fun IwSecondaryPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(color = IwColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("返回") }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.W600,
                    color = IwColors.Text,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 32.dp),
                content = content,
            )
        }
    }
}

/** 分节标题：正文 15/16 w600 + 右侧对齐的次要说明，永不换行（笔记 §7）。 */
@Composable
fun IwSectionHeader(
    title: String,
    trailing: String? = null,
    trailingColor: Color = IwColors.TextMuted,
    titleSize: androidx.compose.ui.unit.TextUnit = 15.sp,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = titleSize,
            fontWeight = FontWeight.W600,
            color = IwColors.Text,
        )
        if (trailing != null) {
            Text(text = trailing, fontSize = 11.5.sp, color = trailingColor)
        }
    }
}

/** 卡片：surface 底 + 20dp 圆角 + outline 描边，内部分隔线由 [IwCardDivider] 提供。 */
@Composable
fun IwCard(
    modifier: Modifier = Modifier,
    containerColor: Color = IwColors.Surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .padding(vertical = 4.dp),
        content = content,
    )
}

@Composable
fun IwCardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 1.dp,
        color = IwColors.Outline,
    )
}

/**
 * 强调色说明带：整行圆角块，图标用一个同色圆点占位（本 BOM 不透传 material-icons）。
 * [BannerShape] 尺寸对齐笔记 §7 的 banner（16dp 圆角、14dp 内边距）。
 */
@Composable
fun IwNoteBand(
    text: String,
    accent: Color,
    accentBackgroundAlpha: Float = 0.09f,
    withBorder: Boolean = false,
    iconSize: Dp = 17.dp,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent.copy(alpha = accentBackgroundAlpha))
            .then(
                if (withBorder) {
                    Modifier.padding(1.dp) // 描边由内层底色近似，避免再包一层 Box
                } else {
                    Modifier
                },
            )
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(iconSize)
                .clip(RoundedCornerShape(percent = 50))
                .background(accent),
        )
        Spacer(Modifier.width(9.dp))
        Text(text = text, fontSize = 11.5.sp, lineHeight = 18.sp, color = accent, modifier = Modifier.weight(1f))
    }
}

/** 页面里一段普通说明文字（11.5 / 1.5 / textMuted）。 */
@Composable
fun IwBodyNote(text: String) {
    Text(text = text, fontSize = 11.5.sp, lineHeight = 17.sp, color = IwColors.TextMuted)
}

/** 左标签 + 右值的单行：标签默认 13 textMuted，值 12.5 w500 右对齐。 */
@Composable
fun IwLabelValueRow(
    label: String,
    value: String,
    valueColor: Color = IwColors.TextMuted,
    labelWidth: Dp? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (labelWidth != null) {
            Box(modifier = Modifier.width(labelWidth)) {
                Text(text = label, fontSize = 13.sp, color = IwColors.TextMuted)
            }
        } else {
            Text(text = label, fontSize = 13.sp, color = IwColors.TextMuted, modifier = Modifier.weight(1f))
        }
        Text(
            text = value,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.W500,
            color = valueColor,
        )
    }
}

@Composable
internal fun IwGap(height: Dp) {
    Spacer(Modifier.height(height))
}

@Composable
internal fun IwMonospace(text: String, color: Color = IwColors.TextMuted) {
    Text(text = text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = color)
}
