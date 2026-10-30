package com.huanzhi.manager.core

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 批量分身调度器。
 *
 * 为什么安装必须串行：每一份 APK 提交给 PackageInstaller 后都会拉起一个系统确认框，
 * 同时提交多个会把确认框互相顶掉，用户只装得上最后一份。
 * 所以安装部分固定为「提交 → 等回执 → 再下一份」。
 *
 * 但**打包**不必串行：等用户点系统弹窗的那几十秒里 CPU 是空的。
 * 1.8 起这里做了预构建 —— 第 N 份提交完立刻开始打第 N+1 份，
 * 用户点完确认时下一份已经就绪，129MB 的大应用能省掉一半以上的等待。
 *
 * 调度跑在 Application 级别的协程里，Activity 转屏重建也不会中断；
 * 界面通过 onProgress / onDone 两个回调接入，重建后重新挂上即可。
 */
object BatchRunner {

    data class Plan(
        val targetPkg: String,
        val targetLabel: String,
        val copies: Int,
        /**
         * 每份的最终名称，按顺序，长度应等于 [copies]。
         *
         * 1.9 起批量不再只有一个「统一前缀」，而是每一份都能单独起名，
         * 所以这里直接收最终结果 —— 名字的拼接、去重、编号都在界面上做完了。
         * 留空表示走默认的「应用名 · 分身N」。
         */
        val names: List<String> = emptyList(),
    )

    /**
     * @param fraction 整批的总体进度（0–1），已经包含「当前这一份打包到几成」。
     *   只看 [done]/[total] 的话，一个 150MB 的应用打包要四十秒，进度条会一动不动，
     *   用户又会以为卡死 —— 那正是这次要解决的问题。
     */
    data class Progress(
        val done: Int,
        val total: Int,
        val text: String,
        val fraction: Float = if (total > 0) done.toFloat() / total else 0f,
    )

    data class Fail(val name: String, val reason: String, val pkg: String? = null)

    data class Summary(val ok: Int, val failed: List<Fail>, val cancelled: Boolean) {
        /** 所有失败都是同一个原因时才返回它：界面上不必重复五遍 */
        val commonReason: String?
            get() = failed.map { it.reason }.distinct().singleOrNull()
                ?.takeIf { failed.size > 1 }
    }

    /** 单份安装的等待下限 / 上限：用户可能手滑切走了，不能无限挂住 */
    private const val INSTALL_TIMEOUT_MIN_MS = 180_000L
    private const val INSTALL_TIMEOUT_MAX_MS = 480_000L
    /**
     * 两份安装之间留的间隙。
     *
     * 不能太短：上一份装完后，系统的安装界面还停在「已安装 · 打开 / 完成」那一屏，
     * 此时立刻提交下一份会再叠一个确认框 —— 用户朝旧屏幕点一下就顺手把新对话框关了，
     * 表现出来就是「建两个只装上一个」。留一秒多让旧界面先退场。
     */
    private const val GAP_MS = 1_200L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    var onProgress: ((Progress) -> Unit)? = null
    var onDone: ((Summary) -> Unit)? = null

    /** 最近一次进度，界面重建后据此恢复显示 */
    var lastProgress: Progress? = null
        private set

    /** 最近一次结果，界面在批量结束后才回到前台时用来补一次汇总 */
    var lastSummary: Summary? = null
        private set

    fun clearSummary() {
        lastSummary = null
    }

    private fun emit(p: Progress) {
        lastProgress = p
        onProgress?.invoke(p)
    }

    val isRunning: Boolean get() = job?.isActive == true

    /** 一份打包的结果：要么有记录，要么有原因 */
    private class Built(val name: String, val rec: CloneRecord?, val error: String?)

