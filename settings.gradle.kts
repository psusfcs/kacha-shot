// ============================================================================
//  咔嚓截屏 —— 工程设置
//
//  新工程，跟 GlobalAiHelper 完全独立。
//  两者共用的只有：① 同一套固定签名（覆盖安装不打架）；
//                ② 同一个 GitHub Actions 云编译流程（本机没有 Android SDK）。
//
//  ★ 关于国内镜像：
//    GitHub 云编译跑在海外机器上，能直连谷歌/中央仓库，**不需要**国内镜像；
//    而且镜像若排在 google() 前面，镜像站一旦对海外 IP 返回 403，
//    Gradle 会把整个仓库标记为不可用，直接导致依赖解析全盘失败。
//    所以这里默认只用官方源。
// ============================================================================

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "KachaShot"
include(":app")
