package com.iwalk.companion.data

import android.content.SharedPreferences
import com.iwalk.core.store.KeyValueStore

/** [KeyValueStore] 的 Android 实现；键名语义仍由 [com.iwalk.core.store.CalibrationStore] 决定。 */
class SharedPreferencesKeyValueStore(
    private val prefs: SharedPreferences,
) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
