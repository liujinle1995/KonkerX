package com.huanzhi.manager.ui.adaptive

import android.content.Context

/**
 * 窗口尺寸类（Material 3 的三档断点）。
 *
 * | 档位       | 宽度区间      | 典型设备                     |
 * |------------|---------------|------------------------------|
 * | COMPACT    | < 600dp       | 手机竖屏                     |
 * | MEDIUM     | 600–839dp     | 手机横屏、折叠屏外屏、小平板  |
 * | EXPANDED   | ≥ 840dp       | 平板、折叠屏展开、桌面窗口    |
 *
 * 为什么自己实现而不用 androidx.window-java：`sw600dp` 这类资源限定符看的是
 * **最小宽度**，而这里看的是**当前宽度**。区别在于：平板横屏下把应用拖进分屏，
 * 实际宽度可能掉到 600dp 以下，但 sw 没变 —— 用资源限定符会继续给出两栏布局，
 * 结果两栏各自被压成一条。
 *
 * 取值用 `Configuration.screenWidthDp`（当前窗口可用宽度，已扣除系统栏），
 * 每调用一次读一次：配置变化（转屏、折叠、分屏）会伴随 Activity 重建，
 * 重建后的宽度自然就是新的，不需要额外传监听。
 */
enum class WindowSizeClass(
    /** 这一档下分身列表给几列。EXPANDED 回到 1 列是因为要留给右侧详情栏 */
    val listColumns: Int,
    /** 是否启用 list-detail 双栏 */
    val listDetail: Boolean,
) {
    COMPACT(listColumns = 1, listDetail = false),
    MEDIUM(listColumns = 2, listDetail = false),
    EXPANDED(listColumns = 1, listDetail = true),
}

/**
 * 尺寸类的集中判定入口。
 *
 * 原先这个功能叫 `util.Screen.columns()`，只有一个宽不宽度判断，
 * 「平板要几列」的答案就散到了各个 Activity 里。现在把断点、列数、
 * 是否双栏三件事放在同一个表的同一行，改断点时不会只改一处。
 */
object WindowSizeClasses {

    /** Material 3 的官方断点值，不要改成整数，也不要到处抄这两的数字 */
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
