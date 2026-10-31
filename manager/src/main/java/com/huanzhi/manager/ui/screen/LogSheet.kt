package com.huanzhi.manager.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanzhi.manager.R
import androidx.compose.ui.res.stringResource
import com.huanzhi.manager.ui.glass.clickableBouncy
import com.huanzhi.manager.ui.theme.HzTheme

/**
 * 安装日志弹层的内容。
 *
 * ── 为什么不是「一堆等宽文本」 ───────────────────────────────────────────
 * 3.10 之前这一个入口打开的是 `InstallLog` 的原始文本：`br recv pkg=… status=-25
 * FAIL reason=签名与已装版本冲突（INSTALL_FAILED_UPDATE_INCOMPATIBLE）`。
 * 对排查有用，对用户是天书 —— 他打开了、看不懂、关掉，然后去群里问「为什么装不上」。
 *
 * 所以分成两层：这个界面是**给人看的**（一份一张卡，说清楚是什么应用、哪一份、
 * 什么时候、成了没有、为什么没成），原始文本仍然留着，由「复制」整段带走。
 *
 * ── 三个分段的数字必须对得上 ─────────────────────────────────────────────
 * 「全部 / 失败 / 成功」里，取消和跳过都算失败：它们的共同点是**这一份现在不在系统里**。
 * 要是把它们单独拎出去，就会出现「全部 12 / 失败 2 / 成功 10」而列表里明明还有
 * 一张「已取消」的情况 —— 用户第一眼就会怀疑这个页面在瞎报数。
 *
 * ── 尺寸 ────────────────────────────────────────────────────────────────
 * 逐点取自 design/15、18、24、30（375×812pt @4x，1dp = 4px）：
 * 卡片圆角 12、卡内边距 12、卡间距 8、徽标 22 高圆角 6、分段条 34 高、
 * 失败卡错误块圆角 8 内边距 9/10、重试胶囊 32 高。
 * 手机内容边距 16，平板 24（弹层宽 620）。
 */

/** 一张卡的状态。跳过与取消都「没装成」，但文案和颜色要分开 —— 一个是我不要，一个是被拒 */
enum class LogRowState { Ok, Fail, Cancel, Skip }

/** 分段筛选 */
enum class LogTab { All, Failed, Ok }

@Immutable
data class LogItemUi(
    val key: String,
    val time: String,
    val title: String,
    val meta: String,
    val state: LogRowState,
    val badge: String,
    /** 失败时的错误码，等宽显示；其它状态为空 */
    val code: String,
    /** 错误块里的那句中文：优先「接下来怎么办」 */
    val body: String,
    /** 底部左半句：缓存包还在不在 */
    val footer: String,
    /** 底部右半句的按钮要不要出现 */
    val canRetry: Boolean,
)

@Immutable
data class LogUiState(
    val wide: Boolean,
    /** 筛选后的卡片；筛选本身在 Activity 里做，这里只管画 */
    val items: List<LogItemUi> = emptyList(),
    val tab: LogTab = LogTab.All,
    val total: Int = 0,
    val failed: Int = 0,
    val ok: Int = 0,
    val emptyTitle: String = "",
    val emptyHint: String = "",
)

private val CardShape = RoundedCornerShape(12.dp)
private val SegmentHeight = 34.dp

