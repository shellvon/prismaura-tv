package tv.prism

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal fun primaryKeys(root: JSONObject): List<String> {
    val keys = root.optJSONArray("primaryKey") ?: return listOf("id")
    return (0 until keys.length()).mapNotNull { keys.opt(it) as? String }.filter { it.isNotBlank() }.distinct().ifEmpty { listOf("id") }
}

private fun itemIdentity(data: JSONObject, keys: List<String>, fallback: String): String =
    if (keys == listOf("id")) data.optString("id").ifBlank { fallback }
    else "composite:" + JSONArray().apply { keys.forEach { put(JSONArray().put(it).put(data.opt(it) ?: JSONObject.NULL)) } }

data class Song(val pluginId: String, val data: JSONObject, val sourceName: String = "", val primaryKey: List<String> = listOf("id")) {
    val title get() = data.optString("title").ifBlank { "未知歌曲" }
    val artist get() = data.optString("artist").ifBlank { "未知歌手" }
    /* MusicFree's field is artwork, but a few plugins only fill coverImg. */
    val artwork get() = data.optString("artwork").ifBlank { data.optString("coverImg") }
    /** Prism MV contract: mvId is the sole marker; getMvSource resolves it. */
    val mvId get() = (data.opt("mvId") as? String)?.trim().orEmpty()
    val hasMv get() = mvId.isNotBlank()
    val album get() = data.optString("album")
    /* Prism extension, not part of MusicFree: sources that expose a platform pay flag may mark
       an item VIP. It is a hint for the list, never a licence check and never an unlock. */
    val isVip get() = data.optBoolean("vip")
    val platform get() = MusicPlatform.forSong(pluginId, sourceName, data)
    val sourceLabel get() = if (platform != MusicPlatform.OTHER) platform.label else sourceName.ifBlank { data.optString("platform").ifBlank { data.optString("source").ifBlank { "未知音源" } } }
    val key get() = "$pluginId:${itemIdentity(data, primaryKey, data.toString())}"
    /* MusicFree's IMusicItem.duration is seconds. A few plugins hand back milliseconds
       instead; anything past 10 hours is certainly ms, so treat it as such. */
    private val seconds get() = data.optDouble("duration", 0.0).let { if (it > 36_000) it / 1000 else it }
    val durationLabel get() = if (seconds > 0) "%d:%02d".format(seconds.toInt() / 60, seconds.toInt() % 60) else "—"
}

data class MusicPlaylist(val id: String, val name: String, val songs: List<Song>, val platform: String = "custom")
/** An artist, album or sheet kept for later. MusicFree calls these entities; they are not songs. */
data class SavedSubject(val pluginId: String, val kind: String, val data: JSONObject, val sourceName: String = "", val primaryKey: List<String> = listOf("id")) {
    val title get() = data.optString("title").ifBlank { data.optString("name").ifBlank { "未命名" } }
    val artwork get() = data.optString("artwork").ifBlank { data.optString("coverImg") }
    val key get() = "$pluginId:$kind:${itemIdentity(data, primaryKey, title)}"
    val kindLabel get() = when (kind) { "artist" -> "歌手"; "album" -> "专辑"; else -> "歌单" }
}
data class LibraryState(
    val favorites: List<Song> = emptyList(),
    val recent: List<Song> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(),
    val subjects: List<SavedSubject> = emptyList(),
    val error: String? = null,
)

