package com.huanzhi.manager.ui.motion

import android.content.Context
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import androidx.recyclerview.widget.RecyclerView

/**
 * 动效令牌与 View 侧的编排工具。
 *
 * 取值依据是 Material 3 的 MotionTokens（时长 / 贝塞尔），
 * 但**怎么用**由 `docs/design-system/MOTION.md` 决定，改之前先看那份文件。
 *
 * 为什么全在这里而不散在 Activity 里：时长和曲线一旦分头维护，
 * 迟早会出现「这个页面 200ms 那个页面 300ms」的不一致，
 * 而这种不一致用户说不出哪里怪，只觉得廉价。
 */

/** 时长令牌，对齐 MotionTokens.Duration*。只允许用这些值，不要写裸数字 */
object Dur {
    /** 100ms · 索引气泡、按压 */
    const val SHORT_2 = 100L

    /** 150ms · 退出侧起步（FAB 收起、列表淡出） */
    const val SHORT_3 = 150L

    /** 200ms · 页面退出、对话框退出 */
    const val SHORT_4 = 200L

    /** 250ms · 列表项入场、FAB 出现、展开折叠、箭头旋转 */
    const val MEDIUM_1 = 250L

    /** 300ms · 页面进入、空态出现 */
    const val MEDIUM_2 = 300L

    /** 350ms · 批量条升起（本应用唯一「重」一点的动作） */
    const val MEDIUM_3 = 350L
}

/**
 * 缓动曲线，对齐 MotionTokens.Easing*CubicBezier。
 *
 * 方向规则是硬的：进入 = DECELERATE，退出 = ACCELERATE，留在屏幕上变 = EMPHASIZED。
 * 同一条曲线同时用于进出，动画就会「没有来路也没有去路」，看着像卡了一下才动。
 */
object Curve {
    /** (0.05, 0.7, 0.1, 1) 一切进入 */
    val EMPHASIZED_DECELERATE: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    /** (0.3, 0, 0.8, 0.15) 一切退出 */
    val EMPHASIZED_ACCELERATE: Interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    /** (0.2, 0, 0, 1) 屏幕上原地变化 */
    val EMPHASIZED: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
}

object Motion {

    fun dp(v: View, value: Float): Float = value * v.resources.displayMetrics.density

