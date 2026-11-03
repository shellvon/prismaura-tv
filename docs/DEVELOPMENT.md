# 开发指南

本文面向准备构建、调试或修改 PrismAura 的贡献者。了解代码职责与状态归属，请先阅读 [架构说明](ARCHITECTURE.md)；修改插件接口时同时更新 [API 参考](PLUGIN_API.md)。

## 环境

| 工具 | 当前项目使用 |
| --- | --- |
| JDK | 17 |
| Gradle Wrapper | 8.9 |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 1.9.24 |
| Android SDK | compile / target 34，最低 23 |
| Compose 编译器 | 1.5.14 |
| Node.js | 推荐 22+；用于运行时打包和测试 |

通过 `ANDROID_HOME` 或本机 `local.properties` 配置 SDK。不要提交本机 SDK 路径、签名文件、凭据、依赖目录或构建产物。

## 构建 Android 应用

```sh
./gradlew :app:assembleDebug
./gradlew :app:lintDebug :app:compileDebugAndroidTestKotlin
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。设备较多时，安装与调试命令均指定序列号：

```sh
adb devices -l
adb -s <设备序列号> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <设备序列号> shell am start -n tv.prism/.MainActivity
```

## 修改插件运行时

源码位于 `tools/plugin-runtime/runtime.js`，Android 加载 `app/src/main/assets/plugin-runtime.js`。修改源码或依赖后执行：

```sh
npm ci --prefix tools/plugin-runtime
npm run build --prefix tools/plugin-runtime
npm test --prefix tools/plugin-runtime
```

提交源码、lockfile、生成的 bundle 与 `THIRD_PARTY_NOTICES.txt`。不要手改 bundle，也不要只测试未重建的旧产物。当前打包目标是 Chrome 66；第三方插件在设备上直接执行，其语法要求由插件作者负责。

## 验证

Node 测试加载真实 bundle，模拟桥接响应，覆盖模块、元数据、标准方法、原始字段、扫码方法及网络 API。Android 集成测试经过真实 WebView、Binder、网络桥和播放器，使用 MockWebServer 与程序生成的音频：

```sh
./gradlew :app:connectedDebugAndroidTest
```

使用可重置的专用测试设备，先保留重要数据。集成测试应从干净的数据状态运行；界面演示中的曲库、恢复队列与持续动画可能影响测试 Activity 的空闲等待。编译测试源码不等于运行测试通过。电视操作路径应使用遥控器完成验证。

测试不访问真实音乐服务，不需要在线订阅。新增协议回归使用内联最小插件、MockWebServer 或 `.test` / `.invalid` 保留域名。

## 本地插件调试

自有或已获授权的插件保存在仓库外：

```sh
node tools/plugin-runtime/run-plugin.js /absolute/path/outside-repository/plugin.js "测试关键词"
```

工具支持 `PRISM_CALL`、`PRISM_ARGS` 和 `PRISM_VARS`。它会执行网络请求并打印结果；`PRISM_DEBUG` 还可能输出敏感请求头。不要把真实配置放入公开命令、日志或 Issue。该工具的 Cookie 与重定向实现不等同于 Android，最终以设备验证为准。

## 展示素材

公开截图可以展示本地已安装插件的界面，但不能包含订阅 URL、凭据、可用的登录会话或私有备份。仓库中的展示二维码使用保留域名；截图包含原创演示内容和本地配置的实际界面，软件不附带平台插件或音乐内容。

## 签名与覆盖更新

官方 APK 沿用最初安装版本的签名，包括当前使用的 debug keystore。release 是构建类型，不要求更换签名密钥；GitHub 也只是分发渠道。后续版本保持包名 `tv.prism`、同一签名，并递增 versionCode，才能直接升级已有安装。

签名通过环境变量配置，不把 keystore 或密码写入源码：

| 环境变量 | 内容 |
| --- | --- |
| PRISM_KEYSTORE_PATH | 已有 APK 所用 keystore 的绝对路径 |
| PRISM_KEYSTORE_PASSWORD | keystore 密码 |
| PRISM_KEY_ALIAS | 密钥别名 |
| PRISM_KEY_PASSWORD | 密钥密码 |

四项齐全时 debug、release 使用同一把指定密钥。沿用本机现有 debug key 时，PRISM_KEYSTORE_PATH 指向原来的 `~/.android/debug.keystore`，别名与密码使用该文件原有配置。debug 构建仍可调试；对外提供 release 构建。

未配置时 debug 使用本机默认 debug key，release 会明确拒绝构建，避免 CI 临时生成另一把密钥。备份并长期保留原始 keystore；不能删除后重新生成，也不能使用 GitHub runner 上新生成的 debug key 替代。同名文件、相同别名或密码不代表同一把密钥。首次发布前对比已安装版本与 release 的证书 SHA-256：

```sh
./gradlew :app:assembleRelease
apksigner verify --print-certs /path/to/already-installed.apk
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## GitHub 自动发布

