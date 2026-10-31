package com.huanzhi.manager.ui.dialog

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.huanzhi.manager.R
import com.huanzhi.manager.core.CloneStore
import com.huanzhi.manager.core.CloneWorkflow
import com.huanzhi.manager.core.InstallHint
import com.huanzhi.manager.core.InstallLog
import com.huanzhi.manager.core.LogEvent
import com.huanzhi.manager.ui.adaptive.WindowSizeClasses
import com.huanzhi.manager.ui.screen.LogItemUi
import com.huanzhi.manager.ui.screen.LogRowState
import com.huanzhi.manager.ui.screen.LogSheet
import com.huanzhi.manager.ui.screen.LogTab
import com.huanzhi.manager.ui.screen.LogUiState
import com.huanzhi.manager.ui.theme.HuanZhiTheme
import com.huanzhi.manager.ui.theme.HzTheme
import com.huanzhi.manager.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 安装日志弹层。
 *
 * ── 手机底部抽屉，平板居中 ──────────────────────────────────────────────
 * 同一个内容换两种壳，依据不是「设备」，而是**哪一种更像在原来的页面上掀开一层**：
 * 手机上从底部推上来，拇指够得着、也能往下拖回去；平板上没有「底部」这个方向，
 * 居中一张 620dp 的板才是这个尺寸下的常规做法（design/18、30 也是这么画的）。
 *
 * ── 为什么这一页不是整页 ────────────────────────────────────────────────
 * 构建进度页之所以做成整页，是因为它是**正在进行时**，用户要在上面等与操作；
 * 安装日志是**回看**，看完就关，底下的列表还在等着。做成整页等于多一次页面跳转、
 * 多一层返回栈，用户还要重新找到刚才那一行。
 */
private const val SHEET_MAX_WIDTH = 620
private const val PHONE_HEIGHT_FRACTION = 0.88f
private const val TABLET_HEIGHT_FRACTION = 0.80f

