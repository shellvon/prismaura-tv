/** PrismAura 0.1.0：MusicFree 常用接口 + Prism 扩展。MIT。 */
export type Awaitable<T> = T | Promise<T>;
/** 搜索实体类型；仅声明插件实际提供的类型。 */
export type SearchType = 'music' | 'artist' | 'album' | 'sheet';
/** 常用四档音质，也接受插件声明的扩展档位。 */
export type Quality = 'low' | 'standard' | 'high' | 'super' | (string & {});
/** cache 复用；no-cache 联网重查、无活动网络可复用；no-store 不缓存。 */
export type CacheControl = 'cache' | 'no-cache' | 'no-store';

export interface Entity {
  /** 平台内标识；传回后续方法。复合主键字段也必须保留。 */
  id: string | number;
  /** 主要显示名称；缺省可使用 name。 */
  title?: string;
  /** 歌手等实体的名称，作为 title 的回退。 */
  name?: string;
  /** 封面或头像 URL；缺失时显示占位图。 */
  artwork?: string;
  /** 表演者或歌手的显示名称。 */
  artist?: string;
  /** 详情页简介。 */
  description?: string;
  /** 插件自定义字段原样保留，宿主不会只保存已知字段。 */
  [field: string]: unknown;
}

export interface MusicItem extends Entity {
  /** 歌曲名称。 */
  title: string;
  /** 歌手显示名称；不是歌手实体 ID。 */
  artist: string;
  /** 歌曲所属平台；多平台插件可逐曲声明。 */
  platform?: string;
  /** 专辑显示名称。 */
  album?: string;
  /** 时长，单位为秒。 */
  duration?: number;
  /** 音频直链；未提供 getMediaSource 时作为回退。 */
  url?: string;
  /** 内嵌歌词，支持格式同 LyricSource.rawLrc。 */
  rawLrc?: string;
  /** LRC 文件 URL；没有 getLyric 方法时由宿主获取。 */
  lrc?: string;
  /** 可用音质的键集合，用于缩小此曲的音质选项；值由插件自用。 */
  qualities?: Record<string, unknown>;
  /** Prism：歌手实体 ID，用于直接打开歌手页。 */
  artistId?: string;
  /** Prism：歌手头像；供本机搜索建议使用，不应填写专辑封面。 */
  artistArtwork?: string;
  /** Prism：专辑实体 ID，用于直接打开专辑页。 */
  albumId?: string;
  /** Prism：MV 平台标识，由 getMvSource 解释；不要求与歌曲 ID 相同。 */
  mvId?: string;
  /** Prism：仅用于展示；可播放性与过滤策略由插件负责，宿主不据此过滤或阻止解析。 */
  vip?: boolean;
}

/** Prism 热门搜索建议；由 type 决定头像／封面布局，点击后发起搜索。 */
export interface HotSearchItem {
  /** 显示名称，必填。 */
  title: string;
  /** 实际搜索词；省略或为空时使用 title。 */
  keyword?: string;
  /** HTTP(S) 图片 URL；歌手填头像，歌曲／专辑／歌单填封面。可省略。 */
  artwork?: string;
  /** 歌手、简介或榜单出处等辅助文字；不支持 HTML。 */
  subtitle?: string;
}

export interface MediaSource {
  /** 可播放 HTTP(S) 地址。 */
  url: string;
  /** 媒体请求头；头名及值会经过宿主校验。 */
  headers?: Record<string, string>;
  /** User-Agent 快捷字段，覆盖 headers 中同名项。 */
  userAgent?: string;
}

export interface LyricSource {
  /** 普通／增强 LRC、YRC、QRC 明文或常见 TTML；逐字高亮需要词级时间戳。 */
  rawLrc?: string;
  /** 带时间戳的译文；与原文按行匹配。 */
  translation?: string;
}

export interface SearchResult<T extends Entity = Entity> {
  /** 当前页结果，保留插件自定义字段。 */
  data: T[];
  /** 是否到末页；省略按 true 处理，页码从 1 开始。空页且 false 时宿主搜索会有限续查。 */
  isEnd?: boolean;
}

