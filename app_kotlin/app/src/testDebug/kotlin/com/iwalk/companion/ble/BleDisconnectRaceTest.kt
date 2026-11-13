package com.iwalk.companion.ble

import android.bluetooth.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.iwalk.core.session.TransportState
import com.iwalk.protocol.G1_NORDIC_UART
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.*
import java.util.UUID

@Implements(BluetoothGatt::class)
class RaceGattShadow : ShadowBluetoothGatt() {
    @RealObject lateinit var realGatt: BluetoothGatt
    var writes = 0
    var disconnectOnRead = false
    @Implementation(minSdk = 33)
    override fun writeCharacteristic(c: BluetoothGattCharacteristic, bytes: ByteArray, writeType: Int): Int {
        writes++
        gattCallback?.onCharacteristicWrite(realGatt, c, BluetoothGatt.GATT_SUCCESS)
        return BluetoothStatusCodes.SUCCESS
    }
    @Implementation
    override fun readCharacteristic(c: BluetoothGattCharacteristic): Boolean {
        if (disconnectOnRead) {
            gattCallback?.onConnectionStateChange(realGatt, 0, BluetoothProfile.STATE_DISCONNECTED)
            return true
        }
        return super.readCharacteristic(c)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RaceGattShadow::class])
class BleDisconnectRaceTest {
    private fun field(obj: Any, key: String) = obj.javaClass.getDeclaredField(key).apply { isAccessible = true }
    private fun setup(): Pair<BleTransport, BluetoothDevice> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF")
        return BleTransport(context, device, "MiniRobot:IWK1", "test") to device
    }
    private fun writer() = BluetoothGattCharacteristic(UUID.fromString(G1_NORDIC_UART.writeUuid), BluetoothGattCharacteristic.PROPERTY_WRITE, 0)

    @Test fun `disconnect invalidates connection before waking queued lock write`() = runBlocking {
        val (transport, device) = setup()
        val gatt = ShadowBluetoothGatt.newInstance(device)
        val shadow = Shadow.extract<RaceGattShadow>(gatt)
        shadow.gattCallback = field(transport, "callback").get(transport) as BluetoothGattCallback
        field(transport, "state").set(transport, TransportState.connected)
        field(transport, "gatt").set(transport, gatt)
        field(transport, "pair").set(transport, G1_NORDIC_UART)
        val writer = writer()
        field(transport, "writeCharacteristic").set(transport, writer)
        val queue = field(transport, "gattMutex").get(transport) as Mutex
        queue.lock()
        val oldWaiter = CompletableDeferred<Boolean>()
        field(transport, "writeWaiter").set(transport, writer.uuid to oldWaiter)
        oldWaiter.invokeOnCompletion { queue.unlock() }
        val request = async(Dispatchers.Unconfined) { runCatching { transport.setLock(true) } }
        transport.disconnect()
        withTimeout(1000) { request.await() }
        assertEquals(0, shadow.writes)
        assertFalse(transport.lockControlAvailable)
    }

    @Test fun `disconnect during standard read cannot publish connected`() = runBlocking {
        val (transport, device) = setup()
        Shadow.extract<ShadowBluetoothDevice>(device).setGattConnectionInterceptor { gatt ->
            val shadow = Shadow.extract<RaceGattShadow>(gatt)
            val service = BluetoothGattService(UUID.fromString(G1_NORDIC_UART.serviceUuid), 0)
            service.addCharacteristic(writer())
            service.addCharacteristic(BluetoothGattCharacteristic(UUID.fromString(G1_NORDIC_UART.notifyUuid), BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0))
            shadow.addDiscoverableService(service)
            val standard = BluetoothGattService(UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"), 0)
            standard.addCharacteristic(BluetoothGattCharacteristic(UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"), BluetoothGattCharacteristic.PROPERTY_READ, 0))
            shadow.addDiscoverableService(standard)
            shadow.disconnectOnRead = true
            shadow.notifyConnection(device.address)
        }
        val result = withTimeout(2000) { runCatching { transport.connect() } }
        assertTrue(result.isFailure)
        assertNotEquals(TransportState.connected, transport.state)
        assertFalse(transport.lockControlAvailable)
        transport.disconnect()
    }
}
