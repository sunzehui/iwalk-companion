package com.iwalk.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** notes/domain-logic.md §2 的真值表与边界。 */
class TransportGroupsTest {

    private val base = "-0000-1000-8000-00805f9b34fb"

    @Test
    fun `ffe0 is g3 for non-M6 and g1 for M6`() {
        val services = mapOf(FFE0_SERVICE to emptySet<String>())
        assertEquals(TransportGroup.g3Ffe0, selectTransport(services, "Mini")?.group)
        assertEquals(TransportGroup.g3Ffe0, selectTransport(services, null)?.group)
        assertEquals(TransportGroup.g1NordicUart, selectTransport(services, "M6-123")?.group)
    }

    @Test
    fun `ffe0 shadows g1 g2 and fff0 even when they are present`() {
        val services = mapOf(
            FFE0_SERVICE to setOf(FFF1_CHAR, FFF2_CHAR),
            G1_NORDIC_UART.serviceUuid to emptySet(),
            G2_AE00.serviceUuid to emptySet(),
            FFF0_SERVICE to setOf(FFF1_CHAR),
        )
        assertEquals(TransportGroup.g3Ffe0, selectTransport(services, "Mini")?.group)
    }

    @Test
    fun `g1 g2 and the fff0 family select the documented pairs`() {
        assertEquals(
            TransportGroup.g1NordicUart,
            selectTransport(mapOf(G1_NORDIC_UART.serviceUuid to emptySet()), "anything")?.group,
        )
        assertEquals(
            TransportGroup.g2Ae00,
            selectTransport(mapOf(G2_AE00.serviceUuid to emptySet()), "anything")?.group,
        )
        assertEquals(
            TransportGroup.g4Fff0,
            selectTransport(mapOf(FFF0_SERVICE to emptySet()), "anything")?.group,
        )
        assertEquals(
            TransportGroup.g5Fff0Alt,
            selectTransport(mapOf(FFF0_SERVICE to setOf(FFF1_CHAR)), "anything")?.group,
        )
        assertEquals(
            TransportGroup.g5Fff0Alt,
            selectTransport(mapOf(FFF0_SERVICE to setOf(FFF2_CHAR)), "anything")?.group,
        )
    }

    @Test
    fun `unknown service set selects nothing`() {
        val onlyGeneric = mapOf("00001800$base" to setOf("00002a00$base"))
        assertNull(selectTransport(onlyGeneric, "Mini"))
    }

    @Test
    fun `uuid lookup is case-insensitive`() {
        val upper = mapOf(G1_NORDIC_UART.serviceUuid.uppercase() to emptySet<String>())
        assertEquals(TransportGroup.g1NordicUart, selectTransport(upper, "Mini")?.group)
    }

    @Test
    fun `the five pairs carry the exact UUIDs`() {
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", G1_NORDIC_UART.serviceUuid)
        assertEquals("6e400002-b5a3-f393-e0a9-e50e24dcca9e", G1_NORDIC_UART.writeUuid)
        assertEquals("6e400003-b5a3-f393-e0a9-e50e24dcca9e", G1_NORDIC_UART.notifyUuid)

        assertEquals("0000ae00$base", G2_AE00.serviceUuid)
        assertEquals("0000ae01$base", G2_AE00.writeUuid)
        assertEquals("0000ae02$base", G2_AE00.notifyUuid)

        assertEquals("0000ffe0$base", G3_FFE0.serviceUuid)
        assertEquals("0000fff3$base", G3_FFE0.writeUuid)
        assertEquals("0000fff4$base", G3_FFE0.notifyUuid)

        assertEquals("0000fff0$base", G4_FFF0.serviceUuid)
        assertEquals("0000fff3$base", G4_FFF0.writeUuid)
        assertEquals("0000fff7$base", G4_FFF0.notifyUuid)

        assertEquals("0000fff0$base", G5_FFF0_ALT.serviceUuid)
        assertEquals("0000fff2$base", G5_FFF0_ALT.writeUuid)
        assertEquals("0000fff1$base", G5_FFF0_ALT.notifyUuid)
    }

    @Test
    fun `name whitelist has 33 verbatim entries and the single leading-space quirk`() {
        assertEquals(33, DEVICE_NAME_PREFIXES.size)
        assertEquals(" MiniPro", DEVICE_NAME_PREFIXES[13])
        assertTrue(isSupportedDeviceName("MiniRobot:IWK1779887"))
        assertTrue(isSupportedDeviceName(" MiniPro-1"))
        assertTrue(isSupportedDeviceName("EO STREET 3"))
        assertTrue(isSupportedDeviceName("M6"))
        assertFalse(isSupportedDeviceName("minirobot")) // case-sensitive
        assertFalse(isSupportedDeviceName("UnknownScooter"))
        assertFalse(isSupportedDeviceName(""))
        assertFalse(isSupportedDeviceName(null))
    }

    @Test
    fun `serial extraction mirrors the Dart edge cases`() {
        assertEquals("1779887", deviceSerialFromName("MiniRobot:IWK1779887"))
        assertEquals("42", deviceSerialFromName("prefix:IWK 42 "))
        assertNull(deviceSerialFromName("MiniRobot"))
        assertNull(deviceSerialFromName("MiniRobot:IWK"))
        assertNull(deviceSerialFromName("MiniRobot:IWK   "))
        assertNull(deviceSerialFromName(null))
        assertEquals("42", deviceSerialFromName("a:IWK42"))
    }

    @Test
    fun `link budget constants match the fixed MTU`() {
        assertEquals(23, LinkBudget.defaultMtu)
        assertEquals(20, LinkBudget.maxPayloadPerPacket)
        assertEquals(75, LinkBudget.maxIntervalMillis)
        assertEquals(250, LinkBudget.reassemblyIdleTimeoutMillis)
    }

    @Test
    fun `property flags keep the fixed order`() {
        assertEquals("RwN", propertyFlags(read = true, write = false, writeWithoutResponse = true, notify = true, indicate = false))
        assertEquals("Ww", propertyFlags(read = false, write = true, writeWithoutResponse = true, notify = false, indicate = false))
    }
}
