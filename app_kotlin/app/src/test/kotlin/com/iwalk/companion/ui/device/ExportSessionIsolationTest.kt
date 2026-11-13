package com.iwalk.companion.ui.device

import com.iwalk.companion.ble.*
import com.iwalk.companion.data.*
import com.iwalk.core.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ExportSessionIsolationTest {
    @get:Rule val tmp = TemporaryFolder()
    private class Link(override val id: String) : BluetoothTransport, LockControl {
        override val label = id
        override val isDemo = false
        override val incoming = emptyFlow<IncomingPacket>()
        override val events = emptyFlow<TransportEvent>()
        override var state = TransportState.connected
        override val lockControlAvailable get() = state == TransportState.connected
        override suspend fun connect() {}
        override suspend fun disconnect() { state = TransportState.disconnected }
        override suspend fun setLock(lock: Boolean) {}
    }
    private fun device(id: String) = ScannedDevice(id, "MiniRobot:IWK1", -50, true, "1", emptyList())
    @Test fun `blocked export retains original sessions command records after switch`() = runBlocking {
        val controller = DeviceSessionController(
            lockSettingsStore = MemoryLockSettingsStore().apply { enabled = true },
            realLinkFactory = RealLinkFactory { Link(it.address) },
            exportStore = FileExportStore { tmp.root },
        )
        try {
            controller.connectReal(device("A"))
            controller.sendLock(true)
            val session = controller.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(controller)!!
            val monitor = session.javaClass.getDeclaredField("monitor").apply { isAccessible = true }.get(session)!!
            val exporting = Thread { runBlocking { controller.exportSession() } }
            synchronized(monitor) {
                exporting.start()
                val deadline = System.nanoTime() + 2_000_000_000
                while (exporting.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, exporting.state)
                controller.stopSession()
                runBlocking {
                    assertTrue(controller.connectReal(device("B")))
                    controller.sendLock(false)
                }
            }
            exporting.join(5000)
            assertFalse(exporting.isAlive)
            val evidence = controller.lastExport.value!!.evidenceFile.readText()
            assertTrue(evidence.contains("\"lock\""))
            assertFalse(evidence.contains("\"unlock\""))
            assertEquals(false, controller.ui.value.lockCommands.single().lock)
        } finally { controller.close() }
    }
}
