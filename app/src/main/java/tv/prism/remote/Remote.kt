package tv.prism.remote

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import tv.prism.BuildConfig
import tv.prism.sources.Plugins
import tv.prism.sources.SyncResult
import tv.prism.sources.Plugin
import tv.prism.library.Song
import tv.prism.playback.audioQualityLabel
import tv.prism.sources.PluginReplacement
import tv.prism.sources.credentialKeys

/**
 * A short lived, token protected page on the LAN.
 *
 * Two modes share one server: a one-shot form (plugin configuration, which still needs a
 * keyboard) and a live remote panel. The remote panel polls [snapshot] and posts commands to
 * [command]; both are marshalled to the main thread because they touch playback and Compose state.
 */
class PhoneInput(
    private val context: Context,
    private val fields: List<Pair<String, String>> = emptyList(),
    private val snapshot: (() -> String)? = null,
    private val command: (suspend (String) -> String)? = null,
    private val initialValues: Map<String, String> = emptyMap(),
    private val formTitle: String = "填写配置",
    private val formMessage: String = "留空的配置项不修改。填写后回到电视确认并保存。",
    private val submit: (Map<String, String>) -> Unit = {},
) : AutoCloseable {
    init { require(fields.size <= 30) { "扫码输入最多支持 30 个字段" } }
    private val address = Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence()
        .filter { it.isUp && !it.isLoopback }.flatMap { Collections.list(it.inetAddresses).asSequence() }
        .filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
        ?.hostAddress ?: throw IllegalStateException("未找到局域网地址，请先连接 Wi-Fi")
    private val remote = snapshot != null
    // Debug sessions have separate, stable ADB ports; a form never interrupts the remote.
    private val socket = ServerSocket().apply {
        try {
            reuseAddress = true
            bind(InetSocketAddress(if (BuildConfig.DEBUG) { if (remote) DEBUG_REMOTE_PORT else DEBUG_FORM_PORT } else 0))
            soTimeout = 1000
        } catch (e: Exception) { close(); throw e }
    }
    /* One held polling connection must not block the play button. */
    private val workers = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val used = AtomicBoolean(false)
    private val template = context.applicationContext.assets.open(if (remote) "phone_remote.html" else "phone_input.html")
        .bufferedReader().use { it.readText() }
    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    val url = "http://$address:${socket.localPort}/$token"
    val qr: Bitmap = qrCodeBitmap(url)

    init { workers.execute {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                workers.execute { runCatching { handle(client) } }
            } catch (_: java.net.SocketTimeoutException) {
            } catch (_: Exception) { if (socket.isClosed) break }
        }
    } }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            check(!socket.isClosed) { "会话已结束" }
            return block()
        }
        val latch = CountDownLatch(1)
        @Suppress("UNCHECKED_CAST") val holder = arrayOfNulls<Any>(1) as Array<T?>
        var error: Throwable? = null
        main.post {
            try {
                check(!socket.isClosed) { "会话已结束" }
                holder[0] = block()
            } catch (t: Throwable) { error = t } finally { latch.countDown() }
        }
        check(latch.await(3, TimeUnit.SECONDS)) { "电视未响应" }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST") return holder[0] as T
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
        var authorized = false
        try {
            client.soTimeout = 5000
            val input = client.getInputStream().buffered()
            fun line(): String {
                val bytes = java.io.ByteArrayOutputStream()
                while (bytes.size() < 8192) {
                    val b = input.read(); if (b < 0 || b == 10) break
                    if (b != 13) bytes.write(b)
                }
                require(bytes.size() < 8192) { "请求过大" }
                return bytes.toString("UTF-8")
            }
            fun body(length: Int): String {
                val bytes = ByteArray(length); var offset = 0
                while (offset < length) { val n = input.read(bytes, offset, length - offset); require(n > 0) { "请求中断" }; offset += n }
                return bytes.toString(Charsets.UTF_8)
            }
            val request = line().split(' ')
            val path = request.getOrNull(1) ?: ""
            require(request.size >= 2) { "无效请求" }
            val headers = mutableMapOf<String, String>()
            while (true) { val h = line(); if (h.isEmpty()) break; require(headers.size < 64) { "请求头过多" }; val i = h.indexOf(':'); if (i > 0) headers[h.substring(0, i).lowercase()] = h.substring(i + 1).trim() }
            val hostHeader = headers["host"]
            val forwarded = client.inetAddress.isLoopbackAddress && hostHeader in setOf("127.0.0.1:${socket.localPort}", "localhost:${socket.localPort}")
            require(hostHeader == "$address:${socket.localPort}" || forwarded) { "无效主机" }
            val origin = headers["origin"]
            require(origin == null || origin == "http://$hostHeader") { "无效来源" }
            // A stable local entry for an explicitly opened debug session. LAN clients and
            // release builds still need the random token; only GET may use this alias.
            val debugEntry = BuildConfig.DEBUG && forwarded && path == "/" && request[0] == "GET"
            require(debugEntry || path == "/$token" || path == "/$token/state" || path == "/$token/action") { "无效地址" }
            authorized = true

            val isState = path == "/$token/state"
            val isAction = path == "/$token/action"
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            /* A restore upload carries a whole backup JSON, which is far larger than a
               playback command; both stay bounded so a hostile client cannot balloon memory. */
            val bodyLimit = if (isAction) 4 * 1024 * 1024 else 16384
            require(length in 0..bodyLimit) { "请求过大" }

            when {
                request[0] == "GET" && isState -> {
                    val json = snapshot?.let { onMain(it) } ?: error("遥控不可用")
                    reply(client, 200, "application/json; charset=utf-8", json)
                }
                request[0] == "GET" && !isAction -> {
                    val html = if (remote) template.replace("{{base}}", JSONObject.quote("/$token"))
                    else {
                        val inputs = fields.mapIndexed { index, (key, label) ->
                            val secret = listOf("password", "cookie", "token", "secret").any { key.contains(it, ignoreCase = true) }
                            "<label>${escape(label)}<input id=\"field-$index\" type=\"${if (secret) "password" else "text"}\" name=\"${escape(key)}\" maxlength=\"4096\" autocomplete=\"off\" value=\"${if (secret) "" else escape(initialValues[key].orEmpty().take(4096))}\"></label>" +
                                if (secret) "<button type=\"button\" class=\"reveal\" data-secret-toggle=\"field-$index\" aria-pressed=\"false\">显示内容</button>" else ""
                        }.joinToString("")
                        inputPage(formTitle, formMessage,
                            "<form method=\"post\" action=\"/$token\">$inputs<button type=\"submit\">填写到电视</button></form>")
                    }
                    reply(client, 200, "text/html; charset=utf-8", html)
                }
                request[0] == "POST" && isAction -> {
                    require(command != null) { "遥控不可用" }
                    /* JSON is not a simple form content type, so a cross-site form cannot reach this. */
                    require(headers["content-type"]?.startsWith("application/json") == true) { "无效请求" }
                    /* Commands are tiny; the large case is the backup restore upload. */
                    require(length in 1..(4 * 1024 * 1024)) { "请求过大" }
                    val payload = body(length)
                    // Only the HTTP worker waits; commands may suspend for backup IO.
                    val result = runBlocking {
                        withTimeout(30_000) {
                            withContext(Dispatchers.Main) {
                                check(!this@PhoneInput.socket.isClosed) { "会话已结束" }
                                command!!.invoke(payload)
                            }
                        }
                    }
                    reply(client, 200, "application/json; charset=utf-8", result)
                }
                request[0] == "POST" && !isState -> {
                    require(fields.isNotEmpty()) { "无效请求" }
                    require(headers["content-type"]?.startsWith("application/x-www-form-urlencoded") == true) { "无效请求" }
                    require(length in 1..16384) { "请求过大" }
                    val data = body(length).split('&').mapNotNull { part ->
                        val pair = part.split('=', limit = 2); if (pair.size != 2) null else URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
                    }.toMap().filterKeys { key -> fields.any { it.first == key } }
                    require(data.values.all { it.length <= 4096 })
                    require(used.compareAndSet(false, true)) { "输入已完成" }
                    try {
                        reply(client, 200, "text/html; charset=utf-8", inputPage("已发送到电视", "已回填电视输入框，尚未执行操作。请回到电视确认，可以关闭此页面。",
                            "<div class=\"result-mark\" aria-hidden=\"true\"><svg viewBox=\"0 0 24 24\"><path d=\"m5 12 4 4 10-10\"/></svg></div>"))
                    } finally {
                        // The submit callback shuts the input service down: send the result
                        // page first; a dropped connection still submits what was received.
                        main.post { submit(data) }
                    }
                }
                else -> reply(client, 405, "text/plain; charset=utf-8", "Method Not Allowed")
            }
        } catch (e: Exception) {
            runCatching {
                if (!remote && authorized) {
                    val content = if (used.get()) "<p>此输入已完成，请回到电视继续操作。</p>"
                        else "<a class=\"button\" href=\"/$token\">返回填写</a>"
                    reply(client, 400, "text/html; charset=utf-8", inputPage("未能发送", e.message ?: "请检查填写内容后重试。", content))
                } else if (remote && authorized) {
                    reply(client, 400, "application/json; charset=utf-8", JSONObject().put("ok", false).put("error", e.message ?: "请求失败").toString())
                } else reply(client, 400, "text/plain; charset=utf-8", e.message ?: "Bad Request")
            }
        }
        }
    }

    private fun inputPage(title: String, message: String, content: String): String = template
        .replace("{{nonce}}", token)
        .replace("{{title}}", escape(title))
        .replace("{{message}}", escape(message))
        .replace("{{content}}", content)

    private fun reply(client: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 405 -> "Method Not Allowed"; else -> "Bad Request" }
        val csp = if (remote)
            "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
        else
            "default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-$token'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
        client.getOutputStream().apply {
            write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: $csp\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }

    override fun close() {
        runCatching { socket.close() }
        workers.shutdownNow()
    }
    companion object {
        private const val DEBUG_REMOTE_PORT = 8765
        private const val DEBUG_FORM_PORT = 8766
        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}

