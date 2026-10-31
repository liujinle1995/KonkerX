package com.huanzhi.manager.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.huanzhi.manager.R
import com.huanzhi.manager.core.AppScanner
import com.huanzhi.manager.ui.glass.GlassSurface
import com.huanzhi.manager.ui.glass.clickableBouncy
import com.huanzhi.manager.ui.theme.HzTheme

/**
 * 把应用图标取成 Bitmap 供 Compose 绘制。
 *
 * 为什么不用 Coil/Accompanist：图标唯一来源是 PackageManager，本来就在内存里，
 * 引一个图片加载库只多一层缓存与线程调度。这里按 (包名, 像素尺寸) 记忆化，
 * 列表滚动时同一行重复组合不会重复解码。
 *
 * 落成固定尺寸 Bitmap 而不是直接画 Drawable：AdaptiveIconDrawable 的 bounds
 * 行为在各 API 上不完全一致，先画到固定画布再统一 Crop，才不会出现
 * 「某些 ROM 上图标偏左」这种只在真机复现的问题。
 */
@Composable
fun rememberAppIcon(pkg: String?, size: Dp): ImageBitmap? {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    return remember(pkg, px) {
        if (pkg == null || px <= 0) return@remember null
        runCatching {
            val d = AppScanner.iconOf(ctx, pkg) ?: return@runCatching null
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, px, px)
            d.draw(AndroidCanvas(bmp))
            bmp.asImageBitmap()
        }.getOrNull()
    }
}

/**
 * 应用图标底座：圆角方形。
 *
 * 设计稿里图标本身已是彩色圆角方块（微信绿、淘宝橙），所以**不额外垫品牌色**，
 * 只有取不到图标时才露出 iconTileBg 占位 —— 多垫一层会让第三方图标
 * 看起来"外面套了个框"，一眼假。
 */
@Composable
fun AppIconTile(
    pkg: String?,
    modifier: Modifier = Modifier,
    size: Dp = HzTheme.dimen.iconSize,
    corner: Dp = HzTheme.dimen.iconCorner,
    /** 取不到图标时显示的首字（通常是应用名）。不给就只留一个中性色块 */
    fallbackLabel: String? = null,
) {
    val icon = rememberAppIcon(pkg, size)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(HzTheme.colors.iconTileBg),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 一个问号会被读成「加载失败了」；显示应用名首字则是「这个应用的图标
            // 取不到，但我知道它是谁」—— 后者更接近事实（常见于源应用已卸载）
            Text(
                text = fallbackLabel?.trim()?.take(1).orEmpty(),
                color = HzTheme.colors.textSecondary,
                style = HzTheme.type.cardTitle,
            )
        }
    }
}

/**
 * 顶部统计徽章（「1 个正在运行」「1 个可更新」）。
 *
 * 圆点是在给「有 N 个」这件事一个视觉锚点：纯文字胶囊会和按钮长得一样，
 * 用户会伸手去点它。
 */
@Composable
fun StatusPill(
    text: String,
    dotColor: Color,
    background: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(5.dp))
        Text(text = text, color = dotColor, style = HzTheme.type.badge)
    }
}

/**
 * 卡片底：填充 + 可选描边。
 *
 * 刻意不复用 [com.huanzhi.manager.ui.glass.liquidGlass]：那个带顶部高光，
 * 适合浮层与导航栏；而一列分组卡片每张都顶着一道亮边时，会被读成"分隔线"，
 * 分组感反而散了。
 */
private fun Modifier.cardSurface(
    fill: Color,
    border: Color,
    corner: Dp,
    borderWidth: Dp = 1.5.dp,
): Modifier = drawBehind {
    val outline = RoundedCornerShape(corner).createOutline(size, layoutDirection, this)
    drawOutline(outline, fill)
    if (border != Color.Transparent) {
        drawOutline(outline, border, style = Stroke(borderWidth.toPx()))
    }
}

/**
 * 分身卡片。
 *
 * 88dp 高 / 16dp 圆角 / 16dp 内边距全部量自设计稿。
 * 右侧是「状态文字 + 细箭头」而不是图标按钮：这一行的主操作是点开它，
 * 再放一个按钮会让「点哪里」从一条路变成两条。
 */
@Composable
fun CloneCard(
    label: String,
    meta: String,
    statusText: String,
    statusColor: Color,
    iconPkg: String?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(d.cardHeight)
            .cardSurface(
                fill = colors.surface,
                border = if (selected) colors.accent else Color.Transparent,
                corner = d.cardCorner,
            )
            .clip(RoundedCornerShape(d.cardCorner))
            .clickableBouncy(onClick = onClick)
            .padding(start = d.cardPaddingStart, end = d.cardPaddingEnd)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = "$label，$meta，$statusText"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconTile(pkg = iconPkg, fallbackLabel = label)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                color = colors.textPrimary,
                style = HzTheme.type.cardTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = meta,
                color = colors.textSecondary,
                style = HzTheme.type.cardMeta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = statusText,
            color = statusColor,
            style = HzTheme.type.status,
            maxLines = 1,
            // 文案本身已压到 4 字以内；省略号是最后一道保险 ——
            // 万一以后加了更长的状态词，宁可截断也不能把箭头挤出卡片
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(4.dp))
        Chevron(color = colors.textTertiary)
    }
}

/**
 * 右侧细箭头。
 *
 * 自己画而不用图标资源：设计稿的 chevron 是细线、开口约 60°，
 * Material 的 chevron 更粗更宽，摆在 13sp 状态文字旁边会粗一档。
 */
@Composable
fun Chevron(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.34f, h * 0.22f)
            lineTo(w * 0.66f, h * 0.50f)
            lineTo(w * 0.34f, h * 0.78f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = this.size.minDimension * 0.13f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/** 分组小标题（「我的分身」）。13sp 半粗，与卡片标题 17sp 拉开两档 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = HzTheme.colors.textSecondary,
        style = HzTheme.type.section,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------- 底部导航

/** 一级页签。设计稿三个，与现有三个 Activity 一一对应 */
enum class MainTab(val labelRes: Int, val iconRes: Int) {
    Clones(R.string.tab_clones, R.drawable.ic_grid),
    Runtime(R.string.tab_runtime, R.drawable.ic_chart),
    Settings(R.string.tab_settings, R.drawable.ic_settings),
}

/**
 * 底部玻璃胶囊导航。
 *
 * 三个页签等宽（weight 1f）而不是按文字宽度自适应：文字长短不一时，
 * 选中胶囊会一会儿宽一会儿窄，切换时看着在跳。
 */
@Composable
fun GlassTabBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HzTheme.colors
    val d = HzTheme.dimen
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(d.tabBarHeight),
        shape = RoundedCornerShape(d.tabBarCorner),
        fill = colors.glassFill,
        shadowElevation = 12.dp,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainTab.entries.forEach { tab ->
                val sel = tab == selected
                val label = stringResource(tab.labelRes)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(29.dp))
                        .background(if (sel) colors.navSelectedBg else Color.Transparent)
                        .clickableBouncy(onClick = { onSelect(tab) })
                        .clearAndSetSemantics {
                            contentDescription = label
                            this.selected = sel
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(tab.iconRes),
                            contentDescription = null,
                            tint = if (sel) colors.accent else colors.textSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = label,
                            color = if (sel) colors.accent else colors.textSecondary,
                            style = HzTheme.type.tab,
                        )
                    }
                }
            }
        }
    }
}
