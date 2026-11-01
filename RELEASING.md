# Umless Android 发布流程

推送 `v*` 标签（如 `v0.2.0`）→ `.github/workflows/umless-release.yml` 构建签名的正式 APK → 生成 `update.json` →
发布 GitHub Release。App 从 `https://github.com/despriber/Umless-Android/releases/latest/download/update.json`
读取更新清单（格式与 versionCode 规则见 Umless 主仓库 `spec/update.md`）。

桌面端发布、两边共用的发布脚本（`tools/release/`）、预发布与回滚的通用说明见主仓库 `docs/releasing.md`，这里只写 Android 特有的部分。

## 一次性准备

### 1. 仓库

在 GitHub 创建 `despriber/Umless-Android`（建议公开），把本地仓库推上去：

```bash
git remote rename origin upstream          # 原来的 origin 指向 fcitx5-android/fcitx5-android
git remote add origin https://github.com/despriber/Umless-Android.git
git push -u origin umless                  # Umless 的主分支；可在 GitHub 上设为默认分支
```

- **不要 `git push --tags`**：本地有上游的 `0.1.1`、`0.1.2`… 标签，推上去会和 Umless 的版本混在一起。
  发布时只推单个 `v*` 标签。
- 如果是用 GitHub 的「Fork」按钮建的仓库：Fork 默认不运行 Actions，要在 Actions 页面手动启用；
  另外 PR 默认指向上游，提 PR 时注意选对目标仓库。
- 上游的工作流（`fdroid.yml`、`nix.yml`、`publish.yml`、`pull_request.yml`）已加了
  `if: github.repository == 'fcitx5-android/fcitx5-android'`，在本仓库里会显示为「跳过」，不会运行。
  合并上游更新时如果这几个文件冲突，保留这一行即可。
- 本仓库的工作流：`umless-ci.yml`（PR 与推送时跑 Umless 模块的单元测试）、`umless-release.yml`（发布）。
  共用的构建环境在 `.github/actions/setup-umless-build/`。

### 2. 签名机密（必需）

正式包必须一直用同一把密钥签名：签名变了就不能覆盖安装，App 内更新也会因为签名校验失败而拒绝安装。
密钥库 `umless-release.jks`（别名 `umless`）只保存在维护者本机的仓库之外，并另有离线备份。

- **密钥库绝对不要提交进仓库**，也不要放在仓库目录里（本仓库 `.gitignore` 没有忽略 `*.jks`）。
- 另外备份一份到安全的地方（密码管理器 / 离线盘）。丢了就只能换新包名重新发布，已装的用户无法升级。
- 上游的 build-logic 用同一个密码作为密钥库密码和密钥密码（`keyPassword = storePassword`），所以两者必须相同。

在 Settings → Secrets and variables → Actions → **Secrets** 添加三项（或用 `gh` 命令）：

| 名称 | 值 |
|---|---|
| `SIGN_KEY_BASE64` | 密钥库文件的 base64（一整行） |
| `SIGN_KEY_PWD` | 密钥库密码 |
| `SIGN_KEY_ALIAS` | `umless` |

```bash
# Git Bash
base64 -w0 path/to/umless-release.jks | gh secret set SIGN_KEY_BASE64 -R despriber/Umless-Android
gh secret set SIGN_KEY_PWD -R despriber/Umless-Android        # 交互输入，不会留在命令历史里
gh secret set SIGN_KEY_ALIAS -R despriber/Umless-Android -b umless
```

PowerShell 生成 base64：
`[Convert]::ToBase64String([IO.File]::ReadAllBytes("path\to\umless-release.jks")) | gh secret set SIGN_KEY_BASE64 -R despriber/Umless-Android`

构建时上游的 build-logic 直接读取环境变量 `SIGN_KEY_BASE64` / `SIGN_KEY_PWD` / `SIGN_KEY_ALIAS`
（`build-logic/convention/src/main/kotlin/ProjectExtensions.kt`），解码到 `build/` 下的临时文件，构建结束删除。
任何一项缺失，发布工作流会直接失败（不会产出未签名的包）。

本地打正式包用同样的环境变量，或者 `-PsignKeyFile=path\to\umless-release.jks -PsignKeyPwd=… -PsignKeyAlias=umless`。

### 3. 仓库变量（可选，建议设置）

