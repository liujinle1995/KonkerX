package com.huanzhi.manager

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.huanzhi.manager.core.ApkInstaller
import com.huanzhi.manager.core.AppScanner
import com.huanzhi.manager.core.BuildCancelledException
import com.huanzhi.manager.core.BuildControl
import com.huanzhi.manager.core.CloneRecord
import com.huanzhi.manager.core.CloneWorkflow
import com.huanzhi.manager.databinding.DialogRenameBinding
import androidx.lifecycle.lifecycleScope
import com.huanzhi.manager.ui.ProgressDialog
import com.huanzhi.manager.ui.dialog.confirmDestructive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单个分身的全部下层动作：菜单、改名、重建、卸载、删记录，以及它们的确认文案。
 *
 * 为什么从 MainActivity 里抽出来：这些方法的入参全是 [CloneRecord]，
 * 没有一个碰列表控件或 FAB，是一组闭环的「对一个分身能做什么」。
 * 放在 Activity 里的代价是 —— 平板双栏要在右侧详情面板里复用同一批动作，
 * 不抽出来就得再抄一遍（而且两处的确认文案很快会长得不一样）。
 *
 * 这里只负责「先把话说清楚，再调 core」，不涉及任何列表刷新：
 * 数据变了通过 [onChanged] 通知宿主去重读，双向解耦。
 */