/** Rendered locally so a TV can show a login link without the phone having to be on the same LAN. */
fun qrCodeBitmap(text: String, size: Int = 320): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}

internal class PhoneRemoteCommands(
    private val isRestoring: () -> Boolean,
    private val playback: Playback,
    private val search: Search,
    private val sources: Sources,
    private val app: App,
) {
    data class Playback(
        val toggle: () -> Unit,
        val step: (Int) -> Unit,
        val favorite: () -> Unit,
        val seek: (Long) -> Unit,
        val playQueueItem: (index: Int, key: String) -> Unit,
        val queuePage: (Int) -> Unit,
        val quality: (String) -> Unit,
    )

    data class Search(
        val start: (String) -> Unit,
        val select: (generation: Int, key: String, play: Boolean, next: Boolean) -> Unit,
        val more: () -> Unit,
        val type: (String) -> Unit,
        val source: (String) -> Unit,
        val subscription: (String) -> Unit,
    )

    data class App(
        val preference: (key: String, value: String) -> Unit,
        val toggleMiniLyrics: () -> Unit,
        val homeShelf: (id: String, visible: Boolean) -> Unit,
        val showPlayer: (Boolean) -> Unit,
        val page: (String) -> Unit,
        val backup: suspend () -> String,
        val restore: (String) -> Unit,
    )

    suspend fun execute(raw: String): String = try {
        val request = JSONObject(raw)
        check(!isRestoring()) { "正在恢复备份，请稍候" }
        val type = request.optString("type")
        val value = request.optString("value").trim()
        dispatch(type, value)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        JSONObject().put("ok", false).put("error", error.message ?: "操作失败").toString()
    }

    private suspend fun dispatch(type: String, value: String): String {
        when (type) {
            "playPause" -> playback.toggle()
            "next" -> playback.step(1)
            "prev" -> playback.step(-1)
            "favorite" -> playback.favorite()
            "seek" -> playback.seek(value.toLongOrNull() ?: error("位置无效"))
            "queuePlay" -> {
                val payload = JSONObject(value)
                playback.playQueueItem(payload.getInt("index"), payload.getString("key"))
            }
            "queuePage" -> playback.queuePage(value.toIntOrNull() ?: error("页码无效"))
            "quality" -> {
                require(value in qualityTiers) { "未知音质" }
                playback.quality(value)
            }
            "theme", "mode" -> app.preference(type, value)
            "miniLyrics" -> app.toggleMiniLyrics()
            "preference" -> {
                val payload = JSONObject(value)
                app.preference(payload.getString("key"), payload.getString("value"))
            }
            "homeShelf" -> {
                val payload = JSONObject(value)
                app.homeShelf(payload.getString("id"), payload.getBoolean("visible"))
            }
            "search" -> {
                require(value.isNotEmpty()) { "请输入搜索内容" }
                search.start(value)
            }
            "playSearchResult", "queueSearchResult" -> {
                val payload = JSONObject(value)
                val play = type == "playSearchResult"
                val placement = if (play) "next" else payload.getString("placement")
                require(placement == "next" || placement == "last") { "未知队列操作" }
                search.select(payload.getInt("generation"), payload.getString("key"), play, placement == "next")
            }
            "searchMore" -> search.more()
            "searchType" -> search.type(value)
            "searchSource" -> search.source(value)
            "subscriptionSearch" -> search.subscription(value)
            "import", "importFile", "importSheet", "sourceToggle", "sourceUpdate", "sourceRemove",
            "sourceConfig", "subscriptionUpdate", "subscriptionRemove", "sourcesUpdate" ->
                return sources.execute(type, value)
            "importReplace" -> {
                val payload = JSONObject(value)
                sources.resolveReplacement(payload.getString("id"), "replace", payload.getString("targetId"))
            }
            "importCancel", "importNew" ->
                sources.resolveReplacement(value, if (type == "importNew") "new" else "cancel", null)
            "player", "closePlayer" -> app.showPlayer(type == "player")
            "page" -> {
                require(value in pages) { "未知页面" }
                app.page(value)
            }
            "backup" -> return JSONObject().put("ok", true).put("data", app.backup()).toString()
            "restore" -> {
                require(value.isNotEmpty()) { "备份内容为空" }
                app.restore(value)
            }
            else -> error("未知操作")
        }
        return JSONObject().put("ok", true).toString()
    }

    class Sources(
        private val plugins: Plugins,
        private val runContentTask: (String, String, suspend ((String) -> Unit) -> SyncResult) -> String,
        private val importPlaylist: (name: String, url: String) -> String,
        val resolveReplacement: (id: String, action: String, targetId: String?) -> Unit,
    ) {
        fun execute(type: String, value: String): String = when (type) {
            "import" -> {
                require(value.isNotEmpty()) { "请输入插件或订阅地址" }
                runContentTask("import", "正在导入音源…") { progress ->
                    plugins.importUrl(value) { done, total, label -> progress("正在导入 $done/$total：$label") }
                }
            }
            "importFile" -> {
                val source = JSONObject(value).getString("source")
                require(source.toByteArray(Charsets.UTF_8).size <= Plugins.MAX_SCRIPT) { "插件文件超过 ${Plugins.MAX_SCRIPT / 1024} KiB" }
                runContentTask("import", "正在导入插件文件…") { plugins.importSource(source) }
            }
            "importSheet" -> {
                val payload = JSONObject(value)
                val url = payload.optString("url").trim()
                require(url.isNotEmpty()) { "请输入歌单或单曲链接" }
                importPlaylist(payload.optString("name"), url)
            }
            "sourceToggle", "sourceUpdate", "sourceRemove", "sourceConfig" -> changeSource(type, value)
            "subscriptionUpdate", "subscriptionRemove", "sourcesUpdate" -> changeSubscription(type, value)
            else -> error("未知音源操作")
        }

        private fun changeSource(type: String, value: String): String {
            val payload = if (type == "sourceConfig") JSONObject(value) else null
            val id = payload?.getString("id") ?: value
            val state = plugins.state.value
            val plugin = state.plugins.firstOrNull { it.id == id } ?: error("音源不存在")
            if (type == "sourceRemove") require(plugin.phoneCanRemove(state.subscriptions)) {
                "这是订阅带回的音源，请移除对应订阅"
            }
            val values = payload?.getJSONObject("values")
            if (values != null) {
                val allowed = plugin.metadata.optJSONArray("userVariables") ?: JSONArray()
                val keys = (0 until allowed.length()).mapNotNull { allowed.optJSONObject(it)?.optString("key") }.toSet()
                require(values.keys().asSequence().all {
                    it in keys && values.opt(it) is String && values.getString(it).length <= 4096
                }) { "配置项无效" }
            }
            return runContentTask("source:$id", "正在处理 ${plugin.displayName}…") {
                when (type) {
                    "sourceUpdate" -> plugins.refresh(id)
                    "sourceRemove" -> {
                        plugins.remove(id)
                        SyncResult("已删除音源：${plugin.displayName}")
                    }
                    "sourceConfig" -> {
                        val merged = JSONObject(plugin.variables.toString())
                        values?.keys()?.forEach { key -> merged.put(key, values.getString(key)) }
                        plugins.setVariables(id, merged)
                        SyncResult("已保存 ${plugin.displayName} 的配置；尚未验证登录或播放")
                    }
                    else -> {
                        plugins.setEnabled(id, !plugin.enabled)
                        SyncResult(if (plugin.enabled) "已停用 ${plugin.displayName}" else "已启用 ${plugin.displayName}")
                    }
                }
            }
        }

        private fun changeSubscription(type: String, value: String): String {
            if (type != "sourcesUpdate") require(value in plugins.state.value.subscriptions) { "订阅已不存在" }
            return runContentTask("sources", "正在处理订阅…") { progress ->
                when (type) {
                    "subscriptionRemove" -> {
                        plugins.removeSubscription(value)
                        SyncResult("已移除订阅及其音源；收藏与歌单保留")
                    }
                    "subscriptionUpdate" -> plugins.importUrl(value) { done, total, label -> progress("正在更新 $done/$total：$label") }
                    else -> plugins.syncAll { done, total, label -> progress("正在更新 $done/$total：$label") }
                }
            }
        }
    }

    private companion object {
        val qualityTiers = setOf(
            "low", "standard", "high", "super", "flac", "flac24bit", "hires", "dolby", "atmos",
            "atmos_plus", "64k", "128k", "192k", "320k", "mgg",
        )
        val pages = setOf("home", "search", "library", "discover", "plugins")
    }
}

