package com.huanzhi.manager.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 3.7 设计令牌 —— Liquid Glass 重设计。
 *
 * 【数值来源】全部由 `design` 目录下的设计稿逐点采样得出，不是估计：
 * 稿面 1500×3248px = 375×812pt @4x，所以 **1dp = 4px**，换算无损。
 *   页面底色 #F2F2F7 / 卡片 #FFFFFF（浅）；#000000 / #1C1C1E（深）
 *   强调蓝 #007AFF / #0A84FF —— 正好是 iOS 的 systemBlue，深浅各一档
 *   次级文字 #8E8E93 / #98989E
 *   卡片 88dp 高、20dp 间距、16dp 页边距、圆角 16dp
 *
 * 【为什么不用 Material3 的 ColorScheme】
 * M3 的动态取色与 tonal palette 会把颜色算到自己的一套色阶上，而设计稿的颜色
 * 是逐档点名的（浅色蓝就是 #007AFF，不是 Primary40）。硬塞进 M3 会被改色，
 * 所以这里自建一套，只借 M3 的排版与交互基建。
 */
@Immutable
data class HzPalette(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val green: Color,
    val orange: Color,
    val red: Color,
    val badgeGreenBg: Color,
    val badgeBlueBg: Color,
    val badgeOrangeBg: Color,
    val badgeRedBg: Color,
    val glassFill: Color,
    val glassStroke: Color,
    val navSelectedBg: Color,
    val divider: Color,
    val iconTileBg: Color,
    /**
     * 平板左侧栏的三档颜色。
     *
     * 浅色下侧栏比页面底 **略暗**（#F0F0F5 vs #F2F2F7），深色下比页面底 **略亮**
     * （#1C1C1E vs #000000）。看着像随手取的，其实是同一个原则的两面：
     * 侧栏要比内容区「退后一层」，而"退后"在亮底上表现为更暗、在暗底上表现为更亮。
     * 如果两档都直接用 background 色，侧栏就只能靠一根分隔线撑着，会显得没边界。
     */
    val railBg: Color,
    val railSelected: Color,
    val railDivider: Color,
    /**
     * 输入框与分段控件的底（iOS 的 secondarySystemFill）。
     *
     * 它比 background 深、又比 surface 浅：卡片是「浮起来的白」，输入框是「凹下去的灰」，
     * 两者都不能用同一个 surface，否则搜索框摆在列表上方会像一张空卡片。
     */
    val fill: Color,
    /** 分段控件里选中那一片。浅色下是纯白（压在一层灰上），深色下是一档更亮的灰 */
    val controlSelected: Color,

    // ------------------------------------------------------ 构建进度页
    /**
     * 琥珀提示卡的底与描边。
     *
     * 这一页唯一「有颜色」的容器，因为它承载的不是信息而是**待办**：
     * 系统安装框已经弹出，用户必须去点一下，否则整条队列停在这里。
     * 换成灰卡的话，用户会把它当成又一条说明文字往下滑。
     */
    val warnFill: Color,
    val warnStroke: Color,
    /** 琥珀卡里的强调文字（「轮到你操作」「跳过这一份」） */
    val warnText: Color,
    /** 琥珀卡顶部那颗圆点、以及队列里「等你点安装」的数字底色：饱和的橙，不是文字用的棕 */
    val warnAccent: Color,
    /** 琥珀卡里的正文与副信息：同色系但更淡，压在琥珀底上不跟标题抢 */
    val warnBody: Color,
    /** 队列行左侧数字徽标的底色（未开始 / 已就绪） */
    val badgeNeutralBg: Color,
    /** 已完成那一行的勾选圆底 */
    val badgeDoneBg: Color,
    /** 正在构建那一行的数字圆底 */
    val badgeBusyBg: Color,
    val isDark: Boolean,
)

/**
 * 浅色档。
 *
 * 有一处刻意偏离设计稿：#C7C7CC（三级文字）在纯白卡片上只有 2.1:1，
 * 达不到 WCAG AA 的 4.5:1。设计稿里它只用于「未启动」这类可省的信息，
 * 我保留原色（视觉一致优先），但所有真正需要读的文字都不用它 —— 见 [HzTypography]。
 */
