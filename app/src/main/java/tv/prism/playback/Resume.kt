package tv.prism.playback

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import tv.prism.library.Song
import tv.prism.library.primaryKeys

data class PlaybackResume(
    val songs: List<Song>,
    val index: Int,
    val position: Long,
    val radio: Boolean = false,
    val radioRecent: List<String> = emptyList(),
    val shuffleOrder: List<Int> = emptyList(),
    val finished: Boolean = false,
)

/** Last queue and position, so the next launch can put the needle back down where it stopped. */
class ResumeStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "playback-resume.json"))

    fun read(): PlaybackResume? =
        synchronized(fileLock) {
            runCatching {
                val root = JSONObject(file.openRead().bufferedReader().use { it.readText() })
                val array = root.optJSONArray("queue") ?: return@runCatching null
                val entries = (0 until array.length()).mapNotNull { oldIndex ->
                    val entry = array.optJSONObject(oldIndex) ?: return@mapNotNull null
                    val pluginId = (entry.opt("pluginId") as? String)?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val data = entry.optJSONObject("data")?.takeIf { it.length() > 0 } ?: return@mapNotNull null
                    oldIndex to Song(pluginId, data, entry.optString("sourceName"), primaryKeys(entry))
                }
                if (entries.isEmpty()) return@runCatching null
                val remapped = entries.withIndex().associate { (index, entry) -> entry.first to index }
                val savedIndex = root.optInt("index", 0).coerceIn(0, array.length() - 1)
                val current = remapped[savedIndex]
                val index = current ?: entries.indexOfFirst { it.first > savedIndex }.takeIf { it >= 0 } ?: entries.lastIndex
                val recent = root.optJSONArray("radioRecent") ?: JSONArray()
                val order = root.optJSONArray("shuffleOrder") ?: JSONArray()
                val shuffle = (0 until minOf(order.length(), MAX))
                    .mapNotNull { remapped[order.optInt(it, -1)] }.distinct()
                PlaybackResume(
                    entries.map { it.second }, index,
                    if (current != null) root.optLong("position", 0L).coerceAtLeast(0L) else 0L,
                    root.optBoolean("radio"),
                    (0 until minOf(recent.length(), 80)).mapNotNull { recent.opt(it) as? String },
                    shuffle.takeIf { it.size == entries.size }.orEmpty(),
                    current != null && root.optBoolean("finished"),
                )
            }.getOrNull()
        }

    internal fun enqueueWrite(resume: PlaybackResume) {
        writer.launch {
            write(
                resume.songs,
                resume.index,
                resume.position,
                resume.radio,
                resume.radioRecent,
                resume.shuffleOrder,
                resume.finished,
            )
        }
    }

    fun write(
        songs: List<Song>,
        index: Int,
        position: Long,
        radio: Boolean = false,
        radioRecent: List<String> = emptyList(),
        shuffleOrder: List<Int> = emptyList(),
        finished: Boolean = false,
    ) {
        synchronized(fileLock) {
            runCatching {
                if (songs.isEmpty()) {
                    file.delete()
                    return@runCatching
                }
                val array = JSONArray()
                /* A resume point only needs the neighbourhood of the current track; an enormous
                imported sheet would otherwise be rewritten on every save. Use playback order
                for this window, then retain source order within the saved subset. */
                val current = index.coerceIn(0, songs.lastIndex.coerceAtLeast(0))
                val shuffled =
                    shuffleOrder.size == songs.size &&
                        shuffleOrder.toSet().size == songs.size &&
                        shuffleOrder.all { it in songs.indices }
                val order = if (shuffled) shuffleOrder else songs.indices.toList()
                val cursor = order.indexOf(current).coerceAtLeast(0)
                val start =
                    (cursor - MAX / 2)
                        .coerceAtLeast(0)
                        .coerceAtMost((songs.size - MAX).coerceAtLeast(0))
                val retained = order.drop(start).take(MAX).sorted()
                val remapped = retained.withIndex().associate { it.value to it.index }
                retained.forEach { index ->
                    val song = songs[index]
                    array.put(
                        JSONObject()
                            .put("pluginId", song.pluginId)
                            .put("data", song.data)
                            .put("sourceName", song.sourceName)
                            .put("primaryKey", JSONArray(song.primaryKey))
                    )
                }
                val savedOrder = if (shuffled) order.mapNotNull { remapped[it] } else emptyList()
                val root =
                    JSONObject()
                        .put("queue", array)
                        .put("index", remapped[current] ?: 0)
                        .put("position", position.coerceAtLeast(0L))
                        .put("radio", radio)
                        .put("finished", finished)
                        .put("radioRecent", JSONArray(radioRecent.takeLast(80)))
                        .put("shuffleOrder", JSONArray(savedOrder))
                val stream = file.startWrite()
                try {
                    stream.write(root.toString().toByteArray(Charsets.UTF_8))
                    file.finishWrite(stream)
                } catch (e: Exception) {
                    file.failWrite(stream)
                    throw e
                }
            }
        }
    }

    companion object {
        private const val MAX = 300
        private val fileLock = Any()

        // Outlives an Activity rotation so the last snapshot still reaches disk.
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    }
}
