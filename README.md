# 幻之分身（HuanZhi）— 原生安卓应用分身（自用版）

一个用**原生 Android（Kotlin + AndroidX）**实现的应用分身 / 多开管理器，
对标 Konker 那类工具，但刻意避开了它的技术路线，目标是**在未来的 Android 版本上继续能用**。

> 3.4 起应用名是「幻之分身」，管理器包名 `com.huanzhi.manager`，分身包名前缀
> `huanzhi.clone.k`。旧版叫「梦之分身」（`com.konkerx.manager` / `konkerx.clone.k`），
> 两者是**完全独立的两个应用**，可以并排装 —— 代价是旧版建的分身不会出现在新版列表里：
> 它们是独立应用、照常运行，但记录在旧版自己的私有目录中，新版读不到。
> 新版首次启动会提示一次，说清这件事。

> 仅供自用，不走应用商店审核。

**系统支持区间：Android 12（API 31）～ Android 17（API 37）。**
`minSdk = 31`、`targetSdk = compileSdk = 37`，构建链 AGP 8.13.2 + Gradle 8.13 + Kotlin 2.0.21 + JDK 17。
实测环境为 Android 17 / API 37 模拟器（Pixel 10 Pro XL，1344×2992 @480dpi）。

---

## 它做什么

选一个已安装的应用 → 生成一个**独立包名、独立 UID、独立数据目录**的副本 → 装上去 →
两个（或多个）副本可以和原应用同时运行，互不干扰。

实测（Android 17 / API 37 模拟器）：

| 项目 | 结果 |
|---|---|
| 样例应用分身的包名 | `huanzhi.clone.k001` |
| 与原应用的关系 | 包名不同、UID 不同（10240 / 10241 / 原应用各自独立）、SharedPreferences 各自一份 |
| 分身里跳到第二页 | `huanzhi.clone.k001/com.huanzhi.sample.SecondActivity`，走的是分身自己的进程 |
| 同时装 3 个副本（k001 / k002 / 原应用） | 互不冲突，可同时运行 |
| 主题 / AppCompat | 正常（`Theme.Material3.*` 正确应用到 Activity） |
| 按名字查资源 | 正常（`getIdentifier("app_name","string",getPackageName())` 能命中） |
| 真实三方 Flutter 应用（53 MB，带 native 库） | 生成、安装、启动、渲染全部正常，`libflutter.so` 从分身 APK 正常加载 |
| **批量创建（跨应用）** | 一次勾选多个应用，每个应用 1–5 份，串行构建 + 串行安装，4 份全部成功 |
| **批量创建（单应用）** | 选中一个应用后拉滑杆选 1–10 份，自动编号（`宝可梦图鉴 · 分身4/5/6`），3 份全部安装成功 |
| 夜间模式 | 跟随系统 / 浅色 / 深色，立即生效，重启后保持 |
| 清除缓存 | 统计并释放生成过程占用的空间（实测 61 MB → 0 B），已安装的分身不受影响 |
| **装完即删包** | 提交安装会话后立刻删掉缓存里的克隆 APK（一份等于整个原应用，红果 129 MB × 5 份 = 600 MB+）；装失败 / 取消也删，进程启动时再扫一遍残骸 |
| **运行概览** | 每个分身的真实存储占用（应用/数据/缓存）、最近启动时间、前台状态；k006 显示「占用 100.6 MB · 数据 15.4 MB · 缓存 15.3 MB」 |
| **权限撞名修复** | 清单里带第三方 SDK 权限（`com.igexin.sdk.permission.PUSH_PROVIDER`）的应用也能克隆：分身声明 `huanzhi.clone.k018.sp.com_igexin_sdk_permission_PUSH_PROVIDER`，不再触发 `INSTALL_FAILED_DUPLICATE_PERMISSION` |
| **重命名** | 长按分身改名 → 用新名字重新打包 → 以「更新」方式覆盖安装，包名 / 签名不变，数据保留（实测桌面名与列表名同步变化） |
| **低 targetSdk 应用** | 样例应用刻意停在 `targetSdk = 30` 且启动页不写 `android:exported`（老应用的普遍形态）。分身沿用 30，两份全部安装成功并正常启动，`dumpsys` 显示 `minSdk=29 targetSdk=30` |
| **兼容模式重建** | 长按 → 兼容模式：跳过 `resources.arsc` 包名改写，覆盖安装（数据保留）。用于排查「装得上但卡启动页 / 白屏 / 资源加载失败」 |
| **平板适配** | 800×1280dp 与 1280×800dp 两种形态实测：内容列最宽 880dp 居中，列表两列，标题与列表左边缘对齐 |

---

## 界面与功能

| 模块 | 能力 |
|---|---|
| 分身列表（主页） | 点击启动、长按「重命名 / 重新生成并安装 / 卸载 / 删除记录」、未安装时提示补装、右上角进运行概览 / 设置；**卸载后记录自动删除**（卸载广播即时生效，回主页再对一次账） |
| 选择应用 · 搜索与索引 | 顶部搜索框支持原名 / 拼音全拼 / 拼音首字母 / 包名（打 `wx` 找到微信）；右侧拼音首字母索引条 A–Z（+`#` / `加`）点按或拖动跳组，中间浮出当前字母；**字母字号可在设置里调三档**，每格行高跟着字号走（字号 × 1.8）不会叠字；列表按拼音排序 |
| 选择应用 · 单应用批量 | 点一个应用即弹出创建框：份数滑杆 1–10；名称框随份数切换语义 —— 1 份填什么就叫什么，多份填的是基础名（自动接「· 分身1 / 2 / 3」），点「逐份」可给每一份单独起名；输入框下方实时预览最终名字；底部实时算「预计占用 / 当前可用」 |
| 选择应用 · 跨应用批量 | 右上角「多选」勾选多个应用，每个应用统一 1–5 份；**每个应用一行独立的名称输入框**，互不影响；多份时每行可「逐份」单独命名；撞名自动加 `(2)` |
| 加固壳识别 | 选应用时读 APK 的 `lib/` 与 `assets/` 条目，命中加固壳（360 / 乐固 / 御安全 / 梆梆 / 爱加密 / 阿里 / 百度 / 娜迦 / 易盾 / 几维 / 顶象 / Epic / DexProtector）就给该行副标题末尾挂「加固壳」三字；创建与批量弹窗里还会点名提示「可能卡在启动页」，不阻止创建。**3.9 起不再把这些应用整体挪到列表末尾** —— 选应用页按首字母分组，加固应用留在自己那一组，标签单独占位不会被包名挤掉。结果按 `versionCode + lastUpdateTime` 缓存，应用升级后自动重扫 |
| 批量创建 | 严格串行「构建 → 提交安装 → 等回执 → 下一份」，进度条细化到「当前这份打包到几成」；**空档预构建**：上一份等你点系统确认框时，下一份已经在后台打包；失败按真实原因汇报（权限重名 / 空间不足 / 签名冲突 / 已取消 / 超时），原因一致时只说一次，可「复制详情」带日志 |
| 源应用版本追踪 | 建包时把源应用的 `versionCode` / `versionName` 一起记进 `clones.json`；列表里比对当前源应用，比记录新就挂「可更新」角标、副标题写成 `8.2 → 8.5`；菜单里的「更新全部（N）」逐个重打包并覆盖安装，带总进度、可随时停 |
| 建包自检 | 装之前先让系统解析一遍生成的 APK：包名、签名、与原应用权限撞名，有问题当场报清楚 |
| 运行概览 | 设备内存占用条 + 每个分身的存储 / 数据用量 / 使用状态 / 最近启动，页面可见时每 3 秒自动刷新 |
| 设置 · 外观 | 跟随系统 / 浅色 / 深色，写入 SharedPreferences，`AppCompatDelegate` 全局生效；**索引字母大小**三档（小 11 / 标准 13 / 大 17 sp，默认标准），改完回到选应用页即时生效 |
| 设置 · 存储 | 显示缓存占用与文件数；清理时一并收掉 `files/clones` 里的临时包与「安装包没了、系统里也没有」的失效记录（否则列表会永远挂一行点不动的「未安装」） |
| 设置 · 诊断 | 安装日志：建包 / 提交 / 系统回执三段原始记录，可查看、复制、清空 —— 判断「只装上一个」是没提交还是没点确认 |
| 设置 · 关于 | 「版本与更新」一行（版本号 + 发布日期，点开看各版本变更）、「插件说明」一行 |
| 设置 · 清理 | 「清除全部分身」：逐个提交卸载（每个要点一次系统确认框），清完删记录、清缓存；「卸载幻之分身」：全部清掉后再打开系统卸载页。原理见「卸载本应用时，分身去哪了」 |
| 设置 · 支持作者 | 底部「请作者喝一杯」：一张静态微信收款码，可「保存收款码」进相册，走微信扫一扫的相册入口；不接支付 SDK、不申请权限、不联网 |
| 版本与更新 | 每个版本一张卡，点标题展开该版本完整变更，最新版默认展开；版本号与入口合并，不再重复出现 |
| 插件说明 | 一屏读完：它做什么、怎么做的、能做到、做不到；细节从底部链接进「技术说明」二级页 |
| 技术说明（二级页） | 为什么不用 hook、模块构成、运行概览读数口径、隐私与数据、使用前提 |
| 平板 / 折叠屏 | 内容列 `layout_constraintWidth_max` 夹在 880dp（列表）/ 760dp（文本页）内居中；`screenWidthDp ≥ 600` 时列表排两列 |

