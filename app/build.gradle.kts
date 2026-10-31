import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名信息不进仓库：口令与密钥路径都放在根目录 keystore.properties（已 gitignore）。
// 没有该文件时 release 不签名，assembleRelease 仍可跑通（产出 app-release-unsigned.apk）。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseKeyFile: File? = keystoreProps.getProperty("storeFile")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }

android {
    namespace = "com.v5yn133n3.skp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.v5yn133n3.skp"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        // 验收机是 arm64 单 ABI；ELF 分支里的守护二进制同样只有 arm64
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (releaseKeyFile != null) {
            create("release") {
                storeFile = releaseKeyFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeyFile != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (releaseKeyFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        noCompress += "elf"
        // 模型会被整块读进内存，压缩只会多一次解压
        noCompress += "bin"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui:1.10.0")
    implementation("androidx.compose.foundation:foundation:1.10.0")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.10.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    debugImplementation("androidx.compose.ui:ui-tooling:1.10.0")
}
