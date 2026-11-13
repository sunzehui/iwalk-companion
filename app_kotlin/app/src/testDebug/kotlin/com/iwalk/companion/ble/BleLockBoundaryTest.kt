package com.iwalk.companion.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BleLockBoundaryTest {
    @Test fun `disconnected GATT never exposes fixed lock write`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val transport = BleTransport(context, adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF"), "i.walk:IWK1", "test")
        assertFalse(transport.lockControlAvailable)
        val error = runCatching { transport.setLock(true) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("未连接"))
        transport.disconnect()
    }
}
