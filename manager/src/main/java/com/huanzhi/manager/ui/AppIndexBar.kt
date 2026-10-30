package com.huanzhi.manager.ui

import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.huanzhi.manager.AppRow
import com.huanzhi.manager.core.Pinyin
import com.huanzhi.manager.core.SettingsStore
import com.huanzhi.manager.ui.motion.Curve
import com.huanzhi.manager.ui.motion.Dur
import com.huanzhi.manager.ui.theme.MaterialAttr

/**
 * 选应用页右侧的首字母索引条。
 *
 * 从 AppPickerActivity 里抽出来的一半是行数（140 行），另一半是它确实自成一体：
 * 只依赖「行的列表」和「要滚到哪一格」，跟分身业务一点关系都没有。
 *
 * 留在 Activity 里时的具体代价：这个页面有四项互不相关的职责（加载过滤、索引、
 * 多选、批量创建），改其中任何一项都得在 1000 行里翻找；而索引条的行为边界很硬，
 * 最能独立验证，是最该先切出来的一块。
 */
class AppIndexBar(
    private val bar: LinearLayout,
    private val bubble: TextView,
    private val list: RecyclerView,
) {

    /** 索引条当前显示的字母，与 rolling 位置一一对应 */
    private var letters: List<Char> = emptyList()

    /** 每个字母对应的列表下标；[letters] 的第 i 项跳到这里 */
    private var positions: IntArray = IntArray(0)

    /** 当前字号（sp）。设置页换了档位后靠它判断要不要重建字母条 */
    private var sp: Float = 0f

    /**
     * 当前字号，给宿主页判断「设置里的档位是不是变了」用。
     *
     * 字母条是一串动态 TextView，没有「改一下 textSize」的快捷路径，档位变了只能整体重建。
     */
    val currentSp: Float get() = sp

    private val bubbleHider = Runnable { hideBubble() }

    fun setup() {
        bar.setOnClickListener { /* 只为挂载无障碍点击，实际交互走 onTouch */ }
        bar.setOnTouchListener { v, ev ->
            if (letters.isEmpty()) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val h = v.height.coerceAtLeast(1)
                    val i = ((ev.y / h) * letters.size).toInt().coerceIn(0, letters.size - 1)
                    jumpTo(letters[i])
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.performClick()
                    bubble.removeCallbacks(bubbleHider)
                    bubble.postDelayed(bubbleHider, BUBBLE_HIDE_DELAY)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 按当前行重建索引。
     *
     * 只在非加固段里按字母建组，末尾补一个「加」直接跳到加固段。
     * 为什么不给加固段也建字母组：那一段整体在列表尾部，用户要么滚到底、
     * 要么就是明确想找某个带壳应用（这时用搜索更快）。再按字母命中一遍，
     * 索引条会翻倍变长，反而不好按。
     */
    fun setRows(rows: List<AppRow>) {
        bar.removeAllViews()
        if (rows.isEmpty()) {
            letters = emptyList()
            positions = IntArray(0)
            bar.visibility = View.GONE
            return
        }

        val firsts = LinkedHashMap<Char, Int>()
        var headerAt = -1
        rows.forEachIndexed { i, row ->
            when (row) {
                is AppRow.Item -> firsts.getOrPut(Pinyin.keyOf(row.app.label).letter) { i }
                is AppRow.Header -> if (headerAt < 0) headerAt = i
            }
        }
        if (headerAt >= 0) firsts[HARDENED_INDEX] = headerAt
        letters = firsts.keys.toList()
        positions = firsts.values.toIntArray()

        val ctx = bar.context
        // 字号来自设置（小 / 标准 / 大）：真机上 11sp 被反馈太小吃不准
        sp = SettingsStore.indexTextSp(ctx)
        val color = MaterialColors.getColor(bar, MaterialAttr.colorOnSurfaceVariant)
        // 索引条整体 48dp 宽是为了热区达标，字母靠右渲染，观感仍是一条细边
        val edge = (8 * ctx.resources.displayMetrics.density).toInt()
        letters.forEach { letter ->
            bar.addView(
                TextView(ctx).apply {
                    text = letter.toString()
                    textSize = sp
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    setPadding(0, 0, edge, 0)
                    setTextColor(color)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                    )
                }
            )
        }
        bar.visibility = View.VISIBLE
        // 约束是 top/bottom 贴住列表：高度得等列表量完才能算，所以延后一拍
        bar.post { fitHeight() }
    }

    /**
     * 高度跟着字母数量走，不铺满整屏 —— 只命中三五个字母时铺满会让间距大得离谱。
     *
     * 每一格的行高按字号推（×1.8）：字号调大后如果还按固定 20dp 排，
     * 字母会自己叠在一起，字是大了反而更难看。
     */
    private fun fitHeight() {
        val n = letters.size
        if (n == 0) return
        val res = bar.resources
        // sp 换算成像素要乘 scaledDensity —— 用户把系统字体调大时，
        // 按 density 算出来的行高会比字形还矮，字母就叠在一起了
        val per = ((sp.takeIf { it > 0f } ?: DEFAULT_SP) *
            res.displayMetrics.scaledDensity * LINE_HEIGHT_FACTOR).toInt()
        val room = (list.height - (INDEX_VERTICAL_MARGIN_DP * res.displayMetrics.density).toInt())
            .coerceAtLeast(per * 3)
        val want = (per * n).coerceAtMost(room)
        val lp = bar.layoutParams as? ConstraintLayout.LayoutParams ?: return
        if (lp.height == want) return
        lp.height = want
        bar.layoutParams = lp
    }

    private fun jumpTo(letter: Char) {
        val i = letters.indexOf(letter)
        if (i < 0) return
        val pos = positions.getOrNull(i) ?: return
        // 多列时用 GridLayoutManager 的重载：普通 scrollToPosition 只保证
        // 「目标项可见」，靠 left/top 对齐的那一项会贴在中间而不是列表顶部
        (list.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(pos, 0)
            ?: list.scrollToPosition(pos)
        showBubble(letter)
    }

    /**
     * 气泡出场：0.85 → 1 + 淡入，100ms。
     *
     * 拖索引条时这个函数会被连续调用（每经过一个字母一次），
     * 所以已经可见时只换字母、不重启动画 —— 否则气泡会一直在闪。
     */
    private fun showBubble(letter: Char) {
        bubble.animate().cancel()
        bubble.text = letter.toString()
        bubble.removeCallbacks(bubbleHider)

        if (bubble.visibility != View.VISIBLE) {
            bubble.visibility = View.VISIBLE
            bubble.alpha = 0f
            bubble.scaleX = 0.85f
            bubble.scaleY = 0.85f
            bubble.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(Dur.SHORT_2)
                .setInterpolator(Curve.EMPHASIZED_DECELERATE)
                .start()
        } else {
            bubble.alpha = 1f
            bubble.scaleX = 1f
            bubble.scaleY = 1f
        }
        bubble.postDelayed(bubbleHider, BUBBLE_SHOW_TIMEOUT)
    }

    private fun hideBubble() {
        if (bubble.visibility != View.VISIBLE) return
        bubble.animate()
            .alpha(0f)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(Dur.SHORT_2)
            .setInterpolator(Curve.EMPHASIZED_ACCELERATE)
            .withEndAction {
                bubble.visibility = View.GONE
                // 复位：下一次 show 直接复用这些 Children 的属性值，
                // 不复会的话第二次出现时气泡是「从上次的残影里淡出来」而不是从 0.85 缩放到 1
                bubble.alpha = 1f
                bubble.scaleX = 1f
                bubble.scaleY = 1f
            }
            .start()
    }

    private companion object {
        const val HARDENED_INDEX = '加'
        const val DEFAULT_SP = 13f
        const val LINE_HEIGHT_FACTOR = 1.8f

        /** 索引条上下给列表留出的余量 */
        const val INDEX_VERTICAL_MARGIN_DP = 40

        /** 松手后气泡停留时长：短到不挡视线，长到用户来得及确认跳到了哪个字母 */
        const val BUBBLE_HIDE_DELAY = 450L
        const val BUBBLE_SHOW_TIMEOUT = 700L
    }
}
