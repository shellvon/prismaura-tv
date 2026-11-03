package tv.prism

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** A suggestion starts a search; it is not a partially populated playable entity. */
internal data class SearchSuggestion(val title: String, val keyword: String = title, val artwork: String = "",
                                     val subtitle: String = "", val badge: String = "热搜")

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
