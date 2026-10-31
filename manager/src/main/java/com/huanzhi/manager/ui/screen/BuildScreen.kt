package com.huanzhi.manager.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanzhi.manager.R
import com.huanzhi.manager.ui.glass.GlassSurface
import com.huanzhi.manager.ui.glass.clickableBouncy
import com.huanzhi.manager.ui.theme.HzIconTint
import com.huanzhi.manager.ui.theme.HzTheme

/**
 * 构建进度页。
 *
 * 这一页替换的是原来的「正在批量创建…」进度弹窗。换掉的理由不是弹窗丑，
 * 而是**弹窗只能表达一个数字**：7 份里做到第 3 份、前面两份成功了、第 4 份正在打包 ——
 * 这些信息在一根 28% 的进度条里全被压扁了。用户盯着它五分钟，能得到的唯一结论是
 * 「还没好」，于是每隔半分钟切回来确认一次。
 *
 * 版式上它和别的页不一样：整页就是一张「状态看板」。
 * · 手机是一条流：总览 → 轮到你了 → 队列；
 * · 平板是左右两栏：左边是「这一次在做什么」（总览 + 待办 + 参数），
 *   右边是「队列里每一份到哪了」。分栏的依据是这两块**都要常驻可见** ——
 *   「轮到你了」的提示被队列顶出屏幕，用户就会以为程序卡住了。
 */
@Composable
fun BuildScreen(
    state: BuildUiState,
    onBack: () -> Unit,
    onCancelAll: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    onCopy: () -> Unit,
) {
    val colors = HzTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            BuildTopBar(
                wide = state.wide,
                title = stringResource(R.string.bp_title),
                actionLabel = stringResource(
                    if (state.wide) R.string.bp_cancel_all else R.string.cancel
                ),
                showAction = state.phase == BuildPhase.Running,
                onBack = onBack,
                onAction = onCancelAll,
            )

            if (state.wide) {
                WideBody(state, onSkip, onCopy)
            } else {
                PhoneBody(state, onSkip, onCopy)
            }
        }

        BuildBottomBar(
            state = state,
            onCancelAll = onCancelAll,
            onFinish = onFinish,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(
                    start = HzTheme.dimen.buildBarInset,
                    end = HzTheme.dimen.buildBarInset,
                    bottom = 12.dp,
                ),
        )
    }
}

// ------------------------------------------------------------------ 两种排布

@Composable
private fun PhoneBody(state: BuildUiState, onSkip: () -> Unit, onCopy: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 底部留出操作条的位置：它是浮在列表上的，不给够高度最后一行会被压在下面
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 100.dp),
    ) {
        item(key = "hero") {
            HeroCard(state)
            Spacer(Modifier.height(12.dp))
        }
        // 纯构建期间没有「轮到你」这件事，这张卡就整张不出现 ——
        // 留一个空壳占着页面正中，用户会以为它加载失败了
        if (state.phase != BuildPhase.Running || state.waitingIndex > 0) {
            item(key = "action") {
                ActionCard(state, wide = false, onSkip = onSkip, onCopy = onCopy)
                Spacer(Modifier.height(4.dp))
            }
        }
        item(key = "queue-head") {
            QueueHeader(state, wide = false)
        }
        items(state.items, key = { it.index }) { item ->
            QueueRow(item, wide = false)
            Spacer(Modifier.height(HzTheme.dimen.buildRowGap))
        }
    }
}

