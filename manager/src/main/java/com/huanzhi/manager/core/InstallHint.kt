package com.huanzhi.manager.core

import android.content.Context
import com.huanzhi.manager.R

/**
 * 失败原因 → 「接下来怎么办」。
 *
 * 用户已经看见「失败了」，再复述一遍没有价值；他要知道的是下一步该做什么。
 * 但系统给的 `reason` 是「签名与已装版本冲突（INSTALL_FAILED_UPDATE_INCOMPATIBLE）」
 * 这种诊断话术，回答不了这个问题。
 *
 * 放在这里而不是各自的界面里：构建进度页的失败卡和安装日志的失败卡片讲的是同一件事，
 * 两处各写一份，改一处忘一处，就会出现「进度页说先卸载，日志页说重新创建」。
 */
object InstallHint {

    fun of(ctx: Context, reason: String?): String? = when {
        reason.isNullOrBlank() -> null
        reason.contains("权限与原应用重名") -> ctx.getString(R.string.hint_duplicate_permission)
        reason.contains("存储空间不足") -> ctx.getString(R.string.hint_no_storage)
        reason.contains("签名与已装版本冲突") -> ctx.getString(R.string.hint_signature)
        reason.contains("没收到安装结果") -> ctx.getString(R.string.hint_timeout)
        reason.contains("无法提交安装") -> ctx.getString(R.string.hint_submit)
        reason.contains("没允许弹出安装确认框") -> ctx.getString(R.string.hint_dialog_blocked)
        reason.contains("冲突") -> ctx.getString(R.string.hint_conflict)
        reason.contains("分片") -> ctx.getString(R.string.hint_split)
        reason.contains("生成失败") -> ctx.getString(R.string.hint_build_failed)
        else -> null
    }

    /**
     * 卡片里那一段把错误码和说明拼起来的正文。
     *
     * 有建议就用建议（它更具体），没有就退回原始原因 —— 宁可给一句生涩的系统话术，
     * 也不要留一个空白的错误块。
     */
    fun bodyOf(ctx: Context, reason: String?, fallback: String): String =
        of(ctx, reason) ?: reason?.takeIf { it.isNotBlank() } ?: fallback
}
