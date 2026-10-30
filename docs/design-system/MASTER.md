# 幻之分身 · 设计系统（Master）

> 本文件是界面层的唯一真理源。改动颜色、间距、触摸目标、反馈策略，先改这里，再改代码。
> 页面级例外写到 `pages/<页面>.md`，只记录与 Master 的差异。
>
> **动效（时长、缓动、逐条转场）不在这里**，见同目录 `MOTION.md` —— 那份文件按
> `compose-motion-design` 技能的方法产出，管的是「界面怎么动」。

**来源**：`ui-ux-pro-max` 技能（v2.15）生成的基线 + Android/Material 3 适配。
**生成器结论**：Minimalism & Swiss Style（企业工具、仪表盘、专业工具向），性能成本 low，
无障碍风险 low，要求 `contrast-text-4.5 / keyboard / visible-focus / reduced-motion`。

> **3.6 起基线换到 Material 3 Expressive**（Material 库 1.12.0 → 1.14.0）。
> 主题基类为 `Theme.Material3Expressive.DayNight.NoActionBar`。下面凡是提到
> 「M3 默认」的地方，取值都由这套基类提供。品牌色未变。

---

## 0. 对生成器输出的取舍

技能按 Web 出模板，直接套会错，逐条判定：

| 生成器给的 | 采纳？ | 处理 |
|---|---|---|
| 风格：Minimalism & Swiss（留白、几何、强层级） | ✅ | 已基本符合，继续强化 4/8dp 节奏与留白 |
| 配色：Navy `#1E3A5F` + 绿 `#059669` | ❌ | 应用已有实装品牌色 `#3D5AFE`，换色收益为 0；改用 M3 语义色 |
| 字体：Outfit / Work Sans | ❌ | 中文环境无对应字形，Android 系统字体（Roboto/思源黑）才是正确选择 |
| 字号：base 16px | ✅ 换算 | 正文 14sp（M3 bodyMedium），标题 ≥ 16sp，绝不出现 < 12sp 的信息性文字 |
| 间距：px 栅格 | ✅ 换算 | 4/8dp 栅格，见 §3 |
| `cursor-pointer` / hover / 150–300ms | ❌ | 触屏无 hover；改为 ripple + `stateListAnimator`，按压反馈 80–150ms |
| 断点 375/768/1024/1440px | ✅ 换算 | 用 `-sw600dp` ＋ `Screen.columns()`，已验证手机 1 列 / 平板 2 列 |
| 图标用 SVG 不用 emoji | ✅ | 全量 Material 24dp 线性填充族，禁止 `⋯`、`☰` 这类字符充当图标 |
| 对比度 4.5:1 | ✅ | 实测见 §2，全部达标 |
| 焦点可见 | ✅ | 已用 `?attr/selectableItemBackground` + `focusable`，TalkBack 可达 |
| reduced-motion | ✅ 部分 | 索引气泡/动画均 ≤ 200ms 且非装饰性循环，保留 |

---

## 1. 颜色（语义 token，禁止在布局里写死 hex）

一律用主题属性，不写 `@color/xxx`（品牌色与徽标色除外）：

| 用途 | Token | 亮色取值 | 深色取值 |
|---|---|---|---|
| 品牌 / 主操作 | `?attr/colorPrimary` | `#3D5AFE` | `#A8B4FF` |
| 主操作上的文字 | `?attr/colorOnPrimary` | `#FFFFFF` | `#0A1120` |
| 页面底色 | `?attr/colorSurface` | M3 默认 | M3 默认 |
| 正文次要文字 | `?attr/colorOnSurfaceVariant` | `#49454F` | `#CAC4D0` |
| 描边 / 分割线 | `?attr/colorOutlineVariant` | M3 默认 | M3 默认 |
| 选中行底色 | `?attr/colorSecondaryContainer` | M3 默认 | M3 默认 |
| 风险徽标文字 | `@color/badge_warn_text` | `#A15C00` | `#FFC46B` |
| 风险徽标底/边 | `@color/badge_warn_bg` / `_stroke` | 琥珀 10%/36% | 琥珀 15%/36% |