@Composable
fun LogSheet(
    state: LogUiState,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onTab: (LogTab) -> Unit,
    onRetry: (LogItemUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HzTheme.colors
    val pad = if (state.wide) 24.dp else 16.dp

    // 撑满外层给的高度，而不是跟着内容缩：切换筛选时如果整张弹层在眼前短一截，
    // 手指第二次点就落空了（点空正好又命中遮罩把弹层关掉）
    Column(modifier.fillMaxSize()) {
        // 抓手：手机上它同时是「可以往下拖」的暗示；平板居中弹层里纯粹是稿面的一部分，
        // 但两处画成一样，用户才不会以为自己拿错了设备
        Box(Modifier.fillMaxWidth().padding(top = if (state.wide) 20.dp else 8.dp), Alignment.Center) {
            Box(
                Modifier
                    .size(width = 36.dp, height = 5.dp)
                    .clip(CircleShape)
                    .background(colors.textTertiary.copy(alpha = 0.6f))
            )
        }

        Spacer(Modifier.height(20.dp))
        Header(onClose = onClose, onCopy = onCopy, pad = pad)
        Spacer(Modifier.height(17.dp))
        LogSegments(
            tab = state.tab,
            total = state.total,
            failed = state.failed,
            ok = state.ok,
            onTab = onTab,
            modifier = Modifier.padding(horizontal = pad),
        )
        Spacer(Modifier.height(10.dp))

        if (state.items.isEmpty()) {
            EmptyLog(state.emptyTitle, state.emptyHint, Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = pad,
                    end = pad,
                    // 平板是居中板子，最后一张卡贴着圆角边会看着像被裁掉；手机抽屉通到屏底，不需要留这么多
                    bottom = if (state.wide) 24.dp else 20.dp,
                ),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                items(state.items, key = { it.key }) { item ->
                    LogCard(item = item, onRetry = { onRetry(item) })
                }
            }
        }
    }
}

@Composable
private fun Header(onClose: () -> Unit, onCopy: () -> Unit, pad: androidx.compose.ui.unit.Dp) {
    val colors = HzTheme.colors
    val closeLabel = stringResource(R.string.log_close)
    Box(Modifier.fillMaxWidth().padding(horizontal = pad)) {
        // 标题用 Box 居中而不是 Row 里的 weight：右边「复制」是两个字、左边是 28dp 圆钮，
        // 用 weight 摆会把标题推得偏离中轴
        Text(
            text = stringResource(R.string.log_title),
            color = colors.textPrimary,
            style = HzTheme.type.cardTitle,
            modifier = Modifier.align(Alignment.Center),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .size(28.dp)
                .clip(CircleShape)
                .background(colors.fill)
                .clickableBouncy(onClick = onClose)
                .semantics { contentDescription = closeLabel },
            contentAlignment = Alignment.Center,
        ) {
            CloseCross(color = colors.textSecondary)
        }
        Text(
            text = stringResource(R.string.log_copy),
            color = colors.accent,
            style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .clickableBouncy(onClick = onCopy)
                .padding(horizontal = 2.dp, vertical = 4.dp),
        )
    }
}

/** 两条 1.5dp 的对角线。用画的而不是图标字体：只有这一处用到叉，为它引一个图标集不划算 */
@Composable
private fun CloseCross(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
        val w = size.width
        val sw = 1.5.dp.toPx()
        drawLine(color, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(w, w), sw)
        drawLine(color, androidx.compose.ui.geometry.Offset(w, 0f), androidx.compose.ui.geometry.Offset(0f, w), sw)
    }
}

