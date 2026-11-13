package com.iwalk.companion.data

import com.iwalk.core.session.exportId
import java.io.File
import java.time.LocalDateTime

/** 一次成功导出的两个文件（顺序与 Dart `SessionExporter` 一致）。 */
data class ExportedFiles(
    val directory: File,
    val rawFile: File,
    val evidenceFile: File,
) {
    val summary: String get() = listOf(rawFile.name, evidenceFile.name).joinToString(", ")
}

/**
 * 会话导出的落盘口。纯接口：JVM 测试用临时目录实现，生产用应用外部文档目录
 * `Documents/iwapp_exports/`（规格 §8；免权限、用户可从文件管理器取走）。
 */
interface ExportStore {
    fun write(sessionId: String, rawJsonLines: List<String>, evidenceJson: String, now: LocalDateTime): ExportedFiles
}

class FileExportStore(private val baseDir: () -> File) : ExportStore {
    override fun write(
        sessionId: String,
        rawJsonLines: List<String>,
        evidenceJson: String,
        now: LocalDateTime,
    ): ExportedFiles {
        val dir = File(baseDir(), "iwapp_exports")
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            error("无法创建导出目录：${dir.path}")
        }
        val id = exportId(sessionId, now)
        val rawFile = File(dir, "raw_$id.jsonl")
        val evidenceFile = File(dir, "evidence_$id.json")
        // 每行一个 JSON 对象，`\n` 结尾（§7.1）
        rawFile.writeText(rawJsonLines.joinToString("") { "$it\n" })
        evidenceFile.writeText(evidenceJson)
        return ExportedFiles(dir, rawFile, evidenceFile)
    }
}
