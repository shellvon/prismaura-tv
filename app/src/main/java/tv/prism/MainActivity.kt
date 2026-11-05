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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clipToBounds
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

// Keep the UI's queue when Android recreates the activity; the audio itself lives in PlaybackService.
private object ActiveQueue {
    var songs by mutableStateOf<List<Song>>(emptyList())
    var index by mutableIntStateOf(-1)
    var radio by mutableStateOf(false)
    var radioRecent: List<String> = emptyList()
}

private data class SearchEntity(val pluginId: String, val sourceName: String, val data: JSONObject)
private data class CollectionMenuTarget(val title: String, val songs: List<Song>, val playlistId: String? = null)
private data class TrackMenuTarget(val song: Song, val list: String? = null, val originFocus: String = "")

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : ComponentActivity() {
    private lateinit var appUpdates: AppUpdates
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
    private var page by mutableStateOf("home")
    private var playerOpen by mutableStateOf(false)
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
    private var playMode by mutableStateOf("顺序播放")
    private var quality by mutableStateOf("standard")
    private var streamFormat by mutableStateOf("")
    /* The tier of the stream that is actually playing, blank until a source resolves one.
       The preference is what to ask for; this is what came back after the ladder ran. */
    private var playingQuality by mutableStateOf("")
    /* A tier the plugin answered with no URL is one it does not serve; offering it in the
       player pill again would promise a stream that cannot play. Keyed by song. */
    private val unsupportedTiers = mutableStateMapOf<String, Set<String>>()
    private val tierCache = mutableMapOf<String, List<String>>()
    private var availableTiers by mutableStateOf<List<String>>(emptyList())
    private var playing by mutableStateOf(false)
    private var playbackState by mutableIntStateOf(Player.STATE_IDLE)
    private var busy by mutableStateOf(false)
    private var resolving: Job? = null
    /* The song the player actually holds. Re-picking it — from an album page, a shelf or the
       queue — has to keep the audio running instead of starting the track over. */
    private var loadedKey = ""
    /* Songs that failed to resolve since the last successful start. Automatic skipping only
       moves to an untried track, so a queue can never loop back onto the same failures. */
    private val failedSongs = mutableSetOf<String>()
    private var autoSkipToken = 0
    private var pendingSeekMs: Long? = null
    private var lyricJob: Job? = null
    private var lyrics by mutableStateOf<List<LyricLine>>(emptyList())
    private var lyricStatus by mutableStateOf("暂无歌词")
    private var lyricToken = 0
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
    private var phoneInput by mutableStateOf<PhoneInput?>(null)
    /* The QR dialog is only the way in. Hiding it must not stop the server: the whole point
       of the remote is to use the phone while the TV stays unobstructed. */
    private var phoneDialog by mutableStateOf(false)
    private var phoneRemoteMode by mutableStateOf(false)
    private val phoneHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var phoneTimeout: Runnable? = null
    private val pluginReplacements = mutableStateListOf<PluginReplacement>()
    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
        /* A failed resolve pauses the old track and leaves its title in the status line. When
           the user resumes that track, the failure belongs to a song that is no longer on
           screen; replace it with what is actually playing. */
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) return
            failedSongs.clear()
            if (ActiveQueue.radio) {
                queue.getOrNull(queueIndex)?.let { song ->
                    val key = Discovery.radioIdentity(song)
                    ActiveQueue.radioRecent = (ActiveQueue.radioRecent.filterNot { it == key } + key).takeLast(80)
                }
                ensureRadioTail()
            }
            if (status.startsWith("解析失败") || status.startsWith("播放失败")) {
                status = queue.getOrNull(queueIndex)?.let { "正在播放：${it.title}" }.orEmpty()
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            /* The stream died; re-picking this song must go through the resolver again
               instead of taking the "already loaded" shortcut. */
            loadedKey = ""
            status = "播放失败：${error.message ?: "未知错误"}"
            if (ActiveQueue.radio) queue.getOrNull(queueIndex)?.let {
                advanceAfterFailure(it, "播放失败", status, controller?.playWhenReady == true) { }
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
            if (playbackState == Player.STATE_ENDED && queue.isNotEmpty() && resolving?.isActive != true) {
                when {
                    ActiveQueue.radio -> step(1)
                    playMode == "单曲循环" -> playAt(queueIndex, openPlayer = false)
                    playMode == "顺序播放" && queueIndex == queue.lastIndex -> status = "播放完毕"
                    else -> {
                        step(1)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        SourcePlatforms.initialize(this)
        appUpdates = (application as PrismApplication).appUpdates
        lifecycleScope.launch {
            delay(10_000)
            if (updateJob?.isActive != true) updateJob = lifecycleScope.launch { appUpdates.check() }
        }
        plugins = AppPlugins.get(this)
        library = MusicLibrary(this)
        resumeStore = ResumeStore(this)
        quality = getSharedPreferences("playback", MODE_PRIVATE).getString("quality", "standard") ?: "standard"
        theme = getSharedPreferences("appearance", MODE_PRIVATE).getString("theme", "gold") ?: "gold"
        showMiniLyrics = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("mini_lyrics", false)
        reduceMotion = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("reduce_motion", false)
        pluginWorkerLimit = PluginWorkers.saved(this)
        searchHistory = loadSearchHistory()
        searchLatency = runCatching {
            val saved = JSONObject(getSharedPreferences("search", MODE_PRIVATE).getString("latency", "{}") ?: "{}")
            saved.keys().asSequence().associateWith { saved.optLong(it) }
        }.getOrDefault(emptyMap())
        playMode = getSharedPreferences("playback", MODE_PRIVATE).getString("mode", "顺序播放") ?: "顺序播放"
        homeShelfHidden = getSharedPreferences("home", MODE_PRIVATE).getStringSet("hidden_shelves", null)?.toSet() ?: emptySet()
        recommendationPluginId = getSharedPreferences("home", MODE_PRIVATE).getString("recommendation_plugin", "") ?: ""
        chartPluginId = getSharedPreferences("home", MODE_PRIVATE).getString("chart_plugin", "") ?: ""
        AppPlugins.schedule(this)
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
        connect()
        queue.getOrNull(queueIndex)?.let { song ->
            plugins.state.value.plugins.firstOrNull { it.id == song.pluginId }?.let { loadLyrics(it, song) }
        }
        lifecycleScope.launch {
            var ticks = 0
            while (isActive) {
                val p = controller
                /* While a restored track is still being prepared, the controller reports 0 and
                   would overwrite the saved position with it. */
                if (p != null && resumePending < 0 && pendingSeekMs == null) {
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
        setContent { MaterialTheme { Screen() } }
    }

    private fun connect() {
        connection?.let(MediaController::releaseFuture)
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    this@MainActivity.controller = null
                    status = "播放器已断开，请选择重新连接"
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
            catch (e: Exception) { status = "播放器连接失败：${e.cause?.message ?: e.message}" }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun search(query: String, append: Boolean = false) {
        val requestedType = searchType
        val sources = plugins.state.value.plugins.filter { it.enabled }.filter { plugin ->
            val supported = plugin.metadata.optJSONArray("supportedSearchType")
            (searchSources.isEmpty() || plugin.id in searchSources) && (!append || plugin.id in searchNextPages) &&
                (supported == null || (0 until supported.length()).any { supported.optString(it) == requestedType })
        }.sortedBy { searchLatency[it.id] ?: 0L }
        if (sources.isEmpty() || query.isBlank()) { status = "请选择插件并输入搜索内容"; return }
        if (!append) {
            val term = query.trim()
            val updated = (listOf(term) + searchHistory[requestedType].orEmpty().filterNot { it.equals(term, ignoreCase = true) }).take(30)
            searchHistory = searchHistory + (requestedType to updated)
            saveSearchHistory()
        }
        searchJob?.cancel()
        if (!append) focusSearchResults = true
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
        catch (e: Exception) { if (generation == chartGeneration) chartStatus = "榜单加载失败，可刷新重试：${e.message ?: "未知错误"}" }
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

    @Composable private fun RecommendationShelf(plugin: Plugin, up: FocusRequester, entry: FocusAnchor, onMenu: (JSONObject, FocusRequester) -> Unit, onOpen: (JSONObject) -> Unit) {
        val c = LocalPrismColors.current
        var categoriesOpen by remember { mutableStateOf(false) }
        val categoryFocus = remember { FocusRequester() }
        val scope = rememberCoroutineScope()
        fun closeCategories() {
            categoriesOpen = false
            scope.launch { withFrameNanos { }; categoryFocus.requestFocus() }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrismShelfHeader("歌单推荐", null, if (recommendationLoading) "加载中" else "刷新", {
                if (!recommendationLoading) lifecycleScope.launch { loadRecommendations(plugin) }
            })
            val scenes = Discovery.scenes(recommendationTags)
            val sceneIds = scenes.map { it.second.optString("id") }.toSet()
            val choices = scenes + recommendationTags.filterNot { it.optString("id") in sceneIds }.map { it.optString("title") to it }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
                item { PrismAction(if (recommendationTag.optString("id").isEmpty()) "✓ 推荐" else "推荐", {
                    recommendationTag = JSONObject().put("id", "").put("title", "推荐")
                }, Modifier.focusAnchor(entry).focusProperties { this.up = up }) }
                items(choices.take(3)) { (label, tag) ->
                    PrismAction("${if (recommendationTag.optString("id") == tag.optString("id")) "✓ " else ""}$label", {
                        recommendationTag = tag
                    }, Modifier.focusProperties { this.up = up })
                }
                if (choices.size > 3) item {
                    Box {
                        val selected = choices.drop(3).firstOrNull { it.second.optString("id") == recommendationTag.optString("id") }
                        PrismAction(selected?.let { "${it.first} ▾" } ?: "全部分类 ▾", { categoriesOpen = true },
                            Modifier.focusRequester(categoryFocus).focusProperties { this.up = up })
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
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                    items(recommendations) { sheet ->
                        val focus = remember(plugin.id, sheet.optString("id")) { FocusRequester() }
                        PrismSheetCard(sheet.optString("title").ifBlank { "推荐歌单" },
                            sheet.optString("artwork").ifBlank { sheet.optString("coverImg") }, plugin.displayName,
                            Modifier.focusRequester(focus).focusProperties { this.up = up },
                            onLongPress = { onMenu(sheet, focus) }) { onOpen(sheet) }
                    }
                    if (recommendationMore) item {
                        PrismAction(if (recommendationLoading) "加载中" else "更多歌单", {
                            if (!recommendationLoading) lifecycleScope.launch { loadRecommendations(plugin, append = true) }
                        }, Modifier.focusProperties { this.up = up })
                    }
                }
                if (recommendationStatus.isNotBlank()) Text(recommendationStatus, color = c.muted, fontSize = 12.sp)
            }
        }
    }

    @Composable private fun SourcePicker(options: List<Plugin>, selected: String, modifier: Modifier = Modifier, onSelect: (Plugin) -> Unit) {
        val c = LocalPrismColors.current
        var open by remember { mutableStateOf(false) }
        val current = options.firstOrNull { it.id == selected } ?: options.firstOrNull()
        Box {
            PrismAction("${current?.displayName ?: "暂无支持此能力的音源"} ▾", { open = true }, modifier)
            if (open) Popup(alignment = Alignment.TopStart, offset = IntOffset(0, with(LocalDensity.current) { 52.dp.roundToPx() }),
                onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { withFrameNanos { }; runCatching { focus.requestFocus() } }
                Column(Modifier.width(260.dp).heightIn(max = 280.dp).background(c.panel)
                    .border(1.dp, c.line).verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (options.isEmpty()) PrismAction("暂无支持的音源 · 返回", { open = false }, Modifier.focusRequester(focus))
                    options.forEachIndexed { index, plugin ->
                        PlatformAction(plugin.platform, "${if (plugin.id == current?.id) "✓ " else ""}${plugin.displayName}",
                            modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier) {
                            onSelect(plugin); open = false
                        }
                    }
                }
            }
        }
    }

    private fun toggleFavorite(song: Song?) {
        if (song == null) return
        if (song.pluginId == "local" && !song.data.optBoolean("persisted")) status = "本地文件未获得长期授权，不能保存"
        else lifecycleScope.launch { try { library.toggleFavorite(song) } catch (e: Exception) { status = e.message ?: "保存失败" } }
    }

    private fun toggleSubject(pluginId: String, kind: String, item: JSONObject, sourceName: String) {
        lifecycleScope.launch {
            try {
                val keys = plugins.state.value.plugins.firstOrNull { it.id == pluginId }?.metadata?.let(::primaryKeys) ?: listOf("id")
                val key = SavedSubject(pluginId, kind, item, sourceName, keys).key
                val wasSaved = library.state.value.subjects.any { it.key == key }
                library.toggleSubject(SavedSubject(pluginId, kind, item, sourceName, keys))
                status = if (wasSaved) "已取消收藏" else "已收藏"
            } catch (e: Exception) { status = e.message ?: "保存失败" }
        }
    }

    private fun playAt(index: Int, openPlayer: Boolean = true, autoplay: Boolean = true, startAt: Long = 0L, autoSkip: Boolean = false) {
        val song = queue.getOrNull(index) ?: return
        val player = controller ?: run { status = "播放器未连接"; return }
        /* The loaded track needs no second pass: the audio is mid-phrase and its title is on
           screen, so re-picking it only moves the needle within the queue. */
        if (!autoSkip && startAt <= 0L && song.key == loadedKey && playbackState != Player.STATE_ENDED && playbackState != Player.STATE_IDLE) {
            resolving?.cancel()
            failedSongs.clear(); autoSkipToken++
            queueIndex = index
            if (openPlayer) playerOpen = true
            if (autoplay && !player.playWhenReady) player.play()
            status = if (player.playWhenReady) "正在播放：${song.title}" else "已暂停：${song.title}"
            if (autoplay) lifecycleScope.launch { runCatching { library.record(song) } }
            return
        }
        /* A manual pick starts a fresh pass; an automatic skip is part of the current one. */
        if (!autoSkip) { failedSongs.clear(); autoSkipToken++ }
        val previousIndex = queueIndex
        val previousSong = queue.getOrNull(previousIndex)
        /* The queue often changes before this call (search results, playlists), so the old
           position alone would point at a different song afterwards. Restore by identity. */
        val restorePrevious: () -> Unit = {
            val at = previousSong?.let { old -> queue.indexOfFirst { it.key == old.key } } ?: -1
            if (at >= 0) queueIndex = at
        }
        if (openPlayer) playerOpen = true
        streamFormat = ""
        playingQuality = ""
        resolving?.cancel()
        lyricJob?.cancel()
        val previousLyrics = lyrics
        val previousLyricStatus = lyricStatus
        lyrics = emptyList()
        lyricStatus = "歌词加载中…"
        queueIndex = index
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
                status = when {
                    !autoplay -> "上次听到 ${clock(startAt)}，按播放继续"
                    song.data.optBoolean("persisted") -> "正在播放：${song.title}"
                    else -> "正在播放；文件授权未能长期保存"
                }
                if (autoplay && song.data.optBoolean("persisted")) lifecycleScope.launch { runCatching { library.record(song) } }
            } catch (e: Exception) {
                pendingSeekMs = null
                /* The file may have prepared and then failed; the next track is the one on screen. */
                loadedKey = ""
                lyrics = previousLyrics
                lyricStatus = previousLyricStatus
                advanceAfterFailure(song, "本地文件播放失败", "本地文件播放失败：${e.message}", autoplay) { if (!autoSkip) restorePrevious() }
            }
            return
        }
        val plugin = plugins.state.value.plugins.firstOrNull { it.id == song.pluginId }
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
            status = "正在解析：${song.title}"
            try {
                val attempts = qualityAttempts(qualityOptions(plugin, song), quality)
                var resolved: JSONObject? = null
                var lastError: Exception? = null
                var resolvedTier = ""
                val unsupported = mutableSetOf<String>()
                for (tier in attempts) {
                    var failure: Exception? = null
                    val candidate = try {
                        plugins.mediaSource(plugin, song, tier)
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
                if (unsupported.isNotEmpty()) unsupportedTiers[song.key] = unsupportedTiers[song.key].orEmpty() + unsupported
                val uri = Uri.parse(result.optString("url"))
                require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) { "插件返回了无效播放地址" }
                val headers = Bundle()
                sourceHeaders(result).forEach { (key, value) -> headers.putString(key, value) }
                val artworkUri = Uri.parse(song.artwork).takeIf { it.scheme == "https" || it.scheme == "http" }
                val metadata = MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setArtworkUri(artworkUri)
                    .setExtras(Bundle().apply { putBundle("headers", headers) }).build()
                player.setMediaItem(MediaItem.Builder().setUri(uri).setMediaMetadata(metadata).build())
                player.prepare()
                loadedKey = song.key
                if (startAt > 0) { player.seekTo(startAt); pendingSeekMs = startAt; positionMs = startAt }
                if (autoplay) {
                    player.play(); failedSongs.clear(); status = if (attempts.firstOrNull() != resolvedTier || actualTier != resolvedTier) "正在播放：${song.title} · 已回退至 ${qualityText(actualTier)}" else "正在播放：${song.title}"
                    lifecycleScope.launch { runCatching { library.record(song) } }
                } else status = "上次听到 ${clock(startAt)}，按播放继续"
                loadLyrics(plugin, song)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                pendingSeekMs = null
                /* The old track is paused, not replaced. If no skip follows, put the needle back
                   where it was so the title on screen and the audio agree. */
                lyrics = previousLyrics
                lyricStatus = previousLyricStatus
                advanceAfterFailure(song, "解析失败", "解析失败：${song.title} · ${e.message ?: "插件没有返回播放地址"}", autoplay) { if (!autoSkip) restorePrevious() }
            }
        }
    }

    /* A source that cannot resolve one track used to leave the old song paused and the queue
       stuck there. Skip to the next track that has not failed yet in this pass; once every
       track has failed, stop instead of cycling through the same failures forever. */
    private fun advanceAfterFailure(song: Song, label: String, detail: String, autoplay: Boolean = true, restore: () -> Unit) {
        if (!autoplay) { status = detail; restore(); return }
        failedSongs += song.key
        val next = (if (ActiveQueue.radio) (queueIndex + 1 until queue.size).toList() else
            (1 until queue.size).map { offset -> (queueIndex + offset) % queue.size })
            .firstOrNull { index -> queue[index].key !in failedSongs }
        if (next == null || ActiveQueue.radio && failedSongs.size >= 5) {
            if (ActiveQueue.radio) { controller?.pause(); radioJob?.cancel(); radioRetryAt = android.os.SystemClock.elapsedRealtime() + 30_000 }
            failedSongs.clear()
            status = detail
            restore()
            return
        }
        /* queueIndex stays on the failed song until the skip fires, so the pass continues
           from here instead of jumping back to an already-failed neighbour. */
        status = "$label，正在尝试下一首：${song.title}"
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
            /* Some sources simply have no lyric for a track, and a slow source can leave the
               loading line on screen long after the song has started. After twelve seconds say
               the truth instead of pretending to keep loading. */
            val fallback = launch {
                delay(12_000)
                if (token == lyricToken && lyrics.isEmpty()) lyricStatus = "暂无歌词"
            }
            try {
                val supplied = song.data.optString("rawLrc")
                val result = if (supplied.isNotBlank()) JSONObject().put("rawLrc", supplied)
                else if (!plugin.capabilities.has("lyric") && song.data.optString("lrc").isBlank()) null
                else plugins.client.call(plugin.source, plugin.variables, "getLyric", JSONArray().put(song.data), plugin.id) as? JSONObject
                if (token != lyricToken) return@launch
                val text = result?.optString("rawLrc").orEmpty()
                val parsed = Lyrics.parse(text, result?.optString("translation").orEmpty())
                lyrics = parsed
                lyricStatus = when {
                    parsed.isNotEmpty() -> ""
                    text.isNotBlank() -> text.take(3000)
                    else -> "暂无歌词"
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { if (token == lyricToken) { lyrics = emptyList(); lyricStatus = "暂无歌词" } }
            finally { fallback.cancel() }
        }
    }

    /* 导入只用 MusicFree 的标准方法 importMusicSheet，宿主不为某个平台写特例：链接认得出平台
       就先交给对应音源，认不出来就依次问每一个声明了导入能力的音源，直到有一个交出歌曲。 */
    private fun importCandidates(url: String): List<Plugin> {
        val capable = plugins.state.value.plugins.filter { it.enabled && (it.metadata.optBoolean("importSheet") || it.metadata.optBoolean("importItem")) }
        val target = MusicPlatform.identify(url)
        return capable.sortedBy { if (it.platform == target) 0 else 1 }
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

    private fun importPlaylist(name: String, input: String) {
        val url = shareUrlOf(input)
        if (url.isBlank()) { status = "请填写歌单或单曲链接"; return }
        val candidates = importCandidates(url)
        if (candidates.isEmpty()) {
            status = "没有音源支持链接导入：请先启用支持歌单或单曲导入的音源"
            return
        }
        lifecycleScope.launch {
            busy = true
            val reasons = mutableListOf<String>()
            try {
                for (plugin in candidates) {
                    status = "正在用 ${plugin.displayName} 导入…"
                    var sheet: JSONArray? = null
                    var failure = ""
                    val methods = buildList {
                        if (plugin.metadata.optBoolean("importSheet")) add("importMusicSheet")
                        if (plugin.metadata.optBoolean("importItem")) add("importMusicItem")
                    }
                    for (method in methods) {
                        try {
                            val imported = plugins.client.call(plugin.source, plugin.variables, method, JSONArray().put(url), plugin.id)
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
                status = "链接导入失败：" + (reasons.firstOrNull() ?: "音源没有返回歌曲") +
                    if (reasons.size > 1) "（已依次尝试 ${reasons.size} 个音源）" else ""
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { status = "链接导入失败：${e.message}" }
            finally { busy = false }
        }
    }

    /* Written every 15 seconds and on the way out: a TV is usually stopped, not closed, so
       onStop is the one signal that reliably precedes the process going away. */
    private fun saveResume() {
        if (queue.isEmpty()) return
        val position = controller?.currentPosition?.coerceAtLeast(0L) ?: positionMs
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
                status = "极光电台 正在续曲…"
                ensureRadioTail(force = true)?.join()
                if (ActiveQueue.radio && queue.getOrNull(queueIndex)?.key == current) {
                    if (queueIndex < queue.lastIndex) playAt(queueIndex + 1, openPlayer = false)
                    else status = "电台暂时没有更多曲目，请稍后重试或换一批"
                }
            }
            return
        }
        playAt(nextIndex(direction), openPlayer = false)
    }

    private fun nextIndex(direction: Int): Int =
        if (playMode == "随机播放" && queue.size > 1 && direction > 0) {
            val n = kotlin.random.Random.nextInt(queue.size - 1); if (n >= queueIndex) n + 1 else n
        } else (queueIndex + direction + queue.size) % queue.size

    private fun toggleHomeShelf(id: String) {
        homeShelfHidden = if (id in homeShelfHidden) homeShelfHidden - id else homeShelfHidden + id
        getSharedPreferences("home", MODE_PRIVATE).edit()
            .putStringSet("hidden_shelves", homeShelfHidden.toHashSet()).apply()
    }

    /* After a backup restore the phone session must survive, so instead of recreate()
       the preference-backed state is read again in place. */
    private fun reloadBackedState() {
        quality = getSharedPreferences("playback", MODE_PRIVATE).getString("quality", "standard") ?: "standard"
        playMode = getSharedPreferences("playback", MODE_PRIVATE).getString("mode", "顺序播放") ?: "顺序播放"
        theme = getSharedPreferences("appearance", MODE_PRIVATE).getString("theme", "gold") ?: "gold"
        showMiniLyrics = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("mini_lyrics", false)
        reduceMotion = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("reduce_motion", false)
        pluginWorkerLimit = PluginWorkers.saved(this)
        homeShelfHidden = getSharedPreferences("home", MODE_PRIVATE).getStringSet("hidden_shelves", null)?.toSet() ?: emptySet()
        recommendationPluginId = getSharedPreferences("home", MODE_PRIVATE).getString("recommendation_plugin", "") ?: ""
        chartPluginId = getSharedPreferences("home", MODE_PRIVATE).getString("chart_plugin", "") ?: ""
        searchHistory = loadSearchHistory()
    }

    /* History is kept per search type so 歌手 searches don't suggest 歌曲 terms.
       One JSON object under the old "history" key; a plain array there is the
       pre-type history, which belongs to 歌曲. */
    private fun loadSearchHistory(): Map<String, List<String>> = runCatching {
        val stored = getSharedPreferences("search", MODE_PRIVATE).getString("history", "[]")
        val root = JSONObject(stored)
        root.keys().asSequence().associateWith { key ->
            root.optJSONArray(key)?.let { a ->
                (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.take(30)
            } ?: emptyList()
        }
    }.getOrDefault(
        runCatching {
            val legacy = JSONArray(getSharedPreferences("search", MODE_PRIVATE).getString("history", "[]"))
            mapOf("music" to (0 until legacy.length()).map { legacy.optString(it) }.filter { it.isNotBlank() }.take(30))
        }.getOrDefault(emptyMap())
    )

    private fun saveSearchHistory() {
        val root = JSONObject()
        searchHistory.forEach { (type, terms) -> root.put(type, JSONArray(terms)) }
        getSharedPreferences("search", MODE_PRIVATE).edit().putString("history", root.toString()).apply()
    }

    private fun closePhoneInput() {
        phoneTimeout?.let { phoneHandler.removeCallbacks(it) }
        phoneTimeout = null
        phoneDialog = false
        phoneInput?.close(); phoneInput = null
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
        closePhoneInput()
        try {
            phoneInput = PhoneInput(this, fields) { values ->
                closePhoneInput()
                onSubmit(values)
            }
            phoneRemoteMode = false
            phoneDialog = true
            phoneTimeout = Runnable { closePhoneInput(); status = "扫码输入已过期，请重新打开" }.also { phoneHandler.postDelayed(it, 300_000) }
        } catch (e: Exception) { status = "无法启动手机输入：${e.message}" }
    }

    /* The remote panel is meant to stay open while someone browses the TV. It lives half an
       hour; hiding the QR only hides the dialog. */
    private fun openPhoneRemote(snapshot: () -> String, command: (String) -> String) {
        closePhoneInput()
        try {
            phoneInput = PhoneInput(this, snapshot = snapshot, command = command)
            phoneRemoteMode = true
            phoneDialog = true
            phoneTimeout = Runnable { closePhoneInput(); status = "手机遥控已过期，请重新打开" }.also { phoneHandler.postDelayed(it, 1_800_000) }
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

    @SuppressLint("RestrictedApi") // Forward unhandled keys through ComponentActivity's dispatcher.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> { step(1); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { step(-1); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { controller?.let { if (it.playWhenReady) it.pause() else it.play() }; return true }
                KeyEvent.KEYCODE_SEARCH -> { page = "search"; return true }
                /* Remotes have a MENU key that long-press does not cover. The queue is the
                   thing with no other one-press door on the home page. */
                KeyEvent.KEYCODE_MENU -> {
                    super.dispatchKeyEvent(event)
                    return true
                }
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
            if (super.dispatchKeyEvent(event)) return true
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
        return super.dispatchKeyEvent(event)
    }

    @Composable private fun Screen() {
        val state by plugins.state.collectAsState()
        val updateState by appUpdates.state.collectAsState()
        val libraryState by library.state.collectAsState()
        /* Disabled sources stay installed but disappear from pickers, search and recommendations. */
        val activePlugins = state.plugins.filter { it.enabled }
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
        var subjectMenu by remember { mutableStateOf<SavedSubject?>(null) }
        var collectionMenu by remember { mutableStateOf<CollectionMenuTarget?>(null) }
        var menuReturnFocus by remember { mutableStateOf<FocusRequester?>(null) }
        var menuWasOpen by remember { mutableStateOf(false) }
        var menuPage by remember { mutableStateOf("") }
        var menuBrowse by remember { mutableStateOf<JSONObject?>(null) }
        var menuPlayer by remember { mutableStateOf(false) }
        var activeMv by remember { mutableStateOf<VideoSource?>(null) }
        var resumeAfterMv by remember { mutableStateOf(false) }
        /* 管理模式 covers one list at a time: song keys, saved subject keys, or local playlist ids. */
        var libraryManage by remember { mutableStateOf(false) }
        var librarySelected by remember { mutableStateOf(setOf<String>()) }
        /* 管理 owns selecting; the confirmation dialog owns the irreversible part. */
        var confirmBatchOpen by remember { mutableStateOf(false) }
        var settingsSection by remember { mutableStateOf("外观") }
        var cacheSize by remember { mutableLongStateOf(-1L) }
        var clearingCache by remember { mutableStateOf(false) }
        val versionName = remember { runCatching { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }.getOrDefault("") }
        val deviceInfo = remember {
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}"
        }
        val webViewInfo = remember {
            runCatching {
                val provider = if (android.os.Build.VERSION.SDK_INT >= 26) android.webkit.WebView.getCurrentWebViewPackage()
                else listOf("com.google.android.webview", "com.android.webview").firstNotNullOfOrNull { name ->
                    runCatching { packageManager.getPackageInfo(name, 0) }.getOrNull()
                }
                provider?.let { "${it.packageName} ${it.versionName.orEmpty()}" } ?: "未检测到 WebView"
            }.getOrDefault("未检测到 WebView")
        }
        LaunchedEffect(settingsSection) {
            if (settingsSection == "关于") cacheSize = withContext(Dispatchers.IO) { directorySize(cacheDir) }
        }
        /* Tabs switch lists, so a selection carried across would arm the next tab's verb
           against items the remote never chose. */
        LaunchedEffect(selectedList) { libraryManage = false; librarySelected = emptySet(); confirmBatchOpen = false }
        var searchFiltersOpen by remember { mutableStateOf(false) }
        var shelvesMenuOpen by remember { mutableStateOf(false) }
        var statusVisible by remember { mutableStateOf(false) }
        val statusNotice = status.takeUnless {
            it.startsWith("正在播放") || it.startsWith("正在解析") || it.startsWith("已暂停") || it.startsWith("上次听到") || it == "播放完毕"
        }.orEmpty()
        LaunchedEffect(statusNotice) { statusVisible = statusNotice.isNotBlank(); delay(4500); statusVisible = false }
        fun openMv(song: Song?) {
            if (song == null) return
            lifecycleScope.launch {
                status = "正在解析 MV…"
                try {
                    val source = plugins.resolveVideo(song) ?: error("该音源没有返回可播放的 MV 地址")
                    if (queue.getOrNull(queueIndex)?.key != song.key) return@launch
                    resumeAfterMv = controller?.playWhenReady == true
                    controller?.pause(); activeMv = source; status = ""
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { status = "MV 不可用：${e.message ?: "解析失败"}" }
            }
        }

        fun openPhoneHub() {
            /* Reopening the QR after hiding it reuses the live session, so the phone page that
               is already open keeps the same address. A leftover config form is not a remote. */
            if (phoneInput != null && phoneRemoteMode) { phoneDialog = true; return }
            openPhoneRemote(
                snapshot = {
                    val song = queue.getOrNull(queueIndex)
                    JSONObject()
                        .put("title", song?.title.orEmpty())
                        .put("artist", song?.artist.orEmpty())
                        .put("source", song?.sourceLabel.orEmpty())
                        .put("playing", playing && playbackState != Player.STATE_ENDED)
                        .put("position", positionMs)
                        .put("duration", durationMs)
                        .put("quality", quality)
                        .put("qualityLabel", qualityText(playingQuality.ifBlank { quality }))
                        .put("mode", if (ActiveQueue.radio) "极光电台" else playMode)
                        .put("miniLyrics", showMiniLyrics)
                        .put("theme", theme)
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
                            val plugin = song?.let { current -> plugins.state.value.plugins.firstOrNull { it.id == current.pluginId } }
                            if (song != null && plugin != null) qualityOptions(plugin, song)
                                .filterNot { it in unsupportedTiers[song.key].orEmpty() }
                                .forEach { tier -> array.put(JSONObject().put("id", tier).put("label", qualityText(tier))) }
                        })
                        .put("status", status)
                        .put("page", page)
                        .put("playerOpen", playerOpen)
                        .put("importBusy", busy)
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
                        val value = request.optString("value").trim()
                        var result: String? = null
                        when (val type = request.optString("type")) {
                            "playPause" -> {
                                val p = controller ?: error("播放器未连接")
                                when {
                                    p.playWhenReady -> p.pause()
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
                                controller?.seekTo(at); positionMs = at
                            }
                            "queuePlay" -> {
                                val index = value.toIntOrNull() ?: error("歌曲序号无效")
                                require(index in queue.indices) { "这首歌已经不在播放列表里" }
                                playAt(index, openPlayer = false)
                            }
                            "theme" -> {
                                require(value in setOf("gold", "porcelain", "mono")) { "未知主题" }
                                theme = value
                                getSharedPreferences("appearance", MODE_PRIVATE).edit().putString("theme", value).apply()
                            }
                            "quality" -> {
                                /* Extended tiers are real for some plugins; rejecting them here
                                   would make the phone's own quality sheet offer dead options. */
                                require(value in setOf("low", "standard", "high", "super", "flac", "flac24bit", "hires", "dolby", "atmos", "atmos_plus", "64k", "128k", "192k", "320k", "mgg")) { "未知音质" }
                                if (quality != value) {
                                    quality = value
                                    getSharedPreferences("playback", MODE_PRIVATE).edit().putString("quality", value).apply()
                                    /* On the TV the pill re-resolves the current track when the
                                       tier changes; the phone has to do the same or the sheet
                                       would say 高品 while the speakers stayed on 标准. */
                                    if (queue.isNotEmpty() && queue.getOrNull(queueIndex)?.pluginId != "local") {
                                        playAt(queueIndex, openPlayer = playerOpen, autoplay = controller?.playWhenReady == true, startAt = positionMs)
                                    }
                                }
                            }
                            "mode" -> {
                                require(value in setOf("顺序播放", "列表循环", "单曲循环", "随机播放")) { "未知播放模式" }
                                leaveRadio()
                                playMode = value
                                getSharedPreferences("playback", MODE_PRIVATE).edit().putString("mode", value).apply()
                            }
                            "miniLyrics" -> {
                                showMiniLyrics = !showMiniLyrics
                                getSharedPreferences("appearance", MODE_PRIVATE).edit().putBoolean("mini_lyrics", showMiniLyrics).apply()
                            }
                            "search" -> {
                                require(value.isNotEmpty()) { "请输入搜索内容" }
                                keyword = value
                                playerOpen = false
                                page = "search"
                                search(value)
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
                                require(source.toByteArray(Charsets.UTF_8).size <= Plugins.MAX_SCRIPT) { "插件文件超过 384 KiB" }
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
                                /* Restore rewrites the library and reinstalls sources; the
                                   phone waits for the outcome, so it cannot be fire-and-forget. */
                                runBlocking { LibraryBackup.apply(this@MainActivity, library, plugins, JSONObject(value)) }
                                reloadBackedState()
                                status = "备份已恢复"
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
        val heroFocus = remember { FocusAnchor() }
        val radioCardFocus = remember { FocusAnchor() }
        val discoverFocus = remember { FocusRequester() }
        val libraryFocus = remember { FocusRequester() }
        val searchFocus = remember { FocusRequester() }
        val searchInputFocus = remember { FocusAnchor() }
        /* The 类型/音源 strip sits between the field and the list, and the field's Down is
           named at the list, so the strip had no door of its own: these anchors make it a
           stop on the way, and opening the source row hands the remote to its chips. */
        val searchFilterFocus = remember { FocusAnchor() }
        val searchSourceFocus = remember { FocusAnchor() }
        val searchChipFocus = remember { FocusAnchor() }
        /* Coming back from a played track should land on the result you left, not on the
           text field, which sits one key above the list and invites another round of typing. */
        val searchResultsFocus = remember { FocusAnchor() }
        /* One anchor per collection type. Playlist detail owns a separate Back action. */
        val libraryTabs = remember { listOf("favorites", "albums", "artists", "playlists", "recent").associateWith { FocusAnchor() } }
        /* Opening a playlist's table happens one step inside 歌单, so the page still enters
           through that pill rather than losing the anchor to an unknown key. */
        val libraryTabKey = if (selectedList.startsWith("playlist:")) "playlists" else selectedList
        val libraryContentFocus = remember { FocusAnchor() }
        /* The 全部播放 action sits above the table; a row has no geometric neighbour there. */
        val libraryHeaderFocus = remember { FocusAnchor() }
        /* The detail keeps its own focus boundary until Back returns to the originating card. */
        val playlistBackFocus = remember { FocusAnchor() }
        val libraryPlaylistListState = remember { LazyListState() }
        val discoverEntryFocus = remember { FocusAnchor() }
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
        /* The settings content scrolls, which makes it a separate focus space: a directional
           search from the rail never reaches it, so the rail<->content edges are declared. */
        /* Rail order in one place: Down from the banner enters the rail at its top, so the list
           the remote walks and the map of anchors cannot drift apart. */
        val settingsSections = remember { listOf("外观", "推荐", "音源", "播放", "关于") }
        val sectionFocus = remember { mapOf("外观" to FocusAnchor(), "推荐" to FocusAnchor(), "音源" to FocusAnchor(), "播放" to FocusAnchor(), "关于" to FocusAnchor()) }
        /* The phone-remote banner sits between the bar and the rail. Down from 设置 lands on
           its QR button, so the page's first stop is the thing that needs a keyboard. */
        val bannerFocus = remember { FocusAnchor() }
        val sourceActionFocus = remember { FocusAnchor() }
        val sectionEntryFocus = remember { mapOf("外观" to FocusAnchor(), "推荐" to FocusAnchor(), "音源" to sourceActionFocus, "播放" to FocusAnchor(), "关于" to FocusAnchor()) }
        val configEntryFocus = remember { FocusAnchor() }
        val miniFocus = remember { FocusRequester() }
        /* The bar and the page bodies live in different scroll/focus spaces, so a directional
           search from the bar sometimes finds nothing. Name the landing spot per page instead. */
        fun entryFocus(target: String): FocusAnchor? = when (target) {
            "home" -> heroFocus
            "search" -> searchInputFocus
            "library" -> if (selectedPlaylist != null) playlistBackFocus else libraryTabs[libraryTabKey]
            "plugins" -> if (configured == null) bannerFocus else configEntryFocus
            "discover" -> when {
                browseItem != null -> detailHeaderFocus
                chartSelection != null -> discoverEntryFocus.takeIf { chartSongs.isNotEmpty() }
                else -> discoverEntryFocus
            }
            else -> null
        }
        val pageEntryFocus = entryFocus(page)
        val contentMenuOpen = trackMenu != null || subjectMenu != null || collectionMenu != null
        LaunchedEffect(contentMenuOpen) {
            if (contentMenuOpen) {
                menuWasOpen = true; menuPage = page; menuBrowse = browseItem; menuPlayer = playerOpen
                if (menuReturnFocus == null) menuReturnFocus = trackMenu?.originFocus?.let { detailCardFocus[it] }
            } else if (menuWasOpen) {
                menuWasOpen = false
                val target = menuReturnFocus; menuReturnFocus = null
                if (page != menuPage || browseItem !== menuBrowse || playerOpen != menuPlayer) return@LaunchedEffect
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
        /* Settings rows remember themselves here; the mini player's Up returns to the last
           one instead of the page entry (the banner). */
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
        /* Quality is resolved one track at a time and only on detail pages: on a list page this
           would mean a network round-trip per row before anything useful is on screen. The empty
           string records "asked, nothing to show" so a failed lookup is not retried on every focus. */
        val qualityTags = remember { mutableStateMapOf<String, String>() }
        val qualityPending = remember { mutableSetOf<String>() }
        fun resolveQuality(song: Song) {
            if (qualityTags.containsKey(song.key) || !qualityPending.add(song.key)) return
            val plugin = state.plugins.firstOrNull { it.id == song.pluginId }
            if (plugin == null) { qualityPending.remove(song.key); return }
            lifecycleScope.launch {
                val qualities = song.data.optJSONObject("qualities")
                val tag = when {
                    qualities?.has("hires") == true -> "Hi-Res"
                    qualities?.has("flac24bit") == true -> "24-bit"
                    qualities?.has("flac") == true -> "无损"
                    else -> null
                }
                qualityTags[song.key] = tag.orEmpty()
                qualityPending.remove(song.key)
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
        fun openSubject(song: Song, kind: String, originFocus: String = "") {
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
                openBrowse(plugin, subject, kind, originFocus)
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
            subjectJob?.cancel()
            subjectJob = lifecycleScope.launch {
                try {
                    val response = plugins.client.call(plugin.source, plugin.variables, "search",
                        JSONArray().put(name).put(1).put(kind), plugin.id) as? JSONObject ?: error("音源未返回搜索结果")
                    val data = response.optJSONArray("data") ?: JSONArray()
                    val candidates = (0 until minOf(data.length(), 50)).mapNotNull { data.optJSONObject(it) }
                    val wanted = normalizedName(name)
                    val best = candidates.firstOrNull {
                        val candidate = normalizedName(subjectTitle(it))
                        candidate == wanted || (candidate.isNotBlank() && (candidate.contains(wanted) || wanted.contains(candidate)))
                    } ?: candidates.firstOrNull()
                    if (best == null) {
                        status = "没有找到「$name」的$label，已改为搜索"
                        playerOpen = false
                        searchSubject(name, kind, song)
                    } else {
                        status = ""
                        openBrowse(plugin, best, kind, originFocus)
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
        LaunchedEffect(playerOpen, queueIndex, queue.getOrNull(queueIndex)?.key) {
            val target = queue.getOrNull(queueIndex)
            availableTiers = emptyList()
            if (!playerOpen || target == null) return@LaunchedEffect
            val plugin = state.plugins.firstOrNull { it.id == target.pluginId } ?: return@LaunchedEffect
            availableTiers = qualityOptions(plugin, target)
        }
        LaunchedEffect(playerOpen) {
            if (playerOpen) playerWasOpen = true
            else if (playerWasOpen) {
                withFrameNanos { }
                if (!playerOpen && !suppressMiniReturn) miniFocus.requestFocus()
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
                    else libraryHeaderFocus.requestIfAttached()
                }
                importWasOpen = false
            }
        }
        LaunchedEffect(page) {
            if (page != "search") focusSearchResults = false
            /* The bar already owns focus while previewing; moving it now would stop the user
               from continuing left/right along the tabs. */
            if (navBarFocused) return@LaunchedEffect
            /* Back from a detail restores the card that opened it; that effect owns focus. */
            if (pendingReturnFocus.isNotBlank()) return@LaunchedEffect
            withFrameNanos { }
            when (page) {
                "home" -> heroFocus.requestIfAttached()
                "search" -> searchInputFocus.requestIfAttached()
                /* A detail is opened from a card on another page: the effect below waits for its
                   songs and lands on the first one. Taking the bar's tab here would park the
                   remote on the top bar until the user pressed Down again. */
                "discover" -> if (browseItem == null && chartSelection == null) discoverEntryFocus.requestIfAttached()
                /* Opening 我的 from 设置 should land on the list tab that is lit, not on the
                   bar's copy of it — otherwise the highlight and the remote disagree. */
                "library" -> if (selectedPlaylist != null) playlistBackFocus.requestIfAttached() else libraryTabs[libraryTabKey].requestIfAttached()
                "plugins" -> if (configured == null) bannerFocus.requestIfAttached() else configEntryFocus.requestIfAttached()
            }
        }
        /* 发现 swaps between the chart grid and two lists. Swapping recomposes away the node that
           held focus, so hand focus to whatever the new shape shows first — or back to the bar
           when the list came back empty, which would otherwise strand focus nowhere. */
        LaunchedEffect(page, browseItem, chartSelection) {
            if (navBarFocused) return@LaunchedEffect
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
                /* A subject card in 歌手/专辑 uses "<kind>:<plugin>:<id>" as its key. The list is
                   sectioned, so scroll to the header+row position before requesting focus. */
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
                            } else runCatching { librarySubjectListState.scrollToItem(1 + at / 6) }
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
                    "discover" -> if (browseItem == null && chartSelection == null) discoverEntryFocus.requestIfAttached()
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
            val target = if (browseItem != null) detailHeaderFocus else discoverEntryFocus
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
                    queue = emptyList(); queueIndex = -1; playing = false; lyrics = emptyList(); loadedKey = ""
                    stopService(Intent(this@MainActivity, PlaybackService::class.java)); finish()
                }
            }
        }
        /* Sync rewrites every Plugin object, so keying effects on the list glued the rails to disk
           writes. What actually changes the view is the set of sources, their version and config. */
        val pluginSignature = state.plugins.joinToString("|") {
            "${it.id}:${it.enabled}:${it.source.hashCode()}:${it.metadata}:${it.variables}"
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
        LaunchedEffect(chartPluginId, pluginSignature) {
            chartGeneration++
            chartJob?.cancel(); chartSelection = null; chartSongs = emptyList()
            charts = emptyList(); chartPreviews = emptyMap()
            val plugin = chartSource
            if (plugin == null) chartStatus = "暂无提供榜单的音源，可在设置中添加"
            else loadCharts(plugin, force = false)
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
        CompositionLocalProvider(LocalPrismColors provides prismColors(theme), LocalAuraMotion provides motionEnabled) {
            val c = LocalPrismColors.current
            Box(Modifier.fillMaxSize()) {
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
                                if (navBarFocused) navTabs.firstOrNull { it.first == page }?.second?.requestFocus()
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val step = when (event.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> return@onPreviewKeyEvent false }
                            val index = navTabs.indexOfFirst { it.first == page }
                            val next = navTabs.getOrNull(index + step) ?: return@onPreviewKeyEvent index >= 0
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
                        PrismNav("设置", page == "plugins", { enterPage("plugins") }, navModifier(pluginsFocus))
                        Spacer(Modifier.weight(1f))
                        AuraSourcePill(activePlugins.size)
                        Spacer(Modifier.width(24.dp))
                        AuraClock()
                    }
                    Spacer(Modifier.height(18.dp))
                    if (state.error != null || libraryState.error != null) Text(state.error ?: libraryState.error.orEmpty(), fontSize = 12.sp, color = c.accent, modifier = Modifier.padding(bottom = 8.dp), maxLines = 2)
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                        Box(Modifier.fillMaxSize()) {
                        when (page) {
                            "home" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                if (updateState.release != null && !updateState.skipped) {
                                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("新版本 ${updateState.release?.tag}", color = c.accent, fontSize = 13.sp)
                                        PrismAction("查看更新", { updateDialogOpen = true })
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
                                    modifier = Modifier.weight(1f).padding(start = 10.dp).focusProperties { up = homeFocus },
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
                                        Modifier.weight(1f).focusAnchor(radioCardFocus).focusProperties { up = homeFocus }, onLongPress = { radioMenuOpen = true })
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
                                        /* Top of the scrolling page: without a named Up target the
                                           remote had no way back to the bar from here. */
                                        modifier = Modifier.weight(1f).focusProperties { up = homeFocus },
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
                                homeShelves.filter { it.songs.isNotEmpty() }.forEach { shelf ->
                                    val shelfActionFocus = remember(shelf.id) { FocusRequester() }
                                    PrismShelfHeader(
                                        shelf.title, shelf.songs.size,
                                        if (shelf.songs.isEmpty()) "添加" else "全部播放",
                                        {
                                            if (shelf.songs.isEmpty()) { selectedList = shelf.id; page = "library" }
                                            else playQueue(shelf.songs)
                                        },
                                        actionFocus = shelfActionFocus,
                                    )
                                    if (shelf.songs.isEmpty()) {
                                        Text(
                                            if (shelf.id == "favorites") "播放时收藏歌曲，之后就能从这里直接播放"
                                            else if (shelf.id == "recent") "最近播放的歌曲会自动出现在这里"
                                            else "到「我的」添加歌曲，之后可从这里直接播放",
                                            color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp),
                                        )
                                    } else LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                                        itemsIndexed(shelf.songs.take(20)) { index, song ->
                                            val focus = detailFocus("home:${shelf.id}:${song.key}")
                                            PrismCoverCard(song, index, { playQueue(shelf.songs, index) }, Modifier.focusRequester(focus).focusProperties { up = shelfActionFocus },
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
                                                    PrismAction(if (saved) "✓  已收藏" else "收藏", {
                                                        toggleSubject(browsePluginId, browseKind, item, sourceName)
                                                    }, if (!playAllInHero) heroUp.focusAnchor(detailHeaderFocus) else heroUp)
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
                                                    LazyRow(state = browseAlbumRowState, horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
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
                                                                ),
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
                                                )
                                            }
                                            if (browseSongs.isEmpty() && browseStatus.isNotBlank()) item {
                                                Text(browseStatus, color = c.muted, fontSize = 13.sp)
                                            }
                                            itemsIndexed(browseSongs) { index, song ->
                                                val rowFocus = trackFocus(song.key)
                                                detailCardFocus["song:${song.key}"] = rowFocus
                                                PrismTrackRow(song, index, { playQueue(browseSongs.toList(), index) },
                                                    modifier = Modifier.focusRequester(rowFocus).then(
                                                        if (index == 0) Modifier.focusProperties {
                                                            if (browseAlbums.isEmpty()) {
                                                                up = if (detailHeaderFocus.attached) detailHeaderFocus.requester else discoverFocus
                                                            }
                                                        } else Modifier
                                                    ),
                                                    current = queue.getOrNull(queueIndex)?.key == song.key,
                                                    qualityTag = qualityTags[song.key]?.takeIf { it.isNotBlank() },
                                                    /* Reaching the end also requests the next page. */
                                                    onFocus = {
                                                        detailFocusMemory.value = rowFocus to (detailSongRowOffset() + index)
                                                        resolveQuality(song)
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
                                            PrismTrackRow(song, index, { playQueue(chartSongs.toList(), index) }, modifier = if (index == 0) Modifier.focusAnchor(discoverEntryFocus).focusProperties { if (detailHeaderFocus.attached) up = detailHeaderFocus.requester } else Modifier.focusRequester(rowFocus), current = queue.getOrNull(queueIndex)?.key == song.key, qualityTag = qualityTags[song.key]?.takeIf { it.isNotBlank() }, onFocus = { detailFocusMemory.value = rowFocus to index; resolveQuality(song) }, onLongPress = { trackMenu = TrackMenuTarget(song, originFocus = "song:${song.key}") })
                                            }
                                        }
                                    }
                                } else {
                                PrismPageHead("发现") {
                                    PrismLink("发现设置", { settingsSection = "推荐"; page = "plugins" },
                                        (if (recommendationSource == null) Modifier.focusAnchor(discoverEntryFocus) else Modifier).focusProperties { up = discoverFocus })
                                }
                                val artistsFirst = savedArtists.isNotEmpty()
                                /* MusicFree has no global artist/album listing to fill the
                                   sketch's grids with, so these shelves show what you kept from
                                   a detail page; the charts below remain source-driven. */
                                LazyColumn(state = discoverListState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                                    if (recommendationSource != null) item {
                                        RecommendationShelf(recommendationSource, discoverFocus, discoverEntryFocus, { item, focus -> showSubjectMenu(recommendationSource, "sheet", item, focus) }) { openBrowse(recommendationSource, it, "sheet") }
                                    }
                                    if (artistSeeds.isNotEmpty()) {
                                        item { PrismShelfHeader("从常听歌手继续", null, "", {}) }
                                        item {
                                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                                                items(artistSeeds) { seed ->
                                                    PrismSheetCard(seed.artist, seed.data.optString("artistArtwork"), "来自你的收藏与曲库 · ${seed.sourceLabel}", portrait = true,
                                                        modifier = Modifier.focusRequester(detailFocus("seed:${seed.key}")).focusProperties { up = discoverFocus },
                                                        onLongPress = {
                                                            menuReturnFocus = detailFocus("seed:${seed.key}")
                                                            trackMenu = TrackMenuTarget(seed)
                                                        }) { openSubject(seed, "artist") }
                                                }
                                            }
                                        }
                                    }
                                    if (artistsFirst) {
                                        item { PrismShelfHeader("歌手", null, "", {}) }
                                        item {
                                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                                                items(savedArtists) { subject ->
                                                    PrismSheetCard(subject.title, subject.artwork, "${subject.kindLabel} · ${subject.sourceName.ifBlank { "已收藏" }}", portrait = subject.kind == "artist",
                                                        modifier = Modifier.focusRequester(detailFocus("${subject.kind}:${subject.pluginId}:${subject.data.optString("id")}"))
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
                                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                                                items(savedAlbums) { subject ->
                                                    PrismSheetCard(subject.title, subject.artwork, "${subject.kindLabel} · ${subject.sourceName.ifBlank { "已收藏" }}", portrait = subject.kind == "artist",
                                                        modifier = Modifier.focusRequester(detailFocus("${subject.kind}:${subject.pluginId}:${subject.data.optString("id")}"))
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
                                    item { PrismShelfHeader("音乐榜单", null, if (plugin == null) "" else "刷新", { if (plugin != null) lifecycleScope.launch { loadCharts(plugin) } }) }
                                    if (charts.isEmpty()) item { Text(chartStatus, color = c.muted, fontSize = 13.sp) }
                                    else itemsIndexed(charts.chunked(3)) { chunkIndex, group ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                            group.forEach { chart ->
                                                val chartId = chart.optString("id")
                                                val cardFocus = detailFocus("chart:$chartId")
                                                val cardRow = discoverChartsOffset + chunkIndex
                                                PrismChartCard(
                                                    chart.optString("title").ifBlank { "榜单" },
                                                    chart.optString("coverImg").ifBlank { chart.optString("artwork") },
                                                    chartPreviews[chartId].orEmpty(),
                                                    Modifier.weight(1f).focusRequester(cardFocus)
                                                        .onFocusChanged { if (it.isFocused) discoverFocusMemory.value = cardFocus to cardRow }
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
                                PrismPageHead("搜索音乐")
                                /* Pressing 搜索 is the end of typing. Leaving the IME up covers half the
                                   results you just asked for — the same mistake as opening a page and
                                   covering it with a dialog. */
                                val ime = LocalSoftwareKeyboardController.current
                                val firstSuggestionFocus = remember { FocusAnchor() }
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
                                val suggestionsVisible = !busy && (keyword.isBlank() || keyword.trim() != searchTerm.trim())
                                val searchListState = remember(searchTerm, searchType) { LazyListState() }
                                val searchContentFocus = if (suggestionsVisible) firstSuggestionFocus else searchResultsFocus
                                LaunchedEffect(focusSearchResults, busy, results.isNotEmpty(), entities.isNotEmpty(), hasMore, navBarFocused) {
                                    if (!focusSearchResults || navBarFocused || playerOpen) return@LaunchedEffect
                                    if (results.isEmpty() && entities.isEmpty() && busy) return@LaunchedEffect
                                    if (results.isNotEmpty() || entities.isNotEmpty() || hasMore) {
                                        searchListState.scrollToItem(0)
                                        repeat(4) {
                                            withFrameNanos { }
                                            if (searchResultsFocus.requestIfAttached()) { focusSearchResults = false; return@LaunchedEffect }
                                        }
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
                                            if (searchFilterFocus.attached) down = searchFilterFocus.requester
                                            up = searchFocus
                                        }, manualKeyboard = true, onSubmit = { if (!busy && keyword.isNotBlank()) search(keyword) })
                                    AuraIconButton("search", if (busy) "搜索中" else "搜索", {
                                        /* Do not clearFocus here: this button already holds focus, and
                                           clearing it leaves the whole screen without a focus target.
                                           Hiding the IME is enough to get it out of the way. */
                                        if (!busy) { ime?.hide(); if (keyword.isBlank()) openPhoneHub() else search(keyword) }
                                    }, Modifier.focusProperties {
                                        if (searchContentFocus.attached) down = searchContentFocus.requester
                                        up = searchFocus
                                    }, primary = true)
                                    AuraIconButton("qr", "手机遥控", { openPhoneHub() })
                                }
                                /* Filters are footnotes: a quiet link row under the field, not a strip
                                   of buttons competing with the thing you actually came to do. */
                                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("类型", color = c.muted, fontSize = 10.sp, modifier = Modifier.padding(end = 6.dp))
                                    searchTypeOptions.forEachIndexed { optionIndex, (type, label) ->
                                        PrismLink(label, {
                                            if (!busy && searchType != type) { searchType = type; results = emptyList(); entities = emptyList(); hasMore = false; if (keyword.isNotBlank()) search(keyword) }
                                        }, (if (optionIndex == 0) Modifier.focusAnchor(searchFilterFocus) else Modifier)
                                            /* From the strip the remote should be able to leave upward as
                                               well as downward: geometric search would otherwise strand it
                                               in the row itself, since the field is the only thing above. */
                                            .focusProperties {
                                                if (searchInputFocus.attached) up = searchInputFocus.requester
                                                val target = if (searchFiltersOpen) searchChipFocus else searchContentFocus
                                                if (target.attached) down = target.requester
                                            },
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
                                            if (open) pageScope.launch {
                                                repeat(3) { withFrameNanos { } }
                                                searchChipFocus.requestIfAttached()
                                            }
                                        },
                                        Modifier.focusAnchor(searchSourceFocus).focusProperties {
                                            if (searchInputFocus.attached) up = searchInputFocus.requester
                                            if (searchFiltersOpen && searchChipFocus.attached) down = searchChipFocus.requester
                                            else if (searchContentFocus.attached) down = searchContentFocus.requester
                                        },
                                        active = searchFiltersOpen || searchSources.isNotEmpty())
                                }
                                    if (searchFiltersOpen) {
                                    Spacer(Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("音源", color = c.muted, fontSize = 10.sp, modifier = Modifier.width(30.dp))
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                                            item { PrismAction(if (searchSources.isEmpty()) "✓ 全部音源" else "全部音源", { if (!busy) { searchSources = emptySet(); results = emptyList(); entities = emptyList(); hasMore = false; if (keyword.isNotBlank()) search(keyword) } }, Modifier.focusAnchor(searchChipFocus).focusProperties { if (searchSourceFocus.attached) up = searchSourceFocus.requester }) }
                                            itemsIndexed(activePlugins) { chipIndex, p ->
                                                val platform = p.platform
                                                val name = p.displayName
                                                PlatformAction(platform, if (p.id in searchSources) "✓ $name" else name,
                                                    if (chipIndex == 0 && !searchChipFocus.attached) Modifier.focusAnchor(searchChipFocus) else Modifier) {
                                                    if (!busy) { searchSources = if (p.id in searchSources) searchSources - p.id else searchSources + p.id; selectedPlugin = p.id; results = emptyList(); entities = emptyList(); hasMore = false; if (keyword.isNotBlank()) search(keyword) }
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
                                            PrismLink("清空历史", { searchHistory = searchHistory - searchType; saveSearchHistory() })
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    if (suggestionRows.isEmpty()) Text(
                                        if (keyword.isBlank()) "还没有搜索记录。用遥控器输入，或扫码用手机遥控搜索" else "没有匹配的联想，按搜索键直接搜索「$keyword」",
                                        color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                                    ) else Column(Modifier.weight(1f).onFocusChanged {
                                        pinnedSuggestions = if (it.hasFocus) pinnedSuggestions ?: availableSuggestions else null
                                    }.focusGroup().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (keyword.isBlank() && searchType != "music") suggestionRows.chunked(4).forEachIndexed { rowIndex, group ->
                                            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                group.forEachIndexed { columnIndex, item ->
                                                    PrismSearchSuggestionCard(item, searchType, {
                                                        keyword = item.keyword; ime?.hide(); searchInputFocus.requestIfAttached(); if (!busy) search(item.keyword)
                                                    }, Modifier.weight(1f)
                                                        .then(if (rowIndex == 0 && columnIndex == 0) Modifier.focusAnchor(firstSuggestionFocus) else Modifier)
                                                        .focusProperties { if (rowIndex == 0 && searchFilterFocus.attached) up = searchFilterFocus.requester })
                                                }
                                                repeat(4 - group.size) { Spacer(Modifier.weight(1f)) }
                                            }
                                        } else suggestionRows.forEachIndexed { index, item ->
                                            PrismSuggestionRow(
                                                item.title,
                                                item.badge,
                                                /* The row that was focused vanishes once the list collapses;
                                                   hand focus back to the field so Down still reaches results. */
                                                { keyword = item.keyword; ime?.hide(); searchInputFocus.requestIfAttached(); if (!busy) search(item.keyword) },
                                                if (index == 0) Modifier.focusAnchor(firstSuggestionFocus)
                                                    .focusProperties { if (searchFilterFocus.attached) up = searchFilterFocus.requester }
                                                    else Modifier,
                                                artwork = item.artwork, subtitle = item.subtitle,
                                            )
                                        }
                                    }
                                } else {
                                Spacer(Modifier.height(18.dp))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("搜索结果", color = c.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.width(8.dp))
                                    val resultCount = if (searchType == "music") results.size else entities.size
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
                                        Text(if (searchType == "music") "长按确定键：收藏 / 找歌手" else "确定键打开详情", color = c.muted.copy(alpha = .8f), fontSize = 10.sp)
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                if (searchType != "music") {
                                    if (entities.isEmpty()) Text(
                                        if (busy) "正在搜索…" else "没有找到「$keyword」的${listOf("artist" to "歌手", "album" to "专辑", "sheet" to "歌单").firstOrNull { it.first == searchType }?.second ?: "结果"}。这个音源可能只支持歌曲搜索，切到「歌曲」再试一次",
                                        color = c.muted, fontSize = 12.sp
                                    )
                                    else LazyColumn(Modifier.weight(1f), state = searchListState, verticalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp)) {
                                        items(entities.chunked(6)) { row ->
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                row.forEach { entry ->
                                                    val last = entry === entities.last()
                                                    PrismSheetCard(entry.data.optString("title").ifBlank { entry.data.optString("name").ifBlank { "未命名" } }, entry.data.optString("artwork").ifBlank { entry.data.optString("coverImg") }, entry.sourceName, portrait = searchType == "artist",
                                                        modifier = Modifier
                                                            .focusRequester(detailFocus("$searchType:${entry.pluginId}:${entry.data.optString("id")}"))
                                                            .then(if (entry === entities.first()) Modifier.focusAnchor(searchResultsFocus)
                                                                .focusProperties { if (searchFilterFocus.attached) up = searchFilterFocus.requester }
                                                                else Modifier)
                                                            .then(if (last && hasMore) Modifier.onFocusChanged {
                                                                if (it.isFocused && !busy) search(searchTerm, append = true)
                                                            } else Modifier),
                                                        onLongPress = { state.plugins.firstOrNull { it.id == entry.pluginId }?.let {
                                                            showSubjectMenu(it, searchType, entry.data, detailFocus("$searchType:${entry.pluginId}:${entry.data.optString("id")}"))
                                                        } }) {
                                                        state.plugins.firstOrNull { it.id == entry.pluginId }?.let { openBrowse(it, entry.data, searchType) }
                                                    }
                                                }
                                            }
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
                                } else LazyColumn(Modifier.weight(1f).padding(top = 4.dp), state = searchListState) {
                                    itemsIndexed(results, key = { _, song -> song.key }) { index, song ->
                                        val rowFocus = if (index == 0) searchResultsFocus.requester else trackFocus(song.key)
                                        detailCardFocus["song:${song.key}"] = rowFocus
                                        PrismTrackRow(song, index, { playQueue(results.toList(), index) },
                                            current = queue.getOrNull(queueIndex)?.key == song.key,
                                            onLongPress = { trackMenu = TrackMenuTarget(song, originFocus = "song:${song.key}") },
                                            onFocus = {
                                                if (index == results.lastIndex && hasMore && !busy) search(searchTerm, append = true)
                                            },
                                            modifier = if (index == 0) Modifier.focusAnchor(searchResultsFocus)
                                                /* The first row is also the door back to the 类型/音源 strip;
                                                   without it the only upward exit is the field. */
                                                .focusProperties { if (searchFilterFocus.attached) up = searchFilterFocus.requester }
                                                else Modifier.focusRequester(rowFocus))
                                    }
                                    if (searchAppending && results.isNotEmpty()) item {
                                        Text("正在加载更多…", color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                                    }
                                }
                                }
                                if (hasMore) {
                                    PrismAction(if (busy) "正在加载…" else "加载更多", { if (!busy) search(searchTerm, append = true) },
                                        if (results.isEmpty() && entities.isEmpty()) Modifier.focusAnchor(searchResultsFocus)
                                            .focusProperties { if (searchFilterFocus.attached) up = searchFilterFocus.requester }
                                        else Modifier)
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

                                /* The bar below already names the list and counts it; a second copy in
                                   the head would just repeat it at the same scroll position. */
                                PrismPageHead(if (selectedPlaylist == null) "我的" else "歌单详情") {
                                    if (selectedPlaylist != null) PrismAction("返回歌单", { closePlaylistTable() },
                                        Modifier.focusAnchor(playlistBackFocus).onPreviewKeyEvent { it.key == Key.DirectionUp })
                                }
                                /* The tab strip and the list beneath it are separate focus
                                   spaces; name the first row so Down never falls through. */
                                val belowTabs = Modifier.focusProperties {
                                    val listShown = when {
                                        showingSubjects -> visibleSubjects.isNotEmpty()
                                        showingPlaylists -> libraryState.playlists.isNotEmpty() || savedSheets.isNotEmpty()
                                        else -> songs.isNotEmpty()
                                    }
                                    if (listShown && libraryContentFocus.attached) down = libraryContentFocus.requester
                                    else if (libraryHeaderFocus.attached) down = libraryHeaderFocus.requester
                                    up = libraryFocus
                                }
                                if (selectedPlaylist == null) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                                    val tabs = listOf(
                                        Triple("favorites", "歌曲", libraryState.favorites.size),
                                        Triple("albums", "专辑", libraryState.subjects.count { it.kind == "album" }),
                                        Triple("artists", "歌手", libraryState.subjects.count { it.kind == "artist" }),
                                        Triple("playlists", "歌单", libraryState.playlists.size + savedSheets.size),
                                        Triple("recent", "最近", libraryState.recent.size),
                                    )
                                    items(tabs, key = { it.first }) { (id, label, count) ->
                                        PrismAction("$label $count", { selectedList = id }, primary = selectedList == id,
                                            modifier = Modifier.focusAnchor(libraryTabs.getValue(id)).then(belowTabs)
                                                .onFocusChanged { if (it.isFocused && selectedList != id) selectedList = id })
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                                /* One bar above all four lists, always in the same slot: entering
                                   管理 swaps the verbs on its right end and the list below stays put. */
                                val manageTargets = when {
                                    showingSubjects -> visibleSubjects.map { it.key }
                                    showingPlaylists -> libraryState.playlists.map { it.id }
                                    else -> songs.map { it.key }
                                }
                                val allChosen = manageTargets.isNotEmpty() && manageTargets.all { it in librarySelected }
                                val headerEdges = Modifier.focusProperties {
                                    up = if (selectedPlaylist != null) playlistBackFocus.requester
                                        else libraryTabs.getValue(libraryTabKey).requester
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
                                        if (showingPlaylists) {
                                            PrismAction("导入歌单", { importDialog = true }, Modifier.focusAnchor(libraryHeaderFocus).then(headerEdges))
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        if (!showingSubjects && !showingPlaylists && songs.isNotEmpty()) {
                                            PrismAction("全部播放", { playQueue(songs) }, Modifier.focusAnchor(libraryHeaderFocus).then(headerEdges))
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        if (manageTargets.isNotEmpty()) {
                                            PrismAction("管理", { enterLibraryManage() }, headerEdges.then(
                                                if (showingSubjects) Modifier.focusAnchor(libraryHeaderFocus) else Modifier))
                                        } else if (!showingPlaylists && selectedPlaylist == null) {
                                            PrismAction("去发现", { page = "discover" }, Modifier.focusAnchor(libraryHeaderFocus).then(headerEdges))
                                        } else if (selectedPlaylist != null && songs.isEmpty()) {
                                            PrismAction("返回歌单", { closePlaylistTable() }, Modifier.focusAnchor(libraryHeaderFocus).then(headerEdges))
                                        }
                                    }
                                }
                                if (showingSubjects) {
                                    Spacer(Modifier.height(8.dp))
                                    if (subjectSections.isEmpty()) {
                                        Text("在${if (subjectKind == "artist") "歌手" else "专辑"}详情页按「收藏」，就会收进这里", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                                    } else LazyColumn(state = librarySubjectListState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                                        subjectSections.forEach { (label, group) ->
                                            item(key = "subject-header:$label") {
                                                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Text(label, color = c.foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                                    Spacer(Modifier.width(8.dp))
                                                    Text("${group.size} 个", color = c.muted, fontSize = 11.sp)
                                                }
                                            }
                                            items(group.chunked(6), key = { row -> "subject-row:${row.first().key}" }) { row ->
                                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    row.forEach { subject ->
                                                        PrismSheetCard(subject.title, subject.artwork, "${subject.kindLabel} · ${subject.sourceName.ifBlank { "已收藏" }}", portrait = subject.kind == "artist",
                                                            modifier = Modifier.focusRequester(detailFocus("${subject.kind}:${subject.pluginId}:${subject.data.optString("id")}"))
                                                                .then(if (subject === firstSubject) Modifier.focusAnchor(libraryContentFocus) else Modifier)
                                                                .focusProperties { if (subject in visibleSubjects.take(6) && libraryHeaderFocus.attached) up = libraryHeaderFocus.requester },
                                                            onLongPress = if (libraryManage) null else { {
                                                                menuReturnFocus = detailFocus("${subject.kind}:${subject.pluginId}:${subject.data.optString("id")}")
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
                                    LazyColumn(Modifier.weight(1f), state = libraryPlaylistListState, verticalArrangement = Arrangement.spacedBy(10.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                                        items(libraryState.playlists.chunked(6), key = { row -> "local:${row.first().id}" }) { row ->
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                row.forEach { list ->
                                                        val platform = MusicPlatform.identify(list.platform)
                                                        val cardFocus = detailFocus("playlist:${list.id}")
                                                        detailCardFocus["playlist:${list.id}"] = cardFocus
                                                        PrismSheetCard(list.name, list.songs.firstOrNull { it.artwork.startsWith("http") }?.artwork.orEmpty(),
                                                            "${list.songs.size} 首 · ${platform.label}",
                                                            modifier = Modifier.focusRequester(cardFocus)
                                                                .then(
                                                                    if (list == libraryState.playlists.first()) Modifier.focusAnchor(libraryContentFocus)
                                                                    else Modifier,
                                                                ).focusProperties {
                                                                    if (list in libraryState.playlists.take(6) && libraryHeaderFocus.attached) up = libraryHeaderFocus.requester
                                                                },
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
                                            item(key = "saved-sheets") { Text("收藏的歌单", color = c.foreground, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp)) }
                                            items(savedSheets.chunked(6), key = { row -> "saved:${row.first().key}" }) { row ->
                                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    row.forEach { subject ->
                                                        val key = "sheet:${subject.pluginId}:${subject.data.optString("id")}"
                                                        val cardFocus = detailFocus(key)
                                                        PrismSheetCard(subject.title, subject.artwork, subject.sourceName.ifBlank { "已收藏" },
                                                            modifier = Modifier.focusRequester(cardFocus)
                                                                .then(if (libraryState.playlists.isEmpty() && subject == savedSheets.first()) Modifier.focusAnchor(libraryContentFocus) else Modifier)
                                                                .focusProperties {
                                                                    if (libraryState.playlists.isEmpty() && subject in savedSheets.take(6) && libraryHeaderFocus.attached) up = libraryHeaderFocus.requester
                                                                },
                                                            onLongPress = { menuReturnFocus = cardFocus; subjectMenu = subject }) {
                                                            state.plugins.firstOrNull { it.id == subject.pluginId }?.let { openBrowse(it, subject.data, "sheet") }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        if (libraryState.playlists.isEmpty() && savedSheets.isEmpty()) {
                                            item { Text("导入平台歌单，或在歌单详情页收藏，就会出现在这里", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                                        }
                                    }
                                } else {
                                Spacer(Modifier.height(8.dp))
                                PrismTrackHeader(managing = libraryManage)
                                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line.copy(alpha = .5f)))
                                if (songs.isEmpty()) {
                                    Text(
                                        when {
                                            selectedPlaylist != null -> "这个歌单里还没有歌"
                                            selectedList == "favorites" -> "播放时按收藏，歌曲会出现在这里"
                                            else -> "播过的歌会自动记在这里"
                                        },
                                        color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp),
                                    )
                                } else LazyColumn(Modifier.weight(1f).padding(top = 4.dp)) {
                                    itemsIndexed(songs, key = { _, song -> song.key }) { index, song ->
                                        val rowFocus = if (index == 0) libraryContentFocus.requester else trackFocus(song.key)
                                        detailCardFocus["song:${song.key}"] = rowFocus
                                        val rowMenu: (() -> Unit)? = if (libraryManage) null else { -> trackMenu = TrackMenuTarget(song, selectedList, "song:${song.key}") }
                                        PrismTrackRow(song, index,
                                            { if (libraryManage) toggleLibrarySelection(song.key) else playQueue(songs, index) },
                                            modifier = if (index == 0) Modifier.focusAnchor(libraryContentFocus).focusProperties { if (libraryHeaderFocus.attached) up = libraryHeaderFocus.requester } else Modifier.focusRequester(rowFocus),
                                            current = queue.getOrNull(queueIndex)?.key == song.key,
                                            onLongPress = rowMenu,
                                            selected = if (libraryManage) song.key in librarySelected else null)
                                    }
                                }
                                }
                            }
                            else -> {
                                val selected = state.plugins.firstOrNull { it.id == configured }
                                val settingsScroll = rememberScrollState()
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
                                    /* Down lands on the section rail, not inside the panel.
                                       The rail is where 外观/推荐/音源 live; dropping straight
                                       into the content skipped it, which read as "the tabs are
                                       unreachable" even though Left could find them. Right from
                                       the rail, or OK on it, enters the open section. */
                                    val bannerDown = Modifier.focusProperties {
                                        val anchor = sectionFocus.getValue(settingsSection)
                                        /* Down walks into the rail from its first entry: jumping straight to
                                           whichever section is lit made the top of the list look unreachable.
                                           Left still lands on the lit one so the bar-style horizontal preview
                                           keeps working. */
                                        val entry = sectionFocus.getValue(settingsSections.first())
                                        if (entry.attached) down = entry.requester
                                        if (anchor.attached) left = anchor.requester
                                        up = pluginsFocus
                                    }
                                    PrismPageHead("设置")
                                    /* The phone is the real keyboard for this app. Put the door to it at
                                       the top of the page instead of burying it inside 外观. The whole
                                       banner is the focus target: a 40 dp icon was the only thing the
                                       ring could reach, which read as a decoration rather than a door. */
                                    var bannerFocused by remember { mutableStateOf(false) }
                                    val bannerLight by animateFloatAsState(if (bannerFocused) 1f else 0f,
                                        tween(if (LocalAuraMotion.current) 170 else 0), label = "remote banner")
                                    val bannerShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                                    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp).focusAnchor(bannerFocus)
                                        .onFocusChanged { bannerFocused = it.isFocused }
                                        /* focusProperties only steers the focus target that
                                           follows it. Applying it after `clickable` left the
                                           banner on the geometric search, which dropped Down
                                           into the panel and made the rail look unreachable. */
                                        .then(bannerDown)
                                        .clip(bannerShape)
                                        .background(Brush.horizontalGradient(listOf(
                                            c.accent.copy(alpha = .16f + .10f * bannerLight),
                                            c.panel.copy(alpha = .55f + .10f * bannerLight),
                                            c.panel.copy(alpha = .2f),
                                        )))
                                        .border(if (bannerLight > .5f) 2.dp else 1.dp,
                                            c.accent.copy(alpha = .22f + .5f * bannerLight), bannerShape)
                                        .clickable { openPhoneHub() }
                                        .padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(30.dp).clip(bannerShape).background(c.accent.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                                            Text("⌁", color = c.accent, fontSize = 17.sp)
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("手机遥控", color = c.foreground, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                            Text("同一 Wi-Fi 扫码：遥控播放、管理音源、搜索与换主题", color = c.muted, fontSize = 12.sp, maxLines = 1)
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(if (bannerFocused) c.accent else c.panel), contentAlignment = Alignment.Center) {
                                            Glyph("qr", Modifier.size(18.dp), if (bannerFocused) c.onAccent else c.foreground.copy(alpha = .82f))
                                        }
                                    }
                                    Row(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                    Column(Modifier.width(130.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        settingsSections.forEach { section ->
                                            PrismNav(section, settingsSection == section, {
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
                                                Modifier.fillMaxWidth().focusAnchor(sectionFocus.getValue(section))
                                                    /* Same contract as the top bar: the panel changes
                                                       with the highlight, so one press both previews
                                                       and selects. */
                                                    .onFocusChanged { if (it.isFocused && settingsSection != section) settingsSection = section }
                                                    /* Only the selected section's content is composed; pointing
                                                       Right anywhere else would target a node that isn't there. */
                                                    .focusProperties {
                                                        val entry = sectionEntryFocus.getValue(section)
                                                        if (section == settingsSection && entry.attached) right = entry.requester
                                                    }
                                                    /* An unselected section's content isn't on
                                                       screen yet: Right would find no candidate
                                                       and drop focus off the screen. */
                                                    .onPreviewKeyEvent {
                                                        it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight &&
                                                            section != settingsSection
                                                    }, dense = true)
                                        }
                                    }
                                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(settingsScroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (settingsSection == "外观") {
                                    Text("主题", fontSize = 18.sp, color = c.foreground)
                                    Text("三种光线，同一种专注", color = c.muted, fontSize = 12.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        listOf(Triple("gold", "极夜暖金", "温暖、沉浸"), Triple("porcelain", "晨光白瓷", "明亮、柔和"), Triple("mono", "静谧黑白", "纯粹、克制")).forEachIndexed { index, (value, label, detail) ->
                                            AuraThemeCard(label, detail, prismColors(value), theme == value, {
                                                theme = value; getSharedPreferences("appearance", MODE_PRIVATE).edit().putString("theme", value).apply()
                                            }, if (index == 0) Modifier.weight(1f).focusAnchor(sectionEntryFocus.getValue("外观")).then(entryEdge).rememberFocus(bodyFocusMemory) else Modifier.weight(1f).rememberFocus(bodyFocusMemory))
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        PrismAction(if (showMiniLyrics) "播放条歌词 · 开" else "播放条歌词 · 关", {
                                            showMiniLyrics = !showMiniLyrics
                                            getSharedPreferences("appearance", MODE_PRIVATE).edit().putBoolean("mini_lyrics", showMiniLyrics).apply()
                                        }, edgeToRail.rememberFocus(bodyFocusMemory))
                                        PrismAction(if (reduceMotion) "减少动态效果 · 开" else "减少动态效果 · 关", {
                                            reduceMotion = !reduceMotion
                                            getSharedPreferences("appearance", MODE_PRIVATE).edit().putBoolean("reduce_motion", reduceMotion).apply()
                                        }, Modifier.rememberFocus(bodyFocusMemory))
                                    }
                                    Text("关闭环境光流动与过渡动画", color = c.muted, fontSize = 12.sp)
                                    }
                                    if (settingsSection == "播放") {
                                        Text("播放", fontSize = 18.sp, color = c.foreground)
                                        Text("拿不到更高档时自动降档", color = c.muted, fontSize = 12.sp)

                                        Text("首选音质", fontSize = 13.sp, color = c.foreground, modifier = Modifier.padding(top = 10.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf("low" to "流畅", "standard" to "标准", "high" to "高品", "super" to "无损优先").forEachIndexed { index, pair ->
                                                val (value, label) = pair
                                                PrismAction(if (quality == value) "✓ $label" else label, {
                                                    quality = value
                                                    getSharedPreferences("playback", MODE_PRIVATE).edit().putString("quality", value).apply()
                                                }, if (index == 0) Modifier.focusAnchor(sectionEntryFocus.getValue("播放")).then(entryEdge).rememberFocus(bodyFocusMemory) else Modifier.rememberFocus(bodyFocusMemory))
                                            }
                                        }

                                        Text("播放模式", fontSize = 13.sp, color = c.foreground, modifier = Modifier.padding(top = 10.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf("顺序播放", "列表循环", "单曲循环", "随机播放").forEachIndexed { index, mode ->
                                                PrismAction(if (playMode == mode) "✓ $mode" else mode, {
                                                    leaveRadio()
                                                    playMode = mode
                                                    getSharedPreferences("playback", MODE_PRIVATE).edit().putString("mode", mode).apply()
                                                }, if (index == 0) edgeToRail.rememberFocus(bodyFocusMemory) else Modifier.rememberFocus(bodyFocusMemory))
                                            }
                                        }

                                        Text("搜索并发", fontSize = 13.sp, color = c.foreground, modifier = Modifier.padding(top = 10.dp))
                                        Text(
                                            "自动按电视性能选择（当前 ${PluginWorkers.automatic(this@MainActivity)} 个），调大更快也更占内存",
                                            color = c.muted, fontSize = 12.sp,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf(0 to "自动", 1 to "1 个", 2 to "2 个", 3 to "3 个", 4 to "4 个").forEach { (value, label) ->
                                                PrismAction(if (pluginWorkerLimit == value) "✓ $label" else label, {
                                                    pluginWorkerLimit = value
                                                    PluginWorkers.save(this@MainActivity, value)
                                                }, if (value == 0) edgeToRail.rememberFocus(bodyFocusMemory) else Modifier.rememberFocus(bodyFocusMemory))
                                            }
                                        }
                                    }
                                    if (settingsSection == "关于") {
                                    Text("版本", fontSize = 18.sp, color = c.foreground)
                                    Text("PrismAura $versionName", color = c.foreground, fontSize = 16.sp)
                                    Text(updateState.message, color = c.muted, fontSize = 12.sp)
                                    PrismAction(if (updateState.checking) "正在检查…" else if (updateState.release != null) "查看更新 ${updateState.release?.tag}" else "检查更新", {
                                        checkAppUpdates()
                                    }, Modifier.focusAnchor(sectionEntryFocus.getValue("关于")).then(entryEdge).rememberFocus(bodyFocusMemory))
                                    Text("运行环境", fontSize = 18.sp, color = c.foreground, modifier = Modifier.padding(top = 14.dp))
                                    Text(deviceInfo, color = c.foreground, fontSize = 14.sp)
                                    Text("WebView · $webViewInfo", color = c.muted, fontSize = 12.sp)
                                    Text("缓存", fontSize = 18.sp, color = c.foreground, modifier = Modifier.padding(top = 14.dp))
                                    Text(
                                        if (cacheSize < 0) "正在统计缓存…" else "本机缓存 ${formatBytes(cacheSize)} · 封面、歌词与音源脚本",
                                        color = c.muted, fontSize = 12.sp,
                                    )
                                    PrismAction(if (clearingCache) "清理中…" else "清理缓存", {
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
                                    }, edgeToRail.rememberFocus(bodyFocusMemory))
                                    Text("清理临时文件与发现缓存，保留收藏、歌单和音源配置", color = c.muted, fontSize = 12.sp)
                                    /* 备份与恢复只在手机遥控上做：电视没有文件选择器，
                                       这里再放一个入口只会把人带到另一台设备上。 */
                                    }
                                    if (settingsSection == "推荐") {
                                    Text("发现与首页", fontSize = 18.sp, color = c.foreground)
                                    Text("歌单推荐音源", color = c.foreground, fontSize = 15.sp)
                                    SourcePicker(activePlugins.filter { it.capabilities.recommendations }, recommendationPluginId,
                                        Modifier.focusAnchor(sectionEntryFocus.getValue("推荐")).then(entryEdge).rememberFocus(bodyFocusMemory)) { plugin ->
                                        recommendationPluginId = plugin.id
                                        getSharedPreferences("home", MODE_PRIVATE).edit().putString("recommendation_plugin", plugin.id).apply()
                                    }
                                    Text("榜单音源", color = c.foreground, fontSize = 15.sp)
                                    SourcePicker(activePlugins.filter { it.capabilities.charts }, chartPluginId, edgeToRail.rememberFocus(bodyFocusMemory)) { plugin ->
                                        chartPluginId = plugin.id
                                        getSharedPreferences("home", MODE_PRIVATE).edit().putString("chart_plugin", plugin.id).apply()
                                    }
                                    Text("只列出能完成相应浏览流程的音源；推荐与榜单分别选择", color = c.muted, fontSize = 12.sp)
                                    Text("取消勾选即从首页隐藏", color = c.muted, fontSize = 12.sp)
                                    Box {
                                        PrismAction("首页栏目 · ${homeShelves.size} ▾", { shelvesMenuOpen = true }, Modifier.rememberFocus(bodyFocusMemory))
                                        if (shelvesMenuOpen) {
                                            val menuOffset = with(LocalDensity.current) { 52.dp.roundToPx() }
                                            Popup(alignment = Alignment.TopStart, offset = IntOffset(0, menuOffset), onDismissRequest = { shelvesMenuOpen = false }, properties = PopupProperties(focusable = true)) {
                                                val menuFocus = remember { FocusRequester() }
                                                LaunchedEffect(Unit) { menuFocus.requestFocus() }
                                                Column(Modifier.focusRequester(menuFocus).width(240.dp).heightIn(max = 280.dp).background(c.panel, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).border(1.dp, c.line, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    /* Every shelf is offered, hidden ones included, so a
                                                       playlist imported after the last choice can be brought
                                                       back onto 首页 instead of staying invisible. */
                                                    (listOf("favorites" to "我的收藏", "recent" to "最近播放") +
                                                        libraryState.playlists.map { it.id to it.name }).forEach { (id, label) ->
                                                        PrismAction("${if (id !in homeShelfHidden) "✓ " else ""}$label", { toggleHomeShelf(id) })
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    Text("首页栏目取自本机的收藏、最近播放与导入的歌单", color = c.muted, fontSize = 12.sp)
                                    PrismAction("打开「我的」", { page = "library" }, Modifier.rememberFocus(bodyFocusMemory))
                                    }
                                    if (settingsSection == "音源") {
                                    var addingSource by remember { mutableStateOf(false) }
                                    var removingSubscription by remember { mutableStateOf<String?>(null) }
                                    var addSourceError by remember { mutableStateOf("") }
                                    val addressFocus = remember { FocusRequester() }
                                    val addFocus = remember { FocusRequester() }
                                    val updateSourcesFocus = remember { FocusRequester() }
                                    val firstSourceFocus = remember { FocusAnchor() }
                                    val subscriptionGroups = state.subscriptions.map { url ->
                                        url to state.plugins.filter { it.origin == url || (it.origin.isBlank() && it.url == url) }
                                    }
                                    val standalone = state.plugins.filter { it.origin.isBlank() && it.url !in state.subscriptions }
                                    val firstSourceId = subscriptionGroups.firstOrNull { it.second.isNotEmpty() }?.second?.firstOrNull()?.id
                                        ?: standalone.firstOrNull()?.id
                                    val toolbarDown = Modifier.focusProperties { if (firstSourceFocus.attached) down = firstSourceFocus.requester }
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
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("音源", fontSize = 19.sp, color = c.foreground, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.width(10.dp))
                                        Text("${state.plugins.count { it.enabled }} / ${state.plugins.size} 已启用", color = c.muted, fontSize = 12.sp)
                                        Spacer(Modifier.weight(1f))
                                        PrismAction("添加音源", { addSourceError = ""; addingSource = true }, Modifier.focusAnchor(sourceActionFocus).then(entryEdge).then(toolbarDown)
                                            .focusProperties { right = updateSourcesFocus }.rememberFocus(bodyFocusMemory), primary = true)
                                        Spacer(Modifier.width(8.dp))
                                        PrismAction(if (busy) "更新中…" else "全部更新", { if (!busy) lifecycleScope.launch { busy = true; try { showPluginImport(plugins.syncAll()) } catch (e: Exception) { status = "同步失败：${e.message}" } finally { busy = false } } },
                                            Modifier.focusRequester(updateSourcesFocus).focusProperties { up = pluginsFocus; left = sourceActionFocus.requester }.then(toolbarDown).rememberFocus(bodyFocusMemory))
                                    }
                                    if (state.plugins.isEmpty() && state.subscriptions.isEmpty()) Text("添加信任的 MusicFree 插件或订阅，开始听歌", color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 18.dp))

                                    if (addingSource) Dialog(onDismissRequest = { closeAddSource() }) {
                                        LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; addressFocus.requestFocus() }
                                        Column(Modifier.width(580.dp).background(c.panel, androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                            Text("添加音源", color = c.foreground, fontSize = 22.sp)
                                            Input(subscription, { subscription = it; addSourceError = "" }, "插件 .js 或订阅 .json 地址",
                                                Modifier.fillMaxWidth().focusRequester(addressFocus).focusProperties { down = addFocus },
                                                keyboardType = KeyboardType.Uri, manualKeyboard = true, onSubmit = { addFocus.requestFocus() })
                                            if (addSourceError.isNotBlank()) Text(addSourceError, color = c.accent, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                PrismAction(if (busy) "添加中…" else "添加", { addSource() }, Modifier.focusRequester(addFocus).focusProperties { up = addressFocus }, primary = true)
                                                PrismAction("用手机添加", { closeAddSource(); openPhoneHub() }, Modifier.focusProperties { up = addressFocus })
                                                PrismAction("取消", { closeAddSource() }, Modifier.focusProperties { up = addressFocus })
                                            }
                                        }
                                    }
                                    removingSubscription?.let { url ->
                                        val first = remember { FocusRequester() }
                                        PrismContextMenu("移除订阅？", "同时移除订阅内音源，保留本机歌单和收藏", { removingSubscription = null }, first) {
                                            PrismAction("取消", { removingSubscription = null }, Modifier.focusRequester(first), primary = true)
                                            PrismAction("移除订阅", {
                                                removingSubscription = null
                                                lifecycleScope.launch {
                                                    try { plugins.removeSubscription(url); status = "已移除订阅及其音源"; sourceRemoved() }
                                                    catch (e: Exception) { status = e.message ?: "移除失败" }
                                                }
                                            })
                                        }
                                    }

                                    /* A subscription is the thing that can be updated, so it is the row
                                       that owns removal. Its plugins are only switched on or off:
                                       deleting one would just bring it back on the next sync. */
                                    subscriptionGroups.forEach { (url, owned) ->
                                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text("订阅 · ${owned.firstOrNull()?.displayName ?: url.substringAfterLast('/').take(30)}", color = c.foreground, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("${owned.size} 个音源", color = c.muted, fontSize = 12.sp)
                                            }
                                            PrismAction("更新", { if (!busy) lifecycleScope.launch {
                                                busy = true
                                                status = "正在更新订阅…"
                                                try { showPluginImport(plugins.importUrl(url)) }
                                                catch (e: Exception) { status = "更新失败：${e.message}" }
                                                finally { busy = false }
                                            } }, edgeToRail.rememberFocus(bodyFocusMemory))
                                            PrismAction("移除订阅", { removingSubscription = url }, Modifier.rememberFocus(bodyFocusMemory))
                                        }
                                        owned.forEach { plugin -> key(plugin.id) { PluginSourceRow(plugin, owned = true, onConfigure = { configured = plugin.id }, onRemoved = ::sourceRemoved,
                                            configureFocus = sourceConfigFocus.getOrPut(plugin.id) { FocusAnchor() },
                                            edge = edgeToRail.rememberFocus(bodyFocusMemory).then(if (plugin.id == firstSourceId) Modifier.focusAnchor(firstSourceFocus) else Modifier),
                                            top = sourceActionFocus.requester.takeIf { plugin.id == firstSourceId }) } }
                                    }

                                    if (standalone.isNotEmpty() && state.subscriptions.isNotEmpty()) Text("单独添加", color = c.muted, fontSize = 12.sp)
                                    standalone.forEach { plugin -> key(plugin.id) { PluginSourceRow(plugin, owned = false, onConfigure = { configured = plugin.id }, onRemoved = ::sourceRemoved,
                                        configureFocus = sourceConfigFocus.getOrPut(plugin.id) { FocusAnchor() },
                                        edge = edgeToRail.rememberFocus(bodyFocusMemory).then(if (plugin.id == firstSourceId) Modifier.focusAnchor(firstSourceFocus) else Modifier),
                                        top = sourceActionFocus.requester.takeIf { plugin.id == firstSourceId }) } }
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
                        playing = playing,
                        position = positionMs,
                        duration = durationMs,
                        lyric = if (showMiniLyrics) lyrics.lastOrNull { it.timeMs <= positionMs }?.text.orEmpty() else "",
                        favorite = queue.getOrNull(queueIndex)?.let { song -> libraryState.favorites.any { it.key == song.key } } == true,
                        onOpen = { if (queue.getOrNull(queueIndex) == null) page = "search" else playerOpen = true },
                        onToggle = { controller?.let { if (it.playWhenReady) it.pause() else it.play() } },
                        onPrevious = { step(-1) },
                        onNext = { step(1) },
                        onFavorite = { toggleFavorite(queue.getOrNull(queueIndex)) },
                        onQueue = { queueOpen = true },
                        openFocus = miniFocus,
                        onLongPress = queue.getOrNull(queueIndex)?.let { current -> {
                            menuReturnFocus = miniFocus; trackMenu = TrackMenuTarget(current)
                        } },
                        /* After a search result plays, Up should reach the list you were
                           browsing. The text field is only the landing spot while the page
                           has no results to return to. */
                        upTarget = if (page == "search" && (results.isNotEmpty() || entities.isNotEmpty())) searchResultsFocus else pageEntryFocus,
                        upMemory = bodyFocusMemory,
                        /* The 发现 cards live in a list the bar is not part of: after a fast scroll
                           to the bottom the card has no node any more, so Up has to scroll its row
                           back before the remembered requester can take the remote. */
                        upRestore = when {
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
                PrismBackground {
                    val song = queue.getOrNull(queueIndex)
                    PrismPlayer(song, lyrics, lyricStatus, positionMs, durationMs, playing, playMode,
                        song != null && libraryState.favorites.any { it.key == song.key }, quality, playingQuality,
                        queueIndex, queue.size, status,
                        streamFormat,
                        hasMv = song?.let { it.hasMv && state.plugins.firstOrNull { p -> p.id == it.pluginId }?.capabilities?.has("mvSource") == true } == true,
                        mvOpen = activeMv != null,
                        onBack = { playerOpen = false }, onPlay = { controller?.let { if (it.playWhenReady) it.pause() else it.play() } },
                        onPrevious = { step(-1) }, onNext = { step(1) }, onShuffle = {
                            leaveRadio()
                            val modes = listOf("顺序播放", "列表循环", "单曲循环", "随机播放")
                            playMode = modes[(modes.indexOf(playMode) + 1).coerceAtLeast(0) % modes.size]
                            getSharedPreferences("playback", MODE_PRIVATE).edit().putString("mode", playMode).apply()
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
                        onSeek = { target -> controller?.seekTo(target) },
                        onArtist = song?.let { { openSubject(it, "artist") } },
                        onAlbum = song?.let { { openSubject(it, "album") } },
                        onQueue = { queueOpen = true },
                        onMv = { openMv(song) },
                        /* A tier the plugin left unanswered is dropped, so the pill only cycles
                           through streams this song can actually play. */
                        tiers = availableTiers.filterNot { it in unsupportedTiers[song?.key].orEmpty() },
                        onSelectQuality = { tier ->
                            if (tier != quality) {
                                quality = tier
                                getSharedPreferences("playback", MODE_PRIVATE).edit().putString("quality", tier).apply()
                                if (queue.getOrNull(queueIndex)?.pluginId != "local" && queue.isNotEmpty()) {
                                    playAt(queueIndex, openPlayer = false, autoplay = controller?.playWhenReady == true, startAt = positionMs)
                                }
                            }
                        })
                }
            }
            }
            activeMv?.let { source ->
                val mvSong = queue.getOrNull(queueIndex)
                MvPlayer(source, mvSong?.title.orEmpty(), mvSong?.artist.orEmpty(), progressKey = mvSong?.key.orEmpty()) {
                    activeMv = null
                    status = ""
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
                    pageScope.launch { withFrameNanos { }; pageEntryFocus?.requestIfAttached() }
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
                val currentQueueFocus = remember { FocusRequester() }
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
                            Column(Modifier.fillMaxWidth(.46f).fillMaxHeight().background(Brush.verticalGradient(listOf(c.panel.copy(alpha = .98f), c.background.copy(alpha = .98f)))).padding(horizontal = 24.dp, vertical = 28.dp)) {
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
                                            onLongPress = { menuReturnFocus = rowFocus; trackMenu = TrackMenuTarget(item, originFocus = "queue:${item.key}") }
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
                                PrismIconAction(if (saved) "heartFilled" else "heart", if (saved) "已收藏" else "收藏", {
                                    toggleFavorite(song); trackMenu = null
                                }, Modifier.focusRequester(firstFocus), active = saved)
                                if (song.artist.isNotBlank() && song.artist != "未知歌手")
                                    PrismIconAction("mic", if (artistDetail) "歌手详情" else "搜歌手", {
                                        trackMenu = null
                                        if (artistDetail) openSubject(song, "artist", target.originFocus) else find(song.artist, "music")
                                    })
                                if (song.album.isNotBlank())
                                    PrismIconAction("album", if (albumDetail) "专辑详情" else "搜专辑", {
                                        trackMenu = null
                                        if (albumDetail) openSubject(song, "album", target.originFocus) else find(song.album, if (albumSearch) "album" else "music")
                                    })
                                if (target.list?.startsWith("playlist:") == true)
                                    PrismIconAction("trash", "移出歌单", {
                                        trackMenu = null
                                        lifecycleScope.launch { try { library.removeSong(target.list.removePrefix("playlist:"), song) } catch (e: Exception) { status = e.message ?: "操作失败" } }
                                    })
                                if (target.list == "recent")
                                    PrismIconAction("trash", "删除记录", {
                                        trackMenu = null
                                        lifecycleScope.launch { try { library.removeRecent(song) } catch (e: Exception) { status = e.message ?: "删除失败" } }
                                    })
                            }
                        }
                    }
                }
            }
            subjectMenu?.let { subject ->
                val firstFocus = remember { FocusRequester() }
                val saved = libraryState.subjects.any { it.key == subject.key }
                val plugin = activePlugins.firstOrNull { it.id == subject.pluginId }
                PrismContextMenu(subject.title, if (subject.kind == "chart") "榜单" else subject.kindLabel,
                    { subjectMenu = null }, initialFocus = firstFocus) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (plugin != null) PrismAction("打开详情", {
                            subjectMenu = null
                            if (subject.kind == "chart") openChart(plugin, subject.data)
                            else openBrowse(plugin, subject.data, subject.kind)
                        }, Modifier.focusRequester(firstFocus))
                        if (subject.kind != "chart") PrismAction(if (saved) "取消收藏" else "收藏", {
                            subjectMenu = null
                            toggleSubject(subject.pluginId, subject.kind, subject.data, subject.sourceName)
                        }, if (plugin == null) Modifier.focusRequester(firstFocus) else Modifier)
                        if (subject.kind == "chart" && plugin != null) PrismAction("刷新曲目", {
                            subjectMenu = null
                            lifecycleScope.launch {
                                try {
                                    val songs = fetchChart(plugin, subject.data)
                                    chartPreviews = chartPreviews + (subject.data.optString("id") to songs)
                                    status = "已刷新榜单"
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) { status = "刷新失败：${e.message}" }
                            }
                        })
                    }
                }
            }
            collectionMenu?.let { target ->
                var confirming by remember(target) { mutableStateOf(false) }
                var removing by remember(target) { mutableStateOf(false) }
                var error by remember(target) { mutableStateOf("") }
                val firstFocus = remember { FocusRequester() }
                LaunchedEffect(confirming) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }
                val verb = if (target.playlistId != null) "删除歌单" else "取消专辑收藏"
                PrismContextMenu(target.title, "本机 · ${target.songs.size} 首", { if (!removing) collectionMenu = null }, initialFocus = firstFocus) {
                    if (confirming) Text(if (target.playlistId != null) "删除本机歌单？此操作无法撤销。" else "取消这张专辑中 ${target.songs.size} 首歌曲的收藏？", color = c.foreground)
                    if (error.isNotBlank()) Text(error, color = c.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!confirming) {
                            PrismAction("播放", {
                                if (target.songs.isNotEmpty()) { collectionMenu = null; playQueue(target.songs) }
                                else error = "这个歌单还没有歌曲"
                            }, Modifier.focusRequester(firstFocus))
                            PrismAction(verb, { confirming = true })
                        } else {
                            PrismAction("取消", { if (!removing) confirming = false }, Modifier.focusRequester(firstFocus))
                            PrismAction(if (removing) "处理中…" else "确认$verb", {
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
                            })
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
                val firstFocus = remember { FocusRequester() }
                val nameFocus = remember { FocusRequester() }
                val primaryFocus = remember { FocusRequester() }
                Dialog(onDismissRequest = { importDialog = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    LaunchedEffect(Unit) {
                        repeat(2) { withFrameNanos { } }
                        firstFocus.requestFocus()
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(Modifier.width(620.dp).focusGroup().clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                            .background(Brush.verticalGradient(listOf(c.panel, c.background.copy(alpha = .985f))))
                            .border(1.dp, c.line.copy(alpha = .75f), androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                            .padding(horizontal = 30.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("导入歌单或单曲", color = c.foreground, fontSize = 22.sp)
                            /* 两个输入框和主按钮显式连成竖链：几何搜索在三颗并排按钮里会挑中
                               最右边那颗，Down 从名称框直接落到「取消」，主按钮永远够不到。 */
                            Input(importUrl, { importUrl = it }, "歌单、单曲链接或编号",
                                Modifier.fillMaxWidth().focusRequester(firstFocus).focusProperties { down = nameFocus },
                                keyboardType = KeyboardType.Uri, manualKeyboard = true, onSubmit = { nameFocus.requestFocus() })
                            Input(importName, { importName = it }, "歌单名称 · 选填，留空按音源命名",
                                Modifier.fillMaxWidth().focusRequester(nameFocus).focusProperties { up = firstFocus; down = primaryFocus },
                                manualKeyboard = true, onSubmit = { primaryFocus.requestFocus() })
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                PrismAction(if (busy) "导入中…" else "开始导入", {
                                    if (!busy) {
                                        val url = importUrl
                                        importDialog = false
                                        importPlaylist(importName, url)
                                    }
                                }, Modifier.focusRequester(primaryFocus).focusProperties { up = nameFocus }, primary = true)
                                PrismAction("用手机填写", { importDialog = false; openPhoneHub() }, Modifier.focusProperties { up = nameFocus })
                                PrismAction("取消", { importDialog = false }, Modifier.focusProperties { up = nameFocus })
                            }
                            /* 链接由音源的标准导入能力解析，宿主不认平台：这样任何实现了它的订阅都能用。 */
                            Text("认得出平台的链接先交给该平台的音源，认不出则依次尝试其他已启用且支持导入的音源",
                                color = c.muted, fontSize = 11.sp)
                            Text("导入的歌单存在「我的 → 歌单」，也可以在设置 · 推荐里显示到首页；私有歌单需先在设置里填好该音源的 Cookie",
                                color = c.muted, fontSize = 11.sp)
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
                Dialog(onDismissRequest = { phoneDialog = false }) {
                    LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; phoneCloseFocus.requestFocus() }
                    Column(Modifier.background(c.panel, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (phoneRemoteMode) "手机遥控" else "扫码输入配置", color = c.foreground, fontSize = 22.sp)
                        Image(input.qr.asImageBitmap(), contentDescription = if (phoneRemoteMode) "手机遥控二维码" else "手机输入二维码", modifier = Modifier.size(190.dp))
                        /* 一句话就够：这个弹窗下面两颗按钮已经写着「隐藏二维码」「结束遥控」，
                           再用文字复述一遍关窗口会发生什么，只是把提示挤成一段说明。 */
                        Text(
                            "手机需要与电视在同一个局域网",
                            color = c.muted, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrismAction("隐藏二维码", { phoneDialog = false }, Modifier.focusRequester(phoneCloseFocus), primary = true)
                            PrismAction(if (phoneRemoteMode) "结束遥控" else "结束输入", { closePhoneInput() })
                        }
                    }
                }
            }
        }
    }

    @Composable private fun PluginSourceRow(plugin: Plugin, owned: Boolean, onConfigure: () -> Unit, onRemoved: () -> Unit, configureFocus: FocusAnchor, edge: Modifier = Modifier, top: FocusRequester? = null) {
        val c = LocalPrismColors.current
        var menuOpen by remember(plugin.id) { mutableStateOf(false) }
        var confirmRemoval by remember(plugin.id) { mutableStateOf(false) }
        val moreFocus = remember(plugin.id) { FocusRequester() }
        val menuFocus = remember(plugin.id) { FocusRequester() }
        val toggleFocus = remember(plugin.id) { FocusAnchor() }
        val topEdge = Modifier.focusProperties { top?.let { up = it } }
        val scope = rememberCoroutineScope()
        fun closeMenu() {
            menuOpen = false; confirmRemoval = false
            scope.launch { withFrameNanos { }; runCatching { moreFocus.requestFocus() } }
        }
        val labels = plugin.capabilities.labels
        val highlights = (listOf("MV", "扫码登录", "歌单导入", "单曲导入", "推荐歌单", "榜单", "搜索", "歌词")
            .filter { it in labels } + labels).distinct().take(4)
        Column(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(c.panel.copy(alpha = .35f))
            .padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PlatformIcon(plugin.platform, Modifier.size(32.dp))
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(plugin.displayName, color = if (plugin.enabled) c.foreground else c.muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(plugin.metadata.optString("version").takeIf { it.isNotBlank() }?.let { "v$it" }, plugin.metadata.optString("author").takeIf { it.isNotBlank() }, if (plugin.enabled) "已启用" else "已停用").joinToString(" · "),
                        color = if (plugin.enabled) c.accent else c.muted, fontSize = 11.sp,
                    )
                }
                PrismAction("配置", onConfigure, edge.then(topEdge).focusAnchor(configureFocus).focusProperties { right = toggleFocus.requester })
                PrismAction(if (plugin.enabled) "停用" else "启用", {
                    /* 焦点要等重组后的节点，所以必须挂在这一帧的帧时钟上：lifecycleScope 的
                       上下文里没有 MonotonicFrameClock，withFrameNanos 会直接抛异常。 */
                    scope.launch {
                        try { plugins.setEnabled(plugin.id, !plugin.enabled); withFrameNanos { }; toggleFocus.requestIfAttached() }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { status = e.message ?: "操作失败" }
                    }
                }, Modifier.focusAnchor(toggleFocus).then(topEdge).focusProperties {
                    left = configureFocus.requester
                    if (!owned || plugin.updateUrl.isNotBlank() || plugin.enabled && plugin.capabilities.has("search")) right = moreFocus
                })
                if (!owned || plugin.updateUrl.isNotBlank() || plugin.enabled && plugin.capabilities.has("search"))
                    PrismAction("更多", { confirmRemoval = false; menuOpen = true }, Modifier.focusRequester(moreFocus).then(topEdge).focusProperties { left = toggleFocus.requester })
            }
            Text(highlights.joinToString(" · ").ifBlank { "暂无可用能力" } + if (labels.size > highlights.size) " · 另 ${labels.size - highlights.size} 项" else "",
                color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (menuOpen) key(confirmRemoval) {
            PrismContextMenu(if (confirmRemoval) "移除 ${plugin.displayName}？" else plugin.displayName,
                if (confirmRemoval) "本机歌单和收藏会保留" else "音源操作", { closeMenu() }, menuFocus) {
                if (confirmRemoval) {
                    PrismAction("取消", { closeMenu() }, Modifier.focusRequester(menuFocus), primary = true)
                    PrismAction("移除音源", {
                        menuOpen = false
                        lifecycleScope.launch { try { plugins.remove(plugin.id); status = "已移除音源"; onRemoved() } catch (e: Exception) { status = e.message ?: "移除失败" } }
                    })
                } else {
                    var first = true
                    fun nextFocus(): Modifier = if (first) Modifier.focusRequester(menuFocus).also { first = false } else Modifier
                    if (plugin.enabled && plugin.capabilities.has("search")) PrismAction("搜索此音源", {
                        menuOpen = false; selectedPlugin = plugin.id; searchSources = setOf(plugin.id); page = "search"
                    }, nextFocus())
                    if (plugin.updateUrl.isNotBlank()) PrismAction("更新插件", {
                        closeMenu()
                        lifecycleScope.launch { try { showPluginImport(plugins.refresh(plugin.id)) } catch (e: Exception) { status = "更新失败：${e.message}" } }
                    }, nextFocus())
                    if (!owned) PrismAction("移除音源", { confirmRemoval = true }, nextFocus())
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
        val capabilityLabels = plugin.capabilities.labels
        val description = plugin.metadata.optString("description").trim()
        val hasInfo = capabilityLabels.isNotEmpty() || description.isNotBlank()
        val loginOpen = remember(plugin.id) { mutableStateOf(false) }
        var manualOpen by remember(plugin.id) { mutableStateOf(false) }
        var infoOpen by remember(plugin.id) { mutableStateOf(false) }
        var platformOpen by remember(plugin.id) { mutableStateOf(false) }
        var clearCookiesOpen by remember(plugin.id) { mutableStateOf(false) }
        var saving by remember(plugin.id) { mutableStateOf(false) }
        var error by remember(plugin.id) { mutableStateOf("") }
        val backFocus = remember(plugin.id) { FocusRequester() }
        val manualFocus = remember(plugin.id) { FocusRequester() }
        val infoFocus = remember(plugin.id) { FocusRequester() }
        val clearFocus = remember(plugin.id) { FocusRequester() }
        val platformFocus = remember(plugin.id) { FocusRequester() }
        val platformOptionFocus = remember(plugin.id) { FocusRequester() }
        val dialogFocus = remember(plugin.id) { FocusRequester() }
        val fieldFocuses = remember(plugin.id, entries.size) { List(entries.size) { FocusRequester() } }
        val loginFocus = remember(plugin.id) { FocusRequester() }
        val phoneFocus = remember(plugin.id) { FocusRequester() }
        val manualCloseFocus = remember(plugin.id) { FocusRequester() }
        val manualSaveFocus = remember(plugin.id) { FocusRequester() }
        val bottomFocus = remember(plugin.id) { FocusRequester() }
        val scope = rememberCoroutineScope()
        val firstSetting = if (hasInfo) infoFocus else platformFocus
        val firstFocus = when { canLoginQr -> loginFocus; hasFields -> manualFocus; else -> firstSetting }
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
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (canLoginQr || hasFields) {
                Text("账号配置", color = c.foreground, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (canLoginQr) {
                        PrismConfigChoice("扫码登录", "使用对应平台 App 扫码", "qr", { manualOpen = false; loginOpen.value = true },
                            Modifier.weight(1f).focusRequester(loginFocus).focusAnchor(entry).focusProperties {
                                up = backFocus; down = firstSetting; if (hasFields) right = manualFocus
                            }.rememberFocus(memory))
                    }
                    if (hasFields) PrismConfigChoice("手动填写", "使用遥控器或手机输入", "keyboard", {
                        values.keys.toList().forEach { key -> values[key] = plugin.variables.optString(key) }
                        error = ""; manualOpen = true
                    }, Modifier.weight(1f).focusRequester(manualFocus).then(if (!canLoginQr) Modifier.focusAnchor(entry) else Modifier).focusProperties {
                        up = backFocus; down = firstSetting; if (canLoginQr) left = loginFocus
                    }.rememberFocus(memory))
                }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (hasInfo) PrismAction("能力说明", { infoOpen = true; platformOpen = false },
                        Modifier.focusRequester(infoFocus).then(if (!canLoginQr && !hasFields) Modifier.focusAnchor(entry) else Modifier)
                            .focusProperties { up = beforeSettings; down = bottomFocus; right = platformFocus }.rememberFocus(memory))
                    PrismAction("平台 · ${SourcePlatforms.get(plugin.id)?.label ?: "自动识别"} ▾", { platformOpen = !platformOpen; infoOpen = false },
                        Modifier.focusRequester(platformFocus).then(if (!canLoginQr && !hasFields && !hasInfo) Modifier.focusAnchor(entry) else Modifier)
                            .focusProperties {
                                up = beforeSettings; down = if (platformOpen) platformOptionFocus else bottomFocus
                                if (hasInfo) left = infoFocus
                                right = clearFocus
                            }.rememberFocus(memory))
                    PrismAction("清除自动 Cookie", { clearCookiesOpen = true }, Modifier.focusRequester(clearFocus)
                        .focusProperties { left = platformFocus; up = beforeSettings; down = bottomFocus }.rememberFocus(memory))
                }
                if (platformOpen) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(4.dp)) {
                    fun selectPlatform(platform: MusicPlatform?) {
                        SourcePlatforms.set(plugin.id, platform); platformOpen = false
                        scope.launch { withFrameNanos { }; platformFocus.requestFocus() }
                    }
                    item { PrismAction("${if (SourcePlatforms.get(plugin.id) == null) "✓ " else ""}自动识别", { selectPlatform(null) },
                        Modifier.focusRequester(platformOptionFocus).focusProperties { up = platformFocus; down = bottomFocus }.rememberFocus(memory)) }
                    items(MusicPlatform.entries.filterNot { it in setOf(MusicPlatform.LOCAL, MusicPlatform.CUSTOM, MusicPlatform.OTHER) }) { platform ->
                        PlatformAction(platform, "${if (SourcePlatforms.get(plugin.id) == platform) "✓ " else ""}${platform.label}",
                            Modifier.focusProperties { up = platformFocus; down = bottomFocus }.rememberFocus(memory)) { selectPlatform(platform) }
                    }
                }
            }
            if (error.isNotBlank()) Text(error, color = c.accent, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line.copy(alpha = .35f)))
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("配置保存在本机", color = c.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                PrismAction("完成", onBack,
                    Modifier.focusRequester(bottomFocus).focusProperties { up = clearFocus }.rememberFocus(memory), primary = true)
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
                    Spacer(Modifier.height(18.dp))
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        entries.forEachIndexed { position, (field, key) ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(field.optString("title").ifBlank { field.optString("name").ifBlank { key } }, color = c.foreground, fontSize = 14.sp)
                                Input(values[key].orEmpty(), { values[key] = it }, field.optString("hint").ifBlank { "未填写" },
                                    Modifier.fillMaxWidth().focusRequester(fieldFocuses[position]).focusProperties {
                                        up = fieldFocuses.getOrNull(position - 1) ?: phoneFocus
                                        down = fieldFocuses.getOrNull(position + 1) ?: manualSaveFocus
                                    }, secret = credentialKeys.any { key.contains(it, ignoreCase = true) }, manualKeyboard = true,
                                    onSubmit = { (fieldFocuses.getOrNull(position + 1) ?: manualSaveFocus).requestFocus() })
                            }
                        }
                    }
                    if (error.isNotBlank()) Text(error, color = c.accent, fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(top = 10.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.End) {
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
            returned.keys().forEach { key -> if (key in values) values[key] = returned.optString(key) }
            closeQr()
            lifecycleScope.launch {
                try { plugins.setVariables(plugin.id, merged); status = "扫码登录成功，配置已自动保存" }
                catch (e: Exception) { error = "登录成功，但写入配置失败：${e.message}" }
            }
        })
        if (infoOpen) {
            val readFocus = remember { FocusRequester() }
            val closeInfoFocus = remember { FocusRequester() }
            val infoScroll = rememberScrollState()
            val scrollStep = with(LocalDensity.current) { 96.dp.toPx() }
            PrismContextMenu("音源能力说明", "上下键滚动 · 返回键关闭", { infoOpen = false }, readFocus) {
                Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).focusRequester(readFocus)
                    .auraFocus(androidx.compose.foundation.shape.RoundedCornerShape(8.dp), scale = 1f)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false
                        else when (event.key) {
                            Key.DirectionDown -> {
                                if (infoScroll.value < infoScroll.maxValue) scope.launch { infoScroll.scrollBy(scrollStep) }
                                else closeInfoFocus.requestFocus()
                                true
                            }
                            Key.DirectionUp -> {
                                scope.launch { infoScroll.scrollBy(-scrollStep) }; true
                            }
                            else -> false
                        }
                    }.focusable().verticalScroll(infoScroll).padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (capabilityLabels.isNotEmpty()) Text(capabilityLabels.joinToString(" · "), color = c.accent, fontSize = 14.sp)
                    if (description.isNotBlank()) Text(description, color = c.foreground, fontSize = 14.sp)
                    plugin.metadata.optString("author").takeIf { it.isNotBlank() }?.let { Text("作者 · $it", color = c.muted, fontSize = 12.sp) }
                }
                PrismAction("关闭", { infoOpen = false }, Modifier.focusRequester(closeInfoFocus).focusProperties { up = readFocus }, primary = true)
            }
        }
            if (clearCookiesOpen) PrismContextMenu("清除自动 Cookie？", "手动填写的配置会保留", { clearCookiesOpen = false }, dialogFocus) {
                PrismAction("取消", { clearCookiesOpen = false }, Modifier.focusRequester(dialogFocus), primary = true)
                PrismAction("清除", {
                    clearCookiesOpen = false
                    lifecycleScope.launch {
                        try { plugins.client.call("", JSONObject(), "clearCookies", pluginId = plugin.id); status = "已清除自动 Cookie" }
                        catch (e: Exception) { error = e.message ?: "清除失败" }
                    }
                })
            }
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
        saveResume()
        closePhoneInput()
        super.onStop()
    }

    override fun onDestroy() {
        radioJob?.cancel(); radioAdvanceJob?.cancel()
        if (::appUpdates.isInitialized) { updateJob?.cancel(); appUpdates.cancelNetwork() }
        closePhoneInput()
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

@Composable private fun Input(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, secret: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text, manualKeyboard: Boolean = false, onSubmit: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(!manualKeyboard) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val c = LocalPrismColors.current
    LaunchedEffect(editing) { if (manualKeyboard && editing) { withFrameNanos { }; keyboardController?.show() } }
    /* A read-only field can still be handed the IME the moment it gains focus, which is exactly
       when the remote is travelling through with direction keys. Keep it closed until OK asks. */
    LaunchedEffect(focused, editing) {
        if (manualKeyboard && focused && !editing) { withFrameNanos { }; keyboardController?.hide() }
    }
    BasicTextField(value = value, onValueChange = onChange, singleLine = true, readOnly = manualKeyboard && !editing,
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
            if (down && event.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                if (manualKeyboard && !editing) { editing = true; true }
                else if (manualKeyboard && editing) { editing = false; keyboardController?.hide(); true }
                else false
            }
            /* A direction key must always leave the field, editing or not: once the IME has
               been closed the field would otherwise keep eating the keys for a caret the user
               is not even looking at, and the buttons below become unreachable. */
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
                focusManager.moveFocus(FocusDirection.Down); true
            }
            /* Up is eaten the same way (caret line movement), which would make the nav bar
               unreachable from the field. Single line, so there is nowhere to move to. */
            else if (down && event.key == Key.DirectionUp) {
                if (manualKeyboard && editing) { editing = false; keyboardController?.hide() }
                focusManager.moveFocus(FocusDirection.Up); true
            }
            else false
        }.onFocusChanged { focused = it.isFocused; if (!it.isFocused && manualKeyboard) editing = false }.border(1.dp, if (focused) c.accent else c.line, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(c.panel, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(12.dp),
        decorationBox = { inner -> Box(Modifier.fillMaxWidth()) {
            if (value.isEmpty()) Text(hint, color = c.muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = if (manualKeyboard && focused && !editing) 80.dp else 0.dp))
            inner()
            /* Without this the field looks editable and the user waits for a keyboard that will
               never come: say which key starts editing. */
            if (manualKeyboard && focused && !editing) Text("确定键输入", color = c.accent, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterEnd))
        } })
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
