package com.huanzhi.manager.core

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * 管理器自身的偏好设置。
 *
 * 用 SharedPreferences 就够了，引入 DataStore 只会多一个依赖和一层协程。
 */
object SettingsStore {

    private const val FILE = "huanzhi_settings"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_INDEX_SCALE = "index_scale"
    private const val KEY_LEGACY_NOTICE = "legacy_notice_shown"

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    const val INDEX_SMALL = 0
    const val INDEX_NORMAL = 1
    const val INDEX_LARGE = 2

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun themeMode(ctx: Context): Int = sp(ctx).getInt(KEY_THEME, THEME_SYSTEM)

    fun setThemeMode(ctx: Context, mode: Int) {
        sp(ctx).edit().putInt(KEY_THEME, mode).apply()
        applyTheme(ctx)
    }

    /**
     * 在 Application.onCreate 里调用，早于任何 Activity 创建，
     * 这样首次进入界面就已经是正确的日夜模式，不会闪一下再切。
     */
    fun applyTheme(ctx: Context) {
        val mode = when (themeMode(ctx)) {
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    // ---------------- 选应用页的首字母索引 ----------------

    fun indexScale(ctx: Context): Int = sp(ctx).getInt(KEY_INDEX_SCALE, INDEX_NORMAL)

    fun setIndexScale(ctx: Context, scale: Int) {
        sp(ctx).edit().putInt(KEY_INDEX_SCALE, scale).apply()
    }

    /**
     * 索引条字母的字号（sp）。
     *
     * 2.x 里写死 11sp：在 6.7 寸真机上只有指甲盖一半高，按着找字母很费劲。
     * 默认抬到 13sp，再给两档让用户自己调 —— 视力、机型、单手还是双手，差别挺大。
     */
    fun indexTextSp(ctx: Context): Float = when (indexScale(ctx)) {
        INDEX_SMALL -> 11f
        INDEX_LARGE -> 17f
        else -> 13f
    }

    // ---------------- 旧包名前缀的提示 ----------------

    /**
     * 3.4 换了包名（konkerx → huanzhi），旧版分身不再归新版管。
     * 这个标记只保证提示弹一次 —— 每次启动都弹就成了骚扰。
     */
    fun legacyNoticeShown(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_LEGACY_NOTICE, false)

    fun setLegacyNoticeShown(ctx: Context) {
        sp(ctx).edit().putBoolean(KEY_LEGACY_NOTICE, true).apply()
    }
}
