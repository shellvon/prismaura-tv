const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function runtime(onRequest, onCookie = () => ({ok:true,value:true})) {
  const waiters = new Map();
  const context = {console, setTimeout, clearTimeout, URL, URLSearchParams, TextEncoder, TextDecoder, atob, btoa,
    PrismBridge: {
      request(id, request) { onRequest(id, JSON.parse(request), context); },
      result(id, result) { waiters.get(id)?.(JSON.parse(result)); },
      cookies(action, data) { return JSON.stringify(onCookie(action, JSON.parse(data))); }
    }};
  context.window = context;
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../../../app/src/main/assets/plugin-runtime.js'), 'utf8'), context);
  return { context, call(method, args) { return new Promise(resolve => { waiters.set('call', resolve); context.PrismEngine.invoke('call', method, args); }); } };
}

test('MusicFree search uses axios and plugin variables and preserves source data', async () => {
  const r = runtime((id, request, context) => {
    assert.match(request.url, /source=netease/);
    assert.match(request.url, /name=hello/);
    context.PrismEngine.networkComplete(id, {ok:true,status:200,statusText:'OK',headers:{'content-type':'application/json'},body:'[{"id":"42","secret":"kept"}]'});
  });
  const meta = JSON.parse(r.context.PrismEngine.load(`const axios = require('axios'); module.exports = { platform:'fixture', userVariables:[{key:'source'}], async search(q) { const res = await axios.get('https://example.test/search', {params:{name:q, source:env.getUserVariables().source}}); return {data:res.data}; } };`, {source:'netease'}));
  assert.equal(meta.platform, 'fixture');
  const result = await r.call('search', ['hello', 1, 'music']);
  assert.equal(result.ok, true);
  assert.equal(result.value.data[0].secret, 'kept');
});

test('getMediaSource returns plugin headers and reports missing modules', async () => {
  const r = runtime(() => assert.fail('unexpected HTTP'));
  r.context.PrismEngine.load(`module.exports={platform:'fixture',getMediaSource(song){return {url:song.url,headers:{Referer:'https://example.test'}}}}`, {});
  const result = await r.call('getMediaSource', [{url:'https://example.test/track'}, 'standard']);
  assert.equal(result.value.headers.Referer, 'https://example.test');
  assert.throws(() => r.context.PrismEngine.load(`require('missing-package'); module.exports={platform:'x'}`, {}), /不支持的模块/);
});

test('describe keeps standard import hints and drops non-string entries', async () => {
  const r = runtime(() => assert.fail('unexpected HTTP'));
  const meta = JSON.parse(r.context.PrismEngine.load(`module.exports={platform:'hinted',hints:{importMusicSheet:['a',42,'b'],importMusicItem:[],other:'x'},importMusicSheet(){return []}}`, {}));
  assert.deepEqual(meta.hints, {importMusicSheet: ['a', 'b']});
  assert.equal(meta.importSheet, true);
});

test('Optional extensions declare quality and resolve MV with headers', async () => {
  const r = runtime(() => assert.fail('unexpected HTTP'));
  const meta = JSON.parse(r.context.PrismEngine.load(`module.exports={platform:'bili',supportedQualities:['128k','flac'],getMvSource(song){return {url:'https://example.test/'+btoa(song.mvId),headers:{Referer:'https://video.example.test/'}}}}`, {}));
  assert.equal(meta.mvSource, true);
  assert.deepEqual(meta.supportedQualities, ['128k','flac']);
  const result = await r.call('getMvSource', [{mvId:'BV1'}]);
  assert.equal(result.value.headers.Referer, 'https://video.example.test/');
  assert.match(result.value.url, /QlYx$/);
});

test('scan login is advertised only when both session and poll methods exist', async () => {
  const half = JSON.parse(runtime(() => assert.fail('unexpected HTTP'))
    .context.PrismEngine.load(`module.exports={platform:'half',getLoginSession(){return {session:'k'}}}`, {}));
  assert.equal(half.loginQr, false);
  const r = runtime(() => assert.fail('unexpected HTTP'));
  const meta = JSON.parse(r.context.PrismEngine.load(`module.exports={platform:'qr',
    getLoginSession(){return {session:'k',qrText:'https://example.test/login?codekey=k'}},
    pollLoginStatus(key){return key==='k'?{state:'confirmed',variables:{cookie:'MUSIC_U=abc'}}:{state:'expired'}}}`, {}));
  assert.equal(meta.loginQr, true);
  assert.equal((await r.call('getLoginSession', [])).value.qrText, 'https://example.test/login?codekey=k');
  assert.equal((await r.call('pollLoginStatus', ['k'])).value.variables.cookie, 'MUSIC_U=abc');
  assert.equal((await r.call('pollLoginStatus', ['gone'])).value.state, 'expired');
});

