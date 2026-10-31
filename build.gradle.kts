plugins {
    // AGP 8.13 起才认识 API 37 的「37.0」小版本号平台目录（platforms;android-37.0）
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 起 Compose 编译器随 Kotlin 一起发布，版本必须与 kotlin.android 严格一致。
    // 不写这个插件、只在 buildFeatures 里开 compose，会报「Compose Compiler plugin is required」。
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