@Composable
private fun WideBody(state: BuildUiState, onSkip: () -> Unit, onCopy: () -> Unit) {
    val d = HzTheme.dimen
    Row(Modifier.fillMaxSize()) {
        // 左栏 410dp : 右栏 550dp（稿面实测），用比例而不是固定值 ——
        // 窗口再宽一点时，两边一起变宽比「右边越来越长、左边不动」更像一页
        Column(
            Modifier
                .weight(0.427f)
                .fillMaxHeight(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = d.contentPadding, top = 4.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "hero") { HeroCard(state) }
                if (state.phase != BuildPhase.Running || state.waitingIndex > 0) {
                    item(key = "action") {
                        ActionCard(state, wide = true, onSkip = onSkip, onCopy = onCopy)
                    }
                }
                if (state.detail.isNotEmpty()) {
                    item(key = "detail") { DetailCard(state) }
                }
            }
        }

        Spacer(Modifier.width(16.dp))

        Column(
            Modifier
                .weight(0.573f)
                .fillMaxHeight(),
        ) {
            QueueHeader(state, wide = true)
            LazyColumn(
                contentPadding = PaddingValues(end = d.contentPadding, bottom = 100.dp),
            ) {
                items(state.items, key = { it.index }) { item ->
                    QueueRow(item, wide = true)
                    Spacer(Modifier.height(d.buildRowGapWide))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 顶栏

@Composable
private fun BuildTopBar(
    wide: Boolean,
    title: String,
    actionLabel: String,
    showAction: Boolean,
    onBack: () -> Unit,
    onAction: () -> Unit,
) {
    val colors = HzTheme.colors
    val side = if (wide) HzTheme.dimen.contentPadding else HzTheme.dimen.pagePadding
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = side),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickableBouncy(onClick = onBack)
                .semantics { contentDescription = "返回" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackArrow(color = colors.accent)
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.bp_back),
                color = colors.accent,
                style = HzTheme.type.subtitle,
                maxLines = 1,
            )
        }
        Text(
            text = title,
            color = colors.textPrimary,
            style = HzTheme.type.cardTitle,
            maxLines = 1,
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (showAction) {
                Text(
                    text = actionLabel,
                    color = colors.red,
                    style = HzTheme.type.subtitle,
                    maxLines = 1,
                    modifier = Modifier
                        .clickableBouncy(onClick = onAction)
                        .semantics { contentDescription = actionLabel },
                )
            }
        }
    }
}

@Composable
private fun BackArrow(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.62f, h * 0.10f)
            lineTo(w * 0.24f, h * 0.50f)
            lineTo(w * 0.62f, h * 0.90f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = w * 0.18f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

// ------------------------------------------------------------------ 总览卡

@Composable
private fun HeroCard(state: BuildUiState) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen
    val wide = state.wide
    val pad = if (wide) 20.dp else 14.dp

    Column(
        Modifier
            .fillMaxWidth()
            .drawCard(colors.surface, colors.isDark, d.cardCorner)
            .padding(pad),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.heroTitle,
                    color = colors.textPrimary,
                    style = HzTheme.type.metric.copy(
                        fontSize = 20.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(if (wide) 6.dp else 4.dp))
                Text(
                    text = state.heroSub,
                    color = colors.textSecondary,
                    style = HzTheme.type.subtitle.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${state.heroPercent}%",
                color = colors.accent,
                style = HzTheme.type.metric.copy(fontSize = 26.sp, lineHeight = 30.sp),
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(if (wide) 16.dp else 10.dp))
        ProgressTrack(
            percent = state.heroPercent,
            height = 8.dp,
            track = colors.fill,
            fill = colors.accent,
        )
        if (state.heroNote.isNotEmpty()) {
            Spacer(Modifier.height(if (wide) 14.dp else 8.dp))
            Text(
                text = state.heroNote,
                color = colors.textTertiary,
                style = HzTheme.type.cardMeta.copy(fontSize = 12.sp, lineHeight = 16.sp),
            )
        }
    }
}

/**
 * 进度条。
 *
 * 百分比走的是 `animateFloatAsState` 而不是直接跳：构建时每 100ms 上报一次，
 * 直接跳的话圆头会以 8% 的台阶一格一格蹦，看着像下载卡顿。
 * 420ms 的时长比上报间隔长，两段动画会自然接上，视觉上是一条连续的推进。
 */
@Composable
private fun ProgressTrack(
    percent: Int,
    height: Dp,
    track: Color,
    fill: Color,
    modifier: Modifier = Modifier,
) {
    val target = percent.coerceIn(0, 100) / 100f
    val fraction by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 420),
        label = "buildProgress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(track),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceAtLeast(0.02f))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(fill),
            )
        }
    }
}

// ------------------------------------------------------------------ 待办卡