截图（`docs/screenshots/`，Android 17 / API 37 模拟器实拍）：

| 分身列表 | 运行概览 | 设置 |
|---|---|---|
| ![](docs/screenshots/08-clone-list.png) | ![](docs/screenshots/09-runtime-overview.png) | ![](docs/screenshots/10-settings.png) |

| 平板 · 主页两列 | 平板 · 运行概览 | 平板 · 横屏 1280dp |
|---|---|---|
| ![](docs/screenshots/11-tablet-main.png) | ![](docs/screenshots/12-tablet-runtime.png) | ![](docs/screenshots/13-tablet-landscape.png) |

| 选择应用 | 创建 1 份（可命名） | 创建多份（自动编号） |
|---|---|---|
| ![](docs/screenshots/01-batch-select.png) | ![](docs/screenshots/02-create-single.png) | ![](docs/screenshots/03-create-batch.png) |

| 批量进度 | 设置（深色） | 版本与更新 | 插件说明 |
|---|---|---|---|
| ![](docs/screenshots/04-batch-progress.png) | ![](docs/screenshots/05-settings-dark.png) | ![](docs/screenshots/06-changelog.png) | ![](docs/screenshots/07-about.png) |

| 重命名 | 创建时预估空间 | 兼容模式重建 |
|---|---|---|
| ![](docs/screenshots/17-rename.png) | ![](docs/screenshots/18-space-hint.png) | ![](docs/screenshots/19-compat-mode.png) |

| 加固壳徽标 | 加固壳提示 |
|---|---|
| ![](docs/screenshots/20-hardening-badge.png) | ![](docs/screenshots/21-hardening-hint.png) |

| 多份时的名称预览（单个应用） | 逐份命名 |
|---|---|
| ![](docs/screenshots/22-name-prefix.png) | ![](docs/screenshots/27-single-per-copy.png) |

| 搜索 + 拼音首字母索引 | 批量创建（每个应用一个名字框） | 逐份命名弹窗 |
|---|---|---|
| ![](docs/screenshots/23-picker-index.png) | ![](docs/screenshots/24-batch-names.png) | ![](docs/screenshots/25-per-copy-names.png) |

> 索引条只按真实存在的首字母生成。上面这台模拟器只装了两个三方应用，所以右侧只有 `B`、`K` 两个字母；
> 真机上百个应用时是完整的一列 A–Z（+`#`），高度会跟着字母数量变，最多撑到列表高度。

| 技术说明（二级页） | 图标 |
|---|---|
| ![](docs/screenshots/16-manual.png) | ![](docs/screenshots/15-icon-preview.png) |

2.0 按 UI/UX 规范补齐的三处反馈（空态有入口、破坏性操作先确认、长任务有进度）：

| 空态带动作 | 卸载前确认 |
|---|---|
| ![](docs/screenshots/26-empty-state.png) | ![](docs/screenshots/29-uninstall-confirm.png) |

3.1 的进度反馈与加固分组：

| 加固应用归到列表最下面 | 打包阶段（按字节） | 写入安装会话 |
|---|---|---|
| ![](docs/screenshots/32-picker-hardening-group.png) | ![](docs/screenshots/33-progress-pack.png) | ![](docs/screenshots/34-progress-install.png) |

| 批量创建（总体百分比） | 请作者喝一杯 |
|---|---|
| ![](docs/screenshots/36-batch-progress.png) | ![](docs/screenshots/35-support-qr.png) |

3.2 给收款码加了「保存收款码」——存进相册，用微信扫一扫自己的相册入口识别：

| 弹窗（左：保存 · 右：关闭） | 存完的样子 |
|---|---|
| ![](docs/screenshots/37-support-save.png) | ![](docs/screenshots/38-support-saved.png) |

3.3 加了索引字母大小设置，一次批量 6 个也全部落地：

| 设置里的三档 | 标准 ↔ 大 的对比 |
|---|---|
| ![](docs/screenshots/39-index-size-setting.png) | ![](docs/screenshots/42-index-compare.png) |

| 批量 6 个全成功 | 列表里没有残留的「未安装」 |
|---|---|
| ![](docs/screenshots/43-batch-ok6.png) | ![](docs/screenshots/44-list-after-batch.png) |

3.4 改名「幻之分身」，并让分身知道源应用已经升级：

| 旧版分身的一次性说明 | 源应用升到 1.1 后的「可更新」 |
|---|---|
| ![](docs/screenshots/45-legacy-notice.png) | ![](docs/screenshots/46-updatable-badge.png) |

覆盖安装走的是系统自己的确认框，和普通升级一样；装完记录里的源版本跟着变成 1.1，角标消失。
分身的数据目录没动 —— `firstInstallTime` 还是第一次装的时间，只有 `lastUpdateTime` 变了：

![](docs/screenshots/47-update-confirm.png)

3.5 把「卸载时清空分身」做成了两个入口 —— 实测下来，系统对每个分身都要弹一次确认框，
所以这一版做的是「把 N 个框排好队」，而不是假装能静默清空：

| 设置里的清理分区 | 清除确认框（含旧版分身提示） |
|---|---|
| ![](docs/screenshots/49-settings-reset.png) | ![](docs/screenshots/48-purge-confirm.png) |

更新记录也一起改了写法：每个版本压到几条短句，展开一屏就能看完，不再是小作文：

![](docs/screenshots/50-changelog.png)

---

## 为什么不学 Konker 的「轻壳」

拆开 Konker 3.0 能看到它是典型的**壳 APK** 方案：0.5 MB 的壳 + Flutter 写的 UI +
一层 R8 混淆过的 Java（`com.ifma.cmpt.utils.a`–`z`）+ native 引擎 `libifmacommon-jni.so`，
靠 `attachBaseContext` / `addAssetPath` / `CmnLoader::loadMemDex` /
`makeInMemoryDexElements` / `cmn_hook_getAbsolutePath` 把宿主应用的 dex 和资源
**在运行时挂进自己的进程**。克隆出来的 0.5 MB APK 依赖原应用必须还装着。

