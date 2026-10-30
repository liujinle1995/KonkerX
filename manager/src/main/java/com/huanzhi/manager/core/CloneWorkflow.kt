package com.huanzhi.manager.core

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 创建分身的完整流程编排。
 *
 * 拆成 prepare（只构建）和 install（只提交安装）两步，是为了支持批量：
 * 批量时每份必须等前一份装完再提交下一份，否则系统会同时弹出好几个确认框互相顶掉。
 */
class CloneWorkflow(private val ctx: Context) {

    private val store = CloneStore(ctx)
    private val builder = CloneBuilder(ctx)

    /**
     * 找目标应用的启动 Activity。
     * 不用 getLaunchIntentForPackage —— 它在包可见性受限时会静默返回 null，
     * 显式 query 更可靠。
     */
    private fun launcherOf(pkg: String): String? = try {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(pkg)
        ctx.packageManager.queryIntentActivities(intent, 0)
            .firstOrNull()?.activityInfo?.name
    } catch (t: Throwable) {
        null
    }

    /** 只构建并登记，不安装 */
    suspend fun prepare(
        targetPkg: String,
        targetLabel: String,
        label: String,
        control: BuildControl = BuildControl(),
    ): CloneRecord = withContext(Dispatchers.IO) {
        val safeLabel = label.trim().ifEmpty { "$targetLabel 分身" }
        val id = store.nextId()
        val result = builder.build(
            targetPkg, targetLabel, safeLabel, launcherOf(targetPkg), id, control = control,
        )

        val record = CloneRecord(
            id = id,
            clonePkg = result.clonePkg,
            targetPkg = targetPkg,
            targetLabel = targetLabel,
            label = safeLabel,
            createdAt = System.currentTimeMillis(),
            apkPath = result.apk.absolutePath,
            sourceVersionCode = result.sourceVersionCode,
            sourceVersionName = result.sourceVersionName,
        )
        store.add(record)
        record
    }

    /** 把已经构建好的 APK 提交给系统安装器 */
    fun install(record: CloneRecord, onProgress: ((Float) -> Unit)? = null): Boolean {
        val apk = File(record.apkPath)
        if (!apk.exists()) {
            InstallBus.dispatch(record.clonePkg, false, "安装包已丢失，请重新生成")
            return false
        }
        return submit(apk, record.clonePkg, onProgress)
    }

    /**
     * 提交安装，并把用完的包删掉。
     *
     * [ApkInstaller.install] 返回时字节已经完整拷进系统会话，本地这份文件从此没有用处；
     * 留着的唯一后果是「一份等于一个原应用」的垃圾堆积（建 5 份 = 5 个原应用）。
     * 成败都删：装失败时用户点「重新生成并安装」会重新构建，不需要这份文件。
     */
    private fun submit(
        apk: File,
        clonePkg: String,
        onProgress: ((Float) -> Unit)? = null,
    ): Boolean {
        val ok = ApkInstaller.install(ctx, apk, clonePkg, onProgress)
        CloneCache.drop(apk)
        return ok
    }

    /**
     * 生成并安装一个分身。失败抛异常，由调用方提示。
     *
     * 进度是**一条**连续的百分比：构建占 0–88%，写安装会话占 88–100%。
     * 分两段报会让进度条在「打包完成」和「开始安装」之间跳一下，
     * 而这两件事对用户来说是同一件事 —— 「还要等多久」。
     */
    suspend fun createAndInstall(
        targetPkg: String,
        targetLabel: String,
        label: String,
        control: BuildControl = BuildControl(),
    ): CloneRecord {
        val buildControl = BuildControl(
            onProgress = { p -> control.onProgress(p.scaled(0, 88)) },
            isCancelled = control.isCancelled,
        )
        val record = prepare(targetPkg, targetLabel, label, buildControl)
        // 写 session 是几十 MB 的 IO，别占主线程
        withContext(Dispatchers.IO) {
            control.onProgress(BuildProgress(BuildStage.INSTALL, 88, "写入安装会话"))
            install(record) { f ->
                control.onProgress(
                    BuildProgress(
                        BuildStage.INSTALL,
                        88 + (f * 12f).toInt(),
                        "写入安装会话 ${(f * 100).toInt()}%",
                    )
                )
            }
            control.onProgress(BuildProgress(BuildStage.DONE, 100, "已提交，等待系统确认"))
        }
        return record
    }

    /**
     * 构建好、但还没提交安装的一份分身。
     *
     * 把「构建」和「提交」拆开是为了批量更新：系统同一时间只认一个安装确认框，
     * 必须等上一份装完（或用户点了取消）才能交下一份。单份重建用不上这一层，
     * 但两条路径共用同一套代码，才不会出现「单个能装、批量装不上」的分裂。
     */
    data class Staged(val record: CloneRecord, val apk: File)

