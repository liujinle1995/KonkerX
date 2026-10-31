package com.huanzhi.manager.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.huanzhi.manager.ui.adaptive.WindowSizeClass
import com.huanzhi.manager.ui.adaptive.rememberWindowSizeClass
import com.huanzhi.manager.ui.components.GlassTabBar
import com.huanzhi.manager.ui.components.MainTab
import com.huanzhi.manager.ui.components.SideRail
import com.huanzhi.manager.ui.theme.HzTheme
import androidx.compose.ui.unit.dp

/**
 * 应用外壳：**同一份内容，两种导航**。
 *
 *   COMPACT / MEDIUM → 内容铺满，底部浮一枚玻璃胶囊
 *   EXPANDED        → 左侧 220dp 侧栏 + 右侧内容区
 *
 * ── 为什么导航不自己判断设备「是不是平板」 ───────────────────────────────────
 * 判据是**当前窗口宽度**（840dp），不是 `sw600dp` 之类的设备属性。Fold 折叠起来
 * 是手机、展开是平板，平板分屏可以窄到 500dp —— 同一个应用在同一台设备上
 * 两种形态来回切是常态，跟着窗口走才不会出现"侧栏把内容挤成一条"。
 *
 * ── 为什么底部导航要浮在内容上，而侧栏不 ─────────────────────────────────────
 * 玻璃胶囊压在列表上是设计稿的意图（滚动内容从它底下穿过去）；侧栏则是与内容区
 * 平级的一列，内容从它右边开始排 —— 一个覆盖、一个占位，这是两种不同的关系。
 */
@Composable
fun AppScaffold(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    railTitle: String,
    railSubtitle: String,
    railFooter: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen

    when (rememberWindowSizeClass()) {
        WindowSizeClass.EXPANDED -> {
            Row(
                modifier = modifier
                    .fillMaxSize()
                    .background(colors.background)
            ) {
                SideRail(
                    selected = selected,
                    onSelect = onSelect,
                    title = railTitle,
                    subtitle = railSubtitle,
                    footer = railFooter,
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .background(colors.background)
                ) {
                    content()
                }
            }
        }

        else -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(colors.background)
            ) {
                content()
                GlassTabBar(
                    selected = selected,
                    onSelect = onSelect,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = d.tabBarSideInset)
                        .padding(bottom = d.tabBarInset),
                )
            }
        }
    }
}
