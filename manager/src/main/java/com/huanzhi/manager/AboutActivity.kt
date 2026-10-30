package com.huanzhi.manager

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.huanzhi.manager.databinding.ActivityAboutBinding

/**
 * 插件说明（一屏读完）。
 *
 * 只回答三件事：怎么做的、能做到什么、做不到什么。
 * 原理细节、模块构成、运行概览读数口径都放到「技术说明」二级页，避免这一页变成 README。
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var b: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }

        val doc = DocRenderer(this, b.container)
        doc.header(
            "幻之分身 ${BuildConfig.VERSION_NAME}",
            "全量重打包 · 本机离线 · 无网络权限"
        )

        doc.section(
            DocSection(
                "它做什么",
                listOf(
                    "把一个已安装的应用整份复制成新的 APK，换一个包名重新签名后装进系统。两份应用各自有独立的数据目录和独立 UID，互相看不见对方的数据。"
                )
            )
        )

        doc.section(
            DocSection(
                "怎么做的",
                listOf(
                    "1. 读出目标应用的 APK（分片安装包会直接拒绝）",
                    "2. 只改二进制清单和资源表里的包名，主题、组件、原生库原样保留",
                    "3. 重新对齐、签名，交给系统安装器；装完就是一个普通应用"
                )
            )
        )

        doc.section(
            DocSection(
                "能做到",
                listOf(
                    "同一应用同时开多份，各自登录、各自存储",
                    "与原应用共存，原应用卸载后分身照常运行",
                    "分身可以随时重命名，数据不动（改名走覆盖安装，等同升级）",
                    "不用运行时 hook，系统升级不会整体失效"
                )
            )
        )

        doc.section(
            DocSection(
                "做不到",
                listOf(
                    "分片安装包（App Bundle 直装）不支持",
                    "自己校验签名的应用，登录、支付、风控这类场景大概率不可用；有些会直接卡在启动页",
                    "免不掉系统安装确认框，每一份都要点一次"
                )
            )
        )

        doc.link("完整技术说明：原理、模块与读数口径") {
            startActivity(Intent(this, ManualActivity::class.java))
        }
    }
}
