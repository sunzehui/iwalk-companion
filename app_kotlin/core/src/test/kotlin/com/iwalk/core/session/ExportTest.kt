package com.iwalk.core.session

import com.iwalk.core.analysis.analyseFrames
import com.iwalk.core.decode.TelemetryDecoder
import com.iwalk.core.model.CalibrationSet
import com.iwalk.protocol.IbwalkFrame
import java.time.Instant
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** notes/domain-logic.md §7：schema、键序、键名与快照不变量（E60）。 */
class ExportTest {

    private class StubTransport : BluetoothTransport {
        override val id = "AA:BB:CC:DD:EE:FF"
        override val label = "MiniRobot:IWK1779887"
        override val isDemo = false
        override var linkFacts: LinkFacts? = LinkFacts(
            advertisementName = label,
            transportGroup = "g1NordicUart",
            serviceUuid = "svc",
            writeUuid = "wr",
            notifyUuid = "nt",
            lockControl = "fixed-g1-smalltype-experimental",
            writeCharacteristicAvailable = true,
            gattTree = listOf("D AA:BB:CC:DD:EE:FF mtu~23"),
            standardReads = linkedMapOf("00002a00-0000-1000-8000-00805f9b34fb" to "4d696e69"),
        )
        override val incoming = MutableSharedFlow<IncomingPacket>(extraBufferCapacity = 8)
        override val events = MutableSharedFlow<TransportEvent>(replay = 1)
        override var state = TransportState.connected
        override suspend fun connect() = Unit
        override suspend fun disconnect() = Unit
    }

    @Test
    fun `导出 id 与 Dart 同形`() {
        val now = LocalDateTime.of(2026, Month.OCTOBER, 21, 7, 15, 44, 550_323_000)
        assertEquals("demo-1_20261021T071544550323", exportId("demo-1", now))
    }

    @Test
    fun `原始行重包为 procedure D 且 null 必须写出`() {
        val now = Instant.parse("2026-11-06T00:00:00.000000Z")
        val lines = listOf(
            "2026-11-06T05:52:00.123456Z deadbeef",
            "no-space-line",
            "not-a-time aa bb",
        )
        val out = buildRawJsonLines(lines, deviceId = "AA", sessionId = "ble-1", now = now)

        assertEquals(3, out.size)
        val first = Json.parseToJsonElement(out[0]).jsonObject
        assertEquals(
            listOf(
                "ts", "device", "session", "source", "direction", "service_uuid",
                "char_uuid", "hex", "step", "app_state", "complete", "confidence", "note",
            ),
            first.keys.toList(),
        )
        assertEquals("iwalk_kotlin", first["source"]!!.jsonPrimitive.content)
        assertTrue(out[0].contains("\"service_uuid\":null"))
        assertTrue(out[0].contains("\"app_state\":\"\""))
        assertEquals("deadbeef", first["hex"]!!.jsonPrimitive.content)
        assertEquals("live", first["step"]!!.jsonPrimitive.content)
        // 本地时间格式（6 位小数、无时区后缀）
        assertTrue(out[0].startsWith("{\"ts\":\"20"))
        assertFalse(out[0].contains("Z\""))

        val noStamp = Json.parseToJsonElement(out[1]).jsonObject
        assertEquals("no-space-line", noStamp["hex"]!!.jsonPrimitive.content)

        val badStamp = Json.parseToJsonElement(out[2]).jsonObject
        assertEquals("aa bb", badStamp["hex"]!!.jsonPrimitive.content)
    }