internal fun Plugin.phoneCanRemove(subscriptions: List<String>): Boolean =
    origin.isBlank() && url !in subscriptions

/** The H5 contract stays here. The Activity supplies live values for each poll. */
internal class PhoneRemoteSnapshot {
    private val json = JSONObject()

    fun playback(
        queue: List<Song>, queueIndex: Int, order: List<Int>, offset: Int, stream: Song?,
        playing: Boolean, position: Long, duration: Long, quality: String, streamFormat: String,
        mode: String, radioActive: Boolean, favorite: Boolean, qualities: List<String>,
    ) {
        val song = queue.getOrNull(queueIndex)
        val cursor = order.indexOf(queueIndex).coerceAtLeast(0)
        val start = if (offset < 0) (cursor - 100).coerceIn(0, (queue.size - 200).coerceAtLeast(0))
        else offset.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        val items = JSONArray()
        // Keep the 1.2 s poll bounded even when a playlist contains thousands of songs.
        order.drop(start).take(200).forEachIndexed { relative, index ->
            val item = queue[index]
            items.put(JSONObject().put("title", item.title).put("artist", item.artist).put("index", index)
                .put("position", start + relative).put("key", item.key))
        }
        json.put("title", song?.title.orEmpty())
            .put("artist", song?.artist.orEmpty())
            .put("source", stream?.sourceLabel.orEmpty())
            .put("playing", playing)
            .put("position", position)
            .put("duration", duration)
            .put("quality", quality)
            .put("qualityLabel", audioQualityLabel(quality))
            .put("streamFormat", streamFormat)
            .put("mode", mode)
            .put("radioActive", radioActive)
            .put("favorite", favorite)
            .put("queueSize", queue.size)
            .put("queueIndex", if (queue.isEmpty()) 0 else cursor + 1)
            .put("queueSongs", items)
            .put("qualities", JSONArray(qualities.map { tier ->
                JSONObject().put("id", tier).put("label", audioQualityLabel(tier))
            }))
    }

