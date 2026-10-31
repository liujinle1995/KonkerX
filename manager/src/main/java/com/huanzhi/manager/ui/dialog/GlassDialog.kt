package com.huanzhi.manager.ui.dialog

import android.app.Dialog
import android.content.Context
import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.compose.material3.Text
import com.huanzhi.manager.R
import com.huanzhi.manager.ui.glass.clickableBouncy
import com.huanzhi.manager.ui.glass.liquidGlass
import com.huanzhi.manager.ui.theme.HuanZhiTheme
import com.huanzhi.manager.ui.theme.HzTheme

/**
 * 自绘玻璃弹窗。
 *
 * ── 为什么不用 MaterialAlertDialog ─────────────────────────────────────────
 * 整套界面已经换成 iOS 观感（纯色底 + 卡片 + 玻璃边缘高光），只有弹窗还是
 * Material 的方块白底 + 左上角标题 + 右下角两个文字按钮。这个反差在深色下最刺眼：
 * 页面是纯黑，弹窗是一块 #2B2930 的圆角矩形，四个角还带 M3 的 tonal 抬升。
 *
 * 更实际的一条：Material 弹窗的按钮是"文字按钮"，而这里的破坏性操作（卸载、删除记录）
 * 需要**一眼看出这是危险动作** —— 自绘之后红色是整块填充的胶囊，不是一行红字。
 *
 * ── 这一层为什么放在代码里画，而不是再做一个 XML 布局 ───────────────────
 * 弹窗内容全是文字与按钮，没有需要 findViewById 的东西；而且玻璃材质
 * （[liquidGlass]）本来就是 Compose 的 Modifier，用 XML 反而要把边缘高光重新画一遍。
 *
 * ── 与 Activity 的关系 ───────────────────────────────────────────────────
 * 调用方仍是「命令式」的（`context.glassAlert(...).show()` 之前的世界里是 `.show()`），
 * 因为触发点多在回调里，而不是在 Compose 的渲染树内。宿主用 [ComponentDialog]：
 * 它自带 LifecycleOwner 与 SavedStateRegistryOwner，`ComposeView` 装在
 * 普通 `Dialog` 里会抛 "ViewTreeLifecycleOwner not found"。
 */
private const val CARD_MAX_WIDTH = 344

fun Context.glassAlert(
    title: CharSequence,
    message: CharSequence? = null,
    confirmLabel: CharSequence,
    cancelLabel: CharSequence? = null,
    neutralLabel: CharSequence? = null,
    destructive: Boolean = false,
    dismissible: Boolean = true,
    onNeutral: () -> Unit = {},
    onConfirm: () -> Unit = {},
): Dialog = buildGlassDialog(
    GlassSpec(
        title = title.toString(),
        message = message?.toString(),
        confirm = confirmLabel.toString(),
        cancel = cancelLabel?.toString(),
        neutral = neutralLabel?.toString(),
        destructive = destructive,
        dismissible = dismissible,
        onConfirm = onConfirm,
        onNeutral = onNeutral,
    ),
)

/** 单选列表：切主题、索引字号这类「选一个就好」的设置 */
fun Context.glassChoice(
    title: CharSequence,
    labels: List<CharSequence>,
    checked: Int = -1,
    onPick: (Int) -> Unit,
): Dialog = buildGlassDialog(
    GlassSpec(
        title = title.toString(),
        items = labels.map { it.toString() },
        checked = checked,
        cancel = getString(R.string.cancel),
        onPick = onPick,
    ),
)

/**
 * 动作选单（原来 `.setItems(...)` 的那种）。
 *
 * 与 [glassChoice] 的区别只在语义：选单点了就执行、没有"当前选中"这一说，
 * 所以不画勾。两者共用同一套行样式，用户看到的是同一个东西。
 */
fun Context.glassMenu(
    title: CharSequence? = null,
    items: List<CharSequence>,
    onPick: (Int) -> Unit,
): Dialog = buildGlassDialog(
    GlassSpec(
        title = title?.toString().orEmpty(),
        items = items.map { it.toString() },
        cancel = getString(R.string.cancel),
        onPick = onPick,
    ),
)

private class GlassSpec(
    val title: String,
    val message: String? = null,
    val items: List<String> = emptyList(),
    val checked: Int = -1,
    val confirm: String? = null,
    val cancel: String? = null,
    val neutral: String? = null,
    val destructive: Boolean = false,
    /** 批量结果这种「必须看到了再走」的提示关掉遮罩点击 */
    val dismissible: Boolean = true,
    val onConfirm: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onNeutral: () -> Unit = {},
    val onPick: (Int) -> Unit = {},
)

private fun Context.buildGlassDialog(spec: GlassSpec): Dialog {
    val dialog = ComponentDialog(this, R.style.Theme_HuanZhi_GlassDialog)
    val content = ComposeView(dialog.context).apply {
        setContent {
            HuanZhiTheme {
                GlassDialogContent(spec = spec, dismiss = { dialog.dismiss() })
            }
        }
    }
    dialog.setContentView(content)
    dialog.window?.let { w ->
        // 让遮罩铺到状态栏与导航栏底下。dismiss 只由遮罩点击负责，
        // 所以这里不需要「点外部关闭」—— 全屏窗口没有"外部"可言
        WindowCompat.setDecorFitsSystemWindows(w, false)
    }
    dialog.setCanceledOnTouchOutside(false)
    // 批量结果这类提示必须看到了再走：连返回键一起挡住
    dialog.setCancelable(spec.dismissible)
    dialog.show()
    return dialog
}

