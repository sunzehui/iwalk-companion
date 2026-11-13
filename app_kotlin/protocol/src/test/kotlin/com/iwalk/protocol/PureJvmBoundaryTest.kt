package com.iwalk.protocol

import org.junit.Assert.fail
import org.junit.Test

/**
 * 骨架阶段对 :protocol 的唯一要求：它是一个纯 JVM 模块。
 * 协议编解码由后续工单补齐，这里守护依赖边界（规格第 3 节：
 * :protocol 不得出现任何 Android 依赖，避免链路层反向渗透进纯逻辑）。
 */
class PureJvmBoundaryTest {

    @Test
    fun `android 类不在 protocol 的类路径上`() {
        assertAndroidAbsent("android.os.Build")
        assertAndroidAbsent("android.bluetooth.BluetoothGatt")
    }

    private fun assertAndroidAbsent(className: String) {
        val loaded = runCatching { Class.forName(className) }.getOrNull()
        if (loaded != null) {
            fail("$className 出现在 :protocol 的测试类路径上：该模块不允许依赖 Android")
        }
    }
}