这条路在现代 Android 上有个绕不过去的坎：

> Activity 的 `Resources` 是由 `ResourcesManager` 依据 **APK 资源目录**新建的，
> 不是从 `LoadedApk` 拿的。所以往 `LoadedApk` 上打的 `mClassLoader` / `mResources` /
> `mAppDir` 补丁，对 Activity 完全不生效。

这条结论是实测出来的：日志里 `bootstrap done`、`LoadedApk patched classLoader=true resources=true`
全都打出来了，Activity 起来照样报 `You need to use a Theme.AppCompat theme`。
要继续走轻壳路线，就得一路 hook 隐藏 API（`Instrumentation.execStartActivity` 之类），
既脆又和系统版本强耦合 —— 这基本就是原作者停更的原因。

**幻之分身的选择：全量重打包。** 目标 APK 的 dex / 资源 / assets / so 全部带上，
换包名、换签名，装成一个真正独立的 APK。代价是体积等于原应用，换来的是零 hook、
全部原生行为、以及不会随系统升级失效。

---

## 处理流水线

```
目标 APK (base.apk)
   │
   ├─ 1. AxmlPatcher     读原始 AndroidManifest.xml（aapt2 产物），只改必须改的字段
   ├─ 2. ArscPatcher     改写 resources.arsc 里的包名（定长 128 UTF-16 原地改）
   ├─ 3. CloneBuilder    重打包：换掉清单、丢掉旧签名、arsc/so 不压缩并对齐
   └─ 4. SigningEngine   用内置自签证书做 v2 签名
         │
         └──> clone-huanzhi.clone.kNNN.apk ──(PackageInstaller 会话安装)──> 系统
```

### 1. AxmlPatcher —— 改写而不是生成

**这是整个项目踩坑最多的地方，也是最终方案的关键。**

最初的实现是 `ManifestGenerator + AxmlBuilder`：按 `PackageManager` 读到的信息，
用手写的 AXML 序列化器从零拼一份新清单。手写的清单 `aapt2 dump xmltree` 能正常解析、
`pm install` 也认，但**框架解析出来的 `android:theme` 是空的**，AppCompatActivity 一律崩。

对照实验定位了问题：拿 aapt2 的原始清单，**只把包名字符串等长替换**（`com.huanzhi.sample`
和 `huanzhi.clone.k001` 恰好都是 18 字节），重新签名装上去 —— 主题完全正常。
所以问题出在手写序列化，而不是包名 / 资源之类的外部因素。

从零生成还有第二个硬伤：只能覆盖"我知道的"那些标签，`<provider>`、`<receiver>`、
`<meta-data>`、`appComponentFactory`、自声明 `<permission>` 全会丢。其中丢 `<permission>`
会直接导致：

```
INSTALL_FAILED_DUPLICATE_PERMISSION: Package huanzhi.clone.k001 attempting to
redeclare permission com.huanzhi.sample.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
already owned by com.huanzhi.sample
```

AGP 给**每个**应用都自动加一条 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，
所以这不是个别现象 —— 走"从零生成"路线的第一版其实是靠"漏掉 `<permission>`"才碰巧装上的。

所以改成 `AxmlPatcher`：**拿原始清单，只改必须改的字段，其余每个字节原样保留。**

实现上利用了一个性质：字符串池是**追加式**的。原有字符串下标一个都不变，新串追加在末尾，
于是所有节点块尺寸都不用重算，只需要

1. 重建字符串池（原串序不动 + 追加新串）；
2. 把被改写属性记录的 `rawValue` / `typedValue.data` 指向新串；
3. 平移所有块的位置并修正外层 `totalSize`。

具体改写规则：

| 目标 | 处理 |
|---|---|
| `<manifest package>` | 换成分身包名 |
| 组件的 `android:name`（application/activity/activity-alias/service/receiver/provider/instrumentation） | 相对类名补成绝对类名（`.MainActivity` → `com.foo.MainActivity`），绝对类名保持不动 |
| `permission` / `uses-permission` 的 `android:name` | 包名前缀换成分身包名（避开 DUPLICATE_PERMISSION） |
| `<application android:label>` | 换成分身名称字面量（字面量才能区分多个副本） |
| `authorities` / `process` / `taskAffinity` | 包名前缀换掉，多个副本才不会互相顶掉 |
| `targetActivity` / `parentActivityName` | 同样做相对类名补全 |
| `sharedUserId` | 换成分身包名（否则与原应用签名冲突） |
| `uses-sdk targetSdkVersion` | **沿用原值**；只在低于系统安装门槛时抬到门槛（Android 14 起 23、Android 15 起 24）。绝不往最新版本抬，原因见下 |
| `extractNativeLibs` | 强制 `true`（重打包后不能保证 so 页对齐） |

> ⚠️ 踩过的坑：`Patch` 必须带上精确宽度。`dataType` 只占 1 字节，
> 早期按 4 字节去写，把紧邻的 `typedValue.data` 低 3 字节清零了 ——
> 表现是应用名变成了字符串池里第 0 个串（正好是 `"theme"`）。
>
> ⚠️ 更大的坑：**别改 `targetSdk`**。1.0–1.4 把它抬到 34，代价是两个真实的失败：
> targetSdk ≥ 31 时，带 intent-filter 的组件必须显式写 `android:exported`，
> 老应用当年不需要写，抬高后整份清单非法（`INSTALL_PARSE_FAILED_MANIFEST_MALFORMED`，
> 红果免费短剧整套克隆一份都装不上）；而且 targetSdk 是「应用向系统声明的行为版本」，
> 抬高会改掉 PendingIntent 可变性、前台服务类型、通知权限等运行时行为，
> 老应用即使装上了也容易一启动就崩。现在只在系统不允许安装时才抬到门槛。

### 2. ArscPatcher

`ResTable_package` 里 `uint16 name[128]` 是定长 256 字节 UTF-16，可以原地改写、不影响后续偏移。
不改的话 `getIdentifier(name, type, getPackageName())` 这类按名字查资源的调用会失败
（走 `R` 常量的代码不受影响，但少数三方库会按名字查）。
资源 ID 是纯数字，所以改名不影响 `@0x7f...` 这种引用解析。

### 3. CloneBuilder（重打包 + 对齐）

- 丢掉 `META-INF/*`（旧签名必须清掉，否则 v2 校验失败）
- `resources.arsc`、`*.so` 不压缩，并做 **zipalign**：在 local header 后塞一个 `0xd935`
  扩展字段把数据起始位置推到 `align` 的整数倍（arsc → 4，so → 4096）。
  不做这一步，部分设备会拒绝加载未对齐的 arsc / so。

**大包为什么能快**（1.8 起）：

| 手段 | 效果 |
|---|---|
| 压缩等级 6 → 1 | 129 MB 的包只多出几个百分点，CPU 时间降一大截 |
| 「原本就没压缩」的条目直接照搬 | 游戏的 assets / 视频往往是 store 模式，解压再压是纯浪费 |
| 流式搬运 | 只有 arsc 与 so 需要整块读（要算 CRC、要改写），其余按 64 KB 流式 copy，不再为上百兆的条目开临时数组 |
| 空档预构建 | 上一份在等用户点系统确认框时打下一份，把「打包 → 等 → 打包 → 等」压成「打包 →（等+打包）→ 装」 |
| 暂存目录放 `files/clones` | 存储紧张时系统会清 `cacheDir`，1.7 之前偶发「打包到一半文件被抹掉」，重打一次等于白等 |

