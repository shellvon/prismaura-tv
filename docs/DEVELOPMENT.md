# 开发指南

本文面向准备构建、调试或修改 PrismAura 的贡献者。了解代码职责与状态归属，请先阅读 [架构说明](ARCHITECTURE.md)；修改插件接口时同时更新 [API 参考](PLUGIN_API.md)。

## 环境

| 工具 | 当前项目使用 |
| --- | --- |
| JDK | 17 |
| Gradle Wrapper | 9.3.1 |
| Android Gradle Plugin | 9.1.1 |
| Kotlin | 2.2.10 |
| Android SDK | compile 37，target 34，最低 23 |
| Compose 编译器 | Kotlin Compose 插件 2.2.10 |
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

## 手机 H5 联调

手机遥控和扫码代填由电视 APK 内置提供。在电视打开对应二维码后，用同一局域网内的手机扫码。页面资源随 APK 更新，网站目录 `site/` 是项目展示页。

本机浏览器调试 debug 包时，可将设备的固定端口转发到电脑：

```sh
adb -s <设备序列号> forward tcp:8765 tcp:8765
adb -s <设备序列号> forward tcp:8766 tcp:8766
```

8765 是手机遥控，8766 是扫码代填。浏览器访问时，将二维码 URL 的主机换成 `127.0.0.1`，保留端口、会话路径和片段。Release 包的端口由系统分配，使用实际二维码地址。

变更手机页面或遥控协议时，用准备发布的 APK 复查扫码打开、搜索点歌、播放控制和一次输入回填。若有反馈链接变更，也检查能否打开对应的邮件应用和 GitHub 页面。

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

测试不访问真实音乐服务，不需要在线订阅。MV 测试使用 `app/src/androidTest/assets/mv-test.mp4`（本地生成的 30 秒 AVC 测试画面）与支持 Range 的 MockWebServer，验证解码、拖动、重入续播和重试。新增协议回归使用内联最小插件、MockWebServer 或 `.test` / `.invalid` 保留域名。

## TV 交互约定

- 方向键默认移动焦点，确定键执行当前项。设置内容区的左键回左栏；多选项按确定打开纵向列表，上下只浏览、确定提交、返回取消。
- 选项弹层标记当前值，但浏览焦点独立于选中值；手机等外部操作改变当前值或选项数量时保留仍可用的浏览焦点，当前焦点项被移除后才恢复到可用落点。已保存的值不在可用选项中时明确提示，返回不自动修正，按确定后才保存新值。
- 从播放器菜单打开歌手／专辑详情或其搜索兜底时，返回恢复原菜单行；再返回回到更多按钮。主动选择顶栏「发现」（包括当前已选中的标签）进入推荐首页，清除旧详情返回链并回到顶部。
- 返回每次只退出当前一层，并恢复具体入口和原列表位置；入口已删除或页面已改变时才使用仍可达的备用落点。弹层回焦须等待父窗口重新获得焦点。
- 播放器默认聚焦进度条，上键首次到沉浸按钮，之后回到上次使用的顶部按钮，MV 不再可用时回退到沉浸。保留短按／长按确定和左右跳转；控制条隐藏时首个导航键只唤醒控件。切到其他按钮后，进度条不能继续拦截按键。
- 操作菜单直接执行所选动作，不加确定／取消；上下浏览到边界时停留，不用方向键退出。播放器更多菜单只用返回键逐层退出，不增加关闭或返回行；标题固定、操作提示放底部。表单与破坏性确认才使用提交／取消；破坏性确认默认聚焦取消。
- 歌曲等内容的长按或菜单键打开次要操作；普通设置、导航和关闭按钮不额外定义隐藏手势。
- 封面卡片使用统一 14dp 圆角和 2dp 焦点框，聚焦时放大至 1.035 倍并轻微抬升，进入 160ms、离开 110ms；按钮、设置行与歌曲列表行不缩放，列表行内封面也保持稳定。快速导航和列表滚动时保留即时焦点框，省略放大；减少动效时取消过渡动画。
- MV 默认聚焦进度条：左右轻按跳转 10 秒，长按预览、松开提交，确定播放／暂停；下键进入按钮区，可切换队列上下首 MV 或打开「⋯ 更多」菜单调节倍速、画面比例（适应／铺满）、单曲循环与默认关闭的参考歌词。控件隐藏时首个导航键只唤醒。手机 H5 在 MV 播放期间同步接管播放进度、跳转与 MV 模式控制。
- H5 标签保留各自滚动位置；请求期间防止重复提交，失败后恢复可重试状态。备份下载提示只说明文件已生成，不假设浏览器已完成保存。
- H5 抽屉使用浏览器返回关闭，并恢复入口焦点；搜索增量结果保留已有按钮节点。播放进度只在电视确实播放时插值，缓冲、断线和后台暂停更新；拖动后的目标保留至电视确认，切歌时清除。

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

1. 在 gradle.properties 更新 `appVersionName` 与递增的 `appVersionCode`（build.gradle.kts 读取该属性并提供 0.1.0/1 的默认值），整理 CHANGELOG.md 的 Unreleased 条目。
2. 提交后推送与 versionName 相同的标签，例如 `v0.1.0`。
3. [release.yml](../.github/workflows/release.yml) 自动构建、lint、验签，先创建草稿、上传附件，再发布为 latest。

Release 正文自动取自 Unreleased，客户端用 GitHub 返回的 body 显示更新说明，html_url 是 GitHub 自动生成的发布页。无需每次手动创建 Release。标签与源码版本不一致、密钥缺失或产物可调试时发布会失败。已发布版本不要覆盖附件；失败留下的草稿需维护者处理后再重试。

附件为 `PrismAura.apk`、`latest.json` 和同次构建的 `mapping.txt.gz`。工作流仅由版本标签触发，未配置密钥或未推送标签时不会发布。

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
