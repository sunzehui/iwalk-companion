package com.iwalk.companion.ui.device

import com.iwalk.companion.data.MemoryLockSettingsStore
import com.iwalk.companion.data.FileExportStore
import com.iwalk.core.demo.DemoProfile
import com.iwalk.core.store.CalibrationStore
import com.iwalk.core.store.InMemoryKeyValueStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SettingsAndLockTest {
    @get:Rule val tmp = TemporaryFolder()
    private suspend fun ready(controller: DeviceSessionController) {
        withTimeout(5000) { while (controller.ui.value.frames.isEmpty()) delay(10) }
    }

    @Test fun `invalid import preserves calibration and valid import persists without contaminating demo`() = runBlocking {
        val store = CalibrationStore(InMemoryKeyValueStore())
        val controller = DeviceSessionController(calibrationStore = store, demoPeriodMillis = 20)
        try {
            val user = DemoProfile.set().copy(profiles = DemoProfile.set().profiles.map { it.copy(name = "user") })
            assertNull(controller.importCalibration(user.encode()))
            assertNotNull(controller.importCalibration("{bad json"))
            assertEquals("user", store.load()!!.profiles.first().name)
            controller.startDemo()
            ready(controller)
            controller.clearCalibration()
            assertNull(store.load())
            assertTrue(controller.ui.value.calibration.profiles.isNotEmpty())
            assertFalse(controller.ui.value.hasUserCalibration)
            assertTrue(controller.ui.value.snapshot.values.isNotEmpty())
        } finally { controller.close() }
    }

    @Test fun `import immediately decodes retained real notifications and clear removes values`() = runBlocking {
        val incoming = kotlinx.coroutines.flow.MutableSharedFlow<com.iwalk.core.session.IncomingPacket>(replay = 1)
        val transport = object : com.iwalk.core.session.BluetoothTransport {
            override val id = "fake-real"
            override val label = "fake-real"
            override val isDemo = false
            override val incoming = incoming
            override val events = kotlinx.coroutines.flow.emptyFlow<com.iwalk.core.session.TransportEvent>()
            override val state = com.iwalk.core.session.TransportState.connected
            override suspend fun connect() {}
            override suspend fun disconnect() {}
        }
        val store = CalibrationStore(InMemoryKeyValueStore())
        val controller = DeviceSessionController(calibrationStore = store,
            realLinkFactory = com.iwalk.companion.ble.RealLinkFactory { transport })
        try {
            controller.connectReal(com.iwalk.companion.ble.ScannedDevice("AA:BB:CC:DD:EE:FF", "i.walk:IWK1", -50, true, "1", emptyList()))
            incoming.emit(com.iwalk.core.session.IncomingPacket(com.iwalk.protocol.IbwalkFrame(6, 1, 5, byteArrayOf(100, 0, 80, 0, 1, 0, 0)).encode()))
            ready(controller)
            assertTrue(controller.ui.value.snapshot.values.isEmpty())
            assertNull(controller.importCalibration(DemoProfile.set().encode()))
            assertEquals(1.0, controller.ui.value.snapshot[com.iwalk.core.model.TelemetryField.speed]!!, 0.0)
            val restarted = DeviceSessionController(calibrationStore = store)
            assertTrue(restarted.ui.value.hasUserCalibration)
            restarted.close()
            controller.clearCalibration()
            assertTrue(controller.ui.value.snapshot.values.isEmpty())
            assertEquals(1, controller.ui.value.frames.size)
        } finally { controller.close() }
    }

    @Test fun `overflow and fractional calibration cannot replace valid settings`() {
        val store = CalibrationStore(InMemoryKeyValueStore())
        val controller = DeviceSessionController(calibrationStore = store)
        val valid = DemoProfile.set().encode()
        controller.importCalibration(valid)
        for (bad in listOf(
            valid.replace("\"cmd\": 6", "\"cmd\": 4294967302"),
            valid.replace("\"offset\": 0", "\"offset\": 4294967296"),
            valid.replace("\"offset\": 0", "\"offset\": 0.5"),
        )) {
            assertNotEquals(valid, bad)
            assertNotNull(controller.importCalibration(bad))
            assertEquals(valid, store.load()!!.encode())
        }
        controller.close()
    }

    @Test fun `lock preference survives controller recreation`() {
        val settings = MemoryLockSettingsStore()
        val first = DeviceSessionController(lockSettingsStore = settings)
        assertFalse(first.settings.value.lockControlEnabled)
        first.setLockControlEnabled(true)
        first.close()
        val second = DeviceSessionController(lockSettingsStore = settings)
        assertTrue(second.settings.value.lockControlEnabled)
        second.setLockControlEnabled(false)
        second.close()
        assertFalse(settings.enabled)
    }

    @Test fun `real lock is blocked before any write when speed is uncalibrated`() = runBlocking {
        var writes = 0
        val transport = object : com.iwalk.core.session.BluetoothTransport, com.iwalk.core.session.LockControl {
            override val id = "no-speed"
            override val label = id
            override val isDemo = false
            override val incoming = kotlinx.coroutines.flow.emptyFlow<com.iwalk.core.session.IncomingPacket>()
            override val events = kotlinx.coroutines.flow.emptyFlow<com.iwalk.core.session.TransportEvent>()
            override val state = com.iwalk.core.session.TransportState.connected
            override val lockControlAvailable = true
            override suspend fun connect() {}
            override suspend fun disconnect() {}
            override suspend fun setLock(lock: Boolean) { writes++ }
        }
        val controller = DeviceSessionController(
            realLinkFactory = com.iwalk.companion.ble.RealLinkFactory { transport },
            exportStore = FileExportStore { tmp.root },
        )
        try {
            controller.setLockControlEnabled(true)
            assertTrue(controller.connectReal(com.iwalk.companion.ble.ScannedDevice("AA:BB:CC:DD:EE:FF", "MiniRobot:IWK1", -50, true, "1", emptyList())))
            assertNull(controller.ui.value.snapshot[com.iwalk.core.model.TelemetryField.speed])
            val message = controller.sendLock(true)
            // E66：速度未知曾被当作放行，真车实测后改为默认阻止
            assertEquals("速度未标定，无法证明车辆静止，已阻止操作", message)
            assertEquals(0, writes)
            val record = controller.ui.value.lockCommands.single()
            assertFalse(record.ok)
            assertEquals(message, record.error)
            assertFalse(controller.ui.value.lockBusy)
            controller.exportSession()
            assertTrue(controller.lastExport.value!!.evidenceFile.readText().contains("速度未标定"))
        } finally { controller.close() }
    }

    @Test fun `failed real lock attempt is recorded once and exported as unconfirmed`() = runBlocking {
        var writes = 0
        val incoming = kotlinx.coroutines.flow.MutableSharedFlow<com.iwalk.core.session.IncomingPacket>(replay = 1)
        val transport = object : com.iwalk.core.session.BluetoothTransport, com.iwalk.core.session.LockControl {
            override val id = "failed-lock"
            override val label = id
            override val isDemo = false
            override val incoming = incoming
            override val events = kotlinx.coroutines.flow.emptyFlow<com.iwalk.core.session.TransportEvent>()
            override val state = com.iwalk.core.session.TransportState.connected
            override val lockControlAvailable = true
            override suspend fun connect() {}
            override suspend fun disconnect() {}
            override suspend fun setLock(lock: Boolean) { writes++; error("write failed") }
        }
        // 速度闸门现在要求有读数：先落一份速度标定，再用一帧把速度置为 1.0（允许边界）
        val store = CalibrationStore(InMemoryKeyValueStore()).apply { save(DemoProfile.set()) }
        val controller = DeviceSessionController(
            calibrationStore = store,
            realLinkFactory = com.iwalk.companion.ble.RealLinkFactory { transport },
            exportStore = FileExportStore { tmp.root },
        )
        try {
            controller.setLockControlEnabled(true)
            controller.connectReal(com.iwalk.companion.ble.ScannedDevice("AA:BB:CC:DD:EE:FF", "MiniRobot:IWK1", -50, true, "1", emptyList()))
            incoming.emit(com.iwalk.core.session.IncomingPacket(com.iwalk.protocol.IbwalkFrame(6, 1, 5, byteArrayOf(100, 0, 80, 0, 1, 0, 0)).encode()))
            ready(controller)
            assertEquals(1.0, controller.ui.value.snapshot[com.iwalk.core.model.TelemetryField.speed]!!, 0.0)
            assertEquals("write failed", controller.sendLock(true))
            assertEquals(1, writes)
            assertFalse(controller.ui.value.lockCommands.single().ok)
            assertFalse(controller.ui.value.lockCommands.single().simulated)
            assertFalse(controller.ui.value.lockBusy)
            controller.exportSession()
            val text = controller.lastExport.value!!.evidenceFile.readText()
            assertTrue(text.contains("\"ok\": false"))
            assertTrue(text.contains("\"vehicle_state\": \"unconfirmed\""))
        } finally { controller.close() }
    }

    @Test fun `demo single flight writes one simulated command and export includes it`() = runBlocking {
        val controller = DeviceSessionController(demoPeriodMillis = 20, exportStore = FileExportStore { tmp.root })
        try {
            controller.startDemo()
            ready(controller)
            val first = async { controller.sendLock(true) }
            yield()
            val second = controller.sendLock(false)
            assertTrue(second.contains("发送中"))
            assertTrue(first.await().contains("模拟发送"))
            val commands = controller.ui.value.lockCommands
            assertEquals(1, commands.size)
            assertTrue(commands.single().simulated)
            assertTrue(commands.single().ok)
            assertEquals("55aa040a037001007dff", commands.single().hex)
            assertFalse(controller.ui.value.lockBusy)
            assertTrue(controller.exportSession().startsWith("已导出："))
            val evidence = controller.lastExport.value!!.evidenceFile.readText()
            assertTrue(evidence.contains("\"vehicle_state\": \"unconfirmed\""))
            assertTrue(evidence.contains("\"simulated\": true"))
        } finally { controller.close() }
    }
}
