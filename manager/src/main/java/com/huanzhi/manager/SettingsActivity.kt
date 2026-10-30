package com.huanzhi.manager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.huanzhi.manager.core.CacheCleaner
import com.huanzhi.manager.core.Changelog
import com.huanzhi.manager.core.CloneStore
import com.huanzhi.manager.core.InstallLog
import com.huanzhi.manager.core.Purge
import com.huanzhi.manager.core.SettingsStore
import com.huanzhi.manager.databinding.ActivitySettingsBinding
import com.huanzhi.manager.databinding.DialogLogBinding
import com.huanzhi.manager.databinding.DialogPayBinding
import com.huanzhi.manager.ui.ProgressDialog
import com.huanzhi.manager.ui.motion.Curve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    /** 正在做淡出→重建的交接，期间忽略重复点击 */
    private var switching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        // 因切主题而重建时，新窗口从透明淡入
        fadeInIfNeeded()

        b.toolbar.setNavigationOnClickListener { finish() }

        setupTheme()
        setupIndexSize()
        setupRows()
        setupReset()
    }

    override fun onResume() {
        super.onResume()
        refreshCacheSize()
        // 从系统那屏「激活设备管理器」回来、或刚卸了几个分身，都要重算
        refreshReset()
    }

    // ---------------- 外观 ----------------

    private fun setupTheme() {
        val group = b.themeCard.themeGroup
        group.check(
            when (SettingsStore.themeMode(this)) {
                SettingsStore.THEME_LIGHT -> R.id.themeLight
                SettingsStore.THEME_DARK -> R.id.themeDark
                else -> R.id.themeSystem
            }
        )
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || switching) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.themeLight -> SettingsStore.THEME_LIGHT
                R.id.themeDark -> SettingsStore.THEME_DARK
                else -> SettingsStore.THEME_SYSTEM
            }
            // 只有真的变了才切：setDefaultNightMode 会重建 Activity
            if (mode != SettingsStore.themeMode(this)) {
                switchTheme(mode)
            }
        }
    }

    /**
     * 索引字母大小。
     *
     * 这一档不重建 Activity：它只影响「选应用」页右侧索引条的字号，那边在 onResume
     * 里发现档位变了会自己重建索引条，不需要为了一个字号把整个设置页闪一下。
     */
    private fun setupIndexSize() {
        val group = b.indexSizeCard.indexSizeGroup
        group.check(
            when (SettingsStore.indexScale(this)) {
                SettingsStore.INDEX_SMALL -> R.id.indexSizeSmall
                SettingsStore.INDEX_LARGE -> R.id.indexSizeLarge
                else -> R.id.indexSizeNormal
            }
        )
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            SettingsStore.setIndexScale(
                this,
                when (checkedId) {
                    R.id.indexSizeSmall -> SettingsStore.INDEX_SMALL
                    R.id.indexSizeLarge -> SettingsStore.INDEX_LARGE
                    else -> SettingsStore.INDEX_NORMAL
                }
            )
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
     *
     * 淡的是 `decorView` 而不是内容根 View：连窗口背景一起淡，才能看到下面的主界面，
     * 只淡内容会露出主题底色，反而更闪。
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

    // ---------------- 各项入口 ----------------

    private fun setupRows() {
        b.rowCache.title.text = getString(R.string.clear_cache)
        b.rowCache.sub.text = "计算中…"
        b.rowCache.chevron.visibility = android.view.View.GONE
        b.rowCache.root.setOnClickListener { confirmClearCache() }

        b.rowUpdate.title.text = getString(R.string.version_and_updates)
        b.rowUpdate.sub.text =
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${Changelog.current.date}"
        b.rowUpdate.root.setOnClickListener { ChangelogDialog.show(this) }

        b.rowAbout.title.text = getString(R.string.plugin_info)
        b.rowAbout.sub.text = "怎么分身、能做到什么、做不到什么"
        b.rowAbout.root.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        b.rowLog.title.text = getString(R.string.install_log)
        b.rowLog.sub.text = getString(R.string.install_log_sub)
        b.rowLog.root.setOnClickListener { showLog() }

        b.rowSupport.title.text = getString(R.string.support_row_title)
        b.rowSupport.sub.text = getString(R.string.support_row_sub)
        b.rowSupport.root.setOnClickListener { showSupport() }
    }

    /**
     * 收款码。
     *
     * 为什么不做「点一下就跳微信直接付」：微信把这条路封死了，不是我们没找到 API。
     * 收款码里存的 `wxp://f2f0…` 是微信自家协议，微信支付社区对同一个问题的回复是
     * 「暂不支持直接拉起收银台」；唤起扫一扫的 `weixin://dl/scan` 从 2021 年起只对
     * 白名单（手机厂商自带的扫一扫）开放，第三方 App 调用是静默失败 —— 与其加一个
     * 点了没反应的按钮，不如把能做到的最短路给出来。
     *
     * 能做到的最短路就是「把图存进相册」：微信扫一扫自己的相册入口是可靠的，
     * 用户点两下就进付款页。保存走 MediaStore，API 29+ 写自己新建的图片不需要任何
     * 权限，也不内嵌 SDK、不联网、不做「已支付」校验 —— 这就是一张静态图片，
     * 扫码之后发生什么完全在微信里。
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
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            val out = resolver.openOutputStream(uri) ?: return@runCatching false
            out.use { resources.openRawResource(R.drawable.pay_wechat).use { it.copyTo(out) } }

            // 不清 IS_PENDING 的话这张图永远不会出现在相册里，微信自然也选不到
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrDefault(false)

        Toast.makeText(
            this,
            if (ok) R.string.support_saved else R.string.support_save_failed,
            Toast.LENGTH_LONG,
        ).show()
    }

    // ---------------- 诊断 ----------------

    /**
     * 「建了两个只装上一个」这种问题，光看界面是判断不了的：
     * 真正的原因藏在建包、提交、系统回执这三段里，所以要能直接看到。
     */
    private fun showLog() {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                InstallLog.tail(this@SettingsActivity, 200)
            }
            val view = DialogLogBinding.inflate(layoutInflater)
            view.logText.text = text.ifBlank { getString(R.string.install_log_empty) }
            val dlg = MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(R.string.install_log)
                .setView(view.root)
                .setPositiveButton(R.string.copy) { _, _ ->
                    val cm = getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("幻之分身安装日志", text))
                    toast("已复制到剪贴板")
                }
                .setNeutralButton("清空") { _, _ ->
                    InstallLog.file(this@SettingsActivity).delete()
                    toast("日志已清空")
                }
                .setNegativeButton(R.string.done, null)
                .create()
            dlg.show()
        }
    }

    // ---------------- 缓存 ----------------

    private fun refreshCacheSize() {
        lifecycleScope.launch {
            val stat = withContext(Dispatchers.IO) { CacheCleaner.scan(this@SettingsActivity) }
            b.rowCache.sub.text = if (stat.files == 0) {
                "当前没有占用"
            } else {
                "已占用 ${CacheCleaner.format(stat.bytes)} · ${stat.files} 个文件"
            }
        }
    }

    private fun confirmClearCache() {
        val stat = CacheCleaner.scan(this)
        val dead = CloneStore(this).deadCount()
        if (stat.files == 0 && dead == 0) {
            toast("缓存已经是空的")
            return
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
        MaterialAlertDialogBuilder(this)
            .setTitle("清除缓存")
            .setMessage(body)
            .setPositiveButton("清除") { _, _ -> doClear() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doClear() {
        lifecycleScope.launch {
            // 缓存清了但记录还在，是「清了却看不出变化」的根源：
            // 装不上的分身包已经删了，记录里那行「未安装」永远点不动，所以一起收掉。
            val (freed, pruned) = withContext(Dispatchers.IO) {
                val f = CacheCleaner.clear(this@SettingsActivity)
                f to CloneStore(this@SettingsActivity).pruneDead()
            }
            refreshCacheSize()
            val parts = buildList {
                if (freed > 0) add("已释放 ${CacheCleaner.format(freed)}")
                if (pruned > 0) add("清理了 $pruned 条失效记录")
            }
            toast(parts.joinToString("，").ifEmpty { "没有可清理的内容" })
        }
    }

    // ---------------- 清理与卸载 ----------------

    /**
     * 分身是本工具装进系统的独立 APK，卸载本应用不会带走它们。
     * 这一组入口就是补上这件事：要么只清分身，要么连本应用一起卸掉。
     *
     * 注意这里**做不到静默**：普通应用每卸一个包，系统都要用户点一次确认框。
     * 所以入口的职责是把 N 个卸载框排好队，而不是假装能一键清空。
     */
    private fun setupReset() {
        b.rowPurge.title.text = getString(R.string.purge_clones)
        b.rowPurge.root.setOnClickListener { confirmPurge() }

        b.rowUninstall.title.text = getString(R.string.purge_uninstall_self)
        b.rowUninstall.sub.text = getString(R.string.purge_uninstall_self_sub)
        // 不可逆动作，标题用错误色，跟上面那条明确拉开层级
        b.rowUninstall.title.setTextColor(
            MaterialColors.getColor(
                b.rowUninstall.title,
                com.google.android.material.R.attr.colorError
            )
        )
        b.rowUninstall.root.setOnClickListener { confirmUninstallSelf() }
    }

    private fun refreshReset() {
        lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) { Purge.snapshot(this@SettingsActivity) }
            if (isFinishing || isDestroyed) return@launch
            b.rowPurge.sub.text = when {
                snap.total == 0 && snap.records == 0 && snap.legacy.isEmpty() ->
                    getString(R.string.purge_clones_sub_none)
                // 旧版分身归不到这版管，但「这里还有几个分身」这件事必须让用户看见
                snap.legacy.isNotEmpty() ->
                    getString(R.string.purge_clones_sub_legacy, snap.total, snap.records, snap.legacy.size)
                else -> getString(R.string.purge_clones_sub, snap.total, snap.records)
            }
        }
    }

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
            val dlg = MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(R.string.purge_confirm_title)
                .setMessage(body)
                .setPositiveButton(R.string.purge_confirm_ok) { _, _ -> doPurge(uninstallAfter = false) }
                .setNegativeButton(R.string.cancel, null)
                .create()
            dlg.show()
            dlg.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(
                MaterialColors.getColor(
                    dlg.getButton(DialogInterface.BUTTON_POSITIVE),
                    com.google.android.material.R.attr.colorError
                )
            )
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
            val dlg = MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(R.string.purge_uninstall_confirm_title)
                .setMessage(body)
                .setPositiveButton(R.string.purge_uninstall_self) { _, _ ->
                    doPurge(uninstallAfter = true)
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
            dlg.show()
            dlg.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(
                MaterialColors.getColor(
                    dlg.getButton(DialogInterface.BUTTON_POSITIVE),
                    com.google.android.material.R.attr.colorError
                )
            )
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
            refreshReset()
            refreshCacheSize()
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
