package com.huanzhi.manager.util

import java.util.Locale

/**
 * 大小展示统一走这里，避免每个页面各写一套格式。
 * 规则：小于 1 MB 用整数 KB，其余保留一位小数。
 */
object Formats {

    fun size(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val kb = bytes / 1024.0
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024 -> String.format(Locale.getDefault(), "%.0f KB", kb)
            bytes < 1024L * 1024 * 1024 ->
                String.format(Locale.getDefault(), "%.1f MB", kb / 1024)
            else -> String.format(Locale.getDefault(), "%.2f GB", kb / 1024 / 1024)
        }
    }

    /** 「刚刚 / 12 分钟前 / 3 小时前」，用于展示上次启动时间 */
    fun relative(millis: Long, now: Long = System.currentTimeMillis()): String {
        if (millis <= 0L) return ""
        val sec = ((now - millis) / 1000).coerceAtLeast(0)
        return when {
            sec < 60 -> "刚刚"
            sec < 3600 -> "${sec / 60} 分钟前"
            sec < 86400 -> "${sec / 3600} 小时前"
            sec < 86400 * 30 -> "${sec / 86400} 天前"
            else -> "${sec / (86400 * 30)} 个月前"
        }
    }
}
