/*
 * Umless 主 App：WebView 承载与桌面端相同的网页界面（来自 Umless 主仓库 desktop/umless/ui/web），
 * JS 桥接到 org.umless.api.UmlessApi；以及输入法状态、麦克风权限、导出日志等平台接口。见 ARCHITECTURE.md
 */
plugins {
    id("org.fcitx.fcitx5.android.lib-convention")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.umless"
}

dependencies {
    api(project(":umless-core"))
    api(project(":umless-voice"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
}
