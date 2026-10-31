package com.huanzhi.manager.ui.dialog

import android.app.Dialog
import android.content.Context
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.huanzhi.manager.R
import com.huanzhi.manager.core.StorageCheck
import com.huanzhi.manager.ui.adaptive.WindowSizeClasses
import com.huanzhi.manager.ui.glass.clickableBouncy
import com.huanzhi.manager.ui.glass.liquidGlass
import com.huanzhi.manager.ui.theme.HuanZhiTheme
import com.huanzhi.manager.ui.theme.HzTheme
import com.huanzhi.manager.ui.theme.HzTypography
import com.huanzhi.manager.util.Formats
import kotlin.math.roundToInt

/**
 * 创建分身 / 批量创建分身弹窗。
 *
 * 稿面：design/34–41（手机 / 平板 × 浅色 / 深色 × 单应用 / 批量，共八张）。
 *
 * ── 为什么这两个弹窗要自己画 ────────────────────────────────────────────
 * 整套界面剩下的最后一块 Material：方块白底、左上角标题、右下角两个文字按钮。
 * 更要紧的是它早就"装不下"了 —— 加固壳提示、逐份命名、份数滑块、空间预估全塞进去之后，
 * Material 那层自动套的 ScrollView 会在矮屏上把内容裁掉（横屏手机上连"共 N 个分身"
 * 都被压扁）。自绘之后内容多高是自己算的：矮屏滚动、高屏居中，两个按钮永远在最下面。
 *
 * ── 两个入口为什么共用这一个 ────────────────────────────────────────────
 * 「建一个」和「建一批」在稿面上就是同一个东西：标题换一行、名称区从一行变成 N 行，
 * 其余（加固提示、份数滑块、空间预估、右下角两个文字按钮）完全一样。
 * 以前是两套 XML 加两套渲染函数，改一处要记得改另一处；现在差别只落在
 * [CreateCloneRequest] 的字段上。
 *
 * ── 度量 ────────────────────────────────────────────────────────────────
 * 手机稿 1500×3248 = 375×812@4x、平板稿 4096×3072 = 1024×768@4x，故 1dp = 4px。
 * 逐项实测值都收在 [Metrics] 里，改之前先看那里的注释。
 */
internal const val MAX_NAME_LEN = 24

// ---------------------------------------------------------------- 输入 / 输出

class CreateCloneRequest(
    /** 「创建分身」/「批量创建分身」 */
    val title: String,
    /** 单应用：应用名（灰）；批量：已选 N 个应用（黑） */
    val subtitle: String,
    /** 批量时列出应用名（"微信、淘宝"）；单应用留空则不画这一行 */
    val apps: String = "",
    /** 加固壳提示；null 表示不画提示块 */
    val warn: String? = null,
    val rows: List<Row>,
    /** 统一份数；每行份数已经由上一屏定过时，[slider] 为 false */
    val copies: Int,
    val maxCopies: Int,
    /** 是否显示「份数」滑块。平板右栏逐项定过份数且各不相同时必须关掉 */
    val slider: Boolean,
    /** 名称区块标题下的说明（单应用没有） */
    val hint: String = "",
    /** 底部说明；[spaceHint] 为真时再拼一句「预计占用 …，当前可用 …」 */
    val note: String,
    /**
     * 底部要不要带空间预估。稿面只有单应用那四张（34/36/38/40）带，
     * 批量那四张（35/37/39/41）说明就停在「…点取消可以中止后续。」。
     */
    val spaceHint: Boolean = true,
    /** 份数变化时没手动改过的行填什么：(行号, 份数) → 默认基础名 */
    val defaultName: (row: Int, copies: Int) -> String,
) {
    class Row(
        val pkg: String,
        val label: String,
        /** 输入框被清空时兜底用的基础名 */
        val fallback: String,
        /** 源应用 APK 大小，用来算整批要占多少空间 */
        val sourceBytes: Long,
        /** 逐项定过份数时这一行的份数；统一模式被滑块覆盖 */
        val count: Int,
    )
}

class CreateCloneResult(
    val copies: Int,
    val plans: List<Plan>,
) {
    class Plan(val pkg: String, val names: List<String>)
}

