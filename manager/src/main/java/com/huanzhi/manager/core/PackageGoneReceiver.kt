package com.huanzhi.manager.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 分身被卸载时把记录一起删掉。
 *
 * 为什么用广播而不是「打开主页时对账」：用户卸完一个分身，很可能接着去卸第二个，
 * 管理人根本没被打开过。等下次进来才对账，列表会先闪一下已卸载的条目；
 * 广播则是系统在卸载完成的瞬间叫醒我们，记录即刻消失。
 *
 * [Intent.ACTION_PACKAGE_FULLY_REMOVED] 是受保护广播（只有系统能发），
 * 且「更新式覆盖安装」不会触发它 —— 正好只对应「真的被移除」。
 * 覆盖安装走的是 ACTION_PACKAGE_REMOVED + EXTRA_REPLACING，这里另外挡一道。
 */
class PackageGoneReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        if (!TemplateSpec.isClonePkg(pkg)) return

        // 广播不等于事实：真去问一次系统，避免任何伪造 / 时序问题把记录删错
        if (AppScanner.isInstalled(context, pkg)) return

        val app = context.applicationContext
        runCatching {
            val store = CloneStore(app)
            val rec = store.find(pkg) ?: return
            CloneCache.drop(java.io.File(rec.apkPath))
            store.remove(pkg)
            InstallLog.append(app, "uninstalled $pkg -> record dropped")
            Log.i("HuanZhi", "record dropped after uninstall $pkg")
        }
    }
}
