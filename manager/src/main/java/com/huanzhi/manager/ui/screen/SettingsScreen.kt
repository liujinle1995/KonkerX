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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.huanzhi.manager.R
import com.huanzhi.manager.ui.adaptive.WindowSizeClass
import com.huanzhi.manager.ui.adaptive.rememberWindowSizeClass
import com.huanzhi.manager.ui.components.Chevron
import com.huanzhi.manager.ui.components.PageTitleRow
import com.huanzhi.manager.ui.glass.clickableBouncy
import com.huanzhi.manager.ui.theme.HzTheme
import com.huanzhi.manager.ui.theme.HzIconTint

/**
 * 设置页要显示的全部数据。
 *
 * 和 [CloneListUiState] 一样只带**值**不带样式：这里的每一行右侧都是"当前状态"
 * （1.3 GB / 0 B / 3 条记录），Activity 算好字符串塞进来，Composable 负责排版。
 */
data class SettingsUiState(
    val loading: Boolean = true,
    /** SettingsStore.THEME_* 之一 */
    val themeMode: Int = 0,
    /** SettingsStore.INDEX_* 之一 */
    val indexScale: Int = 1,
    val cloneCount: Int = 0,
    val totalOccupied: Long = 0L,
    val cacheBytes: Long = 0L,
    val cacheFiles: Int = 0,
    val logCount: Int = 0,
    /** 待确认的失效记录条数（「清理与卸载记录」右侧那个数） */
    val pendingRecords: Int = 0,
    val versionName: String = "",
    val versionDate: String = "",
)

/**
 * 设置页。
 *
 * 结构对位 `design/08-手机-设置.png` 与 `09-平板-设置.png`：
 *   页标题 + 四组设置（外观 / 存储与清理 / 诊断 / 关于）
 *   手机单列纵向排；平板**分成左右两栏**，左栏外观 + 存储，右栏诊断 + 关于。
 *
 * ── 平板为什么是两栏而不是多几份网格 ───────────────────────────────────────
 * 设置项是一组一组的小卡片，撑不满一屏。如果像分身列表那样铺网格，
 * 每张卡还会被拉宽到 500dp、右侧空出半屏 —— 那只是"利用了空间"，没有"利用好"。
 * 设计稿的做法是把四组按语义分到两列（左侧的是"日常会改的"，右侧是"偶尔点开的"），
 * 高度也刚好接近，看着才稳定。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onPickTheme: () -> Unit,
    onPickIndexSize: () -> Unit,
    onOpenCleanup: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenVersion: () -> Unit,
    onOpenDocs: () -> Unit,
    onOpenSupport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (rememberWindowSizeClass()) {
        WindowSizeClass.EXPANDED -> TabletSettings(
            state, onPickTheme, onPickIndexSize, onOpenCleanup,
            onOpenLog, onOpenVersion, onOpenDocs, onOpenSupport, modifier,
        )

        else -> PhoneSettings(
            state, onPickTheme, onPickIndexSize, onOpenCleanup,
            onOpenLog, onOpenVersion, onOpenDocs, onOpenSupport, modifier,
        )
    }
}

// ------------------------------------------------------------------ 两组分组定义

/** 一行的内容。`value` 为 null 表示右侧只有箭头（或什么都没有） */
private data class SettingRow(
    val title: String,
    val value: String?,
    val iconRes: Int? = null,
    val iconTint: Color = HzIconTint.gray,
    val onClick: (() -> Unit)? = null,
    /** 不可逆动作：标题着红 */
    val danger: Boolean = false,
)