test('Scan login can declare several QR methods for the dialog', async () => {
  const r = runtime(() => assert.fail('unexpected HTTP'));
  const meta = JSON.parse(r.context.PrismEngine.load(`module.exports={platform:'multi',
    loginMethods:[{key:'qq',name:'QQ 扫码'},{key:'wx',name:'微信扫码'},{key:'bad'},{}],
    getLoginSession(method){return {session:method,qrText:'https://example.test/'+method}},
    pollLoginStatus(){return {state:'pending'}}}`, {}));
  assert.deepEqual(meta.loginMethods, [{key:'qq',name:'QQ 扫码'},{key:'wx',name:'微信扫码'}]);
  assert.equal((await r.call('getLoginSession', ['wx'])).value.session, 'wx');
  const plain = JSON.parse(runtime(() => assert.fail('unexpected HTTP'))
    .context.PrismEngine.load(`module.exports={platform:'single',getLoginSession(){return {session:'k'}},pollLoginStatus(){return {state:'pending'}}}`, {}));
  assert.deepEqual(plain.loginMethods, []);
});


test('axios arraybuffer returns bytes to the plugin', async () => {
  const r = runtime((id, request, context) => {
    assert.equal(request.responseType, 'arraybuffer');
    context.PrismEngine.networkComplete(id, {ok:true,status:200,statusText:'OK',headers:{},binary:true,body:'AAH/'});
  });
  r.context.PrismEngine.load(`const axios=require('axios'); module.exports={platform:'binary',async search(){const r=await axios.get('https://example.test/bytes',{responseType:'arraybuffer'});return {data:[Array.from(new Uint8Array(r.data))]}}};`, {});
  const result = await r.call('search', ['test', 1, 'music']);
  assert.deepEqual(JSON.parse(JSON.stringify(result.value.data[0])), [0, 1, 255]);
});

test('the final URL after redirects reaches the plugin in both request styles', async () => {
  const r = runtime((id, request, context) => {
    context.PrismEngine.networkComplete(id, {ok:true, url:'https://music.example.test/playlist/12345/?id=12345', status:200, statusText:'OK', headers:{}, body:'{}'});
  });
  r.context.PrismEngine.load(`const axios=require('axios'); module.exports={platform:'redirected',async search(){
    const viaAxios = await axios.get('https://short.example.test/abc').then(res => res.request.res.responseUrl);
    const viaFetch = await fetch('https://short.example.test/abc').then(res => res.url);
    return {data:[{axios:viaAxios, fetch:viaFetch}]}}};`, {});
  const result = await r.call('search', ['q', 1, 'music']);
  assert.equal(result.value.data[0].axios, 'https://music.example.test/playlist/12345/?id=12345');
  assert.equal(result.value.data[0].fetch, 'https://music.example.test/playlist/12345/?id=12345');
});

test('global fetch routes through the bridge and returns a fetch-shaped response', async () => {
  const r = runtime((id, request, context) => {
    assert.equal(request.method, 'GET');
    assert.equal(request.url, 'https://example.test/api/list?page=2');
    assert.equal(request.headers['X-Token'], 'abc');
    context.PrismEngine.networkComplete(id, {ok:true,status:200,statusText:'OK',headers:{'Content-Type':'application/json'},body:'{"items":[1,2]}'});
  });
  const meta = JSON.parse(r.context.PrismEngine.load(`module.exports={platform:'fetchy',async search(){const res=await fetch('https://example.test/api/list?page=2',{headers:{'X-Token':'abc'}});const json=await res.json();return {data:[{id:String(res.ok),title:res.headers.get('content-type'),n:json.items.length}]}}};`, {}));
  assert.equal(meta.platform, 'fetchy');
  const result = await r.call('search', ['q', 1, 'music']);
  assert.equal(result.value.data[0].id, 'true');
  assert.equal(result.value.data[0].title, 'application/json');
  assert.equal(result.value.data[0].n, 2);
});

