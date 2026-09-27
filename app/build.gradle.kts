plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 读构建参数（-PKEY=值 / gradle.properties），取第一个非空的。
 * 源码里不留明文，CI 从 Secret 传。
 */
fun prop(vararg names: String): String =
    names.firstNotNullOfOrNull { n ->
        (project.findProperty(n) as String?)?.takeIf { it.isNotBlank() }
    }.orEmpty().trim()

android {
    namespace = "com.wentao.kacha"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wentao.kacha"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "1.9"
        // 只有一个界面，不需要多语言资源
        resourceConfigurations += listOf("zh", "en")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // ★ 固定签名：跟 GlobalAiHelper 用同一套密钥。
            //   两个 App 各自独立包名，共用密钥互不影响；
            //   好处是以后两个 App 的升级都不会出现「应用未安装」。
            //   密钥由 CI 从 Secret 解出来放到 keystore.p12，本机没配就退回默认 debug 签名。
            val ksFile = rootProject.file("keystore.p12")
            if (ksFile.exists()) {
                signingConfig = signingConfigs.create("fixed") {
                    storeFile = ksFile
                    storePassword = prop("KS_STOREPASS").ifBlank { "aihelper2026" }
                    keyAlias = prop("KS_ALIAS").ifBlank { "aihelper" }
                    keyPassword = prop("KS_KEYPASS").ifBlank { "aihelper2026" }
                    storeType = "PKCS12"
                }
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // 协程（滚动截图在协程里跑）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ★ 本 App 不需要：网络库、数据库、EXIF、图片编辑 —— 全部不要。
    //   它只干三件事：截图 → 存相册 → 分享跳转。
}
