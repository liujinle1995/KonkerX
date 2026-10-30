package com.huanzhi.manager.core

import android.os.SystemClock

/**
 * 构建分身的阶段。
 *
 * 顺序就是 [CloneBuilder.build] 里的实际执行顺序；[weight] 是这个阶段占总耗时的比例，
 * 用来把「一段内部的百分比」折算成整条进度条的百分比 —— 没有它，进度条会这样走：
 * 拷贝（占八成时间）一直卡在 100%，然后签名和自检在两秒内从 100% 跳到结束。
 */
enum class BuildStage(val weight: Int, val label: String) {
    /** 读清单、检查分片、算总字节数：一瞬间 */
    PREPARE(2, "准备"),
    /** 全量拷贝 dex/资源/assets/so —— 大应用几十秒都花在这里 */
    COPY(76, "打包"),
    /** apksig 重签：要重新算一遍摘要并重写整个包 */
    SIGN(16, "签名"),
    /** 装之前自检包名/签名/权限 */
    VERIFY(6, "自检"),
    /** 把包写进系统的安装会话（不算构建，但用户要等） */
    INSTALL(0, "安装"),
    DONE(0, "完成"),
}

/**
 * 一次构建的进度快照。
 *
 * [percent] 是**整条流程**的百分比（0–100），不是当前阶段的百分比 ——
 * 界面上要显示一个不会倒退、不会跳变的数字，折算在产出方完成。
 */
data class BuildProgress(
    val stage: BuildStage,
    val percent: Int,
    /** 补充说明，例如「已处理 61.2 MB / 145.0 MB」 */
    val detail: String = "",
) {
    /** 把 0–100 的进度重新映射到 [from]–[to] 区间 */
    fun scaled(from: Int, to: Int): BuildProgress {
        val p = percent.coerceIn(0, 100)
        val mapped = from + ((to - from) * p / 100f).toInt()
        return copy(percent = mapped.coerceIn(0, 100))
    }
}

/** 用户主动取消。和真正的构建失败区分开：取消不该弹「失败」对话框 */
class BuildCancelledException : RuntimeException("已取消")

/**
 * 构建过程的控制柄：一边往里塞进度，一边读取消标记。
 *
 * 为什么不用协程的 cancellation：打包是一段阻塞 IO（ZipInputStream → ZipOutputStream），
 * 中间没有挂起点，`Job.cancel()` 插不进去，得等到下一次 `withContext` 才生效 ——
 * 对「已经等了 40 秒、想立刻停下」的用户来说太晚。这里改成每一批字节检查一次标记。
 */
class BuildControl(
    val onProgress: (BuildProgress) -> Unit = {},
    val isCancelled: () -> Boolean = { false },
) {
    fun checkCancelled() {
        if (isCancelled()) throw BuildCancelledException()
    }
}

/**
 * 进度节流：大包拷贝时每 256KB 就回调一次会有上千次，全部跨线程丢给界面没有意义。
 * 规则是「百分比变了才发」，并保证两次之间至少隔 [MIN_GAP_MS]，首尾强制发。
 */
internal class ProgressThrottle(private val control: BuildControl) {

    private var lastAt = 0L
    private var lastPercent = -1

    fun emit(p: BuildProgress, force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force) {
            if (p.percent == lastPercent) return
            if (now - lastAt < MIN_GAP_MS) return
        }
        lastAt = now
        lastPercent = p.percent
        control.onProgress(p)
    }

    private companion object {
        const val MIN_GAP_MS = 90L
    }
}
