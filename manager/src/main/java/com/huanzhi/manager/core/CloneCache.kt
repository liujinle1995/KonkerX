package com.huanzhi.manager.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 克隆包的暂存目录管理。
 *
 * 为什么必须及时删：一份克隆包 = 整个原应用那么大（红果 129MB），建 5 份就是 600MB+，
 * 而它真正被用到的时间只有「写进 PackageInstaller 会话」那几秒 ——
 * [ApkInstaller] 会把字节完整拷进会话，之后系统用的是会话里的副本，本地这份就是纯垃圾。
 *
 * 所以策略是两条：
 *   1. 提交完会话立刻删（[CloneWorkflow.submit]）；
 *   2. 进程启动时扫一遍目录，把上一次因为崩溃 / 被杀 / 断电留下的残骸清掉（[sweep]）。
 *
 * 为什么放 filesDir 而不是 cacheDir：
 *   系统在**存储空间紧张时**会主动清空应用的 cacheDir —— 而「空间紧张」恰恰就是
 *   建大包的时候。1.8 之前把临时包放在 cache 里，就出现过边打包边被系统抹掉，
 *   最后报一句 `raw-xxx.apk: open failed: ENOENT`。filesDir 不会被系统自动回收，
 *   由我们自己删（提交即删 + 启动清扫），代价可控。
 *
 * 需要重装时不需要这份文件：「重新生成并安装」本来就会重新构建。
 */
object CloneCache {

    private const val TAG = "HuanZhi"

    /** 暂存目录（应用私有、系统不会自动清） */
    fun dir(ctx: Context): File = File(ctx.filesDir, "clones")

    /** 1.7 及以前的缓存位置，启动时顺手清一次 */
    private fun legacyDir(ctx: Context): File = File(ctx.cacheDir, "clones")

    /** 删单个包（提交完会话后调用） */
    fun drop(apk: File?) {
        if (apk == null) return
        runCatching {
            if (apk.exists() && apk.delete()) Log.i(TAG, "cache dropped ${apk.name}")
        }
    }

    /**
     * 清空整个暂存目录。
     *
     * 只在进程启动时调用：那时不可能有正在构建的包（构建都在本进程内），
     * 也不可能有还在等确认的包（会话里已经存了完整副本）。
     *
     * @return 释放的字节数
     */
    fun sweep(ctx: Context): Long {
        val freed = sweepDir(dir(ctx)) + sweepDir(legacyDir(ctx))
        if (freed > 0) Log.i(TAG, "cache sweep freed ${freed / 1024 / 1024} MB")
        return freed
    }

    private fun sweepDir(d: File): Long {
        if (!d.isDirectory) return 0
        var freed = 0L
        d.listFiles()?.forEach { f ->
            runCatching {
                freed += f.length()
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }
        }
        if (d.listFiles().isNullOrEmpty()) d.delete()
        return freed
    }

    /** 当前暂存占用（字节） */
    fun size(ctx: Context): Long {
        val d = dir(ctx)
        if (!d.isDirectory) return 0
        return d.listFiles()?.sumOf { it.length() } ?: 0
    }
}
