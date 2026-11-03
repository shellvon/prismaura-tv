#!/usr/bin/env node
/* Runs one plugin against the real network with the same runtime the app ships.
   Usage: node tools/plugin-runtime/run-plugin.js <plugin.js> [query] [page] */
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const http = require('node:http');
const https = require('node:https');
const zlib = require('node:zlib');

const root = path.resolve(__dirname, '../..');
const pluginPath = path.resolve(process.argv[2] || '');
const query = process.argv[3] || 'hello';
const page = Number(process.argv[4] || 1);
const variables = process.env.PRISM_VARS ? JSON.parse(process.env.PRISM_VARS) : {};
const callMethod = process.env.PRISM_CALL || '';
const callArgs = process.env.PRISM_ARGS ? JSON.parse(process.env.PRISM_ARGS) : [];
if (!fs.existsSync(pluginPath)) {
  console.error('usage: node tools/plugin-runtime/run-plugin.js <plugin.js> [query] [page]');
  console.error('  PRISM_VARS=\'{"cookie":"..."}\'  PRISM_CALL=getArtistWorks PRISM_ARGS=\'[{"id":"1"}]\'');
  process.exit(1);
}

const cookieJar = new Map();
const pending = new Map();
let nextId = 1;

function decodeBuffer(buffer, encoding) {
  try {
    if (encoding === 'gzip') return zlib.gunzipSync(buffer);
    if (encoding === 'deflate') return zlib.inflateSync(buffer);
    if (encoding === 'br') return zlib.brotliDecompressSync(buffer);
  } catch (e) { /* keep the raw bytes */ }
  return buffer;
}

function storeCookies(url, headers) {
  const raw = headers['set-cookie'];
  if (!raw) return;
  const list = Array.isArray(raw) ? raw : [raw];
  const host = new URL(url).host;
  const jar = cookieJar.get(host) || new Map();
  list.forEach(function (line) {
    const pair = String(line).split(';')[0];
    const index = pair.indexOf('=');
    if (index > 0) jar.set(pair.slice(0, index).trim(), pair.slice(index + 1).trim());
  });
  cookieJar.set(host, jar);
}

function cookieHeader(url) {
  const jar = cookieJar.get(new URL(url).host);
  return jar && jar.size ? Array.from(jar, function (entry) { return entry[0] + '=' + entry[1]; }).join('; ') : '';
}

function request(id, raw, redirects = 0, methodOverride) {
  let spec;
  try { spec = JSON.parse(raw); } catch (e) { fail(id, e.message); return; }
  const target = new URL(spec.url);
  const lib = target.protocol === 'https:' ? https : http;
  const method = String(methodOverride || spec.method || 'GET').toUpperCase();
  const headers = Object.assign({}, spec.headers || {});
  if (process.env.PRISM_DEBUG) console.error('REQ', method, spec.url, JSON.stringify(headers));
  const cookie = cookieHeader(spec.url);
  if (cookie) headers.cookie = cookie;
  const client = lib.request(target, { method: method, headers: headers }, function (res) {
    const chunks = [];
    res.on('data', function (chunk) { chunks.push(chunk); });
    res.on('end', function () {
      const status = res.statusCode || 0;
      const location = res.headers.location;
      if (status >= 300 && status < 400 && location && redirects < 6) {
        const next = new URL(location, spec.url).toString();
        const nextMethod = status === 303 || ((status === 301 || status === 302) && method !== 'HEAD') ? 'GET' : method;
        request(id, JSON.stringify(Object.assign({}, spec, { url: next })), redirects + 1, nextMethod);
        return;
      }
      storeCookies(spec.url, res.headers);
      const body = decodeBuffer(Buffer.concat(chunks), String(res.headers['content-encoding'] || '').toLowerCase());
      const binary = spec.responseType === 'arraybuffer';
      complete(id, {
        /* spec.url 到这里已经是跟完重定向后的地址，和电视端桥返回的 url 含义一致。 */
        ok: true, url: spec.url, status: status, statusText: res.statusMessage || '', headers: res.headers,
        binary: binary, body: binary ? body.toString('base64') : body.toString('utf8'),
      });
    });
  });
  client.on('error', function (error) { fail(id, error.message); });
  if (spec.body !== undefined && spec.body !== null && method !== 'GET' && method !== 'HEAD') client.write(String(spec.body));
  client.end();
}

