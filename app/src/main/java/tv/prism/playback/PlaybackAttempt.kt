package tv.prism.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.SessionCommand
import java.util.UUID

internal enum class PlaybackPhase(val message: String = "", val holdControls: Boolean = false) {
    Idle,
    Connecting("正在连接播放器…"),
    Resolving("正在解析音源…"),
    Buffering("正在缓冲…"),
    Switching("正在尝试其他音质或音源…"),
    Ready,
    Failed("播放失败", holdControls = true),
    Finished("队列已播完", holdControls = true),
}

internal val playbackAttemptCommand = SessionCommand("tv.prism.playback.ATTEMPT_EVENT", Bundle.EMPTY)

internal class PlaybackAttempt {
    var item: MediaItem? = null
        private set

    fun begin(source: MediaItem): MediaItem =
        source.buildUpon().setMediaId(UUID.randomUUID().toString()).build().also { item = it }

    fun clear() { item = null }

    fun accepts(mediaId: String?): Boolean = mediaId != null && mediaId == item?.mediaId
}