export interface TrackResult {
  /** 当前页曲目。 */
  musicList: MusicItem[];
  /** 是否到末页；省略按 true 处理。空页且 false 时宿主歌曲详情会有限续查。 */
  isEnd?: boolean;
  /** MusicFree：专辑详情补充，首屏合并进原始专辑实体。 */
  albumItem?: Partial<Entity>;
  /** MusicFree：歌单详情补充，首屏合并进原始歌单实体。 */
  sheetItem?: Partial<Entity>;
  /** Prism：专辑补充信息；同名信息以标准 albumItem 为准。 */
  albumInfo?: Partial<Entity> & {
    /** 唱片公司显示名称。 */
    company?: string;
    /** 可读的发行日期。 */
    publishDate?: string;
    /** 发行时间，Unix 毫秒。 */
    publishTime?: number;
  };
}

export interface ArtistInfo extends Partial<Entity> {
  /** 歌手专辑数量，用于信息展示。 */
  albumSize?: number;
  /** 歌手歌曲数量，用于信息展示。 */
  musicSize?: number;
}
export interface ArtistResult extends SearchResult {
  /** Prism：歌手简介、头像及作品数量，首屏合并进歌手实体。 */
  artistInfo?: ArtistInfo;
}

export interface Tag {
  /** 平台分类 ID；默认推荐可使用空串。 */
  id: string;
  /** 分类显示名称；场景入口按实际名称匹配。 */
  title: string;
  /** 分类所需的其它参数会原样传回。 */
  [field: string]: unknown;
}
export interface TagGroup {
  /** 分类组名称。 */
  title: string;
  /** 此组分类。 */
  data: Tag[];
}
export interface TagResult {
  /** 优先显示的分类。 */
  pinned?: Tag[];
  /** 分组分类；宿主汇总为可选择的标签。 */
  data?: TagGroup[];
}
export interface UserVariable {
  /** 配置键，必须非空；通过 env 读取。 */
  key: string;
  /** MusicFree 配置项名称。 */
  title?: string;
  /** 配置项显示名；宿主优先 name，其次 title，再次 key。 */
  name?: string;
  /** 输入提示，不等同于插件级 hints。 */
  hint?: string;
}
export interface PluginEnvironment {
  /** 读取此插件当前配置；不要依赖模块变量跨方法调用存续。 */
  getUserVariables(): Record<string, string>;
}

/** Prism：扫码登录会话；宿主只展示与轮询，不自行实现平台认证。 */
export interface ScanSession {
  /** 能独立恢复轮询的会话字符串，不可依赖内存状态。 */
  session: string;
  /** 二维码原文，由宿主生成图像；与图片至少提供一个。 */
  qrText?: string;
  /** 无 qrText 时使用的 PNG/JPEG base64，不带 data URL 前缀。 */
  qrImageBase64?: string;
  /** 有效秒数，宿主最多按 24 小时计；省略时由轮询状态决定。 */
  expiresIn?: number;
  /** 二维码下方说明，如使用哪个手机 App 扫码。 */
  hint?: string;
}
export interface ScanStatus {
  /** pending 等待；confirmed 成功；expired 过期；failed 失败，后三者停止轮询。 */
  state: 'pending' | 'confirmed' | 'expired' | 'failed';
  /** 当前状态说明。 */
  message?: string;
  /** confirmed 时合并进当前配置，不清空未返回的字段。可能含凭据。 */
  variables?: Record<string, string>;
  /** 非成功状态时，可换成新的轮询会话。 */
  session?: string;
  /** 可替换当前二维码原文。 */
  qrText?: string;
  /** 可替换当前二维码图片，格式同 ScanSession。 */
  qrImageBase64?: string;
  /** 替换后的有效秒数；不提供则沿用当前截止时间。 */
  expiresIn?: number;
}
export interface LoginMethod {
  /** 传给 getLoginSession 的方式标识。 */
  key: string;
  /** TV 上的方式名称，如“网页端码”。 */
  name: string;
}

/** Prism：只允许关闭空实现能力；不能用 true 声明不存在的方法。 */
export type CapabilityOverrides = Partial<Record<
  'recommendTags' | 'recommendSheets' | 'sheetInfo' | 'albumInfo' | 'artistWorks' |
  'topLists' | 'topDetail' | 'lyric' | 'mvSource' | 'hotSearch' | 'loginQr', false>>;

