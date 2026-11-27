package tv.prism.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Locale
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import tv.prism.library.Song
import tv.prism.sources.Plugin

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

data class MvRemoteState(
    val playing: Boolean,
    val advancing: Boolean,
    val position: Long,
    val duration: Long,
    val seekable: Boolean,
    val buffering: Boolean,
    val ended: Boolean,
    val quality: String,
    val loopOne: Boolean,
    val speed: Float,
    val fillScreen: Boolean,
    val canPrev: Boolean,
    val canNext: Boolean,
)

class MvRemoteSession(
    val read: () -> MvRemoteState,
    val togglePlay: () -> Unit,
    val seekTo: (Long) -> Unit,
    val stepBy: (Long) -> Unit,
    val prevOrRewind: () -> Unit,
    val nextOrForward: () -> Unit,
    val restart: () -> Unit,
    val toggleLoop: () -> Unit,
    val cycleSpeed: () -> Unit,
    val toggleFill: () -> Unit,
)

internal class UnsupportedAudioEncryptionException(message: String = "此音质采用的加密格式暂不支持") : IllegalStateException(message)

internal const val QMC_EKEY_EXTRA = "prism.qmc2.ekey"

internal const val CENC_KEY_EXTRA = "prism.cenc.key"

private val clearKeyAvailable by lazy {
    // FrameworkMediaDrm uses the common PSSH UUID for ClearKey before API 27.
    val uuid = if (android.os.Build.VERSION.SDK_INT < 27) androidx.media3.common.C.COMMON_PSSH_UUID
        else androidx.media3.common.C.CLEARKEY_UUID
    runCatching { android.media.MediaDrm.isCryptoSchemeSupported(uuid) }.getOrDefault(false)
}

internal fun requireSupportedAudioEncryption(source: JSONObject) {
    val cek = source.opt("cek") as? String
    val key = source.opt("ekey") as? String
    if (!cek.isNullOrBlank()) {
        if (!key.isNullOrBlank()) throw UnsupportedAudioEncryptionException("音源同时返回了两种加密密钥")
        clearKeyValue(cek)
        if (!clearKeyAvailable) throw UnsupportedAudioEncryptionException("此设备不支持 ClearKey，请尝试其他音质或音源")
    }
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

internal fun audioMediaItem(song: Song, source: JSONObject): MediaItem {
    requireSupportedAudioEncryption(source)
    val uri = Uri.parse(source.optString("url"))
    require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) { "插件返回了无效播放地址" }
    val headers = Bundle().apply { sourceHeaders(source).forEach { (key, value) -> putString(key, value) } }
    val artwork = Uri.parse(song.artwork).takeIf { it.scheme in listOf("http", "https") }
    val ekey = (source.opt("ekey") as? String)?.takeIf { it.isNotBlank() }
    val extras = Bundle().apply {
        putBundle("headers", headers)
        ekey?.let { putString(QMC_EKEY_EXTRA, it) }
        (source.opt("cek") as? String)?.takeIf { it.isNotBlank() }?.let { putString(CENC_KEY_EXTRA, it) }
    }
    val metadata = MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setArtworkUri(artwork)
        .setExtras(extras).build()
    val builder = MediaItem.Builder().setUri(uri).setMediaMetadata(metadata)
    if (ekey != null) when (uri.lastPathSegment?.substringAfterLast('.')?.lowercase()) {
        "mflac", "mflac0" -> builder.setMimeType("audio/flac")
        "mgg", "mgg0", "mgg1" -> builder.setMimeType("audio/ogg")
        "mmp3" -> builder.setMimeType("audio/mpeg")
    }
    return builder.build()
}

private fun videoQualityRank(label: String): Int {
    val key = label.trim().lowercase(Locale.ROOT)
    Regex("(\\d{3,4})").find(key)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    return when {
        key in setOf("4k", "uhd", "2160p") -> 2160
        key in setOf("2k", "qhd", "1440p") -> 1440
        key in setOf("fhd", "1080p", "high", "super", "hd_high", "blue", "bluray", "蓝光", "超清") -> 1080
        key in setOf("hd", "720p", "standard", "高清") -> 720
        key in setOf("sd", "480p", "标清") -> 480
        key in setOf("ld", "360p", "low", "流畅") -> 360
        else -> 0
    }
}