@Composable
private fun buildGroups(
    state: SettingsUiState,
    onPickTheme: () -> Unit,
    onPickIndexSize: () -> Unit,
    onOpenCleanup: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenVersion: () -> Unit,
    onOpenDocs: () -> Unit,
    onOpenSupport: () -> Unit,
): List<Pair<String, List<SettingRow>>> {
    val themeLabel = when (state.themeMode) {
        com.huanzhi.manager.core.SettingsStore.THEME_LIGHT -> stringResource(R.string.theme_light)
        com.huanzhi.manager.core.SettingsStore.THEME_DARK -> stringResource(R.string.theme_dark)
        else -> stringResource(R.string.value_follow_system)
    }
    val indexLabel = when (state.indexScale) {
        com.huanzhi.manager.core.SettingsStore.INDEX_SMALL -> stringResource(R.string.index_size_small)
        com.huanzhi.manager.core.SettingsStore.INDEX_LARGE -> stringResource(R.string.index_size_large)
        else -> stringResource(R.string.index_size_normal)
    }

    return listOf(
        stringResource(R.string.row_appearance) to listOf(
            SettingRow(
                title = stringResource(R.string.row_appearance),
                value = themeLabel,
                iconRes = R.drawable.ic_sun,
                iconTint = HzIconTint.blue,
                onClick = onPickTheme,
            ),
            SettingRow(
                title = stringResource(R.string.row_index_size),
                value = indexLabel,
                iconRes = R.drawable.ic_list,
                iconTint = HzIconTint.gray,
                onClick = onPickIndexSize,
            ),
        ),
        stringResource(R.string.section_storage_clean) to listOf(
            SettingRow(
                title = stringResource(R.string.row_total_occupied),
                value = com.huanzhi.manager.util.Formats.knownSize(state.totalOccupied),
                iconRes = R.drawable.ic_bars,
                iconTint = HzIconTint.orange,
            ),
            SettingRow(
                title = stringResource(R.string.row_pending_cache),
                value = com.huanzhi.manager.util.Formats.knownSize(state.cacheBytes),
                iconRes = R.drawable.ic_box,
                iconTint = HzIconTint.green,
            ),
            SettingRow(
                title = stringResource(R.string.row_cleanup_records),
                value = if (state.pendingRecords > 0) {
                    stringResource(R.string.value_pending_n, state.pendingRecords)
                } else {
                    null
                },
                iconRes = R.drawable.ic_trash,
                iconTint = HzIconTint.red,
                onClick = onOpenCleanup,
            ),
        ),
        stringResource(R.string.section_diagnostics) to listOf(
            SettingRow(
                title = stringResource(R.string.row_install_log),
                value = stringResource(R.string.value_log_count, state.logCount),
                iconRes = R.drawable.ic_doc,
                iconTint = HzIconTint.gray,
                onClick = onOpenLog,
            ),
        ),
        stringResource(R.string.section_about) to listOf(
            SettingRow(
                title = stringResource(R.string.row_version),
                value = stringResource(R.string.value_version, "v${state.versionName}", state.versionDate),
                onClick = onOpenVersion,
            ),
            SettingRow(
                title = stringResource(R.string.row_plugin_doc),
                value = null,
                iconRes = R.drawable.ic_info,
                iconTint = HzIconTint.blue,
                onClick = onOpenDocs,
            ),
            SettingRow(
                title = stringResource(R.string.row_support),
                value = stringResource(R.string.value_qr_in_album),
                iconRes = R.drawable.ic_cup,
                iconTint = HzIconTint.pink,
                onClick = onOpenSupport,
            ),
        ),
    )
}

// ------------------------------------------------------------------ 手机

