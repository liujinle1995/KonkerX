package com.huanzhi.manager.core

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.storage.StorageManager
import android.util.Log
import java.io.File

/** 从 /proc 读到的一条进程样本（仅在系统允许读 /proc 时能拿到） */
data class ProcSample(
    val pid: Int,
    val name: String,
    val uid: Int,
    val rssKb: Long,
    val threads: Int
)

/** 分身的存储占用，来自 StorageStatsManager */
data class StorageInfo(
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long
) {
    val total: Long get() = appBytes + dataBytes + cacheBytes
}

/** 分身的活跃情况，来自 UsageStatsManager */
data class UsageInfo(
    val lastUsedMs: Long,
    val foregroundMs: Long,
    val inForeground: Boolean
)

data class CloneRuntime(
    val record: CloneRecord,
    val installed: Boolean,
    val uid: Int,
    val apkBytes: Long,
    val storage: StorageInfo?,
    val usage: UsageInfo?,
    val processes: List<ProcSample>
) {
    val running: Boolean get() = inForeground || processes.isNotEmpty()
    val inForeground: Boolean get() = usage?.inForeground == true
    val totalRssKb: Long get() = processes.fold(0L) { acc, p -> acc + p.rssKb }
    val threadCount: Int get() = processes.fold(0) { acc, p -> acc + p.threads }

    /** 最实在的那个占用数字：优先真实存储占用，拿不到就用安装包体积 */
    val occupiedBytes: Long get() = storage?.total ?: apkBytes
}

data class DeviceMemory(
    val totalBytes: Long,
    val availBytes: Long,
    val thresholdBytes: Long,
    val lowMemory: Boolean
) {
    val usedBytes: Long get() = (totalBytes - availBytes).coerceAtLeast(0)
    val ratio: Float get() = if (totalBytes <= 0) 0f else usedBytes.toFloat() / totalBytes
}

/**
 * 运行态采集。三条数据通道，能拿到哪条算哪条：
 *
 * 1. StorageStatsManager —— 分身的代码/数据/缓存占用。需要「使用情况访问权限」
 *    （PACKAGE_USAGE_STATS + 用户在系统设置里授予）。
 * 2. UsageStatsManager —— 最近使用时间、累计前台时长、当前是否在前台。同一权限。
 * 3. /proc —— 进程数、常驻内存、线程数。**不需要权限，但绝大多数系统在 SELinux 里
 *    把 untrusted_app 的跨 UID 读取挡掉了**（hidepid）。读得到就显示，读不到就不显示，
 *    绝不拿 0 冒充。
 *
 * 早期版本试过 ActivityManager.getRunningAppProcesses()，从 Android 8.0 起它只返回
 * 调用者自己的进程，对分身完全无效。
 */
object RuntimeProbe {

    private const val TAG = "HuanZhi"
    private const val PAGE_SIZE_KB = 4L

    @Volatile
    private var procReadable = false

    /** 上一次扫描是否真的读到了别的 UID 的进程（而不是只读到自己） */
    val canReadProc: Boolean get() = procReadable

