package com.huanzhi.manager

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.huanzhi.manager.core.ApkInstaller
import com.huanzhi.manager.core.AppScanner
import com.huanzhi.manager.core.BuildCancelledException
import com.huanzhi.manager.core.BuildControl
import com.huanzhi.manager.core.CloneRecord
import com.huanzhi.manager.core.CloneStore
import com.huanzhi.manager.core.CloneVersions
import com.huanzhi.manager.core.CloneWorkflow
import com.huanzhi.manager.core.InstallBus
import com.huanzhi.manager.core.InstallListener
import com.huanzhi.manager.core.SettingsStore
import com.huanzhi.manager.core.TemplateSpec
import com.huanzhi.manager.core.UpdateRunner
import com.huanzhi.manager.databinding.ActivityMainBinding
import com.huanzhi.manager.databinding.DialogRenameBinding
import com.huanzhi.manager.ui.motion.Curve
import com.huanzhi.manager.ui.motion.cascade
import com.huanzhi.manager.ui.motion.crossFade
import com.huanzhi.manager.ui.motion.hideScaled
import com.huanzhi.manager.ui.motion.showScaled
import com.huanzhi.manager.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var workflow: CloneWorkflow
    private lateinit var adapter: CloneAdapter

    /** 当前比源应用旧的那些分身；菜单项和「更新全部」都以它为准 */
    private var outdated: List<CloneRecord> = emptyList()

    /** 批量更新进行中时挂着的进度弹窗；转屏重建后要重新挂一个 */
    private var updateDialog: com.huanzhi.manager.ui.ProgressDialog? = null

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
        if (com.huanzhi.manager.core.TemplateSpec.isClonePkg(pkg)) {
            runOnUiThread { if (!isFinishing && !isDestroyed) refresh() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 冷启动闪屏：只接手「退出」那一段，让图标放大淡出、和内容淡入接上
        installSplashExit()

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        workflow = CloneWorkflow(this)
        adapter = CloneAdapter(onClick = ::onCloneClick, onMore = ::onMoreClick)
        // 平板上两列，手机上仍然一列
        b.list.layoutManager = GridLayoutManager(this, Screen.columns(this))
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
        ensureInstallPermission()
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
                toast("已移除 ${gone.size} 个已卸载的分身记录")
            }
            // 版本比对要查 PackageManager，和读记录一起放到 IO。
            // 结果同时用于两件事：行里的角标、菜单里的「更新全部（N）」——
            // 两处各算一遍的话，早晚会出现「菜单说有 2 个、列表里只标了 1 个」。
            val (rows, outdated) = withContext(Dispatchers.IO) {
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
            this@MainActivity.outdated = outdated
            adapter.submit(rows)
            render(rows.isEmpty())
            syncUpdateMenu(outdated.size)
        }
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
    private fun wireUpdateUi(dlg: com.huanzhi.manager.ui.ProgressDialog) {
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

    /**
     * 冷启动闪屏的退出动画。
     *
     * Android 12 起系统强制给每个应用一个闪屏（图标 + 背景），默认退出是图标直接消失、
     * 内容同时出现，边界很硬。这里接住退出回调，让图标放大 12% 并淡出，与内容淡入接上。
     *
     * 平台侧的 `SplashScreen.setOnExitAnimationListener` 是 @hide，只有官方兼容库能用；
     * 库里在 API 31+ 会直接转给平台实现。
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

    private fun onCloneClick(rec: CloneRecord) {
        if (!AppScanner.isInstalled(this, rec.clonePkg)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(rec.label)
                .setMessage("这个分身还没装到系统里，要现在安装吗？")
                .setPositiveButton("安装") { _, _ -> rebuild(rec) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        val intent = packageManager.getLaunchIntentForPackage(rec.clonePkg)
        if (intent == null) {
            toast("这个分身没有可用入口，建议重新生成")
            return
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            toast("启动失败：${t.message}")
        }
    }

    private fun openPicker() {
        startActivity(Intent(this, AppPickerActivity::class.java))
    }

    private fun onMoreClick(rec: CloneRecord, anchor: android.view.View) {
        val items = arrayOf(
            getString(R.string.action_rename),
            getString(R.string.action_rebuild),
            getString(R.string.action_compat),
            getString(R.string.action_uninstall),
            getString(R.string.action_forget)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(rec.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> askRename(rec)
                    1 -> rebuild(rec)
                    2 -> askCompatRebuild(rec)
                    3 -> confirmUninstall(rec)
                    4 -> confirmForget(rec)
                }
            }
            .show()
    }

    /**
     * 卸载前必须确认。
     *
     * 卸载会把分身自己的数据一起删掉（不可恢复），原来点一下就执行，
     * 跟旁边的「重命名」只差一个条目位置，误触代价太高。
     */
    private fun confirmUninstall(rec: CloneRecord) {
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.uninstall_title, rec.label))
            .setMessage(getString(R.string.uninstall_message))
            .setPositiveButton(getString(R.string.action_uninstall)) { _, _ ->
                ApkInstaller.uninstall(this, rec.clonePkg)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.show()
        // 确认按钮是 destructive 语义：红色，跟「取消」拉开层级
        dlg.getButton(DialogInterface.BUTTON_POSITIVE)
            ?.setTextColor(MaterialColors.getColor(dlg.getButton(DialogInterface.BUTTON_POSITIVE),
                com.google.android.material.R.attr.colorError))
    }

    /** 只删记录：要说清「应用还在不在」，否则用户以为被卸载了 */
    private fun confirmForget(rec: CloneRecord) {
        val installed = AppScanner.isInstalled(this, rec.clonePkg)
        val msg = if (installed) {
            getString(R.string.forget_message_installed, rec.label, rec.clonePkg)
        } else {
            getString(R.string.forget_message_missing, rec.label)
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.forget_title)
            .setMessage(msg)
            .setPositiveButton(R.string.action_forget) { _, _ ->
                workflow.forget(rec)
                refresh()
                toast(getString(R.string.record_removed))
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.show()
        dlg.getButton(DialogInterface.BUTTON_POSITIVE)
            ?.setTextColor(MaterialColors.getColor(dlg.getButton(DialogInterface.BUTTON_POSITIVE),
                com.google.android.material.R.attr.colorError))
    }

    /**
     * 阻塞式进度：打包期间禁用返回和重复点击。
     * 现在是有百分比的确定型进度（见 [ProgressDialog]），不再是一个转圈 ——
     * 重打包要几十秒，用户需要知道「还剩多少」，而不是只知道「在忙」。
     */
    private fun progressDialog(): com.huanzhi.manager.ui.ProgressDialog =
        com.huanzhi.manager.ui.ProgressDialog(this)

    /**
     * 重命名。
     *
     * 分身名是打进清单里的，所以改名 = 用新名字重新打包 + 覆盖安装（等同升级），
     * 数据不会丢。这一点必须在弹窗里讲清楚，否则用户会以为只是改个显示名。
     */
    private fun askRename(rec: CloneRecord) {
        val view = DialogRenameBinding.inflate(layoutInflater)
        view.nameInput.setText(rec.label)
        // 光标按过滤后的实际长度算：maxLength 可能截短默认名，直接用 label.length 会越界
        view.nameInput.setSelection(view.nameInput.text?.length ?: 0)
        view.hint.text = "改名会重新打包，并以「更新」的方式覆盖安装；分身里的数据、登录状态都保留。"

        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("重命名")
            .setView(view.root)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dlg.show()
        dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = view.nameInput.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                toast("名称不能为空")
                return@setOnClickListener
            }
            if (name == rec.label) {
                dlg.dismiss()
                return@setOnClickListener
            }
            dlg.dismiss()
            rename(rec, name)
        }
    }

    private fun rename(rec: CloneRecord, newLabel: String) {
        runBuild(
            title = getString(R.string.progress_rename, newLabel),
            onWork = { control -> workflow.rename(rec, newLabel, control) },
            onDone = { refresh(); toast("已改名为「$newLabel」，请在系统弹窗确认更新") },
            failPrefix = "改名失败",
        )
    }

    private fun rebuild(rec: CloneRecord, compat: Boolean = false) {
        runBuild(
            title = getString(R.string.progress_repack, rec.label),
            onWork = { control -> workflow.rebuild(rec, compat, control) },
            onDone = { refresh(); toast("已提交安装，请在系统弹窗中确认") },
            failPrefix = "生成失败",
        )
    }

    /**
     * 三类长任务的公共外壳：进度弹窗 + 取消 + 异常提示。
     *
     * 取消做成「尽力而为」：打包的拷贝循环每 256KB 查一次标记，通常几百毫秒内就停下；
     * 但已经进到签名或写安装会话那一步就拦不住了 —— 那两步各自只有一次系统调用，
     * 中间没有可以安全抽身的点。所以按钮点下去后显示「正在取消…」而不是立刻消失。
     */
    private fun runBuild(
        title: String,
        failPrefix: String,
        onWork: suspend (BuildControl) -> Unit,
        onDone: () -> Unit,
    ) {
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val dlg = progressDialog()
        dlg.show(
            title = title,
            hint = getString(R.string.progress_hint_install),
            onCancel = { cancel.set(true) },
        )
        lifecycleScope.launch {
            try {
                onWork(BuildControl(onProgress = dlg::update, isCancelled = { cancel.get() }))
                dlg.finish(getString(R.string.progress_submitted))
                // 让「100% 已提交」这一帧被看到，再把界面交回给系统安装框
                kotlinx.coroutines.delay(320)
                onDone()
            } catch (e: BuildCancelledException) {
                toast(getString(R.string.cancelled))
            } catch (t: Throwable) {
                toast("$failPrefix：${t.message ?: t.javaClass.simpleName}")
            } finally {
                dlg.dismiss()
            }
        }
    }

    /**
     * 兼容模式重建。
     *
     * 「装上了但卡在启动页 / 闪退」在克隆里很常见，原因通常只有两类：
     *   1. 应用自己在校验签名 —— 工具层无解，只能换别的多开方式；
     *   2. 应用按名字去找资源或配置，而资源表里的包名被我们改掉了 —— 这时关掉改名就有机会好。
     *
     * 所以这里把两种可能都摆出来再动手，避免用户以为「兼容模式」是万能药。
     */
    private fun askCompatRebuild(rec: CloneRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle("兼容模式重建")
            .setMessage(
                "会重新打包并以「更新」方式覆盖安装（数据保留），区别只有一处：" +
                    "不改动资源表 resources.arsc 里的包名。\n\n" +
                    "适用于：分身能装上，但卡在启动页、白屏、或提示资源加载失败。\n\n" +
                    "如果原版应用自己能正常启动、克隆版却卡住，多半是它在校验签名 —— " +
                    "这种情况兼容模式也解决不了，只能改用系统自带的多开 / 工作资料。"
            )
            .setPositiveButton("开始重建") { _, _ -> rebuild(rec, compat = true) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun ensureInstallPermission() {
        if (ApkInstaller.canInstall(this)) return
        MaterialAlertDialogBuilder(this)
            .setTitle("需要安装权限")
            .setMessage("创建分身需要把生成好的 APK 装进系统，请允许「安装未知应用」。")
            .setPositiveButton("去设置") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