fun Context.showInstallLog(events: List<LogEvent>): Dialog {
    val dialog = ComponentDialog(this, R.style.Theme_HuanZhi_GlassDialog)
    val content = ComposeView(dialog.context).apply {
        setContent {
            HuanZhiTheme {
                InstallLogSheet(
                    events = events,
                    dismiss = { dialog.dismiss() },
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
private fun InstallLogSheet(events: List<LogEvent>, dismiss: () -> Unit) {
    val ctx = LocalContext.current
    val colors = HzTheme.colors
    val wide = WindowSizeClasses.canShowTwoPane(ctx)
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(LogTab.All) }
    // 进来先算一次就够：这份弹层是只读快照，装完再进来会重新读
    val all = remember(events) { events.map { it.toUi(ctx) } }
    val okCount = all.count { it.state == LogRowState.Ok }
    val failedCount = all.size - okCount
    val shown = when (tab) {
        LogTab.All -> all
        LogTab.Failed -> all.filter { it.state != LogRowState.Ok }
        LogTab.Ok -> all.filter { it.state == LogRowState.Ok }
    }

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(260, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)),
        label = "logSheetIn",
    )

    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val scrim = Color.Black.copy(alpha = if (colors.isDark) 0.56f else 0.32f)
    val inert = remember { MutableInteractionSource() }
    val shape = if (wide) RoundedCornerShape(28.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val maxH = screenH * (if (wide) TABLET_HEIGHT_FRACTION else PHONE_HEIGHT_FRACTION)

    Box(
        Modifier
            .fillMaxSize()
            .background(scrim.copy(alpha = scrim.alpha * progress))
            .clickable(interactionSource = inert, indication = null) { dismiss() },
        contentAlignment = if (wide) Alignment.Center else Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .then(if (wide) Modifier.width(SHEET_MAX_WIDTH.dp) else Modifier.fillMaxWidth())
                // 固定高度，不跟着内容缩：切换筛选时如果整张板子矮下去一截，
                // 手指要点的位置就位移了 —— 而点空正好命中遮罩、把弹层一并关掉
                .height(maxH)
                .graphicsLayer {
                    // 手机从底部推上来，平板则是原地放大一点点 —— 平移在居中的板子上
                    // 会显得"从下面掉出来"，那是抽屉的语汇，用错了地方
                    translationY = if (wide) 0f else size.height * (1f - progress)
                    alpha = if (wide) progress else 1f
                    scaleX = if (wide) 0.96f + 0.04f * progress else 1f
                    scaleY = if (wide) 0.96f + 0.04f * progress else 1f
                }
                .clip(shape)
                .background(colors.logSheet)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .semantics { paneTitle = ctx.getString(R.string.log_title) }
                .then(if (wide) Modifier.statusBarsPadding() else Modifier.navigationBarsPadding()),
        ) {
            LogSheet(
                state = LogUiState(
                    wide = wide,
                    items = shown,
                    tab = tab,
                    total = all.size,
                    failed = failedCount,
                    ok = okCount,
                    emptyTitle = ctx.getString(R.string.log_empty),
                    emptyHint = ctx.getString(R.string.log_empty_hint),
                ),
                onClose = dismiss,
                onCopy = {
                    copyLog(ctx, all)
                    toast(ctx, ctx.getString(R.string.log_copied))
                },
                onTab = { tab = it },
                onRetry = { item ->
                    val ev = events.firstOrNull { it.uiKey() == item.key }
                    if (ev != null) {
                        retry(ctx, ev, scope) { dismiss() }
                    }
                },
            )
        }
    }
}

/**
 * 「重试这一份」= 把当初留下的那个包重新提交一遍，**不重新打包**。
 *
 * 装失败的那一份，包在 [com.huanzhi.manager.core.BatchRunner] 里是特意留着的：
 * 重打包一个 500MB 的应用要半分钟，重提交只要一两秒，而失败的那一份恰恰最可能再试一次。
 *
 * 记录在失败时就已经被撤掉了（列表里不该堆一串「未安装」），所以重提交成功后要补回去，
 * 否则会出现「系统里装着、列表里没有」这种最容易被当成 bug 的状态。
 */
private fun retry(ctx: Context, ev: LogEvent, scope: kotlinx.coroutines.CoroutineScope, onDone: () -> Unit) {
    val rec = ev.snapshot?.toRecord()
    val apk = File(ev.cachePath)
    if (rec == null || ev.cachePath.isEmpty() || !apk.exists()) {
        toast(ctx, ctx.getString(R.string.log_retry_missing))
        return
    }
    scope.launch {
        val app = ctx.applicationContext
        val ok = withContext(Dispatchers.IO) {
            runCatching { CloneWorkflow(app).install(rec) }.getOrDefault(false)
        }
        if (!ok) {
            toast(ctx, ctx.getString(R.string.log_retry_missing))
            return@launch
        }
        withContext(Dispatchers.IO) { CloneStore(app).add(rec) }
        toast(ctx, ctx.getString(R.string.log_retry_submitted))
        // 系统安装框已经盖在上面了，弹层留在底下只会挡住刚弹出来的东西
        onDone()
    }
}

/**
 * 复制出去的东西分两段：先给人看的那份（和屏幕上一致），再附原始日志。
 *
 * 只复制卡片的话，对方拿到的是一段读得懂但无法定位的叙述；
 * 只复制原始日志的话，用户自己回看是一堆 pkg/status。两段都带上才既能读又能查。
 */
private fun copyLog(ctx: Context, items: List<LogItemUi>) {
    val text = buildString {
        items.forEach { item ->
            append(item.time).append(' ').append(item.badge).append(' ')
            append(item.title).append('\n')
            append("  ").append(item.meta).append('\n')
            if (item.code.isNotEmpty()) append("  ").append(item.code).append('\n')
            if (item.body.isNotEmpty()) append("  ").append(item.body).append('\n')
            if (item.footer.isNotEmpty()) append("  ").append(item.footer).append('\n')
        }
        val raw = InstallLog.tail(ctx, 200)
        if (raw.isNotBlank()) {
            append("\n—— 原始日志 ——\n").append(raw)
        }
    }
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    cm?.setPrimaryClip(ClipData.newPlainText("幻之分身安装日志", text))
}

private fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

/** 列表项的稳定 key：同一毫秒可能落两条，包名补上就够区分了 */
private fun LogEvent.uiKey(): String = "$at|$clonePkg|$kind"

private val hm = DateTimeFormatter.ofPattern("HH:mm")
private val md = DateTimeFormatter.ofPattern("M月d日 HH:mm")

private fun LogEvent.toUi(ctx: Context): LogItemUi {
    val zone = ZoneId.systemDefault()
    val when_ = Instant.ofEpochMilli(at).atZone(zone)
    val today = LocalDate.now(zone)
    val time = when (when_.toLocalDate()) {
        today -> ctx.getString(R.string.log_time_today, when_.format(hm))
        today.minusDays(1) -> ctx.getString(R.string.log_time_yesterday, when_.format(hm))
        else -> when_.format(md)
    }

    val state = when (kind) {
        LogEvent.Kind.Installed -> LogRowState.Ok
        LogEvent.Kind.Failed -> LogRowState.Fail
        LogEvent.Kind.Cancelled -> LogRowState.Cancel
        LogEvent.Kind.Skipped -> LogRowState.Skip
    }
    val badge = ctx.getString(
        when (state) {
            LogRowState.Ok -> R.string.log_badge_ok
            LogRowState.Fail -> R.string.log_badge_failed
            LogRowState.Cancel -> R.string.log_badge_cancelled
            LogRowState.Skip -> R.string.log_badge_skipped
        }
    )

    // 默认名是「应用名 · 分身N」，里面已经带了一遍应用名，再拼一次就成了
    // 「微信 · 微信 · 分身1」。只有用户自己起的名字才需要把应用名补在前面
    val title = when {
        appLabel.isBlank() -> cloneName
        cloneName.startsWith(appLabel) -> cloneName
        else -> "$appLabel · $cloneName"
    }

    // 装成的那一份关心「花了多久、有多大」；没装成的那一份关心「这是哪个应用的哪个包」
    val meta = if (state == LogRowState.Ok) {
        listOfNotNull(
            clonePkg,
            buildMs.takeIf { it > 0 }?.let { ctx.getString(R.string.log_meta_build, short(it)) },
            installMs.takeIf { it > 0 }?.let { ctx.getString(R.string.log_meta_install, short(it)) },
            bytes.takeIf { it > 0 }?.let { Formats.size(it) },
        ).joinToString(" · ")
    } else {
        if (sourcePkg.isBlank()) clonePkg else ctx.getString(R.string.log_meta_pkg, clonePkg, sourcePkg)
    }

    val body = when (state) {
        LogRowState.Ok -> ""
        LogRowState.Cancel -> ctx.getString(R.string.log_cancel_note)
        LogRowState.Skip -> ctx.getString(R.string.log_skip_note)
        LogRowState.Fail -> InstallHint.bodyOf(ctx, reason, ctx.getString(R.string.bp_failed_body))
    }

    val kept = cachePath.isNotEmpty() && File(cachePath).exists()
    return LogItemUi(
        key = uiKey(),
        time = time,
        title = title,
        meta = meta,
        state = state,
        badge = badge,
        code = if (state == LogRowState.Fail) code else "",
        body = body,
        footer = if (kept) ctx.getString(R.string.log_cache_kept, Formats.size(bytes)) else "",
        // 只有「包还在、记录还认得出」的时候重试才有意义：包没了就只能重新创建，
        // 而重新创建要走选应用那一整套流程，不该藏在这个按钮底下
        canRetry = kept && state != LogRowState.Ok && snapshot != null,
    )
}

/** 「7 秒」「2 分 18 秒」—— 安装耗时大多在秒级，报成分钟就全是一样的数 */
private fun short(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    if (sec < 60) return "${sec.coerceAtLeast(1)} 秒"
    val m = sec / 60
    val s = sec % 60
    return if (s == 0L) "$m 分" else "$m 分 $s 秒"
}
