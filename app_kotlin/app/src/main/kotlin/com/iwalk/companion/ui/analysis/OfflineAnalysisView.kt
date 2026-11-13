package com.iwalk.companion.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iwalk.companion.ui.IwColors
import com.iwalk.core.analysis.CaptureComparison
import com.iwalk.core.analysis.FrameGroupStats
import com.iwalk.core.analysis.GroupDiff
import com.iwalk.core.analysis.OffsetDiff
import com.iwalk.core.analysis.OffsetStat
import com.iwalk.core.analysis.ReplayResult
import com.iwalk.core.analysis.analyseFrames
import com.iwalk.core.analysis.compareCaptures
import com.iwalk.core.analysis.comparisonReport
import com.iwalk.core.analysis.offlineReportText
import com.iwalk.core.analysis.replayHex
import kotlinx.coroutines.launch

/**
 * 离线分析视图（notes/ui-analysis.md §3，逐条对齐 `app_v2/lib/features/replay/replay_page.dart`）。
 *
 * 纯离线：不读取 BLE 状态、不创建会话、不出现任何语义字段名。它把粘贴的线上字节交给
 * `:core` 的 [replayHex]，把校验失败块与不完整尾巴都显示出来；对照只描述字节分布。
 *
 * 状态由宿主持有（[OfflineAnalysisState]）：切换实时/离线分段时输入、解析结果与对照
 * 结果都不会被销毁（笔记 §1 的 IndexedStack 语义）。
 */

// —— 逐字文案（笔记 §3；测试里各抄一份字面量，任何漂移都会红）——
const val OFFLINE_INTRO =
    "粘贴通知 hex，每行一包，可带空格、冒号或“rx:”前缀。用于分析其它工具抓到的数据，不连接车辆。也可以再粘贴一份采集 B 做逐偏移字节对照。"
const val OFFLINE_HINT_A = "55aa...\n55aa..."
const val OFFLINE_HINT_B = "第二份通知 hex\n55aa..."
const val OFFLINE_PARSE = "解析"
const val OFFLINE_PASTE = "粘贴"
const val OFFLINE_CLEAR = "清空输入"
const val OFFLINE_TITLE_B = "对照采集 B（可选）"
const val OFFLINE_SUBTITLE_B = "与 A 比较逐偏移字节分布；不接触蓝牙"
const val OFFLINE_COMPARE = "开始对照"
const val OFFLINE_PASTE_B = "粘贴 B"

const val OFFLINE_ERROR_EMPTY_A = "请输入或粘贴通知 hex"
const val OFFLINE_ERROR_NO_FRAMES = "未识别出任何帧：请确认是 55AA 开头的通知数据"
const val OFFLINE_ERROR_NEED_A = "先解析采集 A，且 A 至少包含 1 个完整帧"
const val OFFLINE_ERROR_EMPTY_B = "请输入或粘贴采集 B"
const val OFFLINE_ERROR_B_NO_FRAMES = "采集 B 未识别出完整帧，无法做偏移对照"

const val OFFLINE_LABEL_FRAMES = "识别帧"
const val OFFLINE_LABEL_REJECTED = "拒收块"
const val OFFLINE_LABEL_PENDING = "不完整"
const val OFFLINE_LABEL_TOTAL = "总字节"
const val OFFLINE_ALL_FAILED = "所有数据都校验失败：可能帧格式不同。原始字节已保留，可在报告中复制。"
const val OFFLINE_COPY_REPORT = "复制报告"
const val OFFLINE_REPORT_COPIED = "离线分析报告已复制"

