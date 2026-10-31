package com.huanzhi.manager

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.huanzhi.manager.core.BatchRunner
import com.huanzhi.manager.core.InstallLog
import com.huanzhi.manager.util.Formats
import com.huanzhi.manager.ui.adaptive.WindowSizeClasses
import com.huanzhi.manager.ui.screen.BuildItemUi
import com.huanzhi.manager.ui.screen.BuildPhase
import com.huanzhi.manager.ui.screen.BuildRowState
import com.huanzhi.manager.ui.screen.BuildScreen
import com.huanzhi.manager.ui.screen.BuildUiState
import com.huanzhi.manager.ui.theme.HuanZhiTheme

/**
 * 构建进度页（整页，不是弹窗）。
 *
 * 这一页只管展示与三个动作（跳过 / 取消剩余 / 完成），真正干活的是 [BatchRunner]：
 * 它跑在 Application 级的协程里，所以转屏、切深浅色、甚至退出去再进来，
 * 构建都不会中断，重新挂上回调就能接着显示。
 *
 * 这也是为什么整页比弹窗合适：弹窗一旦被系统回收就没了，用户回来看到的是一片空白；
 * 整页可以随时重建，因为「进度」本身不在界面里，而在调度器里。
 */
class BuildActivity : ComponentActivity() {

    /**
     * 一次构建请求。
     *
     * 用静态字段传递而不是 Intent extras：[BatchRunner.Plan] 是嵌套列表，
     * 序列化进 Intent 要写 Parcelable，收益为零 —— 这个页面和它的数据是同一件事，
     * 不会有人从别处「只打开界面不带参数」。
     */
    data class Request(
        val plans: List<BatchRunner.Plan>,
        /** 这批分身是从哪个应用来的，用在平板的参数卡上 */
        val sourcePkg: String,
        val sourceLabel: String,
        val copyCount: Int,
        val estimatedBytes: Long,
        val freeBytes: Long,
        /** 逐份独立命名 / 统一前缀，参数卡上要写清楚 */
        val nameMode: String,
    )

    private var ui by mutableStateOf(BuildUiState())
    private var wide = false
    private var request: Request? = null
    private var finishedShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 宽度够、高度不够的时候（横屏手机 891×411dp）不能摆双栏：
        // 左列三块卡叠起来放不进一屏，提醒卡会沉到玻璃条底下
        wide = WindowSizeClasses.canShowTwoPane(this)
        request = pending

