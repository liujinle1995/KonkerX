package com.huanzhi.manager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * 应用主题。
 *
 * 自建 [HzPalette] 承载设计稿的颜色，同时把一套等价映射喂给 [MaterialTheme] ——
 * 因为 Compose 的内建组件（TextField、Dialog、Switch）只认 MaterialTheme.colorScheme，
 * 不提供的话它们会退回紫色默认皮肤，在页面里格外扎眼。
 *
 * 两者的分工：自己的界面一律读 `HzTheme.colors`，Material 组件读 MaterialTheme，
 * 但取值同源，不会出现「开关是紫的、周围是蓝的」这种事故。
 */
@Composable
fun HuanZhiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) hzDarkPalette else hzLightPalette
    val typography = hzTypography

    CompositionLocalProvider(
        LocalHzPalette provides palette,
        LocalHzTypography provides typography,
        LocalHzDimens provides HzDimens(),
    ) {
        MaterialTheme(
            colorScheme = palette.toMaterialScheme(),
            typography = typography.toMaterialTypography(),
            content = content,
        )
    }
}

/**
 * 取令牌的入口。
 *
 * 写成 object 而不是让调用方直接读 Local，是为了让「这个页面依赖主题」这件事
 * 在 import 里就能看出来；也方便以后加语义别名（如 `HzTheme.colors.statusOk`）。
 */
object HzTheme {
    val colors: HzPalette
        @Composable @ReadOnlyComposable get() = LocalHzPalette.current

    val type: HzTypography
        @Composable @ReadOnlyComposable get() = LocalHzTypography.current

    val dimen: HzDimens
        @Composable @ReadOnlyComposable get() = LocalHzDimens.current
}

/**
 * 映射到 Material3。
 *
 * 只映射界面上真会用到的那几个槽位，其余留 Material 默认 —— 生成一整套
 * tonal palette 等于把设计稿的颜色又交给算法算一遍，反而不准。
 */
private fun HzPalette.toMaterialScheme() = if (isDark) {
    darkColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = badgeBlueBg,
        onPrimaryContainer = accent,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceRaised,
        onSurfaceVariant = textSecondary,
        error = red,
        onError = Color.White,
        outline = divider,
        outlineVariant = glassStroke,
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = badgeBlueBg,
        onPrimaryContainer = accent,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceRaised,
        onSurfaceVariant = textSecondary,
        error = red,
        onError = Color.White,
        outline = divider,
        outlineVariant = glassStroke,
    )
}

private fun TextStyle.toM3() = this

private fun HzTypography.toMaterialTypography() = Typography(
    displayLarge = largeTitle,
    titleLarge = cardTitle,
    titleMedium = cardTitle,
    bodyLarge = subtitle,
    bodyMedium = cardMeta,
    bodySmall = cardMeta,
    labelLarge = button,
    labelMedium = badge,
    labelSmall = tab,
)