const val OFFLINE_TITLE_COMPARISON = "A / B 字节对照"
const val OFFLINE_COMPARE_CAPTION = "逐偏移比较两份采集的分布，不保证逐帧时间对齐，也不代表字段语义。"
const val OFFLINE_COPY_COMPARISON = "复制对照报告"
const val OFFLINE_COMPARISON_COPIED = "对照报告已复制"
const val OFFLINE_STAT_SHARED = "共有组"
const val OFFLINE_STAT_ONLY_A = "仅 A"
const val OFFLINE_STAT_ONLY_B = "仅 B"
const val OFFLINE_STAT_DIFF_OFFSETS = "差异偏移"
const val OFFLINE_COMPARE_NO_DIFF = "未发现可报告的字节差异；样本不足仍可能漏掉变化。"
const val OFFLINE_ONLY_GROUP = "该帧组只出现在一份采集中，无法做逐偏移对照。"
const val OFFLINE_IDENTICAL_GROUP = "已观察偏移的分布一致。"
const val OFFLINE_COLUMN_OFFSET = "偏移"
const val OFFLINE_FRAME_COUNT_MISMATCH = "两份采集帧数不同，分布差异可能来自采样量。"

const val OFFLINE_TITLE_GROUPS = "帧组统计"
const val OFFLINE_GROUPS_NOTE = "样本 <2 时“恒定”不构成字段证据。"
const val OFFLINE_GROUPS_EMPTY = "没有可统计的完整帧。"
const val OFFLINE_FOOTER = "离线分析不接触蓝牙；原始字节和拒收块都会保留，不静默丢弃。"

/** 与 Dart `ValueKey('offline-a')` 等同的测试锚点。 */
internal const val OFFLINE_TAG_A = "offline-a"
internal const val OFFLINE_TAG_B = "offline-b"
internal const val OFFLINE_TAG_COMPARE_TILE = "offline-compare-tile"
internal const val OFFLINE_TAG_CLEAR = "offline-clear"
internal const val OFFLINE_TAG_COPY_REPORT = "offline-copy-report"
internal const val OFFLINE_TAG_COPY_COMPARISON = "offline-copy-comparison"
internal const val OFFLINE_TAG_STAT_FRAMES = "offline-stat-frames"
internal const val OFFLINE_TAG_STAT_REJECTED = "offline-stat-rejected"
internal const val OFFLINE_TAG_STAT_PENDING = "offline-stat-pending"
internal const val OFFLINE_TAG_STAT_TOTAL = "offline-stat-total"
internal const val OFFLINE_TAG_COMPARE_SHARED = "offline-compare-shared"
internal const val OFFLINE_TAG_COMPARE_ONLY_A = "offline-compare-only-a"
internal const val OFFLINE_TAG_COMPARE_ONLY_B = "offline-compare-only-b"
internal const val OFFLINE_TAG_COMPARE_DIFF_OFFSETS = "offline-compare-diff-offsets"

/** 对照差值表最多直接展开多少行（§3.6）。 */
private const val DIFF_ROW_LIMIT = 8

/**
 * 离线视图的自有状态：A/B 文本、两份解析结果、对照结果与两条内联错误。
 *
 * 放在宿主 [AnalysisTabBody] 的 `remember` 里，因此切换分段不会丢——与 Dart 的
 * `IndexedStack` 保持两棵子树常驻是同一效果（这里以状态提升实现，语义树仍只含当前段）。
 */
@Stable
class OfflineAnalysisState {
    var textA by mutableStateOf("")
    var textB by mutableStateOf("")
    var result by mutableStateOf<ReplayResult?>(null)
    var compareResult by mutableStateOf<ReplayResult?>(null)
    var comparison by mutableStateOf<CaptureComparison?>(null)
    var error by mutableStateOf<String?>(null)
    var compareError by mutableStateOf<String?>(null)
    var compareExpanded by mutableStateOf(false)

    /** 解析 A；成功即作废旧的对照（§3.3）。 */
    fun parse() {
        val text = textA.trim()
        if (text.isEmpty()) {
            error = OFFLINE_ERROR_EMPTY_A
            return
        }
        val parsed = try {
            replayHex(text)
        } catch (e: Exception) {
            error = "解析失败：$e"
            return
        }
        result = parsed
        error = if (parsed.frames.isEmpty() && parsed.pending.isEmpty()) OFFLINE_ERROR_NO_FRAMES else null
        comparison = null
        compareResult = null
        compareError = null
    }

