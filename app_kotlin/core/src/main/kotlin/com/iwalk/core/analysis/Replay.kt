package com.iwalk.core.analysis

import com.iwalk.protocol.IbwalkFrame
import com.iwalk.protocol.StreamDecoder
import com.iwalk.protocol.hexToBytes
import com.iwalk.protocol.toHex

/**
 * 离线回放（notes/domain-logic.md §6.2，对应 `app_v2/lib/analysis/replay.dart`）。
 *
 * 用户粘贴的是**线上字节**：一行一个通知包，可以带空格/冒号/连字符，也可以带
 * `rx:`/`notify:` 这类方向前缀。解析只丢“不可能构成帧”的碎屑，其余全部保留：
 * 校验失败的块原样进 [ReplayResult.rejected]，尾部拼不完整的字节进 [ReplayResult.pending]。
 * 不解单位、不做语义推断——这里只有字节。
 */

/**
 * 一次粘贴的解析结果。
 *
 * [frames] 按出现顺序；[rejected] 是解码器拒收的原始块（逐字节不变）；[pending] 是尾部
 * 尚未拼完整的缓冲。[totalBytes] = Σ 帧编码长度 + Σ 拒收块长度 + 未完整缓冲长度。
 */
class ReplayResult(
    val frames: List<IbwalkFrame>,
    val rejected: List<ByteArray>,
    val pending: ByteArray,
) {
    val totalBytes: Int =
        frames.sumOf { it.encode().size } + rejected.sumOf { it.size } + pending.size
}

/**
 * 方向前缀：最多 10 个字母、可带 `:` 或 `>`、可带空白，且**只有后面紧跟 4 个 hex 字符时**才匹配。
 * 与 Dart 的正则 `^[A-Za-z]{1,10}\s*[:>]?\s*(?=[0-9a-fA-F]{4})` 逐字一致（含其回溯行为）。
 */
private val DIRECTION_PREFIX = Regex("^[A-Za-z]{1,10}\\s*[:>]?\\s*(?=[0-9a-fA-F]{4})")

/**
 * 从自由文本里抽取十六进制包（§6.2 第 1–6 步）：
 *
 * 1. 按 `\n` 切行、`trim()`；空行与 `#` 开头的注释行跳过；
 * 2. 去掉行首方向前缀（`rx: 55AA…`、`notify: …`、`n55aa…` 都容忍）；
 * 3. 删掉所有非十六进制字符（空格、冒号、连字符、时间戳都因此消失）；
 * 4. 少于 4 个 hex 字符的行跳过（“太短，不可能是帧”）；
 * 5. 奇数字符时**静默**丢掉最后一个半字节；
 * 6. 两两成字节。
 *
 * 说明行（中文/英文散文）自然落到第 4 步被忽略，不会报错。
 */
fun extractHexPackets(text: String): List<ByteArray> {
    val packets = ArrayList<ByteArray>()
    for (rawLine in text.split("\n")) {
        var line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val prefix = DIRECTION_PREFIX.find(line)
        if (prefix != null) line = line.substring(prefix.range.last + 1)
        val hex = buildString {
            for (c in line) if (c.isHexDigit()) append(c)
        }
        if (hex.length < 4) continue // 太短，不可能是帧
        val even = if (hex.length % 2 == 0) hex else hex.dropLast(1)
        packets.add(even.hexToBytes())
    }
    return packets
}

/**
 * 用与实时链路**同一个** [StreamDecoder] 解码粘贴的包（§6.2 第 7 步）。
 *
 * 所有包按顺序喂给一个共享解码器，因此 XOR 模式在整个粘贴里只锁定一次；
 * 明文与混淆混贴无法同时解出——这是 Dart 的行为，不在此处“修正”。
 */
fun replayHex(text: String): ReplayResult {
    val decoder = StreamDecoder()
    val frames = ArrayList<IbwalkFrame>()
    for (packet in extractHexPackets(text)) {
        frames.addAll(decoder.feed(packet))
    }
    return ReplayResult(
        frames = frames.toList(),
        rejected = decoder.rejected.toList(),
        pending = decoder.pendingBytes,
    )
}

/**
 * `复制报告` 的正文（notes/ui-analysis.md §3.5，逐字节对齐 Dart 的 `StringBuffer`）。
 *
 * 帧组段与 `offsetReportText` 同口径（偏移空格左补齐、样本不足注释），随后追加
 * 拒收字节（最多前 20 块）与不完整缓冲。报告不含任何字段/单位语义。
 */
fun offlineReportText(result: ReplayResult, maxOffset: Int = 16): String {
    val out = StringBuilder()
    out.append("# iWalk 离线分析报告（字节视角，未含单位/语义）\n")
    out.append("# 帧 ").append(result.frames.size)
        .append("，拒收块 ").append(result.rejected.size)
        .append("，不完整缓冲 ").append(result.pending.size).append(" B\n")
    for (group in analyseFrames(result.frames, maxOffset)) {
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
    if (result.rejected.isNotEmpty()) {
        out.append("\n# 拒收字节（校验失败/噪声，已保留）\n")
        for (block in result.rejected.take(REJECTED_REPORT_LIMIT)) {
            out.append(block.toHex()).append('\n')
        }
    }
    if (result.pending.isNotEmpty()) {
        out.append("\n# 不完整帧缓冲 ").append(result.pending.size).append(" B\n")
        out.append(result.pending.toHex()).append('\n')
    }
    return out.toString()
}

/** 报告里最多列出多少块拒收字节（§3.5）。 */
private const val REJECTED_REPORT_LIMIT = 20

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