private val LightPalette = HzPalette(
    background = Color(0xFFF2F2F7),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF000000),
    textSecondary = Color(0xFF8E8E93),
    textTertiary = Color(0xFFC7C7CC),
    accent = Color(0xFF007AFF),
    green = Color(0xFF248A3D),
    orange = Color(0xFFC93400),
    red = Color(0xFFD70015),
    badgeGreenBg = Color(0xFFD7ECE0),
    badgeBlueBg = Color(0xFFD2E3F6),
    badgeOrangeBg = Color(0xFFF7E3CC),
    badgeRedBg = Color(0xFFFADCD9),
    glassFill = Color(0xF2F5F5F5),
    glassStroke = Color(0x14000000),
    navSelectedBg = Color(0xFFD2E3F6),
    divider = Color(0xFFE5E5EA),
    iconTileBg = Color(0x0F000000),
    railBg = Color(0xFFF0F0F5),
    railSelected = Color(0xFFFFFFFF),
    railDivider = Color(0xFFD8D8DE),
    fill = Color(0xFFE4E4E9),
    controlSelected = Color(0xFFFFFFFF),
    warnFill = Color(0xFFFFF6E5),
    warnStroke = Color(0xFFFFD08A),
    warnText = Color(0xFFA9701B),
    warnAccent = Color(0xFFFF9500),
    warnBody = Color(0xFF7A5A2E),
    badgeNeutralBg = Color(0xFFEFEFF4),
    badgeDoneBg = Color(0xFFDEF6E4),
    badgeBusyBg = Color(0xFFDBECFF),
    isDark = false,
)

/**
 * 深色档。
 *
 * 卡片 #1C1C1E 与底色 #000000 之间只差 12 阶亮度 —— 这是 iOS 分组列表的典型做法，
 * 靠「卡片比背景亮一点」而不是描边来分层。因此深色下**不要**再给卡片加描边，
 * 否则会得到一圈比卡片本身还显眼的白边。
 */
private val DarkPalette = HzPalette(
    background = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    surfaceRaised = Color(0xFF242426),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF98989E),
    textTertiary = Color(0xFF6C6C70),
    accent = Color(0xFF0A84FF),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
    red = Color(0xFFFF453A),
    badgeGreenBg = Color(0xFF092610),
    badgeBlueBg = Color(0xFF02182E),
    badgeOrangeBg = Color(0xFF2A1D06),
    badgeRedBg = Color(0xFF2E0D0B),
    glassFill = Color(0xF2242426),
    glassStroke = Color(0x1FFFFFFF),
    navSelectedBg = Color(0xFF1F3751),
    divider = Color(0xFF38383A),
    iconTileBg = Color(0x1FFFFFFF),
    railBg = Color(0xFF1C1C1E),
    railSelected = Color(0xFF3A3A3C),
    railDivider = Color(0xFF38383A),
    fill = Color(0xFF2C2C2E),
    controlSelected = Color(0xFF636366),
    warnFill = Color(0xFF2D2412),
    warnStroke = Color(0xFFA0722B),
    warnText = Color(0xFFFFB340),
    warnAccent = Color(0xFFFF9F0A),
    warnBody = Color(0xFFD0B88C),
    badgeNeutralBg = Color(0xFF262628),
    badgeDoneBg = Color(0xFF20402A),
    badgeBusyBg = Color(0xFF18304B),
    isDark = true,
)

/**
 * 排版。字号同样量自设计稿（大标题字高 29dp → 34pt 字号）。
 *
 * 中文在 Android 上默认落到思源黑体/Roboto，与稿面的苹方有细微差异：
 * 同样的字号，思源的中宫更大、字面更满，观感会「胖」一点点。
 * 这里把行高按 1.2 倍给足，抵消这个差异，不去改字号（改了就不叫像素级了）。
 */
@Immutable
data class HzTypography(
    val largeTitle: TextStyle,
    val subtitle: TextStyle,
    val section: TextStyle,
    val cardTitle: TextStyle,
    val cardMeta: TextStyle,
    val status: TextStyle,
    val badge: TextStyle,
    val tab: TextStyle,
    val button: TextStyle,
    /** 平板页内标题 24sp —— 比手机的 34sp 小一档，因为内容区旁边还有一列导航 */
    val pageTitle: TextStyle,
    /** 统计数值（「1.3 GB」「1」），28sp 半粗 */
    val metric: TextStyle,
    /** 侧栏顶部应用名，20sp 粗 */
    val railTitle: TextStyle,
    /** 侧栏导航项文字，15sp */
    val railItem: TextStyle,
    /** 统计数值的单位（「GB」「个」）15sp 常规 —— 与 28sp 的数字同一行，靠字号差拉开主次 */
    val metricUnit: TextStyle,
    /** 页内小标题（「各分身占用」）15sp 半粗。比卡片标题的 17sp 小一档，它是分区而不是内容 */
    val sectionTitle: TextStyle,
    /**
     * 右栏说明面板的正文，12sp / 16sp 行高。
     *
     * 比卡片正文的 13sp/18sp 小一档，数值量自稿面：面板正文的行间距实测 64px @4x = 16dp。
     * 面板只有 230dp 宽、里面是三段说明，用 13sp 的话「StorageStatsManager 按包精确统计」
     * 这一句会挤成三行还带一个孤字 —— 稿面是两行。
     */
    val panelBody: TextStyle,
)

