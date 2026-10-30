package com.huanzhi.manager.core

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * 二进制清单（AXML）改写器。
 *
 * ## 为什么是「改写」而不是「生成」
 *
 * 最早这里是 ManifestGenerator + AxmlBuilder：按 PackageManager 读到的信息从零拼一份新清单。
 * 实测有两个致命问题（都用对照实验证实过）：
 *
 *  1. 手写出来的清单 aapt2 能解析，但**框架解析出的 android:theme 丢失**，
 *     AppCompatActivity 直接崩 "You need to use a Theme.AppCompat theme"。
 *     同一份 APK 换成 aapt2 原始清单（只把包名字符串等长替换）就完全正常，
 *     说明问题出在手写序列化，而不是包名 / 资源之类的外部原因。
 *  2. 从零生成只能覆盖"我知道的"那些标签，`<provider>`、`<receiver>`、`<meta-data>`、
 *     `appComponentFactory`、自声明 `<permission>` 全都会丢。其中丢 `<permission>`
 *     会直接导致 INSTALL_FAILED_DUPLICATE_PERMISSION —— 原包已经声明过同名权限，
 *     分身再声明一次就装不上（AGP 给每个应用都自动加了一条自定义权限，
 *     所以这不是个别现象）。
 *
 * 所以改成：**拿目标 APK 里 aapt2 生成的原始清单，只改必须改的字段**，
 * 其余每个字节原样保留。主题、图标、权限、provider、meta-data 全部天然正确。
 *
 * ## targetSdk 为什么**不能**往上抬
 *
 * 1.0–1.4 曾把 targetSdk 一路抬到 34（理由写在旧注释里：「Play Protect 拦太低的包」）。
 * 这是个错判，代价是两个真实的失败：
 *
 *  1. **清单直接非法**。targetSdk ≥ 31 时，带 intent-filter 的组件必须显式写
 *     `android:exported`；老应用没写（它们当时不需要写），抬高后系统解析就成了
 *     INSTALL_PARSE_FAILED_MANIFEST_MALFORMED —— 一个 129MB 的包建好、签名好、
 *     弹窗点完，最后一步被拒（红果免费短剧实测）。
 *  2. **运行时行为被改掉**。targetSdk 是「应用向系统声明的行为版本」：
 *     ≥31 的 PendingIntent 可变性、前台服务类型、通知权限、file:// URI 禁令……
 *     老代码没按新规则写，抬高后即使装上了也常常一启动就崩。
 *
 * 所以策略是：**沿用原应用的 targetSdk**，只在它低于「系统允许安装的门槛」时抬到门槛
 * （Android 14 起 <23 装不上，Android 15 起 <24 装不上），其余一律不动。
 *
 * ## 为什么能不动节点结构
 *
 * 字符串池是**追加式**的：原有字符串下标一个都不变，新串追加在末尾。
 * 于是所有节点块（chunk）的尺寸都不用重算，只需要
 *   1. 重建字符串池（原串序不变 + 追加新串）；
 *   2. 把被改写属性记录里的 rawValue / typedValue.data 指向新串；
 *   3. 平移所有块的位置（池子变长了）并修正外层 totalSize。
 *
 * 参考 AOSP ResourceTypes.h：
 *   ResXMLTree_node      { ResChunk_header header; u32 lineNumber; ResStringPool_ref comment; }
 *   ResXMLTree_attrExt   { ResStringPool_ref ns, name; u16 attributeStart, attributeSize,
 *                          attributeCount, idIndex, classIndex, styleIndex; }
 *   ResXMLTree_attribute { ResStringPool_ref ns, name, rawValue; Res_value typedValue; }
 *   Res_value            { u16 size; u8 res0; u8 dataType; u32 data; }
 */
object AxmlPatcher {

    private const val TAG = "HuanZhi"

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_XML_START_ELEMENT = 0x0102
    private const val RES_XML_END_ELEMENT = 0x0103

    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_BOOLEAN = 0x12

    /** 属性记录内部字段偏移 */
    private const val OFF_RAW = 8
    private const val OFF_TYPE = 15
    private const val OFF_DATA = 16
    private const val ATTR_SIZE_MIN = 20