实测（API 37 模拟器、53 MB 的 Flutter 应用）：单份 9–18 秒；三份连装总耗时 109 秒，
其中绝大部分是三次人工点「安装」的间隔，而不是打包。

### 4. SigningEngine

用 `com.android.tools.build:apksig` 做 **仅 v2** 签名（v1 关、v3 关，`minSdkVersion = 31`）。
密钥是内置在 `manager/src/main/assets/` 里的自签 RSA-2048（`key.pem` + `cert.pem`，
CN 仍是 `KonkerX Self-Use`，有效期 10000 天），运行时读取、base64 解码、`pkcs8` 格式喂给 apksig。

---

## 运行概览是怎么读到数据的

这个页面最容易被误认为"应该能显示内存"，所以把四条通道写清楚：

| 数据 | 来源 | 前提 |
|---|---|---|
| 存储占用（应用 / 数据 / 缓存） | `StorageStatsManager.queryStatsForUid` | 需要「使用情况访问权限」 |
| 最近启动 / 前台状态 | `UsageStatsManager.queryUsageStats` + `queryEvents` | 同上 |
| 进程数 / 内存 / 线程 | `/proc/<pid>/status` | 系统未限制跨 UID 读取（多数系统会限制） |
| 设备内存 | `ActivityManager.MemoryInfo` | 无 |

为什么没有 CPU / GPU 读数：CPU 在 Android 12 之后不少机型把 `/proc/stat` 对应用关了，
只能退到 cpufreq 的频率比值（跟任务管理器的占用率对不上）；GPU 的厂商节点基本是 root-only。
两个口径都不硬，2.2 起整块撤掉，只留内存与存储 —— 宁可不显示，也不给一个看起来精确的错数字。

几个踩过的点：

- **`/proc` 在应用进程里读不到别人的进程。** 用 `adb shell run-as` 能列出 291 个 PID、
  读出别人的 `VmRSS`，但那是 `runas_app` 域；应用自己是 `untrusted_app` 域。日志实测
  `proc scan: 1 procs, 1 mine, 0 foreign` —— 只能看到自己。所以内存那一行在真机上基本不出现，
  这是系统限制，不是代码没写，也不会用 0 顶替。
- **`ActivityManager.getRunningAppProcesses()` 同样不行**：从 Android 8.0 起它只返回调用者自己的进程。
- **判断前台/后台看事件流的最后一笔**：`ACTIVITY_RESUMED` 记为在前台，
  `ACTIVITY_PAUSED` / `ACTIVITY_STOPPED` 记为不在。系统不提供"是否还在后台驻留"的查询，
  所以顶部统计写的是「前台活跃」而不是「正在运行」。
- **没授权时不能写"从未启动"**，那是判不出来。状态显示「未观测」，顶部数字显示 `—`。

`StorageStats.appBytes` 是 APK **解压后**的体积，所以 53 MB 的 Flutter 应用会显示占用
100 MB 左右 —— 这是对的，不是重复计算。

---

## 平板适配的做法

没有为平板单独做一套布局，而是让同一套布局自己收窄：

- 每个页面把内容包进 `ConstraintLayout`，用 `layout_constraintWidth_max`
  限制列宽（列表页 880dp、纯文本页 760dp）。**手机上屏幕本来没这么宽，
  约束会自动退化成 `match_parent`**，所以不需要 `values-sw600dp` 的第二套布局。
- 列表用 `GridLayoutManager`，列数由 `screenWidthDp ≥ 600` 决定。用 `screenWidthDp`
  而不是 `smallestScreenWidthDp`，是为了让折叠屏展开、平板横屏也能落到两列。
- Toolbar 也放进同一宽度约束里，标题才会和下面的列表左边缘对齐。
- **FAB 的行踪跟着内容列，而不是跟着屏幕**：它约束在列表的右边缘、底部留 24dp，
  所以平板上内容列居中时，按钮也停在列的右下角；手机上列宽等于屏宽，看起来还是贴右下。
  为此首页根布局从 `CoordinatorLayout` 换成了纵向 `LinearLayout` —— 原来那层
  `appbar_scrolling_view_behavior` 在标题栏不折叠时没有作用，却会把内容整体下移、
  吃掉底部空间，FAB 也就没法贴着内容列摆。
- 验证方式（不用新建 AVD）：

```bash
adb shell wm size 1280x2048 && adb shell wm density 256   # 800×1280dp 竖屏平板
adb shell settings put system user_rotation 1             # 转横屏 = 1280×800dp
adb shell wm size reset && adb shell wm density reset     # 还原
```

---

## 工程结构

```
D:\app\KonkerX\
├── settings.gradle.kts          :manager + :sample
├── build.gradle.kts             AGP 8.7.3 / Kotlin 2.0.21
├── gradle/wrapper/              Gradle 8.11.1
├── manager/                     分身管理器（applicationId = com.huanzhi.manager）
│   └── src/main/
│       ├── assets/              key.pem / cert.pem（自签签名密钥）
│       └── java/com/huanzhi/manager/
│           ├── MainActivity.kt          分身列表、启动、长按菜单、运行概览 / 设置入口
│           ├── AppPickerActivity.kt     选应用壳：扫描 / 过滤 / 勾选 / 建包编排（3.9 起界面在 screen/AppPickerScreen.kt）
│           ├── NamingDialog.kt          逐份命名弹窗（一份一个输入框）
│           ├── RuntimeActivity.kt       运行概览：三张统计卡、分色占用条、逐行体积与状态
│           ├── SettingsActivity.kt      外观 / 存储 / 关于
│           ├── AboutActivity.kt         插件说明（精简一屏）
│           ├── ManualActivity.kt        技术说明二级页（原理、模块、读数口径、隐私）
│           ├── DocRenderer.kt           两页共用的说明排版
│           ├── ChangelogDialog.kt       可展开的版本更新记录弹窗
│           ├── CloneAdapter.kt
│           ├── ui/                      界面层（Compose，3.7 起）
│           │   ├── theme/               色板 / 排版 / 尺寸令牌（数值逐点采自设计稿）
│           │   ├── glass/               液态玻璃材质与按压缩放
│           │   ├── components/          卡片、图标块、底部胶囊、侧栏
│           │   ├── nav/AppScaffold.kt   同一份内容两种导航：底部胶囊 / 左侧栏
│           │   ├── screen/              主页、选应用、运行概览、设置四屏（手机 + 平板两套排布）
│           │   ├── dialog/              自绘玻璃弹窗：确认 / 单选 / 动作选单三种形态
│           │   ├── adaptive/            窗口宽度 → COMPACT / MEDIUM / EXPANDED
│           │   └── motion/              转场曲线与级联
│           ├── util/
│           │   └── Formats.kt           大小与相对时间格式化
│           └── core/
│               ├── AxmlPatcher.kt       ★ 二进制清单改写（核心技术）
│               ├── ArscPatcher.kt       resources.arsc 包名改写
│               ├── CloneBuilder.kt      重打包 + zipalign（大条目流式搬运）
│               ├── SigningEngine.kt     apksig v2 签名
│               ├── ApkVerifier.kt       ★ 建包自检：包名 / 签名 / 权限撞名
│               ├── CloneWorkflow.kt     编排：prepare（构建）/ install / rename
│               ├── BatchRunner.kt       批量调度：串行构建 + 安装 + 等回执
│               ├── ApkInstaller.kt      PackageInstaller 会话安装 / 卸载
│               ├── InstallReceiver.kt   安装结果回调 + INSTALL_FAILED_* 翻译
│               ├── PackageGoneReceiver.kt 卸载广播 → 删掉对应记录
│               ├── InstallBus            多监听者的安装事件总线（在 ApkInstaller.kt 内）
│               ├── InstallLog.kt        安装日志（files/install.log，供复制诊断）
│               ├── StorageCheck.kt      建包前的空间估算
│               ├── RuntimeProbe.kt      /proc + StorageStats + UsageStats 采集
│               ├── CloneStore.kt        clones.json 元数据 + 默认命名
│               ├── AppScanner.kt        扫描可分身的三方应用
│               ├── Pinyin.kt            中文名 → 拼音（ICU Han-Latin）：排序 + 首字母 + 搜索
│               ├── CacheCleaner.kt      缓存统计与清理
│               ├── SettingsStore.kt     夜间模式偏好
│               ├── Changelog.kt         版本变更数据
│               └── TemplateSpec.kt      分身包名规则
├── docs/
│   ├── screenshots/             界面实拍
│   ├── uidump.py                小工具：dump 界面元素与坐标（Git Bash 下要绕开路径改写）
│   └── icon/make_icons.py       图标处理脚本（去水印、按主体裁正方形、导出各密度）
└── sample/                      自测用的目标应用（com.huanzhi.sample）
    ├── MainActivity.kt          打印包名 / 主题探测 / 本地计数
    └── SecondActivity.kt        验证分身内部页面跳转
```