> **关于「不要在 xml 里写死 hex」**：`res/values/colors.xml` 与 `values-night/colors.xml`
> **是**令牌的定义处，写在那里是规定动作，不违规。违规的是在 `res/layout/**` 或
> Kotlin 里直接写 `#RRGGBB`。同理，矢量图标里的 `android:fillColor="#FFFFFF"`
> 配合 `android:tint="?attr/colorControlNormal"` 是标准写法 —— 真正的颜色由 tint 决定。

> 已弃用：`?android:attr/textColorSecondary`（框架色，深浅色切换不完全受 Material 3 控制）。
> 新代码一律 `?attr/colorOnSurfaceVariant`。

---

## 2. 对比度实测（WCAG 2.1，正文阈值 4.5:1）

| 组合 | 比值 | 判定 |
|---|---|---|
| 品牌 `#3D5AFE` on `#FFFFFF` | 5.13:1 | ✅ |
| 徽标文字 `#A15C00` on `#FEF7E9`（琥珀底叠白） | 4.87:1 | ✅ |
| 深色品牌 `#A8B4FF` on `#141218` | 9.42:1 | ✅ |
| 深色徽标 `#FFC46B` on `#2A2110` | 10.09:1 | ✅ |
| 次要文字 `#49454F` on `#FFFFFF` | 9.34:1 | ✅ |

结论：无需改色。深浅两套都要各自测，不能由亮色推导深色。

---

## 3. 尺寸与节奏（4/8dp 栅格）

| Token | 值 | 用途 |
|---|---|---|
| `space_1` | 4dp | 图标与文字、行内微间距 |
| `space_2` | 8dp | 组件内部、卡片之间的最小间隔 |
| `space_3` | 12dp | 卡片内边距、列表外边距 |
| `space_4` | 16dp | 页面左右留白、条目内边距 |
| `space_5` | 24dp | 分组之间 |
| `space_6` | 32dp | 空态、页脚 |
| `touch_min` | 48dp | **所有**可点区域的最小边长（Android 硬指标） |
| `touch_gap` | 8dp | 相邻可点区域的间距 |
| `icon_lg` / `icon_md` / `icon_sm` | 24 / 20 / 16dp | 主图标 / 列表尾标 / 行内小图标 |

**圆角**（`shape.xml`，3.6 新增）：`radius_xs` 8 / `radius_sm` 12 / `radius_md` 16 /
`radius_lg` 20 / `radius_xl` 28 / `radius_pill` 9999。相邻档至少差 4dp —— 差得出来才
看得出是设计。胶囊用 9999dp 而不是具体值，控件改高度也不会塌成圆角矩形。

**列数**（`integers.xml`）：`settings_columns` 手机 1 / ≥840dp 时 2。

禁止出现 1dp、2dp、14dp、18dp、26dp 这类随手值。
文字上限：标题 1 行 `ellipsize=end`；包名 `ellipsize=middle`（保住前后缀）；任何截断都要有第二条可达路径（点进详情/菜单看全）。

---

## 4. 组件约定

### 列表条目
- 最小高度 64dp，`foreground="?attr/selectableItemBackground"` 提供 ripple（80–150ms）。
- 图标 44–48dp、`contentDescription="@null"`（旁边有文字，属装饰，不能占无障碍焦点）。
- 主标题 1 行 + 副标题 1 行；徽标紧贴标题右侧，**颜色不能是唯一信息载体**（必须带文字）。

### 图标按钮
- 尺寸 48×48dp，`contentDescription` 必填，语义化描述动作（"更多操作"而非"三个点"）。
- 只用矢量图标，禁止 `⋯`、`⋮`、`➕` 等字符代替图标。

### 空态
- 必须三件套：图标 + 一句现状 + **一个动作**。只写"还没有"是反模式。

### 破坏性操作
- 卸载 / 删除记录 / 覆盖重建：先弹确认，说明后果与可逆性，确认按钮用 destructive 语义。
- 不可逆动作的确认文案必须写明"数据会怎样"。

### 长任务（>300ms）
- 立刻给阻塞式反馈（进度 + 说明文字），期间禁用入口，避免重复提交。
- 完成后再给结果提示；失败要给下一步建议，不能只报错。

---

