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
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clipToBounds
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import tv.prism.library.Song
import tv.prism.playback.SourceRecovery
import tv.prism.playback.PlaybackOrder
import tv.prism.playback.ActiveQueue
import tv.prism.playback.PlayingSource
import tv.prism.updates.AppUpdates
import tv.prism.sources.Plugins
import tv.prism.library.MusicLibrary
import tv.prism.playback.ResumeStore
import tv.prism.ui.SearchViewModel
import tv.prism.ui.LibraryViewModel
import tv.prism.ui.PluginViewModel
import tv.prism.ui.SettingsViewModel
import tv.prism.playback.VideoSource
import tv.prism.library.SavedSubject
import tv.prism.catalog.SearchSession
import tv.prism.catalog.SearchUpdate
import tv.prism.catalog.SearchSuggestion
import tv.prism.playback.PlaybackMode
import tv.prism.lyrics.LyricLine
import tv.prism.lyrics.LyricStatus
import tv.prism.remote.PhoneSessions
import tv.prism.remote.ContentTask
import tv.prism.sources.PluginReplacement
import tv.prism.catalog.Discovery
import tv.prism.catalog.DiscoverySession
import tv.prism.playback.audioMediaItem
import tv.prism.playback.QmcDecryptionException
import tv.prism.playback.audioStreamLabel
import tv.prism.sources.AppPlugins
import tv.prism.sources.SourcePlatforms
import tv.prism.sources.PluginWorkers
import tv.prism.ui.PrismApp
import tv.prism.ui.widgets.LocalPrismColors
import tv.prism.ui.widgets.prismColors
import tv.prism.ui.widgets.PrismBackground
import tv.prism.ui.widgets.AuraBrand
import tv.prism.lyrics.LyricCalibration
import tv.prism.playback.PlaybackService
import tv.prism.catalog.SearchResultsPage
import tv.prism.sources.Plugin
import tv.prism.sources.requestRevision
import tv.prism.catalog.isAlbumShaped
import tv.prism.ui.widgets.FocusAnchor
import tv.prism.ui.widgets.PrismShelfHeader
import tv.prism.ui.widgets.PrismAction
import tv.prism.ui.widgets.focusAnchor
import tv.prism.ui.widgets.RecommendationSkeleton
import tv.prism.ui.widgets.PrismSheetCard
import tv.prism.library.primaryKeys
import tv.prism.playback.qualityAttempts
import tv.prism.playback.qualityOptions
import tv.prism.playback.UnsupportedAudioEncryptionException
import tv.prism.playback.audioQualityLabel
import tv.prism.playback.recordingDurationMatches
import tv.prism.lyrics.Lyrics
import tv.prism.lyrics.LyricPayload
import tv.prism.lyrics.LyricProbe
import tv.prism.lyrics.LyricProbeState
import tv.prism.lyrics.LyricLookup
import tv.prism.lyrics.LyricSourcePrefs
import tv.prism.lyrics.LyricSources
import tv.prism.lyrics.withoutOpeningHeading
import tv.prism.sources.MusicPlatform
import tv.prism.sources.SyncResult
import tv.prism.playback.PlaybackPhase
import tv.prism.playback.playbackAttemptCommand
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import tv.prism.playback.PlaybackResume
import tv.prism.ui.playback.lyricFontLevels
import tv.prism.remote.PhoneRemoteSnapshot
import tv.prism.library.LibraryBackup
import tv.prism.ui.widgets.requestIfAttached
import tv.prism.ui.playback.PrismLoadingSkeleton

internal class PlaybackRecovery(val sources: SourceRecovery, val onFailure: () -> Unit) {
    var ready: CompletableDeferred<Long>? = null
    var mediaId = ""
    var position = 0L
}

internal class QualitySwitch(val songKey: String, var position: Long) {
    var mediaId = ""
    var ready: CompletableDeferred<Long>? = null
    var replaced = false
}

