package com.huanzhi.manager.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.huanzhi.manager.R
import com.huanzhi.manager.ui.components.AppIconTile
import com.huanzhi.manager.ui.glass.GlassSurface
import com.huanzhi.manager.ui.glass.clickableBouncy
import com.huanzhi.manager.ui.theme.HzTheme

/**
 * 选应用页。
 *
 * 与主页/设置页不同，这一页有两个形态**不是同一套排版缩一缩**：
 * 手机上它是一条流（搜索 → 筛选 → 列表 → 底部总结条），
 * 平板上它是「左选右配」——右侧留出 276dp 专放已选清单和每份数量。
 * 设计稿给平板单独画了一版，说明这不是自适应能糊过去的事：
 * 在 1024dp 的窗口里，勾选之后还要滑回列表顶部才能改份数，那一步就不该存在。
 *
 * 【两种点法】
 * · 平时点一行 = 直接给这个应用建一个分身（从 1.0 起就是这条路，用户已经熟）；
 * · 点「多选」进入勾选态，点一行 = 选中/取消，底部浮出「已选 N 个应用 · 下一步」。
 * 平板上没有「平时」这一说：右侧面板要求左边必须先有选中项，所以平板一进来就是勾选态。
 */
@Composable
fun AppPickerScreen(
    state: AppPickerUiState,
    onBack: () -> Unit,
    onToggleSelectionMode: () -> Unit,
    onCancelOrClear: () -> Unit,
    onQuery: (String) -> Unit,
    onFilter: (PickerFilter) -> Unit,
    onRowClick: (PickerAppUi) -> Unit,
    onCopies: (String, Int) -> Unit,
    onNext: () -> Unit,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen
    val listState = rememberLazyListState()
    val gridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            PickerTopBar(
                wide = state.wide,
                title = stringResource(R.string.pk_title),
                actionLabel = when {
                    state.wide -> stringResource(R.string.cancel)
                    state.selectionMode -> stringResource(R.string.done)
                    else -> stringResource(R.string.multi_select)
                },
                onBack = onBack,
                onAction = {
                    if (state.wide) onCancelOrClear() else onToggleSelectionMode()
                },
            )

            Row(Modifier.weight(1f)) {
                // 左栏：搜索 + 筛选 + 列表。右栏是独立的一列，不跟着列表滚动
                Column(Modifier.weight(1f)) {
                    PickerSearch(
                        query = state.query,
                        onQuery = onQuery,
                        modifier = Modifier.padding(
                            start = if (state.wide) d.contentPadding else d.pagePadding,
                            end = if (state.wide) d.contentPadding else d.pagePadding,
                            top = 4.dp,
                        ),
                    )
                    PickerSegments(
                        selected = state.filter,
                        onSelect = onFilter,
                        modifier = Modifier.padding(
                            start = if (state.wide) d.contentPadding else d.pagePadding,
                            end = if (state.wide) d.contentPadding else d.pagePadding,
                            top = 12.dp,
                            bottom = 4.dp,
                        ),
                    )

                    Box(Modifier.weight(1f)) {
                        when {
                            state.loading -> CenterHint(stringResource(R.string.pk_scanning))
                            state.sections.isEmpty() && state.query.isNotBlank() ->
                                CenterHint(stringResource(R.string.search_empty))
                            state.sections.isEmpty() ->
                                // 空状态要说清「为什么空」：切到「已分身」却一个都没有，
                                // 和「这台设备上没有能分身的三方应用」是两件事
                                CenterHint(
                                    stringResource(
                                        when (state.filter) {
                                            PickerFilter.Cloned -> R.string.pk_empty_cloned
                                            PickerFilter.Unsupported -> R.string.pk_empty_unsupported
                                            PickerFilter.All -> R.string.pk_no_apps
                                        }
                                    )
                                )
                            else -> {
                                val pad = if (state.wide) d.contentPadding else d.pagePadding
                                // 底部总结条浮在列表上，最后一项要躲开它
                                val bottom = if (state.selectionMode && !state.wide) 96.dp else 24.dp
                                if (state.wide) {
                                    // 平板两列瀑布流：每一节的高度随应用数量变（有的字母下 1 个、
                                    // 有的 3 个），等高的网格会在下面留出大片空洞
                                    LazyVerticalStaggeredGrid(
                                        columns = StaggeredGridCells.Fixed(2),
                                        state = gridState,
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                            start = pad, end = pad, top = 8.dp, bottom = bottom,
                                        ),
                                        verticalItemSpacing = 10.dp,
                                        horizontalArrangement = Arrangement.spacedBy(d.gridGap),
                                    ) {
                                        items(state.sections, key = { it.letter }) { section ->
                                            PickerSectionView(
                                                section = section,
                                                wide = true,
                                                selectionMode = state.selectionMode,
                                                selected = state.selected,
                                                onRowClick = onRowClick,
                                            )
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        state = listState,
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                            start = pad, end = pad, top = 8.dp, bottom = bottom,
                                        ),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        items(state.sections, key = { it.letter }) { section ->
                                            PickerSectionView(
                                                section = section,
                                                wide = false,
                                                selectionMode = state.selectionMode,
                                                selected = state.selected,
                                                onRowClick = onRowClick,
                                            )
                                        }
                                    }
                                }

                                if (!state.wide && state.query.isBlank()) {
                                    PickerIndexBar(
                                        letters = state.sections.map { it.letter },
                                        onJump = { i ->
                                            // 索引条是「跳到那个字母」，不是精确滚动；
                                            // 列表里一节就是一个 item，所以 index 直接对得上
                                            scope.launch { listState.scrollToItem(i) }
                                        },
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .padding(end = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.wide) {
                    Box(
                        Modifier
                            .padding(start = 4.dp)
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(colors.divider)
                    )
                    SelectedPanel(
                        state = state,
                        onCopies = onCopies,
                        onNext = onNext,
                    )
                }
            }
        }

        if (!state.wide && state.selectionMode) {
            PickerBottomBar(
                count = state.selected.size,
                onNext = onNext,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = d.pagePadding, end = d.pagePadding, bottom = 12.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ 顶栏

/**
 * 顶栏：左「返回」、中标题、右动作。
 *
 * 三格等宽而不是「中间居中对齐」：右侧文案在「多选 / 完成 / 取消」之间切换，
 * 宽度不一样，用图标居中对齐的话标题会跟着左右跳。
 */
@Composable
private fun PickerTopBar(
    wide: Boolean,
    title: String,
    actionLabel: String,
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
            BackChevron(color = colors.accent)
            Spacer(Modifier.width(2.dp))
            Text(
                text = stringResource(R.string.pk_back),
                color = colors.accent,
                style = HzTheme.type.cardTitle,
            )
        }
        Text(
            text = title,
            color = colors.textPrimary,
            style = HzTheme.type.cardTitle,
            maxLines = 1,
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = actionLabel,
                color = colors.accent,
                style = HzTheme.type.cardTitle,
                modifier = Modifier
                    .clickableBouncy(onClick = onAction)
                    .padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun BackChevron(color: Color) {
    Canvas(Modifier.size(11.dp)) {
        val p = Path().apply {
            moveTo(size.width * 0.68f, size.height * 0.12f)
            lineTo(size.width * 0.28f, size.height * 0.50f)
            lineTo(size.width * 0.68f, size.height * 0.88f)
        }
        drawPath(
            path = p,
            color = color,
            style = Stroke(
                width = size.minDimension * 0.16f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

// ------------------------------------------------------------------ 搜索

@Composable
private fun PickerSearch(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen
    var text by remember { mutableStateOf(query) }

    // 外部清空（切筛选、返回）时要同步到输入框
    LaunchedEffect(query) { if (query != text) text = query }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(d.searchHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.fill)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    onQuery(it)
                },
                singleLine = true,
                textStyle = HzTheme.type.subtitle.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Search
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "搜索应用" },
            )
            if (text.isEmpty()) {
                Text(
                    text = stringResource(R.string.pk_search_hint),
                    color = colors.textSecondary,
                    style = HzTheme.type.subtitle,
                    maxLines = 1,
                )
            }
        }
        if (text.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clickableBouncy(onClick = {
                        text = ""
                        onQuery("")
                    })
                    .clearAndSetSemantics { contentDescription = "清除" },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(16.dp)) {
                    drawCircle(color = Color(0xFFC7C7CC), radius = size.minDimension / 2)
                    val inset = size.width * 0.32f
                    drawLine(
                        color = Color.White,
                        start = Offset(inset, inset),
                        end = Offset(size.width - inset, size.height - inset),
                        strokeWidth = 1.6.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(size.width - inset, inset),
                        end = Offset(inset, size.height - inset),
                        strokeWidth = 1.6.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 分段

@Composable
private fun PickerSegments(
    selected: PickerFilter,
    onSelect: (PickerFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(d.segmentHeight)
            .clip(RoundedCornerShape(9.dp))
            .background(colors.fill)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PickerFilter.entries.forEach { filter ->
            val sel = filter == selected
            val label = stringResource(filter.labelRes)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (sel) colors.controlSelected else Color.Transparent)
                    .clickableBouncy(onClick = { onSelect(filter) })
                    .semantics {
                        this.selected = sel
                        contentDescription = label
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (sel) colors.textPrimary else colors.textSecondary,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 16.sp),
                    maxLines = 1,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 列表

@Composable
private fun PickerSectionView(
    section: PickerSectionUi,
    wide: Boolean,
    selectionMode: Boolean,
    selected: Map<String, Int>,
    onRowClick: (PickerAppUi) -> Unit,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen
    Column {
        Text(
            text = section.letter.toString(),
            color = colors.textSecondary,
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .drawCard(colors.surface, colors.isDark, d.pickerCorner)
                .clip(RoundedCornerShape(d.pickerCorner))
        ) {
            section.apps.forEachIndexed { i, app ->
                if (i > 0) {
                    // 分隔线从文字起点开始，不从卡片边缘 —— 与设置页保持同一条规矩
                    Box(
                        Modifier
                            .padding(start = if (wide) 12.dp + 44.dp + 12.dp else 10.dp + 40.dp + 10.dp)
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(colors.divider)
                    )
                }
                PickerRow(
                    app = app,
                    wide = wide,
                    selectionMode = selectionMode,
                    checked = selected.containsKey(app.pkg),
                    onClick = { onRowClick(app) },
                )
            }
        }
    }
}

@Composable
private fun PickerRow(
    app: PickerAppUi,
    wide: Boolean,
    selectionMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen
    val dim = !app.supported

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (wide) d.pickerRowHeightWide else d.pickerRowHeight)
            .clickableBouncy(enabled = app.supported, onClick = onClick)
            .padding(start = if (wide) 12.dp else 10.dp, end = if (wide) 12.dp else 10.dp)
            .semantics(mergeDescendants = true) {
                this.selected = checked
                contentDescription = buildString {
                    append(app.label)
                    append('，')
                    append(app.meta)
                    if (app.hardened) append("，带加固壳")
                    if (!app.supported) append("，不支持分片")
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconTile(
            pkg = app.pkg,
            size = if (wide) d.pickerIconWide else d.pickerIcon,
            corner = if (wide) 11.dp else 10.dp,
            fallbackLabel = app.label,
            modifier = if (dim) Modifier.alpha(0.4f) else Modifier,
        )
        Spacer(Modifier.width(if (wide) 12.dp else 10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = app.label,
                color = if (dim) colors.textSecondary else colors.textPrimary,
                style = HzTheme.type.cardTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            // 包名与「加固壳」分开排：包名 weight(1f, fill = false) 先让出标签的位置，
            // 自己再按剩余宽度截。拼成一条字符串的话，先被截掉的会是末尾的加固壳
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (dim) Modifier.alpha(0.7f) else Modifier,
            ) {
                Text(
                    text = app.meta,
                    color = colors.textSecondary,
                    style = HzTheme.type.cardMeta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (app.hardened) {
                    Text(
                        text = " · " + stringResource(R.string.pk_hardening_tag),
                        color = colors.textSecondary,
                        style = HzTheme.type.cardMeta,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))

        if (dim) {
            // 不支持的项不给勾选框，改放一句原因：给一个能点却点不动的圆，
            // 用户会以为是自己没点准
            Text(
                text = stringResource(R.string.pk_unsupported_split),
                color = colors.textTertiary,
                style = HzTheme.type.cardMeta,
                maxLines = 1,
            )
        } else if (selectionMode) {
            SelectCircle(checked = checked)
        } else {
            // 单选模式（手机默认）也要有个"能点进去"的指引，用细箭头而不是圆点 ——
            // 圆点是"可以勾"，箭头是"点开看看"，两种语法不能混用
            ChevronIndicator(color = colors.textTertiary)
        }
    }
}

/** 勾选圆：22dp，选中后填充强调蓝 + 白色对勾 */
@Composable
private fun SelectCircle(checked: Boolean) {
    val colors = HzTheme.colors
    Canvas(Modifier.size(22.dp)) {
        val r = size.minDimension / 2f
        if (!checked) {
            drawCircle(
                color = colors.textTertiary,
                radius = r - 0.75.dp.toPx(),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        } else {
            drawCircle(color = colors.accent, radius = r)
            val p = Path().apply {
                moveTo(size.width * 0.27f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.68f)
                lineTo(size.width * 0.74f, size.height * 0.34f)
            }
            drawPath(
                path = p,
                color = Color.White,
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

@Composable
private fun ChevronIndicator(color: Color) {
    Canvas(Modifier.size(13.dp)) {
        val p = Path().apply {
            moveTo(size.width * 0.36f, size.height * 0.22f)
            lineTo(size.width * 0.68f, size.height * 0.50f)
            lineTo(size.width * 0.36f, size.height * 0.78f)
        }
        drawPath(
            path = p,
            color = color,
            style = Stroke(
                width = size.minDimension * 0.13f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

// ------------------------------------------------------------------ 索引条

/**
 * 首字母索引条。
 *
 * 拖动的判定用「手指落在条上的相对高度」而不是逐个字母的命中区：
 * 字母只有 10sp，命中区撑到 14dp 也还是难按，而按比例换算能让整条可拖、
 * 手指划到哪就跳到哪 —— 这是 iOS 通讯录的做法，也是用户的手指已经习惯的东西。
 */
@Composable
private fun PickerIndexBar(
    letters: List<Char>,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (letters.isEmpty()) return
    val colors = HzTheme.colors
    val rowHeight = 14.dp
    val total = rowHeight * letters.size
    val density = LocalDensity.current

    Column(
        modifier = modifier
            .width(22.dp)
            .height(total)
            .pointerInput(letters) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        val h = with(density) { total.toPx() }
                        onJump(((offset.y / h) * letters.size).toInt().coerceIn(0, letters.lastIndex))
                    },
                    onVerticalDrag = { change, _ ->
                        val h = with(density) { total.toPx() }
                        onJump(((change.position.y / h) * letters.size).toInt().coerceIn(0, letters.lastIndex))
                    },
                )
            }
            .pointerInput(letters) {
                detectTapGestures { offset ->
                    val h = with(density) { total.toPx() }
                    onJump(((offset.y / h) * letters.size).toInt().coerceIn(0, letters.lastIndex))
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        letters.forEach { letter ->
            Box(Modifier.height(rowHeight), contentAlignment = Alignment.Center) {
                Text(
                    text = letter.toString(),
                    // 灰色而不是强调蓝：索引条是一条"工具栏"，蓝色在这里它会和
                    // 顶栏的两个操作抢注意力，而它平时并不需要被注意到
                    color = colors.textSecondary,
                    style = TextStyle(fontSize = 10.sp, lineHeight = 10.sp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 底部条

/**
 * 底部总结条（仅手机勾选态）。
 *
 * 用玻璃浮层而不是把按钮钉在屏幕底部：它出现的时机是「刚勾了几个」，
 * 浮起来是这件事的视觉回应；钉死的底栏会让人以为它一直在那儿。
 */
@Composable
private fun PickerBottomBar(
    count: Int,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(d.pickerBarHeight),
        shape = RoundedCornerShape(d.pickerBarHeight / 2),
        fill = colors.glassFill,
        shadowElevation = 12.dp,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 20.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.selected_apps, count),
                color = colors.textPrimary,
                style = HzTheme.type.subtitle,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            PrimaryPill(
                label = stringResource(R.string.pk_next),
                enabled = count > 0,
                onClick = onNext,
                width = d.pickerActionWidth,
                height = d.pickerActionHeight,
            )
        }
    }
}

// ------------------------------------------------------------------ 平板右栏

@Composable
private fun SelectedPanel(
    state: AppPickerUiState,
    onCopies: (String, Int) -> Unit,
    onNext: () -> Unit,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen
    val chosen = state.selectedApps
    val totalCopies = state.selectedApps.sumOf { state.selected[it.pkg] ?: 1 }

    Column(
        Modifier
            .width(d.pickerPanelWidth)
            .fillMaxHeight()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.pk_selected_header),
                color = colors.textPrimary,
                style = HzTheme.type.sectionTitle,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.pk_selected_count, chosen.size, totalCopies),
                color = colors.textSecondary,
                style = HzTheme.type.cardMeta,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(12.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .drawCard(colors.surface, colors.isDark, d.pickerCorner)
                .clip(RoundedCornerShape(d.pickerCorner))
        ) {
            if (chosen.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(88.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.pk_panel_empty),
                        color = colors.textSecondary,
                        style = HzTheme.type.cardMeta,
                    )
                }
            }
            chosen.forEachIndexed { i, app ->
                if (i > 0) {
                    Box(
                        Modifier
                            .padding(start = 8.dp + 40.dp + 10.dp)
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(colors.divider)
                    )
                }
                SelectedRow(
                    app = app,
                    copies = state.selected[app.pkg] ?: 1,
                    onCopies = { onCopies(app.pkg, it) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.pk_panel_hint),
            color = colors.textSecondary,
            style = HzTheme.type.cardMeta,
        )

        Spacer(Modifier.weight(1f))
        PrimaryPill(
            label = if (totalCopies > 0) {
                stringResource(R.string.pk_next_total, totalCopies)
            } else {
                stringResource(R.string.pk_next)
            },
            enabled = chosen.isNotEmpty(),
            onClick = onNext,
            width = Dp.Unspecified,
            height = 52.dp,
            fillWidth = true,
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SelectedRow(
    app: PickerAppUi,
    copies: Int,
    onCopies: (Int) -> Unit,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen
    Row(
        Modifier
            .fillMaxWidth()
            .height(88.dp)
            .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconTile(
            pkg = app.pkg,
            size = d.pickerIcon,
            corner = 10.dp,
            fallbackLabel = app.label,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = app.label,
                color = colors.textPrimary,
                style = HzTheme.type.cardTitle,
                // 右栏卡片内宽只有 236dp，扣掉图标、间距、步进器后留给名字约 114dp。
                // 「HuanZhi 测试目标」这类中英混排会超，折成两行比截断强 ——
                // 这一栏的唯一职责是让人确认"我勾了哪几个"，名字截掉就失去意义了。
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.pk_per_copy),
                color = colors.textSecondary,
                style = HzTheme.type.cardMeta,
                maxLines = 1,
            )
        }
        Stepper(
            value = copies,
            onChange = onCopies,
        )
    }
}

/**
 * 份数步进器：− 数值 +，56×28dp（稿面实测 58×22，高度因触摸目标抬到 28）。
 *
 * 上限 5 与批量创建的 MAX_BATCH_COPIES 对齐：再多，系统安装确认框会点到手酸，
 * 而且每一份都是整包复制，五个大应用就能把剩余空间吃光。
 */
@Composable
private fun Stepper(value: Int, onChange: (Int) -> Unit) {
    val colors = HzTheme.colors
    Row(
        Modifier
            .width(56.dp)
            .height(28.dp)
            .clip(CircleShape)
            .background(colors.fill),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton(
            sign = "−",
            enabled = value > 1,
            color = colors.textPrimary,
            onClick = { onChange((value - 1).coerceAtLeast(1)) },
        )
        Text(
            text = value.toString(),
            color = colors.textPrimary,
            style = TextStyle(fontSize = 15.sp, lineHeight = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1,
        )
        StepperButton(
            sign = "+",
            enabled = value < 5,
            color = colors.accent,
            onClick = { onChange((value + 1).coerceAtMost(5)) },
        )
    }
}

@Composable
private fun StepperButton(
    sign: String,
    enabled: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(22.dp)
            .fillMaxHeight()
            .clickableBouncy(enabled = enabled, onClick = onClick)
            .clearAndSetSemantics { contentDescription = sign },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = sign,
            color = if (enabled) color else HzTheme.colors.textTertiary,
            style = TextStyle(fontSize = 16.sp, lineHeight = 18.sp),
        )
    }
}

// ------------------------------------------------------------------ 共用

/** 主行动按钮：蓝色胶囊。禁用态用一档淡蓝而不是灰色，它还是"下一步"，只是还不能点 */
@Composable
private fun PrimaryPill(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    width: Dp,
    height: Dp,
    fillWidth: Boolean = false,
) {
    val colors = HzTheme.colors
    val w = if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(width)
    Box(
        modifier = w
            .height(height)
            .clip(CircleShape)
            .background(if (enabled) colors.accent else colors.accent.copy(alpha = 0.35f))
            .clickableBouncy(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            style = HzTheme.type.button,
            maxLines = 1,
        )
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = HzTheme.colors.textSecondary,
            style = HzTheme.type.subtitle,
        )
    }
}

/**
 * 卡片底：填充 + 深色下的细边。
 *
 * 与运行概览同一个写法：深色卡片 #1C1C1E 和 #000000 的底只差 12 阶亮度，
 * 纯靠填充区分已经够用，但仍需要一道 6% 的白边把圆角勾出来 —— 少了它，
 * 圆角在纯黑底上会糊成直角。
 */
private fun Modifier.drawCard(fill: Color, isDark: Boolean, corner: Dp): Modifier =
    drawBehind {
        val r = CornerRadius(corner.toPx(), corner.toPx())
        drawRoundRect(color = fill, cornerRadius = r)
        if (isDark) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.06f),
                cornerRadius = r,
                style = Stroke(width = 0.75.dp.toPx()),
            )
        }
    }


// ------------------------------------------------------------------ 状态

/**
 * 三个筛选项。
 *
 * 「不支持」单独成一个筛选项而不是只靠列表里的灰行：多数人不需要知道哪个应用是分片包，
 * 但真碰上了、装到一半失败，第一反应是"这个工具不行"。把这类应用集中收进一个筛选项，
 * 是为了让那句「不支持」出现在**选择之前**，而不是失败之后。
 */
enum class PickerFilter(@StringRes val labelRes: Int) {
    All(R.string.pk_filter_all),
    Cloned(R.string.pk_filter_cloned),
    Unsupported(R.string.pk_filter_unsupported),
}

/**
 * 一行应用。
 *
 * [meta] 已经拼好（"com.taobao.taobao · 412 MB"），Compose 层不再做格式化 ——
 * 手机显示「包名 · 体积」，平板只显示包名，这个差异由调用方按宽度决定。
 */
@Immutable
data class PickerAppUi(
    val pkg: String,
    val label: String,
    val meta: String,
    /** 分片包：整包复制走不通，行内标「不支持分片」且不可选 */
    val supported: Boolean,
    /** 已经有分身了（筛选「已分身」用） */
    val hasClone: Boolean,
    val letter: Char,
    /**
     * 带加固壳。**单独一个字段而不是拼进 [meta]**：加固壳这三个字是固定宽度，
     * 包名却可以无限长。拼成一条字符串再 ellipsize，先被截掉的永远是末尾的加固壳 ——
     * 而它恰恰是「这个应用能不能跑」的关键提示。拆开后包名自己截，标签自己占位。
     */
    val hardened: Boolean = false,
)

@Immutable
data class PickerSectionUi(
    val letter: Char,
    val apps: List<PickerAppUi>,
)

@Immutable
data class AppPickerUiState(
    val loading: Boolean = true,
    val sections: List<PickerSectionUi> = emptyList(),
    val query: String = "",
    val filter: PickerFilter = PickerFilter.All,
    /**
     * 是否处于勾选态。
     *
     * 手机上默认 false（点一行就建一个分身），点「多选」才进来；
     * 平板上一进来就是 true —— 右侧面板要求左边先有选中项。
     */
    val selectionMode: Boolean = false,
    /** 选中的包名 → 份数。份数只在平板右栏可改，手机恒为 1 */
    val selected: Map<String, Int> = emptyMap(),
    /** 选中项本身的数据，按选中顺序。不能从 [sections] 里捞：切了筛选也还得看得见已选 */
    val selectedApps: List<PickerAppUi> = emptyList(),
    val wide: Boolean = false,
)
