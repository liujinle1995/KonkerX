package com.huanzhi.manager.core

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/**
 * 加固壳特征检测。
 *
 * 为什么需要：加固后的应用在启动时由壳先接管（解密 dex、校验包完整性、反调试），
 * 分身必然是新签名、清单与资源表也被改过，壳一旦校验不过就会停在启动页 ——
 * 表现是进程活着、CPU 为 0、没有崩溃日志、首帧永远画不出来，用户只看到「卡住」。
 *
 * 这类失败在装完之后才发现代价很高（红果一份就 129MB），所以在选应用时就先标出来。
 * 只做「特征 so / assets 文件名」识别，不碰壳的逻辑，也不需要网络。
 */
object HardeningDetector {

    private const val PREFS = "hardening_cache"

    /**
     * 特征串（全部小写，按 APK 内部路径匹配）→ 加固品牌。
     *
     * 取值口径：只放**确定属于壳**的文件名，宁缺勿滥。
     * 像 libyidun / libexec 这类常被正常 SDK 也带着的名字不放进来，避免满屏误报。
     */
    private val SIGNS: List<Pair<String, String>> = listOf(
        "libjiagu" to "360 加固",
        "libshella-" to "腾讯乐固",
        "libshellx-" to "腾讯乐固",
        "libtosprotection" to "腾讯乐固",
        "libprotectclass" to "腾讯乐固",
        "libshell-super" to "腾讯御安全",
        "libdexhelper" to "梆梆加固",
        "libsecshell" to "梆梆加固",
        "libsecexe" to "梆梆加固",
        "libsecmain" to "梆梆加固",
        "libmobisec" to "阿里聚安全",
        "libijm" to "爱加密",
        "ijiami.dat" to "爱加密",
        "ijiami.ajm" to "爱加密",
        "libexecmain" to "爱加密",
        "libbaiduprotect" to "百度加固",
        "libchaosvmp" to "娜迦加固",
        "libddog" to "娜迦加固",
        "libfdog" to "娜迦加固",
        "libnagaina" to "娜迦加固",
        "libnesec" to "网易易盾",
        "libkwscmm" to "几维加固",
        "libkwscr" to "几维加固",
        "libkwslinker" to "几维加固",
        "libx3g" to "顶象加固",
        "libepic_protect" to "Epic 加固",
        "libdexprotector" to "Dex 加固"
    )

    /** 进程内结果缓存。空串表示「扫过、确认没有壳」—— 不能直接存 null，Map 不认 */
    private val memory = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val NONE = ""

    /** 未扫过、或扫过但没壳都返回 null，用 isProbed() 区分 */
    fun peek(pkg: String): String? = memory[pkg]?.takeIf { it.isNotEmpty() }

    fun isProbed(pkg: String): Boolean = memory.containsKey(pkg)

    /**
     * 带缓存的检测。缓存键带上 versionCode + lastUpdateTime，
     * 目标应用升级后会自动重扫。
     */
    fun detectCached(ctx: Context, pkg: String): String? {
        if (memory.containsKey(pkg)) return peek(pkg)

        val stamp = stampOf(ctx, pkg)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(pkg, null)
        if (raw != null && stamp.isNotEmpty()) {
            val cut = raw.indexOf('|')
            if (cut > 0 && raw.substring(0, cut) == stamp) {
                val brand = raw.substring(cut + 1)
                memory[pkg] = brand
                return brand.ifEmpty { null }
            }
        }

        // 读 APK 失败（正在升级、分包损坏、没权限）不写缓存，下次还要重扫
        val brand = try {
            detect(ctx, pkg)
        } catch (t: Throwable) {
            return null
        }
        memory[pkg] = brand ?: NONE
        // 版本拿不到（应用刚被卸载等）同样不写，免得留下脏键
        if (stamp.isNotEmpty()) {
            sp.edit().putString(pkg, "$stamp|${brand ?: ""}").apply()
        }
        return brand
    }

    /** 直接扫，不走缓存 */
    fun detect(ctx: Context, pkg: String): String? {
        val ai = try {
            ctx.packageManager.getApplicationInfo(pkg, 0)
        } catch (t: Throwable) {
            return null
        }
        // base + split：so 常被拆进 config.arm64_v8a.apk 之类的分包
        val files = ArrayList<File>(4)
        ai.sourceDir?.let { files.add(File(it)) }
        ai.splitSourceDirs?.forEach { if (it != null) files.add(File(it)) }

        for (f in files) {
            val brand = scan(f)
            if (brand != null) return brand
        }
        return null
    }

    private fun scan(file: File): String? {
        if (!file.isFile) return null
        // 这里不吞异常：调用方要能区分「扫完没有壳」和「根本没读到」，
        // 后者不该写进缓存，否则一次临时失败会把徽标永久按下去
        return ZipFile(file).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name.lowercase()
                // 只看 so 与 assets，dex 与资源条目跳过能省掉大半遍历
                if (!name.startsWith("lib/") && !name.startsWith("assets/")) continue
                for ((needle, brand) in SIGNS) {
                    if (name.contains(needle)) return brand
                }
            }
            null
        }
    }

    private fun stampOf(ctx: Context, pkg: String): String = try {
        val pi = ctx.packageManager.getPackageInfo(pkg, 0)
        "${pi.longVersionCode}/${pi.lastUpdateTime}"
    } catch (t: Throwable) {
        ""
    }
}