test('global fetch resolves non-2xx like the standard instead of throwing', async () => {
  const r = runtime((id, request, context) => {
    context.PrismEngine.networkComplete(id, {ok:true,status:404,statusText:'Not Found',headers:{},body:'missing'});
  });
  r.context.PrismEngine.load(`module.exports={platform:'fetchy404',async search(){const res=await fetch('https://example.test/gone');return {data:[{ok:res.ok,status:res.status,body:await res.text()}]}}};`, {});
  const result = await r.call('search', ['q', 1, 'music']);
  assert.deepEqual(JSON.parse(JSON.stringify(result.value.data[0])), {ok:false, status:404, body:'missing'});
});

test('global fetch returns bytes for binary bodies', async () => {
  const r = runtime((id, request, context) => {
    assert.equal(request.responseType, 'arraybuffer');
    context.PrismEngine.networkComplete(id, {ok:true,status:200,statusText:'OK',headers:{},binary:true,body:'AAH/'});
  });
  r.context.PrismEngine.load(`module.exports={platform:'fetchbin',async search(){const buf=await (await fetch('https://example.test/bytes')).arrayBuffer();return {data:[Array.from(new Uint8Array(buf))]}}};`, {});
  const result = await r.call('search', ['q', 1, 'music']);
  assert.deepEqual(JSON.parse(JSON.stringify(result.value.data[0])), [0, 1, 255]);
});

test('standard metadata keeps update, cache, compound keys and variable titles', () => {
  const r = runtime(() => assert.fail('unexpected HTTP'));
  const meta = JSON.parse(r.context.PrismEngine.load(`module.exports={platform:'fixture',primaryKey:['id','cid'],srcUrl:'https://plugins.example.test/source.js',cacheControl:'no-store',userVariables:[{key:'cookie',title:'登录信息'},{key:'server',name:'服务地址',title:'兼容标题'},null]}`, {}));
  assert.deepEqual(meta.primaryKey, ['id','cid']);
  assert.equal(meta.srcUrl, 'https://plugins.example.test/source.js');
  assert.equal(meta.cacheControl, 'no-store');
  assert.deepEqual(meta.userVariables.map(v => v.name), ['登录信息', '服务地址']);
  const defaults = JSON.parse(r.context.PrismEngine.load(`module.exports={platform:'plain'}`, {}));
  assert.deepEqual(defaults.primaryKey, ['id']);
  assert.equal(defaults.cacheControl, 'no-cache');
});

test('song lyric URLs work without a getLyric implementation', async () => {
  const r = runtime((id, request, context) => {
    assert.equal(request.url, 'https://lyrics.example.test/song.lrc');
    context.PrismEngine.networkComplete(id, {ok:true,status:200,headers:{},binary:true,body:Buffer.from('[00:01.00]演示歌词').toString('base64')});
  });
  r.context.PrismEngine.load(`module.exports={platform:'lyrics'}`, {});
  const response = await r.call('getLyric', [{id:'one',lrc:'https://lyrics.example.test/song.lrc'}]);
  assert.equal(response.ok, true);
  assert.equal(response.value.rawLrc, '[00:01.00]演示歌词');
});

test('single-track import is an independent standard capability', async () => {
  const r = runtime(() => assert.fail('unexpected HTTP'));
  const meta = JSON.parse(r.context.PrismEngine.load(`module.exports={platform:'single',importMusicItem(input){return {id:input,title:'导入曲目',opaque:'kept'}}}`, {}));
  assert.equal(meta.importItem, true);
  assert.equal(meta.importSheet, false);
  const result = await r.call('importMusicItem', ['local-id']);
  assert.deepEqual(result.value, {id:'local-id',title:'导入曲目',opaque:'kept'});
});

test('MusicFree cookie module exposes promise based set and get', async () => {
  const calls = [];
  const r = runtime(() => assert.fail('unexpected HTTP'), (action, input) => {
    calls.push(action);
    return {ok:true, value:action === 'get' ? {session:{value:'saved'}} : true};
  });
  r.context.PrismEngine.load(`const c=require('@react-native-cookies/cookies').default; module.exports={platform:'cookie',async search(){await c.setFromResponse('https://example.test','session=saved'); const v=await c.get('https://example.test'); return {data:[{id:v.session.value}]}}};`, {});
  const result = await r.call('search', ['test', 1, 'music']);
  assert.equal(result.value.data[0].id, 'saved');
  assert.deepEqual(calls, ['setFromResponse', 'get']);
});