`sample` 刻意保留三种「真实老应用」的形态，作为重打包的回归夹具：
`targetSdk = 30`、启动页带 intent-filter 但不写 `android:exported`、
清单里声明一条第三方命名空间的权限（`com.igexin.sdk.permission.PUSH_PROVIDER`）。
分身若擅自抬高 targetSdk 或不给权限改名，都能在这里当场失败。

分身包名规则：`huanzhi.clone.k` + 三位序号（`k001` … `k999`），共 18 字符。

---

## 编译与安装

需要 JDK 17 + Android SDK（`compileSdk 37`，`minSdk 31`）。

> `compileSdk 37` 对应的是 **`platforms;android-37.0`**（API 37 起平台目录带小版本号）。
> AGP 8.13 才能解析这种目录名，8.7 及更早会报
> `Failed to find Platform SDK with path: platforms;android-37` —— 升级 AGP 时顺手把
> Gradle 一起升到 8.13，两者版本是绑定的。

```bash
# 1) 管理器
./gradlew :manager:assembleDebug
adb install -r manager/build/outputs/apk/debug/manager-debug.apk

# 2) （可选）自测目标应用
./gradlew :sample:assembleDebug
adb install -r sample/build/outputs/apk/debug/sample-debug.apk
```

首次使用需要在管理器里授予「安装未知应用」权限。

> `local.properties` 里的 `sdk.dir` 指向本机 SDK，换机器要改。
> 本机 `JAVA_HOME` 如果是坏的，命令前临时指定：
> `export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"`

### 打包正式版

```bash
./gradlew :manager:assembleRelease
# 产物：manager/build/outputs/apk/release/manager-release.apk
# 归档副本：dist/HuanZhi-3.5-release.apk
```

3.5 的正式包 **3,355,749 字节**，`sha256 4bfd8062595e3d8976b5c3a342aff740670af86c73ee906ccd1b856e7936f373`，
签名 v2 + v3（无 v1）。

正式包与调试包的差别：

| | 调试包 | 正式包 |
|---|---|---|
| 体积 | 14 MB | **3.2 MB**（R8 代码收缩 + 资源裁剪） |
| 签名密钥 | `~/.android/debug.keystore`（公共密钥，谁都有） | `signing/huanzhi-release.jks`（RSA-4096，10000 天） |
| 签名方案 | v2 | **v2 + v3** |
| `debuggable` | true | false |
| 崩溃栈 | 原始 | 混淆后，靠 `dist/mapping-3.5.txt` 还原（已保留行号） |

**密钥与口令**：`signing/huanzhi-release.jks` + 仓库根的 `keystore.properties`
（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）。两者都在 `.gitignore` 里，
且**必须一起备份**——丢任何一个就没法再发布能覆盖升级的版本，只能让用户卸载重装。

**换签名会断升级链**：正式包与之前用调试密钥签的包签名不同，不能覆盖安装，
先 `adb uninstall com.huanzhi.manager` 再装。管理器卸载**不会**自动带走已经装进系统的分身
（它们是独立包，系统不管谁装了谁）—— 要一起清掉就走设置 →「清理」里的
「卸载幻之分身」，先把分身逐个清掉再打开系统卸载页。

**R8 规则**（`manager/proguard-rules.pro`）只保留四处，其余交给 R8：

- `com.android.apksig.**` 整包——它按签名算法 id 动态挑实现，裁掉不会在启动时报错，
  而是等到「生成分身 → 签名」才炸，最难查；
- 枚举的 `values()`/`valueOf()`、`Parcelable.CREATOR`；
- 行号表（`SourceFile,LineNumberTable`），否则正式包崩溃只有一串 `a.a.a`。

签发校验：

```bash
apksigner verify --verbose --min-sdk-version 31 dist/HuanZhi-3.5-release.apk
#   Verified using v2 scheme: true
#   Verified using v3 scheme: true
```

> 不加 `--min-sdk-version 31` 时 apksigner 只报 v3：它按 APK 的 minSdk 推导校验区间，
> 全区间都用 v3 验，就把 v2 标成 false。想确认 v2 块真的在，直接看签名块里的 id
> （`0x7109871a` = v2，`0xf05368c0` = v3）—— `tools/verify_sig.py` 就是这个干活的：
>
> ```bash
> python tools/verify_sig.py dist/HuanZhi-3.5-release.apk
> #   id 0x7109871a  len 2603  -> v2
> #   id 0xf05368c0  len 2603  -> v3
> #   v2 存在: True | v3 存在: True
> #   v1 存在: False
> ```

### 自测对照

```bash
# 原应用
adb shell am start -n com.huanzhi.sample/.MainActivity
# 分身
adb shell am start -n huanzhi.clone.k001/com.huanzhi.sample.MainActivity
# 看探针输出（三项都应正常）
adb logcat -s KXSample
#   I KXSample: pkg=huanzhi.clone.k001 appNameId=0x7f0f001c windowBg=true(0x7f050118) themeRes=-1
#   I KXSample: beforeSet: windowActionBar=true
```

`themeRes=-1` 是正常的：读它用的是隐藏 API `Activity.getThemeResId()`，被反射限制挡住了。
真正判断主题看 `beforeSet: windowActionBar=true`（`false` 说明用的是系统默认主题）。

离线核对生成的清单：

```bash
adb exec-out run-as com.huanzhi.manager cat files/clones/clone-huanzhi.clone.k001.apk > c.apk
aapt2 dump xmltree --file AndroidManifest.xml c.apk
```

---

## 界面规范（UI/UX）

界面不再靠手感调，而是有一套写在代码里的规范：**`docs/design-system/MASTER.md`**。
它是用 `ui-ux-pro-max` 技能（79 种风格 / 192 套行业配色 / 119 条 UX 准则 / 一套交付前检查清单）
针对「Android 工具类应用」跑出来的基线，再按 Material 3 的 DP/SP 体系逐条换算得到的。

### 生成器给了什么，我们采纳了什么

