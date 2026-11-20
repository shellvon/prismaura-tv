package tv.prism.catalog

import android.content.SharedPreferences
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tv.prism.library.Song
import tv.prism.sources.PluginClient
import tv.prism.sources.Plugin
import tv.prism.sources.requestRevision
import tv.prism.library.SavedSubject
import tv.prism.library.LibraryState

internal data class SearchEntity(
    val pluginId: String,
    val sourceName: String,
    val data: JSONObject,
)

internal data class SearchResultsPage(
    val songs: List<Song> = emptyList(),
    val entities: List<SearchEntity> = emptyList(),
    val nextPages: Map<String, Int> = emptyMap(),
)

internal sealed interface SearchUpdate {
    data class Page(val value: SearchResultsPage) : SearchUpdate
    data class Term(val value: String) : SearchUpdate
    data class Notice(val value: String) : SearchUpdate
    data class Running(val value: Boolean) : SearchUpdate
    data object Unavailable : SearchUpdate
}

/** Owns one screen host's search requests. Calls and updates run on the host's main scope. */
internal class SearchSession(
    private val scope: CoroutineScope,
    private val client: PluginClient,
    private val preferences: SharedPreferences,
    private val publish: (SearchUpdate) -> Unit,
) {
    private data class CachedSearch(
        val page: SearchResultsPage,
        val notice: String,
        val completed: Set<String>,
        val finished: Boolean,
    )

    private val cache = LruCache<Pair<String, String>, CachedSearch>(8)
    private var revisions = emptyList<String>()
    private val latency = runCatching {
        val saved = JSONObject(preferences.getString("latency", "{}") ?: "{}")
        saved.keys().asSequence().associateWith { saved.optLong(it) }.toMutableMap()
    }.getOrElse { mutableMapOf() }

    private var job: Job? = null
    val isRunning get() = job?.isActive == true
    var generation = 0
        private set

    fun cancel() {
        job?.cancel()
    }

    fun search(
        query: String,
        type: String,
        selected: List<Plugin>,
        append: Boolean,
        navigating: Boolean,
        deferSubmission: Boolean,
        initialPage: SearchResultsPage,
        onAccepted: () -> Unit,
    ) {
        val selectedRevisions = selected.map { it.requestRevision() }
        if (selectedRevisions != revisions) {
            cache.evictAll()
            revisions = selectedRevisions
        }
        val sources = selected.filter { plugin ->
            val supported = plugin.metadata.optJSONArray("supportedSearchType")
            (!append || plugin.id in initialPage.nextPages) &&
                (supported == null || (0 until supported.length()).any { supported.optString(it) == type })
        }.sortedBy { latency[it.id] ?: 0L }
        val term = query.trim()
        if (sources.isEmpty() || term.isEmpty()) {
            cancel()
            generation++
            publish(SearchUpdate.Running(false))
            publish(SearchUpdate.Page(if (append) initialPage.copy(nextPages = emptyMap()) else SearchResultsPage()))
            publish(SearchUpdate.Unavailable)
            publish(SearchUpdate.Notice("请选择插件并输入搜索内容"))
            return
        }

        onAccepted()
        cancel()
        val request = ++generation
        val key = term to type
        val cached = if (navigating && !append) cache.get(key) else null
        if (cached != null) {
            publish(SearchUpdate.Page(cached.page))
            publish(SearchUpdate.Notice(cached.notice))
            publish(SearchUpdate.Term(term))
            publish(SearchUpdate.Running(false))
            if (cached.finished) return
        } else if (!append) cache.remove(key)

        job = scope.launch {
            publish(SearchUpdate.Running(true))
            var page = cached?.page ?: if (append) initialPage else SearchResultsPage()
            val failures = mutableListOf<String>()
            val nextPages = cached?.page?.nextPages.orEmpty().toMutableMap()
            val completed = cached?.completed.orEmpty().toMutableSet()
            var done = completed.size
            fun progress() = "已查询 $done/${sources.size} 个音源"
            fun count() = if (type == "music") page.songs.size else page.entities.size
            fun rememberPage(notice: String, finished: Boolean) {
                cache.put(key, CachedSearch(page.copy(nextPages = nextPages.toMap()), notice, completed.toSet(), finished))
            }
            try {
                if (!append && cached == null) publish(SearchUpdate.Page(page))
                publish(SearchUpdate.Notice(progress()))
                if (deferSubmission) delay(180)
                publish(SearchUpdate.Term(term))
                coroutineScope {
                    sources.filterNot { it.id in completed }.map { plugin ->
                        async {
                            if (request != generation) return@async
                            val started = System.currentTimeMillis()
                            try {
                                val firstPage = if (append) initialPage.nextPages.getValue(plugin.id) else 1
                                val loaded = client.callPage(plugin, "search", firstPage, "data") { number ->
                                    JSONArray().put(term).put(number).put(type)
                                }
                                if (request != generation) return@async
                                val response = loaded.response
                                val items = response.optJSONArray("data") ?: JSONArray()
                                if (!response.optBoolean("isEnd", true)) nextPages[plugin.id] = loaded.number + 1
                                page = page.withResults(plugin, type, items)
                                publish(SearchUpdate.Page(page))
                                completed.add(plugin.id)
                                done++
                                val notice = "找到 ${count()} 条 · ${progress()}"
                                publish(SearchUpdate.Notice(notice))
                                if (!append) rememberPage(notice, finished = false)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (request == generation) {
                                    val reason = e.message?.trim()?.takeIf { it.isNotEmpty() } ?: "未知错误"
                                    failures += "${plugin.displayName}（$reason）"
                                    done++
                                    publish(SearchUpdate.Notice(progress()))
                                }
                            } finally {
                                if (request == generation) {
                                    val elapsed = System.currentTimeMillis() - started
                                    latency[plugin.id] = latency[plugin.id]?.let { (it * 2 + elapsed) / 3 } ?: elapsed
                                }
                            }
                        }
                    }.awaitAll()
                }
                if (request != generation) return@launch
                page = page.copy(nextPages = nextPages.toMap())
                publish(SearchUpdate.Page(page))
                val timing = JSONObject().apply { latency.forEach { (id, ms) -> put(id, ms) } }
                preferences.edit().putString("latency", timing.toString()).apply()
                val details = failures.joinToString("、").take(300)
                val notice = if (count() == 0 && failures.isNotEmpty()) "搜索异常：$details"
                    else "找到 ${count()} 条结果" + if (failures.isEmpty()) "" else "；搜索失败：$details"
                publish(SearchUpdate.Notice(notice))
                if (completed.isNotEmpty()) rememberPage(notice, finished = true)
            } finally {
                if (request == generation) publish(SearchUpdate.Running(false))
            }
        }
    }

    private fun SearchResultsPage.withResults(plugin: Plugin, type: String, items: JSONArray): SearchResultsPage {
        val entries = (0 until minOf(items.length(), 100)).mapNotNull(items::optJSONObject)
        if (type == "music") return copy(songs = (songs + entries.map(plugin::song)).distinctBy { it.key })
        return copy(entities = entities + entries.filter { item ->
            when (type) {
                "album" -> isAlbumShaped(item)
                "artist", "sheet" -> !isSongShaped(item)
                else -> true
            }
        }.map { SearchEntity(plugin.id, plugin.displayName, it) })
    }
}

