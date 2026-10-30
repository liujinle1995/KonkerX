package com.huanzhi.manager.core

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.huanzhi.manager.util.Formats
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 组装分身 APK。
 *
 * 做法是「全量重打包」：把目标 APK 的 dex / 资源 / assets / so 原样搬到新 APK 里，
 * 只替换 AndroidManifest.xml 并把包名改成新的。
 *
 * 为什么不学 Konker 做 0.5MB 轻壳：
 * 轻壳要在运行时把别人的 dex 和资源挂进自己进程，而 Activity 的 Resources 是由
 * ResourcesManager 按 APK 资源目录新建的，挂载补丁打不到；要继续做下去就得一路
 * hook 隐藏 API，既脆又跟新系统版本强耦合 —— 那正是原作者停更的原因。
 * 全量重打包把资源、主题、assets、native 库全部原生化，代价只是体积等于原应用。
 */
class CloneBuilder(private val ctx: Context) {

    companion object {
        private const val TAG = "HuanZhi"
        private const val ENTRY_MANIFEST = "AndroidManifest.xml"
        private const val ENTRY_ARSC = "resources.arsc"
    }

    private val workDir: File by lazy {
        CloneCache.dir(ctx).apply { if (!isDirectory) mkdirs() }
    }

    /**
     * @param sourceVersionCode 打包时源应用的版本号，由 [build] 顺带带出来。
     *   界面上「可更新」的判断全靠它，所以必须在读 [ApplicationInfo] 的那一段取，
     *   而不是装完之后再去查 —— 中途源应用要是被升级了，查到的就是新版本，
     *   记录会撒谎说「这份已经是新的」。
     */
    data class Result(
        val apk: File,
        val clonePkg: String,
        val sourceVersionCode: Long,
        val sourceVersionName: String,
    )