fun Context.showCreateCloneDialog(
    request: CreateCloneRequest,
    onConfirm: (CreateCloneResult) -> Unit,
): Dialog {
    val dialog = ComponentDialog(this, R.style.Theme_HuanZhi_GlassDialog)
    val content = ComposeView(dialog.context).apply {
        setContent {
            HuanZhiTheme {
                CreateCloneSheet(
                    request = request,
                    dismiss = { dialog.dismiss() },
                    onConfirm = {
                        dialog.dismiss()
                        onConfirm(it)
                    },
                )
            }
        }
    }
    dialog.setContentView(content)
    dialog.window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
    // 里面正在起名字，蹭一下屏幕就把半天的输入关掉太亏了
    dialog.setCanceledOnTouchOutside(false)
    dialog.show()
    return dialog
}

// ---------------------------------------------------------------- 状态

/**
 * 弹窗正在编辑的那份草稿。
 *
 * 状态放在这一层而不是 Activity：名称要跟着份数实时重算预览，改份数还要把没手改过的行
 * 恢复默认 —— 这些都是"界面自己的推导"，搬到 Activity 里就得再写一遍同样的逻辑。
 */
private class Draft(request: CreateCloneRequest) {

    class RowState(
        val src: CreateCloneRequest.Row,
        val index: Int,
        name: String,
        count: Int,
    ) {
        /** 输入框里的基础名 */
        var name by mutableStateOf(name)
        /** 逐份定过的名字；null 表示按基础名自动编号 */
        var custom by mutableStateOf<List<String>?>(null)
        /** 用户手动改过基础名（改过之后不再被份数变化覆盖） */
        var touched by mutableStateOf(false)
        /** 逐项模式下这一行的份数 */
        var count by mutableStateOf(count)
    }

    /** 份数由滑块统一控制 */
    val uniform: Boolean = request.slider
    val maxCopies: Int = request.maxCopies
    private val defaultName = request.defaultName

    val rows: List<RowState> = request.rows.mapIndexed { i, r ->
        RowState(r, i, request.defaultName(i, if (request.slider) request.copies else r.count), r.count)
    }

    var copies by mutableStateOf(request.copies)

    fun countOf(r: RowState): Int = if (uniform) copies else r.count

    fun namesOf(r: RowState): List<String> {
        val n = countOf(r)
        r.custom?.let { if (it.size == n) return it }
        val base = r.name.trim().ifEmpty { r.src.fallback }
        return if (n <= 1) listOf(base) else (1..n).map { "$base · 分身$it" }
    }

    fun total(): Int = rows.sumOf(::countOf)

    /** 整批落地要吃掉多少空间 —— 分身是整包复制，不是增量 */
    fun needBytes(): Long = rows.sumOf { StorageCheck.estimateNeeded(it.src.sourceBytes, countOf(it)) }

    /**
     * 拖动份数滑块。
     *
     * 两条连带规则，都是为了让"输入框里看到的"和"预览里算出来的"永远说的是同一件事：
     *   · 逐份定过的名字数量对不上就作废（三份的名字套不到两份上）；
     *   · 没手改过的行跟着回到默认（1 份时叫「微信 2」，改成两份后应该回到「微信」）。
     */
    fun applyCopies(n: Int) {
        copies = n
        rows.forEach { r ->
            if (uniform) r.count = n
            if (r.custom != null && r.custom!!.size != countOf(r)) r.custom = null
            if (!r.touched) r.name = defaultName(r.index, countOf(r))
        }
    }

    fun onName(r: RowState, v: String) {
        r.name = v.take(MAX_NAME_LEN)
        r.touched = true
        r.custom = null
    }

    fun onCustom(r: RowState, names: List<String>) {
        r.custom = names
        r.touched = true
    }

    fun result(): CreateCloneResult = CreateCloneResult(
        copies = copies,
        plans = rows.map { CreateCloneResult.Plan(it.src.pkg, namesOf(it)) },
    )
}

// ---------------------------------------------------------------- 画面

