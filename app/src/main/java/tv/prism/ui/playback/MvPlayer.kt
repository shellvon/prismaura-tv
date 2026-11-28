package tv.prism.ui.playback

import android.content.Context
import android.os.SystemClock
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.prism.lyrics.LyricLine
import tv.prism.lyrics.lineIndexAt
import tv.prism.playback.MvRemoteSession
import tv.prism.playback.MvRemoteState
import tv.prism.playback.VideoSource
import tv.prism.ui.widgets.*

private data class MvHudFeedback(val icon: String, val label: String, val token: Int)

private val mvSpeedOptions = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

private fun mvSpeedLabel(speed: Float): String = when (speed) {
    0.75f -> "0.75x"
    1.0f -> "1.0x"
    1.25f -> "1.25x"
    1.5f -> "1.5x"
    2.0f -> "2.0x"
    else -> "${speed}x"
}

/** MV has its own player and surface; the caller owns pausing/resuming the music session. */
@Composable
fun MvPlayer(
    source: VideoSource,
    title: String,
    artist: String,
    progressKey: String = "",
    lyrics: List<LyricLine> = emptyList(),
    subLyrics: Boolean = true,
    lyricOffsetMs: Long = 0L,
    onPrevMv: (() -> Unit)? = null,
    onNextMv: (() -> Unit)? = null,
    onRemoteSession: (MvRemoteSession?) -> Unit = {},
    onClose: () -> Unit,
) {
    val theme = LocalPrismColors.current
    // Video always has a dark canvas, including when the rest of the app uses the light theme.
    CompositionLocalProvider(LocalPrismColors provides if (theme.panel.luminance() > .5f) prismColors("gold") else theme) {
        key(source, progressKey) {
            MvPlayback(
                source = source,
                title = title,
                artist = artist,
                progressKey = progressKey,
                lyrics = lyrics,
                subLyrics = subLyrics,
                lyricOffsetMs = lyricOffsetMs,
                onPrevMv = onPrevMv,
                onNextMv = onNextMv,
                onRemoteSession = onRemoteSession,
                onClose = onClose,
            )
        }
    }
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun MvPlayback(
    source: VideoSource,
    title: String,
    artist: String,
    progressKey: String,
    lyrics: List<LyricLine>,
    subLyrics: Boolean,
    lyricOffsetMs: Long,
    onPrevMv: (() -> Unit)?,
    onNextMv: (() -> Unit)?,
    onRemoteSession: (MvRemoteSession?) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    val scope = rememberCoroutineScope()
    val close by rememberUpdatedState(onClose)
    val prefs = remember(context) { context.getSharedPreferences("mv_progress", Context.MODE_PRIVATE) }
    val savedStart = remember { if (progressKey.isBlank()) 0L else prefs.getLong(progressKey, 0L).coerceAtLeast(0L) }
    val player = remember {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(source.headers)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(DefaultDataSource.Factory(context, http)))
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                // Always prefer the highest available video quality automatically.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setForceHighestSupportedBitrate(true)
                    .setMaxVideoBitrate(Int.MAX_VALUE)
                    .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                    .setViewportSize(Int.MAX_VALUE, Int.MAX_VALUE, false)
                    .build()
            }
    }
    val rootFocus = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val prevMvFocus = remember { FocusRequester() }
    val rewindFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val forwardFocus = remember { FocusRequester() }
    val nextMvFocus = remember { FocusRequester() }
    val restartFocus = remember { FocusRequester() }
    val moreFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    var lastControl by remember { mutableStateOf(railFocus) }
    var railFocused by remember { mutableStateOf(false) }
    var focusedHint by remember { mutableStateOf("") }
    var playIntent by remember { mutableStateOf(true) }
    var advancing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var videoSize by remember { mutableStateOf<VideoSize?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffering by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var seekable by remember { mutableStateOf(false) }
    var firstFrame by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var closing by remember { mutableStateOf(false) }
    var loopOne by remember { mutableStateOf(false) }
    var speedIndex by remember { mutableIntStateOf(1) }
    var fillScreen by remember { mutableStateOf(prefs.getBoolean("pref_fill_screen", false)) }
    // Lyrics are off by default because MusicFree lacks MV subtitle timings and MV duration often differs from audio.
    var showLyrics by remember { mutableStateOf(false) }
    var wakeKey by remember { mutableStateOf<Key?>(null) }
    var seekKey by remember { mutableStateOf<Key?>(null) }
    var repeatingSeek by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Long?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var seekNotice by remember { mutableStateOf(false) }
    var hudToken by remember { mutableIntStateOf(0) }
    var hud by remember { mutableStateOf<MvHudFeedback?>(null) }
    val scrim = remember { Animatable(1f) }
    val clock = rememberPlaybackPosition(progressKey, { position }, advancing, { player.currentPosition.coerceAtLeast(0L) })
    val displayPosition = { preview ?: clock.value }
    val time by remember { derivedStateOf { mvTime(displayPosition()) } }
    val canSeek = seekable && duration > 0 && error.isBlank() && !closing
    val speed = mvSpeedOptions[speedIndex]
    val activeLyricIndex by remember(lyrics, lyricOffsetMs, clock) {
        derivedStateOf(structuralEqualityPolicy()) {
            if (lyrics.isEmpty()) -1 else lyrics.lineIndexAt(clock.value - lyricOffsetMs)
        }
    }
    fun showHud(icon: String, label: String) {
        hudToken++
        hud = MvHudFeedback(icon, label, hudToken)
    }
    fun clearSavedProgress() {
        if (progressKey.isNotBlank()) prefs.edit().remove(progressKey).apply()
    }
    fun saveProgress() {
        if (progressKey.isBlank()) return
        val at = player.currentPosition.coerceAtLeast(0L)
        val total = player.duration.coerceAtLeast(0L)
        if (firstFrame && !ended && at > 2_000 && (total <= 0 || at < total - 4_000)) {
            prefs.edit().putLong(progressKey, at).apply()
        } else {
            prefs.edit().remove(progressKey).apply()
        }
    }
    fun leave() {
        if (closing) return
        closing = true
        preview = null
        seekKey = null
        repeatingSeek = false
        saveProgress()
        player.pause()
        scope.launch {
            scrim.animateTo(1f, tween(if (motion) 220 else 0, easing = FastOutSlowInEasing))
            close()
        }
    }
    fun togglePlayback(showOverlay: Boolean = true) {
        interaction++
        when {
            ended -> {
                player.seekTo(0)
                player.play()
                if (showOverlay) showHud("play", "重新播放")
            }
            player.playWhenReady -> {
                player.pause()
                if (showOverlay) showHud("pause", "已暂停")
            }
            else -> {
                player.play()
                if (showOverlay) showHud("play", "继续播放")
            }
        }
    }
    fun seekTo(at: Long, feedbackIcon: String? = null, feedbackLabel: String? = null) {
        if (!canSeek) {
            seekNotice = true
            return
        }
        val target = at.coerceIn(0L, duration)
        player.seekTo(target)
        position = target
        interaction++
        if (feedbackIcon != null && feedbackLabel != null) showHud(feedbackIcon, feedbackLabel)
    }
    fun stepBy(deltaMs: Long) {
        if (!canSeek) {
            seekNotice = true
            return
        }
        val target = (player.currentPosition.coerceAtLeast(0L) + deltaMs).coerceIn(0L, duration)
        seekTo(
            target,
            feedbackIcon = if (deltaMs >= 0) "forward" else "rewind",
            feedbackLabel = if (deltaMs >= 0) "快进 ${deltaMs / 1000} 秒" else "快退 ${-deltaMs / 1000} 秒",
        )
    }
    fun restartPlayback() {
        interaction++
        ended = false
        clearSavedProgress()
        player.seekTo(0L)
        position = 0L
        player.play()
        showHud("undo", "从头播放")
    }
    fun toggleFill(target: Boolean = !fillScreen) {
        interaction++
        fillScreen = target
        prefs.edit().putBoolean("pref_fill_screen", fillScreen).apply()
        showHud("aspect", if (fillScreen) "画面铺满" else "适应画面")
    }
    fun stepSpeed(dir: Int) {
        interaction++
        val nextIndex = if (dir > 0) (speedIndex + 1) % mvSpeedOptions.size else (speedIndex - 1).coerceAtLeast(0)
        speedIndex = nextIndex
        val next = mvSpeedOptions[speedIndex]
        player.setPlaybackSpeed(next)
        showHud("play", "${mvSpeedLabel(next)} 倍速")
    }
    fun toggleLoop(target: Boolean = !loopOne) {
        interaction++
        loopOne = target
        player.repeatMode = if (loopOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        showHud("repeatOne", if (loopOne) "单曲循环已开启" else "单曲循环已关闭")
    }
    fun toggleLyrics(target: Boolean = !showLyrics) {
        interaction++
        showLyrics = target
        showHud("lyric", if (showLyrics) "已开启参考歌词" else "已关闭参考歌词")
    }
    val qualityBadge = videoSize?.height?.takeIf { it > 0 }?.let { h ->
        when {
            h >= 2160 -> "4K"
            h >= 1440 -> "2K"
            else -> "${h}p"
        }
    }
    fun previewAt(x: Float, width: Int) {
        if (width > 0 && canSeek) preview = (duration * (x / width).coerceIn(0f, 1f)).toLong()
    }
    fun retryPlayback() {
        error = ""
        buffering = true
        ended = false
        interaction++
        val startAt = if (firstFrame) position.coerceAtLeast(0L) else 0L
        if (!firstFrame) clearSavedProgress()
        // Prepare from 0 first when retrying before first frame to avoid range-seek failures.
        player.setMediaItem(MediaItem.fromUri(source.url))
        player.prepare()
        if (startAt > 0L) player.seekTo(startAt)
        player.play()
    }
    val remoteRegister by rememberUpdatedState(onRemoteSession)
    DisposableEffect(player, onPrevMv, onNextMv) {
        val session = MvRemoteSession(
            read = {
                MvRemoteState(
                    playing = playIntent && !ended,
                    advancing = advancing && !ended,
                    position = player.currentPosition.coerceAtLeast(0L),
                    duration = duration,
                    seekable = canSeek,
                    buffering = buffering,
                    ended = ended,
                    quality = qualityBadge.orEmpty(),
                    loopOne = loopOne,
                    speed = speed,
                    fillScreen = fillScreen,
                    canPrev = onPrevMv != null,
                    canNext = onNextMv != null,
                )
            },
            togglePlay = { if (error.isNotBlank()) retryPlayback() else togglePlayback() },
            seekTo = { target -> seekTo(target) },
            stepBy = { delta -> stepBy(delta) },
            prevOrRewind = { if (onPrevMv != null) { saveProgress(); onPrevMv.invoke() } else stepBy(-10_000) },
            nextOrForward = { if (onNextMv != null) { saveProgress(); onNextMv.invoke() } else stepBy(10_000) },
            restart = ::restartPlayback,
            toggleLoop = { toggleLoop() },
            cycleSpeed = { stepSpeed(1) },
            toggleFill = { toggleFill() },
        )
        remoteRegister(session)
        onDispose { remoteRegister(null) }
    }
    BackHandler(onBack = ::leave)
    DisposableEffect(player, lifecycle) {
        var resumeHandled = false
        var fallbackFromResumeUsed = false
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playIntent = playWhenReady }
            override fun onIsPlayingChanged(isPlaying: Boolean) { advancing = isPlaying }
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
            override fun onPlayerError(playbackError: PlaybackException) {
                // If seeking to a saved resume position failed before the first frame rendered,
                // clear the stale position and seamlessly recover from the beginning.
                if (resumeHandled && savedStart > 0L && !firstFrame && !fallbackFromResumeUsed) {
                    fallbackFromResumeUsed = true
                    clearSavedProgress()
                    position = 0L
                    buffering = true
                    player.setMediaItem(MediaItem.fromUri(source.url))
                    player.prepare()
                    player.play()
                    return
                }
                error = "MV 播放失败：${playbackError.message ?: "音源暂不可用"}"
                buffering = false
            }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                seekable = player.isCurrentMediaItemSeekable && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                duration = player.duration.coerceAtLeast(0L)
                // Always prepare from 0 first so range-restricted streams and TV decoders initialize
                // cleanly, then apply the saved resume position once READY and seekable.
                if (state == Player.STATE_READY && !resumeHandled) {
                    resumeHandled = true
                    if (savedStart > 2_000L) {
                        if (seekable && duration > 0L && savedStart < duration - 4_000L) {
                            player.seekTo(savedStart)
                            position = savedStart
                            showHud("play", "已续播至 ${mvTime(savedStart)}")
                        } else {
                            clearSavedProgress()
                        }
                    }
                }
                ended = state == Player.STATE_ENDED
                if (ended) clearSavedProgress()
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { saveProgress(); player.pause() }
        }
        player.addListener(listener)
        lifecycle.addObserver(observer)
        player.setMediaItem(MediaItem.fromUri(source.url))
        player.prepare()
        player.play()
        onDispose {
            if (!closing) saveProgress()
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(firstFrame) {
        if (firstFrame) scrim.animateTo(0f, tween(if (motion) 380 else 0, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(hud?.token) {
        val current = hud ?: return@LaunchedEffect
        delay(1_100)
        if (hud?.token == current.token) hud = null
    }
    LaunchedEffect(player) {
        var stalledAt = SystemClock.elapsedRealtime()
        while (isActive) {
            position = player.currentPosition.coerceAtLeast(0L)
            buffered = player.bufferedPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            seekable = player.isCurrentMediaItemSeekable && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            if ((!buffering && firstFrame) || !playIntent) stalledAt = SystemClock.elapsedRealtime()
            else if (error.isBlank() && SystemClock.elapsedRealtime() - stalledAt > 18_000) {
                error = "MV 画面暂时无法加载，请检查连接后重试。"
                buffering = false
                player.pause()
            }
            delay(200)
        }
    }
    LaunchedEffect(seekNotice) { if (seekNotice) { delay(2_000); seekNotice = false } }
    LaunchedEffect(seekKey) {
        val key = seekKey ?: return@LaunchedEffect
        delay(android.view.ViewConfiguration.getLongPressTimeout().toLong())
        while (isActive && seekKey == key) {
            repeatingSeek = true
            preview = ((preview ?: player.currentPosition) + if (key == Key.DirectionRight) 10_000 else -10_000).coerceIn(0L, duration)
            interaction++
            delay(180)
        }
    }
    val timeout = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        6_000L, containsIcons = true, containsText = true, containsControls = true,
    ) ?: 6_000L
    LaunchedEffect(interaction, advancing, buffering, ended, error, seekKey, dragging, menuOpen) {
        chrome = true
        if (advancing && !buffering && !ended && error.isBlank() && seekKey == null && !dragging && !menuOpen) {
            delay(timeout)
            chrome = false
        }
    }
    LaunchedEffect(ended) {
        if (ended) lastControl = playFocus
    }
    LaunchedEffect(chrome, error, closing, menuOpen) {
        if (closing || menuOpen) return@LaunchedEffect
        withFrameNanos { }
        val target = when {
            error.isNotBlank() -> retryFocus
            chrome -> lastControl
            else -> rootFocus
        }
        if (!target.requestAfterScroll { } && chrome && error.isBlank()) railFocus.requestAfterScroll { }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFocus).focusable()
            .onPreviewKeyEvent { event ->
                if (closing) return@onPreviewKeyEvent true
                if (event.key == Key.Back || event.key == Key.Escape) {
                    if (event.type == KeyEventType.KeyUp && !event.nativeKeyEvent.isCanceled) leave()
                    return@onPreviewKeyEvent true
                }
                if (event.key == wakeKey) {
                    if (event.type == KeyEventType.KeyUp) wakeKey = null
                    return@onPreviewKeyEvent true
                }
                val navigation = event.key in setOf(
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
                )
                if (!chrome && error.isBlank() && navigation) {
                    if (event.type == KeyEventType.KeyDown) {
                        wakeKey = event.key
                        interaction++
                        chrome = true
                    }
                    return@onPreviewKeyEvent true
                }
                if (error.isBlank() && railFocused) {
                    if (event.key in setOf(Key.DirectionLeft, Key.DirectionRight)) {
                        if (!canSeek) {
                            if (event.type == KeyEventType.KeyDown) seekNotice = true
                            return@onPreviewKeyEvent true
                        }
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                            seekKey = event.key
                            repeatingSeek = false
                            preview = (player.currentPosition + if (event.key == Key.DirectionRight) 10_000 else -10_000).coerceIn(0L, duration)
                            interaction++
                        } else if (event.type == KeyEventType.KeyUp && seekKey == event.key) {
                            val wasRepeating = repeatingSeek
                            if (!event.nativeKeyEvent.isCanceled) {
                                preview?.let { target ->
                                    val dirRight = event.key == Key.DirectionRight
                                    seekTo(
                                        target,
                                        feedbackIcon = if (dirRight) "forward" else "rewind",
                                        feedbackLabel = if (wasRepeating) mvTime(target) else if (dirRight) "快进 10 秒" else "快退 10 秒",
                                    )
                                }
                            }
                            preview = null
                            seekKey = null
                            repeatingSeek = false
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (event.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                        if (event.type == KeyEventType.KeyUp && !event.nativeKeyEvent.isCanceled) togglePlayback()
                        return@onPreviewKeyEvent true
                    }
                }
                val media = event.nativeKeyEvent.keyCode
                if (media in setOf(
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                        android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_STOP,
                        android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, android.view.KeyEvent.KEYCODE_MEDIA_REWIND,
                        android.view.KeyEvent.KEYCODE_MEDIA_NEXT, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    )
                ) {
                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                        interaction++
                        when (media) {
                            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (error.isBlank()) togglePlayback() else retryPlayback()
                            android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> if (error.isNotBlank()) retryPlayback() else { if (ended) player.seekTo(0); player.play(); showHud("play", "继续播放") }
                            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_STOP -> { player.pause(); showHud("pause", "已暂停") }
                            android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> stepBy(10_000)
                            android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> stepBy(-10_000)
                            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> onNextMv?.invoke()
                            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> onPrevMv?.invoke()
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                false
            }
            .pointerInput(Unit) { detectTapGestures { if (error.isBlank() && !closing) { interaction++; chrome = !chrome } } },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
            val ratio = videoSize?.takeIf { it.height > 0 }?.let { it.width * it.pixelWidthHeightRatio / it.height } ?: (16f / 9f)
            val containerRatio = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 16f / 9f
            val useWidth = if (fillScreen) ratio < containerRatio else ratio >= containerRatio
            val video = if (useWidth) Modifier.fillMaxWidth().height(maxWidth / ratio)
            else Modifier.fillMaxHeight().width(maxHeight * ratio)
            key(player) {
                AndroidView(
                    factory = { SurfaceView(it).also(player::setVideoSurfaceView) },
                    update = { it.keepScreenOn = playIntent && !ended && error.isBlank() && !closing },
                    onRelease = { player.clearVideoSurfaceView(it) },
                    modifier = video,
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim.value)))

        // Optional cinema lyric subtitle overlay
        val activeLine = if (showLyrics && error.isBlank() && activeLyricIndex in lyrics.indices) lyrics[activeLyricIndex] else null
        val lyricBottomInset by animateDpAsState(
            if (chrome && error.isBlank()) 198.dp else 44.dp,
            tween(if (motion) 220 else 0, easing = FastOutSlowInEasing),
            label = "mv lyric offset",
        )
        AnimatedVisibility(
            visible = activeLine != null && firstFrame,
            enter = fadeIn(tween(if (motion) 180 else 0)),
            exit = fadeOut(tween(if (motion) 180 else 0)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 64.dp).padding(bottom = lyricBottomInset),
        ) {
            activeLine?.let { line ->
                Column(
                    Modifier.clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = .54f))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        line.text,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .85f), Offset(0f, 2f), 6f)),
                    )
                    val sub = if (subLyrics) line.supportingLines.firstOrNull() else null
                    if (!sub.isNullOrBlank()) {
                        Text(
                            sub,
                            color = Color.White.copy(alpha = .78f),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .85f), Offset(0f, 1f), 4f)),
                        )
                    }
                }
            }
        }

        // Buffering / initial loading overlay
        if (buffering && error.isBlank()) {
            val spin = if (motion) rememberInfiniteTransition(label = "mv buffering").animateFloat(
                0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "arc",
            ) else null
            Column(
                Modifier.align(Alignment.Center)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = if (firstFrame) .58f else .35f))
                    .border(1.dp, c.foreground.copy(alpha = .10f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 26.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Canvas(Modifier.size(34.dp)) {
                    drawCircle(c.foreground.copy(alpha = .14f), style = Stroke(2.dp.toPx()))
                    drawArc(c.accent, spin?.value ?: 0f, 96f, false, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
                }
                Text(
                    if (!firstFrame) "正在加载 MV…" else "正在缓冲…",
                    color = c.foreground.copy(alpha = .88f),
                    fontSize = 13.sp,
                    letterSpacing = .6.sp,
                )
            }
        }

        // Transient center HUD for play/pause/seek feedback
        AnimatedVisibility(
            visible = hud != null && !buffering && error.isBlank(),
            enter = fadeIn(tween(if (motion) 140 else 0)) + scaleIn(tween(if (motion) 160 else 0), initialScale = .92f),
            exit = fadeOut(tween(if (motion) 220 else 0)) + scaleOut(tween(if (motion) 200 else 0), targetScale = .96f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            hud?.let { feedback ->
                Row(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = .72f))
                        .border(1.dp, c.accent.copy(alpha = .28f), RoundedCornerShape(50))
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Glyph(feedback.icon, Modifier.size(20.dp), c.accent)
                    Text(feedback.label, color = c.foreground, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // Error dialog card
        if (error.isNotBlank()) {
            Column(
                Modifier.align(Alignment.Center)
                    .widthIn(min = 420.dp, max = 560.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(c.panel.copy(alpha = .96f))
                    .border(1.dp, c.line, RoundedCornerShape(22.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = .12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Glyph("mv", Modifier.size(22.dp), c.accent)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("MV 暂不可用", color = c.foreground, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        if (title.isNotBlank()) {
                            Text(title, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Text(error, color = c.muted, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrismAction("重试", ::retryPlayback, Modifier.heightIn(min = 46.dp).focusRequester(retryFocus), primary = true, icon = "refresh")
                    PrismAction("返回音乐", ::leave, Modifier.heightIn(min = 46.dp), icon = "back")
                }
            }
        }

        // Top cinema header bar
        AnimatedVisibility(
            chrome && error.isBlank(),
            enter = fadeIn(tween(if (motion) 180 else 0)),
            exit = fadeOut(tween(if (motion) 180 else 0)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(
                Modifier.fillMaxWidth().height(124.dp)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .82f), Color.Black.copy(alpha = .32f), Color.Transparent)))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = PrismPagePadding, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AuraBrand(compact = true)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "MV",
                        color = c.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .background(c.accent.copy(alpha = .12f))
                            .border(1.dp, c.accent.copy(alpha = .32f), RoundedCornerShape(50))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (loopOne) MvHeaderPill("单曲循环", c.accent)
                        if (speed != 1.0f) MvHeaderPill(mvSpeedLabel(speed), c.accent)
                        if (fillScreen) MvHeaderPill("铺满", c.foreground.copy(alpha = .85f))
                        val statusLabel = when {
                            ended -> "播放结束"
                            buffering -> "缓冲中"
                            playIntent -> "正在播放"
                            else -> "已暂停"
                        }
                        val statusSummary = listOfNotNull(qualityBadge, statusLabel).joinToString(" · ")
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = .09f))
                                .border(1.dp, Color.White.copy(alpha = .15f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Box(
                                Modifier.size(6.dp).clip(CircleShape)
                                    .background(if (advancing && !ended) c.accent else c.muted.copy(alpha = .6f))
                            )
                            Text(
                                statusSummary,
                                color = c.foreground.copy(alpha = .9f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Box(Modifier.width(1.dp).height(10.dp).background(Color.White.copy(alpha = .16f)))
                        AuraClock()
                    }
                }
            }
        }

        // Bottom cinema control deck
        AnimatedVisibility(
            chrome && error.isBlank(),
            enter = fadeIn(tween(if (motion) 180 else 0)),
            exit = fadeOut(tween(if (motion) 180 else 0)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            .22f to Color.Black.copy(alpha = .68f),
                            1f to Color.Black.copy(alpha = .94f),
                        )
                    )
                    .padding(start = PrismPagePadding, end = PrismPagePadding, top = 52.dp, bottom = 20.dp),
            ) {
                // Song identity row + floating preview timestamp when scrubbing
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            title.ifBlank { "MV" },
                            color = c.foreground,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (artist.isNotBlank()) {
                            Text(
                                artist,
                                color = c.muted,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    val scrubbing = preview != null
                    if (scrubbing && duration > 0L) {
                        val target = preview ?: 0L
                        val deltaSec = ((target - position) / 1000L)
                        val deltaText = when {
                            deltaSec > 0 -> "+${deltaSec}s"
                            deltaSec < 0 -> "${deltaSec}s"
                            else -> "0s"
                        }
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(c.accent.copy(alpha = .16f))
                                .border(1.dp, c.accent.copy(alpha = .4f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(mvTime(target), color = c.foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(deltaText, color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                // Interactive progress rail (matching PrismPlayerUi)
                Box(
                    Modifier.fillMaxWidth().height(28.dp).focusRequester(railFocus)
                        .focusProperties { canFocus = chrome && !closing; down = playFocus }
                        .onFocusChanged {
                            railFocused = it.isFocused
                            if (it.isFocused) {
                                lastControl = railFocus
                                focusedHint = ""
                            }
                        }
                        .pointerInput(duration, canSeek) {
                            detectTapGestures { if (canSeek) seekTo((duration * (it.x / size.width).coerceIn(0f, 1f)).toLong()) }
                        }
                        .pointerInput(duration, canSeek) {
                            detectDragGestures(
                                onDragStart = { if (canSeek) { dragging = true; previewAt(it.x, size.width) } },
                                onDragEnd = { if (dragging) preview?.let { seekTo(it) }; dragging = false; preview = null },
                                onDragCancel = { dragging = false; preview = null },
                                onDrag = { change, _ -> if (dragging) { change.consume(); previewAt(change.position.x, size.width) } },
                            )
                        }
                        .semantics {
                            contentDescription = "MV 播放进度"
                            progressBarRangeInfo = ProgressBarRangeInfo(if (duration > 0) (displayPosition().toFloat() / duration).coerceIn(0f, 1f) else 0f, 0f..1f)
                            onClick(label = "播放或暂停") { togglePlayback(); true }
                            setProgress { value -> if (canSeek) { seekTo((duration * value.coerceIn(0f, 1f)).toLong()); true } else false }
                        }
                        .focusable(),
                ) {
                    ProgressRail(
                        progress = { if (duration > 0) displayPosition().toFloat() / duration else 0f },
                        focused = railFocused,
                        playing = advancing && chrome,
                        modifier = Modifier.fillMaxWidth(),
                        buffered = { if (duration > 0) buffered.toFloat() / duration else 0f },
                    )
                }

                // Timestamps + contextual center hint
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(time, color = c.foreground.copy(alpha = .85f), fontSize = 12.sp)
                    Text(
                        when {
                            seekNotice -> "当前 MV 暂不支持跳转"
                            repeatingSeek || dragging -> "松开即可跳转"
                            ended -> "播放结束 · 按确定重新播放"
                            railFocused -> if (canSeek) "← → 快进快退 · 长按连续拖动 · 确定播放／暂停" else "确定播放／暂停 · ↓ 更多控制"
                            focusedHint.isNotBlank() -> focusedHint
                            else -> "↑ 返回进度条 · 返回键回音乐"
                        },
                        color = if (seekNotice || repeatingSeek || dragging) c.accent else c.accent.copy(alpha = .72f),
                        fontSize = 11.sp,
                        letterSpacing = .8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    )
                    Text(mvTime(duration), color = c.muted, fontSize = 12.sp)
                }

                // Symmetrical circular transport row matching PrismPlayerUi
                val orderedControls = buildList {
                    add(backFocus)
                    if (onPrevMv != null) add(prevMvFocus)
                    if (canSeek) add(rewindFocus)
                    add(playFocus)
                    if (canSeek) add(forwardFocus)
                    if (onNextMv != null) add(nextMvFocus)
                    add(restartFocus)
                    add(moreFocus)
                }
                fun rowFocusProps(target: FocusRequester): Modifier {
                    val idx = orderedControls.indexOf(target)
                    return Modifier.focusProperties {
                        up = railFocus
                        if (idx > 0) left = orderedControls[idx - 1]
                        if (idx in 0 until orderedControls.lastIndex) right = orderedControls[idx + 1]
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        MvCircleButton(
                            icon = "back",
                            label = "返回音乐",
                            onClick = ::leave,
                            enabled = chrome && !closing && !menuOpen,
                            modifier = Modifier.focusRequester(backFocus)
                                .then(rowFocusProps(backFocus))
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        lastControl = backFocus
                                        focusedHint = "返回音乐播放页"
                                    }
                                },
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onPrevMv != null) {
                            MvCircleButton(
                                icon = "prev",
                                label = "上一个 MV",
                                onClick = { interaction++; saveProgress(); onPrevMv.invoke() },
                                enabled = chrome && !closing && !menuOpen,
                                modifier = Modifier.focusRequester(prevMvFocus)
                                    .then(rowFocusProps(prevMvFocus))
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            lastControl = prevMvFocus
                                            focusedHint = "上一个 MV"
                                        }
                                    },
                            )
                        }
                        MvCircleButton(
                            icon = "rewind",
                            label = "快退 10 秒",
                            onClick = { stepBy(-10_000) },
                            enabled = chrome && !closing && !menuOpen && canSeek,
                            modifier = Modifier.focusRequester(rewindFocus)
                                .then(rowFocusProps(rewindFocus))
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        lastControl = rewindFocus
                                        focusedHint = "快退 10 秒"
                                    }
                                },
                        )
                        MvPrimaryPlayButton(
                            playing = playIntent && !ended,
                            onClick = { togglePlayback() },
                            enabled = chrome && !closing && !menuOpen,
                            modifier = Modifier.focusRequester(playFocus)
                                .then(rowFocusProps(playFocus))
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        lastControl = playFocus
                                        focusedHint = if (ended) "重新播放" else if (playIntent) "暂停" else "播放"
                                    }
                                },
                        )
                        MvCircleButton(
                            icon = "forward",
                            label = "快进 10 秒",
                            onClick = { stepBy(10_000) },
                            enabled = chrome && !closing && !menuOpen && canSeek,
                            modifier = Modifier.focusRequester(forwardFocus)
                                .then(rowFocusProps(forwardFocus))
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        lastControl = forwardFocus
                                        focusedHint = "快进 10 秒"
                                    }
                                },
                        )
                        if (onNextMv != null) {
                            MvCircleButton(
                                icon = "next",
                                label = "下一个 MV",
                                onClick = { interaction++; saveProgress(); onNextMv.invoke() },
                                enabled = chrome && !closing && !menuOpen,
                                modifier = Modifier.focusRequester(nextMvFocus)
                                    .then(rowFocusProps(nextMvFocus))
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            lastControl = nextMvFocus
                                            focusedHint = "下一个 MV"
                                        }
                                    },
                            )
                        }
                    }
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MvCircleButton(
                            icon = "undo",
                            label = "从头播放",
                            onClick = ::restartPlayback,
                            enabled = chrome && !closing && !menuOpen,
                            modifier = Modifier.focusRequester(restartFocus)
                                .then(rowFocusProps(restartFocus))
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        lastControl = restartFocus
                                        focusedHint = "从头播放"
                                    }
                                },
                        )
                        MvMoreDots(
                            onClick = {
                                interaction++
                                lastControl = moreFocus
                                menuOpen = true
                            },
                            enabled = chrome && !closing && !menuOpen,
                            modifier = Modifier.focusRequester(moreFocus)
                                .then(rowFocusProps(moreFocus))
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        lastControl = moreFocus
                                        focusedHint = "更多 · 倍速、画面比例与循环"
                                    }
                                },
                        )
                    }
                }
            }
        }
        if (menuOpen) {
            MvMenu(
                title = title.ifBlank { "MV" },
                artist = artist,
                quality = qualityBadge,
                speedIndex = speedIndex,
                fillScreen = fillScreen,
                loopOne = loopOne,
                hasLyrics = lyrics.isNotEmpty(),
                showLyrics = showLyrics,
                onStepSpeed = ::stepSpeed,
                onToggleFill = { toggleFill(it) },
                onToggleLoop = { toggleLoop(it) },
                onToggleLyrics = { toggleLyrics(it) },
                onClose = { menuOpen = false; interaction++ },
            )
        }
    }
}

