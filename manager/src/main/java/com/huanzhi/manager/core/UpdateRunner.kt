package com.huanzhi.manager.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一键更新：把一批「源应用已经升级、分身还停在旧版」的分身逐个重打包 + 覆盖安装。
 *
 * 和批量创建（[BatchRunner]）的区别只有两点，但两点都关键：
 *   1. 每一份都是**覆盖安装**同一个包名，所以分身里的数据、登录态都保留；
 *   2. 没有预构建 —— 覆盖安装必须等上一份装完才能继续，中间插不进第二份的打包
 *      （同一时间只允许一个系统安装确认框，抢着提交会互相顶掉）。
 *
 * 因此这里就是一条直白的队列：打包 → 提交 → 等回执 → 下一份。
 * 进度按「第几份 + 当前这一份打到几成」折算成整批的百分比。
 */
object UpdateRunner {

    data class Progress(val done: Int, val total: Int, val text: String, val fraction: Float)

    data class Fail(val name: String, val reason: String)

    data class Summary(val ok: Int, val failed: List<Fail>, val cancelled: Boolean)

    /** 两份安装之间留的间隙：上一份的「已安装 · 打开 / 完成」界面还没退场就提交下一份，会叠出两个框 */
    private const val GAP_MS = 1_200L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val cancelFlag = AtomicBoolean(false)

    var onProgress: ((Progress) -> Unit)? = null
    var onDone: ((Summary) -> Unit)? = null

    var lastProgress: Progress? = null
        private set

    val isRunning: Boolean get() = job?.isActive == true

    /** 取消是「尽力而为」：打包能立刻停（每 256KB 查一次标记），等安装回执要等这一步走完 */
    fun cancel() {
        cancelFlag.set(true)
    }

    fun start(ctx: Context, records: List<CloneRecord>) {
        if (isRunning || records.isEmpty()) return
        cancelFlag.set(false)
        lastProgress = null
        val app = ctx.applicationContext
        job = scope.launch {
            val wf = CloneWorkflow(app)
            val store = CloneStore(app)
            var ok = 0
            val failed = mutableListOf<Fail>()
            var cancelled = false
            val total = records.size
            val startedAt = System.currentTimeMillis()

            InstallLog.append(app, "update start n=$total " + records.joinToString(",") { it.clonePkg })

            for ((idx, snapshot) in records.withIndex()) {
                if (!isActive || cancelFlag.get()) {
                    cancelled = true
                    break
                }
                // 记录可能在列表展示之后被删/被重建过，以库里最新的为准
                val rec = store.find(snapshot.clonePkg) ?: continue

                emit(Progress(idx, total, "正在重新打包 ${rec.label}（${idx + 1}/$total）", idx.toFloat() / total))

                val staged = try {
                    wf.stageRebuild(
                        rec,
                        control = BuildControl(
                            onProgress = { p ->
                                // 构建占整批的 0–88%，剩下的留给写安装会话
                                val within = idx + p.percent / 100f * 0.88f
                                val detail = if (p.detail.isNotEmpty()) " · ${p.detail}" else ""
                                emit(
                                    Progress(
                                        idx,
                                        total,
                                        "${p.stage.label} ${rec.label}（${idx + 1}/$total）$detail",
                                        within / total,
                                    )
                                )
                            },
                            isCancelled = { cancelFlag.get() },
                        )
                    )
                } catch (e: BuildCancelledException) {
                    cancelled = true
                    break
                } catch (t: Throwable) {
                    failed += Fail(rec.label, t.message ?: t.javaClass.simpleName)
                    InstallLog.append(app, "update build FAIL ${rec.clonePkg} -> ${t.message}")
                    continue
                }

                val apkBytes = runCatching { File(staged.apk.absolutePath).length() }.getOrDefault(0L)
                val waiter = InstallWait(rec.clonePkg)
                InstallBus.addListener(waiter.listener)
                val outcome = try {
                    emit(
                        Progress(
                            idx,
                            total,
                            "正在写入安装会话 ${rec.label}（${idx + 1}/$total）",
                            (idx + 0.9f) / total,
                        )
                    )
                    val submitted = withContext(Dispatchers.IO) {
                        wf.submitStaged(staged) { f ->
                            emit(
                                Progress(
                                    idx,
                                    total,
                                    "正在写入安装会话 ${rec.label}（${idx + 1}/$total）· ${(f * 100).toInt()}%",
                                    (idx + 0.9f + f * 0.1f) / total,
                                )
                            )
                        }
                    }
                    if (!submitted) {
                        InstallOutcome(0, "无法提交安装（系统拒绝创建安装会话）")
                    } else {
                        emit(
                            Progress(
                                idx,
                                total,
                                "等待系统确认：${rec.label}（${idx + 1}/$total）",
                                (idx + 1f) / total,
                            )
                        )
                        waiter.await(InstallWait.timeoutFor(apkBytes))
                    }
                } finally {
                    InstallBus.removeListener(waiter.listener)
                }

                // 回执只能当参考：部分 ROM 在「已完成」屏点「完成」回的是 ABORTED，
                // 但包其实已经装进去了。以系统安装列表为准。
                val landed = outcome.code == 1 || AppScanner.isInstalled(app, rec.clonePkg)
                when {
                    landed -> {
                        ok++
                        store.markInstalled(rec.clonePkg)
                        // 版本号落库 = 角标消失，必须等真的装上
                        wf.commitStaged(staged)
                        if (outcome.code != 1) {
                            InstallLog.append(
                                app,
                                "update recheck ${rec.clonePkg} -> installed（回执：${outcome.reason}）"
                            )
                        }
                    }
                    outcome.code == -1 -> {
                        failed += Fail(rec.label, "已取消")
                        cancelled = true
                    }
                    else -> failed += Fail(rec.label, outcome.reason)
                }
                InstallLog.append(app, "update ${rec.clonePkg} -> ${outcome.reason}")
                // 更新也是一次安装，失败了一样要留痕 —— 否则「我把分身更新到最新版了」
                // 之后它没变，用户找不到任何证据说明到底试没试过
                val kind = when {
                    landed -> LogEvent.Kind.Installed
                    outcome.code == -1 -> LogEvent.Kind.Cancelled
                    else -> LogEvent.Kind.Failed
                }
                LogBook.append(
                    app,
                    LogEvent(
                        at = System.currentTimeMillis(),
                        kind = kind,
                        appLabel = rec.targetLabel,
                        cloneName = rec.label,
                        clonePkg = rec.clonePkg,
                        sourcePkg = rec.targetPkg,
                        code = LogBook.codeOf(outcome.reason),
                        reason = if (landed) "" else outcome.reason,
                        bytes = apkBytes,
                        buildMs = 0,
                        installMs = 0,
                        cachePath = "",
                        snapshot = RecordSnapshot.of(rec),
                    )
                )
                emit(
                    Progress(
                        idx + 1,
                        total,
                        "已完成 ${idx + 1}/$total",
                        (idx + 1f) / total,
                    )
                )
                if (cancelled) break
                delay(GAP_MS)
            }

            val elapsed = (System.currentTimeMillis() - startedAt) / 1000
            InstallLog.append(app, "update done ok=$ok fail=${failed.size} ${elapsed}s")
            onDone?.invoke(Summary(ok, failed, cancelled))
        }
    }

    private fun emit(p: Progress) {
        lastProgress = p
        onProgress?.invoke(p)
    }
}
