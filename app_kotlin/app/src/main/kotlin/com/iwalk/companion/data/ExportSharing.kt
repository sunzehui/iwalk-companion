package com.iwalk.companion.data

import android.content.Context
import android.content.Intent
import android.content.ClipData
import androidx.core.content.FileProvider

fun shareExport(context: Context, files: ExportedFiles) {
    val uris = arrayListOf(files.rawFile, files.evidenceFile).mapTo(arrayListOf()) {
        check(it.isFile) { "导出文件已不存在，请重新导出" }
        FileProvider.getUriForFile(context, "${context.packageName}.exports", it)
    }
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "application/json"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        clipData = ClipData.newUri(context.contentResolver, "iWalk 证据包", uris.first()).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享证据包"))
}
