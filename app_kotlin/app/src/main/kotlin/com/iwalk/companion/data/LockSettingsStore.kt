package com.iwalk.companion.data

import android.content.SharedPreferences

interface LockSettingsStore { var enabled: Boolean }
class MemoryLockSettingsStore : LockSettingsStore { override var enabled = false }
class PreferenceLockSettingsStore(private val prefs: SharedPreferences) : LockSettingsStore {
    override var enabled: Boolean
        get() = runCatching { prefs.getBoolean(KEY, false) }.getOrDefault(false)
        set(value) { prefs.edit().putBoolean(KEY, value).apply() }
    companion object { const val KEY = "lock_control_enabled.v1" }
}
