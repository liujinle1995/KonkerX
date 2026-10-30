package com.huanzhi.manager.ui.theme

/**
 * Material 库版本带来的 attr 归属漂移，收在这里一处。
 *
 * 背景：`colorPrimary` / `colorError` 这两个 attr 原先由 Material 库自己声明，
 * 代码里写成 `com.google.android.material.R.attr.colorPrimary`。Material 1.13 起把
 * 它们删了（保留 `colorPrimaryContainer` 等 M3 专属 attr 仍归自己），改由
 * androidx.appcompat 声明、由 `Theme.Material3.*` 家族提供具体配色。
 *
 * 直接散落到各 Activity 里的后果：下次库再挪一次，就得在 N 个文件里重找。
 * 集中成一个常量后，漂移只改这一处。
 *
 * 取值本身仍然从当前主题解析（`MaterialColors.getColor`），不在这里缓存具体颜色 ——
 * 主题可以在运行时切换（设置里有浅色/深色/跟随系统），缓存颜色会把旧值钉死。
 */
// 注意：不能写成 const val。AGP 8 起库模块的 R 字段不再是编译期常量，
// 加了 const 会报 "Const 'val' initializer should be a constant value"。
// 普通 val 每次读取只是一次静态字段访问，编译期照样内联，没有额外开销。
object MaterialAttr {

    /** 品牌主色；深浅色主题下取值不同（见 values/colors.xml 与 values-night/colors.xml） */
    val colorPrimary: Int = androidx.appcompat.R.attr.colorPrimary

    /** 错误 / 破坏性操作的语义色 */
    val colorError: Int = androidx.appcompat.R.attr.colorError

    /** M3 专属，仍由 Material 库自己声明 */
    val colorPrimaryContainer: Int = com.google.android.material.R.attr.colorPrimaryContainer

    val colorOnPrimaryContainer: Int = com.google.android.material.R.attr.colorOnPrimaryContainer

    val colorSurface: Int = com.google.android.material.R.attr.colorSurface

    val colorOutlineVariant: Int = com.google.android.material.R.attr.colorOutlineVariant

    val colorOnSurfaceVariant: Int = com.google.android.material.R.attr.colorOnSurfaceVariant

    val colorOnSurface: Int = com.google.android.material.R.attr.colorOnSurface
}
