package com.iwalk.core.analysis

/**
 * 两份离线采集的逐偏移字节对照（notes/domain-logic.md §6.3 / §6.4，
 * 对应 `app_v2/lib/analysis/compare.dart`）。
 *
 * 这是**分布对照**，不是逐帧对齐：两次采集没有共同时钟，同一偏移的字节差异不能单独
 * 证明任何字段含义。报告文本因此只出现字节事实，不出现速度/电量之类的语义词。
 */

/** 一个 payload 偏移在两份采集里的分布。[a]/[b] 为 null 表示该侧没有这个偏移。 */
data class OffsetDiff(
    val offset: Int,
    val a: OffsetStat?,
    val b: OffsetStat?,
) {
    val onlyInA: Boolean get() = a != null && b == null
    val onlyInB: Boolean get() = b != null && a == null

    /** 任一侧缺失即不同；否则只比较 min/max/distinct（constant 与 u16 不参与，§6.3）。 */
    val differs: Boolean
        get() {
            val x = a ?: return true
            val y = b ?: return true
            return x.min != y.min || x.max != y.max || x.distinct != y.distinct
        }
}

/** 一个 (cmd,a1,a2) 帧组在两份采集里的对照。 */
data class GroupDiff(
    val key: String,
    val a: FrameGroupStats?,
    val b: FrameGroupStats?,
    val offsets: List<OffsetDiff>,
) {
    val onlyInA: Boolean get() = a != null && b == null
    val onlyInB: Boolean get() = b != null && a == null
    val shared: Boolean get() = a != null && b != null

    /** 只有两侧都存在时才谈得上长度变化（§6.3）。 */
    val lengthDiffers: Boolean
        get() {
            val x = a ?: return false
            val y = b ?: return false
            return x.payloadLength != y.payloadLength || x.lengthStable != y.lengthStable
        }

    val differences: List<OffsetDiff> get() = offsets.filter { it.differs }
}

/**
 * 一次 A/B 对照。两份采集的拒收块与不完整缓冲原样挂在各自一侧（[a]/[b]），
 * 对照本身不丢弃任何证据字节。
 */
class CaptureComparison(
    val a: ReplayResult,
    val b: ReplayResult,
    val groups: List<GroupDiff>,
) {
    val sharedGroups: List<GroupDiff> get() = groups.filter { it.shared }
    val onlyACount: Int get() = groups.count { it.onlyInA }
    val onlyBCount: Int get() = groups.count { it.onlyInB }
    val differingOffsets: Int get() = sharedGroups.sumOf { it.differences.size }

    val hasDifferences: Boolean
        get() = onlyACount > 0 || onlyBCount > 0 || differingOffsets > 0
}

/**
 * 按 (cmd,a1,a2) 分组对照，偏移上限 [maxOffset]（默认 16，与帧组统计一致）。
 *
 * 组键集合 = 两侧键的并集，**按字符串字典序**排序（不是数值序）；
 * 组内偏移 = 两侧偏移的并集，按数值升序。
 */
fun compareCaptures(a: ReplayResult, b: ReplayResult, maxOffset: Int = 16): CaptureComparison {
    val aGroups = analyseFrames(a.frames, maxOffset).associateBy { it.key }
    val bGroups = analyseFrames(b.frames, maxOffset).associateBy { it.key }
    val keys = (aGroups.keys + bGroups.keys).sorted()

    val groups = keys.map { key ->
        val left = aGroups[key]
        val right = bGroups[key]
        val aOffsets = left?.offsets?.associateBy { it.offset } ?: emptyMap()
        val bOffsets = right?.offsets?.associateBy { it.offset } ?: emptyMap()
        val offsetKeys = (aOffsets.keys + bOffsets.keys).sorted()
        GroupDiff(
            key = key,
            a = left,
            b = right,
            offsets = offsetKeys.map { offset ->
                OffsetDiff(offset = offset, a = aOffsets[offset], b = bOffsets[offset])
            },
        )
    }
    return CaptureComparison(a = a, b = b, groups = groups)
}

/**
 * `复制对照报告` 的正文（notes/domain-logic.md §6.4，逐字节对齐 Dart）。
 *
 * 段落顺序：仅在 A 的组 → 仅在 B 的组 → 共有组（各自沿用字典序）。
 * 偏移在这里**零填充**到 2 位（`off 00:`），与偏移分析报告的空格补齐刻意不同。
 */
fun comparisonReport(comparison: CaptureComparison): String {
    val a = comparison.a
    val b = comparison.b
    val out = StringBuilder()
    out.append("# iWalk 离线采集对照（仅字节分布，未含单位/语义）\n")
    out.append("# A: 帧 ").append(a.frames.size)
        .append("，拒收块 ").append(a.rejected.size)
        .append("，不完整 ").append(a.pending.size).append(" B\n")
    out.append("# B: 帧 ").append(b.frames.size)
        .append("，拒收块 ").append(b.rejected.size)
        .append("，不完整 ").append(b.pending.size).append(" B\n")
    out.append("# 共有组 ").append(comparison.sharedGroups.size)
        .append("，仅 A ").append(comparison.onlyACount)
        .append("，仅 B ").append(comparison.onlyBCount)
        .append("，差异偏移 ").append(comparison.differingOffsets).append('\n')

    for (group in comparison.groups) {
        if (group.onlyInA) out.append("\n## ").append(group.key).append("  仅在 A\n")
    }
    for (group in comparison.groups) {
        if (group.onlyInB) out.append("\n## ").append(group.key).append("  仅在 B\n")
    }
    for (group in comparison.groups) {
        if (!group.shared) continue
        val left = group.a ?: continue
        val right = group.b ?: continue
        out.append("\n## ").append(group.key)
            .append("  A frames=").append(left.frames)
            .append(" len=").append(left.payloadLength)
        if (!left.lengthStable) out.append(" (长度不一致)")
        out.append("  B frames=").append(right.frames)
            .append(" len=").append(right.payloadLength)
        if (!right.lengthStable) out.append(" (长度不一致)")
        out.append('\n')
        val differences = group.differences
        if (differences.isEmpty()) {
            out.append("# 已观察偏移的分布一致；样本量不同仍可能掩盖差异\n")
            continue
        }
        for (diff in differences) {
            out.append("off ").append(diff.offset.toString().padStart(2, '0'))
                .append(": ").append(sideText(diff.a))
                .append("  |  ").append(sideText(diff.b)).append('\n')
        }
    }

    out.append("\n# 说明：对照只比较两份采集的字节分布，不保证逐帧时间对齐；偏移差异不能单独证明字段含义。\n")
    return out.toString()
}

/** 报告里的一侧：`distinct=<d> <min>..<max>[ CONST][  u16le <a>..<b>]`，缺失为全宽破折号。 */
private fun sideText(stat: OffsetStat?): String {
    if (stat == null) return "—"
    val u16 = if (stat.u16leMin == null) "" else "  u16le ${stat.u16leMin}..${stat.u16leMax}"
    return "distinct=${stat.distinct} ${stat.min}..${stat.max}" +
        (if (stat.constant) " CONST" else "") + u16
}
