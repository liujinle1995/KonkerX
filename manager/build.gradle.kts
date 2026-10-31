import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 正式包签名：口令与密钥库分离放在仓库根目录（keystore.properties + signing/*.jks），
// 两者都要备份，缺一个就没法再发布能覆盖升级的版本
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile")
    ?.let { rootProject.file(it).exists() } == true
if (!hasReleaseKey) {
    logger.warn("找不到 keystore.properties 或 signing/huanzhi-release.jks，release 将退回调试签名，不可对外发布")
}

android {
    namespace = "com.huanzhi.manager"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.huanzhi.manager"
        minSdk = 31
        // 支持区间：Android 12 (API 31) ~ Android 17 (API 37)
        targetSdk = 37
        versionCode = 24
        versionName = "3.10"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // minSdk 31 用不到 v1（JAR 签名），明确关掉；
                // v2 显式打开：v3 单独存在时能装，但不少第三方安装器/校验工具只认 v2
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                // v4 会额外生成 .idsig 旁文件，自用分发用不上
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                // 没有密钥库时退回调试签名，至少还能装能跑（构建时会打 warning）
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        viewBinding = true
        // 设置页要显示版本号，AGP 8 起 BuildConfig 默认关闭
        buildConfig = true
        // 3.7 起界面层逐步迁到 Compose（Liquid Glass 需要背景模糊与自由绘制，
        // XML 做不了），未迁移的页面继续走 ViewBinding，两套短期内共存
        compose = true
    }

    lint { abortOnError = false }
}

dependencies {
    // material 1.14 的 POM 硬约束 core 1.16.0 / constraintlayout 2.2.1，
    // 不跟着抬会在 dependency resolution 阶段直接报 resolutionStrategy 冲突
    implementation("androidx.core:core-ktx:1.16.0")
    // 冷启动闪屏的退出动画。平台侧的 SplashScreen.setOnExitAnimationListener 是 @hide，
    // 只有官方这个兼容库把它开放出来（API 31+ 时内部直接转给平台）
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // 平板适配靠 ConstraintLayout 的 layout_constraintWidth_max 限制内容列宽
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    // 更新记录的展开/折叠靠 ChangeBounds（AutoTransition），Material 自带但显式声明更稳
    implementation("androidx.transition:transition:1.5.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // APK 签名（v1/v2/v3），Google 官方库，纯 Java 可在 Android 上跑
    implementation("com.android.tools.build:apksig:8.7.3")

    // ── Compose ──────────────────────────────────────────────────────────────
    // BOM 统一管版本：Compose 各组件的版本必须彼此对齐，单独写版本号迟早撞车。
    // 2024.12.01 = Compose 1.7.6，与 Kotlin 2.0.21 的编译器插件配套。
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // 预览与布局检查器只在 debug 编入，release 不带
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Activity 与 Compose 的粘合层（setContent / ViewCompositionStrategy）
    implementation("androidx.activity:activity-compose:1.10.1")
    // 跨 Activity/Fragment 的 ViewModel 取用（Compose 侧用 viewModel()）
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
}