private val Typography = HzTypography(
    // 34pt / 41pt —— iOS Large Title
    largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
    subtitle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    section = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    cardTitle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    cardMeta = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    status = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    badge = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    tab = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium),
    button = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    // 平板标题实测字身 82px @4x ≈ 20.5dp，苹方中文字身约占字号的 0.88 → 24pt
    pageTitle = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    metric = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    /** 侧栏顶部应用名，24sp 粗 —— 与内容区页标题同档，它俩是同一屏上的两个标题 */
    railTitle = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    railItem = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    metricUnit = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    sectionTitle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    panelBody = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
)

/**
 * 尺寸。命名按语义而不是数值 —— 看到 `cardGap` 就知道改它会影响什么，
 * 看到 `20dp` 只能靠翻代码。
 */
@Immutable
data class HzDimens(
    /** 页面左右边距。量得 16dp，与 iOS 分组列表的标准边距一致 */
    val pagePadding: androidx.compose.ui.unit.Dp = 16.dp,
    val cardHeight: androidx.compose.ui.unit.Dp = 88.dp,
    val cardGap: androidx.compose.ui.unit.Dp = 20.dp,
    val cardCorner: androidx.compose.ui.unit.Dp = 16.dp,
    /**
     * 卡片内边距左右不等：实测左边 12dp、右边 16dp。
     *
     * 不是手抖 —— 图标是实心色块、贴边 12dp 看起来正好；而右侧是 13sp 文字加一个
     * 细箭头，字号小、视觉重量轻，同样的 12dp 会显得"贴到边上了"。设计上常用这种
     * 光学校正，按数值统一成 16/16 反而会让右边显得局促。
     */
    val cardPaddingStart: androidx.compose.ui.unit.Dp = 12.dp,
    val cardPaddingEnd: androidx.compose.ui.unit.Dp = 16.dp,
    val iconSize: androidx.compose.ui.unit.Dp = 48.dp,
    val iconCorner: androidx.compose.ui.unit.Dp = 12.dp,
    /** 大标题到副标题的间距 */
    val titleGap: androidx.compose.ui.unit.Dp = 6.dp,
    val sectionGap: androidx.compose.ui.unit.Dp = 18.dp,
    /** 底部玻璃导航栏胶囊高 70dp，距底 16dp（留出系统手势区） */
    val tabBarHeight: androidx.compose.ui.unit.Dp = 70.dp,
    val tabBarInset: androidx.compose.ui.unit.Dp = 16.dp,
    /**
     * 导航栏的水平外边距 20dp，比页面的 16dp 更大。
     *
     * 实测如此，而且是对的：卡片是矩形块，16dp 的边距已经能"呼吸"；
     * 而导航栏是大圆角胶囊，圆角会把视觉轮廓往里收，同样的边距看起来会比卡片更贴边。
     */
    val tabBarSideInset: androidx.compose.ui.unit.Dp = 20.dp,
    val tabBarCorner: androidx.compose.ui.unit.Dp = 35.dp,
    val fabSize: androidx.compose.ui.unit.Dp = 56.dp,
    val touchMin: androidx.compose.ui.unit.Dp = 48.dp,

    // ---------------------------------------------------------------- 平板档
    /**
     * 左侧栏宽度。设计稿实测 880px @4x = 220dp，且是**固定值**而非比例 ——
     * 侧栏里只有 3 个导航项和两行文字，宽度再大也只是留白变多。
     */
    val railWidth: androidx.compose.ui.unit.Dp = 220.dp,
    /** 侧栏导航项：48dp 高、左右各 8dp 内缩、12dp 圆角（量自 09/26 稿） */
    val railItemHeight: androidx.compose.ui.unit.Dp = 48.dp,
    val railItemCorner: androidx.compose.ui.unit.Dp = 12.dp,
    val railItemInset: androidx.compose.ui.unit.Dp = 8.dp,
    /**
     * 平板内容区的左右边距 24dp，比手机的 16dp 更大。
     *
     * 不是"平板就自动加宽"，而是同一屏里要并排放两张卡：卡与卡之间 14dp，
     * 如果外边距还是 16dp，左右两条缝比中间那条还窄，两列会被看成"一列宽卡被劈开"。
     */
    val contentPadding: androidx.compose.ui.unit.Dp = 24.dp,
    /** 平板卡片 112dp 高（手机 88dp）：图标仍是 48dp，多出来的全给上下留白 */
    val wideCardHeight: androidx.compose.ui.unit.Dp = 112.dp,
    /** 网格列间距 14dp —— 与卡片的 112dp 高度相比略小于半行，横向排得紧、纵向才透气 */
    val gridGap: androidx.compose.ui.unit.Dp = 14.dp,
    /** 设置页单行高度 52dp（手机稿实测 208px @4x） */
    val rowHeight: androidx.compose.ui.unit.Dp = 52.dp,
    /** 设置页组内行的左内边距：图标贴边 14dp，与卡片右侧的 16dp 形成光学校正 */
    val rowPaddingStart: androidx.compose.ui.unit.Dp = 14.dp,

    // ------------------------------------------------------------ 运行概览
    /**
     * 运行概览的行高 76dp，比主页的 88dp 矮一档。
     *
     * 不是"随手画矮了"：这一页每行左侧要放「名称 + 启动时间」、右侧要放「数值 + 状态」，
     * 信息密度比主页高，行再高就散了。稿面手机量得 78dp、平板 74dp，取中间是刻意的 ——
     * 同一个组件在两个形态上差 4dp 只会让人以为哪边没对齐。
     */
    val runtimeRowHeight: androidx.compose.ui.unit.Dp = 76.dp,
    /** 运行概览卡圆角 12dp（稿面实测 48px），比主页卡的 16dp 收一档 */
    val runtimeRowCorner: androidx.compose.ui.unit.Dp = 12.dp,
    /** 运行概览行内图标 40dp（主页 48dp）：行矮了图标也得跟着收，否则上下留白只剩 14dp */
    val runtimeIcon: androidx.compose.ui.unit.Dp = 40.dp,
    val runtimeRowGap: androidx.compose.ui.unit.Dp = 8.dp,
    /** 三张统计卡：手机 92dp、平板 103dp，多出来的 11dp 全给了第三条说明的加长版文案 */
    val metricHeight: androidx.compose.ui.unit.Dp = 92.dp,
    val metricHeightWide: androidx.compose.ui.unit.Dp = 103.dp,
    /** 分段占用条 7dp 高。比它再细就分不清五段，再粗就抢了下面列表的注意力 */
    val segmentBarHeight: androidx.compose.ui.unit.Dp = 7.dp,
    /** 平板右栏固定 230dp：里面只有三块短文案加一个开关，宽了只是留白变多 */
    val panelWidth: androidx.compose.ui.unit.Dp = 230.dp,

    // ------------------------------------------------------------ 选择应用
    /**
     * 选应用页的行高：手机 72dp、平板 96dp。
     *
     * 两个数都量自稿面，差距比主页卡的 88/112 更明显 —— 这一页每行只有
     * 「图标 + 名字 + 包名」，没有状态列，所以行高几乎是图标高度加留白：
     * 手机 40dp 图标配 72dp 行，平板 44dp 图标配 96dp 行。
     */
    val pickerRowHeight: androidx.compose.ui.unit.Dp = 72.dp,
    val pickerRowHeightWide: androidx.compose.ui.unit.Dp = 96.dp,
    val pickerIcon: androidx.compose.ui.unit.Dp = 40.dp,
    val pickerIconWide: androidx.compose.ui.unit.Dp = 44.dp,
    /** 行卡圆角 12dp（稿面 48px），与运行概览同档 —— 都是"一列短行" */
    val pickerCorner: androidx.compose.ui.unit.Dp = 12.dp,
    /** 搜索框 44dp 高、32dp 分段控件，都是稿面实测 */
    val searchHeight: androidx.compose.ui.unit.Dp = 44.dp,
    val segmentHeight: androidx.compose.ui.unit.Dp = 32.dp,
    /** 底部总结条 64dp 高、32dp 圆角；里面那颗「下一步」78×44dp */
    val pickerBarHeight: androidx.compose.ui.unit.Dp = 64.dp,
    val pickerActionHeight: androidx.compose.ui.unit.Dp = 44.dp,
    val pickerActionWidth: androidx.compose.ui.unit.Dp = 78.dp,
    /**
     * 平板右栏 276dp。
     *
     * 比运行概览的 230dp 宽：那一栏只放说明文字，这一栏要放「图标 + 名字 + 步进器」，
     * 步进器本身就占 96dp，230dp 扣掉之后名字只剩 90dp，「淘宝」后面就得截断。
     */
    val pickerPanelWidth: androidx.compose.ui.unit.Dp = 276.dp,

    // ------------------------------------------------------------ 构建进度
    /**
     * 队列行：手机 64dp + 5dp 间距，平板 66dp + 8dp 间距。
     *
     * 5dp 这个间距是量出来的（稿面相邻两行卡片的上沿差 69dp），不是随手写的 —
     * 这一页的行是一串进度，行与行之间「连着」比「分开」更像一条队列；
     * 换成 8dp 的常规间距，列表会读成七个互不相干的应用。
     */
    val buildRowHeight: androidx.compose.ui.unit.Dp = 64.dp,
    val buildRowGap: androidx.compose.ui.unit.Dp = 5.dp,
    val buildRowHeightWide: androidx.compose.ui.unit.Dp = 66.dp,
    val buildRowGapWide: androidx.compose.ui.unit.Dp = 8.dp,
    /** 行首的任务号 / 勾选圆：手机 26dp、平板 28dp（稿面 100/112px @4x） */
    val buildBadge: androidx.compose.ui.unit.Dp = 26.dp,
    val buildBadgeWide: androidx.compose.ui.unit.Dp = 28.dp,
    /** 底部操作条 62dp 高、22dp 外边距；里面那颗「取消剩余」120×52dp */
    val buildBarHeight: androidx.compose.ui.unit.Dp = 62.dp,
    val buildBarInset: androidx.compose.ui.unit.Dp = 22.dp,
    val buildPillWidth: androidx.compose.ui.unit.Dp = 120.dp,
    val buildPillHeight: androidx.compose.ui.unit.Dp = 52.dp,
)

