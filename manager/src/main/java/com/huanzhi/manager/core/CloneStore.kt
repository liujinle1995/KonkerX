package com.huanzhi.manager.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CloneRecord(
    val id: Int,
    val clonePkg: String,
    val targetPkg: String,
    val targetLabel: String,
    val label: String,
    val createdAt: Long,
    val apkPath: String,
    /**
     * 这条分身有没有真的装进过系统。
     *
     * 卸载后自动删记录全靠它区分两种情况 —— 两者的表象完全一样（包不在、apkPath 也早删了）：
     *   · true  → 曾经装好过，现在系统里没有 = 用户自己卸了，记录该跟着走；
     *   · false → 装到一半失败 / 用户取消了确认框，记录得留着，方便点它重装。
     * 老版本没有这个字段，[CloneStore.syncWithSystem] 会给「此刻装着的」记录补上。
     */
    val installedOnce: Boolean = false,
    /**
     * 这份分身是从源应用的哪个版本打出来的。
     *
     * 分身是「构建那一刻的快照」，源应用更新不会自动跟 —— 想跟只能重建。
     * 但没有这两个值，界面上根本没法说出「你这份还是 8.2，源应用已经 8.5 了」，
     * 用户只能靠自己去应用商店里对版本号，等于这个能力不存在。
     *
     * 0 / 空串表示未知：3.4 之前的记录、以及从别处导入的老记录都没有这两位。
     * 未知时不做任何「可更新」判断 —— 宁可不说，也不要凭猜给人一个错角标。
     */
    val sourceVersionCode: Long = 0L,
    val sourceVersionName: String = "",
)

/**
 * 分身元数据。存私有目录下的 json，够用且零依赖。
 *
 * 注意这里记的是「创作记录」而不是「已安装列表」：一个分身装到系统里以后，
 * 这条记录仍然是它唯一的身份说明（包名 = 序号，改不回来），
 * 所以清理时只该删那些**装也装不上、包也没了**的死记录，见 [pruneDead]。
 */
class CloneStore(ctx: Context) {

    private val app = ctx.applicationContext
    private val file = File(app.filesDir, "clones.json")