@Composable
private fun CreateCloneSheet(
    request: CreateCloneRequest,
    dismiss: () -> Unit,
    onConfirm: (CreateCloneResult) -> Unit,
) {
    val ctx = LocalContext.current
    val wide = WindowSizeClasses.canShowTwoPane(ctx)
    val m = remember(wide) { Metrics.of(wide) }
    val draft = remember(request) { Draft(request) }
    val colors = HzTheme.colors
    val type = HzTheme.type
    val batch = request.rows.size > 1

    val total = draft.total()
    val need = draft.needBytes()
    val free = remember(request) { StorageCheck.freeBytes(ctx) }

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(260, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)),
        label = "createCloneIn",
    )

    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val scrim = Color.Black.copy(alpha = if (colors.isDark) 0.56f else 0.32f)
    val inert = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(m.cardCorner)

    Box(
        Modifier
            .fillMaxSize()
            .background(scrim.copy(alpha = scrim.alpha * progress))
            .clickable(interactionSource = inert, indication = null) { dismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .then(
                    if (wide) {
                        Modifier.width(m.cardWidth)
                    } else {
                        // 先限宽再撑满：widthIn 必须在外层，否则只夹住内容、卡片仍会撑满屏宽
                        Modifier.widthIn(max = m.cardWidth).fillMaxWidth()
                    }
                )
                // 稿面批量那张在 1024x768 的平板上就有 738dp 高，只留 15dp 上下边距；
                // 按屏高的百分比卡会在矮屏上把「取消 / 创建」挤出可视区，改成留固定边距
                .heightIn(max = (screenH - 24.dp).coerceAtLeast(240.dp))
                .graphicsLayer {
                    alpha = progress
                    scaleX = 0.96f + 0.04f * progress
                    scaleY = 0.96f + 0.04f * progress
                }
                .liquidGlass(
                    shape = shape,
                    fill = colors.dialogSurface,
                    shadowElevation = 28.dp,
                    highlight = Color.White.copy(alpha = if (colors.isDark) 0.14f else 0.75f),
                )
                .clip(shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .semantics { paneTitle = request.title },
        ) {
            // 内容区和按钮行分开：内容照稿面一次性铺开，装不下时（横屏手机、1024x768 的
            // 平板）才滚动，而「取消 / 创建」始终钉在卡片底部 —— 跟着内容一起滚出去的话，
            // 用户会以为弹窗没有出口。
            Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(top = m.padTop)
                    .padding(horizontal = m.padSide),
            ) {
                Title(request = request, m = m)

                if (request.warn != null) {
                    Spacer(Modifier.height(m.gapWarn))
                    WarnBlock(text = request.warn, m = m)
                }

                Spacer(Modifier.height(m.gapSection))
                // 单应用这一行是"字段标签"（灰、小）；批量它是一个分区标题（黑、半粗）——
                // 稿面上两处的字号与颜色都不一样，不是同一个东西
                Text(
                    text = stringResource(R.string.clone_name),
                    color = if (batch) colors.textPrimary else colors.textSecondary,
                    style = if (batch) {
                        m.label(type).copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        m.body(type)
                    },
                )
                if (request.hint.isNotEmpty()) {
                    Spacer(Modifier.height(m.gapSectionHint))
                    Text(
                        text = request.hint,
                        color = colors.textSecondary,
                        style = m.body(type),
                    )
                }

                draft.rows.forEach { row ->
                    val count = draft.countOf(row)
                    val names = draft.namesOf(row)
                    Spacer(Modifier.height(m.gapRowRow))
                    if (batch) {
                        Text(
                            text = row.src.label,
                            color = colors.textPrimary,
                            style = m.label(type),
                        )
                        Spacer(Modifier.height(m.gapRowField))
                    }
                    NameRow(
                        value = row.name,
                        onValue = { draft.onName(row, it) },
                        m = m,
                        // 只有一份的时候谈"每份各叫什么"没有意义，按钮出现反而像漏填了什么
                        perCopy = if (count > 1) {
                            {
                                ctx.showPerCopyNaming(
                                    title = ctx.getString(R.string.per_copy_title, row.src.label),
                                    initial = names,
                                ) { draft.onCustom(row, it) }
                            }
                        } else {
                            null
                        },
                    )
                    Spacer(Modifier.height(m.gapFieldPreview))
                    Text(
                        text = if (batch) {
                            "→ " + names.take(3).joinToString("、") +
                                if (names.size > 3) " … 共 ${names.size} 个" else ""
                        } else if (names.size == 1) {
                            stringResource(R.string.name_preview_one, names[0])
                        } else {
                            stringResource(R.string.name_preview_many, names[0], names[1], names.size)
                        },
                        color = colors.textSecondary,
                        style = m.preview(type),
                    )
                }

                val countLabel = when {
                    !batch -> if (total == 1) {
                        stringResource(R.string.clone_count_one)
                    } else {
                        stringResource(R.string.clone_count_many, total)
                    }
                    draft.uniform -> stringResource(R.string.cc_count_batch, draft.copies, total)
                    // 逐项定过份数时再写"每个应用生成 N 份"就是谎话
                    else -> stringResource(R.string.cc_count_batch_total, total)
                }
                Spacer(Modifier.height(m.gapCount))
                Text(
                    text = countLabel,
                    color = colors.textPrimary,
                    style = m.count(type),
                )
                if (draft.uniform) {
                    Spacer(Modifier.height(m.gapSlider))
                    CopySlider(
                        value = draft.copies,
                        max = draft.maxCopies,
                        onChange = { draft.applyCopies(it) },
                        m = m,
                    )
                }

                Spacer(Modifier.height(m.gapNote))
                Note(
                    note = request.note,
                    need = need,
                    free = free,
                    spaceHint = request.spaceHint,
                    m = m,
                )

            }

            ActionRow(
                confirm = if (total == 1) {
                    stringResource(R.string.create)
                } else {
                    stringResource(R.string.cc_create_many, total)
                },
                m = m,
                modifier = Modifier
                    .padding(top = m.gapButtons, bottom = m.padBottom)
                    .padding(horizontal = m.padSide),
                onCancel = dismiss,
                onConfirm = {
                    // 空名字不能提交，但也不能就此关掉弹窗：关掉再开，其他几份的输入一起没了
                    if (draft.rows.any { draft.namesOf(it).any(String::isBlank) }) {
                        Toast.makeText(ctx, ctx.getString(R.string.name_empty), Toast.LENGTH_SHORT).show()
                        return@ActionRow
                    }
                    onConfirm(draft.result())
                },
            )
            }
        }
    }
}

