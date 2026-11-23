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
    val timedLines: List<LyricLine>? = null,
) {
    fun lines() = timedLines ?: Lyrics.parse(text, translation)
}

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
    fun migrateMode(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = prefs.getString("mode", null) ?: return
        val editor = prefs.edit().remove("mode")
        if (mode == "pluginOnly") {
            for (source in listOf("lrclib", "kugou", "netease")) editor.putBoolean(source, false)
        }
        editor.apply()
    }

    fun lrclib(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("lrclib", false)

    fun kugou(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("kugou", false)

    fun netease(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("netease", false)
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
            val lines = payload?.lines().orEmpty()
            if (payload == null || lines.isEmpty()) {
                LyricLookup.Missing
            } else {
                val ranked = if (lines.any { !it.words.isNullOrEmpty() }) payload.copy(score = payload.score + 100) else payload
                remember(id, song, ranked)
                LyricLookup.Found(ranked)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LyricLookup.Failed
        }
    }

    internal suspend fun fetchExternal(song: Song, sources: Set<String>): LyricLookup =
        coroutineScope {
            val results = sources.map { id -> async { lookup(id, song) } }.awaitAll()
            results.filterIsInstance<LyricLookup.Found>().maxByOrNull { it.payload.score }
                ?: if (LyricLookup.Failed in results) LyricLookup.Failed else LyricLookup.Missing
        }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("""[^\p{L}\p{N}]"""), "")

    private fun performers(value: String): Set<String> = value
        .split('/', '、', ',', '，', '&', '；')
        .map { normalize(it) }
        .filter { it.isNotBlank() }
        .toSet()

    // Only rank candidates after checking that the metadata identifies the same recording.
    internal fun matchScore(
        title: String, artist: String, album: String, duration: Double, song: Song,
        timed: Boolean,
    ): Int? {
        val wantedTitle = normalize(song.title)
        if (wantedTitle.isBlank() || normalize(title) != wantedTitle) return null
        val wantedArtists = performers(song.artist)
        if (wantedArtists.isEmpty() || performers(artist) != wantedArtists) return null

        val wantedAlbum = normalize(song.album)
        val candidateAlbum = normalize(album)
        val albumsKnown = wantedAlbum.isNotBlank() && candidateAlbum.isNotBlank()
        if (albumsKnown && wantedAlbum != candidateAlbum) return null

        val durationsKnown = song.durationSeconds > 0 && duration > 0
        val timingScore = if (durationsKnown) {
            val difference = abs(song.durationSeconds - duration)
            if (!difference.isFinite() || difference > 3.0) return null
            30 - (difference * 10).roundToInt()
        } else 0
        if (!durationsKnown && !albumsKnown) return null
        return timingScore + (if (albumsKnown) 35 else 0) + (if (timed) 8 else 0)
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
            if (single != null) return single.withWordTiming()
        }
        val rows = LyricsHttp.array(
            LyricsHttp.url(LRCLIB_SEARCH, "track_name" to song.title, "artist_name" to song.artist),
            appAgent = true,
        ) ?: return null
        return (0 until rows.length())
            .mapNotNull { rows.optJSONObject(it)?.let { row -> parseRow(row, song) } }
            .maxByOrNull { it.score }
            ?.withWordTiming()
    }

    private data class LrclibMatch(val payload: LyricPayload, val row: JSONObject) {
        val score get() = payload.score
        fun withWordTiming(): LyricPayload {
            val lines = (row.opt("lyricsfile") as? String)?.let(::parseLyricsFile)
            android.util.Log.d("PrismLyrics", "LRCLIB id=${row.optLong("id")} timedLines=${lines?.count { it.words != null } ?: 0}")
            return payload.copy(timedLines = lines)
        }
    }

    private fun parseRow(row: JSONObject, song: Song): LrclibMatch? {
        if (row.optBoolean("instrumental")) return null
        val synced = row.optString("syncedLyrics")
        val text = synced.ifBlank { row.optString("plainLyrics") }
        if (text.isBlank()) return null
        val score = matchScore(
            row.optString("trackName").ifBlank { row.optString("name") },
            row.optString("artistName"),
            row.optString("albumName"),
            row.optDouble("duration", 0.0),
            song, synced.isNotBlank(),
        ) ?: return null
        return LrclibMatch(LyricPayload(text, source = label(LRCLIB), sourceId = LRCLIB, score = score), row)
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
            val score = matchScore(title, row.optString("SingerName"), row.optString("AlbumName"),
                row.optDouble("Duration", 0.0), song, true) ?: return@mapNotNull null
            hash to score
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
            val score = matchScore(title, artists, row.optJSONObject("album")?.optString("name").orEmpty(),
                row.optDouble("duration", 0.0) / 1000.0, song, true) ?: return@mapNotNull null
            id to score
        }.maxByOrNull { it.second } ?: return null
        val lyric = LyricsHttp.json(
            LyricsHttp.url(NETEASE_LYRIC, "id" to best.first.toString(), "lv" to "1", "kv" to "1", "tv" to "-1", "yv" to "1"),
            NETEASE_REFERER,
        ) ?: return null
        val wordTimed = lyric.optJSONObject("yrc")?.optString("lyric").orEmpty()
        val text = wordTimed.takeIf { Lyrics.parse(it).any { line -> !line.words.isNullOrEmpty() } }
            ?: lyric.optJSONObject("lrc")?.optString("lyric").orEmpty()
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
