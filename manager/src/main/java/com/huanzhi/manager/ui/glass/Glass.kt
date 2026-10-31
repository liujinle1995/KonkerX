package com.huanzhi.manager.ui.glass

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.huanzhi.manager.ui.theme.HzTheme

/**
 * 玻璃材质。
 *
 * ── 为什么这里没有真「背景模糊」 ──────────────────────────────────────────────
 * Liquid Glass 的核心是 backdrop-aware 渲染：把背后的内容采样、模糊、再折射。
 * 但**这个设计里它没有视觉收益**，理由是实测出来的：
 *   · 页面底色是纯色（浅 #F2F2F7 / 深 #000000），模糊纯色得到的还是同一个纯色；
 *   · 底部导航栏的填充是 #F5F5F5 叠在 #F2F2F7 上 —— 只差 3/255，接近不透明；
 *   · 唯一会「漏」到玻璃下面的内容是滚动中的卡片，而设计稿把它画成了实色覆盖。
 *
 * 也就是说，画面上能看到的玻璃特征 100% 来自**边缘高光与描边**，不是模糊本身。
 * 在 Compose 里做真 backdrop 采样需要先把背景做成 Bitmap 再回贴（PixelCopy 或
 * 离屏渲染），而列表滚动时每帧都要重采样 —— 代价是丢帧，收益是零。
 * 所以这里用「高不透明度填充 + 上亮下暗的边缘渐变」，这是有意取舍，不是偷懒。
 *
 * 真要做的时候（比如以后背景换成图片或渐变），把这个 Modifier 换成带
 * RenderEffect 的实现即可，调用方不用动 —— 这也是把它单独抽成 Modifier 的原因。
 */
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(16.dp),
    fill: Color,
    strokeWidth: Dp = 0.75.dp,
    highlight: Color = Color.White.copy(alpha = 0.55f),
    shade: Color = Color.Black.copy(alpha = 0.06f),
    shadowElevation: Dp = 0.dp,
): Modifier = this
    .then(
        if (shadowElevation > 0.dp) {
            Modifier.shadow(
                elevation = shadowElevation,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.08f),
            )
        } else Modifier
    )
    .drawBehind {
        val outline: Outline = shape.createOutline(size, layoutDirection, this)
        // DrawScope 自己就是 Density，直接转 px；不要在这里读 LocalDensity ——
        // 那是 @Composable，普通 Modifier 函数里调不了（编译器会直接拒绝）
        val sw = strokeWidth.toPx()

        // 1) 底：半透明填充
        drawOutline(outline, fill)

        // 2) 边缘：上沿亮、下沿暗。
        //    这是玻璃「有厚度」的唯一线索 —— 去掉它，半透明色块会立刻显得扁平。
        drawOutline(
            outline = outline,
            brush = Brush.verticalGradient(
                0f to highlight,
                0.45f to Color.Transparent,
                0.72f to Color.Transparent,
                1f to shade,
            ),
            style = Stroke(width = sw),
        )
    }

/**
 * 玻璃容器。分组的卡片、浮动面板、导航栏都用它，保证材质只有一个真源。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    fill: Color = HzTheme.colors.glassFill,
    strokeWidth: Dp = 0.75.dp,
    shadowElevation: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .liquidGlass(
                shape = shape,
                fill = fill,
                strokeWidth = strokeWidth,
                shadowElevation = shadowElevation,
            )
            .clip(shape),
        content = content,
    )
}

/**
 * 圆形玻璃按钮（页面右上角的「+」）。
 *
 * 尺寸 56dp：设计稿量得，同时满足 48dp 最小热区。
 * 用 CircleShape 而不是 RoundedCornerShape(percent=50)：后者在非正方形约束下会变成胶囊。
 */
@Composable
fun GlassIconButton(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    tint: Color = HzTheme.colors.accent,
    onClick: () -> Unit,
) {
    val colors = HzTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .liquidGlass(
                shape = CircleShape,
                fill = colors.surface.copy(alpha = if (colors.isDark) 0.72f else 0.92f),
                highlight = Color.White.copy(alpha = if (colors.isDark) 0.22f else 0.90f),
            )
            .clip(CircleShape)
            .clickableBouncy(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.42f),
        )
    }
}

/**
 * 带按压缩放的点击。抽出来是因为全应用所有可点元素都要同一套手感：
 * 按下缩到 0.96、120ms 直进，松手用欠阻尼弹簧回弹 —— 数字落在技能给的区间内
 * （press 80–140ms、release 250–420ms）。
 *
 * 用 `indication = null` 是有意的：Material 的水波纹是 Android 的触感语言，
 * 与这套 iOS 观感冲突，按压缩放本身就是"这一下接住了"的反馈，不需要再来一层涟漪。
 */
fun Modifier.clickableBouncy(
    enabled: Boolean = true,
    scaleDown: Float = 0.96f,
    onClick: () -> Unit,
): Modifier = this.composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) scaleDown else 1f,
        animationSpec = if (pressed && enabled) {
            // 按下要"立刻"，手指必须马上得到回应
            tween(durationMillis = 120, easing = FastOutSlowInEasing)
        } else {
            // 回弹带一点点过冲：0.55 的阻尼比会轻微越过 1.0 再回来，
            // 这是 iOS 按压回弹的来源，完全临界阻尼（1.0）会显得发死
            spring(dampingRatio = 0.55f, stiffness = 520f)
        },
        label = "pressScale",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}