@Composable
private fun Title(request: CreateCloneRequest, m: Metrics) {
    val colors = HzTheme.colors
    val type = HzTheme.type
    Column {
        Text(
            text = request.title,
            color = colors.textPrimary,
            style = m.title(type),
        )
        Spacer(Modifier.height(m.gapTitleSub))
        Text(
            text = request.subtitle,
            // 批量这一行是"已选 N 个应用"，是结论；单应用是应用名，是补充 —— 稿面上前者黑、后者灰
            color = if (request.apps.isEmpty()) colors.textSecondary else colors.textPrimary,
            style = m.sub(type),
        )
        if (request.apps.isNotEmpty()) {
            Spacer(Modifier.height(m.gapSubApps))
            Text(
                text = request.apps,
                color = colors.textSecondary,
                style = m.body(type),
            )
        }
    }
}

/**
 * 加固壳提示块。
 *
 * 整张卡里唯一有底色的容器 —— 它承载的是"待办"而不是说明：这批装上去大概率跑不起来，
 * 用户有权在点「创建」之前知道。换成灰底它就会退化成又一段会被滑过去的文字。
 */
@Composable
private fun WarnBlock(text: String, m: Metrics) {
    val colors = HzTheme.colors
    val type = HzTheme.type
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(m.warnCorner))
            .background(colors.dialogWarnFill)
            .padding(horizontal = m.warnPadH, vertical = m.warnPadV),
    ) {
        Text(
            text = text,
            color = colors.dialogWarnText,
            style = m.warn(type),
        )
    }
}

