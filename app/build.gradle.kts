plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 构建目标 ABI 可由 -Pabi=x86_64 覆盖（模拟器测试用），默认真机 arm64
val targetAbi = (project.findProperty("abi") as String?) ?: "arm64-v8a"

android {
    namespace = "app.dsh.asr"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.dsh.asr"
        minSdk = 26
        // targetSdk 28 —— 与 dsh-android 保持一致（sideload 分发策略）
        targetSdk = 28
        versionCode = 10
        versionName = "0.5.0"

        ndk {
            abiFilters += listOf(targetAbi)
        }
    }

    // release 签名（v0.5.0）：与 DSH Mobile 同一 keystore，方便用户覆盖安装。
    // 凭据可用 -PksPass= / -PkeyPass= 覆盖，默认值即仓库内 keystore 的口令。
    signingConfigs {
        create("release") {
            storeFile = file("../../dsh-android/keystore/dsh-release.keystore")
            storePassword = (project.findProperty("ksPass") as String?) ?: "dshmobile2026"
            keyAlias = "dsh"
            keyPassword = (project.findProperty("keyPass") as String?) ?: "dshmobile2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    // Shizuku（v0.4.0）：用于「一键设为系统默认识别服务」——
    // 普通应用没有 WRITE_SECURE_SETTINGS，但 Shizuku 能以 adb(uid 2000) 身份
    // 执行 `settings put secure voice_recognition_service ...`，从而自动完成设置。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
}