## 5. 交付前检查清单（每次改 UI 都过一遍）

- [ ] 所有可点区域 ≥ 48×48dp，相邻间距 ≥ 8dp
- [ ] 每个图标按钮有 `contentDescription`，装饰图标标 `@null`
- [ ] 没有用字符/emoji 当图标
- [ ] 亮色与深色分别验证，正文对比度 ≥ 4.5:1
- [ ] 分隔线、描边在两种主题下都可见
- [ ] 按压有 ripple，且不引起布局位移
- [ ] 空态有动作入口；长任务有进度；破坏性操作有确认
- [ ] 4/8dp 节奏，无随手值
- [ ] 系统字体放大（1.3×/1.5×）不裁切、不重叠
- [ ] 手机竖屏 / 平板横屏各看一遍，内容不被固定栏遮挡

---

## 6. 已知不做（有意为之）

- **不做品牌换色**：现有 `#3D5AFE` 已达标且用户已习惯，改色属于无收益的美化。
- **不引入第三方字体**：中文正文字体包会让 APK 涨 3–8MB，工具类应用不值。
- **不做 hover / cursor 态**：触屏平台无此语义，仅保留 ripple 与 focus。
- **不做底部导航**：一级页面只有"分身列表 / 设置"，FAB + 溢出菜单已足够，加 tab 反而多一层。

---

## 7. 响应式（3.6）

三档断点，与 Material 3 的 window size class 一致，实现在
`ui/adaptive/WindowSizeClass.kt`：

| 档位 | 宽度 | 主页 | 选应用页 | 设置页 |
|---|---|---|---|---|
| COMPACT | < 600dp | 单栏列表 | 1 列 | 1 列 |
| MEDIUM | 600–839dp | 2 列列表 | 2 列 | 1 列 |
| EXPANDED | ≥ 840dp | **list-detail 双栏**（左列表 + 右 380dp 详情） | 3 列 | 2 列 |

**为什么双栏在运行时判断，不用 `layout-sw840dp` 资源限定符**：资源限定符看的是最小
宽度（sw）。平板横屏拖进分屏后，实际窗口宽度可能掉到 600dp 以下，但 sw 没变 ——
按资源给双栏会把两栏各压成一条。判断依据用 `Configuration.screenWidthDp`（当前窗口
可用宽度），配置变化会触发 Activity 重建，重建后自然拿到新值。

**双栏下的点击语义**：点一行**只选中**，不启动。列表和详情同屏可见时把应用推到前台
违反预期；要启动有面板上那个明确的「启动」按钮。单栏下仍是点即打开。

**选中态三重线索**：底色（`colorPrimaryContainer`）+ 描边加粗到 2dp +
`View.isSelected`。只换底色在深色模式下几乎看不出来；只加粗描边在 AMOLED 上像渲染
问题；`isSelected` 是给 TalkBack 的 —— 视觉再清楚，读屏用户也拿不到这个信息。

## 8. 关于验证器告警的说明

跑了 `mobile-app-uiux-pro-max` 自带的 6 个验证器，结论需要人工判读：

- **`COL-TOK` 23 条 error 全部是误报**。它按 Flutter / React 的约定扫，认为颜色必须
  引自某个 tokens 文件；而 Android 的 `res/values/colors.xml` **就是**那个 tokens 文件，
  矢量图标的 `fillColor="#FFFFFF"` + `tint="?attr/..."` 也是标准写法。
  已逐条核实：`res/layout/**` 与 Kotlin 里**没有**任何硬编码颜色。
- **`STATE-COV` 126 条 warning 基本是误报**。它按文件逐个要求 7 种状态，连 drawable
  和 adapter 都算一个文件。本应用**完全离线**，没有网络请求，"offline" 不适用；
  权限被拒的状态在确实需要权限的地方是有的（使用情况访问、安装未知应用）。
- 真实命中并已修复的两条：**RTL 下 `setPadding` 把相对方向固化成物理左右**、
  **6dp / 2dp 不在 4/8dp 栅格上**。

后续再跑验证器时，请以这三条判读为准，不要为了把分数刷上去而在 colors.xml 里
插抑制注释 —— 那只会让令牌文件变得难读。。
