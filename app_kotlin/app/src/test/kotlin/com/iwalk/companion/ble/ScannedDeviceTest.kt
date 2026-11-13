package com.iwalk.companion.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 扫描列表的隐私掩码与短 UUID（notes/ui-device-scan.md §3.3）。 */
class ScannedDeviceTest {

    @Test
    fun `baseName falls back for empty names`() {
        assertEquals(
            "MiniRobot",
            ScannedDevice("A", "MiniRobot:IWK1779887", -60, true, "1779887", emptyList()).baseName,
        )
        assertEquals(
            "未命名设备",
            ScannedDevice("A", "", -60, false, null, emptyList()).baseName,
        )
        assertEquals(
            "未命名设备",
            ScannedDevice("A", "  ", -60, false, null, emptyList()).baseName,
        )
    }

    @Test
    fun `masked mac keeps head and tail`() {
        assertEquals(
            "AA:BB:CC:…:EE:FF",
            ScannedDevice("AA:BB:CC:DD:EE:FF", "Mini", -60, true, null, emptyList()).maskedMac,
        )
        assertEquals(
            "not-a-mac",
            ScannedDevice("not-a-mac", "Mini", -60, true, null, emptyList()).maskedMac,
        )
    }

    @Test
    fun `serial mask keeps last four digits`() {
        assertEquals("…9887", maskSerial("1779887"))
        assertEquals("…", maskSerial("1234"))
        assertNull(maskSerial(null))
        assertNull(maskSerial("  "))
    }

    @Test
    fun `short uuid strips only the standard base`() {
        assertEquals("0000ffe0", shortUuid("0000ffe0-0000-1000-8000-00805f9b34fb"))
        assertEquals(
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
            shortUuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
        )
    }
}