private fun validVideoUrl(raw: String?): String? {
    val url = raw?.trim().orEmpty()
    if (url.isBlank()) return null
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    return if (uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) url else null
}

fun videoSource(result: Any?): VideoSource? {
    val obj = result as? JSONObject ?: return null
    val candidates = mutableListOf<Pair<Int, String>>()
    // Prefer the highest available resolution/bitrate when a plugin returns multiple streams.
    for (mapKey in listOf("urls", "qualities", "brs")) {
        val map = obj.optJSONObject(mapKey) ?: continue
        map.keys().forEach { qualityKey ->
            val value = map.opt(qualityKey)
            val candidateUrl = when (value) {
                is String -> validVideoUrl(value)
                is JSONObject -> validVideoUrl(value.optString("url"))
                else -> null
            }
            if (candidateUrl != null) candidates += videoQualityRank(qualityKey) to candidateUrl
        }
    }
    obj.optJSONArray("sources")?.let { arr ->
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val candidateUrl = validVideoUrl(item.optString("url")) ?: continue
            val rank = maxOf(
                item.optInt("height", 0),
                item.optInt("resolution", 0),
                item.optInt("bitrate", 0) / 1000,
                videoQualityRank(item.optString("quality").ifBlank { item.optString("label") }),
            )
            candidates += rank to candidateUrl
        }
    }
    val url = candidates.maxByOrNull { it.first }?.second ?: validVideoUrl(obj.optString("url")) ?: return null
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

/** Caches resolved URLs, not audio. Script/configuration changes naturally select a new key. */
internal class MediaSourceCache(private val directory: File) {
    private val mutex = Mutex()
    private val revisions = mutableMapOf<String, Long>()

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    // Hash restored plugin IDs as well as cache identities so neither can introduce path segments.
    private fun file(plugin: Plugin, song: Song, quality: String): File {
        val identity = listOf(plugin.id, plugin.source, plugin.variables.toString(), song.key, quality).joinToString("\u0000")
        return File(directory, "${digest(plugin.id)}-${digest(identity)}.json")
    }

    suspend fun revision(pluginId: String): Long = mutex.withLock { revisions[pluginId] ?: 0L }

    /** Invalidate pending writes before deleting this plugin's cached URLs. */
    suspend fun clearFor(pluginId: String) = mutex.withLock {
        require(pluginId.isNotBlank()) { "未选择音源" }
        revisions[pluginId] = (revisions[pluginId] ?: 0L) + 1L
        withContext(Dispatchers.IO) {
            val files = directory.listFiles()
            check(files != null || !directory.exists()) { "无法读取播放地址缓存" }
            val prefix = "${digest(pluginId)}-"
            files.orEmpty().filter { it.name.startsWith(prefix) }.forEach {
                AtomicFile(it).delete()
                check(!it.exists()) { "无法清除播放地址缓存" }
            }
        }
    }

    suspend fun read(plugin: Plugin, song: Song, quality: String): JSONObject? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val path = file(plugin, song, quality)
            runCatching {
                if (!path.exists()) null else JSONObject(AtomicFile(path).openRead().bufferedReader().use { it.readText() })
                    .takeIf { it.optString("url").isNotBlank() }
            }.getOrNull()
        }
    }

    suspend fun write(plugin: Plugin, song: Song, quality: String, source: JSONObject, revision: Long) = mutex.withLock {
        if (revision != (revisions[plugin.id] ?: 0L)) return@withLock
        withContext(Dispatchers.IO) {
            val bytes = source.toString().toByteArray()
            if (bytes.size > 64 * 1024 || source.optString("url").isBlank()) return@withContext
            check(directory.isDirectory || directory.mkdirs()) { "无法创建播放地址缓存" }
            val atomic = AtomicFile(file(plugin, song, quality))
            val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) }
            catch (e: Exception) { atomic.failWrite(output); throw e }
            directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }
                ?.drop(128)?.forEach { AtomicFile(it).delete() }
        }
    }
}
