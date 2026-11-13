package com.iwalk.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 官方 App 5.4.1 的两条固定查询帧（notes/domain-logic.md §5.1 表 + E71 修订）。
 * 明文与线上（⊕0x55）逐字节黄金比对；顺序即优先级。
 *
 * 第二条原为 0xbc 哨兵（bebe，无信息量）；E71（2026-11-13）证明 0x2a 是官方
 * 「详细信息」页真实轮询的查询，且应答载荷 off2 = 预计剩余里程 km，故在同一
 * 1010 ms 时隙换成 0x2a——周期不变、总线负载不变。黄金值不是推算的：
 * `55aa030a012a22a5ff` / `00ff565f547f77f0aa` 直接来自真车截获
 * （`captures/20261113_detail/att_lines.txt` 的混淆原样逐字节 ⊕0x55 复原）。
 */
class PollQueriesTest {

    @Test
    fun `两条官方查询帧逐字节匹配黄金值`() {
        val goldenPlaintext = listOf(
            "55aa030a011426b7ff" to 110,
            "55aa030a012a22a5ff" to 1010,
        )
        assertEquals(2, kOfficialPollQueries.size)
        kOfficialPollQueries.zip(goldenPlaintext).forEach { (query, expected) ->
            val (hex, period) = expected
            assertEquals(hex, query.build().encode().toHex())
            assertEquals(period, query.periodMs)
        }
    }

    @Test
    fun `线上编码对整帧逐字节异或0x55`() {
        // §1.5 黄金向量：明文 → 线（第二条 = E71 真车截获原样）
        assertEquals(
            "00ff565f544173e2aa",
            kOfficialPollQueries[0].build().encodeWire().toHex(),
        )
        assertEquals(
            "00ff565f547f77f0aa",
            kOfficialPollQueries[1].build().encodeWire().toHex(),
        )
    }

    @Test
    fun `查询帧不改动明文长度也不重算校验`() {
        val query = kOfficialPollQueries[0]
        val plain = query.build().encode()
        val wire = query.build().encodeWire()
        assertEquals(plain.size, wire.size)
        // 混淆后头部即 00 FF
        assertEquals("00ff", wire.take(2).toHex())
    }

    @Test
    fun `查询的寄存器与期望应答长度`() {
        val q = kOfficialPollQueries[0]
        assertEquals(0x0A, q.cmd)
        assertEquals(0x01, q.a1)
        assertEquals(0x14, q.a2)
        assertEquals(listOf(0x26), q.payload.toList())
        assertEquals("cmd=0xa a2=0x14 @110ms", q.toString())
    }

    @Test
    fun `第二条查询是0x2a预计里程帧且载荷为期望应答长度`() {
        val q = kOfficialPollQueries[1]
        assertEquals(0x0A, q.cmd)
        assertEquals(0x01, q.a1)
        assertEquals(0x2A, q.a2)
        // 0x2a 应答载荷 34 字节（E71），查询载荷字节 = 期望应答长度
        assertEquals(listOf(0x22), q.payload.toList())
        assertEquals(1010, q.periodMs)
        assertEquals("cmd=0xa a2=0x2a @1010ms", q.toString())
    }
}
