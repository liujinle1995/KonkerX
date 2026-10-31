package com.huanzhi.manager.ui.adaptive

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 窗口尺寸类（Material 3 的三档断点）。
 *
 * | 档位       | 宽度区间      | 典型设备                     | 导航形态     |
 * |------------|---------------|------------------------------|--------------|
 * | COMPACT    | < 600dp       | 手机竖屏                     | 底部胶囊     |
 * | MEDIUM     | 600–839dp     | 手机横屏、折叠屏外屏、小竖屏平板 | 底部胶囊     |
 * | EXPANDED   | ≥ 840dp       | 平板横屏、折叠屏展开、桌面窗口 | 左侧栏       |
 *
 * 为什么自己实现而不用 androidx.window-java：`sw600dp` 这类资源限定符看的是
 * **最小宽度**，而这里看的是**当前宽度**。区别在于：平板横屏下把应用拖进分屏，
 * 实际宽度可能掉到 600dp 以下，但 sw 没变 —— 用资源限定符会继续给出侧栏布局，
 * 结果侧栏占掉 220dp，内容区只剩 300dp 宽。
 *
 * 3.7 把「三档各自几列」从枚举里拿掉了。原因是设计稿给的是**平板两列网格**，
 * 而列数真正该由「内容区还有多宽」决定，不是由窗口档位决定 —— 分屏、桌面模式
 * 拉伸窗口时档位可能不变但宽度一直在变。现在只回答一个问题：
 * **该用哪种导航**，剩下的交给 [gridColumns]。
 */
enum class WindowSizeClass(val railNavigation: Boolean) {
    COMPACT(railNavigation = false),
    MEDIUM(railNavigation = false),
    EXPANDED(railNavigation = true),
}

/**
 * 尺寸类的集中判定入口。
 */
object WindowSizeClasses {

    /** Material 3 的官方断点值，不要改成整数，也不要到处抄这两个数字 */
    private const val MEDIUM_MIN_DP = 600
    private const val EXPANDED_MIN_DP = 840

    fun of(context: Context): WindowSizeClass {
        val w = context.resources.configuration.screenWidthDp
        return when {
            w >= EXPANDED_MIN_DP -> WindowSizeClass.EXPANDED
            w >= MEDIUM_MIN_DP -> WindowSizeClass.MEDIUM
            else -> WindowSizeClass.COMPACT
        }
    }

    /** 选应用页：手机上一条流，平板/折叠屏展开下三列网格 */
    fun pickerColumns(context: Context): Int = when (of(context)) {
        WindowSizeClass.COMPACT -> 1
        WindowSizeClass.MEDIUM -> 2
        WindowSizeClass.EXPANDED -> 3
    }
}

/**
 * 头像卡片网格的列数。
 *
 * 从「内容区宽度能塞下几张最小宽度的卡」反推，而不是写死 2/3/4 列：
 * 设计稿的 1024dp 平板给 2 列（每列 370dp），但同一个布局在 1280dp 的桌面窗口里
 * 硬撑 2 列会得到两张 600dp 宽、内容只占左边 1/3 的空卡。
 *
 * [minCardWidth] 取 340dp：比设计稿的 370dp 略窄，留出"刚好还能放一张"的余量，
 * 避免在 900dp 这类临界宽度上把卡压到 380dp 反而显得局促。
 * 返回值至少 1，最多 [maxColumns]，手机上永远返回 1。
 */
fun gridColumns(
    contentWidth: Dp,
    minCardWidth: Dp = 340.dp,
    gap: Dp = 14.dp,
    maxColumns: Int = 4,
): Int {
    if (contentWidth <= 0.dp) return 1
    val fit = ((contentWidth + gap) / (minCardWidth + gap)).toInt()
    return fit.coerceIn(1, maxColumns)
}

/** Compose 侧读当前档位。转屏/分屏会让 Activity 重建，重组时配置已是新的 */
@Composable
@ReadOnlyComposable
fun rememberWindowSizeClass(): WindowSizeClass {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w >= 840 -> WindowSizeClass.EXPANDED
        w >= 600 -> WindowSizeClass.MEDIUM
        else -> WindowSizeClass.COMPACT
    }
}
