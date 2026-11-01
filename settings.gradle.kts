pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "fcitx5-android"

include(":lib:common")
include(":lib:fcitx5")
include(":lib:fcitx5-lua")
include(":lib:libime")
include(":lib:fcitx5-chinese-addons")
include(":codegen")
include(":app")
// Umless：语音输入、教练与主 App（见 umless/ARCHITECTURE.md）
include(":umless-core")
include(":umless-voice")
include(":umless")
include(":lib:plugin-base")
include(":plugin:anthy")
include(":plugin:clipboard-filter")
include(":plugin:unikey")
include(":plugin:rime")
include(":plugin:hangul")
include(":plugin:chewing")
include(":plugin:sayura")
include(":plugin:jyutping")
include(":plugin:thai")
