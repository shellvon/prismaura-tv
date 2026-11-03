const axios = require('axios');
const modules = {
  'crypto-js': require('crypto-js'),
  dayjs: require('dayjs'),
  'big-integer': require('big-integer'),
  qs: require('qs'),
  he: require('he'),
  cheerio: require('cheerio'),
  buffer: require('buffer'),
};
let networkId = 0;
const pending = new Map();
axios.defaults.adapter = config => new Promise((resolve, reject) => {
  const id = String(++networkId);
  pending.set(id, { resolve, reject, config });
  try {
    const url = axios.getUri(config);
    PrismBridge.request(id, JSON.stringify({url, method: config.method || 'get',
      headers: config.headers || {}, body: config.data == null ? null : config.data,
      timeout: config.timeout || 15000, responseType: config.responseType || ''}));
  } catch (e) { pending.delete(id); reject(e); }
});
modules.axios = axios;
const cookieCall = (action, data = {}) => Promise.resolve().then(() => {
  const response = JSON.parse(PrismBridge.cookies(action, JSON.stringify(data)));
  if (!response.ok) throw new Error(response.error || 'Cookie 操作失败');
  return response.value;
});
const cookieManager = {
  get: url => cookieCall('get', {url}),
  set: (url, cookie) => cookieCall('set', {url, cookie}),
  setFromResponse: (url, value) => cookieCall('setFromResponse', {url, value}),
  clearAll: () => cookieCall('clearAll'),
  flush: () => Promise.resolve(true),
};
cookieManager.default = cookieManager;
modules['@react-native-cookies/cookies'] = cookieManager;
/* Plugins written against the standard fetch need it, but the WebView's own fetch would bypass
   PrismBridge: no plugin cookies, and cross-origin rules against a file:// page. Route the
   plugin-visible fetch through the same bridge axios uses so both share one code path. */