Settings → Secrets and variables → Actions → **Variables**：

| 名称 | 作用 |
|---|---|
| `UMLESS_CERT_SHA256` | 正式签名证书的 SHA-256。设了以后，工作流会用 `apksigner` 核对 APK 的签名证书，防止用错密钥。现有正式包（0.1.1）的证书是 `af190f911eaa7348fdb71b6bc800be1f0a5234573114a60c9d6b4b3feb05b812`（CN=Umless, O=Umless），可用 `keytool -list -v -keystore umless-release.jks -alias umless` 核对 |
| `UMLESS_REF` | 构建时使用的 Umless 主仓库分支 / 标签 / 提交，默认 `main` |

如果主仓库 `despriber/Umless` 设成了私有，再加一个机密 `UMLESS_REPO_TOKEN`：对该仓库有 Contents 只读权限的
fine-grained token。公开仓库不需要。

## 构建依赖主仓库

构建时从 Umless 主仓库复制 `spec/`（规则、提示词、测试样例）和桌面端网页界面 `desktop/umless/ui/web`
（`umless-core/build.gradle.kts` 的 `umlessRepo`）。工作流把两个仓库检出到同一层目录：

```
$GITHUB_WORKSPACE/Umless-Android    本仓库
$GITHUB_WORKSPACE/Umless            主仓库（UMLESS_REF，默认 main；同一次发布的各任务固定用同一个提交）
```

并传 `-PumlessRepo=../Umless`，和本地开发时一样。发布脚本（`prepare_release.py`、`collect_apk.py`、
`make_update_json.py`）也从主仓库的 `tools/release/` 调用。所以**发 Android 版之前，先确认主仓库 `main`
上的网页界面和 spec 是你想打包进去的版本**；需要固定时把 `UMLESS_REF` 设成主仓库的某个标签。

## 版本号

- 只改 `gradle.properties` 的 `buildVersionName`（如 `0.2.0`）。标签必须是 `v` + 这个值，不一致时工作流失败。
- versionCode 由版本名算出：`(MAJOR × 1 000 000 + MINOR × 1 000 + PATCH) × 10 + ABI`，
  ABI：armeabi-v7a=1、arm64-v8a=2、x86=3、x86_64=4。例如 0.2.0 arm64 → 20002。
  工作流会核对 APK 实际的 versionCode 等于这个公式的结果（`collect_apk.py`），不等就失败。
- `update.json` 顶层的 `version_code` 是不含 ABI 位的基础号（0.2.0 → 2000），每个 APK 另带完整 versionCode。
- 0.1.0 / 0.1.1 测试包的 versionCode 是上游沿用的 112；0.1.2 起按新公式（0.1.2 arm64 → 10022），都比它大，可以直接覆盖安装。
- **预发布**（`0.2.0-beta.1`）的 versionCode 与对应正式版（`0.2.0`）相同。装了 beta 的手机不会被提示升级到同号的正式版，
  测试机需要手动安装正式版（同签名、同 versionCode 可以覆盖安装）。
- MINOR、PATCH 不能超过 999；版本号只增不减，已发布过的版本号不要复用。

## 发布步骤

1. **改版本号**：`gradle.properties` 里 `buildVersionName=0.2.0`。
2. **写更新说明**：`docs/release-notes/0.2.0.zh.md` 和 `docs/release-notes/0.2.0.en.md`，两个都要有、不能为空，
   否则工作流失败并提示缺哪个文件。写法同主仓库（每条一句话，写给用户看；一级标题和注释会被去掉）。
3. **本地检查**（需要 `../Umless`）：
   ```bash
   ./gradlew :umless-core:testDebugUnitTest :umless-voice:testDebugUnitTest :umless:testDebugUnitTest
   python ../Umless/tools/release/prepare_release.py --tag v0.2.0 --gradle-properties gradle.properties
   ```
   想先在 CI 上试构建：Actions 页面手动运行「Umless release」（可指定主仓库的 ref），它会测试、构建、生成
   `update.json` 并作为构建产物上传，但不发布 Release。
4. **提交、打标签、推送**：
   ```bash
   git commit -am "release: 0.2.0"
   git tag -a v0.2.0 -m "Umless Android 0.2.0"
   git push origin umless
   git push origin v0.2.0
   ```