    /** 对照 A/B；所有失败都写进 B 的内联错误（§3.4 表格）。 */
    fun compare() {
        val a = result
        if (a == null || a.frames.isEmpty()) {
            compareError = OFFLINE_ERROR_NEED_A
            return
        }
        val text = textB.trim()
        if (text.isEmpty()) {
            compareError = OFFLINE_ERROR_EMPTY_B
            return
        }
        val b = try {
            replayHex(text)
        } catch (e: Exception) {
            compareError = "对照解析失败：$e"
            return
        }
        if (b.frames.isEmpty()) {
            compareResult = b
            comparison = null
            compareError = OFFLINE_ERROR_B_NO_FRAMES
            return
        }
        compareResult = b
        comparison = compareCaptures(a, b)
        compareError = null
    }

    /** 清空两个输入框与全部结果/错误。 */
    fun clear() {
        textA = ""
        textB = ""
        result = null
        compareResult = null
        comparison = null
        error = null
        compareError = null
    }
}

@Composable
fun OfflineAnalysisView(
    state: OfflineAnalysisState,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val result = state.result
    val groups = remember(result) {
        result?.let { analyseFrames(it.frames) } ?: emptyList<FrameGroupStats>()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 28.dp),
    ) {
        Text(OFFLINE_INTRO, fontSize = 12.sp, lineHeight = 18.6.sp, color = IwColors.TextMuted)
        Spacer(Modifier.height(12.dp))
        HexField(
            tag = OFFLINE_TAG_A,
            value = state.textA,
            onValueChange = { state.textA = it },
            hint = OFFLINE_HINT_A,
            error = state.error,
            minLines = 6,
            maxLines = 10,
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = state::parse,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
            ) { Text(OFFLINE_PARSE) }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(
                onClick = {
                    val pasted = clipboard.getText()?.text
                    if (!pasted.isNullOrEmpty()) {
                        state.textA = pasted
                        state.error = null
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
            ) { Text(OFFLINE_PASTE) }
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = state::clear,
                modifier = Modifier
                    .testTag(OFFLINE_TAG_CLEAR)
                    .semantics { contentDescription = OFFLINE_CLEAR },
            ) {
                // delete_outline 的本地等价物：本 BOM 不透传 material-icons
                Text("⌫", fontSize = 18.sp, color = IwColors.TextMuted)
            }
        }
        Spacer(Modifier.height(8.dp))
        CompareSourceCard(
            state = state,
            onPasteB = {
                val pasted = clipboard.getText()?.text
                if (!pasted.isNullOrEmpty()) {
                    state.textB = pasted
                    state.compareError = null
                }
            },
        )

        if (result != null) {
            Spacer(Modifier.height(18.dp))
            ResultStatsCard(
                result = result,
                onCopy = {
                    clipboard.setText(AnnotatedString(offlineReportText(result)))
                    scope.launch { snackbar.showSnackbar(OFFLINE_REPORT_COPIED) }
                },
            )
            if (result.frames.isEmpty() && result.rejected.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                AllChecksumFailedBand()
            }
            val comparison = state.comparison
            if (comparison != null) {
                Spacer(Modifier.height(18.dp))
                ComparisonCard(
                    comparison = comparison,
                    onCopy = {
                        clipboard.setText(AnnotatedString(comparisonReport(comparison)))
                        scope.launch { snackbar.showSnackbar(OFFLINE_COMPARISON_COPIED) }
                    },
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(OFFLINE_TITLE_GROUPS, fontSize = 15.sp, fontWeight = FontWeight.W600, color = IwColors.Text)
            Spacer(Modifier.height(4.dp))
            Text(OFFLINE_GROUPS_NOTE, fontSize = 11.5.sp, color = IwColors.TextMuted)
            Spacer(Modifier.height(4.dp))
            if (groups.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = IwColors.Surface),
                ) {
                    Text(
                        text = OFFLINE_GROUPS_EMPTY,
                        modifier = Modifier.padding(20.dp),
                        fontSize = 12.5.sp,
                        color = IwColors.TextMuted,
                    )
                }
            } else {
                groups.forEach { group -> FrameGroupCard(group) }
            }
            if (result.pending.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "另有 ${result.pending.size} B 不完整缓冲（可能等待后续通知包）。",
                    fontSize = 11.5.sp,
                    color = IwColors.TextMuted,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text("⛨", fontSize = 15.sp, color = IwColors.TextMuted)
            Spacer(Modifier.width(7.dp))
            Text(
                text = OFFLINE_FOOTER,
                modifier = Modifier.weight(1f),
                fontSize = 11.5.sp,
                lineHeight = 17.25.sp,
                color = IwColors.TextMuted,
            )
        }
    }
}

