package tv.prism

import org.json.JSONObject
import kotlin.random.Random
import java.util.Locale

/** Host-recognised interfaces, independent of network health and account privileges. */
data class PluginCapabilities(private val metadata: JSONObject) {
    fun has(name: String) = metadata.optBoolean(name)
    val recommendations get() = has("recommendSheets") && has("sheetInfo")
    val charts get() = has("topLists") && has("topDetail")
    val artist get() = has("artistWorks")
    val labels get() = buildList {
        if (has("search")) add("搜索")
        if (recommendations) add("推荐歌单")
        if (charts) add("榜单")
        if (artist) add("歌手详情")
        if (has("albumInfo")) add("专辑详情")
        if (has("sheetInfo")) add("歌单详情")
        if (has("lyric")) add("歌词")
        if (has("mvSource")) add("MV")
        if (has("importSheet")) add("歌单导入")
        if (has("importItem")) add("单曲导入")
        if (has("loginQr")) add("扫码登录")
        if (has("hotSearch")) add("热搜")
    }
}

internal object Discovery {
    fun select(plugins: List<Plugin>, preferred: String, supports: (PluginCapabilities) -> Boolean): Plugin? =
        plugins.filter { it.enabled && supports(it.capabilities) }.let { eligible ->
            eligible.firstOrNull { it.id == preferred } ?: eligible.firstOrNull()
        }

    fun localSongs(library: LibraryState, plugins: List<Plugin>): List<Song> {
        val active = plugins.filter { it.enabled }.map { it.id }.toSet()
        return (library.favorites + library.recent + library.playlists.flatMap { it.songs })
            .filter { it.pluginId in active || (it.pluginId == "local" && it.data.optBoolean("persisted")) }
            .distinctBy { it.key }
    }

    fun artists(library: LibraryState, plugins: List<Plugin>): List<Song> {
        val capable = plugins.filter { it.enabled && it.capabilities.artist }.associateBy { it.id }
        return localSongs(library, plugins).filter { song ->
            val plugin = capable[song.pluginId] ?: return@filter false
            song.artist != "未知歌手" && song.artist.isNotBlank() &&
                (song.data.optString("artistId").isNotBlank() || plugin.capabilities.has("search") &&
                    (plugin.metadata.optJSONArray("supportedSearchType")?.let { types ->
                        (0 until types.length()).any { types.optString(it) == "artist" }
                    } ?: true))
        }.distinctBy { "${it.pluginId}:${it.data.optString("artistId").ifBlank { it.artist }}" }.take(12)
    }

    /** Avoid the same recording across sources when a usable title and artist are available. */
    fun radioIdentity(song: Song): String {
        fun clean(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[\\s\\p{P}]+"), "")
        return if (song.title.isNotBlank() && song.title != "未知歌曲" && song.artist.isNotBlank() && song.artist != "未知歌手")
            "track:${clean(song.title)}:${clean(song.artist)}" else song.key
    }

    /** Prefer unheard candidates, then the least recently played; never duplicate the pending tail. */
    fun radio(local: List<Song>, candidates: List<Song>, random: Random = Random.Default,
              recent: List<String> = emptyList(), excluded: Set<String> = emptySet(), limit: Int = 60): List<Song> {
        val known = local.map(::radioIdentity).toSet()
        val history = recent.withIndex().associate { it.value to it.index }
        val familiar = local.distinctBy(::radioIdentity).filterNot { radioIdentity(it) in excluded }.shuffled(random)
        val fresh = candidates.distinctBy(::radioIdentity).filterNot { radioIdentity(it) in known || radioIdentity(it) in excluded }.shuffled(random)
        return buildList {
            fun mix(a: List<Song>, b: List<Song>) {
                var i = 0; var j = 0
                while (size < limit && (i < a.size || j < b.size)) {
                    repeat(2) { if (i < a.size && size < limit) add(a[i++]) }
                    if (j < b.size && size < limit) add(b[j++])
                }
            }
            mix(familiar.filterNot { radioIdentity(it) in history }, fresh.filterNot { radioIdentity(it) in history })
            mix(familiar.filter { radioIdentity(it) in history }.sortedBy { history[radioIdentity(it)] },
                fresh.filter { radioIdentity(it) in history }.sortedBy { history[radioIdentity(it)] })
        }
    }

    /** Only expose scenes that match real tags; keep the complete original tag for plugin calls. */
    fun scenes(tags: List<JSONObject>): List<Pair<String, JSONObject>> = listOf(
        "放松" to listOf("放松", "轻松", "舒缓", "治愈"),
        "专注" to listOf("专注", "学习", "工作", "阅读"),
        "夜晚" to listOf("夜晚", "夜深", "深夜", "睡前", "助眠"),
    ).mapNotNull { (label, words) ->
        tags.firstOrNull { tag -> words.any { tag.optString("title").contains(it) } }?.let { label to it }
    }.distinctBy { it.second.optString("id") }
}
