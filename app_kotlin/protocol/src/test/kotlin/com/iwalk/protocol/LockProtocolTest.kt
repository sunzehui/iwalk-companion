package com.iwalk.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两条静态推导的固定锁帧（notes/domain-logic.md §4.4 末、§8 lock_control_test 1）。
 * 本模块只提供编码：真车效果已于 2026-11-12 实测（E66），但不导出任何通用写接口。
 */
class LockProtocolTest {

    @Test
    fun `锁帧与解锁帧逐字节匹配黄金值`() {
        assertEquals("55aa040a037001007dff", LockProtocol.frame(lock = true).toHex())
        assertEquals("55aa040a037101007cff", LockProtocol.frame(lock = false).toHex())
    }

    @Test
    fun `锁帧长度为10字节`() {
        assertEquals(10, LockProtocol.frame(lock = true).size)
        assertEquals(10, LockProtocol.frame(lock = false).size)
    }

    @Test
    fun `锁帧寄存器组合为0x0A03_70或71加载荷0100`() {
        // 用帧模型独立拼一次，确认常量组合没有漂移
        val lock = IbwalkFrame(
            cmd = LockProtocol.COMMAND,
            a1 = LockProtocol.A1,
            a2 = LockProtocol.LOCK_A2,
            payload = byteArrayOf(LockProtocol.PAYLOAD_VALUE.toByte(), 0),
        )
        assertEquals("55aa040a037001007dff", lock.encode().toHex())
        val unlock = IbwalkFrame(
            cmd = LockProtocol.COMMAND,
            a1 = LockProtocol.A1,
            a2 = LockProtocol.UNLOCK_A2,
            payload = byteArrayOf(LockProtocol.PAYLOAD_VALUE.toByte(), 0),
        )
        assertEquals("55aa040a037101007cff", unlock.encode().toHex())
    }

    @Test
    fun `线上锁帧等长且头部在线上读作00FF`() {
        val wire = LockProtocol.wireFrame(lock = true)
        assertEquals(LockProtocol.frame(lock = true).size, wire.size)
        assertEquals("00ff", wire.take(2).toHex())
    }

    @Test
    fun `hex助手给出小写十六进制`() {
        assertEquals("55aa040a037001007dff", LockProtocol.hex(lock = true))
        assertEquals("55aa040a037101007cff", LockProtocol.hex(lock = false))
    }

    @Test
    fun `解锁帧与锁帧只差A2与校验和`() {
        val lock = LockProtocol.frame(lock = true)
        val unlock = LockProtocol.frame(lock = false)
        // 前 6 字节相同（头、L、CMD、A1），A2 差 1，末两字节校验和随之变化
        assertTrue(lock.take(5).toHex() == unlock.take(5).toHex())
        assertEquals(0x70, lock[5].toInt() and 0xFF)
        assertEquals(0x71, unlock[5].toInt() and 0xFF)
    }
}
