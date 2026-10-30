# 幻之分身 · 设计系统（Master）

> 本文件是界面层的唯一真理源。改动颜色、间距、触摸目标、反馈策略，先改这里，再改代码。
> 页面级例外写到 `pages/<页面>.md`，只记录与 Master 的差异。
>
> **动效（时长、缓动、逐条转场）不在这里**，见同目录 `MOTION.md` —— 那份文件按
> `compose-motion-design` 技能的方法产出，管的是「界面怎么动」。

**来源**：`ui-ux-pro-max` 技能（v2.15）生成的基线 + Android/Material 3 适配。
**生成器结论**：Minimalism & Swiss Style（企业工具、仪表盘、专业工具向），性能成本 low，
无障碍风险 low，要求 `contrast-text-4.5 / keyboard / visible-focus / reduced-motion`。

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
