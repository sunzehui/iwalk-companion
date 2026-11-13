package com.iwalk.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StreamDecoder] 的公开边界：喂进任意分包的原始字节，出来的是帧、被拒块与挂起尾巴。
 * 黄金向量取自 notes/domain-logic.md §1.4–§1.5、§8（协议测试 5、7、8、9、10）。
 */
class StreamDecoderTest {

    /** 一条真实格式的明文 0x06 演示帧，用于构造输入。 */
    private fun demoFrame(payload: ByteArray): ByteArray =
        IbwalkFrame(cmd = 0x06, a1 = 0x01, a2 = 0x05, payload = payload).encode()

    @Test
    fun `跨包分片可重组并跳过前置噪声`() {
        val f1 = IbwalkFrame(cmd = 0xF1, a1 = 0x05, a2 = 0x01).encode() // 55aa02f1050106ff
        val f2 = IbwalkFrame(cmd = 0x04, a1 = 0x01, a2 = 0x04, payload = byteArrayOf(0x10, 0x11)).encode()
        val stream = byteArrayOf(0x00, 0x55) + f1 + f2
        val decoder = StreamDecoder()

        // 前 3 个字节里还没有完整帧头：第一块只产出挂起字节，不出帧
        val first = decoder.feed(stream.copyOfRange(0, 5))
        val second = decoder.feed(stream.copyOfRange(5, stream.size))

        val frames = first + second
        assertEquals(listOf(0xF1, 0x04), frames.map { it.cmd })
        // f1 即黄金心跳帧 55aa02f1050106ff（a1=0x05, a2=0x01）；f2 为 0x04/0x01/0x04
        assertEquals(listOf(0x05, 0x01), frames.map { it.a1 })
        assertEquals(listOf(0x01, 0x04), frames.map { it.a2 })
        assertEquals(listOf(0, 2), frames.map { it.payload.size })
        assertEquals(0, decoder.rejected.size)
        assertEquals(0, decoder.pendingBytes.size)
    }

    @Test
    fun `未定模式时一字节首包不能锁定编码`() {
        val decoder = StreamDecoder()
        assertEquals(emptyList<IbwalkFrame>(), decoder.feed(byteArrayOf(0x55.toByte())))
        assertNull(decoder.encodingMode)
        // 下一包自己带头部，此时才锁定为明文；首包残留的半个头部被静默丢弃
        val frames = decoder.feed("55aa02f1050106ff".hexToBytes())
        assertEquals(listOf(0xF1), frames.map { it.cmd })
        assertEquals(0, decoder.encodingMode)
        assertEquals(0, decoder.pendingBytes.size)
    }

    @Test
    fun `校验失败保留整块被拒字节并继续重新同步`() {
        val bad = "55aa02ff0103dead".hexToBytes() // 校验位被改掉
        val good = demoFrame(byteArrayOf(0x64, 0x00, 0x50, 0x01, 0x02, 0x01, 0x00))
        val decoder = StreamDecoder()

        val frames = decoder.feed(bad + good)

        // 坏帧不产出任何帧，但字节必须原样留着（证据用途）
        assertEquals(listOf(0x06), frames.map { it.cmd })
        assertEquals(7, frames.single().payload.size)
        assertEquals(1, decoder.rejected.size)
        assertEquals("55aa02ff0103dead", decoder.rejected[0].toHex())
        assertEquals(0, decoder.pendingBytes.size)

        // 调用方没排空之前，拒收队列不会自清空（回归 E26：坏帧不能卡住流）
        assertEquals(listOf(0x06), decoder.feed(good).map { it.cmd })
        assertEquals(1, decoder.rejected.size)
    }

    @Test
    fun `拒收队列由调用方排空`() {
        val decoder = StreamDecoder()
        decoder.feed("55aa02ff0103dead".hexToBytes())
        assertEquals(1, decoder.rejected.size)
        decoder.rejected.clear()
        assertEquals(0, decoder.rejected.size)
    }

    @Test
    fun `畸形长度字节喂足数据后能重新同步`() {
        // L=0xFF → 期望 261 字节；258 个 0x55 噪声字节使校验位读作 0x5555 ≠ 计算值
        val bogus = byteArrayOf(0x55, 0xAA.toByte(), 0xFF.toByte()) + ByteArray(258) { 0x55 }
        val good = demoFrame(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07))
        val decoder = StreamDecoder()

        val frames = decoder.feed(bogus + good)

