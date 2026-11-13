package com.iwalk.core.analysis

import com.iwalk.protocol.IbwalkFrame
import com.iwalk.protocol.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧组统计的 JVM 覆盖（notes/domain-logic.md §6.1 / §7 frame_analysis 六条期望）。
 * 用例逐条移植自 `app_v2/test/frame_analysis_test.dart`。
 */
class FrameAnalysisTest {

    private fun frame(cmd: Int, a1: Int, a2: Int, payloadHex: String) =
        IbwalkFrame(cmd, a1, a2, payloadHex.hexToBytes())

    private fun List<OffsetStat>.statAt(off: Int): OffsetStat =
        single { it.offset == off }

    @Test
    fun `按 CMD A1 A2 分组并报告逐偏移变化`() {
        val groups = analyseFrames(
            listOf(
                frame(0x06, 1, 5, "6400000000"), // 100
                frame(0x06, 1, 5, "c800000000"), // 200
                frame(0x06, 1, 5, "2c01000000"), // 300
                frame(0x0A, 1, 1, "aabbcc"),
            ),
        )
        assertEquals(2, groups.size)

        val g6 = groups.single { it.cmd == 0x06 }
        assertEquals(3, g6.frames)
        assertEquals(5, g6.payloadLength)
        assertTrue(g6.lengthStable)
        assertEquals("0x06/0x01/0x05", g6.key)

        val off0 = g6.offsets.statAt(0)
        assertEquals(3, off0.distinct)
        assertFalse(off0.constant)
        assertEquals(0x2c, off0.min)
        assertEquals(0xc8, off0.max)
        assertEquals(100, off0.u16leMin)
        assertEquals(300, off0.u16leMax)

        // offset 1 是 16 位值的高字节，同样在动
        assertFalse(g6.offsets.statAt(1).constant)

        // offsets 2..4 在本样本内不动
        for (off in listOf(2, 3, 4)) {
            val stat = g6.offsets.statAt(off)
            assertTrue("offset $off 应恒定", stat.constant)
            assertEquals(1, stat.distinct)
        }
        // 偏移 3 为奇数、偏移 4 没有后继字节：都不给 u16le
        assertNull(g6.offsets.statAt(3).u16leMin)
        assertNull(g6.offsets.statAt(4).u16leMin)

        // 第二组同样带 u16le（长度 3：偏移 0 有后继，偏移 2 没有）
        val g10 = groups.single { it.cmd == 0x0A }
        assertEquals(3, g10.payloadLength)
        assertEquals(0xBBAA, g10.offsets.statAt(0).u16leMin)
        assertNull(g10.offsets.statAt(2).u16leMin)
    }

    @Test
    fun `单样本帧组由帧数标记样本不足`() {
        val groups = analyseFrames(listOf(frame(0x0A, 1, 1, "aabbcc")))
        assertEquals(1, groups.single().frames)
        assertTrue(groups.single().offsets.all { it.constant })
    }

    @Test
    fun `载荷长度混合时 lengthStable 为假`() {
        val groups = analyseFrames(
            listOf(
                frame(0x06, 1, 5, "6400"),
                frame(0x06, 1, 5, "640000"),
            ),
        )
        assertFalse(groups.single().lengthStable)
        assertEquals(3, groups.single().payloadLength)
        // 只包含该偏移的帧参与统计：偏移 2 只有 1 个样本
        assertEquals(1, groups.single().offsets.statAt(2).distinct)
    }

    @Test
    fun `超出 maxOffset 的偏移不报告`() {
        val groups = analyseFrames(listOf(frame(0x06, 1, 5, "00112233445566778899")), maxOffset = 4)
        assertEquals(4, groups.single().offsets.size)
        assertEquals(listOf(0, 1, 2, 3), groups.single().offsets.map { it.offset })
    }

    @Test
    fun `组顺序确定且升序`() {
        val groups = analyseFrames(
            listOf(
                frame(0x0A, 2, 1, "00"),
                frame(0x06, 2, 1, "00"),
                frame(0x06, 1, 2, "00"),
            ),
        )
        assertEquals(
            listOf(Triple(0x06, 1, 2), Triple(0x06, 2, 1), Triple(0x0A, 2, 1)),
            groups.map { Triple(it.cmd, it.a1, it.a2) },
        )
        assertEquals(listOf("0x06/0x01/0x02", "0x06/0x02/0x01", "0x0a/0x02/0x01"), groups.map { it.key })
    }

    @Test
    fun `空输入没有帧组`() {
        assertTrue(analyseFrames(emptyList()).isEmpty())
        assertTrue(analyseFrames(listOf(), maxOffset = 4).isEmpty())
    }

    @Test
    fun `偏移分析文本逐字节对齐 Dart 输出`() {
        val groups = analyseFrames(
            listOf(
                frame(0x06, 1, 5, "6400000000"),
                frame(0x06, 1, 5, "c800000000"),
                frame(0x06, 1, 5, "2c01000000"),
                frame(0x0A, 1, 1, "aabb"),
            ),
        )
        val expected = buildString {
            append("# iWalk 帧偏移分析（仅字节统计，未含单位/语义）\n")
            append("# session: demo-1\n")
            append("\n## 0x06/0x01/0x05  frames=3 len=5\n")
            append("off  0: distinct=3 44..200  u16le 100..300\n")
            append("off  1: distinct=2 0..1\n")
            append("off  2: distinct=1 0..0 CONST  u16le 0..0\n")
            append("off  3: distinct=1 0..0 CONST\n")
            append("off  4: distinct=1 0..0 CONST\n")
            append("\n## 0x0a/0x01/0x01  frames=1 len=2\n")
            append("# 样本不足（<2），偏移不变不能作为字段证据\n")
            append("off  0: distinct=1 170..170 CONST  u16le 48042..48042\n")
            append("off  1: distinct=1 187..187 CONST\n")
        }
        assertEquals(expected, offsetReportText(groups, "demo-1"))
    }

    @Test
    fun `偏移分析文本无会话时写 none 且不含字段语义`() {
        val groups = analyseFrames(listOf(frame(0x06, 1, 5, "64000000")))
        val text = offsetReportText(groups, null)
        assertTrue(text.startsWith("# iWalk 帧偏移分析（仅字节统计，未含单位/语义）\n# session: none\n"))
        assertTrue(text.contains("## 0x06/0x01/0x05  frames=1 len=4\n"))
        assertTrue(text.contains("# 样本不足（<2），偏移不变不能作为字段证据\n"))
        assertFalse(text.contains("速度"))
        assertFalse(text.contains("电量"))
    }

    @Test
    fun `载荷长度不一致时报告标注`() {
        val groups = analyseFrames(
            listOf(
                frame(0x06, 1, 5, "6400"),
                frame(0x06, 1, 5, "640000"),
            ),
        )
        assertTrue(offsetReportText(groups, "s").contains("## 0x06/0x01/0x05  frames=2 len=3 (长度不一致)\n"))
    }
}
