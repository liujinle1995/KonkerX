package com.huanzhi.manager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ScrollView
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
        // 这一页不再需要接管批量的进度：构建/安装全程都在独立的构建进度页上，
        // 从选应用页点下去的那一刻就交接过去了
    }

    override fun onDestroy() {
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

    /**
     * 没装权限时的统一出口。
     *
     * 这里原来只弹一句 toast，一秒钟就没了 —— 而「没有安装权限」恰恰是**新装之后的默认状态**：
     * 用户点哪一行都没反应，只会以为应用坏了。换成弹窗，直接给一个「去设置」。
     */
    private fun ensureInstallPermission(): Boolean {
        if (ApkInstaller.canInstall(this)) return true
        glassAlert(
            title = getString(R.string.install_permission_title),
            message = getString(R.string.install_permission_message),
            confirmLabel = getString(R.string.install_permission_action),
            cancelLabel = getString(R.string.install_permission_later),
        ) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
        }
        return false
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
        if (!ensureInstallPermission()) return

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
     * 走的是和批量完全相同的路径（[BatchRunner] + 构建进度页），只是队列里只有一份。
     * 以前单份是一条独立代码路（CloneWorkflow.createAndInstall + 进度弹窗），
     * 结果是同一个应用「建一份」和「建两份」看到的是两套界面 —— 用户没法把两次的经验
     * 互相套用，而这两件事的等待时间其实一模一样（都要重打一遍整包）。
     */
    private fun create(app: AppEntry, name: String) {
        val store = CloneStore(this)
        val usedNames = store.all().mapTo(HashSet()) { it.label }
        val plan = BatchRunner.Plan(
            targetPkg = app.pkg,
            targetLabel = app.label,
            copies = 1,
            names = listOf(store.uniqueName(name, usedNames)),
        )
        val need = StorageCheck.estimateNeeded(StorageCheck.sourceApkSize(this, app.pkg), 1)
        BuildActivity.start(
            this,
            BuildActivity.Request(
                plans = listOf(plan),
                sourcePkg = app.pkg,
                sourceLabel = app.label,
                copyCount = 1,
                estimatedBytes = need,
                freeBytes = StorageCheck.freeBytes(this),
                nameMode = "单个",
            ),
        )
        finish()
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
        if (!ensureInstallPermission()) return

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
        // 横屏手机只有 411dp 高：标题 + 内容 + 按钮塞不进一屏时，Material 那一层
        // 是直接裁掉超出部分（「共 N 个分身」被压成一条线）。矮窗口里自己套一层滚动，
        // 高窗口保持原样 —— 那里 Material 会自己处理，双层滚动会互相抢手势
        val body: View = if (resources.configuration.screenHeightDp < 480) {
            ScrollView(this).apply { addView(view.root) }
        } else {
            view.root
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.batch_title)
            .setView(body)
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
            ) { launchBatch(entries, need, free) }
            return
        }
        launchBatch(entries, need, free)
    }

    private fun launchBatch(
        entries: List<Pair<AppEntry, List<String>>>,
        need: Long,
        free: Long,
    ) {
        val store = CloneStore(this)
        // 逐份命名允许用户把几份起成同一个名字，这里统一兜底：撞了就加 (2)
        val usedNames = store.all().mapTo(HashSet()) { it.label }
        val plans = entries.map { (app, rawNames) ->
            val names = rawNames.map { store.uniqueName(it, usedNames) }
            BatchRunner.Plan(app.pkg, app.label, names.size, names)
        }

        selected.clear()
        push()

        BuildActivity.start(
            this,
            BuildActivity.Request(
                plans = plans,
                sourcePkg = entries.first().first.pkg,
                sourceLabel = entries.first().first.label,
                copyCount = plans.sumOf { it.copies },
                estimatedBytes = need,
                freeBytes = free,
                nameMode = "逐份独立命名",
            ),
        )
        // 选应用页的使命到此为止：构建进度页有自己的返回，回到这里只会让用户
        // 面对一个「已经选完、但没有理由再存在」的列表
        finish()
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
