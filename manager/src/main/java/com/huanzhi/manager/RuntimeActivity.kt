package com.huanzhi.manager

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.huanzhi.manager.core.CacheCleaner
import com.huanzhi.manager.core.CloneRuntime
import com.huanzhi.manager.core.CloneStore
import com.huanzhi.manager.core.CloneVersions
import com.huanzhi.manager.core.HardeningDetector
import com.huanzhi.manager.core.RuntimeProbe
import com.huanzhi.manager.core.UpdateKind
import com.huanzhi.manager.ui.components.MainTab
import com.huanzhi.manager.ui.nav.AppScaffold
import com.huanzhi.manager.ui.screen.RuntimeRowUi
import com.huanzhi.manager.ui.screen.RuntimeScreen
import com.huanzhi.manager.ui.screen.RuntimeUiState
import com.huanzhi.manager.ui.screen.resolveCloneStatus
import com.huanzhi.manager.ui.theme.HuanZhiTheme
import com.huanzhi.manager.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 分身运行概览。
 *
 * 3.7 起界面层迁到 Compose（与主页、设置页同一套 Liquid Glass 令牌）。这个 Activity
 * 只做三件事：**算数据、跑轮询、处理授权入口**。
 *
 * ── 为什么是 3 秒轮询而不是推送 ───────────────────────────────────────────────
 * 前台的判断依据是 UsageStats 的事件流，系统不提供"某个包刚进前台"的回调；
 * 而一次扫描只是查几个包的 StorageStats 与最近 2 小时的事件，代价很低。
 * 频率再高没有意义（UsageStats 自己就有几十毫秒的落盘延迟），再低则"刚刚启动"
 * 要等好几秒才反映出来。
 *
 * ── 为什么页面不可见就停 ─────────────────────────────────────────────────────
 * 轮询是在 `repeatOnLifecycle(STARTED)` 里跑的：切到后台自动停，回来自动续。
 * 不这么做的话，用户把应用切走以后每一轮扫描仍然会唤醒 CPU，一晚上几十次。
 */
class RuntimeActivity : AppCompatActivity() {

    /** 渲染状态。Compose 观察它，赋值即重绘 */
    private var uiState by mutableStateOf(RuntimeUiState())

