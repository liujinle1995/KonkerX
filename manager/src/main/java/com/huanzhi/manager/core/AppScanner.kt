package com.huanzhi.manager.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    /** 识别到的加固壳品牌，null 表示没扫到或还没扫 */
    val hardening: String? = null
)

/**
 * 扫描「可用于分身」的应用。
 */
object AppScanner {

    fun launchableApps(ctx: Context, includeSystem: Boolean = false): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)

        val out = LinkedHashMap<String, AppEntry>()
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == ctx.packageName) continue
            if (TemplateSpec.isClonePkg(pkg)) continue
            // 壳模板自身的包名也不允许分身，否则会自我套娃
            if (pkg == TemplateSpec.SHELL_PKG) continue

            val ai = ri.activityInfo.applicationInfo ?: continue
            val system = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            if (system && !includeSystem) continue

            val label = ri.loadLabel(pm).toString()
            val icon = ri.loadIcon(pm)
            val existing = out[pkg]
            // 同一个包多个入口时保留第一个
            if (existing == null) out[pkg] = AppEntry(pkg, label, icon, system)
        }
        return out.values.sortedBy { it.label }
    }

    fun labelOf(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (t: Throwable) {
        pkg
    }

    fun iconOf(ctx: Context, pkg: String): Drawable? = try {
        ctx.packageManager.getApplicationIcon(pkg)
    } catch (t: Throwable) {
        null
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (t: Throwable) {
        false
    }
}