    /**
     * 系统设置里关掉动画（开发者选项 / 无障碍）时，所有时长退化为 0。
     *
     * 不做这个判断的后果不是「动画难看」，而是「界面看起来没响应」——
     * 用户已经把动画关了，我们却还要他等 300ms。
     */
    fun animationsDisabled(ctx: Context): Boolean = runCatching {
        Settings.Global.getFloat(
            ctx.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }.getOrDefault(false)
}

/** 把令牌时长换算成实际时长：动画被关掉时一律 0 */
private fun View.ms(duration: Long): Long =
    if (Motion.animationsDisabled(context)) 0L else duration

/**
 * 从下方浮起 + 淡入。列表项、空态、批量条都走这一条。
 *
 * 已经在目标状态时直接返回：批量条这类「每勾一个应用就渲染一次」的元素，
 * 不判断的话会不停从下方重放。
 *
 * @param startDelay 级联延迟（cascade 用）
 * @param riseDp     位移距离，默认 12dp —— 超过 1/3 屏就得加中间关键帧，这里远小于该阈值
 */
@JvmOverloads
fun View.appear(
    startDelay: Long = 0L,
    riseDp: Float = 12f,
    duration: Long = Dur.MEDIUM_1,
) {
    if (visibility == View.VISIBLE && alpha == 1f && translationY == 0f) return
    appearInternal(startDelay, riseDp, duration)
}

/** 不带幂等判断的版本，级联专用：条目刚 bind 完就是「已在最终状态」，但仍要播放 */
internal fun View.appearInternal(
    startDelay: Long = 0L,
    riseDp: Float = 12f,
    duration: Long = Dur.MEDIUM_1,
) {
    val d = ms(duration)
    animate().cancel()
    visibility = View.VISIBLE
    alpha = 0f
    translationY = Motion.dp(this, riseDp)
    animate()
        .alpha(1f)
        .translationY(0f)
        .setDuration(d)
        .setStartDelay(if (d == 0L) 0L else startDelay)
        .setInterpolator(Curve.EMPHASIZED_DECELERATE)
        .start()
}

/** 淡出并可选择收成 GONE。退场永远比入场快，调用方传的时长要遵守这一点 */
@JvmOverloads
fun View.disappear(duration: Long = Dur.SHORT_3, goneAtEnd: Boolean = true) {
    if (visibility != View.VISIBLE) return
    val d = ms(duration)
    animate().cancel()
    animate()
        .alpha(0f)
        .setDuration(d)
        .setInterpolator(Curve.EMPHASIZED_ACCELERATE)
        .withEndAction {
            if (goneAtEnd) visibility = View.GONE
            alpha = 1f
        }
        .start()
}

/**
 * 两块内容互换（列表 ↔ 空态）。已经在目标状态时直接返回 ——
 * `onResume` 会反复调渲染，不判断就会每次回到这一页都重放一遍。
 */
fun crossFade(
    outView: View,
    inView: View,
    outDuration: Long = Dur.SHORT_3,
    inDuration: Long = Dur.MEDIUM_2,
) {
    if (outView === inView) return
    // 已经在目标状态就别动：运行概览每 3 秒刷一次，不判断的话卡片会一直闪
    if (outView.visibility != View.VISIBLE &&
        inView.visibility == View.VISIBLE &&
        inView.alpha == 1f &&
        inView.scaleX == 1f
    ) return
    val outD = outView.ms(outDuration)
    val inD = inView.ms(inDuration)

    outView.animate().cancel()
    inView.animate().cancel()

    if (outView.visibility == View.VISIBLE) {
        outView.animate()
            .alpha(0f)
            .setDuration(outD)
            .setInterpolator(Curve.EMPHASIZED_ACCELERATE)
            .withEndAction {
                outView.visibility = View.GONE
                outView.alpha = 1f
            }
            .start()
    }

    inView.visibility = View.VISIBLE
    inView.alpha = 0f
    inView.scaleX = 0.97f
    inView.scaleY = 0.97f
    inView.animate()
        .alpha(1f)
        .scaleX(1f)
        .scaleY(1f)
        // 让出场动画走完一半再进：完全错开会出现「中间什么都没有」的空帧
        .setStartDelay(if (inD == 0L) 0L else outD / 2)
        .setDuration(inD)
        .setInterpolator(Curve.EMPHASIZED_DECELERATE)
        .start()
}

/**
 * FAB 一类的「点状出现」：scale 0 → 1 + 淡入。
 *
 * 和 [appear] 的区别是它不位移 —— 浮在内容之上的元素如果从下方滑进来，
 * 会跟下方内容的滚动方向打架。
 */
@JvmOverloads
fun View.showScaled(delay: Long = 0L, duration: Long = Dur.MEDIUM_1) {
    if (visibility == View.VISIBLE && alpha == 1f && scaleX == 1f) return
    val d = ms(duration)
    animate().cancel()
    visibility = View.VISIBLE
    alpha = 0f
    scaleX = 0f
    scaleY = 0f
    animate()
        .alpha(1f)
        .scaleX(1f)
        .scaleY(1f)
        .setStartDelay(if (d == 0L) 0L else delay)
        .setDuration(d)
        .setInterpolator(Curve.EMPHASIZED_DECELERATE)
        .start()
}

/** 收起：缩到 0.8 而不是 0，避免最后几帧变成一个点 */
@JvmOverloads
fun View.hideScaled(duration: Long = Dur.SHORT_3) {
    if (visibility != View.VISIBLE) return
    val d = ms(duration)
    animate().cancel()
    animate()
        .alpha(0f)
        .scaleX(0.8f)
        .scaleY(0.8f)
        .setDuration(d)
        .setInterpolator(Curve.EMPHASIZED_ACCELERATE)
        .withEndAction {
            visibility = View.GONE
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
        }
        .start()
}

/**
 * 首屏级联：只动看得见的前几项，后面的不管。
 *
 * 为什么限 6 项：每项 40ms 延迟、最后一项 250ms 才结束，
 * 6 项总时长 450ms，还在 500ms 预算内；不限的话长列表最后一项要等两秒。
 * 也就只在「从 0 条变成有」时调用一次，滚动和刷新都不重放。
 */
@JvmOverloads
fun RecyclerView.cascade(
    startDelay: Long = 0L,
    stagger: Long = 40L,
    maxAnimated: Int = 6,
    riseDp: Float = 12f,
) {
    if (childCount == 0) return
    val n = minOf(childCount, maxAnimated)
    for (i in 0 until n) {
        getChildAt(i).appearInternal(startDelay + i * stagger, riseDp)
    }
}

/**
 * 按压反馈：0.98 缩放。
 *
 * 只缩 2% 是刻意的 —— 工具类应用要的是「按到了」而不是「按下去会弹」。
 * 返回 false 是为了不吞掉 click / ripple。
 */
@JvmOverloads
fun View.pressFeedback(pressedScale: Float = 0.98f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate()
                .scaleX(pressedScale).scaleY(pressedScale)
                .setDuration(v.ms(Dur.SHORT_2))
                .setInterpolator(Curve.EMPHASIZED)
                .start()

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(v.ms(Dur.SHORT_3))
                .setInterpolator(Curve.EMPHASIZED)
                .start()
        }
        false
    }
}

/** 显式复位，给复用型条目（RecyclerView.ViewHolder）用 */
fun View.resetMotion() {
    animate().cancel()
    alpha = 1f
    translationY = 0f
    scaleX = 1f
    scaleY = 1f
}
