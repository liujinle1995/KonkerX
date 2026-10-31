package com.huanzhi.manager.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次安装的结果。
 *
 * [definitive] 区分「确定失败」和「还没结果」：超时属于后者，
 * 记录要留着让用户能补装；签名冲突属于前者，记录该撤掉。
 */
internal class InstallOutcome(
    val code: Int,
    val reason: String,
    val definitive: Boolean = true,
)

/**
 * 等一份装完。
 *
 * 用法很讲究：**先挂监听，再提交会话**。小包从 commit 到回执可能只要几十毫秒，
 * 反过来做会漏掉那次回调，然后傻等几分钟超时 —— 而应用其实早就装好了。
 * 这正是「建两个只装上一个」的成因之一。
 *
 * 批量创建和批量更新都用它，两处各自实现的话，等安装的坑（超时、取消、回执丢失）
 * 迟早只在一个地方修。
 */
internal class InstallWait(private val pkg: String) {

    private val slot = CompletableDeferred<InstallOutcome>()
    private val fired = AtomicBoolean(false)

    val listener: InstallListener = { p, ok, message ->
        if (p == pkg && fired.compareAndSet(false, true)) {
            slot.complete(
                when {
                    ok -> InstallOutcome(1, "成功")
                    message == "已取消" -> InstallOutcome(-1, "已取消")
                    else -> InstallOutcome(0, message.ifBlank { "安装失败（系统没给原因）" })
                }
            )
        }
    }

    suspend fun await(timeoutMs: Long): InstallOutcome = withTimeoutOrNull(timeoutMs) { slot.await() }
        ?: InstallOutcome(
            0,
            "等了 ${timeoutMs / 1000} 秒没收到安装结果（系统确认框可能没弹出来或被切走了）",
            definitive = false,
        )

    /**
     * 用户点「跳过这一份」/「取消剩余」时，不必再等系统回执。
     *
     * 返回的 code -2 是个特殊值：它既不是失败也不是取消 —— 包已经打好了，
     * 只是用户不想现在装。调用方要把它记成「未安装」并**保留记录**，
     * 让用户随时能点它补装；当成失败处理会顺手把记录删掉，包就白打了。
     *
     * [fired] 的 CAS 是必须的：放弃等待的那一瞬间，系统回执可能正好到达，
     * 两边都往里写会让循环看到两个不同的结果。
     */
    fun abandon() {
        if (fired.compareAndSet(false, true)) {
            slot.complete(InstallOutcome(-2, "已跳过", definitive = false))
        }
    }

    companion object {
        /**
         * 等一份装完要多久，跟包体积强相关：几十 MB 的包写到 session、解压、dexopt
         * 在低端机上能耗上一两分钟。按体积放宽，别让大应用背「超时」的锅。
         */
        fun timeoutFor(apkBytes: Long): Long {
            val perMb = apkBytes / (1024 * 1024) * 1000L   // 1 MB ≈ 1 秒余量
            return (180_000L + perMb).coerceIn(180_000L, 480_000L)
        }
    }
}
