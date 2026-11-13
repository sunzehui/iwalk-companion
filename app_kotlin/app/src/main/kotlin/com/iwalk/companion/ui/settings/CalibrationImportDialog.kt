package com.iwalk.companion.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.iwalk.companion.ui.device.DeviceSessionController

@Composable
fun CalibrationImportDialog(controller: DeviceSessionController, onDismiss: () -> Unit) {
    var json by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // SAF 文件选择：无需存储权限，Android 11+ 的 Android/data 限制不影响用户主动选文件。
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                json = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes().decodeToString()
                } ?: ""
                error = null
            } catch (e: Exception) {
                error = "读取文件失败：${e.message ?: "未知错误"}"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入标定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("粘贴或从文件选择经过采集验证的 JSON。导入会替换本机标定，并重新解析当前真车会话；演示数据保持独立。")
                OutlinedTextField(
                    value = json, onValueChange = { json = it; error = null },
                    label = { Text("标定 JSON") }, minLines = 5, maxLines = 10,
                    modifier = Modifier.fillMaxWidth().testTag("calibration-json"),
                    isError = error != null,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = controller.importCalibration(json)
                if (error == null) onDismiss()
            }, enabled = json.isNotBlank()) { Text("导入并应用") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    pickFile.launch(arrayOf("application/json", "text/plain", "text/*"))
                }) { Text("从文件选择") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
