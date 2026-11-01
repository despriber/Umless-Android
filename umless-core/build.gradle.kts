/*
 * Umless 核心：配置、界面文字、本地数据库、规则指标、大模型调用、表达教练与练习评测、后端接口注册。
 * 不依赖上游与录音 / 识别，逻辑都能在 JVM 单元测试里跑（数据库测试用 sqlite-jdbc）。见 ../umless/ARCHITECTURE.md
 */
plugins {
    id("org.fcitx.fcitx5.android.lib-convention")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.umless.core"

    sourceSets {
        named("main") { assets.srcDir(file("build/generated/umless/assets")) }
        // 单元测试直接读取共享规范里的样例（spec/cases）
        named("test") { resources.srcDir(file("build/generated/umless/assets")) }
    }

    // 日期时间用 java.time（minSdk 23），与主 App 一样启用核心库脱糖
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// 共享规范 spec/ 与主 App 网页界面都以 Umless 主仓库为准，构建时复制进 assets（spec/、web/）
val umlessRepo = providers.gradleProperty("umlessRepo").orElse("../Umless").map { rootProject.file(it) }

val syncUmlessShared by tasks.registering(Sync::class) {
    val repo = umlessRepo.get()
    from(File(repo, "spec")) { into("spec"); exclude("tools/**") }
    from(File(repo, "desktop/umless/ui/web")) { into("web") }
    into(layout.buildDirectory.dir("generated/umless/assets"))
}
tasks.named("preBuild") { dependsOn(syncUmlessShared) }
tasks.withType<Test>().configureEach {
    dependsOn(syncUmlessShared)
    // 数据库 schema 测试与桌面端 db.py 逐字比对
    systemProperty("umless.repo", umlessRepo.get().absolutePath)
}

dependencies {
    api(libs.kotlinx.coroutines)
    api(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    coreLibraryDesugaring(libs.android.desugarJDKLibs)
    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.kotlinx.coroutines.test)
}