test('MusicFree tags, search types and detail calls preserve the original item', async () => {
  const r = runtime(() => assert.fail('unexpected HTTP'));
  const meta = JSON.parse(r.context.PrismEngine.load(`module.exports={
    platform:'fixture', supportedSearchType:['music','artist','album','sheet'],
    async search(q,page,type){return {data:[{id:'a',title:q,type,opaque:'kept'}],isEnd:true}},
    async getRecommendSheetTags(){return {pinned:[{id:'tag',title:'分类'}]}},
    async getRecommendSheetsByTag(tag,page){return {data:[{id:tag.id,title:'歌单',page}],isEnd:true}},
    async getMusicSheetInfo(item,page){return {musicList:[{id:item.opaque,title:'歌曲'}],isEnd:true}},
    async getArtistWorks(item,page,type){return {data:[{id:item.opaque,title:type}],isEnd:true}},
    async getAlbumInfo(item,page){return {musicList:[{id:item.opaque,title:'专辑歌曲'}],isEnd:true}},
    async getTopLists(){return [{title:'榜单',data:[{id:'top',title:'热门'}]}]},
    async getTopListDetail(item){return {musicList:[{id:item.id,title:'榜单歌曲'}]}}
  }`, {}));
  assert.deepEqual(meta.supportedSearchType, ['music','artist','album','sheet']);
  assert.equal(meta.recommendTags, true);
  assert.equal(meta.artistWorks, true);
  const artist = (await r.call('search', ['歌手', 1, 'artist'])).value.data[0];
  assert.equal((await r.call('getArtistWorks', [artist, 1, 'music'])).value.data[0].id, 'kept');
  assert.equal((await r.call('getAlbumInfo', [artist, 1])).value.musicList[0].id, 'kept');
  assert.equal((await r.call('getMusicSheetInfo', [artist, 1])).value.musicList[0].id, 'kept');
  assert.equal((await r.call('getRecommendSheetTags', [])).value.pinned[0].id, 'tag');
  assert.equal((await r.call('getRecommendSheetsByTag', [{id:'tag', title:'分类'}, 2])).value.data[0].page, 2);
  assert.equal((await r.call('getTopLists', [])).value[0].data[0].id, 'top');
  assert.equal((await r.call('getTopListDetail', [{id:'top'}])).value.musicList[0].id, 'top');
});

test('hot search capability is advertised only when the plugin implements it', async () => {
  const r = runtime(() => assert.fail('unexpected HTTP'));
  const enabled = JSON.parse(r.context.PrismEngine.load(`module.exports={platform:'hot-fixture',getHotSearch(type){return [{title:type,keyword:'模拟热词',artwork:'https://example.test/cover.png',subtitle:'榜单出处'}]}}`, {}));
  assert.equal(enabled.hotSearch, true);
  const result = await r.call('getHotSearch', ['music']);
  assert.equal(result.ok, true);
  assert.deepEqual(result.value, [{title:'music',keyword:'模拟热词',artwork:'https://example.test/cover.png',subtitle:'榜单出处'}]);
  const disabled = JSON.parse(r.context.PrismEngine.load(`module.exports={platform:'plain'}`, {}));
  assert.equal(disabled.hotSearch, false);
  const invalid = JSON.parse(r.context.PrismEngine.load(`module.exports={platform:'invalid',getHotSearch:true}`, {}));
  assert.equal(invalid.hotSearch, false);
});

test('plugin sandbox injects the MusicFree parameters, process included', async () => {
  const r = runtime(() => assert.fail('unexpected HTTP'));
  r.context.PrismEngine.load(`module.exports={platform:'env-fixture',search(){return {
    platform: process.platform, version: process.version, os: env.os, app: env.appVersion,
    cookie: process.env.userVariables.cookie, alias: typeof __musicfree_require, url: typeof URL,
    buffer: typeof Buffer
  }}}`, {cookie:'kept'}, '0.4.0');
  const result = await r.call('search', ['hello', 1, 'music']);
  assert.deepEqual(result.value, {platform:'android', version:'0.4.0', os:'android', app:'0.4.0',
    cookie:'kept', alias:'function', url:'function', buffer:'undefined'});
});

test('process is also reachable as a global for pre-captured closures', async () => {
  const r = runtime(() => assert.fail('unexpected HTTP'));
  // Bundled plugins read process from a closure the parameter never reaches.
  r.context.PrismEngine.load(`const platform = globalThis.process.platform;
    const version = window.process.version;
    module.exports={platform:'global-fixture',search(){return {platform, version}}}`,
    {}, '0.4.0');
  const result = await r.call('search', ['hello', 1, 'music']);
  assert.deepEqual(result.value, {platform:'android', version:'0.4.0'});
});
