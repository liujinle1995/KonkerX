package com.huanzhi.manager.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.huanzhi.manager.R
import com.huanzhi.manager.ui.adaptive.WindowSizeClass
import com.huanzhi.manager.ui.adaptive.rememberWindowSizeClass
import com.huanzhi.manager.ui.components.AppIconTile
import com.huanzhi.manager.ui.glass.GlassIconButton
import com.huanzhi.manager.ui.glass.clickableBouncy
import com.huanzhi.manager.ui.theme.HzSegmentColors
import com.huanzhi.manager.ui.theme.HzTheme
import com.huanzhi.manager.util.Formats

/**
 * 一行分身的展示数据。
 *
 * [status] 带的是**语义**而不是颜色（理由见 [CloneStatus]）；[meta] 是左侧副标题，
 * 由 Activity 算好（要读 UsageStats 与时间差），页面只负责摆。
 */
data class RuntimeRowUi(
    val key: String,
    val label: String,
    val pkg: String,
    val sizeText: String,
    val meta: String,
    val status: CloneStatus,
    val occupiedBytes: Long,
    /**
     * 此刻是否在前台。
     *
     * 和 [status] 分开是因为二者不等价：非 root 设备读不到别的 UID 的进程，
     * 所以"能看到进程"几乎就等于"在前台"；但一旦 `/proc` 可读，
     * 就可能出现"进程在、人不在"的分身 —— 那种情况它是 [CloneStatus.Running]，
     * 却不能写「前台运行」。文案按这个字段分岔。
     */
    val foreground: Boolean = false,
) {
    val running: Boolean get() = status == CloneStatus.Running
}

/**
 * 运行概览的整页状态。
 *
 * 3.6 那张「设备内存」进度条在这里**没有对应位置** —— 设计稿把它换成了三张统计卡
 * 加一条分段占用条，内存只剩一句「多数真机不可见」的说明。这不是简化：
 * 非 root 设备读不到别的 UID 的 RSS，那张卡在真机上大概率是空的，留着它等于每次
 * 打开页面都先说一次"我读不到"。所以 [lastRefreshMs] 变成了这个页面的健康信号。
 */
data class RuntimeUiState(
    val loading: Boolean = true,
    val rows: List<RuntimeRowUi> = emptyList(),
    val totalOccupied: Long = 0L,
    val foregroundCount: Int = 0,
    val foregroundLabel: String = "",
    val cacheBytes: Long = 0L,
    val lastRefreshMs: Long = 0L,
    val autoRefresh: Boolean = true,
    val usageGranted: Boolean = true,
)

/**
 * 运行概览。
 *
 * 两种形态，同一份数据：
 *   COMPACT / MEDIUM —— 单列（`design/12-手机-运行概览.png`）
 *   EXPANDED        —— 左列数据 + 右列三块说明面板（`design/13-平板-运行概览.png`）
 *
 * 右列不是"把手机版多出来的内容塞过去"，它回答的是这一页必须回答的问题：
 * 这些数字从哪来、为什么看不到内存、什么时候会刷新。手机上那两句脚注
 * （内存不可见 / 本页只看存储）在宽屏上有地方展开成面板，就不该继续挤在列表底下。
 */
@Composable
fun RuntimeScreen(
    state: RuntimeUiState,
    onRefresh: () -> Unit,
    onToggleAuto: (Boolean) -> Unit,
    onGrantUsage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (rememberWindowSizeClass()) {
        WindowSizeClass.EXPANDED -> TabletRuntime(state, onRefresh, onToggleAuto, onGrantUsage, modifier)
        else -> PhoneRuntime(state, onRefresh, onGrantUsage, modifier)
    }
}

// ------------------------------------------------------------------ 手机

@Composable
private fun PhoneRuntime(
    state: RuntimeUiState,
    onRefresh: () -> Unit,
    onGrantUsage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen

    Box(modifier.fillMaxSize()) {
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            return@Box
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(
                start = d.pagePadding,
                end = d.pagePadding,
                top = 8.dp,
                bottom = d.tabBarHeight + d.tabBarInset + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(d.runtimeRowGap),
        ) {
            item(key = "head") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.nav_runtime),
                            color = colors.textPrimary,
                            style = HzTheme.type.largeTitle,
                            modifier = Modifier.weight(1f),
                        )
                        GlassIconButton(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = stringResource(R.string.runtime_refresh),
                            onClick = onRefresh,
                            size = 44.dp,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    AutoStatusLine(state)
                    Spacer(Modifier.height(18.dp))
                    MetricRow(state, wide = false)
                    Spacer(Modifier.height(20.dp))
                    SectionHeader(wide = false)
                    Spacer(Modifier.height(10.dp))
                    SegmentBar(state.rows)
                }
            }

            if (!state.usageGranted) {
                item(key = "grant") { GrantCard(onGrantUsage) }
            }

            if (state.rows.isEmpty()) {
                item(key = "empty") { EmptyRuntime() }
            } else {
                items(state.rows, key = { it.key }) { row -> RuntimeRow(row, wide = false) }
            }

            item(key = "foot") { FooterNote() }
        }
    }
}

