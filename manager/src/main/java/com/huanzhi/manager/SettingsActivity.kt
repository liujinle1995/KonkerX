package com.huanzhi.manager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.huanzhi.manager.core.CacheCleaner
import com.huanzhi.manager.core.Changelog
import com.huanzhi.manager.core.CloneStore
import com.huanzhi.manager.core.InstallLog
import com.huanzhi.manager.core.LogBook
import com.huanzhi.manager.core.Purge
import com.huanzhi.manager.core.RuntimeProbe
import com.huanzhi.manager.core.SettingsStore
import com.huanzhi.manager.databinding.DialogPayBinding
import com.huanzhi.manager.ui.ProgressDialog
import com.huanzhi.manager.ui.components.MainTab
import com.huanzhi.manager.ui.dialog.glassAlert
import com.huanzhi.manager.ui.dialog.showInstallLog
import com.huanzhi.manager.ui.dialog.glassChoice
import com.huanzhi.manager.ui.dialog.glassMenu
import com.huanzhi.manager.ui.motion.Curve
import com.huanzhi.manager.ui.nav.AppScaffold
import com.huanzhi.manager.ui.screen.SettingsScreen
import com.huanzhi.manager.ui.screen.SettingsUiState
import com.huanzhi.manager.ui.theme.HuanZhiTheme
import com.huanzhi.manager.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页。
 *
 * 3.7 起界面层迁到 Compose（与主页同一套 Liquid Glass 令牌），这个 Activity
 * 只负责三件事：算数据、弹确认框、执行动作。
 *
 * 弹窗暂时仍用 Material 的 [MaterialAlertDialogBuilder] —— 确认框是"打断流程"，
 * 用系统观感反而更稳（用户对系统对话框的肌肉记忆是可靠的）；等下一步把
 * 破坏性确认也换成自绘版，再统一。
 */
class SettingsActivity : AppCompatActivity() {

    /** 渲染状态。Compose 观察它，赋值即重绘 */
    private var uiState by mutableStateOf(SettingsUiState())

    /** 正在做淡出→重建的交接，期间忽略重复点击 */
    private var switching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内容要画到状态栏与导航栏底下，玻璃层才会真的"浮"在内容上
        enableEdgeToEdge()
        // 因切主题而重建时，新窗口从透明淡入
        fadeInIfNeeded()

