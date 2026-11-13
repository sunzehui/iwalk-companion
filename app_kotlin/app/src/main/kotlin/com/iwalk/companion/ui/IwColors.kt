package com.iwalk.companion.ui

import androidx.compose.ui.graphics.Color

/**
 * iWalk 伴侣的公开调色板：从 Flutter 主题逐字迁移的设计令牌（深色、恒不使用动态取色）。
 *
 * 令牌定义一次，后续工单从这里取值，不要再把十六进制字面量散落在各个 composable 里。
 * 命名对齐规格/笔记里的 `IwColors`，方便跨端对照。
 */
object IwColors {
    val Background = Color(0xFF0A0C0D)
    val Surface = Color(0xFF141719)
    val SurfaceHigh = Color(0xFF1D2124)
    val Outline = Color(0xFF2B3033)
    val Text = Color(0xFFF3F5F6)
    val TextMuted = Color(0xFFA2AAAD)
    val Primary = Color(0xFF35D6C6)
    val Success = Color(0xFF4ADE80)
    val Warning = Color(0xFFFBBF24)
    val Error = Color(0xFFF87171)
    val Demo = Color(0xFF60A5FA)

    /** 填充 primary 面上的文字/滑块用色（笔记 §1 一次性字面量）。 */
    val OnPrimary = Color(0xFF04201D)
}
