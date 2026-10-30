package com.huanzhi.manager.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * 系统安装会话的回执。
 *
 * 这里最重要的不是「成功 / 失败」，而是**把失败原因翻译成人话**：
 * PackageInstaller 的 status 只有六七种粗粒度值（CONFLICT / INVALID / BLOCKED…），
 * 真正的原因藏在 EXTRA_STATUS_MESSAGE 里的 `INSTALL_FAILED_*` 标志上。
 * 不解析它，用户看到的就永远是「安装失败」，排查全靠猜。
 */
class InstallReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_UNINSTALL = "uninstall"

        /**
         * 提交这次会话之前，这个包是不是已经装着。
         *
         * 回执不可信时要靠它区分「真装上了」和「本来就装着、这次其实没成功」：
         * 少了这一位，覆盖重装失败会被当成装好了。
         */
        const val EXTRA_WAS_INSTALLED = "was_installed"

        private const val TAG = "HuanZhi"

        /** 从系统消息里解析出真正的原因 */
        fun describe(status: Int, message: String): String {
            if (status == PackageInstaller.STATUS_SUCCESS) return "成功"
            if (status == PackageInstaller.STATUS_FAILURE_ABORTED) return "已取消"

            val token = Regex("(INSTALL|DELETE)_[A-Z_]+").find(message)?.value

            // 清单格式错的原因很杂，最常见也最隐蔽的是「targetSdk ≥ 31 但组件没写 android:exported」：
            // 系统只在 EXTRA_STATUS_MESSAGE 里跟一句解释，不单独给错误码，
            // 不解析它，用户看到的永远只有「清单格式有误」。
            if (message.contains("android:exported", ignoreCase = true)) {
                val who = Regex("([A-Za-z0-9_.]+): Targeting S").find(message)?.groupValues?.get(1)
                return "清单里的组件缺少 android:exported" +
                    (if (who != null) "（$who）" else "") +
                    "，targetSdk ≥ 31 时会被判为非法清单"
            }

            val byToken = when (token) {
                "INSTALL_FAILED_DUPLICATE_PERMISSION" -> "权限与原应用重名"
                "INSTALL_FAILED_UPDATE_INCOMPATIBLE" -> "签名与已装版本冲突"
                "INSTALL_FAILED_VERSION_DOWNGRADE" -> "版本号低于已装版本"
                "INSTALL_FAILED_ALREADY_EXISTS" -> "同包名应用已存在"
                "INSTALL_FAILED_INSUFFICIENT_STORAGE" -> "存储空间不足"
                "INSTALL_FAILED_INVALID_APK" -> "APK 结构无效"
                "INSTALL_FAILED_INVALID_URI" -> "APK 文件读不到"
                "INSTALL_FAILED_MISSING_SPLIT" -> "缺少分片包"
                "INSTALL_FAILED_USER_RESTRICTED" -> "系统限制了安装"
                "INSTALL_FAILED_VERIFICATION_FAILURE" -> "系统校验未通过"
                "INSTALL_FAILED_OLDER_SDK" -> "系统版本过低，装不上"
                "INSTALL_FAILED_DEPRECATED_SDK_VERSION" -> "targetSdk 过低，被系统拒绝"
                "INSTALL_PARSE_FAILED_MANIFEST_MALFORMED" -> "清单格式有误"
                "INSTALL_PARSE_FAILED_NO_CERTIFICATES" -> "APK 没有签名"
                "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES" -> "新旧签名不一致"
                "FAILED_MISSING_SPLIT" -> "缺少分片包"
                else -> null
            }
            if (byToken != null) {
                return if (token != null) "$byToken（$token）" else byToken
            }

            return when (status) {
                PackageInstaller.STATUS_FAILURE_BLOCKED -> "被系统拦截"
                PackageInstaller.STATUS_FAILURE_CONFLICT -> "与已装应用冲突（签名或权限）"
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "与当前系统不兼容"
                PackageInstaller.STATUS_FAILURE_INVALID -> "APK 无效"
                PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足"
                else -> {
                    val brief = message.replace(Regex("^\\s+|\\s+$"), "").take(120)
                    if (brief.isEmpty()) "失败（状态码 $status）" else "失败（$status）$brief"
                }
            }
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        val uninstall = intent.getBooleanExtra(EXTRA_UNINSTALL, false)
        val wasInstalled = intent.getBooleanExtra(EXTRA_WAS_INSTALLED, false)
        Log.i(TAG, "install result pkg=$pkg status=$status msg=$message")

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // 注意 -1 就是 STATUS_PENDING_USER_ACTION，不是异常值：
            // 这里要把系统给的确认界面拉起来，否则安装会一直挂在这个 session 上
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm == null) {
                InstallLog.append(ctx, "pending pkg=$pkg but EXTRA_INTENT missing")
                InstallBus.dispatch(pkg, false, "系统没有给出安装确认界面")
                return
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val err = runCatching { ctx.startActivity(confirm) }.exceptionOrNull()
            if (err != null) {
                // Android 10 起「后台启动 Activity」受限：如果这一份提交时本应用已经不在前台，
                // 确认框会被系统悄悄拦掉，用户看不到任何提示，而这一份就永远停在 pending。
                // 这种情况必须立刻报错，不能让批量在那儿傻等三分钟超时。
                InstallLog.append(ctx, "pending pkg=$pkg startActivity FAIL ${err.javaClass.simpleName}")
                InstallBus.dispatch(
                    pkg, false,
                    "系统没允许弹出安装确认框（应用不在前台）。切回幻之分身后再点「安装」重试"
                )
            } else {
                InstallLog.append(ctx, "pending pkg=$pkg dialog shown")
            }
            return
        }

        val ok = status == PackageInstaller.STATUS_SUCCESS ||
            (!uninstall && rescuedAbort(ctx, pkg, status, message, wasInstalled))
        val reason = when {
            uninstall && ok -> "已卸载"
            ok && status != PackageInstaller.STATUS_SUCCESS ->
                "已安装（系统回执是「${describe(status, message)}」，" +
                    "部分机型「完成」那一屏会这么回，已按实际安装状态校正）"
            else -> describe(status, message)
        }
        InstallLog.append(
            ctx,
            "recv pkg=$pkg status=$status ${if (ok) "OK" else "FAIL"} reason=$reason raw=$message"
        )
        InstallBus.dispatch(pkg, ok, reason)
    }

    /**
     * 回执说失败，但包其实装上了 —— 该不该信？
     *
     * 小米 / OPPO / vivo 这类 ROM 在装完之后还会多一屏「应用已安装 · 打开 / 完成」。
     * 用户点「完成」时系统送来的是 `STATUS_FAILURE_ABORTED`，字面意思是「用户取消」，
     * 可包早就装进系统了。真机实测就栽在这里：五份分身，第一份被判成取消，
     * 批量直接停摆，最后只有两个包 —— 一个装好的、一个预构建好但没装的。
     *
     * 只校正「看起来是用户取消」这一类回执；真失败（签名冲突、空间不足…）照实报错。
     * 判据也不能只看「现在装上了」—— 覆盖重装失败时旧版本还在，会被误判成功，
     * 所以要同时满足「提交前本来没装」，这一位由 [ApkInstaller] 在提交会话前写进 PendingIntent。
     */
    private fun rescuedAbort(
        ctx: Context,
        pkg: String,
        status: Int,
        message: String,
        wasInstalled: Boolean,
    ): Boolean {
        if (status == PackageInstaller.STATUS_SUCCESS) return false
        if (wasInstalled) return false
        val looksLikeCancel = status == PackageInstaller.STATUS_FAILURE_ABORTED ||
            Regex("cancel|abort", RegexOption.IGNORE_CASE).containsMatchIn(message)
        if (!looksLikeCancel) return false
        // 回执送达和「包在 PackageManager 里可见」之间有几十毫秒的空档，
        // 查不到就再等两拍（总共最多 240ms）。这里宁可多等一下，也不要把装好的那一份
        // 报成「已取消」—— 用户看着它装完却被告知取消了，比慢 0.2 秒难受得多。
        repeat(3) { i ->
            if (AppScanner.isInstalled(ctx, pkg)) return true
            if (i < 2) runCatching { Thread.sleep(120) }
        }
        return false
    }
}
