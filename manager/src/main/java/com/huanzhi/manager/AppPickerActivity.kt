package com.huanzhi.manager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.huanzhi.manager.core.ApkInstaller
import com.huanzhi.manager.core.AppEntry
import com.huanzhi.manager.core.AppScanner
import com.huanzhi.manager.core.BatchRunner
import com.huanzhi.manager.core.BuildCancelledException
import com.huanzhi.manager.core.BuildControl
import com.huanzhi.manager.core.CloneStore
import com.huanzhi.manager.core.CloneWorkflow
import com.huanzhi.manager.core.HardeningDetector
import com.huanzhi.manager.core.InstallLog
import com.huanzhi.manager.core.Pinyin
import com.huanzhi.manager.core.StorageCheck
import com.huanzhi.manager.core.TemplateSpec
import com.huanzhi.manager.databinding.DialogBatchBinding
import com.huanzhi.manager.databinding.DialogCloneNameBinding
import com.huanzhi.manager.databinding.ItemNameEditBinding
import com.huanzhi.manager.ui.ProgressDialog
import com.huanzhi.manager.ui.adaptive.WindowSizeClass
import com.huanzhi.manager.ui.adaptive.WindowSizeClasses
import com.huanzhi.manager.ui.screen.AppPickerScreen
import com.huanzhi.manager.ui.screen.AppPickerUiState
import com.huanzhi.manager.ui.screen.PickerAppUi
import com.huanzhi.manager.ui.screen.PickerFilter
import com.huanzhi.manager.ui.dialog.glassAlert
import com.huanzhi.manager.ui.screen.PickerSectionUi
import com.huanzhi.manager.ui.theme.HuanZhiTheme
import com.huanzhi.manager.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 选应用页。
 *
 * 3.9 起界面层迁到 Compose，这个 Activity 只留「扫描 → 过滤 → 选中 → 建包」这条链路。
 * 与主页不同的是，这一页**交互模型**也改了，不只是外观：
 *
 * · 手机保留「点一行直接建一个」—— 这是从 1.0 起就有的路径，用户已经形成习惯，
 *   不该为一次视觉改版改掉；点「多选」才进勾选态。
 * · 平板一进来就是勾选态：右侧的已选面板必须先有选中项才有内容。平板上把
 *   「选应用」和「定份数」拆成两步，等于让人在左右两个区域之间来回跑。
 *
 * 「不支持」现在**在扫描阶段就能判出来**（[AppEntry.split]），不再等到建包时才报
 * 「分片不支持」—— 那时候用户已经点完确认、等了半分钟。
 */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var workflow: CloneWorkflow

    /** 渲染状态。Compose 观察它，赋值即重绘 */
    private var ui by mutableStateOf(AppPickerUiState())

    /** 全量应用（拼音序、已带加固信息） */
    private var scanned: List<AppEntry> = emptyList()
    private var byPkg: Map<String, AppEntry> = emptyMap()

    /** 已经有分身的源包名，供「已分身」筛选用 */
    private var clonedPkgs: Set<String> = emptySet()

    private var query = ""
    private var filter = PickerFilter.All
    private var selectionMode = false

    /** 选中的包名 → 份数，按选中顺序。份数只在平板右栏改得到 */
    private val selected = LinkedHashMap<String, Int>()

    private var wide = false

    private var progressDlg: ProgressDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内容画到状态栏底下，顶栏才和系统状态栏连成一片
        enableEdgeToEdge()
        wide = WindowSizeClasses.of(this) == WindowSizeClass.EXPANDED
        // 平板一进来就是勾选态：右侧面板没有选中项就是个空盒子
        selectionMode = wide
        // 转屏 / 切深浅色会把 Activity 重建，勾了半天的清单不能就此清空
        restorePicked(savedInstanceState)

        workflow = CloneWorkflow(this)

        setContentView(
            ComposeView(this).apply {
                setContent {
                    HuanZhiTheme {
                        AppPickerScreen(
                            state = ui,
                            onBack = { finish() },
                            onToggleSelectionMode = ::toggleSelectionMode,
                            onCancelOrClear = ::clearOrFinish,
                            onQuery = ::onQuery,
                            onFilter = ::onFilter,
                            onRowClick = ::onRowClick,
                            onCopies = ::onCopies,
                            onNext = ::onNext,
                        )
                    }
                }
            }
        )

        load()
    }

    override fun onStart() {
        super.onStart()
        // 批量在 Application 级协程里跑，转屏或被系统回收后重建都能重新挂上
        if (BatchRunner.isRunning) {
            BatchRunner.onProgress = ::renderProgress
            BatchRunner.onDone = ::onBatchDone
            showProgressDialog()
            BatchRunner.lastProgress?.let { renderProgress(it) }
        } else if (BatchRunner.lastSummary != null && progressDlg != null) {
            val s = BatchRunner.lastSummary ?: return
            BatchRunner.clearSummary()
            dismissProgress()
            onBatchDone(s)
        }
    }

    override fun onStop() {
        super.onStop()
        if (BatchRunner.isRunning) {
            BatchRunner.onProgress = null
            BatchRunner.onDone = null
        }
    }

    override fun onDestroy() {
        dismissProgress()
        super.onDestroy()
    }

    /**
     * 转屏、切换深浅色都会重建 Activity。这一页的「已勾选 + 每项份数 + 关键词 + 筛选」
     * 全是 Activity 里的普通字段，不写进 Bundle 就会连同 88dp 的行一起蒸发 ——
     * 用户勾了五个应用，系统一发夜间模式广播就全没了，这不能算"小问题"。
     *
     * 只存包名与份数，不存 [AppEntry]：扫描结果重建后自然会再有一份，
     * 存对象既撑大 Bundle 又可能因为应用在这期间被卸载而对不上。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_QUERY, query)
        outState.putString(KEY_FILTER, filter.name)
        outState.putBoolean(KEY_MULTI, selectionMode)
        outState.putStringArrayList(KEY_PKGS, ArrayList(selected.keys))
        outState.putIntArray(KEY_COPIES, selected.values.toIntArray())
    }

    /** 与 [onSaveInstanceState] 配对。必须在 load() 之前跑完，扫描结束时会用它对上名字 */
    private fun restorePicked(b: Bundle?) {
        if (b == null) return
        query = b.getString(KEY_QUERY).orEmpty()
        filter = runCatching { PickerFilter.valueOf(b.getString(KEY_FILTER).orEmpty()) }
            .getOrDefault(PickerFilter.All)
        selectionMode = b.getBoolean(KEY_MULTI, selectionMode)
        val pkgs = b.getStringArrayList(KEY_PKGS).orEmpty()
        val copies = b.getIntArray(KEY_COPIES) ?: IntArray(0)
        selected.clear()
        pkgs.forEachIndexed { i, pkg -> selected[pkg] = copies.getOrElse(i) { 1 } }
        // 先把关键词与筛选推上去，避免重建后搜索框先空一帧再回填
        ui = ui.copy(query = query, filter = filter, selectionMode = selectionMode, wide = wide)
    }

    // ---------------- 扫描 ----------------

    private fun load() {
        ui = ui.copy(loading = true)
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                // 排序要用 ICU 转拼音，几百个应用放在主线程会明显卡首帧
                Pinyin.sortByLabel(
                    AppScanner.launchableApps(this@AppPickerActivity)
                        .filter { it.pkg.toByteArray().size <= TemplateSpec.MAX_TARGET_PKG_LEN }
                )
            }
            // 加固探测必须放在「显示之前」：结果会出现在副标题里，
            // 先显示再逐步回填会让列表在用户眼皮底下自己跳
            val probed = withContext(Dispatchers.IO) { probeHardening(found) }
            val cloned = withContext(Dispatchers.IO) {
                CloneStore(this@AppPickerActivity).all().mapNotNull { it.targetPkg }.toSet()
            }

            scanned = probed
            byPkg = probed.associateBy { it.pkg }
            clonedPkgs = cloned
            push()
        }
    }

    /**
     * 逐个应用探测加固壳。
     *
     * 并行跑：每个应用要打开一次 ZipFile 读中央目录（几十毫秒），串行扫两百个应用
     * 就是一整个能感知到的等待。结果在 [HardeningDetector] 里按 versionCode 缓存，
     * 所以只有第一次进这个页面会真的扫盘。
     */
    private suspend fun probeHardening(list: List<AppEntry>): List<AppEntry> =
        kotlinx.coroutines.coroutineScope {
            val ctx = applicationContext
            list.map { e ->
                async(Dispatchers.IO) {
                    val brand = runCatching { HardeningDetector.detectCached(ctx, e.pkg) }.getOrNull()
                    if (brand == null) e else e.copy(hardening = brand)
                }
            }.awaitAll()
        }

    // ---------------- 状态推送 ----------------

    /** 过滤 → 分组 → 推给 Compose。所有会改变画面的操作最后都落到这里 */
    private fun push() {
        val q = query.trim()
        val visible = scanned.filter { app ->
            matches(app, q) && when (filter) {
                PickerFilter.All -> true
                PickerFilter.Cloned -> app.pkg in clonedPkgs
                PickerFilter.Unsupported -> app.split
            }
        }

        val sections = visible
            .groupBy { Pinyin.keyOf(it.label).letter }
            // 「#」放最后：# 是数字与符号开头的名字，排在 A 前面会显得列表没排序
            .toSortedMap(compareBy({ if (it == '#') 1 else 0 }, { it }))
            .map { (letter, apps) -> PickerSectionUi(letter, apps.map(::toUi)) }

        ui = ui.copy(
            loading = false,
            sections = sections,
            query = query,
            filter = filter,
            selectionMode = selectionMode,
            selected = LinkedHashMap(selected),
            selectedApps = selected.keys.mapNotNull { byPkg[it] }.map(::toUi),
            wide = wide,
        )
    }

    /**
     * 应用 → 行数据。
     *
     * 副标题的构成随宽度变：手机「包名 · 体积」（一列，宽度够），平板只留包名
     * （两列时每列 321dp，「com.taobao.taobao · 412 MB」会被截成「com.taobao.tao…」）。
     * 加固壳接在最后 —— 它决定"要不要试一把"，比体积重要，两种宽度都得看得见。
     */
    private fun toUi(app: AppEntry): PickerAppUi {
        val meta = buildString {
            append(app.pkg)
            if (!wide && app.apkBytes > 0L) append(" · ").append(Formats.compact(app.apkBytes))
        }
        return PickerAppUi(
            pkg = app.pkg,
            label = app.label,
            meta = meta,
            supported = !app.split,
            hasClone = app.pkg in clonedPkgs,
            letter = Pinyin.keyOf(app.label).letter,
            hardened = app.hardening != null,
        )
    }

    /**
     * 搜索命中四种写法：原名、拼音全拼、拼音首字母、包名。
     * 首字母用 startsWith 而不是 contains —— 打「wx」找微信是直觉，
     * 但 contains 会把「adwxa」这类乱序也放进来，结果反而变吵。
     */
    private fun matches(app: AppEntry, q: String): Boolean {
        if (q.isEmpty()) return true
        val lower = q.lowercase(Locale.ROOT)
        if (app.label.lowercase(Locale.ROOT).contains(lower)) return true
        if (app.pkg.lowercase(Locale.ROOT).contains(lower)) return true
        val key = Pinyin.keyOf(app.label)
        return key.full.contains(lower) || key.initials.startsWith(lower)
    }

    // ---------------- 交互 ----------------

    private fun onQuery(q: String) {
        if (q == query) return
        query = q
        push()
    }

    private fun onFilter(f: PickerFilter) {
        if (f == filter) return
        filter = f
        push()
    }

    private fun toggleSelectionMode() {
        selectionMode = !selectionMode
        if (!selectionMode) selected.clear()
        push()
    }

    /** 平板顶栏的「取消」：有选中就先清空，已经空了才退出 —— 一次点击只做一件事 */
    private fun clearOrFinish() {
        if (selected.isNotEmpty()) {
            selected.clear()
            push()
        } else {
            finish()
        }
    }

    private fun onRowClick(app: PickerAppUi) {
        if (!app.supported) {
            // 行本身已经灰了，但仍然说明一句：不点一下是不会知道"为什么不行"的
            toast(getString(R.string.pk_split_toast))
            return
        }
        val entry = byPkg[app.pkg] ?: return
        if (selectionMode) {
            if (selected.remove(app.pkg) == null) selected[app.pkg] = 1
            push()
        } else {
            askClone(entry)
        }
    }

    private fun onCopies(pkg: String, copies: Int) {
        if (!selected.containsKey(pkg)) return
        selected[pkg] = copies.coerceIn(1, MAX_BATCH_COPIES)
        push()
    }

    private fun onNext() {
        if (selected.isEmpty()) {
            toast("先勾选要分身的应用")
            return
        }
        // 平板在右栏已经定过份数，弹窗只负责命名；手机上份数仍在弹窗里调
        showBatchDialog(if (wide) LinkedHashMap(selected) else null)
    }

    // ---------------- 单个应用：1 份或多份 ----------------

    /**
     * 同一个弹窗既处理「建一个」也处理「建一批」。
     *
     * 名称框的语义跟着份数走：
     *   · 1 份 → 填什么就叫什么；
     *   · 多份 → 填的是基础名，实际名字自动接「· 分身1 / 2 / 3」；
     *   · 想给某一份单独起名，点「逐份」逐条改。
     */
    private fun askClone(app: AppEntry) {
        if (!ApkInstaller.canInstall(this)) {
            toast("请先允许「安装未知应用」")
            return
        }

        val used = CloneStore(this).all().size
        val maxTotal = (999 - used).coerceAtLeast(0)
        if (maxTotal == 0) {
            showError("分身数量已满", "最多只能有 999 个分身，请先卸载一些再试。")
            return
        }
        val maxCopies = minOf(MAX_SINGLE_COPIES, maxTotal)

        val view = DialogCloneNameBinding.inflate(layoutInflater)
        view.appTitle.text = app.label

        // 加固壳：不阻止创建，只把「装了可能卡启动页」提前说清楚
        val hardening = HardeningDetector.peek(app.pkg) ?: app.hardening
        if (hardening != null) {
            view.warnHint.text = getString(R.string.hardening_hint_one, hardening)
            view.warnHint.visibility = View.VISIBLE
        } else {
            view.warnHint.visibility = View.GONE
        }

        // 默认名按该应用已有的分身数量编号，不再用全局序号（否则会冒出「分身7」这种怪名字）
        val defaultName = CloneStore(this).defaultName(app.label, app.pkg)
        val baseName = app.label.take(12)
        var touched = false
        var programmatic = false
        var customNames: List<String>? = null

        fun setInput(text: String) {
            programmatic = true
            view.nameInput.setText(text)
            // 光标位置要按过滤后的实际长度算：maxLength 可能把默认名截短，
            // 直接 setSelection(text.length) 会抛 IndexOutOfBounds
            view.nameInput.setSelection(view.nameInput.text?.length ?: 0)
            programmatic = false
        }

        fun currentBase(): String {
            val typed = view.nameInput.text?.toString()?.trim().orEmpty()
            return typed.ifEmpty { baseName }
        }

        fun autoNames(base: String, n: Int): List<String> =
            if (n <= 1) listOf(base) else (1..n).map { "$base · 分身$it" }

        fun currentNames(n: Int): List<String> =
            customNames?.takeIf { it.size == n } ?: autoNames(currentBase(), n)

        fun renderPreview(n: Int) {
            val names = currentNames(n)
            view.namePreview.text = if (names.size == 1) {
                getString(R.string.name_preview_one, names[0])
            } else {
                getString(R.string.name_preview_many, names[0], names[1], names.size)
            }
        }

        var copies = 1
        view.nameInput.doAfterTextChanged {
            if (!programmatic) {
                touched = true
                // 改了基础名，之前逐份定好的名字就作废，避免预览和输入框对不上
                customNames = null
            }
            renderPreview(copies)
        }
        setInput(defaultName)

        view.slider.valueFrom = 1f
        view.slider.valueTo = maxCopies.toFloat()
        view.slider.value = 1f

        val sourceSize = StorageCheck.sourceApkSize(this, app.pkg)
        // 按钮文字要跟着份数变，先留个引用，show 之后才拿得到
        var posButton: Button? = null
        fun render(n: Int) {
            copies = n
            if (customNames != null && customNames!!.size != n) customNames = null
            if (n == 1) {
                view.nameLayout.hint = getString(R.string.clone_name)
                if (!touched) setInput(defaultName)
                view.countLabel.setText(R.string.clone_count_one)
                view.countHint.setText(R.string.clone_count_hint_one)
            } else {
                view.nameLayout.hint = getString(R.string.clone_name_prefix)
                if (!touched) setInput(baseName)
                view.countLabel.text = getString(R.string.clone_count_many, n)
                view.countHint.setText(R.string.clone_count_hint_many)
            }
            view.perCopyBtn.visibility = if (n > 1) View.VISIBLE else View.GONE
            renderPreview(n)
            applySpaceHint(
                view.spaceHint,
                StorageCheck.estimateNeeded(sourceSize, n),
                StorageCheck.freeBytes(this)
            )
            posButton?.text = if (n == 1) "创建" else "创建 $n 个"
        }

        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_clone_title)
            .setView(view.root)
            .setPositiveButton("创建", null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.show()

        posButton = dlg.getButton(DialogInterface.BUTTON_POSITIVE)
        view.slider.addOnChangeListener { _, value, _ -> render(value.toInt()) }
        render(1)

        view.perCopyBtn.setOnClickListener {
            val n = copies
            if (n < 2) return@setOnClickListener
            NamingDialog.show(
                this,
                getString(R.string.per_copy_title, app.label),
                currentNames(n)
            ) { names ->
                customNames = names
                touched = true
                renderPreview(copies)
            }
        }

        posButton?.setOnClickListener {
            val n = copies
            val typed = view.nameInput.text?.toString()?.trim().orEmpty()
            if (typed.isEmpty()) {
                toast("名称不能为空")
                return@setOnClickListener
            }
            if (n == 1) {
                dlg.dismiss()
                create(app, typed)
            } else {
                val names = if (customNames?.size == n) customNames!! else autoNames(typed, n)
                dlg.dismiss()
                startBatch(listOf(app to names))
            }
        }
    }

    /**
     * 单份创建。
     *
     * 「转圈」只说「在忙」，说不清还要多久 —— 一个 150MB 的应用要四十秒。
     * 所以用带百分比的进度弹窗：阶段（准备 / 打包 / 签名 / 自检 / 安装）+ 已处理字节数，
     * 等待变成可预期的事。
     */
    private fun create(app: AppEntry, name: String) {
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val dlg = com.huanzhi.manager.ui.ProgressDialog(this)
        progressDlg = dlg
        dlg.show(
            title = getString(R.string.progress_create, name),
            hint = getString(R.string.progress_hint_install),
            onCancel = { cancel.set(true) },
        )
        lifecycleScope.launch {
            try {
                val rec = workflow.createAndInstall(
                    app.pkg, app.label, name,
                    BuildControl(onProgress = dlg::update, isCancelled = { cancel.get() }),
                )
                dlg.finish(getString(R.string.progress_submitted))
                // 让「100% 已提交」这一帧被看到，再把界面交给系统安装框
                kotlinx.coroutines.delay(320)
                toast("${rec.label} 已生成，请在系统弹窗中确认安装")
                finish()
            } catch (e: BuildCancelledException) {
                toast(getString(R.string.cancelled))
            } catch (t: Throwable) {
                showError("生成失败", t.message ?: t.javaClass.simpleName)
            } finally {
                dlg.dismiss()
                progressDlg = null
            }
        }
    }

    // ---------------- 批量创建 ----------------

    /**
     * 批量创建。
     *
     * [presetCounts] 不为空表示份数已经在上一个界面定好了（平板右栏的步进器），
     * 这里只负责命名：隐藏统一份数的滑块，每行按自己的份数展开名字。
     * 为空时（手机）保持老做法 —— 一个滑块统一定份数。
     *
     * 为什么不统一：平板右栏存在的意义就是「淘宝 2 份、微信 1 份」这种混搭，
     * 弹窗再拿一个滑块把它们抹平，前面那一步就白做了。
     */
    private fun showBatchDialog(presetCounts: Map<String, Int>?) {
        if (selected.isEmpty()) {
            toast("先勾选要分身的应用")
            return
        }
        if (!ApkInstaller.canInstall(this)) {
            toast("请先允许「安装未知应用」")
            return
        }

        // 用全量列表而不是当前过滤结果：搜索状态下勾选的几个应用，
        // 不会因为输入框里还留着关键字就悄悄漏建
        val choices = selected.keys.mapNotNull { byPkg[it] }
        if (choices.isEmpty()) return

        val store = CloneStore(this)
        val used = store.all().size
        val maxTotal = (999 - used).coerceAtLeast(0)
        if (maxTotal == 0) {
            showError("分身数量已满", "最多只能有 999 个分身，请先卸载一些再试。")
            return
        }
        val maxCopies = (maxTotal / choices.size.coerceAtLeast(1))
            .coerceIn(1, MAX_BATCH_COPIES)

        val view = DialogBatchBinding.inflate(layoutInflater)
        view.summary.text = getString(R.string.selected_apps, choices.size)
        view.subtitle.text = choices.joinToString("、") { it.label }

        val hardened = choices.filter {
            (HardeningDetector.peek(it.pkg) ?: it.hardening) != null
        }
        if (hardened.isNotEmpty()) {
            val names = hardened.take(3).joinToString("、") { it.label } +
                if (hardened.size > 3) " 等 ${hardened.size} 个应用" else ""
            view.warnHint.text = getString(R.string.hardening_hint_batch, names)
            view.warnHint.visibility = View.VISIBLE
        } else {
            view.warnHint.visibility = View.GONE
        }

        val sourceSizes = choices.associate { it.pkg to StorageCheck.sourceApkSize(this, it.pkg) }
        val uniform = presetCounts == null
        var uniformCopies = minOf(2, maxCopies)
        var programmatic = false

        class Row(val app: AppEntry, val v: ItemNameEditBinding) {
            /** 这一行的份数。统一模式下由滑块改，预置模式下由上一屏定死 */
            var count: Int = 1
            /** 逐份定好的名字；null 表示按基础名自动编号 */
            var custom: List<String>? = null
            /** 用户是否手动改过基础名（改过就不再被份数变化覆盖） */
            var touched = false
        }

        fun autoNames(base: String, n: Int): List<String> =
            if (n <= 1) listOf(base) else (1..n).map { "$base · 分身$it" }

        fun baseOf(row: Row): String =
            row.v.rowNameInput.text?.toString()?.trim().orEmpty()
                .ifEmpty { row.app.label.take(12) }

        fun namesOf(row: Row, n: Int): List<String> =
            row.custom?.takeIf { it.size == n } ?: autoNames(baseOf(row), n)

        fun setInput(row: Row, text: String) {
            programmatic = true
            row.v.rowNameInput.setText(text)
            row.v.rowNameInput.setSelection(row.v.rowNameInput.text?.length ?: 0)
            programmatic = false
        }

        fun renderRow(row: Row) {
            val names = namesOf(row, row.count)
            row.v.rowPreview.text = "→ " + names.take(3).joinToString("、") +
                if (names.size > 3) " … 共 ${names.size} 个" else ""
            row.v.rowPerCopy.visibility = if (row.count > 1) View.VISIBLE else View.GONE
        }

        fun defaultFor(app: AppEntry, n: Int): String =
            if (n == 1) store.defaultName(app.label, app.pkg) else app.label.take(12)

        val rows = choices.map { app ->
            val v = ItemNameEditBinding.inflate(layoutInflater, view.namesHost, false)
            v.rowTitle.text = app.label
            view.namesHost.addView(v.root)
            val row = Row(app, v)
            row.count = (presetCounts?.get(app.pkg) ?: uniformCopies).coerceIn(1, maxCopies)
            setInput(row, defaultFor(app, row.count))

            v.rowNameInput.doAfterTextChanged {
                if (!programmatic) {
                    row.touched = true
                    row.custom = null
                }
                renderRow(row)
            }
            v.rowPerCopy.setOnClickListener {
                if (row.count < 2) return@setOnClickListener
                NamingDialog.show(
                    this,
                    getString(R.string.per_copy_title, app.label),
                    namesOf(row, row.count)
                ) { names ->
                    row.custom = names
                    row.touched = true
                    renderRow(row)
                }
            }
            row
        }

        var posButton: Button? = null

        fun renderAll() {
            val total = rows.sumOf { it.count }
            view.countLabel.text = if (uniform) {
                "每个应用生成 $uniformCopies 份 · 共 $total 个分身"
            } else {
                "共 $total 个分身"
            }
            view.countLabel.setTextColor(
                if (total > maxTotal) 0xFFB3261E.toInt() else
                    MaterialColors.getColor(
                        view.countLabel, com.google.android.material.R.attr.colorOnSurface
                    )
            )
            val need = rows.sumOf { StorageCheck.estimateNeeded(sourceSizes[it.app.pkg] ?: 0L, it.count) }
            applySpaceHint(view.spaceHint, need, StorageCheck.freeBytes(this))

            // 份数变了：没手改过的行跟着恢复默认，逐份定过的作废（数量对不上了）
            rows.forEach { row ->
                if (row.custom != null && row.custom!!.size != row.count) row.custom = null
                if (!row.touched) setInput(row, defaultFor(row.app, row.count))
                renderRow(row)
            }
            posButton?.text = if (total == 1) "创建" else "创建 $total 个"
        }

        view.slider.valueFrom = 1f
        view.slider.valueTo = maxCopies.toFloat()
        view.slider.value = uniformCopies.toFloat()
        if (uniform) {
            view.slider.addOnChangeListener { _, value, _ ->
                uniformCopies = value.toInt()
                rows.forEach { if (it.count != uniformCopies) it.count = uniformCopies }
                renderAll()
            }
        } else {
            // 份数已经在上一屏定过了（每行可能不同），这里再放一个「统一份数」滑块
            // 会让人以为一拖就能改全部，实际会把之前的设置抹掉
            view.slider.visibility = View.GONE
            // 这两段说明（独立 APK、每份都要确认；每行可单独改名）在选择页右栏
            // 和每行下面的预览里已经表达过了。平板只有 768dp 高，留着它们会把
            // 「预计占用」那一行顶出弹窗
            view.note.visibility = View.GONE
            view.namesHint.visibility = View.GONE
            (view.countLabel.layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin = dp(12)
        }

        // 名称行是按选中应用逐行加的，选五个应用时内容会高过弹窗上限。
        // **不要**自己再包一层 ScrollView：MaterialAlertDialog 在内容超高时会自己套一层，
        // 外面再套一层的话手势会被里层吃掉（里层没有可滚范围），看上去就是"内容被截断、
        // 而且划不动"。交给它自己处理，再顺手把这一屏用不到的说明收起来。
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.batch_title)
            .setView(view.root)
            .setPositiveButton(R.string.batch_create, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.show()
        posButton = dlg.getButton(DialogInterface.BUTTON_POSITIVE)
        renderAll()

        dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val total = rows.sumOf { it.count }
            if (total > maxTotal) {
                toast("超出上限，最多还能创建 $maxTotal 个")
                return@setOnClickListener
            }
            val entries = ArrayList<Pair<AppEntry, List<String>>>(rows.size)
            for (row in rows) {
                val names = namesOf(row, row.count)
                if (names.any { it.isBlank() }) {
                    toast(getString(R.string.name_empty))
                    return@setOnClickListener
                }
                entries += row.app to names
            }
            dlg.dismiss()
            startBatch(entries)
        }
    }

    private fun startBatch(entries: List<Pair<AppEntry, List<String>>>) {
        // 分身是整包复制，五份大应用很容易把空间吃光；空间不够时系统只会回一句「安装失败」，
        // 所以先在开建前把账算清楚，让用户自己决定
        val need = entries.sumOf { (app, names) ->
            StorageCheck.estimateNeeded(StorageCheck.sourceApkSize(this, app.pkg), names.size)
        }
        val free = StorageCheck.freeBytes(this)
        if (need > free) {
            glassAlert(
                title = "空间可能不够",
                message = "这一批预计需要 ${Formats.size(need)}，当前可用 ${Formats.size(free)}。" +
                    "\n\n空间不足时安装会被系统中断，建议先减少份数或清理空间。",
                confirmLabel = "仍然继续",
                cancelLabel = getString(R.string.cancel),
            ) { launchBatch(entries) }
            return
        }
        launchBatch(entries)
    }

    private fun launchBatch(entries: List<Pair<AppEntry, List<String>>>) {
        val store = CloneStore(this)
        // 逐份命名允许用户把几份起成同一个名字，这里统一兜底：撞了就加 (2)
        val usedNames = store.all().mapTo(HashSet()) { it.label }
        val plans = entries.map { (app, rawNames) ->
            val names = rawNames.map { store.uniqueName(it, usedNames) }
            BatchRunner.Plan(app.pkg, app.label, names.size, names)
        }

        selected.clear()
        push()

        BatchRunner.onProgress = ::renderProgress
        BatchRunner.onDone = ::onBatchDone
        showProgressDialog()
        renderProgress(BatchRunner.Progress(0, plans.sumOf { it.copies }, "准备中…"))
        BatchRunner.start(this, plans)
    }

    private fun showProgressDialog() {
        if (progressDlg?.isShowing == true) return
        val dlg = com.huanzhi.manager.ui.ProgressDialog(this)
        progressDlg = dlg
        // 批量中途停下会把队列留在半截状态（前几份装好、后几份搁置），
        // 所以这里不提供取消，只负责把「到第几份、在做什么」讲清楚
        dlg.show(title = getString(R.string.batch_progress_title), onCancel = null)
    }

    private fun renderProgress(p: BatchRunner.Progress) {
        progressDlg?.update((p.fraction * 100).toInt(), p.text)
    }

    private fun dismissProgress() {
        progressDlg?.dismiss()
        progressDlg = null
    }

    private fun onBatchDone(s: BatchRunner.Summary) {
        dismissProgress()
        BatchRunner.clearSummary()

        val sb = StringBuilder()
        sb.append("成功创建并安装 ${s.ok} 个分身。")
        if (s.cancelled) {
            sb.append("\n\n已在系统弹窗上取消，后续未创建。")
            sb.append("已经生成的包留在列表里，显示为「未安装」，随时可以点它补装。")
        }
        val common = s.commonReason
        if (s.failed.isNotEmpty()) {
            if (common != null) {
                // 五条一模一样的原因没必要念五遍，说一次再说说是哪几份
                sb.append("\n\n${s.failed.size} 份都是同一个原因：")
                sb.append(common)
                sb.append("\n\n涉及：")
                sb.append(s.failed.joinToString("、") { it.name })
            } else {
                sb.append("\n\n未完成的项：")
                s.failed.take(8).forEach {
                    sb.append("\n· ").append(it.name).append("：").append(it.reason)
                }
                if (s.failed.size > 8) sb.append("\n… 另有 ${s.failed.size - 8} 项")
            }
            hintFor(common)?.let { sb.append("\n\n").append(it) }
        }

        glassAlert(
            title = getString(R.string.batch_done_title),
            message = sb.toString(),
            confirmLabel = getString(R.string.done),
            neutralLabel = if (s.failed.isNotEmpty()) "复制详情" else null,
            dismissible = false,
            onNeutral = { copyDiagnostics(s) },
        ) { finish() }
    }

    /**
     * 原因不确定时，让用户能把原始状态一次性复制出来。
     * 光看「安装失败」谁也判断不了，日志里才有 INSTALL_FAILED_* 这类真凭实据。
     */
    private fun copyDiagnostics(s: BatchRunner.Summary) {
        val text = buildString {
            append("幻之分身 ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")")
            append(" · Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")")
            append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append('\n')
            if (s.failed.isNotEmpty()) {
                append("失败项：\n")
                s.failed.forEach { append("· ").append(it.name).append("：").append(it.reason).append('\n') }
            }
            append("\n安装日志：\n")
            append(InstallLog.tail(this@AppPickerActivity, 40))
        }
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("幻之分身诊断", text))
        toast("已复制到剪贴板")
    }

    /** 常见原因给一句「接下来怎么办」 */
    private fun hintFor(reason: String?): String? = when {
        reason == null -> null
        reason.contains("权限与原应用重名") ->
            "分身与原应用声明了同名权限，系统不允许重复声明。1.4 起会自动改名，更新后重新创建即可。"
        reason.contains("存储空间不足") ->
            "先用设置里的「清除缓存」，或卸载不用的分身，再减少每批份数。"
        reason.contains("签名与已装版本冲突") ->
            "这个包名装过另一份签名不同的版本，先卸载它再装。"
        reason.contains("没收到安装结果") ->
            "系统确认框要手动点「安装」。没等到结果的那几份可能稍后才装上，回到列表看状态即可；" +
                "显示「未安装」的点一下会重新生成并安装。"
        reason.contains("无法提交安装") ->
            "检查是否允许了「安装未知应用」，以及存储是否可写。"
        reason.contains("冲突") ->
            "同一包名的旧分身还装在系统里（记录可能已删）。先卸载它，或让工具重新分配序号后再试。"
        reason.contains("分片") -> "分片安装的应用暂不支持，需要换成完整 APK 再试。"
        else -> null
    }

    /** 「预计占用 X，当前可用 Y」；不够用时标红，别等装到一半才发现没空间 */
    private fun applySpaceHint(tv: TextView, need: Long, free: Long) {
        if (need <= 0L) {
            tv.text = ""
            return
        }
        if (need > free) {
            tv.text = "预计需要 ${Formats.size(need)}，当前可用 ${Formats.size(free)}：空间可能不够"
            tv.setTextColor(0xFFB3261E.toInt())
        } else {
            tv.text = "预计占用 ${Formats.size(need)}，当前可用 ${Formats.size(free)}"
            tv.setTextColor(
                MaterialColors.getColor(
                    tv, com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            )
        }
    }

    private fun showError(title: String, message: String) {
        glassAlert(
            title = title,
            message = message,
            confirmLabel = "知道了",
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** 单个应用一次最多生成几份：再多安装确认框会点到手酸，也吃存储 */
        private const val MAX_SINGLE_COPIES = 10

        /** 跨应用批量时的份数上限（也是平板右栏步进器的上限） */
        private const val MAX_BATCH_COPIES = 5

        private const val KEY_QUERY = "picker.query"
        private const val KEY_FILTER = "picker.filter"
        private const val KEY_MULTI = "picker.multi"
        private const val KEY_PKGS = "picker.pkgs"
        private const val KEY_COPIES = "picker.copies"
    }
}