        setContentView(
            ComposeView(this).apply {
                setContent {
                    HuanZhiTheme {
                        BuildScreen(
                            state = ui,
                            onBack = ::leave,
                            onCancelAll = { BatchRunner.cancel() },
                            onSkip = { BatchRunner.skipCurrent() },
                            onFinish = { finish() },
                            onCopy = ::copyDiagnostics,
                        )
                    }
                }
            }
        )

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = leave()
            },
        )

        val req = request
        if (req == null) {
            // 没有请求也没有在跑的批次：进程被回收后重建，没东西可看
            finish()
            return
        }
        ui = buildState(req, BatchRunner.lastProgress, phase = BuildPhase.Running, summary = null)
        if (!BatchRunner.isRunning) {
            BatchRunner.start(this, req.plans)
        }
    }

    override fun onStart() {
        super.onStart()
        // 每次回到前台都重新挂：之前在别的页面时回调是空的
        BatchRunner.onProgress = { push(BuildPhase.Running, summary = null) }
        BatchRunner.onDone = { s ->
            val failed = s.failed.isNotEmpty()
            val phase = when {
                s.cancelled -> BuildPhase.Stopped
                failed -> BuildPhase.Failed
                else -> BuildPhase.Done
            }
            finishedShown = true
            push(phase, s)
        }
        if (BatchRunner.isRunning) push(BuildPhase.Running, summary = null)
        // 批次已经结束、界面却刚起来（比如从后台回来）：直接显示结果
        if (!BatchRunner.isRunning && !finishedShown) {
            BatchRunner.lastSummary?.let { s ->
                finishedShown = true
                push(
                    if (s.cancelled) BuildPhase.Stopped
                    else if (s.failed.isNotEmpty()) BuildPhase.Failed
                    else BuildPhase.Done,
                    s,
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        BatchRunner.onProgress = null
        BatchRunner.onDone = null
    }

    private fun push(phase: BuildPhase, summary: BatchRunner.Summary?) {
        val p = BatchRunner.lastProgress ?: return
        val req = request ?: return
        ui = buildState(req, p, phase, summary)
    }

    /**
     * 离开页面。
     *
     * 批次在 Application 级协程里跑，所以离开只是「不看它了」，不是「不做了」。
     * 但用户按下去的理由多半是以为这样能停下 —— 必须说一句，否则他会以为
     * 任务被取消了，然后在列表里发现多了几个分身而莫名其妙。
     */
    private fun leave() {
        if (BatchRunner.isRunning) {
            toast("创建在后台继续，完成后列表会自动刷新")
        }
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ 状态拼装

    private fun buildState(
        req: Request,
        p: BatchRunner.Progress?,
        phase: BuildPhase,
        summary: BatchRunner.Summary?,
    ): BuildUiState {
        val items = p?.items.orEmpty()
        val total = p?.total ?: req.copyCount
        val installed = items.count { it.state == BatchRunner.ItemState.Installed }
        val skipped = items.count { it.state == BatchRunner.ItemState.Skipped }
        val failed = items.count {
            it.state == BatchRunner.ItemState.Failed || it.state == BatchRunner.ItemState.Cancelled
        }
        val remaining = items.count { !it.finished }
        val percent = ((p?.fraction ?: 0f) * 100f).toInt().coerceIn(0, 100)
        val waiting = p?.waiting ?: -1
        val rowItems = items.map { it.toUi(req, phase) }

        val queueNote = when {
            phase != BuildPhase.Running -> ""
            wide -> getString(R.string.bp_queue_note_wide)
            remaining > 0 -> getString(R.string.bp_queue_note, remaining)
            else -> ""
        }

        return BuildUiState(
            wide = wide,
            phase = phase,
            heroTitle = heroTitle(req),
            heroPercent = if (phase == BuildPhase.Running) percent else 100,
            heroSub = heroSub(installed, remaining, skipped, failed, p?.elapsedMs ?: 0L, phase),
            // 跑完以后「一份装完才构建下一份」这类说明讲的就是过去的事了，
            // 停在完成页上只会白占位置
            heroNote = if (phase == BuildPhase.Running) {
                getString(if (wide) R.string.bp_hero_note_wide else R.string.bp_hero_note)
            } else {
                ""
            },
            waitingIndex = waiting,
            actionHead = if (waiting > 0) getString(R.string.bp_act_head, waiting, total) else "",
            actionBody = getString(if (wide) R.string.bp_act_body_wide else R.string.bp_act_body),
            waitingName = waitingName(items, waiting),
            queueTitle = if (wide) {
                getString(R.string.bp_queue_title, total)
            } else {
                getString(R.string.bp_queue_title_plain)
            },
            queueNote = queueNote,
            items = rowItems,
            detail = if (wide) detailRows(req, items) else emptyList(),
            footerTitle = footerTitle(installed),
            footerNote = getString(
                if (phase == BuildPhase.Stopped) R.string.bp_footer_note_stop
                else R.string.bp_footer_note
            ),
            pillLabel = if (phase == BuildPhase.Running) {
                getString(R.string.bp_cancel_rest, remaining.coerceAtLeast(0))
            } else {
                getString(R.string.done)
            },
            // 底注只服务两件事：跑着时的「还要多久」、失败后的「怎么查」。
            // 顺利装完就没什么可说的，留一句「可复制诊断」反而像出了事
            footnote = if (wide && (phase == BuildPhase.Running || phase == BuildPhase.Failed)) {
                footnote(req, p, remaining)
            } else {
                ""
            },
            doneTitle = doneTitle(phase, summary, installed),
            doneBody = if (phase == BuildPhase.Failed) failedBody(summary)
            else getString(R.string.bp_done_body),
        )
    }

    /**
     * 抬头：「微信 7 个分身」。
     *
     * 单应用走名字，多应用走数量 —— 「微信、淘宝、支付宝 7 个分身」这种句子读起来
     * 比「3 个应用 · 7 个分身」累，而这一行在页面最醒目的位置。
     */
    private fun heroTitle(req: Request): String {
        val apps = req.plans.map { it.targetLabel }.distinct()
        return if (apps.size == 1) {
            "${apps.first()} ${req.copyCount} 个分身"
        } else {
            "${apps.size} 个应用 · ${req.copyCount} 个分身"
        }
    }

    private fun heroSub(
        installed: Int,
        remaining: Int,
        skipped: Int,
        failed: Int,
        elapsedMs: Long,
        phase: BuildPhase,
    ): String = when {
        phase != BuildPhase.Running && remaining == 0 -> {
            // 收尾这句是全页唯一会撒谎的地方。跳过的那份没装上，它既不算失败，
            // 也不能塞进「全部完成」—— 只有真的一份不差，才有资格说这四个字
            if (failed == 0 && skipped == 0) {
                getString(R.string.bp_hero_sub_done, installed)
            } else {
                getString(R.string.bp_hero_sub_base, installed) +
                    (if (failed > 0) getString(R.string.bp_hero_sub_failed_part, failed) else "") +
                    (if (skipped > 0) getString(R.string.bp_hero_sub_skipped_part, skipped) else "")
            }
        }
        wide -> getString(
            R.string.bp_hero_sub_wide, installed, remaining, duration(elapsedMs),
        )
        else -> getString(R.string.bp_hero_sub, installed, remaining)
    }

    private fun waitingName(items: List<BatchRunner.Item>, waiting: Int): String {
        val item = items.firstOrNull { it.index == waiting } ?: return ""
        // 平板上包名是这一行的「身份证」：七份名字可能长得很像（微信 · 工作 / 微信 · 生活），
        // 包名才是唯一确定的那个
        return if (wide && item.pkg.isNotEmpty()) "${item.name} · ${item.pkg}" else item.name
    }

    private fun footerTitle(installed: Int): String =
        if (installed > 0) getString(R.string.bp_footer_keep, installed)
        else getString(R.string.bp_footer_empty)

    private fun doneTitle(
        phase: BuildPhase,
        summary: BatchRunner.Summary?,
        installed: Int,
    ): String = when (phase) {
        BuildPhase.Done -> getString(R.string.bp_done_title, installed)
        BuildPhase.Failed -> getString(R.string.bp_failed_title, summary?.failed?.size ?: 0)
        BuildPhase.Stopped -> getString(R.string.bp_done_title_stop, installed)
        BuildPhase.Running -> ""
    }

    private fun detailRows(req: Request, items: List<BatchRunner.Item>): List<Pair<String, String>> {
        val pkgs = items.mapNotNull { it.pkg.takeIf(String::isNotEmpty) }
        val pkgRange = when {
            pkgs.isEmpty() -> getString(R.string.bp_pkg_pending)
            pkgs.size >= items.size -> "${pkgs.first()} – ${pkgs.last().substringAfterLast('.')}"
            else -> "${pkgs.first()} – …"
        }
        val version = runCatching {
            packageManager.getPackageInfo(req.sourcePkg, 0).versionName
        }.getOrNull()
        val source = buildString {
            append(req.sourceLabel)
            if (!version.isNullOrBlank()) append(' ').append(version)
        }
        return listOf(
            getString(R.string.bp_detail_source) to source,
            getString(R.string.bp_detail_copies) to "${req.copyCount} 份 · ${req.nameMode}",
            getString(R.string.bp_detail_space) to
                "约 ${Formats.size(req.estimatedBytes)} · 剩余可用 ${Formats.size(req.freeBytes)}",
            getString(R.string.bp_detail_pkgs) to pkgRange,
        )
    }

    /**
     * 剩余时间的估算只有一句话的价值，却能把「还要不要继续等」这件事讲明白。
     * 用已完成的平均耗时外推 —— 不精确，但比一个不动的百分比有用得多。
     */
    private fun footnote(
        req: Request,
        p: BatchRunner.Progress?,
        remaining: Int,
    ): String {
        if (p == null || remaining <= 0) {
            return getString(R.string.bp_footnote_idle)
        }
        val doneCount = p.items.count { it.finished && it.state == BatchRunner.ItemState.Installed }
        // 一份都还没装完时没法外推 —— 拿 0 去除会得到一个「剩余约 1 秒」的蠢数字
        if (doneCount <= 0) return getString(R.string.bp_footnote_idle)
        return getString(R.string.bp_footnote, duration(p.elapsedMs / doneCount * remaining))
    }

    /**
     * 失败卡的那段正文。
     *
     * 优先说「接下来怎么办」而不是复述「失败了」：用户已经知道失败了，
     * 他要的是下一步。多份同一个原因时说一次即可，逐条重念五遍只是噪音。
     */
    private fun failedBody(summary: BatchRunner.Summary?): String {
        val reason = summary?.commonReason ?: summary?.failed?.singleOrNull()?.reason
        return when {
            reason == null -> getString(R.string.bp_failed_body)
            else -> hintFor(reason) ?: reason
        }
    }

    /** 常见原因给一句「接下来怎么办」 */
    private fun hintFor(reason: String?): String? = when {
        reason == null -> null
        reason.contains("权限与原应用重名") ->
            "分身与原应用声明了同名权限，系统不允许重复声明。更新后重新创建即可。"
        reason.contains("存储空间不足") ->
            "先用设置里的「清除缓存」，或卸载不用的分身，再减少每批份数。"
        reason.contains("签名与已装版本冲突") ->
            "这个包名装过另一份签名不同的版本，先卸载它再装。"
        reason.contains("没收到安装结果") ->
            "系统确认框要手动点「安装」。没等到结果的那几份可能稍后才装上，回列表看状态即可；" +
                "显示「未安装」的点一下会重新生成并安装。"
        reason.contains("无法提交安装") ->
            "检查是否允许了「安装未知应用」，以及存储是否可写。"
        reason.contains("冲突") ->
            "同一包名的旧分身还装在系统里（记录可能已删）。先卸载它，或让工具重新分配序号后再试。"
        reason.contains("分片") -> "分片安装的应用暂不支持，需要换成完整 APK 再试。"
        else -> null
    }

    /**
     * 原因不确定时，让用户能把原始状态一次性复制出来。
     * 光看「安装失败」谁也判断不了，日志里才有 INSTALL_FAILED_* 这类真凭实据。
     */
    /**
     * 原因不确定时，让用户能把原始状态一次性复制出来。
     * 光看「安装失败」谁也判断不了，日志里才有 INSTALL_FAILED_* 这类真凭实据。
     */
    private fun copyDiagnostics() {
        val s = BatchRunner.lastSummary
        val text = buildString {
            append("幻之分身 ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")")
            append(" · Android ").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")")
            append(" · ").append(android.os.Build.MANUFACTURER)
                .append(' ').append(android.os.Build.MODEL)
            append('\n')
            if (s != null && s.failed.isNotEmpty()) {
                append("失败项：\n")
                s.failed.forEach {
                    append("· ").append(it.name).append("：").append(it.reason).append('\n')
                }
            }
            append("\n安装日志：\n").append(InstallLog.tail(this@BuildActivity, 40))
        }
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("幻之分身诊断", text))
        toast("已复制到剪贴板")
    }

    private fun duration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        if (totalSec < 60) return "${totalSec.coerceAtLeast(1)} 秒"
        val m = totalSec / 60
        val s = totalSec % 60
        return if (s == 0L) "$m 分" else "$m 分 $s 秒"
    }

    private fun BatchRunner.Item.toUi(req: Request, phase: BuildPhase): BuildItemUi {
        val state = when (this.state) {
            BatchRunner.ItemState.Queued -> BuildRowState.Queued
            BatchRunner.ItemState.Building -> BuildRowState.Building
            BatchRunner.ItemState.Ready -> BuildRowState.Ready
            BatchRunner.ItemState.Waiting -> BuildRowState.Waiting
            BatchRunner.ItemState.Installed -> BuildRowState.Installed
            BatchRunner.ItemState.Failed -> BuildRowState.Failed
            BatchRunner.ItemState.Skipped -> BuildRowState.Skipped
            BatchRunner.ItemState.Cancelled -> BuildRowState.Cancelled
        }
        val meta = when {
            // 失败行的那一行小字换成原因：包名在这里没有用，用户要的是「为什么」
            // 括号里的 INSTALL_FAILED_* 是给日志看的，行里放不下也读不懂
            this.reason != null -> this.reason.substringBefore('（')
            pkg.isEmpty() -> getString(R.string.bp_meta_queued)
            bytes > 0L -> "$pkg · ${Formats.size(bytes)}"
            else -> pkg
        }
        return BuildItemUi(
            index = index,
            name = name,
            meta = meta,
            state = state,
            percent = percent,
            elapsed = if (wide && elapsedMs > 0) duration(elapsedMs) else null,
        )
    }

    companion object {
        private var pending: Request? = null

        fun start(ctx: Context, request: Request) {
            pending = request
            ctx.startActivity(Intent(ctx, BuildActivity::class.java))
        }
    }
}
