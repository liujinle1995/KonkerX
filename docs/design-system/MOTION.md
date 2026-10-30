# 动效规范（Motion Spec）

> 这份文件是「幻之分身」所有过渡动画的唯一依据。改动画之前先看这里，
> 不要在 Activity 里随手写 `setDuration(300)`。
>
> 产出方式：按 `compose-motion-design` 技能的 Phase 1（Motion Design）八步流程定，
> 再按 Phase 2 的语义映射到 **View 体系**（本项目不是 Compose，见文末「框架适配」）。
> 色彩 / 间距 / 组件约定见同目录 `MASTER.md`。

---

## 1. 情绪目标

这个应用做的是**有后果的操作**：复制别人的 APK、覆盖安装、卸载会连数据一起删。
用户在这一屏上的心理需求不是「惊喜」，而是**「我还知道发生了什么、还要等多久、它结束了没有」**。

所以动效只服务三件事：

| 目标 | 做法 |
|---|---|
| **确定性** | 每个转场都有明确的起点和终点，不做无来由的漂浮 |
| **层级感** | 往深处走 = 从右侧进；返回 = 往右退出。方向永远和信息的上下层一致 |
| **不掩盖状态** | 转场永远不遮盖「正在安装 / 正在打包 / 失败」，慢操作必须有可见的进行中状态 |

反面清单（明确禁止）：

- 弹性过冲（overshoot）—— 数据类操作弹一下会让人怀疑刚才是不是误触了
- 循环呼吸 / 漂浮等装饰性环境动效
- 全屏白闪、硬切
- 超过 400ms 的转场（列表首次 cascade 总预算 500ms 是唯一例外）

## 2. 性格档位：Corporate

| 项 | 取值 |
|---|---|
| 时长区间 | 200–400ms（快 150 / 标准 250 / 慢 300） |
| 缓动家族 | cubic-bezier(0.2, 0, 0, 1) 一族 |
| 过冲 | 0–3%（本项目取 **0**） |
| 关键词 | clean、可预期、工具感 |

与 Material 3 的令牌体系同族，所以下面直接用 M3 的时长/缓动令牌命名，方便和组件库对齐。

## 3. 全局令牌

### 时长（对齐 `MotionTokens.Duration*`）

| 令牌 | 值 | 本项目用途 |
|---|---|---|
| `SHORT_2` | 100ms | 索引气泡出入、按压反馈 |
| `SHORT_3` | 150ms | 退出侧一律用它起步（FAB 收起、列表淡出） |
| `SHORT_4` | 200ms | 页面退出、对话框退出 |
| `MEDIUM_1` | 250ms | 列表项入场、FAB 出现、展开折叠、箭头旋转 |
| `MEDIUM_2` | 300ms | 页面进入、空态出现 |
| `MEDIUM_3` | 350ms | 批量条升起（唯一「重」一点的动作） |

**进总比出慢 30–50%**，这是全局硬规则。

### 缓动（对齐 `MotionTokens.Easing*CubicBezier`）

| 令牌 | 曲线 | 用途 |
|---|---|---|
| `EMPHASIZED_DECELERATE` | (0.05, 0.7, 0.1, 1) | 一切「进入」 |
| `EMPHASIZED_ACCELERATE` | (0.3, 0, 0.8, 0.15) | 一切「退出」 |
| `EMPHASIZED` | (0.2, 0, 0, 1) | 留在屏幕上的状态变化（展开、交换） |
| `STANDARD_DECELERATE` | (0, 0, 0, 1) | 透明度进入（比位移更平，避免位移缓动拖住淡入） |
| `STANDARD_ACCELERATE` | (0.3, 0, 1, 1) | 透明度退出 |

**透明度永远不跟着位移的缓动走**：位移用 emphasized，透明度用 standard。
两者混用会让淡入显得「卡一下」（技能里对应「effects 不能用 spatial 的弹性」）。

这条在实现上分两种情况：

- **窗口动画、对话框动画**写在 `res/anim/*.xml`，`<translate>` / `<scale>` 与 `<alpha>`
  各带自己的 `android:interpolator`，严格分曲线。
- **View 内部微动效**（列表项、FAB、索引气泡）只有一条 `ViewPropertyAnimator`，
  一个 animator 只能挂一条曲线。这些位移都在 24dp 以内，两条曲线的差异肉眼不可见，
  所以允许共用 `EMPHASIZED_DECELERATE`。超过 24dp 的位移不要用这种写法，改写成 XML。

## 4. 逐条转场