/**
 * 「轮到你了」/「完成了」/「有失败」三种状态共用一张卡。
 *
 * 它们在同一位置、同一时刻只会出现一个 —— 用户的眼睛每次只需要看这一张卡就知道
 * 现在该做什么。做成三个独立组件的话，切换时会出现「旧卡消失、新卡从下面顶上来」
 * 的跳动，而这一页最忌讳的就是动（会让人以为进度变了）。
 */
@Composable
private fun ActionCard(
    state: BuildUiState,
    wide: Boolean,
    onSkip: () -> Unit,
    onCopy: () -> Unit,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen
    val pad = if (wide) 20.dp else 16.dp
    val waiting = state.phase == BuildPhase.Running && state.waitingIndex > 0
    val failed = state.phase == BuildPhase.Failed
    val skipLabel = stringResource(R.string.bp_act_skip)
    val copyLabel = stringResource(R.string.bp_copy_diag)

    val fill = when {
        waiting -> colors.warnFill
        failed -> colors.warnFill
        else -> if (colors.isDark) colors.surfaceRaised else colors.surface
    }
    val stroke = when {
        waiting -> colors.warnStroke
        failed -> colors.warnStroke
        else -> null
    }

    Column(
        Modifier
            .fillMaxWidth()
            .drawCard(fill, colors.isDark, d.cardCorner, stroke = stroke)
            .padding(pad),
    ) {
        if (waiting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.warnAccent),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.actionHead,
                    color = colors.warnText,
                    style = HzTheme.type.sectionTitle.copy(
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        Text(
            text = if (waiting) stringResource(R.string.bp_act_title) else state.doneTitle,
            color = if (failed) colors.warnText else colors.textPrimary,
            style = HzTheme.type.cardTitle.copy(
                fontSize = if (wide) 18.sp else 17.sp,
                lineHeight = 24.sp,
            ),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (waiting) state.actionBody else state.doneBody,
            color = if (waiting || failed) colors.warnBody else colors.textSecondary,
            style = HzTheme.type.cardMeta.copy(
                fontSize = if (wide) 14.sp else 13.sp,
                lineHeight = if (wide) 20.sp else 19.sp,
            ),
        )

        if (waiting || failed) {
            Spacer(Modifier.height(if (wide) 14.dp else 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.waitingName,
                    color = colors.warnBody,
                    style = HzTheme.type.cardMeta.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                // 失败时这个位置放「复制诊断」：与「跳过这一份」同一个语义位 ——
                // 都是「这一张卡上唯一能立刻做的一件事」。
                // 原因写在卡里，完整日志得复制出去才看得全。
                val label = if (waiting) skipLabel else copyLabel
                Text(
                    text = label,
                    color = colors.warnText,
                    style = HzTheme.type.status.copy(fontSize = 14.sp),
                    modifier = Modifier
                        .clickableBouncy(onClick = if (waiting) onSkip else onCopy)
                        .padding(vertical = 4.dp)
                        .semantics { contentDescription = label },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 参数卡（平板）

@Composable
private fun DetailCard(state: BuildUiState) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen
    Column(
        Modifier
            .fillMaxWidth()
            .drawCard(colors.surface, colors.isDark, d.cardCorner)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        state.detail.forEach { (label, value) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    color = colors.textSecondary,
                    style = HzTheme.type.cardMeta.copy(fontSize = 14.sp),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = value,
                    color = colors.textPrimary,
                    style = HzTheme.type.cardMeta.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 队列

@Composable
private fun QueueHeader(state: BuildUiState, wide: Boolean) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                end = if (wide) d.contentPadding else 0.dp,
                top = if (wide) 6.dp else 16.dp,
                bottom = if (wide) 8.dp else 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.queueTitle,
            color = colors.textPrimary,
            style = HzTheme.type.sectionTitle.copy(fontSize = 14.sp, lineHeight = 18.sp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = state.queueNote,
            color = colors.textTertiary,
            style = HzTheme.type.cardMeta.copy(fontSize = 12.sp, lineHeight = 16.sp),
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QueueRow(item: BuildItemUi, wide: Boolean) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen
    val current = item.state == BuildRowState.Waiting
    val building = item.state == BuildRowState.Building

    val fill = if (current) colors.warnFill else colors.surface
    val stroke = if (current) colors.warnStroke else null
    val nameColor = when {
        current -> colors.textPrimary
        // 正在构建的那一行，名字用蓝色：这一行是「唯一的活口」，
        // 七行里只有它在动，不给个颜色标记就很难在扫一眼时定位到
        building -> colors.accent
        item.state == BuildRowState.Cancelled -> colors.textTertiary
        else -> colors.textPrimary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (wide) d.buildRowHeightWide else d.buildRowHeight)
            .drawCard(fill, colors.isDark, d.pickerCorner, stroke = stroke)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QueueBadge(
            index = item.index,
            state = item.state,
            size = if (wide) d.buildBadgeWide else d.buildBadge,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.name,
                color = nameColor,
                style = HzTheme.type.cardTitle.copy(
                    fontSize = 15.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(if (building) 5.dp else 2.dp))
            if (building) {
                // 这一行的第二栏放它自己的进度条，而不是包名：
                // 「构建 62%」在右边已经说了，下面再写包名帮不上忙，
                // 一条会走的细条反而能证明「它真的在动」
                ProgressTrack(
                    percent = item.percent,
                    height = 3.dp,
                    track = colors.fill,
                    fill = colors.accent,
                    modifier = Modifier.fillMaxWidth(0.62f),
                )
            } else {
                Text(
                    text = item.meta,
                    color = colors.textTertiary,
                    style = HzTheme.type.cardMeta.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = stateLabel(item),
            color = when (item.state) {
                BuildRowState.Installed -> colors.green
                BuildRowState.Waiting -> colors.warnText
                BuildRowState.Building -> colors.accent
                BuildRowState.Failed -> colors.red
                else -> colors.textTertiary
            },
            style = HzTheme.type.status.copy(fontSize = 13.sp),
            maxLines = 1,
        )
    }
}

@Composable
private fun stateLabel(item: BuildItemUi): String = when (item.state) {
    BuildRowState.Installed -> if (item.elapsed.isNullOrEmpty()) {
        stringResource(R.string.bp_state_installed)
    } else {
        stringResource(R.string.bp_state_installed_time, item.elapsed)
    }
    BuildRowState.Waiting -> stringResource(R.string.bp_state_waiting)
    BuildRowState.Building -> stringResource(R.string.bp_state_building, item.percent)
    BuildRowState.Ready -> stringResource(R.string.bp_state_ready)
    BuildRowState.Queued -> stringResource(R.string.bp_state_queued)
    BuildRowState.Failed -> stringResource(R.string.bp_state_failed)
    BuildRowState.Skipped -> stringResource(R.string.bp_state_skipped)
    BuildRowState.Cancelled -> stringResource(R.string.bp_state_cancelled)
}

@Composable
private fun QueueBadge(index: Int, state: BuildRowState, size: Dp) {
    val colors = HzTheme.colors
    val bg = when (state) {
        BuildRowState.Installed -> colors.badgeDoneBg
        BuildRowState.Waiting -> colors.warnAccent
        BuildRowState.Building -> colors.badgeBusyBg
        BuildRowState.Failed -> colors.badgeRedBg
        else -> colors.badgeNeutralBg
    }
    val fg = when (state) {
        BuildRowState.Waiting -> Color.White
        BuildRowState.Building -> colors.accent
        BuildRowState.Failed -> colors.red
        BuildRowState.Cancelled -> colors.textTertiary
        else -> colors.textSecondary
    }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        if (state == BuildRowState.Installed) {
            Canvas(Modifier.size(size * 0.52f)) {
                val w = this.size.width
                val h = this.size.height
                val path = Path().apply {
                    moveTo(w * 0.10f, h * 0.52f)
                    lineTo(w * 0.40f, h * 0.80f)
                    lineTo(w * 0.92f, h * 0.20f)
                }
                drawPath(
                    path = path,
                    color = HzIconTint.green,
                    style = Stroke(
                        width = w * 0.16f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        } else {
            Text(
                text = "$index",
                color = fg,
                style = HzTheme.type.badge.copy(
                    fontSize = 13.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
            )
        }
    }
}

// ------------------------------------------------------------------ 底部操作条

@Composable
private fun BuildBottomBar(
    state: BuildUiState,
    onCancelAll: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen
    val running = state.phase == BuildPhase.Running

    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(if (state.wide) 76.dp else d.buildBarHeight),
        shape = RoundedCornerShape(if (state.wide) 20.dp else d.buildBarHeight / 2),
        fill = colors.glassFill,
        shadowElevation = 12.dp,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.footerTitle,
                    color = colors.textPrimary,
                    style = HzTheme.type.status.copy(fontSize = 14.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = state.footerNote,
                    color = colors.textTertiary,
                    style = HzTheme.type.cardMeta.copy(fontSize = 12.sp, lineHeight = 15.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            // 平板上这颗按钮放在中段、注释在最右：稿面如此。
            // 手机上它必须贴右下角 —— 拇指够得到的地方
            Box(
                Modifier
                    .width(d.buildPillWidth)
                    .height(if (state.wide) 44.dp else d.buildPillHeight)
                    .clip(CircleShape)
                    .background(if (running) colors.red else colors.accent)
                    .clickableBouncy(onClick = if (running) onCancelAll else onFinish),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.pillLabel,
                    color = Color.White,
                    style = HzTheme.type.button.copy(fontSize = if (state.wide) 15.sp else 16.sp),
                    maxLines = 1,
                )
            }
            if (state.wide) {
                Spacer(Modifier.width(16.dp))
                Text(
                    text = state.footnote,
                    color = colors.textTertiary,
                    style = HzTheme.type.cardMeta.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 卡片底

/**
 * 卡片底：填充 + 可选描边。
 *
 * 深色下的白色 0.06% 描边是「卡片比背景亮一档」之外的补充 ——
 * 琥珀卡、当前行这类**有颜色**的卡片如果只靠亮度差，在深色里会糊成一块，
 * 加一圈极淡的白边能勾出边界而不显得突兀。
 */
private fun Modifier.drawCard(
    fill: Color,
    isDark: Boolean,
    corner: Dp,
    stroke: Color? = null,
): Modifier = drawBehind {
    val radius = CornerRadius(corner.toPx(), corner.toPx())
    drawRoundRect(color = fill, cornerRadius = radius)
    when {
        stroke != null -> drawRoundRect(
            color = stroke,
            cornerRadius = radius,
            style = Stroke(width = 1.dp.toPx()),
        )
        isDark -> drawRoundRect(
            color = Color.White.copy(alpha = 0.06f),
            cornerRadius = radius,
            style = Stroke(width = 0.75.dp.toPx()),
        )
    }
}

// ------------------------------------------------------------------ 状态

/** 队列行在界面上的状态。与 `BatchRunner.ItemState` 一一对应，故意不复用：这个要给 Compose 看 */
enum class BuildRowState { Queued, Building, Ready, Waiting, Installed, Failed, Skipped, Cancelled }

/** 整页的阶段：还在跑 / 全部完成 / 有失败 / 被取消 */
enum class BuildPhase { Running, Done, Failed, Stopped }

@Immutable
data class BuildItemUi(
    val index: Int,
    val name: String,
    val meta: String,
    val state: BuildRowState,
    val percent: Int = 0,
    /** 平板上的「已安装 · 21 秒」用；手机不显示耗时，位上留给包名 */
    val elapsed: String? = null,
)

@Immutable
data class BuildUiState(
    val wide: Boolean = false,
    val phase: BuildPhase = BuildPhase.Running,
    val heroTitle: String = "",
    val heroPercent: Int = 0,
    val heroSub: String = "",
    val heroNote: String = "",
    val waitingIndex: Int = -1,
    val actionHead: String = "",
    val actionBody: String = "",
    val waitingName: String = "",
    val queueTitle: String = "",
    val queueNote: String = "",
    val items: List<BuildItemUi> = emptyList(),
    /** 平板左栏的参数卡：源应用 / 创建份数 / 预估占用 / 副本包名 */
    val detail: List<Pair<String, String>> = emptyList(),
    val footerTitle: String = "",
    val footerNote: String = "",
    val pillLabel: String = "",
    val footnote: String = "",
    val doneTitle: String = "",
    val doneBody: String = "",
)
