package com.huanzhi.manager.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 3.7 设计令牌 —— Liquid Glass 重设计。
 *
 * 【数值来源】全部由 `design` 目录下的设计稿逐点采样得出，不是估计：
 * 稿面 1500×3248px = 375×812pt @4x，所以 **1dp = 4px**，换算无损。
 *   页面底色 #F2F2F7 / 卡片 #FFFFFF（浅）；#000000 / #1C1C1E（深）
 *   强调蓝 #007AFF / #0A84FF —— 正好是 iOS 的 systemBlue，深浅各一档
 *   次级文字 #8E8E93 / #98989E
 *   卡片 88dp 高、20dp 间距、16dp 页边距、圆角 16dp
 *
 * 【为什么不用 Material3 的 ColorScheme】
 * M3 的动态取色与 tonal palette 会把颜色算到自己的一套色阶上，而设计稿的颜色
 * 是逐档点名的（浅色蓝就是 #007AFF，不是 Primary40）。硬塞进 M3 会被改色，
 * 所以这里自建一套，只借 M3 的排版与交互基建。
 */
@Immutable
data class HzPalette(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val green: Color,
    val orange: Color,
    val red: Color,
    val badgeGreenBg: Color,
    val badgeBlueBg: Color,
    val badgeOrangeBg: Color,
    val badgeRedBg: Color,
    val glassFill: Color,
    val glassStroke: Color,
    val navSelectedBg: Color,
    val divider: Color,
    val iconTileBg: Color,
    val isDark: Boolean,
)

/**
 * 浅色档。
 *
 * 有一处刻意偏离设计稿：#C7C7CC（三级文字）在纯白卡片上只有 2.1:1，
 * 达不到 WCAG AA 的 4.5:1。设计稿里它只用于「未启动」这类可省的信息，
 * 我保留原色（视觉一致优先），但所有真正需要读的文字都不用它 —— 见 [HzTypography]。
 */
private val LightPalette = HzPalette(
    background = Color(0xFFF2F2F7),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF000000),
    textSecondary = Color(0xFF8E8E93),
    textTertiary = Color(0xFFC7C7CC),
    accent = Color(0xFF007AFF),
    green = Color(0xFF248A3D),
    orange = Color(0xFFC93400),
    red = Color(0xFFD70015),
    badgeGreenBg = Color(0xFFD7ECE0),
    badgeBlueBg = Color(0xFFD2E3F6),
    badgeOrangeBg = Color(0xFFF7E3CC),
    badgeRedBg = Color(0xFFFADCD9),
    glassFill = Color(0xF2F5F5F5),
    glassStroke = Color(0x14000000),
    navSelectedBg = Color(0xFFD2E3F6),
    divider = Color(0x1F000000),
    iconTileBg = Color(0x0F000000),
    isDark = false,
)

/**
 * 深色档。
 *
 * 卡片 #1C1C1E 与底色 #000000 之间只差 12 阶亮度 —— 这是 iOS 分组列表的典型做法，
 * 靠「卡片比背景亮一点」而不是描边来分层。因此深色下**不要**再给卡片加描边，
 * 否则会得到一圈比卡片本身还显眼的白边。
 */
private val DarkPalette = HzPalette(
    background = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    surfaceRaised = Color(0xFF242426),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF98989E),
    textTertiary = Color(0xFF6C6C70),
    accent = Color(0xFF0A84FF),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
    red = Color(0xFFFF453A),
    badgeGreenBg = Color(0xFF092610),
    badgeBlueBg = Color(0xFF02182E),
    badgeOrangeBg = Color(0xFF2A1D06),
    badgeRedBg = Color(0xFF2E0D0B),
    glassFill = Color(0xF2242426),
    glassStroke = Color(0x1FFFFFFF),
    navSelectedBg = Color(0xFF1F3751),
    divider = Color(0xFF2C2C2E),
    iconTileBg = Color(0x1FFFFFFF),
    isDark = true,
)

/**
 * 排版。字号同样量自设计稿（大标题字高 29dp → 34pt 字号）。
 *
 * 中文在 Android 上默认落到思源黑体/Roboto，与稿面的苹方有细微差异：
 * 同样的字号，思源的中宫更大、字面更满，观感会「胖」一点点。
 * 这里把行高按 1.2 倍给足，抵消这个差异，不去改字号（改了就不叫像素级了）。
 */
@Immutable
data class HzTypography(
    val largeTitle: TextStyle,
    val subtitle: TextStyle,
    val section: TextStyle,
    val cardTitle: TextStyle,
    val cardMeta: TextStyle,
    val status: TextStyle,
    val badge: TextStyle,
    val tab: TextStyle,
    val button: TextStyle,
)

private val Typography = HzTypography(
    // 34pt / 41pt —— iOS Large Title
    largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
    subtitle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    section = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    cardTitle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    cardMeta = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    status = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    badge = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    tab = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium),
    button = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
)

/**
 * 尺寸。命名按语义而不是数值 —— 看到 `cardGap` 就知道改它会影响什么，
 * 看到 `20dp` 只能靠翻代码。
 */
@Immutable
data class HzDimens(
    /** 页面左右边距。量得 16dp，与 iOS 分组列表的标准边距一致 */
    val pagePadding: androidx.compose.ui.unit.Dp = 16.dp,
    val cardHeight: androidx.compose.ui.unit.Dp = 88.dp,
    val cardGap: androidx.compose.ui.unit.Dp = 20.dp,
    val cardCorner: androidx.compose.ui.unit.Dp = 16.dp,
    val cardPadding: androidx.compose.ui.unit.Dp = 16.dp,
    val iconSize: androidx.compose.ui.unit.Dp = 48.dp,
    val iconCorner: androidx.compose.ui.unit.Dp = 12.dp,
    /** 大标题到副标题的间距 */
    val titleGap: androidx.compose.ui.unit.Dp = 6.dp,
    val sectionGap: androidx.compose.ui.unit.Dp = 18.dp,
    /** 底部玻璃导航栏胶囊高 70dp，距底 16dp（留出系统手势区） */
    val tabBarHeight: androidx.compose.ui.unit.Dp = 70.dp,
    val tabBarInset: androidx.compose.ui.unit.Dp = 16.dp,
    val tabBarCorner: androidx.compose.ui.unit.Dp = 35.dp,
    val fabSize: androidx.compose.ui.unit.Dp = 56.dp,
    val touchMin: androidx.compose.ui.unit.Dp = 48.dp,
)

/** 最小可点区域。低于 48dp 的按钮一律用它兜住，不靠调用方自觉 */
val TouchMin: androidx.compose.ui.unit.Dp = 48.dp

val LocalHzPalette = staticCompositionLocalOf { LightPalette }
val LocalHzTypography = staticCompositionLocalOf { Typography }
val LocalHzDimens = staticCompositionLocalOf { HzDimens() }

internal val hzLightPalette: HzPalette get() = LightPalette
internal val hzDarkPalette: HzPalette get() = DarkPalette
internal val hzTypography: HzTypography get() = Typography
