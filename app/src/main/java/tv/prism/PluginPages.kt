package tv.prism

import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

internal data class PluginPage(val number: Int, val response: JSONObject)

/** Called with an execution slot held: skip empty pages within one call budget,
 * retaining the actual page and detail metadata.
 * At the five-page limit, isEnd remains false so the user can continue explicitly. */
internal suspend fun loadPluginPage(
    firstPage: Int,
    itemsKey: String,
    request: suspend (Int) -> JSONObject,
): PluginPage = withTimeoutOrNull(35_000) {
    val merged = JSONObject()
    var page = firstPage
    repeat(5) { attempt ->
        val response = request(page)
        response.keys().forEach { key -> if (!response.isNull(key)) merged.put(key, response.get(key)) }
        val items = response.optJSONArray(itemsKey) ?: JSONArray()
        val isEnd = response.optBoolean("isEnd", true)
        merged.put(itemsKey, items).put("isEnd", isEnd)
        if (items.length() > 0 || isEnd || attempt == 4) return@withTimeoutOrNull PluginPage(page, merged)
        page++
    }
    error("无法读取插件分页")
} ?: error("插件分页加载超时")
