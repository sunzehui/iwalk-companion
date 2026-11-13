package com.iwalk.protocol

/**
 * 线上混淆：整帧逐字节 ⊕ 0x55（notes/domain-logic.md §1.5）。
 *
 * 这是编解码原语，不是出站写接口：只有 [IbwalkFrame.encodeWire] /
 * [LockProtocol.encodeWire] 这类固定编码器能把字节交给链路，
 * 本模块不导出“写入任意字节”的能力（规格第 3 节的强制安全边界）。
 */
const val XOR_KEY = 0x55

/** 混淆/解混淆同一条操作：等长、逐字节 ⊕ 0x55、不重算校验和。 */
fun obfuscate(bytes: ByteArray): ByteArray = ByteArray(bytes.size) { i -> (bytes[i].toInt() xor XOR_KEY).toByte() }
