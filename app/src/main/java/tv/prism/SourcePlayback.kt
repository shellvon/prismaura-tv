package tv.prism

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Locale

/** Source tier names describe the provider's offer, not a certification of the decoded stream. */
internal fun audioQualityLabel(tier: String): String = when (tier) {
    "low" -> "流畅"
    "standard" -> "标准"
    "high" -> "高品质"
    "super" -> "无损优先"
    "flac" -> "无损 FLAC"
    "flac24bit" -> "24-bit 无损"
    "hires" -> "Hi-Res 高解析"
    "dolby" -> "Dolby 杜比"
    "atmos" -> "Atmos 全景声"
    "atmos_plus" -> "Atmos+ 增强档"
    "master" -> "母带（音源档位）"
    "64k" -> "64 kbps"
    "128k" -> "128 kbps"
    "192k" -> "192 kbps"
    "320k" -> "320 kbps"
    "mgg" -> "OGG 音质"
    else -> tier
}

/** Parsed input-track parameters. These do not describe the TV's resampled/output format. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun audioStreamLabel(format: Format): String {
    val mime = format.sampleMimeType.orEmpty()
    val codec = when (mime) {
        "audio/flac" -> "FLAC"
        "audio/alac" -> "ALAC"
        "audio/mpeg" -> "MP3"
        "audio/mp4a-latm" -> "AAC"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/raw" -> "PCM"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        "audio/eac3-joc" -> "E-AC-3 JOC"
        "audio/ac4" -> "AC-4"
        else -> mime.removePrefix("audio/").uppercase(Locale.ROOT).takeIf { it.isNotBlank() }
    }
    val depth = when (format.pcmEncoding) {
        C.ENCODING_PCM_8BIT -> "8-bit"
        C.ENCODING_PCM_16BIT -> "16-bit"
        C.ENCODING_PCM_24BIT -> "24-bit"
        C.ENCODING_PCM_32BIT -> "32-bit"
        C.ENCODING_PCM_FLOAT -> "32-bit float"
        else -> null
    }
    val rate = format.sampleRate.takeIf { it > 0 }?.let { BigDecimal.valueOf(it.toLong(), 3).stripTrailingZeros().toPlainString() + " kHz" }
    val channels = when (format.channelCount) {
        1 -> "单声道"
        2 -> "双声道"
        in 3..Int.MAX_VALUE -> "${format.channelCount} 声道"
        else -> null
    }
    val bitrate = format.bitrate.takeIf { it > 0 && mime !in setOf("audio/flac", "audio/alac", "audio/raw") }?.let {
        BigDecimal.valueOf(it.toLong(), 3).stripTrailingZeros().toPlainString() + " kbps"
    }
    return listOfNotNull(codec, depth, rate, channels, bitrate).joinToString(" · ")
}

data class VideoSource(val url: String, val headers: Map<String, String> = emptyMap())

internal class UnsupportedAudioEncryptionException(message: String = "此音质采用的加密格式暂不支持") : IllegalStateException(message)

internal const val QMC_EKEY_EXTRA = "prism.qmc2.ekey"

internal fun requireSupportedAudioEncryption(source: JSONObject) {
    val cek = source.opt("cek") as? String
    val key = source.opt("ekey") as? String
    if (!cek.isNullOrBlank()) throw UnsupportedAudioEncryptionException("此音质采用 CENC 加密，当前版本暂不支持")
    if (key.isNullOrBlank()) return
    try { Qmc2Cipher.fromEkey(key) }
    catch (e: IllegalArgumentException) { throw QmcDecryptionException("加密音频密钥无效或无法识别") }
}

fun sourceHeaders(result: JSONObject): Map<String, String> = buildMap {
    result.optJSONObject("headers")?.let { headers ->
        headers.keys().forEach { key ->
            val value = headers.optString(key)
            if (key.matches(Regex("[!#\\$%&'*+.^_`|~0-9A-Za-z-]+")) && !value.contains('\n') && !value.contains('\r')) put(key, value)
        }
    }
    result.optString("userAgent").takeIf { it.isNotBlank() && !it.contains('\n') && !it.contains('\r') }?.let { put("User-Agent", it) }
}

internal fun audioMediaItem(song: Song, source: JSONObject, mediaId: String = ""): MediaItem {
    requireSupportedAudioEncryption(source)
    val uri = Uri.parse(source.optString("url"))
    require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) { "插件返回了无效播放地址" }
    val headers = Bundle().apply { sourceHeaders(source).forEach { (key, value) -> putString(key, value) } }
    val artwork = Uri.parse(song.artwork).takeIf { it.scheme in listOf("http", "https") }
    val ekey = (source.opt("ekey") as? String)?.takeIf { it.isNotBlank() }
    val metadata = MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setArtworkUri(artwork)
        .setExtras(Bundle().apply { putBundle("headers", headers); ekey?.let { putString(QMC_EKEY_EXTRA, it) } }).build()
    val builder = MediaItem.Builder().setMediaId(mediaId).setUri(uri).setMediaMetadata(metadata)
    if (ekey != null) when (uri.lastPathSegment?.substringAfterLast('.')?.lowercase()) {
        "mflac", "mflac0" -> builder.setMimeType("audio/flac")
        "mgg", "mgg0", "mgg1" -> builder.setMimeType("audio/ogg")
        "mmp3" -> builder.setMimeType("audio/mpeg")
    }
    return builder.build()
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

private fun declaredQualities(plugin: Plugin): List<String> {
    val declared = plugin.metadata.optJSONArray("supportedQualities") ?: return emptyList()
    return (0 until declared.length()).map { declared.optString(it).lowercase() }.filter { it.isNotBlank() }
}

/** The plugin's whole ladder, rung by rung, whether or not this song answers each one. The
    menu needs the list it did not get to pick from: a tier that is missing entirely reads as
    "this source has one quality", while a tier shown as unavailable reads as a fact. */
fun qualityLadder(plugin: Plugin, song: Song): List<String> {
    val supported = declaredQualities(plugin)
    if (supported.isEmpty()) return qualityOptions(plugin, song)
    val order = if (supported.any { it in extendedQualityOrder }) extendedQualityOrder else standardQualityOrder
    val ranked = order.filter { it in supported }
    return ranked + supported.filterNot { it in ranked }
}

fun qualityOptions(plugin: Plugin, song: Song): List<String> {
    val supported = declaredQualities(plugin)
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