    /** 节点公共头：8 字节 chunk header + lineNumber + comment */
    private const val NODE_HEADER = 16

    /**
     * 这些组件带 intent-filter 时，targetSdk ≥ 31 必须显式声明 android:exported，
     * 否则清单会被判为非法。见 [Patched.missingExported]。
     */
    private val COMPONENT_ELEMENTS = setOf(
        "activity", "activity-alias", "service", "receiver",
    )

    /** android:name 是「类名」的标签，需要把相对写法补成绝对类名 */
    private val CLASS_NAME_ELEMENTS = setOf(
        "application", "activity", "activity-alias",
        "service", "receiver", "provider", "instrumentation",
    )

    /** android:name 是「权限名」的标签，需要跟着改包名前缀 */
    private val PERMISSION_ELEMENTS = setOf(
        "permission", "uses-permission", "uses-permission-sdk-23",
        "permission-group", "permission-tree",
    )

    /** 其中这几个是**声明**权限的标签；声明名必须全部改掉，否则会和原应用撞名 */
    private val DECLARE_PERMISSION_ELEMENTS = setOf(
        "permission", "permission-group", "permission-tree",
    )

    /**
     * [width] 必须精确：dataType 只占 1 字节，若按 4 字节写会把相邻的 data 字段清零。
     * （踩过：label 的 data 被写成 0，应用名直接变成字符串池里第 0 个串 "theme"。）
     */
    private class Patch(val pos: Int, val fieldOffset: Int, val value: Int, val width: Int = 4)

    /** 清单里与 SDK 版本相关的信息，用来决定 targetSdk 要不要改、改完会不会非法 */
    private class SdkInfo(
        val minSdk: Int,
        val declaredTargetSdk: Int,
        val missingExported: List<String>,
    )

    /**
     * @param bytes           改写后的清单字节
     * @param minSdk          原应用声明的 minSdk（0 = 未声明）
     * @param targetSdk       分身实际生效的 targetSdk
     * @param targetSdkRaised 是否因为低于系统安装门槛而被抬起
     * @param missingExported 有 intent-filter 但没写 android:exported 的组件（targetSdk < 31 时合法）
     */
    class Patched(
        val bytes: ByteArray,
        val minSdk: Int,
        val targetSdk: Int,
        val targetSdkRaised: Boolean,
        val missingExported: List<String>,
    )

    /**
     * 系统允许安装的最低 targetSdk。只抬到门槛，绝不主动往最新的版本抬（原因见类注释）。
     */
    private fun installFloor(): Int = when {
        android.os.Build.VERSION.SDK_INT >= 35 -> 24   // Android 15 起 <24 装不上
        android.os.Build.VERSION.SDK_INT >= 34 -> 23   // Android 14 起 <23 装不上
        else -> 0
    }

