package com.iwalk.companion.ui.reference

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iwalk.companion.ui.IwColors
import com.iwalk.companion.ui.secondary.IwBodyNote
import com.iwalk.companion.ui.secondary.IwCard
import com.iwalk.companion.ui.secondary.IwCardDivider
import com.iwalk.companion.ui.secondary.IwGap
import com.iwalk.companion.ui.secondary.IwLabelValueRow
import com.iwalk.companion.ui.secondary.IwNoteBand
import com.iwalk.companion.ui.secondary.IwSectionHeader
import com.iwalk.companion.ui.secondary.IwSecondaryPage
import com.iwalk.core.reference.MiniRobotReference

/**
 * 本次会话里实际收到的三个原始码。
 *
 * 只承载“车端主动发来的数值”，本工单没有会话，因此默认全空；页面把它和官方短码**并列**展示，
 * 不做任何数值 → 标签的匹配（映射未验证，笔记 §7）。
 *
 * [hasSession] 与 [isDemo] 分开建模，对齐 Dart `session == null` / `transport.isDemo` 两个分支：
 * 有会话但某字段从未出现时显示 `本次会话未观察到`，而不是 `无活动会话`。
 */
data class ReferenceRawValues(
    val hasSession: Boolean = false,
    val isDemo: Boolean = false,
    val workModeRaw: Long? = null,
    val warningCodeRaw: Long? = null,
    val errorCodeRaw: Long? = null,
)

/** 参考页标题，供外壳副标题与测试共用。 */
const val REFERENCE_PAGE_TITLE: String = "故障与术语参考"

private const val DISCLAIMER_BANNER =
    "以下内容来自 MiniRobot 官方资源，不是 iWalk 的诊断结论。" +
        "官方短码是同一条目表里的名字，不保证等于 payload 中的数值错误码；" +
        "数值映射与取码逻辑均未验证。"
private const val RAW_NOTE = "原始值不会自动匹配下面的条目；即使原始值为 0，也不能据此断言“无故障”。"
private const val LONG_FAULT_NOTE = "官方 App 的提示文案，用来理解可能的状态词汇；不能由一条文案反推具体硬件故障。"
private const val FOOTER_NOTE =
    "本页只用于命名与分类。iWalk 不会据此发送故障查询、校准、控制或升级命令；" +
        "车辆实际发来的原始码始终优先于这里的标签。"
private const val NO_SESSION_VALUE = "无活动会话"
private const val NOT_OBSERVED_VALUE = "本次会话未观察到"

/**
 * 故障与术语参考页：三张表（hxz 短码 / 长故障文案 / 术语 / 版本组件）+ 本次会话原始值，
 * 全部是编译期常量，本页不查询、不解析、不写入任何状态。
 *
 * @param onBack 返回设置页；本工单的返回栈由 [com.iwalk.companion.ui.IwApp] 持有。
 * @param rawValues 无会话时传默认值即可，三行都显示 `无活动会话`。
 */
