package tv.prism.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

private class PlaybackSourceConfig(val headers: Map<String, String>, val cipher: Qmc2Cipher?, val clearKey: String?)

/** Each item owns its request headers and cipher; tracks on the same CDN never share keys. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class PrismMediaSourceFactory(private val context: Context) : MediaSource.Factory {
    private val supported = DefaultMediaSourceFactory(context).supportedTypes
    private var drm: DrmSessionManagerProvider? = null
    private var errors: LoadErrorHandlingPolicy? = null
    private val clearKeyManagers = mutableMapOf<String, DefaultDrmSessionManager>()

    private fun clearKeyManager(key: String) = clearKeyManagers.getOrPut(key) { clearKeySessionManager(key) }
    fun release() { clearKeyManagers.values.forEach { it.release() }; clearKeyManagers.clear() }

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = apply { drm = provider }
    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = apply { errors = policy }
    override fun getSupportedTypes(): IntArray = supported.clone()

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val config = mediaItem.localConfiguration?.tag as? PlaybackSourceConfig
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(config?.headers.orEmpty())
        val upstream = DefaultDataSource.Factory(context, http)
        val cipher = config?.cipher
        val dataSources = if (cipher == null) upstream else DataSource.Factory { QmcDataSource(upstream.createDataSource(), cipher) }
        val factory = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSources)
        val clearKey = config?.clearKey
        if (clearKey == null) drm?.let(factory::setDrmSessionManagerProvider)
        else factory.setDrmSessionManagerProvider { clearKeyManager(clearKey) }
        errors?.let(factory::setLoadErrorHandlingPolicy)
        // Neither headers nor keys are included in the timeline/notification metadata.
        return factory.createMediaSource(mediaItem.buildUpon().setTag(null).build())
    }
}

/** Playback is separate from the UI so a native decoder crash does not also kill the UI. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var mediaSourceFactory: PrismMediaSourceFactory

    override fun onCreate() {
        super.onCreate()
        mediaSourceFactory = PrismMediaSourceFactory(this)
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector { mime, secure, tunneling ->
                val codecs = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                if (MimeTypes.isAudio(mime)) codecs.sortedBy { !it.softwareOnly } else codecs
            }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(mediaSourceFactory)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).build()
        val callback = object : MediaSession.Callback {
            override fun onAddMediaItems(session: MediaSession, controller: ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                val configured = mediaItems.map { item ->
                    val extras = item.mediaMetadata.extras ?: return@map item
                    val bundle = extras.getBundle("headers")
                    require((bundle?.size() ?: 0) <= 64) { "音频请求头过多" }
                    val headers = bundle?.keySet().orEmpty().mapNotNull { key ->
                        val value = bundle?.get(key) as? String
                        if (value != null && value.length <= 8192 && key.matches(Regex("[!#\\$%&'*+.^_`|~0-9A-Za-z-]+")) && !value.contains('\r') && !value.contains('\n')) key to value else null
                    }.toMap()
                    val ekey = extras.getString(QMC_EKEY_EXTRA)
                    val config = PlaybackSourceConfig(headers, ekey?.let(Qmc2Cipher::fromEkey), extras.getString(CENC_KEY_EXTRA)?.let(::clearKeyValue))
                    val publicExtras = Bundle(extras).apply { remove("headers"); remove(QMC_EKEY_EXTRA); remove(CENC_KEY_EXTRA) }
                    item.buildUpon().setTag(config)
                        .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(publicExtras).build()).build()
                }
                return Futures.immediateFuture(configured)
            }
        }
        session = MediaSession.Builder(this, player).setCallback(callback).build()
        player.addAnalyticsListener(object : AnalyticsListener {
            private val window = Timeline.Window()

            private fun send(time: AnalyticsListener.EventTime, data: Bundle) {
                // Use the timeline that produced the event, before crossing the process boundary.
                if (time.windowIndex !in 0 until time.timeline.windowCount) return
                val id = time.timeline.getWindow(time.windowIndex, window).mediaItem.mediaId
                data.putString("mediaId", id)
                session?.let { session ->
                    session.connectedControllers.filter { it.uid == applicationInfo.uid }.forEach {
                        session.sendCustomCommand(it, playbackAttemptCommand, data)
                    }
                }
            }

            override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                send(eventTime, Bundle().apply {
                    putInt("state", state)
                    putLong("duration", player.duration)
                })
            }

            override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
                send(eventTime, Bundle().apply { putBoolean("active", isPlaying) })
            }

            override fun onPlayWhenReadyChanged(eventTime: AnalyticsListener.EventTime, playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) send(eventTime, Bundle().apply { putBoolean("pause", true) })
            }

            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                val causes = generateSequence(error as Throwable) { it.cause }.take(8).toList()
                send(eventTime, Bundle().apply {
                    putBundle("error", error.toBundle())
                    putString("httpCode", causes.firstNotNullOfOrNull {
                        (it as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode?.toString()
                            ?: Regex("Response code: (\\d{3})").find(it.message.orEmpty())?.groupValues?.get(1)
                    })
                    putString("protection", causes.filterIsInstance<QmcDecryptionException>().firstOrNull()?.message)
                })
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        if (::mediaSourceFactory.isInitialized) mediaSourceFactory.release()
        super.onDestroy()
    }
}
