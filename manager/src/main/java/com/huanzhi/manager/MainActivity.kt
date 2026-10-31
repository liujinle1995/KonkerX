package com.huanzhi.manager

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.huanzhi.manager.core.AppScanner
import com.huanzhi.manager.core.CacheCleaner
import com.huanzhi.manager.core.CloneRecord
import com.huanzhi.manager.core.CloneRuntime
import com.huanzhi.manager.core.CloneStore
import com.huanzhi.manager.core.CloneVersions
import com.huanzhi.manager.core.CloneWorkflow
import com.huanzhi.manager.core.HardeningDetector
import com.huanzhi.manager.core.InstallBus
import com.huanzhi.manager.core.InstallListener
import com.huanzhi.manager.core.RuntimeProbe
import com.huanzhi.manager.core.SettingsStore
import com.huanzhi.manager.core.TemplateSpec
import com.huanzhi.manager.core.UpdateKind
import com.huanzhi.manager.core.UpdateRunner
import com.huanzhi.manager.ui.ProgressDialog
import com.huanzhi.manager.ui.components.MainTab
import com.huanzhi.manager.ui.motion.Curve
import com.huanzhi.manager.ui.nav.AppScaffold
import com.huanzhi.manager.ui.screen.CloneCardUi
import com.huanzhi.manager.ui.screen.CloneListScreen
import com.huanzhi.manager.ui.screen.CloneListUiState
import com.huanzhi.manager.ui.screen.CloneStatus
import com.huanzhi.manager.ui.theme.HuanZhiTheme
import com.huanzhi.manager.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主页：分身列表。
 *
 * 3.7 起界面层迁到 Compose（Liquid Glass 需要自由绘制与弹簧动画，XML 做不了）。
 * 这个 Activity 现在只做一件事：**把 core 层的数据翻译成 [CloneListUiState]**，
 * 然后把渲染全部交给 [CloneListScreen]。
 *
 * 「对一个分身能做什么」仍然在 [CloneActions]：Compose 只负责"点了"，
 * 不负责"点了之后弹什么" —— 确认文案与业务规则不跟着 UI 框架走。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var workflow: CloneWorkflow
    private lateinit var actions: CloneActions

    /** 渲染状态。Compose 观察它，赋值即重绘 */
    private var uiState by mutableStateOf(CloneListUiState())

    /** 比源应用旧的那些分身；「更新全部」以它为准 */
    private var outdated: List<CloneRecord> = emptyList()

    /** 批量更新进行中时挂着的进度弹窗；转屏重建后要重新挂一个 */
    private var updateDialog: ProgressDialog? = null

    /**
     * 安装回执一到就刷新列表。
     *
     * 不加这个监听时有个很误导人的窗口期：点完系统安装框回到列表，onResume 已经跑过，
     * 而安装回执还要几百毫秒才到 —— 用户正好看到刚建好的分身显示「未安装」，
     * 明明刚才才装完。
     */
    private val installListener: InstallListener = { pkg, _, _ ->
        if (TemplateSpec.isClonePkg(pkg)) {
            runOnUiThread { if (!isFinishing && !isDestroyed) refresh() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内容要画到状态栏与导航栏底下，玻璃导航栏才会真的"浮"在内容上
        enableEdgeToEdge()
        // 冷启动闪屏：只接手「退出」那一段，让图标放大淡出、和内容淡入接上
        installSplashExit()

        workflow = CloneWorkflow(this)
        actions = CloneActions(this, workflow, onChanged = ::refresh)

        val root = ComposeView(this).apply {
            setContent {
                HuanZhiTheme {
                    AppScaffold(
                        selected = MainTab.Clones,
                        onSelect = ::onTabSelected,
                        railTitle = getString(R.string.app_name),
                        railSubtitle = if (uiState.cards.isEmpty()) {
                            getString(R.string.home_summary_zero, uiState.cards.size)
                        } else {
                            getString(
                                R.string.home_summary,
                                uiState.cards.size,
                                Formats.size(uiState.totalOccupied),
                            )
                        },
                        railFooter = getString(R.string.rail_footer, Formats.knownSize(uiState.cacheBytes)),
                    ) {
                        CloneListScreen(
                            state = uiState,
                            onAdd = ::openPicker,
                            onUpdateAll = ::startUpdateAll,
                            onCardClick = { rec -> actions.openOrInstall(rec) },
                        )
                    }
                }
            }
        }
        setContentView(root)

        InstallBus.addListener(installListener)
        checkLegacyClones()
    }

    /**
     * 底部 / 侧栏导航。
     *
     * 三个页面目前还是三个 Activity（迁移中间态）。用 REORDER_TO_FRONT 而不是普通
     * startActivity：三个页面之间来回切时，栈里始终只有各一份，不会出现
     * 「主页 → 设置 → 主页 → 设置」按四次返回键才退出。
     */
    private fun onTabSelected(tab: MainTab) {
        when (tab) {
            MainTab.Clones -> Unit // 已经在主页
            MainTab.Runtime -> startActivity(
                Intent(this, RuntimeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
            MainTab.Settings -> startActivity(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
    }

    /**
     * 首次启动时认一下旧版分身。
     *
     * 3.4 把包名前缀从 konkerx.clone.k 换成了 huanzhi.clone.k，老分身装在同一台机器上
     * 却不在新版的记录里 —— 不解释一句，用户会以为「分身全没了」。
     */
    private fun checkLegacyClones() {
        if (SettingsStore.legacyNoticeShown(this)) return
        lifecycleScope.launch {
            val legacy = withContext(Dispatchers.IO) {
                runCatching {
                    packageManager.getInstalledPackages(0)
                        .map { it.packageName }
                        .filter { TemplateSpec.isLegacyClonePkg(it) }
                }.getOrDefault(emptyList())
            }
            if (isFinishing || isDestroyed) return@launch
            SettingsStore.setLegacyNoticeShown(this@MainActivity)
            if (legacy.isEmpty()) return@launch
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.legacy_clones_title, legacy.size))
                .setMessage(R.string.legacy_clones_msg)
                .setPositiveButton(R.string.done, null)
                .show()
        }
    }

    override fun onDestroy() {
        InstallBus.removeListener(installListener)
        // 任务本身活在 Application 级别（装到一半不能因为转屏就断），
        // 但回调绑的是这个 Activity 的弹窗，必须解绑
        UpdateRunner.onProgress = null
        UpdateRunner.onDone = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        actions.ensureInstallPermission()
        // 转屏 / 从系统安装器回来时进度弹窗已经没了，任务还在跑就重新挂上
        if (UpdateRunner.isRunning && updateDialog == null) {
            val dlg = progressDialog()
            updateDialog = dlg
            dlg.show(
                title = getString(R.string.update_progress_title),
                hint = getString(R.string.update_hint),
                onCancel = { UpdateRunner.cancel() },
            )
            UpdateRunner.lastProgress?.let { p ->
                dlg.update((p.fraction * 100).toInt().coerceIn(0, 100), p.text)
            }
            wireUpdateUi(dlg)
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            // 先跟系统对账：分身被卸载后记录要跟着走（广播是主路径，这里兜底）
            val gone = withContext(Dispatchers.IO) {
                runCatching { CloneStore(this@MainActivity).syncWithSystem() }.getOrDefault(emptyList())
            }
            if (gone.isNotEmpty()) {
                toast(getString(R.string.sync_pruned, gone.size))
            }

            val (state, stale) = withContext(Dispatchers.IO) { buildUiState() }
            uiState = state
            outdated = stale
        }
    }

    /**
     * 把 core 数据翻译成界面状态。**只在 IO 线程调用** —— 里面要读 PackageManager、
     * StorageStatsManager 与 /proc，任何一个都不该在主线程跑。
     *
     * [RuntimeProbe.snapshot] 一次返回体积、运行态与最近启动时间，避免为每一行
     * 分别查三次：那样 20 个分身就是 60 次跨进程调用，滚动前会明显卡一下。
     * 没有「使用情况访问权限」时它返回的 usage 为 null，此时体积退化为安装包大小、
     * 状态退化为「未启动」—— 是降级，不是错误。
     */
    private fun buildUiState(): Pair<CloneListUiState, List<CloneRecord>> {
        val recs = workflow.all()
        val runtimes = runCatching { RuntimeProbe.snapshot(this, recs) }.getOrDefault(emptyList())
        val byPkg = runtimes.associateBy { it.record.clonePkg }

        var total = 0L
        var running = 0
        var updatable = 0
        var foreground = ""
        val stale = mutableListOf<CloneRecord>()

        val cards = recs.map { rec ->
            val rt = byPkg[rec.clonePkg]
            val installed = rt?.installed ?: AppScanner.isInstalled(this, rec.clonePkg)
            val update = CloneVersions.stateOf(this, rec)
            // peek 只读内存缓存：detect 要解压 APK，不能在列表里逐行做
            val hardening = HardeningDetector.peek(rec.clonePkg)
            val occupied = rt?.occupiedBytes ?: 0L

            total += occupied
            if (rt?.running == true) running++
            // 只取第一个在前台的。设计稿那一栏叫「当前前台」、值是个数，
            // 事实上同时在前台的分身只可能有一个，多取无意义
            if (foreground.isEmpty() && rt?.inForeground == true) foreground = rec.label
            if (installed && update.kind == UpdateKind.AVAILABLE) {
                updatable++
                stale += rec
            }

            CloneCardUi(
                rec = rec,
                meta = metaOf(rec, occupied),
                status = statusOf(installed, update.kind, hardening, rt),
                recentLabel = rt?.usage?.lastUsedMs
                    ?.takeIf { it > 0L }
                    ?.let { Formats.relative(it) }
                    .orEmpty(),
            )
        }

        // 缓存体积只在这条 IO 路径上算：CacheCleaner.scan 要遍历目录树，
        // 放到重组里会把掉帧直接送到滚动上
        val cacheBytes = runCatching { CacheCleaner.scan(this).bytes }.getOrDefault(0L)

        return CloneListUiState(
            loading = false,
            cards = cards,
            totalOccupied = total,
            runningCount = running,
            updatableCount = updatable,
            foregroundLabel = foreground,
            cacheBytes = cacheBytes,
        ) to stale
    }

    /**
     * 副标题：「包名 · 体积」。
     *
     * 体积为 0 时不写「 · 0 B」—— 拿不到占用（没授权、或记录是从别处导入的）
     * 和真的占用为零是两件事，报一个 0 会被读成后者。
     */
    private fun metaOf(rec: CloneRecord, occupied: Long): String {
        val pkg = shortPkg(rec.clonePkg)
        return if (occupied > 0L) "$pkg · ${Formats.size(occupied)}" else pkg
    }

    /**
     * 长包名只保留末尾几段。
     *
     * 卡片的副标题位只有约 190dp 宽，13sp 下放得下 22 个字符。真实包名
     * （`com.google.android.apps.maps`）远超这个长度，而默认的尾部省略会把它压成
     * `com.google.android.ap…` —— 一串包名的前半截都是 `com.xxx.`，等于什么都没说。
     *
     * 反过来从右边开始拼段、放不下了再加前缀的省略号，得到 `…android.apps.maps`：
     * 丢掉的 `com.google` 恰好是最没有辨识度的部分。设计稿里的包名是自家生成的
     * `huanzhi.clone.k01`（16 字符），根本不会触发这段逻辑，但真实设备上一定会。
     */
    private fun shortPkg(pkg: String, max: Int = 22): String {
        if (pkg.length <= max) return pkg
        val segs = pkg.split('.')
        var keep = ""
        for (i in segs.size - 1 downTo 0) {
            val cand = segs.subList(i, segs.size).joinToString(".")
            if (cand.length > max - 2 && keep.isNotEmpty()) break
            keep = cand
        }
        return "…$keep"
    }

    /**
     * 状态优先级。
     *
     * 顺序是有讲究的，不是随手排的：
     *   1. 没装 —— 说别的一切都没意义
     *   2. 可更新 —— 用户此刻唯一能做的"改善"动作
     *   3. 源应用没了 —— 需要解释为什么"可更新"不出现
     *   4. 加固风险 —— 解释了为什么点了会失败
     *   5. 正在跑 / 最近用过 / 从没用过 —— 纯信息
     */
    private fun statusOf(
        installed: Boolean,
        kind: UpdateKind,
        hardening: String?,
        rt: CloneRuntime?,
    ): CloneStatus = when {
        !installed -> CloneStatus.NotInstalled
        kind == UpdateKind.AVAILABLE -> CloneStatus.Updatable
        kind == UpdateKind.SOURCE_GONE -> CloneStatus.SourceGone
        hardening != null -> CloneStatus.Risky
        rt?.running == true -> CloneStatus.Running
        (rt?.usage?.lastUsedMs ?: 0L) > 0L -> CloneStatus.Recent
        else -> CloneStatus.NeverLaunched
    }

    /**
     * 一键更新：把「源应用已升级、分身还是旧版」的那些逐个重打包并覆盖安装。
     *
     * 必须在界面上做而不是丢给后台静默跑 —— 每份都会弹一次系统安装确认框，
     * 用户看得见进度、也随时能停下。
     */
    private fun startUpdateAll() {
        if (UpdateRunner.isRunning) return
        val targets = outdated
        if (targets.isEmpty()) return
        val dlg = progressDialog()
        updateDialog = dlg
        dlg.show(
            title = getString(R.string.update_progress_title),
            hint = getString(R.string.update_hint),
            onCancel = { UpdateRunner.cancel() },
        )
        wireUpdateUi(dlg)
        UpdateRunner.start(this, targets)
    }

    /** 进度回调统一在这里接，转屏重建后重新接一次即可 */
    private fun wireUpdateUi(dlg: ProgressDialog) {
        UpdateRunner.onProgress = { p ->
            dlg.update((p.fraction * 100).toInt().coerceIn(0, 100), p.text)
        }
        UpdateRunner.onDone = { s ->
            if (!isFinishing && !isDestroyed) {
                updateDialog = null
                dlg.finish(getString(R.string.update_done_title))
                // 让「100%」这一帧被看到，再把弹窗收走
                window.decorView.postDelayed({ runCatching { dlg.dismiss() } }, 700)
                refresh()
                toast(
                    when {
                        s.cancelled -> getString(R.string.update_stopped, s.ok)
                        s.failed.isEmpty() -> getString(R.string.update_done_msg, s.ok)
                        else -> getString(R.string.update_partial, s.ok, s.failed.size)
                    }
                )
            }
        }
    }

    /**
     * 冷启动闪屏的退出动画。
     *
     * Android 12 起系统强制给每个应用一个闪屏，默认退出是图标直接消失、内容同时出现，
     * 边界很硬。这里接住退出回调，让图标放大 12% 并淡出，与内容淡入接上。
     * 全程 runCatching：热启动、分屏时根本不回调，拿不到就算了。
     */
    private fun installSplashExit() {
        runCatching {
            installSplashScreen().setOnExitAnimationListener { provider ->
                var removed = false
                val remove = Runnable {
                    if (!removed) {
                        removed = true
                        runCatching { provider.remove() }
                    }
                }
                provider.iconView.animate()
                    .alpha(0f)
                    .scaleX(1.12f)
                    .scaleY(1.12f)
                    .setDuration(180L)
                    .setInterpolator(Curve.EMPHASIZED_ACCELERATE)
                    .withEndAction(remove)
                    .start()
                provider.iconView.postDelayed(remove, 400L)
            }
        }
    }

    private fun openPicker() {
        startActivity(Intent(this, AppPickerActivity::class.java))
    }

    private fun progressDialog(): ProgressDialog = ProgressDialog(this)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
