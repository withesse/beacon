plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

version = "0.1.0"

android {
    namespace = "com.withesse.beacon"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // XLog: mmap high-performance log writes (crash-safe) | mmap 高性能日志写入（crash 不丢日志）
    implementation("com.tencent.mars:mars-xlog:1.2.6")

    // MMKV: high-performance KV storage (config persistence) | 高性能 KV 存储（配置持久化）
    implementation("com.tencent:mmkv:1.3.4")

    // xCrash: unified Java + Native + ANR capture | Java + Native + ANR 统一捕获
    implementation("com.iqiyi.xcrash:xcrash-android-lib:3.1.0")

    // Coroutines | 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // OkHttp: network interceptor (compileOnly, host app provides) | 网络拦截器（compileOnly，宿主 App 自行引入）
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")

    // FileProvider + Lifecycle | 文件分享 + 生命周期
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
}
