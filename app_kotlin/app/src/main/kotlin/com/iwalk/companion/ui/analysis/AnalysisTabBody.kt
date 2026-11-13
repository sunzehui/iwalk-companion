package com.iwalk.companion.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iwalk.companion.ui.IwColors
import com.iwalk.companion.ui.device.DeviceUiState
import com.iwalk.companion.ui.device.digitsOf
import com.iwalk.companion.ui.device.formatFieldValue
import com.iwalk.companion.ui.device.labelOf
import com.iwalk.companion.ui.device.unitOf
import com.iwalk.core.analysis.analyseFrames
import com.iwalk.core.analysis.offsetReportText
import com.iwalk.core.decode.FieldValue
import com.iwalk.core.model.TelemetryField
import com.iwalk.core.session.EvidenceEvent
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 分析页签正文（notes/ui-analysis.md §1 / §2）。
 *
 * 两段 `SegmentedButton`（实时分析 / 离线分析）。离线段由工单 06 接入，这里只留一行说明；
 * 实时段是工单 05 的正文：汇总条、复制入口、证据时间线、字段字典、故障参考、帧偏移组卡、
 * 原始通知与安全边界。
 *
 * 会话状态由 [com.iwalk.companion.ui.device.DeviceSessionController] 单一持有，本页只读传入的
 * [DeviceUiState]，不创建任何平行状态；空会话时**完整渲染零值**，不是“请先连接”占位（§1 末）。
 */

private enum class AnalysisSource { live, offline }

// —— 逐字文案（笔记 §1/§2；测试里各抄一份字面量，任何漂移都会红）——
const val SEGMENT_LIVE = "实时分析"
const val SEGMENT_OFFLINE = "离线分析"
const val CAPTION_LIVE = "实时会话的帧统计与原始通知；技术数据只在需要时展开。"
const val CAPTION_OFFLINE = "离线解析其它工具抓到的 hex，不连接蓝牙。"

const val SUMMARY_LABEL_FRAMES = "帧"
const val SUMMARY_LABEL_GROUPS = "帧组"
const val SUMMARY_LABEL_UNCALIBRATED = "未标定帧"

const val ACTION_COPY_RAW = "复制原始记录"
const val ACTION_COPY_OFFSETS = "复制偏移分析"
const val ACTION_EXPORT = "导出会话"
const val ACTION_EXPORTING = "正在导出…"

const val HEADING_TIMELINE = "证据时间线"
const val TIMELINE_WAITING = "等待通知"
const val TIMELINE_NOTE = "只记录车辆主动发来的帧及当时解码结果；不向车辆查询黑匣子。"
const val TIMELINE_EMPTY = "还没有收到通知。连接真车或启动演示后，每帧会在这里出现。"
const val TIMELINE_FOOTNOTE =
    "仅显示最近 12 条；完整时间线随“导出会话”写入 evidence_timeline。非零原始码会标出，但 0 不代表已验证“无故障”。"
/** 时间线撞上 2000 条上限时的脚注：不能再说「完整时间线随导出」，因为导出的也是截断版本。 */
const val TIMELINE_FOOTNOTE_TRUNCATED =
    "仅显示最近 12 条；时间线已达上限，导出时 evidence_timeline 也不含更早条目（帧统计与原始通知仍为全量）。"
/** 时间线撞上限的提示正文；条数与帧数由调用方传入，不做估算。 */
fun timelineTruncatedNote(kept: Int, frames: Int): String =
    "时间线已达上限：只保留最近 $kept 条，已收 $frames 帧中更早的条目只剩帧统计与原始通知里有；" +
        "导出时 evidence_timeline 同样已截断。"
const val TIMELINE_UNCALIBRATED = "未标定帧组 · 原始字节仍保留"

const val TITLE_DICTIONARY = "字段字典"
const val STATUS_OBSERVED = "已观察"
const val STATUS_WAITING = "等待匹配帧"
const val STATUS_UNMAPPED = "未标定"
const val DETAIL_UNMAPPED = "字段位置未经验证；不会猜测数值"
const val DETAIL_NOT_SEEN_SUFFIX = "尚未收到匹配帧"

