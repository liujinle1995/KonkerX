package com.huanzhi.manager

import android.content.DialogInterface
import android.text.InputFilter
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 逐份命名：一个输入框对应一份分身。
 *
 * 批量创建时「每份都叫 应用名 · 分身N」只是默认值，用户经常想给某一份起个别的名字
 * （比如一个号放工作、一个号放家里），统一前缀满足不了。这里把每份摊开成一个输入框，
 * 改哪个都行，没改的保持默认。
 */
object NamingDialog {

    const val MAX_NAME_LEN = 24

    /**
     * @param initial 每份的初始名称，长度即份数
     * @param onDone  用户点「确定」后的结果，长度与 [initial] 一致，已 trim 且非空
     */
    fun show(
        activity: AppCompatActivity,
        title: String,
        initial: List<String>,
        onDone: (List<String>) -> Unit,
    ) {
        val d = activity.resources.displayMetrics.density
        val host = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (6 * d).toInt(), (16 * d).toInt(), 0)
        }

        val inputs = ArrayList<TextInputEditText>(initial.size)
        initial.forEachIndexed { i, name ->
            val til = TextInputLayout(activity).apply {
                hint = activity.getString(R.string.per_copy_hint, i + 1)
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * d).toInt() }
            }
            val et = TextInputEditText(til.context).apply {
                setText(name)
                // 光标按过滤后的实际长度算：maxLength 会把超长默认名截短
                setSelection(text?.length ?: 0)
                filters = arrayOf(InputFilter.LengthFilter(MAX_NAME_LEN))
                isSingleLine = true
            }
            til.addView(et)
            host.addView(til)
            inputs += et
        }

        val dlg = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(host)
            .setPositiveButton(R.string.done, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.show()

        dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val names = inputs.map { it.text?.toString()?.trim().orEmpty() }
            val blank = names.indexOfFirst { it.isEmpty() }
            if (blank >= 0) {
                // 不关弹窗，让用户接着改：关掉再重新打开会把其他几份的输入一起丢掉
                Toast.makeText(activity, "第 ${blank + 1} 份名称不能为空", Toast.LENGTH_SHORT).show()
                inputs[blank].requestFocus()
                return@setOnClickListener
            }
            dlg.dismiss()
            onDone(names)
        }
    }
}
