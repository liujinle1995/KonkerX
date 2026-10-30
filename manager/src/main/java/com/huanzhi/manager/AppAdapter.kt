package com.huanzhi.manager

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.huanzhi.manager.core.AppEntry
import com.huanzhi.manager.databinding.ItemAppBinding
import com.huanzhi.manager.databinding.ItemGroupHeaderBinding
import com.huanzhi.manager.ui.motion.pressFeedback
import com.huanzhi.manager.ui.motion.resetMotion

/**
 * 列表里的一行：普通应用，或一条分组标题。
 *
 * 引入标题行的原因：带加固壳的应用被移到列表最下面，如果只是「顺序变了」，
 * 用户会觉得排序出了 bug —— 明明刚才还在第一屏的应用，刷新一下跑到末尾去了。
 * 一条明确的标题把这件事实说出来：它们不是被排错了，是被单独归到一类。
 */
sealed class AppRow {
    data class Item(val app: AppEntry) : AppRow()
    data class Header(val title: String, val hint: String) : AppRow()
}

class AppAdapter(
    private val onClick: (AppEntry) -> Unit,
    private val onLongClick: (AppEntry) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<AppRow>()
    private var selectionMode = false
    private var selected: Set<String> = emptySet()

    fun submit(list: List<AppRow>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setSelectionMode(on: Boolean) {
        if (selectionMode == on) return
        selectionMode = on
        notifyDataSetChanged()
    }

    fun setSelected(pkg: Set<String>) {
        selected = pkg
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position] is AppRow.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemGroupHeaderBinding.inflate(inflater, parent, false))
        } else {
            ItemVH(ItemAppBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = items[position]) {
            is AppRow.Header -> (holder as HeaderVH).bind(row)
            is AppRow.Item -> (holder as ItemVH).bind(row.app)
        }
    }

    inner class ItemVH(private val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(app: AppEntry) {
            b.root.resetMotion()
            b.name.text = app.label
            b.pkg.text = app.pkg
            b.icon.setImageDrawable(app.icon)

            // 加固壳徽标：缩在应用名右边。分组标题已经说明了它们的处境，
            // 徽标保留是为了让用户知道具体是哪一家的壳（决定要不要试一把）
            val brand = app.hardening
            if (brand != null) {
                b.badge.text = brand
                b.badge.visibility = View.VISIBLE
            } else {
                b.badge.visibility = View.GONE
            }

            val checked = app.pkg in selected
            b.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
            b.check.isChecked = checked
            // 选中项用一层淡色底提示，避免只靠勾选框
            b.root.isActivated = selectionMode && checked

            b.root.setOnClickListener { onClick(app) }
            b.root.setOnLongClickListener {
                onLongClick(app)
                true
            }
            b.root.pressFeedback()
        }
    }

    inner class HeaderVH(private val b: ItemGroupHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: AppRow.Header) {
            b.title.text = row.title
            b.hint.text = row.hint
        }
    }

    private companion object {
        const val TYPE_ITEM = 0
        const val TYPE_HEADER = 1
    }
}
