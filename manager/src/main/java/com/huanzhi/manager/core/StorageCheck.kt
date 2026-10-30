package com.huanzhi.manager.core

import android.content.Context
import java.io.File

/**
 * 分身是「整包复制」，一份就顶一个原应用。批量建五份大应用时，
 * 缓存里的克隆包 + 已装好的分身 + 安装会话临时拷贝会同时占空间，
 * 空间不够时系统只会回一句「安装失败」，很难联想到是存储。
 * 所以开建之前先把账算给用户看。
 */
object StorageCheck {

    /** 目标应用 base.apk 的大小；读不到返回 0（不阻塞流程） */
    fun sourceApkSize(ctx: Context, pkg: String): Long = try {
        File(ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir).length()
    } catch (t: Throwable) {
        0L
    }

    /** 应用私有缓存所在分区（/data）的可用空间 */
    fun freeBytes(ctx: Context): Long = ctx.cacheDir.usableSpace

    /**
     * 估算一批分身的峰值占用。
     *
     * 峰值出现在某一份正在安装时：已经装好的 (copies-1) 份 + 缓存里的克隆包 1 份
     * + 提交给安装会话的同一份，约 (copies+1) 个原包大小，再留 20% 给解压 / dexopt。
     */
    fun estimateNeeded(sourceSize: Long, copies: Int): Long =
        if (sourceSize <= 0L || copies <= 0) 0L else (sourceSize * (copies + 1) * 1.2).toLong()
}
