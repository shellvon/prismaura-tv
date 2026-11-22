package tv.prism.lyrics

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.prism.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit

/* 外部歌词源的统一 HTTP 入口：共享连接池，按站点选 UA，参数由 HttpUrl 编码。 */
internal object LyricsHttp {
    /* LRCLIB 要求可识别 UA；酷狗、网易云只认浏览器 UA。 */
    private val APP_UA = "PrismAura/${BuildConfig.VERSION_NAME} (Android TV; https://github.com/shellvon/prismaura-tv)"

    private const val WEB_UA =
        "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    internal fun url(base: String, vararg query: Pair<String, String?>): HttpUrl =
        base.toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> if (!value.isNullOrBlank()) addQueryParameter(key, value) }
        }.build()

    /** 404 视为没有匹配，返回 null；其余状态码抛出。 */
    internal fun get(url: HttpUrl, referer: String? = null, appAgent: Boolean = false): String? {
        val request = Request.Builder().url(url)
            .header("User-Agent", if (appAgent) APP_UA else WEB_UA)
            .apply {
                if (referer != null) header("Referer", referer)
                if (appAgent) header("Accept", "application/json")
            }
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("empty body")
        }
    }

    internal fun json(url: HttpUrl, referer: String? = null, appAgent: Boolean = false): JSONObject? =
        get(url, referer, appAgent)?.let { JSONObject(it) }

    internal fun array(url: HttpUrl, referer: String? = null, appAgent: Boolean = false): JSONArray? =
        get(url, referer, appAgent)?.let { JSONArray(it) }
}