    fun preferences(
        miniLyrics: Boolean, theme: String, reduceMotion: Boolean, autoSourceFallback: Boolean,
        lyricFontLevel: String, subLyrics: Boolean, pluginWorkerLimit: Int, automaticWorkers: Int,
        recommendationSource: String, chartSource: String, shelves: List<Pair<String, String>>, hiddenShelves: Set<String>,
    ) {
        json.put("miniLyrics", miniLyrics)
            .put("theme", theme)
            .put("reduceMotion", reduceMotion)
            .put("autoSourceFallback", autoSourceFallback)
            .put("lyricFontLevel", lyricFontLevel)
            .put("subLyrics", subLyrics)
            .put("pluginWorkerLimit", pluginWorkerLimit)
            .put("automaticWorkers", automaticWorkers)
            .put("recommendationSource", recommendationSource)
            .put("chartSource", chartSource)
            .put("homeShelves", JSONArray(shelves.map { (id, name) ->
                JSONObject().put("id", id).put("name", name).put("visible", id !in hiddenShelves)
            }))
    }

    fun sources(plugins: Plugins, importTarget: String, importCapableCount: Int, replacement: PluginReplacement?) {
        val state = plugins.state.value
        json.put("maxPluginBytes", Plugins.MAX_SCRIPT)
            .put("subscriptions", JSONArray(state.subscriptions))
            .put("subscriptionDescriptions", JSONObject(state.subscriptionDescriptions))
            .put("importTarget", importTarget)
            .put("importCapableCount", importCapableCount)
            .put("pluginReplacement", replacement?.let {
                JSONObject().put("id", it.incoming.id).put("name", it.incoming.name)
                    .put("author", it.incoming.metadata.optString("author"))
                    .put("newVersion", it.incoming.metadata.optString("version"))
                    .put("candidates", JSONArray(it.candidates.map { candidate ->
                        JSONObject().put("id", candidate.id).put("name", candidate.name)
                            .put("author", candidate.metadata.optString("author"))
                            .put("version", candidate.metadata.optString("version"))
                            .put("blocker", it.replacementBlocker(candidate).orEmpty())
                    }))
            })
            .put("sources", JSONArray(state.plugins.map { plugin ->
                val subscription = state.subscriptions.firstOrNull { it == plugin.origin || it == plugin.url }
                val capabilities = plugin.capabilities
                JSONObject().put("id", plugin.id).put("name", plugin.displayName)
                    .put("groupId", subscription.orEmpty())
                    .put("groupName", subscription?.let(plugins::subscriptionLabel) ?: "单独安装")
                    .put("supportsRecommendations", capabilities.recommendations)
                    .put("supportsCharts", capabilities.charts)
                    .put("supportsSearch", capabilities.has("search"))
                    .put("author", plugin.metadata.optString("author"))
                    .put("enabled", plugin.enabled)
                    .put("version", plugin.metadata.optString("version"))
                    .put("capabilities", JSONArray(capabilities.labels))
                    .put("canUpdate", plugin.updateUrl.isNotBlank())
                    .put("canRemove", plugin.phoneCanRemove(state.subscriptions))
                    .put("fields", fields(plugin))
            }))
    }

