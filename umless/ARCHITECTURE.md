# Umless Android 架构

Umless 在 fcitx5-android 之上增加语音输入与表达教练。上游代码只做少量接入改动，Umless 的代码集中在三个模块和 `app` 里的一层薄适配。

产品设计见 Umless 主仓库 `docs/android-design.md`；两端共用的规则、提示词与测试样例见主仓库 `spec/`；网页界面的后端接口约定见主仓库 `docs/ui-api.md`。

## 已定的决定

- 应用 ID `org.umless.ime`，应用名「Umless 输入法」/「Umless」；代码包名（namespace）保持上游不变，方便合并上游。
- 主 App（概览 / 报告 / 练习 / 历史 / 设置 / 新手引导）**复用桌面端网页界面**：WebView 加载构建时从主仓库复制的 `desktop/umless/ui/web`，JS 通过桥接调用与桌面端同名的接口。
- 键盘保留上游布局，新增 Umless 配色主题并设为默认。
- 录音在调用方进程（输入法 / 主 App，有可见窗口）；识别在 `:voice` 独立进程（sherpa-onnx + SenseVoice）。
- 大模型润色、诊断、练习评测用用户自己的 OpenAI 兼容接口，提示词与桌面端一致。

## 模块

| 模块 | 包 | 内容 |
|---|---|---|
| `:umless-core` | `org.umless.core` 等 | 配置、界面文字、日志、本地数据库（与桌面端同一 schema）、规则指标（含 difflib 移植）、识别结果后处理（`TextFix`：SenseVoice 全大写英文的大小写修正）、大模型客户端与提示词、表达教练（诊断 / 习惯卡 / 概览 / 报告 / 趋势 / 历史）、练习出题与评测、耗时任务、**与平台无关的后端接口注册**、应用更新的逻辑（`org.umless.update`：清单解析、版本比较、下载续传与校验、缓存）。纯逻辑，JVM 单元测试跑共享规范样例 |
| `:umless-voice` | `org.umless.voice`、`org.umless.ime` | `:voice` 进程的识别服务（AIDL `IVoiceEngine`）、`VoiceClient`、`AudioRecorder`、`ModelManager`（下载 / 校验 / 解压）、语音输入流程 `VoiceController`（按住 → 录音 → 识别 → 润色 → 写入 → 落库、撤销 / 原话、失败兜底、密码框 / 无痕 / 黑名单）、`VoiceHost` 接口 |
| `:umless` | `org.umless.ui`、`org.umless.update` | 主 App：`UmlessActivity`（WebView + `WebViewAssetLoader` + 注入 `android-shim.js`）、JS 桥、平台相关接口（输入法状态、麦克风权限、导出日志、模型下载、麦克风测试、练习录音）；更新的 Android 部分（`AndroidUpdatePlatform`：版本 / 签名 / PackageInstaller，`UpdateInstallReceiver`：安装结果） |
| `:app` 内 `org.umless.glue` | — | `UmlessVoiceComponent`（实现 `VoiceHost`，挂在输入法视图的依赖域里）、空格键触摸钩子、语音面板窗口、Umless 主题预设 |

依赖方向：`:app` → `:umless` → `:umless-voice` → `:umless-core`。`:umless*` 不能引用上游类。

## 跨模块约定（签名不要随意改；需要改时同步所有调用方）

- `org.umless.core`：`Umless`、`UmlessConfig`、`I18n`、`UmlessLog`、`Jobs`、`Polish`、`Records`、`PracticeEval`
- `org.umless.api.UmlessApi`：`register(name, handler)` / `call(name, args)`，参数和返回值都是 `kotlinx.serialization` 的 `JsonElement`
- `org.umless.voice`：`VoiceClient`、`AudioRecorder`、`ModelManager`、AIDL `IVoiceEngine`
- `org.umless.ime.VoiceHost`、`VoiceStatus`、`CommitAction`
- `org.umless.update`：`Updater`（`REPO` 常量、`register(UpdatePlatform)`）、`UpdatePlatform`、`UpdateHttp`、`VersionCodes`

## 应用更新

规则见主仓库 `spec/update.md`，界面接口见 `docs/ui-api.md`「更新」。

