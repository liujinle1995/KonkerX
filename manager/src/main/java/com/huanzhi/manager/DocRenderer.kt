package com.huanzhi.manager

import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

/** 说明页里的一节：小标题 + 若干条正文，以数字开头的条目不加项目符号 */
data class DocSection(val title: String, val lines: List<String>)

/**
 * 把说明文字渲染进一个 LinearLayout。
 * 插件说明页与技术说明页共用同一套排版，避免两处样式慢慢跑偏。
 */
class DocRenderer(
    private val activity: AppCompatActivity,
    private val container: LinearLayout
) {

    fun header(title: String, subtitle: String) {
        container.addView(
            TextView(activity).apply {
                text = title
                setTextAppearance(MaterialR.style.TextAppearance_Material3_TitleLarge)
                setPadding(0, dp(4), 0, dp(2))
            }
        )
        container.addView(
            TextView(activity).apply {
                text = subtitle
                setTextAppearance(MaterialR.style.TextAppearance_Material3_BodySmall)
                setTextColor(MaterialColors.getColor(this, MaterialR.attr.colorOnSurfaceVariant))
                setPadding(0, 0, 0, dp(6))
            }
        )
    }

    fun section(section: DocSection) {
        container.addView(
            TextView(activity).apply {
                text = section.title
                setTextAppearance(MaterialR.style.TextAppearance_Material3_TitleMedium)
                setTextColor(MaterialColors.getColor(this, MaterialR.attr.colorPrimary))
                setPadding(0, dp(18), 0, dp(4))
            }
        )
        section.lines.forEach { line ->
            val numbered = line.firstOrNull()?.isDigit() == true
            container.addView(
                TextView(activity).apply {
                    text = if (numbered) line else "· $line"
                    setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyMedium)
                    setLineSpacing(0f, 1.25f)
                    setPadding(0, dp(3), 0, dp(3))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
            )
        }
    }

    /** 跳到二级页面的入口，看上去像链接而不是按钮 */
    fun link(text: String, onClick: () -> Unit) {
        container.addView(
            TextView(activity).apply {
                this.text = "$text  →"
                setTextAppearance(MaterialR.style.TextAppearance_Material3_TitleSmall)
                setTextColor(MaterialColors.getColor(this, MaterialR.attr.colorPrimary))
                setPadding(0, dp(24), 0, dp(8))
                isClickable = true
                isFocusable = true
                selectableBackground()?.let { setBackgroundResource(it) }
                setOnClickListener { onClick() }
            }
        )
    }

    private fun selectableBackground(): Int? {
        val tv = android.util.TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId.takeIf { it != 0 }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
