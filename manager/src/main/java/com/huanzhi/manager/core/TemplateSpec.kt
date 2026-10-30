package com.huanzhi.manager.core

/**
 * 分身包名规则。
 *
 * 每个分身是一个真实安装的 APK，包名由序号生成：
 *   huanzhi.clone.k001、huanzhi.clone.k002 …
 *
 * 序号定长补零不只是好看：包名要写进清单、日志、诊断信息，
 * 等长之后按前缀批量识别（[isClonePkg]）只需要比一次长度，也便于人眼扫。
 */
object TemplateSpec {

    /** shell 模块的 applicationId */
    const val SHELL_PKG = "huanzhi.clone.k000"

    const val CLONE_PKG_PREFIX = "huanzhi.clone.k"

    /**
     * 3.4 改名之前的前缀。
     *
     * 只用来在首次启动时认出「旧版留下的分身」并提示一句 —— 它们仍是独立应用、
     * 照常能跑，但新版管理器不会再接管它们（记录是另一个应用的私有数据）。
     * 不参与任何新逻辑，将来确认没人再用就可以删掉。
     */
    private const val LEGACY_PKG_PREFIX = "konkerx.clone.k"

    fun clonePkg(id: Int): String = CLONE_PKG_PREFIX + id.toString().padStart(3, '0')

    /**
     * 是不是「本产品产出的分身」—— 新旧前缀都算。
     *
     * 旧前缀也要认：换包名之后旧分身仍然装在这台机器上，选应用页不能把它们
     * 当成普通应用列出来（否则会拿一个分身去套娃），卸载广播也不该漏掉它们。
     */
    fun isClonePkg(pkg: String): Boolean =
        pkg.length == SHELL_PKG.length &&
            (pkg.startsWith(CLONE_PKG_PREFIX) || pkg.startsWith(LEGACY_PKG_PREFIX))

    fun isLegacyClonePkg(pkg: String): Boolean =
        pkg.startsWith(LEGACY_PKG_PREFIX) && pkg.length == SHELL_PKG.length

    /** 目标包名上限，纯粹是防御性检查 */
    const val MAX_TARGET_PKG_LEN = 96
}