/** MusicFree's original item JSON must survive storage because plugins may add their own fields. */
class MusicLibrary(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "music-library.json"))
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(read())
    val state: StateFlow<LibraryState> = mutable

    private fun read(): LibraryState = try {
        if (!file.baseFile.exists()) LibraryState() else parse(JSONObject(file.openRead().bufferedReader().use { it.readText() }))
    } catch (e: Exception) { LibraryState(error = "曲库读取失败，已停止写入：${e.message}") }

    private fun parse(root: JSONObject): LibraryState {
        val lists = root.optJSONArray("playlists") ?: JSONArray()
        val subjects = root.optJSONArray("subjects") ?: JSONArray()
        return LibraryState(
            songs(root.optJSONArray("favorites")), songs(root.optJSONArray("recent")),
            (0 until lists.length()).map { i ->
                val item = lists.getJSONObject(i)
                MusicPlaylist(item.getString("id"), item.getString("name"), songs(item.optJSONArray("songs")), item.optString("platform", "custom"))
            },
            (0 until subjects.length()).map { i ->
                val item = subjects.getJSONObject(i)
                SavedSubject(item.getString("pluginId"), item.getString("kind"), item.getJSONObject("data"), item.optString("sourceName"), primaryKeys(item))
            },
        )
    }

    private fun songs(array: JSONArray?): List<Song> = if (array == null) emptyList() else
        (0 until array.length()).map { index -> array.getJSONObject(index).let { Song(it.getString("pluginId"), it.getJSONObject("data"), it.optString("sourceName"), primaryKeys(it)) } }

    private fun json(songs: List<Song>) = JSONArray().also { out ->
        songs.forEach { out.put(JSONObject().put("pluginId", it.pluginId).put("data", it.data).put("sourceName", it.sourceName).put("primaryKey", JSONArray(it.primaryKey))) }
    }

    private suspend fun change(transform: (LibraryState) -> LibraryState) = mutex.withLock {
        val old = mutable.value
        check(old.error == null) { old.error ?: "曲库读取失败" }
        val next = transform(old)
        if (next == old) return@withLock
        val lists = JSONArray()
        next.playlists.forEach { lists.put(JSONObject().put("id", it.id).put("name", it.name).put("songs", json(it.songs)).put("platform", it.platform)) }
        val subjects = JSONArray().also { out ->
            next.subjects.forEach { out.put(JSONObject().put("pluginId", it.pluginId).put("kind", it.kind).put("data", it.data).put("sourceName", it.sourceName).put("primaryKey", JSONArray(it.primaryKey))) }
        }
        val root = JSONObject().put("favorites", json(next.favorites)).put("recent", json(next.recent)).put("playlists", lists).put("subjects", subjects)
        withContext(Dispatchers.IO) {
            val stream = file.startWrite()
            try { stream.write(root.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
        }
        mutable.value = next
    }

    /* 备份导出/恢复共用磁盘上的原始 JSON：插件的自定义字段必须原样保留，
       重新拼装一次只会丢掉应用不认识的东西。 */
    suspend fun backupJson(): JSONObject = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists()) JSONObject() else JSONObject(file.baseFile.readText())
    }

    suspend fun restoreBackup(root: JSONObject) = mutex.withLock {
        val next = parse(root)
        withContext(Dispatchers.IO) {
            val stream = file.startWrite()
            try { stream.write(root.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
        }
        mutable.value = next
    }

    suspend fun toggleFavorite(song: Song) = change { state ->
        state.copy(favorites = if (state.favorites.any { it.key == song.key }) state.favorites.filterNot { it.key == song.key } else state.favorites + song)
    }

    /** Existing entries keep their raw fields, so declared compound IDs can be restored in place. */
    suspend fun alignPrimaryKeys(plugins: List<Plugin>) = change { state ->
        val definitions = plugins.associate { it.id to primaryKeys(it.metadata) }
        fun songs(items: List<Song>) = items.map { song ->
            definitions[song.pluginId]?.takeIf { it != song.primaryKey }?.let { song.copy(primaryKey = it) } ?: song
        }.distinctBy { it.key }
        state.copy(favorites = songs(state.favorites), recent = songs(state.recent),
            playlists = state.playlists.map { it.copy(songs = songs(it.songs)) },
            subjects = state.subjects.map { subject ->
                definitions[subject.pluginId]?.takeIf { it != subject.primaryKey }?.let { subject.copy(primaryKey = it) } ?: subject
            }.distinctBy { it.key })
    }
    suspend fun record(song: Song) = change { state ->
        state.copy(recent = (listOf(song) + state.recent.filterNot { it.key == song.key }).take(100))
    }
    suspend fun removeRecent(song: Song) = change { state ->
        state.copy(recent = state.recent.filterNot { it.key == song.key })
    }
    suspend fun toggleSubject(subject: SavedSubject) = change { state ->
        state.copy(subjects = if (state.subjects.any { it.key == subject.key }) state.subjects.filterNot { it.key == subject.key } else listOf(subject) + state.subjects)
    }
    /* Batch removals for 我的 的管理模式: the selection is a set of keys, so one rewrite
       covers the whole operation instead of one file write per item. */
    suspend fun removeFavorites(keys: Set<String>) = change { state ->
        state.copy(favorites = state.favorites.filterNot { it.key in keys })
    }
    suspend fun removeRecent(keys: Set<String>) = change { state ->
        state.copy(recent = state.recent.filterNot { it.key in keys })
    }
    suspend fun removeSubjects(keys: Set<String>) = change { state ->
        state.copy(subjects = state.subjects.filterNot { it.key in keys })
    }
    suspend fun removePlaylists(ids: Set<String>) = change { state ->
        state.copy(playlists = state.playlists.filterNot { it.id in ids })
    }
    suspend fun create(name: String, songs: List<Song> = emptyList(), platform: String = "custom") = change { state ->
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 60) { "歌单名需为 1 至 60 个字符" }
        state.copy(playlists = state.playlists + MusicPlaylist(UUID.randomUUID().toString(), clean, songs.distinctBy { it.key }, platform))
    }
    suspend fun add(playlistId: String, song: Song) = change { state ->
        require(state.playlists.any { it.id == playlistId }) { "歌单不存在" }
        state.copy(playlists = state.playlists.map { if (it.id == playlistId && it.songs.none { s -> s.key == song.key }) it.copy(songs = it.songs + song) else it })
    }
    suspend fun removeSong(playlistId: String, song: Song) = change { state ->
        state.copy(playlists = state.playlists.map { if (it.id == playlistId) it.copy(songs = it.songs.filterNot { s -> s.key == song.key }) else it })
    }
    suspend fun removeSongsFrom(playlistId: String, keys: Set<String>) = change { state ->
        state.copy(playlists = state.playlists.map { if (it.id == playlistId) it.copy(songs = it.songs.filterNot { s -> s.key in keys }) else it })
    }
    suspend fun delete(playlistId: String) = change { state ->
        state.copy(playlists = state.playlists.filterNot { it.id == playlistId })
    }
}
