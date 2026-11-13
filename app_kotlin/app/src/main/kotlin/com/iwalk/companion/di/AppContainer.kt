package com.iwalk.companion.di

import android.content.Context
import android.os.Environment
import com.iwalk.companion.ble.AndroidRealLinkFactory
import com.iwalk.companion.data.PreferenceLockSettingsStore
import com.iwalk.companion.data.FileExportStore
import com.iwalk.companion.data.SharedPreferencesKeyValueStore
import com.iwalk.companion.ui.device.DeviceSessionController
import com.iwalk.core.store.CalibrationStore
import java.io.File

/**
 * 应用级依赖容器（规格 §3：手写 `AppContainer`，不引 Hilt）。
 *
 * 标定存储、真车链路工厂与会话导出落盘；工单 07 的设置页继续从这里取依赖。
 * 使用 application context，避免 Activity 重建时泄漏。
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("iwapp_prefs", Context.MODE_PRIVATE)
    private val calibrationStore = CalibrationStore(SharedPreferencesKeyValueStore(prefs))

    val deviceController: DeviceSessionController = DeviceSessionController(
        calibrationStore = calibrationStore,
        lockSettingsStore = PreferenceLockSettingsStore(prefs),
        realLinkFactory = AndroidRealLinkFactory(appContext),
        exportStore = FileExportStore {
            // getExternalFilesDir(DOCUMENTS)：无需存储权限、卸载随应用删除；不可用时退回私有目录
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: File(appContext.filesDir, "Documents")
        },
    )

    fun close() {
        deviceController.close()
    }
}