@Composable
fun ReferenceScreen(
    onBack: () -> Unit,
    rawValues: ReferenceRawValues = ReferenceRawValues(),
) {
    IwSecondaryPage(title = REFERENCE_PAGE_TITLE, onBack = onBack) {
        IwNoteBand(
            text = DISCLAIMER_BANNER,
            accent = IwColors.Warning,
            accentBackgroundAlpha = 0.08f,
            withBorder = true,
            iconSize = 18.dp,
        )
        IwGap(8.dp)
        Text(
            text = "来源：${MiniRobotReference.SOURCE_APP} · ${MiniRobotReference.SOURCE_PACKAGE}\n" +
                MiniRobotReference.SOURCE_NOTE,
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = IwColors.TextMuted,
        )
        IwGap(22.dp)

        IwSectionHeader(
            title = "本次会话原始值",
            trailing = when {
                !rawValues.hasSession -> "无会话"
                rawValues.isDemo -> "演示数据"
                else -> "真车通知"
            },
            trailingColor = if (rawValues.hasSession && rawValues.isDemo) IwColors.Demo else IwColors.TextMuted,
        )
        IwGap(4.dp)
        IwBodyNote(RAW_NOTE)
        IwGap(10.dp)
        IwCard {
            val rows = listOf(
                "工作模式" to rawValues.workModeRaw to false,
                "警告码" to rawValues.warningCodeRaw to true,
                "错误码" to rawValues.errorCodeRaw to true,
            )
            rows.forEachIndexed { index, (labelAndRaw, alertOnNonZero) ->
                val (label, raw) = labelAndRaw
                if (index > 0) IwCardDivider()
                IwLabelValueRow(
                    label = label,
                    value = when {
                        !rawValues.hasSession -> NO_SESSION_VALUE
                        raw == null -> NOT_OBSERVED_VALUE
                        else -> "raw $raw · 标定值 ${formatZeroDigits(raw)}"
                    },
                    valueColor = if (rawValues.hasSession && alertOnNonZero && raw != null && raw != 0L) {
                        IwColors.Warning
                    } else {
                        IwColors.TextMuted
                    },
                )
            }
        }
        IwGap(24.dp)

        IwSectionHeader(
            title = "短故障码（hxz）",
            trailing = "${MiniRobotReference.shortFaults.size} 条 · 未映射数值",
        )
        IwGap(10.dp)
        IwCard {
            MiniRobotReference.shortFaults.forEachIndexed { index, code ->
                if (index > 0) IwCardDivider()
                FaultCodeRow(code)
            }
        }
        IwGap(24.dp)

        IwSectionHeader(title = "长故障文案（节选）", trailing = "按域分组")
        IwGap(4.dp)
        IwBodyNote(LONG_FAULT_NOTE)
        IwGap(10.dp)
        IwCard {
            MiniRobotReference.longFaultGroups.forEachIndexed { index, group ->
                if (index > 0) IwCardDivider()
                FaultGroupTile(group)
            }
        }
        IwGap(24.dp)

        IwSectionHeader(title = "状态与术语")
        IwGap(10.dp)
        IwCard {
            MiniRobotReference.terms.forEachIndexed { index, term ->
                if (index > 0) IwCardDivider()
                IwLabelValueRow(
                    label = term.label,
                    value = term.values,
                    valueColor = IwColors.Text,
                    labelWidth = 84.dp,
                )
            }
        }
        IwGap(24.dp)

        IwSectionHeader(title = "版本组件", trailing = "不主动查询")
        IwGap(4.dp)
        IwBodyNote(MiniRobotReference.VERSION_LIMIT)
        IwGap(10.dp)
        IwCard {
            MiniRobotReference.versionComponents.forEachIndexed { index, version ->
                if (index > 0) IwCardDivider()
                IwLabelValueRow(
                    label = version.component,
                    value = version.handling,
                    valueColor = IwColors.Text,
                    labelWidth = 96.dp,
                )
            }
        }
        IwGap(22.dp)
        IwBodyNote(FOOTER_NOTE)
    }
}

/** 短码行：48dp 等宽 code 单元 + 标签 +（可空）备注。 */
@Composable
private fun FaultCodeRow(code: MiniRobotReference.Code) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxWidth(),
    ) {
        Text(
            text = code.code,
            modifier = Modifier.width(48.dp),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = IwColors.Warning,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = code.label, fontSize = 13.sp, fontWeight = FontWeight.W500, color = IwColors.Text)
            if (code.note.isNotEmpty()) {
                Text(
                    text = code.note,
                    modifier = Modifier.padding(top = 3.dp),
                    fontSize = 11.5.sp,
                    color = IwColors.TextMuted,
                )
            }
        }
    }
}

/** 长故障分组：域名 + `N 条`，条目按官方顺序逐行列出（本节静态展开，不做折叠动画）。 */
@Composable
private fun FaultGroupTile(group: MiniRobotReference.Group) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = group.domain,
                modifier = Modifier.weight(1f),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.W500,
                color = IwColors.Text,
            )
            Text(text = "${group.items.size} 条", fontSize = 11.sp, color = IwColors.TextMuted)
        }
        group.items.forEach { item ->
            Text(
                text = "· $item",
                modifier = Modifier.padding(top = 5.dp),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = IwColors.TextMuted,
            )
        }
    }
}

/** 原始码按 0 位小数显示（对齐 Dart `formatFieldValue(value, digits: 0)` 的去尾零规则）。 */
private fun formatZeroDigits(raw: Long): String = raw.toString()
