package com.iwalk.core.session

import com.iwalk.core.demo.DemoProfile
import com.iwalk.core.model.*
import com.iwalk.protocol.IbwalkFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionIntegrityTest {
    @Test fun `recalibration retains receive time and capture is coherent`() = runTest {
        val packets = MutableSharedFlow<IncomingPacket>(extraBufferCapacity = 8)
        val transport = object : BluetoothTransport {
            override val id = "test"
            override val label = "test"
            override val isDemo = false
            override val incoming = packets
            override val events = MutableSharedFlow<TransportEvent>()
            override val state = TransportState.connected
            override suspend fun connect() {}
            override suspend fun disconnect() {}
        }
        val session = SessionManager(transport, DemoProfile.set(), "test", backgroundScope)
        runCurrent()
        packets.emit(IncomingPacket(IbwalkFrame(6, 1, 5, byteArrayOf(100, 0, 80, 0, 1, 0, 0)).encode()))
        runCurrent()
        val received = session.snapshot.value.values.getValue(TelemetryField.speed).at
        val snapshotTime = session.snapshot.value.updatedAt
        Thread.sleep(5)
        session.setCalibration(DemoProfile.set())
        assertEquals(received, session.snapshot.value.values.getValue(TelemetryField.speed).at)
        assertEquals(snapshotTime, session.snapshot.value.updatedAt)
        val command = LockCommandRecord(received, true, "55aa040a037001007dff", true, false)
        session.recordLockCommand(command)
        val capture = session.capture()
        assertEquals(listOf(command), capture.commands)
        assertEquals(1, capture.frames.size)
        assertEquals(capture.frames.size, capture.events.size)
        assertEquals(1, capture.rawLines.size)
        assertEquals(capture.frames.size, capture.snapshot.framesDecoded)
        session.stop()
    }

    @Test fun `malicious offset cannot overflow bounds check`() {
        for (type in ScalarType.entries) {
            assertNull(type.read(byteArrayOf(1, 2), Int.MAX_VALUE))
            assertFalse(FieldMapping(TelemetryField.speed, Int.MAX_VALUE, type).fits(2))
            assertFalse(FieldMapping(TelemetryField.speed, -1, type).fits(2))
        }
    }

    @Test fun `lock policy blocks real control but allows connected simulation`() {
        assertNotNull(lockBlockedReason(false, false, true, true, null))
        assertNotNull(lockBlockedReason(true, false, false, true, null))
        assertNotNull(lockBlockedReason(true, false, true, false, null))
        assertNotNull(lockBlockedReason(true, false, true, true, -1.01))
        assertNotNull(lockBlockedReason(true, false, true, true, Double.NaN))
        assertNull(lockBlockedReason(true, false, true, true, 1.0))
        // 速度未知不再放行（E66：真车实测时闸门曾因此完全失效）
        assertEquals(
            "速度未标定，无法证明车辆静止，已阻止操作",
            lockBlockedReason(true, false, true, true, null),
        )
        // 演示会话不读速度也不影响可用性
        assertNull(lockBlockedReason(false, true, true, true, 9.0))
        assertNull(lockBlockedReason(false, true, true, true, null))
        assertNotNull(lockBlockedReason(false, true, false, true, 9.0))
    }
}