/** Previously opened artist results retain their source-specific detail object. */
internal data class SearchSuggestion(val title: String, val keyword: String = title, val artwork: String = "",
                                     val subtitle: String = "", val badge: String = "热搜", val artist: SavedSubject? = null)

internal object SearchSuggestions {
    /** Preserve the source's HTTP(S) scheme, including images from a local network server. */
    private fun artwork(value: String): String = value.trim().toHttpUrlOrNull()
        ?.takeIf { it.username.isEmpty() && it.password.isEmpty() }?.toString().orEmpty()

    fun parse(array: JSONArray, source: String): List<SearchSuggestion> = (0 until minOf(array.length(), 32)).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val title = (item.opt("title") as? String)?.trim()?.take(120)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val keyword = (item.opt("keyword") as? String)?.trim()?.take(120)?.takeIf { it.isNotEmpty() } ?: title
        val subtitle = (item.opt("subtitle") as? String)?.trim()?.take(120).orEmpty()
        SearchSuggestion(title, keyword, artwork((item.opt("artwork") as? String).orEmpty()),
            listOf(subtitle, source).filter { it.isNotBlank() }.joinToString(" · "))
    }

    fun local(type: String, library: LibraryState): List<SearchSuggestion> {
        val songs = (library.favorites + library.recent + library.playlists.flatMap { it.songs }).distinctBy { it.key }
        fun subject(item: SavedSubject) = SearchSuggestion(item.title, artwork = artwork(item.artwork), badge = "收藏")
        return when (type) {
            "artist" -> library.subjects.filter { it.kind == "artist" }.map(::subject) + songs
                .filter { it.artist.isNotBlank() && it.artist != "未知歌手" }.map {
                    SearchSuggestion(it.artist, artwork = artwork(it.data.optString("artistArtwork")), badge = "常听")
                }
            "album" -> library.subjects.filter { it.kind == "album" }.map(::subject) + songs.mapNotNull {
                it.data.optString("album").takeIf { name -> name.isNotBlank() && name != "未知专辑" }?.let { name ->
                    SearchSuggestion(name, artwork = artwork(it.artwork), subtitle = it.artist, badge = "曲库")
                }
            }
            "sheet" -> library.subjects.filter { it.kind == "sheet" }.map(::subject) + library.playlists.map {
                SearchSuggestion(it.name, artwork = artwork(it.songs.firstOrNull()?.artwork.orEmpty()), subtitle = "${it.songs.size} 首", badge = "本机歌单")
            }
            else -> songs.map { SearchSuggestion(it.title, artwork = artwork(it.artwork), subtitle = it.artist, badge = "曲库") }
        }.distinctBy { it.keyword.lowercase(java.util.Locale.ROOT) }.take(12)
    }
}

internal fun subjectTitle(item: JSONObject): String = item.optString("title").ifBlank { item.optString("name") }

/* getArtistWorks(item, page, "album") has no declared return shape, so a source that
   ignores the third argument answers with songs. Songs carry `duration` and an `album`
   name; album entities do not. Without this the album shelf could present songs — and
   opening one called getAlbumInfo with a song id, which is the "album that is actually a
   playlist" confusion. */
internal fun isSongShaped(item: JSONObject): Boolean =
    item.has("duration") || item.optString("album").isNotBlank() ||
        item.optString("songmid").isNotBlank() || item.optString("mediaMid").isNotBlank()

internal fun isAlbumShaped(item: JSONObject): Boolean = !isSongShaped(item) && subjectTitle(item).isNotBlank()