    /**
     * @param arscRename 是否把 resources.arsc 里的包名也改成分身包名。
     *
     *   开启（默认）：`getIdentifier(name, type, getPackageName())` 这类按名字查资源能命中，
     *   走 R 常量的代码不受影响（资源 ID 是纯数字，改名不影响 `@0x7f...` 解析）。
     *
     *   关闭（兼容模式）：资源表原样保留。专门用来排查「分身卡启动页/资源加载异常」——
     *   个别应用把资源名、包名写死在 native 或脚本层（字节系、部分游戏引擎），
     *   资源表改名后它们查不到东西会一直等下去。关掉不一定能修好，但这是最省事的一次对照实验。
     */
    fun build(
        targetPkg: String,
        targetLabel: String,
        label: String,
        launcherActivity: String?,
        cloneId: Int,
        arscRename: Boolean = true,
        control: BuildControl = BuildControl(),
    ): Result {
        val pm = ctx.packageManager
        val reporter = ProgressThrottle(control)
        reporter.emit(BuildProgress(BuildStage.PREPARE, 0, "读取目标应用信息"), force = true)

        val ai = try {
            pm.getApplicationInfo(targetPkg, 0)
        } catch (t: Throwable) {
            throw IllegalStateException("找不到目标应用: $targetPkg")
        }
        // 版本号在 PackageInfo 上（ApplicationInfo 没有）。取不到不算失败：
        // 老记录本来就没有版本号，这里读不到就按未知处理，不能因此挡住打包。
        val srcInfo = runCatching { pm.getPackageInfo(targetPkg, 0) }.getOrNull()
        val srcVersionCode = srcInfo?.longVersionCode ?: 0L
        val srcVersionName = srcInfo?.versionName ?: ""

        val splits = ai.splitSourceDirs
        if (!splits.isNullOrEmpty()) {
            throw IllegalStateException(
                "「$targetLabel」是分片安装包（有 ${splits.size} 个 split），暂不支持。\n" +
                    "请在设置里卸载它，改用完整 APK 重新安装后再试。"
            )
        }

        val src = File(ai.sourceDir)
        if (!src.exists() || !src.canRead()) {
            throw IllegalStateException("读不到目标 APK：${ai.sourceDir}")
        }

        val clonePkg = TemplateSpec.clonePkg(cloneId)
        // 拿 aapt2 生成的原始清单来改写，而不是从零拼一份 —— 原因见 AxmlPatcher 的类注释
        val originalManifest = readEntry(src, ENTRY_MANIFEST)
            ?: throw IllegalStateException("目标 APK 里没有 $ENTRY_MANIFEST")
        reporter.emit(BuildProgress(BuildStage.PREPARE, 1, "改写清单"), force = true)
        control.checkCancelled()
        val patched = AxmlPatcher.patch(originalManifest, targetPkg, clonePkg, label)

        // targetSdk ≥ 31 时，带 intent-filter 却没写 android:exported 的组件会让整份清单非法。
        // 正常应用不可能同时满足「装在自己机器上」和「targetSdk 高且缺 exported」，
        // 所以走到这里说明清单有异常 —— 与其让系统在最后一步回一个笼统的
        // INSTALL_PARSE_FAILED_MANIFEST_MALFORMED，不如现在就把话说清楚。
        if (patched.targetSdk >= 31 && patched.missingExported.isNotEmpty()) {
            throw IllegalStateException(
                "「$targetLabel」的清单不合法：${patched.missingExported.first()} 带 intent-filter " +
                    "但没声明 android:exported，targetSdk=${patched.targetSdk} 时系统会拒绝解析"
            )
        }

        val raw = File(workDir, "raw-$clonePkg.apk")
        // 目录可能在上一次构建之后被清掉（用户点了「清除缓存」、或系统回收了空间）
        if (!workDir.isDirectory) workDir.mkdirs()
        if (!workDir.isDirectory) {
            throw IllegalStateException("暂存目录不可用：${workDir.absolutePath}")
        }
        val totalBytes = uncompressedSize(src)
        reporter.emit(BuildProgress(BuildStage.COPY, 2, "准备拷贝"), force = true)
        try {
            assemble(src, raw, patched.bytes, targetPkg, clonePkg, arscRename, control, reporter, totalBytes)
        } catch (e: BuildCancelledException) {
            runCatching { raw.delete() }
            throw e
        } catch (e: java.io.FileNotFoundException) {
            // 最常见的是写到一半目录被删：报一句人话，别把 ENOENT 原样丢给用户
            runCatching { raw.delete() }
            throw IllegalStateException(
                "打包途中暂存目录被清掉了（${e.message}）。多半是存储空间紧张，请清理一些空间后重试。"
            )
        } catch (e: java.io.IOException) {
            runCatching { raw.delete() }
            throw IllegalStateException(
                "写入分身包失败：${e.message ?: e.javaClass.simpleName}。请检查存储空间是否充足。"
            )
        }
        control.checkCancelled()
        reporter.emit(BuildProgress(BuildStage.SIGN, 78, "正在重新签名"), force = true)
        val signed = File(workDir, "clone-$clonePkg.apk")
        SigningEngine.sign(ctx, raw, signed)
        raw.delete()

        // 装之前先自检：包名、签名、权限撞名。确定性失败在这里就报清楚，
        // 不用等系统弹窗点完再看到一个笼统的「安装失败」
        reporter.emit(BuildProgress(BuildStage.VERIFY, 94, "安装前自检"), force = true)
        try {
            ApkVerifier.verify(ctx, signed, clonePkg, targetPkg, patched.targetSdk)
        } catch (t: Throwable) {
            runCatching { signed.delete() }
            throw t
        }

        InstallLog.append(
            ctx,
            "build pkg=$clonePkg size=${signed.length()} minSdk=${patched.minSdk} " +
                "targetSdk=${patched.targetSdk}${if (patched.targetSdkRaised) "(raised)" else ""} " +
                "srcVer=$srcVersionCode/$srcVersionName"
        )
        Log.i(TAG, "built clone=$clonePkg from=$targetPkg size=${signed.length()} targetSdk=${patched.targetSdk}")
        reporter.emit(BuildProgress(BuildStage.DONE, 100, "已完成"), force = true)
        return Result(
            apk = signed,
            clonePkg = clonePkg,
            sourceVersionCode = srcVersionCode,
            sourceVersionName = srcVersionName,
        )
    }

