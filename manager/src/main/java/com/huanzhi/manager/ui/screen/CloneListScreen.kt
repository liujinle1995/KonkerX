package com.huanzhi.manager.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.huanzhi.manager.R
import com.huanzhi.manager.core.CloneRecord
import com.huanzhi.manager.ui.adaptive.WindowSizeClass
import com.huanzhi.manager.ui.adaptive.gridColumns
import com.huanzhi.manager.ui.adaptive.rememberWindowSizeClass
import com.huanzhi.manager.ui.components.CloneCard
import com.huanzhi.manager.ui.components.PageTitleRow
import com.huanzhi.manager.ui.components.PrimaryPillButton
import com.huanzhi.manager.ui.components.SectionLabel
import com.huanzhi.manager.ui.components.StatusPill
import com.huanzhi.manager.ui.glass.GlassIconButton
import com.huanzhi.manager.ui.glass.clickableBouncy
import com.huanzhi.manager.ui.theme.HzTheme
import com.huanzhi.manager.util.Formats

/**
 * 卡片右侧状态的**语义**，不携带颜色。
 *
 * 颜色是主题的事：同一个「运行中」在浅色主题是 #248A3D、深色是 #30D158，
 * 把这个判断塞进数据层，数据层就得知道当前是深色还是浅色 —— 那是 UI 的职责。
 *
 * 枚举顺序即优先级，见 MainActivity.statusOf。
 */
enum class CloneStatus {
    /** 还没装进系统（装到一半取消、或装完被卸载但记录还在） */
    NotInstalled,

    /** 源应用升级了，这份还是旧的 */
    Updatable,

    /** 源应用已经不在设备上：分身能跑，但没法再重建 */
    SourceGone,

    /** 检测到加固壳，启动大概率失败 */
    Risky,

    /** 进程在跑或在后台驻留 */
    Running,

    /** 有使用记录但不是正在跑 —— 显示相对时间 */
    Recent,

    /** 从未启动过 */
    NeverLaunched,
}

/**
 * 一张卡片要显示的全部信息。
 *
 * 只带语义，不带颜色；`meta` 是副标题（包名 · 体积），`recentLabel` 仅在
 * [CloneStatus.Recent] 时有值（"12 分钟前"）。
 */
data class CloneCardUi(
    val rec: CloneRecord,
    val meta: String,
    val status: CloneStatus,
    val recentLabel: String = "",
)

data class CloneListUiState(
    val loading: Boolean = true,
    val cards: List<CloneCardUi> = emptyList(),
    val totalOccupied: Long = 0L,
    val runningCount: Int = 0,
    val updatableCount: Int = 0,
    /** 正在前台的分身名（"微信 · 工作"），没有则为空串。平板概览摘要用 */
    val foregroundLabel: String = "",
    /** 待清理的缓存包体积。平板概览摘要用 */
    val cacheBytes: Long = 0L,
)

/**
 * 主页：分身列表。
 *
 * 两种形态，同一份数据：
 *   COMPACT / MEDIUM —— 单列表（`design/04-手机-分身列表.png`）
 *   EXPANDED        —— 2 列网格 + 底部运行概览摘要（`design/26-平板深色-分身列表.png`）
 *
 * 导航栏不在这里画，由 [com.huanzhi.manager.ui.nav.AppScaffold] 提供 ——
 * 手机是浮在内容上的玻璃胶囊、平板是左侧栏，那是外壳的职责。
 * 页面自己只关心"内容区里放什么"。
 */
