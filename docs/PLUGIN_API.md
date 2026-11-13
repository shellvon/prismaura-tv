# 插件开发参考

本文面向希望让现有 MusicFree 插件运行在 PrismAura，或为 TV 接入新能力的插件作者。描述客户端当前行为，对应版本见仓库 [变更记录](../CHANGELOG.md) 与发布页；标准参考 [MusicFree 官方协议](https://musicfree.catcat.work/plugin/protocol.html)，额外能力按本文的 Prism 扩展约定实现。

本文中的类型与数据样例用于说明接口，不是音乐源或订阅。可直接参考 [TypeScript 类型声明](plugin-api.d.ts)。

## 兼容矩阵

✅ 支持　⚠️ 部分支持　➖ 有意忽略　❌ 未接入。这里描述 PrismAura 对 MusicFree 接口的消费方式；Prism 扩展单独列在后面。

| API／字段 | 状态 | TV 行为与差异 |
| --- | :---: | --- |
| CommonJS / default 导出 | ✅ | 接受 module.exports / module.exports.default；platform 必须非空 |
| search(query, page, type) | ✅ | 歌曲、歌手、专辑、歌单；按 supportedSearchType 提供入口 |
| getMediaSource(item, quality) | ✅ | 透传有效 headers / userAgent；缺少方法时使用歌曲 url |
| getLyric(item) | ✅ | LRC、YRC、QRC 明文与常见 TTML；支持逐字高亮、译文及注音；支持歌曲 rawLrc，缺少方法时读取 lrc URL |
| getRecommendSheetTags() | ✅ | 可选分类；场景入口仅映射插件实际返回的标签 |
| getRecommendSheetsByTag(tag, page) | ✅ | 首页和发现页展示；需同时提供 getMusicSheetInfo 才列入推荐选源 |
| getTopLists() / getTopListDetail(item, page) | ✅ | 两个方法齐全才列入榜单选源；与推荐分别选择 |
| getAlbumInfo(item, page) / getMusicSheetInfo(item, page) | ✅ | 分页曲目；首屏合并标准 albumItem / sheetItem |
| getArtistWorks(item, page, type) | ⚠️ | music 分页；album 仅第 1 页，最多 60 个。供歌手页与常听歌手入口使用 |
| importMusicSheet(urlLike) / importMusicItem(urlLike) | ✅ | 共用链接导入，保存为本机歌单 |
| userVariables / env.getUserVariables() | ✅ | title / name 为显示名，hint 为输入提示 |
| primaryKey | ✅ | 复合标识随曲库及恢复快照保存 |
| srcUrl / version | ✅ | 按 srcUrl 更新，可比较版本拒绝降级，保留安装身份与配置 |
| cacheControl | ✅ | cache / no-cache / no-store，仅缓存解析结果 |
| getMusicInfo(item) | ➖ | TV 暂不补全单曲详情，必要字段随搜索／歌单结果返回 |
| getMusicComments(item) | ➖ | TV 暂不提供评论界面 |
| hints / appVersion | ➖ | 不展示自定义导入提示，不按 MusicFree 版本号限制客户端 |
| webdav、任意 RN / Node 原生模块 | ❌ | 不在内置模块表中的 require 会报错 |
| 加密 QRC、QRC XML 容器、KRC | ❌ | 插件需自行解密、提取或转换，返回下文支持的纯文本格式 |
| MV 清晰度选择 | ❌ | supportedVideoQualities 仅记录元数据 |

标准接口仍由插件决定是否实现；宿主不会为缺失的能力伪造结果。首次加载或更新脚本时识别能力，已有插件新增方法后需要更新才能刷新能力元数据。

## 模块与元数据

| 字段 | 类型 | 默认值／说明 |
| --- | --- | --- |
| `platform` | string | 必填，非空的插件名称 |
| `author` / `version` / `description` | string | 可选，版本与介绍用于管理界面 |
| `supportedSearchType` | `('music' \| 'artist' \| 'album' \| 'sheet')[]` | 未声明时不限制宿主的搜索类型；建议明确列出实际能力 |
| `primaryKey` | string[] | 默认 `['id']`；声明字段应随所有对应实体返回 |
| `srcUrl` | string | 更新地址；相对路径按最初安装地址解析，缺省使用安装地址 |
| `cacheControl` | `'cache' \| 'no-cache' \| 'no-store'` | 缺省／未知值按 no-cache 处理 |
| `userVariables` | `{ key, title?, name?, hint? }[]` | 展示名称优先 name，其次 title，再次 key；hint 是输入提示 |

用户变量通过 `env.getUserVariables()` 读取。配置字段含 password、cookie、token、secret 等敏感键名时界面会遮罩；遮罩不意味着变量已加密，见 [安全说明](../SECURITY.md)。

源码按 SHA-256 保存为应用私有执行副本，跨进程调用只传源码哈希、方法和参数；插件进程校验后缓存源码文本。每次方法调用仍重新执行模块，不应依赖模块变量跨调用存续。原始歌曲／实体 JSON 会保存并传回，宿主不裁掉插件自定义字段。

### 实体与标识

歌曲使用 `id`、`title`、`artist`、`album`、`artwork`、`duration`（**秒**）、`url?`、`rawLrc?`、`lrc?`。封面兼容 `coverImg` 回退。歌手、专辑和歌单使用相应的 id、title/name、artwork、description 等字段。

默认标识为“本机插件 ID + id”。复合主键按声明顺序将字段名和值编码为标识，不使用无分隔符的字符串拼接。例如 `['id', 'cid']` 会让相同 id、不同 cid 的歌曲分别收藏。已有曲库保留原始 JSON，重新识别插件后会对已有记录应用声明；已经合并丢失的数据无法凭空恢复。

### 更新与缓存

手机遥控的音源页也可直接导入本地 JavaScript 文件。文件导入只是客户端入口，判重、替换与更新规则和在线安装一致，不改变插件写法。

对插件作者有一条契约：识别新代码时以 `describe` 执行，该阶段不传入已安装插件的配置，也不提供 Cookie 身份——`env.getUserVariables()` 为空，Cookie 模块操作会报错。`describe` 不应依赖用户配置或登录态，配置只在正常方法调用中读取。

更新下载新脚本后先加载元数据；失败则保留旧版。srcUrl 不改变本机插件 ID 和订阅归属。对于可比较的版本，较低版本拒绝覆盖，相同版本允许更新。比较支持主／次／修订号、预发布标识及构建元数据，也兼容短数字版本；无法解析的第三方版本字符串不进行顺序判断。

订阅约每 12 小时由联网后台任务检查，另有带 6 小时间隔的启动检查。系统可延迟调度；一批订阅更新可以部分成功，不是全批事务。清单中消失的旧条目不会自动删除；移除整个订阅会移除其关联插件。

| cacheControl | 播放解析行为 |
| --- | --- |
| cache | 有解析结果缓存时复用，否则调用插件并缓存 |
| no-cache | 联网时重新调用插件；设备没有活动网络时可复用旧结果 |
| no-store | 每次调用插件，不读取或写入解析缓存 |

缓存键包含插件 ID、源码、配置、歌曲标识与音质；脚本或配置变化后不复用旧结果。最多保留 128 个结果，单个缓存 JSON 上限 64 KiB。只缓存 URL／请求头，不下载音频，因此**不代表支持离线听歌**；带有效期的地址仍可能过期，可清理缓存或使用 no-store。

## 标准方法

分页从 **1** 开始，`isEnd` 缺省按 true 处理。插件过滤后即使当前页为空，也应按上游原始分页结果返回 `isEnd`，不能将空页直接当作末页。搜索和歌曲详情遇到空页且 `isEnd: false` 时自动续查；每个音源的一次加载最多查询 5 页，获得执行槽后共享 35 秒预算，排队等待不计入，达到页数上限后可手动继续。搜索按音源分别记录实际页码，避免跳页后重复或遗漏。

| 方法与参数 | 返回值 | 用途 |
| --- | --- | --- |
| `search(query: string, page: number, type: SearchType)` | `{ data: Entity[], isEnd?: boolean }` | type 决定实体种类 |
| `getMediaSource(song: MusicItem, quality: string)` | `{ url, headers?, userAgent? } \| null` | 默认音质参数为 low / standard / high / super |
| `getLyric(song)` | `{ rawLrc?, translation? } \| null` | 返回下文支持的歌词纯文本，含逐字时间戳时自动逐字高亮；翻译按相邻时间戳匹配 |
| `getRecommendSheetTags()` | `{ pinned?: Tag[], data?: { title, data: Tag[] }[] }` | 分类项至少带 id 与 title |
| `getRecommendSheetsByTag(tag, page)` | `{ data: SheetItem[], isEnd? }` | tag 是完整原始分类对象 |
| `getTopLists()` | `{ title, data: TopListItem[] }[]` | 分组榜单 |
| `getTopListDetail(item, page)` | `{ musicList: MusicItem[], isEnd? }` | 榜单曲目 |
| `getAlbumInfo(item, page)` | `{ musicList, isEnd?, albumItem? }` | albumItem 补充首屏实体信息 |
| `getMusicSheetInfo(item, page)` | `{ musicList, isEnd?, sheetItem? }` | sheetItem 补充首屏实体信息 |
| `getArtistWorks(item, page, type)` | `{ data, isEnd? }` | type 为 music 或 album；album 本身是标准能力 |
| `importMusicSheet(urlLike: string)` | MusicItem[] | 导入歌单 |
| `importMusicItem(urlLike: string)` | MusicItem | 导入单曲 |

链接导入先尝试匹配平台的启用插件，再尝试其它有导入能力的插件；同一插件先尝试歌单接口，再尝试单曲接口。第一个返回歌曲的结果胜出，单曲也保存为本机歌单。每次最多保存 5000 首；短链、编号或私有链接能否解析由插件负责。导入不写回平台账号。

专辑／歌单优先使用 MusicFree 标准的 albumItem／sheetItem；Prism 新增 artistInfo／albumInfo 补充字段，同名信息以标准字段为准。单页展示限制：搜索每源最多 100 项，详情最多 200 首；插件应正确分页而不是一次返回无限列表。

### 歌词格式

平台仅表示常见来源，实际支持取决于插件返回的格式和时间戳。

| 格式 | 常见来源 | 当前支持 |
| --- | --- | --- |
| 普通 LRC | 通用，非平台专属 | 行级歌词 |
| 增强 LRC | 通用，非平台专属 | 含词级时间戳时逐字高亮 |
| QRC | QQ 音乐 | 仅已解密、从 XML 容器提取的正文 |
| KRC | 酷狗音乐 | 不直接支持，插件需转换为支持的格式 |
| YRC | 网易云音乐 | 支持逐字正文，不解析 JSON 元数据行 |
| TTML | [Apple Music](https://help.apple.com/itc/videoaudioassetguide/en.lproj/static.html)、[AMLL 国内社区](https://github.com/amll-dev/amll-ttml-db)，基于 [W3C 通用标准](https://www.w3.org/TR/ttml1/) | 支持常见行级／逐字歌词、内嵌译文与注音 |

`rawLrc` 支持的正文示例：

- 普通 LRC：`[00:01.00]Hello world`
- 增强 LRC：`[00:01.00]<00:01.00>Hello <00:01.50>world<00:02.00>`，末尾空时间标记可表示最后一词结束。
- YRC（网易云）：`[1000,1000](1000,500,0)Hello (1500,500,0)world`。
- QRC（QQ，已解密并从容器提取的正文）：`[1000,1000]Hello (1000,500)world(1500,500)`。

YRC／QRC 示例中的词起点均为相对歌曲开头的绝对毫秒，时长为毫秒。`[offset:毫秒]` 同时调整行与词时间。网易云歌词中的 JSON 元数据行不作为逐字歌词解析。

是否启用逐字高亮取决于插件实际返回的数据，不由歌曲所属平台决定；客户端不会额外联网补找歌词。不要直接返回加密数据或 QRC XML 容器；KRC 即使已解密，也需由插件转换成上述格式。

TTML 文本同样放入 `rawLrc`，支持 `<p>` 行、`<span>` 词时间、`begin/end/dur`，以及 Apple／AMLL 的 `x-translation`、`x-roman` 和按行 key 关联的元数据。Ruby 注音及背景人声降为附属文本，不复刻 TTML 的完整排版或多声部动画。XML 不允许 DTD／外部实体；无法解析时显示无可用歌词。

双语歌词也可分别通过 `rawLrc`（原文）和 `translation`（译文）返回。每行原文匹配起点时间最近、相差不超过 150 毫秒的一行译文；原文可逐字高亮，译文和注音在下方按行显示，不自动生成翻译或词时间。

相同时间戳的多行文本会合并为一组并去重，优先以含词时间的一行为主行，否则保留第一行为主行，其余附在下方；不猜测各行语言或角色。TV 最多显示三行附属文本，超出时省略。

## 内置模块：准确版本

这些是 PrismAura 内置模块的版本，随依赖锁定更新（见 lockfile 与 APK 中的第三方声明），不等同于 MusicFree 的打包版本。`require` 接受下表模块名及下文单列的 Cookie 适配名。

| 插件可 require 的名称 | 版本 | 说明 |
| --- | --- | --- |
| axios | 1.21.0 | 通过宿主原生网络桥请求 |
| crypto-js | 4.2.0 | JS 加解密工具 |
| dayjs | 1.11.4 | 时间处理 |
| big-integer | 1.6.51 | 大整数 |
| qs | 6.17.1 | 查询／表单序列化 |
| he | 1.2.0 | HTML 实体处理 |
| cheerio | 1.0.0-rc.12 | HTML 解析 |
| buffer | 6.0.3 | Buffer；同时注入全局 Buffer |

**Cookie 兼容适配单独说明：** 没有打包 React Native 或完整的 `@react-native-cookies/cookies` 包。为兼容使用该模块名的插件，宿主提供同名的有限桥接适配；这不代表支持其它 RN 模块。

另注入 `process`（0.11.10）及全局 fetch；process 是构建 shim，不作为可 require 的完整 Node 模块。esbuild 0.28.2 是构建工具，不提供给插件。

### 网络与 Cookie

axios 和 fetch 共享原生桥，支持 GET / HEAD / POST / PUT / PATCH / DELETE。不会使用 WebView 的直接跨源网络请求。

- axios 支持常用配置、请求头、状态校验与 arraybuffer；最终重定向地址可从 `response.request.res.responseUrl` 获取。
- fetch 接受 URL 和 method／headers／body／signal 等常用参数；返回 `ok/status/statusText/url`、headers 的 get/has/forEach，以及 text/json/arrayBuffer/clone。4xx/5xx 正常 resolve；二进制经桥按字节返回。
- fetch 不提供完整 Request/Response 流、FormData/Blob 上传和运行中的 AbortSignal 取消；只在开始前检查 aborted。其它浏览器 API 不能据此假定存在。
- Cookie 模块提供 Promise 形式的 get(url)、set(url, cookie)、setFromResponse(url, header)、clearAll()、flush()。flush 返回成功；未实现其它 RN Cookie 方法。
- Cookie 按插件隔离；显式 Cookie 头不会转发到其它源站。不要依赖自定义鉴权头跨域转发。

## Prism 扩展约定 v1

以下为本客户端识别的扩展，不作为 MusicFree 官方标准宣传；第三方生态中的同名实现仍需按参数和返回形状核对。未提供扩展不会妨碍标准功能使用。

### 能力声明与界面

宿主默认从实际方法识别能力，不要求插件重复填写完整能力清单。可用 `capabilities` 将有空实现的接口关闭，例如 `{ lyric: false }`；它只能收窄能力，不能凭 true 启用缺失方法。

可关闭的键：recommendTags、recommendSheets、sheetInfo、albumInfo、artistWorks、topLists、topDetail、lyric、mvSource、hotSearch、loginQr。loginQr 关闭后不展示登录入口。

TV 音源列表展示主要能力，配置页及 H5 音源页展示完整标签。能力以文字标签完整换行展示；“歌词”只说明存在接口，不保证每首歌有歌词；网络失败或暂无内容不等于永久不支持。

极光电台由客户端编排，以本机收藏／历史／歌单为基础，穿插榜单和推荐歌单中的曲库外歌曲。首页提供开启／继续入口；播放器的收音机图标按确定直接开启／关闭，长按或菜单键可更换待播歌曲。从播放器开启或更换待播时保留当前歌曲和进度，关闭电台后保留已有队列。临近队尾自动续曲，队列和 FM 状态随播放进度保存，重启后暂停恢复，按确定继续。主动播放其它歌单或切换普通播放模式会退出 FM。

内部首次最多编排 60 首、续曲每批最多 30 首，优先按两首熟悉曲目与一首新发现交替；这些是批次预算，不是平台返回的推荐数量。优先选择最近 80 次 FM 播放之外的曲目，候选不足时按较久未听的顺序复用；待播放队尾不重复加入。跨源的同名同歌手曲目也参与去重，不能保证识别所有录音版本。

FM 准备／续曲的额外网络请求共用 12 秒预算；不可用时继续使用已有候选和本机曲库记录。在线曲目仍需联网解析和播放，FM 不代表离线收听或平台账号的个性化推荐，也不新增插件 API。来源无可用曲目时提示重试，连续解析／播放失败会停止自动尝试。

### 音质声明

`supportedQualities: string[]` 声明宿主可选档位，歌曲可用 `qualities` 对象的键缩小可选范围。常用 low／standard／high／super；扩展顺序为：

```text
mgg → 64k → 128k → 192k → 320k → flac → flac24bit → hires → dolby → atmos → atmos_plus
```

选择偏好后依次向较低档位尝试；必要时追加 standard。未声明时提供常用四档回退，不代表已经验证真实码率或账号权益。`supportedVideoQualities` 只记录元数据，当前不驱动 MV 清晰度选择。

可选的“播放失败自动换源”默认关闭。开启后先补试本源剩余音质，再按音源列表顺序查询其他已启用且支持歌曲搜索／解析的插件，每源只查首个搜索页。严格匹配归一化后的歌名和歌手，要求双方提供时长，差值不超过 3 秒且不超过原曲的 2%；Live、伴奏等版本标记须一致，特殊版本还须专辑一致。每轮恢复最多 25 秒，失败后接回原有自动跳歌流程；换源不改写歌单或收藏，歌词和来源显示使用实际播放项。

### MV：一个字段、一个方法

这是 Prism 扩展，不属于 MusicFree 标准。接入只需两步：

1. 歌曲有 MV 时返回非空字符串 mvId；无 MV 时省略。
2. 实现 getMvSource(song)，读取原始歌曲中的 mvId，返回 `{ url, headers?, userAgent? }` 或 null。

```ts
getMvSource(song: MusicItem): Awaitable<MediaSource | null>;
```

客户端仅在歌曲有 mvId 且插件实现该方法时显示 MV 入口。地址需要实时解析或鉴权时，由方法完成，媒体请求头一并返回。只接受无内嵌用户名密码的 HTTP(S) 地址；进入 MV 暂停音乐，退出按进入前状态决定是否恢复。

不存在其它 MV 直链字段、方法别名或多路回退。MV 解析失败时显示错误，不尝试另一个接口。

### 详情补充与 VIP

| 字段／返回 | 含义 |
| --- | --- |
| 歌曲 `artistId` / `albumId` | 优先用平台标识打开详情；缺省尝试插件实体搜索 |
| `getArtistWorks(..., 'music')` 的 artistInfo | description、artwork、albumSize、musicSize |
| `getAlbumInfo(...)` 的 albumInfo | Prism 补充信息；同名信息优先采用标准 albumItem |
| 歌曲 `vip: true` | 仅用于展示 VIP 标记；宿主不据此或根据凭据／Cookie 状态过滤歌曲、阻止解析 |

> 可播放性与过滤策略由插件负责，插件可根据自身解析能力和登录状态决定各接口返回哪些歌曲。凭据存在与否不是实际登录、会员或授权验证；实际播放权限由内容服务决定。

### 热搜

`getHotSearch(type: SearchType): HotSearchItem[]` 是 Prism 扩展，返回当前类型的热门搜索建议，完整定义见 [TypeScript 接口](plugin-api.d.ts)。统一返回对象数组，不使用字符串数组。

| 字段 | 类型 | 释义 |
| --- | --- | --- |
| title | string | 必填显示名称 |
| keyword | string? | 确认时搜索的词；省略或为空使用 title |
| artwork | string? | HTTP(S) 歌手头像或内容封面，省略时展示文字占位 |
| subtitle | string? | 歌手、简介或真实榜单出处等辅助文字，纯文本 |

歌曲展示为列表，歌手展示圆形头像卡片，专辑／歌单展示方形封面卡片；图片不必提供，也不需要插件控制 UI。点击建议始终在当前类型下发起搜索，不直接播放或自动跳转详情。平台没有某个类型的数据时返回空数组，不能用歌曲热搜充当热门歌手。若建议来自榜单或推荐歌单，应在 subtitle 标明出处，不伪造热度或排名。

宿主按当前勾选音源并行询问最多 3 个启用插件，每个 8 秒预算，先返回的先展示，校验字段并去重后最多展示 12 条建议。按搜索类型在当前页面复用，离开后在当前 Activity 内保留最多 16 组、每组 10 分钟；音源选择、源码或配置变化后失效。不在类型之间回退。失败时展示与该类型匹配的本机历史、常听歌手、曲库专辑或收藏歌单，并区分来源；不自动替用户搜索。

### 扫码登录

同时实现 getLoginSession 和 pollLoginStatus 才显示登录入口。

```ts
loginMethods?: { key: string; name: string }[];
getLoginSession(method: string): Promise<ScanSession> | ScanSession;
pollLoginStatus(session: string): Promise<ScanStatus> | ScanStatus;
```

| 数据 | 字段 | 说明 |
| --- | --- | --- |
| ScanSession | session: string | 必填；能恢复轮询的会话，不依赖模块变量 |
| ScanSession | qrText?: string | 二维码内容，宿主本地生成图像 |
| ScanSession | qrImageBase64?: string | 无 qrText 时使用的 PNG/JPEG base64，不带 data URL 前缀 |
| ScanSession | expiresIn?: number | 有效秒数，宿主最多按 24 小时计；未提供时依赖轮询状态 |
| ScanSession | hint?: string | 显示于二维码下方 |
| ScanStatus | state: pending / confirmed / expired / failed | 其它值按失败处理并停止轮询 |
| ScanStatus | message?: string | 当前状态文案，界面会截短长文本 |
| ScanStatus | variables?: Record<string,string> | confirmed 时合并进既有插件配置，不清空其它字段 |
| ScanStatus | session?, qrText?, qrImageBase64?, expiresIn? | 非 confirmed 时可换会话与二维码，随后轮询新会话 |

loginMethods 有多项时显示方式选择；有一项时直接传其 key，未声明时传空串。二维码至少提供 qrText 或可解码图像之一。每次轮询完成后约等 2 秒再发起下一次，不并发堆叠；到期、失败或关闭后停止。失败、过期不改已有配置。

## 资源与兼容边界

| 项目 | 当前限制 |
| --- | --- |
| 插件／订阅文件 | 单份脚本 1 MiB、订阅清单 384 KiB；订阅 1～200 个插件条目 |
| 原生 HTTP 请求体／响应体 | 各 4 MiB；请求超时 1～30 秒 |
| 普通插件方法 | 默认 35 秒，不含等待并发槽 |
| 歌单导入 | 180 秒；单曲导入使用普通预算 |
| 执行 worker | 按设备自动选择 1～4，可在播放设置调整 |
| 运行时 JS 目标 | Chrome 66；插件源码由设备原生 JS 引擎直接解析 |

`describe`、能力位（如 importItem、loginQr、hotSearch）和 PrismBridge 是宿主内部机制，不是插件应该自行调用的 API。运行环境复用且不提供恶意代码强隔离；只加载可信插件。参见 [安全说明](../SECURITY.md)。
