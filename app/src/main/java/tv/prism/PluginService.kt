package tv.prism

import android.app.Service
import android.content.Intent
import android.os.*
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.CookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** This private process runs user-supplied JavaScript with HTTP and per-plugin Cookie APIs.
 *
 *  A worker WebView hosts its own plugin runtime, so several
 *  sources can search at once instead of queueing behind one JS thread. Workers are created on
 *  demand and capped, because every WebView costs a renderer and a TV only has so much memory.
 */
class PluginService : Service() {
    private class Worker {
        lateinit var web: WebView
        var ready = false
        var current: Message? = null
        @Volatile var pluginId = ""
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val workers = mutableListOf<Worker>()
    private val queue = ArrayDeque<Message>()
    /* The host resolves the setting and sends it with every call; this process keeps its own
       copy because its SharedPreferences cache would not see the host's write. */
    private var workerCap = 3
    private val cookieJars = ConcurrentHashMap<String, PluginCookies>()
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            0 -> runCatching { msg.replyTo.send(Message.obtain(null, 0).apply { data = Bundle().apply { putInt("pid", Process.myPid()) } }) }
            1 -> {
                val call = Message.obtain(msg)
                workerCap = call.data.getInt("workers", workerCap).coerceIn(1, MAX_WORKERS)
                if (call.data.getString("method") == "clearCookies") clearCookies(call)
                else { queue.addLast(call); dispatch() }
            }
            3 -> cancel(msg.data.getString("id") ?: "")
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun dispatch() {
        /* Lowering the setting releases idle sandboxes; busy ones finish their call first. */
        while (workers.size > workerCap) {
            val idle = workers.firstOrNull { it.current == null } ?: break
            workers.remove(idle)
            idle.ready = false
            idle.web.destroy()
        }
        while (queue.isNotEmpty()) {
            val worker = workers.firstOrNull { it.ready && it.current == null }
            if (worker != null) { start(worker, queue.removeFirst()); continue }
            if (workers.size < workerCap) createWorker()
            return
        }
    }