/** 一行「名字输入 + 逐份」。「逐份」只在份数大于 1 时出现 */
@Composable
private fun NameRow(
    value: String,
    onValue: (String) -> Unit,
    perCopy: (() -> Unit)?,
    m: Metrics,
) {
    val colors = HzTheme.colors
    val type = HzTheme.type
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = onValue,
            textStyle = m.field(type).copy(color = colors.textPrimary),
            singleLine = true,
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .height(m.fieldHeight)
                        .clip(RoundedCornerShape(m.fieldCorner))
                        .background(colors.dialogField)
                        .padding(horizontal = m.fieldPad),
                    contentAlignment = Alignment.CenterStart,
                ) { inner() }
            },
        )
        if (perCopy != null) {
            Spacer(Modifier.width(m.gapPerCopy))
            Text(
                text = stringResource(R.string.name_per_copy),
                color = colors.accent,
                style = m.body(type),
                modifier = Modifier.clickableBouncy(onClick = perCopy),
            )
        }
    }
}

/** 底部说明 + 空间预估。分身是整包复制，五份大应用很吃空间，账在点「创建」之前算清 */
@Composable
private fun Note(
    note: String,
    need: Long,
    free: Long,
    spaceHint: Boolean,
    m: Metrics,
) {
    val colors = HzTheme.colors
    val space = when {
        need <= 0L -> ""
        !spaceHint -> ""
        need > free -> " " + stringResource(R.string.cc_space_low, Formats.size(need), Formats.size(free))
        else -> " " + stringResource(R.string.cc_space_ok, Formats.size(need), Formats.size(free))
    }
    Text(
        text = note + space,
        color = if (need > free) colors.red else colors.textSecondary,
        style = m.note(HzTheme.type),
    )
}

/**
 * 右下角两个文字按钮。
 *
 * 稿面是"纯文字 + 无底"，和玻璃弹窗那两颗填充胶囊不同：这里的两个动作是平等的
 * 「走 / 留」，不是「主操作 / 次操作」—— 填色会让「取消」看起来像被劝退。
 * 「创建」用半粗拉开一点层级即可。
 */
@Composable
private fun ActionRow(
    confirm: String,
    m: Metrics,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = HzTheme.colors
    val type = HzTheme.type
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.cancel),
            color = colors.accent,
            style = m.action(type),
            modifier = Modifier.clickableBouncy(onClick = onCancel),
        )
        Spacer(Modifier.width(m.actionGap))
        Text(
            text = confirm,
            color = colors.accent,
            style = m.action(type).copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.clickableBouncy(onClick = onConfirm),
        )
    }
}

/**
 * 份数滑块。
 *
 * ── 为什么自己画 ──────────────────────────────────────────────────────
 * 稿面的滑轨是"粗轨道 + 白圆滑块 + 轨道内的刻度点"：轨道 12dp，是 Material 那根 4dp
 * 细线的三倍；刻度点压在未填充段**里面**，而 Material 的 tick 是画在轨道外面的一圈小点。
 * 这两处都不是 Slider 靠换颜色能改出来的。
 *
 * ── 落点 ──────────────────────────────────────────────────────────────
 * 实测两张手机稿（34 的 1 份、35 的 2 份，两稿上限都是 5）：滑块中心分别落在轨道宽度的
 * 20% 与 40%，也就是 **v / max**，而不是常见的 (v-1)/(max-1)。刻度按同一套比例排
 * （i / max），所以拖到第 k 份时滑块正压在第 k 个刻度上。
 */