/** 最小可点区域。低于 48dp 的按钮一律用它兜住，不靠调用方自觉 */
val TouchMin: androidx.compose.ui.unit.Dp = 48.dp

/**
 * 设置页图标底块的颜色。
 *
 * 这六个色**不随深浅主题变**，和 iOS 系统设置的做法一致：
 * 色块的职责是"这一行属于哪一类"（橙=容量、绿=缓存、红=不可逆操作），
 * 一旦跟着主题换色，同一行在两种模式下就变成两种含义。
 * 白图标压在这些饱和色上，深浅底都有 3:1 以上的对比，不需要再分两套。
 */
object HzIconTint {
    val blue = Color(0xFF007AFF)
    val gray = Color(0xFF8E8E93)
    val orange = Color(0xFFFF9500)
    val green = Color(0xFF34C759)
    val red = Color(0xFFFF3B30)
    val pink = Color(0xFFFF2D55)
}

/**
 * 分段占用条的五档颜色，按数值从大到小依次取用。
 *
 * 同样**不随主题变**。这条色带的任务是让五段一眼分得开，而不是表达"这一份是什么状态"；
 * 一旦跟着主题换档，深浅两套下的段序就变了，用户切一次主题会以为自己看错了图。
 * 顺序也不是随手排的：靛 → 蓝 → 绿 → 橙 → 粉，相邻两段色相至少隔 60°，
 * 排在最前面的永远是最大的一块，所以第一段必须是最"沉"的靛蓝，否则最大那块会跳出来抢焦点。
 */
object HzSegmentColors {
    val indigo = Color(0xFF5E5CE6)
    val blue = Color(0xFF007AFF)
    val green = Color(0xFF34C759)
    val orange = Color(0xFFFF9500)
    val pink = Color(0xFFFF2D55)

    /** 超过五段时循环取用 —— 第六个分身用回靛蓝，靠位置而不是靠颜色区分 */
    val all: List<Color> = listOf(indigo, blue, green, orange, pink)

    fun at(index: Int): Color = all[index % all.size]
}

val LocalHzPalette = staticCompositionLocalOf { LightPalette }
val LocalHzTypography = staticCompositionLocalOf { Typography }
val LocalHzDimens = staticCompositionLocalOf { HzDimens() }

internal val hzLightPalette: HzPalette get() = LightPalette
internal val hzDarkPalette: HzPalette get() = DarkPalette
internal val hzTypography: HzTypography get() = Typography