- 清单 `https://github.com/despriber/Umless-Android/releases/latest/download/update.json`（下载源见下方「下载源」；配置 `update.manifest_url` 可覆盖，非 GitHub 地址总是直连）。仓库名只在 `Updater.REPO`。
- 接口（`Updater.register`，在 `UiInit` 里 `PlatformApi` 之后注册）：
  - `check_update(force)` → `{current, latest, available, skipped, notes, published_at, size, checked_at, error, not_published, can_install: true, install_hint: null, downloaded}`；网络错误不抛出，放在 `error`；所有尝试的源都对清单返回 404（还没发布正式版）时 `not_published` 为真、`error` 为 null。`force` 为假时 24 小时内用缓存，过期且开启 `update.auto_check` 才联网（关闭时只返回缓存）。`latest` 是清单里的版本（即使没有适合本机的安装包），`available` 只在有匹配本机 ABI 的 apk 且基础版本号更大时为真；`skipped` 与 `available` 相互独立。
  - `download_update()` → `{job_id}`（Jobs key `update:download`），结果 `{downloaded: true}`。
  - `install_update()` → `{status: "installing" | "needs_permission"}`；没有「安装未知应用」权限时打开系统设置页，用户返回后需再调一次。
  - `skip_update(version)` → `null`（写 `update.skipped_version`；空值清除）。
- 安装包选择：先用已安装包 versionCode 的 ABI 位（且本机支持），再按 `Build.SUPPORTED_ABIS` 顺序，取 `kind=apk`、`platform=android-<abi>` 的第一项。
- 比较：清单 `version_code`（缺省时由 `version` 算）> 已安装 `longVersionCode / 10`。
- 下载：`cacheDir/updates/umless-<完整 versionCode>.apk`，下载中为 `.part`（换源、重开都从断点续传；服务器不支持 Range 时从头写）；sha256 不一致删除并换源。校验后再核对安装包：包名相同、versionCode 大于已安装、签名证书 SHA-256 集合与已安装的完全相同（API 28+ `GET_SIGNING_CERTIFICATES` 的 `apkContentsSigners`，以下 `GET_SIGNATURES`），否则删除并拒绝。安装前再校验一次 sha256 与签名。
- 安装：PackageInstaller 会话（`MODE_FULL_INSTALL`、`setAppPackageName`、Android 12+ 请求 `USER_ACTION_NOT_REQUIRED`），结果广播到 `UpdateInstallReceiver`（显式、`FLAG_MUTABLE`）：需要确认时打开系统确认界面，失败时 Toast + 日志。
- 状态缓存在数据库 kv `update`（上次检查时间、清单原文、错误、已下载的安装包）；配置 `update.auto_check`（默认开）、`update.skipped_version`、`update.manifest_url`（后两项设置界面不显示）。
- 打开 / 回到主 App（`UmlessActivity.onStart`）时自动检查是否到期；不做后台定时任务。主 App 初始化时删除已安装（versionCode ≤ 已安装）的安装包与无法识别的文件。

## 下载源

识别模型（`ModelManager`）与更新清单 / 安装包（`Updater`）共用 `org.umless.core.DownloadSources`，只对 `https://github.com/` 的地址生效：

- 配置 `network.download_source`：`auto`（默认）/ `github`（只直连）/ `mirror`（只走镜像）；`network.mirror_url` 镜像前缀，默认 `https://gh-proxy.com/`，必须是 http(s) 地址，保存时补末尾 `/`，下载地址 = 前缀 + 原地址。
- `auto`：先试上次成功的源（数据库 kv `download_source_last`，从未成功过时先镜像），失败再换另一个；任一源下载成功（模型 sha256 通过、清单解析成功、安装包校验通过）后记住它。
- `test_download_sources()`（`CoreApi` 注册）→ `{github: {ok, ms, error}, mirror: {...}, preferred}`：同时请求两个源的 SenseVoice 模型压缩包前 1 KiB（`Range: bytes=0-1023`，200 / 206 且读到数据为可用，跟随跳转，总共约 8 秒），网络错误不抛出；`preferred` 为可用源里较快的一个（同时记为 `auto` 先试的源），都不可用时为当前记住的源。

## 版本号与发布文件