const val TITLE_FAULT_REFERENCE = "故障 / 警告参考"
const val FAULT_SUBTITLE_NONE = "本次未观察到告警字段 · 官方短码与长文案仅作参考"
const val FAULT_SUBTITLE_SUFFIX = "数值↔标签映射未验证"

const val HEADING_OFFSETS = "帧偏移分析"
const val OFFSETS_TRAILING_READY = "按 CMD / A1 / A2 分组"
const val OFFSETS_TRAILING_WAITING = "等待数据"
const val OFFSETS_NOTE =
    "统计 payload 每个偏移的取值；样本 <2 时“恒定”不构成字段证据。单位仍需与官方界面读数对照（U03）。"
const val EMPTY_GROUP_TITLE = "暂无帧组"
const val EMPTY_GROUP_BODY = "开始演示或连接真车后，这里按 cmd/a1/a2 展示字节统计。"

const val TITLE_RAW = "原始通知（最近 100 条）"
const val RAW_EMPTY_SUBTITLE = "暂无数据"
const val RAW_EMPTY_BODY = "通知到达时按顺序记录，不做去重或插值。"

const val SAFETY_NOTE = "安全边界：本页只分析已收通知；真车遥测使用两条固定查询。锁车需在设置中开启，无校准或升级入口。"

/** 汇总条数值的测试锚点（可见文案保持不变）。 */
internal const val SUMMARY_TAG_FRAMES = "analysis-summary-frames"
internal const val SUMMARY_TAG_GROUPS = "analysis-summary-groups"
internal const val SUMMARY_TAG_UNCALIBRATED = "analysis-summary-uncalibrated"

/** 时间线 / 原始通知的显示上限（§2.5、§2.10）。 */
private const val TIMELINE_LIMIT = 12
private const val RAW_LINES_LIMIT = 100

@Composable
fun AnalysisTabBody(
    state: DeviceUiState,
    onOpenReference: () -> Unit,
    /** Dart `_exporting`：导出在途时第三张动作卡换成「正在导出…」并禁用。 */
    exporting: Boolean = false,
    /** 导出回调（挂起）；返回 SnackBar 文案，空串静默。 */
    onExportSession: (suspend () -> String)? = null,
) {
    var source by remember { mutableStateOf(AnalysisSource.live) }
    // 实时视图除展开卡外没有自有状态：展开态提到宿主，切换分段时不丢（对齐 IndexedStack）
    var dictionaryExpanded by remember { mutableStateOf(false) }
    var rawExpanded by remember { mutableStateOf(false) }
    // 离线段的自有状态提到宿主：切换分段时输入、解析结果与对照结果都保留（笔记 §1 第 3 项）
    val offlineState = remember { OfflineAnalysisState() }
    val focusManager = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 2.dp),
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = source == AnalysisSource.live,
                        // 切段前清焦点：离线段的输入框键盘不能粘在实时段上（§1 第 1 项）
                        onClick = {
                            focusManager.clearFocus()
                            source = AnalysisSource.live
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        colors = segmentColors(),
                        label = {
                            Text(SEGMENT_LIVE, fontSize = 13.sp, fontWeight = FontWeight.W600)
                        },
                    )
                    SegmentedButton(
                        selected = source == AnalysisSource.offline,
                        onClick = {
                            focusManager.clearFocus()
                            source = AnalysisSource.offline
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        colors = segmentColors(),
                        label = {
                            Text(SEGMENT_OFFLINE, fontSize = 13.sp, fontWeight = FontWeight.W600)
                        },
                    )
                }
            }
            Text(
                text = if (source == AnalysisSource.live) CAPTION_LIVE else CAPTION_OFFLINE,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 8.dp, end = 20.dp),
                fontSize = 11.5.sp,
                color = IwColors.TextMuted,
            )
            Box(modifier = Modifier.weight(1f)) {
                when (source) {
                    AnalysisSource.live -> LiveAnalysisView(
                        state = state,
                        dictionaryExpanded = dictionaryExpanded,
                        onDictionaryExpandedChange = { dictionaryExpanded = it },
                        rawExpanded = rawExpanded,
                        onRawExpandedChange = { rawExpanded = it },
                        snackbar = snackbar,
                        onOpenReference = onOpenReference,
                        exporting = exporting,
                        onExportSession = onExportSession,
                    )
                    AnalysisSource.offline -> OfflineAnalysisView(
                        state = offlineState,
                        snackbar = snackbar,
                    )
                }
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun segmentColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = IwColors.Primary.copy(alpha = 0.16f),
    activeContentColor = IwColors.Primary,
    inactiveContainerColor = Color.Transparent,
    inactiveContentColor = IwColors.TextMuted,
)