    /**
     * @param src        目标 APK 里的原始 AndroidManifest.xml
     * @param targetPkg  目标应用包名
     * @param clonePkg   分身包名
     * @param label      分身要显示的桌面名称
     */
    fun patch(src: ByteArray, targetPkg: String, clonePkg: String, label: String): Patched {
        val total = i32(src, 4)
        if (u16(src, 0) != RES_XML_TYPE) {
            throw IllegalStateException("不是 AXML（type=0x${u16(src, 0).toString(16)}）")
        }
        if (total > src.size) throw IllegalStateException("AXML 长度非法: $total > ${src.size}")

        // ---------------- 字符串池 ----------------
        val pOff = 8
        if (u16(src, pOff) != RES_STRING_POOL_TYPE) {
            throw IllegalStateException("清单首个块不是字符串池")
        }
        val pHdrSize = u16(src, pOff + 2)
        val pSize = i32(src, pOff + 4)
        val strCount = i32(src, pOff + 8)
        val styleCount = i32(src, pOff + 12)
        val flags = i32(src, pOff + 16)
        val stringsStart = i32(src, pOff + 20)
        if (styleCount != 0) throw IllegalStateException("清单字符串池带 style，暂不支持")
        val utf8 = (flags and 0x00000100) != 0

        val strings = ArrayList<String>(strCount + 8)
        val lookup = HashMap<String, Int>(strCount * 2)
        for (i in 0 until strCount) {
            val rel = i32(src, pOff + pHdrSize + i * 4)
            val s = readString(src, pOff + stringsStart + rel, utf8)
            strings.add(s)
            if (!lookup.containsKey(s)) lookup[s] = i
        }
        val originalCount = strings.size

        // ---------------- resource map ----------------
        val resMapOff = pOff + pSize
        if (u16(src, resMapOff) != RES_XML_RESOURCE_MAP_TYPE) {
            throw IllegalStateException("清单缺少 resource map，无法安全改写")
        }
        val rmSize = i32(src, resMapOff + 4)
        val rmCount = (rmSize - 8) / 4
        val resIds = IntArray(rmCount) { i32(src, resMapOff + 8 + it * 4) }
        val bodyStart = resMapOff + rmSize

        // ---------------- 扫描元素，登记补丁 ----------------
        val patches = ArrayList<Patch>(48)
        val elements = ArrayList<Int>(64)

        var off = bodyStart
        while (off + 8 <= total) {
            val type = u16(src, off)
            val size = i32(src, off + 4)
            if (size <= 0 || off + size > total) break
            if (type == RES_XML_START_ELEMENT) elements.add(off)
            off += size
        }

        // ---------------- 版本信息：targetSdk 该怎么处理 ----------------
        val sdk = readSdkInfo(src, bodyStart, total, elements, strings, resIds)
        val baseTarget =
            if (sdk.declaredTargetSdk > 0) sdk.declaredTargetSdk else sdk.minSdk.coerceAtLeast(1)
        val floor = installFloor()
        val effectiveTarget = maxOf(baseTarget, floor)
        val raised = effectiveTarget > baseTarget
        if (raised && sdk.declaredTargetSdk <= 0) {
            // 清单里根本没有 targetSdk 属性，改不了；这种包在 Android 14+ 上会被系统拒绝安装。
            // 硬插一个属性要重排节点、还要动 resource map（属性名没有 resId 就解析不出来），
            // 风险远大于收益，如实写日志让用户知道卡在哪。
            Log.w(TAG, "清单未声明 android:targetSdkVersion，无法抬到 $floor（minSdk=${sdk.minSdk}）")
        }

        // 第一遍：登记本清单**声明**的所有权限名 → 分身对应的新名字。
        // 必须全量改名，而不能只改「以目标包名开头」的那些，理由见 renamePermission。
        val permRenames = LinkedHashMap<String, String>()
        for (e in elements) {
            val el = elementName(src, e, strings) ?: continue
            if (el !in DECLARE_PERMISSION_ELEMENTS) continue
            val v = androidAttr(src, e, strings, resIds, android.R.attr.name) ?: continue
            if (v.isNotEmpty()) permRenames[v] = renamePermission(v, targetPkg, clonePkg)
        }

        // 第二遍：按映射改写属性
        for (e in elements) {
            collect(src, e, strings, lookup, resIds, targetPkg, clonePkg, label, permRenames, floor, patches)
        }

        // ---------------- 重建字符串池并应用补丁 ----------------
        val newPool = buildPool(strings, utf8)
        val delta = (8 + newPool.size) - resMapOff
        val newTotal = 8 + newPool.size + (total - resMapOff)

        val out = ByteArray(newTotal)
        System.arraycopy(src, 0, out, 0, 8)
        System.arraycopy(newPool, 0, out, 8, newPool.size)
        System.arraycopy(src, resMapOff, out, 8 + newPool.size, total - resMapOff)
        writeInt(out, 4, newTotal)

        for (p in patches) {
            val at = p.pos + delta + p.fieldOffset
            if (at < 0) continue
            if (p.width == 1) {
                if (at < newTotal) out[at] = (p.value and 0xff).toByte()
            } else if (at + 4 <= newTotal) {
                writeInt(out, at, p.value)
            }
        }

        Log.i(
            TAG,
            "axml patched: $total -> $newTotal bytes, strings $originalCount -> ${strings.size}, " +
                "patches=${patches.size}, targetSdk ${sdk.declaredTargetSdk} -> $effectiveTarget" +
                (if (sdk.missingExported.isEmpty()) "" else ", 组件缺 exported=${sdk.missingExported.size}")
        )
        return Patched(
            bytes = out,
            minSdk = sdk.minSdk,
            targetSdk = effectiveTarget,
            targetSdkRaised = raised,
            missingExported = sdk.missingExported,
        )
    }

