import com.android.build.api.variant.FilterConfiguration

plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
    id("org.fcitx.fcitx5.android.fcitx-component")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.fcitx.fcitx5.android"

    defaultConfig {
        // Umless：独立的应用 ID，可与原版 fcitx5 同时安装；代码包名（namespace）保持上游不变，方便合并
        applicationId = "org.umless.ime"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                targets(
                    // jni
                    "native-lib",
                    // copy fcitx5 built-in addon libraries
                    "copy-fcitx5-modules",
                    // android specific modules
                    "androidfrontend",
                    "androidkeyboard",
                    "androidnotification"
                )
            }
        }
    }

    buildFeatures {
        viewBinding = true
        resValues = true
    }

    buildTypes {
        // Umless：启动图标用 umless 模块里的青绿声波图标（调试包与正式包相同，靠应用名区分）
        release {
            resValue("mipmap", "app_icon", "@mipmap/umless_ic_launcher")
            resValue("mipmap", "app_icon_round", "@mipmap/umless_ic_launcher_round")
            resValue("string", "app_name", "@string/app_name_release")
            proguardFile("proguard-rules.pro")
        }
        debug {
            resValue("mipmap", "app_icon", "@mipmap/umless_ic_launcher")
            resValue("mipmap", "app_icon_round", "@mipmap/umless_ic_launcher_round")
            resValue("string", "app_name", "@string/app_name_debug")
        }
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }
}

/* ---------------------------------------------------------------- Umless：版本号与正式包文件名 */

// versionCode = (MAJOR×1_000_000 + MINOR×1_000 + PATCH)×10 + ABI（armeabi-v7a=1、arm64-v8a=2、x86=3、x86_64=4），
// 由 gradle.properties 的 buildVersionName 算出（Umless 主仓库 spec/update.md 第 5 节；与 :umless-core 的
// org.umless.update.VersionCodes 同一公式，改动时两处一起改）。上游按 Versions.baseVersionCode 设的每个 ABI 的
// versionCode 在 build-logic 的 onVariants 里，这里注册得更晚，会覆盖它；不改上游 build-logic
val umlessVersionName: String = buildVersionName
val umlessBaseVersionCode: Int = run {
    val m = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""").matchEntire(umlessVersionName.trim())
        ?: error("buildVersionName 必须是 MAJOR.MINOR.PATCH：$umlessVersionName")
    val (major, minor, patch) = m.destructured.toList().map { it.toInt() }
    check(minor < 1000 && patch < 1000 && major < 200) { "版本号超出 versionCode 的范围：$umlessVersionName" }
    major * 1_000_000 + minor * 1_000 + patch
}
val umlessAbiIds = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            // 没有 ABI 拆分的输出（如 AAB）ABI 位为 0
            output.versionCode.set(umlessBaseVersionCode * 10 + (umlessAbiIds[abi] ?: 0))
        }
    }
}

// 正式包另存一份固定文件名，供 CI 上传到 GitHub Release（文件名写在 spec/update.md 的清单示例里）：
// build/outputs/umless/Umless-<版本>-<arm64|armv7|x86|x86_64>.apk
val umlessReleaseApk = tasks.register<Sync>("umlessReleaseApk") {
    val version = umlessVersionName
    val shortAbi = mapOf("arm64-v8a" to "arm64", "armeabi-v7a" to "armv7", "x86_64" to "x86_64", "x86" to "x86")
    val pattern = Regex("""-(armeabi-v7a|arm64-v8a|x86_64|x86)-release\.apk$""")
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*-release.apk") }
    into(layout.buildDirectory.dir("outputs/umless"))
    rename { name -> pattern.find(name)?.let { "Umless-$version-${shortAbi.getValue(it.groupValues[1])}.apk" } ?: name }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(umlessReleaseApk) }

fcitxComponent {
    includeLibs = listOf(
        "fcitx5",
        "fcitx5-lua",
        "libime",
        "fcitx5-chinese-addons"
    )
    // exclude (delete immediately after install) tables that nobody would use
    excludeFiles = listOf("cangjie", "erbi", "qxm", "wanfeng").map {
        "usr/share/fcitx5/inputmethod/$it.conf"
    }
    installPrebuiltAssets = true
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    ksp(project(":codegen"))
    implementation(project(":lib:fcitx5"))
    implementation(project(":lib:fcitx5-lua"))
    implementation(project(":lib:libime"))
    implementation(project(":lib:fcitx5-chinese-addons"))
    implementation(project(":lib:common"))
    implementation(project(":umless"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.viewpager2)
    implementation(libs.material)
    implementation(libs.arrow.core)
    implementation(libs.arrow.functions)
    implementation(libs.imagecropper)
    implementation(libs.flexbox)
    implementation(libs.dependency)
    implementation(libs.timber)
    implementation(libs.splitties.bitflags)
    implementation(libs.splitties.dimensions)
    implementation(libs.splitties.resources)
    implementation(libs.splitties.views.dsl)
    implementation(libs.splitties.views.dsl.appcompat)
    implementation(libs.splitties.views.dsl.constraintlayout)
    implementation(libs.splitties.views.dsl.coordinatorlayout)
    implementation(libs.splitties.views.dsl.recyclerview)
    implementation(libs.splitties.views.recyclerview)
    implementation(libs.aboutlibraries.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.lifecycle.testing)
    androidTestImplementation(libs.junit)
}

configurations {
    all {
        // remove Baseline Profile Installer or whatever it is...
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
        // remove unwanted splitties libraries...
        exclude(group = "com.louiscad.splitties", module = "splitties-appctx")
        exclude(group = "com.louiscad.splitties", module = "splitties-systemservices")
    }
}