    fun all(): List<CloneRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CloneRecord(
                    id = o.getInt("id"),
                    clonePkg = o.getString("clonePkg"),
                    targetPkg = o.getString("targetPkg"),
                    targetLabel = o.optString("targetLabel"),
                    label = o.getString("label"),
                    createdAt = o.optLong("createdAt"),
                    apkPath = o.optString("apkPath"),
                    installedOnce = o.optBoolean("installedOnce", false),
                    sourceVersionCode = o.optLong("sourceVersionCode", 0L),
                    sourceVersionName = o.optString("sourceVersionName", ""),
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun save(records: List<CloneRecord>) {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("clonePkg", r.clonePkg)
                    put("targetPkg", r.targetPkg)
                    put("targetLabel", r.targetLabel)
                    put("label", r.label)
                    put("createdAt", r.createdAt)
                    put("apkPath", r.apkPath)
                    put("installedOnce", r.installedOnce)
                    put("sourceVersionCode", r.sourceVersionCode)
                    put("sourceVersionName", r.sourceVersionName)
                }
            )
        }
        file.writeText(arr.toString())
    }

    fun add(record: CloneRecord) {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.clonePkg == record.clonePkg }
        // 覆盖同一包名时保持原位置：否则改个名就会在列表里跳到最底下
        if (idx >= 0) list[idx] = record else list.add(record)
        save(list)
    }

    fun remove(clonePkg: String) {
        save(all().filterNot { it.clonePkg == clonePkg })
    }

    fun find(clonePkg: String): CloneRecord? = all().firstOrNull { it.clonePkg == clonePkg }

    /** 记一笔「这份真的装进系统了」，卸载后据此判断该不该删记录 */
    fun markInstalled(clonePkg: String) {
        val list = all()
        val i = list.indexOfFirst { it.clonePkg == clonePkg }
        if (i < 0 || list[i].installedOnce) return
        val next = list.toMutableList()
        next[i] = next[i].copy(installedOnce = true)
        save(next)
    }

    /**
     * 跟系统里的实际安装情况对一次账。
     *
     * 分三种：
     *   1. 系统里有 → 打上 installedOnce（老版本的记录靠这一步补标记）；
     *   2. 标记过装好、现在系统里没有 → 用户把分身卸了，记录一并删掉；
     *   3. 从没装成过（失败 / 取消）→ 留着，列表里显示「未安装」，点一下能重装。
     *
     * @return 被删掉的记录（界面可以据此提示一句）
     */
    fun syncWithSystem(): List<CloneRecord> {
        val list = all()
        if (list.isEmpty()) return emptyList()
        val pm = app.packageManager
        var changed = false
        val keep = ArrayList<CloneRecord>(list.size)
        val gone = ArrayList<CloneRecord>()

        for (r in list) {
            if (installed(r.clonePkg, pm)) {
                if (!r.installedOnce) {
                    keep += r.copy(installedOnce = true)
                    changed = true
                } else {
                    keep += r
                }
            } else if (r.installedOnce) {
                gone += r
                changed = true
            } else {
                keep += r
            }
        }
        if (changed) save(keep)
        return gone
    }

    /**
     * 分配一个未被占用的序号。
     *
     * 除了看本地记录，还要避开「系统里还装着、但记录已经没了」的包名
     * （点过「删除记录」、或重装过本工具都会留下这种情况）。
     * 不避开的话，新分身会撞上同包名的旧分身：两者签名不同，覆盖安装会被系统
     * 以 INSTALL_FAILED_UPDATE_INCOMPATIBLE 拒掉 —— 表现就是「建了两个只装上一个」。
     */
    fun nextId(): Int {
        val used = all().mapTo(HashSet()) { it.id }
        val pm = app.packageManager
        var i = 1
        while (i < 1000) {
            if (i !in used && !installed(TemplateSpec.clonePkg(i), pm)) return i
            i++
        }
        error("分身数量已达上限 999")
    }

    private fun installed(pkg: String, pm: android.content.pm.PackageManager): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (t: Throwable) {
        false
    }

    /** 某个目标应用已经建过几个分身，用于生成「应用名 · 分身N」这种默认名 */
    fun countFor(targetPkg: String): Int = all().count { it.targetPkg == targetPkg }

    fun defaultName(targetLabel: String, targetPkg: String, offset: Int = 1): String {
        // 老版本用全局序号命名，可能出现「宝可梦图鉴 · 分身3」和「宝可梦图鉴 · 分身2」并存，
        // 所以除了按数量递推，还要跳过已经被占用的名字
        val used = all().mapTo(HashSet()) { it.label }
        var n = countFor(targetPkg) + offset
        while (true) {
            val name = "${targetLabel.take(12)} · 分身$n"
            if (name !in used) return name
            n++
        }
    }

    /**
     * 用户自己起的名字（逐个命名时）撞名就加「 (2)」。
     *
     * 自动编号的路径不需要它，但逐份命名允许用户把三份都打成一模一样的「微信」，
     * 那样桌面上会出现三个同名图标，装完根本分不出哪个是哪个。
     */
    fun uniqueName(desired: String, used: MutableSet<String>): String {
        var name = desired
        var i = 2
        while (!used.add(name)) {
            name = "$desired ($i)"
            i++
        }
        return name
    }

    /**
     * 清掉失效记录：既没装进系统、本地安装包也没了的分身。
     *
     * 这类记录留着只会让列表永远挂一行点不动的「未安装」——
     * 用户清完缓存看到它们还在，会以为清理没生效。
     *
     * @return 清掉的条数
     */
    fun pruneDead(): Int {
        val list = all()
        val alive = list.filter { !isDead(it) }
        val removed = list.size - alive.size
        if (removed > 0) save(alive)
        return removed
    }

    /** 有多少条失效记录（清理前的预估，不落盘） */
    fun deadCount(): Int = all().count { isDead(it) }

    private fun isDead(rec: CloneRecord): Boolean =
        !installed(rec.clonePkg, app.packageManager) &&
            (rec.apkPath.isEmpty() || !File(rec.apkPath).exists())
}