internal const val DISCOVERY_FRESH_MS = 15 * 60_000L
internal data class CardFocusRow(val cards: List<FocusRequester>, val horizontal: LazyListState? = null)
internal data class CollectionMenuTarget(val title: String, val songs: List<Song>, val playlistId: String? = null)
internal data class TrackMenuTarget(val song: Song, val list: String? = null, val originFocus: String = "")

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : ComponentActivity() {
    internal lateinit var appUpdates: AppUpdates
    internal var repeatedDirection by mutableIntStateOf(KeyEvent.KEYCODE_UNKNOWN)
    internal var lastDirectionRepeatAt = 0L
    internal var firstFrameDrawn by mutableStateOf(false)
    internal var updateDialogOpen by mutableStateOf(false)
    internal var updateJob: Job? = null

    internal fun checkAppUpdates() {
        updateDialogOpen = true
        if (updateJob?.isActive == true) return
        updateJob = lifecycleScope.launch { appUpdates.check(force = true) }
    }
    internal fun cancelAppUpdate() {
        updateJob?.cancel(); appUpdates.cancelNetwork()
    }
    internal fun downloadAppUpdate() {
        if (updateJob?.isActive == true) return
        updateJob = lifecycleScope.launch { appUpdates.download() }
    }

    internal lateinit var plugins: Plugins
    internal val discoverySession by lazy { DiscoverySession(this, lifecycleScope) { plugins.client } }
    internal lateinit var library: MusicLibrary
    internal lateinit var resumeStore: ResumeStore
    /* Position to restore once the controller connects; -1 means nothing is waiting. */
    internal var resumePending = -1L
    internal var exitingAfterResumeSave = false
    internal var connection: ListenableFuture<MediaController>? = null
    internal var controller by mutableStateOf<MediaController?>(null)
    internal var status by mutableStateOf("正在连接播放器…")
    internal var playbackPhase: PlaybackPhase
        get() = ActiveQueue.phase
        set(value) { ActiveQueue.phase = value }
    internal var playbackDetail: String
        get() = ActiveQueue.failureDetail
        set(value) { ActiveQueue.failureDetail = value }

    /* 持焦节点被换掉后按键会整页落空；记一次，由界面按当前页面补落焦。 */
    internal var focusRecovery by mutableStateOf(0)
    /* 被吞掉的那次按键，落焦后重放一次。 */
    internal var replayKey by mutableStateOf<KeyEvent?>(null)
    internal var replaying = false
    internal val replayableKeys = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    )

    internal fun reportPlayback(message: String) {
        status = message
    }
    internal var page by mutableStateOf("home")
    internal val searchViewModel by viewModels<SearchViewModel>()
    internal val libraryViewModel by viewModels<LibraryViewModel>()
    internal val pluginViewModel by viewModels<PluginViewModel>()
    internal val settingsViewModel by viewModels<SettingsViewModel>()
    internal var playerOpen by mutableStateOf(false)
    internal var activeMv by mutableStateOf<VideoSource?>(null)
    internal var playerScreenOff by mutableStateOf(false)
    // Retain the player during the fade; remove it once the black curtain is opaque.
    internal var screenOffSettled by mutableStateOf(true)
    internal val wakeKeys = mutableSetOf<Int>()
    internal var selectedPlugin by mutableStateOf("")
    internal var searchSources: Set<String>
        get() = searchViewModel.state.value.sources
        set(value) { searchViewModel.setSources(value) }
    internal var recommendationPluginId by mutableStateOf("")
    internal var chartPluginId by mutableStateOf("")
    internal var radioLoading by mutableStateOf(false)
    internal var radioStatus by mutableStateOf("")
    internal var radioJob: Job? = null
    internal var radioAdvanceJob: Job? = null
    internal var radioRetryAt = 0L
    internal var radioMenuOpen by mutableStateOf(false)
    internal val radioSheets = mutableListOf<String>()
    internal val results get() = searchViewModel.state.value.songs
    internal var searchType: String
        get() = searchViewModel.state.value.type
        set(value) { searchViewModel.setType(value) }
    internal val entities get() = searchViewModel.state.value.entities
    internal var searchTerm: String
        get() = searchViewModel.state.value.term
        set(value) { searchViewModel.setTerm(value) }
    internal var recentArtists by mutableStateOf<List<SavedSubject>>(emptyList())
    internal var searchHistory by mutableStateOf<Map<String, List<String>>>(emptyMap())
    internal val hasMore get() = searchViewModel.state.value.hasMore
    internal val searchSession by lazy {
        SearchSession(lifecycleScope, plugins.client, getSharedPreferences("search", MODE_PRIVATE)) { update ->
            when (update) {
                is SearchUpdate.Page -> searchViewModel.setPage(update.value)
                is SearchUpdate.Term -> searchTerm = update.value
                is SearchUpdate.Notice -> status = update.value
                is SearchUpdate.Running -> busy = update.value
                SearchUpdate.Unavailable -> focusSearchResults = false
            }
        }
    }
    internal val hotSearchCache = linkedMapOf<String, Pair<Long, List<SearchSuggestion>>>()
    internal var focusSearchResults by mutableStateOf(false)
    internal var browseTitle by mutableStateOf("")
    internal var browseReturnPage by mutableStateOf("home")
    internal var browseKind by mutableStateOf("")
    internal var browseItem by mutableStateOf<JSONObject?>(null)
    internal var browsePluginId by mutableStateOf("")
    internal var browseSongs by mutableStateOf<List<Song>>(emptyList())
    internal var browsePage by mutableIntStateOf(1)
    internal var browseMore by mutableStateOf(false)
    internal var browseStatus by mutableStateOf("")
    internal var browseLoading by mutableStateOf(false)
    internal var browseJob: Job? = null
    internal var browseGeneration = 0
    /* An artist page carries its albums above the songs. The info object is a Prism-only extra
       from our own plugins (bio, photo, publish date); standard plugins simply omit it. */
    internal var browseAlbums by mutableStateOf<List<JSONObject>>(emptyList())
    internal var browseAlbumStatus by mutableStateOf("")
    internal var browseAlbumLoading by mutableStateOf(false)
    internal var browseAlbumJob: Job? = null
    internal var browseInfo by mutableStateOf<JSONObject?>(null)
    /* Detail pages stack: an album opened from an artist returns there, and a detail opened
       from the player returns to the player. */
    internal val browseStack = ArrayDeque<BrowseReturn>()
    /* The card that opened a detail, so Back can put the remote back on it instead of
       dropping focus on the first row. */
    internal var pendingReturnFocus by mutableStateOf("")
    internal var queueOpen by mutableStateOf(false)
    internal var subjectJob: Job? = null
    internal var queue: List<Song>
        get() = ActiveQueue.songs
        set(value) { ActiveQueue.songs = value }
    internal var queueIndex: Int
        get() = ActiveQueue.index
        set(value) { ActiveQueue.index = value }
    internal var playMode by mutableStateOf(PlaybackMode.SEQUENTIAL)
    internal var quality by mutableStateOf("standard")
    internal var autoSourceFallback by mutableStateOf(false)
    internal var recovery: PlaybackRecovery? = null
    internal var streamFormat by mutableStateOf("")
    /* The tier of the stream that is actually playing, blank until a source resolves one.
       The preference is what to ask for; this is what came back after the ladder ran. */
    internal var playingQuality by mutableStateOf("")
    /* A tier the plugin answered with no URL is one it does not serve; offering it in the
       player pill again would promise a stream that cannot play. Keyed by song. */
    internal val unsupportedTiers = mutableStateMapOf<String, Set<String>>()
    // Per-song session overrides leave the global quality preference unchanged.
    internal val songTier = mutableStateMapOf<String, String>()
    internal fun tierFor(song: Song?): String = song?.let { songTier[it.key] } ?: quality
    internal val tierCache = mutableMapOf<String, List<String>>()
    internal var availableTiers by mutableStateOf<List<String>>(emptyList())
    /* 正在为哪一档取流。面板靠它把「按了」和「切好了」分开显示，也靠它吃掉连发键。 */
    internal var qualitySwitching by mutableStateOf("")
    /* 上一次切换为什么没成。面板里那一行提示的生命周期比状态条短，四秒后自己散。 */
    internal var qualityNotice by mutableStateOf("")
    internal var qualityNoticeJob: Job? = null
    internal var playing by mutableStateOf(false)
    internal var playbackActive by mutableStateOf(false)
    internal var exitArmedAt = 0L
    internal var playbackState by mutableIntStateOf(Player.STATE_IDLE)
    internal val busyState = mutableStateOf(false)
    internal var busy by busyState
    internal val subscriptionUpdateProgress = mutableStateOf<Pair<String, String>?>(null)
    internal var resolving: Job? = null
    /* A tier swap is not a track change; it gets its own job so it can never cancel, or be
       cancelled by, the resolver that put the current song on screen. */
    internal var streamSwap: Job? = null
    internal var qualitySwap: QualitySwitch? = null
    /* One re-mint per track per failure: a second 403 on a freshly minted address means the
       source itself is refusing, and asking a third time would only spin. */
    internal var remintKey = ""
    /* The song the player actually holds. Re-picking it — from an album page, a shelf or the
       queue — has to keep the audio running instead of starting the track over. */
    internal var loadedKey = ""
    /* Automatic skips share one failure pass; manual selection or normal track advancement resets it. */
    internal val failedSongs = mutableSetOf<String>()
    internal var autoSkipToken = 0

    internal var lyricJob: Job? = null
    internal var lyrics by mutableStateOf<List<LyricLine>>(emptyList())
    internal var lyricStatus by mutableStateOf<LyricStatus>(LyricStatus.Empty())
    internal var lyricToken = 0
    /* 歌词字号档位与副歌词开关全机共享；校准是当前这首歌的（换歌即重载）。 */
    internal var lyricFontLevel by mutableStateOf("标准")
    internal var hideHeading by mutableStateOf(true)
    internal var subLyrics by mutableStateOf(true)
    internal var lyricCalibrationMs by mutableStateOf(0L)
    internal var lyricLrclib by mutableStateOf(false)
    internal var lyricKugou by mutableStateOf(false)
    internal var lyricNetease by mutableStateOf(false)
    internal var lyricSourceLabel by mutableStateOf("")
    internal var lyricActiveSource by mutableStateOf("")
    internal var lyricSourceOverride by mutableStateOf<String?>(null)
    internal var lyricOverrideKey = ""
    internal var lyricProbes by mutableStateOf<Map<String, LyricProbe>>(emptyMap())
    private var lyricProbeJob: Job? = null
    private var lyricProbeKey = ""
    internal var positionMs by mutableLongStateOf(0L)
    private var pendingSeek: Job? = null
    internal var durationMs by mutableLongStateOf(0L)
    internal var theme by mutableStateOf("gold")
    internal var showMiniLyrics by mutableStateOf(false)
    internal var reduceMotion by mutableStateOf(false)
    /* 0 is automatic; PluginWorkers turns it into a count for this device. */
    internal var pluginWorkerLimit by mutableIntStateOf(0)
    /* Home shelves are visible unless the user hides them, so a new favourite list —
       an imported playlist most of all — shows up on its own. */
    internal var homeShelfHidden by mutableStateOf(setOf<String>())
    /* The import runs outside the composable that owns 我的's tab state, so the playlist it
       writes comes across as an id to open rather than a page nobody can find. */
    internal var playlistToShow by mutableStateOf<String?>(null)
    internal val phoneSessions = PhoneSessions(this) { status = it }
    internal val contentTasks = mutableStateMapOf<String, ContentTask>()
    internal var contentTaskRevision = 0L
    internal var phoneQueueOffset = -1
    internal var restoringBackup by mutableStateOf(false)
    internal var restoreMessage by mutableStateOf("")
    internal var restoreFailed by mutableStateOf(false)
    internal val pluginReplacements = mutableStateListOf<PluginReplacement>()
    internal var lastPlayingSample = 0L
    internal val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            playing = playWhenReady
        }
        override fun onTracksChanged(tracks: Tracks) {
            if (!ActiveQueue.attempt.accepts(controller?.currentMediaItem?.mediaId)) return
            val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            val format = group?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
            streamFormat = format?.let(::audioStreamLabel).orEmpty()
        }
    }

    private fun installStream(player: Player, source: MediaItem, at: Long): String {
        pendingSeek?.cancel()
        val item = ActiveQueue.attempt.begin(source)
        playbackState = Player.STATE_BUFFERING
        playbackActive = false
        positionMs = at
        if (playbackPhase != PlaybackPhase.Switching) playbackPhase = PlaybackPhase.Buffering
        player.setMediaItem(item, at)
        return item.mediaId
    }

    internal fun resetStream(player: Player?, pause: Boolean = true) {
        pendingSeek?.cancel()
        ActiveQueue.attempt.clear()
        playbackState = Player.STATE_IDLE
        playbackActive = false
        loadedKey = ""
        if (pause) player?.pause()
        player?.stop()
        player?.clearMediaItems()
    }

    internal fun trustedPosition(player: Player? = controller): Long {
        val p = player ?: return positionMs
        if (pendingSeek?.isActive == true || resumePending >= 0 || playbackPhase.holdControls ||
            !ActiveQueue.attempt.accepts(p.currentMediaItem?.mediaId) ||
            (playbackState != Player.STATE_READY && playbackState != Player.STATE_ENDED) ||
            (p.playbackState != Player.STATE_READY && p.playbackState != Player.STATE_ENDED) ||
            qualitySwap?.replaced == true || recovery?.ready != null) return positionMs
        return p.currentPosition.coerceAtLeast(0L)
    }

    internal fun onAttemptEvent(data: Bundle) {
        val id = data.getString("mediaId") ?: return
        if (!ActiveQueue.attempt.accepts(id)) return
        data.getBundle("error")?.let {
            handleStreamError(id, PlaybackException.fromBundle(it), data.getString("httpCode"), data.getString("protection"))
            return
        }
        if (data.getBoolean("pause")) {
            if (controller?.playWhenReady == false && recovery?.mediaId == id) pauseRecoveryIfPending()
            return
        }
        if (data.containsKey("active")) {
            onAttemptActive(data.getBoolean("active"))
            return
        }
        val state = data.getInt("state")
        playbackState = state
        val duration = data.getLong("duration").coerceAtLeast(0L)
        val pendingRecovery = recovery?.takeIf { it.mediaId == id }?.ready
        val pendingQuality = qualitySwap?.takeIf { it.mediaId == id }?.ready
        when (state) {
            Player.STATE_BUFFERING -> if (playbackPhase == PlaybackPhase.Ready) playbackPhase = PlaybackPhase.Buffering
            Player.STATE_READY, Player.STATE_ENDED -> {
                if (duration > 0) durationMs = duration
                if (state == Player.STATE_READY || duration > 0) {
                    pendingRecovery?.complete(duration)
                    pendingQuality?.complete(duration)
                }
                if (pendingRecovery != null || pendingQuality != null || playbackPhase == PlaybackPhase.Failed) return
                if (state == Player.STATE_READY) {
                    if (qualitySwap == null || playbackPhase != PlaybackPhase.Switching) playbackPhase = PlaybackPhase.Ready
                } else if (qualitySwap == null) finishCurrentTrack()
            }
        }
    }

    private fun onAttemptActive(active: Boolean) {
        playbackActive = active
        if (!active) { lastPlayingSample = 0L; return }
        if (recovery?.ready != null || qualitySwap?.ready != null) return
        failedSongs.clear()
        if (ActiveQueue.radio) {
            queue.getOrNull(queueIndex)?.let { song ->
                val key = Discovery.radioIdentity(song)
                ActiveQueue.radioRecent = (ActiveQueue.radioRecent.filterNot { it == key } + key).takeLast(80)
            }
            ensureRadioTail()
        }
    }

    private fun finishCurrentTrack() {
        if (playbackState != Player.STATE_ENDED || queue.isEmpty() || playbackPhase == PlaybackPhase.Failed ||
            playbackPhase == PlaybackPhase.Finished) return
        ActiveQueue.listenKey = ""
        when {
            ActiveQueue.radio -> step(1)
            playMode == PlaybackMode.REPEAT_ONE -> {
                val player = controller
                if (player != null && ActiveQueue.attempt.accepts(player.currentMediaItem?.mediaId) &&
                    player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) &&
                    player.isCurrentMediaItemSeekable
                ) {
                    positionMs = 0L
                    lastPlayingSample = 0L
                    player.seekTo(0L)
                    player.play()
                    playbackState = player.playbackState
                    playbackPhase = if (playbackState == Player.STATE_READY) PlaybackPhase.Ready else PlaybackPhase.Buffering
                } else {
                    playAt(queueIndex, openPlayer = false, restart = true, sourceSong = ActiveQueue.source?.song)
                }
            }
            playMode == PlaybackMode.SEQUENTIAL && queueIndex == queue.lastIndex -> {
                positionMs = durationMs
                playbackPhase = PlaybackPhase.Finished
                controller?.pause()
                playing = false
                reportPlayback(PlaybackPhase.Finished.message)
            }
            else -> step(1) // Shuffle wraps within the current random order.
        }
    }

    private fun handleStreamError(id: String, error: PlaybackException, httpCode: String?, protection: String?) {
        if (!ActiveQueue.attempt.accepts(id)) return
        val player = controller ?: return
        val at = trustedPosition()
        positionMs = at
        loadedKey = ""
        val prepareFailure = protection?.let(::QmcDecryptionException) ?: error
        qualitySwap?.takeIf { it.mediaId == id }?.ready?.let {
            it.completeExceptionally(prepareFailure)
            return
        }
        recovery?.takeIf { it.mediaId == id }?.ready?.let {
            it.completeExceptionally(prepareFailure)
            return
        }
        if (qualitySwap != null) {
            qualitySwap = null
            qualitySwitching = ""
            streamSwap?.cancel()
        }
        val original = queue.getOrNull(queueIndex) ?: return
        val streamSong = playbackSong() ?: original
        val wasPlaying = player.playWhenReady
        if (error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) {
            pendingSeek?.cancel()
            showPlaybackFailure(original, "设备暂时无法输出声音")
            return
        }
        // Refresh an expired URL once before the usual failure recovery.
        if (recovery == null && httpCode in setOf("403", "404", "410") && original.pluginId != "local" &&
            remintKey != original.key) {
            val owner = plugins.state.value.plugins.firstOrNull { it.id == streamSong.pluginId }
            if (owner != null) {
                remintKey = original.key
                val tier = playingQuality.ifBlank { quality }
                streamSwap?.cancel()
                playbackPhase = PlaybackPhase.Switching
                streamSwap = lifecycleScope.launch {
                    val source = try { plugins.mediaSource(owner, streamSong, tier, refresh = true) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { null }
                    if (!ActiveQueue.attempt.accepts(id)) return@launch
                    val replacement = source?.let { runCatching { audioMediaItem(original, it) }.getOrNull() }
                    if (replacement == null) {
                        handleStreamError(id, error, httpCode, protection)
                        return@launch
                    }
                    playingQuality = source.optString("quality").ifBlank { tier }
                    loadedKey = original.key
                    installStream(player, replacement, positionMs)
                    player.prepare()
                }
                return
            }
        }
        if (autoSourceFallback && wasPlaying && original.pluginId != "local") {
            val existing = recovery?.takeIf { it.sources.original.key == original.key }
            val sources = existing?.sources ?: SourceRecovery(plugins, original, streamSong, tierFor(streamSong))
            val pass = existing ?: PlaybackRecovery(sources) {
                val detail = sources.protectionFailure ?: sources.failureDetail ?: "未找到可播放的同版本"
                advanceAfterFailure(original, detail)
            }.also { recovery = it }
            if (existing != null) pass.sources.failed(prepareFailure)
            playbackPhase = PlaybackPhase.Switching
            continueRecovery(pass, at)
            return
        }
        val reason = when {
            protection != null -> protection
            httpCode != null -> "音频地址不可访问（HTTP $httpCode）"
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "音频地址请求失败"
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败"
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接超时"
            else -> "当前音频暂时无法播放"
        }
        advanceAfterFailure(original, reason, wasPlaying && (autoSourceFallback || ActiveQueue.radio))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pluginLoad = lifecycleScope.async(Dispatchers.IO) { AppPlugins.get(applicationContext) }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        SourcePlatforms.initialize(this)
        appUpdates = (application as PrismApplication).appUpdates
        resumeStore = ResumeStore(this)
        val restoreQueue = queue.isEmpty()
        val localLoad = lifecycleScope.async(Dispatchers.IO) {
            MusicLibrary(applicationContext) to if (restoreQueue) resumeStore.read() else null
        }
        quality = getSharedPreferences("playback", MODE_PRIVATE).getString("quality", "standard") ?: "standard"
        autoSourceFallback = getSharedPreferences("playback", MODE_PRIVATE).getBoolean(SourceRecovery.PREFERENCE, false)
        theme = getSharedPreferences("appearance", MODE_PRIVATE).getString("theme", "gold") ?: "gold"
        showMiniLyrics = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("mini_lyrics", false)
        reduceMotion = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("reduce_motion", false)
        lyricFontLevel = getSharedPreferences("appearance", MODE_PRIVATE).getString("lyric_font_level", "标准") ?: "标准"
        hideHeading = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("hide_heading", true)
        subLyrics = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("sub_lyrics", true)
        LyricSourcePrefs.migrateMode(this)
        lyricLrclib = LyricSourcePrefs.lrclib(this)
        lyricKugou = LyricSourcePrefs.kugou(this)
        lyricNetease = LyricSourcePrefs.netease(this)
        pluginWorkerLimit = PluginWorkers.saved(this)
        searchHistory = loadSearchHistory()
        recentArtists = loadRecentArtists()
        playMode = readPlaybackMode()
        homeShelfHidden = getSharedPreferences("home", MODE_PRIVATE).getStringSet("hidden_shelves", null)?.toSet() ?: emptySet()
        recommendationPluginId = getSharedPreferences("home", MODE_PRIVATE).getString("recommendation_plugin", "") ?: ""
        chartPluginId = getSharedPreferences("home", MODE_PRIVATE).getString("chart_plugin", "") ?: ""
        var ready by mutableStateOf(false)
        setContent {
            MaterialTheme {
                if (ready) PrismApp()
                else CompositionLocalProvider(LocalPrismColors provides prismColors(theme)) {
                    PrismBackground {
                        AuraBrand(Modifier.align(Alignment.Center))
                    }
                }
            }
        }
        lifecycleScope.launch {
            val (loadedLibrary, saved) = localLoad.await()
            library = loadedLibrary
            // The service may already own the queue when the activity is recreated.
            if (queue.isEmpty() && saved != null) {
                queue = saved.songs
                queueIndex = saved.index
                ActiveQueue.radio = saved.radio
                ActiveQueue.radioRecent = saved.radioRecent
                ActiveQueue.order = ActiveQueue.order.restored(saved.shuffleOrder, saved.index,
                    playMode == PlaybackMode.SHUFFLE && !saved.radio)
                positionMs = saved.position
                if (saved.finished) {
                    durationMs = saved.position
                    playbackPhase = PlaybackPhase.Finished
                } else resumePending = saved.position
            }
            plugins = pluginLoad.await()
            ready = true
        }
    }

    internal fun startPlaybackAndMaintenance() {
        if (firstFrameDrawn || isDestroyed) return
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
        lifecycleScope.launch {
            var nextResumeSave = android.os.SystemClock.elapsedRealtime() + 15_000L
            while (isActive) {
                val p = controller
                /* While a restored track is still being prepared, the controller reports 0 and
                   would overwrite the saved position with it. */
                if (p != null && ActiveQueue.attempt.accepts(p.currentMediaItem?.mediaId)) {
                    positionMs = trustedPosition(p)
                    val duration = p.duration
                    if (duration > 0 && playbackState == Player.STATE_READY && qualitySwap?.replaced != true && recovery?.ready == null) {
                        if (durationMs != duration) durationMs = duration
                    }
                    if (ActiveQueue.radio && p.isPlaying) ensureRadioTail()
                }
                sampleListen(p)
                val now = android.os.SystemClock.elapsedRealtime()
                if (now >= nextResumeSave) {
                    saveResume()
                    nextResumeSave = now + 15_000L
                }
                val foreground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                val playerVisible = playerOpen && !playerScreenOff && activeMv == null
                val miniVisible = !playerOpen && activeMv == null && queue.getOrNull(queueIndex) != null
                delay(if (foreground && (playerVisible || miniVisible)) 500 else 1_000)
            }
        }
    }

    internal suspend fun sampleListen(player: Player?) {
        val song = queue.getOrNull(queueIndex)
        if (player?.isPlaying == true && song != null && (song.pluginId != "local" || song.data.optBoolean("persisted")) && song.key == loadedKey && resolving?.isActive != true) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (ActiveQueue.listenKey != song.key) {
                ActiveQueue.listenKey = song.key; ActiveQueue.listenMs = 0; ActiveQueue.listenCounted = false
                lastPlayingSample = 0
            }
            if (!ActiveQueue.listenCounted && lastPlayingSample > 0)
                ActiveQueue.listenMs += (now - lastPlayingSample).coerceIn(0, 1000)
            lastPlayingSample = now
            if (!ActiveQueue.listenCounted && ActiveQueue.listenMs >= 30_000) {
                ActiveQueue.listenCounted = true
                try { library.recordListen(song, System.currentTimeMillis()) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { status = e.message ?: "收听记录保存失败" }
            }
        } else lastPlayingSample = 0
    }

    internal fun connect(retryPlayback: Boolean = false) {
        connection?.let(MediaController::releaseFuture)
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (this@MainActivity.controller !== controller) return
                    this@MainActivity.controller = null
                    playbackPhase = PlaybackPhase.Failed
                    playbackDetail = "播放器连接中断，按播放重新连接"
                    reportPlayback(playbackDetail)
                }

                override fun onCustomCommand(controller: MediaController, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (this@MainActivity.controller === controller && command == playbackAttemptCommand) {
                        onAttemptEvent(args)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }).buildAsync()
        connection = future
        future.addListener({
            if (connection !== future || isDestroyed) return@addListener
            try {
                val player = future.get()
                controller = player
                player.addListener(listener)
                playing = player.playWhenReady
                playbackActive = player.isPlaying
                playbackState = player.playbackState
                if (ActiveQueue.attempt.accepts(player.currentMediaItem?.mediaId)) {
                    loadedKey = queue.getOrNull(queueIndex)?.key.orEmpty()
                    listener.onTracksChanged(player.currentTracks)
                    if (playbackPhase == PlaybackPhase.Connecting) {
                        playbackPhase = if (playbackState == Player.STATE_READY) PlaybackPhase.Ready else PlaybackPhase.Buffering
                    }
                    if (playbackState == Player.STATE_ENDED) finishCurrentTrack()
                } else if (playbackPhase == PlaybackPhase.Connecting) playbackPhase = PlaybackPhase.Idle
                /* Restore paused: reopening the app should show where you stopped, not start
                   talking to the room on its own. */
                val at = resumePending
                if (at >= 0 && queue.isNotEmpty()) {
                    resumePending = -1
                    playAt(queueIndex, openPlayer = false, autoplay = retryPlayback, startAt = at)
                } else if (retryPlayback && queue.isNotEmpty()) {
                    playAt(queueIndex, openPlayer = false, startAt = positionMs, sourceSong = playbackSong())
                }
            } catch (e: Exception) {
                playbackPhase = PlaybackPhase.Failed
                playbackDetail = "播放器连接失败：${e.cause?.message ?: e.message}"
                reportPlayback(playbackDetail)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    internal fun searchUsingSubscription(url: String) {
        val current = plugins.state.value
        require(url in current.subscriptions) { "订阅已移除" }
        searchUsingSources(current.plugins.filter { it.origin == url || (it.origin.isBlank() && it.url == url) }.mapTo(linkedSetOf()) { it.id })
    }

    internal fun searchUsingSources(ids: Set<String>) {
        check(!busy) { "请等待当前操作完成后再切换音源" }
        val sources = plugins.state.value.plugins.filter { it.id in ids && it.enabled && it.capabilities.has("search") }
        // An empty filter means ALL sources; never turn a disabled/removed group into that scope.
        require(sources.isNotEmpty()) { "没有已启用且支持搜索的音源" }
        searchSources = sources.mapTo(linkedSetOf()) { it.id }
        selectedPlugin = sources.first().id
        val types = searchTypeChoices(sources)
        if (types.none { it.first == searchType }) searchType = types.firstOrNull()?.first ?: "music"
        searchViewModel.clearResults()
        page = "search"; playerOpen = false; focusSearchResults = false
        if (searchTerm.isNotBlank()) search(searchTerm, focusResults = false)
    }

    internal fun search(query: String, append: Boolean = false, focusResults: Boolean = true, navigating: Boolean = false) {
        val requestedType = searchType
        val sources = searchSources
        searchSession.search(
            query, requestedType,
            plugins.state.value.plugins.filter { it.enabled && (sources.isEmpty() || it.id in sources) },
            append, navigating, deferSubmission = navigating && !focusResults,
            initialPage = SearchResultsPage(results, entities, searchViewModel.nextPages),
            onAccepted = {
                if (!append) {
                    val term = query.trim()
                    val previous = searchHistory[requestedType].orEmpty()
                    val updated = (listOf(term) + previous.filterNot { it.equals(term, ignoreCase = true) }).take(30)
                    if (updated != previous) {
                        searchHistory = searchHistory + (requestedType to updated)
                        saveSearchHistory()
                    }
                    focusSearchResults = focusResults
                }
            },
        )
    }

    internal fun captureBrowse(): BrowseSnapshot? = browseItem?.let { item ->
        BrowseSnapshot(browseTitle, browseKind, item, browsePluginId, browseSongs, browsePage,
            browseMore, browseStatus, browseAlbums, browseInfo)
    }

    internal fun restoreBrowse(snapshot: BrowseSnapshot) {
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
    internal fun leaveBrowse() {
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

    internal fun openBrowse(plugin: Plugin, item: JSONObject, kind: String, originFocus: String? = null) {
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

    internal fun loadBrowse(append: Boolean) {
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

    internal fun loadBrowseAlbums() {
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

    internal fun supportsSubjectSearch(plugin: Plugin?, kind: String): Boolean {
        val types = plugin?.metadata?.optJSONArray("supportedSearchType") ?: return plugin != null
        return (0 until types.length()).any { types.optString(it) == kind }
    }

    /* Rows above the first song in the detail LazyColumn: the hero, an optional artist album
       shelf (header + one row), then the song shelf header. Needed to scroll back to a row. */
    internal fun detailSongRowOffset(): Int =
        2 + if (browseKind == "artist" && (browseAlbums.isNotEmpty() || browseAlbumStatus.isNotBlank())) 2 else 0

    internal fun normalizedName(value: String): String =
        value.lowercase().replace(Regex("[\\s·・/\\\\|（）()\\[\\]【】《》<>-]"), "")

    internal fun leaveRadio() {
        if (ActiveQueue.radio && playMode == PlaybackMode.SHUFFLE)
            ActiveQueue.order = ActiveQueue.order.randomized(queueIndex)
        ActiveQueue.radio = false
        radioJob?.cancel(); radioAdvanceJob?.cancel()
    }

    internal fun playQueue(songs: List<Song>, index: Int = 0, radio: Boolean = false) {
        if (songs.isEmpty()) return
        if (!radio) leaveRadio()
        ActiveQueue.replace(songs, index, radio, playMode == PlaybackMode.SHUFFLE)
        playAt(index, restart = true)
    }

    internal fun playCollection(songs: List<Song>, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        updatePreference("mode", (if (shuffle) PlaybackMode.SHUFFLE else PlaybackMode.SEQUENTIAL).id)
        playQueue(songs, if (shuffle) songs.indices.random() else 0)
    }

    /* A single pick joins the listening context instead of replacing it: insert right after
       the current track and play it, so 上一首 is still what you were hearing. */
    internal fun playSingle(song: Song) {
        leaveRadio()
        if (queue.isEmpty()) { playQueue(listOf(song)); return }
        playAt(ActiveQueue.placeForImmediatePlayback(song))
    }

    internal fun playAlternateSource(song: Song) {
        if (controller == null) {
            reportPlayback("播放器未连接")
            return
        }
        radioAdvanceJob?.cancel()
        if (queue.isEmpty()) {
            playQueue(listOf(song), radio = ActiveQueue.radio)
            return
        }
        val inserted = ActiveQueue.enqueue(song, next = true)
        val target = if (inserted) queue.indexOfFirst { it.key == song.key } else queueIndex
        playAt(target)
    }

    internal fun enqueueSong(song: Song, next: Boolean) {
        if (!ActiveQueue.enqueue(song, next)) {
            status = "这首歌已是当前曲目"
            return
        }
        saveResume()
        status = "${if (next) "已设为下一首" else "已添加到播放队列"}：${song.title}"
    }

    internal fun removeQueueItem(index: Int) {
        if (index !in queue.indices) return
        val autoplay = controller?.playWhenReady == true
        val removingCurrent = index == queueIndex
        val stopAtTail = removingCurrent && index == queue.lastIndex && playMode == PlaybackMode.SEQUENTIAL && !ActiveQueue.radio
        val following = if (removingCurrent && playMode == PlaybackMode.SHUFFLE && !ActiveQueue.radio && queue.size > 1)
            nextIndex(1) else null
        queue = queue.toMutableList().also { it.removeAt(index) }
        when {
            queue.isEmpty() || stopAtTail -> {
                autoSkipToken++
                resolving?.cancel(); streamSwap?.cancel(); lyricJob?.cancel(); lyricToken++
                qualitySwap = null; qualitySwitching = ""
                recovery?.ready?.cancel(); recovery = null
                leaveRadio()
                queueIndex = queue.lastIndex
                ActiveQueue.source = null
                lyrics = emptyList(); lyricStatus = LyricStatus.Empty()
                positionMs = 0; durationMs = 0
                playbackPhase = PlaybackPhase.Idle
                playbackDetail = ""
                resetStream(controller)
                if (queue.isNotEmpty()) reportPlayback("已停止，按播放从当前曲目继续")
            }
            index < queueIndex -> queueIndex -= 1
            removingCurrent -> {
                queueIndex = following?.let { it - if (it > index) 1 else 0 } ?: index.coerceAtMost(queue.lastIndex)
                playAt(queueIndex, openPlayer = false, autoplay = autoplay)
            }
        }
        saveResume()
    }

    /** Fetch independently of the discovery page, so FM also works directly from the home card. */
    internal suspend fun radioCandidates(): Pair<List<Song>, Boolean> {
        val installed = plugins.state.value.plugins
        val recommendation = Discovery.select(installed, recommendationPluginId) { it.recommendations }
        val chartSource = Discovery.select(installed, chartPluginId) { it.charts }
        val activeIds = installed.filter { it.enabled }.map { it.id }.toSet()
        val candidates = discoverySession.chartPreviews.values.flatten().filter { it.pluginId in activeIds }.toMutableList()
        var failed = false
        suspend fun request(plugin: Plugin, method: String, args: JSONArray = JSONArray()) =
            plugins.client.call(plugin.source, plugin.variables, method, args, plugin.id)
        val completed = kotlinx.coroutines.withTimeoutOrNull(12_000) {
            if (recommendation != null) try {
                val ownsCache = discoverySession.recommendationContext.startsWith("${recommendation.id}|")
                val tag = if (ownsCache) discoverySession.recommendationTag else JSONObject().put("id", "").put("title", "推荐")
                val sheets = if (ownsCache && discoverySession.recommendations.isNotEmpty()) discoverySession.recommendations else {
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
                val chart = discoverySession.charts.firstOrNull() ?: (request(chartSource, "getTopLists") as? JSONArray)?.let { groups ->
                    (0 until minOf(groups.length(), 30)).firstNotNullOfOrNull { groups.optJSONObject(it)?.optJSONArray("data")?.optJSONObject(0) }
                }
                if (chart != null) candidates += discoverySession.fetchChart(chartSource, chart).take(100)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (_: Exception) { failed = true }
            true
        } ?: false
        val enabled = plugins.state.value.plugins.filter { it.enabled }.map { it.id }.toSet()
        return candidates.filter { it.pluginId in enabled } to (failed || !completed)
    }

    internal fun startRadio(restart: Boolean = false, keepCurrent: Boolean = false) {
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

    internal fun ensureRadioTail(force: Boolean = false): Job? {
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

    internal fun toggleFavorite(song: Song?, onSuccess: () -> Unit = {}, onFailure: (String) -> Unit = { status = it }) {
        if (song == null) return
        if (song.pluginId == "local" && !song.data.optBoolean("persisted")) onFailure("本地文件未获得长期授权，不能保存")
        else lifecycleScope.launch {
            try { library.toggleFavorite(song); onSuccess() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { onFailure(e.message ?: "保存失败") }
        }
    }

    internal fun toggleSubject(pluginId: String, kind: String, item: JSONObject, sourceName: String,
                              onSuccess: () -> Unit = {}, onFailure: (String) -> Unit = { status = it }) {
        lifecycleScope.launch {
            try {
                val keys = plugins.state.value.plugins.firstOrNull { it.id == pluginId }?.metadata?.let(::primaryKeys) ?: listOf("id")
                val key = SavedSubject(pluginId, kind, item, sourceName, keys).key
                val wasSaved = library.state.value.subjects.any { it.key == key }
                library.toggleSubject(SavedSubject(pluginId, kind, item, sourceName, keys))
                status = if (wasSaved) "已取消收藏" else "已收藏"
                onSuccess()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { onFailure(e.message ?: "保存失败") }
        }
    }

    internal fun playAt(
        index: Int, openPlayer: Boolean = true, autoplay: Boolean = true, startAt: Long = 0L,
        autoSkip: Boolean = false, sourceSong: Song? = null, restart: Boolean = false,
    ) {
        val song = queue.getOrNull(index) ?: return
        val player = controller ?: run { reportPlayback("播放器未连接"); return }
        /* The loaded track needs no second pass: the audio is mid-phrase and its title is on
           screen, so re-picking it only moves the needle within the queue. */
        if (sourceSong == null && !restart && !autoSkip && startAt <= 0L && song.key == loadedKey &&
            playbackPhase == PlaybackPhase.Ready && ActiveQueue.attempt.accepts(player.currentMediaItem?.mediaId)) {
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
        if (openPlayer) playerOpen = true
        streamFormat = ""
        qualityNoticeJob?.cancel(); qualityNotice = ""
        playingQuality = ""
        recovery?.ready?.cancel()
        recovery = null
        ActiveQueue.source = sourceSong?.let { PlayingSource(song.key, it) }
        resolving?.cancel()
        streamSwap?.cancel()
        remintKey = ""
        lyricJob?.cancel()
        lyricToken++
        qualitySwap = null
        qualitySwitching = ""
        playbackDetail = ""
        playbackPhase = PlaybackPhase.Resolving
        resetStream(player)
        lyrics = emptyList()
        lyricStatus = LyricStatus.Loading
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
                installStream(player, MediaItem.Builder().setUri(uri).setMediaMetadata(metadata).build(), positionMs)
                player.prepare()
                loadedKey = song.key
                if (autoplay) player.play()
                lyricStatus = LyricStatus.Empty("本地文件暂无歌词", local = true)
                reportPlayback(when {
                    !autoplay -> "上次听到 ${clock(startAt)}，按播放继续"
                    song.data.optBoolean("persisted") -> "正在播放：${song.title}"
                    else -> "正在播放；文件授权未能长期保存"
                })
                if (autoplay && song.data.optBoolean("persisted")) lifecycleScope.launch { runCatching { library.record(song) } }
            } catch (e: Exception) {
                advanceAfterFailure(song, e.message ?: "本地文件无法读取", autoplay)
            }
            return
        }
        val streamSong = sourceSong ?: song
        if (autoSourceFallback && autoplay) {
            if (player.playWhenReady) player.pause()
            loadedKey = ""
            positionMs = startAt
            durationMs = (song.durationSeconds * 1000).toLong().coerceAtLeast(0L)
            val sources = SourceRecovery(plugins, song, streamSong, tierFor(streamSong))
            val pass = PlaybackRecovery(sources) {
                advanceAfterFailure(song, sources.protectionFailure ?: sources.failureDetail ?: "未找到可播放的同版本", autoplay)
            }
            recovery = pass
            continueRecovery(pass, startAt)
            return
        }
        val plugin = plugins.state.value.plugins.firstOrNull { it.id == streamSong.pluginId }
        if (plugin == null) {
            advanceAfterFailure(song, "所需音源已删除", autoplay)
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
                var protectionFailure: String? = null
                val unsupported = mutableSetOf<String>()
                for (tier in attempts) {
                    var failure: Exception? = null
                    val candidate = try {
                        plugins.mediaSource(plugin, streamSong, tier)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e is UnsupportedAudioEncryptionException || e is QmcDecryptionException) protectionFailure = e.message
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
                val result = resolved ?: throw IllegalStateException(protectionFailure?.let { "$it；其余音质未能播放" } ?: lastError?.message ?: "插件未返回有效播放地址")
                val actualTier = result.optString("quality").ifBlank { resolvedTier }
                playingQuality = actualTier
                if (unsupported.isNotEmpty()) unsupportedTiers[streamSong.key] = unsupportedTiers[streamSong.key].orEmpty() + unsupported
                installStream(player, audioMediaItem(song, result), positionMs)
                player.prepare()
                loadedKey = song.key
                if (autoplay) {
                    player.play()
                    reportPlayback(when {
                        protectionFailure != null -> "$protectionFailure；已回退至${audioQualityLabel(actualTier)}：${song.title}"
                        attempts.firstOrNull() != resolvedTier || actualTier != resolvedTier -> "正在播放：${song.title} · 已回退至 ${audioQualityLabel(actualTier)}"
                        else -> "正在播放：${song.title}"
                    })
                    lifecycleScope.launch { runCatching { library.record(song) } }
                } else reportPlayback("上次听到 ${clock(startAt)}，按播放继续")
                ActiveQueue.source = PlayingSource(song.key, streamSong)
                loadLyrics(plugin, streamSong)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                advanceAfterFailure(song, e.message ?: "插件没有返回播放地址", autoplay)
            }
        }
    }

    internal fun playbackSong(): Song? {
        val song = queue.getOrNull(queueIndex) ?: return null
        return ActiveQueue.source?.takeIf { it.originalKey == song.key }?.song ?: song
    }

    /* Failures and fallback results use the menu's fixed header slot for four seconds. */
    internal fun qualityFailure(message: String) {
        qualityNoticeJob?.cancel()
        qualityNotice = message
        qualityNoticeJob = lifecycleScope.launch {
            delay(4_000)
            qualityNotice = ""
        }
    }

    // Keep the old stream until a candidate is resolved; commit its tier only after READY.
    internal fun switchQuality(tier: String) {
        val song = queue.getOrNull(queueIndex) ?: return
        val player = controller ?: return
        val streamSong = playbackSong() ?: song
        if (song.pluginId == "local" || tier.isBlank()) return
        /* 上一档还在取流时不接第二次：连按只会把上一个请求取消掉，界面看着像没反应。 */
        if (qualitySwitching.isNotBlank()) return
        if (resolving?.isActive == true) { qualityFailure("请等待当前曲目加载完成"); return }
        if (tier == playingQuality.ifBlank { tierFor(streamSong) } && player.playerError == null && player.playbackState != Player.STATE_IDLE) return
        val plugin = plugins.state.value.plugins.firstOrNull { it.id == streamSong.pluginId } ?: return
        val previousItem = ActiveQueue.attempt.item ?: return
        val previousQuality = playingQuality.ifBlank { tierFor(streamSong) }
        val pass = QualitySwitch(song.key, trustedPosition(player))
        streamSwap?.cancel()
        qualitySwap = pass
        playbackPhase = PlaybackPhase.Switching
        qualitySwitching = tier
        qualityNoticeJob?.cancel()
        qualityNotice = ""
        streamSwap = lifecycleScope.launch {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            var protectionFailure: String? = null
            fun current() = qualitySwap === pass && queue.getOrNull(queueIndex)?.key == pass.songKey
            try {
                val accepted = withTimeoutOrNull(SourceRecovery.BUDGET_MS) {
                    for (attempt in qualityAttempts(qualityOptions(plugin, streamSong), tier)) {
                        if (!current()) return@withTimeoutOrNull null
                        qualitySwitching = attempt
                        var answered = false
                        val source = try {
                            withTimeoutOrNull(5_000) {
                                plugins.mediaSource(plugin, streamSong, attempt, refresh = true).also { answered = true }
                            }
                        } catch (e: UnsupportedAudioEncryptionException) {
                            protectionFailure = e.message
                            reportPlayback("$protectionFailure，正在尝试其他音质…")
                            null
                        } catch (e: QmcDecryptionException) {
                            protectionFailure = e.message
                            reportPlayback("$protectionFailure，正在尝试其他音质…")
                            null
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                          catch (e: Exception) {
                            android.util.Log.w("PrismQuality", "Resolve $attempt failed: ${e.javaClass.simpleName}")
                            null
                        }
                        val uri = Uri.parse(source?.optString("url").orEmpty())
                        if (source == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) {
                            if (answered) unsupportedTiers[streamSong.key] = unsupportedTiers[streamSong.key].orEmpty() + attempt
                            continue
                        }
                        if (!current()) return@withTimeoutOrNull null
                        if (!pass.replaced) {
                            // The old song kept advancing while its replacement was resolving.
                            pass.position = trustedPosition(player)
                            recovery?.ready?.cancel(); recovery = null
                            pass.replaced = true
                        }
                        val ready = CompletableDeferred<Long>()
                        pass.ready = ready
                        var prepared = false
                        try {
                            loadedKey = ""
                            pass.mediaId = installStream(player, audioMediaItem(song, source), pass.position)
                            // prepare retains the latest play/pause intent, including presses during the swap.
                            player.prepare()
                            prepared = withTimeoutOrNull(SourceRecovery.PREPARE_MS) { ready.await(); true } == true
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                          catch (e: Exception) {
                            generateSequence(e as Throwable) { it.cause }.take(8).filterIsInstance<QmcDecryptionException>().firstOrNull()?.let { protectionFailure = it.message }
                            android.util.Log.w("PrismQuality", "Prepare $attempt failed: ${(e as? PlaybackException)?.errorCode ?: e.javaClass.simpleName}")
                        } finally { if (pass.ready === ready) pass.ready = null }
                        if (!current()) return@withTimeoutOrNull null
                        if (prepared) return@withTimeoutOrNull attempt to source
                        android.util.Log.w("PrismQuality", "Rejected $attempt; trying lower tier")
                        resetStream(player, pause = false); positionMs = pass.position
                    }
                    null
                }
                if (!current()) return@launch
                if (accepted == null) {
                    if (pass.replaced) {
                        // Every lower tier failed or timed out: return to the last working stream.
                        playbackPhase = PlaybackPhase.Buffering
                        installStream(player, previousItem, pass.position)
                        player.prepare()
                        loadedKey = song.key; positionMs = pass.position
                    }
                    playingQuality = previousQuality
                    val message = protectionFailure?.let { "$it；已保留${audioQualityLabel(previousQuality)}" } ?: "${audioQualityLabel(tier)}暂不可用，已保留${audioQualityLabel(previousQuality)}"
                    qualityFailure(message); reportPlayback(message)
                    return@launch
                }
                val (resolvedTier, source) = accepted
                val actual = source.optString("quality").ifBlank { resolvedTier }
                playingQuality = actual
                if (actual != resolvedTier) unsupportedTiers[streamSong.key] = unsupportedTiers[streamSong.key].orEmpty() + resolvedTier
                songTier[song.key] = actual
                songTier[streamSong.key] = actual
                loadedKey = song.key; positionMs = pass.position
                playbackPhase = PlaybackPhase.Ready
                if (playbackActive) onAttemptActive(true)
                ActiveQueue.source = PlayingSource(song.key, streamSong)
                failedSongs.clear()
                val message = when {
                    protectionFailure != null -> "$protectionFailure；已回退至${audioQualityLabel(actual)}"
                    actual == tier -> "已切换至${audioQualityLabel(actual)}"
                    else -> "${audioQualityLabel(tier)}暂不可用，已回退至${audioQualityLabel(actual)}"
                }
                if (actual != tier || protectionFailure != null) qualityFailure(message)
                reportPlayback(message)
                android.util.Log.i("PrismQuality", "Requested $tier, accepted $actual in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms")
            } finally {
                if (qualitySwap === pass) {
                    qualitySwap = null; qualitySwitching = ""
                    if (!pass.replaced && playbackPhase == PlaybackPhase.Switching) {
                        playbackPhase = if (playbackState == Player.STATE_BUFFERING) PlaybackPhase.Buffering else PlaybackPhase.Ready
                    }
                    if (queue.getOrNull(queueIndex)?.key == song.key) finishCurrentTrack()
                }
            }
        }
    }

    internal fun setAutoSourceFallback(enabled: Boolean, persist: Boolean = true) {
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

    internal fun pauseRecoveryIfPending(): Boolean {
        val pass = recovery?.takeIf { resolving?.isActive == true } ?: return false
        recovery = null
        pass.ready?.cancel(); resolving?.cancel()
        resetStream(controller)
        playbackPhase = PlaybackPhase.Idle
        positionMs = pass.position
        reportPlayback("已暂停：${pass.sources.original.title}")
        return true
    }

    internal fun togglePlayback() {
        if (pauseRecoveryIfPending()) return
        val player = controller ?: run { resumePlayback(); return }
        if (playbackPhase == PlaybackPhase.Finished || playbackPhase == PlaybackPhase.Failed || !player.playWhenReady) {
            resumePlayback()
        } else player.pause()
    }

    internal fun resumePlayback() {
        val player = controller ?: run {
            if (playbackPhase == PlaybackPhase.Failed) {
                playbackPhase = PlaybackPhase.Connecting
                connect(retryPlayback = true)
            }
            return
        }
        if (resolving?.isActive == true || playbackPhase == PlaybackPhase.Switching) return
        if (queue.isEmpty()) return
        when {
            playbackPhase == PlaybackPhase.Finished || playbackState == Player.STATE_ENDED ->
                playAt(queueIndex, openPlayer = false, sourceSong = playbackSong())
            playbackPhase == PlaybackPhase.Failed || !ActiveQueue.attempt.accepts(player.currentMediaItem?.mediaId) ||
                playbackState == Player.STATE_IDLE -> playAt(queueIndex, openPlayer = false, startAt = positionMs, sourceSong = playbackSong())
            else -> player.play()
        }
    }

    internal fun seekPlayback(target: Long) {
        val at = target.coerceAtLeast(0L)
        if (playbackPhase == PlaybackPhase.Finished || playbackPhase == PlaybackPhase.Failed) return
        positionMs = at
        if (resumePending >= 0) resumePending = at
        qualitySwap?.position = at
        recovery?.takeIf { resolving?.isActive == true }?.position = at
        pendingSeek?.cancel()
        val player = controller ?: return
        val id = player.currentMediaItem?.mediaId ?: return
        if (!ActiveQueue.attempt.accepts(id)) return
        pendingSeek = lifecycleScope.launch {
            delay(250)
            if (ActiveQueue.attempt.accepts(id) && player.currentMediaItem?.mediaId == id &&
                playbackPhase != PlaybackPhase.Failed && playbackPhase != PlaybackPhase.Finished) player.seekTo(at)
        }
    }

    internal fun continueRecovery(pass: PlaybackRecovery, startAt: Long) {
        resolving?.cancel()
        pass.position = startAt
        resolving = lifecycleScope.launch {
            val player = controller ?: return@launch
            val original = pass.sources.original
            fun current() = recovery === pass && autoSourceFallback && queue.getOrNull(queueIndex)?.key == original.key
            val succeeded = withTimeoutOrNull(SourceRecovery.BUDGET_MS) {
                while (current()) {
                    if (pass.sources.current != null) playbackPhase = PlaybackPhase.Switching
                    reportPlayback(playbackPhase.message)
                    val stream = pass.sources.next() ?: return@withTimeoutOrNull false
                    if (!current()) return@withTimeoutOrNull false
                    val ready = CompletableDeferred<Long>()
                    pass.ready = ready
                    var started = false
                    var preparationFailure: Exception? = null
                    try {
                        pass.mediaId = installStream(player, audioMediaItem(original, stream.source), pass.position)
                        player.prepare(); player.play()
                        val duration = withTimeoutOrNull(SourceRecovery.PREPARE_MS) { ready.await() }
                        started = duration != null && (original.durationSeconds <= 0 || duration <= 0 ||
                            recordingDurationMatches(original, duration / 1000.0))
                        if (started && duration != null && duration > 0) durationMs = duration
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                      catch (e: Exception) { preparationFailure = e }
                    finally { if (pass.ready === ready) pass.ready = null }
                    /* Abandoned mid-prepare: stop the orphan here. Letting it run leaves the
                       player on one track while the display names another. */
                    if (!current()) {
                        if (ActiveQueue.attempt.accepts(pass.mediaId)) resetStream(player)
                        return@withTimeoutOrNull false
                    }
                    if (started) {
                        positionMs = pass.position
                        playbackPhase = PlaybackPhase.Ready
                        loadedKey = original.key
                        ActiveQueue.source = PlayingSource(original.key, stream.song)
                        playingQuality = stream.source.optString("quality").ifBlank { stream.quality }
                        pass.sources.unsupported.forEach { (key, tiers) -> unsupportedTiers[key] = unsupportedTiers[key].orEmpty() + tiers }
                        lyricCalibrationMs = LyricCalibration.load(this@MainActivity, stream.song.key)
                        loadLyrics(stream.plugin, stream.song)
                        if (playbackActive) onAttemptActive(true)
                        reportPlayback("正在播放：${original.title}" + when {
                            stream.song.pluginId != original.pluginId -> " · 已由${original.sourceLabel}切到${stream.song.sourceLabel}"
                            pass.sources.protectionFailure != null -> " · ${pass.sources.protectionFailure}；已回退至${audioQualityLabel(playingQuality)}"
                            else -> ""
                        })
                        lifecycleScope.launch { runCatching { library.record(original) } }
                        return@withTimeoutOrNull true
                    }
                    resetStream(player)
                    pass.sources.failed(preparationFailure)
                }
                false
            } == true
            if (!succeeded && current()) {
                recovery = null
                resetStream(player)
                pass.onFailure()
            } else if (!succeeded && ActiveQueue.attempt.accepts(pass.mediaId)) {
                resetStream(player)
            } else if (succeeded && current()) {
                resolving = null
                finishCurrentTrack()
            }
        }
    }

    internal fun advanceAfterFailure(song: Song, detail: String, autoplay: Boolean = true) {
        if (!autoplay) {
            showPlaybackFailure(song, detail)
            return
        }
        failedSongs += song.key
        val order = playbackIndices()
        val current = order.indexOf(queueIndex)
        val sequential = ActiveQueue.radio || playMode == PlaybackMode.SEQUENTIAL
        val candidates = order.drop(current + 1) + if (sequential) emptyList() else order.take(current.coerceAtLeast(0))
        val next = candidates.firstOrNull { queue[it].key !in failedSongs }
        if (next == null || ActiveQueue.radio && failedSongs.size >= 5) {
            if (ActiveQueue.radio) { radioJob?.cancel(); radioRetryAt = android.os.SystemClock.elapsedRealtime() + 30_000 }
            failedSongs.clear()
            showPlaybackFailure(song, detail)
            return
        }
        playbackPhase = PlaybackPhase.Switching
        reportPlayback("正在尝试下一首：${song.title}")
        val token = ++autoSkipToken
        val nextKey = queue[next].key
        lifecycleScope.launch {
            delay(600)
            if (token != autoSkipToken) return@launch
            val index = queue.indexOfFirst { it.key == nextKey }
            if (index >= 0) playAt(index, openPlayer = false, autoSkip = true)
            else advanceAfterFailure(song, detail, autoplay)
        }
    }

    private fun showPlaybackFailure(song: Song, detail: String) {
        controller?.pause()
        playing = false
        playbackPhase = PlaybackPhase.Failed
        playbackDetail = detail
        if (lyricStatus == LyricStatus.Loading && lyricJob?.isActive != true) lyricStatus = LyricStatus.Empty("暂无歌词")
        reportPlayback("播放失败：${song.title} · $detail")
    }

    internal fun loadLyrics(plugin: Plugin, song: Song) {
        lyricJob?.cancel()
        val token = ++lyricToken
        if (lyricOverrideKey != song.key) { lyricSourceOverride = null; lyricOverrideKey = song.key }
        if (lyricProbeKey != song.key) {
            lyricProbeJob?.cancel()
            lyricProbes = emptyMap()
            lyricProbeKey = ""
        }
        lyricJob = lifecycleScope.launch {
            lyrics = emptyList()
            lyricSourceLabel = ""
            lyricActiveSource = ""
            lyricStatus = LyricStatus.Loading
            try {
                val externalSources = enabledExternalLyricSources()
                val override = lyricSourceOverride?.takeIf { it == LyricSources.PLUGIN || it in externalSources }
                val payload = when {
                    override == LyricSources.PLUGIN -> pluginLyric(plugin, song)
                    override != null -> when (val found = LyricSources.lookup(override, song)) {
                        is LyricLookup.Found -> found.payload
                        LyricLookup.Missing -> null
                        LyricLookup.Failed -> error("歌词源连接失败")
                    }
                    externalSources.isEmpty() -> pluginLyric(plugin, song)
                    else -> {
                        val primary = try {
                            withTimeout(2_000) { pluginLyric(plugin, song) }
                                ?.takeIf { payload ->
                                    isInstrumentalLyric(payload.text) || withContext(Dispatchers.Default) {
                                        payload.lines().isNotEmpty()
                                    }
                                }
                                ?.let { LyricLookup.Found(it) } ?: LyricLookup.Missing
                        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                            LyricLookup.Failed
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            LyricLookup.Failed
                        }
                        (primary as? LyricLookup.Found)?.payload?.takeIf { it.text.isNotBlank() }
                            ?: when (val external = LyricSources.fetchExternal(song, externalSources)) {
                                is LyricLookup.Found -> external.payload
                                LyricLookup.Failed -> error("歌词源连接失败")
                                LyricLookup.Missing -> if (primary == LyricLookup.Failed) error("插件歌词获取失败") else null
                            }
                    }
                }
                if (token != lyricToken) return@launch
                if (payload == null || payload.text.isBlank()) {
                    lyrics = emptyList()
                    lyricStatus = LyricStatus.Empty()
                    return@launch
                }
                publishLyric(payload, token)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { if (token == lyricToken) { lyrics = emptyList(); lyricStatus = LyricStatus.Error } }
        }
    }

    private suspend fun publishLyric(payload: LyricPayload, token: Int) {
        val parsed = withContext(Dispatchers.Default) { payload.lines() }
        if (token != lyricToken) return
        lyrics = parsed
        lyricSourceLabel = payload.source
        lyricActiveSource = payload.sourceId
        lyricStatus = when {
            parsed.isNotEmpty() -> LyricStatus.Ready
            payload.text.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("<") -> LyricStatus.Empty("暂无可用歌词")
            isInstrumentalLyric(payload.text) -> LyricStatus.Empty("纯音乐 · 暂无歌词")
            else -> LyricStatus.Empty()
        }
    }

    private fun isInstrumentalLyric(text: String): Boolean =
        text.trim() in setOf("纯音乐，请欣赏", "纯音乐", "Instrumental")

    private suspend fun pluginLyric(plugin: Plugin, song: Song): LyricPayload? {
        val supplied = song.data.optString("rawLrc").ifBlank { song.data.optString("lrc") }
        if (supplied.isNotBlank()) return LyricPayload(supplied,
            source = LyricSources.label(LyricSources.PLUGIN), sourceId = LyricSources.PLUGIN)
        if (!plugin.capabilities.has("lyric")) return null
        val result = plugins.client.call(plugin.source, plugin.variables, "getLyric", JSONArray().put(song.data), plugin.id) as? JSONObject
            ?: return null
        val text = result.optString("rawLrc")
        if (text.isBlank()) return null
        return LyricPayload(text, result.optString("translation"),
            LyricSources.label(LyricSources.PLUGIN), sourceId = LyricSources.PLUGIN)
    }

    internal fun enabledExternalLyricSources(): Set<String> = buildSet {
        if (lyricLrclib) add(LyricSources.LRCLIB)
        if (lyricKugou) add(LyricSources.KUGOU)
        if (lyricNetease) add(LyricSources.NETEASE)
    }

    private fun refreshLyricSources() {
        lyricProbeJob?.cancel()
        lyricProbeKey = ""
        lyricProbes = emptyMap()
        if (lyricSourceOverride != LyricSources.PLUGIN && lyricSourceOverride !in enabledExternalLyricSources())
            lyricSourceOverride = null
        val song = playbackSong() ?: return
        val plugin = plugins.state.value.plugins.firstOrNull { it.id == song.pluginId } ?: return
        loadLyrics(plugin, song)
    }

    internal fun selectLyricSource(source: String?) {
        if (source != null && source != LyricSources.PLUGIN && source !in enabledExternalLyricSources()) return
        val song = playbackSong() ?: return
        val plugin = plugins.state.value.plugins.firstOrNull { it.id == song.pluginId } ?: return
        lyricSourceOverride = source
        lyricOverrideKey = song.key
        val cached = source?.let { id ->
            lyricProbes[id]?.takeIf { it.state == LyricProbeState.READY }?.payload
        }
        if (cached == null) { loadLyrics(plugin, song); return }
        lyricJob?.cancel()
        val token = ++lyricToken
        lyricJob = lifecycleScope.launch {
            lyrics = emptyList()
            lyricSourceLabel = ""
            lyricActiveSource = ""
            lyricStatus = LyricStatus.Loading
            publishLyric(cached, token)
        }
    }

    private fun setLyricProbe(source: String, probe: LyricProbe) {
        val song = playbackSong() ?: return
        if (lyricProbeKey != song.key) return
        lyricProbes = lyricProbes + (source to probe)
    }

    internal fun probeLyricSources() {
        val external = enabledExternalLyricSources()
        if (external.isEmpty()) return
        val song = playbackSong() ?: return
        val sameSong = lyricProbeKey == song.key
        val settled = sameSong && lyricProbes.isNotEmpty() &&
            lyricProbes.values.none { it.state == LyricProbeState.LOADING || it.state == LyricProbeState.FAILED }
        if (settled || (sameSong && lyricProbeJob?.isActive == true)) return
        lyricProbeJob?.cancel()
        lyricProbeKey = song.key
        val plugin = plugins.state.value.plugins.firstOrNull { it.id == song.pluginId }
        lyricProbes = (listOf(LyricSources.PLUGIN) + external).associateWith { id ->
            /* 重试只补没问到的源：已经拿到的结果留着，列表不会整体闪回「查询中」。 */
            (if (sameSong) lyricProbes[id]?.takeIf {
                it.state == LyricProbeState.READY || it.state == LyricProbeState.EMPTY ||
                    it.state == LyricProbeState.UNSUPPORTED
            } else null) ?: LyricProbe(LyricProbeState.LOADING)
        }
        val pending = lyricProbes.filterValues { it.state == LyricProbeState.LOADING }.keys
        lyricProbeJob = lifecycleScope.launch {
            if (LyricSources.PLUGIN in pending) launch {
                val supplied = song.data.optString("rawLrc").ifBlank { song.data.optString("lrc") }
                val payload = if (supplied.isNotBlank()) {
                    LyricPayload(supplied,
                        source = LyricSources.label(LyricSources.PLUGIN),
                        sourceId = LyricSources.PLUGIN)
                } else if (plugin != null && plugin.capabilities.has("lyric")) {
                    runCatching { pluginLyric(plugin, song) }.getOrNull()
                } else null
                setLyricProbe(
                    LyricSources.PLUGIN,
                    if (payload == null) LyricProbe(LyricProbeState.UNSUPPORTED)
                    else LyricProbe(LyricProbeState.READY, payload),
                )
            }
            external.forEach { id ->
                if (id !in pending) return@forEach
                launch {
                    val probe = when (val result = LyricSources.lookup(id, song)) {
                        is LyricLookup.Found -> LyricProbe(LyricProbeState.READY, result.payload)
                        LyricLookup.Missing -> LyricProbe(LyricProbeState.EMPTY)
                        LyricLookup.Failed -> LyricProbe(LyricProbeState.FAILED)
                    }
                    setLyricProbe(id, probe)
                }
            }
        }
    }

    /* Import goes through importMusicSheet/importMusicItem with no platform special-casing:
       a recognized link goes to its source; otherwise every source declaring import capability
       is asked in turn until one returns a song. */
    internal fun importCandidates(url: String): List<Plugin> {
        val capable = plugins.state.value.plugins.filter { it.enabled && (it.metadata.optBoolean("importSheet") || it.metadata.optBoolean("importItem")) }
        val target = MusicPlatform.identify(url)
        return capable.sortedBy { if (it.platform == target) 0 else 1 }
    }

    /* 标准协议的 importMusicSheet 只返回歌曲数组，没有歌单标题，所以留空时按音源名自动命名。 */
    internal fun autoPlaylistName(plugin: Plugin, existing: Set<String>): String {
        val base = plugin.displayName.ifBlank { plugin.name }.take(56)
        var candidate = base
        var index = 2
        while (candidate in existing) { candidate = "$base $index"; index++ }
        return candidate
    }

    /* 手机分享出来的是整段文本（「分享歌单: 名字 https://… (@网易云音乐)」），先抽出
       第一个链接再交给插件：音源识别和插件拿到的都是干净的 URL，而不是分享文案。 */
    internal fun shareUrlOf(input: String): String {
        val match = Regex("""https?://[^\s"'<>，。；：！？、（）【】]+""").find(input) ?: return input.trim()
        return match.value.trimEnd(')', '】', ']', '}', ',', '.', ';', ':', '!', '?')
    }

    internal fun importPlaylist(name: String, input: String, target: Plugin? = null): String {
        val url = shareUrlOf(input)
        require(url.isNotBlank()) { "请填写歌单或单曲链接" }
        val candidates = target?.let { listOf(it) } ?: importCandidates(url)
        require(candidates.isNotEmpty()) { "没有音源支持链接导入：请先启用支持歌单或单曲导入的音源" }
        return runContentTask("importSheet", "正在导入歌单…") { progress ->
            val reasons = mutableListOf<String>()
            try {
                for ((position, plugin) in candidates.withIndex()) {
                    progress("正在用 ${plugin.displayName} 导入…")
                    var sheet: JSONArray? = null
                    var sheetTitle = ""
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
                            /* importMusicSheet 在 MusicFree 的类型里是 MusicItem[]，但那是历史写法
                               （它自己的 plugin.d.ts 还留着「数据结构应该是 IMusicSheetItem」的 todo）。
                               新一代插件返回整个歌单对象 {title, artwork, worksNum, musicList}，
                               BakaMusic 也按两种都收处理并写进了插件文档。只认数组的话这些音源
                               一律报「没有返回歌曲」，所以数组优先、其次取对象里的 musicList。 */
                            val tracks = when {
                                method == "importMusicItem" -> (imported as? JSONObject)?.let { JSONArray().put(it) }
                                imported is JSONArray -> imported
                                imported is JSONObject -> imported.optJSONArray("musicList")
                                else -> null
                            }
                            if (tracks != null && tracks.length() > 0) {
                                sheet = tracks
                                sheetTitle = (imported as? JSONObject)?.optString("title").orEmpty().trim()
                                break
                            }
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
                    /* 插件自己报的歌单名比「网易云音乐 2」有用，但用户填了名字就听用户的。 */
                    val title = name.trim().ifBlank { sheetTitle.ifBlank { autoPlaylistName(plugin, library.state.value.playlists.map { it.name }.toSet()) } }
                    library.create(title, kept, source.id)
                    /* create appends, so the list just written is the last one. Handing its id to 我的
                       is what makes an import findable instead of a number in a status line. */
                    playlistToShow = library.state.value.playlists.lastOrNull()?.id
                    val message = "已导入「$title」${kept.size} 首歌曲 · 我的 → 歌单" +
                        if (songs.size > kept.size) "（歌单共 ${songs.size} 首，超出 5000 首上限的部分未导入）" else ""
                    return@runContentTask SyncResult(message)
                }
                val detail = reasons.take(2).joinToString("；").ifBlank { "音源没有返回歌曲" }
                SyncResult("链接导入失败：$detail" + when {
                    target != null -> "；可以换个音源再试"
                    reasons.size > 2 -> "（共尝试 ${reasons.size} 个音源）"
                    else -> ""
                }, failed = true)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { SyncResult("链接导入失败：${e.message}", failed = true) }
        }
    }

    /* Written every 15 seconds and on the way out: a TV is usually stopped, not closed, so
       onStop is the one signal that reliably precedes the process going away. */
    internal fun saveResume() {
        val position = trustedPosition()
        // Snapshot on the main thread, serialise and write off it (one writer keeps saves ordered).
        val songs = queue; val index = queueIndex; val radio = ActiveQueue.radio; val recent = ActiveQueue.radioRecent
        val order = if (playMode == PlaybackMode.SHUFFLE && !radio) ActiveQueue.order.indices else emptyList()
        resumeStore.enqueueWrite(PlaybackResume(songs, index, position, radio, recent, order,
            finished = playbackPhase == PlaybackPhase.Finished))
    }

    internal fun step(direction: Int) {
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

    internal fun playbackIndices(): List<Int> =
        if (playMode == PlaybackMode.SHUFFLE && !ActiveQueue.radio) ActiveQueue.order.indices else queue.indices.toList()

    internal fun nextIndex(direction: Int): Int {
        val order = playbackIndices()
        val current = order.indexOf(queueIndex).coerceAtLeast(0)
        return order[(current + direction + order.size) % order.size]
    }

    internal fun readPlaybackMode(): PlaybackMode {
        val prefs = getSharedPreferences("playback", MODE_PRIVATE)
        val stored = prefs.all["mode"] as? String
        val mode = PlaybackMode.fromId(stored) ?: PlaybackMode.SEQUENTIAL
        if (stored != mode.id) prefs.edit().putString("mode", mode.id).apply()
        return mode
    }

    internal fun updatePreference(key: String, value: String) {
        when (key) {
            "mode" -> {
                val mode = PlaybackMode.fromId(value) ?: error("未知播放模式")
                leaveRadio()
                if (mode == PlaybackMode.SHUFFLE && playMode != PlaybackMode.SHUFFLE)
                    ActiveQueue.order = ActiveQueue.order.randomized(queueIndex)
                playMode = mode
                getSharedPreferences("playback", MODE_PRIVATE).edit().putString("mode", mode.id).apply()
                saveResume()
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
            "miniLyrics", "reduceMotion", "subLyrics", "hideHeading" -> {
                val enabled = value.toBooleanStrictOrNull() ?: error("开关值无效")
                val preference = when (key) {
                    "miniLyrics" -> { showMiniLyrics = enabled; "mini_lyrics" }
                    "reduceMotion" -> { reduceMotion = enabled; "reduce_motion" }
                    "hideHeading" -> { hideHeading = enabled; "hide_heading" }
                    else -> { subLyrics = enabled; "sub_lyrics" }
                }
                getSharedPreferences("appearance", MODE_PRIVATE).edit().putBoolean(preference, enabled).apply()
            }
            "lyricFontLevel" -> {
                require(value in lyricFontLevels) { "未知歌词字号" }
                lyricFontLevel = value
                getSharedPreferences("appearance", MODE_PRIVATE).edit().putString("lyric_font_level", value).apply()
            }
            "lyricLrclib", "lyricKugou", "lyricNetease" -> {
                val enabled = value.toBooleanStrictOrNull() ?: error("开关值无效")
                val preference = when (key) {
                    "lyricLrclib" -> { lyricLrclib = enabled; "lrclib" }
                    "lyricKugou" -> { lyricKugou = enabled; "kugou" }
                    else -> { lyricNetease = enabled; "netease" }
                }
                getSharedPreferences(LyricSourcePrefs.PREFS, MODE_PRIVATE).edit().putBoolean(preference, enabled).apply()
                refreshLyricSources()
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

    internal fun toggleHomeShelf(id: String) {
        homeShelfHidden = if (id in homeShelfHidden) homeShelfHidden - id else homeShelfHidden + id
        getSharedPreferences("home", MODE_PRIVATE).edit()
            .putStringSet("hidden_shelves", homeShelfHidden.toHashSet()).apply()
    }

    /* After a backup restore the phone session must survive, so instead of recreate()
       the preference-backed state is read again in place. */
    internal fun reloadBackedState() {
        quality = getSharedPreferences("playback", MODE_PRIVATE).getString("quality", "standard") ?: "standard"
        setAutoSourceFallback(getSharedPreferences("playback", MODE_PRIVATE).getBoolean(SourceRecovery.PREFERENCE, false), persist = false)
        playMode = readPlaybackMode()
        theme = getSharedPreferences("appearance", MODE_PRIVATE).getString("theme", "gold") ?: "gold"
        showMiniLyrics = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("mini_lyrics", false)
        reduceMotion = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("reduce_motion", false)
        lyricFontLevel = getSharedPreferences("appearance", MODE_PRIVATE).getString("lyric_font_level", "标准") ?: "标准"
        hideHeading = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("hide_heading", true)
        subLyrics = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("sub_lyrics", true)
        LyricSourcePrefs.migrateMode(this)
        lyricLrclib = LyricSourcePrefs.lrclib(this)
        lyricKugou = LyricSourcePrefs.kugou(this)
        lyricNetease = LyricSourcePrefs.netease(this)
        refreshLyricSources()
        lyricCalibrationMs = playbackSong()?.let { LyricCalibration.load(this, it.key) } ?: 0L
        pluginWorkerLimit = PluginWorkers.saved(this)
        homeShelfHidden = getSharedPreferences("home", MODE_PRIVATE).getStringSet("hidden_shelves", null)?.toSet() ?: emptySet()
        recommendationPluginId = getSharedPreferences("home", MODE_PRIVATE).getString("recommendation_plugin", "") ?: ""
        chartPluginId = getSharedPreferences("home", MODE_PRIVATE).getString("chart_plugin", "") ?: ""
        searchHistory = loadSearchHistory()
        recentArtists = loadRecentArtists()
        SourcePlatforms.initialize(this)
    }

    /* History is kept per search type so 歌手 searches don't suggest 歌曲 terms.
       One JSON object under the old "history" key; a plain array there is the
       pre-type history, which belongs to 歌曲. */
    internal fun loadSearchHistory(): Map<String, List<String>> = runCatching {
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

    internal fun loadRecentArtists(): List<SavedSubject> = runCatching {
        val rows = JSONArray(getSharedPreferences("search", MODE_PRIVATE).getString("recent_artists", "[]"))
        (0 until minOf(rows.length(), 8)).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val data = row.optJSONObject("data") ?: return@mapNotNull null
            SavedSubject(row.getString("pluginId"), "artist", data, row.optString("sourceName"), primaryKeys(row))
        }
    }.getOrDefault(emptyList())

    internal fun saveRecentArtists(artists: List<SavedSubject>) {
        recentArtists = artists
        val rows = JSONArray()
        artists.forEach { rows.put(JSONObject().put("pluginId", it.pluginId).put("data", it.data)
            .put("sourceName", it.sourceName).put("primaryKey", JSONArray(it.primaryKey))) }
        getSharedPreferences("search", MODE_PRIVATE).edit().putString("recent_artists", rows.toString()).apply()
    }

    internal fun saveSearchHistory() {
        val root = JSONObject()
        searchHistory.forEach { (type, terms) -> root.put(type, JSONArray(terms)) }
        getSharedPreferences("search", MODE_PRIVATE).edit().putString("history", root.toString()).apply()
    }

    private fun closePhoneSessions() {
        phoneSessions.close()
        pluginReplacements.clear()
    }

    internal fun showPluginImport(result: SyncResult) {
        status = result.message
        var bytes = pluginReplacements.sumOf { it.incoming.source.toByteArray(Charsets.UTF_8).size }
        for (replacement in result.replacements) {
            if (pluginReplacements.any { it.candidates.map { p -> p.id } == replacement.candidates.map { p -> p.id } && it.incoming.source == replacement.incoming.source }) continue
            bytes += replacement.incoming.source.toByteArray(Charsets.UTF_8).size
            if (bytes > Plugins.MAX_PENDING_SCRIPTS) { status += "；待确认文件过多，请分批导入"; break }
            pluginReplacements += replacement
        }
        if (pluginReplacements.isNotEmpty()) phoneSessions.dialogVisible = false
    }

    internal fun resolvePluginReplacement(id: String, action: String, targetId: String? = null) {
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

    /** A command acknowledgement is separate from the result of its asynchronous work. */
    internal fun runContentTask(key: String, message: String, block: suspend ((String) -> Unit) -> SyncResult): String {
        check(!busy && !restoringBackup && contentTasks.values.none { it.running } && pluginReplacements.isEmpty()) { "请先完成当前操作或音源替换确认" }
        val revision = ++contentTaskRevision
        contentTasks[key] = ContentTask(revision, message)
        busy = true
        lifecycleScope.launch {
            try {
                val result = block { progress ->
                    contentTasks[key] = ContentTask(revision, progress)
                    status = progress
                }
                showPluginImport(result)
                contentTasks[key] = ContentTask(revision, result.message, running = false, failed = result.failed)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                val error = e.message ?: "操作失败"
                status = error
                contentTasks[key] = ContentTask(revision, error, running = false, failed = true)
            } finally { busy = false }
        }
        return JSONObject().put("ok", true).put("taskKey", key).put("taskRevision", revision).toString()
    }

    internal fun phoneSnapshot(importTarget: String, importCapableCount: Int): String {
        val sourceState = plugins.state.value
        val libraryState = library.state.value
        val current = queue.getOrNull(queueIndex)
        val stream = playbackSong()
        val streamPlugin = stream?.let { song -> sourceState.plugins.firstOrNull { it.id == song.pluginId } }
        val qualities = if (stream != null && streamPlugin != null) {
            qualityOptions(streamPlugin, stream).filterNot { it in unsupportedTiers[stream.key].orEmpty() }
        } else emptyList()
        val shelves = tv.prism.ui.home.homeSectionOptions

        return PhoneRemoteSnapshot().apply {
            playback(
                queue = queue, queueIndex = queueIndex, order = playbackIndices(), offset = phoneQueueOffset,
                stream = stream, playing = playing && playbackState != Player.STATE_ENDED,
                position = positionMs, duration = durationMs, quality = playingQuality.ifBlank { tierFor(stream) },
                streamFormat = streamFormat, mode = playMode.id, radioActive = ActiveQueue.radio,
                favorite = current != null && libraryState.favorites.any { it.key == current.key },
                qualities = qualities,
            )
            preferences(
                miniLyrics = showMiniLyrics, theme = theme, reduceMotion = reduceMotion,
                autoSourceFallback = autoSourceFallback, lyricFontLevel = lyricFontLevel, subLyrics = subLyrics,
                pluginWorkerLimit = pluginWorkerLimit, automaticWorkers = PluginWorkers.automatic(this@MainActivity),
                recommendationSource = Discovery.select(sourceState.plugins, recommendationPluginId) { it.recommendations }?.id.orEmpty(),
                chartSource = Discovery.select(sourceState.plugins, chartPluginId) { it.charts }?.id.orEmpty(),
                shelves = shelves, hiddenShelves = homeShelfHidden,
            )
            sources(plugins, importTarget, importCapableCount, pluginReplacements.firstOrNull())
            search(
                type = searchType, term = searchTerm, generation = searchSession.generation,
                running = searchSession.isRunning, hasMore = hasMore, songs = results, entityCount = entities.size,
                currentKey = current?.key, choices = searchTypeChoices(sourceState.plugins.filter { it.enabled }),
                sources = searchSources,
            )
            activity(status, page, playerOpen, busy, restoringBackup, restoreMessage, restoreFailed, contentTasks)
        }.encode()
    }

    internal fun togglePhonePlayback() = togglePlayback()

    internal fun changePhoneSearchType(type: String) {
        require(!busy) { "电视正在搜索，稍后再改筛选" }
        val choices = searchTypeChoices(plugins.state.value.plugins.filter { it.enabled })
        require(choices.any { it.first == type }) { "没有已启用的音源支持这种搜索" }
        if (searchType == type) return
        searchType = type
        searchViewModel.clearResults()
        searchViewModel.state.value.draft.takeIf { it.isNotBlank() }?.let { search(it, navigating = true) }
    }

    internal fun changePhoneSearchSource(id: String) {
        require(!busy) { "电视正在搜索，稍后再改筛选" }
        if (id.isEmpty()) {
            searchSources = emptySet()
        } else {
            val active = plugins.state.value.plugins.firstOrNull { it.id == id && it.enabled }
                ?: error("这个音源不存在或已停用")
            searchSources = if (id in searchSources) searchSources - id else searchSources + id
            selectedPlugin = active.id
        }
        searchViewModel.clearResults()
        searchViewModel.state.value.draft.takeIf { it.isNotBlank() }?.let { search(it) }
    }

    internal fun restorePhoneBackup(value: String) {
        check(!busy && pluginReplacements.isEmpty()) { "请先完成当前音源操作" }
        restoringBackup = true
        busy = true
        restoreFailed = false
        restoreMessage = "正在恢复曲库、音源与设置…"
        phoneSessions.closeForm()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { LibraryBackup.apply(this@MainActivity, library, plugins, JSONObject(value)) }
                reloadBackedState()
                restoreMessage = "备份恢复完成，电视已应用曲库、音源与设置。"
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                runCatching { reloadBackedState() }
                restoreFailed = true
                restoreMessage = "恢复未完成：${error.message ?: "未知错误"}。部分数据可能已更新。"
            } finally {
                restoringBackup = false
                busy = false
                status = restoreMessage
            }
        }
    }

    /* Only offer the search types some enabled source can actually answer: a type every source
       ignores would always land on an empty list. The TV page and the phone remote both read this,
       so the two can never present a different set of choices. */
    internal fun searchTypeChoices(active: List<Plugin>): List<Pair<String, String>> {
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
        if (!::plugins.isInitialized) return super.dispatchKeyEvent(event)
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
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_UP && event.keyCode == repeatedDirection) {
                    repeatedDirection = KeyEvent.KEYCODE_UNKNOWN
                } else if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        repeatedDirection = KeyEvent.KEYCODE_UNKNOWN
                        lastDirectionRepeatAt = 0
                    } else {
                        repeatedDirection = event.keyCode
                        if (event.eventTime - lastDirectionRepeatAt < 65L) return true
                        lastDirectionRepeatAt = event.eventTime
                    }
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> { step(1); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { step(-1); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlayback(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (!pauseRecoveryIfPending()) controller?.pause(); return true }
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
    internal fun dispatchWithFocusRecovery(event: KeyEvent): Boolean {
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
    override fun onDestroy() {
        radioJob?.cancel(); radioAdvanceJob?.cancel()
        if (::appUpdates.isInitialized) { updateJob?.cancel(); appUpdates.cancelNetwork() }
        closePhoneSessions()
        recovery?.ready?.cancel(); recovery = null
        lyricJob?.cancel(); resolving?.cancel(); controller?.removeListener(listener); connection?.let(MediaController::releaseFuture); controller = null
        super.onDestroy()
    }
    override fun onStop() {
        repeatedDirection = KeyEvent.KEYCODE_UNKNOWN
        lastDirectionRepeatAt = 0
        playerScreenOff = false
        screenOffSettled = true
        wakeKeys.clear()
        if (::resumeStore.isInitialized && !exitingAfterResumeSave) saveResume()
        closePhoneSessions()
        super.onStop()
    }

}


internal data class BrowseSnapshot(
    val title: String, val kind: String, val item: JSONObject, val pluginId: String,
    val songs: List<Song>, val page: Int, val more: Boolean, val status: String,
    val albums: List<JSONObject>, val info: JSONObject?,
)

internal data class BrowseReturn(val page: String, val playerOpen: Boolean, val browse: BrowseSnapshot?, val focusKey: String = "")

internal fun clock(ms: Long) = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)

internal fun directorySize(dir: java.io.File): Long =
    dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

internal fun clearDirectory(dir: java.io.File, except: java.io.File? = null) {
    dir.listFiles()?.forEach { if (it != except) it.deleteRecursively() }
}




@Composable
internal fun LoadingStatus(status: String, fontSize: Int) {
    if (status.startsWith("正在加载")) PrismLoadingSkeleton(status)
    else Text(status, color = LocalPrismColors.current.muted, fontSize = fontSize.sp)

}
