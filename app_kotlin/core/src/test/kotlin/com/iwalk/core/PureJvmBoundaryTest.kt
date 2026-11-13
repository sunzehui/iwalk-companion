package com.iwalk.core

import org.junit.Assert.fail
import org.junit.Test

/**
 * 骨架阶段对 :core 的唯一要求：它是纯 JVM 模块，且能看到 :protocol。
 * 会话/解码/导出逻辑由后续工单补齐，这里守护依赖边界（规格第 3 节）。
 */
class PureJvmBoundaryTest {

    @Test
    fun `android 类不在 core 的类路径上`() {
        assertAndroidAbsent("android.os.Build")
        assertAndroidAbsent("android.bluetooth.BluetoothGatt")
        assertAndroidAbsent("androidx.annotation.NonNull")
    }

    private fun assertAndroidAbsent(className: String) {
        val loaded = runCatching { Class.forName(className) }.getOrNull()
        if (loaded != null) {
            fail("$className 出现在 :core 的测试类路径上：该模块不允许依赖 Android")
        }
    }
}