// —————————————————————————— 实时分析 ——————————————————————————

@Composable
private fun LiveAnalysisView(
    state: DeviceUiState,
    dictionaryExpanded: Boolean,
    onDictionaryExpandedChange: (Boolean) -> Unit,
    rawExpanded: Boolean,
    onRawExpandedChange: (Boolean) -> Unit,
    snackbar: SnackbarHostState,
    onOpenReference: () -> Unit,
    exporting: Boolean = false,
    onExportSession: (suspend () -> String)? = null,
) {
    val groups = remember(state.frames) { analyseFrames(state.frames) }
    val events = state.evidenceEvents
    val lines = state.rawLines
    val hasData = lines.isNotEmpty() || groups.isNotEmpty()
    // 会话自己记录是否裁过时间线（超出 2000 条时丢最旧 500）；不做帧数反推。
    val timelineTruncated = state.timelineTruncated
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 28.dp),
    ) {
        SummaryStrip(
            frames = state.snapshot.framesDecoded,
            groups = groups.size,
            uncalibrated = state.snapshot.uncalibratedFrames,
        )
        Spacer(Modifier.height(14.dp))
        ActionGrid(
            copyRawEnabled = lines.isNotEmpty(),
            copyOffsetsEnabled = groups.isNotEmpty(),
            onCopyRaw = {
                clipboard.setText(AnnotatedString(lines.joinToString("\n")))
                val message = "已复制 ${lines.size} 条原始通知"
                scope.launch { snackbar.showSnackbar(message) }
            },
            onCopyOffsets = {
                clipboard.setText(AnnotatedString(offsetReportText(groups, state.sessionId)))
                scope.launch { snackbar.showSnackbar("偏移分析已复制") }
            },
            exportLabel = if (exporting) ACTION_EXPORTING else ACTION_EXPORT,
            exportEnabled = state.active && !exporting && onExportSession != null,
            onExport = {
                val callback = onExportSession ?: return@ActionGrid
                scope.launch {
                    val message = callback()
                    if (message.isNotEmpty()) snackbar.showSnackbar(message)
                }
            },
        )
        Spacer(Modifier.height(18.dp))

        SectionHeading(
            title = HEADING_TIMELINE,
            trailing = if (events.isEmpty()) TIMELINE_WAITING else "最近 ${minOf(events.size, TIMELINE_LIMIT)} 条",
        )
        Spacer(Modifier.height(4.dp))
        Text(TIMELINE_NOTE, fontSize = 11.5.sp, lineHeight = 17.sp, color = IwColors.TextMuted)
        if (timelineTruncated) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = timelineTruncatedNote(events.size, state.snapshot.framesDecoded),
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
                color = IwColors.Warning,
            )
        }
        Spacer(Modifier.height(10.dp))
        TimelineCard(events = events, truncated = timelineTruncated)
        Spacer(Modifier.height(20.dp))

        DictionaryCard(
            state = state,
            expanded = dictionaryExpanded,
            onExpandedChange = onDictionaryExpandedChange,
        )
        Spacer(Modifier.height(10.dp))

        FaultReferenceCard(state = state, onOpenReference = onOpenReference)
        Spacer(Modifier.height(20.dp))

        SectionHeading(
            title = HEADING_OFFSETS,
            trailing = if (hasData) OFFSETS_TRAILING_READY else OFFSETS_TRAILING_WAITING,
        )
        Spacer(Modifier.height(6.dp))
        Text(OFFSETS_NOTE, fontSize = 11.5.sp, lineHeight = 17.sp, color = IwColors.TextMuted)
        Spacer(Modifier.height(8.dp))
        if (groups.isEmpty()) {
            EmptyCard()
        } else {
            groups.forEach { group -> FrameGroupCard(group) }
        }
        Spacer(Modifier.height(16.dp))

        RawNotificationsCard(
            lines = lines,
            expanded = rawExpanded,
            onExpandedChange = onRawExpandedChange,
        )
        Spacer(Modifier.height(20.dp))
        SafetyNote()
    }
}

