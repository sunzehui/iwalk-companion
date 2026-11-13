package com.iwalk.protocol

/**
 * 字节 <-> 小写十六进制（无分隔符）。
 *
 * 口径来自 Dart：`bytes.map((b) => b.toRadixString(16).padLeft(2, "0")).join()`，
 * 原始行、证据 hex、黄金向量比对都走这里，避免各处再拼格式化。
 */

/** 字节数组 → 小写十六进制串，每字节两位。 */
fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        sb.append(HEX_CHARS[(b.toInt() shr 4) and 0x0F])
        sb.append(HEX_CHARS[b.toInt() and 0x0F])
    }
    return sb.toString()
}

fun List<Byte>.toHex(): String = toByteArray().toHex()

/**
 * 十六进制串 → 字节数组。长度必须为偶数；含非十六进制字符时抛
 * [IllegalArgumentException]（解析容错属于回放层的职责，见工单 05）。
 */
fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "十六进制串长度为奇数：$this" }
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = digit(this[2 * i], i)
        val lo = digit(this[2 * i + 1], i)
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private fun digit(c: Char, index: Int): Int = when {
    c in '0'..'9' -> c - '0'
    c in 'a'..'f' -> c - 'a' + 10
    c in 'A'..'F' -> c - 'A' + 10
    else -> throw IllegalArgumentException("第 $index 字节处不是十六进制字符：'$c'")
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()
