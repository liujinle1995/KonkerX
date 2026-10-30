package com.huanzhi.manager

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.huanzhi.manager.databinding.ActivityManualBinding

/**
 * 技术说明（二级页）。
 *
 * 这里写的都是能对着代码验证的事实，不写宣传语：
 * 用了哪些模块、数字从哪来、哪些事情做不到、数据去哪了。
 */
class ManualActivity : AppCompatActivity() {

    private lateinit var b: ActivityManualBinding

    private val sections = listOf(
        DocSection(
            "为什么不用运行时 hook",
            listOf(
                "常见分身工具的做法是让宿主进程加载目标应用的 dex，再拦截系统调用把包名、路径替换掉。这条路依赖大量隐藏 API 与私有实现，系统每升一个大版本就要重新适配一次。",
                "这里换成「全量重打包」：分身在磁盘上就是一个真实的、包名不同的 APK，由系统正常安装、正常启动。系统怎么改都与它无关，代价是每个分身占用的空间约等于原应用。",
                "也因此不要求原应用保持安装状态——原应用卸载后，分身照样能跑。"
            )
        ),
        DocSection(
            "模块构成",
            listOf(
                "清单改写（AxmlPatcher）：直接修改 aapt2 生成的二进制 AndroidManifest.xml，只置换包名、相对类名前缀、自定义权限、authorities、process、taskAffinity 等必要字段。targetSdk 沿用原应用的值，只在其低于系统安装门槛（Android 14 起 23、Android 15 起 24）时抬到门槛 —— 往上抬会改掉应用的运行时行为（exported 必填、PendingIntent 可变性、前台服务类型、通知权限等），老应用常因此装不上或一启动就崩。主题、provider、receiver、meta-data 全部原样保留，这也是分身能完整加载原应用资源的原因。",
                "权限改名：清单里自己声明的权限一律换成分身专属名字。只改「以目标包名开头」的那些是不够的——国内不少 SDK 会把 com.xxx.sdk.permission.YYY 这类不属于宿主命名空间的权限声明进宿主清单，不改名就会与原应用重名，安装直接被拒（INSTALL_FAILED_DUPLICATE_PERMISSION）。引用这些权限的 uses-permission 走同一张映射表同步改写。",
                "建包自检（ApkVerifier）：交出去之前先用 PackageManager 解析一遍生成的 APK，核对包名、签名块，并检查声明的权限是否与原应用撞名。确定性失败在这里就报清楚，不必等系统弹窗点完再猜。",
                "资源表改写（ArscPatcher）：把 resources.arsc 里的包名改成分身包名，保证按名字查资源（getIdentifier）仍然可用。少数应用把包名/资源名写死在 native 或脚本层，改名后反而查不到，可以在长按菜单里用「兼容模式重建」跳过这一步做对照。",
                "重打包（CloneBuilder）：dex、资源、assets、so 原样迁移；arsc 4 字节对齐、so 4096 字节对齐，部分设备会拒绝加载未对齐的包。",
                "签名（SigningEngine）：apksig 只做 v2 签名，minSdk 29。v1 已无必要，v3 会和覆盖安装的密钥轮换语义冲突。",
                "安装（ApkInstaller）：走 PackageInstaller 会话。非 root 无法静默安装，每一份都会弹一次系统确认框。会话里存的是完整副本，所以提交完成后本地那份克隆包就删掉了（装失败或取消也删），一份克隆包等于整个原应用，留着很快就能吃掉几个 GB。",
                "批量调度（BatchRunner）：构建与安装拆开，严格串行、等回执再下一份，避免多个确认框互相顶掉；单份等待时间按包体积自适应（180 秒起，最多 8 分钟）。",
                "运行采集（RuntimeProbe）：见下一节。"
            )
        ),
        DocSection(
            "运行概览怎么读数",
            listOf(
                "存储占用来自 StorageStatsManager.queryStatsForUid，按分身自己的 UID 统计应用本体、用户数据、缓存三块，需要在系统设置里授予「使用情况访问权限」。没授权时这一栏退回成安装包体积。",
                "运行状态与最近启动时间来自 UsageStatsManager 的事件流：某个包最后一笔是 ACTIVITY_RESUMED 就判定为在前台，ACTIVITY_PAUSED / STOPPED 记为不在。",
                "进程数与内存来自 /proc/<pid>/status 的 Name / Uid / VmRSS / Threads。但普通应用在 SELinux 的 untrusted_app 域内只能看到自己 UID 的进程，实测枚举结果只有 1 条，所以这一行在多数机器上不会出现。系统允许读时才显示，读不到就不显示，不会用 0 顶替。",
                "也没有用 ActivityManager.getRunningAppProcesses()：从 Android 8.0 起它只返回调用者自己的进程，拿不到分身的数据。",
                "系统不提供「某个应用是否还在后台驻留」的查询，所以顶部统计写的是「前台活跃」而不是「正在运行」，不能当作存活总数看。",
                "设备内存卡片读的是 ActivityManager.MemoryInfo，反映整机余量，不是分身的专属配额。",
                "页面可见时每 3 秒刷新一次，切到后台即停止，不常驻耗电。"
            )
        ),
        DocSection(
            "装上却卡在启动页时",
            listOf(
                "分身能装进系统、能启动到启动页，说明包名、清单、资源、原生库都已经正确落地，卡住的是应用自己的初始化。",
                "第一件要排除的事：同一个应用的原版，在同一个环境下能不能正常启动。原版也卡，问题在环境、网络或系统，不在分身。",
                "带加固壳的应用最值得先怀疑（选应用时如果带壳，列表里会有琥珀色徽标）。加固应用的启动顺序是：壳先接管，解密 dex、校验包完整性，再把控制权交给应用。分身必然是新签名，清单和资源表也被改过，壳校验不过就直接停住——表现是进程活着、CPU 为 0、没有任何崩溃日志，首帧永远画不出来。红果短剧加载的 libEpic_Protect.so 就属于这一类。",
                "克隆里最常见的原因是应用校验自己的签名。分身必然是新签名（原理上无法避免），这类校验通常表现为卡在启动页、白屏或静默退出，工具层无解。",
                "第二类是按名字查资源：资源表里的包名已被改成分身包名，把包名或资源名写死在 native / 脚本层的应用会查不到，于是一直等。这类可以试「兼容模式重建」（不改资源表包名）。",
                "第三类是被当成异常设备：部分应用会检测模拟器、多开环境、非官方渠道安装，表现为功能不可用或启动即退。",
                "以上都不成立时，用真机 + 原版对照最省事：同一环境原版能跑、分身卡住，基本就可以判定是签名或环境识别。"
            )
        ),
        DocSection(
            "隐私与数据",
            listOf(
                "整个应用没有申请 INTERNET 权限，所有处理都在本机完成，不会上传任何东西。",
                "读取已安装应用列表是为了让你挑选分身目标；分身过程中的文件只写在应用私有缓存目录里，可以在设置页一键清空。",
                "内置签名密钥随应用打包，仅用于本机自用。换一台设备或重装管理器后，旧分身无法覆盖升级，需要重新生成。",
                "分身记录存在私有目录的 clones.json 里。分身是独立安装的 APK，系统卸载本应用时不会替我们带走它们，所以设置 →「清理」里单独给了「清除全部分身」和「卸载幻之分身」两个入口。",
                "这两个入口都做不到「一键静默」：普通应用卸载任何一个包，系统都要用户在弹窗上点一次确认（静默卸载只对持有 DELETE_PACKAGES 的系统应用与设备所有者开放）。工具能做的是把 N 个确认框按顺序排好队，不必自己到系统设置里一个个找。",
                "反过来，系统里卸掉一个分身时，它的记录也会跟着消失（监听系统卸载广播，并在主页再对一次账）——列表只保留真实存在的分身。"
            )
        ),
        DocSection(
            "使用前提",
            listOf(
                "需要授予「安装未知应用」权限，否则无法把生成的分身装进系统。",
                "想让运行概览显示真实占用，需要授予「使用情况访问权限」；不授权也能创建和使用分身。",
                "仅供个人在自己设备上使用。请遵守各应用的服务条款，不要用它规避风控或做批量账号操作。",
                "体积敏感：装三四个大型应用的分身，占用会以 GB 计，创建前先看一眼剩余空间。"
            )
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityManualBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }

        val doc = DocRenderer(this, b.container)
        doc.header(
            "幻之分身 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "全量重打包的实现细节与读数口径"
        )
        sections.forEach { doc.section(it) }
    }
}
