package com.huanzhi.manager.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File

/**
 * 用 PackageInstaller 会话安装分身 APK。
 * 非系统应用无法静默安装，用户会看到一次系统安装确认框。
 */
object ApkInstaller {

    fun canInstall(ctx: Context): Boolean =
        ctx.packageManager.canRequestPackageInstalls()

    /**
     * @param onProgress 写安装会话的进度（0f–1f）。把包塞进系统会话是纯 IO，
     *   一个 150MB 的分身在这段要好几秒；不给数字，用户会以为上次点完就卡死了。
     */
    fun install(
        ctx: Context,
        apk: File,
        clonePkg: String,
        onProgress: ((Float) -> Unit)? = null,
    ): Boolean = try {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(clonePkg)
            // minSdk 31 起这两个 API 恒可用，不再需要版本判断
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    val total = apk.length().coerceAtLeast(1L)
                    var written = 0L
                    var lastEmit = 0L
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        written += n
                        val now = android.os.SystemClock.uptimeMillis()
                        if (onProgress != null && now - lastEmit >= 90L) {
                            lastEmit = now
                            onProgress(written.toFloat() / total)
                        }
                    }
                    session.fsync(out)
                    onProgress?.invoke(1f)
                }
            }
            val intent = Intent(ctx, InstallReceiver::class.java)
                .putExtra(InstallReceiver.EXTRA_PKG, clonePkg)
                // 记下「装之前是不是已经有这个包」：部分 ROM 装完那屏「完成」会回 ABORTED，
                // 回执不可信时靠这一位 + 当前是否已装来判定真成功，同时避免覆盖重装失败被误判
                .putExtra(InstallReceiver.EXTRA_WAS_INSTALLED, AppScanner.isInstalled(ctx, clonePkg))
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pi = PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
            session.commit(pi.intentSender)
        }
        true
    } catch (t: Throwable) {
        InstallBus.dispatch(clonePkg, false, "安装会话创建失败: ${t.message}")
        false
    }

    fun uninstall(ctx: Context, clonePkg: String): Boolean = try {
        val intent = Intent(ctx, InstallReceiver::class.java)
            .putExtra(InstallReceiver.EXTRA_PKG, clonePkg)
            .putExtra(InstallReceiver.EXTRA_UNINSTALL, true)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val pi = PendingIntent.getBroadcast(ctx, clonePkg.hashCode(), intent, flags)
        ctx.packageManager.packageInstaller.uninstall(clonePkg, pi.intentSender)
        true
    } catch (t: Throwable) {
        InstallBus.dispatch(clonePkg, false, "卸载失败: ${t.message}")
        false
    }
}

/** 安装结果回调：包名、是否成功、给用户看的说明 */
typealias InstallListener = (pkg: String, ok: Boolean, message: String) -> Unit

/**
 * 同进程安装事件总线。
 *
 * 需要多个监听者：Application 里挂一个兜底 Toast，批量调度器还要用一次性监听
 * 等「这一份装完了」再发下一份。
 */
object InstallBus {

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<InstallListener>()

    fun addListener(l: InstallListener) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: InstallListener) {
        listeners.remove(l)
    }

    fun dispatch(pkg: String, ok: Boolean, message: String) {
        listeners.forEach { runCatching { it(pkg, ok, message) } }
    }
}