@Composable
private fun CopySlider(
    value: Int,
    max: Int,
    onChange: (Int) -> Unit,
    m: Metrics,
) {
    val colors = HzTheme.colors
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(m.sliderHeight),
        contentAlignment = Alignment.Center,
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }

        fun valueFromX(x: Float): Int =
            ((x / widthPx).coerceIn(0f, 1f) * max).roundToInt().coerceIn(1, max)

        // 拖动交给 draggable 而不是 detectDragGestures：竖着划的时候它会自动把手势
        // 让给外层滚动（矮屏上这张卡是要滚的），自己抓着不放就划不动了
        val dragX = remember { mutableStateOf(0f) }
        Canvas(
            Modifier
                .fillMaxSize()
                .draggable(
                    state = rememberDraggableState { delta ->
                        dragX.value += delta
                        onChange(valueFromX(dragX.value))
                    },
                    orientation = Orientation.Horizontal,
                    onDragStarted = {
                        dragX.value = it.x
                        onChange(valueFromX(it.x))
                    },
                )
                .pointerInput(max) {
                    detectTapGestures { onChange(valueFromX(it.x)) }
                },
        ) {
            val trackPx = m.trackHeight.toPx()
            val thumbPx = m.thumbSize.toPx()
            val cy = size.height / 2f
            val cx = (value.toFloat() / max.toFloat()) * size.width
            val r = trackPx / 2f
            val top = cy - trackPx / 2f

            drawRoundRect(
                color = colors.dialogField,
                topLeft = Offset(0f, top),
                size = Size(size.width, trackPx),
                cornerRadius = CornerRadius(r, r),
            )
            drawRoundRect(
                color = colors.accent,
                topLeft = Offset(0f, top),
                // 最左一格也要留住圆头，否则 1 份时看起来像轨道断了一截
                size = Size(maxOf(trackPx, cx), trackPx),
                cornerRadius = CornerRadius(r, r),
            )

            val tickR = m.tickSize.toPx() / 2f
            for (i in 1 until max) {
                val x = (i.toFloat() / max.toFloat()) * size.width
                drawCircle(color = colors.dialogTick, radius = tickR, center = Offset(x, cy))
            }

            // Canvas 里没有 elevation，阴影用一层压扁的低透明度圆代替（稿面滑块确实带一圈投影）
            drawCircle(
                color = Color.Black.copy(alpha = 0.10f),
                radius = thumbPx / 2f + 1.5.dp.toPx(),
                center = Offset(cx, cy + 1.dp.toPx()),
            )
            drawCircle(color = Color.White, radius = thumbPx / 2f, center = Offset(cx, cy))
        }
    }
}

// ---------------------------------------------------------------- 度量

/**
 * 两份度量：手机稿（34/35/38/39）与平板稿（36/37/40/41）。
 *
 * 平板不是等比放大 —— 标题 +4sp（20→24），正文 +2sp（13→15、15→17），而卡片、输入框
 * 这类容器按各自实测值走（320→440、48→56、内边距 20→24）。分开写清是为了以后调某一项时
 * 知道自己在动稿面的哪一格。
 *
 * 间距一律是"上一个文本框的下沿 → 下一个文本框的上沿"，按稿面的墨迹位置反推：
 * 墨迹在行高里居中，所以 gap = 实测墨迹间距 − (上一行行高 − 墨迹高)/2 − (下一行同理)。
 */
