package com.iwalk.core.session

import kotlin.math.abs

/** Simulation bypasses the real-car opt-in and speed guard, but still needs a live transport. */
fun lockBlockedReason(
    enabled: Boolean,
    isDemo: Boolean,
    connected: Boolean,
    available: Boolean,
    speedKmh: Double?,
): String? = when {
    !isDemo && !enabled -> "锁车控制默认关闭"
    !connected -> "连接断开，无法发送指令"
    !available -> "当前链路不支持锁车指令"
    // 2026-11-12 真车教训（E66）：速度未知时放行等于闸门完全失效。
    // 没有速度读数就无法证明车辆静止，默认阻止；要恢复可用需先有速度标定。
    !isDemo && speedKmh == null -> "速度未标定，无法证明车辆静止，已阻止操作"
    !isDemo && speedKmh != null && !speedKmh.isFinite() -> "速度读数无效，无法发送指令"
    !isDemo && speedKmh != null && abs(speedKmh) > 1.0 -> "速度仍高于 1.0 km/h，已阻止操作"
    else -> null
}