// —— §3.2 输入框 ——

@Composable
private fun HexField(
    tag: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    error: String?,
    minLines: Int,
    maxLines: Int,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.8.sp,
        ),
        placeholder = {
            Text(hint, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = IwColors.TextMuted)
        },
        isError = error != null,
        supportingText = if (error != null) {
            { Text(error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        minLines = minLines,
        maxLines = maxLines,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = IwColors.SurfaceHigh,
            unfocusedContainerColor = IwColors.SurfaceHigh,
            focusedBorderColor = IwColors.Primary,
            unfocusedBorderColor = IwColors.Outline,
            cursorColor = IwColors.Primary,
            focusedTextColor = IwColors.Text,
            unfocusedTextColor = IwColors.Text,
        ),
    )
}

// —— §3.4 对照采集 B 卡片 ——

@Composable
private fun CompareSourceCard(state: OfflineAnalysisState, onPasteB: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = IwColors.Surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(OFFLINE_TAG_COMPARE_TILE)
                    .clickable { state.compareExpanded = !state.compareExpanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnalysisDot(20.dp, IwColors.TextMuted)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = OFFLINE_TITLE_B,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.W500,
                        color = IwColors.Text,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(OFFLINE_SUBTITLE_B, fontSize = 11.5.sp, color = IwColors.TextMuted)
                }
                Text(if (state.compareExpanded) "▴" else "▾", fontSize = 14.sp, color = IwColors.TextMuted)
            }
            if (state.compareExpanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    HexField(
                        tag = OFFLINE_TAG_B,
                        value = state.textB,
                        onValueChange = { state.textB = it },
                        hint = OFFLINE_HINT_B,
                        error = state.compareError,
                        minLines = 5,
                        maxLines = 8,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            onClick = state::compare,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = IwColors.Primary.copy(alpha = 0.16f),
                                contentColor = IwColors.Primary,
                            ),
                        ) { Text(OFFLINE_COMPARE) }
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(
                            onClick = onPasteB,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                        ) { Text(OFFLINE_PASTE_B) }
                    }
                    state.compareResult?.let { b ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "B：帧 ${b.frames.size} · 拒收块 ${b.rejected.size} · 不完整 ${b.pending.size} B",
                            fontSize = 11.5.sp,
                            color = IwColors.TextMuted,
                        )
                    }
                }
            }
        }
    }
}

// —— §3.5 结果统计卡 / 全失败提示 ——

