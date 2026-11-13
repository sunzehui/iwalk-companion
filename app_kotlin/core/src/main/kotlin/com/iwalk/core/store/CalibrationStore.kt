package com.iwalk.core.store

import com.iwalk.core.model.CalibrationSet

/**
 * 用户标定存储（notes/domain-logic.md §3.5）。这里是纯 JVM 接口 + 语义规则，
 * SharedPreferences 实现放在 :app（工单 05 接线）。
 *
 * 规则逐字对齐 Dart：空 profile 列表 → 删除键；损坏值 → 删除键并返回 null，
 * 永不阻塞启动；**演示标定从不调用 save**——演示会话的标定由 [com.iwalk.core.demo.DemoSession]
 * 注入到会话自身，与本存储无接触面。
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/** JVM 测试与演示隔离验证用的内存实现。 */
class InMemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()

    override fun getString(key: String): String? = map[key]

    override fun putString(key: String, value: String) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }

    /** 隔离测试用：键是否出现过残留。 */
    fun keys(): Set<String> = map.keys.toSet()
}

class CalibrationStore(private val kv: KeyValueStore) {

    companion object {
        const val KEY = "calibration.v1"
    }

    /** 空集合不保存而是删除键（§3.5 save 规则）。 */
    fun save(set: CalibrationSet) {
        if (set.profiles.isEmpty()) {
            kv.remove(KEY)
        } else {
            kv.putString(KEY, set.encode())
        }
    }

    /** 缺失/空/损坏/解码后为空 → null；损坏时顺手删除键（§3.5 load 规则）。 */
    fun load(): CalibrationSet? {
        val text = kv.getString(KEY) ?: return null
        if (text.isEmpty()) {
            kv.remove(KEY)
            return null
        }
        return try {
            val set = CalibrationSet.decode(text)
            if (set.profiles.isEmpty()) null else set
        } catch (e: Exception) {
            kv.remove(KEY)
            null
        }
    }
}
