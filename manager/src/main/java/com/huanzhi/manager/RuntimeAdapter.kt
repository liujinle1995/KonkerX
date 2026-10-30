package com.huanzhi.manager

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.huanzhi.manager.core.AppScanner
import com.huanzhi.manager.core.CloneRuntime
import com.huanzhi.manager.databinding.ItemRuntimeBinding
import com.huanzhi.manager.util.Formats

class RuntimeAdapter(private val ctx: Context) :
    RecyclerView.Adapter<RuntimeAdapter.VH>() {

    private var items: List<CloneRuntime> = emptyList()

    /**
     * 页面每 3 秒刷一次，用 DiffUtil 只重绑变了的行，
     * 否则图标每 3 秒重新加载一次，肉眼能看出闪。
     */
    fun submit(list: List<CloneRuntime>) {
        val old = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) =
                old[o].record.clonePkg == list[n].record.clonePkg

            override fun areContentsTheSame(o: Int, n: Int) = old[o] == list[n]
        })
        items = list
        diff.dispatchUpdatesTo(this)
    }

    class VH(val b: ItemRuntimeBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemRuntimeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]
        val b = holder.b

        b.name.text = it.record.label
        b.pkg.text = "${it.record.clonePkg} · UID ${if (it.uid >= 0) it.uid else "—"}"

        // 图标沿用目标应用的图标，一眼能认出这是谁的分身
        b.icon.setImageDrawable(AppScanner.iconOf(ctx, it.record.targetPkg))

        // ---- 状态徽标 ----
        // 没有 usage 数据（未授权）时不要谎称「从未启动」，那是判不出来，不是没启动过
        val lastUsed = it.usage?.lastUsedMs ?: 0L
        val recent = lastUsed > 0 && System.currentTimeMillis() - lastUsed < 10 * 60_000L
        val (color, text) = when {
            !it.installed -> MISSING_COLOR to ctx.getString(R.string.runtime_state_missing)
            it.inForeground -> RUNNING_COLOR to ctx.getString(R.string.runtime_state_foreground)
            it.processes.isNotEmpty() -> RUNNING_COLOR to ctx.getString(R.string.runtime_state_running)
            it.usage == null -> IDLE_COLOR to ctx.getString(R.string.runtime_state_unknown)
            recent -> RECENT_COLOR to ctx.getString(R.string.runtime_state_recent)
            lastUsed > 0L -> IDLE_COLOR to ctx.getString(R.string.runtime_state_used_before)
            else -> IDLE_COLOR to ctx.getString(R.string.runtime_state_never)
        }
        val pill = ContextCompat.getDrawable(ctx, R.drawable.bg_badge)!!.mutate() as GradientDrawable
        pill.setColor(ColorUtils.setAlphaComponent(color, 0x2E))
        b.badge.background = pill
        b.badge.setTextColor(color)
        b.badge.text = text

        // ---- 存储占用 ----
        b.metrics.text = when {
            !it.installed -> "记录还在，但应用已被卸载"
            it.storage != null -> ctx.getString(
                R.string.runtime_occupied, Formats.size(it.storage.total)
            ) + " · " +
                ctx.getString(R.string.runtime_split_data, Formats.size(it.storage.dataBytes)) +
                " · " +
                ctx.getString(R.string.runtime_split_cache, Formats.size(it.storage.cacheBytes))
            else -> ctx.getString(R.string.runtime_apk_size, Formats.size(it.apkBytes))
        }

        // ---- 第二行：有进程数据就报内存，否则退一步显示上次启动时间 ----
        when {
            it.processes.isNotEmpty() -> {
                b.memLine.visibility = View.VISIBLE
                b.memLine.setTextColor(
                    MaterialColors.getColor(b.memLine, com.google.android.material.R.attr.colorPrimary)
                )
                b.memLine.text = ctx.getString(
                    R.string.runtime_mem_line,
                    it.processes.size,
                    Formats.size(it.totalRssKb * 1024)
                )
            }
            lastUsed > 0L -> {
                b.memLine.visibility = View.VISIBLE
                b.memLine.setTextColor(
                    MaterialColors.getColor(b.memLine, com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
                b.memLine.text = "上次启动 ${Formats.relative(lastUsed)}"
            }
            else -> b.memLine.visibility = View.GONE
        }
    }

    private companion object {
        const val RUNNING_COLOR = 0xFF16C79A.toInt()
        const val RECENT_COLOR = 0xFF42A5F5.toInt()
        const val IDLE_COLOR = 0xFF8A8F98.toInt()
        const val MISSING_COLOR = 0xFFFF8A65.toInt()
    }
}