| # | 场景 | 主属性 | 时长 / 缓动 | 次级 | 编排 |
|---|---|---|---|---|---|
| 1 | **前进**（主页 → 选应用 / 运行概览 / 设置 / 关于 / 说明书） | 新页从右侧 6% 屏宽位移入场 | 300 / EMPH_DECEL | alpha 0→1（STANDARD_DECEL） | 旧页同步退场：左移 3% + 淡出 |
| 2 | **旧页被覆盖** | 左移 3% 屏宽 | 200 / EMPH_ACCEL | alpha 1→0 | 与 #1 同时开始，但更早结束 |
| 3 | **返回**（返回到上一页） | 上一页从 -3% 回到 0 | 250 / EMPH_DECEL | alpha 0→1 | 当前页右移 6% + 淡出 |
| 4 | **当前页退出** | 右移 6% 屏宽 | 200 / EMPH_ACCEL | alpha 1→0 | 见 #3 |
| 5 | **对话框出现** | 缩放 0.92 → 1（中心） | 250 / EMPH_DECEL | alpha 0→1 | 无背景压暗动画（系统 scrim 自带） |
| 6 | **对话框消失** | 缩放 1 → 0.96 | 200 / EMPH_ACCEL | alpha 1→0 | — |
| 7 | **空态 ↔ 列表** | 交叉淡入 | 出 150 / 进 300，都 EMPH | 空态 0.97→1 微缩放 | in 在 out 走完一半时进场（完全错开会留空帧） |
| 8 | **FAB 出现** | scale 0 → 1 | 250 / EMPH_DECEL | alpha 0→1 | 比列表晚 80ms（先看见内容，再看见动作） |
| 9 | **FAB 收起** | scale 1 → 0.8 | 150 / EMPH_ACCEL | alpha 1→0 | 立刻开始，不延迟 |
| 10 | **列表首次出现** | translateY 12dp → 0 | 250 / EMPH_DECEL，逐项 +40ms | alpha 0→1 | 最多级联 6 项，总预算 250+200=450ms < 500ms |
| 11 | **列表刷新**（已有内容，仅数据变化） | 不重放级联 | — | ItemAnimator 默认 250ms | 只在「首次从 0 到有」时级联 |
| 12 | **条目按压** | scale 1 → 0.98 | 100 / EMPH（按下）回到 1 用 150 | ripple 保留 | 与 ripple 叠加，不再改颜色 |
| 13 | **首字母气泡出现** | scale 0.85 → 1 | 100 / EMPH_DECEL | alpha 0→1 | 快速滑动时不能有延迟感 |
| 14 | **首字母气泡消失** | scale 1 → 0.85 | 100 / EMPH_ACCEL | alpha 1→0 | 松手后 450ms 才收 |
| 15 | **多选批量条升起** | translateY 24dp → 0 | 350 / EMPH_DECEL | alpha 0→1 | 收起 200 / EMPH_ACCEL |
| 16 | **更新记录展开** | 高度变化（ChangeBounds） | 250 / EMPH | 内容淡入 | 箭头旋转 0↔180 用 250 / EMPH，与高度同长 |
| 17 | **切主题** | 整窗淡出 → 重建 → 淡入 | 出 90 / accel，进 210 / decel | — | fade-through：旧界面先走，新界面后到，中间不留空白帧 |
| 18 | **冷启动闪屏** | 图标放大到 1.12 并淡出 | 180 / EMPH_ACCEL | 内容同时淡入 | 交给系统 SplashScreen，只接手退出那一段 |

### 编排规则（来自技能 `choreography.md` / `1/3 规则`）

1. **位移不超过屏宽的 1/6**（6%），超过就要加中间关键帧 —— 本项目的页面转场刻意不用整屏滑动，
   因为「主页 ↔ 选应用」是高频来回的路径，整屏滑会显得慢。
2. **同一时刻最多两层在动**（主 + 次）。列表 cascade 是唯一的第三层，且只在首屏发生一次。
3. **hero 只有一个**：页面上有 FAB 时，FAB 是 hero，列表让位（因此 FAB 晚 80ms 出现）。
4. **方向可预测**：同一层级（设置 ↔ 关于）用同样的前进/返回方向，不发明新的轴。

## 5. 框架适配（Compose → View）

技能 Phase 2 的产出是 Compose 代码。本项目是 **XML + Activity + ViewBinding**，
所以只借用它的决策层，实现层按下表映射，语义一一对应：

| Compose | 本项目用法 |
|---|---|
| `MotionTokens.Duration*` | `ui/motion/Motion.kt` 里的 `Dur` 常量 |
| `CubicBezierEasing(...)` | `PathInterpolator(...)`，同时导出成 `res/interpolator/*.xml` 给窗口动画用 |
| `AnimatedVisibility` + `fadeIn`/`slideIn` | `ViewPropertyAnimator`（`Motion.fadeSlideIn` / `fadeSlideOut`） |
| `Crossfade` | `Motion.crossFade(old, new)`：先出后进 |
| `updateTransition` | `TransitionManager.beginDelayedTransition`（展开折叠） |
| `MaterialSharedAxis` | `android:windowAnimationStyle` 的四个窗口动画（前进/退场/返回/退出） |
| `MotionScheme.defaultEffectsSpec()` | 不位移的属性（alpha）单独用 STANDARD 缓动 |

**为什么用 `windowAnimationStyle` 而不是逐个 Activity 调 `overridePendingTransition`**：
四个窗口动画一次配好，所有页面（含以后新增的）自动一致；`overridePendingTransition(0,0)`
那种散落写法会导致「某个页面忘了加」的漏网。API 34+ 的 `overrideActivityTransition` 也只在
需要不对称动画时才用，本项目不需要。

**预测式返回**：`targetSdk 37` 下系统默认接管返回手势的动画。此时 #3/#4 由系统播放，
我们只保证前进方向（#1/#2）一致；两条路径的时长都在 200–300ms 内，观感不会打架。

## 6. 验收清单

交付前逐条过：

- [ ] 任何 `setDuration(...)` 的入参都来自 `Dur` 常量，没有裸数字
- [ ] 每个「进入」用 DECELERATE，每个「退出」用 ACCELERATE，没有共用一个曲线
- [ ] 退出时长 ≤ 进入时长
- [ ] 透明度没有跟着位移的缓动走
- [ ] 重复 `onResume` 不会重放动画（helper 内部要判断「目标状态 == 当前状态」就直接返回）
- [ ] 系统「移除动画」开关打开时，所有动画退化为瞬时（`Settings.Global.ANIMATOR_DURATION_SCALE == 0`）
- [ ] 列表 cascade 只在首次出现时跑，滚动 / 刷新不重放
- [ ] 慢操作（打包、安装）在动画期间不会被遮住状态
