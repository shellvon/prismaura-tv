package tv.prism

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.net.Inet4Address
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
    private val command: ((String) -> String)? = null,
    private val submit: (Map<String, String>) -> Unit = {},
) : AutoCloseable {
    init { require(fields.size <= 30) { "扫码输入最多支持 30 个字段" } }
    private val address = Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence()
        .filter { it.isUp && !it.isLoopback }.flatMap { Collections.list(it.inetAddresses).asSequence() }
        .filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
        ?.hostAddress ?: throw IllegalStateException("未找到局域网地址，请先连接 Wi-Fi")
    private val socket = ServerSocket(0).apply { soTimeout = 1000 }
    /* One held polling connection must not block the play button. */
    private val workers = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val used = AtomicBoolean(false)
    private val remote = snapshot != null
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
            require(request.size >= 2 && (path == "/$token" || path == "/$token/state" || path == "/$token/action")) { "无效地址" }
            val headers = mutableMapOf<String, String>()
            while (true) { val h = line(); if (h.isEmpty()) break; require(headers.size < 64) { "请求头过多" }; val i = h.indexOf(':'); if (i > 0) headers[h.substring(0, i).lowercase()] = h.substring(i + 1).trim() }
            val hostHeader = headers["host"]
            val forwarded = client.inetAddress.isLoopbackAddress && hostHeader in setOf("127.0.0.1:${socket.localPort}", "localhost:${socket.localPort}")
            require(hostHeader == "$address:${socket.localPort}" || forwarded) { "无效主机" }
            val origin = headers["origin"]
            require(origin == null || origin == "http://$hostHeader") { "无效来源" }
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
                        val inputs = fields.joinToString("") { (key, label) ->
                            val secret = listOf("password", "cookie", "token", "secret").any { key.contains(it, ignoreCase = true) }
                            "<label>${escape(label)}<input type=\"${if (secret) "password" else "text"}\" name=\"${escape(key)}\" maxlength=\"4096\" autocomplete=\"off\"></label>"
                        }
                        inputPage("发送到电视", "填写后发送，再回到电视确认并保存配置。",
                            "<form method=\"post\" action=\"/$token\">$inputs<button type=\"submit\">发送到电视</button></form>")
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
                    val result = onMain { command!!.invoke(payload) }
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
                        reply(client, 200, "text/html; charset=utf-8", inputPage("已发送到电视", "请回到电视确认并保存配置，可以关闭此页面。",
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
        .replace("{{title}}", escape(title))
        .replace("{{message}}", escape(message))
        .replace("{{content}}", content)

    private fun reply(client: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 405 -> "Method Not Allowed"; else -> "Bad Request" }
        val csp = if (remote)
            "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
        else
            "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
        client.getOutputStream().apply {
            write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: $csp\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }

    override fun close() {
        runCatching { socket.close() }
        workers.shutdownNow()
    }
    companion object { private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;") }
}

/** Rendered locally so a TV can show a login link without the phone having to be on the same LAN. */
fun qrCodeBitmap(text: String, size: Int = 320): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}