// —— §2.1 汇总条 ——

@Composable
private fun SummaryStrip(frames: Int, groups: Int, uncalibrated: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = IwColors.Surface),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            SummaryCell("$frames", SUMMARY_LABEL_FRAMES, SUMMARY_TAG_FRAMES, Modifier.weight(1f))
            VerticalRule()
            SummaryCell("$groups", SUMMARY_LABEL_GROUPS, SUMMARY_TAG_GROUPS, Modifier.weight(1f))
            VerticalRule()
            SummaryCell("$uncalibrated", SUMMARY_LABEL_UNCALIBRATED, SUMMARY_TAG_UNCALIBRATED, Modifier.weight(1f))
        }
    }
}

@Composable
private fun VerticalRule() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(IwColors.Outline),
    )
}

@Composable
private fun SummaryCell(
    value: String,
    label: String,
    valueTag: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = value,
            modifier = Modifier.testTag(valueTag),
            fontSize = 26.sp,
            fontWeight = FontWeight.W500,
            lineHeight = 29.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFeatureSettings = "tnum"),
            color = IwColors.Text,
        )
        Spacer(Modifier.height(5.dp))
        Text(label, fontSize = 11.5.sp, color = IwColors.TextMuted)
    }
}

// —— §2.2 动作网格 ——

@Composable
private fun ActionGrid(
    copyRawEnabled: Boolean,
    copyOffsetsEnabled: Boolean,
    onCopyRaw: () -> Unit,
    onCopyOffsets: () -> Unit,
    exportLabel: String,
    exportEnabled: Boolean,
    onExport: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        ActionCard(ACTION_COPY_RAW, copyRawEnabled, Modifier.weight(1f), onCopyRaw)
        Spacer(Modifier.width(10.dp))
        ActionCard(ACTION_COPY_OFFSETS, copyOffsetsEnabled, Modifier.weight(1f), onCopyOffsets)
        Spacer(Modifier.width(10.dp))
        ActionCard(exportLabel, exportEnabled, Modifier.weight(1f), onExport)
    }
}

/**
 * 动作卡：图标位用同色圆点占位（本 BOM 不透传 material-icons）。禁用时整卡 0.55、
 * 前景 textMuted α0.4、无点击（§2.2）。
 */
@Composable
private fun ActionCard(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = if (enabled) IwColors.Text else IwColors.TextMuted.copy(alpha = 0.4f)
    Box(modifier = modifier.alpha(if (enabled) 1f else 0.55f)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(IwColors.Surface)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.45f)),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = label,
                fontSize = 11.5.sp,
                lineHeight = 14.5.sp,
                fontWeight = FontWeight.W500,
                color = color,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// —— §2.3–§2.5 证据时间线 ——

@Composable
private fun SectionHeading(title: String, trailing: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            fontWeight = FontWeight.W600,
            color = IwColors.Text,
        )
        Text(text = trailing, fontSize = 11.5.sp, color = IwColors.TextMuted)
    }
}