// ------------------------------------------------------------------ 平板

/**
 * 平板形态。
 *
 * 左列（数据）跟着内容滚，右列（说明）自己滚 —— 两列各自滚动是刻意的：
 * 说明面板一屏就能读完，把它绑在列表的滚动位置上，等于逼用户先滚到底才看得到
 * 「这些数字是怎么来的」，而那句话恰恰是给"看不懂"的人准备的。
 */
@Composable
private fun TabletRuntime(
    state: RuntimeUiState,
    onRefresh: () -> Unit,
    onToggleAuto: (Boolean) -> Unit,
    onGrantUsage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen

    Box(modifier.fillMaxSize().statusBarsPadding()) {
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            return@Box
        }

        Column(Modifier.fillMaxSize()) {
            // 标题与刷新状态同一行：宽屏上这一行是"页头"，它下面才分成两列
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = d.contentPadding, end = d.contentPadding, top = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.nav_runtime),
                    color = colors.textPrimary,
                    style = HzTheme.type.pageTitle,
                    modifier = Modifier.weight(1f),
                )
                AutoStatusLine(state)
                // 平板页头没有手动刷新按钮：稿面如此，而且右栏「自动刷新」面板里
                // 已经有一个「立即刷新」，页头再放一个是同一个动作两个入口
            }

            Spacer(Modifier.height(18.dp))

            Row(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = d.contentPadding,
                        end = 0.dp,
                        top = 0.dp,
                        bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(d.runtimeRowGap),
                ) {
                    item(key = "head") {
                        Column {
                            MetricRow(state, wide = true)
                            Spacer(Modifier.height(20.dp))
                            SectionHeader(wide = true)
                            Spacer(Modifier.height(10.dp))
                            SegmentBar(state.rows)
                            if (!state.usageGranted) {
                                Spacer(Modifier.height(12.dp))
                                GrantCard(onGrantUsage)
                            }
                        }
                    }
                    if (state.rows.isEmpty()) {
                        item(key = "empty") { EmptyRuntime() }
                    } else {
                        items(state.rows, key = { it.key }) { row -> RuntimeRow(row, wide = true) }
                    }
                }

                Column(
                    // padding 要写在 width **前面**：Compose 的修饰符由外向内生效，
                    // `.width(230).padding(...)` 会把 230 当成外框、内宽只剩 190 ——
                    // 面板里的正文差这 40dp 就会多折一行
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(start = 16.dp, end = d.contentPadding)
                        .width(d.panelWidth)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SourcePanel()
                    CachePanel(state.cacheBytes)
                    AutoPanel(state, onRefresh, onToggleAuto)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 头部零件

/**
 * 「● 每 3 秒自动刷新 · 刚刚更新」。
 *
 * 绿点不是装饰：这一页的数字每 3 秒自己变一次，用户必须能一眼判断"它还在动"。
 * 关掉自动刷新时点转成灰、文案跟着改 —— 否则一条不动的「每 3 秒刷新」就是在骗人。
 */
@Composable
private fun AutoStatusLine(state: RuntimeUiState) {
    val colors = HzTheme.colors
    val rel = if (state.lastRefreshMs <= 0L) {
        stringResource(R.string.rt_just_now)
    } else {
        Formats.relative(state.lastRefreshMs)
    }
    val updated = stringResource(R.string.rt_updated, rel)
    val text = stringResource(
        if (state.autoRefresh) R.string.rt_auto_on else R.string.rt_auto_off,
        updated,
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (state.autoRefresh) colors.green else colors.textTertiary)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = colors.textSecondary,
            style = HzTheme.type.cardMeta,
        )
    }
}

// ------------------------------------------------------------------ 统计卡

/**
 * 三张统计卡。
 *
 * 数值与单位拆成两个 Text 而不是一句「1.3 GB」：稿面上「1.3」是 28sp、
 * 「GB」只有 15sp，两者靠字号差建立主次。整句一起渲染的话，单位会跟着数字一起放大，
 * 三张卡就会从"三个数"退化成"三段字"。
 */
