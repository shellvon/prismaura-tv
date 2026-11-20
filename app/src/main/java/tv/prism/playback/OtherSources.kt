package tv.prism.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import tv.prism.library.Song
import tv.prism.sources.Plugin
import tv.prism.sources.Plugins

internal fun alternateSongSources(song: Song, plugins: List<Plugin>): List<Plugin> {
    if (!sameRecordingIdentity(song, song)) return emptyList()
    return plugins.filter { plugin ->
        val types = plugin.metadata.optJSONArray("supportedSearchType")
        plugin.id != song.pluginId && plugin.enabled && plugin.capabilities.has("search") &&
            plugin.capabilities.has("media") &&
            (types == null || (0 until types.length()).any { types.optString(it) == "music" })
    }.distinctBy { it.id }
}

internal data class AlternateSong(val song: Song, val matchesRecording: Boolean)

/** Only the explicit chooser invokes search. A null result means the source could not answer. */
internal suspend fun findAlternateSongs(
    original: Song,
    sources: List<Plugin>,
    plugins: Plugins,
    onSource: (List<AlternateSong>?) -> Unit,
) = coroutineScope {
    val slots = Semaphore(3)
    val query = JSONArray().put("${original.title} ${original.artist}").put(1).put("music").toString()
    sources.map { source ->
        async {
            val result = slots.withPermit {
                try {
                    withTimeoutOrNull(5_000) {
                        val response = plugins.client.call(source.source, source.variables, "search",
                            JSONArray(query), source.id) as? JSONObject ?: return@withTimeoutOrNull null
                        val data = response.optJSONArray("data") ?: return@withTimeoutOrNull null
                        (0 until minOf(data.length(), 100)).mapNotNull { data.optJSONObject(it)?.let(source::song) }
                            .filter { sameRecordingIdentity(original, it) }
                            .distinctBy { it.key }
                            .map { AlternateSong(it, sameRecording(original, it)) }
                            .sortedByDescending { it.matchesRecording }
                            .take(3)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            }
            onSource(result)
        }
    }.awaitAll()
}