| 生成器建议 | 处理 | 原因 |
|---|---|---|
| 风格 Minimalism & Swiss | ✅ 采纳 | 工具类应用要的是留白与强层级，不做装饰性动效 |
| 配色 Navy `#1E3A5F` + 绿 `#059669` | ❌ 拒绝 | 已有品牌色 `#3D5AFE` 对比度 5.13:1 达标，换色是无收益改动 |
| 字体 Outfit / Work Sans | ❌ 拒绝 | 中文无对应字形；系统字体才能跟上厂商字重与字号缩放 |
| 16px 正文 / 4-8px 栅格 | ✅ 换算 | 14sp 正文 + 4/8dp 间距令牌 |
| `cursor-pointer` / hover / 375px 断点 | ❌ 拒绝 | 触屏语义，改 ripple + `-sw600dp` |
| 图标用矢量不用 emoji | ✅ 采纳 | 全部 Material 矢量图，禁止 `⋯` 这类字符顶替图标 |
| 对比度 4.5:1、焦点可见、动效可收敛 | ✅ 采纳 | 见下表的实测值 |

### 落到代码上的四层

1. **令牌层** —— `values/dimens.xml` 只有六档间距（4/8/12/16/24/32dp）、
   `touch_min = 48dp`、三档图标（16/20/24dp）。出现 1/2/10/14/18/26dp 就是违规。
2. **颜色层** —— 一律走 Material 3 语义色（`?attr/colorOnSurfaceVariant`、
   `?attr/colorOutlineVariant`…），不用框架的 `?android:attr/textColorSecondary`，
   布局里零硬编码色值。深浅两套**分别**测对比度：

   | 组合 | 实测 |
   |---|---|
   | 品牌色 on 白 | 5.13:1 |
   | 加固徽标文字 on 琥珀底 | 4.87:1 |
   | 深色品牌 on `#141218` | 9.42:1 |
   | 深色徽标 on `#2A2110` | 10.09:1 |

3. **组件层** —— 列表条目 64dp 起、图标 44–48dp 且标 `@null`（旁边有文字，属装饰）；
   图标按钮 48×48dp 且必须有 `contentDescription`；徽标带文字，颜色不是唯一信息载体。
4. **反馈层** —— 空态必须「图标 + 现状 + 一个动作」；破坏性操作先确认（确认按钮用 `colorError`）；
   >300ms 的操作给阻塞式进度，期间禁止重复提交。

### 这一版修掉的违规

| 违规 | 规范条款 | 修法 |
|---|---|---|
| 「更多」用字符 `⋯` 当图标，热区偏小 | 禁止字符充当图标 / 触点 ≥48dp | 换 `ic_more_vert`，`IconButton` 48dp |
| 字母索引条宽 22dp | 触点 ≥48dp | 热区拉到 48dp，字母靠右渲染，列表右侧让出 48dp |
| 空态只有一行文字 | 空态要有动作 | 加图标 + 「创建第一个分身」 |
| 卸载/删记录点了就执行 | 破坏性操作先确认 | 二次确认 + 红色确认按钮 + 后果说明 |
| 重打包只弹 2 秒 Toast | >300ms 要有进度 | 阻塞式进度弹窗，`finally` 里关闭 |
| 徽标 10sp | 信息性文字 ≥11sp | 11sp |
| 间距 1/2/10/14/18/26dp 混用 | 4/8dp 节奏 | 全量替换为令牌 |
| 系统 `ic_input_add` / `ic_menu_close_clear_cancel` | 图标族一致 | 自绘 `ic_add` / `ic_close` |

### 验收清单

改任何界面都过一遍 `docs/design-system/MASTER.md` §5：触点、图标语义、深浅色对比度、
空态/进度/确认三件套、字体放大 1.5× 不裁切、手机竖屏 + 平板横屏各看一遍。

---

## 动效规范（Motion）

界面怎么摆有 `MASTER.md`，**动起来怎么走有 `docs/design-system/MOTION.md`**。
后者按 `compose-motion-design` 技能的 Phase 1 八步流程产出：先定情绪目标与性格档位，
再定属性、时长、缓动、编排，最后才是代码。改任何动画之前先看它。

**情绪目标不是「炫」而是「可控」**：这个应用在做有后果的操作（复制别人的 APK、覆盖安装、
卸载连数据一起删）。动效只服务三件事 —— 让用户始终知道发生了什么、还要等多久、有没有结束。
所以硬规则是：不做弹性过冲、不做装饰性循环动效、不做超过 400ms 的转场、
慢操作在动画期间不能被遮住状态。

| 层 | 落点 |
|---|---|
| 令牌 | `ui/motion/Motion.kt` 的 `Dur` / `Curve`，取值对齐 M3 `MotionTokens`。代码里不允许出现裸的 `setDuration(300)` |
| 页面转场 | `res/anim/nav_*.xml` + 主题里的 `windowAnimationStyle`，四个方向一次配好，新增页面自动一致 |
| 对话框 | `res/anim/dialog_*.xml` + `ThemeOverlay.HuanZhi.Dialog`，缩放 0.92→1 |
| 编排 | `crossFade` / `showScaled` / `cascade` / `pressFeedback`（`ui/motion/Motion.kt`） |

**关键决策与理由**

| 决策 | 为什么 |
|---|---|
| 页面位移只走 6% / 3% 屏宽，不整屏滑 | 主页 ↔ 选应用是高频来回路径，整屏滑在 300ms 里显得慢；6% 已足够表达「更深一层」 |
| 进入 300ms / 退出 200ms，缓动一 DECELERATE 一 ACCELERATE | 退场比入场短是硬规则；同一条曲线两头用，动画会既没来路也没去路 |
| 四个方向的窗口动画配在主题里，不用 `overridePendingTransition` | 逐页写必漏，漏掉的那页就是硬切，而且很难在测试里发现 |
| 透明度与位移分两条曲线（XML 逐元素设） | 位移用 emphasized、透明度用 standard；混用时淡入会「卡一下」 |
| 列表级联只在「从 0 条到有」时放一次，最多 6 项 | 每次刷新都重放就是闪烁；不限项数则长列表最后一项要等两秒 |
| FAB 比列表晚 80ms 出现，收起时缩到 0.8 而不是 0 | 先看见内容再看见动作；缩到 0 最后几帧会变成一个点 |
| 所有动画都读系统「动画时长缩放」 | 用户关掉动画后还让他等 300ms，比动画难看更糟 |

截图（`docs/motion/`，API 37 模拟器实拍，把系统动画放慢 6× 才抓得到中间帧）：
`01-forward-crossfade.png` 前进时新旧两页交叉淡入、`02-back-exit-shift.png` 返回时右移退场、
`03-dialog-enter.png` 对话框缩放淡入。

---

## 进度反馈

建一个分身对用户来说是「等一个不知道多久的黑盒」：要读原包、整包复制、改清单、重签名、
自检、再写进系统安装会话。150 MB 的应用在模拟器上要 40 秒左右，真机更久。
3.1 之前这里只有一个转圈 + 一句 Toast，等于只说「在忙」。

现在是一条连续的百分比，分四段加权：

| 阶段 | 权重 | 用户看到的 |
|---|---|---|
| 准备 | 2% | 「读取目标应用信息 / 改写清单」 |
| 打包 | 76% | 「打包 · 已处理 61.2 MB / 145.0 MB」 |
| 签名 | 16% | 「正在重新签名」 |
| 自检 | 6% | 「安装前自检」 |
| 写安装会话 | 单独 +12% | 「写入安装会话 64%」 |

**几个不那么显然的决定**

