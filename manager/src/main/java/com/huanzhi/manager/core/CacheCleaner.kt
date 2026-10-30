package com.huanzhi.manager.core

import android.content.Context
import java.io.File

/**
 * 缓存统计与清理。
 *
 * 统计三处：cacheDir（系统可以随时回收）、externalCacheDir，
 * 以及 [CloneCache.dir] —— 生成分身时的临时包放在 filesDir 下（避免打包途中被系统清掉），
 * 但它同样是「用完就该没」的临时文件，所以要算进这里、也要能被清理。
 *
 * 清理是安全的：已安装的分身是系统里独立的 APK，删掉临时文件不影响它们运行，
 * 只是「重新生成」时要重新构建一次。
 */
object CacheCleaner {

    data class Stat(val bytes: Long, val files: Int)

    fun scan(ctx: Context): Stat {
        var bytes = 0L
        var files = 0
        roots(ctx).forEach { root ->
            walk(root) { f, len ->
                bytes += len
                files++
            }
        }
        return Stat(bytes, files)
    }

    /** 清理缓存，返回实际释放的字节数 */
    fun clear(ctx: Context): Long {
        var freed = 0L
        roots(ctx).forEach { root ->
            walk(root) { f, len ->
                if (f.delete()) freed += len
            }
        }
        // 删完之后把可能被清空的子目录也顺手收掉，根目录本身保留
        roots(ctx).forEach { root ->
            root.listFiles()?.forEach { if (it.isDirectory && it.listFiles().isNullOrEmpty()) it.delete() }
        }
        return freed
    }

    private fun roots(ctx: Context): List<File> = listOfNotNull(
        ctx.cacheDir,
        ctx.externalCacheDir,
        CloneCache.dir(ctx),
        File(ctx.cacheDir, "clones"),   // 1.7 及以前的暂存位置
    ).filter { it.exists() }

    private fun walk(dir: File, onFile: (File, Long) -> Unit) {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) walk(f, onFile) else onFile(f, f.length())
        }
    }

    fun format(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }
}