- 版本名只在 `gradle.properties` 的 `buildVersionName` 改（`MAJOR.MINOR.PATCH`）。
- `versionCode = (MAJOR×1_000_000 + MINOR×1_000 + PATCH)×10 + ABI`，ABI：armeabi-v7a=1、arm64-v8a=2、x86=3、x86_64=4（无 ABI 拆分的输出为 0）。例：0.1.2 arm64 → 10022，0.2.0 arm64 → 20002；清单里的基础号 `version_code` = versionCode / 10。
  在 `app/build.gradle.kts` 的 `androidComponents.onVariants` 里覆盖上游 build-logic 按 `Versions.baseVersionCode` 算的值（不改 build-logic）；公式与 `org.umless.update.VersionCodes` 相同，改动时两处一起改。0.1.0 / 0.1.1 的 versionCode 是上游沿用的 112，0.1.2 起都比它大，可直接覆盖安装。
- `assembleRelease` 结束后自动（`umlessReleaseApk` 任务）把正式包复制为固定文件名：
  `app/build/outputs/umless/Umless-<版本>-<arm64|armv7|x86|x86_64>.apk`（对应 arm64-v8a / armeabi-v7a / x86 / x86_64），CI 上传 Release 用这个文件；Gradle 原始输出仍是 `app/build/outputs/apk/release/org.umless.ime-<版本>-<abi>-release.apk`。

## 上游改动清单（尽量只有这些）

1. `settings.gradle.kts`、`app/build.gradle.kts`：引入模块；applicationId；启动图标（umless 模块的 `umless_ic_launcher`）；versionCode 方案与正式包固定文件名（见「版本号与发布文件」）；`gradle/libs.versions.toml` 增加依赖；`gradle.properties` 的 `buildVersionName`
2. `input/keyboard/SpaceLongPressBehavior.kt`：新增 `UmlessVoice`，设为默认
3. `input/keyboard/CommonKeyActionListener.kt`：长按空格分支调用 Umless
4. `input/keyboard/BaseKeyboard.kt`：空格键加不拦截事件的触摸钩子（松开结束、上滑取消）
5. `input/InputView.kt`：依赖域加入 `UmlessVoiceComponent`
6. `input/bar/KawaiiBarComponent.kt`：工具栏麦克风按钮打开 Umless 语音面板
7. `data/theme/ThemePreset.kt`、`ThemeManager.kt`、`ThemePrefs.kt`：Umless 主题与默认值；`ThemeManager.kt`、`ManagedThemePreference.kt`、`ThemeFilesManager.kt`、`input/FcitxInputMethodService.kt`（`onStartInputView`）：所选主题暂时读不出来时只临时用默认主题、不改写保存的选择，之后重读（规则与诊断日志在 `org.umless.glue.UmlessThemes`）；`test/.../ThemeSerializationTest.kt` 修正上游过时的断言
8. `ui/main/MainFragment.kt`：入口；`AndroidManifest.xml`：启动入口改为 Umless 主 App
9. `FcitxApplication.kt`：`:voice` 进程跳过上游初始化；主进程调用 `Umless.init`
10. `res/values*/strings.xml`：应用名
11. `res/xml/data_extraction_rules.xml`、`full_backup_content.xml`：备份排除 `umless/models`、`umless/logs`

## 构建

```
./gradlew :app:assembleDebug -PbuildABI=arm64-v8a      # 调试包
./gradlew :app:assembleRelease -PbuildABI=arm64-v8a    # 正式包（R8 + 签名），另存为 app/build/outputs/umless/Umless-<版本>-arm64.apk
./gradlew :umless-core:testDebugUnitTest                 # 共享规范样例等单元测试
```

- 正式包签名读环境变量 `SIGN_KEY_FILE`（或 `SIGN_KEY_BASE64`）、`SIGN_KEY_ALIAS`、`SIGN_KEY_PWD`（上游 build-logic 的约定）。应用内更新会核对新包与已安装包的签名证书，**所有正式包必须用同一个签名密钥**。

- 需要 Umless 主仓库在 `../Umless`（或 `-PumlessRepo=路径`），构建时复制 `spec/` 与网页界面。
- sherpa-onnx 官方 AAR 在构建时下载并校验 sha256；可用环境变量 `SHERPA_ONNX_AAR` 指向本地已下载的文件。
