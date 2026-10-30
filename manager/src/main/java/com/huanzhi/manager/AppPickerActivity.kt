package com.huanzhi.manager

import com.huanzhi.manager.ui.adaptive.WindowSizeClasses

import android.content.ClipData
import android.content.ClipboardManager
import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.huanzhi.manager.core.ApkInstaller
import com.huanzhi.manager.core.AppEntry
import com.huanzhi.manager.core.AppScanner
import com.huanzhi.manager.core.BuildCancelledException
import com.huanzhi.manager.core.BuildControl
import com.huanzhi.manager.core.BatchRunner
import com.huanzhi.manager.core.CloneStore
import com.huanzhi.manager.core.CloneWorkflow
import com.huanzhi.manager.core.HardeningDetector
import com.huanzhi.manager.core.InstallLog
import com.huanzhi.manager.core.Pinyin
import com.huanzhi.manager.core.SettingsStore
import com.huanzhi.manager.core.StorageCheck
import com.huanzhi.manager.core.TemplateSpec
import com.huanzhi.manager.databinding.ActivityPickerBinding
import com.huanzhi.manager.databinding.DialogBatchBinding
import com.huanzhi.manager.databinding.DialogCloneNameBinding
import com.huanzhi.manager.databinding.ItemNameEditBinding
import com.huanzhi.manager.ui.AppIndexBar
import com.huanzhi.manager.ui.motion.Curve
import com.huanzhi.manager.ui.motion.Dur
import com.huanzhi.manager.ui.motion.appear
import com.huanzhi.manager.ui.motion.cascade
import com.huanzhi.manager.ui.motion.disappear
import com.huanzhi.manager.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class AppPickerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPickerBinding
    private lateinit var adapter: AppAdapter
    private lateinit var workflow: CloneWorkflow

    /** 全部可分身应用，按拼音排好序 */
    private var apps: List<AppEntry> = emptyList()

    /** 当前列表里真正显示的（搜索时是过滤后的子集） */
    private var shown: List<AppEntry> = emptyList()

    /** 右侧首字母索引条（含拖动跟踪与首字母气泡） */
    private lateinit var indexBar: AppIndexBar

    /** 交给 RecyclerView 的行：普通应用 + 一条「带加固壳」分组标题 */
    private var rows: List<AppRow> = emptyList()

    private var query = ""

    // 多选状态
    private var selectionMode = false
    private val selected = LinkedHashSet<String>()

    private var progressDlg: com.huanzhi.manager.ui.ProgressDialog? = null

    /** 列表是否已经级联播放过（只放一次，见 cascadeFirstList） */
    private var cascaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPickerBinding.inflate(layoutInflater)
        setContentView(b.root)

        workflow = CloneWorkflow(this)
        adapter = AppAdapter(::onAppClick, ::onAppLongClick)
        val columns = WindowSizeClasses.pickerColumns(this)
        val lm = GridLayoutManager(this, columns)
        // 分组标题要占满整行；平板上两列时，否则标题只会占左边半格，看着像漏了一列
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (rows.getOrNull(position) is AppRow.Header) columns else 1
        }
        b.list.layoutManager = lm
        b.list.adapter = adapter
        b.toolbar.setNavigationOnClickListener { finish() }

        b.toolbar.inflateMenu(R.menu.menu_picker)
        b.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_multi) {
                toggleSelectionMode()
                true
            } else {
                false
            }
        }

        b.batchGo.setOnClickListener { showBatchDialog() }
        applySelectionState()

        b.searchInput.doAfterTextChanged { editable ->
            val next = editable?.toString().orEmpty()
            if (next == query) return@doAfterTextChanged
            query = next
            applyFilter()
        }

        indexBar = AppIndexBar(b.indexBar, b.indexBubble, b.list)
        indexBar.setup()
        load()
    }

    override fun onResume() {
        super.onResume()
        // 从设置页回来时字号档位可能变了。索引条是一串动态加的 TextView，
        // 没有「改一下 textSize」的快捷路径，重建一次最省心（字母数也就二三十个）
        if (query.isBlank() && rows.isNotEmpty() &&
            SettingsStore.indexTextSp(this) != indexBar.currentSp
        ) {
            indexBar.setRows(rows)
        }
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
            // 出去了一趟，回来时批量已经结束：补一次汇总
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

    // ---------------- 列表 ----------------

    private fun load() {
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                // 排序要用 ICU 转拼音，几百个应用放在主线程会明显卡首帧，挪到 IO
                Pinyin.sortByLabel(
                    AppScanner.launchableApps(this@AppPickerActivity)
                        .filter { it.pkg.toByteArray().size <= TemplateSpec.MAX_TARGET_PKG_LEN }
                )
            }
            // 加固探测必须放在「显示之前」：它的结果决定这一行排在哪里，
            // 如果像以前那样先显示再逐步回填，列表会在用户眼皮底下自己往下跳
            val probed = withContext(Dispatchers.IO) { probeHardening(found) }
            apps = probed
            b.progress.visibility = View.GONE
            if (probed.isEmpty()) toast("没有找到可分身的三方应用")
            applyFilter()
        }
    }

    /**
     * 逐个应用探测加固壳，返回带上 brand 的列表。
     *
     * 并行跑：每个应用要打开一次 ZipFile 读中央目录（几十毫秒），串行扫两百个应用
     * 就是一整个能感知到的等待。探测结果在 [HardeningDetector] 里按 versionCode 缓存，
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

    /** 按搜索词过滤、分组，然后把行交给列表 */
    private fun applyFilter() {
        val q = query.trim()
        shown = if (q.isEmpty()) apps else apps.filter { matches(it, q) }
        rows = groupRows(shown)
        adapter.submit(rows)
        cascadeFirstList(rows)

        val searching = q.isNotEmpty()
        b.searchEmpty.visibility =
            if (searching && shown.isEmpty() && apps.isNotEmpty()) View.VISIBLE else View.GONE
        if (searching) {
            b.indexBar.visibility = View.GONE
        } else {
            indexBar.setRows(rows)
        }
    }

    /**
     * 把已排好序的应用切成「普通 → 分组标题 → 带加固壳」两段。
     *
     * 为什么要把带壳的挪到最下面：这类应用装上去大概率卡在启动页，
     * 对绝大多数使用场景是噪音。留在原位会和能用的应用混在一起，
     * 用户点进去、等四十秒、装完、启动卡住，才知道选错了。
     *
     * 两段内部都保持传入顺序（拼音 A–Z），所以并没有破坏排序，只是分了个区。
     */
    private fun groupRows(list: List<AppEntry>): List<AppRow> {
        if (list.isEmpty()) return emptyList()
        val plain = list.filter { it.hardening == null }
        val hardened = list.filter { it.hardening != null }
        val out = ArrayList<AppRow>(list.size + 1)
        plain.forEach { out.add(AppRow.Item(it)) }
        if (hardened.isNotEmpty()) {
            out.add(
                AppRow.Header(
                    title = getString(R.string.hardening_group_title, hardened.size),
                    hint = getString(R.string.hardening_group_hint),
                )
            )
            hardened.forEach { out.add(AppRow.Item(it)) }
        }
        return out
    }

    /**
     * 列表第一次从空变成有内容时，让前几项依次浮起。
     *
     * 只放一次：搜索过滤每次都重放的话，每敲一个字整列都会跳一遍，
     * 那是干扰，不是「流畅」。
     */
    private fun cascadeFirstList(list: List<AppRow>) {
        if (cascaded || list.isEmpty()) return
        cascaded = true
        b.list.post { b.list.cascade(startDelay = 40L) }
    }

    /**
     * 搜索命中四种写法：原名、拼音全拼、拼音首字母、包名。
     * 首字母用 startsWith 而不是 contains —— 打「wx」找微信是直觉，
     * 但 contains 会把「adwxa」这类乱序也放进来，结果反而变吵。
     */
    private fun matches(app: AppEntry, q: String): Boolean {
        val lower = q.lowercase(Locale.ROOT)
        if (app.label.lowercase(Locale.ROOT).contains(lower)) return true
        if (app.pkg.lowercase(Locale.ROOT).contains(lower)) return true
        val key = Pinyin.keyOf(app.label)
        return key.full.contains(lower) || key.initials.startsWith(lower)
    }

    private fun onAppClick(app: AppEntry) {
        if (selectionMode) {
            if (!selected.remove(app.pkg)) selected.add(app.pkg)
            applySelectionState()
            return
        }
        askClone(app)
    }

    private fun onAppLongClick(app: AppEntry) {
        if (!selectionMode) {
            selectionMode = true
            selected.add(app.pkg)
            applySelectionState()
        }
    }

    // ---------------- 多选 ----------------

    private fun toggleSelectionMode() {
        selectionMode = !selectionMode
        if (!selectionMode) selected.clear()
        applySelectionState()
    }

    private fun applySelectionState() {
        adapter.setSelectionMode(selectionMode)
        adapter.setSelected(selected.toSet())

        // 批量条从下方升起：它是「进入多选模式」的视觉确认，直接 GONE→VISIBLE 会显得突然
        if (selectionMode) {
            b.batchBar.appear(riseDp = 24f, duration = Dur.MEDIUM_3)
        } else {
            b.batchBar.disappear(Dur.SHORT_4)
        }
        b.selectionCount.text = getString(R.string.selected_apps, selected.size)
        b.batchGo.isEnabled = selected.isNotEmpty()
        b.toolbar.menu.findItem(R.id.action_multi)?.title =
            getString(if (selectionMode) R.string.done else R.string.multi_select)

        // 批量条浮在列表上，底部留出等高的空白，最后一项不会被挡住
        b.list.setPadding(
            b.list.paddingLeft,
            b.list.paddingTop,
            b.list.paddingRight,
            if (selectionMode) (88 * resources.displayMetrics.density).toInt() else
                (24 * resources.displayMetrics.density).toInt()
        )
    }

    // ---------------- 单个应用：1 份或多份 ----------------

    /**
     * 同一个弹窗既处理「建一个」也处理「建一批」。
     *
     * 名称框的语义跟着份数走：
     *   · 1 份 → 填什么就叫什么；
     *   · 多份 → 填的是基础名，实际名字自动接「· 分身1 / 2 / 3」；
     *   · 想给某一份单独起名，点「逐份」逐条改（1.9 起）。
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
        var posButton: android.widget.Button? = null
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
     * 以前这里只是把列表藏起来、显示一个转圈，再配一句 Toast。问题在于「转圈」只说
     * 「在忙」，说不清还要多久 —— 一个 150MB 的应用要四十秒，用户只能干等到不知道哪一刻。
     * 现在换成带百分比的进度弹窗：阶段（准备 / 打包 / 签名 / 自检 / 安装）+ 已处理字节数，
     * 等待变成了可预期的事。
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
     * 批量创建：份数统一，但**每个应用的名字都能单独改**（1.9 起）。
     *
     * 之前只有一个「统一前缀」，几个应用批量时要么全叫「测试 · 分身1」，
     * 要么被迫带上一串自动拼出来的应用名。现在每个应用一行输入框，
     * 多份时还能点「逐份」把某一批里的每一份都改成不同的名字。
     */
    private fun showBatchDialog() {
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
        val choices = apps.filter { it.pkg in selected }
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
        var copies = minOf(2, maxCopies)
        var programmatic = false

        class Row(val app: AppEntry, val v: ItemNameEditBinding) {
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
            val names = namesOf(row, copies)
            row.v.rowPreview.text = "→ " + names.take(3).joinToString("、") +
                if (names.size > 3) " … 共 ${names.size} 个" else ""
            row.v.rowPerCopy.visibility = if (copies > 1) View.VISIBLE else View.GONE
        }

        fun defaultFor(app: AppEntry, n: Int): String =
            if (n == 1) store.defaultName(app.label, app.pkg) else app.label.take(12)

        val rows = choices.map { app ->
            val v = ItemNameEditBinding.inflate(layoutInflater, view.namesHost, false)
            v.rowTitle.text = app.label
            view.namesHost.addView(v.root)
            val row = Row(app, v)
            setInput(row, defaultFor(app, copies))

            v.rowNameInput.doAfterTextChanged {
                if (!programmatic) {
                    row.touched = true
                    row.custom = null
                }
                renderRow(row)
            }
            v.rowPerCopy.setOnClickListener {
                if (copies < 2) return@setOnClickListener
                NamingDialog.show(
                    this,
                    getString(R.string.per_copy_title, app.label),
                    namesOf(row, copies)
                ) { names ->
                    row.custom = names
                    row.touched = true
                    renderRow(row)
                }
            }
            row
        }

        fun render(n: Int) {
            copies = n
            val total = choices.size * n
            view.countLabel.text = "每个应用生成 $n 份 · 共 $total 个分身"
            view.countLabel.setTextColor(
                if (total > maxTotal) 0xFFB3261E.toInt() else
                    MaterialColors.getColor(
                        view.countLabel, com.google.android.material.R.attr.colorOnSurface
                    )
            )
            val need = choices.sumOf { StorageCheck.estimateNeeded(sourceSizes[it.pkg] ?: 0L, n) }
            applySpaceHint(view.spaceHint, need, StorageCheck.freeBytes(this))

            // 份数变了：没手改过的行跟着恢复默认，逐份定过的作废（数量对不上了）
            rows.forEach { row ->
                if (row.custom != null && row.custom!!.size != n) row.custom = null
                if (!row.touched) setInput(row, defaultFor(row.app, n))
                renderRow(row)
            }
        }

        view.slider.valueFrom = 1f
        view.slider.valueTo = maxCopies.toFloat()
        view.slider.value = minOf(2, maxCopies).toFloat()
        render(copies)
        view.slider.addOnChangeListener { _, value, _ -> render(value.toInt()) }

        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.batch_title)
            .setView(view.root)
            .setPositiveButton(R.string.batch_create, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.show()

        dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val total = choices.size * copies
            if (total > maxTotal) {
                toast("超出上限，最多还能创建 $maxTotal 个")
                return@setOnClickListener
            }
            val entries = ArrayList<Pair<AppEntry, List<String>>>(rows.size)
            for (row in rows) {
                val names = namesOf(row, copies)
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
            MaterialAlertDialogBuilder(this)
                .setTitle("空间可能不够")
                .setMessage(
                    "这一批预计需要 ${Formats.size(need)}，当前可用 ${Formats.size(free)}。" +
                        "\n\n空间不足时安装会被系统中断，建议先减少份数或清理空间。"
                )
                .setPositiveButton("仍然继续") { _, _ -> launchBatch(entries) }
                .setNegativeButton(R.string.cancel, null)
                .show()
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
        selectionMode = false
        selected.clear()
        applySelectionState()

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

        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.batch_done_title)
            .setMessage(sb.toString())
            .setCancelable(false)
            .setPositiveButton(R.string.done) { _, _ -> finish() }
        if (s.failed.isNotEmpty()) {
            dlg.setNeutralButton("复制详情") { _, _ -> copyDiagnostics(s) }
        }
        dlg.show()
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
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        /** 单个应用一次最多生成几份：再多安装确认框会点到手酸，也吃存储 */
        private const val MAX_SINGLE_COPIES = 10

        /** 跨应用批量时的份数上限 */
        private const val MAX_BATCH_COPIES = 5

        /** 索引条末尾那个「跳到加固段」的入口。字母段全是 A–Z，不会撞 */
        private const val HARDENED_INDEX = '加'
    }
}