function complete(id, payload) {
  const value = 'PrismEngine' in context ? context.PrismEngine.networkComplete(id, payload) : null;
  return value;
}
function fail(id, message) {
  context.PrismEngine.networkComplete(id, { ok: false, error: message });
}

function probe(url, headers) {
  return new Promise(function (resolve) {
    let target;
    try { target = new URL(url); } catch (e) { resolve('invalid URL'); return; }
    const lib = target.protocol === 'https:' ? https : http;
    const req = lib.request(target, { method: 'GET', headers: Object.assign({ Range: 'bytes=0-4095' }, headers || {}) }, function (res) {
      const status = res.statusCode || 0;
      const type = res.headers['content-type'] || '';
      res.destroy();
      resolve(status + (type ? ' · ' + type : ''));
    });
    req.on('error', function (error) { resolve('error: ' + error.message); });
    req.setTimeout(10000, function () { req.destroy(); resolve('timeout'); });
    req.end();
  });
}

const context = {
  console: console, setTimeout: setTimeout, clearTimeout: clearTimeout,
  URL: URL, URLSearchParams: URLSearchParams, TextEncoder: TextEncoder, TextDecoder: TextDecoder, atob: atob,
  PrismBridge: {
    request: function (id, spec) { request(id, spec); },
    result: function (id, raw) {
      const waiter = pending.get(id);
      pending.delete(id);
      if (waiter) waiter(JSON.parse(raw));
    },
    cookies: function () { return JSON.stringify({ ok: true, value: {} }); },
  },
};
context.window = context;
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(root, 'app/src/main/assets/plugin-runtime.js'), 'utf8'), context);

function call(method, args) {
  return new Promise(function (resolve) {
    const id = String(nextId++);
    pending.set(id, resolve);
    context.PrismEngine.invoke(id, method, args);
  });
}

(async function main() {
  const meta = JSON.parse(context.PrismEngine.load(fs.readFileSync(pluginPath, 'utf8'), variables));
  console.log(meta.platform + ' ' + (meta.version || '') + ' — ' + (meta.description || ''));
  if (callMethod) {
    const result = await call(callMethod, callArgs);
    console.log(JSON.stringify(result, null, 2));
    if (!result.ok) process.exitCode = 1;
    return;
  }
  const search = await call('search', [query, page, 'music']);
  if (!search.ok) throw new Error(String(search.error));
  const songs = (search.value && search.value.data) || [];
  console.log('search "' + query + '" page ' + page + ': ' + songs.length + ' 条, isEnd=' + search.value.isEnd);
  for (const song of songs.slice(0, 3)) {
    console.log('- ' + song.title + ' / ' + song.artist + ' · ' + (song.duration || 0) + 's');
    const source = await call('getMediaSource', [song, 'standard']);
    if (!source.ok) { console.log('  media: 失败 — ' + source.error); continue; }
    const url = source.value && source.value.url;
    console.log('  media: ' + url);
    console.log('  probe: ' + await probe(url, source.value.headers));
    if (meta.lyric) {
      const lyric = await call('getLyric', [song]);
      const text = lyric.ok && lyric.value ? lyric.value.rawLrc || '' : '';
      console.log('  lyric: ' + (text ? text.split('\n').slice(0, 2).join(' / ') : '无'));
    }
  }
})().catch(function (error) {
  console.error('failed: ' + (error && error.message ? error.message : error));
  process.exitCode = 1;
});