        assertEquals(listOf(0x06), frames.map { it.cmd })
        assertEquals(7, frames.single().payload.size)
        assertTrue(decoder.rejected.isNotEmpty())
        assertEquals("55aaff", decoder.rejected[0].take(3).toHex())
        assertEquals(261, decoder.rejected[0].size)
        assertEquals(0, decoder.pendingBytes.size)
    }

    @Test
    fun `头部不匹配的字节被静默丢弃而不计入拒收`() {
        val decoder = StreamDecoder()
        // 规格：只有校验失败块进入 rejected；头部重同步丢的字节不算证据（notes §10-5）
        val frames = decoder.feed(byteArrayOf(0x01, 0x02, 0x03) + demoFrame(ByteArray(7)))
        assertEquals(1, frames.size)
        assertEquals(0, decoder.rejected.size)
    }

    @Test
    fun `不完整尾部留在挂起缓冲里而不是丢失`() {
        val frame = demoFrame(byteArrayOf(0x2c, 0x00, 0x53, 0x01, 0x00, 0x01, 0x00))
        val decoder = StreamDecoder()

        assertEquals(emptyList<IbwalkFrame>(), decoder.feed(frame.copyOfRange(0, 4)))
        assertEquals(4, decoder.pendingBytes.size)
        assertEquals(frame.copyOfRange(0, 4).toHex(), decoder.pendingBytes.toHex())

        val frames = decoder.feed(frame.copyOfRange(4, frame.size))
        assertEquals(1, frames.size)
        assertEquals(0x06, frames.single().cmd)
        assertEquals(0, decoder.pendingBytes.size)
    }

    @Test
    fun `自动识别XOR0x55并把46字节状态帧按20+20+6重组`() {
        val decoder = StreamDecoder()
        val chunks = listOf(
            "00ff7d58544165676166666d656565656565b44f",
            "555555559d4da3545455555561ab36553c555555",
            "55555555c5ad",
        )

        assertEquals(emptyList<IbwalkFrame>(), decoder.feed(chunks[0].hexToBytes()))
        assertEquals(0x55, decoder.encodingMode)
        assertEquals(20, decoder.pendingBytes.size)
        assertEquals(emptyList<IbwalkFrame>(), decoder.feed(chunks[1].hexToBytes()))
        assertEquals(40, decoder.pendingBytes.size)

        val frames = decoder.feed(chunks[2].hexToBytes())
        assertEquals(1, frames.size)
        val frame = frames.single()
        assertEquals(0x0D, frame.cmd)
        assertEquals(0x01, frame.a1)
        assertEquals(0x14, frame.a2)
        // 解混淆后的载荷与明文黄金一致
        assertEquals(
            "303234333338303030303030e11a00000000c818f6010100000034fe63006900000000000000",
            frame.payload.toHex(),
        )
        assertEquals(0, decoder.rejected.size)
        assertEquals(0, decoder.pendingBytes.size)
    }

    @Test
    fun `明文流锁定模式后原样解码`() {
        val decoder = StreamDecoder()
        val frame = demoFrame(byteArrayOf(0x2c, 0x00, 0x53, 0x01, 0x00, 0x01, 0x00))

        assertEquals(listOf(0x06), decoder.feed(frame).map { it.cmd })
        assertEquals(0, decoder.encodingMode)
        // 重复喂同一条明文帧，不做任何异或
        repeat(3) {
            assertEquals(listOf(0x06), decoder.feed(frame).map { it.cmd })
        }
        assertEquals(0, decoder.encodingMode)
    }

    @Test
    fun `模式一旦锁定终生不变`() {
        val decoder = StreamDecoder()
        // 首包是混淆头部 → 锁定 0x55
        val wire = demoFrame(byteArrayOf(0x2c, 0x00, 0x53, 0x01, 0x00, 0x01, 0x00)).let { obfuscate(it) }
        assertEquals(1, decoder.feed(wire).size)
        assertEquals(0x55, decoder.encodingMode)

        // 之后再喂明文头部会被再次异或，因此不会当成帧解码，但模式仍是 0x55
        assertEquals(emptyList<IbwalkFrame>(), decoder.feed(demoFrame(ByteArray(7))))
        assertEquals(0x55, decoder.encodingMode)
    }

    @Test
    fun `混淆模式下头部读作00FF`() {
        val decoder = StreamDecoder()
        val handshake = "55aa02ff0103fafe".hexToBytes().let { obfuscate(it) }
        assertEquals("00ff57aa5456afab", handshake.toHex())
        val frames = decoder.feed(handshake)
        assertEquals(listOf(0xFF), frames.map { it.cmd })
        assertEquals(0x03, frames.single().a2)
    }

    @Test
    fun `不按文档里的ACK规则过滤帧`() {
        // 分析文档写过 A2 = CMD ^ 0x05，但 Dart 代码从未实现或断言它，且黄金帧直接反例：
        // 0x0D^0x05=0x08 ≠ 0x14，0x0A^0x05=0x0F ≠ 0x14/0xBC。若据此丢帧就会丢掉真车数据，
        // 因此这里锁定「解码不看 A2 与 CMD 的关系」（notes §1.5 末）。
        val golden =
            "55aa280d0114303234333338303030303030e11a00000000c818f6010100000034fe6300690000000000000090f8"
        val frame = decodeFrame(golden.hexToBytes()).frame
        assertEquals(0x0D, frame!!.cmd)
        assertEquals(0x14, frame.a2)
        assertEquals(1, StreamDecoder().feed(golden.hexToBytes()).size)
        // 两条官方查询的 A2 都不满足 A2 = CMD ^ 0x05
        assertEquals(2, kOfficialPollQueries.count { it.a2 != (it.cmd xor 0x05) })
    }
}
