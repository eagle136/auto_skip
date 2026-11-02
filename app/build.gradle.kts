import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 应用身份（applicationId + 签名）可从命令行整体替换，源码里不留死值：
 *
 *     ./gradlew assembleRelease -PappId=com.xxx.yyy -PksFile=/abs/release.jks \
 *         -PksStorePass=... -PksKeyAlias=... -PksKeyPass=...
 *
 * 不传参时的回退顺序：
 *   1. keystore.properties            —— 本地私有签名（gitignore）
 *   2. signing/open-release.properties —— 仓库内一次性公开签名
 *   3. 都不在                          —— 不签名，assembleRelease 仍能跑通
 *
 * 正式发布走 tools/make_release.py，它每次都现生成随机包名与随机密钥。
 */
fun gradleProp(name: String): String? =
    providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }

val identityProps = Properties()
val identityPropsFile: File? = listOfNotNull(
    gradleProp("ksProps")?.let { rootProject.file(it) },
    rootProject.file("keystore.properties"),
    rootProject.file("signing/open-release.properties"),
).firstOrNull { it.exists() }
identityPropsFile?.inputStream()?.use { identityProps.load(it) }

val kv = { key: String, prop: String ->
    gradleProp(prop) ?: identityProps.getProperty(key)
}
val releaseKeyFile: File? = kv("storeFile", "ksFile")
    ?.let { p -> File(p).let { if (it.isAbsolute) it else rootProject.file(p) } }
    ?.takeIf { it.exists() }

/** 随机发布脚本通过 -PappId 注入；缺省是仓库原始包名。 */
val appId: String = gradleProp("appId") ?: "com.v5yn133n3.skp"

android {
    namespace = "com.v5yn133n3.skp"
    compileSdk = 37

    defaultConfig {
        applicationId = appId
        minSdk = 35
        targetSdk = 37
        versionCode = 3
        versionName = "1.2"
        // 验收机是 arm64 单 ABI；ELF 分支里的守护二进制同样只有 arm64
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (releaseKeyFile != null) {
            create("release") {
                storeFile = releaseKeyFile
                storePassword = kv("storePassword", "ksStorePass")
                keyAlias = kv("keyAlias", "ksKeyAlias")
                keyPassword = kv("keyPassword", "ksKeyPass")
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
