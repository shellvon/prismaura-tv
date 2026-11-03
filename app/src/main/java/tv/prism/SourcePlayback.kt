package tv.prism

import android.net.Uri
import org.json.JSONObject

data class VideoSource(val url: String, val headers: Map<String, String> = emptyMap())

fun sourceHeaders(result: JSONObject): Map<String, String> = buildMap {
    result.optJSONObject("headers")?.let { headers ->
        headers.keys().forEach { key ->
            val value = headers.optString(key)
            if (key.matches(Regex("[!#\\$%&'*+.^_`|~0-9A-Za-z-]+")) && !value.contains('\n') && !value.contains('\r')) put(key, value)
        }
    }
    result.optString("userAgent").takeIf { it.isNotBlank() && !it.contains('\n') && !it.contains('\r') }?.let { put("User-Agent", it) }
}

fun videoSource(result: Any?): VideoSource? {
    val obj = result as? JSONObject ?: return null
    val url = obj.optString("url").trim()
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
    return VideoSource(url, sourceHeaders(obj))
}

private val extendedQualityOrder = listOf("mgg", "64k", "128k", "192k", "320k", "flac", "flac24bit", "hires", "dolby", "atmos", "atmos_plus")
private val standardQualityOrder = listOf("low", "standard", "high", "super")

fun qualityOptions(plugin: Plugin, song: Song): List<String> {
    val declared = plugin.metadata.optJSONArray("supportedQualities")
    val supported = (0 until (declared?.length() ?: 0)).map { declared!!.optString(it).lowercase() }.filter { it.isNotBlank() }
    val actual = song.data.optJSONObject("qualities")?.let { q -> q.keys().asSequence().map { it.lowercase() }.toList() }.orEmpty()
    val order = if (supported.any { it in extendedQualityOrder }) extendedQualityOrder else standardQualityOrder
    val available = if (actual.isNotEmpty()) order.filter { it in actual && (supported.isEmpty() || it in supported) } else order.filter { it in supported }
    return available.ifEmpty { if (order === standardQualityOrder) standardQualityOrder else order.filter { it in supported } }
}

fun qualityAttempts(options: List<String>, preferred: String): List<String> {
    if (options.isEmpty()) return emptyList()
    val standardIndex = standardQualityOrder.indexOf(preferred)
    val index = options.indexOf(preferred).takeIf { it >= 0 } ?: if (standardIndex >= 0) {
        when (preferred) { "low" -> 0; "standard" -> options.indexOfFirst { it in listOf("128k", "192k", "320k") }.coerceAtLeast(0); "high" -> options.indexOf("320k").takeIf { it >= 0 } ?: options.lastIndex; else -> options.indexOf("flac").takeIf { it >= 0 } ?: options.lastIndex }
    } else options.lastIndex
    val ladder = options.take(index + 1).reversed().toMutableList()
    /* Standard is MusicFree's baseline: a plugin may implement only that one tier (the NetEase
       and Kuwo plugins do) and still play fine. Stopping the ladder at 流畅 would otherwise turn
       "prefer a smaller stream" into "this song cannot be played". */
    if (ladder.none { it == "standard" } && "standard" in options) ladder += "standard"
    return ladder
}
