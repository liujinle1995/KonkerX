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

    /**
     * 确定为零的体积。
     *
     * [size] 对 0 返回「—」，那是「不知道」的意思 —— 用于拿不到占用的分身记录
     * （没授权、或记录是从别处导进来的）。但缓存体积是我们自己扫出来的，
     * 扫到 0 就是真的 0，报「—」等于说「没测」，会让人以为这一栏没刷新。
     *
     * 两个函数并存不是重复：区别在于**这个 0 是测出来的还是缺数据**。
     */
    fun knownSize(bytes: Long): String = if (bytes <= 0L) "0 B" else size(bytes)

    /**
     * 紧凑体积，用于卡片右侧与统计卡的大数字。
     *
     * 和 [size] 的区别是**小数位的取舍方向不同**：
     * `size` 追求"同一量级报同一精度"（512.0 MB / 1.30 GB），适合正文里的整句；
     * 而这个函数追求"三位数以内" —— 512 MB 不是 512.0 MB，1.3 GB 不是 1.30 GB。
     * 卡片右侧只有 17sp 一格宽，多两位小数会把「412 MB」挤成「412.0 M…」。
     *
     * 0 报 `0 B`：这里所有的 0 都是扫出来的（缓存、总占用），不是"没测到"。
     */
    fun compact(bytes: Long): String {
        if (bytes < 0L) return "—"
        if (bytes == 0L) return "0 B"
        val kb = bytes / 1024.0
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024 -> String.format(Locale.getDefault(), "%.0f KB", kb)
            bytes < 1024L * 1024 * 1024 -> {
                val mb = kb / 1024
                // 10 MB 以上不再给小数：99.6 MB 读起来比 100 MB 更费劲，而那些位数的精度
                // 对一个"占了多少空间"的判断没有任何影响
                if (mb >= 10) String.format(Locale.getDefault(), "%.0f MB", mb)
                else String.format(Locale.getDefault(), "%.1f MB", mb)
            }
            else -> {
                val gb = kb / 1024 / 1024
                val s = String.format(Locale.getDefault(), "%.1f GB", gb)
                // 2.0 GB 写成 2 GB：整数没必要拖一个 .0
                if (s.endsWith(".0 GB")) s.removeSuffix(".0 GB") + " GB" else s
            }
        }
    }

    /**
     * 前台时长：「2 小时 14 分」「18 分钟」。
     *
     * 不报秒：UsageStats 的 `totalTimeInForeground` 是累计值，精确到秒反而暗示了
     * 一个它给不出的精度。不足 1 分钟显示「不到 1 分钟」——直接写「0 分钟」会被读成
     * "没在前台"，而事实恰恰相反。
     */
    fun duration(millis: Long): String {
        if (millis <= 0L) return "0 分钟"
        val min = millis / 60000L
        return when {
            min < 1 -> "不到 1 分钟"
            min < 60 -> "$min 分钟"
            else -> "${min / 60} 小时 ${min % 60} 分"
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
