package tv.prism

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal fun recoveryQualities(options: List<String>, preferred: String): List<String> =
    (qualityAttempts(options, preferred) + options).distinct()

private fun recordingText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

private val recordingVersions = listOf(
    Regex("\\blive\\b|演唱会|現場|现场", RegexOption.IGNORE_CASE),
    Regex("\\binstrumental\\b|\\bkaraoke\\b|伴奏|纯音乐|純音樂", RegexOption.IGNORE_CASE),
    Regex("\\bremix\\b|\\bdj\\b|混音", RegexOption.IGNORE_CASE),
    Regex("\\bcover\\b|翻唱", RegexOption.IGNORE_CASE),
    Regex("\\bacoustic\\b|\\bunplugged\\b|不插电|不插電", RegexOption.IGNORE_CASE),
    Regex("\\bre[-\\s]?master(?:ed)?\\b|重制|重製", RegexOption.IGNORE_CASE),
    Regex("\\bre[-\\s]?record(?:ed|ing)?\\b|重录|重錄", RegexOption.IGNORE_CASE),
    Regex("\\bdemo\\b|试唱|試唱", RegexOption.IGNORE_CASE),
)

internal fun recordingDurationMatches(original: Song, seconds: Double): Boolean {
    val expected = original.durationSeconds
    return expected.isFinite() && seconds.isFinite() && expected > 0 && seconds > 0 &&
        abs(expected - seconds) <= minOf(3.0, expected * .02)
}

/** Missing identity or duration is insufficient evidence for an automatic replacement. */
internal fun sameRecording(original: Song, candidate: Song): Boolean {
    val title = recordingText(original.title)
    val artist = recordingText(original.artist)
    if (title.isBlank() || title in setOf("未知歌曲", "unknown", "untitled") ||
        artist.isBlank() || artist in setOf("未知歌手", "unknown", "unknownartist", "variousartists", "群星")) return false
    if (title != recordingText(candidate.title) || artist != recordingText(candidate.artist)) return false
    if (!recordingDurationMatches(original, candidate.durationSeconds)) return false
    val version = "${original.title} ${original.album}"
    val otherVersion = "${candidate.title} ${candidate.album}"
    if (recordingVersions.any { it.containsMatchIn(version) != it.containsMatchIn(otherVersion) }) return false
    // Two live recordings can share both title and duration; retain their album identity too.
    return recordingVersions.none { it.containsMatchIn(version) } ||
        recordingText(original.album).let { it.isNotBlank() && it == recordingText(candidate.album) }
}

internal data class RecoveryStream(val plugin: Plugin, val song: Song, val quality: String, val source: JSONObject)

/** One track's finite recovery pass. Playback errors consume the remaining tiers before another source is searched. */
internal class SourceRecovery(
    private val plugins: Plugins,
    val original: Song,
    primary: Song,
    private val preferred: String,
) {
    companion object {
        const val PREFERENCE = "auto_source_fallback"
        const val BUDGET_MS = 25_000L
        const val PREPARE_MS = 7_000L
    }

    private val sources = ArrayDeque(plugins.state.value.plugins.filter { plugin ->
        val types = plugin.metadata.optJSONArray("supportedSearchType")
        plugin.id != primary.pluginId && plugin.enabled && plugin.capabilities.has("search") && plugin.capabilities.has("media") &&
            (types == null || (0 until types.length()).any { types.optString(it) == "music" })
    }.distinctBy { it.id })
    private var plugin = plugins.state.value.plugins.firstOrNull { it.id == primary.pluginId }
    private var song = primary
    private var tiers = ArrayDeque(plugin?.let { recoveryQualities(qualityOptions(it, primary), preferred) }.orEmpty())
    private var refresh = false
    private var canRefresh = false
    private val streams = mutableSetOf<String>()
    var current: RecoveryStream? = null
        private set
    val unsupported = mutableMapOf<String, Set<String>>()

    private fun installed(owner: Plugin): Boolean = plugins.state.value.plugins.any {
        it.id == owner.id && it.enabled && it.source == owner.source && it.variables.toString() == owner.variables.toString()
    }

    fun failed() { refresh = canRefresh; canRefresh = false }

    suspend fun next(): RecoveryStream? {
        while (true) {
            val owner = plugin
            if (owner != null && (tiers.isNotEmpty() || refresh)) {
                if (!installed(owner)) {
                    tiers.clear(); refresh = false
                    continue
                }
                val fresh = refresh
                val tier = if (fresh) current?.quality else tiers.removeFirstOrNull()
                refresh = false
                if (tier == null) continue
                var answered = false
                val source = try {
                    withTimeoutOrNull(5_000) {
                        plugins.mediaSource(owner, song, tier, refresh = fresh).also { answered = true }
                    }
                } catch (e: CancellationException) { throw e }
                  catch (_: Exception) { continue }
                val uri = Uri.parse(source?.optString("url").orEmpty())
                if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) {
                    if (answered) unsupported[song.key] = unsupported[song.key].orEmpty() + tier
                    continue
                }
                val identity = "${owner.id}\u0000$uri\u0000${sourceHeaders(source!!).toSortedMap()}"
                if (!streams.add(identity)) continue
                canRefresh = !fresh && owner.metadata.optString("cacheControl") == "cache"
                return RecoveryStream(owner, song, tier, source).also { current = it }
            }
            if (!sameRecording(original, original)) return null
            val next = sources.removeFirstOrNull() ?: return null
            if (!installed(next)) continue
            plugin = next
            canRefresh = false
            val response = try {
                withTimeoutOrNull(4_000) {
                    plugins.client.call(next.source, next.variables, "search",
                        JSONArray().put("${original.title} ${original.artist}").put(1).put("music"), next.id)
                } as? JSONObject
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { null }
            val results = response?.optJSONArray("data")
            val match = (0 until minOf(results?.length() ?: 0, 100)).mapNotNull { i -> results?.optJSONObject(i)?.let(next::song) }
                .filter { sameRecording(original, it) }
                .minByOrNull { abs(it.durationSeconds - original.durationSeconds) }
            if (match == null) { tiers.clear(); continue }
            song = match
            tiers = ArrayDeque(recoveryQualities(qualityOptions(next, match), preferred))
        }
    }
}