    // ------------------------------------------------------------------ 元素处理

    private fun collect(
        src: ByteArray,
        chunkOff: Int,
        strings: MutableList<String>,
        lookup: MutableMap<String, Int>,
        resIds: IntArray,
        targetPkg: String,
        clonePkg: String,
        label: String,
        permRenames: Map<String, String>,
        targetSdkFloor: Int,
        patches: MutableList<Patch>,
    ) {
        val base = chunkOff + NODE_HEADER
        val nameIdx = i32(src, base + 4)
        if (nameIdx !in strings.indices) return
        val elName = strings[nameIdx]
        val attrStart = u16(src, base + 8)
        val attrSize = u16(src, base + 10)
        val attrCount = u16(src, base + 12)
        if (attrSize < ATTR_SIZE_MIN) return
        val attrBase = base + attrStart

        fun intern(s: String): Int = lookup[s] ?: run {
            strings.add(s)
            val i = strings.size - 1
            lookup[s] = i
            i
        }

        fun setString(pos: Int, value: String, wasString: Boolean) {
            val idx = intern(value)
            patches.add(Patch(pos, OFF_RAW, idx))
            patches.add(Patch(pos, OFF_DATA, idx))
            if (!wasString) patches.add(Patch(pos, OFF_TYPE, TYPE_STRING, 1))
        }

        for (i in 0 until attrCount) {
            val p = attrBase + i * attrSize
            if (p + ATTR_SIZE_MIN > src.size) break
            val aName = i32(src, p + 4)
            val aRaw = i32(src, p + OFF_RAW)
            val aType = src[p + OFF_TYPE].toInt() and 0xff
            val aData = i32(src, p + OFF_DATA)
            val resId = if (aName in 0 until resIds.size) resIds[aName] else 0
            val isString = aType == TYPE_STRING && aData in strings.indices
            val value: String? = when {
                isString -> strings[aData]
                aRaw in strings.indices -> strings[aRaw]
                else -> null
            }
            val attrName = if (aName in strings.indices) strings[aName] else ""

            when {
                // <manifest package="...">
                resId == 0 && elName == "manifest" && attrName == "package" ->
                    setString(p, clonePkg, isString)

                // 类名 / 权限名
                resId == android.R.attr.name && value != null -> {
                    if (elName in PERMISSION_ELEMENTS) {
                        // 自己声明的权限走登记表；引用的权限只在「本清单声明过」时才改，
                        // 系统权限（android.permission.*）绝对不能动
                        val v = permRenames[value]
                            ?: if (elName in DECLARE_PERMISSION_ELEMENTS) {
                                renamePermission(value, targetPkg, clonePkg)
                            } else {
                                null
                            }
                        if (v != null && v != value) setString(p, v, isString)
                    } else if (elName in CLASS_NAME_ELEMENTS) {
                        val v = absoluteClass(value, targetPkg)
                        if (v != value) setString(p, v, isString)
                    }
                }

                // 桌面名称：改成字面量，多个分身才分得清
                resId == android.R.attr.label && elName == "application" ->
                    setString(p, label, isString)

                // 这些值通常带包名，跟着换前缀，保证多个分身互不冲突
                resId == android.R.attr.authorities ||
                    resId == android.R.attr.process ||
                    resId == android.R.attr.taskAffinity -> {
                    if (value != null) {
                        val v = replacePkg(value, targetPkg, clonePkg)
                        if (v != value) setString(p, v, isString)
                    }
                }

                // 别名页 / 父页面也可能是相对类名
                resId == android.R.attr.targetActivity ||
                    resId == android.R.attr.parentActivityName -> {
                    if (value != null) {
                        val v = absoluteClass(value, targetPkg)
                        if (v != value) setString(p, v, isString)
                    }
                }

                // 共享 uid 必须换掉，否则和原应用签名 / uid 冲突
                resId == android.R.attr.sharedUserId -> setString(p, clonePkg, isString)

                // targetSdk 只在低于系统安装门槛时抬到门槛，绝不往上抬（原因见类注释）
                resId == android.R.attr.targetSdkVersion && elName == "uses-sdk" -> {
                    if (targetSdkFloor > 0 && aData in 1 until targetSdkFloor) {
                        patches.add(Patch(p, OFF_DATA, targetSdkFloor))
                    }
                }

                // 目标 APK 里 so 可能不解压直接映射，要求页对齐；
                // 重新打包后不保证对齐，强制解压最稳。
                resId == android.R.attr.extractNativeLibs -> {
                    patches.add(Patch(p, OFF_DATA, 1))
                    if (aType != TYPE_INT_BOOLEAN) patches.add(Patch(p, OFF_TYPE, TYPE_INT_BOOLEAN, 1))
                }
            }
        }
    }