@Composable
private fun LogSegments(
    tab: LogTab,
    total: Int,
    failed: Int,
    ok: Int,
    onTab: (LogTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HzTheme.colors
    val entries = listOf(
        LogTab.All to (stringResource(R.string.log_tab_all) to total),
        LogTab.Failed to (stringResource(R.string.log_tab_failed) to failed),
        LogTab.Ok to (stringResource(R.string.log_tab_ok) to ok),
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(SegmentHeight)
            .clip(RoundedCornerShape(9.dp))
            .background(colors.fill)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEach { (t, pair) ->
            val sel = t == tab
            val label = pair.first
            val count = pair.second
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height(SegmentHeight - 4.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (sel) colors.controlSelected else Color.Transparent)
                    .clickableBouncy(onClick = { onTab(t) })
                    .semantics {
                        selected = sel
                        contentDescription = label
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        color = if (sel) colors.textPrimary else colors.textSecondary,
                        style = TextStyle(fontSize = 13.sp, lineHeight = 16.sp),
                        maxLines = 1,
                    )
                    // 数字比标签重一档：这三个数才是用户真正在看的（「有没有失败」）
                    Text(
                        text = " $count",
                        color = if (sel) colors.textPrimary else colors.textSecondary,
                        style = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogCard(item: LogItemUi, onRetry: () -> Unit) {
    val colors = HzTheme.colors
    val stroke = when (item.state) {
        LogRowState.Fail -> colors.logFailStroke
        LogRowState.Cancel -> colors.logWarnStroke
        // 跳过不是取消。取消是「不要了」，跳过是「先放着，包还在」——
        // 所以跳过用主色蓝：蓝在这套语言里就是「还能点、还欠一次操作」，
        // 稿面上那个「重试这一份」按钮也是这个颜色
        LogRowState.Skip -> colors.accent
        LogRowState.Ok -> Color.Transparent
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(colors.surface)
            .then(if (stroke == Color.Transparent) Modifier else Modifier.border(1.dp, stroke, CardShape))
    ) {
        Column(Modifier.padding(12.dp)) {
            // 元信息行：圆点 + 时间（左）／徽标（右）
            Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor(item.state)))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = item.time,
                    color = colors.textSecondary,
                    style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                LogBadge(text = item.badge, state = item.state)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.title,
                color = colors.textPrimary,
                style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.meta,
                color = colors.textSecondary,
                style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (item.body.isNotEmpty()) {
                val isFail = item.state == LogRowState.Fail
                Spacer(Modifier.height(if (isFail) 8.dp else 6.dp))
                if (isFail && item.code.isNotEmpty()) {
                    ErrorBlock(code = item.code, body = item.body)
                } else {
                    Text(
                        text = item.body,
                        color = colors.textSecondary,
                        style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                    )
                }
            }

            if (item.footer.isNotEmpty() || item.canRetry) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.footer,
                        color = colors.textSecondary,
                        style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.canRetry) {
                        Spacer(Modifier.width(8.dp))
                        RetryPill(onClick = onRetry)
                    }
                }
            }
        }
    }
}

/**
 * 错误块：上面一行系统错误码，下面一段人话。
 *
 * 错误码用等宽字重一档、饱和度更高——它是能拿去搜、拿去贴给别人的东西；
 * 说明文字同色系但更淡，读得清但不跟错误码抢第一眼。
 */
@Composable
private fun ErrorBlock(code: String, body: String) {
    val colors = HzTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.logFailFill)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Text(
            text = code,
            color = colors.logFailText,
            style = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = body,
            color = colors.logFailBody,
            style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
        )
    }
}

@Composable
private fun LogBadge(text: String, state: LogRowState) {
    val colors = HzTheme.colors
    val bg = when (state) {
        LogRowState.Fail -> colors.logFailBadge
        LogRowState.Cancel -> colors.logWarnBadge
        LogRowState.Skip -> colors.badgeBusyBg
        LogRowState.Ok -> colors.badgeDoneBg
    }
    val fg = when (state) {
        LogRowState.Fail -> colors.logFailText
        LogRowState.Cancel -> colors.logWarnText
        LogRowState.Skip -> colors.accent
        LogRowState.Ok -> colors.green
    }
    Box(
        Modifier
            .height(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = fg,
            style = TextStyle(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
}

@Composable
private fun RetryPill(onClick: () -> Unit) {
    val colors = HzTheme.colors
    Box(
        Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(colors.accent)
            .clickableBouncy(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.log_retry),
            color = Color.White,
            style = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptyLog(title: String, hint: String, modifier: Modifier = Modifier) {
    val colors = HzTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp, bottom = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            color = colors.textPrimary,
            style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = hint,
            color = colors.textSecondary,
            style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private fun dotColor(state: LogRowState): Color = when (state) {
    LogRowState.Fail -> Color(0xFFFF3B30)
    LogRowState.Cancel -> Color(0xFFFF9500)
    LogRowState.Skip -> Color(0xFF007AFF)
    LogRowState.Ok -> Color(0xFF34C759)
}
