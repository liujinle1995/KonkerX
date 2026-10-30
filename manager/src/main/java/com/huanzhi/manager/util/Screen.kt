package com.huanzhi.manager.util

import android.content.Context

/**
 * 屏幕形态判断。
 *
 * 只判断「够不够宽放两列」，所以用 screenWidthDp 而不是 smallestScreenWidthDp：
 * 折叠屏展开、平板横屏都能正确落到两列。
 */
object Screen {

    private const val TWO_COLUMN_MIN_DP = 600

    fun columns(ctx: Context): Int =
        if (ctx.resources.configuration.screenWidthDp >= TWO_COLUMN_MIN_DP) 2 else 1
}
