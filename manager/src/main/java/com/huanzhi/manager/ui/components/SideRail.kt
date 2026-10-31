package com.huanzhi.manager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.huanzhi.manager.R
import com.huanzhi.manager.ui.glass.clickableBouncy
import com.huanzhi.manager.ui.theme.HzTheme

/**
 * 平板左侧栏导航。
 *
 * 结构对位 `design/09-平板-设置.png` 与 `26-平板深色-分身列表.png`：
 *   应用名 + 统计副标题 → 三个导航项（48dp 高、8dp 内缩的圆角块）→ 底部缓存提示
 *
 * ── 为什么侧栏是固定 220dp 而不是按比例 ─────────────────────────────────────
 * 侧栏里最长的一行是「5 个分身 · 已占用 1.3 GB」，量下来 190dp 出头。按比例做的话，
 * 1024dp 窗口给 22%、1600dp 桌面窗口就给 352dp —— 多出来的 130dp 里什么都没有，
 * 只是把内容区无端压窄。固定宽度在宽屏下反而让内容区拿到更多空间。
 *
 * ── 为什么不用 NavigationRail ───────────────────────────────────────────────
 * Material 的 NavigationRail 是"图标在上、标签在下"的窄条（默认 80dp），
 * 设计稿是"图标在左、文字在右"的宽栏，两者不是同一个东西，套不进它的槽位。
 */
@Composable
fun SideRail(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    title: String,
    subtitle: String,
    footer: String,
    modifier: Modifier = Modifier,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen

    Row(modifier.fillMaxHeight()) {
        Column(
            Modifier
                .width(d.railWidth)
                .fillMaxHeight()
                .background(colors.railBg)
                .statusBarsPadding()
                .padding(top = 24.dp),
        ) {
            Text(
                text = title,
                color = colors.textPrimary,
                style = HzTheme.type.railTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = colors.textSecondary,
                style = HzTheme.type.cardMeta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Spacer(Modifier.height(16.dp))

            Column(
                Modifier.padding(horizontal = d.railItemInset),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                MainTab.entries.forEach { tab ->
                    RailItem(
                        tab = tab,
                        label = stringResource(tab.navLabelRes),
                        selected = tab == selected,
                        onClick = { onSelect(tab) },
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = footer,
                color = colors.textTertiary,
                style = HzTheme.type.tab,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            )
        }

        // 分隔线画在侧栏外侧的 0.5dp 竖条上，而不是给侧栏加右边框 ——
        // 侧栏自己要能整块换底色（深浅两档色值不同），边框容易在深色下糊掉
        Box(
            Modifier
                .width(0.5.dp)
                .fillMaxHeight()
                .background(colors.railDivider)
        )
    }
}

@Composable
private fun RailItem(
    tab: MainTab,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(d.railItemHeight)
            .clip(RoundedCornerShape(d.railItemCorner))
            .background(if (selected) colors.railSelected else Color.Transparent)
            .clickableBouncy(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = label
                this.selected = selected
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(tab.iconRes),
            contentDescription = null,
            tint = if (selected) colors.accent else colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = if (selected) colors.textPrimary else colors.textSecondary,
            style = HzTheme.type.railItem,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 主按钮：实心强调色胶囊（「+ 新建分身」）。
 *
 * 尺寸量自 26 稿：448×192px @4x = 112×48dp，圆角即半高。
 * 手机上同一操作仍是右上角的圆形玻璃按钮 —— 那里没有地方写四个字，
 * 而平板上有。这不是两套按钮，是同一个动作在两种宽度下的两种表达。
 */
@Composable
fun PrimaryPillButton(
    text: String,
    iconRes: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HzTheme.colors
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.accent)
            .clickableBouncy(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            color = Color.White,
            style = HzTheme.type.status,
            maxLines = 1,
        )
    }
}

/** 侧栏/内容区共用的页标题行：左标题 + 右操作区 */
@Composable
fun PageTitleRow(
    title: String,
    modifier: Modifier = Modifier,
    titleStyle: androidx.compose.ui.text.TextStyle = HzTheme.type.pageTitle,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = HzTheme.colors.textPrimary,
            style = titleStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}
