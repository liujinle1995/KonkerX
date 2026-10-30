package com.huanzhi.manager

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.huanzhi.manager.core.CloneRecord
import com.huanzhi.manager.core.CloneVersions
import com.huanzhi.manager.core.UpdateKind
import com.huanzhi.manager.core.UpdateState
import com.huanzhi.manager.databinding.ItemCloneBinding
import com.huanzhi.manager.ui.motion.pressFeedback
import com.huanzhi.manager.ui.motion.resetMotion

/**
 * 列表一行的数据。
 *
 * 「装没装」和「是不是旧版」在界面层是两个独立维度：
 * 没装 + 可更新 同时成立时，副标题该说「未安装」，角标再提示有新版可打。
 */
data class CloneRow(
    val rec: CloneRecord,
    val installed: Boolean,
    val update: UpdateState,
)

class CloneAdapter(
    private val onClick: (CloneRecord) -> Unit,
    private val onMore: (CloneRecord, android.view.View) -> Unit
) : RecyclerView.Adapter<CloneAdapter.VH>() {

    private val items = mutableListOf<CloneRow>()

    fun submit(list: List<CloneRow>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemCloneBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val b: ItemCloneBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: CloneRow) {
            val rec = item.rec
            // 复用的条目可能带着上一次级联动画的残留，先复位再画
            b.root.resetMotion()
            b.name.text = rec.label

            val cur = CloneVersions.show(rec.sourceVersionCode, rec.sourceVersionName)
            b.sub.text = when {
                !item.installed -> "${rec.targetLabel} · 未安装"
                item.update.kind == UpdateKind.AVAILABLE && item.update.source != null ->
                    "${rec.targetLabel} · $cur → " +
                        CloneVersions.show(item.update.source.code, item.update.source.name)
                item.update.kind == UpdateKind.SOURCE_GONE -> "${rec.targetLabel} · 原应用已卸载"
                else -> "${rec.targetLabel} · ${rec.clonePkg}"
            }

            bindBadge(rec, item)

            b.icon.setImageDrawable(com.huanzhi.manager.core.AppScanner.iconOf(b.root.context, rec.targetPkg))
            b.root.setOnClickListener { onClick(rec) }
            b.more.setOnClickListener { v -> onMore(rec, v) }
            // 按下缩 2%：告诉手指「这一行接住了」，但幅度小到不会打断阅读
            b.root.pressFeedback()
        }

        /**
         * 角标只表达一件事：这份该重打了。
         *
         * 源应用已卸载的分身不显示「可更新」—— 它压根没法重建，给个点了没反应的提示
         * 比不给更糟；那种情况交给副标题去说。
         */
        private fun bindBadge(rec: CloneRecord, item: CloneRow) {
            val show = item.installed && item.update.kind == UpdateKind.AVAILABLE
            b.badge.visibility = if (show) View.VISIBLE else View.GONE
            if (!show) return
            val ctx = b.root.context
            b.badge.setTextColor(
                MaterialColors.getColor(b.badge, com.google.android.material.R.attr.colorOnPrimaryContainer)
            )
            b.badge.backgroundTintList = ColorStateList.valueOf(
                MaterialColors.getColor(b.badge, com.google.android.material.R.attr.colorPrimaryContainer)
            )
            b.badge.contentDescription = ctx.getString(
                R.string.badge_updatable_desc,
                CloneVersions.show(rec.sourceVersionCode, rec.sourceVersionName),
                item.update.source?.let { CloneVersions.show(it.code, it.name) } ?: "",
            )
        }
    }
}
