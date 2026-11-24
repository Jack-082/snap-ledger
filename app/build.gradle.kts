plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------- 版本号：由 Git 自动生成 ----------------
// versionCode = 提交总数（每次提交 +1，保证新版本总能覆盖旧版本）
// versionName = 最近的 v* 标签：正好在标签上 → "0.1.0"；
//               标签之后又有 3 个提交 → "0.1.0-3-gabc1234"；还没有标签 → "0.0.0-12-gabc1234"
fun git(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
}.getOrNull()

val gitCommitCount: Int = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
val gitVersionName: String = git("describe", "--tags", "--match", "v[0-9]*", "--dirty")?.removePrefix("v")
    ?: "0.0.0-$gitCommitCount-g${git("rev-parse", "--short", "HEAD") ?: "unknown"}"

android {
    namespace = "io.github.snapledger"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.snapledger"
        minSdk = 28
        targetSdk = 35
        versionCode = gitCommitCount
        versionName = gitVersionName
        // 只打包 64 位 ARM 的原生库：两个识别引擎的原生库很大，每多一种架构安装包就多十几 MB。
        // 近几年的手机都是 arm64；要支持 32 位手机，在这里加上 "armeabi-v7a"。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 可选的固定签名：在 CI 里通过环境变量提供，保证每次构建的 APK 能覆盖安装。
    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                val storePw = System.getenv("SIGNING_STORE_PASSWORD")
                storePassword = storePw
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")?.takeIf { it.isNotEmpty() } ?: "snapledger"
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: storePw
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // 输出文件名带版本号，例如 SnapLedger-0.1.0-release.apk
    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "SnapLedger-${variant.versionName}-${variant.name}.apk"
        }
    }

    // 原生库和 dex 在 APK 里压缩存放（默认不压缩），下载体积小一半以上，代价是安装后多占一些存储
    packaging {
        jniLibs { useLegacyPackaging = true }
        dex { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// 单元测试的标准输出显示在构建日志里（识别样本测试会打印每一行的识别结果）
tasks.withType<Test>().configureEach {
    testLogging { showStandardStreams = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // 端侧 OCR（中文 + 拉丁字符），模型打包进 APK，不依赖 Google Play 服务和网络
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // 第二个识别引擎：PP-OCRv5 mobile（模型在 assets/ppocr），用 ONNX Runtime 运行。
    // 特意不用最新版：1.26 之后原生库体积翻倍，而这个模型只用到很老的算子（opset 7）
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    testImplementation("junit:junit:4.13.2")
    // 单元测试里 android.jar 的 org.json 是空实现，换成真实实现来读取识别数据样本
    testImplementation("org.json:json:20240303")
    // 桌面版 ONNX Runtime（同一套 Java API），让单元测试在 CI 上用真实模型识别样本图片
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
}