@Composable
private fun TimelineCard(events: List<EvidenceEvent>, truncated: Boolean = false) {
    val recent = events.asReversed().take(TIMELINE_LIMIT)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = IwColors.Surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 10.dp),
        ) {
            if (recent.isEmpty()) {
                Text(TIMELINE_EMPTY, fontSize = 12.sp, lineHeight = 18.sp, color = IwColors.TextMuted)
            } else {
                recent.forEachIndexed { index, event ->
                    if (index > 0) TimelineDivider()
                    EvidenceRow(event)
                }
                TimelineDivider()
                Text(
                    text = if (truncated) TIMELINE_FOOTNOTE_TRUNCATED else TIMELINE_FOOTNOTE,
                    fontSize = 10.5.sp,
                    lineHeight = 15.2.sp,
                    color = IwColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun TimelineDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        HorizontalDivider(thickness = 1.dp, color = IwColors.Outline)
    }
}

@Composable
private fun EvidenceRow(event: EvidenceEvent) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = clockOf(event.at),
            modifier = Modifier.width(58.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            style = TextStyle(fontFeatureSettings = "tnum"),
            color = IwColors.TextMuted,
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.groupKey,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = if (event.calibrated) IwColors.TextMuted else IwColors.Warning,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = evidenceSummary(event),
                fontSize = 12.sp,
                lineHeight = 16.8.sp,
                color = IwColors.Text,
            )
            val codes = evidenceCodes(event)
            if (codes.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(13.dp)
                            .clip(CircleShape)
                            .background(IwColors.Warning),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(codes, fontSize = 11.sp, color = IwColors.Warning)
                }
            }
        }
    }
}

private fun evidenceSummary(event: EvidenceEvent): String {
    if (event.values.isEmpty()) return TIMELINE_UNCALIBRATED
    val shown = event.values.entries.take(3).joinToString(" · ") { (field, value) ->
        val unit = unitOf(field)
        "${labelOf(field)} ${formatFieldValue(value.value, digitsOf(field))}" + if (unit.isEmpty()) "" else " $unit"
    }
    val hidden = event.values.size - 3
    return shown + if (hidden > 0) " · +$hidden 字段" else ""
}

private fun evidenceCodes(event: EvidenceEvent): String {
    val parts = mutableListOf<String>()
    for (field in listOf(TelemetryField.warningCode, TelemetryField.errorCode)) {
        val raw = event.rawOf(field) ?: continue
        if (raw != 0L) parts += "${labelOf(field)} raw $raw"
    }
    return parts.joinToString(" · ")
}

private fun clockOf(at: Instant): String {
    val local = at.atZone(java.time.ZoneId.systemDefault())
    return "${local.hour.toString().padStart(2, '0')}:" +
        "${local.minute.toString().padStart(2, '0')}:" +
        local.second.toString().padStart(2, '0')
}

// —— §2.6 字段字典 ——

/** 12 个可标定字段（`TelemetryField` 去掉兜底 `unknown`），顺序即枚举顺序。 */
internal val DICTIONARY_FIELDS: List<TelemetryField> =
    TelemetryField.entries.filter { it != TelemetryField.unknown }

@Composable
private fun DictionaryCard(
    state: DeviceUiState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val observed = DICTIONARY_FIELDS.count { state.snapshot.values.containsKey(it) }
    ExpandableCard(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        title = TITLE_DICTIONARY,
        titleWeight = FontWeight.W600,
        subtitle = "本次已观察 $observed / ${DICTIONARY_FIELDS.size} 个字段 · 未出现不等于不支持",
        childrenPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
    ) {
        DICTIONARY_FIELDS.forEach { field ->
            DictionaryRow(
                field = field,
                value = state.snapshot.values[field],
                mapping = state.snapshot.values[field]?.mapping ?: state.calibration.mappingFor(field),
            )
        }
    }
}

@Composable
private fun DictionaryRow(field: TelemetryField, value: FieldValue?, mapping: com.iwalk.core.model.FieldMapping?) {
    val status = when {
        value != null -> STATUS_OBSERVED
        mapping != null -> STATUS_WAITING
        else -> STATUS_UNMAPPED
    }
    val statusColor = when {
        value != null -> IwColors.Success
        mapping != null -> IwColors.Warning
        else -> IwColors.TextMuted
    }
    val detail = when {
        value != null -> {
            val unit = unitOf(field)
            "${formatFieldValue(value.value, digitsOf(field))}" +
                (if (unit.isEmpty()) "" else " $unit") +
                " · raw ${formatFieldValue(value.raw, 0)}" +
                " · ${mapping?.type?.name} @ ${mapping?.offset}"
        }
        mapping != null -> "${mapping.type.name} @ ${mapping.offset} · $DETAIL_NOT_SEEN_SUFFIX"
        else -> DETAIL_UNMAPPED
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(76.dp)) {
            Text(
                text = labelOf(field),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.W500,
                color = IwColors.Text,
            )
        }
        Text(
            text = detail,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            color = IwColors.TextMuted,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = status,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.W600,
            color = statusColor,
        )
    }
}