    // ------------------------------------------------------------------ 版本信息检查

    /**
     * 读出 minSdk / targetSdk，并找出「带 intent-filter 却没写 android:exported」的组件。
     *
     * 后者本身不一定是错误：targetSdk < 31 时系统允许省略（默认为 true）。
     * 但只要 targetSdk ≥ 31，省掉就成了非法清单 —— 这正是「原应用能装、分身装不上」的机理，
     * 所以这里顺便统计出来，写进日志便于事后定位。
     */
    private fun readSdkInfo(
        src: ByteArray,
        bodyStart: Int,
        total: Int,
        elements: List<Int>,
        strings: List<String>,
        resIds: IntArray,
    ): SdkInfo {
        var minSdk = 0
        var targetSdk = 0
        for (e in elements) {
            if (elementName(src, e, strings) != "uses-sdk") continue
            intAttr(src, e, resIds, android.R.attr.minSdkVersion)?.let { minSdk = it }
            intAttr(src, e, resIds, android.R.attr.targetSdkVersion)?.let { targetSdk = it }
        }
        return SdkInfo(minSdk, targetSdk, scanMissingExported(src, bodyStart, total, strings, resIds))
    }

    /** 一个在扫描过程中的元素栈帧，只对组件关心属性 */
    private class Frame(val name: String) {
        val isComponent = name in COMPONENT_ELEMENTS
        var label = name
        var exportedDeclared = false
        var hasIntentFilter = false
    }

    private fun scanMissingExported(
        src: ByteArray,
        bodyStart: Int,
        total: Int,
        strings: List<String>,
        resIds: IntArray,
    ): List<String> {
        val missing = ArrayList<String>(2)
        val stack = ArrayList<Frame>(8)
        var off = bodyStart
        while (off + 8 <= total) {
            val type = u16(src, off)
            val size = i32(src, off + 4)
            if (size <= 0 || off + size > total) break
            when (type) {
                RES_XML_START_ELEMENT -> {
                    val name = elementName(src, off, strings).orEmpty()
                    val f = Frame(name)
                    if (f.isComponent) {
                        f.label = androidAttr(src, off, strings, resIds, android.R.attr.name)
                            ?.let { shortenClass(it) } ?: "<$name>"
                        f.exportedDeclared = hasAttr(src, off, resIds, android.R.attr.exported)
                    }
                    // intent-filter 一定是组件的直接子节点
                    if (name == "intent-filter") stack.lastOrNull()?.let { if (it.isComponent) it.hasIntentFilter = true }
                    stack.add(f)
                }
                RES_XML_END_ELEMENT -> {
                    if (stack.isEmpty()) break
                    val f = stack.removeAt(stack.size - 1)
                    if (f.isComponent && f.hasIntentFilter && !f.exportedDeclared) missing.add(f.label)
                }
            }
            off += size
        }
        return missing
    }

