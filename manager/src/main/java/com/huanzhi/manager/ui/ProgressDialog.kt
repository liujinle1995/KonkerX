package com.huanzhi.manager.ui

import android.content.DialogInterface
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.huanzhi.manager.R
import com.huanzhi.manager.core.BuildProgress
import com.huanzhi.manager.databinding.DialogProgressBinding

/**
 * 打包 / 安装进度弹窗。
 *
 * 三处共用（新建分身、重新打包 / 改名、批量创建），因为它们等的是同一件事：
 * 拿原应用的 APK 重打一份再塞进系统。以前三处各自实现，两处是转圈、一处是变长条，
 * 用户看到的反馈不一致，判断「有没有卡死」的经验也就没法复用。
 *
 * [update] 会被后台线程调用，内部统一 post 到主线程 —— 调用方不必自己切线程。
 */
class ProgressDialog(private val activity: AppCompatActivity) {

    private val view = DialogProgressBinding.inflate(activity.layoutInflater)
    private val dialog: AlertDialog = MaterialAlertDialogBuilder(activity)
        .setView(view.root)
        .setCancelable(false)
        .setNegativeButton(R.string.cancel, null)
        .create()

    private var onCancel: (() -> Unit)? = null
    private var cancelled = false

    init {
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.setOnClickListener { requestCancel() }
        }
    }

    /**
     * @param hint 底部那行补充说明（比如「完成后会弹出系统安装确认」）
     * @param onCancel 传 null 表示这次不可取消（批量创建中途停下会把队列留在半截状态）
     */
    fun show(title: String, hint: String? = null, onCancel: (() -> Unit)? = null) {
        this.onCancel = onCancel
        cancelled = false
        view.title.text = title
        view.pct.text = "0%"
        view.bar.setProgressCompat(0, false)
        view.stage.text = ""
        if (hint.isNullOrEmpty()) {
            view.hint.visibility = View.GONE
        } else {
            view.hint.text = hint
            view.hint.visibility = View.VISIBLE
        }
        if (!dialog.isShowing) dialog.show()
        // 取消按钮只在真能取消时出现：摆一个按不动的按钮比不摆更让人焦躁
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.visibility =
            if (onCancel != null) View.VISIBLE else View.GONE
    }

    /** 来自任意线程；节流已在 BuildProgress 的产出方做过 */
    fun update(p: BuildProgress) {
        val text = buildString {
            append(p.stage.label)
            if (p.detail.isNotEmpty()) {
                append(" · ").append(p.detail)
            }
        }
        update(p.percent, text)
    }

    /** 批量任务自己算好总体百分比和文案，直接用这一版 */
    fun update(percent: Int, text: String) {
        val p = percent.coerceIn(0, 100)
        view.root.post {
            if (!dialog.isShowing) return@post
            view.bar.setProgressCompat(p, false)
            view.pct.text = "$p%"
            view.stage.text = text
        }
    }

    /** 用一句最终状态收尾，让用户看到「100% 已提交」而不是弹窗被直接抽走 */
    fun finish(text: String) {
        view.root.post {
            if (!dialog.isShowing) return@post
            view.bar.setProgressCompat(100, false)
            view.pct.text = "100%"
            view.stage.text = text
            view.hint.visibility = View.GONE
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.visibility = View.GONE
        }
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    val isShowing: Boolean get() = dialog.isShowing

    private fun requestCancel() {
        if (cancelled) return
        cancelled = true
        view.stage.text = "正在取消…"
        view.hint.visibility = View.GONE
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.isEnabled = false
        onCancel?.invoke()
    }
}
