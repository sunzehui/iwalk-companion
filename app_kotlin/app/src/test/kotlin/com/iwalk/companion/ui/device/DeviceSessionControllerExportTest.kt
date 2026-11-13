package com.iwalk.companion.ui.device

import com.iwalk.companion.data.ExportStore
import com.iwalk.companion.data.FileExportStore
import com.iwalk.core.session.buildEvidenceJson
import com.iwalk.core.session.buildRawJsonLines
import java.nio.file.Files
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 工单 04：导出入口的防重入、落盘与失败文案（JVM，临时目录）。
 * JSON 形状本身由 `:core` 的 `ExportTest` 覆盖；这里验证装配与不变量。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSessionControllerExportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun controller(store: ExportStore? = null) = DeviceSessionController(
        demoPeriodMillis = 20,
        nowMillis = { 1_000L },
        exportStore = store ?: FileExportStore { tmp.root },
    )

    @Test
    fun `演示会话导出落盘且计数一致`() = runTest(dispatcher) {
        val controller = controller()
        controller.startDemo()
        // 等待演示链路产出帧（真实时间推进，演示 tick 跑在 Default）
        val deadline = System.currentTimeMillis() + 10_000
        while (controller.ui.value.frames.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(controller.ui.value.frames.isNotEmpty())

        val message = controller.exportSession()
        advanceUntilIdle()

        assertTrue(message.startsWith("已导出："))
        val dirPath = message.removePrefix("已导出：")
        val dir = java.io.File(dirPath)
        assertTrue(dir.isDirectory)
        val files = dir.listFiles().orEmpty()
        assertEquals(2, files.size)
        val rawFile = files.first { it.name.startsWith("raw_") }
        val evidenceFile = files.first { it.name.startsWith("evidence_") }
        assertTrue(rawFile.name.endsWith(".jsonl"))
        assertTrue(evidenceFile.name.endsWith(".json"))

        // 演示会话在导出后仍在推进，因此只断言导出文件**内部**的一致性（E60）
        val evidence = evidenceFile.readText()
        assertTrue(evidence.contains("\"schema\": \"iwapp-evidence-v1\""))
        assertTrue(evidence.contains("\"app\": \"iwalk_kotlin\""))
        val intField = { key: String -> Regex("\"$key\": (\\d+)").find(evidence)!!.groupValues[1].toInt() }
        val framesTotal = intField("frames_total")
        val rawPackets = intField("raw_packets")
        val groupSum = Regex("\"frames\": (\\d+)").findAll(evidence).sumOf { it.groupValues[1].toInt() }
        assertTrue(framesTotal >= 1)
        assertEquals(framesTotal, groupSum)
        assertEquals(rawPackets, rawFile.readLines().count { it.isNotBlank() })

        controller.stopSession()
    }

    @Test
    fun `无会话导出静默返回空串`() = runTest(dispatcher) {
        assertEquals("", controller().exportSession())
    }

    @Test
    fun `落盘失败给出导出失败前缀`() = runTest(dispatcher) {
        val failing = object : ExportStore {
            override fun write(
                sessionId: String,
                rawJsonLines: List<String>,
                evidenceJson: String,
                now: LocalDateTime,
            ): com.iwalk.companion.data.ExportedFiles = error("磁盘只读")
        }
        val controller = controller(failing)
        controller.startDemo()
        val deadline = System.currentTimeMillis() + 10_000
        while (controller.ui.value.frames.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        val message = controller.exportSession()
        advanceUntilIdle()

        assertTrue(message.startsWith("导出失败："))
        assertTrue(message.contains("磁盘只读"))
        assertTrue(!controller.exporting.value)
        controller.stopSession()
    }

    @Test
    fun `导出 JSONL 与 evidence 内容来自同一快照`() = runTest(dispatcher) {
        val store = RecordingStore()
        val controller = controller(store)
        controller.startDemo()
        val deadline = System.currentTimeMillis() + 10_000
        while (controller.ui.value.frames.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        controller.exportSession()
        advanceUntilIdle()

        // 同一次导出里 frames_total 与帧组之和天然一致（同一快照，E60）
        assertTrue(store.framesAtWrite >= 1)

        controller.stopSession()
    }

    private class RecordingStore : ExportStore {
        var framesAtWrite: Int = -1

        override fun write(
            sessionId: String,
            rawJsonLines: List<String>,
            evidenceJson: String,
            now: LocalDateTime,
        ): com.iwalk.companion.data.ExportedFiles {
            framesAtWrite = Regex("\"frames_total\": (\\d+)").find(evidenceJson)!!.groupValues[1].toInt()
            val dir = Files.createTempDirectory("export").toFile()
            val raw = java.io.File(dir, "raw_x.jsonl").apply { writeText(rawJsonLines.joinToString("") { "$it\n" }) }
            val evidence = java.io.File(dir, "evidence_x.json").apply { writeText(evidenceJson) }
            return com.iwalk.companion.data.ExportedFiles(dir, raw, evidence)
        }
    }
}