    /** com.dragon.read.pages.splash.SplashActivity → com.dragon.read.….SplashActivity */
    private fun shortenClass(v: String): String {
        val i = v.lastIndexOf('.')
        if (i <= 0) return v
        val pkg = v.substring(0, i)
        return if (pkg.length > 24) pkg.take(16) + "…" + v.substring(i) else v
    }

    /** 元素上有没有某个 android 属性（不看取值类型） */
    private fun hasAttr(src: ByteArray, chunkOff: Int, resIds: IntArray, attrResId: Int): Boolean {
        val base = chunkOff + NODE_HEADER
        val attrStart = u16(src, base + 8)
        val attrSize = u16(src, base + 10)
        val attrCount = u16(src, base + 12)
        if (attrSize < ATTR_SIZE_MIN) return false
        val attrBase = base + attrStart
        for (i in 0 until attrCount) {
            val p = attrBase + i * attrSize
            if (p + ATTR_SIZE_MIN > src.size) return false
            val aName = i32(src, p + 4)
            val resId = if (aName in 0 until resIds.size) resIds[aName] else 0
            if (resId == attrResId) return true
        }
        return false
    }

    /** 读某个整数型 android 属性；写成字符串的（极罕见）当作没声明 */
    private fun intAttr(src: ByteArray, chunkOff: Int, resIds: IntArray, attrResId: Int): Int? {
        val base = chunkOff + NODE_HEADER
        val attrStart = u16(src, base + 8)
        val attrSize = u16(src, base + 10)
        val attrCount = u16(src, base + 12)
        if (attrSize < ATTR_SIZE_MIN) return null
        val attrBase = base + attrStart
        for (i in 0 until attrCount) {
            val p = attrBase + i * attrSize
            if (p + ATTR_SIZE_MIN > src.size) return null
            val aName = i32(src, p + 4)
            val resId = if (aName in 0 until resIds.size) resIds[aName] else 0
            if (resId != attrResId) continue
            if (src[p + OFF_TYPE].toInt() and 0xff == TYPE_STRING) return null
            return i32(src, p + OFF_DATA)
        }
        return null
    }

    // ------------------------------------------------------------------ 工具

    private fun replacePkg(v: String, targetPkg: String, clonePkg: String): String = when {
        v == targetPkg -> clonePkg
        v.startsWith("$targetPkg.") || v.startsWith("$targetPkg:") -> clonePkg + v.substring(targetPkg.length)
        else -> v
    }

    private fun elementName(src: ByteArray, chunkOff: Int, strings: List<String>): String? {
        val idx = i32(src, chunkOff + NODE_HEADER + 4)
        return if (idx in strings.indices) strings[idx] else null
    }

    /** 读某个元素上指定 android 属性的字符串值；属性不存在或不是字符串 → null */
    private fun androidAttr(
        src: ByteArray,
        chunkOff: Int,
        strings: List<String>,
        resIds: IntArray,
        attrResId: Int,
    ): String? {
        val base = chunkOff + NODE_HEADER
        val attrStart = u16(src, base + 8)
        val attrSize = u16(src, base + 10)
        val attrCount = u16(src, base + 12)
        if (attrSize < ATTR_SIZE_MIN) return null
        val attrBase = base + attrStart
        for (i in 0 until attrCount) {
            val p = attrBase + i * attrSize
            if (p + ATTR_SIZE_MIN > src.size) return null
            val aName = i32(src, p + 4)
            val resId = if (aName in 0 until resIds.size) resIds[aName] else 0
            if (resId != attrResId) continue
            val aType = src[p + OFF_TYPE].toInt() and 0xff
            val aData = i32(src, p + OFF_DATA)
            return if (aType == TYPE_STRING && aData in strings.indices) strings[aData] else null
        }
        return null
    }

