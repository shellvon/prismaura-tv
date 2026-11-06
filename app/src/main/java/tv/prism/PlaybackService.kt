package tv.prism

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ConcurrentHashMap

/** Playback is separate from the UI so a native decoder crash does not also kill the UI. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val headersByOrigin = ConcurrentHashMap<String, Map<String, String>>()

    override fun onCreate() {
        super.onCreate()
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector { mime, secure, tunneling ->
                val codecs = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                if (MimeTypes.isAudio(mime)) codecs.sortedBy { !it.softwareOnly } else codecs
            }
        val dataSources = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true))) { spec ->
            val extra = headersByOrigin[origin(spec.uri)] ?: emptyMap()
            if (extra.isEmpty()) spec else spec.withAdditionalHeaders(extra)
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSources))
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).build()
        val callback = object : MediaSession.Callback {
            override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                headersByOrigin.clear()
                mediaItems.forEach { item ->
                    val uri = item.localConfiguration?.uri ?: return@forEach
                    val bundle = item.mediaMetadata.extras?.getBundle("headers") ?: return@forEach
                    val headers = bundle.keySet().associateWith { bundle.getString(it) ?: "" }
                    headersByOrigin[origin(uri)] = headers
                }
                return Futures.immediateFuture(mediaItems)
            }
        }
        session = MediaSession.Builder(this, player).setCallback(callback).build()
    }

    private fun origin(uri: Uri): String = "${uri.scheme}://${uri.host}:${uri.port}"
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() { session?.run { player.release(); release() }; session = null; super.onDestroy() }
}