class CloneActions(
    private val activity: AppCompatActivity,
    private val workflow: CloneWorkflow,
    private val onChanged: () -> Unit,
) {

    /**
     * 点击一个分身：装了就启动，没装先问要不要安装。
     *
     * 之所以要问：装上不可避免地要系统弹一次确认框，而用户点这一行可能只是想看看
     * ——直接把框怼上去，等于替他做了「现在就得装」的决定。
     */
    fun openOrInstall(rec: CloneRecord) {
        if (!AppScanner.isInstalled(activity, rec.clonePkg)) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(rec.label)
                .setMessage(activity.getString(R.string.not_installed_message, rec.label))
                .setPositiveButton(R.string.action_install) { _, _ -> rebuild(rec) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val intent = activity.packageManager.getLaunchIntentForPackage(rec.clonePkg)
        if (intent == null) {
            toast(activity.getString(R.string.no_launch_entry))
            return
        }
        try {
            activity.startActivity(intent)
        } catch (t: Throwable) {
            toast(activity.getString(R.string.launch_failed, t.message ?: t.javaClass.simpleName))
        }
    }

    /** 更多操作菜单。手机从左列的「⋯」唤出，平板也可以从右侧详情面板唤出 */
    fun showMenu(rec: CloneRecord) {
        val items = arrayOf(
            activity.getString(R.string.action_rename),
            activity.getString(R.string.action_rebuild),
            activity.getString(R.string.action_compat),
            activity.getString(R.string.action_uninstall),
            activity.getString(R.string.action_forget),
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(rec.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> askRename(rec)
                    1 -> rebuild(rec)
                    2 -> askCompatRebuild(rec)
                    3 -> confirmUninstall(rec)
                    4 -> confirmForget(rec)
                }
            }
            .show()
    }

    /**
     * 卸载前必须确认。
     *
     * 卸载会把分身自己的数据一起删掉（不可恢复），原来点一下就执行，
     * 跟旁边的「重命名」只差一个条目位置，误触代价太高。
     */
    fun confirmUninstall(rec: CloneRecord) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.uninstall_title, rec.label))
            .setMessage(R.string.uninstall_message)
            .setNegativeButton(R.string.cancel, null)
            .confirmDestructive(activity, activity.getString(R.string.action_uninstall)) {
                ApkInstaller.uninstall(activity, rec.clonePkg)
            }
    }

    /** 只删记录：要说清「应用还在不在」，否则用户以为被卸载了 */
    fun confirmForget(rec: CloneRecord) {
        val installed = AppScanner.isInstalled(activity, rec.clonePkg)
        val msg = if (installed) {
            activity.getString(R.string.forget_message_installed, rec.label, rec.clonePkg)
        } else {
            activity.getString(R.string.forget_message_missing, rec.label)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.forget_title)
            .setMessage(msg)
            .setNegativeButton(R.string.cancel, null)
            .confirmDestructive(activity, activity.getString(R.string.action_forget)) {
                workflow.forget(rec)
                onChanged()
                toast(activity.getString(R.string.record_removed))
            }
    }

    /**
     * 重命名。
     *
     * 分身名是打进清单里的，所以改名 = 用新名字重新打包 + 覆盖安装（等同升级），
     * 数据不会丢。这一点必须在弹窗里讲清楚，否则用户会以为只是改个显示名。
     */
    fun askRename(rec: CloneRecord) {
        val view = DialogRenameBinding.inflate(activity.layoutInflater)
        view.nameInput.setText(rec.label)
        // 光标按过滤后的实际长度算：maxLength 可能截短默认名，直接用 label.length 会越界
        view.nameInput.setSelection(view.nameInput.text?.length ?: 0)
        view.hint.text = activity.getString(R.string.rename_hint)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.rename_title)
            .setView(view.root)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        // 用 View.OnClickListener 而不是 Builder 的回调：回调一触发就 dismiss，
        // 而这里要在「名称为空」时把框留住
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = view.nameInput.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                toast(activity.getString(R.string.name_empty))
                return@setOnClickListener
            }
            if (name == rec.label) {
                dialog.dismiss()
                return@setOnClickListener
            }
            dialog.dismiss()
            rename(rec, name)
        }
    }

    private fun rename(rec: CloneRecord, newLabel: String) {
        runBuild(
            title = activity.getString(R.string.progress_rename, newLabel),
            failLabel = activity.getString(R.string.fail_label_rename),
            onWork = { control -> workflow.rename(rec, newLabel, control) },
            onDone = {
                onChanged()
                toast(activity.getString(R.string.rename_done, newLabel))
            },
        )
    }

    fun rebuild(rec: CloneRecord, compat: Boolean = false) {
        runBuild(
            title = activity.getString(R.string.progress_repack, rec.label),
            failLabel = activity.getString(R.string.fail_label_build),
            onWork = { control -> workflow.rebuild(rec, compat, control) },
            onDone = {
                onChanged()
                toast(activity.getString(R.string.rebuild_submitted))
            },
        )
    }

    /**
     * 兼容模式重建。
     *
     * 「装上了但卡在启动页 / 闪退」在克隆里很常见，原因通常只有两类：
     *   1. 应用自己在校验签名 —— 工具层无解，只能换别的多开方式；
     *   2. 应用按名字去找资源或配置，而资源表里的包名被我们改掉了 —— 这时关掉改名就有机会好。
     *
     * 所以这里把两种可能都摆出来再动手，避免用户以为「兼容模式」是万能药。
     */
    fun askCompatRebuild(rec: CloneRecord) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.compat_title)
            .setMessage(R.string.compat_message)
            .setPositiveButton(R.string.compat_start) { _, _ -> rebuild(rec, compat = true) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 没有安装权限时，与其让用户点完了失败，不如进页面就把话说清楚 */
    fun ensureInstallPermission() {
        if (ApkInstaller.canInstall(activity)) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.install_permission_title)
            .setMessage(R.string.install_permission_message)
            .setPositiveButton(R.string.install_permission_action) { _, _ ->
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            }
            .setNegativeButton(R.string.install_permission_later, null)
            .show()
    }

    /**
     * 三类长任务的公共外壳：进度弹窗 + 取消 + 异常提示。
     *
     * 取消做成「尽力而为」：打包的拷贝循环每 256KB 查一次标记，通常几百毫秒内就停下；
     * 但已经进到签名或写安装会话那一步就拦不住了 —— 那两步各自只有一次系统调用，
     * 中间没有可以安全抽身的点。所以按钮点下去后显示「正在取消…」而不是立刻消失。
     */
    private fun runBuild(
        title: String,
        failLabel: String,
        onWork: suspend (BuildControl) -> Unit,
        onDone: () -> Unit,
    ) {
        val cancel = AtomicBoolean(false)
        val dialog = ProgressDialog(activity)
        dialog.show(
            title = title,
            hint = activity.getString(R.string.progress_hint_install),
            onCancel = { cancel.set(true) },
        )
        activity.lifecycleScope.launch {
            try {
                onWork(BuildControl(onProgress = dialog::update, isCancelled = { cancel.get() }))
                dialog.finish(activity.getString(R.string.progress_submitted))
                // 让「100% 已提交」这一帧被看到，再把界面交回给系统安装框
                delay(320)
                onDone()
            } catch (e: BuildCancelledException) {
                toast(activity.getString(R.string.cancelled))
            } catch (t: Throwable) {
                toast(activity.getString(R.string.build_failed, failLabel, t.message ?: t.javaClass.simpleName))
            } finally {
                dialog.dismiss()
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
}