| 决定 | 为什么 |
|---|---|
| 权重写死在 `BuildStage` 里，不是平均分 | 拷贝占八成时间。四段平分会让进度条在前 20 秒走到 25% 然后卡死，比没有进度更糟 |
| 分母取「解压后总字节」（遍历中央目录算出来的） | `ZipInputStream` 读的是解压后的量，用压缩后的文件长度当分母会早早冲到 100% 然后停住 |
| 进度只许往前走（`BatchRunner` 里的 `shownFraction`） | 批量时「等用户点确认」和「预构建下一份」是并行的两个进度来源，不夹一下就会看到进度条倒退 |
| 取消做成协作式：`BuildControl.isCancelled` + 每 256KB 查一次 | 打包是阻塞 IO，协程的 `Job.cancel()` 插不进去；只有自己查标记才能在半秒内停下 |
| 取消按钮点下去后显示「正在取消…」而不立刻关窗 | 签名和写会话这两段只有一次系统调用，中途没法安全抽身 —— 说清楚比假装已经停下好 |
| 装完立刻刷新列表（`MainActivity.installListener`） | 不加的话有个很难受的窗口期：点完系统安装框回到列表，回执还没到，刚建好的分身显示「未安装」 |

---


## 源应用更新之后，分身会怎样

**分身不会跟着更新。** 它是构建那一刻的一份 APK 快照：构建时读原应用当前安装的
`sourceDir`（`/data/app/.../base.apk`），整份拷出来、改清单包名、用固定密钥重签，
再作为一个**独立应用**装进系统。从装上的那一刻起，它和原应用之间没有任何运行时联系 ——
不共享包名、不共享签名、也不共享数据目录。

| 发生什么 | 对分身的影响 |
|---|---|
| 原应用升级 | 无。分身仍是建包时那个版本 |
| 原应用被卸载 | 无。分身照常运行，登录态和本地数据都在 |
| 原应用换签名证书 | 无。分身用的是内置的签名密钥 |
| 原应用清除数据 | 无。各自的 `/data/data/` 互不相干 |
| 原应用改成分片安装（split） | 已建的分身不影响，但之后**重建**会报「分片安装包，暂不支持」 |

### 想跟版本，只能重建

覆盖安装同一个包名 + 同一个签名 = 原地升级，分身里的数据、登录态全部保留。入口有两个：

- 单份：行长按 →「重新生成并安装」
- 全部：右上角菜单 →「更新全部（N）」，只挑「源应用确实比记录新」的那几份

「更新全部」必须串行（打包 → 提交 → 等回执 → 下一份）。原因和批量创建一样：
同一时间只认一个系统安装确认框，抢着提交会互相顶掉。进度按「第几份 + 当前这份打到几成」
折算，中途可以停 —— 打包能立刻断（每 256KB 查一次标记），等安装回执的那一步要等它自己结束。

### 版本号是怎么记的

建包时把 `versionCode` / `versionName` 写进 `clones.json`（`sourceVersionCode` /
`sourceVersionName`）。3.4 之前的记录没有这两位，读出来是 0 —— 这种情况**不提示可更新**，
也不会被「更新全部」选中：不知道就别猜，否则一升级就把所有老分身重打一遍（每份都是一整个应用的体积）。
手动重建一次之后版本号补上，后续就能正常判断了。

另外，安装失败时**不更新**记录里的版本号。提前改掉的话角标会消失，用户就再也不知道这份其实还是旧的。

---

## 卸载本应用时，分身去哪了

先说事实：**分身是独立安装的 APK，系统不会因为卸载管理器就删掉它们。** 这一点没法靠代码
改变 —— Android 不给「自己被卸载」发任何广播，进程在该事件发生前就已经没了。所以「卸载时
把分身一起清掉」只能在**卸载发生之前**做完。3.5 给了两个入口：

| 入口 | 什么时候用 | 会发生什么 |
|---|---|---|
| 设置 →「清除全部分身」 | 只想腾空间，管理器留着 | 逐个提交卸载（每个点一次系统框）→ 删 `clones.json` → 清缓存 |
| 设置 →「卸载幻之分身」 | 准备连工具一起不要了 | 同上，全部清完再打开本应用的卸载页 |

### 为什么不能一键静默卸载

`PackageInstaller.uninstall()` 只给持有 `DELETE_PACKAGES` 的应用静默卸载的待遇 —— 系统应用、
设备所有者、root / shell。**普通应用卸任何一个包，系统都要用户在弹窗上点一次确认，
哪怕这个包就是它自己刚装的。**

这条是实测的，不是推断：用当前这版装一个分身，再从列表里点「卸载」，系统照样弹出
`UninstallUserActionRequired`（PIA 的「要卸载这个应用吗」）。之前那一版 README 在这里写过
「installer of record 可以静默卸载」，是错的，已经改掉。

所以工具的职责不是「假装能一键清空」，而是**把 N 个确认框排好队**：提交一个、等用户点完、
立刻提交下一个，标题栏显示「第 2 / 5 个」。中途点取消就停下，剩下的原样保留，
本应用也不会跟着被卸载 —— 这是 `purge_uninstall_confirm_body` 里那句话的由来。

为什么不一次把 N 个请求全提上去：系统只有一个卸载界面，同时来多个请求会互相顶掉，
最后往往一个都没卸成（和批量安装踩的是同一个坑，见「安装回执为什么不能照单全收」）。

3.4 改名之前的旧分身（`konkerx.clone.*`）更特殊：它们的安装记录属于**旧包名**，
当前应用连卸载请求都发不出去。这类包会被如实报成「需要手动卸载」，不会假装已清。

---

## 安装回执为什么不能照单全收

真机反馈：「一次要 5 个，只出来两个，一个装好、一个未安装」。根因不在建包，
在**系统回执和实际状态不一致**。

小米 / OPPO / vivo 这类 ROM 装完之后还有一屏「应用已安装 · 打开 / 完成」。
点「完成」时，`PackageInstaller` 送来的回执是 `STATUS_FAILURE_ABORTED`（3），
字面意思是「用户取消」—— 可包早就进系统了。原来照单全收，于是：

1. 装好的那一份被记成「已取消」；
2. 批量看到「取消」就 `break`，剩下的份数全部作废 —— 这就是「只出来两个」；
3. 预构建的那一份记录已落库却没装，列表里多一行点不动的「未安装」。

现在的判定不是「信回执」，而是**以系统安装列表为准，回执只当参考**：

| 回执 | 提交前装过吗 | 现在装上了吗 | 结论 |
|---|---|---|---|
| SUCCESS | — | — | 成功 |
| ABORTED | 否 | 是 | **成功**（真机「完成」那一屏） |
| ABORTED | 是 | 是 | 失败 / 已取消（覆盖重装失败时旧版本还在，不能误判成装好） |
| ABORTED | 否 | 否 | 已取消（用户真的点了取消） |
| 签名冲突 / 空间不足… | — | — | 照实报错，不校正 |

三个配套改动：

- `ApkInstaller` 在提交会话前把「这个包原来装着没」写进 `PendingIntent`
  （`InstallReceiver.EXTRA_WAS_INSTALLED`）—— 没有这一位，上表第二行和第三行分不开。
- 回执送达和「包在 `PackageManager` 里可见」之间有几十毫秒空档，`InstallReceiver`
  最多重查三次（共 240ms）。宁可慢 0.2 秒，也别把刚装好的那份报成取消。
- `BatchRunner` 收尾再对一次账：凡是失败项都回查一遍系统，装上了就纠回来；
  真取消时，预构建那一份的记录会一并撤掉（`BuildControl.isCancelled` 让它在落库前就中断）。

验证方式：用 `run-as` 直接给接收器投一条带 `STATUS=3` 的广播，四种组合逐一跑过 ——
装上的判成功、没装的判取消、`was_installed=true` 的判取消、签名冲突照实报。
批量那条路是实跑 6 份、逐个点系统确认框，`install.log` 收尾为 `batch done ok=6 fail=0 64s`。

---

## 为什么收款码不能「点一下就跳微信付款」

能试的路都试过了，结论是微信把这条路堵死了，不是没找对 API。

