package com.huanzhi.manager

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.huanzhi.manager.core.CloneRuntime
import com.huanzhi.manager.core.CloneStore
import com.huanzhi.manager.core.DeviceMemory
import com.huanzhi.manager.core.RuntimeProbe
import com.huanzhi.manager.databinding.ActivityRuntimeBinding
import com.huanzhi.manager.ui.motion.cascade
import com.huanzhi.manager.ui.motion.crossFade
import com.huanzhi.manager.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 分身运行概览。
 *
 * 页面只做一件事：把「现在有几个分身活着、各自吃了多少内存和存储」摆出来。
 * 数据每 3 秒刷一次（页面不可见时停掉），因为进程的内存是实时变化的，
 * 手动刷新跟不上；但频率也不能太高，一次扫描要读几百个 /proc/<pid>/status。
 */
class RuntimeActivity : AppCompatActivity() {

    private lateinit var b: ActivityRuntimeBinding
    private lateinit var adapter: RuntimeAdapter

    /** 卡片是否已经级联播放过（轮询页面，只放一次） */
    private var cascaded = false

    private data class Snapshot(
        val rows: List<CloneRuntime>,
        val mem: DeviceMemory
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRuntimeBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.inflateMenu(R.menu.menu_runtime)
        b.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_refresh) {
                lifecycleScope.launch { render(collect()) }
                true
            } else false
        }

        adapter = RuntimeAdapter(this)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    render(collect())
                    delay(REFRESH_MS)
                }
            }
        }
    }

    private suspend fun collect(): Snapshot = withContext(Dispatchers.IO) {
        val records = CloneStore(this@RuntimeActivity).all()
        val mem = RuntimeProbe.deviceMemory(this@RuntimeActivity)
        val rows = RuntimeProbe.snapshot(this@RuntimeActivity, records)
            // 运行中的排前面，其次按实际占用降序，没跑起来的沉底
            .sortedWith(
                compareByDescending<CloneRuntime> { it.running }
                    .thenByDescending { it.totalRssKb }
                    .thenBy { it.record.label }
            )
        Snapshot(rows, mem)
    }

    private fun render(s: Snapshot) {
        val rows = s.rows

        // 设备内存
        if (s.mem.totalBytes > 0) {
            b.memText.text = getString(
                R.string.runtime_used_of,
                Formats.size(s.mem.usedBytes),
                Formats.size(s.mem.totalBytes)
            )
            val pct = (s.mem.ratio * 100).roundToInt().coerceIn(0, 100)
            b.memBar.setProgressCompat(pct, true)
            val tight = s.mem.lowMemory || s.mem.ratio >= 0.9f
            b.memBar.setIndicatorColor(
                ContextCompat.getColor(this, if (tight) android.R.color.holo_red_dark else R.color.brand)
            )
            b.memAvail.text = getString(R.string.runtime_available, Formats.size(s.mem.availBytes)) +
                if (s.mem.lowMemory) " · 系统内存偏低" else ""
        } else {
            b.memText.text = "—"
            b.memBar.setProgressCompat(0, false)
            b.memAvail.text = ""
        }

        // 没授权时，存储与使用记录都拿不到，直接把入口摆出来
        val granted = RuntimeProbe.hasUsageAccess(this)
        b.grantCard.visibility = if (granted) View.GONE else View.VISIBLE
        b.grantButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        // 三个数字
        b.statClones.text = rows.size.toString()
        // 非 root 设备观测不到后台存活，这里统计的是「此刻在前台」的分身数
        b.statRunning.text = if (granted) rows.count { it.inForeground }.toString() else "—"
        b.statRss.text = Formats.size(rows.sumOf { it.occupiedBytes })

        // 列表
        // 每 3 秒刷一次，用 crossFade 的幂等判断兜住：状态没变就不重放动画
        if (rows.isEmpty()) {
            crossFade(b.list, b.empty)
        } else {
            crossFade(b.empty, b.list)
        }
        b.empty.text = getString(R.string.runtime_empty_title) + "\n\n" +
            getString(R.string.runtime_empty_hint)
        adapter.submit(rows)

        // 卡片只级联一次：这个页面是轮询刷新的，每 3 秒重放一遍就成了闪烁
        if (rows.isNotEmpty() && !cascaded) {
            cascaded = true
            b.list.post { b.list.cascade(startDelay = 40L) }
        }
    }

    private companion object {
        const val REFRESH_MS = 3000L
    }
}
