package com.iwalk.companion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * 深色 ColorScheme：角色映射照抄 iw_theme.dart:30-39，其余种子色产物一律不保留，
 * 因此直接硬编码上面的令牌即为精确对齐（笔记 §1）。应用恒为深色，不提供 light 变体。
 */
private val IwDarkScheme = darkColorScheme(
    primary = IwColors.Primary,
    onPrimary = IwColors.OnPrimary,
    surface = IwColors.Surface,
    surfaceContainerHigh = IwColors.SurfaceHigh,
    outlineVariant = IwColors.Outline,
    onSurface = IwColors.Text,
    onSurfaceVariant = IwColors.TextMuted,
    error = IwColors.Error,
    background = IwColors.Background,
    onBackground = IwColors.Text,
)

/**
 * 主题外壳包装：把 [IwApp] 及后续所有界面钉死在深色调色板上。
 * 后续工单在内容里通过 MaterialTheme / [IwColors] 取令牌。
 */
@Composable
fun IwTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = IwDarkScheme,
        content = content,
    )
}
