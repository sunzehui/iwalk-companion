package com.iwalk.protocol

/**
 * iWalk（Lebid/LBtech）BLE 协议参考编解码。
 *
 * 帧布局（notes/domain-logic.md §1.1，真车链路采集核对）：
 * ```
 * [0]      0x55   header0
 * [1]      0xAA   header1
 * [2]      L      = payload.size + 2
 * [3]      CMD
 * [4]      A1
 * [5]      A2
 * [6..]    payload（L-2 字节，可为空）
 * [L+4]    chk_lo   小端校验和
 * [L+5]    chk_hi   整帧长度 = L + 6
 * ```
 *
 * 校验和（§1.2）：`(~uint16(sum(bytes[2..L+3]))) & 0xFFFF`，即去掉两字节帧头与
 * 两字节帧尾后全部字节求和（每步按 uint16 回绕）再取反。
 *
 * 文档里另写过一条 ACK 规则 `A2 = CMD ^ 0x05`：**不实现**（notes §1.5 末）——Dart 代码里
 * 没有任何地方断言它，而官方黄金帧本身就是反例（0x0D/0x14、0x0A/0x14）。按它丢帧会丢真车数据。
 */
const val K_HEADER0 = 0x55
const val K_HEADER1 = 0xAA

/** 一帧的固定出站能力之一：仅帧模型与编码，不提供任意字节写入接口。 */
class IbwalkFrame(
    val cmd: Int,
    val a1: Int,
    val a2: Int,
    val payload: ByteArray = ByteArray(0),
) {
    /** 长度字段 L = 载荷长度 + 2。 */
    val lengthField: Int get() = payload.size + 2

    /** 明文帧字节（不含线上 XOR 混淆）。 */
    fun encode(): ByteArray {
        val body = ByteArray(4 + payload.size)
        body[0] = lengthField.toByte()
        body[1] = cmd.toByte()
        body[2] = a1.toByte()
        body[3] = a2.toByte()
        payload.copyInto(body, 4)
        val chk = checksum(body)
        val out = ByteArray(2 + body.size + 2)
        out[0] = K_HEADER0.toByte()
        out[1] = K_HEADER1.toByte()
        body.copyInto(out, 2)
        out[out.size - 2] = (chk and 0xFF).toByte()
        out[out.size - 1] = (chk shr 8 and 0xFF).toByte()
        return out
    }

    /**
     * 线上字节：整帧逐字节 ⊕ 0x55（证据 E46/E57）。真实滑板车收发都做整帧混淆，
     * 因此明文 `55 AA` 头部在线上读作 `00 FF`。
     *
     * 语义由 Dart 侧缺失的 `obfuscate()` 调用点补全（notes §10-1）：等长逐字节异或，
     * 混淆后**不再**重算校验和。
     */
    fun encodeWire(): ByteArray = obfuscate(encode())

    override fun toString(): String =
        "IbwalkFrame(cmd: 0x${cmd.toString(16)}, a1: 0x${a1.toString(16)}, " +
            "a2: 0x${a2.toString(16)}, payload: ${payload.size}B)"

    override fun equals(other: Any?): Boolean = other is IbwalkFrame &&
        cmd == other.cmd && a1 == other.a1 && a2 == other.a2 && payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = cmd
        result = 31 * result + a1
        result = 31 * result + a2
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * 校验和：覆盖范围内每字节累加并按 uint16 回绕，最后取反。
 * 用 Int 累加并显式掩码，绝不依赖 [Byte] 的符号位。
 */
fun checksum(covered: ByteArray): Int {
    var s = 0
    for (b in covered) {
        s = (s + (b.toInt() and 0xFF)) and 0xFFFF
    }
    return s.inv() and 0xFFFF
}

/**
 * 解码结果。[frame] 为 null 表示数据还不够（需要更多字节），此时 [consumed] 恒为 0，
 * 调用方必须原样保留缓冲。
 */
data class DecodeResult(val frame: IbwalkFrame?, val consumed: Int)

/**
 * 校验和不匹配。注意：这里**不重新同步**，由 [StreamDecoder] 决定如何保留被拒字节。
 * `toString` 口径与 Dart 一致：`ChecksumMismatch(calc=0x…, recv=0x…)`（小写、不补零）。
 */
class ChecksumMismatch(val expected: Int, val received: Int) : Exception(
    "calc=0x${Integer.toHexString(expected)}, recv=0x${Integer.toHexString(received)}",
) {
    /** 与 Dart 的 `toString()` 逐字一致：日志/证据里嵌入的就是这个串。 */
    override fun toString(): String = "ChecksumMismatch($message)"
}

/**
 * 从缓冲区头部解出一帧（notes/domain-logic.md §1.3 契约）：
 *
 * | 条件 | 结果 |
 * |---|---|
 * | 长度 < 3 或头部不是 `55 AA` | `(null, 0)` |
 * | 长度 < L + 6 | `(null, 0)`（需要更多数据，调用方保留字节） |
 * | 校验和不匹配 | 抛 [ChecksumMismatch] |
 * | 其它 | 帧 + 消费 L + 6 字节 |
 *
 * 与 Dart 的一处有意差异：L < 2（自相矛盾的畸形长度）时 Dart 会在切片处抛范围错误；
 * 这里按空载荷处理，保证恶意/畸形字节不可能让链路解码崩溃（notes §10-10 的同一口径）。
 */
fun decodeFrame(buf: ByteArray): DecodeResult {
    if (buf.size < 3 || (buf[0].toInt() and 0xFF) != K_HEADER0 || (buf[1].toInt() and 0xFF) != K_HEADER1) {
        return DecodeResult(null, 0)
    }
    val l = buf[2].toInt() and 0xFF
    val total = l + 6
    if (buf.size < total) return DecodeResult(null, 0)

    val recv = (buf[l + 4].toInt() and 0xFF) or ((buf[l + 5].toInt() and 0xFF) shl 8)
    val calc = checksum(buf.copyOfRange(2, l + 4))
    if (calc != recv) throw ChecksumMismatch(calc, recv)

    val payload = if (l + 4 > 6) buf.copyOfRange(6, l + 4) else ByteArray(0)
    return DecodeResult(IbwalkFrame(buf[3].toInt() and 0xFF, buf[4].toInt() and 0xFF, buf[5].toInt() and 0xFF, payload), total)
}

