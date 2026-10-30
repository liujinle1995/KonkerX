plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.huanzhi.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.huanzhi.sample"
        minSdk = 29
        // 故意停在 30：这是 90% 老应用的真实状态，也是分身回归测试的关键输入。
        // 分身若把它抬到 31+，Manifest 里那个没写 android:exported 的 MainActivity
        // 会让系统判定清单非法（INSTALL_PARSE_FAILED_MANIFEST_MALFORMED）。
        targetSdk = 30
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    lint { abortOnError = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