export interface PrismPlugin {
  /** 必填的非空插件名称。 */
  platform: string;
  /** 作者显示名。 */
  author?: string;
  /** 插件版本，用于展示和更新降级保护。建议 SemVer。 */
  version?: string;
  /** 插件介绍，不应写入真实凭据。 */
  description?: string;
  /** 实体标识字段，默认 ['id']；顺序影响复合标识。 */
  primaryKey?: string[];
  /** 更新地址；相对 URL 按原安装地址解析，省略则使用安装地址。 */
  srcUrl?: string;
  /** 播放解析缓存策略；默认 no-cache，不下载音频。 */
  cacheControl?: CacheControl;
  /** 可搜索类型；省略时不限制宿主提供的类型。 */
  supportedSearchType?: SearchType[];
  /** Prism：可选择的音质档位，不代表已经验证码率或账号权益。 */
  supportedQualities?: Quality[];
  /** Prism：视频音质元数据，当前不提供 MV 清晰度选择。 */
  supportedVideoQualities?: string[];
  /** 插件配置字段。 */
  userVariables?: UserVariable[];
  /** Prism：收窄自动识别的能力，通常无需填写。 */
  capabilities?: CapabilityOverrides;
  /** 标准：query 为关键词，page 从 1 开始，type 决定返回的实体类型。 */
  search?(query: string, page: number, type: SearchType): Awaitable<SearchResult>;
  /** 标准：接收原始歌曲和音质档位，返回音频地址与请求头。 */
  getMediaSource?(song: MusicItem, quality: Quality): Awaitable<MediaSource | null>;
  /** 标准：返回歌词及可选译文，无歌词返回 null 或空原文。 */
  getLyric?(song: MusicItem): Awaitable<LyricSource | null>;
  /** 标准：返回歌单分类；可省略而仅提供默认推荐。 */
  getRecommendSheetTags?(): Awaitable<TagResult>;
  /** 标准：接收完整原始 tag，返回指定分类的分页歌单。 */
  getRecommendSheetsByTag?(tag: Tag, page: number): Awaitable<SearchResult>;
  /** 标准：按组返回榜单实体。 */
  getTopLists?(): Awaitable<{
    /** 榜单分组名称。 */
    title: string;
    /** 完整榜单实体，原样传给详情方法。 */
    data: Entity[];
  }[]>;
  /** 标准：接收完整榜单实体，返回分页曲目。 */
  getTopListDetail?(item: Entity, page: number): Awaitable<TrackResult>;
  /** 标准：接收专辑实体，返回曲目和 albumItem 补充。 */
  getAlbumInfo?(item: Entity, page: number): Awaitable<TrackResult>;
  /** 标准：接收歌单实体，返回曲目和 sheetItem 补充。 */
  getMusicSheetInfo?(item: Entity, page: number): Awaitable<TrackResult>;
  /** 标准：music 返回歌曲，album 返回专辑；不要忽略 type。 */
  getArtistWorks?(item: Entity, page: number, type: 'music' | 'album'): Awaitable<ArtistResult>;
  /** 标准：分享链接或平台编号转为曲目数组，宿主不写回平台账号。 */
  importMusicSheet?(urlLike: string): Awaitable<MusicItem[]>;
  /** 标准：分享链接或平台编号转为单曲，宿主保存为一首歌的本机歌单。 */
  importMusicItem?(urlLike: string): Awaitable<MusicItem>;
  /** Prism：唯一推荐的 MV 解析方法，接收原始歌曲；鉴权头放对象返回值中。 */
  getMvSource?(song: MusicItem): Awaitable<MediaSource | null>;
  /** Prism：按当前搜索类型返回热门建议；无对应类型的数据时返回 []，不要跨类型填充。 */
  getHotSearch?(type: SearchType): Awaitable<HotSearchItem[]>;
  /** Prism：可选登录方式；一项直接使用，多项显示选择，省略时传空串。 */
  loginMethods?: LoginMethod[];
  /** Prism：按 method 建立会话；与 pollLoginStatus 同时存在才显示入口。 */
  getLoginSession?(method: string): Awaitable<ScanSession>;
  /** Prism：轮询 session；每次完成后约 2 秒再请求，关闭时停止。 */
  pollLoginStatus?(session: string): Awaitable<ScanStatus>;
}