@Composable
private fun GlassDialogContent(spec: GlassSpec, dismiss: () -> Unit) {
    val colors = HzTheme.colors
    val scrim = Color.Black.copy(alpha = if (colors.isDark) 0.56f else 0.32f)
    val shape = RoundedCornerShape(26.dp)
    // 卡片不透明：这一层没有真背景模糊（原因见 Glass.kt 的注释），
    // 半透明只会让背后的行文字以 2–3% 的强度渗出来 —— 那不是"玻璃"，是脏。
    // 玻璃的观感由描边高光与投影提供，填充必须是不透明的。
    val cardFill = colors.surface
    // 手写而不是用 Modifier.clickable{}：Material 的涟漪在玻璃上没有落点，
    // 它的扩散半径是矩形的，压在这张 26dp 圆角的卡片上会从角上漏出去
    val inert = remember { MutableInteractionSource() }
    val isChoice = spec.items.isNotEmpty()

    Box(
        Modifier
            .fillMaxSize()
            .background(scrim)
            // 点遮罩关闭。卡片自己再消费一次点击，否则点卡片也会传到这里
            .clickable(
                interactionSource = inert,
                indication = null,
                enabled = spec.dismissible,
            ) { dismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 32.dp)
                .widthIn(max = CARD_MAX_WIDTH.dp)
                .fillMaxWidth()
                .liquidGlass(
                    shape = shape,
                    fill = cardFill,
                    shadowElevation = 28.dp,
                    highlight = Color.White.copy(alpha = if (colors.isDark) 0.14f else 0.75f),
                )
                .clip(shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .semantics { paneTitle = spec.title }
                .padding(horizontal = 20.dp)
                .padding(top = 22.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (spec.title.isNotEmpty()) {
                Text(
                    text = spec.title,
                    color = colors.textPrimary,
                    style = HzTheme.type.cardTitle,
                    textAlign = TextAlign.Center,
                )
            }
            if (spec.message != null) {
                Spacer(Modifier.height(if (spec.title.isEmpty()) 0.dp else 8.dp))
                // 批量结果那种正文可能列出七八条失败原因，短屏上会把卡片顶出屏幕。
                // 给正文一个上限并允许滚动 —— 标题与按钮始终留在原位，
                // 滚动只发生在"读的那一段"里
                Box(
                    Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = spec.message,
                        color = colors.textSecondary,
                        // 14sp / 20sp：比卡片正文的 13sp 大一档。弹窗里的这段是"要被读完再决定"
                        // 的文字，不是列表里的辅助信息
                        style = HzTheme.type.cardMeta.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (isChoice) {
                Spacer(Modifier.height(14.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        // 选项多的时候（比如以后加语言选择）不让卡片顶穿屏幕
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    spec.items.forEachIndexed { i, label ->
                        if (i > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .background(colors.divider),
                            )
                        }
                        GlassItemRow(
                            label = label,
                            checked = i == spec.checked,
                            onClick = {
                                dismiss()
                                spec.onPick(i)
                            },
                        )
                    }
                }
            }

            val hasButtons = spec.confirm != null || spec.cancel != null || spec.neutral != null
            if (hasButtons) {
                Spacer(Modifier.height(if (isChoice) 8.dp else 18.dp))
                // 三个动作挤在一行会各自被压成 100dp 以下，「复制详情」这种四字标签
                // 在中文里就要换行了 —— 多于两个就改成竖排，宽度换高度
                if (spec.neutral != null) {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (spec.confirm != null) {
                            GlassButton(
                                label = spec.confirm,
                                fill = if (spec.destructive) colors.red else colors.accent,
                                textColor = Color.White,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                dismiss()
                                spec.onConfirm()
                            }
                        }
                        GlassButton(
                            label = spec.neutral,
                            fill = colors.fill,
                            textColor = colors.textPrimary,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            dismiss()
                            spec.onNeutral()
                        }
                        if (spec.cancel != null) {
                            GlassButton(
                                label = spec.cancel,
                                fill = colors.fill,
                                textColor = colors.textPrimary,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                dismiss()
                                spec.onCancel()
                            }
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (spec.cancel != null) {
                            GlassButton(
                                label = spec.cancel,
                                fill = colors.fill,
                                textColor = colors.textPrimary,
                                modifier = Modifier.weight(1f),
                            ) {
                                dismiss()
                                spec.onCancel()
                            }
                        }
                        if (spec.confirm != null) {
                            GlassButton(
                                label = spec.confirm,
                                fill = if (spec.destructive) colors.red else colors.accent,
                                textColor = Color.White,
                                modifier = Modifier.weight(1f),
                            ) {
                                dismiss()
                                spec.onConfirm()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 选项行 48dp：既是列表节奏，也是最小触摸目标。
 * 选中态用勾 + 主色文字，不用整行填色 —— 整行填色会让"当前选中"和"按下"看起来一样。
 */
@Composable
private fun GlassItemRow(label: String, checked: Boolean, onClick: () -> Unit) {
    val colors = HzTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickableBouncy(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (checked) colors.accent else colors.textPrimary,
            style = HzTheme.type.subtitle.copy(
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (checked) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun GlassButton(
    label: String,
    fill: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(fill)
            .clickableBouncy(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            style = HzTheme.type.subtitle.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}
