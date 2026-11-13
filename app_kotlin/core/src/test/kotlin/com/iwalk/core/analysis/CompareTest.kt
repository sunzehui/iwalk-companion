package com.iwalk.core.analysis

import com.iwalk.protocol.IbwalkFrame
import com.iwalk.protocol.hexToBytes
import com.iwalk.protocol.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A/B 采集对照的 JVM 覆盖（notes/domain-logic.md §6.3 / §6.4 / §7 compare 六条期望）。
 * 用例逐条移植自 `app_v2/test/compare_test.dart`。
 */
class CompareTest {

    private fun frame(cmd: Int, a1: Int, a2: Int, payloadHex: String) =
        IbwalkFrame(cmd, a1, a2, payloadHex.hexToBytes())

    private fun capture(
        frames: List<IbwalkFrame>,
        rejected: List<ByteArray> = emptyList(),
        pending: ByteArray = ByteArray(0),
    ) = ReplayResult(frames = frames, rejected = rejected, pending = pending)

    private fun sameGroup(payloadHex: String, cmd: Int = 0x06, a1: Int = 1, a2: Int = 5) =
        capture(listOf(frame(cmd, a1, a2, payloadHex)))

    @Test
    fun `共有组只报告分布不同的偏移`() {
        val comparison = compareCaptures(sameGroup("0a00ff"), sameGroup("1500ff"))

        val group = comparison.groups.single()
        assertTrue(group.shared)
        assertFalse(group.onlyInA)
        assertFalse(group.onlyInB)
        assertEquals(listOf(0), group.differences.map { it.offset })
        assertEquals(0x0a, group.differences.single().a!!.min)
        assertEquals(0x15, group.differences.single().b!!.max)
        assertFalse(group.lengthDiffers)
        assertEquals(1, comparison.differingOffsets)
        assertTrue(comparison.hasDifferences)
    }

    @Test
    fun `只出现在一侧的帧组被保留`() {
        val onlyA = capture(listOf(frame(0x0A, 1, 1, "aabb")))
        val onlyB = capture(listOf(frame(0x06, 1, 5, "6400")))

        val comparison = compareCaptures(onlyA, onlyB)
        assertEquals(1, comparison.onlyACount)
        assertEquals(1, comparison.onlyBCount)
        assertEquals(0, comparison.sharedGroups.size)
        assertEquals(0, comparison.differingOffsets)
        // 字典序（字符串），不是数值序
        assertEquals(listOf("0x06/0x01/0x05", "0x0a/0x01/0x01"), comparison.groups.map { it.key })
        assertEquals(listOf("仅在 B", "仅在 A"), comparison.groups.map { if (it.onlyInB) "仅在 B" else "仅在 A" })
        assertTrue(comparison.hasDifferences)
    }

    @Test
    fun `字节分布相同时没有差异`() {
        val comparison = compareCaptures(sameGroup("0a00ff"), sameGroup("0a00ff"))

        assertEquals(1, comparison.sharedGroups.size)
        assertEquals(0, comparison.differingOffsets)
        assertTrue(comparison.groups.single().differences.isEmpty())
        assertFalse(comparison.hasDifferences)
    }

    @Test
    fun `载荷长度变化逐偏移报告`() {
        val comparison = compareCaptures(sameGroup("0102"), sameGroup("0102ff"))

        val group = comparison.groups.single()
        assertTrue(group.lengthDiffers)
        assertEquals(2, group.a!!.payloadLength)
        assertEquals(3, group.b!!.payloadLength)
        val offset2 = group.offsets.single { it.offset == 2 }
        assertTrue(offset2.onlyInB)
        assertNull(offset2.a)
        assertEquals(0xff, offset2.b!!.min)
        assertEquals(listOf(2), group.differences.map { it.offset })
    }

