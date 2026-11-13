package com.iwalk.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 帧编解码的黄金向量（notes/domain-logic.md §1.1–§1.2）。
 * 期望值全部逐字抄自笔记，绝不由被测代码反推。
 */
class IbwalkFrameTest {

    @Test
    fun `校验和公式匹配真车帧规则`() {
        // covered = 02 ff 01 03 → 0xFEFA
        assertEquals(0xFEFA, checksum("02ff0103".hexToBytes()))
    }

    @Test
    fun `编码握手帧得到黄金字节`() {
        // 明文握手：CMD FF / A1 01 / A2 03，空载荷
        val frame = IbwalkFrame(cmd = 0xFF, a1 = 0x01, a2 = 0x03)
        assertEquals("55aa02ff0103fafe", frame.encode().toHex())
    }

    @Test
    fun `编码心跳帧得到黄金字节`() {
        val frame = IbwalkFrame(cmd = 0xF1, a1 = 0x05, a2 = 0x01)
        assertEquals("55aa02f1050106ff", frame.encode().toHex())
    }

    @Test
    fun `长度字段为载荷加二且总长为六加L`() {
        val frame = IbwalkFrame(cmd = 0x0A, a1 = 0x01, a2 = 0x14, payload = "26".hexToBytes())
        assertEquals(3, frame.lengthField)
        // 总长 = L + 6 = 3 + 6
        assertEquals(9, frame.encode().size)
    }

    @Test
    fun `编码真实状态帧得到46字节黄金串`() {
        val golden =
            "55aa280d0114303234333338303030303030e11a00000000c818f6010100000034fe6300690000000000000090f8"
        val payload = golden.substring(12, golden.length - 4).hexToBytes()
        val frame = IbwalkFrame(cmd = 0x0D, a1 = 0x01, a2 = 0x14, payload = payload)
        assertEquals(golden, frame.encode().toHex())
    }

    @Test
    fun `解码来自二进制的硬编码帧`() {
        val (frame, consumed) = decodeFrame("55aa02ff0103fafe".hexToBytes())
        assertEquals(8, consumed)
        assertEquals(0xFF, frame!!.cmd)
        assertEquals(0x01, frame.a1)
        assertEquals(0x03, frame.a2)
        assertEquals(0, frame.payload.size)
    }

    @Test
    fun `解码带载荷的帧取到L加6字节`() {
        val golden =
            "55aa280d0114303234333338303030303030e11a00000000c818f6010100000034fe6300690000000000000090f8"
        val (frame, consumed) = decodeFrame(golden.hexToBytes())
        assertEquals(46, consumed)
        assertEquals(0x0D, frame!!.cmd)
        assertEquals(0x01, frame.a1)
        assertEquals(0x14, frame.a2)
        assertEquals(38, frame.payload.size)
        // 载荷首字节是序列号 ASCII '0'
        assertEquals(0x30, frame.payload[0].toInt() and 0xFF)
        assertEquals(
            "303234333338303030303030e11a00000000c818f6010100000034fe63006900000000000000",
            frame.payload.toHex(),
        )
    }

    @Test
    fun `数据不足时返回空并要求更多`() {
        // 只给出 8 字节帧的前 5 字节：不报错，消费 0 字节，调用方保留缓冲
        val (frame, consumed) = decodeFrame("55aa02ff01".hexToBytes())
        assertEquals(null, frame)
        assertEquals(0, consumed)
    }

    @Test
    fun `头部不匹配时不消费任何字节`() {
        val (frame, consumed) = decodeFrame("00aa02ff0103fafe".hexToBytes())
        assertEquals(null, frame)
        assertEquals(0, consumed)
    }

    @Test
    fun `校验和不匹配抛出异常并带上两侧数值`() {
        val ex = assertThrows(ChecksumMismatch::class.java) {
            decodeFrame("55aa02ff0103dead".hexToBytes())
        }
        // covered=02ff0103 → calc 0xfefa；尾部 de ad 按小端读作 0xadde
        assertEquals("ChecksumMismatch(calc=0xfefa, recv=0xadde)", ex.toString())
    }

    @Test
    fun `编码解码往返一致`() {
        val original = IbwalkFrame(cmd = 0xF1, a1 = 0x05, a2 = 0x01)
        val encoded = original.encode()
        val (decoded, consumed) = decodeFrame(encoded)
        assertEquals(encoded.size, consumed)
        assertEquals(original.cmd, decoded!!.cmd)
        assertEquals(original.a1, decoded.a1)
        assertEquals(original.a2, decoded.a2)
        assertEquals(0, decoded.payload.size)
    }
}
