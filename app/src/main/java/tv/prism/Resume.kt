package tv.prism

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PlaybackResume(val songs: List<Song>, val index: Int, val position: Long,
                          val radio: Boolean = false, val radioRecent: List<String> = emptyList())

/** Last queue and position, so the next launch can put the needle back down where it stopped. */
class ResumeStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "playback-resume.json"))

    fun read(): PlaybackResume? = runCatching {
        if (!file.baseFile.exists()) return@runCatching null
        val root = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        val array = root.optJSONArray("queue") ?: return@runCatching null
        val songs = (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { entry ->
                entry.optJSONObject("data")?.let { Song(entry.optString("pluginId"), it, entry.optString("sourceName"), primaryKeys(entry)) }
            }
        }
        if (songs.isEmpty()) return@runCatching null
        val recent = root.optJSONArray("radioRecent") ?: JSONArray()
        PlaybackResume(songs, root.optInt("index", 0).coerceIn(0, songs.lastIndex), root.optLong("position", 0L).coerceAtLeast(0L),
            root.optBoolean("radio"), (0 until minOf(recent.length(), 80)).mapNotNull { recent.opt(it) as? String })
    }.getOrNull()

    fun write(songs: List<Song>, index: Int, position: Long, radio: Boolean = false, radioRecent: List<String> = emptyList()) {
        runCatching {
            val array = JSONArray()
            /* A resume point only needs the neighbourhood of the current track; an enormous
               imported sheet would otherwise be rewritten on every save. */
            val current = index.coerceIn(0, songs.lastIndex.coerceAtLeast(0))
            val start = (current - MAX / 2).coerceAtLeast(0).coerceAtMost((songs.size - MAX).coerceAtLeast(0))
            songs.drop(start).take(MAX).forEach { song ->
                array.put(JSONObject().put("pluginId", song.pluginId).put("data", song.data).put("sourceName", song.sourceName).put("primaryKey", JSONArray(song.primaryKey)))
            }
            val root = JSONObject().put("queue", array).put("index", current - start).put("position", position.coerceAtLeast(0L))
                .put("radio", radio).put("radioRecent", JSONArray(radioRecent.takeLast(80)))
            val stream = file.startWrite()
            try { stream.write(root.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
        }
    }

    companion object { private const val MAX = 300 }
}
