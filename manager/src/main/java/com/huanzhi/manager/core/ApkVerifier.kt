package com.huanzhi.manager.core

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File

/**
 * 生成后的 APK 自检 —— 在交给 PackageInstaller 之前先问系统「你认不认这个包」。
 *
 * 为什么要这一步：安装失败时 PackageInstaller 只会给一个笼统的状态码，
 * 批量跑起来就是五条一模一样的「安装超时或失败」，完全没法定位。
 * 用 [PackageManager.getPackageArchiveInfo] 提前解析一遍，就能把
 * 「清单改写坏了」「签名没写进去」「和原应用权限撞名」这类**确定性失败**
 * 在建包阶段就翻译成人话，省掉一轮装→失败→猜的来回。
 *
 * 其中权限撞名是实测踩过的坑：分身声明了与原应用同名的权限（尤其是 SDK 带进来的
 * 非本包命名空间权限），系统会直接拒装，报 INSTALL_FAILED_DUPLICATE_PERMISSION。
 */
object ApkVerifier {

    private const val TAG = "HuanZhi"

    /**
     * @param expectTargetSdk 期望分身生效的 targetSdk（改写清单时算出来的）
     * @throws IllegalStateException 附带给用户看的原因
     */
    fun verify(
        ctx: Context,
        apk: File,
        clonePkg: String,
        targetPkg: String,
        expectTargetSdk: Int = 0,
    ) {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS
        val info = try {
            pm.getPackageArchiveInfo(apk.absolutePath, flags)
        } catch (t: Throwable) {
            throw IllegalStateException("系统无法解析生成的 APK：${t.message ?: t.javaClass.simpleName}")
        } ?: throw IllegalStateException("生成的 APK 无法被系统解析，清单改写可能有误")

        if (info.packageName != clonePkg) {
            throw IllegalStateException("生成的 APK 包名是 ${info.packageName}，期望 $clonePkg")
        }

        val si = info.signingInfo
        val signed = si != null &&
            (si.apkContentsSigners?.isNotEmpty() == true || si.signingCertificateHistory?.isNotEmpty() == true)
        if (!signed) throw IllegalStateException("生成的 APK 没有可用的签名，请重试")

        // 用系统解析出来的 targetSdk 反查我们自己写的补丁有没有真的落到字节上。
        // 补丁写偏一字节在编码层面完全看不出来，但行为会整体错位 —— 这里是最便宜的兜底。
        val parsedTarget = info.applicationInfo?.targetSdkVersion ?: 0
        if (expectTargetSdk > 0 && parsedTarget != expectTargetSdk) {
            Log.w(TAG, "targetSdk 不符：写入 $expectTargetSdk，系统解析 $parsedTarget")
            InstallLog.append(
                ctx,
                "verify WARN pkg=$clonePkg targetSdk wrote=$expectTargetSdk parsed=$parsedTarget"
            )
        }

        // 权限撞名校验：装之前就查，别等系统拒
        val mine = info.permissions?.mapNotNull { it?.name }?.toSet().orEmpty()
        if (mine.isEmpty()) {
            Log.i(TAG, "verify ok pkg=$clonePkg size=${apk.length()} perms=0")
            return
        }
        val clash = mine intersect declaredPermissions(pm, targetPkg)
        if (clash.isNotEmpty()) {
            throw IllegalStateException(
                "分身声明了与原应用同名的权限「${clash.sorted().first()}」，系统会拒绝安装" +
                    "（INSTALL_FAILED_DUPLICATE_PERMISSION）"
            )
        }
        Log.i(TAG, "verify ok pkg=$clonePkg size=${apk.length()} perms=${mine.size}")
    }

    @Suppress("DEPRECATION")
    private fun declaredPermissions(pm: PackageManager, pkg: String): Set<String> = try {
        pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            .permissions?.mapNotNull { it?.name }?.toSet().orEmpty()
    } catch (t: Throwable) {
        emptySet()
    }
}