    @Test
    fun `拒收块与不完整缓冲各自留在原来的采集里`() {
        val rejectedA = listOf("55aa02ff0103dead".hexToBytes())
        val a = capture(listOf(frame(0x06, 1, 5, "0a00ff")), rejectedA, "55aa06".hexToBytes())
        val b = capture(listOf(frame(0x06, 1, 5, "0a00ff")))

        val comparison = compareCaptures(a, b)
        assertEquals(1, comparison.a.rejected.size)
        assertEquals("55aa02ff0103dead", comparison.a.rejected.single().toHex())
        assertEquals(3, comparison.a.pending.size)
        assertTrue(comparison.b.rejected.isEmpty())
        assertEquals(0, comparison.b.pending.size)
    }

    @Test
    fun `对照报告写明计数与差异且不含字段语义`() {
        val comparison = compareCaptures(sameGroup("0a00ff"), sameGroup("1500ff"))
        val report = comparisonReport(comparison)

        assertTrue(report.contains("仅字节分布"))
        assertTrue(report.contains("# 共有组 1，仅 A 0，仅 B 0，差异偏移 1"))
        assertTrue(report.contains("0x06/0x01/0x05"))
        assertTrue(report.contains("off 00:"))
        assertTrue(report.contains("distinct=1"))
        assertTrue(report.contains("不保证逐帧时间对齐"))
        assertFalse(report.contains("速度"))
    }

    @Test
    fun `对照报告逐字节对齐 Dart 输出`() {
        val comparison = compareCaptures(sameGroup("0a00ff"), sameGroup("1400ff"))
        val expected = buildString {
            append("# iWalk 离线采集对照（仅字节分布，未含单位/语义）\n")
            append("# A: 帧 1，拒收块 0，不完整 0 B\n")
            append("# B: 帧 1，拒收块 0，不完整 0 B\n")
            append("# 共有组 1，仅 A 0，仅 B 0，差异偏移 1\n")
            append("\n## 0x06/0x01/0x05  A frames=1 len=3  B frames=1 len=3\n")
            append("off 00: distinct=1 10..10 CONST  u16le 10..10  |  distinct=1 20..20 CONST  u16le 20..20\n")
            append("\n# 说明：对照只比较两份采集的字节分布，不保证逐帧时间对齐；偏移差异不能单独证明字段含义。\n")
        }
        assertEquals(expected, comparisonReport(comparison))
    }

    @Test
    fun `对照报告包含仅在一侧的组与长度不一致标记`() {
        val a = capture(
            listOf(
                frame(0x06, 1, 5, "6400"),
                frame(0x0A, 1, 1, "aabb"),
            ),
        )
        val b = capture(
            listOf(
                frame(0x06, 1, 5, "640000"),
                frame(0x0B, 1, 1, "00"),
            ),
        )
        val report = comparisonReport(compareCaptures(a, b))

        assertTrue(report.contains("## 0x0a/0x01/0x01  仅在 A\n"))
        assertTrue(report.contains("## 0x0b/0x01/0x01  仅在 B\n"))
        // 每一侧内部长度稳定，所以不打印“长度不一致”标记；跨侧长度变化只体现在偏移上
        assertTrue(report.contains("## 0x06/0x01/0x05  A frames=1 len=2  B frames=1 len=3\n"))
        assertTrue(report.contains("off 02: —  |  distinct=1 0..0 CONST\n"))
    }

    @Test
    fun `分布一致的共有组在报告里写明一致`() {
        val a = capture(listOf(frame(0x06, 1, 5, "0a00ff"), frame(0x06, 1, 5, "0b00ff")))
        val b = capture(listOf(frame(0x06, 1, 5, "0a00ff")))
        val report = comparisonReport(compareCaptures(a, b))

        // 偏移 0 的 min/max/distinct 不同 → 有差异，不写“一致”
        assertFalse(report.contains("# 已观察偏移的分布一致；样本量不同仍可能掩盖差异"))

        val identical = comparisonReport(compareCaptures(a, a))
        assertTrue(identical.contains("# 已观察偏移的分布一致；样本量不同仍可能掩盖差异\n"))
    }
}