// —— §2.7 故障 / 警告参考 ——

@Composable
private fun FaultReferenceCard(state: DeviceUiState, onOpenReference: () -> Unit) {
    val raws = buildList {
        state.snapshot.values[TelemetryField.warningCode]?.let {
            add("${labelOf(TelemetryField.warningCode)} raw ${formatFieldValue(it.raw, 0)}")
        }
        state.snapshot.values[TelemetryField.errorCode]?.let {
            add("${labelOf(TelemetryField.errorCode)} raw ${formatFieldValue(it.raw, 0)}")
        }
    }
    val subtitle = if (raws.isEmpty()) {
        FAULT_SUBTITLE_NONE
    } else {
        raws.joinToString(" · ") + " · " + FAULT_SUBTITLE_SUFFIX
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = IwColors.Surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenReference)
                .padding(start = 16.dp, top = 4.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(21.dp, IwColors.Warning)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(
                    text = TITLE_FAULT_REFERENCE,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W600,
                    color = IwColors.Text,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = IwColors.TextMuted,
                )
            }
            Spacer(Modifier.width(8.dp))
            // chevron_right 用等宽字形占位
            Text("›", fontSize = 20.sp, color = IwColors.TextMuted)
        }
    }
}

// —— §2.9 空卡片 / §2.10 原始通知 / §2.11 安全边界 ——

@Composable
private fun EmptyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = IwColors.Surface),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.Top) {
            Dot(22.dp, IwColors.TextMuted)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(EMPTY_GROUP_TITLE, fontSize = 14.sp, fontWeight = FontWeight.W600, color = IwColors.Text)
                Spacer(Modifier.height(4.dp))
                Text(EMPTY_GROUP_BODY, fontSize = 12.sp, lineHeight = 18.sp, color = IwColors.TextMuted)
            }
        }
    }
}

@Composable
private fun RawNotificationsCard(
    lines: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    ExpandableCard(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        title = TITLE_RAW,
        titleWeight = FontWeight.W500,
        subtitle = if (lines.isEmpty()) RAW_EMPTY_SUBTITLE else "共 ${lines.size} 条",
        childrenPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        if (lines.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Text(RAW_EMPTY_BODY, fontSize = 12.sp, color = IwColors.TextMuted)
            }
        } else {
            SelectionContainer {
                Text(
                    text = lines.takeLast(RAW_LINES_LIMIT).joinToString("\n"),
                    modifier = Modifier.fillMaxWidth(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.5.sp,
                    color = IwColors.Text,
                )
            }
        }
    }
}

/**
 * 可展开卡片（Dart `ExpansionTile` 的等价物；Compose Material3 没有该组件）。
 *
 * 头部整行可点：左侧图标位 + 标题/副标题 + 右侧折叠指示；展开内容按 [childrenPadding] 缩进。
 */
@Composable
private fun ExpandableCard(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    title: String,
    titleWeight: FontWeight,
    subtitle: String,
    childrenPadding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = IwColors.Surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(20.dp, IwColors.TextMuted)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontSize = 14.sp, fontWeight = titleWeight, color = IwColors.Text)
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, fontSize = 11.5.sp, color = IwColors.TextMuted)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (expanded) "▴" else "▾", fontSize = 14.sp, color = IwColors.TextMuted)
            }
            if (expanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(childrenPadding), content = content)
            }
        }
    }
}

@Composable
private fun SafetyNote() {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        // shield_outlined 用警示字符占位（与设备页安全说明同口径）
        Text("⛨", fontSize = 15.sp, color = IwColors.TextMuted)
        Spacer(Modifier.width(7.dp))
        Text(
            text = SAFETY_NOTE,
            modifier = Modifier.weight(1f),
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            color = IwColors.TextMuted,
        )
    }
}

/** 图标位占位：一个纯色圆点（本 BOM 不透传 material-icons）。 */
@Composable
private fun Dot(size: Dp, color: Color) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}