    @Test
    fun `evidence 键序与扁平链路事实`() = runTest {
        val session = SessionManager(
            transport = StubTransport(),
            calibration = CalibrationSet.empty,
            sessionId = "ble-9",
            scope = this,
        )
        // 3 字节载荷：offset 0 有 u16le，offset 1 没有
        val frame = IbwalkFrame(0x0d, 0x01, 0x14, byteArrayOf(0x01, 0x02, 0x03))
        val transport = session.transport as StubTransport
        // 先等收集器订阅完成（runTest 的虚拟时间不会自动推进 Default/主调度器）
        var spins = 0
        while (transport.incoming.subscriptionCount.value == 0 && spins < 500) {
            testScheduler.runCurrent()
            spins++
        }
        transport.incoming.emit(IncomingPacket(frame.encode(), "svc", "char"))
        var frames = session.frames
        while (frames.isEmpty() && spins < 500) {
            testScheduler.runCurrent()
            frames = session.frames
            spins++
        }
        assertEquals(1, frames.size)

        val now = LocalDateTime.of(2026, Month.OCTOBER, 21, 7, 15, 44, 550_323_000)
        val json = buildEvidenceJson(
            sessionId = session.sessionId,
            transport = session.transport,
            frames = session.frames,
            rawLineCount = session.rawLines.size,
            events = session.evidenceEvents,
            lockCommands = emptyList(),
            exportedAt = now,
        )
        val root = Json.parseToJsonElement(json).jsonObject

        assertEquals(
            listOf(
                "schema", "app", "exported_at", "session", "transport",
                "frames_total", "raw_packets", "frame_groups",
                "evidence_timeline", "commands_sent", "note",
            ),
            root.keys.toList(),
        )
        assertEquals("iwapp-evidence-v1", root["schema"]!!.jsonPrimitive.content)
        assertEquals("iwalk_kotlin", root["app"]!!.jsonPrimitive.content)
        assertEquals("ble-9", root["session"]!!.jsonPrimitive.content)
        assertEquals(1, root["frames_total"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, root["raw_packets"]!!.jsonPrimitive.content.toInt())

        val transportMap = root["transport"]!!.jsonObject
        assertEquals(
            listOf("id", "label", "is_demo", "kind", "advertisement_name", "mtu_now",
                "max_payload_per_packet", "transport_group", "service_uuid", "write_uuid",
                "notify_uuid", "lock_control", "write_characteristic_available",
                "gatt_tree", "standard_reads"),
            transportMap.keys.toList(),
        )
        assertEquals("g1NordicUart", transportMap["transport_group"]!!.jsonPrimitive.content)

        val group = root["frame_groups"]!!.jsonArray[0].jsonObject
        assertEquals("0x0d", group["cmd"]!!.jsonPrimitive.content)
        val offsets = group["offsets"]!!.jsonArray.map { it.jsonObject }
        assertEquals(513, offsets[0]["u16le_min"]!!.jsonPrimitive.content.toInt())
        assertFalse(offsets[1].containsKey("u16le_min"))
        assertTrue(root["commands_sent"]!!.jsonArray.isEmpty())
        assertTrue(json.contains("\n  \"schema\""))

        session.stop()
    }

    @Test
    fun `锁帧记录的 vehicle_state 恒为 unconfirmed`() {
        val record = LockCommandRecord(
            at = Instant.parse("2026-11-06T00:00:00Z"),
            lock = true,
            hex = "55aa040a037001007dff",
            ok = true,
            simulated = false,
        )
        val json = Json.parseToJsonElement(toCompactJson(record.toJson())).jsonObject
        assertEquals("unconfirmed", json["vehicle_state"]!!.jsonPrimitive.content)
        assertEquals("lock", json["action"]!!.jsonPrimitive.content)
        assertEquals("unconfirmed", record.toJson()["vehicle_state"])

        val unlock = LockCommandRecord(Instant.now(), false, "h", false, true, "err").toJson()
        assertEquals("unlock", unlock["action"])
        assertEquals("err", unlock["error"])
    }

    @Test
    fun `时间线事件导出含映射与标定名`() {
        val frame = IbwalkFrame(0x0d, 0x01, 0x14, byteArrayOf(1, 2, 3))
        val decoder = TelemetryDecoder(CalibrationSet.empty)
        val touched = decoder.ingest(frame, Instant.parse("2026-11-06T00:00:00Z"))
        val event = EvidenceEvent(
            at = Instant.parse("2026-11-06T00:00:00Z"),
            cmd = frame.cmd,
            a1 = frame.a1,
            a2 = frame.a2,
            payload = frame.payload,
            calibrated = false,
            values = touched.mapNotNull { f -> decoder.valueOf(f)?.let { f to it } }.toMap(),
        )
        val json = Json.parseToJsonElement(toCompactJson(event.toJson())).jsonObject
        assertEquals(
            listOf("at", "group", "payload_hex", "calibrated", "values"),
            json.keys.toList(),
        )
        assertEquals("0x0d/0x01/0x14", json["group"]!!.jsonPrimitive.content)
        assertEquals("010203", json["payload_hex"]!!.jsonPrimitive.content)
    }

    @Test
    fun `demo 传输没有链路事实时回落 kind unknown`() {
        val transport = object : BluetoothTransport {
            override val id = "demo"
            override val label = "演示模式（模拟车）"
            override val isDemo = true
            override val incoming = MutableSharedFlow<IncomingPacket>()
            override val events = MutableSharedFlow<TransportEvent>(replay = 1)
            override var state = TransportState.connected
            override suspend fun connect() = Unit
            override suspend fun disconnect() = Unit
        }
        val json = buildEvidenceJson(
            sessionId = "demo-1",
            transport = transport,
            frames = emptyList(),
            rawLineCount = 0,
            events = emptyList(),
            lockCommands = emptyList(),
            exportedAt = LocalDateTime.of(2026, 1, 1, 0, 0),
        )
        val transportMap = Json.parseToJsonElement(json).jsonObject["transport"]!!.jsonObject
        assertEquals("unknown", (transportMap["kind"] as JsonPrimitive).content)
        assertEquals(4, transportMap.size)
    }
}
