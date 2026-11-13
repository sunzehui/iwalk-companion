package com.iwalk.companion.ble

/** 真车连接失败时给用户看的一句话（不暴露异常类名，除非无法归类）。 */
const val LINK_ERR_INIT_INTERRUPTED: String = "链路在初始化阶段被中断；常见于刚重启蓝牙或车辆离开范围，可直接重试"
const val LINK_ERR_CONNECT_TIMEOUT: String = "15 秒内没有建立连接；确认车辆已开机且在 10 米内"
const val LINK_ERR_SERVICE_TIMEOUT: String = "已连上，但 15 秒内没有拿到服务列表"
const val LINK_ERR_CLOSED: String = "上一次尝试已结束，请重新连接"
const val LINK_ERR_NO_ADAPTER: String = "蓝牙不可用，请检查系统蓝牙开关"
const val LINK_ERR_NO_GATT: String = "系统没有返回 GATT 连接；蓝牙刚重启时常见，可直接重试"

/**
 * 把链路异常翻成可读中文。规则只用 `message` 子串匹配——[com.iwalk.companion.ble.BleTransport]
 * 的 `check(...)`/`error(...)` 文案是稳定契约，异常类型在不同 Android 版本上并不稳定。
 */
fun linkErrorText(error: Throwable?): String {
    if (error == null) return "未知原因"
    val raw = error.message?.takeIf { it.isNotBlank() }
    if (raw == null) return error.javaClass.simpleName
    return when {
        raw.contains("连接初始化已中断") -> LINK_ERR_INIT_INTERRUPTED
        raw.contains("连接超时或失败") -> LINK_ERR_CONNECT_TIMEOUT
        raw.contains("服务发现超时") -> LINK_ERR_SERVICE_TIMEOUT
        raw.contains("链路已关闭") -> LINK_ERR_CLOSED
        raw.contains("蓝牙不可用") -> LINK_ERR_NO_ADAPTER
        raw.contains("connectGatt 返回 null") -> LINK_ERR_NO_GATT
        // 未归类的失败保留类型名：宁可难看，也不要掩盖真实性质
        else -> "$raw（${error.javaClass.simpleName}）"
    }
}

/**
 * 是否值得立刻重试一次：初始化被打断是瞬时的（用户实测「再点一次就成功」），
 * 而超时类失败重试只会再等 15 秒，交给用户决定。
 */
fun isTransientLinkFailure(error: Throwable?): Boolean {
    val raw = error?.message ?: return false
    return raw.contains("连接初始化已中断") || raw.contains("connectGatt 返回 null")
}