    /**
     * 重新打包，但不安装。
     *
     * 记录里的源版本号在这一步就换成了「这次打包用的版本」，但**还没落库** ——
     * 要等 [commitStaged]。否则打包成功、安装失败时，角标会提前消失。
     */
    suspend fun stageRebuild(
        record: CloneRecord,
        compat: Boolean = false,
        control: BuildControl = BuildControl(),
    ): Staged = withContext(Dispatchers.IO) {
        val result = builder.build(
            record.targetPkg,
            record.targetLabel,
            record.label,
            launcherOf(record.targetPkg),
            record.id,
            arscRename = !compat,
            control = BuildControl(
                onProgress = { p -> control.onProgress(p.scaled(0, 88)) },
                isCancelled = control.isCancelled,
            ),
        )
        Staged(
            record = record.copy(
                apkPath = result.apk.absolutePath,
                sourceVersionCode = result.sourceVersionCode,
                sourceVersionName = result.sourceVersionName,
            ),
            apk = result.apk,
        )
    }

    /** 提交已构建好的包；返回是否成功写入安装会话 */
    fun submitStaged(staged: Staged, onProgress: ((Float) -> Unit)? = null): Boolean =
        submit(staged.apk, staged.record.clonePkg, onProgress)

    /**
     * 把构建结果写回记录。
     *
     * 只在该装的确实装上之后调用。失败时保留旧的版本号，角标才会继续提示「可更新」——
     * 提前改掉等于骗自己，用户再也不会知道这份还是旧的。
     */
    fun commitStaged(staged: Staged) {
        store.add(staged.record)
    }

    /**
     * 重新生成同名分身（覆盖安装，分身自己的数据保留）
     *
     * @param compat 兼容模式：不改资源表里的包名（排查「卡启动页 / 资源加载异常」用）
     */
    suspend fun rebuild(
        record: CloneRecord,
        compat: Boolean = false,
        control: BuildControl = BuildControl(),
    ): File = withContext(Dispatchers.IO) {
        val staged = stageRebuild(record, compat, control)
        control.onProgress(BuildProgress(BuildStage.INSTALL, 88, "写入安装会话"))
        val submitted = submitStaged(staged) { f ->
            control.onProgress(
                BuildProgress(BuildStage.INSTALL, 88 + (f * 12f).toInt(), "写入安装会话 ${(f * 100).toInt()}%")
            )
        }
        control.onProgress(BuildProgress(BuildStage.DONE, 100, "已提交，等待系统确认"))
        // 单份这条路拿不到安装回执（提交完就把界面交回系统了），能确认的只有
        // 「会话写进去了」。回执后期由 InstallReceiver 兜底刷新列表。
        if (submitted) commitStaged(staged)
        staged.apk
    }

    /**
     * 重命名。
     *
     * 桌面名称是写进 APK 清单 `android:label` 的字面量（这样多个分身才能各叫各的），
     * 所以改名没法只改数据库 —— 必须用新名字重新打包，再以「更新」的方式覆盖安装。
     * 包名和签名都没变，覆盖安装等同于升级，分身里的数据、登录态都保留。
     */
    suspend fun rename(
        record: CloneRecord,
        newLabel: String,
        control: BuildControl = BuildControl(),
    ): CloneRecord = withContext(Dispatchers.IO) {
        val clean = newLabel.trim().ifEmpty { record.label }
        if (clean == record.label) return@withContext record

        val result = builder.build(
            record.targetPkg,
            record.targetLabel,
            clean,
            launcherOf(record.targetPkg),
            record.id,
            control = BuildControl(
                onProgress = { p -> control.onProgress(p.scaled(0, 88)) },
                isCancelled = control.isCancelled,
            ),
        )
        val updated = record.copy(
            label = clean,
            apkPath = result.apk.absolutePath,
            // 改名是拿源应用当前版本重打的，版本号跟着一起更新；
            // 这里能落库，是因为「改名必须覆盖安装成功」已经被下面这一句强制了
            sourceVersionCode = result.sourceVersionCode,
            sourceVersionName = result.sourceVersionName,
        )
        if (!submit(result.apk, updated.clonePkg) { f ->
                control.onProgress(
                    BuildProgress(BuildStage.INSTALL, 88 + (f * 12f).toInt(), "写入安装会话 ${(f * 100).toInt()}%")
                )
            }
        ) {
            throw IllegalStateException("改名后无法提交安装")
        }
        // 名字先落库：万一用户在系统弹窗上点了取消，列表里至少和刚才的操作一致，
        // 再点一次「重命名 / 重新生成并安装」就能补上
        store.add(updated)
        updated
    }

    fun forget(record: CloneRecord) {
        store.remove(record.clonePkg)
        runCatching { File(record.apkPath).delete() }
    }

    fun all(): List<CloneRecord> = store.all()
}
