package tv.prism

import android.content.Context
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.tv.material3.Text
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** MV is an explicit Prism extension; the music session is paused by the caller. */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun MvPlayer(source: VideoSource, title: String, artist: String, progressKey: String = "", onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val c = LocalPrismColors.current
    val focusManager = LocalFocusManager.current
    val seekScope = rememberCoroutineScope()
    val player = remember(source) {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(source.headers)
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(DefaultDataSource.Factory(context, http)))
            .setAudioAttributes(AudioAttributes.DEFAULT, true).setHandleAudioBecomingNoisy(true).build()
    }
    /* Where the MV stopped last time lives per song, not per URL: a resolved stream address is
       usually signed and changes every open, while the song does not. */
    val progressPrefs = remember(context) { context.getSharedPreferences("mv_progress", Context.MODE_PRIVATE) }
    val playFocus = remember { FocusRequester() }
    /* When the chrome hides, focus has nowhere to live. The screen itself becomes the
       focus target so OK can wake the toolbar and Back can still leave. */
    val rootFocus = remember { FocusRequester() }
    var playing by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var videoSize by remember { mutableStateOf<VideoSize?>(null) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    /* A video is the content; controls are the interruption. They open with the MV, fade once
       playback settles, and come back on an OK press that also toggles play/pause. */
    var chrome by remember { mutableStateOf(true) }
    var chromeTick by remember { mutableIntStateOf(0) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var pendingShortPress by remember { mutableStateOf(0) } // -1 left, 1 right
    var seekHeld by remember { mutableStateOf(false) } // true once the repeat ticks start
    var pressFromChrome by remember { mutableStateOf(true) }
    val saveProgress = {
        if (progressKey.isNotBlank()) {
            val p = player.currentPosition.coerceAtLeast(0L)
            val d = player.duration.coerceAtLeast(0L)
            if (p > 2000 && (d <= 0 || p < d - 4000)) progressPrefs.edit().putLong(progressKey, p).apply()
            else progressPrefs.edit().remove(progressKey).apply()
        }
    }
    val close = { saveProgress(); player.pause(); onClose() }
    BackHandler(onBack = close)
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
            override fun onPlayerError(playbackError: PlaybackException) { error = "MV 播放失败：${playbackError.message ?: "未知错误"}" }
            override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && progressKey.isNotBlank()) {
                    val saved = progressPrefs.getLong(progressKey, 0L)
                    val total = player.duration.coerceAtLeast(0L)
                    if (saved > 2000 && (total <= 0 || saved < total - 4000)) player.seekTo(saved)
                    else progressPrefs.edit().remove(progressKey).apply()
                }
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
        onDispose { saveProgress(); lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }
    /* Only hide while the video runs. A paused picture should keep its controls on screen. */
    LaunchedEffect(chrome, chromeTick, playing) {
        if (!chrome || !playing) return@LaunchedEffect
        delay(4_000)
        chrome = false
    }
    LaunchedEffect(chrome) {
        withFrameNanos { }
        if (chrome) runCatching { playFocus.requestFocus() } else runCatching { rootFocus.requestFocus() }
    }
    val startSeek = { direction: Int ->
        chrome = true
        seekJob?.cancel()
        seekJob = seekScope.launch {
            /* Bilibili TV style: a held direction key becomes a repeating seek. The first tick
               waits out the short-press window, then every tick jumps 10s and keeps the chrome
               alive so the progress bar is the feedback. */
            delay(550)
            seekHeld = true
            var ticks = 0
            while (isActive && ticks < 200) {
                val total = player.duration.takeIf { it > 0 } ?: (Long.MAX_VALUE / 2)
                position = (player.currentPosition + direction * 10_000).coerceIn(0L, total)
                player.seekTo(position)
                chromeTick += 1
                ticks += 1
                delay(300)
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFocus).focusable().onPreviewKeyEvent { event ->
        when {
            event.type == KeyEventType.KeyDown && event.key in setOf(Key.Back, Key.Escape) -> { close(); true }
            event.type != KeyEventType.KeyDown && event.type != KeyEventType.KeyUp -> false
            /* A hidden toolbar turns OK into play/pause and brings the controls back. While the
               toolbar is visible, OK belongs to the focused button instead. */
            event.type == KeyEventType.KeyDown && event.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter) && !chrome -> {
                if (player.playWhenReady) player.pause() else player.play()
                chrome = true
                chromeTick += 1
                true
            }
            event.key in setOf(Key.DirectionLeft, Key.DirectionRight) -> {
                val direction = if (event.key == Key.DirectionRight) 1 else -1
                if (event.type == KeyEventType.KeyDown) {
                    if (event.nativeKeyEvent.repeatCount == 0) {
                        /* System repeats must not cancel the seek: the first repeat arrives
                           before the 550ms window closes, so cancelling here killed every
                           long-press before its first tick. */
                        seekHeld = false
                        pressFromChrome = chrome
                        pendingShortPress = direction
                        startSeek(direction)
                    }
                    true
                } else {
                    seekJob?.cancel(); seekJob = null
                    val short = pendingShortPress
                    pendingShortPress = 0
                    if (short != 0 && !seekHeld) {
                        /* A tap while the toolbar was already visible walks the buttons; a tap
                           that woke the toolbar leaves focus on the play/pause button. */
                        if (pressFromChrome) focusManager.moveFocus(if (short > 0) FocusDirection.Right else FocusDirection.Left)
                    }
                    seekHeld = false
                    true
                }
            }
            event.type == KeyEventType.KeyDown && !chrome && event.key in setOf(Key.DirectionUp, Key.DirectionDown) -> {
                chrome = true
                chromeTick += 1
                true
            }
            else -> false
        }
    }) {
        /* Keep the video's own aspect ratio instead of stretching it to the panel. A portrait
           clip on a 16:9 TV would otherwise look like a funhouse mirror. */
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val ratio = videoSize?.takeIf { it.height > 0 }?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
            val screenRatio = maxWidth.value / maxHeight.value
            val video = if (ratio >= screenRatio) Modifier.fillMaxWidth().height(maxWidth / ratio)
                else Modifier.fillMaxHeight().width(maxHeight * ratio)
            AndroidView(factory = { SurfaceView(it).also(player::setVideoSurfaceView) }, modifier = video)
        }
        AnimatedVisibility(
            visible = chrome, enter = fadeIn(tween(180)), exit = fadeOut(tween(360)),
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        ) {
            Box(Modifier.fillMaxWidth().height(140.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .74f), Color.Transparent)))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    AuraBrand()
                    Spacer(Modifier.width(18.dp))
                    Text("MV", color = c.accent, fontSize = 10.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if (playing) "正在播放" else "已暂停", color = c.muted, fontSize = 11.sp)
                }
            }
        }
        AnimatedVisibility(
            visible = chrome, enter = fadeIn(tween(180)), exit = fadeOut(tween(360)),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .9f))))
                .padding(start = 44.dp, end = 44.dp, top = 80.dp, bottom = 32.dp)) {
                Text(title.ifBlank { "MV" }, color = c.foreground, fontSize = 26.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (artist.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(artist, color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(18.dp))
                val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = .16f))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(c.accent))
                }
                Spacer(Modifier.height(7.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(timeLabel(position), color = c.muted, fontSize = 10.sp)
                    Text(timeLabel(duration), color = c.muted, fontSize = 10.sp)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrismAction(if (playing) "暂停" else "播放",
                        { if (player.playWhenReady) player.pause() else player.play() }, Modifier.focusRequester(playFocus), primary = true)
                    PrismAction("返回音乐", close)
                    if (error.isNotBlank()) Text(error, color = c.accent, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                /* The seek lives on the direction keys, not on a row of buttons: hold 左/右 to
                   rewind/advance. Say it once so the remote never has to guess. */
                Spacer(Modifier.height(10.dp))
                Text("按住 左/右 快退快进 · 确定键 播放暂停", color = c.muted.copy(alpha = .75f), fontSize = 10.sp)
            }
        }
    }
}

private fun timeLabel(ms: Long): String = "%d:%02d".format(ms / 1000 / 60, ms / 1000 % 60)
