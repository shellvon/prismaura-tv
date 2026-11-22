package tv.prism.lyrics

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.prism.library.Song
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class LyricPayload(
    val text: String,
    val translation: String = "",
    val source: String = "",
    val score: Int = 0,
    val sourceId: String = "",
)

internal sealed interface LyricLookup {
    data class Found(val payload: LyricPayload) : LyricLookup
    data object Missing : LyricLookup
    data object Failed : LyricLookup
}

internal enum class LyricProbeState { LOADING, READY, EMPTY, FAILED, UNSUPPORTED }

internal data class LyricProbe(
    val state: LyricProbeState,
    val payload: LyricPayload? = null,
)

object LyricSourcePrefs {
    const val PREFS = "lyrics"
    const val MODE_AUTO = "auto"
    const val MODE_PLUGIN_ONLY = "pluginOnly"

    fun mode(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("mode", MODE_AUTO)?.takeIf { it == MODE_PLUGIN_ONLY } ?: MODE_AUTO

    fun lrclib(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("lrclib", true)

    fun kugou(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("kugou", true)

    fun netease(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("netease", true)
}

internal object LyricSources {
    const val PLUGIN = "plugin"
    const val LRCLIB = "lrclib"
    const val KUGOU = "kugou"
    const val NETEASE = "netease"

    fun label(id: String): String = when (id) {
        LRCLIB -> "LRCLIB"
        KUGOU -> "酷狗音乐"
        NETEASE -> "网易云音乐"
        PLUGIN -> "音源"
        else -> "自动"
    }

    private const val LRCLIB_GET = "https://lrclib.net/api/get"
    private const val LRCLIB_SEARCH = "https://lrclib.net/api/search"
    private const val KUGOU_SEARCH = "https://songsearch.kugou.com/song_search_v2"
    private const val KUGOU_MATCH = "https://krcs.kugou.com/search"
    private const val KUGOU_LYRIC = "https://lyrics.kugou.com/download"
    private const val NETEASE_SEARCH = "https://music.163.com/api/search/get"
    private const val NETEASE_LYRIC = "https://music.163.com/api/song/lyric"
    private const val NETEASE_REFERER = "https://music.163.com/"

    /* 同一首歌反复请求时复用结果，按访问顺序淘汰。 */
    private val cache = object : LinkedHashMap<String, LyricPayload>(64, .75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, LyricPayload>,
        ) = size > 96
    }

    private fun cacheKey(id: String, song: Song) = "$id|${song.key}"

    private fun cached(id: String, song: Song): LyricPayload? =
        synchronized(cache) { cache[cacheKey(id, song)] }

    private fun remember(id: String, song: Song, payload: LyricPayload) {
        synchronized(cache) { cache[cacheKey(id, song)] = payload }
    }

    internal suspend fun lookup(id: String, song: Song): LyricLookup = withContext(Dispatchers.IO) {
        cached(id, song)?.let { return@withContext LyricLookup.Found(it) }
        try {
            val payload = when (id) {
                LRCLIB -> lrclib(song)
                KUGOU -> kugou(song)
                NETEASE -> netease(song)
                else -> null
            }
            if (payload != null && payload.text.isNotBlank()) {
                remember(id, song, payload)
                LyricLookup.Found(payload)
            } else LyricLookup.Missing
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LyricLookup.Failed
        }
    }

    internal suspend fun fetchExternal(
        song: Song, lrclibEnabled: Boolean, kugouEnabled: Boolean, neteaseEnabled: Boolean,
    ): LyricPayload? =
        coroutineScope {
            val ids = buildList {
                if (lrclibEnabled) add(LRCLIB)
                if (kugouEnabled) add(KUGOU)
                if (neteaseEnabled) add(NETEASE)
            }
            if (ids.isEmpty()) return@coroutineScope null
            ids.map { id -> async { (lookup(id, song) as? LyricLookup.Found)?.payload } }
                .awaitAll().filterNotNull().maxByOrNull { it.score }
        }

    private val brackets = Regex("""\s*[（(\[【].*$""")
    private val featuring = Regex("""(?i)\s*(feat\.?|ft\.?|featuring)\s.*$""")

    private fun normalize(value: String): String = value
        .replace(featuring, "")
        .replace(brackets, "")
        .lowercase()
        .replace(Regex("""[^\p{L}\p{N}]"""), "")

    private fun performers(value: String): Set<String> = value
        .split('/', '、', ',', '，', '&', ';', '；')
        .map { normalize(it) }
        .filter { it.isNotBlank() }
        .toSet()

    /* 候选打分：歌名完全一致 +40、互相包含 +18、不同 -30；歌手有交集 +30、无交集 -20；
       专辑一致 +10；时长差 ≤3s +50、≤6s +30、≤12s +10、≤25s 0、更远 -40；
       有逐行时间轴 +8，带翻译 +2。 */
    private fun score(
        title: String, artist: String, album: String, duration: Double, song: Song,
        timed: Boolean, translated: Boolean,
    ): Int {
        var total = 0
        val wanted = normalize(song.title)
        val got = normalize(title)
        total += when {
            wanted.isBlank() || got.isBlank() -> 0
            wanted == got -> 40
            got.contains(wanted) || wanted.contains(got) -> 18
            else -> -30
        }
        val wantedArtists = performers(song.artist)
        val gotArtists = performers(artist)
        if (wantedArtists.isNotEmpty() && gotArtists.isNotEmpty()) {
            total += if (wantedArtists.intersect(gotArtists).isNotEmpty()) 30 else -20
        }
        if (song.album.isNotBlank() && album.isNotBlank() && normalize(song.album) == normalize(album)) total += 10
        val target = song.durationSeconds
        if (target > 0 && duration > 0) {
            total += when (val diff = abs(target - duration)) {
                in 0.0..3.0 -> 50
                in 3.0..6.0 -> 30
                in 6.0..12.0 -> 10
                in 12.0..25.0 -> 0
                else -> -40
            }
        }
        if (timed) total += 8
        if (translated) total += 2
        return total
    }

    private fun lrclib(song: Song): LyricPayload? {
        /* 有专辑和曲长时 /api/get 直接返回唯一结果，未命中再退回搜索。 */
        if (song.album.isNotBlank() && song.durationSeconds > 0) {
            val single = LyricsHttp.json(
                LyricsHttp.url(
                    LRCLIB_GET,
                    "track_name" to song.title,
                    "artist_name" to song.artist,
                    "album_name" to song.album,
                    "duration" to song.durationSeconds.roundToInt().toString(),
                ),
                appAgent = true,
            )?.let { row -> parseRow(row, song) }
            if (single != null) return single
        }
        val rows = LyricsHttp.array(
            LyricsHttp.url(LRCLIB_SEARCH, "track_name" to song.title, "artist_name" to song.artist),
            appAgent = true,
        ) ?: return null
        return (0 until rows.length())
            .mapNotNull { rows.optJSONObject(it)?.let { row -> parseRow(row, song) } }
            .maxByOrNull { it.score }
    }

    private fun parseRow(row: JSONObject, song: Song): LyricPayload? {
        if (row.optBoolean("instrumental")) return null
        val synced = row.optString("syncedLyrics")
        val text = synced.ifBlank { row.optString("plainLyrics") }
        if (text.isBlank()) return null
        return LyricPayload(
            text = text,
            source = label(LRCLIB),
            sourceId = LRCLIB,
            score = score(
                row.optString("trackName").ifBlank { row.optString("name") },
                row.optString("artistName"),
                row.optString("albumName"),
                row.optDouble("duration", 0.0),
                song, synced.isNotBlank(), false,
            ),
        )
    }

    private fun kugou(song: Song): LyricPayload? {
        val keyword = listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" ")
        val lists = LyricsHttp.json(
            LyricsHttp.url(KUGOU_SEARCH, "keyword" to keyword, "page" to "1", "pagesize" to "10"),
        )?.optJSONObject("data")?.optJSONArray("lists") ?: return null
        val best = (0 until lists.length()).mapNotNull { index ->
            val row = lists.optJSONObject(index) ?: return@mapNotNull null
            val hash = row.optString("FileHash")
            if (hash.isBlank()) return@mapNotNull null
            val title = row.optString("SongName")
            if (title.contains("伴奏") || title.contains("纯音乐")) return@mapNotNull null
            hash to score(title, row.optString("SingerName"), row.optString("AlbumName"),
                row.optDouble("Duration", 0.0), song, true, false)
        }.maxByOrNull { it.second } ?: return null
        val candidate = LyricsHttp.json(
            LyricsHttp.url(KUGOU_MATCH, "ver" to "1", "man" to "yes", "client" to "mobi", "hash" to best.first),
        )?.optJSONArray("candidates")?.optJSONObject(0) ?: return null
        val id = candidate.optString("id")
        val accessKey = candidate.optString("accesskey")
        if (id.isBlank() || accessKey.isBlank()) return null
        val encoded = LyricsHttp.json(
            LyricsHttp.url(
                KUGOU_LYRIC, "ver" to "1", "client" to "pc", "id" to id,
                "accesskey" to accessKey, "fmt" to "lrc", "charset" to "utf8",
            ),
        )?.optString("content").orEmpty()
        if (encoded.isBlank()) return null
        val text = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        if (text.isBlank()) return null
        return LyricPayload(text, source = label(KUGOU), sourceId = KUGOU, score = best.second)
    }

    private fun netease(song: Song): LyricPayload? {
        val keyword = listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" ")
        val songs = LyricsHttp.json(
            LyricsHttp.url(NETEASE_SEARCH, "s" to keyword, "type" to "1", "limit" to "10", "offset" to "0"),
            NETEASE_REFERER,
        )?.optJSONObject("result")?.optJSONArray("songs") ?: return null
        val best = (0 until songs.length()).mapNotNull { index ->
            val row = songs.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optLong("id")
            if (id <= 0) return@mapNotNull null
            val title = row.optString("name")
            if (title.contains("伴奏") || title.contains("纯音乐")) return@mapNotNull null
            val artists = row.optJSONArray("artists")?.let { list ->
                (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("name") }.joinToString(" / ")
            }.orEmpty()
            id to score(title, artists, row.optJSONObject("album")?.optString("name").orEmpty(),
                row.optDouble("duration", 0.0) / 1000.0, song, true, false)
        }.maxByOrNull { it.second } ?: return null
        val lyric = LyricsHttp.json(
            LyricsHttp.url(NETEASE_LYRIC, "id" to best.first.toString(), "lv" to "1", "kv" to "1", "tv" to "-1"),
            NETEASE_REFERER,
        ) ?: return null
        val text = lyric.optJSONObject("lrc")?.optString("lyric").orEmpty()
        if (text.isBlank()) return null
        return LyricPayload(
            text,
            lyric.optJSONObject("tlyric")?.optString("lyric").orEmpty(),
            label(NETEASE),
            best.second,
            NETEASE,
        )
    }
}
