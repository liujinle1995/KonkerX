package com.huanzhi.manager.ui.dialog

import android.content.Context
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AlertDialog.Builder
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.huanzhi.manager.ui.theme.MaterialAttr

/**
 * 「确认」按钮着成红色的 secondary dialog。
 *
 * 破坏性操作（卸载、删除记录、清除全部）必须一眼就和「取消」拉开层级：
 * 原来这个着色的三行代码在五个方法里各抄了一遍，还都依赖调用者记得在 show() 之后
 * 再去取 Button —— 写在前头的话 getButton() 返回 null，颜色就静默丢失了，
 * 而「按钮没变红」在开发时几乎不会有人注意到。
 *
 * 所以把它收敛成一个 API：** show 的动作也在这里完成 **，
 * 调用方拿不到「还没显示」的 Builder，也就没法把顺序写错。
 *
 * @param actionLabel 已经本地化好的按钮文案（必须写清后果，「卸载」而不是「确定」）
 */
fun Builder.confirmDestructive(
    context: Context,
    actionLabel: CharSequence,
    onConfirm: () -> Unit,
): AlertDialog {
    setPositiveButton(actionLabel) { _, _ -> onConfirm() }
    val dialog = show()
    dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.let { btn ->
        btn.setTextColor(MaterialColors.getColor(btn, MaterialAttr.colorError))
    }
    return dialog
}

/**
 * 最简单的信息型对话框：一个标题、一段说明、一个「完成」。
 *
 * 用来替代原来散落各处的 `MaterialAlertDialogBuilder(this).setTitle(...)
 * .setPositiveButton(R.string.done, null).show()`。单独抽出来不是图省代码，
 * 是为了让「这类说明用什么按钮」有一个统一答案。
 */
fun Context.infoDialog(title: CharSequence, message: CharSequence): AlertDialog =
    MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok, null)
        .show()