    /**
     * 把「本清单声明的权限名」映射成分身专用的名字。
     *
     * 只改以目标包名开头的那些是不够的：国内大量 SDK（推送 / 广告 / 统计）会在宿主清单里
     * 声明 `com.xxx.sdk.permission.YYY` 这种**不属于宿主包命名空间**的权限，个别应用自己也会这么写。
     * 这类名字不改，分身就与原应用「重名声明」，安装时被系统直接拒：
     *
     *   INSTALL_FAILED_DUPLICATE_PERMISSION: Package <clone> attempting to redeclare
     *   permission <name> already owned by <target>
     *
     * 所以凡本清单声明的权限，一律换成以分身包名开头的新名字；
     * 引用处（uses-permission 等）走同一张映射表，保证一一对应。
     */
    private fun renamePermission(v: String, targetPkg: String, clonePkg: String): String = when {
        v.isEmpty() -> v
        v == targetPkg -> clonePkg
        v.startsWith("$targetPkg.") || v.startsWith("$targetPkg:") ->
            clonePkg + v.substring(targetPkg.length)
        // 外部命名空间：原名整体挂到分身包名下，非标识符字符转义成下划线，保证唯一且合法
        else -> clonePkg + ".sp." + v.replace(Regex("[^A-Za-z0-9_]"), "_")
    }

    /** `.MainActivity` / `MainActivity` → `com.foo.MainActivity`；已经是别的绝对类名就保持 */
    private fun absoluteClass(v: String, targetPkg: String): String = when {
        v.isEmpty() -> v
        v.startsWith(".") -> targetPkg + v
        !v.contains(".") -> "$targetPkg.$v"
        v.startsWith("$targetPkg.") -> v
        else -> v
    }

    private fun readString(src: ByteArray, p0: Int, utf8: Boolean): String {
        var p = p0
        if (utf8) {
            var shift = 0
            while (true) {
                val b = src[p++].toInt() and 0xff
                shift += 7
                if (b and 0x80 == 0) break
            }
            var bytes = 0
            shift = 0
            while (true) {
                val b = src[p++].toInt() and 0xff
                bytes = bytes or ((b and 0x7f) shl shift)
                shift += 7
                if (b and 0x80 == 0) break
            }
            return String(src, p, bytes, StandardCharsets.UTF_8)
        }
        var len = u16(src, p)
        p += 2
        if (len and 0x8000 != 0) {
            len = ((len and 0x7fff) shl 16) or u16(src, p)
            p += 2
        }
        return String(src, p, len * 2, StandardCharsets.UTF_16LE)
    }

    private fun buildPool(strings: List<String>, utf8: Boolean): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        strings.forEachIndexed { i, s ->
            offsets[i] = data.size()
            if (utf8) {
                val bytes = s.toByteArray(StandardCharsets.UTF_8)
                data.write(uleb(s.length))
                data.write(uleb(bytes.size))
                data.write(bytes)
                data.write(0)
            } else {
                val n = s.length
                if (n > 0x7fff) data.write(u16le((n ushr 16) or 0x8000))
                data.write(u16le(n and 0xffff))
                data.write(s.toByteArray(StandardCharsets.UTF_16LE))
                data.write(0)
                data.write(0)
            }
        }
        while (data.size() % 4 != 0) data.write(0)

        val headerSize = 28
        val stringsStart = headerSize + strings.size * 4
        val total = stringsStart + data.size()
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0001)
        buf.putShort(headerSize.toShort())
        buf.putInt(total)
        buf.putInt(strings.size)
        buf.putInt(0)                           // styleCount
        buf.putInt(if (utf8) 0x00000100 else 0) // flags
        buf.putInt(stringsStart)
        buf.putInt(0)                           // stylesStart
        offsets.forEach { buf.putInt(it) }
        buf.put(data.toByteArray())
        return buf.array()
    }

    private fun uleb(v0: Int): ByteArray {
        var v = v0
        val out = ByteArrayOutputStream()
        do {
            var b = v and 0x7f
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out.write(b)
        } while (v != 0)
        return out.toByteArray()
    }

    private fun u16le(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v ushr 8) and 0xff).toByte())

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    private fun i32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or
            ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or
            ((b[o + 3].toInt() and 0xff) shl 24)

    private fun writeInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xff).toByte()
        b[o + 1] = ((v ushr 8) and 0xff).toByte()
        b[o + 2] = ((v ushr 16) and 0xff).toByte()
        b[o + 3] = ((v ushr 24) and 0xff).toByte()
    }
}
