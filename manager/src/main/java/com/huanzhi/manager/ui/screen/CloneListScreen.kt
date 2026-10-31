package com.huanzhi.manager.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.huanzhi.manager.R
import com.huanzhi.manager.core.CloneRecord
import com.huanzhi.manager.ui.components.CloneCard
import com.huanzhi.manager.ui.components.GlassTabBar
import com.huanzhi.manager.ui.components.MainTab
import com.huanzhi.manager.ui.components.SectionLabel
import com.huanzhi.manager.ui.components.StatusPill
import com.huanzhi.manager.ui.glass.GlassIconButton
import com.huanzhi.manager.ui.glass.clickableBouncy
import com.huanzhi.manager.ui.theme.HzTheme

/**
 * 卡片右侧状态的**语义**，不携带颜色。
 *
 * 颜色是主题的事：同一个「运行中」在浅色主题是 #248A3D、深色是 #30D158，
 * 把这个判断塞进数据层，数据层就得知道当前是深色还是浅色 —— 那是 UI 的职责。
 *
 * 枚举顺序即优先级，见 [statusOf]。
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
)

/**
 * 主页：分身列表。
 *
 * 结构对位 `design/04-手机-分身列表.png`（浅色）与 `20-手机深色-分身列表.png`：
 *   大标题 + 右上圆形「+」→ 统计副标题 → 两枚状态徽章
 *   → 「我的分身 / 更新全部」→ 卡片列表 → 底部玻璃胶囊导航
 *
 * 导航栏用 align(BottomCenter) 浮在列表上方，而不是 LazyColumn 的 bottomBar：
 * 列表内容滚到导航栏下面时会被它盖住，这正是设计稿的效果（玻璃层压住内容）。
 * 代价是 LazyColumn 的 contentPadding 必须留出等高的空间，否则最后一张卡
 * 永远停在导航栏后面，用户以为列表滚不到底。
 */
@Composable
fun CloneListScreen(
    state: CloneListUiState,
    onAdd: () -> Unit,
    onUpdateAll: () -> Unit,
    onCardClick: (CloneRecord) -> Unit,
    onTabSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
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
                        val (statusText, statusColor) = card.label()
                        CloneCard(
                            label = card.rec.label,
                            meta = card.meta,
                            statusText = statusText,
                            statusColor = statusColor,
                            iconPkg = card.rec.targetPkg,
                            onClick = { onCardClick(card.rec) },
                        )
                    }
                }
            }
        }

        GlassTabBar(
            selected = MainTab.Clones,
            onSelect = onTabSelect,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = d.tabBarSideInset)
                .padding(bottom = d.tabBarInset),
        )
    }
}

/**
 * 头部。
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
                    com.huanzhi.manager.util.Formats.size(totalOccupied),
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