@Composable
private fun MetricRow(state: RuntimeUiState, wide: Boolean) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen
    val h = if (wide) d.metricHeightWide else d.metricHeight

    val (totalNum, totalUnit) = splitUnit(Formats.compact(state.totalOccupied))
    val cacheNum = splitUnit(Formats.compact(state.cacheBytes))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricCard(
            title = stringResource(
                if (wide) R.string.rt_metric_total_wide else R.string.rt_metric_total
            ),
            value = totalNum,
            unit = totalUnit,
            caption = stringResource(
                if (wide) R.string.rt_metric_total_sub_wide else R.string.rt_metric_total_sub,
                state.rows.size,
            ),
            height = h,
            modifier = Modifier.weight(1f),
        )
        MetricCard(
            title = stringResource(R.string.runtime_card_foreground),
            value = state.foregroundCount.toString(),
            unit = stringResource(R.string.rt_metric_fg_unit),
            valueColor = if (state.foregroundCount > 0) colors.green else colors.textPrimary,
            caption = state.foregroundLabel.ifBlank {
                stringResource(R.string.rt_metric_fg_none)
            },
            height = h,
            modifier = Modifier.weight(1f),
        )
        MetricCard(
            title = stringResource(
                if (wide) R.string.rt_metric_cache_wide else R.string.rt_metric_cache
            ),
            value = cacheNum.first,
            unit = cacheNum.second,
            caption = stringResource(
                if (wide) R.string.rt_metric_cache_sub_wide else R.string.rt_metric_cache_sub
            ),
            height = h,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    unit: String,
    caption: String,
    height: Dp,
    modifier: Modifier = Modifier,
    valueColor: Color = HzTheme.colors.textPrimary,
) {
    val colors = HzTheme.colors
    Column(
        modifier = modifier
            .height(height)
            .drawCard(colors.surface, colors.isDark, HzTheme.dimen.runtimeRowCorner)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            color = colors.textSecondary,
            style = HzTheme.type.cardMeta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row {
            Text(
                text = value,
                color = valueColor,
                style = HzTheme.type.metric,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = unit,
                    color = colors.textSecondary,
                    style = HzTheme.type.metricUnit,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        Text(
            text = caption,
            color = colors.textSecondary,
            style = HzTheme.type.cardMeta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "1.3 GB" → ("1.3", "GB")。没有单位（"0 B" 有，但 "—" 没有）时返回空串，调用方跳过即可 */
private fun splitUnit(text: String): Pair<String, String> {
    val i = text.lastIndexOf(' ')
    return if (i <= 0) text to "" else text.substring(0, i) to text.substring(i + 1)
}

// ------------------------------------------------------------------ 分区与占用条

@Composable
private fun SectionHeader(wide: Boolean) {
    val colors = HzTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.rt_section_storage),
            color = colors.textPrimary,
            style = HzTheme.type.sectionTitle,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(
                if (wide) R.string.rt_source_stats_refresh else R.string.rt_source_stats
            ),
            color = colors.textTertiary,
            style = HzTheme.type.cardMeta,
        )
    }
}

/**
 * 分段占用条。
 *
 * 每段宽度按该分身的占用**比例**分配，段与段之间留 1.5dp —— 不留缝的话
 * 相邻两段的色相差异不够大时（蓝接靛）会看成一条渐变，读不出"这是五个东西"。
 *
 * 段色按顺序取 [HzSegmentColors]，不按分身身份：同一个人今天排第一、明天排第三，
 * 颜色跟着位置走才稳定，跟着人走的话每次进来都得重新认一遍颜色。
 */
@Composable
private fun SegmentBar(rows: List<RuntimeRowUi>) {
    val colors = HzTheme.colors
    val total = rows.sumOf { it.occupiedBytes }
    val track = if (colors.isDark) Color(0xFF2C2C2E) else colors.divider

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HzTheme.dimen.segmentBarHeight),
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
    ) {
        if (total <= 0L) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(track)
            )
        } else {
            rows.forEachIndexed { i, row ->
                Box(
                    Modifier
                        .weight(row.occupiedBytes.coerceAtLeast(1L).toFloat())
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(HzSegmentColors.at(i))
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 列表行

/**
 * 一行分身。
 *
 * 两种形态的差别只有一处：**状态文字放哪**。手机窄，右侧是「数值 + 状态」两行；
 * 平板宽，副标题那行还有富余，就把状态并进「最近启动 · 12 分钟前 · 未运行」，
 * 右侧只留数值 —— 这样宽屏上数值能对齐成一条竖线，扫起来是五个数而不是五组字。
 */
@Composable
private fun RuntimeRow(row: RuntimeRowUi, wide: Boolean) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen
    val statusText = row.statusText()
    // 注意要有花括号：`"$row.meta"` 会被解析成「$row 的 toString() + 字面量 .meta」，
    // 屏幕上就是一行 RuntimeRowUi(key=…)，看起来像数据没绑上，其实是模板写错了
    // 「从未启动」已经是状态本身，再并一个「未启动」就是同一句话说两遍
    val mergeStatus = wide && !row.running && row.status != CloneStatus.NeverLaunched
    val meta = if (mergeStatus) "${row.meta} · $statusText" else row.meta

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(d.runtimeRowHeight)
            .drawCard(
                fill = colors.surface,
                isDark = colors.isDark,
                corner = d.runtimeRowCorner,
                border = if (row.running) colors.green else Color.Transparent,
            )
            .padding(start = 14.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconTile(
            pkg = row.pkg,
            size = d.runtimeIcon,
            corner = 10.dp,
            fallbackLabel = row.label,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.label,
                color = colors.textPrimary,
                style = HzTheme.type.cardTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = meta,
                color = colors.textSecondary,
                style = HzTheme.type.cardMeta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (wide) {
            Text(
                text = row.sizeText,
                color = colors.textPrimary,
                style = HzTheme.type.cardTitle,
                maxLines = 1,
            )
        } else {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = row.sizeText,
                    color = colors.textPrimary,
                    style = HzTheme.type.cardTitle,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = statusText,
                    color = row.status.color(),
                    style = HzTheme.type.status,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 状态 → 文案。
 *
 * 与主页**故意不同**：主页那张卡的回答是「这个分身能不能点」（未安装 / 可更新），
 * 这一页的回答是「它现在活着吗」（未运行 / 前台运行）。同一个 [CloneStatus]
 * 在两处的措辞不一样，是这两页问的问题不一样，不是文案没统一。
 */
@Composable
private fun RuntimeRowUi.statusText(): String = when (status) {
    CloneStatus.NotInstalled -> stringResource(R.string.status_not_installed)
    CloneStatus.Updatable -> stringResource(R.string.badge_updatable)
    CloneStatus.SourceGone -> stringResource(R.string.status_source_gone)
    CloneStatus.Risky -> stringResource(R.string.rt_status_risky)
    CloneStatus.Running -> if (foreground) {
        stringResource(R.string.rt_status_foreground)
    } else {
        stringResource(R.string.runtime_state_running)
    }
    CloneStatus.Recent -> stringResource(R.string.rt_status_idle)
    CloneStatus.NeverLaunched -> stringResource(R.string.status_not_launched)
}

@Composable
private fun CloneStatus.color(): Color {
    val colors = HzTheme.colors
    return when (this) {
        CloneStatus.Updatable -> colors.accent
        CloneStatus.Risky -> colors.orange
        CloneStatus.Running -> colors.green
        else -> colors.textSecondary
    }
}

// ------------------------------------------------------------------ 右栏面板（平板）

@Composable
private fun SourcePanel() {
    val colors = HzTheme.colors
    PanelCard {
        Text(
            text = stringResource(R.string.rt_panel_source),
            color = colors.textPrimary,
            style = HzTheme.type.cardTitle,
        )
        Spacer(Modifier.height(10.dp))
        PanelNote(
            head = stringResource(R.string.rt_panel_source_storage),
            body = stringResource(R.string.rt_panel_source_storage_body),
            headColor = colors.textPrimary,
            bodyColor = colors.textSecondary,
        )
        Spacer(Modifier.height(10.dp))
        PanelNote(
            head = stringResource(R.string.rt_panel_source_usage),
            body = stringResource(R.string.rt_panel_source_usage_body),
            headColor = colors.textPrimary,
            bodyColor = colors.textSecondary,
        )
        Spacer(Modifier.height(10.dp))
        // 这一条用橙色而不是灰色：它是**限制**，不是说明。用户看到"没有内存数据"
        // 时的第一反应是"是不是坏了"，得先让他知道这是系统不给，不是这里没做
        PanelNote(
            head = stringResource(R.string.rt_panel_source_mem),
            body = stringResource(R.string.rt_panel_source_mem_body),
            headColor = colors.orange,
            bodyColor = colors.orange,
        )
    }
}

@Composable
private fun CachePanel(cacheBytes: Long) {
    val colors = HzTheme.colors
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.rt_metric_cache_wide),
                color = colors.textPrimary,
                style = HzTheme.type.cardTitle,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = Formats.compact(cacheBytes),
                color = if (cacheBytes > 0L) colors.orange else colors.green,
                style = HzTheme.type.cardTitle,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.rt_panel_cache_body),
            color = colors.textSecondary,
            style = HzTheme.type.panelBody,
        )
    }
}

@Composable
private fun AutoPanel(
    state: RuntimeUiState,
    onRefresh: () -> Unit,
    onToggleAuto: (Boolean) -> Unit,
) {
    val colors = HzTheme.colors
    val last = if (state.lastRefreshMs <= 0L) {
        stringResource(R.string.rt_just_now)
    } else {
        Formats.relative(state.lastRefreshMs)
    }

    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.rt_panel_auto),
                    color = colors.textPrimary,
                    style = HzTheme.type.cardTitle,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.rt_panel_auto_sub),
                    color = colors.textSecondary,
                    style = HzTheme.type.panelBody,
                    maxLines = 1,
                )
            }
            Switch(
                checked = state.autoRefresh,
                onCheckedChange = onToggleAuto,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = colors.green,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = if (colors.isDark) Color(0xFF3A3A3C) else colors.divider,
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.14f))
                .clickableBouncy(onClick = onRefresh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.rt_action_refresh_now),
                color = colors.accent,
                style = HzTheme.type.button,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.rt_last_refresh, last),
            color = colors.textTertiary,
            style = HzTheme.type.panelBody,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 面板底板。内边距 16dp 四周等宽 —— 面板窄，任何一侧收窄都会立刻显出来 */
