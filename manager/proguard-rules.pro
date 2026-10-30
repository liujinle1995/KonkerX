# ============================================================================
# 梦之分身 · R8 规则
#
# 原则：只保留「被反射 / 按类名查找」的部分，其余交给 R8 自己收缩。
# 每条 keep 都要写清楚为什么，否则以后没人敢删。
# ============================================================================

# ---- APK 签名（com.android.tools.build:apksig）----------------------------
# 这是整个应用的核心：它按 algorithm id 动态挑选签名实现，内部还有一层
# 自带 ASN.1 / 加密 provider 的类，一旦被裁掉或改名，错误会抛出在
# 「生成分身 → 签名」这一步，而不是启动时，非常难查。整包保留，代价约 1MB。
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**

# apksig 里对可选加密库的引用（运行时不一定存在）
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn sun.security.**

# ---- 崩溃栈可读性 ---------------------------------------------------------
# 不保留行号，正式包出了崩溃只能看到一串 a.a.a，等于没法排查
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses

# ---- Kotlin 协程 / Kotlin 标准库 -----------------------------------------
# 协程库自带 consumer rules，这里只兜底 kotlin 元数据反射读取的场景
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**

# ---- 枚举 ---------------------------------------------------------------
# R8 在 optimize 模式下可能把 values()/valueOf() 折掉，代码里用到就必须留
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- Parcelable（Activity 之间传参）-------------------------------------
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