| 想做的 | 走的协议 | 结果 |
|---|---|---|
| 点一下 → 微信收银台 | 收款码内容 `wxp://f2f0…`，直接 `ACTION_VIEW` | 微信支付社区对同一个问题的官方回复是「暂不支持直接拉起收银台」；`wxp` 是微信自家协议，第三方拉起通常落到微信主页或直接失败 |
| 点一下 → 微信扫一扫 | `weixin://dl/scan` | 2021 年起只对白名单开放（目前主要是手机厂商自带的扫一扫），第三方调用静默失败、`canOpenURL` 返回 false |
| 让微信识别 App 里的这张图 | 分享图片到微信 + 长按识别 | 「识别图中二维码」只在微信自己的聊天/网页里生效，第三方 App 触发不了 |
| 官方合规的路子 | 微信支付 Native 支付 / 小程序 URL Scheme | 要商户号 + 服务端签名，或非个人主体小程序 —— 一个自用工具背不动这些 |

所以最终落成「保存到相册」：微信扫一扫的**相册入口**是它自己支持的稳定能力，
用户点两下就进付款页，比拿另一台手机对着屏幕拍省事。做不到的就不摆按钮 ——
摆一个点了没反应的按钮比没有更糟。

实现上两个细节：

- **字节直拷**（`openRawResource` → `openOutputStream`），不在运行时解码再压缩。release 包里 AAPT
  会把 PNG 压成 8-bit 调色板（155 KB → 132 KB），这是构建期的无损优化；把 release 包里导出来的
  文件拉回来用 ZXing 解，仍然是同一个 `wxp://` 串 —— 这条是实测的，不是「应该没问题」。
- **先 `IS_PENDING=1` 写、写完改 0**。不清这个标记，图在相册里根本不会出现，微信自然也选不到。

---

## 版本迭代约定

当前版本 **3.9（versionCode 23）**。版本号显示在设置页「版本与更新」一行（与更新记录合并，不再单列），
数据源是 `BuildConfig`，不在代码里硬编码；更新记录的数据源是 `core/Changelog.kt`。

发一版走三步：

1. `manager/build.gradle.kts`：`versionCode` +1、`versionName` 改成新版本号；
2. `core/Changelog.kt`：在 `RELEASES` **列表头部**插入一条 `ReleaseNote`
   （`versionName` / `versionCode` / `date` / `headline` / `sections`）；
3. 编译装机 —— 设置页自动显示新版本，更新记录里最新一条默认展开。

约定：`versionCode` 只增不减（决定能否覆盖安装）；`Changelog.kt` 里的 `versionCode`
必须和 gradle 里一致。这一条没有编译期校验，靠发布时对照。

---

## 已知限制

| 限制 | 说明 |
|---|---|
| **卸载管理器不会自动带走分身** | 分身是独立包，系统在卸载管理器时不给应用任何执行机会（没有「自己被卸载」的广播），普通应用也没有静默卸载权限。3.5 起给了设置 →「清理」里的两个入口：把 N 个卸载确认框排好队，用户逐个点掉。旧前缀（`konkerx.clone.*`）的分身由旧包名安装，连卸载请求都发不出去，只能手动处理。 |
| **不再支持 Android 10 / 11** | 3.0 起 `minSdk = 31`（Android 12）。这两版上装不上，升上去之前的分身记录不会自动迁移。 |
| **分片安装包不支持** | 目标应用若有 split APK（`ApplicationInfo.splitSourceDirs` 非空）会直接拒绝，并在提示里告诉用户换成完整 APK 重装。要做的话得把各 split 的 dex/资源合并进 base，是另一个工作量级。 |
| **校验自身签名的应用会失效** | 登录态、支付、风控、部分防作弊逻辑依赖签名一致。原理上无解 —— 分身必然是新签名。表现不一定是报错：字节系短剧 / 社交 / 支付类应用常常是**卡在启动页、白屏或静默退出**。实测红果免费短剧（129 MB）克隆后可正常安装、启动到启动页，随后停在启动页。 |
| **加固壳应用大概率卡启动页** | 带壳应用的启动顺序是「壳先接管（解密 dex、校验包完整性、反调试）→ 交还控制权」，分身是新签名、清单与资源表也被改过，壳校验不过就停住。实测红果分身（`libEpic_Protect.so`）的日志到 `load_library_ext_stub ... result=0x...` 后完全静默：无 FATAL、无 ANR、无 tombstone，系统连 `Displayed` 都没打印，进程存活但 CPU 为 0 —— 即卡在壳的初始化里，不是清单/资源错误。对照组：同一分身包名装不带壳的应用时 `Displayed +1s383ms` 正常首帧。1.7 起在选应用时就标注，别等装完 129 MB 才发现。 |
| **兼容模式只覆盖一类原因** | 「卡启动页」的另一类原因是应用按名字查资源而资源表包名被改了。长按 → 兼容模式重建可跳过这一步做对照，但对签名校验无效。判定顺序写在「设置 → 插件说明 → 技术说明 → 装上却卡在启动页时」。 |
| **`targetSdk` 沿用原应用** | 分身不擅自抬 targetSdk，只有低于系统安装门槛（Android 14 起 23、Android 15 起 24）时才抬到门槛。代价是极老的包（targetSdk < 23）在 Android 14+ 上会被系统拒绝安装；好处是分身的行为与原应用严格一致，不会因为「被升级到新行为版本」而莫名崩溃。 |
| **每份都要人工点一次「安装」** | 非系统应用无法静默安装，这是系统限制。批量时严格串行 —— 装完一份才提交下一份，否则两个确认框会互相顶掉。若中途把应用切到后台（Android 10 起会拦后台启动界面）、把确认框关掉、或手滑点到「取消」，这一份就装不上。1.8 起：每份都回查 `PackageManager` 的真实状态，收尾再整体回查一次，日志里能分清「没提交 / 提交了但没点确认 / 系统拒绝」三种情况。 |
| **两副本共用一个签名** | 所有分身用同一张内置证书。同签名的应用在系统层面可互相读取部分数据，自用场景可接受；介意的话可以每次生成一对新密钥。 |
| **深链接 / scheme 冲突** | 多个副本注册同一 scheme 时，系统会让用户选，属于预期行为。 |
| **Play Protect 提示** | 自签 + 非商店来源，安装时可能出现「扫描建议」提示，选择忽略即可。 |
| **看不到分身的后台存活** | 非 root 设备拿不到"某个包现在是否还在后台运行"。运行概览能给的是「此刻是否在前台」+「最近一次启动时间」+ 真实存储占用，不是存活列表。 |
| **内存数字多数机器上不显示** | 读 `/proc` 需要跨 UID 权限，`untrusted_app` 域被 SELinux 挡住。已 root 或策略宽松的设备上会自动出现「N 个进程 · 内存 X」，否则整行不显示。 |
| **运行概览需要一次性授权** | 「使用情况访问权限」要在系统设置里手动开（页面里有直达入口）。不开也能正常创建和使用分身，只是概览页只显示安装包体积。 |

---

## 后续可以做的

- **split APK 合并**：把 `split_config.*.apk` 的 dex 合并、资源按包 ID 重映射进 base。
- **每副本独立签名**：生成时现造密钥，进一步降低副本之间的耦合。
- **图标角标**：给分身图标右上角叠一个序号，桌面上一眼区分（目前靠名称区分）。
- **按副本冻结 / 隐藏**：`pm set-app-link`、`suspend` 之类。
- **批量任务可取消**：目前中止批量的唯一办法是在系统安装弹窗上点取消（批量条没有「停止剩余」按钮）。
- **分身记录导出 / 导入**：`clones.json` 换机迁移时用得上（注意内置证书不会跟着走）。
