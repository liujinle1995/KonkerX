package com.huanzhi.manager.core

import android.content.Context
import android.util.Base64
import android.util.Log
import com.android.apksig.ApkSigner
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * 用 apksig 给改写后的 APK 重新签名（v2）。
 * 签名密钥随管理器一起打包在 assets 里 —— 自用场景够用，
 * 但如果要分发给别人，务必改成首次运行时在设备本地生成。
 */
object SigningEngine {

    private const val TAG = "HuanZhi"
    private const val ASSET_KEY = "key.pem"
    private const val ASSET_CERT = "cert.pem"

    @Volatile
    private var cachedKey: PrivateKey? = null

    @Volatile
    private var cachedCert: X509Certificate? = null

    fun sign(ctx: Context, input: File, output: File) {
        val key = cachedKey ?: loadKey(ctx).also { cachedKey = it }
        val cert = cachedCert ?: loadCert(ctx).also { cachedCert = it }

        val config = ApkSigner.SignerConfig.Builder("huanzhi", key, listOf(cert)).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(false)
            .setMinSdkVersion(31)
            .build()
            .sign()

        Log.i(TAG, "signed -> ${output.name} (${output.length()} bytes)")
    }

    private fun loadKey(ctx: Context): PrivateKey {
        val der = readPem(ctx, ASSET_KEY)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    private fun loadCert(ctx: Context): X509Certificate {
        val der = readPem(ctx, ASSET_CERT)
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun readPem(ctx: Context, name: String): ByteArray {
        val text = ctx.assets.open(name).bufferedReader().use { it.readText() }
        val body = text
            .replace(Regex("-----[A-Z ]+-----"), "")
            .replace(Regex("\\s"), "")
        return Base64.decode(body, Base64.DEFAULT)
    }
}