5. **工作流会做的事**（ubuntu-24.04）：
   1. `prepare`：核对标签与 `buildVersionName`、检查更新说明、生成 Release 正文；固定主仓库提交；
   2. `build`（按 ABI 的矩阵，目前只有 arm64-v8a）：检出子模块；装 JDK 21、Android SDK、NDK / CMake / Build-Tools
      （版本取自 `build-logic/convention/src/main/kotlin/Versions.kt`）、`extra-cmake-modules` 与 `gettext`；
      跑 Umless 模块的单元测试；`./gradlew :app:assembleRelease -PbuildABI=arm64-v8a -PumlessRepo=../Umless`；
      取出 APK 改名为 `Umless-<版本>-arm64.apk` 并核对 versionCode、签名；
   3. `release`：生成 `update.json`（`android-arm64-v8a` 的 `apk`，带完整 versionCode）并校验；先建草稿 Release，
      上传 APK 与 `update.json` 后再公开。带后缀的版本发布成 Pre-release，不会推送给用户。
6. **核对**：Release 页面有 APK 和 `update.json`；
   `curl -L https://github.com/despriber/Umless-Android/releases/latest/download/update.json` 返回新版本。
   在装着上一版的手机上打开 App，「设置 → 关于 → 检查更新」应提示新版本，并能下载、安装（数据保留）。

以后加 ABI（如 armeabi-v7a）：在 `umless-release.yml` 的 `build.strategy.matrix.include` 里加一项
`{abi: armeabi-v7a, short: armv7}`，发布任务会自动把所有 APK 收进同一个 `update.json`。

## App 怎么拿到更新

- 每次打开主 App，距上次检查超过 24 小时就读一次清单（先 gh-proxy 镜像，再直连），不做后台定时任务。
- 只看与本机 ABI 匹配的那一项，按 versionCode（去掉 ABI 位）比较。
- 下载到缓存目录 → 校验 sha256 → 核对 APK 签名证书与已安装的一致 → 系统安装界面覆盖安装。首次需要允许「安装未知应用」。

## 发布前测试

- 最简单：用 workflow_dispatch 构建，下载 Artifacts 里的 APK，`adb install -r Umless-0.2.0-arm64.apk` 覆盖安装到装着旧版的手机，
  检查升级后数据还在、功能正常。
- 测试 App 内更新流程：发一个预发布版（如 `v0.2.0-beta.1`），把配置 `update.manifest_url` 指向
  `https://github.com/despriber/Umless-Android/releases/download/v0.2.0-beta.1/update.json`。
  这个配置项不在设置界面里，保存在 App 私有目录的 `files/umless/config.json`（`"update": {"manifest_url": "…"}`），
  正式包只有 root 后才能改。草稿 Release 的附件要登录才能下载，App 读不到，不能用来测试。
- 发布后：立刻用一台装着上一版的手机手动「检查更新」走一遍。

## 出问题时

- **工作流失败**：Release 停在草稿或没建，用户看不到。修好后「Re-run jobs」，同一标签重跑会覆盖附件。
- **已公开的版本有问题**：先 `gh release edit v0.2.0 --prerelease -R despriber/Umless-Android`（或删除这个 Release，保留标签），
  `releases/latest` 回到上一个正式版，后续用户不会再收到它。Android 不能降级安装，修复后发补丁版 `0.2.1`。
- 更多（标签打错、旧版本线补丁、改更新说明）见主仓库 `docs/releasing.md`「出问题时」。

## Obtainium

用户也可以用 [Obtainium](https://github.com/ImranR98/Obtainium) 跟踪 GitHub Releases 自动更新：

1. Obtainium 里「添加应用」，地址填 `https://github.com/despriber/Umless-Android`。
2. 不要勾选「包含预发布版本」。
3. 「按正则表达式筛选 APK」填 `arm64\.apk$`（以后有多个 ABI 时只选与手机匹配的那个）。
4. 安装后 Obtainium 会按 Release 检查新版本。它和 App 内更新装的是同一个 APK，签名相同，混用没有问题；
   只用 Obtainium 的话可以在 App 设置里关掉自动检查更新。

签名证书 SHA-256（可用 AppVerifier 等工具核对）：
`AF:19:0F:91:1E:AA:73:48:FD:B7:1B:6B:C8:00:BE:1F:0A:52:34:57:31:14:A6:0C:9D:6B:4B:3F:EB:05:B8:12`