    /**
     * 目标 APK 解压后的总字节数。
     *
     * 进度条要按「已处理字节 / 总字节」走，而 ZipInputStream 读出来的是解压后的量，
     * 用 APK 文件长度（压缩后）当分母会早早冲到 100%。这里只遍历一遍中央目录，
     * 不去解压，几十毫秒的量级。
     */
    private fun uncompressedSize(apk: File): Long = try {
        ZipFile(apk).use { zf ->
            val es = zf.entries()
            var sum = 0L
            while (es.hasMoreElements()) {
                val size = es.nextElement().size
                if (size > 0) sum += size
            }
            sum
        }
    } catch (t: Throwable) {
        // 读不到就退回文件长度：进度会偏快，但不至于让整条流程失败
        apk.length()
    }

    private fun readEntry(apk: File, name: String): ByteArray? = try {
        ZipFile(apk).use { zf ->
            val e = zf.getEntry(name) ?: return null
            zf.getInputStream(e).use { it.readBytes() }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "读取 $name 失败: ${t.message}")
        null
    }

    private fun assemble(
        src: File,
        out: File,
        manifest: ByteArray,
        targetPkg: String,
        clonePkg: String,
        arscRename: Boolean,
        control: BuildControl,
        reporter: ProgressThrottle,
        totalBytes: Long,
    ) {
        // 拷贝阶段占总权重的绝大部分，这里把它折算到 2%–78% 之间
        var copied = 0L
        fun onBytes(n: Long) {
            copied += n
            val frac = if (totalBytes > 0) (copied.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
            reporter.emit(
                BuildProgress(
                    BuildStage.COPY,
                    2 + (76 * frac).toInt(),
                    "已处理 ${Formats.size(copied)} / ${Formats.size(totalBytes)}",
                )
            )
        }
        fun checkCancelled() {
            control.checkCancelled()
        }

        val counter = CountingOutputStream(BufferedOutputStream(FileOutputStream(out), 256 * 1024))
        ZipInputStream(BufferedInputStream(FileInputStream(src), 256 * 1024)).use { zis ->
            ZipOutputStream(counter).use { zos ->
                // 压缩等级 1：这些包动辄上百 MB，内容多是已压缩的媒体与 so，
                // 6 级相比 1 级只小几个百分点，却要多花一倍以上的 CPU 时间。
                zos.setLevel(1)
                var e: ZipEntry? = zis.nextEntry
                while (e != null) {
                    checkCancelled()
                    val name = e.name
                    if (name.startsWith("META-INF/")) {
                        zis.closeEntry(); e = zis.nextEntry; continue   // 丢掉原签名
                    }
                    if (name == ENTRY_MANIFEST) {
                        zis.closeEntry(); e = zis.nextEntry; continue   // 换成改写过的清单
                    }

                    val needBytes = name == ENTRY_ARSC || name.endsWith(".so")
                    when {
                        needBytes -> {
                            // arsc / so 要保持原始字节算 CRC，只能整块读；
                            // 其余条目（assets、大图、字体）直接流式搬运，避免几十上百 MB 的临时数组
                            val data = zis.readBytes()
                            checkCancelled()
                            if (name == ENTRY_ARSC && arscRename) {
                                val ok = ArscPatcher.patchPackageName(data, targetPkg, clonePkg)
                                Log.i(TAG, "arsc package patched=$ok")
                            }
                            // resources.arsc 必须不压缩且 4 字节对齐（API 30+）；
                            // so 不压缩 + 页对齐，即使目标应用原本 extractNativeLibs=false 也能直接映射
                            writeEntry(zos, counter, name, data, align = if (name == ENTRY_ARSC) 4 else 4096)
                            onBytes(data.size.toLong())
                        }
                        // 原包就没压缩的条目（大量游戏的 assets、部分资源）原样写 STORED：
                        // 省掉一次「解开再压回去」，这是大包提速最明显的一处
                        e.method == ZipEntry.STORED && e.size > 0 && e.crc >= 0 -> {
                            val ne = ZipEntry(name)
                            ne.method = ZipEntry.STORED
                            ne.size = e.size
                            ne.compressedSize = e.size
                            ne.crc = e.crc
                            zos.putNextEntry(ne)
                            copyCounting(zis, zos, ::onBytes, ::checkCancelled)
                            zos.closeEntry()
                        }
                        else -> {
                            zos.putNextEntry(ZipEntry(name))
                            copyCounting(zis, zos, ::onBytes, ::checkCancelled)
                            zos.closeEntry()
                        }
                    }

                    zis.closeEntry()
                    e = zis.nextEntry
                }
                writeEntry(zos, counter, ENTRY_MANIFEST, manifest)
            }
        }
        onBytes(manifest.size.toLong())
    }

    /**
     * 边搬边数、顺带查取消。
     *
     * 不复用 `InputStream.copyTo` 是因为它既不给回调也没法中途停下；
     * 缓冲块取 256KB，取消的响应延迟最多就是一块的大小。
     */
    private fun copyCounting(
        input: java.io.InputStream,
        out: java.io.OutputStream,
        onBytes: (Long) -> Unit,
        checkCancelled: () -> Unit,
    ) {
        val buf = ByteArray(256 * 1024)
        while (true) {
            checkCancelled()
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            onBytes(n.toLong())
        }
    }

    /**
     * 写一个 entry。需要不压缩的（arsc / so）顺便做 zipalign：
     * 在 local header 后面塞一个 0xd935 的扩展字段把数据起始位置推到 [align] 的整数倍。
     * 不做这一步，部分设备会拒绝加载未对齐的 arsc / so。
     */
    private fun writeEntry(
        zos: ZipOutputStream,
        counter: CountingOutputStream,
        name: String,
        data: ByteArray,
        align: Int = 0,
    ) {
        val ne = ZipEntry(name)
        if (align > 0) {
            ne.method = ZipEntry.STORED
            ne.size = data.size.toLong()
            ne.compressedSize = data.size.toLong()
            ne.crc = CRC32().apply { update(data) }.value
            ne.extra = padding(counter.written, name, align)
        }
        zos.putNextEntry(ne)
        zos.write(data)
        zos.closeEntry()
    }

    /** 计算需要的扩展字段，使 localHeader(30) + 文件名 + extraLength 之后正好对齐 */
    private fun padding(offset: Long, name: String, align: Int): ByteArray? {
        val nameLen = name.toByteArray(Charsets.UTF_8).size
        var pad = (((align - ((offset + 30 + nameLen) % align)) % align)).toInt()
        if (pad in 1 until 4) pad += align            // 扩展字段自身至少要 4 字节
        if (pad == 0) return null
        val b = ByteArray(pad)
        b[0] = 0x35; b[1] = 0xd9.toByte()             // id = 0xd935（zipalign 用的那个）
        val payload = pad - 4
        b[2] = (payload and 0xff).toByte()
        b[3] = ((payload ushr 8) and 0xff).toByte()
        if (payload >= 2) {
            b[4] = (align and 0xff).toByte()
            b[5] = ((align ushr 8) and 0xff).toByte()
        }
        return b
    }

    /** 只用来数当前写到第几个字节，便于自己做对齐 */
    private class CountingOutputStream(out: java.io.OutputStream) : java.io.FilterOutputStream(out) {
        var written = 0L
            private set

        override fun write(b: Int) {
            out.write(b); written++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len); written += len
        }
    }
}
