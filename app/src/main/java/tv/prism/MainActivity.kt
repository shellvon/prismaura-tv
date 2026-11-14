package tv.prism

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clipToBounds
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

private data class PlayingSource(val originalKey: String, val song: Song)
private class PlaybackRecovery(val sources: SourceRecovery, val onFailure: (Boolean) -> Unit) {
    var ready: CompletableDeferred<Unit>? = null
    var mediaId = ""
    var hasAcceptedStream = false
    var position = 0L
}

// Keep the UI's queue when Android recreates the activity; the audio itself lives in PlaybackService.
private object ActiveQueue {
    var songs by mutableStateOf<List<Song>>(emptyList())
    var index by mutableIntStateOf(-1)
    var radio by mutableStateOf(false)
    var radioRecent: List<String> = emptyList()
    var source by mutableStateOf<PlayingSource?>(null)
}

private data class SearchEntity(val pluginId: String, val sourceName: String, val data: JSONObject)
private data class CardFocusRow(val cards: List<FocusRequester>, val horizontal: LazyListState? = null)
private data class CollectionMenuTarget(val title: String, val songs: List<Song>, val playlistId: String? = null)
private data class TrackMenuTarget(val song: Song, val list: String? = null, val originFocus: String = "")

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : ComponentActivity() {
    private lateinit var appUpdates: AppUpdates
    private var firstFrameDrawn by mutableStateOf(false)
    private var updateDialogOpen by mutableStateOf(false)
    private var updateJob: Job? = null

    private fun checkAppUpdates() {
        updateDialogOpen = true
        if (updateJob?.isActive == true) return
        updateJob = lifecycleScope.launch { appUpdates.check(force = true) }
    }
    private fun cancelAppUpdate() {
        updateJob?.cancel(); appUpdates.cancelNetwork()
    }
    private fun downloadAppUpdate() {
        if (updateJob?.isActive == true) return
        updateJob = lifecycleScope.launch { appUpdates.download() }
    }

    private lateinit var plugins: Plugins
    private lateinit var library: MusicLibrary
    private lateinit var resumeStore: ResumeStore
    /* Position to restore once the controller connects; -1 means nothing is waiting. */
    private var resumePending = -1L
    private var connection: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private var status by mutableStateOf("正在连接播放器…")
    private var playbackStatus by mutableStateOf("正在连接播放器…")

    /* 持焦节点被换掉后按键会整页落空；记一次，由界面按当前页面补落焦。 */
    private var focusRecovery by mutableStateOf(0)
    /* 被吞掉的那次按键，落焦后重放一次。 */
    private var replayKey by mutableStateOf<KeyEvent?>(null)
    private var replaying = false
    private val replayableKeys = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    )

    private fun reportPlayback(message: String) {
        playbackStatus = message
        status = message
    }
    private var page by mutableStateOf("home")
    /* 音源页是按订阅分组的长列表：二十多个音源、两个组时，折叠比多选更值。
       状态挂在 Activity 上，离开设置页再回来不会自己展开。 */
    private val collapsedSubscriptions = mutableStateMapOf<String, Boolean>()
    private var playerOpen by mutableStateOf(false)
    private var playerScreenOff by mutableStateOf(false)
    // Retain the player during the fade; remove it once the black curtain is opaque.
    private var screenOffSettled by mutableStateOf(true)
    private val wakeKeys = mutableSetOf<Int>()
    private var selectedPlugin by mutableStateOf("")
    private var searchSources by mutableStateOf<Set<String>>(emptySet())
    private var recommendationPluginId by mutableStateOf("")
    private var chartPluginId by mutableStateOf("")
    private var radioLoading by mutableStateOf(false)
    private var radioStatus by mutableStateOf("")
    private var radioJob: Job? = null
    private var radioAdvanceJob: Job? = null
    private var radioRetryAt = 0L
    private var radioMenuOpen by mutableStateOf(false)
    private val radioSheets = mutableListOf<String>()
    private var results by mutableStateOf<List<Song>>(emptyList())
    private var searchType by mutableStateOf("music")
    private var entities by mutableStateOf<List<SearchEntity>>(emptyList())
    private var searchTerm by mutableStateOf("")
    private var searchHistory by mutableStateOf<Map<String, List<String>>>(emptyMap())
    private var hasMore by mutableStateOf(false)
    private var searchAppending by mutableStateOf(false)
    private var searchNextPages = emptyMap<String, Int>()
    /* Exponential moving average of per-source search time, so a slow source stops being
       asked first after its first slow answer. */
    private var searchLatency = emptyMap<String, Long>()
    private val hotSearchCache = linkedMapOf<String, Pair<Long, List<SearchSuggestion>>>()
    private var recommendations by mutableStateOf<List<JSONObject>>(emptyList())
    private var recommendationStatus by mutableStateOf("")
    private var recommendationTags by mutableStateOf<List<JSONObject>>(emptyList())
    private var recommendationTag by mutableStateOf(JSONObject().put("id", "").put("title", "推荐"))
    private var recommendationPage by mutableIntStateOf(1)
    private var recommendationMore by mutableStateOf(false)
    private var recommendationLoading by mutableStateOf(false)
    /* Startup subscription sync publishes new Plugin instances over and over. Effects keyed on
       the list identity restarted and blanked the rail on every write, which is the flicker. */
    private var recommendationTagsOwner = ""
    private var recommendationContext = ""
    private var recommendationGeneration = 0
    private var searchJob: Job? = null
    private var focusSearchResults by mutableStateOf(false)
    private var searchGeneration = 0
    private var browseTitle by mutableStateOf("")
    private var browseReturnPage by mutableStateOf("home")
    private var browseKind by mutableStateOf("")
    private var browseItem by mutableStateOf<JSONObject?>(null)
    private var browsePluginId by mutableStateOf("")
    private var browseSongs by mutableStateOf<List<Song>>(emptyList())
    private var browsePage by mutableIntStateOf(1)
    private var browseMore by mutableStateOf(false)
    private var browseStatus by mutableStateOf("")
    private var browseLoading by mutableStateOf(false)
    private var browseJob: Job? = null
    private var browseGeneration = 0
    /* An artist page carries its albums above the songs. The info object is a Prism-only extra
       from our own plugins (bio, photo, publish date); standard plugins simply omit it. */
    private var browseAlbums by mutableStateOf<List<JSONObject>>(emptyList())
    private var browseAlbumStatus by mutableStateOf("")
    private var browseAlbumLoading by mutableStateOf(false)
    private var browseAlbumJob: Job? = null
    private var browseInfo by mutableStateOf<JSONObject?>(null)
    /* Detail pages stack: an album opened from an artist returns there, and a detail opened
       from the player returns to the player. */
    private val browseStack = ArrayDeque<BrowseReturn>()
    /* The card that opened a detail, so Back can put the remote back on it instead of
       dropping focus on the first row. */
    private var pendingReturnFocus by mutableStateOf("")
    private var queueOpen by mutableStateOf(false)
    private var subjectJob: Job? = null
    private var charts by mutableStateOf<List<JSONObject>>(emptyList())
    private var chartStatus by mutableStateOf("")
    private var chartPreviews by mutableStateOf<Map<String, List<Song>>>(emptyMap())
    private var chartSelection by mutableStateOf<JSONObject?>(null)
    private var chartSongs by mutableStateOf<List<Song>>(emptyList())
    private var chartDetailStatus by mutableStateOf("")
    private var chartJob: Job? = null
    private var chartGeneration = 0
    private var chartLoading by mutableStateOf(false)
    private var queue: List<Song>
        get() = ActiveQueue.songs
        set(value) { ActiveQueue.songs = value }
    private var queueIndex: Int
        get() = ActiveQueue.index
        set(value) { ActiveQueue.index = value }
    private var playMode by mutableStateOf(PlaybackMode.SEQUENTIAL)
    private var quality by mutableStateOf("standard")
    private var autoSourceFallback by mutableStateOf(false)
    private var recovery: PlaybackRecovery? = null
    private var streamFormat by mutableStateOf("")
    /* The tier of the stream that is actually playing, blank until a source resolves one.
       The preference is what to ask for; this is what came back after the ladder ran. */
    private var playingQuality by mutableStateOf("")
    /* A tier the plugin answered with no URL is one it does not serve; offering it in the
       player pill again would promise a stream that cannot play. Keyed by song. */
    private val unsupportedTiers = mutableStateMapOf<String, Set<String>>()
    // Per-song session overrides leave the global quality preference unchanged.
    private val songTier = mutableStateMapOf<String, String>()
    private fun tierFor(song: Song?): String = song?.let { songTier[it.key] } ?: quality
    private val tierCache = mutableMapOf<String, List<String>>()
    private var availableTiers by mutableStateOf<List<String>>(emptyList())
    private var playing by mutableStateOf(false)
    private var exitArmedAt = 0L
    private var playbackState by mutableIntStateOf(Player.STATE_IDLE)
    private var busy by mutableStateOf(false)
    private var resolving: Job? = null
    /* A tier swap is not a track change; it gets its own job so it can never cancel, or be
       cancelled by, the resolver that put the current song on screen. */
    private var streamSwap: Job? = null
    /* One re-mint per track per failure: a second 403 on a freshly minted address means the
       source itself is refusing, and asking a third time would only spin. */
    private var remintKey = ""
    /* The song the player actually holds. Re-picking it — from an album page, a shelf or the
       queue — has to keep the audio running instead of starting the track over. */
    private var loadedKey = ""
    /* Automatic skips share one failure pass; manual selection or normal track advancement resets it. */
    private val failedSongs = mutableSetOf<String>()
    private var autoSkipToken = 0
    private var pendingSeekMs: Long? = null
    private var lyricJob: Job? = null
    private var lyrics by mutableStateOf<List<LyricLine>>(emptyList())
    private var lyricStatus by mutableStateOf("暂无歌词")
    private var lyricToken = 0
    /* 歌词字号档位与副歌词开关全机共享；校准是当前这首歌的（换歌即重载）。 */
    private var lyricFontLevel by mutableStateOf("标准")
    private var subLyrics by mutableStateOf(true)
    private var lyricCalibrationMs by mutableStateOf(0L)
    private var positionMs by mutableLongStateOf(0L)
    private var durationMs by mutableLongStateOf(0L)
    private var theme by mutableStateOf("gold")
    private var showMiniLyrics by mutableStateOf(false)
    private var reduceMotion by mutableStateOf(false)
    /* 0 is automatic; PluginWorkers turns it into a count for this device. */
    private var pluginWorkerLimit by mutableIntStateOf(0)
    /* Home shelves are visible unless the user hides them, so a new favourite list —
       an imported playlist most of all — shows up on its own. */
    private var homeShelfHidden by mutableStateOf(setOf<String>())
    /* The import runs outside the composable that owns 我的's tab state, so the playlist it
       writes comes across as an id to open rather than a page nobody can find. */
    private var playlistToShow by mutableStateOf<String?>(null)
    private var phoneRemote by mutableStateOf<PhoneInput?>(null)
    private var phoneForm by mutableStateOf<PhoneInput?>(null)
    private val phoneInput: PhoneInput? get() = if (phoneRemoteMode) phoneRemote else phoneForm
    /* The QR dialog is only the way in. Hiding it must not stop the server: the whole point
       of the remote is to use the phone while the TV stays unobstructed. */
    private var phoneDialog by mutableStateOf(false)
    private var phoneRemoteMode by mutableStateOf(false)
    private var phoneRemoteEntry by mutableStateOf("play")
    private val phoneHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var phoneRemoteTimeout: Runnable? = null
    private var phoneFormTimeout: Runnable? = null
    private var restoringBackup by mutableStateOf(false)
    private var restoreMessage by mutableStateOf("")
    private var restoreFailed by mutableStateOf(false)
    private val pluginReplacements = mutableStateListOf<PluginReplacement>()
    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            playing = playWhenReady
            if (!playWhenReady && controller?.playWhenReady == false && recovery?.mediaId?.isNotBlank() == true) pauseRecoveryIfPending()
        }
        /* A failed resolve pauses the old track and leaves its title in the status line. When
           the user resumes that track, the failure belongs to a song that is no longer on
           screen; replace it with what is actually playing. */
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) return
            val pass = recovery
            if (pass != null && loadedKey != pass.sources.original.key) {
                if (controller?.currentMediaItem?.mediaId == pass.mediaId) pass.ready?.complete(Unit)
                return
            }
            if (pass == null) failedSongs.clear()
            if (ActiveQueue.radio) {
                queue.getOrNull(queueIndex)?.let { song ->
                    val key = Discovery.radioIdentity(song)
                    ActiveQueue.radioRecent = (ActiveQueue.radioRecent.filterNot { it == key } + key).takeLast(80)
                }
                ensureRadioTail()
            }
            if (playbackStatus.startsWith("解析失败") || playbackStatus.startsWith("播放失败")) {
                reportPlayback(queue.getOrNull(queueIndex)?.let { "正在播放：${it.title}" }.orEmpty())
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            /* The stream died; re-picking this song must go through the resolver again
               instead of taking the "already loaded" shortcut. */
            loadedKey = ""
            val original = queue.getOrNull(queueIndex)
            val httpCode = generateSequence(error as Throwable) { it.cause }.take(6)
                .mapNotNull { Regex("Response code: (\\d{3})").find(it.message.orEmpty())?.groupValues?.get(1) }.firstOrNull()
            // Refresh an expired URL once before the usual failure recovery.
            if (recovery == null && httpCode in setOf("403", "404", "410") && original != null && original.pluginId != "local" &&
                remintKey != original.key) {
                val owner = plugins.state.value.plugins.firstOrNull { it.id == (playbackSong() ?: original).pluginId }
                if (owner != null) {
                    remintKey = original.key
                    val tier = playingQuality.ifBlank { quality }
                    val at = maxOf(positionMs, controller?.currentPosition ?: 0L)
                    val wasPlaying = controller?.playWhenReady == true
                    streamSwap?.cancel()
                    streamSwap = lifecycleScope.launch {
                        val source = try { plugins.mediaSource(owner, playbackSong() ?: original, tier, refresh = true) }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { null }
                        val uri = Uri.parse(source?.optString("url").orEmpty())
                        if (source == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
                            onPlayerError(error)
                            return@launch
                        }
                        playingQuality = source.optString("quality").ifBlank { tier }
                        loadedKey = original.key
                        controller?.setMediaItem(audioMediaItem(original, source))
                        controller?.prepare()
                        if (at > 0L) { controller?.seekTo(at); positionMs = at }
                        if (wasPlaying) controller?.play()
                    }
                    return
                }
            }
            if (autoSourceFallback && controller?.playWhenReady == true && original != null && original.pluginId != "local") {
                val existing = recovery?.takeIf { it.sources.original.key == original.key }
                val pass = existing ?: PlaybackRecovery(SourceRecovery(plugins, original, playbackSong() ?: original, tierFor(original))) {
                    advanceAfterFailure(original, "播放失败", "播放失败：${original.title}，未找到可播放的同版本") { }
                }.also { it.hasAcceptedStream = true; recovery = it }
                if (existing != null) {
                    if (controller?.currentMediaItem?.mediaId != pass.mediaId) return
                    if (pass.ready != null) { pass.ready?.completeExceptionally(error); return }
                    pass.sources.failed()
                }
                continueRecovery(pass, maxOf(positionMs, controller?.currentPosition ?: 0L))
                return
            }
            val reason = when {
                httpCode != null -> "音频地址不可访问（HTTP $httpCode）"
                error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "音频地址请求失败，可重试或更换音源"
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败"
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接超时"
                else -> error.message ?: "未知错误"
            }
            reportPlayback("播放失败：$reason")
            if (ActiveQueue.radio) queue.getOrNull(queueIndex)?.let {
                advanceAfterFailure(it, "播放失败", playbackStatus, controller?.playWhenReady == true) { }
            }
        }
        override fun onTracksChanged(tracks: Tracks) {
            val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            val format = group?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
            val codec = when (format?.sampleMimeType) {
                "audio/flac" -> "FLAC"
                "audio/alac" -> "ALAC"
                "audio/mpeg" -> "MP3"
                "audio/mp4a-latm" -> "AAC"
                "audio/opus" -> "Opus"
                "audio/vorbis" -> "Vorbis"
                "audio/raw" -> "PCM"
                else -> ""
            }
            val bitrate = format?.bitrate?.takeIf { it > 0 }?.let { "${it / 1000} kbps" }.orEmpty()
            streamFormat = listOf(codec, bitrate).filter { it.isNotBlank() }.joinToString(" · ")
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            this@MainActivity.playbackState = playbackState
            if (playbackState == Player.STATE_READY) pendingSeekMs?.let { at -> controller?.seekTo(at); pendingSeekMs = null }
            if (playbackState == Player.STATE_READY) recovery?.takeIf { controller?.currentMediaItem?.mediaId == it.mediaId }?.ready?.complete(Unit)
            if (playbackState == Player.STATE_ENDED && (controller?.duration ?: 0L) > 0L) {
                recovery?.takeIf { controller?.currentMediaItem?.mediaId == it.mediaId }?.ready?.complete(Unit)
            }
            if (playbackState == Player.STATE_ENDED && queue.isNotEmpty() && resolving?.isActive != true) {
                when {
                    ActiveQueue.radio -> step(1)
                    playMode == PlaybackMode.REPEAT_ONE -> playAt(queueIndex, openPlayer = false)
                    playMode == PlaybackMode.SEQUENTIAL && queueIndex == queue.lastIndex -> reportPlayback("播放完毕")
                    else -> {
                        step(1)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        /* A fresh task launch replays the intro even in a warm process; a config-change
           recreation (saved state present) keeps it quiet. */
        if (savedInstanceState == null) PrismLaunch.played = false
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        SourcePlatforms.initialize(this)
        appUpdates = (application as PrismApplication).appUpdates
        plugins = AppPlugins.get(this)
        library = MusicLibrary(this)
        resumeStore = ResumeStore(this)
        quality = getSharedPreferences("playback", MODE_PRIVATE).getString("quality", "standard") ?: "standard"
        autoSourceFallback = getSharedPreferences("playback", MODE_PRIVATE).getBoolean(SourceRecovery.PREFERENCE, false)
        theme = getSharedPreferences("appearance", MODE_PRIVATE).getString("theme", "gold") ?: "gold"
        showMiniLyrics = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("mini_lyrics", false)
        reduceMotion = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("reduce_motion", false)
        lyricFontLevel = getSharedPreferences("appearance", MODE_PRIVATE).getString("lyric_font_level", "标准") ?: "标准"
        subLyrics = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("sub_lyrics", true)
        pluginWorkerLimit = PluginWorkers.saved(this)
        searchHistory = loadSearchHistory()
        searchLatency = runCatching {
            val saved = JSONObject(getSharedPreferences("search", MODE_PRIVATE).getString("latency", "{}") ?: "{}")
            saved.keys().asSequence().associateWith { saved.optLong(it) }
        }.getOrDefault(emptyMap())
        playMode = readPlaybackMode()
        homeShelfHidden = getSharedPreferences("home", MODE_PRIVATE).getStringSet("hidden_shelves", null)?.toSet() ?: emptySet()
        recommendationPluginId = getSharedPreferences("home", MODE_PRIVATE).getString("recommendation_plugin", "") ?: ""
        chartPluginId = getSharedPreferences("home", MODE_PRIVATE).getString("chart_plugin", "") ?: ""
        /* The queue lives outside the activity, so a recreated activity keeps playing; only a
           cold start needs the saved queue and needle position back. */
        if (queue.isEmpty()) resumeStore.read()?.let { saved ->
            queue = saved.songs
            queueIndex = saved.index
            ActiveQueue.radio = saved.radio
            ActiveQueue.radioRecent = saved.radioRecent
            positionMs = saved.position
            resumePending = saved.position
        }
        setContent { MaterialTheme { Screen() } }
        // Let the first home frame draw before starting services and background maintenance.
        window.decorView.doOnPreDraw { view ->
            view.post {
                if (!isDestroyed) {
                    firstFrameDrawn = true
                    val restoringQueue = resumePending >= 0
                    connect()
                    if (!restoringQueue) playbackSong()?.let { song ->
                        /* 重建时后台服务仍在播这首歌：校准要和歌曲身份一起恢复，不能回默认 0。 */
                        lyricCalibrationMs = LyricCalibration.load(this@MainActivity, song.key)
                        plugins.state.value.plugins.firstOrNull { it.id == song.pluginId }?.let { loadLyrics(it, song) }
                    }
                    lifecycleScope.launch(Dispatchers.IO) { AppPlugins.schedule(this@MainActivity) }
                    lifecycleScope.launch {
                        delay(10_000)
                        if (updateJob?.isActive != true) updateJob = lifecycleScope.launch { appUpdates.check() }
                    }
                    /* Subscriptions are remote code: check them once per launch window, not on every launch,
                       so an offline box does not stall and a plugin update lands without waiting for WorkManager. */
                    lifecycleScope.launch {
                        val prefs = getSharedPreferences("sync", MODE_PRIVATE)
                        val now = System.currentTimeMillis()
                        if (now - prefs.getLong("at", 0L) < 6 * 60 * 60 * 1000L) return@launch
                        prefs.edit().putLong("at", now).apply()
                        try {
                            val result = plugins.syncAll()
                            if (result.failed) status = "订阅更新：${result.message}"
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e
                        } catch (_: Exception) { /* Startup sync is a quiet convenience; manual sync reports. */ }
                    }
                    lifecycleScope.launch {
                        var ticks = 0
                        while (isActive) {
                            val p = controller
                            /* While a restored track is still being prepared, the controller reports 0 and
                               would overwrite the saved position with it. */
                            if (p != null && resumePending < 0 && pendingSeekMs == null && !(recovery != null && resolving?.isActive == true)) {
                                val duration = p.duration.coerceAtLeast(0)
                                if (duration > 0 || p.playbackState != Player.STATE_IDLE) {
                                    positionMs = p.currentPosition.coerceAtLeast(0)
                                    durationMs = duration
                                }
                                if (ActiveQueue.radio && p.isPlaying) ensureRadioTail()
                            }
                            if (++ticks >= 30) { ticks = 0; saveResume() }
                            delay(500)
                        }
                    }
                }
            }
        }
    }

    private fun connect() {
        connection?.let(MediaController::releaseFuture)
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    this@MainActivity.controller = null
                    reportPlayback("播放器已断开，请选择重新连接")
                }
            }).buildAsync()
        connection = future
        future.addListener({
            if (connection !== future || isDestroyed) return@addListener
            try {
                controller = future.get().also { it.addListener(listener); playing = it.playWhenReady; playbackState = it.playbackState; listener.onTracksChanged(it.currentTracks) }
                /* Restore paused: reopening the app should show where you stopped, not start
                   talking to the room on its own. */
                val at = resumePending
                if (at >= 0 && queue.isNotEmpty()) {
                    /* Gate the position poll until the prepared stream reaches READY, otherwise
                       it reports 0 before the seek lands and the saved point is lost. */
                    pendingSeekMs = at.takeIf { it > 0 }
                    resumePending = -1
                    playAt(queueIndex, openPlayer = false, autoplay = false, startAt = at)
                }
            }
            catch (e: Exception) { reportPlayback("播放器连接失败：${e.cause?.message ?: e.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun search(query: String, append: Boolean = false, focusResults: Boolean = true) {
        val requestedType = searchType
        val sources = plugins.state.value.plugins.filter { it.enabled }.filter { plugin ->
            val supported = plugin.metadata.optJSONArray("supportedSearchType")
            (searchSources.isEmpty() || plugin.id in searchSources) && (!append || plugin.id in searchNextPages) &&
                (supported == null || (0 until supported.length()).any { supported.optString(it) == requestedType })
        }.sortedBy { searchLatency[it.id] ?: 0L }
        if (sources.isEmpty() || query.isBlank()) {
            searchJob?.cancel(); searchGeneration++
            busy = false; hasMore = false; focusSearchResults = false
            if (!append) { results = emptyList(); entities = emptyList() }
            status = "请选择插件并输入搜索内容"
            return
        }
        if (!append) {
            val term = query.trim()
            val updated = (listOf(term) + searchHistory[requestedType].orEmpty().filterNot { it.equals(term, ignoreCase = true) }).take(30)
            searchHistory = searchHistory + (requestedType to updated)
            saveSearchHistory()
        }
        searchJob?.cancel()
        /* 每次新搜索都明确本次请求的焦点意图；tab 聚焦切换（focusResults=false）必须清掉
           上一次提交留下的 true，否则旧结果到达仍会把焦点从 tab 抢到结果区。 */
        if (!append) focusSearchResults = focusResults
        val generation = ++searchGeneration
        searchAppending = append
        searchJob = lifecycleScope.launch {
            busy = true
            val failures = mutableListOf<String>()
            val nextPages = mutableMapOf<String, Int>()
            var done = 0
            try {
                if (!append) { results = emptyList(); entities = emptyList(); hasMore = false }
                searchTerm = query.trim()
                /* Each source runs in its own sandbox worker, so a slow source no longer holds
                   the others back. Every answer still paints the moment it lands, and latency
                   is remembered so the fastest sources still start first. */
                coroutineScope {
                    sources.map { plugin ->
                        async {
                            if (generation != searchGeneration) return@async
                            val started = System.currentTimeMillis()
                            try {
                                val loaded = plugins.client.callPage(plugin, "search", if (append) searchNextPages.getValue(plugin.id) else 1, "data") { page ->
                                    JSONArray().put(query.trim()).put(page).put(requestedType)
                                }
                                val response = loaded.response
                                val list = response.optJSONArray("data") ?: JSONArray()
                                if (generation != searchGeneration) return@async
                                if (!response.optBoolean("isEnd", true)) nextPages[plugin.id] = loaded.number + 1
                                if (requestedType == "music") {
                                    val songs = (0 until minOf(list.length(), 100)).mapNotNull { list.optJSONObject(it)?.let(plugin::song) }
                                    // The source decides availability; vip is only a display hint.
                                    results = (results + songs).distinctBy { it.key }
                                } else {
                                    val shaped = (0 until minOf(list.length(), 100)).mapNotNull { list.optJSONObject(it)?.let { item -> SearchEntity(plugin.id, plugin.displayName, item) } }
                                        /* A source that ignores the requested type would otherwise put
                                           songs under 专辑/歌手/歌单. Drop them instead of mislabelling. */
                                        .filter { entry ->
                                            when (requestedType) {
                                                "album" -> isAlbumShaped(entry.data)
                                                "artist", "sheet" -> !isSongShaped(entry.data)
                                                else -> true
                                            }
                                        }
                                    entities = entities + shaped
                                }
                                done++
                                status = "找到 ${if (requestedType == "music") results.size else entities.size} 条 · 已查询 $done/${sources.size} 个音源"
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) {
                                if (generation == searchGeneration) {
                                    /* A bare source name told the user nothing about why nothing came back. */
                                    val reason = e.message?.trim()?.takeIf { it.isNotEmpty() } ?: "未知错误"
                                    failures += "${plugin.displayName}（$reason）"
                                    done++
                                }
                            } finally {
                                if (generation == searchGeneration) {
                                    val elapsed = System.currentTimeMillis() - started
                                    val previous = searchLatency[plugin.id]
                                    searchLatency = searchLatency + (plugin.id to if (previous == null) elapsed else (previous * 2 + elapsed) / 3)
                                }
                            }
                        }
                    }.awaitAll()
                }
                if (generation != searchGeneration) return@launch
                hasMore = nextPages.isNotEmpty()
                searchNextPages = nextPages
                getSharedPreferences("search", MODE_PRIVATE).edit().putString("latency",
                    JSONObject().apply { searchLatency.forEach { (id, ms) -> put(id, ms) } }.toString()).apply()
                val failureNote = if (failures.isEmpty()) "" else "；搜索失败：${failures.joinToString("、").take(300)}"
                status = if (results.isEmpty() && entities.isEmpty() && failures.isNotEmpty())
                    "搜索异常：${failures.joinToString("、").take(300)}"
                else "找到 ${if (requestedType == "music") results.size else entities.size} 条结果$failureNote"
            } finally { if (generation == searchGeneration) { busy = false; searchAppending = false } }
        }
    }

    private suspend fun loadRecommendations(plugin: Plugin, tag: JSONObject = recommendationTag, append: Boolean = false, reset: Boolean = false) {
        if (!plugin.capabilities.recommendations) return
        if (append && recommendationLoading) return
        recommendationLoading = true
        val generation = ++recommendationGeneration
        if (reset && !append) {
            recommendations = emptyList()
            recommendationPage = 1
            recommendationMore = false
        }
        if (recommendations.isEmpty()) recommendationStatus = "正在加载推荐歌单…"
        try {
            val nextPage = if (append) recommendationPage + 1 else 1
            val result = plugins.client.call(plugin.source, plugin.variables, "getRecommendSheetsByTag", JSONArray().put(tag).put(nextPage), plugin.id) as? JSONObject
                ?: error("插件没有返回推荐歌单")
            if (generation != recommendationGeneration) return
            val data = result.optJSONArray("data") ?: JSONArray()
            val items = (0 until minOf(data.length(), 100)).mapNotNull { data.optJSONObject(it) }
            recommendations = if (append) recommendations + items else items
            recommendationPage = nextPage
            recommendationMore = items.isNotEmpty() && !result.optBoolean("isEnd", true)
            recommendationStatus = if (recommendations.isEmpty()) "该音源暂无推荐歌单" else ""
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            if (generation != recommendationGeneration) return
            recommendationStatus = if (e.message?.contains("不支持 getRecommendSheetsByTag") == true)
                "该音源暂不支持推荐歌单" else "推荐歌单加载失败：${e.message ?: "未知错误"}"
        } finally { if (generation == recommendationGeneration) recommendationLoading = false }
    }

    private suspend fun loadRecommendationTags(plugin: Plugin) {
        if (!plugin.capabilities.has("recommendTags")) { recommendationTags = emptyList(); return }
        try {
            val result = plugins.client.call(plugin.source, plugin.variables, "getRecommendSheetTags", JSONArray(), plugin.id) as? JSONObject ?: return
            val collected = mutableListOf<JSONObject>()
            fun add(array: JSONArray?) { if (array != null) for (i in 0 until minOf(array.length(), 100)) array.optJSONObject(i)?.let { collected.add(it) } }
            add(result.optJSONArray("pinned"))
            val groups = result.optJSONArray("data") ?: JSONArray()
            for (i in 0 until minOf(groups.length(), 30)) add(groups.optJSONObject(i)?.optJSONArray("data"))
            if (recommendationTagsOwner == plugin.id) recommendationTags = collected.filter { it.optString("id").isNotBlank() && it.optString("title").isNotBlank() }.distinctBy { it.optString("id") }.take(100)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { /* Tags are optional in MusicFree. */ }
    }

    private fun captureBrowse(): BrowseSnapshot? = browseItem?.let { item ->
        BrowseSnapshot(browseTitle, browseKind, item, browsePluginId, browseSongs, browsePage,
            browseMore, browseStatus, browseAlbums, browseInfo)
    }

    private fun restoreBrowse(snapshot: BrowseSnapshot) {
        browseTitle = snapshot.title
        browseKind = snapshot.kind
        browseItem = snapshot.item
        browsePluginId = snapshot.pluginId
        browseSongs = snapshot.songs
        browsePage = snapshot.page
        browseMore = snapshot.more
        browseStatus = snapshot.status
        browseAlbums = snapshot.albums
        browseInfo = snapshot.info
        browseAlbumStatus = ""
        browseLoading = false
        browseAlbumLoading = false
    }

    /* Back leaves the detail, not the page the detail happened to live on: the snapshot is
       whatever the user was looking at when the detail opened. */
    private fun leaveBrowse() {
        browseJob?.cancel()
        browseAlbumJob?.cancel()
        browseGeneration++
        browseLoading = false
        browseAlbumLoading = false
        val previous = browseStack.removeLastOrNull()
        browseItem = null
        browseAlbums = emptyList()
        browseInfo = null
        browseAlbumStatus = ""
        pendingReturnFocus = previous?.focusKey.orEmpty()
        if (previous == null) { page = browseReturnPage; return }
        previous.browse?.let { restoreBrowse(it) }
        page = previous.page
        playerOpen = previous.playerOpen
    }

    private fun openBrowse(plugin: Plugin, item: JSONObject, kind: String, originFocus: String? = null) {
        browseJob?.cancel()
        browseAlbumJob?.cancel()
        browseGeneration++
        browseLoading = false
        browseAlbumLoading = false
        val nested = page == "discover" && browseItem != null
        /* Whatever opened this detail is the card Back should return to. A detail opened from
           the player is different: Back restores the player, not a card. A detail opened from a
           song row passes that row's key so Back lands on the row instead of a card that may
           not even be in the parent list. */
        val returnFocus = when {
            playerOpen -> ""
            originFocus != null -> originFocus
            else -> "$kind:${plugin.id}:${item.optString("id")}"
        }
        pendingReturnFocus = ""
        if (nested) browseStack.addLast(BrowseReturn(page, playerOpen, captureBrowse(), returnFocus))
        else {
            browseStack.clear()
            browseStack.addLast(BrowseReturn(page, playerOpen, null, returnFocus))
        }
        if (!nested) browseReturnPage = page
        playerOpen = false
        browseTitle = item.optString("title").ifBlank { item.optString("name").ifBlank { "详情" } }
        browseKind = kind
        browseItem = item
        browsePluginId = plugin.id
        browseSongs = emptyList()
        browseAlbums = emptyList()
        browseInfo = null
        browsePage = 1
        browseMore = false
        browseStatus = "正在加载…"
        browseAlbumStatus = if (kind == "artist") "正在加载专辑…" else ""
        page = "discover"
        if (kind == "artist") loadBrowseAlbums()
        loadBrowse(false)
    }

    private fun loadBrowse(append: Boolean) {
        val item = browseItem ?: return
        val plugin = plugins.state.value.plugins.firstOrNull { it.id == browsePluginId } ?: run { browseStatus = "音源已移除"; return }
        if (browseLoading) return
        val generation = browseGeneration
        browseJob = lifecycleScope.launch {
            browseLoading = true
            browseStatus = "正在加载…"
            try {
                val nextPage = if (append) browsePage + 1 else 1
                val method = when (browseKind) { "artist" -> "getArtistWorks"; "album" -> "getAlbumInfo"; else -> "getMusicSheetInfo" }
                val loaded = plugins.client.callPage(plugin, method, nextPage, if (browseKind == "artist") "data" else "musicList") { page ->
                    val args = JSONArray().put(item).put(page)
                    if (browseKind == "artist") args.put("music")
                    args
                }
                if (generation != browseGeneration) return@launch
                val result = loaded.response
                val items = if (browseKind == "artist") result.optJSONArray("data") else result.optJSONArray("musicList")
                val count = items?.length() ?: 0
                val songs = (0 until minOf(count, 200)).mapNotNull { items?.optJSONObject(it)?.let(plugin::song) }
                browseSongs = if (append) browseSongs + songs else songs
                if (!append) {
                    val info = JSONObject(item.toString())
                    val supplemental = result.optJSONObject("artistInfo") ?: result.optJSONObject("albumInfo")
                    supplemental?.keys()?.forEach { key -> if (!supplemental.isNull(key)) info.put(key, supplemental.get(key)) }
                    val standard = result.optJSONObject(if (browseKind == "album") "albumItem" else "sheetItem")
                    standard?.keys()?.forEach { key -> if (!standard.isNull(key)) info.put(key, standard.get(key)) }
                    browseItem = info
                    browseInfo = info
                    browseTitle = info.optString("title").ifBlank { info.optString("name").ifBlank { browseTitle } }
                }
                browsePage = loaded.number
                browseMore = !result.optBoolean("isEnd", true)
                browseStatus = if (browseSongs.isEmpty()) { if (browseMore) "本页暂无歌曲，可继续加载" else "暂无歌曲" } else ""
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { browseStatus = "加载失败：${e.message ?: "未知错误"}" }
            finally { if (generation == browseGeneration) browseLoading = false }
        }
    }

    private fun loadBrowseAlbums() {
        val item = browseItem ?: return
        if (browseKind != "artist" || browseAlbumLoading) return
        val plugin = plugins.state.value.plugins.firstOrNull { it.id == browsePluginId } ?: return
        val generation = browseGeneration
        browseAlbumJob = lifecycleScope.launch {
            browseAlbumLoading = true
            try {
                val result = plugins.client.call(plugin.source, plugin.variables, "getArtistWorks",
                    JSONArray().put(item).put(1).put("album"), plugin.id) as? JSONObject ?: error("音源未返回专辑")
                val data = result.optJSONArray("data") ?: JSONArray()
                val raw = (0 until minOf(data.length(), 60)).mapNotNull { data.optJSONObject(it) }
                val albums = raw.filter(::isAlbumShaped)
                browseAlbums = albums
                browseAlbumStatus = if (albums.isEmpty() && raw.isNotEmpty()) "该音源把歌曲当作歌手作品返回，未显示为专辑" else ""
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                /* A source without album works is not an error on this page; the row disappears. */
                browseAlbums = emptyList()
                browseAlbumStatus = ""
            } finally { if (generation == browseGeneration) browseAlbumLoading = false }
        }
    }

    private fun supportsSubjectSearch(plugin: Plugin?, kind: String): Boolean {
        val types = plugin?.metadata?.optJSONArray("supportedSearchType") ?: return plugin != null
        return (0 until types.length()).any { types.optString(it) == kind }
    }

    private fun subjectTitle(item: JSONObject): String = item.optString("title").ifBlank { item.optString("name") }

    /* getArtistWorks(item, page, "album") has no declared return shape, so a source that
       ignores the third argument answers with songs. Songs carry `duration` and an `album`
       name; album entities do not. Without this the album shelf could present songs — and
       opening one called getAlbumInfo with a song id, which is the "album that is actually a
       playlist" confusion. */
    private fun isSongShaped(item: JSONObject): Boolean =
        item.has("duration") || item.optString("album").isNotBlank() ||
            item.optString("songmid").isNotBlank() || item.optString("mediaMid").isNotBlank()

    private fun isAlbumShaped(item: JSONObject): Boolean = !isSongShaped(item) && subjectTitle(item).isNotBlank()

    /* Rows above the first song in the detail LazyColumn: the hero, an optional artist album
       shelf (header + one row), then the song shelf header. Needed to scroll back to a row. */
    private fun detailSongRowOffset(): Int =
        2 + if (browseKind == "artist" && (browseAlbums.isNotEmpty() || browseAlbumStatus.isNotBlank())) 2 else 0

    private fun normalizedName(value: String): String =
        value.lowercase().replace(Regex("[\\s·・/\\\\|（）()\\[\\]【】《》<>-]"), "")

    private suspend fun loadCharts(plugin: Plugin, force: Boolean = true) {
        val generation = ++chartGeneration
        chartLoading = false
        val cache = getSharedPreferences("discovery", MODE_PRIVATE)
        val owner = "${plugin.id}:${plugin.source.hashCode()}:${plugin.variables.toString().hashCode()}"
        val saved = runCatching {
            JSONObject(cache.getString("charts", "").orEmpty()).takeIf { it.optString("owner") == owner }
        }.getOrNull()
        saved?.let { root ->
            val list = root.optJSONArray("items") ?: JSONArray()
            charts = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            val previews = root.optJSONObject("previews") ?: JSONObject()
            chartPreviews = previews.keys().asSequence().associateWith { id ->
                val songs = previews.optJSONArray(id) ?: JSONArray()
                (0 until songs.length()).mapNotNull { songs.optJSONObject(it)?.let(plugin::song) }
            }
            val age = System.currentTimeMillis() - root.optLong("at")
            if (!force && age in 0 until 15 * 60_000 && charts.isNotEmpty() && chartPreviews[charts.first().optString("id")].orEmpty().isNotEmpty()) return
        }
        if (!plugin.capabilities.charts) {
            chartStatus = "该音源暂不支持榜单，可在设置中选择其它音源"
            return
        }
        chartStatus = "正在加载榜单…"
        chartLoading = true
        try {
            val groups = plugins.client.call(plugin.source, plugin.variables, "getTopLists", JSONArray(), plugin.id) as? JSONArray ?: error("音源未返回榜单")
            if (generation != chartGeneration) return
            charts = (0 until groups.length()).flatMap { i ->
                val data = groups.optJSONObject(i)?.optJSONArray("data") ?: JSONArray()
                (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            }
            chartStatus = if (charts.isEmpty()) "该音源暂无榜单" else ""
            val ids = charts.map { it.optString("id") }.toSet()
            chartPreviews = chartPreviews.filterKeys { it in ids }
            // The home card needs one preview; fetch the rest when discovery is opened.
            for (chart in charts.take(1)) {
                val songs = try { kotlinx.coroutines.withTimeout(8_000) { fetchChart(plugin, chart) } }
                catch (e: kotlinx.coroutines.TimeoutCancellationException) { emptyList() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { emptyList() }
                if (generation != chartGeneration) return
                if (songs.isNotEmpty()) chartPreviews = chartPreviews + (chart.optString("id") to songs)
            }
            val previews = JSONObject().apply {
                chartPreviews.entries.take(3).forEach { (id, songs) -> put(id, JSONArray(songs.take(10).map { it.data })) }
            }
            val snapshot = JSONObject().put("owner", owner).put("at", System.currentTimeMillis())
                .put("items", JSONArray(charts.take(60))).put("previews", previews).toString()
            if (snapshot.length < 512_000) cache.edit().putString("charts", snapshot).apply()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { if (generation == chartGeneration) chartStatus = "榜单加载失败，请稍后重试：${e.message ?: "未知错误"}" }
        finally { if (generation == chartGeneration) chartLoading = false }
    }

    private suspend fun fetchChart(plugin: Plugin, chart: JSONObject): List<Song> {
        val result = plugins.client.call(plugin.source, plugin.variables, "getTopListDetail", JSONArray().put(chart), plugin.id) as? JSONObject ?: error("音源未返回榜单详情")
        val items = result.optJSONArray("musicList") ?: JSONArray()
        return (0 until minOf(items.length(), 5000)).mapNotNull { items.optJSONObject(it)?.let(plugin::song) }
    }

    private fun openChart(plugin: Plugin, chart: JSONObject) {
        chartJob?.cancel()
        chartSelection = chart
        chartSongs = chartPreviews[chart.optString("id")] ?: emptyList()
        chartDetailStatus = if (chartSongs.isEmpty()) "正在加载榜单…" else ""
        chartJob = lifecycleScope.launch {
            try {
                chartSongs = fetchChart(plugin, chart)
                chartDetailStatus = if (chartSongs.isEmpty()) "榜单没有歌曲" else ""
                if (chartSongs.isNotEmpty()) chartPreviews = chartPreviews + (chart.optString("id") to chartSongs)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { chartDetailStatus = "加载榜单失败：${e.message}" }
        }
    }

    private fun leaveRadio() {
        ActiveQueue.radio = false
        radioJob?.cancel(); radioAdvanceJob?.cancel()
    }

    private fun playQueue(songs: List<Song>, index: Int = 0, radio: Boolean = false) {
        if (!radio) leaveRadio()
        ActiveQueue.radio = radio
        queue = songs
        playAt(index)
    }

    /* A single pick joins the listening context instead of replacing it: insert right after
       the current track and play it, so 上一首 is still what you were hearing. */
    private fun playSingle(song: Song) {
        leaveRadio()
        if (queue.isEmpty()) { playQueue(listOf(song)); return }
        val existing = queue.indexOfFirst { it.key == song.key }
        if (existing >= 0) { playAt(existing); return }
        val at = (queueIndex + 1).coerceIn(0, queue.size)
        queue = queue.toMutableList().also { it.add(at, song) }
        playAt(at)
    }

    private fun removeQueueItem(index: Int) {
        if (index !in queue.indices) return
        val autoplay = controller?.playWhenReady == true
        val removingCurrent = index == queueIndex
        queue = queue.toMutableList().also { it.removeAt(index) }
        when {
            queue.isEmpty() -> {
                autoSkipToken++
                resolving?.cancel(); streamSwap?.cancel(); lyricJob?.cancel(); lyricToken++
                recovery?.ready?.cancel(); recovery = null
                leaveRadio()
                queueIndex = -1; loadedKey = ""; ActiveQueue.source = null
                lyrics = emptyList(); lyricStatus = "暂无歌词"
                positionMs = 0; durationMs = 0; pendingSeekMs = null
                controller?.stop(); controller?.clearMediaItems()
            }
            index < queueIndex -> queueIndex -= 1
            /* Removing the song under the needle: the next one takes its place and plays,
               so the title on screen and the audio never disagree. */
            removingCurrent -> {
                queueIndex = index.coerceAtMost(queue.lastIndex)
                playAt(queueIndex, openPlayer = false, autoplay = autoplay)
            }
        }
        saveResume()
    }

    /** Fetch independently of the discovery page, so FM also works directly from the home card. */
    private suspend fun radioCandidates(): Pair<List<Song>, Boolean> {
        val installed = plugins.state.value.plugins
        val recommendation = Discovery.select(installed, recommendationPluginId) { it.recommendations }
        val chartSource = Discovery.select(installed, chartPluginId) { it.charts }
        val activeIds = installed.filter { it.enabled }.map { it.id }.toSet()
        val candidates = chartPreviews.values.flatten().filter { it.pluginId in activeIds }.toMutableList()
        var failed = false
        suspend fun request(plugin: Plugin, method: String, args: JSONArray = JSONArray()) =
            plugins.client.call(plugin.source, plugin.variables, method, args, plugin.id)
        val completed = kotlinx.coroutines.withTimeoutOrNull(12_000) {
            if (recommendation != null) try {
                val ownsCache = recommendationContext.startsWith("${recommendation.id}|")
                val tag = if (ownsCache) recommendationTag else JSONObject().put("id", "").put("title", "推荐")
                val sheets = if (ownsCache && recommendations.isNotEmpty()) recommendations else {
                    val result = request(recommendation, "getRecommendSheetsByTag", JSONArray().put(tag).put(1)) as? JSONObject
                    val data = result?.optJSONArray("data") ?: JSONArray()
                    (0 until minOf(data.length(), 100)).mapNotNull { data.optJSONObject(it) }
                }
                val sheet = sheets.filterNot { "${recommendation.id}:${it.optString("id")}" in radioSheets }.ifEmpty { sheets }.randomOrNull()
                if (sheet != null) {
                    val result = request(recommendation, "getMusicSheetInfo", JSONArray().put(sheet).put(1)) as? JSONObject
                    val songs = result?.optJSONArray("musicList") ?: error("歌单没有返回曲目")
                    candidates += (0 until minOf(songs.length(), 100)).mapNotNull { songs.optJSONObject(it)?.let(recommendation::song) }
                    radioSheets += "${recommendation.id}:${sheet.optString("id")}"; while (radioSheets.size > 12) radioSheets.removeAt(0)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (_: Exception) { failed = true }
            if (candidates.isEmpty() && chartSource != null) try {
                val chart = charts.firstOrNull() ?: (request(chartSource, "getTopLists") as? JSONArray)?.let { groups ->
                    (0 until minOf(groups.length(), 30)).firstNotNullOfOrNull { groups.optJSONObject(it)?.optJSONArray("data")?.optJSONObject(0) }
                }
                if (chart != null) candidates += fetchChart(chartSource, chart).take(100)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (_: Exception) { failed = true }
            true
        } ?: false
        val enabled = plugins.state.value.plugins.filter { it.enabled }.map { it.id }.toSet()
        return candidates.filter { it.pluginId in enabled } to (failed || !completed)
    }

    private fun startRadio(restart: Boolean = false, keepCurrent: Boolean = false) {
        if (!restart && ActiveQueue.radio && queue.isNotEmpty()) {
            playerOpen = true
            if (controller?.currentMediaItem != null && playbackState != Player.STATE_ENDED) controller?.play()
            else if (playbackState == Player.STATE_ENDED) step(1) else playAt(queueIndex)
            return
        }
        if (radioLoading) return
        radioAdvanceJob?.cancel()
        radioJob = lifecycleScope.launch {
            radioLoading = true
            radioStatus = "极光电台 正在开启…"
            try {
                val (candidates, failed) = radioCandidates()
                val local = Discovery.localSongs(library.state.value, plugins.state.value.plugins)
                val continuing = queue.getOrNull(queueIndex)?.takeIf {
                    keepCurrent && it.key == loadedKey && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED
                }
                val current = (continuing ?: queue.getOrNull(queueIndex)?.takeIf { ActiveQueue.radio })?.let(Discovery::radioIdentity)
                val songs = Discovery.radio(local, candidates, recent = ActiveQueue.radioRecent,
                    excluded = setOfNotNull(current)).ifEmpty {
                    Discovery.radio(local, candidates, recent = ActiveQueue.radioRecent)
                }
                if (songs.isEmpty()) {
                    radioStatus = if (failed) "发现内容暂不可用；可先收藏歌曲，再试一次" else "收藏歌曲，或添加提供推荐歌单／榜单的音源后开启极光电台"
                } else {
                    radioStatus = if (failed) "发现内容暂不可用，先听熟悉的旋律" else "熟悉的旋律，穿插一些新发现"
                    radioRetryAt = 0L
                    if (continuing != null) {
                        ActiveQueue.radio = true
                        val identity = Discovery.radioIdentity(continuing)
                        ActiveQueue.radioRecent = (ActiveQueue.radioRecent.filterNot { it == identity } + identity).takeLast(80)
                        queue = listOf(continuing) + songs.filterNot { Discovery.radioIdentity(it) == Discovery.radioIdentity(continuing) }
                        queueIndex = 0
                        saveResume()
                    } else playQueue(songs, radio = true)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (_: Exception) { radioStatus = "电台暂不可用，请稍后重试" }
            finally { radioLoading = false }
        }
    }

    private fun ensureRadioTail(force: Boolean = false): Job? {
        if (!ActiveQueue.radio || queue.isEmpty()) return null
        if (radioJob?.isActive == true) return radioJob
        if (!force && (queue.size - queueIndex > 5 || android.os.SystemClock.elapsedRealtime() < radioRetryAt)) return null
        radioJob = lifecycleScope.launch {
            radioLoading = true
            try {
                val (candidates, failed) = radioCandidates()
                if (!ActiveQueue.radio) return@launch
                val local = Discovery.localSongs(library.state.value, plugins.state.value.plugins)
                val pending = queue.drop(queueIndex.coerceAtLeast(0)).map(Discovery::radioIdentity).toSet()
                val added = Discovery.radio(local, candidates, recent = ActiveQueue.radioRecent, excluded = pending, limit = 30)
                if (added.isNotEmpty()) {
                    val drop = (queueIndex - 10).coerceAtLeast(0)
                    queue = queue.drop(drop) + added; queueIndex -= drop
                    radioStatus = if (failed) "发现内容暂不可用，继续收听熟悉的旋律" else "熟悉的旋律，穿插一些新发现"
                    saveResume()
                } else radioStatus = "暂时没有更多曲目，可添加音源或收藏后再试"
                radioRetryAt = android.os.SystemClock.elapsedRealtime() + 30_000
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (_: Exception) { radioStatus = "续曲暂不可用，已保留当前队列"; radioRetryAt = android.os.SystemClock.elapsedRealtime() + 30_000 }
            finally { radioLoading = false }
        }
        return radioJob
    }

    @Composable private fun RecommendationShelf(plugin: Plugin, up: FocusRequester, entry: FocusAnchor, firstCard: FocusAnchor, below: FocusAnchor,
                                                cardFocus: (JSONObject) -> FocusRequester, edges: (Int) -> Modifier, rowState: LazyListState,
                                                onMenu: (JSONObject, FocusRequester) -> Unit, modifier: Modifier = Modifier, onOpen: (JSONObject) -> Unit) {
        val c = LocalPrismColors.current
        var categoriesOpen by remember { mutableStateOf(false) }
        val categoryFocus = remember { FocusRequester() }
        val scope = rememberCoroutineScope()
        fun closeCategories() {
            categoriesOpen = false
            scope.launch { withFrameNanos { }; categoryFocus.requestFocus() }
        }
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrismShelfHeader("歌单推荐", null, "", {})
            val scenes = Discovery.scenes(recommendationTags)
            val sceneIds = scenes.map { it.second.optString("id") }.toSet()
            val choices = scenes + recommendationTags.filterNot { it.optString("id") in sceneIds }.map { it.optString("title") to it }
            val tailChips = choices.take(3)
            val moreChip = choices.size > 3
            // Keep Right inside the category strip at its last item.
            val stopRight = Modifier.onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
                item { PrismAction(if (recommendationTag.optString("id").isEmpty()) "✓ 推荐" else "推荐", {
                    recommendationTag = JSONObject().put("id", "").put("title", "推荐")
                }, Modifier.focusAnchor(entry).focusProperties {
                    this.up = up
                    if (recommendations.isNotEmpty() && firstCard.attached) down = firstCard.requester
                    else if (below.attached) down = below.requester
                }.then(if (tailChips.isEmpty() && !moreChip) stopRight else Modifier)) }
                itemsIndexed(tailChips) { index, (label, tag) ->
                    PrismAction("${if (recommendationTag.optString("id") == tag.optString("id")) "✓ " else ""}$label", {
                        recommendationTag = tag
                    }, Modifier.focusProperties {
                        this.up = up
                        if (recommendations.isNotEmpty() && firstCard.attached) down = firstCard.requester
                        else if (below.attached) down = below.requester
                    }.then(if (!moreChip && index == tailChips.lastIndex) stopRight else Modifier))
                }
                if (moreChip) item {
                    Box {
                        val selected = choices.drop(3).firstOrNull { it.second.optString("id") == recommendationTag.optString("id") }
                        PrismAction(selected?.let { "${it.first} ▾" } ?: "全部分类 ▾", { categoriesOpen = true },
                            Modifier.focusRequester(categoryFocus).focusProperties {
                                this.up = up
                                if (recommendations.isNotEmpty() && firstCard.attached) down = firstCard.requester
                                else if (below.attached) down = below.requester
                            }.then(stopRight))
                        if (categoriesOpen) Popup(onDismissRequest = { closeCategories() }, properties = PopupProperties(focusable = true)) {
                            val first = remember { FocusRequester() }
                            LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; first.requestFocus() }
                            Column(Modifier.width(250.dp).heightIn(max = 230.dp).background(c.panel)
                                .border(1.dp, c.line).verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                choices.forEachIndexed { index, (label, tag) ->
                                    PrismAction(label, { recommendationTag = tag; closeCategories() },
                                        if (index == 0) Modifier.focusRequester(first) else Modifier)
                                }
                            }
                        }
                    }
                }
            }
            if (recommendations.isEmpty()) {
                Text(recommendationStatus.ifBlank { "该分类暂无歌单" }, color = c.muted, fontSize = 12.sp)
            } else {
                LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
                    itemsIndexed(recommendations) { index, sheet ->
                        val focus = cardFocus(sheet)
                        PrismSheetCard(sheet.optString("title").ifBlank { "推荐歌单" },
                            sheet.optString("artwork").ifBlank { sheet.optString("coverImg") }, plugin.displayName,
                            Modifier.focusRequester(focus)
                                .then(if (index == 0) Modifier.focusAnchor(firstCard) else Modifier)
                                .focusProperties { this.up = if (entry.attached) entry.requester else up }
                                .then(edges(index)),
                            onLongPress = { onMenu(sheet, focus) }) { onOpen(sheet) }
                    }
                    if (recommendationMore) item {
                        PrismAction(if (recommendationLoading) "加载中" else "更多歌单", {
                            if (!recommendationLoading) lifecycleScope.launch { loadRecommendations(plugin, append = true) }
                        }, Modifier.focusProperties { this.up = if (entry.attached) entry.requester else up })
                    }
                }
                if (recommendationStatus.isNotBlank()) Text(recommendationStatus, color = c.muted, fontSize = 12.sp)
            }
        }
    }

    /* open is hoisted to the caller: focus lives on the whole row and OK opens the sheet
       there. passive skips the focus ring and pointer events — it only shows the value. */
    @Composable private fun SourcePicker(
        options: List<Plugin>, selected: String, modifier: Modifier = Modifier,
        open: Boolean = false, onOpenChange: (Boolean) -> Unit = {},
        passive: Boolean = false, onSelect: (Plugin) -> Unit,
    ) {
        val c = LocalPrismColors.current
        val current = options.firstOrNull { it.id == selected } ?: options.firstOrNull()
        Box {
            /* 选择音源只影响对应的推荐或榜单。 */
            Row(modifier.height(34.dp).then(if (passive) Modifier else Modifier.auraFocus(RoundedCornerShape(7.dp), 1f))
                .clip(RoundedCornerShape(7.dp))
                .border(1.dp, c.accent.copy(alpha = .24f), RoundedCornerShape(7.dp))
                .background(c.accent.copy(alpha = .055f))
                .then(if (passive) Modifier.focusProperties { canFocus = false } else Modifier.clickable { onOpenChange(true) })
                .padding(start = 11.dp, end = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(current?.displayName ?: "暂无支持此能力的音源", color = c.foreground.copy(alpha = .9f), fontSize = 12.sp, maxLines = 1)
                Glyph("chev", Modifier.size(10.dp), lerp(c.accent, c.glow, .5f))
            }
            if (open) ChoicePopup(options, options.indexOfFirst { it.id == current?.id },
                label = { it.displayName }, onDismiss = { onOpenChange(false) },
                onSelect = { onSelect(it); onOpenChange(false) },
                icon = { PlatformIcon(it.platform, Modifier.size(22.dp)) })
        }
    }

    @Composable private fun <T> ChoicePopup(
        options: List<T>, selectedIndex: Int, label: (T) -> String,
        onDismiss: () -> Unit, onSelect: (T) -> Unit, icon: @Composable (T) -> Unit,
    ) {
        val c = LocalPrismColors.current
        Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, with(LocalDensity.current) { 38.dp.roundToPx() }),
            onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
            val shape = RoundedCornerShape(12.dp)
            Column(Modifier.width(268.dp).heightIn(max = 280.dp).clip(shape).background(c.panel)
                .border(1.dp, c.line, shape).padding(6.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (options.isEmpty()) SettingsButton("暂无可选项 · 返回", onDismiss, Modifier.fillMaxWidth().focusRequester(focus))
                options.forEachIndexed { index, option ->
                    val chosen = index == selectedIndex
                    val rowShape = RoundedCornerShape(8.dp)
                    Row(Modifier.fillMaxWidth().heightIn(min = 42.dp)
                        .then(if (index == selectedIndex.coerceAtLeast(0)) Modifier.focusRequester(focus) else Modifier)
                        .auraFocus(rowShape, 1f).clip(rowShape)
                        .background(if (chosen) c.accent.copy(alpha = .12f) else Color.Transparent)
                        .selectable(chosen, role = Role.RadioButton, onClick = { onSelect(option) })
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        icon(option)
                        Text(label(option), Modifier.weight(1f), color = c.foreground,
                            fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (chosen) Glyph("check", Modifier.size(14.dp), c.accent)
                    }
                }
            }
        }
    }

    private fun toggleFavorite(song: Song?, onFailure: (String) -> Unit = { status = it }) {
        if (song == null) return
        if (song.pluginId == "local" && !song.data.optBoolean("persisted")) onFailure("本地文件未获得长期授权，不能保存")
        else lifecycleScope.launch {
            try { library.toggleFavorite(song) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { onFailure(e.message ?: "保存失败") }
        }
    }

    private fun toggleSubject(pluginId: String, kind: String, item: JSONObject, sourceName: String, onFailure: (String) -> Unit = { status = it }) {
        lifecycleScope.launch {
            try {
                val keys = plugins.state.value.plugins.firstOrNull { it.id == pluginId }?.metadata?.let(::primaryKeys) ?: listOf("id")
                val key = SavedSubject(pluginId, kind, item, sourceName, keys).key
                val wasSaved = library.state.value.subjects.any { it.key == key }
                library.toggleSubject(SavedSubject(pluginId, kind, item, sourceName, keys))
                status = if (wasSaved) "已取消收藏" else "已收藏"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { onFailure(e.message ?: "保存失败") }
        }
    }

    private fun playAt(index: Int, openPlayer: Boolean = true, autoplay: Boolean = true, startAt: Long = 0L, autoSkip: Boolean = false, sourceSong: Song? = null) {
        val song = queue.getOrNull(index) ?: return
        val player = controller ?: run { reportPlayback("播放器未连接"); return }
        /* The loaded track needs no second pass: the audio is mid-phrase and its title is on
           screen, so re-picking it only moves the needle within the queue. */
        if (sourceSong == null && !autoSkip && startAt <= 0L && song.key == loadedKey && playbackState != Player.STATE_ENDED && playbackState != Player.STATE_IDLE) {
            resolving?.cancel()
            failedSongs.clear(); autoSkipToken++
            queueIndex = index
            if (openPlayer) playerOpen = true
            if (autoplay && !player.playWhenReady) player.play()
            reportPlayback(if (player.playWhenReady) "正在播放：${song.title}" else "已暂停：${song.title}")
            if (autoplay) lifecycleScope.launch { runCatching { library.record(song) } }
            return
        }
        /* A manual pick starts a fresh pass; an automatic skip is part of the current one. */
        if (!autoSkip) { failedSongs.clear(); autoSkipToken++ }
        val previousIndex = queueIndex
        val previousSong = queue.getOrNull(previousIndex)
        val previousSource = ActiveQueue.source
        /* The queue often changes before this call (search results, playlists), so the old
           position alone would point at a different song afterwards. Restore by identity. */
        val restorePrevious: () -> Unit = {
            val at = previousSong?.let { old -> queue.indexOfFirst { it.key == old.key } } ?: -1
            if (at >= 0) {
                queueIndex = at
                ActiveQueue.source = previousSource
                /* 回退到旧歌时校准也要回到旧歌的，不能留着目标歌曲的校准串用。 */
                lyricCalibrationMs = LyricCalibration.load(this, playbackSong()?.key ?: queue[at].key)
            }
        }
        if (openPlayer) playerOpen = true
        streamFormat = ""
        playingQuality = ""
        recovery?.ready?.cancel()
        recovery = null
        ActiveQueue.source = null
        resolving?.cancel()
        streamSwap?.cancel()
        remintKey = ""
        lyricJob?.cancel()
        lyricToken++
        loadedKey = ""
        pendingSeekMs = null
        player.stop()
        player.clearMediaItems()
        val previousLyrics = lyrics
        val previousLyricStatus = lyricStatus
        lyrics = emptyList()
        lyricStatus = "歌词加载中…"
        queueIndex = index
        /* The lyric clock starts at this song's zero; the previous song's tail must not be
           what the first lyric frames are measured against. */
        positionMs = startAt.coerceAtLeast(0L)
        durationMs = (song.durationSeconds * 1000).toLong().coerceAtLeast(0L)
        /* 校准跟着歌走，不跟着队列走；同名不同歌的 key 不同，不会继承上一首的校准。 */
        lyricCalibrationMs = LyricCalibration.load(this, song.key)
        if (song.pluginId == "local") {
            try {
                val uri = Uri.parse(song.data.optString("uri"))
                require(uri.scheme == "content") { "本地文件地址无效" }
                val metadata = MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).build()
                player.setMediaItem(MediaItem.Builder().setUri(uri).setMediaMetadata(metadata).build())
                player.prepare()
                loadedKey = song.key
                if (startAt > 0) { player.seekTo(startAt); pendingSeekMs = startAt; positionMs = startAt }
                if (autoplay) player.play()
                lyricStatus = "本地文件暂无歌词"
                reportPlayback(when {
                    !autoplay -> "上次听到 ${clock(startAt)}，按播放继续"
                    song.data.optBoolean("persisted") -> "正在播放：${song.title}"
                    else -> "正在播放；文件授权未能长期保存"
                })
                if (autoplay && song.data.optBoolean("persisted")) lifecycleScope.launch { runCatching { library.record(song) } }
            } catch (e: Exception) {
                pendingSeekMs = null
                /* The file may have prepared and then failed; the next track is the one on screen. */
                loadedKey = ""
                advanceAfterFailure(song, "本地文件播放失败", "本地文件播放失败：${e.message}", autoplay) {
                    if (!autoSkip) {
                        lyrics = previousLyrics; lyricStatus = previousLyricStatus
                        restorePrevious()
                    }
                }
            }
            return
        }
        val streamSong = sourceSong ?: song
        if (autoSourceFallback && autoplay) {
            if (player.playWhenReady) player.pause()
            loadedKey = ""
            positionMs = startAt
            durationMs = (song.durationSeconds * 1000).toLong().coerceAtLeast(0L)
            val pass = PlaybackRecovery(SourceRecovery(plugins, song, streamSong, tierFor(streamSong))) { hasAcceptedStream ->
                advanceAfterFailure(song, "播放失败", "播放失败：${song.title}，未找到可播放的同版本", autoplay) {
                    if (!hasAcceptedStream && !autoSkip) {
                        /* Lyrics travel with the title: they go back only when the display does. */
                        lyrics = previousLyrics; lyricStatus = previousLyricStatus
                        restorePrevious()
                    }
                }
            }
            recovery = pass
            continueRecovery(pass, startAt)
            return
        }
        val plugin = plugins.state.value.plugins.firstOrNull { it.id == streamSong.pluginId }
        if (plugin == null) {
            lyrics = previousLyrics
            lyricStatus = previousLyricStatus
            advanceAfterFailure(song, "所需插件已删除", "所需插件已删除 · ${song.title}", autoplay) { if (!autoSkip) restorePrevious() }
            return
        }
        /* Resolving can take a second or longer. Leaving the previous track audible under the
           new title made the player look like it was playing the song you just picked. */
        if (autoplay && player.playWhenReady) player.pause()
        resolving = lifecycleScope.launch {
            reportPlayback("正在解析：${song.title}")
            try {
                val attempts = qualityAttempts(qualityOptions(plugin, streamSong), tierFor(streamSong))
                var resolved: JSONObject? = null
                var lastError: Exception? = null
                var resolvedTier = ""
                val unsupported = mutableSetOf<String>()
                for (tier in attempts) {
                    var failure: Exception? = null
                    val candidate = try {
                        plugins.mediaSource(plugin, streamSong, tier)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e.message?.contains("超时") == true || e.message?.contains("不支持的模块") == true) throw e
                        failure = e
                        lastError = e
                        null
                    }
                    val candidateUri = Uri.parse(candidate?.optString("url") ?: "")
                    if (candidateUri.scheme in listOf("http", "https") && !candidateUri.host.isNullOrBlank()) {
                        resolved = candidate
                        resolvedTier = tier
                        break
                    }
                    /* A thrown error may be one bad moment on the network; an answer with no URL
                       is the plugin saying it does not serve this tier. */
                    if (failure == null) unsupported += tier
                }
                val result = resolved ?: throw IllegalStateException(lastError?.message ?: "插件未返回有效播放地址")
                val actualTier = result.optString("quality").ifBlank { resolvedTier }
                playingQuality = actualTier
                if (unsupported.isNotEmpty()) unsupportedTiers[streamSong.key] = unsupportedTiers[streamSong.key].orEmpty() + unsupported
                player.setMediaItem(audioMediaItem(song, result))
                player.prepare()
                loadedKey = song.key
                if (startAt > 0) { player.seekTo(startAt); pendingSeekMs = startAt; positionMs = startAt }
                if (autoplay) {
                    player.play(); failedSongs.clear(); reportPlayback(if (attempts.firstOrNull() != resolvedTier || actualTier != resolvedTier) "正在播放：${song.title} · 已回退至 ${qualityText(actualTier)}" else "正在播放：${song.title}")
                    lifecycleScope.launch { runCatching { library.record(song) } }
                } else reportPlayback("上次听到 ${clock(startAt)}，按播放继续")
                ActiveQueue.source = PlayingSource(song.key, streamSong)
                loadLyrics(plugin, streamSong)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                pendingSeekMs = null
                /* The old track is paused, not replaced. If no skip follows, put the needle back
                   where it was so the title on screen and the audio agree. */
                advanceAfterFailure(song, "解析失败", "解析失败：${song.title} · ${e.message ?: "插件没有返回播放地址"}", autoplay) {
                    if (!autoSkip) {
                        lyrics = previousLyrics; lyricStatus = previousLyricStatus
                        restorePrevious()
                    }
                }
            }
        }
    }

    private fun playbackSong(): Song? {
        val song = queue.getOrNull(queueIndex) ?: return null
        return ActiveQueue.source?.takeIf { it.originalKey == song.key }?.song ?: song
    }

    // Keep the current stream and lyric state until the replacement URL is available.
    private fun switchQuality(tier: String) {
        val song = queue.getOrNull(queueIndex) ?: return
        val player = controller ?: return
        val streamSong = playbackSong() ?: song
        if (song.pluginId == "local" || tier.isBlank()) return
        if (tier == playingQuality.ifBlank { tierFor(streamSong) }) return
        val plugin = plugins.state.value.plugins.firstOrNull { it.id == streamSong.pluginId } ?: return
        val at = player.currentPosition.coerceAtLeast(0L).takeIf { it > 0L } ?: positionMs
        val wasPlaying = player.playWhenReady
        streamSwap?.cancel()
        streamSwap = lifecycleScope.launch {
            val source = try {
                plugins.mediaSource(plugin, streamSong, tier, refresh = true)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (e: Exception) {
                reportPlayback("音质切换失败：${e.message ?: "音源没有应答"}")
                return@launch
            }
            val uri = Uri.parse(source?.optString("url").orEmpty())
            if (source == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
                unsupportedTiers[song.key] = unsupportedTiers[song.key].orEmpty() + tier
                reportPlayback("音质切换失败：该音源不提供${qualityText(tier)}")
                return@launch
            }
            val actual = source.optString("quality").ifBlank { tier }
            playingQuality = actual
            if (actual != tier) unsupportedTiers[song.key] = unsupportedTiers[song.key].orEmpty() + tier
            songTier[song.key] = actual
            songTier[streamSong.key] = actual
            player.setMediaItem(audioMediaItem(song, source))
            player.prepare()
            if (at > 0L) { player.seekTo(at); positionMs = at }
            if (wasPlaying) player.play()
            ActiveQueue.source = PlayingSource(song.key, streamSong)
            failedSongs.clear()
        }
    }

    private fun setAutoSourceFallback(enabled: Boolean, persist: Boolean = true) {
        autoSourceFallback = enabled
        if (persist) getSharedPreferences("playback", MODE_PRIVATE).edit().putBoolean(SourceRecovery.PREFERENCE, enabled).apply()
        if (!enabled) {
            val pending = recovery?.takeIf { resolving?.isActive == true }
            recovery?.ready?.cancel()
            recovery = null
            if (pending != null) {
                resolving?.cancel()
                playAt(queueIndex, openPlayer = false, startAt = pending.position)
            }
        }
    }

    private fun pauseRecoveryIfPending(): Boolean {
        val pass = recovery?.takeIf { resolving?.isActive == true } ?: return false
        recovery = null
        pass.ready?.cancel(); resolving?.cancel()
        controller?.pause(); controller?.stop()
        loadedKey = ""; pendingSeekMs = null; positionMs = pass.position
        reportPlayback("已暂停：${pass.sources.original.title}")
        return true
    }

    private fun togglePlayback() {
        if (pauseRecoveryIfPending()) return
        val player = controller ?: return
        if (player.playWhenReady) player.pause() else resumePlayback()
    }

    private fun resumePlayback() {
        if (recovery != null && resolving?.isActive == true) return
        val player = controller ?: return
        val needsResolution = player.currentMediaItem == null || player.playbackState == Player.STATE_IDLE || player.playerError != null
        if (needsResolution && queue.isNotEmpty()) {
            playAt(queueIndex, openPlayer = false, startAt = positionMs, sourceSong = playbackSong())
        } else player.play()
    }

    private fun seekPlayback(target: Long) {
        val at = target.coerceAtLeast(0L)
        val pass = recovery?.takeIf { resolving?.isActive == true }
        if (pass != null) {
            pass.position = at
            if (pass.ready != null) { pendingSeekMs = at; controller?.seekTo(at) }
        } else controller?.seekTo(at)
        positionMs = at
    }

    private fun continueRecovery(pass: PlaybackRecovery, startAt: Long) {
        resolving?.cancel()
        pass.position = startAt
        resolving = lifecycleScope.launch {
            val player = controller ?: return@launch
            val original = pass.sources.original
            fun current() = recovery === pass && autoSourceFallback && queue.getOrNull(queueIndex)?.key == original.key
            val succeeded = withTimeoutOrNull(SourceRecovery.BUDGET_MS) {
                while (current()) {
                    reportPlayback(if (pass.sources.current == null) "正在解析：${original.title}" else "正在尝试其他音质或音源：${original.title}")
                    val stream = pass.sources.next() ?: return@withTimeoutOrNull false
                    if (!current()) return@withTimeoutOrNull false
                    val ready = CompletableDeferred<Unit>()
                    pass.ready = ready
                    pass.mediaId = java.util.UUID.randomUUID().toString()
                    var started = false
                    try {
                        player.setMediaItem(audioMediaItem(original, stream.source, pass.mediaId))
                        pendingSeekMs = pass.position.takeIf { it > 0 }
                        if (pass.position > 0) player.seekTo(pass.position)
                        player.prepare(); player.play()
                        started = withTimeoutOrNull(SourceRecovery.PREPARE_MS) { ready.await(); true } == true && player.playerError == null
                        if (started && original.durationSeconds > 0 && player.duration > 0L) {
                            started = recordingDurationMatches(original, player.duration / 1000.0)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                      catch (_: Exception) { /* The next tier belongs to this same finite pass. */ }
                    finally { if (pass.ready === ready) pass.ready = null }
                    /* Abandoned mid-prepare: stop the orphan here. Letting it run leaves the
                       player on one track while the display names another. */
                    if (!current()) {
                        if (player.currentMediaItem?.mediaId == pass.mediaId) player.stop()
                        return@withTimeoutOrNull false
                    }
                    if (started) {
                        pendingSeekMs = null
                        pass.hasAcceptedStream = true
                        loadedKey = original.key
                        ActiveQueue.source = PlayingSource(original.key, stream.song)
                        playingQuality = stream.source.optString("quality").ifBlank { stream.quality }
                        pass.sources.unsupported.forEach { (key, tiers) -> unsupportedTiers[key] = unsupportedTiers[key].orEmpty() + tiers }
                        lyricCalibrationMs = LyricCalibration.load(this@MainActivity, stream.song.key)
                        loadLyrics(stream.plugin, stream.song)
                        if (player.isPlaying) listener.onIsPlayingChanged(true)
                        reportPlayback("正在播放：${original.title}" + if (stream.song.pluginId != original.pluginId) " · 已由${original.sourceLabel}切到${stream.song.sourceLabel}" else "")
                        lifecycleScope.launch { runCatching { library.record(original) } }
                        return@withTimeoutOrNull true
                    }
                    pendingSeekMs = null
                    player.stop()
                    pass.sources.failed()
                }
                false
            } == true
            if (!succeeded && current()) {
                recovery = null; loadedKey = ""; pendingSeekMs = null
                player.stop()
                pass.onFailure(pass.hasAcceptedStream)
            } else if (!succeeded && controller?.currentMediaItem?.mediaId == pass.mediaId) {
                /* The pass was abandoned (queue moved on) but its last stream is still in
                   the player; silence it instead of leaking it into the new track's display. */
                player.stop(); pendingSeekMs = null
            } else if (succeeded && current() && player.playbackState == Player.STATE_ENDED) {
                // A short stream or a seek near its end can finish before this job returns.
                resolving = null
                listener.onPlaybackStateChanged(Player.STATE_ENDED)
            }
        }
    }

    /* A source that cannot resolve one track used to leave the old song paused and the queue
       stuck there. Skip to the next track that has not failed yet in this pass; once every
       track has failed, stop instead of cycling through the same failures forever. */
    private fun advanceAfterFailure(song: Song, label: String, detail: String, autoplay: Boolean = true, restore: () -> Unit) {
        if (!autoplay) { reportPlayback(detail); restore(); return }
        failedSongs += song.key
        val next = (if (ActiveQueue.radio) (queueIndex + 1 until queue.size).toList() else
            (1 until queue.size).map { offset -> (queueIndex + offset) % queue.size })
            .firstOrNull { index -> queue[index].key !in failedSongs }
        if (next == null || ActiveQueue.radio && failedSongs.size >= 5) {
            if (ActiveQueue.radio) { controller?.pause(); radioJob?.cancel(); radioRetryAt = android.os.SystemClock.elapsedRealtime() + 30_000 }
            failedSongs.clear()
            reportPlayback(detail)
            restore()
            return
        }
        /* queueIndex stays on the failed song until the skip fires, so the pass continues
           from here instead of jumping back to an already-failed neighbour. */
        reportPlayback("$label，正在尝试下一首：${song.title}")
        val token = ++autoSkipToken
        lifecycleScope.launch {
            delay(600)
            /* A manual pick while we wait cancels this skip. */
            if (token == autoSkipToken) playAt(next, openPlayer = false, autoSkip = true)
        }
    }

    private fun loadLyrics(plugin: Plugin, song: Song) {
        lyricJob?.cancel()
        val token = ++lyricToken
        lyricJob = lifecycleScope.launch {
            try {
                val supplied = song.data.optString("rawLrc")
                val result = if (supplied.isNotBlank()) JSONObject().put("rawLrc", supplied)
                else if (!plugin.capabilities.has("lyric") && song.data.optString("lrc").isBlank()) null
                else plugins.client.call(plugin.source, plugin.variables, "getLyric", JSONArray().put(song.data), plugin.id) as? JSONObject
                val text = result?.optString("rawLrc").orEmpty()
                val translated = result?.optString("translation").orEmpty()
                val parsed = withContext(Dispatchers.Default) { Lyrics.parse(text, translated) }
                if (token != lyricToken) return@launch
                lyrics = parsed
                lyricStatus = when {
                    parsed.isNotEmpty() -> ""
                    text.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("<") -> "暂无可用歌词"
                    text.isNotBlank() -> text.take(3000)
                    else -> "暂无歌词"
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { if (token == lyricToken) { lyrics = emptyList(); lyricStatus = "暂无歌词" } }
        }
    }

    /* Import goes through importMusicSheet/importMusicItem with no platform special-casing:
       a recognized link goes to its source; otherwise every source declaring import capability
       is asked in turn until one returns a song. */
    private fun importCandidates(url: String): List<Plugin> {
        val capable = plugins.state.value.plugins.filter { it.enabled && (it.metadata.optBoolean("importSheet") || it.metadata.optBoolean("importItem")) }
        val target = MusicPlatform.identify(url)
        return capable.sortedBy { if (it.platform == target) 0 else 1 }
    }

    /* 标准 hints：{ importMusicSheet: [...], importMusicItem: [...] }，展示当前选中音源的那一组。 */
    private fun importHints(plugin: Plugin): List<String> {
        val hints = plugin.metadata.optJSONObject("hints") ?: return emptyList()
        return listOf("importMusicSheet", "importMusicItem")
            .mapNotNull { key -> hints.optJSONArray(key) }
            .flatMap { array -> (0 until array.length()).mapNotNull { i -> array.optString(i).takeIf { it.isNotBlank() } } }
            .distinct()
    }

    /* 标准协议的 importMusicSheet 只返回歌曲数组，没有歌单标题，所以留空时按音源名自动命名。 */
    private fun autoPlaylistName(plugin: Plugin, existing: Set<String>): String {
        val base = plugin.displayName.ifBlank { plugin.name }.take(56)
        var candidate = base
        var index = 2
        while (candidate in existing) { candidate = "$base $index"; index++ }
        return candidate
    }

    /* 手机分享出来的是整段文本（「分享歌单: 名字 https://… (@网易云音乐)」），先抽出
       第一个链接再交给插件：音源识别和插件拿到的都是干净的 URL，而不是分享文案。 */
    private fun shareUrlOf(input: String): String {
        val match = Regex("""https?://[^\s"'<>，。；：！？、（）【】]+""").find(input) ?: return input.trim()
        return match.value.trimEnd(')', '】', ']', '}', ',', '.', ';', ':', '!', '?')
    }

    private fun importPlaylist(name: String, input: String, target: Plugin? = null) {
        val url = shareUrlOf(input)
        if (url.isBlank()) { status = "请填写歌单或单曲链接"; return }
        val candidates = target?.let { listOf(it) } ?: importCandidates(url)
        if (candidates.isEmpty()) {
            status = "没有音源支持链接导入：请先启用支持歌单或单曲导入的音源"
            return
        }
        lifecycleScope.launch {
            busy = true
            val reasons = mutableListOf<String>()
            try {
                for ((position, plugin) in candidates.withIndex()) {
                    status = "正在用 ${plugin.displayName} 导入…"
                    var sheet: JSONArray? = null
                    var failure = ""
                    val methods = buildList {
                        if (plugin.metadata.optBoolean("importSheet")) add("importMusicSheet")
                        if (plugin.metadata.optBoolean("importItem")) add("importMusicItem")
                    }
                    /* 点名的音源给足预算；自动模式下靠后的只试 20s。 */
                    val budget = if (position == 0) 180_000L else 20_000L
                    for (method in methods) {
                        try {
                            val imported = plugins.client.call(plugin.source, plugin.variables, method, JSONArray().put(url), plugin.id, budget)
                            val tracks = if (method == "importMusicItem") (imported as? JSONObject)?.let { JSONArray().put(it) } else imported as? JSONArray
                            if (tracks != null && tracks.length() > 0) { sheet = tracks; break }
                            failure = "插件没有返回歌曲"
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                          catch (e: Exception) { failure = e.message ?: "没有读到歌曲" }
                    }
                    val result = sheet
                    if (result == null) { reasons += "${plugin.displayName}：$failure"; continue }
                    val installed = plugins.state.value.plugins
                    val songs = (0 until result.length()).mapNotNull { i -> result.optJSONObject(i)?.let { data ->
                        val owner = installed.firstOrNull { it.name.equals(data.optString("platform"), ignoreCase = true) } ?: plugin
                        owner.song(data)
                    } }
                    if (songs.isEmpty()) { reasons += "${plugin.displayName}：歌单里没有可导入的曲目"; continue }
                    /* 超出上限的部分不能悄悄丢掉：导入前 5000 首，并把真实数量说清楚。 */
                    val kept = songs.take(5000)
                    val source = MusicPlatform.identify(url, kept.first().data.optString("source"), kept.first().data.optString("platform"), plugin.displayName)
                    val title = name.trim().ifBlank { autoPlaylistName(plugin, library.state.value.playlists.map { it.name }.toSet()) }
                    library.create(title, kept, source.id)
                    /* create appends, so the list just written is the last one. Handing its id to 我的
                       is what makes an import findable instead of a number in a status line. */
                    playlistToShow = library.state.value.playlists.lastOrNull()?.id
                    status = "已导入「$title」${kept.size} 首歌曲 · 我的 → 歌单" +
                        if (songs.size > kept.size) "（歌单共 ${songs.size} 首，超出 5000 首上限的部分未导入）" else ""
                    return@launch
                }
                val detail = reasons.take(2).joinToString("；").ifBlank { "音源没有返回歌曲" }
                status = "链接导入失败：$detail" + when {
                    target != null -> "；可以换个音源再试"
                    reasons.size > 2 -> "（共尝试 ${reasons.size} 个音源）"
                    else -> ""
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { status = "链接导入失败：${e.message}" }
            finally { busy = false }
        }
    }

    /* Written every 15 seconds and on the way out: a TV is usually stopped, not closed, so
       onStop is the one signal that reliably precedes the process going away. */
    private fun saveResume() {
        if (queue.isEmpty()) return
        val position = recovery?.takeIf { resolving?.isActive == true }?.position
            ?: controller?.currentPosition?.coerceAtLeast(0L) ?: positionMs
        resumeStore.write(queue, queueIndex, position, ActiveQueue.radio, ActiveQueue.radioRecent)
    }

    private fun step(direction: Int) {
        if (queue.isEmpty()) return
        if (ActiveQueue.radio) {
            if (direction < 0) { playAt((queueIndex - 1).coerceAtLeast(0), openPlayer = false); return }
            if (queueIndex < queue.lastIndex) { playAt(queueIndex + 1, openPlayer = false); return }
            if (radioAdvanceJob?.isActive == true) return
            val current = queue.getOrNull(queueIndex)?.key
            radioAdvanceJob = lifecycleScope.launch {
                reportPlayback("极光电台 正在续曲…")
                ensureRadioTail(force = true)?.join()
                if (ActiveQueue.radio && queue.getOrNull(queueIndex)?.key == current) {
                    if (queueIndex < queue.lastIndex) playAt(queueIndex + 1, openPlayer = false)
                    else reportPlayback("电台暂时没有更多曲目，请稍后重试或换一批")
                }
            }
            return
        }
        playAt(nextIndex(direction), openPlayer = false)
    }

    private fun nextIndex(direction: Int): Int =
        if (playMode == PlaybackMode.SHUFFLE && queue.size > 1 && direction > 0) {
            val n = kotlin.random.Random.nextInt(queue.size - 1); if (n >= queueIndex) n + 1 else n
        } else (queueIndex + direction + queue.size) % queue.size

    private fun readPlaybackMode(): PlaybackMode {
        val prefs = getSharedPreferences("playback", MODE_PRIVATE)
        val stored = prefs.all["mode"] as? String
        val mode = PlaybackMode.fromId(stored) ?: PlaybackMode.SEQUENTIAL
        if (stored != mode.id) prefs.edit().putString("mode", mode.id).apply()
        return mode
    }

    private fun updatePreference(key: String, value: String) {
        when (key) {
            "mode" -> {
                val mode = PlaybackMode.fromId(value) ?: error("未知播放模式")
                leaveRadio()
                playMode = mode
                getSharedPreferences("playback", MODE_PRIVATE).edit().putString("mode", mode.id).apply()
            }
            "quality" -> {
                require(value in setOf("low", "standard", "high", "super", "flac", "flac24bit", "hires", "dolby", "atmos", "atmos_plus", "64k", "128k", "192k", "320k", "mgg")) { "未知音质" }
                quality = value
                getSharedPreferences("playback", MODE_PRIVATE).edit().putString("quality", value).apply()
            }
            "autoSourceFallback" -> setAutoSourceFallback(value.toBooleanStrictOrNull() ?: error("开关值无效"))
            "theme" -> {
                require(value in setOf("gold", "porcelain", "mono")) { "未知主题" }
                theme = value
                getSharedPreferences("appearance", MODE_PRIVATE).edit().putString("theme", value).apply()
            }
            "miniLyrics", "reduceMotion", "subLyrics" -> {
                val enabled = value.toBooleanStrictOrNull() ?: error("开关值无效")
                val preference = when (key) {
                    "miniLyrics" -> { showMiniLyrics = enabled; "mini_lyrics" }
                    "reduceMotion" -> { reduceMotion = enabled; "reduce_motion" }
                    else -> { subLyrics = enabled; "sub_lyrics" }
                }
                getSharedPreferences("appearance", MODE_PRIVATE).edit().putBoolean(preference, enabled).apply()
            }
            "lyricFontLevel" -> {
                require(value in lyricFontLevels) { "未知歌词字号" }
                lyricFontLevel = value
                getSharedPreferences("appearance", MODE_PRIVATE).edit().putString("lyric_font_level", value).apply()
            }
            "pluginWorkerLimit" -> {
                val limit = value.toIntOrNull() ?: error("并发数无效")
                require(limit in 0..4) { "并发数应为 0～4" }
                pluginWorkerLimit = limit
                PluginWorkers.save(this, limit)
            }
            "recommendationSource", "chartSource" -> {
                val plugin = plugins.state.value.plugins.firstOrNull { it.id == value && it.enabled } ?: error("音源不存在或已停用")
                val preference = if (key == "recommendationSource") {
                    require(plugin.capabilities.recommendations) { "该音源不支持推荐" }
                    recommendationPluginId = value
                    "recommendation_plugin"
                } else {
                    require(plugin.capabilities.charts) { "该音源不支持榜单" }
                    chartPluginId = value
                    "chart_plugin"
                }
                getSharedPreferences("home", MODE_PRIVATE).edit().putString(preference, value).apply()
            }
            else -> error("未知设置项")
        }
    }

    private fun toggleHomeShelf(id: String) {
        homeShelfHidden = if (id in homeShelfHidden) homeShelfHidden - id else homeShelfHidden + id
        getSharedPreferences("home", MODE_PRIVATE).edit()
            .putStringSet("hidden_shelves", homeShelfHidden.toHashSet()).apply()
    }

    /* After a backup restore the phone session must survive, so instead of recreate()
       the preference-backed state is read again in place. */
    private fun reloadBackedState() {
        quality = getSharedPreferences("playback", MODE_PRIVATE).getString("quality", "standard") ?: "standard"
        setAutoSourceFallback(getSharedPreferences("playback", MODE_PRIVATE).getBoolean(SourceRecovery.PREFERENCE, false), persist = false)
        playMode = readPlaybackMode()
        theme = getSharedPreferences("appearance", MODE_PRIVATE).getString("theme", "gold") ?: "gold"
        showMiniLyrics = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("mini_lyrics", false)
        reduceMotion = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("reduce_motion", false)
        lyricFontLevel = getSharedPreferences("appearance", MODE_PRIVATE).getString("lyric_font_level", "标准") ?: "标准"
        subLyrics = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("sub_lyrics", true)
        lyricCalibrationMs = playbackSong()?.let { LyricCalibration.load(this, it.key) } ?: 0L
        pluginWorkerLimit = PluginWorkers.saved(this)
        homeShelfHidden = getSharedPreferences("home", MODE_PRIVATE).getStringSet("hidden_shelves", null)?.toSet() ?: emptySet()
        recommendationPluginId = getSharedPreferences("home", MODE_PRIVATE).getString("recommendation_plugin", "") ?: ""
        chartPluginId = getSharedPreferences("home", MODE_PRIVATE).getString("chart_plugin", "") ?: ""
        searchHistory = loadSearchHistory()
        SourcePlatforms.initialize(this)
    }

    /* History is kept per search type so 歌手 searches don't suggest 歌曲 terms.
       One JSON object under the old "history" key; a plain array there is the
       pre-type history, which belongs to 歌曲. */
    private fun loadSearchHistory(): Map<String, List<String>> = runCatching {
        val stored = getSharedPreferences("search", MODE_PRIVATE).getString("history", "[]") ?: "[]"
        runCatching {
            val root = JSONObject(stored)
            root.keys().asSequence().associateWith { key ->
                root.optJSONArray(key)?.let { a ->
                    (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.take(30)
                } ?: emptyList()
            }
        }.getOrElse {
            val legacy = JSONArray(stored)
            mapOf("music" to (0 until legacy.length()).map { legacy.optString(it) }.filter { it.isNotBlank() }.take(30))
        }
    }.getOrDefault(emptyMap())

    private fun saveSearchHistory() {
        val root = JSONObject()
        searchHistory.forEach { (type, terms) -> root.put(type, JSONArray(terms)) }
        getSharedPreferences("search", MODE_PRIVATE).edit().putString("history", root.toString()).apply()
    }

    private fun closePhoneInput() {
        closePhoneSession(phoneRemoteMode)
    }

    private fun closePhoneSession(remote: Boolean) {
        val session = if (remote) phoneRemote else phoneForm
        if (phoneInput === session) phoneDialog = false
        val timeout = if (remote) phoneRemoteTimeout else phoneFormTimeout
        timeout?.let { phoneHandler.removeCallbacks(it) }
        session?.close()
        if (remote) { phoneRemote = null; phoneRemoteTimeout = null }
        else { phoneForm = null; phoneFormTimeout = null }
    }

    private fun closePhoneSessions() {
        closePhoneSession(false)
        closePhoneSession(true)
        pluginReplacements.clear()
    }

    private fun showPluginImport(result: SyncResult) {
        status = result.message
        var bytes = pluginReplacements.sumOf { it.incoming.source.toByteArray(Charsets.UTF_8).size }
        for (replacement in result.replacements) {
            if (pluginReplacements.any { it.candidates.map { p -> p.id } == replacement.candidates.map { p -> p.id } && it.incoming.source == replacement.incoming.source }) continue
            bytes += replacement.incoming.source.toByteArray(Charsets.UTF_8).size
            if (bytes > Plugins.MAX_PENDING_SCRIPTS) { status += "；待确认文件过多，请分批导入"; break }
            pluginReplacements += replacement
        }
        if (pluginReplacements.isNotEmpty()) phoneDialog = false
    }

    private fun resolvePluginReplacement(id: String, action: String, targetId: String? = null) {
        check(!busy) { "正在处理音源，请稍后" }
        val replacement = pluginReplacements.firstOrNull { it.incoming.id == id } ?: error("替换请求已失效，请重新导入")
        pluginReplacements.remove(replacement)
        if (action == "cancel") { status = "已取消导入，保留原插件"; return }
        lifecycleScope.launch {
            busy = true
            try {
                showPluginImport(if (action == "new") plugins.installIndependent(replacement)
                    else plugins.confirmReplacement(replacement, targetId ?: replacement.candidates.single().id))
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { status = "替换失败：${e.message}" }
            finally { busy = false }
        }
    }

    private fun openPhoneInput(fields: List<Pair<String, String>>, onSubmit: (Map<String, String>) -> Unit) {
        closePhoneSession(false)
        try {
            lateinit var form: PhoneInput
            form = PhoneInput(this, fields) { values ->
                if (phoneForm === form) {
                    closePhoneSession(false)
                    onSubmit(values)
                }
            }
            phoneForm = form
            phoneRemoteMode = false
            phoneDialog = true
            phoneFormTimeout = Runnable { closePhoneSession(false); status = "扫码输入已过期，请重新打开" }.also { phoneHandler.postDelayed(it, 300_000) }
        } catch (e: Exception) { status = "无法启动手机输入：${e.message}" }
    }

    /* The remote panel is meant to stay open while someone browses the TV. It lives half an
       hour; hiding the QR only hides the dialog. */
    private fun openPhoneRemote(snapshot: () -> String, command: (String) -> String) {
        closePhoneSession(true)
        try {
            phoneRemote = PhoneInput(this, snapshot = snapshot, command = command)
            phoneRemoteMode = true
            phoneDialog = true
            phoneRemoteTimeout = Runnable { closePhoneSession(true); status = "手机遥控已过期，请重新打开" }.also { phoneHandler.postDelayed(it, 1_800_000) }
        } catch (e: Exception) { status = "无法启动手机遥控：${e.message}" }
    }

    private fun qualityText(value: String) = when (value) {
        "low" -> "流畅"; "standard" -> "标准"; "high" -> "高品"; "super" -> "无损优先"
        "flac" -> "无损"; "flac24bit" -> "24-bit 无损"; "hires" -> "Hi-Res"
        "128k" -> "128k"; "192k" -> "192k"; "320k" -> "320k"
        else -> value
    }

    /* Only offer the search types some enabled source can actually answer: a type every source
       ignores would always land on an empty list. The TV page and the phone remote both read this,
       so the two can never present a different set of choices. */
    private fun searchTypeChoices(active: List<Plugin>): List<Pair<String, String>> {
        val all = listOf("music" to "歌曲", "artist" to "歌手", "album" to "专辑", "sheet" to "歌单")
        if (active.isEmpty()) return all
        return all.filter { (type, _) ->
            active.any { plugin ->
                val supported = plugin.metadata.optJSONArray("supportedSearchType")
                supported == null || (0 until supported.length()).any { supported.optString(it) == type }
            }
        }
    }

    /* androidx 把 ComponentActivity.dispatchKeyEvent 标成库内可见，只是不想让外部改它的
       按键分发；遥控器需要在视图之前拦媒体键与熄屏唤醒键，只能在这里接，按原意抑制。 */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Consume the whole wake press; a fresh down also recovers from a missing key-up.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) wakeKeys.remove(event.keyCode)
        if (event.keyCode in wakeKeys) {
            if (event.action == KeyEvent.ACTION_UP) wakeKeys.remove(event.keyCode)
            return true
        }
        val wakeKey = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_SELECT -> true
            else -> false
        }
        if (playerScreenOff && playerOpen && wakeKey) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                wakeKeys.add(event.keyCode)
                playerScreenOff = false
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> { step(1); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { step(-1); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlayback(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (pauseRecoveryIfPending()) return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { resumePlayback(); return true }
                KeyEvent.KEYCODE_SEARCH -> { page = "search"; return true }
                /* Remotes have a MENU key that long-press does not cover. The queue is the
                   thing with no other one-press door on the home page. */
                KeyEvent.KEYCODE_MENU -> {
                    dispatchWithFocusRecovery(event)
                    return true
                }
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
            if (dispatchWithFocusRecovery(event)) return true
            if (queue.isNotEmpty()) queueOpen = true
            return true
        }
        /* The TV IME can swallow the first BACK while a text field is its target, which would
           strand the press before the app's BackHandler sees it. Closing it here lets the same
           press through, so BACK always navigates in one press. */
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            if (imm?.isAcceptingText == true) imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        }
        return dispatchWithFocusRecovery(event)
    }

    @SuppressLint("RestrictedApi") // Forward unhandled keys through ComponentActivity's dispatcher.
    private fun dispatchWithFocusRecovery(event: KeyEvent): Boolean {
        return try {
            super.dispatchKeyEvent(event)
        } catch (e: IllegalStateException) {
            val noActiveFocus = e.message == "Event can't be processed because we do not have an active focus target."
            val detachedTarget = e.message?.contains("FocusRequester is not initialized") == true
            if (!noActiveFocus && !detachedTarget) throw e
            if (event.action == KeyEvent.ACTION_DOWN) wakeKeys.add(event.keyCode)
            if (noActiveFocus) {
                // Android can still consider the Compose view focused after its internal
                // target disappears. Reacquire view focus after the current dispatch finishes.
                val owner = currentFocus ?: window.decorView
                owner.post {
                    if (!isFinishing && !isDestroyed && owner.isAttachedToWindow && owner.hasWindowFocus()) {
                        owner.clearFocus()
                        owner.requestFocus()
                    }
                }
                focusRecovery++
                /* BACK／菜单不重放：换了上下文再做一次就串味了。 */
                if (event.action == KeyEvent.ACTION_DOWN && !replaying && event.keyCode in replayableKeys) replayKey = KeyEvent(event)
            }
            android.util.Log.w("PrismFocus", "焦点暂不可用，忽略本次按键 ${event.keyCode}；恢复焦点=$noActiveFocus")
            true
        }
    }

    @OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Composable private fun Screen() {
        val state by plugins.state.collectAsState()
        val updateState by appUpdates.state.collectAsState()
        val libraryState by library.state.collectAsState()
        /* Disabled sources stay installed but disappear from pickers, search and recommendations. */
        /* Sync rewrites every Plugin object, so keying effects on the list glued the rails to disk
           writes. What actually changes the view is the set of sources, their version and config. */
        val pluginSignature = state.plugins.joinToString("|") {
            "${it.id}:${it.enabled}:${it.source.hashCode()}:${it.metadata}:${it.variables}"
        }
        val activePlugins = state.plugins.filter { it.enabled }
        /* 徽标只承诺能兑现的能力：有 mv 字段但没有 getMvSource 的插件不亮 MV。 */
        val mvCapableIds = remember(state.plugins) { state.plugins.filter { it.capabilities.has("mvSource") }.mapTo(HashSet()) { it.id } }
        val recommendationSource = Discovery.select(activePlugins, recommendationPluginId) { it.recommendations }
        val chartSource = Discovery.select(activePlugins, chartPluginId) { it.charts }
        val artistSeeds = remember(libraryState, state.plugins) { Discovery.artists(libraryState, state.plugins) }
        val savedArtists = libraryState.subjects.filter { it.kind == "artist" }
        val savedAlbums = libraryState.subjects.filter { it.kind == "album" }
        val hiddenShelves = homeShelfHidden
        val homeShelves = buildList {
            if ("favorites" !in hiddenShelves) add(HomeShelf("favorites", "我的收藏", libraryState.favorites))
            if ("recent" !in hiddenShelves) add(HomeShelf("recent", "最近播放", libraryState.recent))
            libraryState.playlists.filterNot { it.id in hiddenShelves }.forEach { list ->
                add(HomeShelf(list.id, list.name, list.songs, MusicPlatform.identify(list.platform)))
            }
        }
        var keyword by remember { mutableStateOf("") }
        LaunchedEffect(searchTerm) { if (searchTerm.isNotBlank()) keyword = searchTerm }
        var subscription by remember { mutableStateOf("") }
        var configured by remember { mutableStateOf<String?>(null) }
        val sourceConfigFocus = remember { mutableMapOf<String, FocusAnchor>() }
        var selectedList by remember { mutableStateOf("favorites") }
        /* 歌单 is one tab with two shapes: the cover grid ("playlists") and, after opening a
           card, that playlist's own table ("playlist:<id>"). Focus effects need both, so the
           reading lives here rather than inside the page composable. */
        val showingPlaylists = selectedList == "playlists"
        val selectedPlaylist = if (selectedList.startsWith("playlist:"))
            libraryState.playlists.firstOrNull { "playlist:${it.id}" == selectedList } else null
        var importUrl by remember { mutableStateOf("") }
        var importName by remember { mutableStateOf("") }
        var importDialog by remember { mutableStateOf(false) }
        var pendingSong by remember { mutableStateOf<Song?>(null) }
        var trackMenu by remember { mutableStateOf<TrackMenuTarget?>(null) }
        val queuePlaybackFocus = remember { FocusRequester() }
        val queueRootFocus = remember { FocusRequester() }
        var subjectMenu by remember { mutableStateOf<SavedSubject?>(null) }
        var collectionMenu by remember { mutableStateOf<CollectionMenuTarget?>(null) }
        var menuReturnFocus by remember { mutableStateOf<FocusRequester?>(null) }
        var menuWasOpen by remember { mutableStateOf(false) }
        var menuPage by remember { mutableStateOf("") }
        var menuBrowse by remember { mutableStateOf<JSONObject?>(null) }
        var menuPlayer by remember { mutableStateOf(false) }
        var activeMv by remember { mutableStateOf<VideoSource?>(null) }
        var resumeAfterMv by remember { mutableStateOf(false) }
        /* Manage mode covers one list at a time: song keys, saved subject keys, or local playlist ids. */
        var libraryManage by remember { mutableStateOf(false) }
        var librarySelected by remember { mutableStateOf(setOf<String>()) }
        /* Manage owns selecting; the confirmation dialog owns the irreversible part. */
        var confirmBatchOpen by remember { mutableStateOf(false) }
        /* Long-press on a tab opens the list-level menu (play all / import / manage):
           toolbar actions stay reachable without crossing five tabs. */
        var libraryListMenu by remember { mutableStateOf(false) }
        var settingsSection by remember { mutableStateOf("外观") }
        var cacheSize by remember { mutableLongStateOf(-1L) }
        var clearingCache by remember { mutableStateOf(false) }
        val versionName = remember { runCatching { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }.getOrDefault("") }
        val deviceInfo = remember {
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}"
        }
        LaunchedEffect(settingsSection) {
            if (settingsSection == "关于") cacheSize = withContext(Dispatchers.IO) { directorySize(cacheDir) }
        }
        /* Tabs switch lists, so a selection carried across would arm the next tab's verb
           against items the remote never chose. */
        LaunchedEffect(selectedList) { libraryManage = false; librarySelected = emptySet(); confirmBatchOpen = false }
        var searchFiltersOpen by remember { mutableStateOf(false) }
        var statusVisible by remember { mutableStateOf(false) }
        var mvResolving by remember { mutableStateOf(false) }
        val statusNotice = status.takeUnless {
            it.startsWith("正在播放") || it.startsWith("正在解析") || it.startsWith("已暂停") || it.startsWith("上次听到") || it == "播放完毕"
        }.orEmpty()
        LaunchedEffect(statusNotice) { statusVisible = statusNotice.isNotBlank(); delay(4500); statusVisible = false }
        fun openMv(song: Song?) {
            if (song == null || mvResolving) return
            mvResolving = true
            lifecycleScope.launch {
                reportPlayback("正在解析 MV…")
                try {
                    val source = withTimeoutOrNull(15_000) { plugins.resolveVideo(song) }
                        ?: error("音源没有返回可播放的 MV 地址，或请求已超时")
                    if (queue.getOrNull(queueIndex)?.key != song.key) return@launch
                    resumeAfterMv = controller?.playWhenReady == true
                    controller?.pause(); activeMv = source; reportPlayback("")
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { reportPlayback("MV 解析失败：${e.message ?: "音源不可用"}") }
                finally { mvResolving = false }
            }
        }

        fun openPhoneHub(entry: String? = null) {
            phoneRemoteEntry = entry ?: when {
                page == "plugins" -> "settings"
                page == "search" || queue.isEmpty() -> "search"
                else -> "play"
            }
            /* Reopening the QR after hiding it reuses the live session, so the phone page that
               is already open keeps the same address. A leftover config form is not a remote. */
            if (phoneRemote != null) { phoneRemoteMode = true; phoneDialog = true; return }
            openPhoneRemote(
                snapshot = {
                    val song = queue.getOrNull(queueIndex)
                    JSONObject()
                        .put("title", song?.title.orEmpty())
                        .put("artist", song?.artist.orEmpty())
                        .put("source", playbackSong()?.sourceLabel.orEmpty())
                        .put("playing", playing && playbackState != Player.STATE_ENDED)
                        .put("position", positionMs)
                        .put("duration", durationMs)
                        .put("quality", tierFor(playbackSong()))
                        .put("qualityLabel", qualityText(playingQuality.ifBlank { quality }))
                        .put("mode", playMode.id)
                        .put("radioActive", ActiveQueue.radio)
                        .put("miniLyrics", showMiniLyrics)
                        .put("theme", theme)
                        .put("reduceMotion", reduceMotion)
                        .put("autoSourceFallback", autoSourceFallback)
                        .put("lyricFontLevel", lyricFontLevel)
                        .put("subLyrics", subLyrics)
                        .put("pluginWorkerLimit", pluginWorkerLimit)
                        .put("automaticWorkers", PluginWorkers.automatic(this@MainActivity))
                        .put("maxPluginBytes", Plugins.MAX_SCRIPT)
                        .put("recommendationSource", Discovery.select(plugins.state.value.plugins, recommendationPluginId) { it.recommendations }?.id.orEmpty())
                        .put("chartSource", Discovery.select(plugins.state.value.plugins, chartPluginId) { it.charts }?.id.orEmpty())
                        .put("homeShelves", JSONArray().also { array ->
                            val shelves = listOf("favorites" to "我的收藏", "recent" to "最近播放") + library.state.value.playlists.map { it.id to it.name }
                            shelves.forEach { (id, name) -> array.put(JSONObject().put("id", id).put("name", name).put("visible", id !in homeShelfHidden)) }
                        })
                        .put("favorite", song != null && library.state.value.favorites.any { it.key == song.key })
                        .put("queueSize", queue.size)
                        .put("queueIndex", if (queue.isEmpty()) 0 else queueIndex + 1)
                        /* The phone needs the queue itself, not just its length, for the
                           playlist button. Capped so a huge queue does not ride along on
                           every 1.2 s poll. */
                        .put("queueSongs", JSONArray().also { array ->
                            queue.take(200).forEachIndexed { index, item ->
                                array.put(JSONObject().put("title", item.title).put("artist", item.artist).put("index", index))
                            }
                        })
                        /* Which tiers this song can actually serve, so the phone offers the
                           same choices as the TV instead of four fixed chips. */
                        .put("qualities", JSONArray().also { array ->
                            val stream = playbackSong()
                            val plugin = stream?.let { current -> plugins.state.value.plugins.firstOrNull { it.id == current.pluginId } }
                            if (stream != null && plugin != null) qualityOptions(plugin, stream)
                                .filterNot { it in unsupportedTiers[stream.key].orEmpty() }
                                .forEach { tier -> array.put(JSONObject().put("id", tier).put("label", qualityText(tier))) }
                        })
                        .put("status", status)
                        .put("page", page)
                        .put("playerOpen", playerOpen)
                        .put("importBusy", busy)
                        .put("restoreBusy", restoringBackup)
                        .put("restoreMessage", restoreMessage)
                        .put("restoreFailed", restoreFailed)
                        .put("pluginReplacement", pluginReplacements.firstOrNull()?.let { replacement ->
                            JSONObject().put("id", replacement.incoming.id).put("name", replacement.incoming.name)
                                .put("author", replacement.incoming.metadata.optString("author"))
                                .put("newVersion", replacement.incoming.metadata.optString("version"))
                                .put("candidates", JSONArray(replacement.candidates.map { candidate ->
                                    JSONObject().put("id", candidate.id).put("name", candidate.name)
                                        .put("author", candidate.metadata.optString("author")).put("version", candidate.metadata.optString("version"))
                                        .put("blocker", replacement.replacementBlocker(candidate).orEmpty())
                                }))
                        })
                        .put("sources", JSONArray().also { array ->
                            plugins.state.value.plugins.forEach { plugin ->
                                val subscription = plugins.state.value.subscriptions.firstOrNull { it == plugin.origin || it == plugin.url }
                                val groupName = subscription?.let { url ->
                                    val uri = Uri.parse(url)
                                    listOfNotNull(uri.host, uri.lastPathSegment?.takeIf { it.isNotBlank() }).joinToString(" / ").ifBlank { "音源订阅" }
                                } ?: "单独安装"
                                val fields = JSONArray()
                                val variables = plugin.metadata.optJSONArray("userVariables") ?: JSONArray()
                                for (i in 0 until variables.length()) {
                                    val field = variables.optJSONObject(i) ?: continue
                                    val key = field.optString("key")
                                    if (key.isBlank()) continue
                                    fields.put(JSONObject()
                                        .put("key", key)
                                        .put("title", field.optString("title").ifBlank { field.optString("name").ifBlank { key } })
                                        .put("hint", field.optString("hint"))
                                        .put("secret", credentialKeys.any { key.contains(it, ignoreCase = true) })
                                        .put("value", plugin.variables.optString(key)))
                                }
                                array.put(JSONObject()
                                    .put("id", plugin.id)
                                    .put("name", plugin.displayName)
                                    .put("groupId", subscription.orEmpty())
                                    .put("groupName", groupName)
                                    .put("supportsRecommendations", plugin.capabilities.recommendations)
                                    .put("supportsCharts", plugin.capabilities.charts)
                                    .put("author", plugin.metadata.optString("author"))
                                    .put("enabled", plugin.enabled)
                                    .put("version", plugin.metadata.optString("version"))
                                    .put("capabilities", JSONArray(plugin.capabilities.labels))
                            .put("canUpdate", plugin.updateUrl.isNotBlank())
                                    /* A subscription will reinstall its plugins on the next
                                       sync, so only locally owned sources can be deleted. */
                                    .put("canRemove", plugin.origin.isBlank() && plugin.url !in plugins.state.value.subscriptions)
                                    .put("fields", fields))
                            }
                        })
                        /* The phone drives the same two filters the TV search page offers, so a search
                           can be narrowed before typing. Computed live here rather than captured: a
                           source enabled on the TV must show up on the phone without a page reload. */
                        .put("searchType", searchType)
                        .put("searchTerm", searchTerm)
                        .put("searchGeneration", searchGeneration)
                        .put("searchBusy", searchJob?.isActive == true)
                        .put("searchHasMore", hasMore)
                        .put("searchResultCount", if (searchType == "music") results.size else entities.size)
                        .put("searchResults", JSONArray().also { array ->
                            if (searchType == "music") results.take(200).forEach { track ->
                                array.put(JSONObject().put("key", track.key).put("title", track.title.take(300))
                                    .put("artist", track.artist.take(200)).put("source", track.sourceLabel.take(120))
                                    .put("duration", track.durationLabel).put("current", track.key == queue.getOrNull(queueIndex)?.key))
                            }
                        })
                        .put("searchTypeOptions", JSONArray().also { array ->
                            searchTypeChoices(plugins.state.value.plugins.filter { it.enabled })
                                .forEach { (type, label) -> array.put(JSONObject().put("id", type).put("label", label)) }
                        })
                        .put("searchSources", JSONArray().also { array -> searchSources.forEach { array.put(it) } })
                        .toString()
                },
                command = { raw ->
                    try {
                        val request = JSONObject(raw)
                        check(!restoringBackup) { "正在恢复备份，请稍候" }
                        val value = request.optString("value").trim()
                        var result: String? = null
                        when (val type = request.optString("type")) {
                            "playPause" -> {
                                val p = controller ?: error("播放器未连接")
                                when {
                                    pauseRecoveryIfPending() -> Unit
                                    p.playWhenReady -> p.pause()
                                    (loadedKey.isBlank() || p.playerError != null) && queue.isNotEmpty() -> togglePlayback()
                                    queue.isNotEmpty() && (p.currentMediaItem == null || p.playbackState == Player.STATE_ENDED) -> playAt(queueIndex, openPlayer = false)
                                    else -> p.play()
                                }
                            }
                            "next" -> { require(queue.isNotEmpty()) { "播放列表为空" }; step(1) }
                            "prev" -> { require(queue.isNotEmpty()) { "播放列表为空" }; step(-1) }
                            "favorite" -> {
                                val song = queue.getOrNull(queueIndex) ?: error("还没有正在播放的歌曲")
                                toggleFavorite(song)
                            }
                            "seek" -> {
                                val target = value.toLongOrNull() ?: error("位置无效")
                                val limit = durationMs.takeIf { it > 0 } ?: target
                                val at = target.coerceIn(0L, limit)
                                seekPlayback(at)
                            }
                            "queuePlay" -> {
                                val index = value.toIntOrNull() ?: error("歌曲序号无效")
                                require(index in queue.indices) { "这首歌已经不在播放列表里" }
                                playAt(index, openPlayer = false)
                            }
                            "theme" -> {
                                updatePreference("theme", value)
                            }
                            "quality" -> {
                                /* Extended tiers are real for some plugins; rejecting them here
                                   would make the phone's own quality sheet offer dead options. */
                                require(value in setOf("low", "standard", "high", "super", "flac", "flac24bit", "hires", "dolby", "atmos", "atmos_plus", "64k", "128k", "192k", "320k", "mgg")) { "未知音质" }
                                /* Same surface as the TV's sheet: it changes this song's stream,
                                   not the library default — that one lives in 设置 → 首选音质. */
                                if (queue.isNotEmpty() && queue.getOrNull(queueIndex)?.pluginId != "local") switchQuality(value)
                            }
                            "mode" -> {
                                updatePreference("mode", value)
                            }
                            "miniLyrics" -> {
                                updatePreference("miniLyrics", (!showMiniLyrics).toString())
                            }
                            "preference" -> {
                                val payload = JSONObject(value)
                                updatePreference(payload.getString("key"), payload.getString("value"))
                            }
                            "homeShelf" -> {
                                val payload = JSONObject(value)
                                val id = payload.getString("id")
                                require(id in setOf("favorites", "recent") || library.state.value.playlists.any { it.id == id }) { "栏目不存在" }
                                if (payload.getBoolean("visible") == (id in homeShelfHidden)) toggleHomeShelf(id)
                            }
                            "search" -> {
                                require(value.isNotEmpty()) { "请输入搜索内容" }
                                keyword = value
                                playerOpen = false
                                page = "search"
                                search(value)
                            }
                            "playSearchResult" -> {
                                val payload = JSONObject(value)
                                require(searchType == "music" && payload.getInt("generation") == searchGeneration) { "搜索结果已更新，请重新选择" }
                                val songs = results.toList()
                                val index = songs.indexOfFirst { it.key == payload.getString("key") }
                                require(index >= 0) { "这首歌已不在搜索结果中，请重新搜索" }
                                playSingle(songs[index])
                            }
                            "searchMore" -> {
                                check(!busy && hasMore && searchType == "music") { "当前无法加载更多" }
                                search(searchTerm, append = true, focusResults = false)
                            }
                            /* The phone's own filter row. Same rules as the TV chips: refuse while a
                               search is in flight, clear what the previous filter produced, and re-run
                               whatever keyword the TV field still holds. */
                            "searchType" -> {
                                require(!busy) { "电视正在搜索，稍后再改筛选" }
                                val choices = searchTypeChoices(plugins.state.value.plugins.filter { it.enabled })
                                require(choices.any { it.first == value }) { "没有已启用的音源支持这种搜索" }
                                if (searchType != value) {
                                    searchType = value
                                    results = emptyList(); entities = emptyList(); hasMore = false
                                    if (keyword.isNotBlank()) search(keyword)
                                }
                            }
                            "searchSource" -> {
                                require(!busy) { "电视正在搜索，稍后再改筛选" }
                                if (value.isEmpty()) {
                                    searchSources = emptySet()
                                } else {
                                    val active = plugins.state.value.plugins.firstOrNull { it.id == value && it.enabled }
                                        ?: error("这个音源不存在或已停用")
                                    searchSources = if (value in searchSources) searchSources - value else searchSources + value
                                    selectedPlugin = active.id
                                }
                                results = emptyList(); entities = emptyList(); hasMore = false
                                if (keyword.isNotBlank()) search(keyword)
                            }
                            "import" -> {
                                require(value.isNotEmpty()) { "请输入插件或订阅地址" }
                                check(!busy && pluginReplacements.isEmpty()) { "请先完成当前音源导入" }
                                lifecycleScope.launch {
                                    busy = true
                                    status = "正在导入…"
                                    try { showPluginImport(plugins.importUrl(value)) }
                                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                    catch (e: Exception) { status = "导入失败：${e.message}" }
                                    finally { busy = false }
                                }
                            }
                            "importFile" -> {
                                check(!busy && pluginReplacements.isEmpty()) { "请先完成当前音源导入" }
                                val payload = JSONObject(value)
                                val source = payload.getString("source")
                                require(source.toByteArray(Charsets.UTF_8).size <= Plugins.MAX_SCRIPT) { "插件文件超过 ${Plugins.MAX_SCRIPT / 1024} KiB" }
                                lifecycleScope.launch {
                                    busy = true
                                    status = "正在导入插件文件…"
                                    try { showPluginImport(plugins.importSource(source)) }
                                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                    catch (e: Exception) { status = "导入失败：${e.message}" }
                                    finally { busy = false }
                                }
                            }
                            "importReplace" -> {
                                val payload = JSONObject(value)
                                resolvePluginReplacement(payload.getString("id"), "replace", payload.getString("targetId"))
                            }
                            "importCancel", "importNew" -> resolvePluginReplacement(value, if (type == "importNew") "new" else "cancel")
                            "importSheet" -> {
                                val payload = JSONObject(value)
                                val sheetUrl = payload.optString("url").trim()
                                require(sheetUrl.isNotEmpty()) { "请输入歌单或单曲链接" }
                                importPlaylist(payload.optString("name"), sheetUrl)
                            }
                            "sourceToggle" -> {
                                val plugin = plugins.state.value.plugins.firstOrNull { it.id == value } ?: error("音源不存在")
                                lifecycleScope.launch {
                                    try {
                                        plugins.setEnabled(plugin.id, !plugin.enabled)
                                        status = if (plugin.enabled) "已停用 ${plugin.displayName}" else "已启用 ${plugin.displayName}"
                                    } catch (e: Exception) { status = "操作失败：${e.message}" }
                                }
                            }
                            "sourceUpdate" -> {
                                val plugin = plugins.state.value.plugins.firstOrNull { it.id == value } ?: error("音源不存在")
                                lifecycleScope.launch {
                                    status = "正在更新 ${plugin.displayName}…"
                                    try { showPluginImport(plugins.refresh(plugin.id)) }
                                    catch (e: Exception) { status = "更新失败：${e.message}" }
                                }
                            }
                            "sourceRemove" -> {
                                val plugin = plugins.state.value.plugins.firstOrNull { it.id == value } ?: error("音源不存在")
                                require(plugin.origin.isBlank() && plugin.url !in plugins.state.value.subscriptions) {
                                    "这是订阅带回的音源，下次同步会重新出现；请在电视上移除整个订阅"
                                }
                                lifecycleScope.launch {
                                    try {
                                        plugins.remove(plugin.id)
                                        status = "已删除音源：${plugin.displayName}"
                                    } catch (e: Exception) { status = "删除失败：${e.message}" }
                                }
                            }
                            "sourceConfig" -> {
                                val payload = JSONObject(value)
                                val id = payload.optString("id")
                                val plugin = plugins.state.value.plugins.firstOrNull { it.id == id } ?: error("音源不存在")
                                val values = payload.optJSONObject("values") ?: JSONObject()
                                lifecycleScope.launch {
                                    try {
                                        val merged = JSONObject(plugin.variables.toString())
                                        val keys = values.keys()
                                        while (keys.hasNext()) { val key = keys.next(); merged.put(key, values.optString(key)) }
                                        plugins.setVariables(plugin.id, merged)
                                        status = "已保存 ${plugin.displayName} 的配置"
                                    } catch (e: Exception) { status = "保存失败：${e.message}" }
                                }
                            }
                            "player" -> playerOpen = true
                            "closePlayer" -> playerOpen = false
                            "page" -> {
                                require(value in setOf("home", "search", "library", "discover", "plugins")) { "未知页面" }
                                page = value
                                playerOpen = false
                            }
                            "backup" -> {
                                /* The panel answers synchronously and a backup build is pure
                                   memory work (prefs, library rows, plugin source). */
                                val payload = runBlocking { LibraryBackup.build(this@MainActivity, library, plugins) }.toString()
                                result = JSONObject().put("ok", true).put("data", payload).toString()
                            }
                            "restore" -> {
                                require(value.isNotEmpty()) { "备份内容为空" }
                                check(!busy && pluginReplacements.isEmpty()) { "请先完成当前音源操作" }
                                restoringBackup = true; busy = true; restoreFailed = false; restoreMessage = "正在恢复曲库、音源与设置…"
                                closePhoneSession(false)
                                lifecycleScope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { LibraryBackup.apply(this@MainActivity, library, plugins, JSONObject(value)) }
                                        reloadBackedState()
                                        restoreMessage = "备份恢复完成，电视已应用曲库、音源与设置。"
                                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                    catch (e: Exception) {
                                        runCatching { reloadBackedState() }
                                        restoreFailed = true
                                        restoreMessage = "恢复未完成：${e.message ?: "未知错误"}。部分数据可能已更新。"
                                    } finally {
                                        restoringBackup = false
                                        busy = false
                                        status = restoreMessage
                                    }
                                }
                            }
                            else -> error("未知操作")
                        }
                        result ?: JSONObject().put("ok", true).toString()
                    } catch (e: Exception) {
                        JSONObject().put("ok", false).put("error", e.message ?: "操作失败").toString()
                    }
                },
            )
        }
        val homeFocus = remember { FocusRequester() }
        val updatePromptFocus = remember { FocusAnchor() }
        val showUpdatePrompt = updateState.release != null && !updateState.skipped && updateState.remind
        val heroFocus = remember { FocusAnchor() }
        val radioCardFocus = remember { FocusAnchor() }
        val discoverFocus = remember { FocusRequester() }
        val libraryFocus = remember { FocusRequester() }
        val searchFocus = remember { FocusRequester() }
        val searchInputFocus = remember { FocusAnchor() }
        /* The 类型/音源 strip sits between the field and the list, and the field's Down is
           named at the list, so the strip had no door of its own: these anchors make it a
           stop on the way, and opening the source row hands the remote to its chips. */
        val searchSourceFocus = remember { FocusAnchor() }
        val searchChipFocus = remember { FocusAnchor() }
        /* Coming back from a played track should land on the result you left, not on the
           text field, which sits one key above the list and invites another round of typing. */
        val searchResultsFocus = remember { FocusAnchor() }
        /* 「加载更多」是列表的最后一个焦点：末行按下去落它，它按上回末行。
           以前只有几何搜索，末行和按钮之间隔着留白，按下去偶尔跳到迷你条。 */
        val searchMoreFocus = remember { FocusAnchor() }
        /* One anchor per collection type. Playlist detail owns a separate Back action. */
        val libraryTabs = remember { listOf("favorites", "albums", "artists", "playlists", "recent").associateWith { FocusAnchor() } }
        /* Opening a playlist's table happens one step inside 歌单, so the page still enters
           through that pill rather than losing the anchor to an unknown key. */
        val libraryTabKey = if (selectedList.startsWith("playlist:")) "playlists" else selectedList
        val libraryContentFocus = remember { FocusAnchor() }
        /* The manage-mode actions sit above the table; a row has no geometric neighbour there. */
        val libraryHeaderFocus = remember { FocusAnchor() }
        /* Each action slot owns an anchor: Right from the last tab lands on the first
           available action instead of crossing five tabs to nothing. */
        val libraryPlayAllFocus = remember { FocusAnchor() }
        val libraryImportFocus = remember { FocusAnchor() }
        /* The detail keeps its own focus boundary until Back returns to the originating card. */
        val playlistBackFocus = remember { FocusAnchor() }
        /* 空收藏的出口（去发现 / 导入歌单）锚在提示语下面：tab 按下去落在
           这里，而不是屏幕右上角那个按钮上。 */
        val libraryEmptyFocus = remember { FocusAnchor() }
        val libraryPlaylistListState = remember { LazyListState() }
        /* 播放会把这首歌挪到「最近」第一条；那一行点名失败时，先滚回页首再落列表锚点。 */
        val librarySongListState = remember(selectedList) { LazyListState() }
        val discoverEntryFocus = remember { FocusAnchor() }
        /* 推荐 shelf 的分类条与首卡各持一个锚点：页头 Down 逐层进入，
           卡片 Up 回分类条，而不是跳去导航。 */
        val recommendEntryFocus = remember { FocusAnchor() }
        val recommendFirstCardFocus = remember { FocusAnchor() }
        /* 推荐之外第一个 shelf 的首卡：无推荐时页头/分类条 Down 的落点；
           滚远后节点不存在时 attached 为假，交还几何搜索。 */
        val discoverBelowFocus = remember { FocusAnchor() }
        /* Detail pages put their actions in the page head. A LazyColumn row has no geometric
           neighbour above it, so the first row declares the head as its Up target. */
        val detailHeaderFocus = remember { FocusAnchor() }
        /* Details are opened from cards in several lists; one requester per card lets Back
           return the remote to the exact card instead of the page's first entry. */
        val detailCardFocus = remember { mutableMapOf<String, FocusRequester>() }
        fun detailFocus(key: String) = detailCardFocus.getOrPut(key) { FocusRequester() }
        fun showSubjectMenu(plugin: Plugin, kind: String, item: JSONObject, focus: FocusRequester) {
            menuReturnFocus = focus
            subjectMenu = SavedSubject(plugin.id, kind, item, plugin.displayName, primaryKeys(plugin.metadata))
        }

        /* Track rows are focus targets too: a detail opened from a row returns to that row,
           and a list that keeps its own LazyListState has to scroll back to it first. */
        val trackRowFocus = remember { mutableMapOf<String, FocusRequester>() }
        fun trackFocus(key: String) = trackRowFocus.getOrPut(key) { FocusRequester() }
        val browseAlbumRowState = remember(browseItem, browsePluginId) { LazyListState() }
        val browseSongListState = remember(browseItem, browsePluginId) { LazyListState() }
        /* 发现 owns one list state so returning from a chart can put the shelf back on screen
           under the card you came from — and so the mini player's Up can scroll to a card the
           list has since virtualized away. */
        val discoverListState = remember { LazyListState() }
        /* The card the remote last held, with its row: the bar is outside this list, so Up
           from it has to be told where to go back to. */
        val discoverFocusMemory = remember { mutableStateOf<Pair<FocusRequester, Int>?>(null) }
        /* Same idea for the two lists that keep their head inside the scroll (a detail's hero and
           shelf titles, a chart's first row): scroll far enough and the node the mini bar would
           name for Up no longer exists, so Up looks like a dead key. The row records itself. */
        val detailFocusMemory = remember { mutableStateOf<Pair<FocusRequester, Int>?>(null) }
        /* 搜索页的迷你条上键恢复：记住结果行与其列表下标，行被回收时先滚回。
           第三位是网格内的列号（歌曲列表恒为 -1），供 down 键点名同列卡片。 */
        val searchFocusMemory = remember(searchTerm, searchType, pluginSignature, searchSources) {
            mutableStateOf<Triple<FocusRequester, Int, Int>?>(null)
        }
        val searchSuggestionsVisible = !busy && (keyword.isBlank() || keyword.trim() != searchTerm.trim())
        val searchListState = remember(searchTerm, searchType) { LazyListState() }
        val searchNavigationScope = rememberCoroutineScope()
        DisposableEffect(page, searchTerm, searchType, pluginSignature, searchSources, searchSuggestionsVisible, searchGeneration) {
            onDispose { searchNavigationScope.coroutineContext.cancelChildren() }
        }
        /* The 音乐榜单 shelf header is one item past the optional 歌手/专辑 shelves above it;
           a chart card's row index is this plus its chunk index. */
        val discoverChartsOffset = 1 + (if (artistSeeds.isNotEmpty()) 2 else 0) +
            (if (recommendationSource != null) 1 else 0) +
            (if (savedArtists.isNotEmpty()) 2 else 0) + (if (savedAlbums.isNotEmpty()) 2 else 0)
        /* 歌手/专辑 is sectioned; a card deep in it has to be scrolled back into view before
           its FocusRequester exists after returning from the detail it opened. */
        val librarySubjectListState = remember(selectedList) { LazyListState() }
        /* A chart keeps its scroll while a detail opened from one of its rows is on screen. */
        val chartSongListState = remember(chartSelection) { LazyListState() }
        val pluginsFocus = remember { FocusRequester() }
        val remotePillFocus = remember { FocusRequester() }
        /* The settings content scrolls, which makes it a separate focus space: a directional
           search from the rail never reaches it, so the rail<->content edges are declared. */
        /* Rail order in one place: Down from 设置 enters the rail at its top, so the list
           the remote walks and the map of anchors cannot drift apart. */
        val settingsSections = remember { listOf("外观", "推荐", "音源", "播放", "关于") }
        val sectionFocus = remember { mapOf("外观" to FocusAnchor(), "推荐" to FocusAnchor(), "音源" to FocusAnchor(), "播放" to FocusAnchor(), "关于" to FocusAnchor()) }
        val sourceActionFocus = remember { FocusAnchor() }
        val sectionEntryFocus = remember { mapOf("外观" to FocusAnchor(), "推荐" to FocusAnchor(), "音源" to sourceActionFocus, "播放" to FocusAnchor(), "关于" to FocusAnchor()) }
        val configEntryFocus = remember { FocusAnchor() }
        val miniFocus = remember { FocusRequester() }
        // 发现页先进入推荐分类，没有推荐时进入首张卡片或空状态入口。
        fun discoverContentEntry(): FocusAnchor = when {
            recommendEntryFocus.attached -> recommendEntryFocus
            discoverBelowFocus.attached -> discoverBelowFocus
            else -> discoverEntryFocus
        }
        /* The bar and the page bodies live in different scroll/focus spaces, so a directional
           search from the bar sometimes finds nothing. Name the landing spot per page instead. */
        fun entryFocus(target: String): FocusAnchor? = when (target) {
            "home" -> if (showUpdatePrompt) updatePromptFocus else heroFocus
            "search" -> searchInputFocus
            "library" -> if (selectedPlaylist != null) playlistBackFocus else libraryTabs[libraryTabKey]
            "plugins" -> if (configured == null) sectionFocus.getValue(settingsSections.first()) else configEntryFocus
            "discover" -> when {
                browseItem != null -> detailHeaderFocus
                chartSelection != null -> discoverEntryFocus.takeIf { chartSongs.isNotEmpty() }
                else -> discoverContentEntry()
            }
            else -> null
        }
        val pageEntryFocus = entryFocus(page)
        val contentMenuOpen = trackMenu != null || subjectMenu != null || collectionMenu != null || libraryListMenu
        /* 界面侧补落焦：搜索页优先回结果首条，其余回页面入口；弹窗与播放页自己持有焦点。 */
        LaunchedEffect(focusRecovery) {
            if (focusRecovery == 0) return@LaunchedEffect
            if (playerOpen || queueOpen || contentMenuOpen || importDialog || activeMv != null) return@LaunchedEffect
            val target = if (page == "search" && !searchSuggestionsVisible && searchResultsFocus.attached) searchResultsFocus
                else entryFocus(page)
            repeat(6) {
                withFrameNanos { }
                if (target?.requestIfAttached() == true) {
                    replayKey?.let { key ->
                        replayKey = null
                        replaying = true
                        try { dispatchKeyEvent(KeyEvent(key)) } finally { replaying = false }
                    }
                    return@LaunchedEffect
                }
            }
            replayKey = null
        }
        LaunchedEffect(contentMenuOpen) {
            if (contentMenuOpen) {
                menuWasOpen = true; menuPage = page; menuBrowse = browseItem; menuPlayer = playerOpen
                if (menuReturnFocus == null) menuReturnFocus = trackMenu?.originFocus?.let { detailCardFocus[it] }
            } else if (menuWasOpen) {
                menuWasOpen = false
                val target = menuReturnFocus; menuReturnFocus = null
                if (page != menuPage || browseItem !== menuBrowse || playerOpen != menuPlayer) return@LaunchedEffect
                if (queueOpen) {
                    (if (queue.isEmpty()) queueRootFocus else queuePlaybackFocus).requestAfterScroll { }
                    return@LaunchedEffect
                }
                repeat(4) {
                    withFrameNanos { }
                    if (target != null && runCatching { target.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
                }
                if (pageEntryFocus?.requestIfAttached() != true) {
                    runCatching { when (page) {
                        "home" -> homeFocus
                        "library" -> if (selectedPlaylist != null) playlistBackFocus.requester else libraryTabs.getValue(libraryTabKey).requester
                        "search" -> searchFocus
                        else -> discoverFocus
                    }.requestFocus() }
                }
            }
        }
        val pageScope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current
        /* Moving along the top bar previews each page; only leaving the bar hands focus to the
           body. Without this, the page focus effect and the preview would fight each other. */
        var navBarFocused by remember { mutableStateOf(false) }
        var remotePillFocused by remember { mutableStateOf(false) }
        /* Settings rows remember themselves here; the mini player's Up returns to the last
           one instead of the page entry (the rail). */
        val bodyFocusMemory = remember { mutableStateOf<FocusRequester?>(null) }
        LaunchedEffect(configured, settingsSection) { bodyFocusMemory.value = null }
        /* A TV tab bar previews the page the highlight lands on; OK only hands focus to the
           body. Making the user press OK twice (select, then enter) is the behaviour of a form,
           not of a remote. Previewing is driven by the Left/Right keys rather than by focus
           arriving: Up from a page body lands on whichever pill happens to be nearest, and
           previewing that one teleported the user to a page they never asked for. */
        val navTabs = listOf("home" to homeFocus, "search" to searchFocus, "library" to libraryFocus, "discover" to discoverFocus, "plugins" to pluginsFocus)
        fun navModifier(requester: FocusRequester) = Modifier.focusRequester(requester)
            .focusProperties { pageEntryFocus?.takeIf { it.attached }?.let { down = it.requester } }
        val enterPage: (String) -> Unit = { target ->
            page = target
            pageScope.launch {
                repeat(4) {
                    withFrameNanos { }
                    if (page != target || playerOpen) return@launch
                    if (entryFocus(target)?.requestIfAttached() == true) return@launch
                }
            }
        }
        /* The bar stays put. Fading it after first use left a black strip where the menu had
           been, which reads as a rendering bug rather than as focus economy. */
        /* 标签首帧算出并占固定槽位：聚焦后才出现会把整行重排。 */
        fun qualityTagOf(song: Song): String? {
            val qualities = song.data.optJSONObject("qualities") ?: return null
            return when {
                qualities.has("hires") -> "Hi-Res"
                qualities.has("flac24bit") -> "24-bit"
                qualities.has("flac") -> "无损"
                /* Standard-tier plugins say super instead of naming a codec. */
                qualities.has("super") -> "无损"
                else -> null
            }
        }
        /* Leaving the player through a subject link lands on a detail page, not on the mini
           player; keep the end-of-playback focus handoff from yanking the remote back. */
        var suppressMiniReturn by remember { mutableStateOf(false) }
        /* The album name alone is ambiguous, so the music-search fallback includes the artist. */
        fun searchSubject(name: String, kind: String, song: Song) {
            val albumCapable = plugins.state.value.plugins.any { it.enabled && supportsSubjectSearch(it, "album") }
            keyword = if (kind == "album" && song.artist.isNotBlank() && song.artist != "未知歌手") "${song.artist} $name" else name
            searchType = if (kind == "album" && !albumCapable) "music" else kind
            page = "search"
            if (!busy) search(keyword)
        }
        /* A song carries only the artist and album names; the detail calls need the source's own
           entity object. Our plugins also emit artistId/albumId (a Prism-only extra), which
           opens the detail directly and avoids guessing among search hits. Sources that only
           give names fall back to searching the same source. */
        fun openSubject(song: Song, kind: String, originFocus: String = "", menu: Boolean = false) {
            subjectJob?.cancel()
            val label = if (kind == "artist") "歌手" else "专辑"
            val name = (if (kind == "artist") song.artist else song.album).trim()
            if (name.isBlank() || name == "未知歌手") { status = "这首歌没有${label}信息"; return }
            if (playerOpen) suppressMiniReturn = true
            val plugin = plugins.state.value.plugins.firstOrNull { it.id == song.pluginId && it.enabled }
            if (plugin == null) {
                playerOpen = false
                searchSubject(name, kind, song)
                return
            }
            fun acceptSubject(item: JSONObject) {
                if (menu) showSubjectMenu(plugin, kind, item, detailFocus(originFocus))
                else openBrowse(plugin, item, kind, originFocus)
            }
            val directId = (if (kind == "artist") song.data.optString("artistId") else song.data.optString("albumId"))
                .trim()
            if (directId.isNotBlank()) {
                val subject = JSONObject()
                    .put("id", directId)
                    .put("title", name)
                    .put("name", name)
                    .put("artist", song.artist)
                    .put("artwork", if (kind == "artist") song.data.optString("artistArtwork") else song.artwork)
                status = ""
                acceptSubject(subject)
                return
            }
            /* Without a platform id the only route is a subject search; a source that
               declares only song search gets the search page instead of an empty detail. */
            if (!supportsSubjectSearch(plugin, kind)) {
                playerOpen = false
                searchSubject(name, kind, song)
                return
            }
            status = "正在打开${label}：$name"
            subjectJob = lifecycleScope.launch {
                try {
                    val response = plugins.client.call(plugin.source, plugin.variables, "search",
                        JSONArray().put(name).put(1).put(kind), plugin.id) as? JSONObject ?: error("音源未返回搜索结果")
                    val data = response.optJSONArray("data") ?: JSONArray()
                    val candidates = (0 until minOf(data.length(), 50)).mapNotNull { data.optJSONObject(it) }
                    val wanted = normalizedName(name)
                    val exact = candidates.firstOrNull { normalizedName(subjectTitle(it)) == wanted }
                    val best = exact ?: if (kind == "album") {
                        candidates.firstOrNull {
                            val candidate = normalizedName(subjectTitle(it))
                            candidate.isNotBlank() && (candidate.contains(wanted) || wanted.contains(candidate))
                        } ?: candidates.firstOrNull()
                    } else null
                    if (best == null) {
                        status = "没有找到「$name」的$label，已改为搜索"
                        playerOpen = false
                        searchSubject(name, kind, song)
                    } else {
                        status = ""
                        acceptSubject(best)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    status = "打开$label 失败：${e.message ?: "未知错误"}，已改为搜索"
                    playerOpen = false
                    searchSubject(name, kind, song)
                }
            }
        }
        var playerWasOpen by remember { mutableStateOf(false) }
        /* Which tiers a plugin actually serves is knowable in advance, but only by asking it. One
           probe per track, cached for the session, and deliberately started after playback has
           resolved its own stream: two concurrent getMediaSource calls on the same track fight
           each other and the loser reports a tier as missing. A lone failure is not evidence —
           two in a row ends the ladder. */
        LaunchedEffect(playerOpen, queueIndex, playbackSong()?.key) {
            val target = playbackSong()
            availableTiers = emptyList()
            if (!playerOpen || target == null) return@LaunchedEffect
            val plugin = state.plugins.firstOrNull { it.id == target.pluginId } ?: return@LaunchedEffect
            availableTiers = qualityOptions(plugin, target)
        }
        /* 关闭播放页后回到离开的那一行；点不到（那首歌已被挪走）就滚回页首用列表锚点。 */
        suspend fun restorePlayerReturnFocus(): Boolean {
            fun tryRequest(requester: FocusRequester?): Boolean =
                requester != null && runCatching { requester.requestFocus(); true }.getOrDefault(false)
            val remembered = when (page) {
                "search" -> searchFocusMemory.value?.first
                "library" -> bodyFocusMemory.value
                "discover" -> detailFocusMemory.value?.first ?: discoverFocusMemory.value?.first
                else -> null
            }
            repeat(3) { withFrameNanos { }; if (tryRequest(remembered)) return true }
            when (page) {
                "search" -> {
                    runCatching { searchListState.scrollToItem(0) }
                    repeat(4) { withFrameNanos { }; if (searchResultsFocus.requestIfAttached()) return true }
                }
                "library" -> {
                    runCatching { librarySongListState.scrollToItem(0) }
                    repeat(4) { withFrameNanos { }; if (libraryContentFocus.requestIfAttached()) return true }
                }
                "discover" -> repeat(4) { withFrameNanos { }; if (discoverEntryFocus.requestIfAttached()) return true }
            }
            return false
        }
        LaunchedEffect(playerOpen) {
            if (!playerOpen) {
                playerScreenOff = false
                screenOffSettled = true
            }
            if (playerOpen) playerWasOpen = true
            else if (playerWasOpen) {
                withFrameNanos { }
                val restored = !suppressMiniReturn && restorePlayerReturnFocus()
                if (!restored) miniFocus.requestFocus()
                suppressMiniReturn = false
                playerWasOpen = false
            }
        }
        /* Cancelling import returns to its toolbar; a completed import opens the new detail. */
        var importWasOpen by remember { mutableStateOf(false) }
        LaunchedEffect(importDialog) {
            if (importDialog) importWasOpen = true
            else if (importWasOpen) {
                withFrameNanos { }
                if (!importDialog) {
                    if (selectedPlaylist != null) playlistBackFocus.requestIfAttached()
                    else if (!libraryHeaderFocus.requestIfAttached()) libraryEmptyFocus.requestIfAttached()
                }
                importWasOpen = false
            }
        }
        LaunchedEffect(page) {
            if (page != "search") focusSearchResults = false
            /* The bar already owns focus while previewing; moving it now would stop the user
               from continuing left/right along the tabs. The player owns focus the same way:
               a detail's Back restores the player, and the page must not yank the remote
               out from under it. */
            if (navBarFocused || remotePillFocused || playerOpen) return@LaunchedEffect
            /* Back from a detail restores the card that opened it; that effect owns focus. */
            if (pendingReturnFocus.isNotBlank()) return@LaunchedEffect
            withFrameNanos { }
            when (page) {
                "home" -> heroFocus.requestIfAttached()
                "search" -> searchInputFocus.requestIfAttached()
                /* A detail is opened from a card on another page: the effect below waits for its
                   songs and lands on the first one. Taking the bar's tab here would park the
                   remote on the top bar until the user pressed Down again. */
                "discover" -> if (browseItem == null && chartSelection == null) discoverContentEntry().requestIfAttached()
                /* Opening 我的 from 设置 should land on the list tab that is lit, not on the
                   bar's copy of it — otherwise the highlight and the remote disagree. */
                "library" -> if (selectedPlaylist != null) playlistBackFocus.requestIfAttached() else libraryTabs[libraryTabKey].requestIfAttached()
                "plugins" -> if (configured == null) sectionFocus.getValue(settingsSections.first()).requestIfAttached() else configEntryFocus.requestIfAttached()
            }
        }
        /* 发现 swaps between the chart grid and two lists. Swapping recomposes away the node that
           held focus, so hand focus to whatever the new shape shows first — or back to the bar
           when the list came back empty, which would otherwise strand focus nowhere. */
        LaunchedEffect(page, browseItem, chartSelection) {
            if (navBarFocused || playerOpen) return@LaunchedEffect
            /* A new list shape means a new set of rows; the old position must not be scrolled back. */
            detailFocusMemory.value = null
            val returnKey = pendingReturnFocus
            if (returnKey.isNotBlank()) {
                pendingReturnFocus = ""
                /* A detail opened from a song row returns to that row. The list may have been
                   recreated while the detail was open, and a row below the fold is not composed,
                   so scroll to it before asking for focus. */
                if (returnKey.startsWith("song:") && browseItem != null) {
                    val key = returnKey.removePrefix("song:")
                    val index = browseSongs.indexOfFirst { it.key == key }
                    if (index >= 0) runCatching { browseSongListState.scrollToItem(detailSongRowOffset() + index) }
                }
                /* The album shelf is a LazyRow: a card beyond the viewport is not composed,
                   so scroll to it before asking for focus. */
                if (browseItem != null && returnKey.startsWith("album:")) {
                    val id = returnKey.substringAfterLast(':')
                    val index = browseAlbums.indexOfFirst { it.optString("id") == id }
                    if (index >= 0) runCatching { browseAlbumRowState.scrollToItem(index) }
                }
                /* A chart card is a row of the 发现 list. Scroll it back into view before
                   asking for focus: below the fold the node does not exist yet. */
                if (returnKey.startsWith("chart:")) {
                    val id = returnKey.substringAfter(':')
                    val index = charts.indexOfFirst { it.optString("id") == id }
                    if (index >= 0) runCatching { discoverListState.scrollToItem(discoverChartsOffset + index / 3) }
                }
                /* A subject card in 歌手/专辑 uses "<kind>:<plugin>:<id>" as its key. Scroll its card row back into view before requesting focus. */
                if (page == "library") {
                    val parts = returnKey.split(":", limit = 3)
                    val kind = parts.getOrNull(0)
                    if (parts.size == 3 && (kind == "artist" || kind == "album" || kind == "sheet")) {
                        val pluginId = parts[1]
                        val id = parts[2]
                        val group = libraryState.subjects.filter { it.kind == kind }
                        val at = group.indexOfFirst { it.pluginId == pluginId && it.data.optString("id") == id }
                        if (at >= 0) {
                            if (kind == "sheet") {
                                val localRows = (libraryState.playlists.size + 5) / 6
                                runCatching { libraryPlaylistListState.scrollToItem(localRows + 1 + at / 6) }
                            } else runCatching { librarySubjectListState.scrollToItem(at / 6) }
                        }
                    }
                }
                repeat(10) {
                    withFrameNanos { }
                    val requester = detailCardFocus[returnKey]
                    if (requester != null && runCatching { requester.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
                }
                /* The origin was a list row that is gone, or a card the parent no longer shows.
                   Land on the page entry rather than leaving the remote with no focus owner. */
                withFrameNanos { }
                when (page) {
                    "search" -> searchInputFocus.requestIfAttached()
                    "library" -> if (selectedPlaylist != null) playlistBackFocus.requestIfAttached() else libraryTabs[libraryTabKey].requestIfAttached()
                    "home" -> heroFocus.requestIfAttached()
                    "discover" -> if (browseItem == null && chartSelection == null) discoverContentEntry().requestIfAttached()
                }
                if (page != "discover") return@LaunchedEffect
            }
            if (page != "discover") return@LaunchedEffect
            when {
                browseItem != null && chartSelection == null -> snapshotFlow {
                    !browseLoading && browseStatus != "正在加载…"
                }.first { it }
                chartSelection != null -> snapshotFlow {
                    chartSongs.isNotEmpty() || chartDetailStatus != "正在加载榜单…"
                }.first { it }
                else -> Unit
            }
            if (chartSelection != null && chartSongs.isEmpty()) { discoverFocus.requestFocus(); return@LaunchedEffect }
            /* A detail opens on its own actions: the hero is at the top of the scroll, so the
               remote starts on 播放全部 instead of teleporting the list past the cover. */
            val target = when {
                browseItem != null -> detailHeaderFocus
                chartSelection != null -> discoverEntryFocus
                else -> discoverContentEntry()
            }
            repeat(6) {
                withFrameNanos { }
                if (target.requestIfAttached()) return@LaunchedEffect
            }
            /* A restored detail whose hero failed to attach (empty page, an error) still needs
               a focused node; the first song row or the bar's 发现 tab always exists. */
            if (browseItem != null) {
                val first = browseSongs.firstOrNull()
                val requester = first?.let { detailCardFocus["song:${it.key}"] }
                if (requester != null && runCatching { requester.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
            }
            runCatching { discoverFocus.requestFocus() }
        }
        val closeConfig: () -> Unit = {
            val origin = configured
            configured = null
            pageScope.launch {
                repeat(6) {
                    withFrameNanos { }
                    if (origin?.let { sourceConfigFocus[it] }.requestIfAttached()) return@launch
                }
                sourceActionFocus.requestIfAttached()
            }
        }
        fun toggleLibrarySelection(key: String) {
            librarySelected = if (key in librarySelected) librarySelected - key else librarySelected + key
        }
        /* A playlist card and its table are the same tab, so nothing else moves the remote:
           stepping in lands on the first song, Back lands on the card again. */
        fun openPlaylistTable(id: String) {
            selectedList = "playlist:$id"
            pageScope.launch {
                /* The table is a new node set, so give it a few frames to compose its first row
                   before asking for it; a single frame can arrive before the row exists. */
                repeat(4) {
                    withFrameNanos { }
                    if (libraryContentFocus.requestIfAttached()) return@launch
                }
                playlistBackFocus.requestIfAttached()
            }
        }
        fun closePlaylistTable() {
            val id = selectedPlaylist?.id ?: return
            val key = "playlist:$id"
            selectedList = "playlists"
            pageScope.launch {
                val row = libraryState.playlists.indexOfFirst { it.id == id }.coerceAtLeast(0) / 6
                libraryPlaylistListState.scrollToItem(row)
                repeat(4) {
                    withFrameNanos { }
                    val card = detailCardFocus[key]
                    if (card != null && runCatching { card.requestFocus(); true }.getOrDefault(false)) return@launch
                }
                libraryTabs.getValue("playlists").requestIfAttached()
            }
        }
        /* A phone import also chooses the new playlist, without changing the open TV page. */
        LaunchedEffect(playlistToShow) {
            val id = playlistToShow ?: return@LaunchedEffect
            playlistToShow = null
            if (libraryState.playlists.any { it.id == id }) {
                if (page == "library") openPlaylistTable(id) else selectedList = "playlist:$id"
            }
        }
        fun enterLibraryManage() {
            libraryManage = true
            librarySelected = emptySet()
            /* Rows trade 播放 for a checkbox, so the remote belongs on the first item: one press
               selects, Down walks. */
            pageScope.launch { withFrameNanos { }; libraryContentFocus.requestIfAttached() }
        }
        fun exitLibraryManage() {
            libraryManage = false
            librarySelected = emptySet()
            confirmBatchOpen = false
            pageScope.launch { withFrameNanos { }; libraryHeaderFocus.requestIfAttached() }
        }
        fun runLibraryBatch() {
            if (librarySelected.isEmpty()) { status = "先用确定键选中要处理的条目"; return }
            val keys = librarySelected.toSet()
            val playlistId = selectedPlaylist?.id
            lifecycleScope.launch {
                try {
                    when {
                        selectedList == "recent" -> { library.removeRecent(keys); status = "已删除 ${keys.size} 条播放记录" }
                        selectedList in setOf("artists", "albums") -> { library.removeSubjects(keys); status = "已移除 ${keys.size} 个收藏" }
                        showingPlaylists -> { library.removePlaylists(keys); status = "已删除 ${keys.size} 个歌单" }
                        playlistId != null -> { library.removeSongsFrom(playlistId, keys); status = "已从歌单移出 ${keys.size} 首" }
                        else -> { library.removeFavorites(keys); status = "已取消收藏 ${keys.size} 首" }
                    }
                } catch (e: Exception) { status = e.message ?: "操作失败" }
            }
            exitLibraryManage()
        }
        BackHandler {
            when {
                playerScreenOff && playerOpen -> {
                    /* Fallback when the wake press reaches the BackHandler instead of dispatchKeyEvent. */
                    playerScreenOff = false
                    screenOffSettled = true
                }
                playerOpen -> playerOpen = false
                /* Back steps out of 管理 first: leaving the page while the confirm dialog
                   is open would hide the confirmation. */
                libraryManage && page == "library" -> exitLibraryManage()
                /* A playlist's table is one step inside the 歌单 tab, so Back steps out of the
                   table rather than leaving 我的 for 首页. */
                page == "library" && selectedPlaylist != null -> closePlaylistTable()
                browseItem != null && page == "discover" -> leaveBrowse()
                chartSelection != null && page == "discover" -> {
                    chartJob?.cancel()
                    /* Back belongs on the chart card you chose, not on whichever one the grid
                       happens to show first. */
                    pendingReturnFocus = "chart:" + chartSelection?.optString("id").orEmpty()
                    chartSelection = null
                }
                configured != null -> closeConfig()
                page != "home" -> {
                    pendingSong = null; page = "home"
                    /* The page body recomposes away and takes the focused node with it, so the
                       remote is handed to the 首页 tab — Back must never strand focus nowhere. */
                    pageScope.launch { withFrameNanos { }; runCatching { homeFocus.requestFocus() } }
                }
                else -> {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (playing && now - exitArmedAt > 2_000) {
                        exitArmedAt = now
                        status = "再按一次返回键退出"
                        return@BackHandler
                    }
                    recovery?.ready?.cancel(); recovery = null
                    resolving?.cancel()
                    /* Back can arrive while the controller is still connecting; stopping only the
                       connected instance would let the audio survive the exit. */
                    val ready = controller
                    if (ready != null) runCatching { ready.stop(); ready.clearMediaItems() }
                    else connection?.let { pending ->
                        pending.addListener({
                            runCatching { pending.get() }.getOrNull()?.let { runCatching { it.stop(); it.clearMediaItems() } }
                        }, ContextCompat.getMainExecutor(this@MainActivity))
                    }
                    queue = emptyList(); queueIndex = -1; playing = false; lyrics = emptyList(); loadedKey = ""; ActiveQueue.source = null
                    lyricCalibrationMs = 0L
                    stopService(Intent(this@MainActivity, PlaybackService::class.java)); finish()
                }
            }
        }
        LaunchedEffect(state.plugins) {
            val wasLoaded = queue.getOrNull(queueIndex)?.key == loadedKey
            queue = queue.map { song -> state.plugins.firstOrNull { it.id == song.pluginId }
                ?.let { song.copy(primaryKey = primaryKeys(it.metadata)) } ?: song }
            if (wasLoaded) loadedKey = queue.getOrNull(queueIndex)?.key.orEmpty()
            try { library.alignPrimaryKeys(state.plugins) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { status = "曲库更新失败：${e.message}" }
            if (selectedPlugin.isNotEmpty() && activePlugins.none { it.id == selectedPlugin }) selectedPlugin = ""
            searchSources = searchSources.intersect(activePlugins.map { it.id }.toSet())
            recommendationPluginId = recommendationSource?.id.orEmpty()
            chartPluginId = chartSource?.id.orEmpty()
        }
        LaunchedEffect(recommendationPluginId, pluginSignature, page == "discover") {
            if (page != "discover") return@LaunchedEffect
            val plugin = recommendationSource
            if (plugin == null) {
                recommendationTags = emptyList(); recommendationTagsOwner = ""
                recommendationTag = JSONObject().put("id", "").put("title", "推荐")
            } else {
                if (recommendationTagsOwner != plugin.id) {
                    recommendationTags = emptyList()
                    recommendationTagsOwner = plugin.id
                    recommendationTag = JSONObject().put("id", "").put("title", "推荐")
                }
                loadRecommendationTags(plugin)
            }
        }
        LaunchedEffect(recommendationPluginId, pluginSignature, recommendationTag.optString("id"), page == "discover") {
            if (page != "discover") return@LaunchedEffect
            val plugin = recommendationSource
            if (plugin == null) {
                recommendations = emptyList(); recommendationContext = ""
                recommendationStatus = "添加支持推荐歌单的音源后显示"; recommendationMore = false
            } else {
                val context = "${plugin.id}|${recommendationTag.optString("id")}"
                val reset = context != recommendationContext || recommendations.isEmpty()
                recommendationContext = context
                /* A refresh keeps the current shelf on screen until the new one arrives. */
                loadRecommendations(plugin, recommendationTag, reset = reset)
            }
        }
        LaunchedEffect(firstFrameDrawn, chartPluginId, pluginSignature) {
            if (!firstFrameDrawn) return@LaunchedEffect
            chartGeneration++
            chartJob?.cancel(); chartSelection = null; chartSongs = emptyList()
            charts = emptyList(); chartPreviews = emptyMap()
            val plugin = chartSource
            if (plugin == null) chartStatus = "暂无提供榜单的音源，可在设置中添加"
            else loadCharts(plugin, force = false)
        }
        LaunchedEffect(page) {
            if (firstFrameDrawn && page == "discover" && !chartLoading) chartSource?.let { loadCharts(it, force = false) }
        }
        LaunchedEffect(page, chartPluginId, charts.take(3).map { it.optString("id") }) {
            if (page != "discover") return@LaunchedEffect
            val plugin = chartSource ?: return@LaunchedEffect
            val generation = chartGeneration
            coroutineScope {
                charts.take(3).drop(1).filter { chartPreviews[it.optString("id")].isNullOrEmpty() }.map { chart -> async {
                    val songs = try { withTimeout(8_000) { fetchChart(plugin, chart) } }
                    catch (e: kotlinx.coroutines.TimeoutCancellationException) { emptyList() }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { emptyList() }
                    if (generation == chartGeneration && songs.isNotEmpty()) chartPreviews = chartPreviews + (chart.optString("id") to songs)
                } }.awaitAll()
            }
        }
        val motionEnabled = !reduceMotion && (android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled())
        /* The screen-off fade and wake fade are the screen's own state, not 动效: they must run
           even when 减少动效 is on. Only hidden background motion obeys the switch here. */
        CompositionLocalProvider(LocalPrismColors provides prismColors(theme), LocalAuraMotion provides (motionEnabled && !(playerScreenOff && screenOffSettled))) {
            val c = LocalPrismColors.current
            Box(Modifier.fillMaxSize()) {
            /* Cold start: the light comes into the room before the furniture does. Drawn above
               everything by zIndex because it is inserted first, and only once per process. */
            /* 减少动效 只跳过动画，不跳过这块标：否则静态启动画面在开关打开时一并消失。 */
            var introVisible by remember { mutableStateOf(!playerOpen && !PrismLaunch.played) }
            /* 冷启动的开屏只认应用内「减少动效」：系统动画缩放为 0 时（不少电视出厂如此）
               Compose 的过渡会整体失效，开屏等于没有动画。 */
            if (introVisible) PrismIntro(!reduceMotion, Modifier.zIndex(1f)) { introVisible = false; PrismLaunch.played = true }
            Box(Modifier.fillMaxSize().focusProperties { canFocus = !playerOpen }) {
            PrismBackground {
                Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
                    /* No focusGroup here: it would turn the bar into a nested focus space, and a
                       directional search starting outside a space can never enter it — the nav
                       would be unreachable from the page body. */
                    Row(Modifier.fillMaxWidth().onFocusChanged { state ->
                            navBarFocused = state.hasFocus
                            /* The ring always starts on the page that is on screen. Landing on
                               a pill only reveals the bar; the preview follows the next Left or
                               Right press. */
                            if (state.hasFocus) pageScope.launch {
                                withFrameNanos { }
                                if (navBarFocused && !remotePillFocused) navTabs.firstOrNull { it.first == page }?.second?.requestFocus()
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val step = when (event.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> return@onPreviewKeyEvent false }
                            /* The remote pill is the bar's right end but an action, not a page:
                               Left returns to the current tab without previewing; Right is a wall. */
                            if (remotePillFocused) {
                                if (step < 0) navTabs.firstOrNull { it.first == page }?.second?.requestFocus()
                                return@onPreviewKeyEvent true
                            }
                            val index = navTabs.indexOfFirst { it.first == page }
                            val next = navTabs.getOrNull(index + step)
                            if (next == null) {
                                if (step > 0 && index == navTabs.lastIndex) remotePillFocus.requestFocus()
                                return@onPreviewKeyEvent index >= 0
                            }
                            page = next.first
                            next.second.requestFocus()
                            true
                        }, verticalAlignment = Alignment.CenterVertically) {
                        AuraBrand()
                        Spacer(Modifier.width(38.dp))
                        PrismNav("首页", page == "home", { enterPage("home") }, navModifier(homeFocus))
                        PrismNav("搜索", page == "search", { enterPage("search") }, navModifier(searchFocus))
                        PrismNav("我的", page == "library", { enterPage("library") }, navModifier(libraryFocus))
                        PrismNav("发现", page == "discover", { enterPage("discover") }, navModifier(discoverFocus))
                        Spacer(Modifier.width(9.dp))
                        Box(Modifier.width(1.dp).height(13.dp).background(c.foreground.copy(alpha = .12f)))
                        Spacer(Modifier.width(9.dp))
                        PrismNav("设置", page == "plugins", { enterPage("plugins") }, navModifier(pluginsFocus), quiet = true)
                        Spacer(Modifier.weight(1f))
                        AuraRemotePill(Modifier.focusRequester(remotePillFocus)
                            .onFocusChanged { remotePillFocused = it.isFocused }
                            .focusProperties { pageEntryFocus?.takeIf { it.attached }?.let { down = it.requester } },
                            onClick = { openPhoneHub() })
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.width(0.5.dp).height(9.dp).background(c.foreground.copy(alpha = .12f)))
                        Spacer(Modifier.width(12.dp))
                        AuraClock()
                    }
                    Spacer(Modifier.height(18.dp))
                    if (state.error != null || libraryState.error != null) Text(state.error ?: libraryState.error.orEmpty(), fontSize = 12.sp, color = c.accent, modifier = Modifier.padding(bottom = 8.dp), maxLines = 2)
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                        Box(Modifier.fillMaxSize()) {
                        /* 首页每一行都是独立的横向行，而几何搜索只看得到视口里的节点：下一行
                           还在屏幕外时按「下」，它找不到正下方的候选，就横向落到同排最右侧
                           那张卡片上——方向键看起来"崩了"。所以行与行之间的上下落点在这里
                           点名：列号相同，下一行更短时取它的最后一列。 */
                        val visibleShelves = homeShelves.filter { it.songs.isNotEmpty() }
                        val shelfCards = visibleShelves.map { shelf ->
                            shelf.songs.take(20).map { detailFocus("home:${shelf.id}:${it.key}") }
                        }
                        when (page) {
                            "home" -> {
                                val focusScope = rememberCoroutineScope()
                                val rowStates = remember { mutableMapOf<String, LazyListState>() }
                                fun focusCard(row: Int, column: Int) {
                                    val cards = shelfCards.getOrNull(row).orEmpty()
                                    if (cards.isEmpty()) return
                                    val col = column.coerceIn(0, cards.lastIndex)
                                    val rowState = rowStates.getOrPut(visibleShelves[row].id) { LazyListState() }
                                    focusScope.coroutineContext.cancelChildren()
                                    focusScope.launch {
                                        if (!cards[col].requestAfterScroll { rowState.scrollToItem(col) }) heroFocus.requestIfAttached()
                                    }
                                }
                                fun downToCard(row: Int, column: Int) = Modifier.onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && row in shelfCards.indices) {
                                        focusCard(row, column)
                                        true
                                    } else false
                                }
                                Column(Modifier.fillMaxSize().padding(bottom = 10.dp).verticalScroll(rememberScrollState())) {
                                if (showUpdatePrompt) {
                                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        val promptEdges = Modifier.focusProperties { up = homeFocus; downTo(heroFocus) }
                                        Text("新版本 ${updateState.release?.tag}", color = c.accent, fontSize = 13.sp)
                                        PrismAction("查看更新", { updateDialogOpen = true },
                                            modifier = Modifier.focusAnchor(updatePromptFocus).then(promptEdges))
                                        PrismAction("忽略", {
                                            appUpdates.skip()
                                            pageScope.launch { withFrameNanos { }; heroFocus.requestIfAttached() }
                                        }, promptEdges)
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                PrismHero(
                                    song = queue.getOrNull(queueIndex),
                                    onContinue = {
                                        if (queue.isNotEmpty()) playerOpen = true
                                        else if (activePlugins.isEmpty()) { settingsSection = "音源"; page = "plugins" }
                                        else page = "search"
                                    },
                                    continueAnchor = heroFocus,
                                    /* Keep the card clear of the clipped page edge so the focus
                                       ring reads as a frame, not as something the screen cut. */
                                    modifier = Modifier.weight(1f).padding(start = 10.dp).focusProperties {
                                        up = if (showUpdatePrompt && updatePromptFocus.attached) updatePromptFocus.requester else homeFocus
                                    }.then(downToCard(0, 0)),
                                    hasSources = activePlugins.isNotEmpty(),
                                    favorite = queue.getOrNull(queueIndex)?.let { s -> libraryState.favorites.any { it.key == s.key } } == true,
                                    playing = playing && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED,
                                    onLongPress = queue.getOrNull(queueIndex)?.let { current -> {
                                        menuReturnFocus = heroFocus.requester; trackMenu = TrackMenuTarget(current)
                                    } },
                                )
                                /* The rail carries what the hero cannot: the strand you were just
                                   listening to, and what is moving right now. The padding keeps
                                   the cards' focus scale inside the page clip, like the hero. */
                                Column(Modifier.width(288.dp).height(172.dp).padding(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AuraRadioCard(ActiveQueue.radio, radioLoading, radioStatus, { startRadio() },
                                        Modifier.weight(1f).focusAnchor(radioCardFocus).focusProperties {
                                            up = if (showUpdatePrompt && updatePromptFocus.attached) updatePromptFocus.requester else homeFocus
                                        }, onLongPress = { radioMenuOpen = true })
                                    val hotChart = charts.firstOrNull()
                                    AuraHotCard(
                                        title = hotChart?.optString("title").orEmpty(),
                                        songs = hotChart?.let { chartPreviews[it.optString("id")].orEmpty() }.orEmpty(),
                                        onOpen = {
                                            val target = charts.firstOrNull()
                                            val p = chartSource
                                            if (p == null) { settingsSection = "音源"; page = "plugins" }
                                            else {
                                                page = "discover"
                                                if (target != null) openChart(p, target)
                                                else if (!chartLoading) lifecycleScope.launch { loadCharts(p) }
                                            }
                                        },
                                        status = if (chartSource == null) "添加支持榜单的音源" else if (chartLoading) "正在更新，确定进入发现" else "暂未获取榜单，确定重试",
                                        modifier = Modifier.weight(1f).focusProperties {
                                            upTo(radioCardFocus)
                                        }.then(downToCard(0, 0)),
                                        onLongPress = { val chart = charts.firstOrNull(); val source = chartSource
                                            if (chart != null && source != null) subjectMenu = SavedSubject(source.id, "chart", chart, source.displayName, primaryKeys(source.metadata))
                                        },
                                    )
                                }
                                }
                                Spacer(Modifier.height(14.dp))

                                if (activePlugins.isEmpty()) {
                                    AuraEmptyState("你的音乐世界，从这里开始", "添加你信任的 MusicFree 插件，即可搜索与发现音乐", "添加音源", { settingsSection = "音源"; page = "plugins" })
                                }
                                if (homeShelves.isEmpty()) {
                                    Text("把喜欢的音乐留在首页，随时接着听", color = c.muted, fontSize = 13.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        PrismAction("去发现挑选音乐", { page = "discover" }, primary = true)
                                        PrismAction("管理首页内容", { settingsSection = "推荐"; page = "plugins" })
                                    }
                                }
                                /* 每一行都是独立的横向行，而几何搜索只看得到视口里的节点：下一行
                                   还在屏幕外时按「下」，它找不到正下方的候选，就横向落到同排最右侧
                                   那张卡片上——方向键看起来"崩了"。所以行与行之间的上下落点在这里
                                   点名：列号相同，下一行更短时取它的最后一列。 */
                                visibleShelves.forEachIndexed { shelfIndex, shelf ->
                                    val shelfActionFocus = remember(shelf.id) { FocusRequester() }
                                    val (shelfReveal, revealShelf) = rememberRowRevealer(pageScope)
                                    val rowState = rowStates.getOrPut(shelf.id) { LazyListState() }
                                    PrismShelfHeader(
                                        shelf.title, shelf.songs.size, "全部播放", { playQueue(shelf.songs) },
                                        actionFocus = shelfActionFocus,
                                        /* 页头按钮在行尾：按「下」先回自己这一行的首卡，
                                           而不是横着跳到最右边那张。 */
                                        actionEdges = downToCard(shelfIndex, 0),
                                    )
                                    LazyRow(shelfReveal, state = rowState, horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        /* 垂直 8dp 留给聚焦放大（1.035 倍约溢出 3dp）：焦点把行带到
                                           视口底边时，卡片缩放后的完整边框仍留在裁剪区内。 */
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
                                        itemsIndexed(shelf.songs.take(20)) { index, song ->
                                            val focus = shelfCards[shelfIndex][index]
                                            PrismCoverCard(song, index, { playSingle(song) }, Modifier.focusRequester(focus).focusProperties {
                                                up = shelfActionFocus
                                                if (shelfIndex == shelfCards.lastIndex && queue.isNotEmpty()) down = miniFocus
                                            }.then(downToCard(shelfIndex + 1, index))
                                                /* 迷你条按上要回到此前卡片，而不是页面入口；同时让页面
                                                   滚出整行，卡片边框完整可见（横向行的 bringIntoView
                                                   请求会被行自身吃掉，必须从行触发）。 */
                                                .onFocusChanged { if (it.isFocused) { bodyFocusMemory.value = focus; revealShelf() } },
                                                current = queue.getOrNull(queueIndex)?.key == song.key,
                                                onLongPress = {
                                                    menuReturnFocus = focus
                                                    trackMenu = TrackMenuTarget(song, if (shelf.id in setOf("favorites", "recent")) shelf.id else "playlist:${shelf.id}")
                                                })
                                        }
                                    }
                                    Spacer(Modifier.height(24.dp))
                                }
                            }
                            }
                            "discover" -> Column(Modifier.fillMaxSize()) {
                                val plugin = chartSource
                                val selectedChart = chartSelection
                                /* A detail page keeps its head out of the scrolling list, so the
                                   geometric search from here never reached the bar and Up stranded
                                   the ring on 播放全部. */
                                val headUp = Modifier.focusProperties { up = discoverFocus }
                                if (browseItem != null) {
                                    val item = browseItem
                                    if (item != null) {
                                        val artistPlayAllFocus = remember(item, browsePluginId) { FocusAnchor() }
                                        val firstSongFocus = remember(item, browsePluginId) { FocusAnchor() }
                                        fun focusSongSection(index: Int, target: FocusAnchor) {
                                            if (target.requestIfAttached()) return
                                            pageScope.launch {
                                                browseSongListState.scrollToItem(index)
                                                repeat(4) {
                                                    withFrameNanos { }
                                                    if (target.requestIfAttached()) return@launch
                                                }
                                            }
                                        }
                                        val sourceName = state.plugins.firstOrNull { it.id == browsePluginId }?.displayName.orEmpty()
                                        val eyebrow = when (browseKind) { "artist" -> "ARTIST / 歌手"; "album" -> "ALBUM / 专辑"; else -> "PLAYLIST / 歌单" }
                                        val artwork = browseInfo?.optString("artwork").orEmpty().ifBlank { item.optString("artwork") }
                                        val publish = browseInfo?.optString("publishDate").orEmpty().ifBlank {
                                            browseInfo?.optLong("publishTime", 0L)?.takeIf { it > 0 }
                                                ?.let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(it)) }.orEmpty()
                                        }.ifBlank { item.optString("publishDate").ifBlank { item.optString("description") } }
                                        val subjectMeta = buildList {
                                            when (browseKind) {
                                                "artist" -> {
                                                    if (browseSongs.isNotEmpty()) add("${browseSongs.size} 首热门")
                                                    /* Only cards the source actually returned as albums are
                                                       counted; a source that answers with songs gets no
                                                       album claim here. */
                                                    if (browseAlbums.isNotEmpty()) {
                                                        add(if (browseAlbums.size >= 60) "60+ 张专辑" else "${browseAlbums.size} 张专辑")
                                                    }
                                                }
                                                "album" -> {
                                                    item.optString("artist").takeIf { it.isNotBlank() }?.let(::add)
                                                    if (publish.isNotBlank()) add(publish)
                                                    browseInfo?.optString("company").orEmpty().takeIf { it.isNotBlank() }?.let(::add)
                                                }
                                                else -> if (browseSongs.isNotEmpty()) add("${browseSongs.size} 首")
                                            }
                                            if (sourceName.isNotBlank()) add(sourceName)
                                        }.joinToString(" · ")
                                        val description = browseInfo?.optString("description").orEmpty().ifBlank {
                                            if (browseKind == "artist") "" else item.optString("description")
                                        }
                                        LazyColumn(state = browseSongListState, modifier = Modifier.weight(1f)) {
                                            item {
                                                PrismDetailHero(
                                                    title = browseTitle, eyebrow = eyebrow, meta = subjectMeta,
                                                    description = description, artwork = artwork,
                                                    portrait = browseKind == "artist",
                                                    modifier = Modifier.padding(bottom = 20.dp),
                                                ) {
                                                    val keys = state.plugins.firstOrNull { it.id == browsePluginId }?.metadata?.let(::primaryKeys) ?: listOf("id")
                                                    val saved = libraryState.subjects.any { it.key == SavedSubject(browsePluginId, browseKind, item, primaryKey = keys).key }
                                                    /* The hero sits inside the scrolling list, so the head's Up has
                                                       no geometric neighbour to the bar; name it. */
                                                    val heroUp = Modifier.focusProperties { up = discoverFocus }
                                                    /* 播放全部 belongs to a song list. An artist page has
                                                       two lists (albums and songs), so the button moves down
                                                       beside 热门歌曲 instead of silently choosing one. */
                                                    val playAllInHero = browseKind != "artist" && browseSongs.isNotEmpty()
                                                    PrismAction(if (saved) "已收藏" else "收藏", {
                                                        toggleSubject(browsePluginId, browseKind, item, sourceName)
                                                    }, if (!playAllInHero) heroUp.focusAnchor(detailHeaderFocus) else heroUp,
                                                        icon = if (saved) "heartFilled" else "heart")
                                                    if (playAllInHero)
                                                        PrismAction("播放全部", { playQueue(browseSongs.toList()) }, heroUp.focusAnchor(detailHeaderFocus), primary = true)
                                                    if (queue.isNotEmpty()) PrismAction("打开播放器", { playerOpen = true }, heroUp)
                                                }
                                            }
                                            if (browseKind == "artist" && (browseAlbums.isNotEmpty() || browseAlbumStatus.isNotBlank())) {
                                                item { PrismShelfHeader("专辑", browseAlbums.size.takeIf { it > 0 }, "", {}) }
                                                if (browseAlbums.isEmpty()) item {
                                                    Text(browseAlbumStatus, color = c.muted, fontSize = 13.sp)
                                                } else item {
                                                    val (albumReveal, revealAlbums) = rememberRowRevealer(pageScope)
                                                    val firstSongFocus = browseSongs.firstOrNull()?.let { trackFocus(it.key) }
                                                    val (albumLayout, focusAlbumExit) = rememberVerticalFocusSearch()
                                                    val albumNavigation = albumLayout.onPreviewKeyEvent { event ->
                                                        if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionDown || firstSongFocus == null) return@onPreviewKeyEvent false
                                                        focusAlbumExit(event.key) { firstSongFocus }
                                                    }
                                                    LazyRow(albumReveal.then(albumNavigation), state = browseAlbumRowState, horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
                                                        itemsIndexed(browseAlbums) { index, album ->
                                                            val albumId = album.optString("id")
                                                            val albumMeta = listOf(
                                                                album.optString("artist"),
                                                                album.optString("publishDate").ifBlank { album.optString("description") },
                                                                album.optInt("size", 0).takeIf { it > 0 }?.let { "$it 首" }.orEmpty(),
                                                            ).filter { it.isNotBlank() }.joinToString(" · ")
                                                            PrismSheetCard(
                                                                album.optString("title").ifBlank { album.optString("name").ifBlank { "未知专辑" } },
                                                                album.optString("artwork"),
                                                                albumMeta,
                                                                Modifier.focusRequester(detailFocus("album:$browsePluginId:$albumId")).then(
                                                                    if (index == 0) Modifier.focusProperties {
                                                                        up = if (detailHeaderFocus.attached) detailHeaderFocus.requester else discoverFocus
                                                                    } else Modifier
                                                                ).onFocusChanged { if (it.isFocused) revealAlbums() },
                                                                onLongPress = { state.plugins.firstOrNull { it.id == browsePluginId }?.let {
                                                                    showSubjectMenu(it, "album", album, detailFocus("album:$browsePluginId:$albumId"))
                                                                } },
                                                            ) {
                                                                state.plugins.firstOrNull { it.id == browsePluginId }?.let { openBrowse(it, album, "album") }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            item {
                                                PrismShelfHeader(
                                                    if (browseKind == "artist") "热门歌曲" else "歌曲",
                                                    browseSongs.size.takeIf { it > 0 },
                                                    if (browseKind == "artist" && browseSongs.isNotEmpty()) "播放全部" else "",
                                                    { if (browseSongs.isNotEmpty()) playQueue(browseSongs.toList()) },
                                                    actionEdges = Modifier.focusAnchor(artistPlayAllFocus)
                                                        .focusProperties {
                                                            if (browseAlbums.isEmpty()) up = if (detailHeaderFocus.attached) detailHeaderFocus.requester else discoverFocus
                                                        }
                                                        .onPreviewKeyEvent { event ->
                                                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                                                focusSongSection(detailSongRowOffset(), firstSongFocus)
                                                                true
                                                            } else false
                                                        },
                                                )
                                            }
                                            if (browseSongs.isEmpty() && browseStatus.isNotBlank()) item {
                                                Text(browseStatus, color = c.muted, fontSize = 13.sp)
                                            }
                                            itemsIndexed(browseSongs) { index, song ->
                                                val rowFocus = trackFocus(song.key)
                                                detailCardFocus["song:${song.key}"] = rowFocus
                                                PrismTrackRow(song, index, { playSingle(song) },
                                                    modifier = Modifier.focusRequester(rowFocus).then(
                                                        if (index == 0) Modifier.focusAnchor(firstSongFocus)
                                                            .onPreviewKeyEvent { event ->
                                                                if (browseKind == "artist" && event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                                                                    focusSongSection(detailSongRowOffset() - 1, artistPlayAllFocus)
                                                                    true
                                                                } else false
                                                            }
                                                            .focusProperties {
                                                                if (browseKind != "artist" && browseAlbums.isEmpty()) {
                                                                    up = if (detailHeaderFocus.attached) detailHeaderFocus.requester else discoverFocus
                                                                }
                                                            } else Modifier
                                                    ),
                                                    current = queue.getOrNull(queueIndex)?.key == song.key,
                                                    qualityTag = qualityTagOf(song), showMv = song.pluginId in mvCapableIds,
                                                    /* Reaching the end also requests the next page. */
                                                    onFocus = {
                                                        detailFocusMemory.value = rowFocus to (detailSongRowOffset() + index)
                                                        if (index == browseSongs.lastIndex && browseMore && !browseLoading) loadBrowse(true)
                                                    },
                                                    onLongPress = { trackMenu = TrackMenuTarget(song, originFocus = "song:${song.key}") })
                                            }
                                            if (browseLoading && browseSongs.isNotEmpty()) item {
                                                Text("正在加载更多…", color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                                            }
                                            if (browseMore) item {
                                                PrismAction(if (browseLoading) "正在加载…" else "加载更多", { if (!browseLoading) loadBrowse(true) })
                                            }
                                        }
                                    }
                                } else if (selectedChart != null) {
                                    PrismPageHead(selectedChart.optString("title").ifBlank { "榜单" }) {
                                        if (chartSongs.isNotEmpty()) PrismAction("播放全部", { playQueue(chartSongs.toList()) }, Modifier.focusAnchor(detailHeaderFocus).then(headUp))
                                        if (queue.isNotEmpty()) PrismAction("打开播放器", { playerOpen = true }, headUp)
                                    }
                                    if (chartSongs.isEmpty()) Text(chartDetailStatus, color = c.muted, fontSize = 13.sp)
                                    else {
                                        LazyColumn(state = chartSongListState, modifier = Modifier.weight(1f)) {
                                            itemsIndexed(chartSongs) { index, song ->
                                            val rowFocus = if (index == 0) discoverEntryFocus.requester else trackFocus(song.key)
                                            detailCardFocus["song:${song.key}"] = rowFocus
                                            PrismTrackRow(song, index, { playSingle(song) }, modifier = if (index == 0) Modifier.focusAnchor(discoverEntryFocus).focusProperties { if (detailHeaderFocus.attached) up = detailHeaderFocus.requester } else Modifier.focusRequester(rowFocus), current = queue.getOrNull(queueIndex)?.key == song.key, qualityTag = qualityTagOf(song), showMv = song.pluginId in mvCapableIds, onFocus = { detailFocusMemory.value = rowFocus to index }, onLongPress = { trackMenu = TrackMenuTarget(song, originFocus = "song:${song.key}") })
                                            }
                                        }
                                    }
                                } else {
                                val focusScope = rememberCoroutineScope()
                                val cardCoordinates = remember { mutableMapOf<FocusRequester, LayoutCoordinates>() }
                                val (rowLayout, focusDiscoverRow) = rememberVerticalFocusSearch()
                                fun trackCard(requester: FocusRequester) = Modifier.keepFocusVisible()
                                    .onPlaced { cardCoordinates[requester] = it }
                                fun centerX(requester: FocusRequester): Float? = cardCoordinates[requester]
                                    ?.takeIf { it.isAttached }?.let { it.positionInRoot().x + it.size.width / 2f }
                                val recommendationRow = remember(recommendationSource?.id) { LazyListState() }
                                val seedRow = remember { LazyListState() }
                                val artistRow = remember { LazyListState() }
                                val albumRow = remember { LazyListState() }
                                /* 推荐 shelf 被滚出视口后节点被回收，锚点 attached 为假；
                                   边界按键先滚回页首再交焦，避免「再也回不去」的焦点夹层。 */
                                fun revealRecommendation(target: FocusAnchor, fallback: FocusAnchor) {
                                    focusScope.coroutineContext.cancelChildren()
                                    focusScope.launch {
                                        discoverListState.scrollToItem(0)
                                        repeat(4) { withFrameNanos { } }
                                        if (!target.requestIfAttached()) fallback.requestIfAttached()
                                    }
                                }
                                val belowUpReveals = Modifier.onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && recommendationSource != null && recommendations.isEmpty() && !recommendEntryFocus.attached) {
                                        revealRecommendation(recommendFirstCardFocus, recommendEntryFocus); true
                                    } else false
                                }
                                val artistsFirst = savedArtists.isNotEmpty()
                                // Adjacent rows have different card widths; choose by screen position, not item number.
                                val recommendCards = if (recommendationSource != null)
                                    recommendations.map { detailFocus("recommend:${recommendationSource.id}:${it.optString("id")}") }
                                else emptyList()
                                val seedCards = artistSeeds.map { detailFocus("seed:${it.key}") }
                                val artistCards = savedArtists.map { detailFocus("${it.kind}:${it.pluginId}:${it.data.optString("id")}") }
                                val albumCards = savedAlbums.map { detailFocus("${it.kind}:${it.pluginId}:${it.data.optString("id")}") }
                                val chartCardRows = charts.chunked(3).map { group -> group.map { detailFocus("chart:${it.optString("id")}") } }
                                val shelfRows = buildList {
                                    if (recommendCards.isNotEmpty()) add(CardFocusRow(recommendCards, recommendationRow))
                                    if (seedCards.isNotEmpty()) add(CardFocusRow(seedCards, seedRow))
                                    if (artistCards.isNotEmpty()) add(CardFocusRow(artistCards, artistRow))
                                    if (albumCards.isNotEmpty()) add(CardFocusRow(albumCards, albumRow))
                                    chartCardRows.forEach { add(CardFocusRow(it)) }
                                }
                                fun stepRow(direction: Key, row: List<FocusRequester>, column: Int): Modifier {
                                    val ordinal = shelfRows.indexOfFirst { it.cards === row }
                                    if (ordinal < 0) return Modifier
                                    val target = shelfRows.getOrNull(ordinal + if (direction == Key.DirectionUp) -1 else 1)
                                        ?: return Modifier.focusProperties {
                                            if (direction == Key.DirectionUp) {
                                                up = if (recommendEntryFocus.attached) recommendEntryFocus.requester else discoverFocus
                                            } else down = miniFocus
                                        }
                                    return Modifier.onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown || event.key != direction) return@onPreviewKeyEvent false
                                        val x = row.getOrNull(column)?.let(::centerX) ?: return@onPreviewKeyEvent false
                                        focusScope.coroutineContext.cancelChildren()
                                        focusDiscoverRow(direction) {
                                            val visible = target.horizontal?.layoutInfo?.visibleItemsInfo?.map { it.index }
                                                ?: target.cards.indices.toList()
                                            visible.mapNotNull { index ->
                                                target.cards.getOrNull(index)?.let { card -> centerX(card)?.let { card to kotlin.math.abs(it - x) } }
                                            }.minByOrNull { it.second }?.first
                                        }
                                    }
                                }
                                /* MusicFree has no global artist/album listing to fill the
                                   sketch's grids with, so these shelves show what you kept from
                                   a detail page; the charts below remain source-driven. */
                                LazyColumn(state = discoverListState, modifier = Modifier.weight(1f).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(22.dp),
                                    /* 末排榜单行在最大滚动时贴着视口底边，聚焦边框没有呼吸空间；
                                       底部留白让最后一行能完整滚入。 */
                                    contentPadding = PaddingValues(bottom = 14.dp)) {
                                    if (recommendationSource != null) item {
                                        RecommendationShelf(recommendationSource, discoverFocus, recommendEntryFocus, recommendFirstCardFocus, discoverBelowFocus,
                                            cardFocus = { sheet -> detailFocus("recommend:${recommendationSource.id}:${sheet.optString("id")}") },
                                            edges = { index -> trackCard(recommendCards[index]).then(stepRow(Key.DirectionDown, recommendCards, index)) }, rowState = recommendationRow, modifier = rowLayout,
                                            onMenu = { item, focus -> showSubjectMenu(recommendationSource, "sheet", item, focus) }) { openBrowse(recommendationSource, it, "sheet") }
                                    }
                                    if (artistSeeds.isNotEmpty()) {
                                        item { PrismShelfHeader("从常听歌手继续", null, "", {}) }
                                        item {
                                            LazyRow(state = seedRow, modifier = rowLayout, horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
                                                itemsIndexed(artistSeeds) { index, seed ->
                                                    PrismSheetCard(seed.artist, seed.data.optString("artistArtwork"), "歌手作品 · ${seed.sourceLabel}", portrait = true,
                                                        modifier = Modifier.focusRequester(seedCards[index]).then(trackCard(seedCards[index]))
                                                            .then(if (index == 0) Modifier.focusAnchor(discoverBelowFocus) else Modifier)
                                                            .then(stepRow(Key.DirectionUp, seedCards, index))
                                                            .then(stepRow(Key.DirectionDown, seedCards, index))
                                                            .then(belowUpReveals),
                                                        onLongPress = {
                                                            openSubject(seed, "artist", "seed:${seed.key}", menu = true)
                                                        }) { openSubject(seed, "artist", "seed:${seed.key}") }
                                                }
                                            }
                                        }
                                    }
                                    if (artistsFirst) {
                                        item { PrismShelfHeader("歌手", null, "", {}) }
                                        item {
                                            LazyRow(state = artistRow, modifier = rowLayout, horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
                                                itemsIndexed(savedArtists) { index, subject ->
                                                    PrismSheetCard(subject.title, subject.artwork, "${subject.kindLabel} · ${subject.sourceName.ifBlank { "已收藏" }}", portrait = subject.kind == "artist",
                                                        modifier = Modifier.focusRequester(artistCards[index]).then(trackCard(artistCards[index]))
                                                            .then(if (artistSeeds.isEmpty() && index == 0) Modifier.focusAnchor(discoverBelowFocus) else Modifier)
                                                            .then(stepRow(Key.DirectionUp, artistCards, index))
                                                            .then(stepRow(Key.DirectionDown, artistCards, index))
                                                            .then(if (artistSeeds.isEmpty()) belowUpReveals else Modifier)
                                                            , onLongPress = {
                                                                menuReturnFocus = detailFocus("${subject.kind}:${subject.pluginId}:${subject.data.optString("id")}")
                                                                subjectMenu = subject
                                                            }) {
                                                        state.plugins.firstOrNull { it.id == subject.pluginId }?.let { openBrowse(it, subject.data, subject.kind) }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (savedAlbums.isNotEmpty()) {
                                        item { PrismShelfHeader("专辑", null, "", {}) }
                                        item {
                                            LazyRow(state = albumRow, modifier = rowLayout, horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
                                                itemsIndexed(savedAlbums) { index, subject ->
                                                    PrismSheetCard(subject.title, subject.artwork, "${subject.kindLabel} · ${subject.sourceName.ifBlank { "已收藏" }}", portrait = subject.kind == "artist",
                                                        modifier = Modifier.focusRequester(albumCards[index]).then(trackCard(albumCards[index]))
                                                            .then(if (artistSeeds.isEmpty() && savedArtists.isEmpty() && index == 0) Modifier.focusAnchor(discoverBelowFocus) else Modifier)
                                                            .then(stepRow(Key.DirectionUp, albumCards, index))
                                                            .then(stepRow(Key.DirectionDown, albumCards, index))
                                                            .then(if (artistSeeds.isEmpty() && savedArtists.isEmpty()) belowUpReveals else Modifier)
                                                            , onLongPress = {
                                                                menuReturnFocus = detailFocus("${subject.kind}:${subject.pluginId}:${subject.data.optString("id")}")
                                                                subjectMenu = subject
                                                            }) {
                                                        state.plugins.firstOrNull { it.id == subject.pluginId }?.let { openBrowse(it, subject.data, subject.kind) }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    item { PrismShelfHeader("音乐榜单", null, "", {}) }
                                    if (charts.isEmpty()) item {
                                        if (recommendationSource == null && shelfRows.isEmpty()) {
                                            PrismEmptyState(chartStatus, "发现设置", { settingsSection = "推荐"; page = "plugins" },
                                                Modifier.focusAnchor(discoverEntryFocus).focusProperties { up = discoverFocus; down = miniFocus })
                                        } else Text(chartStatus, color = c.muted, fontSize = 13.sp)
                                    }
                                    else itemsIndexed(charts.chunked(3)) { chunkIndex, group ->
                                        val rowCards = chartCardRows.getOrNull(chunkIndex).orEmpty()
                                        Row(rowLayout.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                            group.forEachIndexed { column, chart ->
                                                val chartId = chart.optString("id")
                                                val cardFocus = detailFocus("chart:$chartId")
                                                val cardRow = discoverChartsOffset + chunkIndex
                                                PrismChartCard(
                                                    chart.optString("title").ifBlank { "榜单" },
                                                    chart.optString("coverImg").ifBlank { chart.optString("artwork") },
                                                    chartPreviews[chartId].orEmpty(),
                                                    Modifier.weight(1f).focusRequester(cardFocus).then(trackCard(cardFocus))
                                                        .then(if (artistSeeds.isEmpty() && savedArtists.isEmpty() && savedAlbums.isEmpty() && chart === charts.firstOrNull()) Modifier.focusAnchor(discoverBelowFocus) else Modifier)
                                                        .then(stepRow(Key.DirectionUp, rowCards, column))
                                                        .then(stepRow(Key.DirectionDown, rowCards, column))
                                                        .onFocusChanged {
                                                            if (it.isFocused) {
                                                                discoverFocusMemory.value = cardFocus to cardRow
                                                            }
                                                        }
                                                        .then(if (artistSeeds.isEmpty() && savedArtists.isEmpty() && savedAlbums.isEmpty() && chunkIndex == 0) belowUpReveals else Modifier)
                                                        , onLongPress = {
                                                            menuReturnFocus = cardFocus
                                                            plugin?.let { subjectMenu = SavedSubject(it.id, "chart", chart, it.displayName, primaryKeys(it.metadata)) }
                                                        },
                                                ) { plugin?.let { openChart(it, chart) } }
                                            }
                                            repeat(3 - group.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                }
                                }
                            }
                            "search" -> Column(Modifier.fillMaxSize()) {
                                val focusScope = searchNavigationScope
                                val (searchRowLayout, focusSearchRow) = rememberVerticalFocusSearch()
                                var moreFocused by remember(searchTerm, searchType, pluginSignature, searchSources) { mutableStateOf(false) }
                                val showMore = hasMore || moreFocused
                                val moreLabel = if (busy) "正在加载…" else if (hasMore) "加载更多" else "已加载全部"
                                fun focusLastResult() {
                                    val last = if (searchType == "music") results.lastIndex else entities.lastIndex
                                    if (last < 0) { searchInputFocus.requestIfAttached(); return }
                                    val target = if (searchType == "music") {
                                        if (last == 0) searchResultsFocus.requester else trackFocus(results[last].key)
                                    } else entities[last].let { detailFocus("$searchType:${it.pluginId}:${it.data.optString("id")}") }
                                    if (!focusSearchRow(Key.DirectionUp) { target }) searchInputFocus.requestIfAttached()
                                }
                                // Keep the focused footer until focus has actually returned to a result.
                                LaunchedEffect(hasMore, busy, moreFocused) {
                                    if (!hasMore && !busy && moreFocused && !playerOpen) focusLastResult()
                                }
                                val moreEdges = Modifier.onFocusChanged { moreFocused = it.isFocused }
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                                            focusScope.coroutineContext.cancelChildren()
                                            focusLastResult()
                                            true
                                        } else false
                                    }.focusProperties { down = miniFocus }
                                /* Pressing 搜索 is the end of typing. Leaving the IME up covers half the
                                   results you just asked for — the same mistake as opening a page and
                                   covering it with a dialog. */
                                val ime = LocalSoftwareKeyboardController.current
                                val firstSuggestionFocus = remember { FocusAnchor() }
                                val clearHistoryFocus = remember { FocusAnchor() }
                                // Source code/configuration and selection own the cache; each type has its own results.
                                var hotTerms by remember(pluginSignature, searchSources) { mutableStateOf<Map<String, List<SearchSuggestion>>>(emptyMap()) }
                                var hotLoading by remember(pluginSignature, searchSources) { mutableStateOf(false) }
                                LaunchedEffect(pluginSignature, searchSources, searchType) {
                                    if (hotTerms.containsKey(searchType)) { hotLoading = false; return@LaunchedEffect }
                                    val type = searchType
                                    val cacheKey = "${pluginSignature.hashCode()}:${searchSources.sorted()}:$type"
                                    hotSearchCache[cacheKey]?.takeIf { System.currentTimeMillis() - it.first in 0 until 10 * 60_000 }?.let {
                                        hotTerms = hotTerms + (type to it.second); hotLoading = false; return@LaunchedEffect
                                    }
                                    val providers = activePlugins.filter { it.capabilities.has("hotSearch") && (searchSources.isEmpty() || it.id in searchSources) }.take(3)
                                    hotLoading = providers.isNotEmpty()
                                    try {
                                        var received = emptyList<SearchSuggestion>()
                                        coroutineScope { providers.map { plugin -> async {
                                            val items = try {
                                                val result = withTimeout(8000) {
                                                    plugins.client.call(plugin.source, plugin.variables, "getHotSearch", JSONArray().put(type), plugin.id)
                                                } as? JSONArray ?: JSONArray()
                                                SearchSuggestions.parse(result, plugin.displayName)
                                            } catch (e: kotlinx.coroutines.TimeoutCancellationException) { emptyList() }
                                              catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                              catch (_: Exception) { emptyList() }
                                            received = (received + items).distinctBy { it.keyword.lowercase(java.util.Locale.ROOT) }.take(12)
                                            hotTerms = hotTerms + (type to received)
                                        } }.awaitAll() }
                                        if (received.isNotEmpty()) hotSearchCache[cacheKey] = System.currentTimeMillis() to received
                                        while (hotSearchCache.size > 16) hotSearchCache.remove(hotSearchCache.keys.first())
                                    } finally { hotLoading = false }
                                }
                                val visibleHot = hotTerms[searchType].orEmpty()
                                val typeHistory = searchHistory[searchType].orEmpty()
                                val libraryTerms = remember(libraryState, searchType) {
                                    SearchSuggestions.local(searchType, libraryState)
                                }
                                val localTerms = remember(keyword, searchHistory, libraryTerms, searchType) {
                                    val history = typeHistory.map { SearchSuggestion(it, badge = "历史") }
                                    (history + libraryTerms).filter { keyword.isBlank() || matchesSearchHistory(it.keyword, keyword) }
                                        .distinctBy { it.keyword.lowercase(java.util.Locale.ROOT) }.take(12)
                                }
                                val availableSuggestions = remember(keyword, visibleHot, localTerms) {
                                    (if (keyword.isBlank()) visibleHot + localTerms else localTerms)
                                        .distinctBy { it.keyword.lowercase(java.util.Locale.ROOT) }.take(12)
                                }
                                // Network arrivals must not replace the card currently under the remote.
                                var pinnedSuggestions by remember(searchType, keyword, pluginSignature, searchSources) {
                                    mutableStateOf<List<SearchSuggestion>?>(null)
                                }
                                val suggestionRows = pinnedSuggestions ?: availableSuggestions
                                val suggestionsVisible = searchSuggestionsVisible
                                /* 下键总要落到真实节点：联想与结果都可能为空，逐个退让到迷你条。 */
                                val searchContentFocus = when {
                                    suggestionsVisible && firstSuggestionFocus.attached -> firstSuggestionFocus
                                    searchResultsFocus.attached -> searchResultsFocus
                                    searchMoreFocus.attached -> searchMoreFocus
                                    firstSuggestionFocus.attached -> firstSuggestionFocus
                                    else -> searchResultsFocus
                                }
                                LaunchedEffect(focusSearchResults, busy, results.isNotEmpty(), entities.isNotEmpty(), hasMore, navBarFocused, searchFiltersOpen) {
                                    if (!focusSearchResults || navBarFocused || playerOpen || searchFiltersOpen) return@LaunchedEffect
                                    if (results.isEmpty() && entities.isEmpty() && busy) return@LaunchedEffect
                                    if (results.isNotEmpty() || entities.isNotEmpty() || hasMore) {
                                        searchListState.scrollToItem(0)
                                        /* 首条可能同帧还没挂上；多等几帧，失败退回输入框。 */
                                        repeat(12) {
                                            withFrameNanos { }
                                            if (searchResultsFocus.requestIfAttached()) { focusSearchResults = false; return@LaunchedEffect }
                                        }
                                        searchInputFocus.requestIfAttached()
                                    } else {
                                        withFrameNanos { }; searchInputFocus.requestIfAttached()
                                    }
                                    focusSearchResults = false
                                }
                                /* Only offer types some enabled source can answer. A type that every
                                   source ignores would always land on an empty list. */
                                val searchTypeOptions = searchTypeChoices(activePlugins)
                                LaunchedEffect(searchTypeOptions) {
                                    if (searchTypeOptions.none { it.first == searchType }) searchType = "music"
                                }
                                /* 输入框下行落到当前激活的 tab 而不是第一个：进入 tab 行本身不切换
                                   类型，横向移动才实时切换。
                                   锚点按类型常驻：`searchTypeOptions` 会随启用音源的能力变化，
                                   若跟着它重建 FocusRequester，旧节点释放后结果行还握着旧值，
                                   按键时 Compose 会抛「FocusRequester is not initialized」。 */
                                val chipAnchors = remember { mutableMapOf<String, FocusAnchor>() }
                                fun chipAnchor(type: String): FocusAnchor = chipAnchors.getOrPut(type) { FocusAnchor() }
                                fun chipRequester(type: String): FocusRequester? =
                                    chipAnchor(type).takeIf { it.attached }?.requester
                                /* 结果首行锚点随滚动被回收后 down 边消失，几何搜索会把焦点带到
                                   同排右侧（音源入口）或任意卡片；按 down 时先滚回页首再点名。 */
                                fun revealOnDown(target: FocusAnchor): Modifier = Modifier.onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && !target.attached) {
                                        focusScope.coroutineContext.cancelChildren()
                                        focusScope.launch {
                                            val reached = target.requester.requestAfterScroll {
                                                if (!suggestionsVisible && (results.isNotEmpty() || entities.isNotEmpty())) searchListState.scrollToItem(0)
                                            }
                                            /* 迷你条是这一屏最后的落点，它总在。 */
                                            if (!reached && !runCatching { miniFocus.requestFocus(); true }.getOrDefault(false)) {
                                                searchInputFocus.requestIfAttached()
                                            }
                                        }
                                        true
                                    } else false
                                }
                                // Keep the column across rows; the last row exits to the footer or mini player.
                                fun stepRows(index: Int, unit: Int, total: Int, requester: (Int) -> FocusRequester): Modifier =
                                    Modifier.onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        val delta = when (event.key) {
                                            Key.DirectionUp -> -1
                                            Key.DirectionDown -> 1
                                            else -> return@onPreviewKeyEvent false
                                        }
                                        val nextRow = index / unit + delta
                                        if (nextRow < 0) return@onPreviewKeyEvent false
                                        if (nextRow > (total - 1) / unit) {
                                            return@onPreviewKeyEvent focusSearchRow(Key.DirectionDown) {
                                                if (showMore) searchMoreFocus.requester else miniFocus
                                            }
                                        }
                                        val next = (nextRow * unit + index % unit).coerceAtMost(total - 1)
                                        focusScope.coroutineContext.cancelChildren()
                                        focusSearchRow(event.key) { requester(next) }
                                    }
                                /* Input first, then the source row. The source is a filter on the
                                   search, not a separate page — you pick it before you type, because
                                   switching it afterwards silently changes what you're looking at. */
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Input(keyword, { keyword = it }, "搜索歌曲、歌手、专辑或歌单",
                                        Modifier.weight(1f).focusAnchor(searchInputFocus).focusProperties {
                                            /* The list below changes shape, so name the first row
                                               explicitly instead of trusting geometry. While a
                                               search is still loading there is nothing below yet;
                                               letting the geometric search run would drop focus
                                               onto the mini player, which is hard to climb back
                                               from. Keep the remote in the field until a list
                                               exists. */
                                            /* 下键直达联想／结果；类型条从内容区按上回去。 */
                                            if (searchContentFocus.attached) down = searchContentFocus.requester
                                            else chipRequester(searchType)?.let { down = it }
                                            up = searchFocus
                                        }, manualKeyboard = true, onSubmit = { if (!busy && keyword.isNotBlank()) search(keyword) },
                                        downTarget = if (searchContentFocus.attached) searchContentFocus else chipAnchor(searchType))
                                    AuraIconButton("search", if (busy) "搜索中" else "搜索", {
                                        /* Do not clearFocus here: this button already holds focus, and
                                           clearing it leaves the whole screen without a focus target.
                                           Hiding the IME is enough to get it out of the way. */
                                        if (!busy) { ime?.hide(); if (keyword.isBlank()) openPhoneHub("search") else search(keyword) }
                                    }, Modifier.focusProperties {
                                        if (searchContentFocus.attached) down = searchContentFocus.requester
                                        up = searchFocus
                                    }.then(revealOnDown(searchContentFocus)), primary = true)
                                    AuraIconButton("qr", "手机遥控", { openPhoneHub("search") })
                                }
                                /* Filters are footnotes: a quiet link row under the field, not a strip
                                   of buttons competing with the thing you actually came to do. */
                                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("类型", color = c.muted, fontSize = 10.sp, modifier = Modifier.padding(end = 6.dp))
                                    searchTypeOptions.forEachIndexed { _, (type, label) ->
                                        /* 聚焦即切换：遥控落在 tab 上就实时生效，无需确定。结果与联想区的
                                           上键回到当前激活的 tab（聚焦已选中的 tab 不触发切换），再按上
                                           才到输入框，回去换类型不用多按一次下。 */
                                        PrismLink(label, {
                                            if (!busy && searchType != type) { searchType = type; results = emptyList(); entities = emptyList(); hasMore = false; if (keyword.isNotBlank()) search(keyword) }
                                        }, Modifier
                                            .focusAnchor(chipAnchor(type))
                                            .onFocusChanged { focused ->
                                                if (focused.isFocused && searchType != type) {
                                                    searchType = type; results = emptyList(); entities = emptyList(); hasMore = false
                                                    if (keyword.isNotBlank()) search(keyword, focusResults = false) else searchJob?.cancel()
                                                }
                                            }
                                            /* From the strip the remote should be able to leave upward as
                                               well as downward: geometric search would otherwise strand it
                                               in the row itself, since the field is the only thing above. */
                                            .focusProperties {
                                                if (searchInputFocus.attached) up = searchInputFocus.requester
                                                val target = if (searchFiltersOpen) searchChipFocus else searchContentFocus
                                                if (target.attached) down = target.requester
                                            }.then(revealOnDown(if (searchFiltersOpen) searchChipFocus else searchContentFocus)),
                                            active = searchType == type)
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Spacer(Modifier.weight(1f))
                                    PrismLink(if (searchFiltersOpen) "音源 ▴" else if (searchSources.isEmpty()) "全部音源 ▾" else "音源 ${searchSources.size} ▾",
                                        {
                                            /* Opening the row is an invitation to use it, so hand the remote to
                                               the chips — but only on the press that opened it. From a
                                               LaunchedEffect the same request re-fired whenever the page
                                               recomposed, so merely switching to 搜索 parked focus on the
                                               first chip and the bar's left/right walk stopped working. */
                                            val open = !searchFiltersOpen
                                            searchFiltersOpen = open
                                            if (open) focusSearchResults = false
                                            if (open) pageScope.launch {
                                                repeat(3) { withFrameNanos { } }
                                                searchChipFocus.requestIfAttached()
                                            }
                                        },
                                        Modifier.focusAnchor(searchSourceFocus).focusProperties {
                                            if (searchInputFocus.attached) up = searchInputFocus.requester
                                            /* 收起时正下方最近的是「清空历史」，先落它；没有历史或已出
                                               结果时它不存在，再退回内容区第一条。 */
                                            if (searchFiltersOpen && searchChipFocus.attached) down = searchChipFocus.requester
                                            else if (clearHistoryFocus.attached) down = clearHistoryFocus.requester
                                            else if (searchContentFocus.attached) down = searchContentFocus.requester
                                        }.then(revealOnDown(
                                            if (searchFiltersOpen) searchChipFocus
                                            else if (clearHistoryFocus.attached) clearHistoryFocus
                                            else searchContentFocus
                                        )),
                                        active = searchFiltersOpen || searchSources.isNotEmpty())
                                }
                                    if (searchFiltersOpen) {
                                    Spacer(Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("音源", color = c.muted, fontSize = 10.sp, modifier = Modifier.width(30.dp))
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                                            item { PrismAction(if (searchSources.isEmpty()) "✓ 全部音源" else "全部音源", {
                                                searchSources = emptySet(); focusSearchResults = false
                                                results = emptyList(); entities = emptyList(); hasMore = false
                                                if (keyword.isNotBlank()) search(keyword, focusResults = false)
                                            }, Modifier.focusAnchor(searchChipFocus).focusProperties { if (searchSourceFocus.attached) up = searchSourceFocus.requester }) }
                                            itemsIndexed(activePlugins) { chipIndex, p ->
                                                val platform = p.platform
                                                val name = p.displayName
                                                PlatformAction(platform, if (p.id in searchSources) "✓ $name" else name,
                                                    if (chipIndex == 0 && !searchChipFocus.attached) Modifier.focusAnchor(searchChipFocus) else Modifier) {
                                                    /* Checking sources is a browsing act: the remote stays on the
                                                       chips, not on the first result that re-arrives. */
                                                    searchSources = if (p.id in searchSources) searchSources - p.id else searchSources + p.id
                                                    selectedPlugin = p.id; focusSearchResults = false
                                                    results = emptyList(); entities = emptyList(); hasMore = false
                                                    if (keyword.isNotBlank()) search(keyword, focusResults = false)
                                                }
                                            }
                                            if (activePlugins.isEmpty()) item { PrismAction("添加音源", { settingsSection = "音源"; page = "plugins" }) }
                                        }
                                    }
                                    }
                                if (suggestionsVisible) {
                                    /* On a remote the suggestions are a list, not a row of chips: Down
                                       moves into it and OK runs that search. A table header above an
                                       empty table was noise, so it only exists with real results. */
                                    Spacer(Modifier.height(18.dp))
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(if (keyword.isNotBlank()) "搜索联想" else if (suggestionRows.any { it.badge == "热搜" }) when (searchType) {
                                            "artist" -> "热门歌手与最近搜索"; "album" -> "热门专辑与最近搜索"; "sheet" -> "热门歌单与最近搜索"; else -> "热搜与最近"
                                        } else when (searchType) {
                                            "artist" -> "常听歌手与最近搜索"; "album" -> "曲库专辑与最近搜索"; "sheet" -> "收藏歌单与最近搜索"; else -> "常听与最近"
                                        }, color = c.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.width(8.dp))
                                        if (hotLoading || suggestionRows.isNotEmpty()) Text(if (hotLoading) "正在获取热门内容…" else "确定即搜索", color = c.muted, fontSize = 11.sp)
                                        Spacer(Modifier.weight(1f))
                                        if (typeHistory.isNotEmpty()) {
                                            /* 联想区是独立焦点组，几何搜索进不去：上下都点名。 */
                                            PrismLink("清空历史", {
                                                searchHistory = searchHistory - searchType
                                                pinnedSuggestions = null
                                                saveSearchHistory()
                                                status = "已清空当前分类的搜索历史"
                                                pageScope.launch {
                                                    repeat(2) { withFrameNanos { } }
                                                    if (!firstSuggestionFocus.requestIfAttached() && !chipAnchor(searchType).requestIfAttached()) {
                                                        searchInputFocus.requestIfAttached()
                                                    }
                                                }
                                            },
                                                Modifier.focusAnchor(clearHistoryFocus).focusProperties {
                                                    upTo(searchSourceFocus, chipAnchor(searchType), searchInputFocus)
                                                    leftTo(chipAnchor(searchType), searchInputFocus)
                                                    if (searchContentFocus.attached) down = searchContentFocus.requester
                                                })
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    if (suggestionRows.isEmpty()) Text(
                                        if (keyword.isBlank()) "还没有搜索记录。用遥控器输入，或扫码用手机遥控搜索" else "没有匹配的联想，按搜索键直接搜索「$keyword」",
                                        color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                                    ) else Column(Modifier.weight(1f).onFocusChanged {
                                        pinnedSuggestions = if (it.hasFocus) pinnedSuggestions ?: availableSuggestions else null
                                    }.focusGroup().padding(bottom = 10.dp).verticalScroll(rememberScrollState()).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (keyword.isBlank() && searchType != "music") suggestionRows.chunked(4).forEachIndexed { rowIndex, group ->
                                            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                group.forEachIndexed { columnIndex, item ->
                                                    val focus = remember(item.keyword, item.badge, rowIndex, columnIndex) { FocusRequester() }
                                                    PrismSearchSuggestionCard(item, searchType, {
                                                        keyword = item.keyword; ime?.hide(); searchInputFocus.requestIfAttached(); if (!busy) search(item.keyword)
                                                    }, Modifier.weight(1f).focusRequester(focus)
                                                        .then(if (rowIndex == 0 && columnIndex == 0) Modifier.focusAnchor(firstSuggestionFocus) else Modifier)
                                                        .focusProperties {
                                                            if (rowIndex == 0) upTo(clearHistoryFocus, chipAnchor(searchType), searchInputFocus)
                                                            if (rowIndex == suggestionRows.lastIndex / 4) down = miniFocus
                                                        }
                                                        .onFocusChanged { if (it.isFocused) bodyFocusMemory.value = focus })
                                                }
                                                repeat(4 - group.size) { Spacer(Modifier.weight(1f)) }
                                            }
                                        } else suggestionRows.forEachIndexed { index, item ->
                                            val focus = remember(item.keyword, item.badge, index) { FocusRequester() }
                                            PrismSuggestionRow(
                                                item.title,
                                                item.badge,
                                                /* The row that was focused vanishes once the list collapses;
                                                   hand focus back to the field so Down still reaches results. */
                                                { keyword = item.keyword; ime?.hide(); searchInputFocus.requestIfAttached(); if (!busy) search(item.keyword) },
                                                Modifier.focusRequester(focus)
                                                    .then(if (index == 0) Modifier.focusAnchor(firstSuggestionFocus) else Modifier)
                                                    .focusProperties {
                                                        if (index == 0) upTo(clearHistoryFocus, chipAnchor(searchType), searchInputFocus)
                                                        if (index == suggestionRows.lastIndex) down = miniFocus
                                                    }
                                                    .onFocusChanged { if (it.isFocused) bodyFocusMemory.value = focus },
                                                artwork = item.artwork, subtitle = item.subtitle,
                                            )
                                        }
                                    }
                                } else {
                                /* 条目数在页头已经写着，网格结果不需要再单开一行标题：
                                   这一行连同上下的留白会从可视区里抢走接近一整行卡片的高度，
                                   网格恰好只放得下一行时，聚焦边框就被下沿裁掉一截。 */
                                Spacer(Modifier.height(if (searchType == "music") 18.dp else 14.dp))
                                if (searchType == "music") {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("搜索结果", color = c.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.width(8.dp))
                                    val resultCount = results.size
                                    Text(
                                        when {
                                            resultCount > 0 -> "$resultCount 条 · ${if (searchSources.isEmpty()) "全部音源" else "${searchSources.size} 个音源"}" + if (busy) " · 搜索中" else ""
                                            busy -> "搜索中…"
                                            hasMore -> "本页暂无结果"
                                            else -> "没有匹配结果"
                                        },
                                        color = c.muted, fontSize = 11.sp,
                                    )
                                    if (resultCount > 0) {
                                        Spacer(Modifier.weight(1f))
                                        Text("长按确定键：收藏 / 找歌手", color = c.muted.copy(alpha = .8f), fontSize = 10.sp)
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                }
                                if (searchType != "music") {
                                    if (entities.isEmpty()) Text(
                                        if (busy) "正在搜索…" else "没有找到「$keyword」的${listOf("artist" to "歌手", "album" to "专辑", "sheet" to "歌单").firstOrNull { it.first == searchType }?.second ?: "结果"}。这个音源可能只支持歌曲搜索，切到「歌曲」再试一次",
                                        color = c.muted, fontSize = 12.sp
                                    )
                                    else LazyColumn(Modifier.weight(1f).padding(bottom = 10.dp), state = searchListState, verticalArrangement = Arrangement.spacedBy(8.dp),
                                        /* 末排聚焦边框要能完整滚入视口，底部留白与发现页榜单一致。 */
                                        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 22.dp)) {
                                        itemsIndexed(entities.chunked(6)) { rowIndex, row ->
                                            Row(searchRowLayout, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                row.forEachIndexed { col, entry ->
                                                    PrismSheetCard(entry.data.optString("title").ifBlank { entry.data.optString("name").ifBlank { "未命名" } }, entry.data.optString("artwork").ifBlank { entry.data.optString("coverImg") }, entry.sourceName, portrait = searchType == "artist",
                                                        modifier = Modifier
                                                            .focusRequester(detailFocus("$searchType:${entry.pluginId}:${entry.data.optString("id")}"))
                                                            .keepFocusVisible()
                                                            .then(if (entry === entities.first()) Modifier.focusAnchor(searchResultsFocus) else Modifier)
                                                            /* 只有首排按上回分类 tab；其余排在行内点名上下，
                                                               不再被绑死在页头。 */
                                                            .then(if (rowIndex == 0) Modifier.focusProperties { upTo(chipAnchor(searchType), searchInputFocus) } else Modifier)
                                                            .then(stepRows(rowIndex * 6 + col, 6, entities.size) { i ->
                                                                val target = entities[i]
                                                                detailFocus("$searchType:${target.pluginId}:${target.data.optString("id")}")
                                                            })
                                                            /* 末排按下去落「加载更多」，而不是靠几何搜索碰运气。 */
                                                            .onFocusChanged { focused ->
                                                                if (focused.isFocused) {
                                                                    searchFocusMemory.value = Triple(detailFocus("$searchType:${entry.pluginId}:${entry.data.optString("id")}"), rowIndex, col)
                                                                }
                                                            },
                                                        onLongPress = { state.plugins.firstOrNull { it.id == entry.pluginId }?.let {
                                                            showSubjectMenu(it, searchType, entry.data, detailFocus("$searchType:${entry.pluginId}:${entry.data.optString("id")}"))
                                                        } }) {
                                                        state.plugins.firstOrNull { it.id == entry.pluginId }?.let { openBrowse(it, entry.data, searchType) }
                                                    }
                                                }
                                            }
                                        }
                                        if (showMore) item(key = "search-more") {
                                            /* 加载下一页是用户明确按的：以前滚到末排自动追加，
                                               与歌手/专辑/歌单网格的「加载更多」不是一套交互。 */
                                            PrismAction(moreLabel, { if (hasMore && !busy) search(searchTerm, append = true) },
                                                searchRowLayout.keepFocusVisible().focusAnchor(searchMoreFocus).then(moreEdges))
                                        }
                                    }
                                } else {
                                if (results.isNotEmpty()) {
                                    PrismTrackHeader()
                                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line.copy(alpha = .5f)))
                                }
                                if (results.isEmpty()) {
                                    Text(
                                        if (busy) "正在搜索…" else if (hasMore) "本页暂无歌曲，可继续加载" else "没有找到「$keyword」的歌曲。换个关键词，或换一个音源试试",
                                        color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp)
                                    )
                                } else LazyColumn(Modifier.weight(1f).padding(top = 4.dp, bottom = 10.dp), state = searchListState,
                                    contentPadding = PaddingValues(vertical = 10.dp)) {
                                    itemsIndexed(results, key = { _, song -> song.key }) { index, song ->
                                        val rowFocus = if (index == 0) searchResultsFocus.requester else trackFocus(song.key)
                                        detailCardFocus["song:${song.key}"] = rowFocus
                                        /* A search pick joins the queue playing now; it does not
                                           wipe the songs you were listening to. */
                                        PrismTrackRow(song, index, { playSingle(song) },
                                            current = queue.getOrNull(queueIndex)?.key == song.key,
                                            showMv = song.pluginId in mvCapableIds,
                                            onLongPress = { trackMenu = TrackMenuTarget(song, originFocus = "song:${song.key}") },
                                            /* 首行按上回分类 tab；其余行按行号上下点名，回到第一行再按上才离开列表。 */
                                            modifier = (if (index == 0) Modifier.focusAnchor(searchResultsFocus)
                                                else Modifier.focusRequester(rowFocus))
                                                .then(searchRowLayout).keepFocusVisible()
                                                .then(if (index == 0) Modifier.focusProperties { upTo(chipAnchor(searchType), searchInputFocus) } else Modifier)
                                                .then(stepRows(index, 1, results.size) { i ->
                                                    if (i == 0) searchResultsFocus.requester else trackFocus(results[i].key)
                                                })
                                                /* 末行按下去落「加载更多」。 */
                                                .onFocusChanged {
                                                    if (it.isFocused) {
                                                        searchFocusMemory.value = Triple(rowFocus, index, -1)
                                                    }
                                                })
                                    }
                                    if (searchAppending && results.isNotEmpty()) item(key = "search-appending") {
                                        Text("正在加载更多…", color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                                    }
                                    if (showMore) item(key = "search-more") {
                                        PrismAction(moreLabel, { if (hasMore && !busy) search(searchTerm, append = true) },
                                            searchRowLayout.keepFocusVisible().focusAnchor(searchMoreFocus).then(moreEdges))
                                    }
                                }
                                }
                                if (showMore && results.isEmpty() && entities.isEmpty()) {
                                    PrismAction(moreLabel, { if (hasMore && !busy) search(searchTerm, append = true) },
                                        Modifier.focusAnchor(searchResultsFocus).focusAnchor(searchMoreFocus).then(moreEdges))
                                }
                                }
                            }
                            "library" -> Column(Modifier.fillMaxSize()) {
                                // Each tab owns one kind of collection; album favorites are saved album entities.
                                val showingSubjects = selectedList in setOf("artists", "albums")
                                val subjectKind = if (selectedList == "artists") "artist" else "album"
                                val visibleSubjects = libraryState.subjects.filter { it.kind == subjectKind }
                                val savedSheets = libraryState.subjects.filter { it.kind == "sheet" }
                                val songs = when {
                                    showingSubjects -> emptyList()
                                    selectedList == "recent" -> libraryState.recent
                                    selectedPlaylist != null -> selectedPlaylist.songs
                                    else -> libraryState.favorites
                                }
                                val listName = when {
                                    selectedList == "recent" -> "最近播放"
                                    selectedList == "artists" -> "收藏的歌手"
                                    selectedList == "albums" -> "收藏的专辑"
                                    selectedPlaylist != null -> selectedPlaylist.name
                                    showingPlaylists -> "我的歌单"
                                    else -> "收藏的歌曲"
                                }
                                val subjectSections = listOf((if (subjectKind == "artist") "歌手" else "专辑") to visibleSubjects)
                                    .filter { it.second.isNotEmpty() }
                                val firstSubject = subjectSections.firstOrNull()?.second?.firstOrNull()
                                val (libraryRowLayout, focusLibraryRow) = rememberVerticalFocusSearch()
                                val libraryCardRows = when {
                                    showingSubjects -> visibleSubjects.chunked(6).map { row -> row.map {
                                        detailFocus("${it.kind}:${it.pluginId}:${it.data.optString("id")}")
                                    } }
                                    showingPlaylists -> libraryState.playlists.chunked(6).map { row -> row.map {
                                        detailFocus("playlist:${it.id}")
                                    } } + if (libraryManage) emptyList() else savedSheets.chunked(6).map { row -> row.map {
                                        detailFocus("sheet:${it.pluginId}:${it.data.optString("id")}")
                                    } }
                                    else -> emptyList()
                                }
                                fun libraryCardEdges(row: Int, column: Int) = Modifier.onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    val delta = when (event.key) {
                                        Key.DirectionUp -> -1
                                        Key.DirectionDown -> 1
                                        else -> return@onPreviewKeyEvent false
                                    }
                                    val next = row + delta
                                    if (next < 0) return@onPreviewKeyEvent false
                                    focusLibraryRow(event.key) {
                                        if (next >= libraryCardRows.size) miniFocus
                                        else libraryCardRows[next].let { it[column.coerceAtMost(it.lastIndex)] }
                                    }
                                }

                                val manageTargets = when {
                                    showingSubjects -> visibleSubjects.map { it.key }
                                    showingPlaylists -> libraryState.playlists.map { it.id }
                                    else -> songs.map { it.key }
                                }
                                val allChosen = manageTargets.isNotEmpty() && manageTargets.all { it in librarySelected }
                                val headerEdges = Modifier.focusProperties {
                                    if (selectedPlaylist != null) upTo(playlistBackFocus)
                                    else up = libraryFocus
                                    if (libraryContentFocus.attached) down = libraryContentFocus.requester
                                    else down = miniFocus
                                }
                                val batchVerb = when {
                                    selectedList == "recent" -> "删除记录"
                                    showingSubjects -> "移除收藏"
                                    showingPlaylists -> "删除歌单"
                                    selectedPlaylist != null -> "移出歌单"
                                    else -> "取消收藏"
                                }
                                val batchUnit = when {
                                    selectedList == "recent" -> "条"
                                    showingSubjects || showingPlaylists -> "个"
                                    else -> "首"
                                }
                                if (selectedPlaylist != null) {
                                    PrismPageHead("歌单详情") {
                                        PrismAction("返回歌单", { closePlaylistTable() },
                                            Modifier.focusAnchor(playlistBackFocus).onPreviewKeyEvent { it.key == Key.DirectionUp })
                                    }
                                }
                                val belowTabs = Modifier.focusProperties {
                                    up = libraryFocus
                                    when {
                                        libraryContentFocus.attached -> down = libraryContentFocus.requester
                                        libraryEmptyFocus.attached -> down = libraryEmptyFocus.requester
                                        libraryHeaderFocus.attached -> down = libraryHeaderFocus.requester
                                        else -> down = miniFocus
                                    }
                                }
                                /* The empty-collection exit keeps the same spot and padding
                                   across the five tabs; Up returns to the active tab. */
                                val emptyActionEdges = Modifier.focusAnchor(libraryEmptyFocus).focusProperties {
                                    val tab = libraryTabs.getValue(libraryTabKey)
                                    if (tab.attached) up = tab.requester
                                    down = miniFocus
                                }
                                if (selectedPlaylist == null) {
                                // Each category shows only its applicable actions; OK on a tab
                                // enters the first available action.
                                val canPlayAll = !showingSubjects && !showingPlaylists && songs.isNotEmpty()
                                val canImport = showingPlaylists
                                val canManage = manageTargets.isNotEmpty()
                                val firstAction = when {
                                    libraryManage -> libraryHeaderFocus
                                    canPlayAll -> libraryPlayAllFocus
                                    canImport -> libraryImportFocus
                                    canManage -> libraryHeaderFocus
                                    else -> null
                                }
                                /* Tabs and actions share one row: browse left, act right.
                                   ←/→ walk this row, ↓ enters the list, and the first row's
                                   ↑ returns to the active tab. */
                                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    /* The four collection tabs are things you saved; 最近播放
                                       is system history — the divider marks the two kinds. */
                                    val collectionTabs = listOf(
                                        Triple("favorites", "歌曲", libraryState.favorites.size),
                                        Triple("albums", "专辑", libraryState.subjects.count { it.kind == "album" }),
                                        Triple("artists", "歌手", libraryState.subjects.count { it.kind == "artist" }),
                                        Triple("playlists", "歌单", libraryState.playlists.size + savedSheets.size),
                                    )
                                    val recentTab = Triple("recent", "最近", libraryState.recent.size)
                                    val tabs = collectionTabs + recentTab
                                    val tabModifier: (String) -> Modifier = { id ->
                                        Modifier.focusAnchor(libraryTabs.getValue(id)).then(belowTabs)
                                            .semantics { selected = selectedList == id }
                                            .focusProperties {
                                                val index = tabs.indexOfFirst { it.first == id }
                                                left = tabs.getOrNull(index - 1)?.let { libraryTabs.getValue(it.first).requester }
                                                    ?: FocusRequester.Cancel
                                                right = tabs.getOrNull(index + 1)?.let { libraryTabs.getValue(it.first).requester }
                                                    ?: firstAction?.takeIf { it.attached }?.requester ?: FocusRequester.Cancel
                                            }
                                            .onFocusChanged { if (it.isFocused && selectedList != id) selectedList = id }
                                    }
                                    val tabLongPress: (String) -> (() -> Unit)? = { id ->
                                        if (libraryManage) null else {
                                            {
                                                /* No menu when all three actions are unavailable —
                                                   an empty menu is worse than no response. */
                                                val anyAction = songs.isNotEmpty() || showingPlaylists || manageTargets.isNotEmpty()
                                                if (anyAction) {
                                                    menuReturnFocus = libraryTabs.getValue(id).requester
                                                    libraryListMenu = true
                                                }
                                            }
                                        }
                                    }
                                    collectionTabs.forEach { (id, label, count) ->
                                        PrismLibraryTab(label, count, selectedList == id, {
                                            if (selectedList != id) selectedList = id
                                            else if (!firstAction.requestIfAttached() && !libraryContentFocus.requestIfAttached()) {
                                                libraryEmptyFocus.requestIfAttached()
                                            }
                                        },
                                            modifier = tabModifier(id), onLongPress = tabLongPress(id))
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(18.dp).background(c.foreground.copy(alpha = .14f)))
                                    PrismLibraryTab(recentTab.second, recentTab.third, selectedList == recentTab.first, {
                                        if (selectedList != recentTab.first) selectedList = recentTab.first
                                        else if (!firstAction.requestIfAttached() && !libraryContentFocus.requestIfAttached()) {
                                            libraryEmptyFocus.requestIfAttached()
                                        }
                                    },
                                        modifier = tabModifier(recentTab.first), onLongPress = tabLongPress(recentTab.first))
                                    Spacer(Modifier.weight(1f))
                                    if (libraryManage) {
                                        PrismAction(if (allChosen) "取消全选" else "全选", {
                                            librarySelected = if (allChosen) emptySet() else manageTargets.toSet()
                                        }, headerEdges)
                                        Spacer(Modifier.width(8.dp))
                                        PrismAction(
                                            if (librarySelected.isNotEmpty()) "$batchVerb ${librarySelected.size} $batchUnit" else batchVerb,
                                            {
                                                if (librarySelected.isEmpty()) status = "先用确定键选中要处理的条目"
                                                else confirmBatchOpen = true
                                            },
                                            modifier = headerEdges,
                                            primary = librarySelected.isNotEmpty(),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        PrismAction("退出管理", { exitLibraryManage() }, Modifier.focusAnchor(libraryHeaderFocus).then(headerEdges))
                                    } else {
                                        // Only the actions this category supports are shown;
                                        // in empty lists they stay in place, disabled.
                                        if (!showingSubjects && !showingPlaylists) PrismAction("全部播放", { playQueue(songs) },
                                            headerEdges.focusAnchor(libraryPlayAllFocus)
                                                .then(if (firstAction === libraryPlayAllFocus) Modifier.focusProperties {
                                                    left = libraryTabs.getValue(libraryTabKey).requester
                                                } else Modifier),
                                            primary = canPlayAll, enabled = canPlayAll)
                                        if (!showingSubjects && !showingPlaylists) Spacer(Modifier.width(8.dp))
                                        if (showingPlaylists) PrismAction("导入歌单", { importDialog = true },
                                            headerEdges.focusAnchor(libraryImportFocus)
                                                .then(if (firstAction === libraryImportFocus) Modifier.focusProperties {
                                                    left = libraryTabs.getValue(libraryTabKey).requester
                                                } else Modifier),
                                            enabled = canImport)
                                        if (showingPlaylists) Spacer(Modifier.width(8.dp))
                                        PrismAction("管理", { enterLibraryManage() },
                                            Modifier.focusAnchor(libraryHeaderFocus).then(headerEdges)
                                                .then(if (firstAction === libraryHeaderFocus) Modifier.focusProperties {
                                                    left = libraryTabs.getValue(libraryTabKey).requester
                                                } else Modifier),
                                            enabled = canManage)
                                    }
                                }
                                Text(if (libraryManage) "确定键选择条目 · 返回键退出管理" else "左右切换分类 · 确定键进入右侧操作 · 长按打开菜单",
                                    color = c.muted, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1,
                                    modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp))
                                }
                                /* 歌单详情没有 tab 行，列表名和条目数留在这条标题里；
                                   tab 页的列表名与条数已经写在 tab 上，这里不再重复。 */
                                if (selectedPlaylist != null) {
                                Spacer(Modifier.height(16.dp))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    PlatformIcon(MusicPlatform.CUSTOM, Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    if (libraryManage) {
                                        Text("管理", color = c.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            if (librarySelected.isEmpty()) "选中条目后再处理"
                                            else "已选 ${librarySelected.size} / ${manageTargets.size} $batchUnit",
                                            color = c.muted, fontSize = 11.sp,
                                        )
                                    } else {
                                        Text(listName, color = c.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            when {
                                                showingSubjects -> "${visibleSubjects.size} 个"
                                                showingPlaylists -> "${libraryState.playlists.size + savedSheets.size} 个"
                                                else -> "${songs.size} 首"
                                            },
                                            color = c.muted, fontSize = 11.sp,
                                        )
                                        /* Every row and card on this page answers a long hold (or the
                                           MENU key) with its own menu; the hint earns its place only
                                           when there is something to point at. */
                                        if (manageTargets.isNotEmpty()) {
                                            Spacer(Modifier.width(14.dp))
                                            Text("长按确定键打开菜单", color = c.muted, fontSize = 11.sp)
                                        }
                                    }
                                    Spacer(Modifier.weight(1f))
                                    if (libraryManage) {
                                        PrismAction(if (allChosen) "取消全选" else "全选", {
                                            librarySelected = if (allChosen) emptySet() else manageTargets.toSet()
                                        }, headerEdges)
                                        Spacer(Modifier.width(8.dp))
                                        PrismAction(
                                            if (librarySelected.isNotEmpty()) "$batchVerb ${librarySelected.size} $batchUnit" else batchVerb,
                                            {
                                                if (librarySelected.isEmpty()) status = "先用确定键选中要处理的条目"
                                                else confirmBatchOpen = true
                                            },
                                            modifier = headerEdges,
                                            primary = librarySelected.isNotEmpty(),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        PrismAction("退出管理", { exitLibraryManage() }, Modifier.focusAnchor(libraryHeaderFocus).then(headerEdges))
                                    } else {
                                        val detailCanAct = manageTargets.isNotEmpty()
                                        PrismAction("全部播放", { playQueue(songs) },
                                            headerEdges.then(if (detailCanAct) Modifier.focusAnchor(libraryHeaderFocus)
                                                .focusProperties { left = playlistBackFocus.requester } else Modifier),
                                            enabled = detailCanAct)
                                        Spacer(Modifier.width(8.dp))
                                        PrismAction("管理", { enterLibraryManage() }, headerEdges, enabled = detailCanAct)
                                        if (!detailCanAct) {
                                            Spacer(Modifier.width(8.dp))
                                            PrismAction("返回歌单", { closePlaylistTable() }, Modifier.focusAnchor(libraryHeaderFocus).then(headerEdges))
                                        }
                                    }
                                }
                                }
                                if (showingSubjects) {
                                    Spacer(Modifier.height(8.dp))
                                    if (subjectSections.isEmpty()) {
                                        PrismEmptyState(
                                            "在${if (subjectKind == "artist") "歌手" else "专辑"}详情页按「收藏」，就会收进这里",
                                            "去发现", { page = "discover" }, emptyActionEdges, centered = true,
                                        )
                                    } else LazyColumn(state = librarySubjectListState, modifier = Modifier.weight(1f).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                                        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 22.dp)) {
                                        subjectSections.forEach { (_, group) ->
                                            itemsIndexed(group.chunked(6), key = { _, row -> "subject-row:${row.first().key}" }) { rowIndex, row ->
                                                Row(libraryRowLayout, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    row.forEachIndexed { column, subject ->
                                                        val cardFocus = detailFocus("${subject.kind}:${subject.pluginId}:${subject.data.optString("id")}")
                                                        PrismSheetCard(subject.title, subject.artwork, "${subject.kindLabel} · ${subject.sourceName.ifBlank { "已收藏" }}", portrait = subject.kind == "artist",
                                                            modifier = Modifier.focusRequester(cardFocus)
                                                                .keepFocusVisible().then(libraryCardEdges(rowIndex, column))
                                                                .then(if (subject === firstSubject) Modifier.focusAnchor(libraryContentFocus) else Modifier)
                                                                .focusProperties {
                                                                    if (subject in visibleSubjects.take(6)) {
                                                                        if (selectedPlaylist == null && !libraryManage) upTo(libraryTabs.getValue(libraryTabKey)) else if (libraryHeaderFocus.attached) up = libraryHeaderFocus.requester
                                                                        else upTo(libraryTabs.getValue(libraryTabKey))
                                                                    }
                                                                }
                                                                .onFocusChanged { if (it.isFocused) bodyFocusMemory.value = cardFocus },
                                                            onLongPress = if (libraryManage) null else { {
                                                                menuReturnFocus = cardFocus
                                                                subjectMenu = subject
                                                            } },
                                                            selected = if (libraryManage) subject.key in librarySelected else null) {
                                                            if (libraryManage) toggleLibrarySelection(subject.key)
                                                            else state.plugins.firstOrNull { it.id == subject.pluginId }?.let { openBrowse(it, subject.data, subject.kind) }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else if (showingPlaylists) {
                                    Spacer(Modifier.height(8.dp))
                                    /* 空歌单的提示以前在列表里（多一层 6dp/8dp 的 contentPadding），
                                       和歌手 tab 的提示差了几个像素，切 tab 时会跳；现在两处同一个组件。 */
                                    if (libraryState.playlists.isEmpty() && savedSheets.isEmpty()) {
                                        PrismEmptyState(
                                            "导入平台歌单，或在歌单详情页收藏，就会出现在这里",
                                            "导入歌单", { importDialog = true }, emptyActionEdges, centered = true,
                                        )
                                    } else LazyColumn(Modifier.weight(1f).padding(bottom = 10.dp), state = libraryPlaylistListState, verticalArrangement = Arrangement.spacedBy(10.dp),
                                        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 22.dp)) {
                                        itemsIndexed(libraryState.playlists.chunked(6), key = { _, row -> "local:${row.first().id}" }) { rowIndex, row ->
                                            Row(libraryRowLayout, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                row.forEachIndexed { column, list ->
                                                        val platform = MusicPlatform.identify(list.platform)
                                                        val cardFocus = detailFocus("playlist:${list.id}")
                                                        detailCardFocus["playlist:${list.id}"] = cardFocus
                                                        PrismSheetCard(list.name, list.songs.firstOrNull { it.artwork.startsWith("http") }?.artwork.orEmpty(),
                                                            "${list.songs.size} 首 · ${platform.label}",
                                                            modifier = Modifier.focusRequester(cardFocus)
                                                                .keepFocusVisible().then(libraryCardEdges(rowIndex, column))
                                                                .then(
                                                                    if (list == libraryState.playlists.first()) Modifier.focusAnchor(libraryContentFocus)
                                                                    else Modifier,
                                                                ).focusProperties {
                                                                    if (list in libraryState.playlists.take(6)) {
                                                                        if (selectedPlaylist == null && !libraryManage) upTo(libraryTabs.getValue(libraryTabKey)) else if (libraryHeaderFocus.attached) up = libraryHeaderFocus.requester
                                                                        else upTo(libraryTabs.getValue(libraryTabKey))
                                                                    }
                                                                }.onFocusChanged { if (it.isFocused) bodyFocusMemory.value = cardFocus },
                                                            selected = if (libraryManage) list.id in librarySelected else null,
                                                            onLongPress = if (libraryManage) null else { {
                                                                menuReturnFocus = cardFocus
                                                                collectionMenu = CollectionMenuTarget(list.name, list.songs, list.id)
                                                            } }) {
                                                            if (libraryManage) toggleLibrarySelection(list.id)
                                                            else openPlaylistTable(list.id)
                                                        }
                                                }
                                            }
                                        }
                                        if (!libraryManage && savedSheets.isNotEmpty()) {
                                            item(key = "saved-sheets") { Text("收藏的歌单", color = c.muted, fontSize = 13.sp, letterSpacing = .4.sp, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)) }
                                            itemsIndexed(savedSheets.chunked(6), key = { _, row -> "saved:${row.first().key}" }) { rowIndex, row ->
                                                Row(libraryRowLayout, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    row.forEachIndexed { column, subject ->
                                                        val key = "sheet:${subject.pluginId}:${subject.data.optString("id")}"
                                                        val cardFocus = detailFocus(key)
                                                        PrismSheetCard(subject.title, subject.artwork, subject.sourceName.ifBlank { "已收藏" },
                                                            modifier = Modifier.focusRequester(cardFocus)
                                                                .keepFocusVisible().then(libraryCardEdges((libraryState.playlists.size + 5) / 6 + rowIndex, column))
                                                                .then(if (libraryState.playlists.isEmpty() && subject == savedSheets.first()) Modifier.focusAnchor(libraryContentFocus) else Modifier)
                                                                .focusProperties {
                                                                    if (libraryState.playlists.isEmpty() && subject in savedSheets.take(6)) {
                                                                        if (selectedPlaylist == null && !libraryManage) upTo(libraryTabs.getValue(libraryTabKey)) else if (libraryHeaderFocus.attached) up = libraryHeaderFocus.requester
                                                                        else upTo(libraryTabs.getValue(libraryTabKey))
                                                                    }
                                                                }.onFocusChanged { if (it.isFocused) bodyFocusMemory.value = cardFocus },
                                                            onLongPress = { menuReturnFocus = cardFocus; subjectMenu = subject }) {
                                                            state.plugins.firstOrNull { it.id == subject.pluginId }?.let { openBrowse(it, subject.data, "sheet") }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                Spacer(Modifier.height(8.dp))
                                PrismTrackHeader(managing = libraryManage)
                                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line.copy(alpha = .5f)))
                                if (songs.isEmpty()) {
                                    PrismEmptyState(
                                        when {
                                            selectedPlaylist != null -> "这个歌单里还没有歌"
                                            selectedList == "favorites" -> "播放时按收藏，歌曲会出现在这里"
                                            else -> "播过的歌会自动记在这里"
                                        },
                                        if (selectedPlaylist == null) "去发现" else null,
                                        { page = "discover" }, emptyActionEdges, centered = true,
                                    )
                                } else LazyColumn(Modifier.weight(1f).padding(top = 4.dp, bottom = 10.dp), state = librarySongListState) {
                                    itemsIndexed(songs, key = { _, song -> song.key }) { index, song ->
                                        val rowFocus = if (index == 0) libraryContentFocus.requester else trackFocus(song.key)
                                        detailCardFocus["song:${song.key}"] = rowFocus
                                        val rowMenu: (() -> Unit)? = if (libraryManage) null else { -> trackMenu = TrackMenuTarget(song, selectedList, "song:${song.key}") }
                                        PrismTrackRow(song, index,
                                            { if (libraryManage) toggleLibrarySelection(song.key) else playSingle(song) },
                                            modifier = (if (index == 0) Modifier.focusAnchor(libraryContentFocus).focusProperties { if (selectedPlaylist == null && !libraryManage) upTo(libraryTabs.getValue(libraryTabKey)) else if (libraryHeaderFocus.attached) up = libraryHeaderFocus.requester } else Modifier.focusRequester(rowFocus))
                                                /* 迷你条按上回到离开时的那一行，而不是页首 tab。 */
                                                .onFocusChanged { if (it.isFocused) bodyFocusMemory.value = rowFocus },
                                            current = queue.getOrNull(queueIndex)?.key == song.key,
                                            showMv = song.pluginId in mvCapableIds,
                                            onLongPress = rowMenu,
                                            selected = if (libraryManage) song.key in librarySelected else null)
                                    }
                                }
                                }
                            }
                            else -> {
                                val selected = state.plugins.firstOrNull { it.id == configured }
                                val settingsScroll = rememberScrollState()
                                /* Each section starts at its own top: a scroll position carried
                                   across would hide the new section's header and entry row. */
                                LaunchedEffect(settingsSection) { settingsScroll.scrollTo(0) }
                                if (selected != null) PluginConfig(selected, configEntryFocus, bodyFocusMemory, onBack = closeConfig)
                                else Column(Modifier.fillMaxSize()) {
                                    /* The rail and the panel are separate focus spaces, so a
                                       geometric Left from the content falls back to whichever
                                       rail row is nearest and swaps the section under the
                                       remote. Every left edge names the open section. */
                                    val edgeToRail = Modifier.focusProperties {
                                        val anchor = sectionFocus.getValue(settingsSection)
                                        if (anchor.attached) left = anchor.requester
                                    }
                                    /* Walking back up only half-reveals a scrolled section:
                                       bringIntoView stops once the first control is visible, so
                                       the header texts above it stay off-screen and the next Up
                                       leaves the panel. On a scrolled panel, Up from the first
                                       control scrolls back to the top first; the next Up leaves. */
                                    val entryUpScrollsBack = Modifier.onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && settingsScroll.value > 0) {
                                            pageScope.launch { settingsScroll.animateScrollTo(0) }; true
                                        } else false
                                    }
                                    /* Up from the top row used to fall back to the nearest rail
                                       row, which swapped the section under the remote. It goes
                                       to the bar instead, like every other page. */
                                    val entryEdge = Modifier.focusProperties {
                                        val anchor = sectionFocus.getValue(settingsSection)
                                        if (anchor.attached) left = anchor.requester
                                        up = pluginsFocus
                                    }.then(entryUpScrollsBack)
                                    Row(Modifier.weight(1f).fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                                    Column(Modifier.width(164.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        val railIcons = listOf("palette", "compass", "layers", "play", "info")
                                        settingsSections.forEachIndexed { index, section ->
                                            SettingsRailItem(section, railIcons[index], settingsSection == section, {
                                                settingsSection = section
                                                /* The panel for the new section is composed in
                                                   the next frame. Ask for its first control by
                                                   name instead of trusting a geometric Right,
                                                   which has no target during recomposition. */
                                                pageScope.launch {
                                                    repeat(4) {
                                                        withFrameNanos { }
                                                        if (sectionEntryFocus.getValue(section).requestIfAttached()) return@launch
                                                    }
                                                    focusManager.moveFocus(FocusDirection.Right)
                                                }
                                            },
                                                Modifier.focusAnchor(sectionFocus.getValue(section))
                                                    /* Same contract as the top bar: the panel changes
                                                       with the highlight, so one press both previews
                                                       and selects. */
                                                    .onFocusChanged { if (it.isFocused && settingsSection != section) settingsSection = section }
                                                    /* Only the selected section's content is composed; pointing
                                                       Right anywhere else would target a node that isn't there. */
                                                    .focusProperties {
                                                        val entry = sectionEntryFocus.getValue(section)
                                                        if (section == settingsSection && entry.attached) right = entry.requester
                                                        if (index == 0) up = pluginsFocus
                                                    }
                                                    /* An unselected section's content isn't on
                                                       screen yet: Right would find no candidate
                                                       and drop focus off the screen. */
                                                    .onPreviewKeyEvent {
                                                        it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight &&
                                                            section != settingsSection
                                                    })
                                        }
                                    }
                                    Column(Modifier.weight(1f)) {
                                    Column(Modifier.weight(1f).verticalScroll(settingsScroll).padding(end = 6.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                                    if (settingsSection == "外观") {
                                    val themes = listOf(Triple("gold", "极夜暖金", "温暖、沉浸"), Triple("porcelain", "晨光白瓷", "明亮、柔和"), Triple("mono", "静谧黑白", "纯粹、克制"))
                                    fun pickTheme(value: String) {
                                        updatePreference("theme", value)
                                    }
                                    SettingsCard("主题") {
                                        val themeTargets = remember { themes.map { FocusRequester() } }
                                        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            themes.forEachIndexed { index, (value, label, detail) ->
                                                AuraThemeCard(label, detail, prismColors(value), theme == value, { pickTheme(value) },
                                                    Modifier.weight(1f).focusRequester(themeTargets[index])
                                                        .then(if (theme == value) Modifier.focusAnchor(sectionEntryFocus.getValue("外观")) else Modifier)
                                                        .focusProperties {
                                                            left = if (index > 0) themeTargets[index - 1] else sectionFocus.getValue("外观").requester
                                                            right = themeTargets.getOrNull(index + 1) ?: FocusRequester.Cancel
                                                            up = pluginsFocus
                                                        }
                                                        .then(entryUpScrollsBack).rememberFocus(bodyFocusMemory))
                                            }
                                        }
                                    }
                                    SettingsCard("播放界面") {
                                        PrismSwitchRow("减少动态效果", reduceMotion, {
                                            updatePreference("reduceMotion", it.toString())
                                        }, edgeToRail.rememberFocus(bodyFocusMemory), description = "关闭流光与过渡动画")
                                    }
                                    }
                                    if (settingsSection == "播放") {
                                        val qualityTiers = listOf("low" to "流畅", "standard" to "标准", "high" to "高品", "super" to "无损优先")
                                        fun pickQuality(value: String) {
                                            updatePreference("quality", value)
                                        }
                                        /* 两栏布局里左键归左栏（按左就回导航），行内用 OK 循环改值。 */
                                        fun cycleQuality() {
                                            val i = qualityTiers.indexOfFirst { it.first == quality }
                                            if (i < 0) return
                                            pickQuality(qualityTiers[(i + 1) % qualityTiers.size].first)
                                        }
                                        SettingsCard("音质") {
                                            SettingsRow("首选音质",
                                                modifier = Modifier.focusAnchor(sectionEntryFocus.getValue("播放")).then(entryEdge).rememberFocus(bodyFocusMemory),
                                                onActivate = { cycleQuality() }) {
                                                SettingsSeg(qualityTiers, quality, ::pickQuality)
                                            }
                                            SettingsDivider()
                                            PrismSwitchRow("播放失败自动换源", autoSourceFallback, { setAutoSourceFallback(it) },
                                                edgeToRail.rememberFocus(bodyFocusMemory), description = "改用其他音源的同一首歌")
                                        }
                                        val playModes = PlaybackMode.entries.map { it to it.label }
                                        fun pickMode(value: PlaybackMode) {
                                            updatePreference("mode", value.id)
                                        }
                                        fun cycleMode() {
                                            val i = playModes.indexOfFirst { it.first == playMode }
                                            if (i < 0) return
                                            pickMode(playModes[(i + 1) % playModes.size].first)
                                        }
                                        SettingsCard("播放与搜索") {
                                            SettingsRow("播放模式",
                                                modifier = edgeToRail.rememberFocus(bodyFocusMemory),
                                                onActivate = { cycleMode() }) {
                                                SettingsSeg(playModes, playMode, ::pickMode)
                                            }
                                            SettingsDivider()
                                            val workerOptions = listOf(0 to "自动 · ${PluginWorkers.automatic(this@MainActivity)} 个", 1 to "1 个", 2 to "2 个", 3 to "3 个", 4 to "4 个")
                                            fun pickWorkers(value: Int) {
                                                updatePreference("pluginWorkerLimit", value.toString())
                                            }
                                            fun cycleWorkers() {
                                                val i = workerOptions.indexOfFirst { it.first == pluginWorkerLimit }
                                                if (i < 0) return
                                                pickWorkers(workerOptions[(i + 1) % workerOptions.size].first)
                                            }
                                            SettingsRow("搜索并发", "调大更快，也更占内存",
                                                modifier = edgeToRail.rememberFocus(bodyFocusMemory),
                                                onActivate = { cycleWorkers() }) {
                                                SettingsSeg(workerOptions, pluginWorkerLimit, ::pickWorkers)
                                            }
                                        }
                                        /* All lyric-display preferences live in this card. Time calibration
                                           stays on the player page — it only makes sense while listening. */
                                        SettingsCard("歌词") {
                                            PrismSwitchRow("播放条歌词", showMiniLyrics, {
                                                updatePreference("miniLyrics", it.toString())
                                            }, edgeToRail.rememberFocus(bodyFocusMemory), description = "底部播放条显示当前一句")
                                            SettingsDivider()
                                            val levels = lyricFontLevels.map { it to it }
                                            fun pickFont(level: String) {
                                                updatePreference("lyricFontLevel", level)
                                            }
                                            fun cycleFont() {
                                                val i = levels.indexOfFirst { it.first == lyricFontLevel }
                                                if (i < 0) return
                                                pickFont(levels[(i + 1) % levels.size].first)
                                            }
                                            SettingsRow("字号",
                                                modifier = edgeToRail.rememberFocus(bodyFocusMemory),
                                                onActivate = { cycleFont() }) {
                                                SettingsSeg(levels, lyricFontLevel, ::pickFont)
                                            }
                                            SettingsDivider()
                                            PrismSwitchRow("翻译与注音", subLyrics, {
                                                updatePreference("subLyrics", it.toString())
                                            }, edgeToRail.rememberFocus(bodyFocusMemory))
                                        }
                                    }
                                    if (settingsSection == "关于") {
                                    val webViewInfo = remember {
                                        runCatching {
                                            val provider = if (android.os.Build.VERSION.SDK_INT >= 26) android.webkit.WebView.getCurrentWebViewPackage()
                                            else listOf("com.google.android.webview", "com.android.webview").firstNotNullOfOrNull { name ->
                                                runCatching { packageManager.getPackageInfo(name, 0) }.getOrNull()
                                            }
                                            provider?.let { "${it.packageName} ${it.versionName.orEmpty()}" } ?: "未检测到 WebView"
                                        }.getOrDefault("未检测到 WebView")
                                    }
                                    SettingsCard("版本") {
                                        SettingsRow("PrismAura $versionName", updateState.message,
                                            modifier = Modifier.focusAnchor(sectionEntryFocus.getValue("关于")).then(entryEdge).rememberFocus(bodyFocusMemory),
                                            onActivate = { checkAppUpdates() }) {
                                            SettingsButton(if (updateState.checking) "正在检查…" else if (updateState.release != null) "查看更新" else "检查更新", {
                                                checkAppUpdates()
                                            }, icon = "refresh", passive = true)
                                        }
                                        SettingsDivider()
                                        PrismSwitchRow("新版本提醒", updateState.remind, appUpdates::setRemind,
                                            edgeToRail.rememberFocus(bodyFocusMemory))
                                    }
                                    SettingsCard("运行环境") {
                                        SettingsRow("设备") { Text(deviceInfo, color = c.muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                        SettingsDivider()
                                        SettingsRow("WebView") { Text(webViewInfo, color = c.muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    }
                                    SettingsCard("数据") {
                                        val clearCache: () -> Unit = {
                                            if (!clearingCache) lifecycleScope.launch {
                                                clearingCache = true
                                                cacheSize = withContext(Dispatchers.IO) { directorySize(cacheDir) }
                                                withContext(Dispatchers.IO) { clearDirectory(cacheDir) }
                                                getSharedPreferences("discovery", MODE_PRIVATE).edit().clear().apply()
                                                hotSearchCache.clear()
                                                charts = emptyList(); chartPreviews = emptyMap()
                                                cacheSize = 0L
                                                clearingCache = false
                                                status = "缓存已清理"
                                                chartSource?.let { loadCharts(it) }
                                            }
                                        }
                                        SettingsRow("缓存", "清理不影响收藏、歌单与登录",
                                            modifier = edgeToRail.rememberFocus(bodyFocusMemory),
                                            onActivate = clearCache) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Text(if (cacheSize < 0) "…" else formatBytes(cacheSize), color = c.muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
                                                SettingsButton(if (clearingCache) "清理中…" else "清理", { clearCache() }, icon = "trash", passive = true)
                                            }
                                        }
                                        SettingsDivider()
                                        SettingsRow("备份与恢复", "曲库、音源和设置",
                                            modifier = edgeToRail.rememberFocus(bodyFocusMemory),
                                            onActivate = { openPhoneHub("settings") }) {
                                            SettingsButton("用手机管理", { openPhoneHub("settings") }, icon = "qr", passive = true)
                                        }
                                    }
                                    }
                                    if (settingsSection == "推荐") {
                                    SettingsCard("内容来源") {
                                        /* When the popup closes, focus must return to this row — a popup
                                           is its own focus window and the top bar is otherwise a dead end. */
                                        val restoreRowFocus: (Boolean) -> Unit = { open ->
                                            if (!open) pageScope.launch {
                                                withFrameNanos { }
                                                bodyFocusMemory.value?.let { requester -> runCatching { requester.requestFocus() } }
                                            }
                                        }
                                        var recOpen by remember { mutableStateOf(false) }
                                        val recOptions = activePlugins.filter { it.capabilities.recommendations }
                                        SettingsRow("推荐歌单",
                                            modifier = Modifier.focusAnchor(sectionEntryFocus.getValue("推荐")).then(entryEdge).rememberFocus(bodyFocusMemory),
                                            onActivate = { if (recOptions.isNotEmpty()) recOpen = true }) {
                                            SourcePicker(recOptions, recommendationPluginId, open = recOpen,
                                                onOpenChange = { recOpen = it; restoreRowFocus(it) }, passive = true) { plugin ->
                                                updatePreference("recommendationSource", plugin.id)
                                            }
                                        }
                                        SettingsDivider()
                                        var chartOpen by remember { mutableStateOf(false) }
                                        val chartOptions = activePlugins.filter { it.capabilities.charts }
                                        SettingsRow("排行榜",
                                            modifier = edgeToRail.rememberFocus(bodyFocusMemory),
                                            onActivate = { if (chartOptions.isNotEmpty()) chartOpen = true }) {
                                            SourcePicker(chartOptions, chartPluginId, open = chartOpen,
                                                onOpenChange = { chartOpen = it; restoreRowFocus(it) }, passive = true) { plugin ->
                                                updatePreference("chartSource", plugin.id)
                                            }
                                        }
                                    }
                                    /* Every shelf is offered, hidden ones included, so a playlist
                                       imported after the last choice can be brought back onto 首页
                                       instead of staying invisible. */
                                    val shelfOptions = listOf("favorites" to "我的收藏", "recent" to "最近播放") +
                                        libraryState.playlists.map { it.id to it.name }
                                    SettingsCard("首页栏目", "已显示 ${shelfOptions.count { it.first !in homeShelfHidden }} / ${shelfOptions.size}") {
                                        SettingsRow("显示在首页") {
                                            FlowRow(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                shelfOptions.forEachIndexed { index, (id, label) ->
                                                    SettingsChip(label, id !in homeShelfHidden, { toggleHomeShelf(id) },
                                                        (if (index == 0) edgeToRail else Modifier).rememberFocus(bodyFocusMemory))
                                                }
                                            }
                                        }
                                        SettingsDivider()
                                        SettingsRow("管理歌单",
                                            modifier = edgeToRail.rememberFocus(bodyFocusMemory), onActivate = { page = "library" }) {
                                            SettingsButton("打开「我的」", { page = "library" }, passive = true)
                                        }
                                    }
                                    }
                                    if (settingsSection == "音源") {
                                    var addingSource by remember { mutableStateOf(false) }
                                    var removingSubscription by remember { mutableStateOf<String?>(null) }
                                    var addSourceError by remember { mutableStateOf("") }
                                    val addressFocus = remember { FocusRequester() }
                                    val addFocus = remember { FocusRequester() }
                                    val updateSourcesFocus = remember { FocusRequester() }
                                    val subscriptionGroups = state.subscriptions.map { url ->
                                        url to state.plugins.filter { it.origin == url || (it.origin.isBlank() && it.url == url) }
                                    }
                                    val standalone = state.plugins.filter { it.origin.isBlank() && it.url !in state.subscriptions }
                                    fun closeAddSource() {
                                        addingSource = false
                                        pageScope.launch { withFrameNanos { }; sourceActionFocus.requestIfAttached() }
                                    }
                                    fun sourceRemoved() {
                                        pageScope.launch { withFrameNanos { }; sourceActionFocus.requestIfAttached() }
                                    }
                                    fun addSource() {
                                        if (busy) return
                                        if (subscription.isBlank()) { addSourceError = "请输入地址"; return }
                                        lifecycleScope.launch {
                                            busy = true; addSourceError = ""
                                            try { showPluginImport(plugins.importUrl(subscription.trim())); subscription = ""; closeAddSource() }
                                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                            catch (e: Exception) { addSourceError = e.message ?: "添加失败，请检查地址" }
                                            finally { busy = false }
                                        }
                                    }
                                    SettingsCard("添加") {
                                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text("添加音源", color = c.foreground, fontSize = 15.sp, lineHeight = 21.sp)
                                                Text("请只导入可信来源的插件", color = c.muted, fontSize = 12.sp, lineHeight = 17.sp)
                                            }
                                            SettingsButton("添加", { addSourceError = ""; addingSource = true }, Modifier.focusAnchor(sourceActionFocus).then(entryEdge)
                                                .focusProperties { right = updateSourcesFocus }.rememberFocus(bodyFocusMemory), primary = true, icon = "plus")
                                            SettingsButton(if (busy) "更新中…" else "全部更新", { if (!busy) lifecycleScope.launch { busy = true; try { showPluginImport(plugins.syncAll()) } catch (e: Exception) { status = "同步失败：${e.message}" } finally { busy = false } } },
                                                Modifier.focusRequester(updateSourcesFocus).focusProperties { up = pluginsFocus; leftTo(sourceActionFocus) }.rememberFocus(bodyFocusMemory), icon = "refresh")
                                        }
                                    }
                                    SettingsCard("已安装", "已启用 ${state.plugins.count { it.enabled }} / ${state.plugins.size}") {
                                    if (state.plugins.isEmpty() && state.subscriptions.isEmpty()) Text("还没有音源。添加 MusicFree 插件或订阅后就能开始听歌", color = c.muted, fontSize = 13.sp, lineHeight = 19.sp,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp))

                                    if (addingSource) Dialog(onDismissRequest = { closeAddSource() }) {
                                        LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; addressFocus.requestFocus() }
                                        Column(Modifier.width(580.dp).background(c.panel, androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                            Text("添加音源", color = c.foreground, fontSize = 22.sp)
                                            Input(subscription, { subscription = it; addSourceError = "" }, "插件 .js 或订阅 .json 地址",
                                                Modifier.fillMaxWidth().focusRequester(addressFocus).focusProperties { down = addFocus },
                                                keyboardType = KeyboardType.Uri, manualKeyboard = true, onSubmit = { addFocus.requestFocus() })
                                            if (addSourceError.isNotBlank()) Text(addSourceError, color = c.accent, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                PrismAction(if (busy) "添加中…" else "添加", { addSource() }, Modifier.focusRequester(addFocus).focusProperties { up = addressFocus }, primary = true)
                                                PrismAction("用手机添加", { closeAddSource(); openPhoneHub("import-source") }, Modifier.focusProperties { up = addressFocus })
                                                PrismAction("取消", { closeAddSource() }, Modifier.focusProperties { up = addressFocus })
                                            }
                                        }
                                    }
                                    removingSubscription?.let { url ->
                                        PrismConfirmDialog("移除订阅？", "同时移除订阅内音源，保留本机歌单和收藏", "移除订阅",
                                            onDismiss = { removingSubscription = null }, onConfirm = {
                                                removingSubscription = null
                                                lifecycleScope.launch {
                                                    try { plugins.removeSubscription(url); status = "已移除订阅及其音源"; sourceRemoved() }
                                                    catch (e: Exception) { status = e.message ?: "移除失败" }
                                                }
                                            })
                                    }

                                    /* A subscription is the thing that can be updated, so it is the row
                                       that owns removal. Its plugins are only switched on or off:
                                       deleting one would just bring it back on the next sync. */
                                    val firstRowEdge = edgeToRail.rememberFocus(bodyFocusMemory)
                                    var firstRow = true
                                    subscriptionGroups.forEach { (url, owned) ->
                                        val expanded = collapsedSubscriptions[url] != true
                                        val enabledCount = owned.count { it.enabled }
                                        val allEnabled = owned.isNotEmpty() && enabledCount == owned.size
                                        var infoOpen by remember(url) { mutableStateOf(false) }
                                        var groupMenu by remember(url) { mutableStateOf(false) }
                                        val titleFocus = remember(url) { FocusRequester() }
                                        val updateFocus = remember(url) { FocusRequester() }
                                        val moreFocus = remember(url) { FocusRequester() }
                                        val subscriptionUri = remember(url) { Uri.parse(url) }
                                        val groupTitle = subscriptionUri.host?.let { "订阅 · $it" } ?: "音源订阅"
                                        fun backToHeader() {
                                            pageScope.launch { withFrameNanos { }; runCatching { moreFocus.requestFocus() } }
                                        }
                                        if (!firstRow) SettingsDivider()
                                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Row(Modifier.weight(1f).focusRequester(titleFocus).then(edgeToRail).rememberFocus(bodyFocusMemory)
                                                .focusProperties { right = updateFocus }
                                                .auraFocus(RoundedCornerShape(8.dp), 1f).clip(RoundedCornerShape(8.dp))
                                                .tvClickable({ collapsedSubscriptions[url] = expanded }).padding(6.dp)
                                                .semantics { contentDescription = if (expanded) "折叠此订阅" else "展开此订阅" },
                                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Glyph(if (expanded) "chev" else "chevR", Modifier.size(12.dp), c.muted)
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Text(groupTitle, color = c.foreground, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(listOfNotNull(
                                                        "${owned.size} 个音源 · 已启用 $enabledCount",
                                                        subscriptionUri.lastPathSegment?.takeIf { it.isNotBlank() },
                                                    ).joinToString(" · "), color = c.muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                            SettingsButton("更新", { if (!busy) lifecycleScope.launch {
                                                busy = true
                                                status = "正在更新订阅…"
                                                try { showPluginImport(plugins.importUrl(url)) }
                                                catch (e: Exception) { status = "更新失败：${e.message}" }
                                                finally { busy = false }
                                            } }, Modifier.focusRequester(updateFocus).focusProperties { left = titleFocus }.rememberFocus(bodyFocusMemory))
                                            SettingsButton("更多", { groupMenu = true }, Modifier.focusRequester(moreFocus)
                                                .focusProperties { left = updateFocus }.rememberFocus(bodyFocusMemory))
                                        }
                                        if (groupMenu) PrismContextMenu(groupTitle, "${owned.size} 个音源 · 已启用 $enabledCount", {
                                            groupMenu = false; backToHeader()
                                        }, moreFocus, width = menuWidth(3)) {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                                                PrismIconAction(if (allEnabled) "close" else "check",
                                                    if (allEnabled) "全部停用" else "全部启用", {
                                                        if (!busy) lifecycleScope.launch {
                                                            try { plugins.setEnabled(owned.map { it.id }, !allEnabled); status = if (allEnabled) "已停用 ${owned.size} 个音源" else "已启用 ${owned.size} 个音源" }
                                                            catch (e: Exception) { status = e.message ?: "操作失败" }
                                                            finally { groupMenu = false; backToHeader() }
                                                        }
                                                    }, Modifier.weight(1f, fill = false).focusRequester(moreFocus))
                                                PrismIconAction("info", "订阅地址", { groupMenu = false; infoOpen = true }, Modifier.weight(1f, fill = false))
                                                PrismIconAction("trash", "移除订阅", { groupMenu = false; removingSubscription = url }, Modifier.weight(1f, fill = false))
                                            }
                                        }
                                        val subscriptionCloseFocus = remember(url) { FocusRequester() }
                                        if (infoOpen) PrismContextMenu("音源订阅", "订阅地址", {
                                            infoOpen = false; backToHeader()
                                        }, subscriptionCloseFocus) {
                                            SourceAddressList(listOf(SourceAddress("订阅地址", url)))
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                                PrismAction("关闭", { infoOpen = false; backToHeader() }, Modifier.focusRequester(subscriptionCloseFocus), primary = true)
                                            }
                                        }
                                        if (expanded) owned.forEach { plugin -> key(plugin.id) {
                                            SettingsDivider()
                                            Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) { PluginSourceRow(plugin, owned = true, onConfigure = { configured = plugin.id }, onRemoved = ::sourceRemoved,
                                                configureFocus = sourceConfigFocus.getOrPut(plugin.id) { FocusAnchor() },
                                                edge = if (firstRow) firstRowEdge else edgeToRail.rememberFocus(bodyFocusMemory)) }
                                            firstRow = false
                                        } }
                                    }

                                    if (standalone.isNotEmpty() && state.subscriptions.isNotEmpty()) {
                                        SettingsDivider()
                                        Text("单独添加", color = c.muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp))
                                    }
                                    standalone.forEach { plugin -> key(plugin.id) {
                                        if (!firstRow) SettingsDivider()
                                        Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) { PluginSourceRow(plugin, owned = false, onConfigure = { configured = plugin.id }, onRemoved = ::sourceRemoved,
                                            configureFocus = sourceConfigFocus.getOrPut(plugin.id) { FocusAnchor() },
                                            edge = if (firstRow) firstRowEdge else edgeToRail.rememberFocus(bodyFocusMemory)) }
                                        firstRow = false
                                    } }
                                    }

                                    }
                                    }
                                    }
                                    }
                                }
                            }
                        }
                    }
                    }
                    PrismMiniPlayer(
                        song = queue.getOrNull(queueIndex),
                        playbackSource = playbackSong(),
                        playing = playing,
                        position = positionMs,
                        duration = durationMs,
                        lyric = if (showMiniLyrics) lyrics.lastOrNull { it.timeMs <= positionMs - lyricCalibrationMs }?.text.orEmpty() else "",
                        showLyric = showMiniLyrics,
                        onOpen = { if (queue.getOrNull(queueIndex) == null) page = "search" else playerOpen = true },
                        onToggle = { togglePlayback() },
                        openFocus = miniFocus,
                        /* After a search result plays, Up should reach the list you were
                           browsing. The text field is only the landing spot while the page
                           has no results to return to. */
                        upTarget = if (page == "search" && (results.isNotEmpty() || entities.isNotEmpty())) searchResultsFocus else pageEntryFocus,
                        upMemory = bodyFocusMemory,
                        /* The 发现 cards live in a list the bar is not part of: after a fast scroll
                           to the bottom the card has no node any more, so Up has to scroll its row
                           back before the remembered requester can take the remote. */
                        upRestore = when {
                            /* 搜索结果的行随滚动被回收后，迷你条的上键先滚回那一行再交焦，
                               焦点才回得去用户离开的那一条。 */
                            page == "search" && !searchSuggestionsVisible -> {
                                {
                                    val memory = searchFocusMemory.value
                                    if (memory == null) false
                                    else {
                                        searchNavigationScope.coroutineContext.cancelChildren()
                                        searchNavigationScope.launch {
                                            val (requester, index, _) = memory
                                            if (!requester.requestAfterScroll { searchListState.scrollToItem(index) }
                                                && !searchResultsFocus.requestIfAttached()) searchInputFocus.requestIfAttached()
                                        }
                                        true
                                    }
                                }
                            }
                            /* 详情页与榜单把封面/标题也放在同一个列表里：一直 Down 到底，头图会被回收，
                               迷你条的 Up 就没有落点（看着像失焦）。先原地要焦点，要不到再按行号滚回来。 */
                            page == "discover" && (browseItem != null || chartSelection != null) -> {
                                val detailList = if (browseItem != null) browseSongListState else chartSongListState
                                {
                                    val memory = detailFocusMemory.value
                                    if (memory == null) false
                                    else {
                                        pageScope.launch {
                                            val (requester, row) = memory
                                            if (runCatching { requester.requestFocus() }.isSuccess) return@launch
                                            runCatching { detailList.scrollToItem(row) }
                                            repeat(4) { withFrameNanos { } }
                                            if (!runCatching { requester.requestFocus() }.isSuccess
                                                && !detailHeaderFocus.requestIfAttached()) runCatching { discoverFocus.requestFocus() }
                                        }
                                        true
                                    }
                                }
                            }
                            page == "discover" -> {
                                {
                                    val memory = discoverFocusMemory.value
                                    if (memory == null) false
                                    else {
                                        pageScope.launch {
                                            val (requester, row) = memory
                                            runCatching { discoverListState.scrollToItem(row) }
                                            repeat(4) { withFrameNanos { } }
                                            if (!runCatching { requester.requestFocus(); true }.getOrDefault(false)
                                                && !discoverEntryFocus.requestIfAttached()) runCatching { discoverFocus.requestFocus() }
                                        }
                                        true
                                    }
                                }
                            }
                            else -> null
                        },
                    )
                }
                if (statusVisible && statusNotice.isNotBlank()) Box(Modifier.align(Alignment.TopEnd).padding(top = 78.dp, end = 32.dp)
                    .background(c.panel.copy(alpha = .94f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .border(1.dp, c.line, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(statusNotice, color = c.foreground, fontSize = 12.sp, maxLines = 2)
                }
            }
            }
            AnimatedVisibility(visible = playerOpen, enter = fadeIn(tween(if (motionEnabled) 180 else 0)), exit = fadeOut(tween(if (motionEnabled) 120 else 0))) {
                Box(Modifier.fillMaxSize()) {
                    val song = queue.getOrNull(queueIndex)
                    /* The whole ladder the current source claims, plus the rungs this song has
                       already refused. Together they let the quality sheet show what exists
                       before the user spends a click finding out it does not. */
                    val ladder = remember(song?.key, playbackSong()?.pluginId, state.plugins) {
                        val target = playbackSong() ?: song
                        val owner = state.plugins.firstOrNull { it.id == target?.pluginId }
                        if (target == null || owner == null || target.pluginId == "local") emptyList()
                        else qualityLadder(owner, target)
                    }
                    /* 息屏渐隐期间与唤醒渐亮期间，播放器留在幕布下方；完全黑掉后才离场，
                       不留隐藏动画。 */
                    if (!playerScreenOff || !screenOffSettled) PrismBackground {
                        PrismPlayer(song, lyrics, lyricStatus, positionMs, durationMs, playing, playMode,
                            song != null && libraryState.favorites.any { it.key == song.key }, tierFor(song), playingQuality,
                            queueIndex, queue.size, playbackStatus,
                            streamFormat,
                            playbackSource = playbackSong(),
                            hasMv = song != null && song.hasMv && song.pluginId in mvCapableIds,
                            mvResolving = mvResolving,
                            mvOpen = activeMv != null,
                            overlayOpen = queueOpen || radioMenuOpen || phoneDialog || pendingSong != null ||
                                trackMenu != null || subjectMenu != null || collectionMenu != null || updateDialogOpen,
                            readPosition = {
                                if (recovery != null && resolving?.isActive == true) positionMs
                                else controller?.currentPosition?.coerceAtLeast(0) ?: positionMs
                            },
                            lyricFontLevel = lyricFontLevel,
                            onLyricFontLevel = { level ->
                                updatePreference("lyricFontLevel", level)
                            },
                            subLyrics = subLyrics,
                            onSubLyrics = { show ->
                                updatePreference("subLyrics", show.toString())
                            },
                            lyricOffsetMs = lyricCalibrationMs,
                            onLyricCalibration = { value ->
                                lyricCalibrationMs = value
                                playbackSong()?.let { LyricCalibration.save(this@MainActivity, it.key, value) }
                            },
                            onScreenOff = { playerScreenOff = true; screenOffSettled = false },
                            onBack = { playerOpen = false }, onPlay = { togglePlayback() },
                            onPrevious = { step(-1) }, onNext = { step(1) }, onShuffle = {
                                val modes = PlaybackMode.entries
                                updatePreference("mode", modes[(modes.indexOf(playMode) + 1) % modes.size].id)
                                saveResume()
                            },
                            /* The sheet's play-mode row is a stepper: left walks back through
                               the modes instead of wrapping forward from the other side. */
                            onModeStep = { dir ->
                                val modes = PlaybackMode.entries
                                updatePreference("mode", modes[(modes.indexOf(playMode) + dir + modes.size) % modes.size].id)
                                saveResume()
                            },
                            radioActive = ActiveQueue.radio,
                            radioLoading = radioLoading,
                            onRadioToggle = {
                                when {
                                    ActiveQueue.radio -> { leaveRadio(); saveResume() }
                                    radioLoading -> { radioJob?.cancel(); radioStatus = "已取消准备" }
                                    else -> startRadio(keepCurrent = true)
                                }
                            },
                            onRadioMenu = { radioMenuOpen = true },
                            onFavorite = { toggleFavorite(song) },
                            onSeek = { target -> seekPlayback(target) },
                            onArtist = song?.let { { openSubject(it, "artist") } },
                            onAlbum = song?.let { { openSubject(it, "album") } },
                            onQueue = { queueOpen = true },
                            onMv = { openMv(song) },
                            /* A tier the plugin left unanswered is dropped, so the pill only cycles
                               through streams this song can actually play. */
                            tiers = availableTiers.filterNot { it in unsupportedTiers[playbackSong()?.key].orEmpty() },
                            ladder = ladder,
                            onSelectQuality = { tier -> switchQuality(tier) })
                    }
                    /* 幕布：渐隐时盖住正在淡出的播放器；唤醒时播放器先回到幕布下，再渐亮出来。
                       幕布是屏幕自身状态的一部分，减少动效时直接到位。 */
                    val curtain = remember { Animatable(0f) }
                    LaunchedEffect(playerScreenOff, screenOffSettled, motionEnabled) {
                        when {
                            playerScreenOff && !screenOffSettled -> {
                                if (motionEnabled) curtain.animateTo(1f, tween(700)) else curtain.snapTo(1f)
                                screenOffSettled = true
                            }
                            !playerScreenOff && curtain.value > 0f -> {
                                if (motionEnabled) curtain.animateTo(0f, tween(300)) else curtain.snapTo(0f)
                            }
                        }
                    }
                    /* 黑色只由幕布一层来画：息屏层透明、盖在幕布之上接焦点/点击并显示
                       提示。若息屏层自带不透明黑底，入场瞬间就盖住播放器（渐隐不可见）；
                       若把它压在幕布下面，稳定后提示又被幕布盖住。 */
                    if (curtain.value > 0f) Box(Modifier.fillMaxSize().alpha(curtain.value).background(Color.Black))
                    if (playerScreenOff) PrismScreenOff(
                        keepAwake = playing && playbackState in setOf(Player.STATE_READY, Player.STATE_BUFFERING),
                        motion = motionEnabled,
                        settled = screenOffSettled,
                        onWake = { playerScreenOff = false },
                    )
                }
            }
            }
            activeMv?.let { source ->
                val mvSong = queue.getOrNull(queueIndex)
                MvPlayer(source, mvSong?.title.orEmpty(), mvSong?.artist.orEmpty(), progressKey = mvSong?.key.orEmpty()) {
                    activeMv = null
                    reportPlayback("")
                    if (resumeAfterMv) controller?.play()
                    resumeAfterMv = false
                }
            }
            if (radioMenuOpen) {
                val first = remember { FocusRequester() }
                fun closeRadioMenu() {
                    radioMenuOpen = false
                    pageScope.launch {
                        withFrameNanos { }
                        if (!playerOpen) {
                            if (page == "home") radioCardFocus.requestIfAttached() else discoverEntryFocus.requestIfAttached()
                        }
                    }
                }
                Dialog(onDismissRequest = { closeRadioMenu() }) {
                    LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; first.requestFocus() }
                    Column(Modifier.width(440.dp).background(c.panel, androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("极光电台", color = c.foreground, fontSize = 22.sp)
                        Text(if (playerOpen) "保留当前歌曲和进度，为接下来的队列穿插新发现。" else "从收藏与常听出发，穿插榜单和推荐歌单中的曲目，自动接着播放。", color = c.muted, fontSize = 13.sp)
                        if (radioStatus.contains("暂") || radioStatus.contains("失败")) Text(radioStatus, color = c.muted, fontSize = 12.sp)
                        PrismAction(if (ActiveQueue.radio) "更换接下来的歌曲" else "开启电台", {
                            closeRadioMenu(); startRadio(restart = true, keepCurrent = playerOpen || ActiveQueue.radio)
                        }, Modifier.focusRequester(first), primary = true)
                        if (ActiveQueue.radio) PrismAction("退出极光电台，保留当前队列", { leaveRadio(); saveResume(); closeRadioMenu() })
                        PrismAction("返回", { closeRadioMenu() })
                    }
                }
            }
            if (updateDialogOpen) AppUpdateDialog(updateState, appUpdates.releasePage,
                onCheck = { checkAppUpdates() }, onDownload = { downloadAppUpdate() },
                onInstall = { if (updateJob?.isActive != true) updateJob = lifecycleScope.launch { appUpdates.install(this@MainActivity) } },
                onCancel = { cancelAppUpdate() }, onSkip = {
                    appUpdates.skip(); updateDialogOpen = false
                    pageScope.launch {
                        withFrameNanos { }
                        if (page == "home") heroFocus.requestIfAttached()
                        else if (page == "plugins" && settingsSection == "关于") sectionEntryFocus.getValue("关于").requestIfAttached()
                        else pageEntryFocus?.requestIfAttached()
                    }
                },
                onDismiss = {
                    updateDialogOpen = false
                    pageScope.launch {
                        withFrameNanos { }
                        if (page == "plugins" && settingsSection == "关于") sectionEntryFocus.getValue("关于").requestIfAttached()
                        else pageEntryFocus?.requestIfAttached()
                    }
                })
            if (queueOpen) {
                val favoriteKeys = remember(libraryState.favorites) { libraryState.favorites.map { it.key }.toSet() }
                val currentQueueFocus = queuePlaybackFocus
                val queueFocusIndex = queueIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
                val queueListState = rememberLazyListState(initialFirstVisibleItemIndex = queueFocusIndex)
                var panelVisible by remember { mutableStateOf(false) }
                val panelScope = rememberCoroutineScope()
                val queueCloseFocus = remember { FocusAnchor() }
                val closeQueue: () -> Unit = { panelScope.launch { panelVisible = false; delay(260); queueOpen = false } }
                Dialog(onDismissRequest = closeQueue, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    LaunchedEffect(Unit) { panelVisible = true }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                        AnimatedVisibility(visible = panelVisible, enter = slideInHorizontally(tween(if (motionEnabled) 260 else 0)) { it }, exit = slideOutHorizontally(tween(if (motionEnabled) 260 else 0)) { it }) {
                            LaunchedEffect(Unit) {
                                withFrameNanos { }
                                /* A dialog is its own window: with nothing focused no key does
                                   anything, and an empty queue leaves 关闭 as the only target. */
                                if (queue.isNotEmpty()) currentQueueFocus.requestFocus() else queueCloseFocus.requestIfAttached()
                            }
                            Column(Modifier.fillMaxWidth(.46f).fillMaxHeight().focusRequester(queueRootFocus).focusable().background(Brush.verticalGradient(listOf(c.panel.copy(alpha = .98f), c.background.copy(alpha = .98f)))).padding(horizontal = 24.dp, vertical = 28.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    AuraMark(Modifier.size(34.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("接下来播放", color = c.foreground, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                                        Text("${queue.size} 首 · 选择即可切换", color = c.muted, fontSize = 11.sp)
                                    }
                                    PrismAction("关闭", closeQueue, Modifier.focusAnchor(queueCloseFocus))
                                }
                                Spacer(Modifier.height(14.dp))
                                if (queue.isEmpty()) Text("播放歌曲后显示队列", color = c.muted)
                                LazyColumn(Modifier.weight(1f), state = queueListState) {
                                    itemsIndexed(queue) { index, item ->
                                        val rowFocus = if (index == queueFocusIndex) currentQueueFocus else detailFocus("queue:${item.key}:$index")
                                        val rowModifier = Modifier.focusRequester(rowFocus)
                                        PrismQueueRow(item, index, index == queueIndex,
                                            /* The first row has no neighbour above it inside the panel's
                                               focus space; point Up at 关闭 so it is always reachable. */
                                            if (index == 0 && queueCloseFocus.attached) rowModifier.focusProperties { up = queueCloseFocus.requester } else rowModifier,
                                            /* Every song row in the app answers a long hold the same
                                               way; the queue is no exception. */
                                            onLongPress = { menuReturnFocus = rowFocus; trackMenu = TrackMenuTarget(item, list = "queue", originFocus = "queue:${item.key}") },
                                            favorite = item.key in favoriteKeys,
                                        ) { closeQueue(); playAt(index, openPlayer = false) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            trackMenu?.let { target ->
                val song = target.song
                val firstFocus = remember { FocusRequester() }
                var menuError by remember(song.key) { mutableStateOf("") }
                val saved = libraryState.favorites.any { it.key == song.key }
                val songPlugin = activePlugins.firstOrNull { it.id == song.pluginId }
                val artistDetail = supportsSubjectSearch(songPlugin, "artist")
                val albumDetail = supportsSubjectSearch(songPlugin, "album")
                /* Search-type support is declared per plugin. Asking an album search of a
                   music-only source returns nothing, so fall back to a song search. */
                val albumSearch = activePlugins.any { supportsSubjectSearch(it, "album") }
                val find = { term: String, type: String ->
                    trackMenu = null
                    keyword = term
                    searchType = type
                    page = "search"
                    if (!busy && term.isNotBlank()) search(term)
                }
                Dialog(onDismissRequest = { trackMenu = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; firstFocus.requestFocus() }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(Modifier.width(620.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                            .background(Brush.verticalGradient(listOf(c.panel, c.background.copy(alpha = .985f))))
                            .border(1.dp, c.line.copy(alpha = .75f), androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                            .padding(horizontal = 30.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PrismArtwork(song, Modifier.size(96.dp))
                                Spacer(Modifier.width(18.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, color = c.foreground, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(5.dp))
                                    Text(listOf(song.artist, song.album).filter { it.isNotBlank() && it != "未知歌手" }.joinToString(" · ").ifBlank { song.sourceLabel },
                                        color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(7.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        PlatformIcon(song.platform, Modifier.size(14.dp))
                                        Spacer(Modifier.width(7.dp))
                                        Text(song.sourceLabel, color = c.muted.copy(alpha = .85f), fontSize = 10.sp, maxLines = 1)
                                    }
                                }
                                AuraIconButton("close", "关闭", { trackMenu = null }, Modifier.size(38.dp))
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line.copy(alpha = .55f)))
                            /* Verbs in one centred row; the labels say whether 歌手/专辑 opens a
                               detail or falls back to a search, so the glyph is never the whole
                               instruction. */
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                                val current = queue.getOrNull(queueIndex)?.key == song.key && loadedKey == song.key &&
                                    playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED
                                PrismIconAction("play", "播放", {
                                    trackMenu = null
                                    if (current) playerOpen = true
                                    else playSingle(song)
                                }, Modifier.weight(1f, fill = false))
                                PrismIconAction(if (saved) "heartFilled" else "heart", if (saved) "已收藏" else "收藏", {
                                    menuError = ""
                                    toggleFavorite(song) { menuError = it }
                                }, Modifier.weight(1f, fill = false).focusRequester(firstFocus), active = saved)
                                if (song.artist.isNotBlank() && song.artist != "未知歌手")
                                    PrismIconAction("mic", if (artistDetail) "歌手详情" else "搜歌手", {
                                        trackMenu = null
                                        if (artistDetail) openSubject(song, "artist", target.originFocus) else find(song.artist, "music")
                                    }, Modifier.weight(1f, fill = false))
                                if (song.album.isNotBlank())
                                    PrismIconAction("album", if (albumDetail) "专辑详情" else "搜专辑", {
                                        trackMenu = null
                                        if (albumDetail) openSubject(song, "album", target.originFocus) else find(song.album, if (albumSearch) "album" else "music")
                                    }, Modifier.weight(1f, fill = false))
                                if (target.list?.startsWith("playlist:") == true)
                                    PrismIconAction("trash", "移出歌单", {
                                        trackMenu = null
                                        lifecycleScope.launch { try { library.removeSong(target.list.removePrefix("playlist:"), song) } catch (e: Exception) { status = e.message ?: "操作失败" } }
                                    }, Modifier.weight(1f, fill = false))
                                if (target.list == "recent")
                                    PrismIconAction("trash", "删除记录", {
                                        trackMenu = null
                                        lifecycleScope.launch { try { library.removeRecent(song) } catch (e: Exception) { status = e.message ?: "删除失败" } }
                                    }, Modifier.weight(1f, fill = false))
                                if (target.list == "queue")
                                    PrismIconAction("trash", "移出队列", {
                                        trackMenu = null
                                        removeQueueItem(queue.indexOfFirst { it.key == song.key })
                                    }, Modifier.weight(1f, fill = false))
                            }
                            if (menuError.isNotBlank()) Text(menuError, color = c.accent, fontSize = 12.sp)
                        }
                    }
                }
            }
            subjectMenu?.let { subject ->
                val firstFocus = remember { FocusRequester() }
                var menuError by remember(subject.key) { mutableStateOf("") }
                val saved = libraryState.subjects.any { it.key == subject.key }
                val plugin = activePlugins.firstOrNull { it.id == subject.pluginId }
                val subjectActions = (if (plugin != null) 1 else 0) + (if (subject.kind != "chart") 1 else 0) +
                    (if (subject.kind == "chart" && plugin != null) 1 else 0)
                PrismContextMenu(subject.title, if (subject.kind == "chart") "榜单" else subject.kindLabel,
                    { subjectMenu = null }, initialFocus = firstFocus, width = menuWidth(subjectActions)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                        val detailLabel = when (subject.kind) {
                            "artist" -> "歌手详情"
                            "album" -> "专辑详情"
                            "chart" -> "榜单详情"
                            else -> "歌单详情"
                        }
                        val detailIcon = when (subject.kind) { "artist" -> "mic"; "album" -> "album"; else -> "list" }
                        if (plugin != null) PrismIconAction(detailIcon, detailLabel, {
                            val origin = detailCardFocus.entries.firstOrNull { it.value === menuReturnFocus }?.key
                            subjectMenu = null
                            if (subject.kind == "chart") openChart(plugin, subject.data)
                            else openBrowse(plugin, subject.data, subject.kind, origin)
                        }, Modifier.weight(1f, fill = false).focusRequester(firstFocus))
                        if (subject.kind != "chart") PrismIconAction(if (saved) "heartFilled" else "heart", if (saved) "已收藏" else "收藏", {
                            menuError = ""
                            toggleSubject(subject.pluginId, subject.kind, subject.data, subject.sourceName) { menuError = it }
                        }, Modifier.weight(1f, fill = false).then(if (plugin == null) Modifier.focusRequester(firstFocus) else Modifier), active = saved)
                        if (subject.kind == "chart" && plugin != null) PrismIconAction("refresh", "刷新曲目", {
                            subjectMenu = null
                            lifecycleScope.launch {
                                try {
                                    val songs = fetchChart(plugin, subject.data)
                                    chartPreviews = chartPreviews + (subject.data.optString("id") to songs)
                                    status = "已刷新榜单"
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) { status = "刷新失败：${e.message}" }
                            }
                        }, Modifier.weight(1f, fill = false))
                    }
                    if (menuError.isNotBlank()) Text(menuError, color = c.accent, fontSize = 12.sp)
                }
            }
            collectionMenu?.let { target ->
                var confirming by remember(target) { mutableStateOf(false) }
                var removing by remember(target) { mutableStateOf(false) }
                var error by remember(target) { mutableStateOf("") }
                val firstFocus = remember { FocusRequester() }
                LaunchedEffect(confirming) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }
                val verb = if (target.playlistId != null) "删除歌单" else "取消专辑收藏"
                PrismContextMenu(target.title, "本机 · ${target.songs.size} 首", { if (!removing) collectionMenu = null }, initialFocus = firstFocus, width = menuWidth(2)) {
                    if (confirming) Text(if (target.playlistId != null) "删除本机歌单？此操作无法撤销。" else "取消这张专辑中 ${target.songs.size} 首歌曲的收藏？", color = c.foreground)
                    if (error.isNotBlank()) Text(error, color = c.muted)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                        if (!confirming) {
                            PrismIconAction("play", "播放", {
                                if (target.songs.isNotEmpty()) { collectionMenu = null; playQueue(target.songs) }
                                else error = "这个歌单还没有歌曲"
                            }, Modifier.weight(1f, fill = false).focusRequester(firstFocus))
                            PrismIconAction(if (target.playlistId != null) "trash" else "heartFilled", verb, { confirming = true }, Modifier.weight(1f, fill = false))
                        } else {
                            PrismIconAction("close", "取消", { if (!removing) confirming = false }, Modifier.weight(1f, fill = false).focusRequester(firstFocus))
                            PrismIconAction(if (target.playlistId != null) "trash" else "heartFilled", if (removing) "处理中…" else "确认$verb", {
                                if (!removing) lifecycleScope.launch {
                                    removing = true
                                    try {
                                        if (target.playlistId != null) library.delete(target.playlistId)
                                        else library.removeFavorites(target.songs.map { it.key }.toSet())
                                        collectionMenu = null; status = "已$verb"
                                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                    catch (e: Exception) { error = e.message ?: "操作失败" }
                                    finally { removing = false }
                                }
                            }, Modifier.weight(1f, fill = false))
                        }
                    }
                }
            }
            if (libraryListMenu) {
                val menuSongs: List<Song> = when {
                    selectedList == "recent" -> libraryState.recent
                    selectedList == "favorites" -> libraryState.favorites
                    selectedList.startsWith("playlist:") -> selectedPlaylist?.songs.orEmpty()
                    else -> emptyList()
                }
                val menuManageable = when {
                    selectedList in setOf("artists", "albums") -> libraryState.subjects.any { it.kind == if (selectedList == "artists") "artist" else "album" }
                    showingPlaylists -> libraryState.playlists.isNotEmpty()
                    else -> menuSongs.isNotEmpty()
                }
                val menuTitle = when {
                    selectedList == "recent" -> "最近播放"
                    selectedList == "artists" -> "收藏的歌手"
                    selectedList == "albums" -> "收藏的专辑"
                    selectedPlaylist != null -> selectedPlaylist?.name.orEmpty()
                    showingPlaylists -> "我的歌单"
                    else -> "收藏的歌曲"
                }
                val firstFocus = remember { FocusRequester() }
                val menuActions = (if (menuSongs.isNotEmpty()) 1 else 0) + (if (showingPlaylists) 1 else 0) + (if (menuManageable) 1 else 0)
                PrismContextMenu(menuTitle, "", { libraryListMenu = false }, firstFocus, width = menuWidth(menuActions)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                        var needsFocus = true
                        if (menuSongs.isNotEmpty()) {
                            PrismIconAction("play", "播放", { libraryListMenu = false; playQueue(menuSongs) },
                                Modifier.weight(1f, fill = false).focusRequester(firstFocus))
                            needsFocus = false
                        }
                        if (showingPlaylists) {
                            PrismIconAction("import", "导入歌单", { libraryListMenu = false; importDialog = true },
                                Modifier.weight(1f, fill = false).then(if (needsFocus) Modifier.focusRequester(firstFocus) else Modifier))
                            needsFocus = false
                        }
                        if (menuManageable) {
                            PrismIconAction("list", "管理", { libraryListMenu = false; enterLibraryManage() },
                                Modifier.weight(1f, fill = false).then(if (needsFocus) Modifier.focusRequester(firstFocus) else Modifier))
                        }
                    }
                }
            }
            if (confirmBatchOpen) {
                val showingSubjects = selectedList in setOf("artists", "albums")
                val batchVerb = when {
                    selectedList == "recent" -> "删除记录"
                    showingSubjects -> "移除收藏"
                    showingPlaylists -> "删除歌单"
                    selectedPlaylist != null -> "移出歌单"
                    else -> "取消收藏"
                }
                val batchUnit = when {
                    selectedList == "recent" -> "条"
                    showingSubjects || showingPlaylists -> "个"
                    else -> "首"
                }
                val batchNote = when {
                    selectedList == "recent" -> "删除后无法恢复"
                    showingSubjects -> "移除后可以重新收藏"
                    showingPlaylists -> "歌单和其中的歌曲会一起删除"
                    selectedPlaylist != null -> "歌曲只会移出歌单，不会删除"
                    else -> "歌曲将从收藏列表移除"
                }
                val cancelFocus = remember { FocusRequester() }
                /* The remote lands on 取消: the destructive verb must be reached on purpose. */
                Dialog(onDismissRequest = { confirmBatchOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; cancelFocus.requestFocus() }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(Modifier.width(560.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                            .background(Brush.verticalGradient(listOf(c.panel, c.background.copy(alpha = .985f))))
                            .border(1.dp, c.line.copy(alpha = .75f), androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                            .padding(horizontal = 30.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("BATCH / 批量操作", color = c.accent, fontSize = 10.sp, letterSpacing = 1.sp)
                            Text(batchVerb, color = c.foreground, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                            Text("已选 ${librarySelected.size} $batchUnit，$batchNote", color = c.muted, fontSize = 13.sp)
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                                PrismAction("取消", { confirmBatchOpen = false }, Modifier.focusRequester(cancelFocus))
                                PrismAction("确认", { runLibraryBatch() }, primary = true)
                            }
                        }
                    }
                }
                /* The dialog closes on 取消, BACK, or a finished batch; the bar's 菜单 button
                   gets the remote back in every case. */
                var batchWasOpen by remember { mutableStateOf(false) }
                LaunchedEffect(confirmBatchOpen) {
                    if (confirmBatchOpen) batchWasOpen = true
                    else if (batchWasOpen) {
                        withFrameNanos { }
                        if (!confirmBatchOpen) libraryHeaderFocus.requestIfAttached()
                        batchWasOpen = false
                    }
                }
            }
            if (importDialog) {
                val urlFocus = remember { FocusRequester() }
                val doorFocus = remember { FocusRequester() }
                val nameFocus = remember { FocusRequester() }
                val primaryFocus = remember { FocusRequester() }
                val firstRowFocus = remember { FocusRequester() }
                var picker by remember { mutableStateOf(false) }
                var targetId by remember { mutableStateOf<String?>(null) }
                val listState = rememberLazyListState()
                val scrollScope = rememberCoroutineScope()
                /* 链接先认平台，平台再给提示：认不出来就不猜，免得把别家的说明挂在你的链接上。
                   同平台常有多个源，优先用写了导入说明的那一个——否则同一首链接的提示会时有时无。 */
                val capable = plugins.state.value.plugins.filter { it.enabled && (it.metadata.optBoolean("importSheet") || it.metadata.optBoolean("importItem")) }
                val detectedPlatform = MusicPlatform.identify(shareUrlOf(importUrl))
                val samePlatform = capable.filter { it.platform == detectedPlatform }
                val detected = if (detectedPlatform == MusicPlatform.OTHER) null
                    else samePlatform.firstOrNull { importHints(it).isNotEmpty() } ?: samePlatform.firstOrNull()
                val chosen = targetId?.let { id -> capable.firstOrNull { it.id == id } }
                /* 默认跟随识别结果；用户点名之后才覆盖。点名不等于必须先选，只是「我就用这一个」。 */
                val effective = chosen ?: detected
                val hints = effective?.let { importHints(it) }.orEmpty()
                /* 换页前先把焦点交给入口行：它不随换页离场，否则新页上再 request 全部失效。 */
                fun leavePicker() { doorFocus.requestFocus(); picker = false }
                Dialog(onDismissRequest = { if (picker) leavePicker() else importDialog = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; urlFocus.requestFocus() }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(Modifier.width(620.dp).focusGroup().clip(RoundedCornerShape(24.dp))
                            .background(Brush.verticalGradient(listOf(c.panel, c.background.copy(alpha = .985f))))
                            .border(1.dp, c.line.copy(alpha = .75f), RoundedCornerShape(24.dp))
                            .padding(horizontal = 28.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("导入歌单或单曲", color = c.foreground, fontSize = 22.sp)
                                Text(if (picker) "按确定选中的音源会记住，直到关掉这个弹窗" else "粘贴分享链接，自动识别音源",
                                    color = c.muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2)
                            }
                            ImportDoorRow(picker, effective, chosen != null, capable.size, doorFocus,
                                onOpen = { if (picker) leavePicker() else if (capable.isNotEmpty()) picker = true })
                            if (!picker) {
                                Input(importUrl, { importUrl = it }, "歌单、单曲链接或编号",
                                    Modifier.fillMaxWidth().focusRequester(urlFocus).focusProperties { down = nameFocus },
                                    keyboardType = KeyboardType.Uri, manualKeyboard = true, onSubmit = { nameFocus.requestFocus() })
                                /* 说明固定两行高：输入时识别结果会变，行数跳动会把整个弹窗上下推。 */
                                Column(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    if (hints.isNotEmpty()) hints.take(2).forEach { line ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("·", color = c.accent.copy(alpha = .7f), fontSize = 11.sp, lineHeight = 16.sp)
                                            Text(line, Modifier.weight(1f), color = c.muted, fontSize = 11.sp,
                                                lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    } else Text(
                                        if (capable.isEmpty()) "还没有支持链接导入的音源，请先添加音源"
                                        else "自动 · 将按顺序尝试 ${capable.size} 个音源",
                                        color = c.muted, fontSize = 11.sp, lineHeight = 16.sp,
                                    )
                                }
                                Input(importName, { importName = it }, "歌单名称（选填）",
                                    Modifier.fillMaxWidth().focusRequester(nameFocus).focusProperties { up = urlFocus; down = primaryFocus },
                                    manualKeyboard = true, onSubmit = { primaryFocus.requestFocus() })
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PrismAction(if (busy) "导入中…" else "开始导入", {
                                        if (!busy) {
                                            val url = importUrl
                                            importDialog = false
                                            importPlaylist(importName, url, chosen)
                                        }
                                    }, Modifier.focusRequester(primaryFocus).focusProperties { up = nameFocus }, primary = true)
                                    PrismAction("用手机填写", { importDialog = false; openPhoneHub("import-sheet") }, Modifier.focusProperties { up = nameFocus })
                                    PrismAction("取消", { importDialog = false }, Modifier.focusProperties { up = nameFocus })
                                }
                                Text("导入后保存在「我的 → 歌单」。私有歌单请先登录对应音源。",
                                    color = c.muted, fontSize = 11.sp, lineHeight = 16.sp)
                            } else {
                                var focusRow by remember { mutableStateOf(0) }
                                /* 只在落点看不见时才滚：每按一下都把它顶到最上面，整列会在遥控器底下滑动。 */
                                fun reveal(index: Int) {
                                    val range = listState.layoutInfo.visibleItemsInfo
                                    if (range.isEmpty() || index < range.first().index || index > range.last().index) {
                                        scrollScope.launch { listState.animateScrollToItem(index) }
                                    }
                                }
                                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), state = listState,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    item {
                                        ImportOptionRow(selected = chosen == null, title = "自动", meta = "依次尝试全部",
                                            modifier = Modifier.focusRequester(firstRowFocus),
                                            onClick = { targetId = null; leavePicker() },
                                            onFocus = { focusRow = 0; reveal(0) })
                                    }
                                    itemsIndexed(capable) { index, plugin ->
                                        ImportOptionRow(selected = chosen?.id == plugin.id, title = plugin.displayName,
                                            platform = plugin.platform,
                                            meta = when {
                                                detected?.id == plugin.id -> "已识别"
                                                plugin.subscriptionManaged -> ""
                                                else -> "单独添加"
                                            },
                                            onClick = { targetId = plugin.id; leavePicker() },
                                            onFocus = { focusRow = index + 1; reveal(index + 1) })
                                    }
                                }
                                /* 说明只在焦点那一行，固定两行高：文字长短不再决定行高。 */
                                Column(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    val rowHints = if (focusRow == 0) listOf("从第一个可用音源开始，读到歌单就停")
                                        else capable.getOrNull(focusRow - 1)?.let { importHints(it) }.orEmpty()
                                    if (rowHints.isEmpty()) Text("这个音源没有可用的导入说明", color = c.muted, fontSize = 11.sp, lineHeight = 16.sp)
                                    else rowHints.take(2).forEach { line ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("·", color = c.accent.copy(alpha = .7f), fontSize = 11.sp, lineHeight = 16.sp)
                                            Text(line, Modifier.weight(1f), color = c.muted, fontSize = 11.sp,
                                                lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            pluginReplacements.firstOrNull()?.let { replacement ->
                key(replacement.incoming.id) {
                    val first = remember { FocusRequester() }
                    val newVersion = replacement.incoming.metadata.optString("version").ifBlank { "未知版本" }
                    PrismContextMenu("发现同名插件", "${replacement.incoming.name} · $newVersion。替换会沿用原配置和登录状态；独立安装使用独立配置，请确认来源可信。", {
                        if (!busy) resolvePluginReplacement(replacement.incoming.id, "cancel")
                    }, first) {
                        PrismAction("取消", { if (!busy) resolvePluginReplacement(replacement.incoming.id, "cancel") }, Modifier.focusRequester(first), primary = true)
                        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            replacement.candidates.forEach { candidate ->
                                val label = listOf(candidate.name, candidate.metadata.optString("author"), candidate.metadata.optString("version")).filter { it.isNotBlank() }.joinToString(" · ")
                                val blocker = replacement.replacementBlocker(candidate)
                                if (blocker != null) Text("$label：$blocker", color = c.muted, fontSize = 12.sp)
                                else PrismAction("替换 $label", { if (!busy) resolvePluginReplacement(replacement.incoming.id, "replace", candidate.id) })
                            }
                        }
                        PrismAction(if (busy) "处理中…" else "作为独立音源安装", { if (!busy) resolvePluginReplacement(replacement.incoming.id, "new") })
                    }
                }
            }
            if (phoneInput != null && phoneDialog) {
                val input = phoneInput!!
                /* Same reason as the queue panel: a dialog opens with no focus owner, so its
                   only button would be dead on a remote until Back. */
                val phoneCloseFocus = remember { FocusRequester() }
                Dialog(onDismissRequest = { phoneDialog = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; phoneCloseFocus.requestFocus() }
                    val shape = RoundedCornerShape(18.dp)
                    val qrPixels = with(LocalDensity.current) { 216.dp.roundToPx() }
                    val link = if (phoneRemoteMode) "${input.url}#$phoneRemoteEntry" else input.url
                    val qr = remember(link, qrPixels) { qrCodeBitmap(link, qrPixels) }
                    Row(Modifier.width(560.dp).clip(shape).background(c.panel)
                        .border(1.dp, c.accent.copy(alpha = .3f), shape).padding(24.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp)) {
                            Image(qr.asImageBitmap(),
                                contentDescription = if (phoneRemoteMode) "手机遥控二维码" else "手机输入二维码",
                                modifier = Modifier.size(216.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(if (phoneRemoteMode) "手机遥控" else "扫码输入配置", color = c.foreground,
                                fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            Text("手机与电视连接同一网络后扫码", color = c.muted, fontSize = 13.sp)
                            if (phoneRemoteMode) Text("播放控制、搜索、音源管理与备份恢复", color = c.foreground, fontSize = 14.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                SettingsButton("隐藏二维码", { phoneDialog = false }, Modifier.focusRequester(phoneCloseFocus), primary = true)
                                SettingsButton(if (phoneRemoteMode) "结束遥控" else "结束输入", { closePhoneInput() })
                            }
                        }
                    }
                }
            }
        }
    }

    /* 入口行兼返回行：同一条槽位只换文案，节点不离开组合，换页时焦点才留得住。 */
    @Composable private fun ImportDoorRow(
        picker: Boolean, plugin: Plugin?, explicit: Boolean, count: Int,
        focus: FocusRequester, onOpen: () -> Unit,
    ) {
        val c = LocalPrismColors.current
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).focusRequester(focus).rowFocus().tvClickable(onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = if (picker) "返回导入" else "选择导入音源" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (picker) {
                Glyph("back", Modifier.size(14.dp), c.muted)
                Text("返回导入", Modifier.weight(1f), color = c.foreground, fontSize = 14.sp, maxLines = 1)
                Text("$count 个音源", color = c.muted, fontSize = 11.sp, maxLines = 1)
            } else {
                if (plugin != null) PlatformIcon(plugin.platform, Modifier.size(26.dp))
                else Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { Glyph("layers", Modifier.size(19.dp), c.muted) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(when { plugin == null -> "导入音源"; explicit -> "指定音源"; else -> "识别为" },
                        color = c.muted, fontSize = 11.sp, lineHeight = 15.sp)
                    Text(plugin?.displayName ?: "自动 · 依次尝试 $count 个",
                        color = c.foreground, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("更换 ›", color = c.muted, fontSize = 12.sp, maxLines = 1)
            }
        }
    }

    /* 音源列表的一行：图标 + 名称 + 标记 + 选中勾。行高固定，说明放到列表下方单独一栏。 */
    @Composable private fun ImportOptionRow(
        selected: Boolean, title: String, meta: String,
        onClick: () -> Unit, onFocus: () -> Unit,
        modifier: Modifier = Modifier, platform: MusicPlatform? = null,
    ) {
        val c = LocalPrismColors.current
        Row(modifier.fillMaxWidth().height(52.dp).rowFocus()
            .onFocusChanged { if (it.isFocused) onFocus() }
            .tvClickable(onClick).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (platform != null) PlatformIcon(platform, Modifier.size(24.dp))
            else Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Glyph("layers", Modifier.size(18.dp), c.muted) }
            Text(title, Modifier.weight(1f), color = c.foreground, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta.isNotBlank()) Text(meta, color = c.muted, fontSize = 11.sp, maxLines = 1)
            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                if (selected) Glyph("check", Modifier.size(13.dp), c.accent)
            }
        }
    }

    @Composable private fun PluginSourceRow(plugin: Plugin, owned: Boolean, onConfigure: () -> Unit, onRemoved: () -> Unit, configureFocus: FocusAnchor, edge: Modifier = Modifier) {
        val c = LocalPrismColors.current
        var menuOpen by remember(plugin.id) { mutableStateOf(false) }
        var infoOpen by remember(plugin.id) { mutableStateOf(false) }
        var confirmRemoval by remember(plugin.id) { mutableStateOf(false) }
        val moreFocus = remember(plugin.id) { FocusRequester() }
        val menuFocus = remember(plugin.id) { FocusRequester() }
        val toggleFocus = remember(plugin.id) { FocusAnchor() }
        val scope = rememberCoroutineScope()
        fun closeMenu() {
            menuOpen = false; confirmRemoval = false
            scope.launch { withFrameNanos { }; runCatching { moreFocus.requestFocus() } }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PlatformIcon(plugin.platform, Modifier.size(30.dp))
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(plugin.displayName, color = if (plugin.enabled) c.foreground else c.muted, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    /* Capability tags are gone; only the missing-search warning stays — the one
                       that would make the user tap in vain. */
                    Text(
                        listOfNotNull(
                            plugin.metadata.optString("version").takeIf { it.isNotBlank() }?.let { "v$it" },
                            plugin.metadata.optString("author").takeIf { it.isNotBlank() },
                            "不支持搜索".takeIf { !plugin.capabilities.has("search") },
                        ).joinToString(" · "),
                        color = c.muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(edge.focusAnchor(toggleFocus).focusProperties {
                    right = configureFocus.requester
                }.auraFocus(RoundedCornerShape(16.dp), 1f, ring = c.foreground)
                    .toggleable(plugin.enabled, role = Role.Switch, onValueChange = { enable ->
                        /* 焦点要等重组后的节点，所以必须挂在这一帧的帧时钟上：lifecycleScope 的
                           上下文里没有 MonotonicFrameClock，withFrameNanos 会直接抛异常。 */
                        scope.launch {
                            try { plugins.setEnabled(plugin.id, enable); withFrameNanos { }; toggleFocus.requestIfAttached() }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { status = e.message ?: "操作失败" }
                        }
                    }).padding(4.dp)) {
                    PrismCapsule(plugin.enabled)
                }
                SettingsButton("配置", onConfigure, Modifier.focusAnchor(configureFocus).focusProperties { left = toggleFocus.requester; right = moreFocus })
                SettingsButton("更多", { confirmRemoval = false; menuOpen = true }, Modifier.focusRequester(moreFocus).focusProperties { left = configureFocus.requester })
            }
        }
        if (menuOpen) key(confirmRemoval) {
            val actions = if (confirmRemoval) 2
                else 1 + (if (plugin.enabled && plugin.capabilities.has("search")) 1 else 0) +
                    (if (plugin.updateUrl.isNotBlank()) 1 else 0) + (if (!owned) 1 else 0)
            PrismContextMenu(if (confirmRemoval) "移除 ${plugin.displayName}？" else plugin.displayName,
                if (confirmRemoval) "本机歌单和收藏会保留" else listOfNotNull(
                    plugin.metadata.optString("version").takeIf { it.isNotBlank() }?.let { "v$it" },
                    plugin.metadata.optString("author").takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { "未提供版本与作者" }, { closeMenu() }, menuFocus, width = menuWidth(actions)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                    if (confirmRemoval) {
                        PrismIconAction("close", "取消", { closeMenu() }, Modifier.weight(1f, fill = false).focusRequester(menuFocus))
                        PrismIconAction("trash", "移除音源", {
                            menuOpen = false
                            lifecycleScope.launch { try { plugins.remove(plugin.id); status = "已移除音源"; onRemoved() } catch (e: Exception) { status = e.message ?: "移除失败" } }
                        }, Modifier.weight(1f, fill = false))
                    } else {
                        PrismIconAction("info", "音源信息", { menuOpen = false; infoOpen = true }, Modifier.weight(1f, fill = false).focusRequester(menuFocus))
                        if (plugin.enabled && plugin.capabilities.has("search")) PrismIconAction("search", "搜索此音源", {
                            menuOpen = false; selectedPlugin = plugin.id; searchSources = setOf(plugin.id); page = "search"
                        }, Modifier.weight(1f, fill = false))
                        if (plugin.updateUrl.isNotBlank()) PrismIconAction("refresh", "更新插件", {
                            closeMenu()
                            lifecycleScope.launch {
                                try { showPluginImport(plugins.refresh(plugin.id)) }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) { status = "更新失败：${e.message}" }
                            }
                        }, Modifier.weight(1f, fill = false))
                        if (!owned) PrismIconAction("trash", "移除音源", { confirmRemoval = true }, Modifier.weight(1f, fill = false))
                    }
                }
            }
        }
        if (infoOpen) PluginInfoDialog(plugin) { infoOpen = false; menuOpen = true }
    }

    private class SourceAddress(val label: String, val url: String)

    private fun sourceAddresses(plugin: Plugin): List<SourceAddress> {
        val update = plugin.updateUrl
        return buildList {
            if (plugin.url.isNotBlank() && plugin.url == update) add(SourceAddress("插件／更新地址", plugin.url))
            else {
                if (plugin.url.isNotBlank()) add(SourceAddress("插件地址", plugin.url))
                if (update.isNotBlank()) add(SourceAddress("更新地址", update))
            }
            if (plugin.subscriptionManaged) add(SourceAddress("订阅来源", plugin.origin))
        }
    }

    /* 只有一层弹窗。地址是同一窗里的下页，来回靠底部那行切换：换页时焦点
       留在这一行上（它没离开组合），所以换完不需要请求焦点——弹窗里内容一
       换，旧焦点就没了，之后再 request 全部失效。 */
    @Composable private fun PluginInfoDialog(plugin: Plugin, onDismiss: () -> Unit) {
        val c = LocalPrismColors.current
        val addresses = remember(plugin) { sourceAddresses(plugin) }
        val sourceBytes = remember(plugin.source) { plugin.source.toByteArray(Charsets.UTF_8).size }
        var open by remember(plugin.id) { mutableStateOf(false) }
        val doorFocus = remember(plugin.id) { FocusRequester() }
        val closeFocus = remember(plugin.id) { FocusRequester() }
        /* 返回键先退回上页：焦点得先落到入口行（它不随换页离场），
           否则旧焦点跟着地址行一起消失，之后谁也接不住。 */
        PrismContextMenu(plugin.displayName, "音源信息",
            { if (open) { doorFocus.requestFocus(); open = false } else onDismiss() },
            if (addresses.isEmpty()) closeFocus else doorFocus) {
            if (open) {
                SourceAddressList(addresses)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PlatformIcon(plugin.platform, Modifier.size(36.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("版本 ${plugin.metadata.optString("version").ifBlank { "未提供" }} · %.1f KiB".format(sourceBytes / 1024.0),
                            color = c.foreground, fontSize = 15.sp, lineHeight = 20.sp)
                        Text("作者 ${plugin.metadata.optString("author").ifBlank { "未提供" }}",
                            color = c.muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (plugin.enabled) "已启用" else "已停用",
                        color = if (plugin.enabled) c.accent else c.muted, fontSize = 12.sp)
                }
                plugin.metadata.optString("description").trim().takeIf { it.isNotBlank() }?.let { text ->
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("说明", color = c.muted, fontSize = 11.sp, letterSpacing = .6.sp)
                        Text(text, color = c.foreground, fontSize = 14.sp, lineHeight = 21.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("支持能力", color = c.muted, fontSize = 11.sp, letterSpacing = .6.sp)
                    Text(plugin.capabilities.labels.joinToString(" · ").ifBlank { "未识别到支持的能力" },
                        color = c.foreground, fontSize = 14.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (addresses.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.foreground.copy(alpha = .09f)))
                DoorRow(open, addresses.size, { open = !open }, doorFocus,
                    Modifier.focusProperties { up = closeFocus; down = closeFocus })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PrismAction("关闭", onDismiss, Modifier.focusRequester(closeFocus)
                    .focusProperties { up = doorFocus }, primary = true)
            }
        }
    }

    /* 下页入口。同一条槽位只换文案与图标，节点不离开组合，焦点才不会丢。 */
    @Composable private fun DoorRow(
        open: Boolean, count: Int, onToggle: () -> Unit,
        focus: FocusRequester, modifier: Modifier = Modifier,
    ) {
        val c = LocalPrismColors.current
        Row(modifier.fillMaxWidth().heightIn(min = 46.dp).focusRequester(focus).rowFocus()
            .clickable { onToggle() }.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Glyph(if (open) "back" else "link", Modifier.size(15.dp),
                if (open) c.muted else c.accent.copy(alpha = .75f))
            Spacer(Modifier.width(11.dp))
            Text(if (open) "返回音源信息" else "查看地址", Modifier.weight(1f),
                color = if (open) c.muted else c.foreground, fontSize = 14.sp, maxLines = 1)
            if (!open) Text("$count 项", color = c.muted, fontSize = 12.sp)
        }
    }

    /* 地址只读：电视上没有粘贴的地方。 */
    @Composable private fun SourceAddressList(addresses: List<SourceAddress>) {
        val c = LocalPrismColors.current
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            addresses.forEach { address ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(address.label, color = c.muted, fontSize = 12.sp, lineHeight = 16.sp)
                    Text(address.url, color = c.foreground, fontSize = 12.sp, lineHeight = 17.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    @Composable private fun PluginConfig(plugin: Plugin, entry: FocusAnchor, memory: MutableState<FocusRequester?>, onBack: () -> Unit) {
        val c = LocalPrismColors.current
        val fields = plugin.metadata.optJSONArray("userVariables") ?: JSONArray()
        /* 缺 key 的字段不画，也不进焦点链：两边数量对不上会留下一环永远按不到的空位。 */
        val entries = remember(plugin.id) { buildList {
            for (i in 0 until fields.length()) {
                val declared = fields.optJSONObject(i) ?: continue
                val declaredKey = declared.optString("key")
                if (declaredKey.isNotBlank()) add(declared to declaredKey)
            }
        } }
        val values = remember(plugin.id) { mutableStateMapOf<String, String>().also { map ->
            for (i in 0 until fields.length()) fields.optJSONObject(i)?.let { f -> val k = f.optString("key"); if (k.isNotBlank()) map[k] = plugin.variables.optString(k) }
        } }
        val canLoginQr = plugin.metadata.optBoolean("loginQr")
        val hasFields = entries.isNotEmpty()
        val loginOpen = remember(plugin.id) { mutableStateOf(false) }
        var manualOpen by remember(plugin.id) { mutableStateOf(false) }
        var infoOpen by remember(plugin.id) { mutableStateOf(false) }
        var platformOpen by remember(plugin.id) { mutableStateOf(false) }
        var resetOpen by remember(plugin.id) { mutableStateOf(false) }
        var resetting by remember(plugin.id) { mutableStateOf(false) }
        var saving by remember(plugin.id) { mutableStateOf(false) }
        var error by remember(plugin.id) { mutableStateOf("") }
        val backFocus = remember(plugin.id) { FocusRequester() }
        val manualFocus = remember(plugin.id) { FocusRequester() }
        val infoFocus = remember(plugin.id) { FocusRequester() }
        val resetFocus = remember(plugin.id) { FocusRequester() }
        val platformFocus = remember(plugin.id) { FocusRequester() }
        val fieldFocuses = remember(plugin.id, entries.size) { List(entries.size) { FocusRequester() } }
        val loginFocus = remember(plugin.id) { FocusRequester() }
        val phoneFocus = remember(plugin.id) { FocusRequester() }
        val manualCloseFocus = remember(plugin.id) { FocusRequester() }
        val manualSaveFocus = remember(plugin.id) { FocusRequester() }
        val bottomFocus = remember(plugin.id) { FocusRequester() }
        val scope = rememberCoroutineScope()
        val firstFocus = when { canLoginQr -> loginFocus; hasFields -> manualFocus; else -> infoFocus }
        val beforeSettings = firstFocus.takeIf { canLoginQr || hasFields } ?: backFocus
        val dirty = entries.any { (_, key) -> values[key].orEmpty() != plugin.variables.optString(key) }
        fun closeManual() {
            if (saving) return
            values.keys.toList().forEach { key -> values[key] = plugin.variables.optString(key) }
            manualOpen = false; error = ""
            scope.launch { withFrameNanos { }; manualFocus.requestFocus() }
        }
        fun saveManual() {
            if (saving) return
            if (!dirty) { closeManual(); return }
            lifecycleScope.launch {
                saving = true; error = ""
                try {
                    val merged = JSONObject(plugin.variables.toString())
                    values.forEach { (key, value) -> merged.put(key, value) }
                    plugins.setVariables(plugin.id, merged); status = "配置已保存"; manualOpen = false
                    scope.launch { withFrameNanos { }; manualFocus.requestFocus() }
                }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "保存失败" }
                finally { saving = false }
            }
        }
        LaunchedEffect(plugin.id) { repeat(2) { withFrameNanos { } }; firstFocus.requestFocus() }
        /* Reset restores a freshly-installed source: variables emptied, cookies cleared, cached
           URLs invalidated. Partial success must report exactly which half failed. */
        fun resetConfiguration() {
            if (resetting) return
            lifecycleScope.launch {
                resetting = true; error = ""
                try {
                    val failures = plugins.resetConfiguration(plugin.id)
                    val stored = plugins.state.value.plugins.firstOrNull { it.id == plugin.id }?.variables
                    values.keys.toList().forEach { key -> values[key] = stored?.optString(key).orEmpty() }
                    if (failures.isEmpty()) status = "配置已重置"
                    else error = failures.joinToString("；")
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "重置失败" }
                finally {
                    resetting = false
                    scope.launch { withFrameNanos { }; runCatching { resetFocus.requestFocus() } }
                }
            }
        }
        fun closeReset() {
            resetOpen = false
            scope.launch { withFrameNanos { }; runCatching { resetFocus.requestFocus() } }
        }
        fun closeQr() {
            loginOpen.value = false
            scope.launch { withFrameNanos { }; loginFocus.requestFocus() }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PlatformIcon(plugin.platform, Modifier.size(42.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${plugin.displayName}配置", fontSize = 22.sp, color = c.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(plugin.metadata.optString("version").takeIf { it.isNotBlank() }?.let { "v$it" },
                        if (plugin.enabled) "已启用" else "已停用").joinToString(" · "), color = c.muted, fontSize = 12.sp)
                }
                PrismAction("返回音源", { if (!saving) onBack() }, Modifier.focusRequester(backFocus).focusProperties { down = firstFocus }.rememberFocus(memory))
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (canLoginQr || hasFields) {
                /* The count only counts keys with a value: it neither validates required
                   fields nor blocks saving. */
                if (canLoginQr) PrismConfigChoice("扫码登录", "使用对应平台 App 扫码，成功后自动保存", "qr",
                    { manualOpen = false; loginOpen.value = true },
                    Modifier.fillMaxWidth().focusRequester(loginFocus).focusAnchor(entry)
                        .focusProperties { up = backFocus; down = if (hasFields) manualFocus else infoFocus }
                        .rememberFocus(memory))
                if (hasFields) {
                    val filled = entries.count { plugin.variables.optString(it.second).isNotBlank() }
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .focusRequester(manualFocus).then(if (!canLoginQr) Modifier.focusAnchor(entry) else Modifier)
                        .focusProperties { up = if (canLoginQr) loginFocus else backFocus; down = infoFocus }.rememberFocus(memory)
                        .rowFocus()
                        .clickable {
                            values.keys.toList().forEach { key -> values[key] = plugin.variables.optString(key) }
                            error = ""; manualOpen = true
                        }.padding(start = 12.dp, top = 8.dp, end = 10.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Glyph("keyboard", Modifier.size(18.dp), c.muted)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("手动填写", color = c.foreground, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                            Text("${entries.size} 项 · 已填 $filled", color = c.muted, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                        Text("›", color = c.accent, fontSize = 20.sp)
                    }
                }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrismAction("音源信息", { infoOpen = true; platformOpen = false },
                        Modifier.focusRequester(infoFocus).then(if (!canLoginQr && !hasFields) Modifier.focusAnchor(entry) else Modifier)
                            .focusProperties { up = beforeSettings; down = bottomFocus; right = platformFocus }.rememberFocus(memory), icon = "info")
                    Box {
                        val platforms = remember { listOf<MusicPlatform?>(null) + MusicPlatform.entries.filterNot { it in setOf(MusicPlatform.LOCAL, MusicPlatform.CUSTOM, MusicPlatform.OTHER) } }
                        fun closePlatforms() {
                            platformOpen = false
                            scope.launch { withFrameNanos { }; platformFocus.requestFocus() }
                        }
                        PrismAction("平台 · ${SourcePlatforms.get(plugin.id)?.label ?: "自动识别"} ▾", { platformOpen = true; infoOpen = false },
                            Modifier.focusRequester(platformFocus)
                                .focusProperties { up = beforeSettings; down = bottomFocus; left = infoFocus; right = resetFocus }.rememberFocus(memory))
                        if (platformOpen) ChoicePopup(platforms, platforms.indexOf(SourcePlatforms.get(plugin.id)),
                            label = { it?.label ?: "自动识别" }, onDismiss = { closePlatforms() },
                            onSelect = { SourcePlatforms.set(plugin.id, it); closePlatforms() },
                            icon = { if (it == null) Glyph("refresh", Modifier.size(22.dp), c.muted) else PlatformIcon(it, Modifier.size(22.dp)) })
                    }
                    PrismAction(if (resetting) "重置中…" else "重置配置", { if (!resetting) resetOpen = true }, Modifier.focusRequester(resetFocus)
                        .focusProperties { left = platformFocus; up = beforeSettings; down = bottomFocus }.rememberFocus(memory), icon = "trash")
                }
            }
            if (error.isNotBlank()) Text(error, color = c.accent, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line.copy(alpha = .35f)))
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("配置保存在本机", color = c.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                PrismAction("完成", onBack,
                    Modifier.focusRequester(bottomFocus).focusProperties { up = resetFocus }.rememberFocus(memory), primary = true)
            }
        }
        if (manualOpen) Dialog(onDismissRequest = { closeManual() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            LaunchedEffect(plugin.id) { repeat(2) { withFrameNanos { } }; fieldFocuses.first().requestFocus() }
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 800.dp).fillMaxWidth(.86f).heightIn(max = maxHeight - 40.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(c.panel).padding(24.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("手动配置 · ${plugin.displayName}", color = c.foreground, fontSize = 20.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        PrismAction("用手机填写", {
                            openPhoneInput(entries.map { (field, key) -> key to field.optString("title").ifBlank { key } }) { submitted ->
                                submitted.forEach { (key, value) -> if (value.isNotBlank() && key in values) values[key] = value }
                            }
                        }, Modifier.focusRequester(phoneFocus).focusProperties { down = fieldFocuses.first(); right = manualCloseFocus })
                        Spacer(Modifier.width(8.dp))
                        AuraIconButton("close", "取消手动配置", { closeManual() }, Modifier.focusRequester(manualCloseFocus)
                            .focusProperties { down = fieldFocuses.first(); left = phoneFocus })
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val filledCount = entries.count { values[it.second].orEmpty().isNotBlank() }
                        Text("${entries.size} 项 · 已填 $filledCount", color = c.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("确定键开始输入，方向键离开", color = c.muted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.foreground.copy(alpha = .10f)))
                    Spacer(Modifier.height(4.dp))
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        entries.forEachIndexed { position, (field, key) ->
                            val title = field.optString("title").ifBlank { field.optString("name").ifBlank { key } }
                            val secret = credentialKeys.any { key.contains(it, ignoreCase = true) }
                            val filled = values[key].orEmpty().isNotBlank()
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(title, color = c.foreground, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                    Text(if (filled) "已填写" else "未填写", color = if (filled) c.accent else c.muted, fontSize = 11.sp)
                                }
                                Input(values[key].orEmpty(), { values[key] = it }, field.optString("hint").ifBlank { "未填写" },
                                    Modifier.fillMaxWidth().focusRequester(fieldFocuses[position]).focusProperties {
                                        up = fieldFocuses.getOrNull(position - 1) ?: phoneFocus
                                        down = fieldFocuses.getOrNull(position + 1) ?: manualSaveFocus
                                    }, secret = secret, manualKeyboard = true,
                                    onSubmit = { (fieldFocuses.getOrNull(position + 1) ?: manualSaveFocus).requestFocus() })
                            }
                        }
                    }
                    if (error.isNotBlank()) Text(error, color = c.accent, fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(top = 10.dp))
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.foreground.copy(alpha = .10f)))
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                        PrismAction("取消", { closeManual() }, Modifier.focusProperties { up = fieldFocuses.last(); right = manualSaveFocus })
                        Spacer(Modifier.width(10.dp))
                        PrismAction(if (saving) "保存中…" else "保存配置", { saveManual() }, Modifier.focusRequester(manualSaveFocus)
                            .focusProperties { up = fieldFocuses.last() }, primary = true)
                    }
                }
            }
        }
        if (loginOpen.value) PluginLoginQr(plugin, onClose = { closeQr() }, onApplied = { returned ->
            val merged = JSONObject(plugin.variables.toString())
            returned.keys().forEach { key -> merged.put(key, returned.optString(key)) }
            closeQr()
            lifecycleScope.launch {
                /* The status only appears after a successful save; the count is the number of
                   keys the plugin returned, not a completion verdict. */
                val written = returned.length()
                try {
                    plugins.setVariables(plugin.id, merged)
                    returned.keys().forEach { key -> if (key in values) values[key] = returned.optString(key) }
                    status = if (written > 0) "扫码成功，已写入 $written 项配置" else "扫码成功，本次未返回配置项"
                }
                catch (e: Exception) { error = "登录成功，但写入配置失败：${e.message}" }
            }
        })
        if (infoOpen) PluginInfoDialog(plugin) {
            infoOpen = false
            scope.launch { withFrameNanos { }; infoFocus.requestFocus() }
        }
        if (resetOpen) PrismConfirmDialog(
            "重置音源配置？",
            "将清除该音源填写的参数和本机保存的登录信息，之后可重新登录或填写配置。音源、收藏和歌单会保留。",
            "重置配置",
            onDismiss = { closeReset() },
            onConfirm = { resetOpen = false; resetConfiguration() },
            confirmIcon = "trash",
        )
    }

    /* 弹窗只留四行给提示，而平台原话可能是一整个响应体；这里裁短，完整文本留在 logcat。 */
    private fun loginReason(why: String) = if (why.length <= 60) why else why.take(60) + "…"

    /* 扫码登录是 Prism 的扩展能力（非 MusicFree 标准）：插件负责取二维码、报状态，界面只管展示与写入。
       任何一步失败都只停在这个弹窗里，音源已有的 Cookie 和手填路径不受影响。 */
    @Composable private fun PluginLoginQr(plugin: Plugin, onClose: () -> Unit, onApplied: (JSONObject) -> Unit) {
        val c = LocalPrismColors.current
        /* Prism 扩展：插件可以声明同一个登录入口里的几种出码方式（QQ/微信、桌面端/网页端）。
           这是登录流程中的临时选择，所以画在二维码上方，不占配置项；没有声明的插件这一行不存在。 */
        val methods = plugin.metadata.optJSONArray("loginMethods") ?: JSONArray()
        val choosable = methods.length() > 1
        var methodKey by remember(plugin.id) { mutableStateOf(methods.optJSONObject(0)?.optString("key").orEmpty()) }
        var methodSwitch by remember(plugin.id) { mutableStateOf(false) }
        val methodFocus = remember { FocusRequester() }
        var attempt by remember(plugin.id) { mutableStateOf(0) }
        var image by remember(plugin.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
        var link by remember(plugin.id) { mutableStateOf("") }
        var message by remember(plugin.id) { mutableStateOf("正在获取二维码…") }
        var waiting by remember(plugin.id) { mutableStateOf(false) }
        val refreshFocus = remember { FocusRequester() }
        val closeFocus = remember { FocusRequester() }
        /* A dialog opens with no focus owner, so the remote would be dead until Back. */
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(attempt, methodKey, plugin.id) {
            /* 换登录方式是自己按的，重新出码后焦点必须还停在那一行上；
               把它推回「刷新二维码」会让人以为界面在抢操作。
               出码也不算用户操作，所以这里不再监听 waiting：以前码一出现焦点就跳到「关闭」，
               那一刻整张卡片像是自己动了一下。 */
            val target = if (methodSwitch && choosable) { methodSwitch = false; methodFocus } else refreshFocus
            /* 按钮比弹窗晚一帧合成，第一次要焦点可能落在还没挂载的节点上，重试到挂上为止。 */
            repeat(2) { withFrameNanos { } }
            target.requestFocus()
        }
        LaunchedEffect(attempt, methodKey, plugin.id) {
            image = null; link = ""; waiting = false; message = "正在获取二维码…"
            android.util.Log.i("PrismLogin", "获取二维码：${plugin.displayName} 方式=${methodKey.ifBlank { "默认" }}")
            val session = runCatching { plugins.client.call(plugin.source, plugin.variables, "getLoginSession", JSONArray().put(methodKey), plugin.id) as? JSONObject }
            val opened = session.getOrNull()
            if (opened == null) {
                /* The plugin's own error usually names the platform response (风控、参数错误、超时),
                   which is the only clue the user can act on, so show it instead of a generic phrase. */
                val why = session.exceptionOrNull()?.message ?: "该音源没有返回登录会话"
                android.util.Log.w("PrismLogin", "获取二维码失败：$why")
                message = "获取二维码失败：" + loginReason(why)
                return@LaunchedEffect
            }
            fun renderQr(payload: JSONObject) {
                link = payload.optString("qrText")
                image = if (link.isNotBlank()) runCatching { qrCodeBitmap(link) }.getOrNull()
                else payload.optString("qrImageBase64").takeIf { it.isNotBlank() }?.let { encoded ->
                    runCatching {
                        val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                }
            }
            renderQr(opened)
            message = if (image == null && link.isBlank()) "该音源没有返回二维码" else loginReason(opened.optString("hint")).ifBlank { "用手机 App 扫码，并在手机上确认登录" }
            if (image == null) return@LaunchedEffect
            var sessionKey = opened.optString("session")
            if (sessionKey.isBlank()) { message = "该音源没有返回登录会话标识"; return@LaunchedEffect }
            fun deadline(payload: JSONObject): Long {
                val seconds = payload.optLong("expiresIn", opened.optLong("expiresIn", 0))
                return if (seconds > 0) android.os.SystemClock.elapsedRealtime() + seconds.coerceAtMost(86400) * 1000 else Long.MAX_VALUE
            }
            var expiresAt = deadline(opened)
            waiting = true
            android.util.Log.i("PrismLogin", "二维码已出，开始每 2 秒轮询")
            while (isActive) {
                delay(2000)
                if (android.os.SystemClock.elapsedRealtime() >= expiresAt) {
                    waiting = false
                    message = "二维码已失效，请刷新后重新扫码"
                    return@LaunchedEffect
                }
                val poll = runCatching { plugins.client.call(plugin.source, plugin.variables, "pollLoginStatus", JSONArray().put(sessionKey), plugin.id) as? JSONObject }
                val failure = poll.exceptionOrNull()?.message
                val state = poll.getOrNull() ?: JSONObject()
                if (failure != null) android.util.Log.w("PrismLogin", "轮询调用失败：$failure")
                /* 平台对某一类登录接口风控时，插件可以在轮询里直接换一张二维码（新的 session + qrText），
                   界面必须接受并重画，否则用户只能关掉弹窗自己去改配置——那等于没有扫码登录。 */
                if (state.optString("state") != "confirmed") {
                    val rotated = state.optString("session")
                    if (rotated.isNotBlank() && rotated != sessionKey) {
                        sessionKey = rotated
                        renderQr(state)
                        expiresAt = deadline(state)
                        android.util.Log.i("PrismLogin", "插件轮换二维码，继续轮询")
                    }
                }
                when (state.optString("state")) {
                    "pending" -> { val next = state.optString("message"); if (next.isNotBlank()) message = loginReason(next) }
                    "confirmed" -> {
                        waiting = false
                        message = loginReason(state.optString("message")).ifBlank { "登录成功" }
                        val returned = state.optJSONObject("variables") ?: JSONObject()
                        android.util.Log.i("PrismLogin", "登录成功，写入配置键：${returned.names()}")
                        onApplied(returned)
                        return@LaunchedEffect
                    }
                    "expired" -> { waiting = false; message = loginReason(state.optString("message")).ifBlank { "二维码已失效，请刷新后重新扫码" }; android.util.Log.w("PrismLogin", "二维码失效：$message"); return@LaunchedEffect }
                    else -> {
                        waiting = false
                        /* A plugin that threw, or one that answers with a state we don't know, must still
                           tell the user why — the platform's own wording is what makes 风控 actionable. */
                        val why = state.optString("message").takeIf { it.isNotBlank() } ?: failure
                        message = "无法确认登录状态" + (why?.let { "：" + loginReason(it) } ?: "") + "，也可以直接填写 Cookie"
                        android.util.Log.w("PrismLogin", "登录状态未知：${state.optString("message")} / ${failure ?: "无"}")
                        return@LaunchedEffect
                    }
                }
            }
        }
        Column(Modifier.width(680.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(c.panel).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("扫码登录 · ${plugin.displayName}", color = c.foreground, fontSize = 22.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                /* 码到不到都画同一个槽位：等待时是一块浅底方框，明确留着这块地方；码出现时淡入填进
                   同一个位置，不是凭空多出一个元素——原来的空框到二维码是硬切，看着像闪了一下。 */
                Box(
                    Modifier.size(190.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(c.line.copy(alpha = .30f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .border(1.dp, c.line.copy(alpha = .75f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val qr = image
                    val reveal by animateFloatAsState(
                        if (qr != null || link.isNotBlank()) 1f else 0f,
                        tween(if (LocalAuraMotion.current) 220 else 0), label = "二维码淡入",
                    )
                    if (qr != null) Image(qr.asImageBitmap(), contentDescription = "扫码登录二维码", modifier = Modifier.size(190.dp).alpha(reveal))
                    else if (link.isNotBlank()) Text(link, color = c.muted, fontSize = 10.sp, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(reveal))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (choosable) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (index in 0 until methods.length()) {
                        val item = methods.optJSONObject(index) ?: continue
                        val key = item.optString("key")
                        val selected = key == methodKey
                        PrismAction(item.optString("name").ifBlank { key }, {
                            if (!selected) { methodSwitch = true; methodKey = key; attempt++ }
                        }, (if (selected) Modifier.focusRequester(methodFocus) else Modifier)
                            .focusProperties { down = refreshFocus }, primary = selected)
                    }
                }
                /* 提示语每个状态长短不同：只保底高度会让卡片在屏幕上重新居中，用户读到的就是晃。
                   按最多四行一次留满，文字垂直居中，长短都不改变卡片尺寸。 */
                Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                    Text(message, color = c.muted, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrismAction("刷新二维码", { attempt++ }, Modifier.focusRequester(refreshFocus)
                        .focusProperties { if (choosable) up = methodFocus; right = closeFocus })
                    PrismAction("返回配置", onClose, Modifier.focusRequester(closeFocus)
                        .focusProperties { if (choosable) up = methodFocus; left = refreshFocus })
                }
            }
        }
        }
        }
    }

    override fun onStop() {
        playerScreenOff = false
        screenOffSettled = true
        wakeKeys.clear()
        saveResume()
        closePhoneSessions()
        super.onStop()
    }

    override fun onDestroy() {
        radioJob?.cancel(); radioAdvanceJob?.cancel()
        if (::appUpdates.isInitialized) { updateJob?.cancel(); appUpdates.cancelNetwork() }
        closePhoneSessions()
        recovery?.ready?.cancel(); recovery = null
        lyricJob?.cancel(); resolving?.cancel(); controller?.removeListener(listener); connection?.let(MediaController::releaseFuture); controller = null
        super.onDestroy()
    }
}

private data class HomeShelf(
    val id: String,
    val title: String,
    val songs: List<Song>,
    val platform: MusicPlatform? = null,
)

private data class BrowseSnapshot(
    val title: String, val kind: String, val item: JSONObject, val pluginId: String,
    val songs: List<Song>, val page: Int, val more: Boolean, val status: String,
    val albums: List<JSONObject>, val info: JSONObject?,
)

private data class BrowseReturn(val page: String, val playerOpen: Boolean, val browse: BrowseSnapshot?, val focusKey: String = "")

private fun clock(ms: Long) = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)

internal fun matchesSearchHistory(value: String, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty() || value.lowercase().contains(q)) return true
    if (android.os.Build.VERSION.SDK_INT < 29) return false
    return runCatching {
        val transliterator = android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower")
        val parts = value.map { transliterator.transliterate(it.toString()).trim() }
        parts.joinToString("").contains(q) || parts.mapNotNull { it.firstOrNull() }.joinToString("").startsWith(q)
    }.getOrDefault(false)
}

@Composable private fun Input(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, secret: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text, manualKeyboard: Boolean = false, onSubmit: (() -> Unit)? = null, downTarget: FocusAnchor? = null) {
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(!manualKeyboard) }
    var textFieldValue by remember { mutableStateOf(TextFieldValue(value, selection = TextRange(value.length))) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val c = LocalPrismColors.current
    LaunchedEffect(value) {
        if (textFieldValue.text != value) textFieldValue = TextFieldValue(value, selection = TextRange(value.length))
    }
    LaunchedEffect(editing) { if (manualKeyboard && editing) { withFrameNanos { }; keyboardController?.show() } }
    /* A read-only field can still be handed the IME the moment it gains focus, which is exactly
       when the remote is travelling through with direction keys. Keep it closed until OK asks. */
    LaunchedEffect(focused, editing) {
        if (manualKeyboard && focused && !editing) { withFrameNanos { }; keyboardController?.hide() }
    }
    BasicTextField(value = textFieldValue, onValueChange = {
        val previousText = textFieldValue.text
        textFieldValue = it
        if (it.text != previousText) onChange(it.text)
    }, singleLine = true, readOnly = manualKeyboard && !editing,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            keyboardController?.hide()
            if (manualKeyboard) editing = false
            /* Where the next control lives depends on the page: an inline button sits to the
               right (导入/搜索), a stacked form continues below (保存/扫码输入). */
            if (onSubmit != null) onSubmit() else if (!focusManager.moveFocus(FocusDirection.Right)) focusManager.moveFocus(FocusDirection.Down)
        }),
        textStyle = TextStyle(color = c.foreground, fontSize = 16.sp),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.onPreviewKeyEvent { event ->
            val down = event.type == KeyEventType.KeyDown
            if (editing && imeVisible) false
            else if (down && manualKeyboard && event.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                textFieldValue = textFieldValue.copy(selection = TextRange(textFieldValue.text.length), composition = null)
                editing = true
                keyboardController?.show()
                true
            }
            // 键盘收起后，方向键交回页面导航；键盘显示期间留给文字编辑。
            else if (down && event.key == Key.DirectionRight) {
                if (manualKeyboard && editing) { editing = false; keyboardController?.hide() }
                focusManager.moveFocus(FocusDirection.Right); true
            }
            else if (down && event.key == Key.DirectionLeft) {
                if (manualKeyboard && editing) { editing = false; keyboardController?.hide() }
                focusManager.moveFocus(FocusDirection.Left); true
            }
            /* BasicTextField eats Down to move its caret, which would trap the remote in the
               field while suggestions sit right below it. A single-line field has no caret line
               to move to, so Down always leaves — closing the IME first if it is still up. */
            else if (down && event.key == Key.DirectionDown) {
                if (manualKeyboard && editing) { editing = false; keyboardController?.hide() }
                /* 页面点名了落点就照点名走，几何搜索只认正下方最近的控件。 */
                if (!downTarget.requestIfAttached()) focusManager.moveFocus(FocusDirection.Down)
                true
            }
            /* Up is eaten the same way (caret line movement), which would make the nav bar
               unreachable from the field. Single line, so there is nowhere to move to. */
            else if (down && event.key == Key.DirectionUp) {
                if (manualKeyboard && editing) { editing = false; keyboardController?.hide() }
                focusManager.moveFocus(FocusDirection.Up); true
            }
            else false
        }.onFocusChanged { focused = it.isFocused; if (!it.isFocused && manualKeyboard) editing = false }.border(1.dp, if (focused) c.accent else c.line, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(c.panel, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(12.dp),
        decorationBox = { inner ->
            val longHint = hint.length > 22
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (longHint) 4.dp else 0.dp)) {
                Box(Modifier.fillMaxWidth()) {
                    /* Masked fields show a fixed-length placeholder and the character count when
                       idle — a full dot grid of a long cookie reads as nothing; the real input
                       appears only while editing. */
                    val maskedIdle = secret && value.isNotEmpty() && !editing
                    if (maskedIdle) Text("••••••••  ${value.length} 字符", color = c.muted, fontSize = 15.sp, maxLines = 1)
                    else {
                        if (value.isEmpty()) Text(if (longHint) "请输入" else hint, color = c.muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                }
                if (longHint) Text(hint, color = c.muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        })
    }

private fun directorySize(dir: java.io.File): Long =
    dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

private fun clearDirectory(dir: java.io.File) {
    dir.listFiles()?.forEach { it.deleteRecursively() }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(java.util.Locale.US, bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.1f MB".format(java.util.Locale.US, bytes / 1048576.0)
    bytes >= 1L shl 10 -> "%d KB".format(java.util.Locale.US, bytes / 1024)
    else -> "$bytes B"
}