        val root = ComposeView(this).apply {
            setContent {
                HuanZhiTheme {
                    AppScaffold(
                        selected = MainTab.Settings,
                        onSelect = ::onTabSelected,
                        railTitle = getString(R.string.app_name),
                        railSubtitle = if (uiState.cloneCount == 0) {
                            getString(R.string.home_summary_zero, uiState.cloneCount)
                        } else {
                            getString(
                                R.string.home_summary,
                                uiState.cloneCount,
                                Formats.size(uiState.totalOccupied),
                            )
                        },
                        railFooter = getString(R.string.rail_footer, Formats.knownSize(uiState.cacheBytes)),
                    ) {
                        SettingsScreen(
                            state = uiState,
                            onPickTheme = ::pickTheme,
                            onPickIndexSize = ::pickIndexSize,
                            onOpenCleanup = ::pickCleanup,
                            onOpenLog = ::showLog,
                            onOpenVersion = { ChangelogDialog.show(this@SettingsActivity) },
                            onOpenDocs = {
                                startActivity(Intent(this@SettingsActivity, AboutActivity::class.java))
                            },
                            onOpenSupport = ::showSupport,
                        )
                    }
                }
            }
        }
        setContentView(root)
    }

    /** 内容要画到状态栏与导航栏底下，玻璃层才会真的"浮"在内容上 */
    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun onTabSelected(tab: MainTab) {
        when (tab) {
            MainTab.Settings -> Unit
            MainTab.Clones -> {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
                finish()
            }
            MainTab.Runtime -> {
                startActivity(Intent(this, RuntimeActivity::class.java))
            }
        }
    }

    /**
     * 一次性把设置页需要的所有数据算出来。
     *
     * 全部在 IO 线程：CacheCleaner 要遍历目录树、Purge 要问 PackageManager，
     * 任何一个都不该在主线程做。合并成一次是因为它们各自很轻，
     * 但分成四个 launch 会让界面连着刷新四次、每一帧都在跳数。
     */
    private fun refresh() {
        lifecycleScope.launch {
            val next = withContext(Dispatchers.IO) {
                val stat = CacheCleaner.scan(this@SettingsActivity)
                val snap = Purge.snapshot(this@SettingsActivity)
                val store = CloneStore(this@SettingsActivity)
                // 体积取 RuntimeProbe 的口径（StorageStats 优先、退化为安装包大小），
                // 与主页那张「分身总占用」用的是同一个数 —— 两个页面报不同的数是最难解释的 bug
                val recs = store.all()
                val occupied = runCatching { RuntimeProbe.snapshot(this@SettingsActivity, recs) }
                    .getOrDefault(emptyList())
                    .fold(0L) { acc, rt -> acc + rt.occupiedBytes }
                SettingsUiState(
                    loading = false,
                    themeMode = SettingsStore.themeMode(this@SettingsActivity),
                    indexScale = SettingsStore.indexScale(this@SettingsActivity),
                    cloneCount = recs.size,
                    totalOccupied = occupied,
                    cacheBytes = stat.bytes,
                    cacheFiles = stat.files,
                    // 计数必须跟着**弹层里那一份内容**走：外面写 7 条、点开只看到 4 张卡，
                    // 用户对这页的第一印象就是「它在瞎报」。所以只看结构化记录簿，
                    // 原始 InstallLog 的行数（submit FAIL / reconcile 之类）不该出现在肉眼可见的位置
                    logCount = LogBook.read(this@SettingsActivity).size,
                    pendingRecords = store.deadCount(),
                    versionName = BuildConfig.VERSION_NAME,
                    versionDate = Changelog.current.date,
                )
            }
            if (isFinishing || isDestroyed) return@launch
            uiState = next
        }
    }

    // ---------------- 外观 ----------------

    /**
     * 主题选择。
     *
     * 三档用单选列表而不是开关：「跟随系统」不是"开/关"，它和浅色、深色是
     * 同一个维度上的三个值。用开关会逼出"跟随系统且强制深色"这种不存在的状态。
     */
    private fun pickTheme() {
        val labels = arrayOf(
            getString(R.string.value_follow_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
        )
        val modes = intArrayOf(
            SettingsStore.THEME_SYSTEM,
            SettingsStore.THEME_LIGHT,
            SettingsStore.THEME_DARK,
        )
        val checked = modes.indexOf(uiState.themeMode).coerceAtLeast(0)

        glassChoice(
            title = getString(R.string.row_appearance),
            labels = labels.toList(),
            checked = checked,
        ) { which ->
            if (modes[which] != SettingsStore.themeMode(this)) switchTheme(modes[which])
        }
    }

    /**
     * 索引字母大小。
     *
     * 这一档不重建 Activity：它只影响「选应用」页右侧索引条的字号，那边在 onResume
     * 里发现档位变了会自己重建索引条，不需要为了一个字号把整个设置页闪一下。
     */
    private fun pickIndexSize() {
        val labels = arrayOf(
            getString(R.string.index_size_small),
            getString(R.string.index_size_normal),
            getString(R.string.index_size_large),
        )
        val scales = intArrayOf(
            SettingsStore.INDEX_SMALL,
            SettingsStore.INDEX_NORMAL,
            SettingsStore.INDEX_LARGE,
        )
        val checked = scales.indexOf(uiState.indexScale).coerceAtLeast(1)

        glassChoice(
            title = getString(R.string.row_index_size),
            labels = labels.toList(),
            checked = checked,
        ) { which ->
            SettingsStore.setIndexScale(this, scales[which])
            uiState = uiState.copy(indexScale = scales[which])
        }
    }

    /**
     * 切主题：fade-through —— 旧界面先走（90ms），重建后新界面再进（210ms）。
     *
     * 直接调 `SettingsStore.setThemeMode` 会立刻走 `setDefaultNightMode` → 重建，
     * 屏幕上的表现是一帧硬切（深色底突然变白，或者反过来），闪得人眨眼睛。
     *
     * 时长按 MOTION.md #17：出 90 / 进 210。出比进短很多是刻意的 ——
     * 让「旧界面离开」几乎不可感知，注意力全落在「新界面到达」上，这才是 fade-through
     * 区别于普通交叉淡入的地方（后者两半等长，看着像同时糊了一下）。
     */
    private fun switchTheme(mode: Int) {
        if (switching) return
        switching = true
        val decor = window.decorView
        decor.animate().cancel()
        decor.animate().alpha(0f)
            .setDuration(90L)
            .setInterpolator(Curve.EMPHASIZED_ACCELERATE)
            .withEndAction {
                fadeInNextCreate = true
                SettingsStore.setThemeMode(this, mode)
                // 解析出的主题可能跟当前一样（比如「跟随系统」而系统本来就是浅色），
                // 这时 AppCompat 不会重建，得自己把透明度还回去，否则界面停在透明状态
                decor.postDelayed({
                    if (!isFinishing && !isDestroyed && fadeInNextCreate) {
                        fadeInNextCreate = false
                        switching = false
                        decor.animate().alpha(1f).setDuration(210L)
                            .setInterpolator(Curve.EMPHASIZED_DECELERATE).start()
                    }
                }, 220L)
            }
            .start()
    }

    /**
     * 新窗口的第一帧画出来之后再淡入。
     *
     * 原来是在 onCreate 里直接启动动画，那个时刻窗口还没测量完，
     * 动画的前几十毫秒其实被首帧的布局吃掉了，观感是「先卡一下再淡入」。
     */
    private fun fadeInIfNeeded() {
        if (!fadeInNextCreate) return
        fadeInNextCreate = false
        val decor = window.decorView
        decor.alpha = 0f
        decor.doOnPreDraw {
            decor.animate().alpha(1f)
                .setDuration(210L)
                .setInterpolator(Curve.EMPHASIZED_DECELERATE)
                .start()
        }
    }

    // ---------------- 诊断 ----------------

    /**
     * 「建了两个只装上一个」这种问题，光看界面是判断不了的：
     * 真正的原因藏在建包、提交、系统回执这三段里，所以要能直接看到。
     *
     * 3.11 起不再是「一堆等宽文本 + 三个按钮」，而是按份排的卡片（design/15、18、24、30）。
     * 原始文本没有丢，仍然由弹层右上角的「复制」整段带走 —— 那是拿去发给人看的东西，
     * 而卡片是留下来自己看的东西。
     */
    private fun showLog() {
        lifecycleScope.launch {
            val events = withContext(Dispatchers.IO) { LogBook.read(this@SettingsActivity) }
            if (isFinishing || isDestroyed) return@launch
            showInstallLog(events)
        }
    }

    // ---------------- 清理与卸载 ----------------

    /**
     * 「清理与卸载记录」这一个入口背后是两件程度差别很大的事，所以先让用户选，
     * 而不是塞进同一句确认文案里。
     */
    private fun pickCleanup() {
        val items = arrayOf(
            getString(R.string.clear_cache),
            getString(R.string.purge_clones),
            getString(R.string.purge_uninstall_self),
        )
        glassMenu(
            title = getString(R.string.row_cleanup_records),
            items = items.toList(),
        ) { which ->
            when (which) {
                0 -> confirmClearCache()
                1 -> confirmPurge()
                else -> confirmUninstallSelf()
            }
        }
    }

    private fun confirmClearCache() {
        lifecycleScope.launch {
            val (stat, dead) = withContext(Dispatchers.IO) {
                CacheCleaner.scan(this@SettingsActivity) to CloneStore(this@SettingsActivity).deadCount()
            }
            if (stat.files == 0 && dead == 0) {
                toast("缓存已经是空的")
                return@launch
            }
            val body = buildString {
                if (stat.files > 0) {
                    append("将删除生成分身时留下的临时文件和 APK 副本，共 ")
                    append(CacheCleaner.format(stat.bytes)).append("。")
                }
                if (dead > 0) {
                    if (isNotEmpty()) append("\n\n")
                    append("另有 ").append(dead).append(
                        " 条失效记录（安装包已删、系统里也没有这个应用），一并清掉，列表里不会再挂着点不动的条目。"
                    )
                }
                append("\n\n已经装进系统的分身不受影响，卸载后才会消失。")
            }
            glassAlert(
                title = "清除缓存",
                message = body,
                confirmLabel = "清除",
                cancelLabel = getString(R.string.cancel),
                destructive = true,
            ) { doClear() }
        }
    }

    private fun doClear() {
        lifecycleScope.launch {
            // 缓存清了但记录还在，是「清了却看不出变化」的根源：
            // 装不上的分身包已经删了，记录里那行「未安装」永远点不动，所以一起收掉。
            val (freed, pruned) = withContext(Dispatchers.IO) {
                val f = CacheCleaner.clear(this@SettingsActivity)
                f to CloneStore(this@SettingsActivity).pruneDead()
            }
            refresh()
            val parts = buildList {
                if (freed > 0) add("已释放 ${CacheCleaner.format(freed)}")
                if (pruned > 0) add("清理了 $pruned 条失效记录")
            }
            toast(parts.joinToString("，").ifEmpty { "没有可清理的内容" })
        }
    }

    /**
     * 分身是本工具装进系统的独立 APK，卸载本应用不会带走它们。
     * 这一组入口就是补上这件事：要么只清分身，要么连本应用一起卸掉。
     *
     * 注意这里**做不到静默**：普通应用每卸一个包，系统都要用户点一次确认框。
     * 所以入口的职责是把 N 个卸载框排好队，而不是假装能一键清空。
     */
    private fun confirmPurge() {
        lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) { Purge.snapshot(this@SettingsActivity) }
            if (snap.empty) {
                toast(getString(R.string.purge_nothing))
                return@launch
            }
            val body = buildString {
                when {
                    snap.total > 0 -> append(getString(R.string.purge_confirm_body, snap.total))
                    snap.legacy.isEmpty() -> append(getString(R.string.purge_confirm_body_records_only))
                    // 只剩旧版分身时，下面那段自己说得清楚，不必再补一句「当前没有分身」
                    else -> Unit
                }
                if (snap.legacy.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(getString(R.string.purge_confirm_body_legacy, snap.legacy.size))
                }
            }
            glassAlert(
                title = getString(R.string.purge_confirm_title),
                message = body,
                confirmLabel = getString(R.string.purge_confirm_ok),
                cancelLabel = getString(R.string.cancel),
                destructive = true,
            ) { doPurge(uninstallAfter = false) }
        }
    }

    private fun confirmUninstallSelf() {
        lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) { Purge.snapshot(this@SettingsActivity) }
            val body = buildString {
                append(
                    if (snap.total > 0) {
                        getString(R.string.purge_uninstall_confirm_body, snap.total)
                    } else {
                        getString(R.string.purge_uninstall_confirm_body_none)
                    }
                )
                if (snap.legacy.isNotEmpty()) {
                    append("\n\n")
                    append(getString(R.string.purge_confirm_body_legacy, snap.legacy.size))
                }
            }
            glassAlert(
                title = getString(R.string.purge_uninstall_confirm_title),
                message = body,
                confirmLabel = getString(R.string.purge_uninstall_self),
                cancelLabel = getString(R.string.cancel),
                destructive = true,
            ) { doPurge(uninstallAfter = true) }
        }
    }

    /**
     * @param uninstallAfter 清完之后是否接着卸载本应用。
     *   有分身没卸掉时不往下走 —— 本应用一没，剩下的分身就再没人管得着了。
     */
    private fun doPurge(uninstallAfter: Boolean) {
        lifecycleScope.launch {
            val dlg = ProgressDialog(this@SettingsActivity)
            dlg.show(
                title = getString(
                    if (uninstallAfter) R.string.purge_uninstall_confirm_title
                    else R.string.purge_confirm_title
                ),
                hint = getString(R.string.purge_running),
                // 真正的「停止」在系统卸载框上：这里不给取消按钮，
                // 免得按下后既停不了系统框、又让人以为已经停了
                onCancel = null,
            )
            val out = Purge.wipe(this@SettingsActivity) { done, total ->
                val pct = if (total <= 0) 0 else (done * 100 / total)
                dlg.update(
                    pct.coerceIn(0, 99),
                    getString(R.string.purge_step, (done + 1).coerceAtMost(total), total),
                )
            }
            dlg.finish(getString(R.string.purge_done, out.done))
            refresh()
            kotlinx.coroutines.delay(420)
            dlg.dismiss()

            toast(
                when {
                    out.left.isNotEmpty() -> getString(R.string.purge_partial, out.done, out.left.size)
                    out.legacy.isNotEmpty() -> getString(R.string.purge_legacy_left, out.legacy.size)
                    out.done > 0 -> getString(R.string.purge_done, out.done)
                    else -> getString(R.string.purge_nothing)
                }
            )
            if (uninstallAfter && out.left.isEmpty()) {
                Purge.uninstallSelf(this@SettingsActivity)
            }
        }
    }

    // ---------------- 支持作者 ----------------

    /**
     * 收款码。
     *
     * 为什么不做「点一下就跳微信直接付」：微信把这条路封死了，不是我们没找到 API。
     * 收款码里存的 `wxp://f2f0…` 是微信自家协议，微信支付社区对同一个问题的回复是
     * 「暂不支持直接拉起收银台」；唤起扫一扫的 `weixin://dl/scan` 从 2021 年起只对
     * 白名单（手机厂商自带的扫一扫）开放，第三方 App 调用是静默失败 —— 与其加一个
     * 点了没反应的按钮，不如把能做到的最短路给出来。
     */
    private fun showSupport() {
        val view = DialogPayBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.support_title)
            .setView(view.root)
            .setNeutralButton(R.string.support_save_qr) { _, _ -> saveQrToGallery() }
            .setPositiveButton(R.string.close, null)
            .show()
    }

    /**
     * 把打包进 APK 的那张 PNG 原样写进相册。
     *
     * 用 openRawResource 直接拷字节，而不是 decode 成位图再 compress：重编码要多走一遍
     * 采样与压缩，二维码边缘容易多出伪影，而这张图本身才 150KB，没必要为了省这点空间
     * 去赌识别率。
     */
    private fun saveQrToGallery() {
        val ok = runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "huanzhi-pay-${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/HuanZhi")
                // 写入期间对其它 App 不可见，避免微信的相册里闪出一个只有半截的图
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            val out = contentResolver.openOutputStream(uri) ?: return@runCatching false
            out.use { resources.openRawResource(R.drawable.pay_wechat).use { it.copyTo(out) } }

            // 不清 IS_PENDING 的话这张图永远不会出现在相册里，微信自然也选不到
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            true
        }.getOrDefault(false)
        Toast.makeText(
            this,
            if (ok) R.string.support_saved else R.string.support_save_failed,
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        /**
         * 跨一次 Activity 重建传递：新实例要不要淡入。
         * 用静态字段是因为 recreate 不能带 intent extra，而这个进程内的单次交接足够可靠。
         */
        @Volatile
        private var fadeInNextCreate = false
    }
}
