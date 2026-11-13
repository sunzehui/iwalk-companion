package com.iwalk.protocol

/**
 * 滑动窗口重组器，对齐厂商 `Bluetooth::combiningFrame`（notes/domain-logic.md §1.4）。
 *
 * 三条不可让的行为：
 * 1. **校验失败不丢字节**：整块候选帧原样存入 [rejected] 作证据，缓冲只前进 2 字节
 *    （跳过假头）后继续重新同步——坏帧绝不能卡住流（回归缺陷 E26）。
 * 2. **数据永不丢**：解不出来的尾巴留在 [pendingBytes]，下一次 feed 继续拼。
 * 3. **编码模式自动识别并锁定**：以*原始首包*的前两字节判定（`00 FF` → ⊕0x55，
 *    `55 AA` → 明文），一旦锁定终生不变（E46/E50）。因此每个通知特征要用**独立**解码器。
 *
 * [rejected] 是「由调用方排空」的队列，自身永不清空；`SessionManager` 每包排空并累加字节数。
 */
class StreamDecoder {
    private var buf = ByteArray(0)

    /** 校验失败被拒的原始块（调用方负责排空）。 */
    val rejected: MutableList<ByteArray> = mutableListOf()

    private var xor: Int? = null

    /** null = 未定；0 = 明文 `55 AA`；0x55 = 混淆 `00 FF`。 */
    val encodingMode: Int? get() = xor

    /** 因构成不完整帧而暂存的字节。 */
    val pendingBytes: ByteArray get() = buf.copyOf()

    fun feed(data: ByteArray): List<IbwalkFrame> {
        // 1. 模式锁定：只看本次原始入参的前两字节，且只在未定时尝试
        if (xor == null && data.size >= 2) {
            val b0 = data[0].toInt() and 0xFF
            val b1 = data[1].toInt() and 0xFF
            when {
                b0 == (K_HEADER0 xor XOR_KEY) && b1 == (K_HEADER1 xor XOR_KEY) -> xor = XOR_KEY
                b0 == K_HEADER0 && b1 == K_HEADER1 -> xor = 0
            }
        }
        // 2. 解混淆
        val incoming = if (xor != null && xor != 0) obfuscate(data) else data
        // 3. 追加并循环消费
        buf = buf + incoming
        val frames = mutableListOf<IbwalkFrame>()
        while (true) {
            if (buf.isEmpty()) break
            if ((buf[0].toInt() and 0xFF) != K_HEADER0) {
                // 静默重同步：这些字节不进 rejected（notes §10-5）
                buf = buf.copyOfRange(1, buf.size)
                continue
            }
            if (buf.size >= 2 && (buf[1].toInt() and 0xFF) != K_HEADER1) {
                buf = buf.copyOfRange(1, buf.size)
                continue
            }
            val result = try {
                decodeFrame(buf)
            } catch (e: ChecksumMismatch) {
                val l = buf[2].toInt() and 0xFF
                val end = minOf(l + 6, buf.size)
                rejected.add(buf.copyOfRange(0, end))
                buf = buf.copyOfRange(2, buf.size)
                continue
            }
            val frame = result.frame ?: break // 需要更多数据：字节留在缓冲里
            buf = buf.copyOfRange(result.consumed, buf.size)
            frames.add(frame)
        }
        return frames
    }
}
