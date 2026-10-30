package com.huanzhi.manager

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.huanzhi.manager.core.Changelog
import com.huanzhi.manager.databinding.DialogChangelogBinding
import com.huanzhi.manager.databinding.ItemReleaseBinding
import com.huanzhi.manager.ui.motion.Curve
import com.huanzhi.manager.ui.motion.Dur

/**
 * 更新记录弹窗：每个版本是一张卡，点标题展开该版本的详细条目。
 * 最新版本默认展开，其余收起 —— 打开就能看到这次改了什么。
 */
object ChangelogDialog {

    fun show(ctx: Context) {
        val view = DialogChangelogBinding.inflate(LayoutInflater.from(ctx))
        val inflater = LayoutInflater.from(ctx)

        Changelog.RELEASES.forEachIndexed { index, rel ->
            val item = ItemReleaseBinding.inflate(inflater, view.container, false)
            item.ver.text = "v${rel.versionName}"
            item.date.text = rel.date
            item.headline.text = rel.headline

            rel.sections.forEachIndexed { si, sec ->
                item.detail.addView(
                    styled(ctx, sec.title, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                        .apply {
                            setTextColor(
                                MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
                            )
                            setPadding(0, dp(ctx, if (si == 0) 2 else 10), 0, dp(ctx, 2))
                        }
                )
                sec.items.forEach { line ->
                    item.detail.addView(
                        styled(ctx, "· $line", com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                            .apply { setPadding(dp(ctx, 6), dp(ctx, 2), 0, 0) }
                    )
                }
            }

            var expanded = index == 0
            // 展开 / 折叠：高度用 ChangeBounds 过渡，箭头同时转到对应角度。
            // 原来只是 visibility 硬切换，观感是「内容凭空多出来一截」。
            // 箭头要排除掉，否则 ChangeBounds 会连它一起去量位置，转起来发飘。
            val expandTransition = AutoTransition().apply {
                duration = Dur.MEDIUM_1
                interpolator = Curve.EMPHASIZED
                excludeTarget(item.arrow, true)
            }

            fun render(animate: Boolean) {
                if (animate) {
                    TransitionManager.beginDelayedTransition(view.container, expandTransition)
                }
                item.detail.visibility = if (expanded) View.VISIBLE else View.GONE
                item.arrow.animate()
                    .rotation(if (expanded) 180f else 0f)
                    .setDuration(if (animate) Dur.MEDIUM_1 else 0L)
                    .setInterpolator(Curve.EMPHASIZED)
                    .start()
            }
            item.header.setOnClickListener {
                expanded = !expanded
                render(animate = true)
            }
            render(animate = false)

            view.container.addView(item.root)
        }

        val dlg = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.version_and_updates)
            .setView(view.root)
            .setPositiveButton(R.string.done, null)
            .create()
        dlg.show()

        // 弹窗内容整体不能超过屏幕，矮屏上滚动区自动收窄
        val dm = ctx.resources.displayMetrics
        val maxPx = (dm.heightPixels * 0.62f).toInt()
        val wantPx = (dm.density * 420).toInt()
        view.scroll.layoutParams = view.scroll.layoutParams.apply {
            height = minOf(maxPx, wantPx)
        }
    }

    private fun styled(ctx: Context, text: String, style: Int): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(style)
            setLineSpacing(0f, 1.18f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}
