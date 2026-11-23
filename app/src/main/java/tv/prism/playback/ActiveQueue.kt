package tv.prism.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tv.prism.library.Song

internal data class PlayingSource(val originalKey: String, val song: Song)

// Keep the UI's queue when Android recreates the activity; the audio itself lives in PlaybackService.
internal object ActiveQueue {
    private var entries by mutableStateOf<List<Song>>(emptyList())
    var songs: List<Song>
        get() = entries
        set(value) {
            order = order.updated(value.map { it.key })
            entries = value
        }
    var order by mutableStateOf(PlaybackOrder())
    val attempt = PlaybackAttempt()
    var phase by mutableStateOf(PlaybackPhase.Connecting)
    var failureDetail by mutableStateOf("")
    var index by mutableIntStateOf(-1)
    var radio by mutableStateOf(false)
    var listenKey = ""
    var listenMs = 0L
    var listenCounted = false
    var radioRecent: List<String> = emptyList()
    var source by mutableStateOf<PlayingSource?>(null)

    fun replace(items: List<Song>, start: Int, radioMode: Boolean, shuffle: Boolean) {
        radio = radioMode
        songs = items
        order = PlaybackOrder().updated(items.map { it.key }).let {
            if (!radioMode && shuffle) it.randomized(start) else it
        }
    }

    fun placeForImmediatePlayback(song: Song): Int {
        val existing = songs.indexOfFirst { it.key == song.key }
        val target = if (existing >= 0) existing else (index + 1).coerceIn(0, songs.size)
        if (existing < 0) songs = songs.toMutableList().also { it.add(target, song) }
        order = order.placed(target, index, next = true)
        return target
    }

    fun enqueue(song: Song, next: Boolean): Boolean {
        val current = songs.getOrNull(index)?.key
        if (current == song.key) return false
        val updated = songs.filterNot { it.key == song.key }.toMutableList()
        val currentIndex = if (current == null) -1 else {
            index - songs.take(index).count { it.key == song.key }
        }
        val at = if (next && currentIndex >= 0) currentIndex + 1 else updated.size
        updated.add(at, song)
        songs = updated
        index = currentIndex.coerceAtLeast(0)
        order = order.placed(at, index, next)
        return true
    }
}
