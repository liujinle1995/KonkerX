package com.huanzhi.manager.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 一条安装记录。
 *
 * 与 [InstallLog] 的分工：那一份是**给开发者看的原始日志**（自由文本，出问题一键复制），
 * 这一份是**给人看的记录**（结构化，一份一张卡）。
 * 两者的写入点几乎重合，但不合并 —— 原始日志里有 `submit FAIL`、`reconcile rescued`
 * 这类对用户毫无意义、对排查却是关键的行，塞进卡片里就变成噪音；
 * 反过来卡片要的「应用名 / 分身名 / 错误码 / 构建耗时 / 安装耗时」拆不出来，只能靠猜。
 *
 * 存 JSONL（一行一条）而不是一个 JSON 数组：追加不用重写整个文件，
 * 写坏了一行也只丢那一行，其余照读。
 */
data class LogEvent(
    val at: Long,
    val kind: Kind,
    /** 源应用名，如「美团」 */
    val appLabel: String,
    /** 分身名，如「测试」 */
    val cloneName: String,
    val clonePkg: String,
    val sourcePkg: String,
    /** 系统给的错误码，如 INSTALL_FAILED_UPDATE_INCOMPATIBLE；成功/取消时为空 */
    val code: String,
    /** 中文短原因，如「签名与已装版本冲突」 */
    val reason: String,
    val bytes: Long,
    val buildMs: Long,
    val installMs: Long,
    /**
     * 失败时留在暂存目录里的包，供「重试这一份」直接重提交。
     * 空串表示没留（装成功了、或者本来就没什么可留）。
     * 进程重启时 [CloneCache.sweep] 会清掉它，所以界面上要按**文件是否存在**来显示，
     * 不能只信这个字段。
     */
    val cachePath: String,
    /** 失败那一刻的分身记录；重试成功后要拿它把记录补回去 */
    val snapshot: RecordSnapshot?,
) {
    enum class Kind(val id: String) {
        Installed("ok"),
        Failed("fail"),
        Cancelled("cancel"),
        Skipped("skip"),
        ;

        companion object {
            fun of(id: String): Kind = entries.firstOrNull { it.id == id } ?: Failed
        }
    }

    /**
     * 「装成了没有」—— 只有 [Kind.Installed] 算装成。
     *
     * 分段控件那三个数字必须相加等于总数：稿面是「全部 12 / 失败 2 / 成功 10」，
     * 而列表里还躺着一张「已取消」。取消、跳过都没装成，它们归「失败」这一档，
     * 否则 2 + 10 对不上 12，用户第一眼就会觉得这个页面在瞎报数。
     */
    val landed: Boolean get() = kind == Kind.Installed
}

/** [LogEvent] 里带的分身记录快照，只保留重建 [CloneRecord] 需要的字段 */
data class RecordSnapshot(
    val id: Int,
    val clonePkg: String,
    val targetPkg: String,
    val targetLabel: String,
    val label: String,
    val createdAt: Long,
    val apkPath: String,
    val sourceVersionCode: Long,
    val sourceVersionName: String,
) {
    fun toRecord(): CloneRecord = CloneRecord(
        id = id,
        clonePkg = clonePkg,
        targetPkg = targetPkg,
        targetLabel = targetLabel,
        label = label,
        createdAt = createdAt,
        apkPath = apkPath,
        installedOnce = false,
        sourceVersionCode = sourceVersionCode,
        sourceVersionName = sourceVersionName,
    )

    companion object {
        fun of(r: CloneRecord) = RecordSnapshot(
            id = r.id,
            clonePkg = r.clonePkg,
            targetPkg = r.targetPkg,
            targetLabel = r.targetLabel,
            label = r.label,
            createdAt = r.createdAt,
            apkPath = r.apkPath,
            sourceVersionCode = r.sourceVersionCode,
            sourceVersionName = r.sourceVersionName,
        )
    }
}

/**
 * 安装记录簿。
 *
 * 上限 200 条：这是一份「最近怎么了」的账，不是归档。装了几百份以后翻到三个月前
 * 那次失败也没有意义，而无限增长会让读取和渲染都变慢。
 */
