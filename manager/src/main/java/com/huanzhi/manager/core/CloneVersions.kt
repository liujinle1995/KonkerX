package com.huanzhi.manager.core

import android.content.Context

/**
 * 源应用的版本信息。
 */
data class SourceVersion(val code: Long, val name: String)

enum class UpdateKind {
    /** 装的已经是源应用当前版本 */
    NONE,

    /** 记录里没有版本号（3.4 之前建的、或从别处导进来的）—— 无法判断，就不提示 */
    UNKNOWN,

    /** 源应用比这份分身新 */
    AVAILABLE,

    /** 源应用已经不在了：分身照常能跑，但没法再重建 */
    SOURCE_GONE,
}

data class UpdateState(val kind: UpdateKind, val source: SourceVersion? = null)

/**
 * 「这份分身比源应用旧」这件事只在这里判断。
 *
 * 放在界面里算的坏处是：列表、批量更新、角标三处各写一遍，
 * 早晚会出现「角标说可更新、点全部更新却跳过它」这种自相矛盾。
 */
object CloneVersions {

    fun sourceVersion(ctx: Context, pkg: String): SourceVersion? = try {
        val pi = ctx.packageManager.getPackageInfo(pkg, 0)
        SourceVersion(pi.longVersionCode, pi.versionName ?: "")
    } catch (t: Throwable) {
        null
    }

    /**
     * @return 判断结果。注意 [UpdateKind.UNKNOWN] 不是「不需要更新」，而是「不知道」——
     *   这种记录在批量更新里也不会被选中，免得一升级就把所有老分身重打一遍。
     *   等用户手动重建一次，版本号补上，之后就能正常判断了。
     */
    fun stateOf(ctx: Context, rec: CloneRecord): UpdateState {
        if (rec.sourceVersionCode <= 0L) return UpdateState(UpdateKind.UNKNOWN)
        val src = sourceVersion(ctx, rec.targetPkg)
            ?: return UpdateState(UpdateKind.SOURCE_GONE)
        return if (src.code > rec.sourceVersionCode) {
            UpdateState(UpdateKind.AVAILABLE, src)
        } else {
            UpdateState(UpdateKind.NONE, src)
        }
    }

    /** 批量更新要处理的那一批：源应用还在、且确实比记录里新 */
    fun outdated(ctx: Context, records: List<CloneRecord>): List<CloneRecord> =
        records.filter { stateOf(ctx, it).kind == UpdateKind.AVAILABLE }

    /** 版本号显示：优先版本名，没有就退回数字，别显示「→ 」后面一片空白 */
    fun show(code: Long, name: String): String = name.ifBlank { code.toString() }
}