private class Metrics(
    val cardWidth: Dp,
    val cardCorner: Dp,
    val padTop: Dp,
    val padSide: Dp,
    val padBottom: Dp,
    val titleSize: TextUnit,
    val titleLine: TextUnit,
    val subSize: TextUnit,
    val subLine: TextUnit,
    val bodySize: TextUnit,
    val bodyLine: TextUnit,
    val previewSize: TextUnit,
    val labelSize: TextUnit,
    val fieldSize: TextUnit,
    val fieldHeight: Dp,
    val fieldCorner: Dp,
    val fieldPad: Dp,
    val countSize: TextUnit,
    val countLine: TextUnit,
    val warnLine: TextUnit,
    val warnCorner: Dp,
    val warnPadH: Dp,
    val warnPadV: Dp,
    val noteSize: TextUnit,
    val noteLine: TextUnit,
    val actionSize: TextUnit,
    val actionLine: TextUnit,
    val actionGap: Dp,
    val gapPerCopy: Dp,
    val trackHeight: Dp,
    val thumbSize: Dp,
    val tickSize: Dp,
    val sliderHeight: Dp,
    val gapTitleSub: Dp,
    val gapSubApps: Dp,
    val gapWarn: Dp,
    val gapSection: Dp,
    val gapSectionHint: Dp,
    val gapRowRow: Dp,
    val gapRowField: Dp,
    val gapFieldPreview: Dp,
    val gapCount: Dp,
    val gapSlider: Dp,
    val gapNote: Dp,
    val gapButtons: Dp,
) {
    fun title(type: HzTypography) = type.cardTitle.copy(
        fontSize = titleSize,
        lineHeight = titleLine,
        fontWeight = FontWeight.SemiBold,
    )

    fun sub(type: HzTypography) = type.subtitle.copy(fontSize = subSize, lineHeight = subLine)

    fun body(type: HzTypography) = type.cardMeta.copy(fontSize = bodySize, lineHeight = bodyLine)

    fun preview(type: HzTypography) = type.cardMeta.copy(fontSize = previewSize, lineHeight = bodyLine)

    fun label(type: HzTypography) = type.subtitle.copy(fontSize = labelSize, lineHeight = bodyLine)

    fun field(type: HzTypography) = type.subtitle.copy(fontSize = fieldSize, lineHeight = bodyLine)

    fun warn(type: HzTypography) = type.cardMeta.copy(fontSize = bodySize, lineHeight = warnLine)

    fun count(type: HzTypography) = type.subtitle.copy(
        fontSize = countSize,
        lineHeight = countLine,
        fontWeight = FontWeight.SemiBold,
    )

    fun note(type: HzTypography) = type.cardMeta.copy(fontSize = noteSize, lineHeight = noteLine)

    fun action(type: HzTypography) = type.subtitle.copy(fontSize = actionSize, lineHeight = actionLine)

    companion object {
        fun of(wide: Boolean): Metrics = if (wide) {
            Metrics(
                cardWidth = 440.dp,
                cardCorner = 24.dp,
                padTop = 28.dp,
                padSide = 24.dp,
                padBottom = 24.dp,
                titleSize = 24.sp,
                titleLine = 36.sp,
                subSize = 16.sp,
                subLine = 22.sp,
                bodySize = 15.sp,
                bodyLine = 20.sp,
                previewSize = 14.sp,
                labelSize = 15.sp,
                fieldSize = 17.sp,
                fieldHeight = 56.dp,
                fieldCorner = 12.dp,
                fieldPad = 17.dp,
                countSize = 17.sp,
                countLine = 22.sp,
                warnLine = 24.sp,
                warnCorner = 12.dp,
                warnPadH = 17.dp,
                warnPadV = 13.dp,
                noteSize = 15.sp,
                noteLine = 23.sp,
                actionSize = 17.sp,
                actionLine = 22.sp,
                actionGap = 30.dp,
                gapPerCopy = 12.dp,
                trackHeight = 14.dp,
                thumbSize = 22.dp,
                tickSize = 5.dp,
                sliderHeight = 26.dp,
                gapTitleSub = 4.dp,
                gapSubApps = 4.dp,
                gapWarn = 15.dp,
                gapSection = 17.dp,
                gapSectionHint = 6.dp,
                gapRowRow = 14.dp,
                gapRowField = 6.dp,
                gapFieldPreview = 8.dp,
                gapCount = 15.dp,
                gapSlider = 14.dp,
                gapNote = 15.dp,
                gapButtons = 28.dp,
            )
        } else {
            Metrics(
                cardWidth = 320.dp,
                cardCorner = 20.dp,
                padTop = 24.dp,
                padSide = 20.dp,
                padBottom = 20.dp,
                titleSize = 20.sp,
                titleLine = 28.sp,
                subSize = 14.sp,
                subLine = 20.sp,
                bodySize = 13.sp,
                bodyLine = 18.sp,
                previewSize = 12.sp,
                labelSize = 13.sp,
                fieldSize = 15.sp,
                fieldHeight = 48.dp,
                fieldCorner = 10.dp,
                fieldPad = 16.dp,
                countSize = 15.sp,
                countLine = 20.sp,
                warnLine = 20.sp,
                warnCorner = 10.dp,
                warnPadH = 14.5.dp,
                warnPadV = 12.dp,
                noteSize = 13.sp,
                noteLine = 20.sp,
                actionSize = 15.sp,
                actionLine = 20.sp,
                actionGap = 26.dp,
                gapPerCopy = 9.dp,
                trackHeight = 12.dp,
                thumbSize = 20.dp,
                tickSize = 4.dp,
                sliderHeight = 23.dp,
                gapTitleSub = 4.dp,
                gapSubApps = 4.dp,
                gapWarn = 12.dp,
                gapSection = 13.dp,
                gapSectionHint = 10.dp,
                gapRowRow = 6.dp,
                gapRowField = 5.dp,
                gapFieldPreview = 6.dp,
                gapCount = 13.dp,
                gapSlider = 11.dp,
                gapNote = 13.dp,
                gapButtons = 24.dp,
            )
        }
    }
}
