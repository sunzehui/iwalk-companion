package com.iwalk.core.store

import com.iwalk.core.model.CalibrationProfile
import com.iwalk.core.model.CalibrationSet
import com.iwalk.core.model.FieldMapping
import com.iwalk.core.model.ScalarType
import com.iwalk.core.model.TelemetryField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户标定存储的保存/加载规则（notes/domain-logic.md §3.5）。 */
class CalibrationStoreTest {

    private val sample = CalibrationSet(
        listOf(
            CalibrationProfile(
                "t", 13, 1, 20,
                listOf(FieldMapping(TelemetryField.batteryPercent, 28, ScalarType.u8)),
                evidence = "E64 电量锚点",
            ),
        ),
    )

    @Test
    fun `保存后能完整读回`() {
        val kv = InMemoryKeyValueStore()
        val store = CalibrationStore(kv)
        store.save(sample)
        val loaded = store.load()!!
        assertEquals("t", loaded.profiles.single().name)
        assertEquals(13, loaded.match(13, 1, 20)?.cmd)
        assertTrue(kv.keys().contains(CalibrationStore.KEY))
    }

    @Test
    fun `空集合是删除而不是写入`() {
        val kv = InMemoryKeyValueStore()
        val store = CalibrationStore(kv)
        store.save(sample)
        store.save(CalibrationSet.empty)
        assertNull(store.load())
        assertFalse(kv.keys().contains(CalibrationStore.KEY))
    }

    @Test
    fun `损坏值加载时删除且返回空`() {
        val kv = InMemoryKeyValueStore()
        kv.putString(CalibrationStore.KEY, "{not json")
        val store = CalibrationStore(kv)
        assertNull(store.load())
        assertNull(kv.getString(CalibrationStore.KEY)) // 键被删掉，永不阻塞启动
    }

    @Test
    fun `空字符串视作缺失`() {
        val kv = InMemoryKeyValueStore()
        kv.putString(CalibrationStore.KEY, "")
        val store = CalibrationStore(kv)
        assertNull(store.load())
        assertNull(kv.getString(CalibrationStore.KEY))
    }
}