const headerMap = raw => {
  const out = {};
  if (!raw) return out;
  if (typeof raw.forEach === 'function' && !Array.isArray(raw)) { raw.forEach((value, key) => { out[key] = value; }); return out; }
  if (Array.isArray(raw)) { raw.forEach(pair => { if (pair && pair.length === 2) out[pair[0]] = pair[1]; }); return out; }
  Object.keys(raw).forEach(key => { out[key] = raw[key]; });
  return out;
};
const prismResponse = payload => {
  const raw = payload.headers || {};
  const lower = {};
  Object.keys(raw).forEach(key => { lower[key.toLowerCase()] = String(raw[key]); });
  let body = payload.body;
  if (payload.binary) body = Uint8Array.from(atob(body), ch => ch.charCodeAt(0));
  const asText = () => typeof body === 'string' ? body : new TextDecoder().decode(body);
  const response = {
    ok: payload.status >= 200 && payload.status < 300,
    status: payload.status,
    statusText: payload.statusText || '',
    url: payload.url || '',
    headers: {
      get: name => { const key = String(name).toLowerCase(); return key in lower ? lower[key] : null; },
      has: name => String(name).toLowerCase() in lower,
      forEach: fn => Object.keys(lower).forEach(key => fn(lower[key], key)),
    },
    text: () => Promise.resolve(asText()),
    json: () => Promise.resolve().then(() => JSON.parse(asText())),
    arrayBuffer: () => Promise.resolve().then(() => typeof body === 'string' ? new TextEncoder().encode(body).buffer : body.slice().buffer),
  };
  response.clone = () => prismResponse(payload);
  return response;
};
function prismFetch(input, init) {
  const options = init || {};
  if (options.signal && options.signal.aborted) {
    const aborted = new Error('请求已取消'); aborted.name = 'AbortError';
    return Promise.reject(aborted);
  }
  const request = typeof input === 'string' ? {url: input} : (input && typeof input === 'object' ? input : {url: String(input)});
  const url = request.url || String(input);
  const method = String(options.method || request.method || 'GET').toUpperCase();
  const headers = Object.assign({}, headerMap(request.headers), headerMap(options.headers));
  const payload = options.body !== undefined ? options.body : request.body;
  const body = payload == null ? null : (typeof payload === 'string' ? payload : String(payload));
  const id = String(++networkId);
  return new Promise((resolve, reject) => {
    pending.set(id, {resolve, reject, fetch: true, url, method});
    try { PrismBridge.request(id, JSON.stringify({url, method, headers, body, timeout: 20000, responseType: 'arraybuffer'})); }
    catch (e) { pending.delete(id); reject(e); }
  });
}
window.fetch = prismFetch;
let plugin = null;
window.PrismEngine = {
  load(source, variables) {
    const module = { exports: {} };
    const env = {getUserVariables: () => variables || {}};
    const requireModule = name => {
      if (!Object.prototype.hasOwnProperty.call(modules, name)) throw new Error('不支持的模块: ' + name);
      return modules[name];
    };
    new Function('module', 'exports', 'require', 'env', source)(module, module.exports, requireModule, env);
    plugin = module.exports.default || module.exports;
    if (!plugin || typeof plugin !== 'object' || typeof plugin.platform !== 'string' || !plugin.platform.trim()) throw new Error('插件缺少 platform 名称');
    // Optional declarations can turn an empty/unsupported implementation off, never invent one.
    const capability = (name, method) => typeof plugin[method] === 'function' && !(plugin.capabilities && plugin.capabilities[name] === false);
    return JSON.stringify({platform: plugin.platform, author: plugin.author || '', version: plugin.version || '', description: plugin.description || '',
      userVariables: Array.isArray(plugin.userVariables) ? plugin.userVariables.filter(field => field && typeof field.key === 'string' && field.key).map(field => Object.assign({}, field, {name: field.name || field.title || field.key})) : [],
      primaryKey: Array.isArray(plugin.primaryKey) && plugin.primaryKey.length ? plugin.primaryKey.filter(key => typeof key === 'string' && key) : ['id'],
      srcUrl: typeof plugin.srcUrl === 'string' ? plugin.srcUrl : '',
      cacheControl: ['cache', 'no-cache', 'no-store'].includes(plugin.cacheControl) ? plugin.cacheControl : 'no-cache',
      capabilities: Object.keys(plugin.capabilities || {}).reduce((disabled, key) => {
        if (plugin.capabilities[key] === false) disabled[key] = false;
        return disabled;
      }, {}),
      supportedQualities: Array.isArray(plugin.supportedQualities) ? plugin.supportedQualities : [],
      supportedVideoQualities: Array.isArray(plugin.supportedVideoQualities) ? plugin.supportedVideoQualities : [],
      mvSource: capability('mvSource', 'getMvSource'),
      supportedSearchType: Array.isArray(plugin.supportedSearchType) ? plugin.supportedSearchType : undefined,
      recommendTags: capability('recommendTags', 'getRecommendSheetTags'), recommendSheets: capability('recommendSheets', 'getRecommendSheetsByTag'),
      sheetInfo: capability('sheetInfo', 'getMusicSheetInfo'), albumInfo: capability('albumInfo', 'getAlbumInfo'),
      artistWorks: capability('artistWorks', 'getArtistWorks'), topLists: capability('topLists', 'getTopLists'), topDetail: capability('topDetail', 'getTopListDetail'),
      search: typeof plugin.search === 'function', media: typeof plugin.getMediaSource === 'function',
      lyric: capability('lyric', 'getLyric'), importSheet: typeof plugin.importMusicSheet === 'function',
      importItem: typeof plugin.importMusicItem === 'function',
      hotSearch: capability('hotSearch', 'getHotSearch'),
      /* Prism 扩展（非 MusicFree 标准）：扫码登录。两个方法齐全，电视端才显示「扫码登录」；
         缺少时配置页照常提供 Cookie 手填与扫码输入。
         loginMethods 是同一个登录入口里的几种出码方式（例如 QQ 扫码/微信扫码、桌面端码/网页端码），
         二维码弹窗会把它们画成一行动作按钮，按下即以该 key 重新调用 getLoginSession(key)；
         没有声明的插件界面保持只有一张码，标准 MusicFree 插件不会看到这个字段。 */
      loginQr: capability('loginQr', 'getLoginSession') && capability('loginQr', 'pollLoginStatus'),
      loginMethods: Array.isArray(plugin.loginMethods) ? plugin.loginMethods.filter(function (item) {
        return item && typeof item.key === 'string' && item.key && typeof item.name === 'string' && item.name;
      }) : []});
  },
  invoke(id, method, args) {
    Promise.resolve().then(() => {
      if (!plugin) throw new Error('插件尚未加载');
      if (method === 'getMediaSource' && typeof plugin[method] !== 'function') return args[0] && args[0].url ? {url: args[0].url} : null;
      if (method === 'getLyric' && typeof plugin[method] !== 'function' && args[0] && args[0].lrc) {
        return prismFetch(args[0].lrc).then(response => {
          if (!response.ok) throw new Error('歌词请求失败: HTTP ' + response.status);
          return response.text();
        }).then(rawLrc => ({rawLrc}));
      }
      if (typeof plugin[method] !== 'function') throw new Error('插件不支持 ' + method);
      return plugin[method](...args);
    }).then(value => PrismBridge.result(id, JSON.stringify({ok:true, value: value === undefined ? null : value})),
      error => PrismBridge.result(id, JSON.stringify({ok:false, error: String(error && error.message || error)})));
  },
  networkComplete(id, payload) {
    const p = pending.get(id); if (!p) return;
    pending.delete(id);
    if (!payload.ok) { p.reject(new Error(payload.error || '网络请求失败')); return; }
    if (p.fetch) { p.resolve(prismResponse(payload)); return; }
    let body = payload.body;
    if (payload.binary) {
      const bytes = atob(body);
      body = Uint8Array.from(bytes, c => c.charCodeAt(0)).buffer;
    }
    /* responseUrl 是 Node axios 的字段（response.request.res.responseUrl）：真实浏览器/Node 里
       重定向后的地址就在这里，插件靠它把手机分享的短链还原成歌单链接。桥接层没给就留空。 */
    const response = {
      data: body, status: payload.status, statusText: payload.statusText, headers: payload.headers,
      config: p.config, request: { res: { responseUrl: payload.url || '' } },
    };
    if (p.config.validateStatus && !p.config.validateStatus(payload.status)) {
      const error = new Error('Request failed with status code ' + payload.status); error.response = response; p.reject(error);
    } else p.resolve(response);
  }
};