    /** 有没有「使用情况访问权限」——决定能不能显示存储占用与使用时间 */
    fun hasUsageAccess(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            ctx.packageName
        )
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) {
        false
    }

    fun deviceMemory(ctx: Context): DeviceMemory {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        return try {
            am.getMemoryInfo(mi)
            DeviceMemory(mi.totalMem, mi.availMem, mi.threshold, mi.lowMemory)
        } catch (t: Throwable) {
            Log.w(TAG, "getMemoryInfo failed: ${t.message}")
            DeviceMemory(0, 0, 0, false)
        }
    }

    // ---------------- /proc ----------------

    /**
     * 枚举 /proc。只有读到了「不属于本进程 UID」的条目才算真正可用，
     * 否则 hidepid 会把结果缩成自己一个进程，那种情况当作不可读处理。
     */
    fun scanProcesses(): List<ProcSample>? {
        val entries = try {
            File("/proc").list()
        } catch (_: Throwable) {
            null
        } ?: return null

        val out = ArrayList<ProcSample>(64)
        for (e in entries) {
            if (e.isEmpty() || !e[0].isDigit()) continue
            val pid = e.toIntOrNull() ?: continue
            try {
                var name = ""
                var uid = -1
                var rss = 0L
                var threads = 0
                File("/proc/$e/status").forEachLine { line ->
                    when {
                        line.startsWith("Name:") -> name = line.substring(5).trim()
                        line.startsWith("Uid:") -> uid = line.substring(4).trim()
                            .split('\t', ' ')
                            .firstOrNull { it.isNotEmpty() }
                            ?.toIntOrNull() ?: -1
                        line.startsWith("VmRSS:") -> rss = line.substring(6).trim()
                            .split(' ')
                            .firstOrNull { it.isNotEmpty() }
                            ?.toLongOrNull() ?: 0L
                        line.startsWith("Threads:") ->
                            threads = line.substring(8).trim().toIntOrNull() ?: 0
                    }
                }
                if (uid >= 0) {
                    if (rss <= 0L) rss = residentFromStatm(pid)
                    out.add(ProcSample(pid, name, uid, rss, threads))
                }
            } catch (_: Throwable) {
                // 进程在扫描途中退出，或没权限读 —— 跳过
            }
        }

        val myUid = Process.myUid()
        val foreign = out.count { it.uid != myUid }
        procReadable = foreign > 0
        Log.i(TAG, "proc scan: ${out.size} procs, ${out.size - foreign} mine, $foreign foreign")
        return out.ifEmpty { null }
    }

    private fun residentFromStatm(pid: Int): Long = try {
        val s = File("/proc/$pid/statm").readText().trim().split(' ')
        (s.getOrNull(1)?.toLongOrNull() ?: 0L) * PAGE_SIZE_KB
    } catch (_: Throwable) {
        0L
    }

    // ---------------- StorageStats ----------------

    private fun storageOf(ctx: Context, uid: Int): StorageInfo? {
        if (uid < 0) return null
        return try {
            val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val st = ssm.queryStatsForUid(StorageManager.UUID_DEFAULT, uid)
            StorageInfo(st.appBytes, st.dataBytes, st.cacheBytes)
        } catch (t: Throwable) {
            // SecurityException = 没授权；NameNotFound = 包刚被卸载
            null
        }
    }

    // ---------------- UsageStats ----------------

    /** 一次性取最近两小时的事件，汇总出「每个包最后一次是前台还是后台」 */
    private fun foregroundMap(ctx: Context): Map<String, Boolean> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val map = HashMap<String, Boolean>()
        return try {
            val events = usm.queryEvents(now - 2 * 3600_000L, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val pkg = e.packageName ?: continue
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> map[pkg] = true
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED -> map[pkg] = false
                }
            }
            map
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun usageOf(
        ctx: Context,
        pkg: String,
        fgMap: Map<String, Boolean>
    ): UsageInfo? = try {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 30L * 24 * 3600_000, now)
        var lastUsed = 0L
        var fgMs = 0L
        stats?.forEach {
            if (it.packageName == pkg) {
                if (it.lastTimeUsed > lastUsed) lastUsed = it.lastTimeUsed
                fgMs += it.totalTimeInForeground
            }
        }
        UsageInfo(lastUsed, fgMs, fgMap[pkg] == true)
    } catch (_: Throwable) {
        null
    }

    // ---------------- 汇总 ----------------

    fun snapshot(ctx: Context, records: List<CloneRecord>): List<CloneRuntime> {
        val procsByUid = scanProcesses().orEmpty().groupBy { it.uid }
        val fgMap = if (hasUsageAccess(ctx)) foregroundMap(ctx) else emptyMap()
        val pm = ctx.packageManager

        return records.map { r ->
            val ai = try {
                pm.getApplicationInfo(r.clonePkg, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            val uid = ai?.uid ?: -1
            val apk = ai?.sourceDir?.let { runCatching { File(it).length() }.getOrDefault(0L) } ?: 0L
            CloneRuntime(
                record = r,
                installed = ai != null,
                uid = uid,
                apkBytes = apk,
                storage = if (ai != null) storageOf(ctx, uid) else null,
                usage = if (ai != null) usageOf(ctx, r.clonePkg, fgMap) else null,
                processes = if (uid >= 0) procsByUid[uid].orEmpty() else emptyList()
            )
        }
    }
}
