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
     * 队列里一份分身此刻在做什么。
     *
     * 这里的颗粒度是给「任务队列」那张清单用的：用户要能一眼看出
     * 「哪几份好了、现在卡在第几份、为什么卡住」。只报一个总百分比做不到这件事 ——
     * 2/7 和 5/7 在界面上是同一根进度条的两端，但前者是「刚开头」，后者是「快完了」。
     */
    enum class ItemState {
        /** 还没轮到 */
        Queued,
        /** 正在打包：占整份时间的大头 */
        Building,
        /** 包已打好，等前一份装完才轮到它进系统确认框（空档预构建的产物） */
        Ready,
        /** 包已就绪，等用户在系统弹窗上点确认 */
        Waiting,
        Installed,
        /** 打包或安装失败 */
        Failed,
        /** 用户点了「跳过这一份」：包留着，可补装 */
        Skipped,
        /** 用户取消了剩余队列，这一份没轮到 */
        Cancelled,
    }

    /**
     * 队列里的一份。
     *
     * [pkg] 在打包开始前是空的 —— 克隆包名由 `CloneStore.nextId()` 在构建时才分配，
     * 提前猜一个（按顺序推算）在「中间某一份失败」之后就会全体错位，
     * 界面上出现一行写着 k05、点开却是 k06 的东西，比空着更糟。
     */
    data class Item(
        /** 1-based，和界面上的「第 3/7 份」对齐 */
        val index: Int,
        val name: String,
        val pkg: String = "",
        val bytes: Long = 0L,
        val state: ItemState = ItemState.Queued,
        /** 仅在 [ItemState.Building] 时有意义：这一份自己打到几成 */
        val percent: Int = 0,
        val elapsedMs: Long = 0L,
        val reason: String? = null,
    ) {
        val finished: Boolean
            get() = state == ItemState.Installed || state == ItemState.Failed ||
                state == ItemState.Skipped || state == ItemState.Cancelled
    }

    /**
     * @param fraction 整批的总体进度（0–1），已经包含「当前这一份打包到几成」。
     *   只看 [done]/[total] 的话，一个 150MB 的应用打包要四十秒，进度条会一动不动，
     *   用户又会以为卡死 —— 那正是这次要解决的问题。
     * @param items 队列快照。每次都是新列表（[Item] 不可变），界面直接换掉整份状态即可。
     * @param waiting 正在等用户点系统确认的那一份（1-based），-1 表示没有。
     */
    data class Progress(
        val done: Int,
        val total: Int,
        val text: String,
        val fraction: Float = if (total > 0) done.toFloat() / total else 0f,
        val items: List<Item> = emptyList(),
        val waiting: Int = -1,
        /** 整批已经跑了多久。「已用 2 分 18 秒」这句话只有平板写，但两条路径都要有数 */
        val elapsedMs: Long = 0L,
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

    /**
     * 正在等系统回执的那一份。
     *
     * 「跳过这一份」必须能在**安装确认框还开着**的时候生效 —— 那正是用户唯一
     * 需要它的时刻（他不想装这一份，又不想把整个队列作废）。所以这里要留一个句柄，
     * 让界面直接把等待断掉，而不是等一个超时。
     */
    private var currentWait: InstallWait? = null

    /** 跳过当前这一份 */
    @Volatile private var skipRequested = false

    /** 取消剩余全部 */
    @Volatile private var cancelRequested = false

    /**
     * 跳过当前这份（仍保留已打好的包与记录，列表里显示「未安装」）。
     *
     * 只对「正在等确认」的那一刻有效：正在打包时没有可跳过的对象，
     * 那时该走 [cancel]。
     */
    fun skipCurrent() {
        if (!isRunning) return
        skipRequested = true
        currentWait?.abandon()
    }

    /**
     * 取消剩余队列。已经在装的那一份不去动它（系统弹窗正开着，
     * 这里能做的最多是放弃等待），已经装好的更不会回滚。
     */
    fun cancel() {
        if (!isRunning) return
        cancelRequested = true
        currentWait?.abandon()
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
        skipRequested = false
        cancelRequested = false
        currentWait = null
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

            // 名字一次解析完，而不是等构建到那一份才取名：队列清单在第一帧就要显示
            // 七行「微信 · 工作」，否则前半段全是空名。
            // 没给名字时逐份递增 offset —— 拿同一个 offset 连调七次会得到七个「分身1」，
            // 因为那时一份都还没入库。
            val names = items.map { (p, seq) ->
                val given = p.names.getOrNull(seq - 1)?.trim()
                if (!given.isNullOrEmpty()) given
                else store.defaultName(p.targetLabel, p.targetPkg, offset = seq)
            }

            // 队列快照。界面拿到的永远是一份新列表（Item 不可变），直接整份替换即可
            val queue = items.mapIndexed { i, _ -> Item(index = i + 1, name = names[i]) }
                .toMutableList()

            // 进度只许往前走：预构建下一份和等待当前这份安装确认是并行发生的，
            // 两个来源交替上报时，如果不过滤就会出现「进度条倒退」这种最糟的反馈
            var shownFraction = 0f
            var lastText = "准备中…"
            var waitingIdx = -1

            fun push() {
                emit(
                    Progress(
                        done = done,
                        total = total,
                        text = lastText,
                        fraction = shownFraction,
                        items = queue.toList(),
                        waiting = waitingIdx,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                    )
                )
            }

            fun emitFraction(fraction: Float, text: String) {
                shownFraction = fraction.coerceIn(shownFraction, 1f)
                lastText = text
                push()
            }

            // 先把整条队列亮出来：七行「排队中」比一个 0% 的空进度条更能说明
            // 「要建 7 个、现在在第一个」
            push()

            suspend fun build(idx: Int, name: String): Built {
                val started = System.currentTimeMillis()
                queue[idx] = queue[idx].copy(state = ItemState.Building, percent = 0)
                push()
                return try {
                    val plan = items[idx].first
                    val rec = wf.prepare(
                        plan.targetPkg,
                        plan.targetLabel,
                        name,
                        control = BuildControl(
                            // 上一份被用户取消时，正在预构建的这一份也要停 —— 它的记录
                            // 是在 build 成功之后才落库的，趁早中断才能连记录都不留
                            isCancelled = { cancelled || cancelRequested },
                            onProgress = { p ->
                                // 构建占总时长的 88%，剩下 12% 是写安装会话
                                val overall = (idx + p.percent / 100f * 0.88f) / total
                                queue[idx] = queue[idx].copy(
                                    state = ItemState.Building,
                                    percent = p.percent,
                                )
                                emitFraction(
                                    overall,
                                    "正在生成 $name（${idx + 1}/$total）· ${p.stage.label} ${p.percent}%"
                                )
                            }
                        )
                    )
                    InstallLog.append(app, "build ok pkg=${rec.clonePkg} ${System.currentTimeMillis() - started}ms")
                    // 打完不等于在装：这一份可能只是「空档预构建」提前做好的，
                    // 系统确认框要等前一份装完才轮到它
                    queue[idx] = queue[idx].copy(
                        pkg = rec.clonePkg,
                        bytes = File(rec.apkPath).let { if (it.exists()) it.length() else 0L },
                        state = ItemState.Ready,
                        percent = 100,
                        elapsedMs = System.currentTimeMillis() - started,
                    )
                    push()
                    Built(name, rec, null)
                } catch (t: Throwable) {
                    val reason = describeThrowable(t)
                    InstallLog.append(app, "build FAIL ${items[idx].first.targetPkg} -> $reason")
                    queue[idx] = queue[idx].copy(
                        state = ItemState.Failed,
                        reason = reason,
                        elapsedMs = System.currentTimeMillis() - started,
                    )
                    push()
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
                if (cancelRequested) {
                    cancelled = true
                    break
                }

                val b: Built = if (pending != null) {
                    pending.await().also {
                        pending = null
                        pendingResult = null
                    }
                } else {
                    val n = names[idx]
                    emitFraction(idx.toFloat() / total, "正在生成 $n（${idx + 1}/$total）")
                    build(idx, n)
                }

                val rec = b.rec
                if (rec == null) {
                    failed += Fail(b.name, b.error ?: "生成失败", null)
                    done++
                    if (cancelRequested) {
                        cancelled = true
                        break
                    }
                    continue
                }
                built += rec

                // 体积要在提交前量：提交完成后本地这份包会被立刻删掉（见 CloneWorkflow.submit）
                val apkBytes = File(rec.apkPath).let { if (it.exists()) it.length() else 0L }
                queue[idx] = queue[idx].copy(state = ItemState.Waiting, percent = 100)
                waitingIdx = idx + 1
                skipRequested = false
                emitFraction((idx + 0.94f) / total, "等待安装确认：${b.name}（${idx + 1}/$total）")

                // 先挂监听再提交：小包从 commit 到回执可能只要几十毫秒，
                // 反过来做会漏掉那次回调，然后傻等几分钟超时（应用其实早装好了）
                val waiter = InstallWait(rec.clonePkg)
                InstallBus.addListener(waiter.listener)
                currentWait = waiter
                var installStarted = 0L
                val outcome: InstallOutcome
                try {
                    // 写会话只占最后一点：它到 100% 的时候，用户还一份都没点「安装」，
                    // 满格会让他以为已经完事了 —— 最后那 2% 留给真正的装好
                    emitFraction((idx + 0.94f) / total, "写入安装会话：${b.name}（${idx + 1}/$total）")
                    installStarted = System.currentTimeMillis()
                    // keepApk：失败的那一份把包留在暂存目录里。装成功再删（见下面 landed 分支），
                    // 用户点「重试这一份」时就不用再花几十秒重打一遍几百 MB
                    val submitted = withContext(Dispatchers.IO) {
                        wf.install(rec, keepApk = true) { f ->
                            emitFraction(
                                (idx + 0.94f + f * 0.02f) / total,
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
                        if (idx + 1 < items.size && isActive && !cancelRequested) {
                            val nextName = names[idx + 1]
                            pending = async { build(idx + 1, nextName).also { pendingResult = it } }
                            emitFraction(
                                (idx + 1f) / total,
                                "等待安装确认 · 同时准备 $nextName（${idx + 2}/$total）"
                            )
                        }
                        waiter.await(InstallWait.timeoutFor(apkBytes))
                    }
                } finally {
                    InstallBus.removeListener(waiter.listener)
                    currentWait = null
                    waitingIdx = -1
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
                        CloneCache.drop(File(rec.apkPath))
                        store.markInstalled(rec.clonePkg)
                        queue[idx] = queue[idx].copy(state = ItemState.Installed, percent = 100)
                        if (outcome.code != 1) {
                            InstallLog.append(
                                app,
                                "recheck pkg=${rec.clonePkg} -> installed（回执：${outcome.reason}）"
                            )
                        }
                    }
                    // 跳过：包已经打好，只是用户不想现在装。记录留着，
                    // 列表里是「未安装」，点它还能补装 —— 和失败不是一回事
                    outcome.code == -2 -> {
                        queue[idx] = queue[idx].copy(state = ItemState.Skipped)
                    }
                    outcome.code == -1 -> {
                        failed += Fail(name, "已取消", rec.clonePkg)
                        cancelled = true
                        // 用户自己点的取消，不会再想要这一份，包直接删掉
                        CloneCache.drop(File(rec.apkPath))
                        queue[idx] = queue[idx].copy(state = ItemState.Cancelled)
                    }
                    outcome.definitive -> {
                        failed += Fail(name, outcome.reason, rec.clonePkg)
                        // 装不上就把这条记录一起撤掉，免得列表里堆一串「未安装」。
                        // 但**包要留着**：日志里那一份可以点「重试这一份」直接重提交，
                        // 不必等几十秒重新打包（wf.forget 会连包一起删，所以这里只删记录）
                        store.remove(rec.clonePkg)
                        queue[idx] = queue[idx].copy(
                            state = ItemState.Failed,
                            reason = outcome.reason,
                        )
                    }
                    else -> {
                        // 超时属于「还没结果」：记录留着，列表里显示未安装，点它可补装
                        failed += Fail(name, outcome.reason, rec.clonePkg)
                        queue[idx] = queue[idx].copy(
                            state = ItemState.Failed,
                            reason = outcome.reason,
                        )
                    }
                }
                // 记一笔给人看的账。原始 InstallLog 里这些信息散在 build/batch/recv 好几行，
                // 拼不出一张卡：应用名、分身名、错误码、两段耗时、留没留包，这里一次理顺
                val kind = when {
                    landed -> LogEvent.Kind.Installed
                    outcome.code == -2 -> LogEvent.Kind.Skipped
                    outcome.code == -1 -> LogEvent.Kind.Cancelled
                    else -> LogEvent.Kind.Failed
                }
                val kept = File(rec.apkPath).exists()
                LogBook.append(
                    app,
                    LogEvent(
                        at = System.currentTimeMillis(),
                        kind = kind,
                        appLabel = rec.targetLabel,
                        cloneName = name,
                        clonePkg = rec.clonePkg,
                        sourcePkg = rec.targetPkg,
                        code = LogBook.codeOf(outcome.reason),
                        reason = if (landed) "" else outcome.reason,
                        bytes = apkBytes,
                        buildMs = queue[idx].elapsedMs,
                        installMs = if (installStarted > 0) {
                            System.currentTimeMillis() - installStarted
                        } else {
                            0L
                        },
                        cachePath = if (kept) rec.apkPath else "",
                        snapshot = RecordSnapshot.of(rec),
                    )
                )
                InstallLog.append(
                    app,
                    "batch pkg=${rec.clonePkg} size=$apkBytes -> ${outcome.reason}"
                )
                done++
                push()
                if (cancelled || cancelRequested) {
                    cancelled = true
                    break
                }
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
                // 没轮到的那些标成「已取消」。留在「排队中」的话，
                // 界面上会一直有一排灰行，看着像还在跑。
                for (i in queue.indices) {
                    if (!queue[i].finished) {
                        queue[i] = queue[i].copy(state = ItemState.Cancelled)
                    }
                }
                waitingIdx = -1
                push()
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
                    // 这几份刚才记成「安装失败」，其实装上了。日志照实改口，
                    // 否则用户会看到一张红卡说失败、列表里那个分身却能正常打开
                    installed.forEach { f ->
                        val pkg = f.pkg ?: return@forEach
                        LogBook.append(
                            app,
                            LogEvent(
                                at = System.currentTimeMillis(),
                                kind = LogEvent.Kind.Installed,
                                appLabel = "",
                                cloneName = f.name,
                                clonePkg = pkg,
                                sourcePkg = "",
                                code = "",
                                reason = "",
                                bytes = 0,
                                buildMs = 0,
                                installMs = 0,
                                cachePath = "",
                                snapshot = store.find(pkg)?.let { RecordSnapshot.of(it) },
                            )
                        )
                    }
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