private enum class MvMenuRowKind { Step, Toggle }

private data class MvMenuRow(
    val id: String,
    val label: String,
    val value: String,
    val icon: String,
    val kind: MvMenuRowKind,
    val on: Boolean = false,
    val canDown: Boolean = true,
    val canUp: Boolean = true,
    val note: String = "",
)

@Composable
private fun MvMenu(
    title: String,
    artist: String,
    quality: String?,
    speedIndex: Int,
    fillScreen: Boolean,
    loopOne: Boolean,
    hasLyrics: Boolean,
    showLyrics: Boolean,
    onStepSpeed: (Int) -> Unit,
    onToggleFill: (Boolean) -> Unit,
    onToggleLoop: (Boolean) -> Unit,
    onToggleLyrics: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    var row by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; runCatching { focus.requestFocus() } }
    val enter = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (motion) enter.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }
    var closing by remember { mutableStateOf(false) }
    fun dismiss() { if (!closing) closing = true }
    LaunchedEffect(closing) {
        if (closing) {
            enter.animateTo(0f, tween(if (motion) 160 else 0))
            onClose()
        }
    }
    val speed = mvSpeedOptions[speedIndex]
    val rows = buildList {
        add(
            MvMenuRow(
                id = "speed",
                label = "播放倍速",
                value = mvSpeedLabel(speed),
                icon = "play",
                kind = MvMenuRowKind.Step,
                canDown = speedIndex > 0,
                canUp = speedIndex < mvSpeedOptions.lastIndex,
                note = "← → 调整",
            )
        )
        add(
            MvMenuRow(
                id = "fill",
                label = "画面比例",
                value = if (fillScreen) "铺满屏幕" else "适应屏幕",
                icon = "aspect",
                kind = MvMenuRowKind.Step,
                note = if (fillScreen) "裁切黑边铺满电视" else "保留完整视频画面",
            )
        )
        add(
            MvMenuRow(
                id = "loop",
                label = "单曲循环",
                value = "",
                icon = "repeatOne",
                kind = MvMenuRowKind.Toggle,
                on = loopOne,
            )
        )
        if (hasLyrics) {
            add(
                MvMenuRow(
                    id = "lyrics",
                    label = "参考歌词",
                    value = "",
                    icon = "lyric",
                    kind = MvMenuRowKind.Toggle,
                    on = showLyrics,
                    note = "沿用歌曲歌词，时间可能不同步",
                )
            )
        }
    }
    fun currentIndex(): Int = row.coerceIn(0, rows.lastIndex)
    val index = currentIndex()
    fun activate(at: Int) {
        when (rows[at].id) {
            "speed" -> onStepSpeed(1)
            "fill" -> onToggleFill(!fillScreen)
            "loop" -> onToggleLoop(!loopOne)
            "lyrics" -> onToggleLyrics(!showLyrics)
        }
    }
    fun adjust(dir: Int) {
        when (rows[currentIndex()].id) {
            "speed" -> if ((dir > 0 && speedIndex < mvSpeedOptions.lastIndex) || (dir < 0 && speedIndex > 0)) onStepSpeed(dir)
            "fill" -> onToggleFill(dir > 0)
            "loop" -> onToggleLoop(dir > 0)
            "lyrics" -> onToggleLyrics(dir > 0)
        }
    }
    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val original = window?.attributes?.let { WindowManager.LayoutParams().apply { copyFrom(it) } }
            window?.setDimAmount(.14f)
            onDispose { if (original != null) window?.attributes = original }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = enter.value }
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .22f))))
                    .pointerInput(Unit) { detectTapGestures { dismiss() } },
            )
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 56.dp).width(448.dp).heightIn(max = maxHeight - 88.dp)
                    .graphicsLayer {
                        alpha = enter.value
                        translationY = 22.dp.toPx() * (1f - enter.value)
                    }
                    .clip(RoundedCornerShape(12.dp)).background(c.panel.copy(alpha = .97f))
                    .border(1.dp, c.foreground.copy(alpha = .12f), RoundedCornerShape(12.dp))
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        if (closing) return@onPreviewKeyEvent true
                        if (event.key == Key.Back || event.key == Key.Escape) {
                            if (event.type == KeyEventType.KeyUp && !event.nativeKeyEvent.isCanceled) dismiss()
                            return@onPreviewKeyEvent true
                        }
                        when (event.key) {
                            Key.DirectionUp -> {
                                if (event.type == KeyEventType.KeyDown) row = (currentIndex() - 1).coerceAtLeast(0)
                                true
                            }
                            Key.DirectionDown -> {
                                if (event.type == KeyEventType.KeyDown) row = (currentIndex() + 1).coerceAtMost(rows.lastIndex)
                                true
                            }
                            Key.DirectionLeft -> {
                                if (event.type == KeyEventType.KeyDown) adjust(-1)
                                true
                            }
                            Key.DirectionRight -> {
                                if (event.type == KeyEventType.KeyDown) adjust(1)
                                true
                            }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                if (event.type == KeyEventType.KeyDown) activate(currentIndex())
                                true
                            }
                            else -> false
                        }
                    }
                    .focusable().padding(6.dp),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(title, color = c.foreground, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(
                        artist.takeIf { it.isNotBlank() && it != "未知歌手" },
                        quality,
                        mvSpeedLabel(speed),
                        if (fillScreen) "铺满屏幕" else "适应屏幕",
                    ).joinToString(" · ")
                    Spacer(Modifier.height(4.dp))
                    Text(meta, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.foreground.copy(alpha = .09f)))
                    Spacer(Modifier.height(4.dp))
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
                    rows.forEachIndexed { i, entry ->
                        val on = i == index
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp)
                                .semantics(mergeDescendants = true) {
                                    role = if (entry.kind == MvMenuRowKind.Toggle) androidx.compose.ui.semantics.Role.Switch else androidx.compose.ui.semantics.Role.Button
                                    stateDescription = if (entry.kind == MvMenuRowKind.Toggle) {
                                        if (entry.on) "已开启" else "已关闭"
                                    } else listOf(entry.value, entry.note).filter { it.isNotBlank() }.joinToString("，")
                                    onClick(label = entry.label) { row = i; activate(i); true }
                                }
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (on) c.accent.copy(alpha = .11f) else Color.Transparent)
                                .then(if (on) Modifier.border(2.dp, c.accent.copy(alpha = .5f), RoundedCornerShape(8.dp)) else Modifier)
                                .pointerInput(i) { detectTapGestures { row = i; activate(i) } }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))
                                    .background(if (on) c.accent.copy(alpha = .14f) else c.foreground.copy(alpha = .05f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Glyph(entry.icon, Modifier.size(19.dp), if (on) c.accent else c.muted)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(entry.label, color = if (on) c.accent else c.foreground, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (entry.note.isNotBlank()) {
                                    Text(entry.note, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (entry.kind == MvMenuRowKind.Toggle) {
                                Box(
                                    Modifier.width(42.dp).height(24.dp).clip(RoundedCornerShape(12.dp))
                                        .background(if (entry.on) c.accent else c.foreground.copy(alpha = .16f)),
                                    contentAlignment = if (entry.on) Alignment.CenterEnd else Alignment.CenterStart,
                                ) {
                                    Box(Modifier.padding(3.dp).size(18.dp).clip(CircleShape)
                                        .background(if (entry.on) c.onAccent else c.foreground.copy(alpha = .7f)))
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (entry.canDown) Text("‹", color = c.muted.copy(alpha = if (on) .85f else .28f), fontSize = 16.sp)
                                    Text(entry.value, color = if (on) c.foreground else c.muted, fontSize = 15.sp, maxLines = 1)
                                    if (entry.canUp) Text("›", color = c.muted.copy(alpha = if (on) .85f else .28f), fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
                Text("↑ ↓ 选择 · ← → 调整 · 确定执行 · 返回关闭",
                    color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun MvMoreDots(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    val idleBorder = c.accent.copy(alpha = .18f)
    Box(
        modifier.size(44.dp)
            .focusProperties { canFocus = enabled }
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(if (focused) c.accent.copy(alpha = .22f) else Color.White.copy(alpha = .06f))
            .border(if (focused) 2.dp else 1.dp, if (focused) c.accent else idleBorder, CircleShape)
            .semantics { contentDescription = "更多"; if (!enabled) disabled() }
            .then(if (enabled) Modifier.tvClickable(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val r = size.minDimension * .085f
            val cy = size.height / 2f
            listOf(.18f, .5f, .82f).forEach { f ->
                drawCircle(if (focused) c.accent else c.foreground.copy(alpha = .82f), radius = r, center = Offset(size.width * f, cy))
            }
        }
    }
}

@Composable
private fun MvHeaderPill(text: String, tint: Color) {
    Text(
        text,
        color = tint,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = .08f))
            .border(1.dp, tint.copy(alpha = .28f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun MvCircleButton(
    icon: String,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    val idleBorder = if (active) c.accent.copy(alpha = .45f) else c.accent.copy(alpha = .18f)
    Box(
        modifier.size(44.dp)
            .focusProperties { canFocus = enabled }
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(when {
                focused -> c.accent.copy(alpha = .22f)
                active -> c.accent.copy(alpha = .12f)
                else -> Color.White.copy(alpha = .06f)
            })
            .border(if (focused) 2.dp else 1.dp, if (focused) c.accent else idleBorder, CircleShape)
            .semantics { contentDescription = label; selected = active; if (!enabled) disabled() }
            .then(if (enabled) Modifier.tvClickable(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(
            icon,
            Modifier.size(20.dp),
            when {
                !enabled -> c.muted.copy(alpha = .35f)
                focused || active -> c.accent
                else -> c.foreground.copy(alpha = .82f)
            },
        )
    }
}

@Composable
private fun MvPrimaryPlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.size(58.dp)
            .focusProperties { canFocus = enabled }
            .semantics { contentDescription = if (playing) "暂停" else "播放"; if (!enabled) disabled() }
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .then(if (enabled) Modifier.tvClickable(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (focused) {
            Box(
                Modifier.fillMaxSize().clip(CircleShape)
                    .background(c.accent.copy(alpha = .14f))
                    .border(2.dp, c.foreground.copy(alpha = .92f), CircleShape)
            )
        }
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(c.accent),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(if (playing) "pause" else "play", Modifier.size(20.dp), c.onAccent)
        }
    }
}

internal fun mvTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000
    val remainder = "${(seconds / 60 % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
    return if (seconds >= 3600) "${seconds / 3600}:$remainder" else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
