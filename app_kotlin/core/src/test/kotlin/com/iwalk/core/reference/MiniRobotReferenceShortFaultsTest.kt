package com.iwalk.core.reference

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 官方短码表逐字比对：期望值来自 `app_v2/lib/reference/minirobot_reference.dart:11-25`
 * （工单笔记 §7 指定），不由实现代码推导。
 */
class MiniRobotReferenceShortFaultsTest {

    @Test
    fun `hxz1 到 hxz13 的编码 标签与备注逐字对齐官方表`() {
        val expected = listOf(
            Triple("hxz1", "Hall-E", "与 hxz2 重复；为何重复未验证"),
            Triple("hxz2", "Hall-E", "同上"),
            Triple("hxz3", "转向异常 / Steering Abnormality", "转向相关"),
            Triple("hxz4", "陀螺仪异常 / Gyro Abnormal", "姿态传感器"),
            Triple("hxz5", "突然掉电 / sudden power-down", "电源"),
            Triple("hxz6", "大电流保护 / high current protection", "电流保护"),
            Triple("hxz7", "应力片异常 / Abnormal stress sheet", "应力传感器"),
            Triple("hxz8", "RM-Loss", "缩写含义未验证"),
            Triple("hxz9", "LM-Loss", "缩写含义未验证"),
            Triple("hxz10", "陀螺仪异常 / Gyro Abnormal", "与 hxz4 重复"),
            Triple("hxz11", "Hall-A", "与 hxz12 重复；为何重复未验证"),
            Triple("hxz12", "Hall-A", "同上"),
            Triple("hxz13", "倾角速度过大 / Tilt speed is too large", "姿态变化过快"),
        )

        val actual = MiniRobotReference.shortFaults.map { Triple(it.code, it.label, it.note) }
        assertEquals(expected, actual)
    }

    @Test
    fun `短码表条数是 13`() {
        assertEquals(13, MiniRobotReference.shortFaults.size)
    }
}
