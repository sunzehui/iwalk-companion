package com.iwalk.core.analysis

import com.iwalk.protocol.IbwalkFrame
import com.iwalk.protocol.hexToBytes
import com.iwalk.protocol.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线粘贴解析的 JVM 覆盖（notes/domain-logic.md §6.2 / §7 replay 七条期望）。
 * 用例逐条移植自 `app_v2/test/replay_test.dart`，另补“全部校验失败”与报告文本。
 */
class ReplayTest {

    private fun frame(cmd: Int, a1: Int, a2: Int, payloadHex: String) =
        IbwalkFrame(cmd, a1, a2, payloadHex.hexToBytes())

    private fun frameHex(cmd: Int, a1: Int, a2: Int, payloadHex: String) =
        frame(cmd, a1, a2, payloadHex).encode().toHex()

    private fun spaced(hex: String, sep: String = " ") = hex.chunked(2).joinToString(sep)

    @Test
    fun `每行一个包`() {
        val a = frameHex(0x06, 1, 5, "6400")
        val b = frameHex(0x0A, 1, 1, "aabb")
        val result = replayHex("$a\n$b\n")

        assertEquals(2, result.frames.size)
        assertEquals(listOf(0x06, 0x0A), result.frames.map { it.cmd })
        assertTrue(result.rejected.isEmpty())
        assertEquals(0, result.pending.size)
    }

    @Test
    fun `容忍空格冒号连字符与方向前缀`() {
        val hex = frameHex(0x06, 1, 5, "6400000000")
        val text = listOf(
            "rx: ${spaced(hex)}",
            "notify: ${hex.uppercase()}",
            "tx> ${spaced(hex, "-")}",
            "n${hex}",
        ).joinToString("\n")

        val result = replayHex(text)
        assertEquals(4, result.frames.size)
        assertTrue(result.frames.all { it.cmd == 0x06 && it.a1 == 1 && it.a2 == 5 })
        assertTrue(result.rejected.isEmpty())
        assertEquals(0, result.pending.size)
    }

    @Test
    fun `忽略说明行与注释`() {
        val hex = frameHex(0x06, 1, 5, "6400")
        val text = "# capture 2026-10-30\n设备 A 报文采集如下：\n\n$hex\n"

        val result = replayHex(text)
        assertEquals(1, result.frames.size)
        assertTrue(result.rejected.isEmpty())
        assertEquals(0, result.pending.size)
    }

    @Test
    fun `校验失败的块原样保留且后续帧仍能解出`() {
        val good = frameHex(0x06, 1, 5, "6400")
        val result = replayHex("55aa02ff0103dead\n$good")

        assertEquals(1, result.frames.size)
        assertEquals(0x06, result.frames.single().cmd)
        assertEquals(1, result.rejected.size)
        assertEquals("55aa02ff0103dead", result.rejected.single().toHex())
        assertEquals(0, result.pending.size)
    }

    @Test
    fun `跨行拆分的帧会被重新拼起来`() {
        val hex = frameHex(0x06, 1, 5, "6400000000")
        val result = replayHex(hex.substring(0, 6) + "\n" + hex.substring(6))

        assertEquals(1, result.frames.size)
        assertEquals(0x64, result.frames.single().payload[0].toInt() and 0xFF)
        assertTrue(result.rejected.isEmpty())
        assertEquals(0, result.pending.size)
    }

    @Test
    fun `尾部不完整帧作为 pending 而不是丢失`() {
        val result = replayHex("55aa06")

        assertTrue(result.frames.isEmpty())
        assertTrue(result.rejected.isEmpty())
        assertEquals(3, result.pending.size)
        assertEquals("55aa06", result.pending.toHex())
        assertEquals(3, result.totalBytes)
    }

    @Test
    fun `空输入什么都不产出`() {
        for (text in listOf("", "   ", "\n\n\n", "  \n \r\n")) {
            val result = replayHex(text)
            assertTrue(result.frames.isEmpty())
            assertTrue(result.rejected.isEmpty())
            assertEquals(0, result.pending.size)
            assertEquals(0, result.totalBytes)
        }
        assertTrue(extractHexPackets("  \n ").isEmpty())
    }

