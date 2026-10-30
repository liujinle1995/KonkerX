package com.huanzhi.manager

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.huanzhi.manager.core.AppScanner
import com.huanzhi.manager.core.CloneRecord
import com.huanzhi.manager.core.CloneStore
import com.huanzhi.manager.core.CloneVersions
import com.huanzhi.manager.core.CloneWorkflow
import com.huanzhi.manager.core.InstallBus
import com.huanzhi.manager.core.InstallListener
import com.huanzhi.manager.core.SettingsStore
import com.huanzhi.manager.core.TemplateSpec
import com.huanzhi.manager.core.UpdateKind
import com.huanzhi.manager.core.UpdateRunner
import com.huanzhi.manager.databinding.ActivityMainBinding
import com.huanzhi.manager.databinding.ViewCloneDetailBinding
import com.huanzhi.manager.ui.ProgressDialog
import com.huanzhi.manager.ui.adaptive.WindowSizeClass
import com.huanzhi.manager.ui.adaptive.WindowSizeClasses
import com.huanzhi.manager.ui.motion.Curve
import com.huanzhi.manager.ui.motion.cascade
import com.huanzhi.manager.ui.motion.crossFade
import com.huanzhi.manager.ui.motion.hideScaled
import com.huanzhi.manager.ui.motion.showScaled
import com.huanzhi.manager.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主页：分身列表。
 *
 * 现在这个 Activity 只管三件事 —— 列表的显隐编排、工具栏、批量更新。
 * 「对一个分身能做什么」（改名 / 重建 / 卸载 / 删记录）全部搬到了 [CloneActions]：
 * 平板双栏的右侧详情面板要用同一批动作，留在 Activity 里就得再抄一遍，
 * 而两份确认文案很快会长得不一样。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var workflow: CloneWorkflow
    private lateinit var adapter: CloneAdapter
    private lateinit var actions: CloneActions

    /**
     * 当前窗口的尺寸类，决定列表列数与是否双栏。
     *
     * Configuration 变化（转屏、折叠、拖进分屏）会走 Activity 重建，
     * 重建后 onCreate 重新取值就是新的；不要在别处缓存真实宽度。
     */
    private lateinit var sizeClass: WindowSizeClass

    /** 双栏下右侧面板的绑定。单栏时为 null，这样「忘记判 greet 空」会立刻炸而不是显示错数据 */
    private var detailBinding: ViewCloneDetailBinding? = null

    /**
     * 最近一次渲染出来的行数据。
     *
     * 存在这里不是为了重复 adapter 内部状态，而是右侧面板需要一个稳定的数据源：
     * 选中行被卸载后，[CloneRow] 必须换成新算出来的那份（安装状态已经变了），
     * 拿旧对象继续画就会显示过期的「已安装」。
     */
    private var rows: List<CloneRow> = emptyList()

    /** 双栏模式下详情面板当前展示的行的包名；null = 占位态 */
    private var selectedPkg: String? = null

    /** 当前比源应用旧的那些分身；菜单项和「更新全部」都以它为准 */
    private var outdated: List<CloneRecord> = emptyList()

    /** 批量更新进行中时挂着的进度弹窗；转屏重建后要重新挂一个 */
    private var updateDialog: ProgressDialog? = null

    /** 首帧的显隐由窗口进入转场负责，这里不再叠一层动画 */
    private var firstRender = true

    /** 列表是否已经级联播放过 —— 只在「从空到有」时放一次 */
    private var cascaded = false

    /**
     * 安装回执一到就刷新列表。
     *
     * 不加这个监听时有个很误导人的窗口期：点完系统安装框回到列表，onResume 已经跑过，
     * 而安装回执还要几百毫秒才到 —— 用户正好看到刚建好的分身显示「未安装」，
     * 明明刚才才装完。装上（或卸掉）之后立刻重读一次状态，这一帧就不存在了。
     */
    private val installListener: InstallListener = { pkg, _, _ ->
        if (TemplateSpec.isClonePkg(pkg)) {
            runOnUiThread { if (!isFinishing && !isDestroyed) refresh() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 冷启动闪屏：只接手「退出」那一段，让图标放大淡出、和内容淡入接上
        installSplashExit()

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        sizeClass = WindowSizeClasses.of(this)
        applyLayoutForSizeClass()

        workflow = CloneWorkflow(this)
        actions = CloneActions(this, workflow, onChanged = ::refresh)
        adapter = CloneAdapter(
            onClick = ::onCloneClick,
            onMore = { rec, _ -> actions.showMenu(rec) },
        )
        b.list.layoutManager = GridLayoutManager(this, sizeClass.listColumns)
        b.list.adapter = adapter
        b.fab.setOnClickListener { openPicker() }
        // 空态里也放一个入口：只写「还没有分身」是反模式，得让用户当场能动手
        b.emptyAction.setOnClickListener { openPicker() }

        b.toolbar.inflateMenu(R.menu.menu_main)
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_runtime -> {
                    startActivity(Intent(this, RuntimeActivity::class.java))
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_update_all -> {
                    startUpdateAll()
                    true
                }
                else -> false
            }
        }

        InstallBus.addListener(installListener)
        checkLegacyClones()
    }

    /**
     * 按尺寸类切换布局形态。
     *
     * 详情页在资源里默认为 GONE，只在 EXPANDED 下挂出来并取到绑定对象。
     * 反向也会走：平板把窗口拖窄成 COMPACT 后 Activity 重建，这里同样要把它藏回去 ——
     * 否则右侧面板会占着屏幕的固定一块，列表被挤成中间一小条。
     */
    private fun applyLayoutForSizeClass() {
        if (!sizeClass.listDetail) {
            b.detail.root.visibility = View.GONE
            detailBinding = null
            selectedPkg = null
            return
        }
        b.detail.root.visibility = View.VISIBLE
        val d = ViewCloneDetailBinding.bind(b.detail.root)
        detailBinding = d
        wireDetailActions(d)
        renderDetail()
    }

    /**
     * 详情面板三个动作的接线。
     *
     * 放在尺寸类判定时而不是每次选中时：选中会频繁切换，重复 setOnClickListener
     * 虽然不会真的泄漏（它是赋值不是叠加），但把「读数」和「接线」混在一起会让
     * renderDetail 变得有副作用，改起来容易出岔子。
     */
    private fun wireDetailActions(d: ViewCloneDetailBinding) {
        d.actionLaunch.setOnClickListener { currentRow()?.let { actions.openOrInstall(it.rec) } }
        d.actionMore.setOnClickListener { currentRow()?.let { actions.showMenu(it.rec) } }
        d.actionUpdate.setOnClickListener { currentRow()?.let { actions.rebuild(it.rec) } }
    }

    /**
     * 首次启动时认一下旧版分身。
     *
     * 3.4 把包名前缀从 konkerx.clone.k 换成了 huanzhi.clone.k，老分身装在同一台机器上
     * 却不在新版的记录里 —— 不解释一句，用户会以为「分身全没了」。
     * 实际上它们各自是独立应用，数据、登录态都在，只是归不到新版管。
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
        // 但回调绑的是这个 Activity 的弹窗，必须解绑，否则重建后往一个已经 finish 的界面上写进度
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
            // 版本比对要查 PackageManager，和读记录一起放到 IO。
            // 结果同时用于三件事：行里的角标、菜单里的「更新全部（N）」、右侧详情的「更新」按钮 ——
            // 三处各算一遍的话，早晚会出现「菜单说有 2 个、列表里只标了 1 个」。
            val (newRows, newOutdated) = withContext(Dispatchers.IO) {
                val recs = workflow.all()
                val mapped = recs.map { rec ->
                    CloneRow(
                        rec = rec,
                        installed = AppScanner.isInstalled(this@MainActivity, rec.clonePkg),
                        update = CloneVersions.stateOf(this@MainActivity, rec),
                    )
                }
                mapped to CloneVersions.outdated(this@MainActivity, recs)
            }
            outdated = newOutdated
            rows = newRows
            adapter.submit(newRows)
            render(newRows.isEmpty())
            syncUpdateMenu(newOutdated.size)
            // 详情面板里的版本 / 安装状态可能已经变了，必须跟着重画
            syncDetailAfterRefresh()
        }
    }

    /**
     * 刷新后维持右侧面板与列表的一致性。
     *
     * 选中行还在就用新的 [CloneRow] 重画；已经被卸载同步掉了就回到占位态 ——
     * 挂着一个不存在的应用的详情，比右侧空着更让人困惑。
     */
    private fun syncDetailAfterRefresh() {
        if (detailBinding == null) return
        val pkg = selectedPkg
        if (pkg != null && rows.none { it.rec.clonePkg == pkg }) {
            selectedPkg = null
            adapter.setSelected(null)
        }
        renderDetail()
    }

    /** 菜单项跟着刷新走：没有可更新的时候整项隐藏，别留一个点了没反应的入口 */
    private fun syncUpdateMenu(count: Int) {
        val item = b.toolbar.menu.findItem(R.id.action_update_all) ?: return
        item.isVisible = count > 0
        if (count > 0) item.title = getString(R.string.action_update_all_n, count)
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

    /** 进度回调统一在这里接，转屏重建后重新接一次即可，不需要让任务的存活跟 Activity 绑定 */
    private fun wireUpdateUi(dlg: ProgressDialog) {
        UpdateRunner.onProgress = { p ->
            dlg.update((p.fraction * 100).toInt().coerceIn(0, 100), p.text)
        }
        UpdateRunner.onDone = { s ->
            if (!isFinishing && !isDestroyed) {
                updateDialog = null
                dlg.finish(getString(R.string.update_done_title))
                // 让「100%」这一帧被看到，再把弹窗收走
                b.root.postDelayed({ runCatching { dlg.dismiss() } }, 700)
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
     * 空态 / 列表 / FAB 三者的显隐编排（MOTION.md #7 #8 #10）。
     *
     * 原来这里是三行 `visibility = ...`，观感是空态「啪」地消失、列表突然出现。
     * 现在：列表与空态交叉淡入、FAB 比列表晚 80ms 出现（先看见内容，再看见动作），
     * 首帧除外 —— 首帧的进入感已经由窗口转场（nav_enter_forward）表达，
     * 再叠一层就是两个动画抢同一个 300ms。
     */
    private fun render(empty: Boolean) {
        val first = firstRender
        firstRender = false

        if (first) {
            b.empty.visibility = if (empty) View.VISIBLE else View.GONE
            b.empty.alpha = 1f
            b.empty.scaleX = 1f
            b.empty.scaleY = 1f
            b.list.visibility = if (empty) View.GONE else View.VISIBLE
            b.list.alpha = 1f
            b.fab.visibility = if (empty) View.GONE else View.VISIBLE
            b.fab.alpha = 1f
            b.fab.scaleX = 1f
            b.fab.scaleY = 1f
        } else if (empty) {
            crossFade(b.list, b.empty)
            b.fab.hideScaled()
        } else {
            crossFade(b.empty, b.list)
            b.fab.showScaled(delay = 80L)
        }

        // 从「一个都没有」到「有」：级联一次；回到空态后重置，下次再级联。
        // 160ms 的起始延迟 = 等交叉淡入把列表露出来，否则父容器还在淡的时候
        // 子项自己在淡，画面会变成两层透明度相乘，灰得发闷。
        if (empty) {
            cascaded = false
        } else if (!cascaded) {
            cascaded = true
            if (!first) b.list.post { b.list.cascade(startDelay = 160L) }
        }
    }

    // ------------------------------------------------------------ 详情面板

    /**
     * 点击一行。
     *
     * 单栏：直接打开（没装就问要不要装）—— 手机上的主路径，多点一次就变累赘。
     * 双栏：**只选中**。列表和详情同屏可见时，点一下就把应用推到前台是违反预期的，
     * 用户在这里的意图是「看看这个的信息」，真要启动有面板上那个明确的「启动」按钮。
     */
    private fun onCloneClick(rec: CloneRecord) {
        if (sizeClass.listDetail) {
            selectedPkg = rec.clonePkg
            adapter.setSelected(rec.clonePkg)
            renderDetail()
        } else {
            actions.openOrInstall(rec)
        }
    }

    /** 详情面板此刻应该展示的行；没选中或行已消失时返回 null */
    private fun currentRow(): CloneRow? {
        val pkg = selectedPkg ?: return null
        return rows.firstOrNull { it.rec.clonePkg == pkg }
    }

    /** 把当前选中的行渲染进右侧面板；没有选中时回落占位态 */
    private fun renderDetail() {
        val d = detailBinding ?: return
        val row = currentRow()
        if (row == null) {
            d.content.visibility = View.GONE
            d.placeholder.visibility = View.VISIBLE
            return
        }
        d.placeholder.visibility = View.GONE
        d.content.visibility = View.VISIBLE

        val rec = row.rec
        d.label.text = rec.label
        d.targetLabel.text = getString(R.string.detail_target, rec.targetLabel)
        d.icon.setImageDrawable(AppScanner.iconOf(this, rec.targetPkg))

        // 状态 chip 带文字：颜色不能是唯一的信息载体
        d.statusText.text = getString(
            if (row.installed) R.string.detail_status_installed else R.string.detail_status_missing
        )
        d.pkg.text = rec.clonePkg
        d.version.text = CloneVersions.show(rec.sourceVersionCode, rec.sourceVersionName)
        d.installedAt.text = Formats.relative(rec.createdAt)

        // 「更新」只在确定有新版本时出现：留一个点了没反应的按钮，用户会以为是工具坏了
        val updatable = row.installed && row.update.kind == UpdateKind.AVAILABLE
        d.actionUpdate.visibility = if (updatable) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------ 其余

    /**
     * 冷启动闪屏的退出动画。
     *
     * Android 12 起系统强制给每个应用一个闪屏（图标 + 背景），默认退出是图标直接消失、
     * 内容同时出现，边界很硬。这里接住退出回调，让图标放大 12% 并淡出，与内容淡入接上。
     *
     * 全程 runCatching：热启动、分屏、或系统没给闪屏窗口时根本不会回调，
     * 拿不到就算了，不能因为一个装饰性动画影响启动。
     */
    private fun installSplashExit() {
        runCatching {
            // installSplashScreen() 是 Activity 的扩展函数，定义在 SplashScreen 的伴生对象里
            installSplashScreen().setOnExitAnimationListener { provider ->
                    var removed = false
                    val remove = Runnable {
                        if (!removed) {
                            removed = true
                            runCatching { provider.remove() }
                        }
                    }
                    val icon = provider.iconView
                    icon.animate()
                        .alpha(0f)
                        .scaleX(1.12f)
                        .scaleY(1.12f)
                        .setDuration(180L)
                        .setInterpolator(Curve.EMPHASIZED_ACCELERATE)
                        .withEndAction(remove)
                        .start()
                    // 兜底：动画被系统打断时也要把闪屏窗口摘掉，否则会一直挡住界面
                    icon.postDelayed(remove, 400L)
                }
        }
    }

    private fun openPicker() {
        startActivity(Intent(this, AppPickerActivity::class.java))
    }

    private fun progressDialog(): ProgressDialog = ProgressDialog(this)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