@Composable
fun CloneListScreen(
    state: CloneListUiState,
    onAdd: () -> Unit,
    onUpdateAll: () -> Unit,
    onCardClick: (CloneRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (rememberWindowSizeClass()) {
        WindowSizeClass.EXPANDED -> TabletCloneList(state, onAdd, onUpdateAll, onCardClick, modifier)
        else -> PhoneCloneList(state, onAdd, onUpdateAll, onCardClick, modifier)
    }
}

// ------------------------------------------------------------------ 手机版

/**
 * 导航栏用 align(BottomCenter) 浮在列表上方（见 AppScaffold），而不是 LazyColumn 的
 * bottomBar：列表内容滚到导航栏下面时会被它盖住，这正是设计稿的效果（玻璃层压住内容）。
 * 代价是 LazyColumn 的 contentPadding 必须留出等高的空间，否则最后一张卡
 * 永远停在导航栏后面，用户以为列表滚不到底。
 */
@Composable
private fun PhoneCloneList(
    state: CloneListUiState,
    onAdd: () -> Unit,
    onUpdateAll: () -> Unit,
    onCardClick: (CloneRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen

    Box(modifier.fillMaxSize()) {
        if (state.loading) {
            Loading(colors.accent)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
                contentPadding = PaddingValues(
                    start = d.pagePadding,
                    end = d.pagePadding,
                    // 导航栏高度 + 它距底部的留白 + 一点余量，最后一张卡才不会被永久压住
                    bottom = d.tabBarHeight + d.tabBarInset + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(d.cardGap),
            ) {
                item(key = "header") {
                    HomeHeader(
                        totalCount = state.cards.size,
                        totalOccupied = state.totalOccupied,
                        runningCount = state.runningCount,
                        updatableCount = state.updatableCount,
                        onAdd = onAdd,
                        onUpdateAll = onUpdateAll,
                    )
                }

                if (state.cards.isEmpty()) {
                    item(key = "empty") { EmptyState(onAdd = onAdd) }
                } else {
                    items(state.cards, key = { it.rec.clonePkg }) { card ->
                        CloneCardRow(card, onCardClick)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 平板版

/**
 * 平板首页。
 *
 * 结构对位 `26-平板深色-分身列表.png`：
 *   页标题 24sp + 右上「+ 新建分身」胶囊 → 状态徽章行 → 2 列卡片网格 → 运行概览摘要
 *
 * 列数不写死 2：内容区宽到能放下 3 张 340dp 的卡时就给 3 列（见 [gridColumns]）。
 * 设计稿只有 1024dp 这一档，硬编码 2 列在 1280dp 的桌面窗口里会得到两张
 * 各 600dp 宽、右边空掉 1/3 的卡 —— 那不是"还原设计"，是没适配。
 */
@Composable
private fun TabletCloneList(
    state: CloneListUiState,
    onAdd: () -> Unit,
    onUpdateAll: () -> Unit,
    onCardClick: (CloneRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen

    Box(modifier.fillMaxSize()) {
        if (state.loading) {
            Loading(colors.accent)
        } else {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                val inner = maxWidth - d.contentPadding * 2
                val cols = gridColumns(inner, gap = d.gridGap)

                LazyVerticalGrid(
                    columns = GridCells.Fixed(cols),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = d.contentPadding,
                        end = d.contentPadding,
                        top = 24.dp,
                        bottom = 32.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(d.gridGap),
                    // 行距 8dp：平板卡片 112dp 高，纵向再拉开就和横向的 14dp 失衡了
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                        TabletHeader(
                            totalCount = state.cards.size,
                            runningCount = state.runningCount,
                            updatableCount = state.updatableCount,
                            onAdd = onAdd,
                            onUpdateAll = onUpdateAll,
                        )
                    }

                    if (state.cards.isEmpty()) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(onAdd = onAdd)
                        }
                    } else {
                        items(state.cards, key = { it.rec.clonePkg }) { card ->
                            CloneCardRow(card, onCardClick, height = d.wideCardHeight)
                        }
                    }

                    item(key = "runtime", span = { GridItemSpan(maxLineSpan) }) {
                        RuntimeSummarySection(
                            totalCount = state.cards.size,
                            totalOccupied = state.totalOccupied,
                            runningCount = state.runningCount,
                            foregroundLabel = state.foregroundLabel,
                            cacheBytes = state.cacheBytes,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabletHeader(
    totalCount: Int,
    runningCount: Int,
    updatableCount: Int,
    onAdd: () -> Unit,
    onUpdateAll: () -> Unit,
) {
    val colors = HzTheme.colors

    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
    ) {
        PageTitleRow(
            title = stringResource(R.string.home_section_mine),
            trailing = {
                PrimaryPillButton(
                    text = stringResource(R.string.new_clone),
                    iconRes = R.drawable.ic_add,
                    onClick = onAdd,
                )
            },
        )

        Spacer(Modifier.height(12.dp))

        if (runningCount > 0 || updatableCount > 0) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (runningCount > 0) {
                    StatusPill(
                        text = stringResource(R.string.badge_running_n, runningCount),
                        dotColor = colors.green,
                        background = colors.badgeGreenBg,
                    )
                }
                if (updatableCount > 0) {
                    StatusPill(
                        text = stringResource(R.string.badge_updatable_n, updatableCount),
                        dotColor = colors.accent,
                        background = colors.badgeBlueBg,
                    )
                    Text(
                        text = stringResource(R.string.action_update_all),
                        color = colors.accent,
                        style = HzTheme.type.status,
                        modifier = Modifier
                            .clickableBouncy(onClick = onUpdateAll)
                            .padding(vertical = 4.dp, horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * 运行概览摘要。
 *
 * 三张等宽卡：总占用 / 当前前台 / 待清理缓存。平板的一屏比手机宽得多，
 * 分身网格铺完后模型上还剩一大片空白 —— 设计稿在这里补的正是"我最关心的三个数"，
 * 而不是把卡片拉大。
 */
@Composable
private fun RuntimeSummarySection(
    totalCount: Int,
    totalOccupied: Long,
    runningCount: Int,
    foregroundLabel: String,
    cacheBytes: Long,
) {
    val colors = HzTheme.colors

    Column(Modifier.padding(top = 26.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_section_runtime),
                color = colors.textPrimary,
                style = HzTheme.type.cardTitle,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.home_runtime_hint),
                color = colors.textTertiary,
                style = HzTheme.type.cardMeta,
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MetricCard(
                title = stringResource(R.string.runtime_card_total),
                value = Formats.knownSize(totalOccupied),
                caption = stringResource(R.string.runtime_card_total_sub, totalCount),
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                title = stringResource(R.string.runtime_card_foreground),
                value = runningCount.toString(),
                valueColor = if (runningCount > 0) colors.green else colors.textPrimary,
                caption = if (foregroundLabel.isNotBlank()) {
                    stringResource(R.string.runtime_card_foreground_sub, foregroundLabel)
                } else {
                    stringResource(R.string.runtime_card_foreground_none)
                },
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                title = stringResource(R.string.runtime_card_cache),
                value = Formats.knownSize(cacheBytes),
                caption = if (cacheBytes > 0L) {
                    stringResource(R.string.runtime_card_cache_sub, Formats.knownSize(cacheBytes))
                } else {
                    stringResource(R.string.runtime_card_cache_sub_zero)
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    valueColor: Color = HzTheme.colors.textPrimary,
) {
    val colors = HzTheme.colors
    Column(
        modifier
            .height(92.dp)
            .drawBehindCard(colors.surface, colors.isDark)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            color = colors.textSecondary,
            style = HzTheme.type.cardMeta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            color = valueColor,
            style = HzTheme.type.metric,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = caption,
            color = colors.textSecondary,
            style = HzTheme.type.cardMeta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 实心圆角底。
 *
 * 深色下**不描边**：卡片 #1C1C1E 与底 #000000 只差 12 阶亮度，加一圈 1.5dp 的
 * 半透明白边会比卡片本身更显眼（见 GlassTokens 里 DarkPalette 的注释）。
 * 浅色下同理，但原因是白卡躺在 #F2F2F7 上已经靠明度分开了。
 */
private fun Modifier.drawBehindCard(fill: Color, isDark: Boolean): Modifier =
    this
        .drawBehind {
            val r = CornerRadius(16.dp.toPx(), 16.dp.toPx())
            drawRoundRect(color = fill, cornerRadius = r)
            if (isDark) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.06f),
                    cornerRadius = r,
                    style = Stroke(width = 0.75.dp.toPx()),
                )
            }
        }

// ------------------------------------------------------------------ 共用

@Composable
private fun CloneCardRow(
    card: CloneCardUi,
    onCardClick: (CloneRecord) -> Unit,
    height: Dp = 0.dp,
) {
    val (statusText, statusColor) = card.label()
    CloneCard(
        label = card.rec.label,
        meta = card.meta,
        statusText = statusText,
        statusColor = statusColor,
        iconPkg = card.rec.targetPkg,
        height = height,
        onClick = { onCardClick(card.rec) },
    )
}

@Composable
private fun Loading(color: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = color)
    }
}

/**
 * 头部（手机）。
 *
 * 「+」与大标题同一行、垂直居中 —— 设计稿里它不是悬挂在标题下方的独立 FAB，
 * 而是这一行的一部分。做成 FAB 会引入阴影与"悬浮"的语义，与稿面不符。
 */
@Composable
private fun HomeHeader(
    totalCount: Int,
    totalOccupied: Long,
    runningCount: Int,
    updatableCount: Int,
    onAdd: () -> Unit,
    onUpdateAll: () -> Unit,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.app_name),
                color = colors.textPrimary,
                style = HzTheme.type.largeTitle,
                modifier = Modifier.weight(1f),
            )
            GlassIconButton(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = stringResource(R.string.home_add_desc),
                onClick = onAdd,
            )
        }

        Spacer(Modifier.height(d.titleGap))

        Text(
            text = if (totalCount == 0) {
                stringResource(R.string.home_summary_zero, totalCount)
            } else {
                stringResource(
                    R.string.home_summary,
                    totalCount,
                    Formats.size(totalOccupied),
                )
            },
            color = colors.textSecondary,
            style = HzTheme.type.subtitle,
        )

        if (runningCount > 0 || updatableCount > 0) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (runningCount > 0) {
                    StatusPill(
                        text = stringResource(R.string.badge_running_n, runningCount),
                        dotColor = colors.green,
                        background = colors.badgeGreenBg,
                    )
                }
                if (updatableCount > 0) {
                    StatusPill(
                        text = stringResource(R.string.badge_updatable_n, updatableCount),
                        dotColor = colors.accent,
                        background = colors.badgeBlueBg,
                    )
                }
            }
        }

        Spacer(Modifier.height(d.sectionGap))

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.home_section_mine), Modifier.weight(1f))
            if (updatableCount > 0) {
                Text(
                    text = stringResource(R.string.action_update_all),
                    color = colors.accent,
                    style = HzTheme.type.status,
                    modifier = Modifier
                        .clickableBouncy(onClick = onUpdateAll)
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                )
            }
        }
    }
}

/**
 * 语义状态 → 文案与颜色。
 *
 * 全部落在 13sp 的位置上，所以文案必须是短词；长句（比如完整的
 * 「源应用已卸载」）留给详情页，这里只保证一眼能扫出哪几行需要处理。
 */
@Composable
private fun CloneCardUi.label(): Pair<String, Color> {
    val colors = HzTheme.colors
    return when (status) {
        CloneStatus.NotInstalled ->
            stringResource(R.string.status_not_installed) to colors.textSecondary
        CloneStatus.Updatable ->
            stringResource(R.string.badge_updatable) to colors.accent
        CloneStatus.SourceGone ->
            stringResource(R.string.status_source_gone) to colors.textSecondary
        CloneStatus.Risky ->
            stringResource(R.string.status_risky) to colors.orange
        CloneStatus.Running ->
            stringResource(R.string.status_running) to colors.green
        CloneStatus.Recent ->
            recentLabel to colors.textSecondary
        CloneStatus.NeverLaunched ->
            stringResource(R.string.status_not_launched) to colors.textSecondary
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    val colors = HzTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.empty_title),
            color = colors.textPrimary,
            style = HzTheme.type.cardTitle,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_hint),
            color = colors.textSecondary,
            style = HzTheme.type.cardMeta,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.pick_app),
            color = colors.accent,
            style = HzTheme.type.button,
            modifier = Modifier
                .clickableBouncy(onClick = onAdd)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