@Composable
private fun PanelCard(content: @Composable () -> Unit) {
    val colors = HzTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawCard(colors.surface, colors.isDark, HzTheme.dimen.runtimeRowCorner)
            .padding(14.dp),
    ) {
        content()
    }
}

@Composable
private fun PanelNote(head: String, body: String, headColor: Color, bodyColor: Color) {
    Column {
        Text(
            text = head,
            color = headColor,
            style = HzTheme.type.section,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = body,
            color = bodyColor,
            style = HzTheme.type.panelBody,
        )
    }
}

// ------------------------------------------------------------------ 附带状态

/**
 * 没授权时的入口。
 *
 * 不授权这一页几乎全是空的（存储、启动时间都读不到），所以它必须出现在**列表位置**
 * 而不是藏在脚注里 —— 用户看到的每个 0 都要能对应到这一句解释。
 */
@Composable
private fun GrantCard(onGrant: () -> Unit) {
    val colors = HzTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .drawCard(colors.surface, colors.isDark, HzTheme.dimen.runtimeRowCorner)
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.runtime_grant_title),
            color = colors.textPrimary,
            style = HzTheme.type.cardTitle,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.runtime_grant_body),
            color = colors.textSecondary,
            style = HzTheme.type.cardMeta,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.runtime_grant_action),
            color = colors.accent,
            style = HzTheme.type.status,
            modifier = Modifier
                .clickableBouncy(onClick = onGrant)
                .padding(vertical = 4.dp, horizontal = 2.dp),
        )
    }
}