    private fun createWorker() {
        val worker = Worker()
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.domStorageEnabled = false
            addJavascriptInterface(Bridge(worker), "PrismBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val path = request.url.toString()
                    val asset = when (path) {
                        HOST -> "plugin-host.html"
                        "https://prism.invalid/plugin-runtime.js" -> "plugin-runtime.js"
                        else -> null
                    }
                    return if (asset != null) WebResourceResponse(if (asset.endsWith(".js")) "text/javascript" else "text/html", "UTF-8", assets.open(asset))
                    else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("typeof PrismEngine === 'object' && typeof PrismEngine.load === 'function'") { initialized ->
                        if (initialized == "true") {
                            worker.ready = true
                            dispatch()
                        } else {
                            // A missing/old WebView must give a useful error rather than a 35s timeout.
                            val error = JSONObject().put("ok", false).put("error", "插件运行环境无法启动，请更新 Android System WebView 后重试").toString()
                            while (queue.isNotEmpty()) reply(queue.removeFirst(), error)
                            workers.remove(worker)
                            worker.web.destroy()
                        }
                    }
                }
            }
        }
        worker.web = web
        workers += worker
        web.loadUrl(HOST)
    }

    private fun start(worker: Worker, msg: Message) {
        worker.current = msg
        val b = msg.data
        val method = b.getString("method") ?: "describe"
        // Inspection is executable code too. Never give it an installed identity or variables.
        worker.pluginId = if (method == "describe") "" else b.getString("pluginId") ?: ""
        val source = JSONObject.quote(b.getString("source") ?: "")
        val variables = if (method == "describe") "{}" else b.getString("variables") ?: "{}"
        val args = b.getString("args") ?: "[]"
        val id = JSONObject.quote(b.getString("id") ?: "")
        val js = """(function(){try{var m=PrismEngine.load($source,$variables);if(${JSONObject.quote(method)}==='describe'){PrismBridge.result($id,JSON.stringify({ok:true,value:JSON.parse(m)}));}else{PrismEngine.invoke($id,${JSONObject.quote(method)},$args);}}catch(e){PrismBridge.result($id,JSON.stringify({ok:false,error:String(e&&e.message||e)}));}})();"""
        worker.web.evaluateJavascript(js, null)
    }

    private fun reply(msg: Message, value: String) {
        val response = Message.obtain(null, 2).apply {
            data = Bundle().apply { putString("id", msg.data.getString("id")); putString("result", value); putInt("pid", Process.myPid()) }
        }
        runCatching { msg.replyTo.send(response) }
    }

    private fun clearCookies(msg: Message) {
        val pluginId = msg.data.getString("pluginId") ?: ""
        val result = runCatching {
            require(pluginId.isNotBlank()) { "未选择插件" }
            cookieJars.getOrPut(pluginId) { PluginCookies(this, pluginId) }.clear()
        }.fold(
            onSuccess = { JSONObject().put("ok", true).put("value", true) },
            onFailure = { JSONObject().put("ok", false).put("error", it.message ?: "清除失败") },
        )
        reply(msg, result.toString())
    }

    /** A stuck call is abandoned by throwing away its sandbox, not by killing every call. */
    private fun cancel(id: String) {
        queue.removeAll { it.data.getString("id") == id }
        val worker = workers.firstOrNull { it.current?.data?.getString("id") == id } ?: return
        worker.current = null
        worker.ready = false
        worker.web.stopLoading()
        worker.web.loadUrl(HOST)
    }

    private inner class Bridge(private val worker: Worker) {
        @JavascriptInterface fun result(id: String, value: String) {
            main.post {
                val msg = worker.current ?: return@post
                if (msg.data.getString("id") != id) return@post
                reply(msg, value)
                worker.current = null
                dispatch()
            }
        }

        @JavascriptInterface fun request(id: String, data: String) {
            val pluginId = worker.pluginId
            io.execute {
                val result = try {
                    val o = JSONObject(data)
                    val url = o.getString("url")
                    val method = o.optString("method", "get").uppercase()
                    require(method in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")) { "不支持的 HTTP 方法" }
                    val builder = Request.Builder().url(url)
                    val headers = o.optJSONObject("headers") ?: JSONObject()
                    for (key in headers.keys()) {
                        val value = headers.opt(key)
                        if (value is String || value is Number) builder.header(key, value.toString())
                    }
                    val body = if (o.isNull("body")) "" else o.optString("body", "")
                    require(body.toByteArray(Charsets.UTF_8).size <= MAX_BODY) { "请求体过大" }
                    val requestBody = if (method == "GET" || method == "HEAD") null else body.toRequestBody(headers.optString("Content-Type").toMediaTypeOrNull())
                    builder.method(method, requestBody)
                    val request = builder.build()
                    val jar: CookieJar = if (pluginId.isBlank()) CookieJar.NO_COOKIES else
                        cookieJars.getOrPut(pluginId) { PluginCookies(this@PluginService, pluginId) }
                    val explicitCookie = request.header("Cookie")
                    val requestOrigin = request.url
                    val client = http.newBuilder().cookieJar(jar).addNetworkInterceptor { chain ->
                        val next = chain.request()
                        val sameOrigin = next.url.scheme == requestOrigin.scheme && next.url.host == requestOrigin.host && next.url.port == requestOrigin.port
                        val safeRequest = when {
                            explicitCookie != null && sameOrigin -> next.newBuilder().header("Cookie", explicitCookie).build()
                            explicitCookie != null -> next.newBuilder().removeHeader("Cookie").build()
                            else -> next
                        }
                        chain.proceed(safeRequest)
                    }.build()
                    val call = client.newCall(request)
                    val timeout = o.optLong("timeout", 15000).coerceIn(1000, 30000)
                    call.timeout().timeout(timeout, TimeUnit.MILLISECONDS)
                    call.execute().use { response ->
                        val responseBody = response.body ?: error("空响应")
                        require(responseBody.contentLength() <= MAX_BODY) { "响应过大" }
                        val bytes = responseBody.byteStream().use { stream ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = stream.read(buffer)
                                if (n < 0) break
                                require(output.size() + n <= MAX_BODY) { "响应过大" }
                                output.write(buffer, 0, n)
                            }
                            output.toByteArray()
                        }
                        require(bytes.size <= MAX_BODY) { "响应过大" }
                        val responseHeaders = JSONObject()
                        response.headers.forEach { (key, value) -> responseHeaders.put(key.lowercase(), value) }
                        val binary = o.optString("responseType") == "arraybuffer"
                        val content = if (binary) android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) else bytes.toString(Charsets.UTF_8)
                        /* response.request 是重定向之后的最后一次请求：手机分享出来的短链
                           （163cn.tv、c6.y.qq.com）只有在这里才能还原成带歌单 id 的真实地址。 */
                        JSONObject().put("ok", true).put("status", response.code).put("statusText", response.message)
                            .put("url", response.request.url.toString())
                            .put("headers", responseHeaders).put("binary", binary).put("body", content)
                    }
                } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "网络请求失败") }
                main.post { worker.web.evaluateJavascript("PrismEngine.networkComplete(${JSONObject.quote(id)},$result)", null) }
            }
        }

        @JavascriptInterface fun cookies(action: String, data: String): String = try {
            val pluginId = worker.pluginId
            require(pluginId.isNotBlank()) { "未选择插件" }
            val jar = cookieJars.getOrPut(pluginId) { PluginCookies(this@PluginService, pluginId) }
            val input = JSONObject(data)
            val value: Any = when (action) {
                "clearAll" -> { jar.clear(); true }
                "get" -> {
                    val url = input.getString("url").toHttpUrl()
                    val result = JSONObject()
                    jar.loadForRequest(url).forEach { cookie ->
                        result.put(cookie.name, JSONObject().put("name", cookie.name).put("value", cookie.value)
                            .put("domain", cookie.domain).put("path", cookie.path).put("secure", cookie.secure).put("httpOnly", cookie.httpOnly))
                    }
                    result
                }
                "setFromResponse" -> {
                    val url = input.getString("url").toHttpUrl()
                    val header = input.getString("value")
                    require(header.length <= 4096) { "Cookie 过长" }
                    val cookie = Cookie.parse(url, header) ?: error("Cookie 无效")
                    jar.saveFromResponse(url, listOf(cookie)); true
                }
                "set" -> {
                    val url = input.getString("url").toHttpUrl()
                    val item = input.getJSONObject("cookie")
                    val domain = item.optString("domain")
                    val builder = Cookie.Builder().name(item.getString("name")).value(item.getString("value"))
                        .path(item.optString("path", "/").ifBlank { "/" })
                    if (domain.isBlank()) builder.hostOnlyDomain(url.host) else {
                        require(Cookie.parse(url, "prism_probe=1; Domain=$domain") != null) { "Cookie 域名无效" }
                        builder.domain(domain)
                    }
                    if (item.optBoolean("secure")) builder.secure()
                    if (item.optBoolean("httpOnly")) builder.httpOnly()
                    val expires = item.optString("expires")
                    if (expires.isNotBlank()) {
                        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX")
                        val date = patterns.firstNotNullOfOrNull { pattern -> runCatching {
                            SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }.parse(expires)
                        }.getOrNull() } ?: error("Cookie 过期时间无效")
                        builder.expiresAt(date.time)
                    }
                    jar.saveFromResponse(url, listOf(builder.build())); true
                }
                else -> error("不支持的 Cookie 操作")
            }
            JSONObject().put("ok", true).put("value", value).toString()
        } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "Cookie 操作失败").toString() }
    }

    override fun onDestroy() {
        workers.forEach { it.web.destroy() }
        workers.clear()
        io.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val MAX_BODY = 4 * 1024 * 1024
        private const val MAX_WORKERS = 4
        private const val HOST = "https://prism.invalid/plugin-host.html"
    }
}
