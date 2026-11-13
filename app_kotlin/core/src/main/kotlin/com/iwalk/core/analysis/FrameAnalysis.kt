package com.iwalk.core.analysis

import com.iwalk.protocol.IbwalkFrame
import java.util.Locale

/**
 * 帧组字节统计（notes/domain-logic.md §6.1，对应 `app_v2/lib/analysis/frame_analysis.dart`）。
 *
 * 这是**证据**而不是解释：只报告 payload 每个偏移上字节做过什么，绝不声明单位或字段含义。
 * 单样本帧组保留但可被 `frames == 1` 识别——一帧看不出变化，它的“恒定”不构成字段证据。
 */

/** 一个 payload 偏移上的字节分布。 */
data class OffsetStat(
    val offset: Int,
    val distinct: Int,
    val min: Int,
    val max: Int,
    val constant: Boolean,
    /** 仅当偏移为偶数且存在后继字节时计算；否则两者同为 null。 */
    val u16leMin: Int? = null,
    val u16leMax: Int? = null,
) {
    /** 导出键序与 Dart 逐字一致；u16le 仅在有值时出现（§7.3）。 */
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "offset" to offset,
        "distinct" to distinct,
        "min" to min,
        "max" to max,
        "constant" to constant,
    ).apply {
        u16leMin?.let { put("u16le_min", it) }
        u16leMax?.let { put("u16le_max", it) }
    }
}

/** 一个 (cmd,a1,a2) 帧组的统计。[payloadLength] 取组内**最大**载荷长度。 */
data class FrameGroupStats(
    val cmd: Int,
    val a1: Int,
    val a2: Int,
    val frames: Int,
    val payloadLength: Int,
    val lengthStable: Boolean,
    val offsets: List<OffsetStat>,
) {
    /** 小写零填充的组键：`0xNN/0xNN/0xNN`（§4.2 / §6.1）。 */
    val key: String get() = frameGroupKey(cmd, a1, a2)

    /** 导出键序：cmd/a1/a2（`0x%02x` 字符串）、frames、payload_length、length_stable、offsets。 */
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "cmd" to "0x%02x".format(Locale.ROOT, cmd),
        "a1" to "0x%02x".format(Locale.ROOT, a1),
        "a2" to "0x%02x".format(Locale.ROOT, a2),
        "frames" to frames,
        "payload_length" to payloadLength,
        "length_stable" to lengthStable,
        "offsets" to offsets.map { it.toJson() },
    )
}

/** 组键格式：小写、两位十六进制、零填充（与快照 JSON 的不补零变体刻意区分，§10-9）。 */
fun frameGroupKey(cmd: Int, a1: Int, a2: Int): String =
    "0x%02x/0x%02x/0x%02x".format(Locale.ROOT, cmd, a1, a2)

/**
 * 按 (cmd,a1,a2) 分组并逐偏移统计；组按 cmd → a1 → a2 升序，偏移 `0 … min(maxLen, maxOffset)-1`。
 *
 * - `payloadLength` = 组内最大载荷长度；`lengthStable` = 组内长度集合大小为 1；
 * - `distinct/min/max/constant` 只统计**包含该偏移**的帧（`payload.size > off`）；
 * - `u16le` 只在 `off` 为偶数且 `off + 1 < maxLen` 时给出，取包含 `off + 1` 的帧。
 */
fun analyseFrames(frames: List<IbwalkFrame>, maxOffset: Int = 16): List<FrameGroupStats> {
    if (frames.isEmpty()) return emptyList()
    val grouped = LinkedHashMap<Triple<Int, Int, Int>, MutableList<ByteArray>>()
    for (frame in frames) {
        grouped.getOrPut(Triple(frame.cmd, frame.a1, frame.a2)) { mutableListOf() }.add(frame.payload)
    }
    val keys = grouped.keys.sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
    return keys.map { key ->
        val payloads = grouped.getValue(key)
        val maxLen = payloads.maxOf { it.size }
        val lengths = payloads.map { it.size }.toSet()
        val offsets = ArrayList<OffsetStat>(minOf(maxLen, maxOffset))
        var off = 0
        while (off < maxLen && off < maxOffset) {
            val values = payloads.filter { it.size > off }.map { it[off].toInt() and 0xFF }
            if (values.isNotEmpty()) {
                var u16Min: Int? = null
                var u16Max: Int? = null
                if (off % 2 == 0 && off + 1 < maxLen) {
                    val u16 = payloads
                        .filter { it.size > off + 1 }
                        .map { (it[off].toInt() and 0xFF) or ((it[off + 1].toInt() and 0xFF) shl 8) }
                        .sorted()
                    if (u16.isNotEmpty()) {
                        u16Min = u16.first()
                        u16Max = u16.last()
                    }
                }
                val distinct = values.toSet().size
                offsets += OffsetStat(
                    offset = off,
                    distinct = distinct,
                    min = values.min(),
                    max = values.max(),
                    constant = distinct == 1,
                    u16leMin = u16Min,
                    u16leMax = u16Max,
                )
            }
            off++
        }
        FrameGroupStats(
            cmd = key.first,
            a1 = key.second,
            a2 = key.third,
            frames = payloads.size,
            payloadLength = maxLen,
            lengthStable = lengths.size == 1,
            offsets = offsets,
        )
    }
}

/**
 * `复制偏移分析` 的正文（notes/ui-analysis.md §2.2.1，逐字节对齐 Dart 的 `StringBuffer`）。
 *
 * 每行以 `\n` 结束；每个 `##` 组前有一个空行；偏移用空格左补齐到 2 位（`off  0:`）；
 * 组内样本不足 2 时追加一条“样本不足”注释。本报告**不含**单位/语义词汇。
 */
fun offsetReportText(groups: List<FrameGroupStats>, sessionId: String?): String {
    val out = StringBuilder()
    out.append("# iWalk 帧偏移分析（仅字节统计，未含单位/语义）\n")
    out.append("# session: ").append(sessionId ?: "none").append('\n')
    for (group in groups) {
        out.append("\n## ").append(group.key)
            .append("  frames=").append(group.frames)
            .append(" len=").append(group.payloadLength)
        if (!group.lengthStable) out.append(" (长度不一致)")
        out.append('\n')
        if (group.frames < 2) {
            out.append("# 样本不足（<2），偏移不变不能作为字段证据\n")
        }
        for (stat in group.offsets) {
            out.append("off ").append(stat.offset.toString().padStart(2, ' '))
                .append(": distinct=").append(stat.distinct)
                .append(' ').append(stat.min).append("..").append(stat.max)
            if (stat.constant) out.append(" CONST")
            if (stat.u16leMin != null) {
                out.append("  u16le ").append(stat.u16leMin).append("..").append(stat.u16leMax)
            }
            out.append('\n')
        }
    }
    return out.toString()
}
