package com.huanzhi.manager.core

/**
 * 版本更新的结构化数据。
 *
 * 放在代码里而不是 assets/json：条目少、要跟着 versionCode 一起走，
 * 写在代码里还能让编译器帮忙挡住字段改错。
 *
 * 写法约定（3.5 起）：一条只说一件事，能一行说完就别写两行。
 * 这一段是给人翻的，不是设计文档 —— 原理、边界、实测过程都归 README 与「技术说明」，
 * 这里只留「这次变了什么、对我有什么影响」。
 */
data class ChangeSection(val title: String, val items: List<String>)

data class ReleaseNote(
    val versionName: String,
    val versionCode: Int,
    val date: String,
    val headline: String,
    val sections: List<ChangeSection>
)

object Changelog {

    val RELEASES: List<ReleaseNote> = listOf(
        ReleaseNote(
            versionName = "3.6",
            versionCode = 20,
            date = "2026-10-30",
            headline = "平板上列表与详情并排显示，界面换到 Material 3 Expressive",
            sections = listOf(
                ChangeSection(
                    "平板",
                    listOf(
                        "宽屏下主页改成左列表、右详情：点一下只看信息，要启动点面板上的「启动」",
                        "设置页分组按宽度排成两列，卡片不再被拉到九百多 dp 宽",
                        "选应用页按宽度排 1～3 列",
                        "分屏和折叠屏按实际窗口宽度判断，拖窄了会自动退回单栏"
                    )
                ),
                ChangeSection(
                    "界面",
                    listOf(
                        "整体换到 Material 3 Expressive：圆角分了六档，次级动作改用 tonal 按钮",
                        "读应用列表时显示波动进度环，不再是一个和「卡住」长得一样的转圈",
                        "选中行同时用底色、加粗描边和读屏状态三处提示，不只靠颜色"
                    )
                ),
                ChangeSection(
                    "修复",
                    listOf(
                        "深色模式下页面转场和对话框动画会丢失（原来夜间主题没把这两项抄全）",
                        "从右到左的语言下，批量条避让的左右留白不会跟着翻边"
                    )
                )
            )
        ),
        ReleaseNote(
            versionName = "3.5",
            versionCode = 19,
            date = "2026-10-22",
            headline = "卸载本应用前，能把分身一起清掉；更新记录改成短句",
            sections = listOf(
                ChangeSection(
                    "清理",
                    listOf(
                        "设置里新增「清除全部分身」和「卸载幻之分身」，后者清完再走系统卸载",
                        "每个分身都要在系统弹窗点一次「卸载」——普通应用拿不到静默卸载，工具负责把 N 个框排好队",
                        "中途取消就停下，没卸掉的原样保留，本应用也不会被误删"
                    )
                ),
                ChangeSection(
                    "其他",
                    listOf("更新记录每个版本压到几条短句")
                )
            )
        ),
        ReleaseNote(
            versionName = "3.4",
            versionCode = 18,
            date = "2026-10-22",
            headline = "改名「幻之分身」，并记住分身是从源应用的哪个版本打出来的",
            sections = listOf(
                ChangeSection(
                    "更名",
                    listOf(
                        "应用名、管理器包名、分身包名前缀全部换新，与旧版并排装不冲突",
                        "旧版分身照常运行，首次启动提示一次"
                    )
                ),
                ChangeSection(
                    "版本追踪",
                    listOf(
                        "每份分身记住源应用版本；源应用升级后列表显示「可更新」",
                        "菜单新增「更新全部（N）」：逐个重打包并覆盖安装，可中途停止",
                        "覆盖安装保留分身里的数据与登录态"
                    )
                )
            )
        ),
        ReleaseNote(
            versionName = "3.3",
            versionCode = 17,
            date = "2026-10-22",
            headline = "修「装完点完成被当成取消」，索引字母可调大",
            sections = listOf(
                ChangeSection(
                    "安装",
                    listOf(
                        "回执说取消时回查系统实际状态，并且要求提交前本来没装才算成功",
                        "取消后不再留下点不动的「未安装」条目"
                    )
                ),
                ChangeSection(
                    "外观",
                    listOf("索引字母三档可调：小 11 / 标准 13 / 大 17，行高跟着字号走")
                )
            )
        ),
        ReleaseNote(
            versionName = "3.2",
            versionCode = 16,
            date = "2026-10-22",
            headline = "收款码可以存进相册，微信扫一扫直接选",
            sections = listOf(
                ChangeSection(
                    "支持作者",
                    listOf(
                        "「保存收款码」存到相册 Pictures/HuanZhi，存的是原图，不重新编码",
                        "不做一键跳转账：微信没给第三方开这个口子"
                    )
                )
            )
        ),
        ReleaseNote(
            versionName = "3.1",
            versionCode = 15,
            date = "2026-10-22",
            headline = "生成分身有了百分比进度，加固应用单独分组",
            sections = listOf(
                ChangeSection(
                    "进度",
                    listOf(
                        "阶段 + 已处理字节数的确定型进度，大应用也能看出还剩多少",
                        "长任务可取消，拷贝阶段通常半秒内停下"
                    )
                ),
                ChangeSection(
                    "选应用页",
                    listOf(
                        "带加固壳的应用整体排到最下面，并说明为什么",
                        "索引条末尾加「加」，一下跳到加固段"
                    )
                )
            )
        ),
        ReleaseNote(
            versionName = "3.0",
            versionCode = 14,
            date = "2026-10-22",
            headline = "支持 Android 12–17，动效按一套规范重做",
            sections = listOf(
                ChangeSection(
                    "支持范围",
                    listOf("最低 Android 12（API 31），最高 17（API 37）")
                ),
                ChangeSection(
                    "动效",
                    listOf(
                        "页面转场统一「前进从右进、返回向右退」",
                        "对话框缩放淡入、列表逐项浮起、夜间模式 fade-through",
                        "全部跟随系统的「动画时长缩放」，关掉动画就瞬时完成"
                    )
                )
            )
        ),
        ReleaseNote(
            versionName = "2.2",
            versionCode = 13,
            date = "2026-10-21",
            headline = "平板上按钮跟着内容列走，运行概览去掉 CPU / GPU",
            sections = listOf(
                ChangeSection(
                    "改进",
                    listOf(
                        "「新建分身」贴内容列右下边缘，不再飘在离内容很远的屏幕边",
                        "移除 CPU / GPU 卡片：读数口径不硬，留着比不显示更容易误导"
                    )
                )
            )
        ),
        ReleaseNote(
            versionName = "2.1",
            versionCode = 12,
            date = "2026-10-21",
            headline = "运行概览新增 CPU / GPU 平均占用，首页去掉重复入口",
            sections = listOf(
                ChangeSection(
                    "运行概览",
                    listOf(
                        "CPU 显示平均 / 当前 / 峰值，并写出窗口长度与样本数",
                        "GPU 读厂商节点，读不到直接写「系统未开放」，不画 0"
                    )
                ),
                ChangeSection(
                    "首页",
                    listOf("空态收起悬浮按钮，同一个「新建」不再出现两次")
                )
            )
        ),
        ReleaseNote(
            versionName = "2.0",
            versionCode = 11,
            date = "2026-10-21",
            headline = "按一套成文的规范补齐交互细节",
            sections = listOf(
                ChangeSection(
                    "新增",
                    listOf(
                        "空态加入口；卸载、删除记录前都要确认，确认按钮用红色",
                        "重新打包 / 改名显示阻塞式进度"
                    )
                ),
                ChangeSection(
                    "改进",
                    listOf(
                        "热区一律 48dp；间距统一 4/8dp",
                        "颜色走 Material 3 语义色，写死的中文全部收进 strings.xml"
                    )
                )
            )
        ),
        ReleaseNote(
            versionName = "1.9",
            versionCode = 10,
            date = "2026-10-21",
            headline = "逐份命名、搜索、拼音索引，卸载后记录自动消失",
            sections = listOf(
                ChangeSection(
                    "新增",
                    listOf(
                        "批量里每个应用都能单独命名，点「逐份」可给每一份起不同的名字",
                        "搜索支持原名、拼音全拼、首字母、包名",
                        "A–Z 拼音索引，点或拖动快速跳组"
                    )
                ),
                ChangeSection(
                    "改进",
                    listOf("分身被卸载后记录即时删除，不再留一行点不动的「未安装」")
                )
            )
        ),
        ReleaseNote(
            versionName = "1.8",
            versionCode = 9,
            date = "2026-10-20",
            headline = "名字可自己起，大包打得更快，安装结果以系统状态为准",
            sections = listOf(
                ChangeSection(
                    "新增",
                    listOf(
                        "创建时填名称前缀，自动接「· 分身N」，输入框下方实时预览",
                        "设置 → 诊断 → 安装日志：建包、提交、回执三段原始记录"
                    )
                ),
                ChangeSection(
                    "改进",
                    listOf(
                        "压缩等级 6 → 1，原包已压缩的条目直接照搬",
                        "预构建：上一份等你点确认框时，下一份就在后台打包"
                    )
                ),
                ChangeSection(
                    "修复",
                    listOf(
                        "修「建两个只装一个」：先挂监听再提交，回执丢了以系统状态为准"
                    )
                )
            )
        ),
        ReleaseNote(
            versionName = "1.7",
            versionCode = 8,
            date = "2026-10-20",
            headline = "选应用时先看有没有壳",
            sections = listOf(
                ChangeSection(
                    "新增",
                    listOf(
                        "列表标出带加固壳的应用与壳名，创建前提一句「可能卡在启动页」",
                        "只读 APK 里的文件特征，不联网、不执行壳的代码"
                    )
                )
            )
        ),
        ReleaseNote(
            versionName = "1.6",
            versionCode = 7,
            date = "2026-10-20",
            headline = "装上却卡在启动页：加兼容模式",
            sections = listOf(
                ChangeSection(
                    "新增",
                    listOf(
                        "长按分身 →「兼容模式重建」：只跳过改资源表包名这一步",
                        "技术说明写了「卡在启动页」的判定顺序"
                    )
                ),
                ChangeSection(
                    "改进",
                    listOf("装完即删克隆包，五份大应用不再攒出上 GB 垃圾")
                )
            )
        ),
        ReleaseNote(
            versionName = "1.5",
            versionCode = 6,
            date = "2026-10-20",
            headline = "不再抬高 targetSdk，修「清单格式有误」",
            sections = listOf(
                ChangeSection(
                    "修复",
                    listOf(
                        "targetSdk 一律沿用原应用，只在低于安装门槛时抬到门槛",
                        "建包时体检带 intent-filter 却漏写 android:exported 的组件"
                    )
                )
            )
        ),
        ReleaseNote(
            versionName = "1.4",
            versionCode = 5,
            date = "2026-10-20",
            headline = "修「一份都装不上」，失败原因不再是个黑盒",
            sections = listOf(
                ChangeSection(
                    "修复",
                    listOf("清单里自己声明的权限一律改成分身专属名字，不再与原应用撞名")
                ),
                ChangeSection(
                    "新增",
                    listOf(
                        "建包后先让系统解析一遍，包名、签名、权限问题当场说清",
                        "失败按真实原因汇报：取消、空间不足、权限重名、签名冲突分开提示",
                        "分身支持重命名，覆盖安装、数据保留"
                    )
                )
            )
        ),
        ReleaseNote(
            versionName = "1.3",
            versionCode = 4,
            date = "2026-10-20",
            headline = "一个应用也能一次建一批",
            sections = listOf(
                ChangeSection(
                    "新增",
                    listOf(
                        "选中应用后拉滑杆决定生成 1–10 份，自动编号",
                        "技术说明二级页：原理、模块构成、读数口径、隐私说明"
                    )
                ),
                ChangeSection(
                    "变更",
                    listOf("全新图标")
                )
            )
        ),
        ReleaseNote(
            versionName = "1.2",
            versionCode = 3,
            date = "2026-10-20",
            headline = "多个眼睛看分身：运行概览",
            sections = listOf(
                ChangeSection(
                    "新增",
                    listOf(
                        "按 UID 归集进程，看每个分身开了几个进程、占多少内存与存储",
                        "页面可见时每 3 秒自动刷新；读不到就留空，不用 0 顶替",
                        "平板两列布局"
                    )
                ),
                ChangeSection(
                    "变更",
                    listOf("应用更名「梦之分身」")
                )
            )
        ),
        ReleaseNote(
            versionName = "1.1",
            versionCode = 2,
            date = "2026-10-20",
            headline = "批量分身、设置中心与夜间模式",
            sections = listOf(
                ChangeSection(
                    "新增",
                    listOf(
                        "多选模式批量创建，每个应用可选 1–5 份",
                        "设置中心：外观、存储、关于",
                        "夜间模式：跟随系统 / 浅色 / 深色"
                    )
                ),
                ChangeSection(
                    "修复",
                    listOf("深色模式下状态栏图标不反色")
                )
            )
        ),
        ReleaseNote(
            versionName = "1.0",
            versionCode = 1,
            date = "2026-10-20",
            headline = "首个可用版本：全量重打包分身内核",
            sections = listOf(
                ChangeSection(
                    "内核",
                    listOf(
                        "dex / 资源 / assets / so 原样搬进分身，只改清单里的包名与类名",
                        "resources.arsc 包名改写，apksig v2 签名，zipalign 对齐"
                    )
                ),
                ChangeSection(
                    "界面",
                    listOf("分身列表、应用选择、命名对话框、长按菜单；会话安装与结果回调")
                )
            )
        )
    )

    val current: ReleaseNote get() = RELEASES.first()
}
