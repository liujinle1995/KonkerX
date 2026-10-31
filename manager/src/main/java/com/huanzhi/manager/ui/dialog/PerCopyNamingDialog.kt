package com.huanzhi.manager.ui.dialog

import android.app.Dialog
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.huanzhi.manager.R
import com.huanzhi.manager.ui.glass.clickableBouncy
import com.huanzhi.manager.ui.glass.liquidGlass
import com.huanzhi.manager.ui.theme.HuanZhiTheme
import com.huanzhi.manager.ui.theme.HzTheme
import com.huanzhi.manager.ui.theme.HzTypography

/**
 * 逐份命名（自绘玻璃版，替掉原来的 MaterialAlertDialog + TextInputLayout）。
 *
 * 它是从「创建分身」弹窗里点「逐份」弹出来的 —— 底下那层已经是玻璃卡片了，
 * 上面再盖一张 Material 的方块白底，两层圆角与底色都不在一套语言里。
 *
 * 交互上有两条刻意保留的老规则：
 *   · 名字空着不关弹窗。关掉再开，其他几份刚改好的输入会一起丢掉；
 *   · 每份一个输入框、改哪个都行，没改的保持默认。
 */
private const val CARD_MAX_WIDTH = 344

fun Context.showPerCopyNaming(
    title: String,
    initial: List<String>,
    onDone: (List<String>) -> Unit,
): Dialog {
    val dialog = ComponentDialog(this, R.style.Theme_HuanZhi_GlassDialog)
    val content = ComposeView(dialog.context).apply {
        setContent {
            HuanZhiTheme {
                PerCopySheet(
                    title = title,
                    initial = initial,
                    dismiss = { dialog.dismiss() },
                    onDone = {
                        dialog.dismiss()
                        onDone(it)
                    },
                )
            }
        }
    }
    dialog.setContentView(content)
    dialog.window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
    dialog.setCanceledOnTouchOutside(false)
    dialog.show()
    return dialog
}

@Composable
private fun PerCopySheet(
    title: String,
    initial: List<String>,
    dismiss: () -> Unit,
    onDone: (List<String>) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val colors = HzTheme.colors
    val type = HzTheme.type
    val names = remember(initial) { mutableStateListOf<String>().apply { addAll(initial) } }
    var error by remember { mutableStateOf(-1) }
    val scrim = Color.Black.copy(alpha = if (colors.isDark) 0.56f else 0.32f)
    val inert = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(26.dp)

    Box(
        Modifier
            .fillMaxSize()
            .background(scrim)
            .clickable(interactionSource = inert, indication = null) { dismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 32.dp)
                .widthIn(max = CARD_MAX_WIDTH.dp)
                .fillMaxWidth()
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
                .semantics { paneTitle = title }
                .padding(horizontal = 20.dp, vertical = 22.dp),
        ) {
            Text(
                text = title,
                color = colors.textPrimary,
                style = type.cardTitle,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            // 十份就是十个输入框，矮屏（411dp）上必须能滚，否则「完成」会被顶出卡片
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                names.forEachIndexed { i, name ->
                    val bad = error == i
                    Text(
                        text = ctx.getString(R.string.per_copy_hint, i + 1),
                        color = if (bad) colors.red else colors.textSecondary,
                        style = type.cardMeta.copy(fontSize = 12.sp),
                    )
                    Spacer(Modifier.height(4.dp))
                    NameField(
                        value = name,
                        onValue = {
                            names[i] = it.take(MAX_NAME_LEN)
                            if (bad) error = -1
                        },
                        error = bad,
                        type = type,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(
                    label = stringResource(R.string.cancel),
                    fill = colors.dialogField,
                    text = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                    onClick = dismiss,
                )
                Pill(
                    label = stringResource(R.string.done),
                    fill = colors.accent,
                    text = Color.White,
                    modifier = Modifier.weight(1f),
                ) {
                    val blank = names.indexOfFirst { it.isBlank() }
                    if (blank >= 0) {
                        // 不关弹窗，让用户接着改：关掉再开其他几份就白输了
                        error = blank
                        Toast.makeText(ctx, ctx.getString(R.string.name_empty), Toast.LENGTH_SHORT).show()
                        return@Pill
                    }
                    onDone(names.map { it.trim() })
                }
            }
        }
    }
}

@Composable
private fun NameField(
    value: String,
    onValue: (String) -> Unit,
    error: Boolean,
    type: HzTypography,
) {
    val colors = HzTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValue,
        textStyle = type.subtitle.copy(fontSize = 15.sp, color = colors.textPrimary),
        singleLine = true,
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions.Default,
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.dialogField)
                    .then(
                        if (error) {
                            Modifier.background(colors.red.copy(alpha = 0.08f))
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) { inner() }
        },
    )
}

@Composable
private fun Pill(
    label: String,
    fill: Color,
    text: Color,
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
            color = text,
            style = HzTheme.type.subtitle.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}