    fun start(ctx: Context, plans: List<Plan>) {
        if (isRunning) return
        lastProgress = null
        lastSummary = null
        val app = ctx.applicationContext
        job = scope.launch {
            val total = plans.sumOf { it.copies }
            val wf = CloneWorkflow(app)
            val store = CloneStore(app)
            val startedAt = System.currentTimeMillis()
            var done = 0
            var okCount = 0
            val failed = mutableListOf<Fail>()
            val built = mutableListOf<CloneRecord>()
            var cancelled = false

            InstallLog.append(
                app,
                "batch start total=$total " +
                    plans.joinToString(";") { "${it.targetPkg}x${it.copies}" }
            )

            // 摊平成一个队列，方便「上一份等确认时预构建下一份」
            val items = ArrayList<Pair<Plan, Int>>(total)
            plans.forEach { p -> for (i in 1..p.copies) items.add(p to i) }

            fun resolveName(item: Pair<Plan, Int>): String {
                val (p, seq) = item
                val given = p.names.getOrNull(seq - 1)?.trim()
                return if (given.isNullOrEmpty()) {
                    store.defaultName(p.targetLabel, p.targetPkg)
                } else {
                    given
                }
            }

            // 进度只许往前走：预构建下一份和等待当前这份安装确认是并行发生的，
            // 两个来源交替上报时，如果不过滤就会出现「进度条倒退」这种最糟的反馈
            var shownFraction = 0f
            fun emitFraction(fraction: Float, text: String) {
                val f = fraction.coerceIn(shownFraction, 1f)
                shownFraction = f
                emit(Progress(done, total, text, f))
            }

            suspend fun build(item: Pair<Plan, Int>, name: String, index: Int): Built {
                val started = System.currentTimeMillis()
                return try {
                    val rec = wf.prepare(
                        item.first.targetPkg,
                        item.first.targetLabel,
                        name,
                        control = BuildControl(
                            // 上一份被用户取消时，正在预构建的这一份也要停 —— 它的记录
                            // 是在 build 成功之后才落库的，趁早中断才能连记录都不留
                            isCancelled = { cancelled },
                            onProgress = { p ->
                                // 构建占总时长的 88%，剩下 12% 是写安装会话
                                val overall = (index + p.percent / 100f * 0.88f) / total
                                emitFraction(
                                    overall,
                                    "正在生成 $name（${index + 1}/$total）· ${p.stage.label} ${p.percent}%"
                                )
                            }
                        )
                    )
                    InstallLog.append(app, "build ok pkg=${rec.clonePkg} ${System.currentTimeMillis() - started}ms")
                    Built(name, rec, null)
                } catch (t: Throwable) {
                    val reason = describeThrowable(t)
                    InstallLog.append(app, "build FAIL ${item.first.targetPkg} -> $reason")
                    Built(name, null, reason)
                }
            }

            var pending: Deferred<Built>? = null
            // 预构建结果落在哪儿：取消时要用它判断「这一份是不是已经打完了」。
            // 用 getCompleted() 也行，但那是实验 API；这里在 Main 上写、在 Main 上读，
            // 不需要额外同步
            var pendingResult: Built? = null

            for (idx in items.indices) {
                if (!isActive) break

                val item = items[idx]
                val b: Built = if (pending != null) {
                    pending.await().also {
                        pending = null
                        pendingResult = null
                    }
                } else {
                    val n = resolveName(item)
                    emitFraction(idx.toFloat() / total, "正在生成 $n（${idx + 1}/$total）")
                    build(item, n, idx)
                }

                val rec = b.rec
                if (rec == null) {
                    failed += Fail(b.name, b.error ?: "生成失败", null)
                    done++
                    continue
                }
                built += rec

                emitFraction((idx + 0.94f) / total, "等待安装确认：${b.name}（${idx + 1}/$total）")
                // 体积要在提交前量：提交完成后本地这份包会被立刻删掉（见 CloneWorkflow.submit）
                val apkBytes = File(rec.apkPath).let { if (it.exists()) it.length() else 0L }

                // 先挂监听再提交：小包从 commit 到回执可能只要几十毫秒，
                // 反过来做会漏掉那次回调，然后傻等几分钟超时（应用其实早装好了）
                val waiter = InstallWait(rec.clonePkg)
                InstallBus.addListener(waiter.listener)
                val outcome: InstallOutcome
                try {
                    emitFraction((idx + 0.96f) / total, "写入安装会话：${b.name}（${idx + 1}/$total）")
                    val submitted = withContext(Dispatchers.IO) {
                        wf.install(rec) { f ->
                            emitFraction(
                                (idx + 0.96f + f * 0.04f) / total,
                                "写入安装会话：${b.name}（${idx + 1}/$total）· ${(f * 100).toInt()}%",
                            )
                        }
                    }
                    outcome = if (!submitted) {
                        InstallLog.append(app, "submit FAIL pkg=${rec.clonePkg}")
                        InstallOutcome(0, "无法提交安装（系统拒绝创建安装会话）")
                    } else {
                        // 会话已经提交，接下来是「等用户点确认」——这段时间 CPU 是空的，
                        // 正好把下一份打包做掉
                        if (idx + 1 < items.size && isActive) {
                            val next = items[idx + 1]
                            val nextName = resolveName(next)
                            pending = async { build(next, nextName, idx + 1).also { pendingResult = it } }
                            emitFraction(
                                (idx + 1f) / total,
                                "等待安装确认 · 同时准备 $nextName（${idx + 2}/$total）"
                            )
                        }
                        waiter.await(InstallWait.timeoutFor(apkBytes))
                    }
                } finally {
                    InstallBus.removeListener(waiter.listener)
                }

                val name = b.name
                // 系统状态才是真的：回执说取消/失败、回执丢了、超时了，都先问一句「到底装上没有」。
                // 真机上部分 ROM 在「已完成」那一屏点「完成」回的是 STATUS_FAILURE_ABORTED，
                // 只信回执的话，装好的那一份会被记成「已取消」，而且批量会就此停摆 ——
                // 用户看到的就是「要五个，只出来两个」。
                val landed = outcome.code == 1 || isInstalledNow(app, rec.clonePkg)
                when {
                    landed -> {
                        okCount++
                        store.markInstalled(rec.clonePkg)
                        if (outcome.code != 1) {
                            InstallLog.append(
                                app,
                                "recheck pkg=${rec.clonePkg} -> installed（回执：${outcome.reason}）"
                            )
                        }
                    }
                    outcome.code == -1 -> {
                        failed += Fail(name, "已取消", rec.clonePkg)
                        cancelled = true
                    }
                    outcome.definitive -> {
                        failed += Fail(name, outcome.reason, rec.clonePkg)
                        // 装不上就把这条记录一起撤掉，免得列表里堆一串「未安装」
                        wf.forget(rec)
                    }
                    else -> {
                        // 超时属于「还没结果」：记录留着，列表里显示未安装，点它可补装
                        failed += Fail(name, outcome.reason, rec.clonePkg)
                    }
                }
                InstallLog.append(
                    app,
                    "batch pkg=${rec.clonePkg} size=$apkBytes -> ${outcome.reason}"
                )
                done++
                if (cancelled) break
                delay(GAP_MS)
            }

            // 取消之后，预构建的那一份要是已经打完了，记录已经落库却没装 ——
            // 留着就是列表里一行点不动的「未安装」。还没打完的不用管：
            // build() 的 control 上挂着 cancelled 标记，会在落库之前自己中断。
            if (cancelled) {
                pendingResult?.rec?.let {
                    wf.forget(it)
                    InstallLog.append(app, "cancel cleanup pkg=${it.clonePkg}")
                }
                pending = null
                pendingResult = null
            }

            // 收尾回查：以系统安装列表为准，把「其实装上了」的失败项纠正回来
            if (!cancelled && failed.isNotEmpty()) {
                val (installed, stillBad) = failed.partition {
                    it.pkg != null && isInstalledNow(app, it.pkg)
                }
                if (installed.isNotEmpty()) {
                    okCount += installed.size
                    installed.forEach { f -> f.pkg?.let { store.markInstalled(it) } }
                    failed.clear()
                    failed.addAll(stillBad)
                    InstallLog.append(app, "reconcile rescued=${installed.size} still=${stillBad.size}")
                }
            }

            val elapsed = (System.currentTimeMillis() - startedAt) / 1000
            InstallLog.append(app, "batch done ok=$okCount fail=${failed.size} ${elapsed}s")
            val summary = Summary(okCount, failed, cancelled)
            lastSummary = summary
            onDone?.invoke(summary)
        }
    }

    private fun isInstalledNow(ctx: Context, pkg: String): Boolean = AppScanner.isInstalled(ctx, pkg)

    private fun describeThrowable(t: Throwable): String {
        val m = t.message
        return if (m.isNullOrBlank()) t.javaClass.simpleName else m.take(200)
    }

}
