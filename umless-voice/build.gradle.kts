/*
 * Umless 语音：:voice 进程里的 sherpa-onnx 识别服务、录音、模型下载，以及「按住说话 → 识别 → 润色 → 写入」的流程。
 * 键盘一侧通过 org.umless.ime.VoiceHost 接入（实现在 app 的 org.umless.glue）。见 ../umless/ARCHITECTURE.md
 */
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("org.fcitx.fcitx5.android.lib-convention")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.umless.voice"

    buildFeatures {
        aidl = true
    }

    defaultConfig {
        // 正式包开启了混淆：sherpa-onnx 的 JNI 按名字访问 Kotlin 配置类的字段
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        named("main") { jniLibs.srcDir(file("build/sherpa/jni")) }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

/* ---------------------------------------------------------------- sherpa-onnx 预编译库 */

val sherpaVersion = "1.13.8"
val sherpaSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaDir = layout.buildDirectory.dir("sherpa")

/** 下载官方 AAR（sha256 校验），拆出 classes.jar 与各 ABI 的 .so。可用环境变量 SHERPA_ONNX_AAR 指向已下载的文件 */
val prepareSherpa by tasks.registering {
    val out = sherpaDir
    val cachedPath = providers.environmentVariable("SHERPA_ONNX_AAR")
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        val aar = File(dir, "sherpa-onnx-$sherpaVersion.aar")
        if (!aar.exists()) {
            dir.mkdirs()
            val cached = cachedPath.orNull?.let(::File)
            if (cached != null && cached.exists()) cached.copyTo(aar)
            else URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar")
                .toURL().openStream().use { input -> aar.outputStream().use { input.copyTo(it) } }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(aar.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == sherpaSha256) { "sherpa-onnx AAR 校验失败：$digest" }
        ZipFile(aar).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
                val target = when {
                    e.name == "classes.jar" -> File(dir, "classes.jar")
                    e.name.startsWith("jni/") -> File(dir, e.name)
                    else -> return@forEach
                }
                target.parentFile.mkdirs()
                zip.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(prepareSherpa) }

dependencies {
    api(project(":umless-core"))
    implementation(files(sherpaDir.map { it.file("classes.jar") }).builtBy(prepareSherpa))
    implementation(libs.androidx.core.ktx)
    implementation(libs.commons.compress)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