@Composable
private fun PhoneSettings(
    state: SettingsUiState,
    onPickTheme: () -> Unit,
    onPickIndexSize: () -> Unit,
    onOpenCleanup: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenVersion: () -> Unit,
    onOpenDocs: () -> Unit,
    onOpenSupport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen
    val groups = buildGroups(
        state, onPickTheme, onPickIndexSize, onOpenCleanup,
        onOpenLog, onOpenVersion, onOpenDocs, onOpenSupport,
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(
            start = d.pagePadding,
            end = d.pagePadding,
            // 底部导航胶囊压住内容的空间，与分身列表同一口径
            bottom = d.tabBarHeight + d.tabBarInset + 24.dp,
        ),
    ) {
        item(key = "title") {
            PageTitleRow(
                title = stringResource(R.string.settings),
                titleStyle = HzTheme.type.largeTitle,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
        }
        items(groups.size, key = { groups[it].first }) { i ->
            SettingsGroupBlock(title = groups[i].first, rows = groups[i].second)
            // 组间距 26dp：比组内行高的一半略多，扫视时"一组结束"比"一行结束"更明显
            Spacer(Modifier.height(26.dp))
        }
        item(key = "footer") {
            Text(
                text = stringResource(R.string.settings_footer),
                color = colors.textTertiary,
                style = HzTheme.type.cardMeta,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ 平板

@Composable
private fun TabletSettings(
    state: SettingsUiState,
    onPickTheme: () -> Unit,
    onPickIndexSize: () -> Unit,
    onOpenCleanup: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenVersion: () -> Unit,
    onOpenDocs: () -> Unit,
    onOpenSupport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen
    val groups = buildGroups(
        state, onPickTheme, onPickIndexSize, onOpenCleanup,
        onOpenLog, onOpenVersion, onOpenDocs, onOpenSupport,
    )

    val left = groups.take(2)
    val right = groups.drop(2)

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = d.contentPadding)
    ) {
        PageTitleRow(
            title = stringResource(R.string.settings),
            modifier = Modifier.padding(top = 24.dp, bottom = 16.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                left.forEach { (t, rows) -> SettingsGroupBlock(t, rows) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                right.forEach { (t, rows) -> SettingsGroupBlock(t, rows) }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.settings_footer),
            color = colors.textTertiary,
            style = HzTheme.type.cardMeta,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

// ------------------------------------------------------------------ 分组与行

/**
 * 一组设置：小标题 + 白色圆角容器。
 *
 * 分组标题 13sp 灰字、与卡片左边缘对齐（而不是缩进）—— 它是这一组的名字，
 * 不是卡片里的内容，所以要和卡片同一条基线，看上去才像"这组卡片的抬头"。
 */
@Composable
private fun SettingsGroupBlock(title: String, rows: List<SettingRow>) {
    val colors = HzTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = colors.textSecondary,
            style = HzTheme.type.section,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRoundRect(
                        color = colors.surface,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                            16.dp.toPx(), 16.dp.toPx()
                        ),
                    )
                }
                .clip(RoundedCornerShape(16.dp))
        ) {
            rows.forEachIndexed { i, row ->
                SettingRowView(row)
                if (i < rows.lastIndex) {
                    // 分隔线从文字起点开始，不从卡片左边缘 —— 这是 iOS 分组列表的标志性处理：
                    // 线跟着内容走，卡片边界由圆角自己承担，两者不会在同一处打结
                    RowDivider(inset = if (row.iconRes != null) 50.dp else 16.dp)
                }
            }
        }
    }
}

@Composable
private fun SettingRowView(row: SettingRow) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(d.rowHeight)
            .then(if (row.onClick != null) Modifier.clickableBouncy(onClick = row.onClick) else Modifier)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.iconRes != null) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(row.iconTint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(row.iconRes),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
        }

        Text(
            text = row.title,
            color = if (row.danger) colors.red else colors.textPrimary,
            style = HzTheme.type.cardTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 标题吃掉所有剩余宽度，右侧的值按内容自适应。
            // 两边都用 weight 的话这里会变成各占一半，「请作者喝一杯」会被自己的值挤断
            modifier = Modifier.weight(1f),
        )

        if (row.value != null) {
            Text(
                text = row.value,
                color = colors.textSecondary,
                style = HzTheme.type.status,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (row.onClick != null) {
            Spacer(Modifier.width(6.dp))
            Chevron(color = colors.textTertiary)
        }
    }
}

@Composable
private fun RowDivider(inset: androidx.compose.ui.unit.Dp) {
    val colors = HzTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(0.5.dp)
            .background(colors.divider)
    )
}