@Composable
private fun ResultStatsCard(result: ReplayResult, onCopy: () -> Unit) {
    val copyEnabled = !(result.frames.isEmpty() && result.rejected.isEmpty())
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = IwColors.Surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(modifier = Modifier.weight(1f)) {
                OfflineStat("${result.frames.size}", OFFLINE_LABEL_FRAMES, OFFLINE_TAG_STAT_FRAMES)
                OfflineStat("${result.rejected.size}", OFFLINE_LABEL_REJECTED, OFFLINE_TAG_STAT_REJECTED)
                OfflineStat("${result.pending.size}B", OFFLINE_LABEL_PENDING, OFFLINE_TAG_STAT_PENDING)
                OfflineStat("${result.totalBytes}B", OFFLINE_LABEL_TOTAL, OFFLINE_TAG_STAT_TOTAL)
            }
            CopyIconButton(
                enabled = copyEnabled,
                description = OFFLINE_COPY_REPORT,
                tag = OFFLINE_TAG_COPY_REPORT,
                onClick = onCopy,
            )
        }
    }
}

@Composable
private fun AllChecksumFailedBand() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(IwColors.Warning.copy(alpha = 0.10f))
            .padding(13.dp),
    ) {
        Text(
            text = OFFLINE_ALL_FAILED,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = IwColors.Warning,
        )
    }
}

// —— §3.6 A / B 字节对照卡 ——

@Composable
private fun ComparisonCard(comparison: CaptureComparison, onCopy: () -> Unit) {
    val interesting = comparison.groups.filter {
        it.onlyInA || it.onlyInB || it.differences.isNotEmpty()
    }
    val unchangedShared =
        comparison.sharedGroups.size - comparison.sharedGroups.count { it.differences.isNotEmpty() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = IwColors.Surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, end = 8.dp, bottom = 14.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AnalysisDot(20.dp, IwColors.TextMuted)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = OFFLINE_TITLE_COMPARISON,
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.W600,
                    color = IwColors.Text,
                )
                CopyIconButton(
                    enabled = true,
                    description = OFFLINE_COPY_COMPARISON,
                    tag = OFFLINE_TAG_COPY_COMPARISON,
                    onClick = onCopy,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = OFFLINE_COMPARE_CAPTION,
                modifier = Modifier.padding(end = 8.dp),
                fontSize = 11.5.sp,
                lineHeight = 17.25.sp,
                color = IwColors.TextMuted,
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                OfflineStat("${comparison.sharedGroups.size}", OFFLINE_STAT_SHARED, OFFLINE_TAG_COMPARE_SHARED)
                OfflineStat("${comparison.onlyACount}", OFFLINE_STAT_ONLY_A, OFFLINE_TAG_COMPARE_ONLY_A)
                OfflineStat("${comparison.onlyBCount}", OFFLINE_STAT_ONLY_B, OFFLINE_TAG_COMPARE_ONLY_B)
                OfflineStat("${comparison.differingOffsets}", OFFLINE_STAT_DIFF_OFFSETS, OFFLINE_TAG_COMPARE_DIFF_OFFSETS)
            }
            if (interesting.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(OFFLINE_COMPARE_NO_DIFF, fontSize = 12.sp, color = IwColors.TextMuted)
            } else {
                interesting.forEach { group -> ComparisonGroupTile(group) }
            }
            if (unchangedShared > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "另有 $unchangedShared 个共有组在已观察偏移上分布一致。",
                    fontSize = 11.5.sp,
                    color = IwColors.TextMuted,
                )
            }
        }
    }
}