客户端默认查询 `shellvon/prismaura-tv`。仓库名改动或维护 fork 时，通过 `-PupdateRepository=owner/repo` 构建；工作流自动采用它所在的仓库。

首次在 GitHub 仓库 Settings → Secrets and variables → Actions 添加以下 Secrets：

- PRISM_KEYSTORE_BASE64：已有 APK 所用的原始 keystore 文件的 base64 内容；沿用 debug key 时上传本机现有文件，不新建密钥。
- PRISM_KEYSTORE_PASSWORD、PRISM_KEY_ALIAS、PRISM_KEY_PASSWORD：对应的签名配置。

这些值仅交给发布工作流，私钥和密码不进入 APK；base64 只是编码，应作为 Secret 保存。GitHub Actions 每次从 Secret 恢复同一份 keystore，不生成新的签名。每次发布：

1. 更新 app/build.gradle.kts 的 versionName 与递增的 versionCode，整理 CHANGELOG.md 的 Unreleased 条目。
2. 提交后推送与 versionName 相同的标签，例如 `v0.1.0`。
3. [release.yml](../.github/workflows/release.yml) 自动构建、lint、验签，先创建草稿、上传附件，再发布为 latest。

Release 正文自动取自 Unreleased，客户端用 GitHub 返回的 body 显示更新说明，html_url 是 GitHub 自动生成的发布页。无需每次手动创建 Release。标签与源码版本不一致、密钥缺失或产物可调试时发布会失败。已发布版本不要覆盖附件；失败留下的草稿需维护者处理后再重试。

附件为 `PrismAura.apk` 和 `latest.json`。工作流仅由版本标签触发，未配置密钥或未推送标签时不会发布。

## 更新与网络回退

客户端每天最多自动检查一次，可在「设置 → 关于」手动检查；发现新版时首页显示入口，不强制打断播放。支持版本说明、跳过版本、取消下载、手机页面二维码和系统安装确认。安装前核验 SHA-256、包名、版本名、递增 versionCode 与当前签名；不接受预发布版，不静默安装。

GitHub 元数据或 APK 下载失败时可切换自有备用站。配置方式：

```sh
./gradlew :app:assembleRelease -PupdateMirrorBase=https://updates.example.net/prismaura
```

自动发布使用仓库 Variable `UPDATE_MIRROR_BASE`，作用同上。该域名只是示例，不是项目提供的服务。没有配置备用站时只使用 GitHub；GitHub 全部不可达仍需可访问的备用站，二维码本身不能解决所有网络限制。不要把公共代理硬编码进客户端。

备用站需要维护两类文件：

- `<base>/latest.json`：使用工作流生成的清单。
- `<base>/v0.1.0/PrismAura.apk`：与 GitHub 附件字节完全一致的 APK。

先上传版本目录的 APK，再更新 latest.json，避免客户端下载到未同步的版本。设置 UPDATE_MIRROR_BASE 后，工作流生成的清单会自动填写备用 APK 地址；上传到备用站的权限和域名仍由维护者管理，当前工作流只负责 GitHub 发布。两来源回退时版本、大小和摘要必须相同，否则停止下载。

latest.json 使用 GitHub Release 的字段结构，至少包含 tag_name、body、html_url、draft=false、prerelease=false，以及 assets 中名为 PrismAura.apk 的 size、digest（sha256:…）、browser_download_url。无需维护第二份更新说明。地址须为 HTTPS；只有 debug 构建允许 localhost、127.0.0.1、10.0.2.2 的 HTTP，用于本地模拟器验证。

发布前检查版本号、兼容矩阵、CHANGELOG、签名和安装升级，检查 APK assets 仅含应用运行所需文件。平台插件、订阅、私人媒体及配置不能进入源码归档或 APK。
