package com.huanzhi.manager

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.huanzhi.manager.core.BatchRunner
import com.huanzhi.manager.core.CloneCache
import com.huanzhi.manager.core.CloneStore
import com.huanzhi.manager.core.InstallBus
import com.huanzhi.manager.core.SettingsStore
import java.io.File

class ManagerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 必须早于任何 Activity：否则首帧是浅色，随后才被切到深色
        SettingsStore.applyTheme(this)

        // 进程启动时清掉上一次留下的残骸：崩溃、被杀、建包到一半断电都会留下
        // 和原应用一样大的克隆包。放到后台线程，删上百 MB 不必挡首帧。
        Thread {
            runCatching { CloneCache.sweep(this) }
        }.apply { isDaemon = true }.start()

        InstallBus.addListener { pkg, ok, message ->
            // 正常路径在提交会话时就删了（CloneWorkflow.submit）。这里是保险丝：
            // 兜住任何绕过它的路径（系统把会话顶掉、中途取消等），别再攒垃圾。
            runCatching {
                CloneStore(this).find(pkg)?.apkPath?.let { CloneCache.drop(File(it)) }
            }

            // 装成功过一次就记下来：以后系统里查不到它，说明是用户卸载的，记录该跟着删
            if (ok) runCatching { CloneStore(this).markInstalled(pkg) }

            // 批量过程中由进度弹窗自己汇报，避免一次弹十条 Toast
            if (BatchRunner.isRunning) return@addListener
            val text = if (ok) "安装成功" else message
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            }
        }
    }
}
