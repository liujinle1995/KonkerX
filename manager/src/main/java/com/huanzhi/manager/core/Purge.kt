package com.huanzhi.manager.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 把分身一次性清干净。
 *
 * 一个必须说清的事实：**卸载分身一定会弹系统确认框**，即使这个包就是本应用装的。
 * `PackageInstaller.uninstall()` 只给持有 `DELETE_PACKAGES` 的应用（系统应用、设备所有者）
 * 静默卸载的待遇，普通应用每卸一个包都得用户在系统框上点一次 —— 这条是实测的，
 * 不是没找对 API（见 README「卸载本应用时，分身去哪了」）。
 *
 * 所以这里的做法是「替用户把 N 个卸载框一个个排好队」：提交一个，等用户点完，
 * 立刻提交下一个。用户中途点取消就停下，剩下的原样保留 —— 一口气把 N 个请求全提上去
 * 只会让系统界面互相顶掉，最后谁也卸不掉。
 */
object Purge {

    /**
     * 清点家底。
     *
     * @param pending 装在系统里、属于当前前缀的分身（能卸的）
     * @param legacy 3.4 改名之前的旧前缀分身：它们是另一个包名装的，本应用连卸载请求都发不出去
     * @param records 记录条数（含没装成功过的）
     */
    data class Snapshot(
        val pending: List<String>,
        val legacy: List<String>,
        val records: Int,
    ) {
        val total: Int get() = pending.size
        val empty: Boolean get() = total == 0 && legacy.isEmpty() && records == 0
    }

    data class Outcome(
        /** 确实从系统里消失的数量 */
        val done: Int,
        /** 提交了但没卸成（用户取消 / 超时） */
        val left: List<String>,
        val legacy: List<String>,
    )

    fun snapshot(ctx: Context): Snapshot {
        val recs = CloneStore(ctx).all()
        // 记录之外还要扫系统：点过「删除记录」的分身仍占着包名和几百兆空间
        val fromSystem = runCatching {
            ctx.packageManager.getInstalledPackages(0)
                .map { it.packageName }
                .filter { TemplateSpec.isClonePkg(it) }
        }.getOrDefault(emptyList())

        val installed = (recs.map { it.clonePkg } + fromSystem)
            .distinct()
            .filter { AppScanner.isInstalled(ctx, it) }

        return Snapshot(
            pending = installed.filterNot { TemplateSpec.isLegacyClonePkg(it) },
            legacy = installed.filter { TemplateSpec.isLegacyClonePkg(it) },
            records = recs.size,
        )
    }

    /**
     * 逐个卸载。
     *
     * @param onStep 每开始一个（done = 已完成的个数），界面据此提示「第几个」。
     *   这里不传百分比：真正的阻塞点是用户在系统框上点按钮，进度条只能表达「走了几个」。
     */
    suspend fun wipe(
        ctx: Context,
        onStep: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Outcome {
        val snap = snapshot(ctx)
        if (snap.pending.isEmpty()) {
            wipeLocal(ctx)
            return Outcome(0, emptyList(), snap.legacy)
        }

        val total = snap.pending.size
        var done = 0
        val left = ArrayList<String>()

        for ((index, pkg) in snap.pending.withIndex()) {
            onStep(done, total)
            if (!submitOne(ctx, pkg)) {
                // 这一份连同后面没来得及处理的都算「没动」——
                // 只报当前这一个的话，提示里的「还有 N 个」会偏小
                left.addAll(snap.pending.drop(index))
                break
            }
            done++
            onStep(done, total)
        }

        wipeLocal(ctx)
        return Outcome(done, left, snap.legacy)
    }

    /**
     * 提交一个并等用户点完。
     *
     * 返回 false 表示这一份没卸成（用户点了取消、系统拒绝、或长时间没人操作），
     * 调用方据此停下 —— 再往下弹只会把「取消」当成常态。
     *
     * 监听必须先挂再提交：卸载回执也可能在提交后几十毫秒内就回来。
     */
    private suspend fun submitOne(ctx: Context, pkg: String): Boolean {
        val wait = InstallWait(pkg)
        InstallBus.addListener(wait.listener)
        try {
            if (!ApkInstaller.uninstall(ctx, pkg)) return false
            val outcome = withTimeoutOrNull(UNINSTALL_TIMEOUT_MS) { wait.await(UNINSTALL_TIMEOUT_MS) }
                ?: return false
            if (outcome.code != 1) return false

            // 回执说成功还不够：PackageManager 里看不到这个包才算真的卸掉了。
            // 删包和回执之间有几十到几百毫秒的空档，这里给它一点时间落定，
            // 否则会把刚卸掉的判成失败，后面的分身也就不再继续了。
            val deadline = SystemClock.elapsedRealtime() + SETTLE_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!AppScanner.isInstalled(ctx, pkg)) return true
                delay(120)
            }
            return !AppScanner.isInstalled(ctx, pkg)
        } finally {
            InstallBus.removeListener(wait.listener)
        }
    }

    /** 记录与临时文件：整份删掉比逐条删更贴合「清空」的意思 */
    fun wipeLocal(ctx: Context) {
        runCatching { File(ctx.filesDir, "clones.json").delete() }
        runCatching { CacheCleaner.clear(ctx) }
    }

    /** 拉起系统卸载页卸掉本应用 */
    fun uninstallSelf(ctx: Context) {
        val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }

    /** 每个卸载框最多等这么久没人点，就当用户不打算继续了 */
    private const val UNINSTALL_TIMEOUT_MS = 120_000L

    /** 卸载回执到了之后，等 PackageManager 真正把这个包摘掉的时长 */
    private const val SETTLE_MS = 3_000L
}