@Composable
private fun EmptyRuntime() {
    val colors = HzTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.runtime_empty_title),
            color = colors.textPrimary,
            style = HzTheme.type.cardTitle,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.runtime_empty_hint),
            color = colors.textSecondary,
            style = HzTheme.type.cardMeta,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun FooterNote() {
    Text(
        text = stringResource(R.string.rt_note_memory),
        color = HzTheme.colors.textTertiary,
        style = HzTheme.type.cardMeta,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
    )
}

/**
 * 实心圆角底。
 *
 * 与主页卡片同一套规则：深色下补一道 6% 的白描边（卡片 #1C1C1E 与底 #000000
 * 只差 12 阶亮度，没有描边就分不出边界），浅色下不描。
 */
private fun Modifier.drawCard(
    fill: Color,
    isDark: Boolean,
    corner: Dp,
    border: Color = Color.Transparent,
): Modifier = drawBehind {
    val r = CornerRadius(corner.toPx(), corner.toPx())
    drawRoundRect(color = fill, cornerRadius = r)
    if (isDark) {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.06f),
            cornerRadius = r,
            style = Stroke(width = 0.75.dp.toPx()),
        )
    }
    if (border != Color.Transparent) {
        drawRoundRect(color = border, cornerRadius = r, style = Stroke(width = 1.5.dp.toPx()))
    }
}