    @Test
    fun `全是说明文字时既不报错也不产出`() {
        val result = replayHex("这段文字没有任何十六进制\n# 注释\nhello world\n")
        assertTrue(result.frames.isEmpty())
        assertTrue(result.rejected.isEmpty())
        assertEquals(0, result.pending.size)
    }

    @Test
    fun `所有块校验失败时帧为空但拒收字节齐全`() {
        val result = replayHex("55aa02ff0103dead\n55aa02ff0103beef")

        assertTrue(result.frames.isEmpty())
        assertEquals(2, result.rejected.size)
        assertEquals(0, result.pending.size)
        assertEquals(16, result.totalBytes)
    }

    @Test
    fun `奇数个半字节被静默截断`() {
        val hex = frameHex(0x06, 1, 5, "6400")
        val result = replayHex(hex + "a") // 13 个半字节 → 丢掉最后一个

        assertEquals(1, result.frames.size)
        assertTrue(result.rejected.isEmpty())
        assertEquals(0, result.pending.size)
    }

    @Test
    fun `不足四个 hex 字符的行被跳过`() {
        assertTrue(extractHexPackets("ab\nabc\nabcd").map { it.toHex() } == listOf("abcd"))
        val result = replayHex("55\n66\nabcd")
        assertTrue(result.frames.isEmpty())
        assertTrue(result.rejected.isEmpty())
        // abcd 不是 55AA 头：静默重同步后没有剩余缓冲
        assertEquals(0, result.pending.size)
    }

    @Test
    fun `总字节数等于帧编码加拒收块加不完整缓冲`() {
        val good = frameHex(0x06, 1, 5, "6400000000")
        val result = replayHex("$good\n55aa02ff0103dead\n55aa06")

        assertEquals(1, result.frames.size)
        assertEquals(1, result.rejected.size)
        assertEquals(3, result.pending.size)
        val expected = result.frames.sumOf { it.encode().size } +
            result.rejected.sumOf { it.size } + result.pending.size
        assertEquals(expected, result.totalBytes)
        // 载荷 5 B → 整帧 13 B；拒收块 8 B；不完整缓冲 3 B
        assertEquals(13 + 8 + 3, result.totalBytes)
    }

    @Test
    fun `离线分析报告逐字节对齐 Dart 输出`() {
        val hex = frameHex(0x06, 1, 5, "6400")
        val result = replayHex("$hex\n$hex\n55aa02ff0103dead\n55aa06")
        val expected = buildString {
            append("# iWalk 离线分析报告（字节视角，未含单位/语义）\n")
            append("# 帧 2，拒收块 1，不完整缓冲 3 B\n")
            append("\n## 0x06/0x01/0x05  frames=2 len=2\n")
            append("off  0: distinct=1 100..100 CONST  u16le 100..100\n")
            append("off  1: distinct=1 0..0 CONST\n")
            append("\n# 拒收字节（校验失败/噪声，已保留）\n")
            append("55aa02ff0103dead\n")
            append("\n# 不完整帧缓冲 3 B\n")
            append("55aa06\n")
        }
        assertEquals(expected, offlineReportText(result))
    }

    @Test
    fun `没有拒收与不完整时报告不含额外段且无字段语义`() {
        val hex = frameHex(0x06, 1, 5, "6400")
        val text = offlineReportText(replayHex("$hex\n$hex"))
        assertTrue(text.startsWith("# iWalk 离线分析报告（字节视角，未含单位/语义）\n# 帧 2，拒收块 0，不完整缓冲 0 B\n"))
        assertFalse(text.contains("拒收字节"))
        assertFalse(text.contains("不完整帧缓冲"))
        for (word in listOf("速度", "电量", "温度", "里程")) {
            assertFalse("报告不得出现语义字段名：$word", text.contains(word))
        }
    }
}