    /**
     * 自动刷新开关放在 Activity 而不是 uiState 里。
     *
     * 它是**控制流**的状态（决定那个 while 循环要不要继续），不是渲染数据。
     * 放进 uiState 会让"改一下开关"和"刷新一次数据"走同一条路径 ——
     * 而前者要的是取消协程，后者要的是跑一轮，两者混在一起只会互相覆盖。
     */
    private var autoRefresh = true
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val root = ComposeView(this).apply {
            setContent {
                HuanZhiTheme {
                    AppScaffold(
                        selected = MainTab.Runtime,
                        onSelect = ::onTabSelected,
                        railTitle = getString(R.string.app_name),
                        railSubtitle = if (uiState.rows.isEmpty()) {
                            getString(R.string.home_summary_zero, uiState.rows.size)
                        } else {
                            getString(
                                R.string.home_summary,
                                uiState.rows.size,
                                Formats.size(uiState.totalOccupied),
                            )
                        },
                        railFooter = getString(
                            R.string.rail_footer,
                            Formats.knownSize(uiState.cacheBytes),
                        ),
                    ) {
                        RuntimeScreen(
                            state = uiState,
                            onRefresh = { restartPolling() },
                            onToggleAuto = ::setAutoRefresh,
                            onGrantUsage = {
                                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            },
                        )
                    }
                }
            }
        }
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        restartPolling()
    }

    override fun onStop() {
        super.onStop()
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * 重新开始轮询。
     *
     * 先取消再启动，而不是"如果没在跑就启动"：手动刷新和切开关都要立刻拿到**新一轮**
     * 的数据，而不是排在旧循环的下一次 delay 后面 —— 否则点「立即刷新」可能一秒半
     * 才看到变化，用户会以为按钮没生效。
     */
    private fun restartPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val next = withContext(Dispatchers.IO) { compute() }
                    if (isFinishing || isDestroyed) return@repeatOnLifecycle
                    uiState = next
                    // 关掉自动刷新时也跑一轮再停：屏幕上那一帧必须是"此刻"的，
                    // 否则关了开关反而留下一份更旧的数据，比不关还糟
                    if (!autoRefresh) break
                    delay(REFRESH_MS)
                }
            }
        }
    }

    private fun setAutoRefresh(on: Boolean) {
        if (autoRefresh == on) return
        autoRefresh = on
        uiState = uiState.copy(autoRefresh = on)
        if (on) restartPolling()
    }

    // ---------------------------------------------------------------- 数据

    private fun compute(): RuntimeUiState {
        val recs = runCatching { CloneStore(this).all() }.getOrDefault(emptyList())
        val runtimes = runCatching { RuntimeProbe.snapshot(this, recs) }.getOrDefault(emptyList())
        val granted = RuntimeProbe.hasUsageAccess(this)
        val cache = runCatching { CacheCleaner.scan(this).bytes }.getOrDefault(0L)
        val now = System.currentTimeMillis()

        // 按占用降序：这一页的标题就是「各分身占用」，排第一的必须是占得最多的那个。
        // 不按"运行中优先"排（主页那样做是因为主页要回答"谁活着"），
        // 这里混入运行状态会把大小顺序打乱，而分段条是按大小排的，两者会对不上
        val rows = runtimes
            .map { toRow(it, now) }
            .sortedByDescending { it.occupiedBytes }

        val fg = runtimes.firstOrNull { it.inForeground }

        return RuntimeUiState(
            loading = false,
            rows = rows,
            totalOccupied = runtimes.sumOf { it.occupiedBytes },
            foregroundCount = runtimes.count { it.inForeground },
            foregroundLabel = fg?.record?.label.orEmpty(),
            cacheBytes = cache,
            lastRefreshMs = now,
            autoRefresh = autoRefresh,
            usageGranted = granted,
        )
    }

    private fun toRow(rt: CloneRuntime, now: Long): RuntimeRowUi {
        val rec = rt.record
        val kind = runCatching { CloneVersions.stateOf(this, rec).kind }
            .getOrDefault(UpdateKind.UNKNOWN)
        val hardening = HardeningDetector.peek(rec.clonePkg)
        val lastUsed = rt.usage?.lastUsedMs ?: 0L

        val meta = when {
            !rt.installed -> getString(R.string.rt_row_missing)
            rt.inForeground -> getString(
                R.string.rt_row_foreground,
                Formats.duration(rt.usage?.foregroundMs ?: 0L),
            )
            lastUsed > 0L -> getString(R.string.rt_row_recent, Formats.relative(lastUsed, now))
            else -> getString(R.string.rt_row_never)
        }

        return RuntimeRowUi(
            key = rec.clonePkg,
            label = rec.label,
            pkg = rec.targetPkg,
            // 拿不到占用（没授权、或记录是导入的）报「—」而不是「0 B」——
            // 这一页的 0 是要被读成"真的没占地方"的
            sizeText = if (rt.occupiedBytes > 0L) Formats.compact(rt.occupiedBytes) else "—",
            meta = meta,
            status = resolveCloneStatus(
                installed = rt.installed,
                kind = kind,
                hardening = hardening,
                running = rt.running,
                lastUsedMs = lastUsed,
            ),
            foreground = rt.inForeground,
            occupiedBytes = rt.occupiedBytes,
        )
    }

    // ---------------------------------------------------------------- 导航

    private fun onTabSelected(tab: MainTab) {
        when (tab) {
            MainTab.Runtime -> Unit // 已经在概览
            MainTab.Clones -> {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
                finish()
            }
            MainTab.Settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                finish()
            }
        }
    }

    private companion object {
        const val REFRESH_MS = 3000L
    }
}