object LogBook {

    private const val TAG = "HuanZhi"
    private const val FILE_NAME = "install_events.jsonl"
    private const val MAX_ENTRIES = 200

    fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    /** 从 [InstallReceiver.describe] 拼出来的原因里把 `INSTALL_FAILED_*` 抠出来 */
    fun codeOf(reason: String?): String =
        Regex("(?:INSTALL|DELETE)_[A-Z_]+").find(reason.orEmpty())?.value.orEmpty()

    @Synchronized
    fun append(ctx: Context, e: LogEvent) {
        try {
            val f = file(ctx)
            f.appendText(toJson(e).toString() + "\n")
            trim(ctx, f)
        } catch (t: Throwable) {
            Log.w(TAG, "写安装记录失败: ${t.message}")
        }
    }

    /** 新的在前 —— 列表第一张卡就是刚发生的事 */
    fun read(ctx: Context): List<LogEvent> = try {
        val f = file(ctx)
        if (!f.exists()) emptyList()
        else f.readLines().asReversed().mapNotNull { parse(it) }
    } catch (t: Throwable) {
        Log.w(TAG, "读安装记录失败: ${t.message}")
        emptyList()
    }

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }

    /**
     * 超上限就砍掉最老的一半，而不是只砍一条。
     *
     * 每追加一条就重写整个文件太浪费；一次砍一半，摊到每条记录的写入成本可以忽略。
     */
    private fun trim(ctx: Context, f: File) {
        if (!f.exists()) return
        val lines = f.readLines()
        if (lines.size <= MAX_ENTRIES) return
        f.writeText(lines.takeLast(MAX_ENTRIES / 2).joinToString("\n", postfix = "\n"))
    }

    private fun toJson(e: LogEvent): JSONObject = JSONObject().apply {
        put("at", e.at)
        put("kind", e.kind.id)
        put("app", e.appLabel)
        put("name", e.cloneName)
        put("pkg", e.clonePkg)
        put("src", e.sourcePkg)
        put("code", e.code)
        put("reason", e.reason)
        put("bytes", e.bytes)
        put("build", e.buildMs)
        put("install", e.installMs)
        put("cache", e.cachePath)
        e.snapshot?.let { s ->
            put(
                "rec", JSONObject().apply {
                    put("id", s.id)
                    put("pkg", s.clonePkg)
                    put("target", s.targetPkg)
                    put("targetLabel", s.targetLabel)
                    put("label", s.label)
                    put("created", s.createdAt)
                    put("apk", s.apkPath)
                    put("svc", s.sourceVersionCode)
                    put("svn", s.sourceVersionName)
                }
            )
        }
    }

    /** 单行坏了就跳过那一行 —— 断电时写了半行是常态，不该让整份记录都读不出来 */
    private fun parse(line: String): LogEvent? {
        if (line.isBlank()) return null
        return try {
            val o = JSONObject(line)
            val rec = o.optJSONObject("rec")
            LogEvent(
                at = o.optLong("at"),
                kind = LogEvent.Kind.of(o.optString("kind")),
                appLabel = o.optString("app"),
                cloneName = o.optString("name"),
                clonePkg = o.optString("pkg"),
                sourcePkg = o.optString("src"),
                code = o.optString("code"),
                reason = o.optString("reason"),
                bytes = o.optLong("bytes"),
                buildMs = o.optLong("build"),
                installMs = o.optLong("install"),
                cachePath = o.optString("cache"),
                snapshot = rec?.let {
                    RecordSnapshot(
                        id = it.optInt("id"),
                        clonePkg = it.optString("pkg"),
                        targetPkg = it.optString("target"),
                        targetLabel = it.optString("targetLabel"),
                        label = it.optString("label"),
                        createdAt = it.optLong("created"),
                        apkPath = it.optString("apk"),
                        sourceVersionCode = it.optLong("svc"),
                        sourceVersionName = it.optString("svn"),
                    )
                },
            )
        } catch (t: Throwable) {
            null
        }
    }
}