    private fun fields(plugin: Plugin): JSONArray {
        val fields = JSONArray()
        val variables = plugin.metadata.optJSONArray("userVariables") ?: return fields
        for (i in 0 until variables.length()) {
            val field = variables.optJSONObject(i) ?: continue
            val key = field.optString("key")
            if (key.isBlank()) continue
            fields.put(JSONObject().put("key", key)
                .put("title", field.optString("title").ifBlank { field.optString("name").ifBlank { key } })
                .put("hint", field.optString("hint"))
                .put("secret", credentialKeys.any { key.contains(it, ignoreCase = true) })
                .put("value", plugin.variables.optString(key)))
        }
        return fields
    }

    fun search(
        type: String, term: String, generation: Int, running: Boolean, hasMore: Boolean,
        songs: List<Song>, entityCount: Int, currentKey: String?, choices: List<Pair<String, String>>,
        sources: Set<String>,
    ) {
        json.put("searchType", type)
            .put("searchTerm", term)
            .put("searchGeneration", generation)
            .put("searchBusy", running)
            .put("searchHasMore", hasMore)
            .put("searchResultCount", if (type == "music") songs.size else entityCount)
            .put("searchResults", if (type == "music") JSONArray(songs.take(200).map { track ->
                JSONObject().put("key", track.key).put("title", track.title.take(300))
                    .put("artist", track.artist.take(200)).put("album", track.album.take(200))
                    .put("source", track.sourceLabel.take(120)).put("duration", track.durationLabel)
                    .put("current", track.key == currentKey)
            }) else JSONArray())
            .put("searchTypeOptions", JSONArray(choices.map { (id, label) ->
                JSONObject().put("id", id).put("label", label)
            }))
            .put("searchSources", JSONArray(sources))
    }

    fun activity(
        status: String, page: String, playerOpen: Boolean, busy: Boolean,
        restoring: Boolean, restoreMessage: String, restoreFailed: Boolean,
        tasks: Map<String, ContentTask>,
    ) {
        json.put("status", status)
            .put("page", page)
            .put("playerOpen", playerOpen)
            .put("importBusy", busy)
            .put("restoreBusy", restoring)
            .put("restoreMessage", restoreMessage)
            .put("restoreFailed", restoreFailed)
            .put("tasks", JSONObject().also { output ->
                tasks.forEach { (key, task) ->
                    output.put(key, JSONObject().put("revision", task.revision).put("message", task.message)
                        .put("running", task.running).put("failed", task.failed))
                }
            })
    }

    fun encode(): String = json.toString()
}

internal data class ContentTask(val revision: Long, val message: String, val running: Boolean = true, val failed: Boolean = false)
