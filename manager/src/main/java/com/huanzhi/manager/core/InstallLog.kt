package com.huanzhi.manager.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简安装日志。
 *
 * 存在的理由：安装失败时系统只给一个状态码，用户截图给我也只能看到「安装超时或失败」。
 * 把每次建包 / 安装的真实状态写进私有目录的一个小文件，出问题时可以在汇总弹窗上
 * 一键复制出来，不用连 adb 抓 logcat。
 *
 * 超过 64KB 直接整份丢弃重来 —— 自用工具，不搞滚动归档。
 */
object InstallLog {

    private const val TAG = "HuanZhi"
    private const val FILE_NAME = "install.log"
    private const val MAX_BYTES = 64L * 1024

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    @Synchronized
    fun append(ctx: Context, line: String) {
        try {
            val f = file(ctx)
            if (f.length() > MAX_BYTES) f.delete()
            f.appendText("${fmt.format(Date())} $line\n")
        } catch (t: Throwable) {
            Log.w(TAG, "写安装日志失败: ${t.message}")
        }
    }

    fun tail(ctx: Context, lines: Int = 40): String = try {
        val all = file(ctx).readLines()
        all.takeLast(lines).joinToString("\n")
    } catch (t: Throwable) {
        ""
    }

    /**
     * 日志条数，只给设置页那一行显示用。
     *
     * 不做缓存也不做成增量计数：文件本身有 64KB 上限、撑死几百行，
     * 读一遍的代价远小于"计数器和真实文件对不上"带来的困惑。
     * 空行不算一条 —— 追加时的分隔空行不是记录。
     */
    fun count(ctx: Context): Int = try {
        val f = file(ctx)
        if (!f.exists()) 0 else f.useLines { it.count { line -> line.isNotBlank() } }
    } catch (t: Throwable) {
        0
    }
}