/** 一个“有趣”帧组：只有一侧、或共有但偏移分布有差异。初始展开。 */
@Composable
private fun ComparisonGroupTile(group: GroupDiff) {
    var expanded by remember(group.key) { mutableStateOf(true) }
    val differences = group.differences
    val shown = differences.take(DIFF_ROW_LIMIT)
    val subtitle = when {
        group.onlyInA -> "仅在 A 出现 · ${group.a?.frames ?: 0} 帧"
        group.onlyInB -> "仅在 B 出现 · ${group.b?.frames ?: 0} 帧"
        else -> "A ${group.a?.frames ?: 0} 帧 / B ${group.b?.frames ?: 0} 帧" +
            if (group.lengthDiffers) " · payload 长度不一致" else ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(IwColors.SurfaceHigh.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.key,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    color = IwColors.Text,
                )
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 11.sp, color = IwColors.TextMuted)
            }
            Text(if (expanded) "▴" else "▾", fontSize = 14.sp, color = IwColors.TextMuted)
        }
        if (expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                when {
                    !group.shared -> Text(
                        text = OFFLINE_ONLY_GROUP,
                        fontSize = 11.5.sp,
                        color = IwColors.TextMuted,
                    )
                    differences.isEmpty() -> Text(
                        text = OFFLINE_IDENTICAL_GROUP,
                        fontSize = 11.5.sp,
                        color = IwColors.TextMuted,
                    )
                    else -> {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Box(modifier = Modifier.width(44.dp)) {
                                Text(OFFLINE_COLUMN_OFFSET, fontSize = 10.5.sp, color = IwColors.TextMuted)
                            }
                            Text(
                                text = "A（${group.a?.frames ?: 0} 帧）",
                                modifier = Modifier.weight(1f),
                                fontSize = 10.5.sp,
                                color = IwColors.TextMuted,
                            )
                            Text(
                                text = "B（${group.b?.frames ?: 0} 帧）",
                                modifier = Modifier.weight(1f),
                                fontSize = 10.5.sp,
                                color = IwColors.TextMuted,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        shown.forEach { diff -> OffsetDiffRow(diff) }
                        if (differences.size > shown.size) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "另有 ${differences.size - shown.size} 个差异偏移，完整内容见复制报告。",
                                fontSize = 11.sp,
                                color = IwColors.TextMuted,
                            )
                        }
                        if ((group.a?.frames ?: 0) != (group.b?.frames ?: 0)) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = OFFLINE_FRAME_COUNT_MISMATCH,
                                fontSize = 11.sp,
                                color = IwColors.Warning,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OffsetDiffRow(diff: OffsetDiff) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.width(44.dp)) {
            Text(
                text = diff.offset.toString().padStart(2, '0'),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                color = IwColors.Warning,
            )
        }
        OffsetSide(diff.a)
        OffsetSide(diff.b)
    }
}

@Composable
private fun RowScope.OffsetSide(stat: OffsetStat?) {
    if (stat == null) {
        Text(
            text = "—",
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            color = IwColors.TextMuted,
        )
        return
    }
    val u16 = if (stat.u16leMin == null) "" else " · u16 ${stat.u16leMin}..${stat.u16leMax}"
    Text(
        text = "${stat.min}..${stat.max} · ${stat.distinct} 值" +
            (if (stat.constant) " · 恒定" else "") + u16,
        modifier = Modifier.weight(1f),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.4.sp,
        color = IwColors.Text,
    )
}

// —— 共用小件 ——

/** `_Stat`（离线分析的四列数值）：18 w500 tabular + 3 dp + 10.5 muted。 */
@Composable
private fun RowScope.OfflineStat(value: String, label: String, tag: String) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = value,
            modifier = Modifier.testTag(tag),
            fontSize = 18.sp,
            fontWeight = FontWeight.W500,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFeatureSettings = "tnum"),
            color = IwColors.Text,
        )
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 10.5.sp, color = IwColors.TextMuted)
    }
}

@Composable
private fun CopyIconButton(
    enabled: Boolean,
    description: String,
    tag: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .testTag(tag)
            .semantics { contentDescription = description },
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = IwColors.TextMuted,
            disabledContentColor = IwColors.TextMuted.copy(alpha = 0.4f),
        ),
    ) {
        // copy_all_outlined 的本地等价物（本 BOM 不透传 material-icons）
        Text("⧉", fontSize = 17.sp)
    }
}

/** 图标位占位：一个纯色圆点（与实时分析页同口径）。 */
@Composable
private fun AnalysisDot(size: Dp, color: Color) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}
