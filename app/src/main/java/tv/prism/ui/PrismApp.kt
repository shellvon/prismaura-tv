package tv.prism.ui

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clipToBounds
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.tv.material3.Text
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import tv.prism.ui.playback.QueueDialog
import tv.prism.ui.settings.PluginSourceRow
import tv.prism.MainActivity
import tv.prism.catalog.Discovery
import tv.prism.ui.widgets.FocusAnchor
import tv.prism.library.Song
import tv.prism.TrackMenuTarget
import tv.prism.library.SavedSubject
import tv.prism.CollectionMenuTarget
import tv.prism.directorySize
import tv.prism.clearDirectory
import tv.prism.remote.PhoneRemoteCommands
import tv.prism.library.LibraryBackup
import tv.prism.sources.Plugin
import tv.prism.library.primaryKeys
import tv.prism.ui.widgets.requestIfAttached
import tv.prism.ui.widgets.revealFocusRow
import tv.prism.ui.widgets.requestAfterScroll
import tv.prism.catalog.subjectTitle
import tv.prism.playback.PlaybackPhase
import tv.prism.playback.qualityOptions
import tv.prism.playback.ActiveQueue
import tv.prism.playback.PlaybackService
import tv.prism.sources.requestRevision
import tv.prism.DISCOVERY_FRESH_MS
import tv.prism.ui.widgets.LocalPrismColors
import tv.prism.ui.widgets.prismColors
import tv.prism.ui.widgets.LocalAuraMotion
import tv.prism.ui.widgets.LocalPlaybackActive
import tv.prism.ui.widgets.LocalMarqueeSuspended
import tv.prism.ui.widgets.PrismContentDialog
import tv.prism.ui.widgets.PrismAction
import tv.prism.ui.widgets.PrismPagePadding
import tv.prism.ui.widgets.PrismBackground
import tv.prism.ui.home.HomePage
import tv.prism.ui.widgets.FocusRowRevealSpec
import tv.prism.ui.discovery.RecommendationShelf
import tv.prism.ui.home.HomePageState
import tv.prism.ui.home.HomePageFocus
import tv.prism.ui.home.HomePlaylistOrigin
import tv.prism.ui.home.homeSectionOptions
import tv.prism.ui.home.HomePageActions
import tv.prism.ui.widgets.PrismDetailHero
import tv.prism.ui.widgets.focusAnchor
import tv.prism.ui.widgets.PrismShelfHeader
import tv.prism.LoadingStatus
import tv.prism.ui.widgets.rememberVerticalFocusSearch
import tv.prism.ui.widgets.PrismSheetCard
import tv.prism.ui.widgets.PrismTrackRow
import tv.prism.ui.widgets.PrismPageHead
import tv.prism.CardFocusRow
import tv.prism.ui.widgets.RecommendationSkeleton
import tv.prism.ui.widgets.PrismEmptyState
import tv.prism.ui.widgets.PrismChartCard
import tv.prism.ui.search.SearchPage
import tv.prism.ui.search.SearchPageFocus
import tv.prism.ui.search.SearchPageActions
import tv.prism.ui.library.TrackActionsMenu
import tv.prism.playback.alternateSongSources
import tv.prism.ui.playback.OtherSourcesDialog
import tv.prism.ui.library.SubjectActionsMenu
import tv.prism.ui.library.CollectionActionsMenu
import tv.prism.ui.library.LibraryPage
import tv.prism.ui.library.PlaylistImportDialog
import tv.prism.ui.library.PlaylistImportDraft
import tv.prism.ui.library.LibraryPageFocus
import tv.prism.ui.library.LibraryPageActions
import tv.prism.ui.settings.PluginConfig
import tv.prism.ui.settings.SettingsPage
import tv.prism.ui.settings.AppearanceSettings
import tv.prism.ui.settings.PlaybackSettings
import tv.prism.sources.PluginWorkers
import tv.prism.ui.settings.AboutSettings
import tv.prism.ui.settings.DiscoverySettings
import tv.prism.ui.settings.SourcesPage
import tv.prism.ui.playback.PrismMiniPlayer
import tv.prism.ui.widgets.StatusOverlay
import tv.prism.playback.qualityLadder
import tv.prism.ui.playback.PrismPlayer
import tv.prism.lyrics.LyricSources
import tv.prism.lyrics.LyricCalibration
import tv.prism.playback.PlaybackMode
import tv.prism.ui.playback.PrismScreenOff
import tv.prism.ui.playback.MvPlayer
import tv.prism.ui.widgets.PrismActionMenu
import tv.prism.ui.widgets.PrismMenuAction
import tv.prism.ui.widgets.AppUpdateDialog
import tv.prism.ui.widgets.PrismMenuSection
import tv.prism.ui.widgets.rowFocus
import tv.prism.lyrics.withoutOpeningHeading

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun MainActivity.PrismApp() {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            withFrameNanos { }
            startPlaybackAndMaintenance()
        }
        val track = queue.getOrNull(queueIndex)
        val visibleLyrics = remember(lyrics, hideHeading, track) {
            if (hideHeading && track != null) lyrics.withoutOpeningHeading(track.title, track.artist) else lyrics
        }
        val state by plugins.state.collectAsState()
        val updateState by appUpdates.state.collectAsState()
        val libraryState by library.state.collectAsState()
        /* Disabled sources stay installed but disappear from pickers, search and discoverySession.recommendations. */
        /* Sync rewrites every Plugin object, so keying effects on the list glued the rails to disk
           writes. What actually changes the view is the set of sources, their version and config. */
        val pluginSignature = remember(state.plugins) { state.plugins.joinToString("|") {
            "${it.id}:${it.enabled}:${it.source.hashCode()}:${it.metadata}:${it.variables}"
        } }
        var guideOpen by remember { mutableStateOf(false) }
        val activePlugins = remember(state.plugins) { state.plugins.filter { it.enabled } }
        /* 徽标只承诺能兑现的能力：有 mv 字段但没有 getMvSource 的插件不亮 MV。 */
        val mvCapableIds = remember(state.plugins) { state.plugins.filter { it.capabilities.has("mvSource") }.mapTo(HashSet()) { it.id } }
        val recommendationSource = remember(activePlugins, recommendationPluginId) {
            Discovery.select(activePlugins, recommendationPluginId) { it.recommendations }
        }
        val chartSource = remember(activePlugins, chartPluginId) {
            Discovery.select(activePlugins, chartPluginId) { it.charts }
        }
        val artistSeeds = remember(libraryState, state.plugins) { Discovery.artists(libraryState, state.plugins) }
        val savedArtists = libraryState.subjects.filter { it.kind == "artist" }
        val savedAlbums = libraryState.subjects.filter { it.kind == "album" }
        val searchState = searchViewModel.state.collectAsState()
        val searchNavigation by remember(searchState) { derivedStateOf { searchState.value.navigation() } }
        val libraryPageState = libraryViewModel.state.collectAsState()
        val selectedList by remember(libraryPageState) { derivedStateOf { libraryPageState.value.list } }
        val pluginPageState = pluginViewModel.state.collectAsState()
        val configured by remember(pluginPageState) { derivedStateOf { pluginPageState.value.configuredId } }
        val settingsUi by settingsViewModel.state.collectAsState()
        val sourceConfigFocus = remember { mutableMapOf<String, FocusAnchor>() }
        /* 歌单 is one tab with two shapes: the cover grid ("playlists") and, after opening a
           card, that playlist's own table ("playlist:<id>"). Focus effects need both, so the
           reading lives here rather than inside the page composable. */
        val showingPlaylists = selectedList == "playlists"
        val selectedPlaylist = if (selectedList.startsWith("playlist:"))
            libraryState.playlists.firstOrNull { "playlist:${it.id}" == selectedList } else null
        val importDraft = remember { PlaylistImportDraft() }
        var importDialog by remember { mutableStateOf(false) }
        /* Keep the selected source across phone autofill and full remote operations. */
        var pendingSong by remember { mutableStateOf<Song?>(null) }
        var playerSourceChooser by remember { mutableStateOf<Pair<Song, List<Plugin>>?>(null) }
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
        var resumeAfterMv by remember { mutableStateOf(false) }
        /* Manage mode covers one list at a time: song keys, saved subject keys, or local playlist ids. */
        /* Manage owns selecting; the confirmation dialog owns the irreversible part. */
        var confirmBatchOpen by remember { mutableStateOf(false) }
        var batchRunning by remember { mutableStateOf(false) }
        val libraryBatchConfirmFocus = remember { FocusAnchor() }
        /* Long-press on a tab opens the list-level menu (play all / import / manage):
           toolbar actions stay reachable without crossing five tabs. */
        var libraryListMenu by remember { mutableStateOf(false) }
        val settingsSection = settingsUi.section
        var homePlaylistOrigin by remember { mutableStateOf<HomePlaylistOrigin?>(null) }
        var returningHomePlaylist by remember { mutableStateOf<HomePlaylistOrigin?>(null) }
        fun openPlaylists() {
            homePlaylistOrigin = null
            libraryViewModel.open("playlists")
            page = "library"
        }
        fun returnToHomePlaylist(origin: HomePlaylistOrigin) {
            if (origin.playlistId != null && libraryViewModel.state.value.list == "playlist:${origin.playlistId}")
                libraryViewModel.open("playlists")
            homePlaylistOrigin = null
            returningHomePlaylist = origin
            page = "home"
        }
        fun openSources(add: Boolean) {
            guideOpen = false
            settingsViewModel.open("音源")
            page = "plugins"
            pluginViewModel.showAdd(add)
        }
        var cacheBytes by remember { mutableStateOf<Long?>(null) }
        var clearingCache by remember { mutableStateOf(false) }
        @OptIn(coil.annotation.ExperimentalCoilApi::class)
        fun clearCache() {
            if (!clearingCache) lifecycleScope.launch {
                clearingCache = true
                try {
                    withContext(Dispatchers.IO) {
                        val images = imageLoader.diskCache
                        clearDirectory(cacheDir, except = images?.directory?.toFile())
                        images?.clear()
                    }
                    getSharedPreferences("discovery", Context.MODE_PRIVATE).edit().clear().apply()
                    hotSearchCache.clear()
                    discoverySession.invalidateChartCache()
                    status = "缓存已清理"
                    chartSource?.let { discoverySession.loadCharts(it) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    status = "清理缓存失败：${e.message ?: "未知错误"}"
                } finally {
                    clearingCache = false
                }
            }
        }
        LaunchedEffect(page, settingsSection, clearingCache) {
            if (page == "plugins" && settingsSection == "关于" && !clearingCache)
                cacheBytes = withContext(Dispatchers.IO) { directorySize(cacheDir) }
        }
        /* Tabs switch lists, so a selection carried across would arm the next tab's verb
           against items the remote never chose. */
        LaunchedEffect(selectedList) { confirmBatchOpen = false }
        var mvResolving by remember { mutableStateOf(false) }
        var mvError by remember(queue.getOrNull(queueIndex)?.key) { mutableStateOf("") }
        fun openMv(song: Song?) {
            if (song == null || mvResolving) return
            mvResolving = true
            mvError = ""
            lifecycleScope.launch {
                reportPlayback("正在解析 MV…")
                try {
                    val source = withTimeoutOrNull(15_000) { plugins.resolveVideo(song) }
                        ?: error("音源没有返回可播放的 MV 地址，或请求已超时")
                    if (queue.getOrNull(queueIndex)?.key != song.key) return@launch
                    resumeAfterMv = controller?.playWhenReady == true
                    controller?.pause(); activeMv = source; reportPlayback("")
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    if (queue.getOrNull(queueIndex)?.key == song.key) {
                        mvError = "MV 解析失败：${e.message ?: "音源不可用"}"
                        reportPlayback(mvError)
                    }
                }
                finally { mvResolving = false }
            }
        }

        /* 快照与导入命令读「选中且仍可用」的音源；弹窗内的列表另用 collectAsState 保证重组。 */
        fun importCapablePlugins() = plugins.state.value.plugins.filter { it.enabled && (it.metadata.optBoolean("importSheet") || it.metadata.optBoolean("importItem")) }
        fun importTargetPlugin() = importDraft.sourceId?.let { id -> importCapablePlugins().firstOrNull { it.id == id } }

        fun openPhoneHub(entry: String? = null) {
            phoneSessions.remoteEntry = entry ?: when {
                page == "plugins" -> "settings"
                page == "search" || queue.isEmpty() -> "search"
                else -> "play"
            }
            /* Reopening the QR after hiding it reuses the live session, so the phone page that
               is already open keeps the same address. A leftover config form is not a remote. */
            if (phoneSessions.showRemoteQr()) return
            val commands = PhoneRemoteCommands(
                isRestoring = { restoringBackup },
                playback = PhoneRemoteCommands.Playback(
                    toggle = ::togglePhonePlayback,
                    step = { delta ->
                        require(queue.isNotEmpty()) { "播放列表为空" }
                        step(delta)
                    },
                    favorite = {
                        val song = queue.getOrNull(queueIndex) ?: error("还没有正在播放的歌曲")
                        toggleFavorite(song)
                    },
                    seek = { target ->
                        val limit = durationMs.takeIf { it > 0 } ?: target
                        seekPlayback(target.coerceIn(0L, limit))
                    },
                    playQueueItem = { index, key ->
                        require(queue.getOrNull(index)?.key == key) { "播放列表已变化，请重新选择歌曲" }
                        playAt(index, openPlayer = false)
                    },
                    queuePage = { offset ->
                        require(offset == -1 || offset in queue.indices) { "播放列表已变化，请重新定位" }
                        phoneQueueOffset = offset
                    },
                    quality = { tier ->
                        if (queue.isNotEmpty() && queue.getOrNull(queueIndex)?.pluginId != "local") switchQuality(tier)
                    },
                ),
                search = PhoneRemoteCommands.Search(
                    start = { query ->
                        check(contentTasks.values.none { it.running }) { "请等待当前导入或音源操作完成" }
                        searchViewModel.editDraft(query)
                        playerOpen = false
                        page = "search"
                        search(query)
                    },
                    select = { generation, key, play, next ->
                        require(searchType == "music" && generation == searchSession.generation) { "搜索结果已更新，请重新选择" }
                        val song = results.firstOrNull { it.key == key } ?: error("这首歌已不在搜索结果中，请重新搜索")
                        if (play) playSingle(song) else enqueueSong(song, next)
                    },
                    more = {
                        check(!busy && hasMore && searchType == "music") { "当前无法加载更多" }
                        search(searchTerm, append = true, focusResults = false)
                    },
                    type = ::changePhoneSearchType,
                    source = ::changePhoneSearchSource,
                    subscription = ::searchUsingSubscription,
                ),
                sources = PhoneRemoteCommands.Sources(
                    plugins = plugins,
                    runContentTask = ::runContentTask,
                    importPlaylist = { name, url -> importPlaylist(name, url, importTargetPlugin()) },
                    resolveReplacement = ::resolvePluginReplacement,
                ),
                app = PhoneRemoteCommands.App(
                    preference = ::updatePreference,
                    toggleMiniLyrics = { updatePreference("miniLyrics", (!showMiniLyrics).toString()) },
                    homeShelf = { id, visible ->
                        require(homeSectionOptions.any { it.first == id }) {
                            "栏目不存在"
                        }
                        if (visible == (id in homeShelfHidden)) toggleHomeShelf(id)
                    },
                    showPlayer = { playerOpen = it },
                    page = { destination ->
                        page = destination
                        playerOpen = false
                    },
                    backup = { withContext(Dispatchers.IO) { LibraryBackup.build(this@PrismApp, library, plugins).toString() } },
                    restore = ::restorePhoneBackup,
                ),
            )
            phoneSessions.openRemote(
                snapshot = { phoneSnapshot(importTargetPlugin()?.displayName.orEmpty(), importCapablePlugins().size) },
                command = commands::execute,
            )
        }
        val homeFocus = remember { FocusRequester() }
        val updatePromptFocus = remember { FocusAnchor() }
        val showUpdatePrompt = updateState.release != null && !updateState.skipped && updateState.remind
        val heroFocus = remember { FocusAnchor() }
        val homeBodyFocus = remember { FocusAnchor() }
        ReportDrawnWhen { homeBodyFocus.attached }
        val homeListState = rememberLazyListState()
        val homeRowStates = remember { mutableMapOf<String, LazyListState>() }
        val homeFocusMemory = remember { mutableStateOf<FocusRequester?>(null) }
        val homePlayerFocus = remember { FocusAnchor() }
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
        val libraryPlaylistListState = rememberLazyListState()
        /* 播放会把这首歌挪到「最近」第一条；那一行点名失败时，先滚回页首再落列表锚点。 */
        val librarySongListState = rememberSaveable(selectedList, saver = LazyListState.Saver) { LazyListState() }
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
        val discoverListState = rememberLazyListState()
        val recommendationRow = rememberSaveable(recommendationSource?.id, saver = LazyListState.Saver) { LazyListState() }
        val seedRow = rememberLazyListState()
        val artistRow = rememberLazyListState()
        val albumRow = rememberLazyListState()
        /* The card the remote last held, with its row: the bar is outside this list, so Up
           from it has to be told where to go back to. */
        val discoverFocusMemory = remember { mutableStateOf<Pair<FocusRequester, Int>?>(null) }
        /* Same idea for the two lists that keep their head inside the scroll (a detail's hero and
           shelf titles, a chart's first row): scroll far enough and the node the mini bar would
           name for Up no longer exists, so Up looks like a dead key. The row records itself. */
        val detailFocusMemory = remember { mutableStateOf<Pair<FocusRequester, Int>?>(null) }
        /* 搜索页的迷你条上键恢复：记住结果行与其列表下标，行被回收时先滚回。
           第三位是网格内的列号（歌曲列表恒为 -1），供 down 键点名同列卡片。 */
        val (committedTerm, displayedSearchType, displayedSearchSources, hasSearchResults, editingSearch) = searchNavigation
        val searchFocusMemory = remember(committedTerm, displayedSearchType, pluginSignature, displayedSearchSources) {
            mutableStateOf<Triple<FocusRequester, Int, Int>?>(null)
        }
        val searchSuggestionsVisible = !busy && editingSearch
        val searchListState = rememberSaveable(committedTerm, displayedSearchType, saver = LazyListState.Saver) { LazyListState() }
        val searchNavigationScope = rememberCoroutineScope()
        DisposableEffect(page, committedTerm, displayedSearchType, pluginSignature, displayedSearchSources, searchSuggestionsVisible, searchSession.generation) {
            onDispose { searchNavigationScope.coroutineContext.cancelChildren() }
        }
        /* The 音乐榜单 shelf header is one item past the optional 歌手/专辑 shelves above it;
           a chart card's row index is this plus its chunk index. */
        val discoverChartsOffset = 1 + (if (artistSeeds.isNotEmpty()) 2 else 0) +
            (if (recommendationSource != null) 1 else 0) +
            (if (savedArtists.isNotEmpty()) 2 else 0) + (if (savedAlbums.isNotEmpty()) 2 else 0)
        /* 歌手/专辑 is sectioned; a card deep in it has to be scrolled back into view before
           its FocusRequester exists after returning from the detail it opened. */
        val librarySubjectListState = rememberSaveable(selectedList, saver = LazyListState.Saver) { LazyListState() }
        /* A chart keeps its scroll while a detail opened from one of its rows is on screen. */
        val chartSongListState = remember(discoverySession.chartSelection) { LazyListState() }
        val pluginsFocus = remember { FocusRequester() }
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
            "home" -> when {
                showUpdatePrompt && updatePromptFocus.attached -> updatePromptFocus
                heroFocus.attached -> heroFocus
                else -> homeBodyFocus
            }
            "search" -> searchInputFocus
            "library" -> if (selectedPlaylist != null) playlistBackFocus else libraryTabs[libraryTabKey]
            "plugins" -> if (configured == null) sectionFocus.getValue(settingsSection) else configEntryFocus
            "discover" -> when {
                browseItem != null -> detailHeaderFocus
                discoverySession.chartSelection != null -> discoverEntryFocus.takeIf { discoverySession.chartSongs.isNotEmpty() }
                else -> discoverContentEntry()
            }
            else -> null
        }
        val pageEntryFocus = entryFocus(page)
        val navTabs = listOf("home" to homeFocus, "search" to searchFocus, "library" to libraryFocus, "discover" to discoverFocus, "plugins" to pluginsFocus)
        var navFocused by remember { mutableStateOf(false) }
        fun activeTabFocus(): FocusRequester =
            navTabs.firstOrNull { it.first == page }?.second ?: homeFocus
        val navTabFocus = activeTabFocus()
        fun focusActiveTab() {
            activeTabFocus().requestFocus()
        }
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
                    if (target?.requestAfterScroll { } != true)
                        (if (queue.isEmpty()) queueRootFocus else queuePlaybackFocus).requestAfterScroll { }
                    return@LaunchedEffect
                }
                repeat(4) {
                    withFrameNanos { }
                    if (target != null && runCatching { target.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                }
                val content = when (page) {
                    "home" -> homeBodyFocus
                    "search" -> searchResultsFocus
                    "library" -> libraryContentFocus
                    "discover" -> discoverEntryFocus
                    else -> null
                }
                if (!content.requestIfAttached() && !pageEntryFocus.requestIfAttached()) focusActiveTab()
            }
        }
        val pageScope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current
        /* Moving along the top bar previews each page; only leaving the bar hands focus to the
           body. Without this, the page focus effect and the preview would fight each other. */
        /* Settings rows remember themselves here; the mini player's Up returns to the last
           one instead of the page entry (the rail). */
        val bodyFocusMemory = remember { mutableStateOf<FocusRequester?>(null) }
        LaunchedEffect(configured, settingsSection) { bodyFocusMemory.value = null }
        val enterPage: (String) -> Unit = enter@{ target ->
            val origin = homePlaylistOrigin
            if (target == "home" && origin != null) {
                returnToHomePlaylist(origin)
                return@enter
            }
            homePlaylistOrigin = null
            page = target
            pageScope.launch {
                repeat(12) {
                    withFrameNanos { }
                    if (page != target || playerOpen) return@launch
                    if (entryFocus(target)?.requestIfAttached() == true) return@launch
                }
                if (page == target && !playerOpen) focusActiveTab()
            }
        }
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
        var resolvingSubject by remember { mutableStateOf("") }
        fun searchSubject(name: String, kind: String, song: Song) {
            val albumCapable = plugins.state.value.plugins.any { it.enabled && supportsSubjectSearch(it, "album") }
            val query = if (kind == "album" && song.artist.isNotBlank() && song.artist != "未知歌手") "${song.artist} $name" else name
            searchViewModel.editDraft(query)
            searchType = if (kind == "album" && !albumCapable) "music" else kind
            page = "search"
            if (!busy || searchSession.isRunning) search(query, navigating = true)
        }
        /* A song carries only the artist and album names; the detail calls need the source's own
           entity object. Our plugins also emit artistId/albumId (a Prism-only extra), which
           opens the detail directly and avoids guessing among search hits. Sources that only
           give names fall back to searching the same source. */
        fun openSubject(song: Song, kind: String, originFocus: String = "", menu: Boolean = false) {
            subjectJob?.cancel()
            resolvingSubject = ""
            queueOpen = false
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
            val originPage = page
            val originPlayer = playerOpen
            val originItem = browseItem
            resolvingSubject = "正在查找$label · $name"
            subjectJob = lifecycleScope.launch {
                try {
                    val response = plugins.client.call(plugin.source, plugin.variables, "search",
                        JSONArray().put(name).put(1).put(kind), plugin.id) as? JSONObject ?: error("音源未返回搜索结果")
                    val data = response.optJSONArray("data") ?: JSONArray()
                    if (originPage != page || originPlayer != playerOpen || originItem !== browseItem) return@launch
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
                    if (originPage != page || originPlayer != playerOpen || originItem !== browseItem) return@launch
                    status = "打开$label 失败：${e.message ?: "未知错误"}，已改为搜索"
                    playerOpen = false
                    searchSubject(name, kind, song)
                } finally { if (kotlinx.coroutines.currentCoroutineContext().isActive) resolvingSubject = "" }
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
                requester != null && runCatching { requester.requestFocus() }.getOrDefault(false)
            val remembered = when (page) {
                "home" -> homeFocusMemory.value
                "search" -> searchFocusMemory.value?.first
                "library" -> bodyFocusMemory.value
                "discover" -> detailFocusMemory.value?.first ?: discoverFocusMemory.value?.first
                else -> null
            }
            repeat(3) { withFrameNanos { }; if (tryRequest(remembered)) return true }
            when (page) {
                "home" -> return homeBodyFocus.requestIfAttached()
                "search" -> {
                    val index = searchFocusMemory.value?.second ?: 0
                    runCatching { searchListState.scrollToItem(index) }
                    repeat(4) { withFrameNanos { }; if (tryRequest(remembered)) return true }
                    return searchResultsFocus.requestIfAttached()
                }
                "library" -> return libraryContentFocus.requestIfAttached()
                "discover" -> {
                    val memory = detailFocusMemory.value ?: discoverFocusMemory.value
                    val list = when {
                        browseItem != null -> browseSongListState
                        discoverySession.chartSelection != null -> chartSongListState
                        else -> discoverListState
                    }
                    memory?.let { runCatching { list.scrollToItem(it.second) } }
                    repeat(4) { withFrameNanos { }; if (tryRequest(remembered)) return true }
                    return discoverEntryFocus.requestIfAttached()
                }
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
            if (navFocused || playerOpen) return@LaunchedEffect
            /* Back from a detail restores the card that opened it; that effect owns focus. */
            if (pendingReturnFocus.isNotBlank() || (page == "home" && returningHomePlaylist != null))
                return@LaunchedEffect
            withFrameNanos { }
            when (page) {
                "home" -> entryFocus("home").requestIfAttached()
                "search" -> searchInputFocus.requestIfAttached()
                /* A detail is opened from a card on another page: the effect below waits for its
                   songs and lands on the first one. Taking the bar's tab here would park the
                   remote on the top bar until the user pressed Down again. */
                "discover" -> if (browseItem == null && discoverySession.chartSelection == null) discoverContentEntry().requestIfAttached()
                /* Opening 我的 from 设置 should land on the list tab that is lit, not on the
                   bar's copy of it — otherwise the highlight and the remote disagree. */
                "library" -> if (selectedPlaylist != null) playlistBackFocus.requestIfAttached() else libraryTabs[libraryTabKey].requestIfAttached()
                "plugins" -> if (configured == null) sectionFocus.getValue(settingsSection).requestIfAttached() else configEntryFocus.requestIfAttached()
            }
        }
        /* 发现 swaps between the chart grid and two lists. Swapping recomposes away the node that
           held focus, so hand focus to whatever the new shape shows first — or back to the bar
           when the list came back empty, which would otherwise strand focus nowhere. */
        LaunchedEffect(page, browseItem, discoverySession.chartSelection) {
            if (navFocused || playerOpen) return@LaunchedEffect
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
                if (page == "discover" && browseItem == null && returnKey.startsWith("recommend:")) {
                    val index = discoverySession.recommendations.indexOfFirst {
                        returnKey == "recommend:${recommendationSource?.id}:${it.optString("id")}"
                    }
                    if (index >= 0) {
                        discoverListState.scrollToItem(0)
                        recommendationRow.scrollToItem(index)
                    }
                }
                /* A chart card is a row of the 发现 list. Scroll it back into view before
                   asking for focus: below the fold the node does not exist yet. */
                if (returnKey.startsWith("chart:")) {
                    val id = returnKey.substringAfter(':')
                    val index = discoverySession.charts.indexOfFirst { it.optString("id") == id }
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
                    if (requester != null && runCatching { requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                }
                /* The origin was a list row that is gone, or a card the parent no longer shows.
                   Land on the page entry rather than leaving the remote with no focus owner. */
                withFrameNanos { }
                when (page) {
                    "search" -> searchInputFocus.requestIfAttached()
                    "library" -> if (selectedPlaylist != null) playlistBackFocus.requestIfAttached() else libraryTabs[libraryTabKey].requestIfAttached()
                    "home" -> entryFocus("home").requestIfAttached()
                    "discover" -> if (browseItem == null && discoverySession.chartSelection == null) discoverContentEntry().requestIfAttached()
                }
                if (page != "discover") return@LaunchedEffect
            }
            if (page != "discover") return@LaunchedEffect
            when {
                browseItem != null && discoverySession.chartSelection == null -> snapshotFlow {
                    !browseLoading && browseStatus != "正在加载…"
                }.first { it }
                discoverySession.chartSelection != null -> snapshotFlow {
                    discoverySession.chartSongs.isNotEmpty() || discoverySession.chartDetailStatus != "正在加载榜单…"
                }.first { it }
                else -> Unit
            }
            if (discoverySession.chartSelection != null && discoverySession.chartSongs.isEmpty()) { focusActiveTab(); return@LaunchedEffect }
            /* A detail opens on its own actions: the hero is at the top of the scroll, so the
               remote starts on 播放全部 instead of teleporting the list past the cover. */
            val target = when {
                browseItem != null -> detailHeaderFocus
                discoverySession.chartSelection != null -> discoverEntryFocus
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
                if (requester != null && runCatching { requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            }
            focusActiveTab()
        }
        val closeConfig: () -> Unit = {
            val origin = configured
            pluginViewModel.configure(null)
            pageScope.launch {
                repeat(6) {
                    withFrameNanos { }
                    if (origin?.let { sourceConfigFocus[it] }.requestIfAttached()) return@launch
                }
                sourceActionFocus.requestIfAttached()
            }
        }
        fun openPlaylistTable(id: String) {
            libraryViewModel.open("playlist:$id")
        }
        fun closePlaylistTable() {
            val list = libraryViewModel.state.value.list
            if (!list.startsWith("playlist:")) return
            val id = list.removePrefix("playlist:")
            val origin = homePlaylistOrigin
            if (origin?.playlistId == id) returnToHomePlaylist(origin)
            else libraryViewModel.closePlaylist(id)
        }
        /* A phone import also chooses the new playlist, without changing the open TV page. */
        LaunchedEffect(playlistToShow) {
            val id = playlistToShow ?: return@LaunchedEffect
            playlistToShow = null
            if (libraryState.playlists.any { it.id == id }) {
                if (page == "library") openPlaylistTable(id) else libraryViewModel.open("playlist:$id")
            }
        }
        fun enterLibraryManage() {
            libraryViewModel.manage(true)
            /* Rows trade 播放 for a checkbox, so the remote belongs on the first item: one press
               selects, Down walks. */
            pageScope.launch { withFrameNanos { }; libraryContentFocus.requestIfAttached() }
        }
        fun exitLibraryManage() {
            libraryViewModel.manage(false)
            confirmBatchOpen = false
            pageScope.launch { withFrameNanos { }; libraryHeaderFocus.requestIfAttached() }
        }
        fun runLibraryBatch() {
            val keys = libraryViewModel.state.value.selected
            if (keys.isEmpty()) { status = "先用确定键选中要处理的条目"; return }
            val playlistId = selectedPlaylist?.id
            if (batchRunning) return
            batchRunning = true
            lifecycleScope.launch {
                try {
                    when {
                        selectedList == "recent" -> { library.removeRecent(keys); status = "已删除 ${keys.size} 条播放记录" }
                        selectedList in setOf("artists", "albums") -> { library.removeSubjects(keys); status = "已移除 ${keys.size} 个收藏" }
                        showingPlaylists -> { library.removePlaylists(keys); status = "已删除 ${keys.size} 个歌单" }
                        playlistId != null -> { library.removeSongsFrom(playlistId, keys); status = "已从歌单移出 ${keys.size} 首" }
                        else -> { library.removeFavorites(keys); status = "已取消收藏 ${keys.size} 首" }
                    }
                    exitLibraryManage()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { status = e.message ?: "操作失败"; confirmBatchOpen = false }
                finally { batchRunning = false }
            }
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
                page == "library" && libraryViewModel.state.value.managing -> exitLibraryManage()
                /* A playlist's table is one step inside the 歌单 tab, so Back steps out of the
                   table rather than leaving 我的 for 首页. */
                page == "library" && libraryViewModel.state.value.list.startsWith("playlist:") -> closePlaylistTable()
                page == "library" && homePlaylistOrigin != null -> homePlaylistOrigin?.let(::returnToHomePlaylist)
                browseItem != null && page == "discover" -> leaveBrowse()
                discoverySession.chartSelection != null && page == "discover" -> {
                    /* Back belongs on the chart card you chose, not on whichever one the grid
                       happens to show first. */
                    pendingReturnFocus = "chart:" + discoverySession.chartSelection?.optString("id").orEmpty()
                    discoverySession.closeChart()
                }
                configured != null -> closeConfig()
                page != "home" -> {
                    pendingSong = null; page = "home"
                    /* The page body recomposes away and takes the focused node with it, so the
                       remote is handed to the 首页 tab — Back must never strand focus nowhere. */
                    pageScope.launch { withFrameNanos { }; focusActiveTab() }
                }
                else -> {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (playing && now - exitArmedAt > 2_000) {
                        exitArmedAt = now
                        status = "再按一次返回键退出"
                        return@BackHandler
                    }
                    saveResume()
                    exitingAfterResumeSave = true
                    recovery?.ready?.cancel(); recovery = null
                    resolving?.cancel(); streamSwap?.cancel()
                    /* Back can arrive while the controller is still connecting; stopping only the
                       connected instance would let the audio survive the exit. */
                    val ready = controller
                    if (ready != null) runCatching { resetStream(ready) }
                    else connection?.let { pending ->
                        ActiveQueue.attempt.clear()
                        pending.addListener({
                            runCatching { pending.get() }.getOrNull()?.let { runCatching { it.stop(); it.clearMediaItems() } }
                        }, ContextCompat.getMainExecutor(this@PrismApp))
                    }
                    queue = emptyList(); queueIndex = -1; playing = false; lyrics = emptyList(); loadedKey = ""; ActiveQueue.source = null
                    playbackPhase = PlaybackPhase.Idle
                    lyricCalibrationMs = 0L
                    stopService(Intent(this@PrismApp, PlaybackService::class.java)); finish()
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
        val recommendationRevision = remember(recommendationSource) { recommendationSource?.requestRevision() }
        LaunchedEffect(recommendationRevision, page == "discover") {
            if (page == "discover") discoverySession.prepareTags(recommendationSource)
        }
        LaunchedEffect(recommendationRevision, discoverySession.recommendationTag.optString("id"), page == "discover") {
            if (page == "discover") discoverySession.refreshRecommendations(recommendationSource)
        }
        val chartRevision = remember(chartSource) { chartSource?.requestRevision() }
        LaunchedEffect(firstFrameDrawn, chartRevision) {
            if (firstFrameDrawn) discoverySession.changeChartSource(chartSource)
        }
        LaunchedEffect(page) {
            if (firstFrameDrawn && page == "discover") discoverySession.refreshChartsOnEntry(chartSource)
        }
        LaunchedEffect(page, chartRevision, discoverySession.charts.take(3).map { it.optString("id") }) {
            if (page == "discover" && chartSource != null) discoverySession.loadChartPreviews(chartSource)
        }
        val hostState by lifecycle.currentStateFlow.collectAsState()
        val motionEnabled = hostState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) && !reduceMotion && (android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled())
        /* The screen-off fade and wake fade are the screen's own state, not 动效: they must run
           even when 减少动效 is on. Only hidden background motion obeys the switch here. */
        CompositionLocalProvider(
            LocalPrismColors provides prismColors(theme),
            LocalAuraMotion provides (motionEnabled && !(playerScreenOff && screenOffSettled)),
            LocalPlaybackActive provides playbackActive,
            LocalMarqueeSuspended provides (repeatedDirection != KeyEvent.KEYCODE_UNKNOWN),
        ) {
            val c = LocalPrismColors.current
            Box(Modifier.fillMaxSize()) {
            /* Cold start: the light comes into the room before the furniture does. Drawn above
               everything by zIndex because it is inserted first, and only once per process. */
            val addFocus = remember { FocusRequester() }
            fun fillSourceOnPhone(onReturn: () -> Unit) {
                phoneSessions.openForm(listOf("url" to "插件或订阅地址"), mapOf("url" to pluginViewModel.state.value.address),
                    title = "填写音源地址", message = "填写后回到电视确认并添加，不会自动导入。",
                    onReturn = onReturn) {
                    pluginViewModel.editAddress(it["url"].orEmpty())
                }
            }
            if (guideOpen) {
                val guideFocus = remember { FocusRequester() }
                PrismContentDialog("添加音源指南", "", { guideOpen = false }, guideFocus) {
                    Text("音源在哪里找？", color = c.foreground, fontSize = 18.sp)
                    Text("音源是连接音乐服务的 MusicFree 插件。到你信任的插件作者项目主页，按说明复制插件（.js）或订阅（.json）地址。歌曲分享链接不能作为音源地址。", color = c.muted, fontSize = 15.sp)
                    Text("如何添加音源？", color = c.foreground, fontSize = 18.sp)
                    Text("在电视上输入地址，或用同一网络的手机扫码填写。最后在电视上确认添加。", color = c.muted, fontSize = 15.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrismAction("我有地址，去添加", { openSources(true) }, Modifier.focusRequester(guideFocus), primary = true)
                        PrismAction("用手机填写", {
                            openSources(true)
                            fillSourceOnPhone { pageScope.launch { repeat(2) { withFrameNanos { } }; runCatching { addFocus.requestFocus() } } }
                        }, icon = "qr")
                    }
                }
            }
            Box(Modifier.fillMaxSize().focusProperties { canFocus = !playerOpen }) {
            PrismBackground {
                Column(Modifier.fillMaxSize().padding(horizontal = PrismPagePadding, vertical = 20.dp)) {
                    TopNavigation(page, navTabs, pageEntryFocus,
                        onPreviewPage = { page = it }, onEnterPage = enterPage,
                        onNavFocusChanged = { navFocused = it }, onOpenPhone = { openPhoneHub() },
                        restoreHomeOnDown = homePlaylistOrigin != null)
                    Spacer(Modifier.height(18.dp))
                    if (state.error != null || libraryState.error != null) Text(state.error ?: libraryState.error.orEmpty(), fontSize = 12.sp, color = c.accent, modifier = Modifier.padding(bottom = 8.dp), maxLines = 2)
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                        Box(Modifier.fillMaxSize()) {
                        when (page) {
                            "home" -> HomePage(
                                state = HomePageState(
                                    library = libraryState,
                                    hiddenShelves = homeShelfHidden,
                                    hasActiveSources = activePlugins.isNotEmpty(),
                                    hasInstalledSources = state.plugins.isNotEmpty(),
                                    hasQueue = queue.isNotEmpty(),
                                    song = queue.getOrNull(queueIndex),
                                    playing = playing && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED,
                                    updateVersion = updateState.release?.tag.takeIf { showUpdatePrompt },
                                    radioActive = ActiveQueue.radio,
                                    radioLoading = radioLoading,
                                    radioStatus = radioStatus,
                                    hasChartSource = chartSource != null,
                                    chartLoading = discoverySession.chartLoading,
                                    chartStatus = discoverySession.chartStatus,
                                    chartTitle = discoverySession.charts.firstOrNull()?.optString("title").orEmpty(),
                                ),
                                focus = HomePageFocus(navTabFocus, miniFocus, heroFocus, homeBodyFocus, homePlayerFocus,
                                    updatePromptFocus, radioCardFocus, homeListState, homeRowStates, ::detailFocus, { entryFocus("home") }),
                                actions = HomePageActions(
                                    showUpdate = { updateDialogOpen = true },
                                    skipUpdate = appUpdates::skip,
                                    openSources = ::openSources,
                                    showGuide = { guideOpen = true },
                                    continuePlayback = {
                                        if (queue.isNotEmpty()) playerOpen = true
                                        else if (activePlugins.isEmpty()) { settingsViewModel.open("音源"); page = "plugins" }
                                        else page = "search"
                                    },
                                    startRadio = { startRadio() },
                                    openRadioMenu = { radioMenuOpen = true },
                                    openChart = {
                                        val target = discoverySession.charts.firstOrNull()
                                        val source = chartSource
                                        if (source == null) { settingsViewModel.open("音源"); page = "plugins" }
                                        else {
                                            page = "discover"
                                            if (target != null) discoverySession.openChart(source, target)
                                            else if (!discoverySession.chartLoading) lifecycleScope.launch { discoverySession.loadCharts(source) }
                                        }
                                    },
                                    discover = { page = "discover" },
                                    manageShelves = { settingsViewModel.open("推荐"); page = "plugins" },
                                    openPlaylists = { origin ->
                                        homePlaylistOrigin = origin
                                        libraryViewModel.open(origin.playlistId?.let { "playlist:$it" } ?: "playlists")
                                        page = "library"
                                    },
                                    playShelf = { playCollection(it) },
                                    playSong = { playSingle(it) },
                                    openTrackMenu = { song, list, requester ->
                                        menuReturnFocus = requester
                                        trackMenu = TrackMenuTarget(song, list)
                                    },
                                ),
                                returnToPlaylist = returningHomePlaylist,
                                onPlaylistFocusRestored = { returningHomePlaylist = null },
                                onCardFocused = {
                                    homeFocusMemory.value = it
                                    bodyFocusMemory.value = it
                                },
                            )
                            "discover" -> Column(Modifier.fillMaxSize()) {
                                val plugin = chartSource
                                val selectedChart = discoverySession.chartSelection
                                /* A detail page keeps its head out of the scrolling list, so the
                                   geometric search from here never reached the bar and Up stranded
                                   the ring on 播放全部. */
                                val headUp = Modifier.focusProperties { up = navTabFocus }
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
                                            .ifBlank { if (browseKind == "artist") browseInfo?.optString("avatar").orEmpty().ifBlank { item.optString("avatar") } else "" }
                                        val publish = browseInfo?.optString("publishDate").orEmpty().ifBlank {
                                            browseInfo?.optLong("publishTime", 0L)?.takeIf { it > 0 }
                                                ?.let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(it)) }.orEmpty()
                                        }.ifBlank { item.optString("publishDate").ifBlank { item.optString("date") }.ifBlank { item.optString("description") } }
                                        val subjectMeta = buildList {
                                            fun count(key: String) = listOf(browseInfo?.optLong(key, 0L) ?: 0L, item.optLong(key, 0L)).firstOrNull { it > 0 }
                                            when (browseKind) {
                                                "artist" -> {
                                                    count("fans")?.let { add("$it 粉丝") }
                                                    count("worksNum")?.let { add("$it 首作品") }
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
                                        var albumRowFocused by remember(item) { mutableStateOf(false) }
                                        val detailDefaultReveal = LocalBringIntoViewSpec.current
                                        val detailReveal = remember(detailDefaultReveal, item) {
                                            FocusRowRevealSpec(detailDefaultReveal) { albumRowFocused }
                                        }
                                        val detailMotion = LocalAuraMotion.current
                                        LaunchedEffect(albumRowFocused) {
                                            if (albumRowFocused) {
                                                withFrameNanos { }
                                                browseSongListState.revealFocusRow(2, detailMotion)
                                            }
                                        }
                                        CompositionLocalProvider(LocalBringIntoViewSpec provides detailReveal) {
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
                                                    val heroUp = Modifier.focusProperties { up = navTabFocus }
                                                    /* 播放全部 belongs to a song list. An artist page has
                                                       two lists (albums and songs), so the button moves down
                                                       beside 热门歌曲 instead of silently choosing one. */
                                                    val playAllInHero = browseKind != "artist" && browseSongs.isNotEmpty()
                                                    PrismAction(if (saved) "已收藏" else "收藏", {
                                                        toggleSubject(browsePluginId, browseKind, item, sourceName)
                                                    }, if (!playAllInHero) heroUp.focusAnchor(detailHeaderFocus) else heroUp,
                                                        icon = if (saved) "heartFilled" else "heart")
                                                    if (playAllInHero) {
                                                        PrismAction("播放全部", { playCollection(browseSongs.toList()) }, heroUp.focusAnchor(detailHeaderFocus), primary = true, icon = "play")
                                                        PrismAction("随机播放", { playCollection(browseSongs.toList(), shuffle = true) }, heroUp, icon = "shuffle")
                                                    }
                                                    else if (queue.isNotEmpty()) PrismAction("打开播放器", { playerOpen = true }, heroUp)
                                                }
                                            }
                                            if (browseKind == "artist" && (browseAlbums.isNotEmpty() || browseAlbumStatus.isNotBlank())) {
                                                item { PrismShelfHeader("专辑", browseAlbums.size.takeIf { it > 0 }, "", {}) }
                                                if (browseAlbums.isEmpty()) item {
                                                    LoadingStatus(browseAlbumStatus, 13)
                                                } else item {
                                                    val firstSongFocus = browseSongs.firstOrNull()?.let { trackFocus(it.key) }
                                                    val (albumLayout, focusAlbumExit) = rememberVerticalFocusSearch()
                                                    val albumNavigation = albumLayout.onPreviewKeyEvent { event ->
                                                        if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionDown || firstSongFocus == null) return@onPreviewKeyEvent false
                                                        focusAlbumExit(event.key) { firstSongFocus }
                                                    }
                                                    CompositionLocalProvider(LocalBringIntoViewSpec provides detailDefaultReveal) {
                                                    LazyRow(albumNavigation.onFocusChanged { albumRowFocused = it.hasFocus }.focusGroup(),
                                                        state = browseAlbumRowState, horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
                                                        itemsIndexed(browseAlbums) { index, album ->
                                                            val albumId = album.optString("id")
                                                            val albumMeta = listOf(
                                                                album.optString("artist"),
                                                                album.optString("publishDate").ifBlank { album.optString("date") }.ifBlank { album.optString("description") },
                                                                listOf(album.optInt("worksNum", 0), album.optInt("size", 0)).firstOrNull { it > 0 }?.let { "$it 首" }.orEmpty(),
                                                            ).filter { it.isNotBlank() }.joinToString(" · ")
                                                            PrismSheetCard(
                                                                album.optString("title").ifBlank { album.optString("name").ifBlank { "未知专辑" } },
                                                                album.optString("artwork"),
                                                                albumMeta,
                                                                Modifier.focusRequester(detailFocus("album:$browsePluginId:$albumId")).then(
                                                                    if (index == 0) Modifier.focusProperties {
                                                                        up = if (detailHeaderFocus.attached) detailHeaderFocus.requester else navTabFocus
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
                                            }
                                            item {
                                                PrismShelfHeader(
                                                    if (browseKind == "artist") "热门歌曲" else "歌曲",
                                                    browseSongs.size.takeIf { it > 0 },
                                                    if (browseKind == "artist" && browseSongs.isNotEmpty()) "播放全部" else "",
                                                    { if (browseSongs.isNotEmpty()) playCollection(browseSongs.toList()) },
                                                    actionIcon = "play",
                                                    primaryAction = true,
                                                    actionEdges = Modifier.focusAnchor(artistPlayAllFocus)
                                                        .focusProperties {
                                                            if (browseAlbums.isEmpty()) up = if (detailHeaderFocus.attached) detailHeaderFocus.requester else navTabFocus
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
                                                LoadingStatus(browseStatus, 13)
                                            }
                                            itemsIndexed(browseSongs) { index, song ->
                                                val rowFocus = trackFocus(song.key)
                                                detailCardFocus["song:${song.key}"] = rowFocus
                                                PrismTrackRow(song, index, { playSingle(song) }, showAlbum = browseKind != "album",
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
                                                                    up = if (detailHeaderFocus.attached) detailHeaderFocus.requester else navTabFocus
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
                                    }
                                } else if (selectedChart != null) {
                                    PrismPageHead(selectedChart.optString("title").ifBlank { "榜单" }) {
                                        if (discoverySession.chartSongs.isNotEmpty()) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                PrismAction(
                                                    "播放全部",
                                                    { playCollection(discoverySession.chartSongs) },
                                                    Modifier.focusAnchor(detailHeaderFocus).then(headUp),
                                                    primary = true,
                                                    icon = "play",
                                                )
                                                PrismAction(
                                                    "随机播放",
                                                    { playCollection(discoverySession.chartSongs, shuffle = true) },
                                                    headUp,
                                                    icon = "shuffle",
                                                )
                                            }
                                        }
                                    }
                                    if (discoverySession.chartSongs.isEmpty()) LoadingStatus(discoverySession.chartDetailStatus, 13)
                                    else {
                                        LazyColumn(state = chartSongListState, modifier = Modifier.weight(1f)) {
                                            itemsIndexed(discoverySession.chartSongs) { index, song ->
                                            val rowFocus = if (index == 0) discoverEntryFocus.requester else trackFocus(song.key)
                                            detailCardFocus["song:${song.key}"] = rowFocus
                                            PrismTrackRow(song, index, { playSingle(song) }, modifier = if (index == 0) Modifier.focusAnchor(discoverEntryFocus).focusProperties { if (detailHeaderFocus.attached) up = detailHeaderFocus.requester } else Modifier.focusRequester(rowFocus), current = queue.getOrNull(queueIndex)?.key == song.key, qualityTag = qualityTagOf(song), showMv = song.pluginId in mvCapableIds, onFocus = { detailFocusMemory.value = rowFocus to index }, onLongPress = { trackMenu = TrackMenuTarget(song, originFocus = "song:${song.key}") })
                                            }
                                        }
                                    }
                                } else {
                                val focusScope = rememberCoroutineScope()
                                val cardCoordinates = remember { mutableMapOf<FocusRequester, LayoutCoordinates>() }
                                var focusedShelfCard by remember { mutableStateOf<FocusRequester?>(null) }
                                var focusedDiscoverRow by remember { mutableStateOf<Int?>(null) }
                                val discoverRowMemory = remember { mutableMapOf<Int, FocusRequester>() }
                                val motion = LocalAuraMotion.current
                                LaunchedEffect(focusedDiscoverRow) {
                                    val row = focusedDiscoverRow ?: return@LaunchedEffect
                                    withFrameNanos { }
                                    discoverListState.revealFocusRow(row, motion)
                                }
                                fun discoverRow(index: Int) = Modifier.onFocusChanged {
                                    if (it.hasFocus) focusedDiscoverRow = index
                                    else if (focusedDiscoverRow == index) focusedDiscoverRow = null
                                }.focusGroup()
                                fun trackCard(requester: FocusRequester, row: Int) = Modifier
                                        .onPlaced { cardCoordinates[requester] = it }
                                        .onFocusChanged { state ->
                                            if (state.isFocused) {
                                                focusedShelfCard = requester
                                                discoverFocusMemory.value = requester to row
                                                discoverRowMemory[row] = requester
                                            } else if (focusedShelfCard === requester) focusedShelfCard = null
                                        }
                                fun centerX(requester: FocusRequester): Float? = cardCoordinates[requester]
                                    ?.takeIf { it.isAttached }?.let { it.positionInRoot().x + it.size.width / 2f }
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
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && recommendationSource != null && discoverySession.recommendations.isEmpty() && !recommendEntryFocus.attached) {
                                        revealRecommendation(recommendFirstCardFocus, recommendEntryFocus); true
                                    } else false
                                }
                                val artistsFirst = savedArtists.isNotEmpty()
                                // Adjacent rows have different card widths; choose by screen position, not item number.
                                val recommendCards = if (recommendationSource != null)
                                    discoverySession.recommendations.map { detailFocus("recommend:${recommendationSource.id}:${it.optString("id")}") }
                                else emptyList()
                                val seedCards = artistSeeds.map { detailFocus("seed:${it.key}") }
                                val artistCards = savedArtists.map { detailFocus("${it.kind}:${it.pluginId}:${it.data.optString("id")}") }
                                val albumCards = savedAlbums.map { detailFocus("${it.kind}:${it.pluginId}:${it.data.optString("id")}") }
                                val chartCardRows = discoverySession.charts.chunked(3).map { group -> group.map { detailFocus("chart:${it.optString("id")}") } }
                                val seedListRow = if (recommendationSource == null) 1 else 2
                                val artistListRow = seedListRow + if (seedCards.isEmpty()) 0 else 2
                                val albumListRow = artistListRow + if (artistCards.isEmpty()) 0 else 2
                                val shelfRows = buildList {
                                    if (recommendCards.isNotEmpty()) add(CardFocusRow(recommendCards, recommendationRow) to 0)
                                    if (seedCards.isNotEmpty()) add(CardFocusRow(seedCards, seedRow) to seedListRow)
                                    if (artistCards.isNotEmpty()) add(CardFocusRow(artistCards, artistRow) to artistListRow)
                                    if (albumCards.isNotEmpty()) add(CardFocusRow(albumCards, albumRow) to albumListRow)
                                    chartCardRows.forEachIndexed { index, cards ->
                                        add(CardFocusRow(cards) to (discoverChartsOffset + index))
                                    }
                                }
                                fun keepHorizontalFocus(index: Int, count: Int) = Modifier.focusProperties {
                                    if (index == 0) left = FocusRequester.Cancel
                                    if (index == count - 1) right = FocusRequester.Cancel
                                }
                                fun stepRow(direction: Key, row: List<FocusRequester>, column: Int): Modifier {
                                    val ordinal = shelfRows.indexOfFirst { it.first.cards === row }
                                    if (ordinal < 0) return Modifier
                                    val (target, targetRow) = shelfRows.getOrNull(ordinal + if (direction == Key.DirectionUp) -1 else 1)
                                        ?: return Modifier.focusProperties {
                                            if (direction == Key.DirectionUp) {
                                                up = if (recommendEntryFocus.attached) recommendEntryFocus.requester else navTabFocus
                                            } else down = miniFocus
                                        }
                                    return Modifier.onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown || event.key != direction) return@onPreviewKeyEvent false
                                        val x = row.getOrNull(column)?.let(::centerX)
                                        focusScope.coroutineContext.cancelChildren()
                                        fun nearest(): FocusRequester? {
                                            val visible = target.horizontal?.layoutInfo?.visibleItemsInfo?.map { it.index }
                                                ?: target.cards.indices.toList()
                                            return visible.mapNotNull { index ->
                                                target.cards.getOrNull(index)?.let { card ->
                                                    centerX(card)?.let { cardX ->
                                                        card to if (x == null) kotlin.math.abs(index - column).toFloat()
                                                        else kotlin.math.abs(cardX - x)
                                                    }
                                                }
                                            }.minByOrNull { it.second }?.first
                                        }
                                        focusScope.launch {
                                            discoverListState.revealFocusRow(targetRow, motion)
                                            val previous = discoverRowMemory[targetRow]
                                            val index = target.cards.indexOf(previous)
                                            if (index >= 0 && previous != null && previous.requestAfterScroll {
                                                    target.horizontal?.scrollToItem(index)
                                                }) return@launch
                                            repeat(6) {
                                                withFrameNanos { }
                                                val requester = nearest() ?: return@repeat
                                                if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@launch
                                            }
                                        }
                                        true
                                    }
                                }
                                /* MusicFree has no global artist/album listing to fill the
                                   sketch's grids with, so these shelves show what you kept from
                                   a detail page; the discoverySession.charts below remain source-driven. */
                                val defaultReveal = LocalBringIntoViewSpec.current
                                val verticalReveal = remember(defaultReveal) {
                                    FocusRowRevealSpec(defaultReveal) { focusedDiscoverRow != null }
                                }
                                CompositionLocalProvider(LocalBringIntoViewSpec provides verticalReveal) {
                                LazyColumn(state = discoverListState, modifier = Modifier.weight(1f).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(22.dp),
                                    /* 末排榜单行在最大滚动时贴着视口底边，聚焦边框没有呼吸空间；
                                       底部留白让最后一行能完整滚入。 */
                                    contentPadding = PaddingValues(bottom = 14.dp)) {
                                    if (recommendationSource != null) item(key = "discover-recommendations") {
                                        CompositionLocalProvider(LocalBringIntoViewSpec provides defaultReveal) {
                                            RecommendationShelf(
                                                recommendationSource.displayName, discoverySession.recommendations, discoverySession.recommendationTags, discoverySession.recommendationTag,
                                                discoverySession.recommendationLoading, discoverySession.recommendationMore, discoverySession.recommendationStatus,
                                                onSelectTag = { discoverySession.recommendationTag = it },
                                                onMore = { lifecycleScope.launch { discoverySession.loadRecommendations(recommendationSource, append = true) } },
                                                up = navTabFocus, entry = recommendEntryFocus,
                                                firstCard = recommendFirstCardFocus, below = discoverBelowFocus,
                                                cardFocus = { sheet -> detailFocus("recommend:${recommendationSource.id}:${sheet.optString("id")}") },
                                                edges = { index -> trackCard(recommendCards[index], 0).then(stepRow(Key.DirectionDown, recommendCards, index)) }, rowState = recommendationRow, modifier = discoverRow(0),
                                                onMenu = { item, focus -> showSubjectMenu(recommendationSource, "sheet", item, focus) }) {
                                                    openBrowse(recommendationSource, it, "sheet", "recommend:${recommendationSource.id}:${it.optString("id")}")
                                                }
                                        }
                                    }
                                    if (artistSeeds.isNotEmpty()) {
                                        item(key = "discover-seeds-title") { PrismShelfHeader("从常听歌手继续", null, "", {}) }
                                        item(key = "discover-seeds") {
                                            CompositionLocalProvider(LocalBringIntoViewSpec provides defaultReveal) {
                                                LazyRow(state = seedRow, modifier = discoverRow(seedListRow), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
                                                    itemsIndexed(artistSeeds) { index, seed ->
                                                        PrismSheetCard(seed.artist, seed.data.optString("artistArtwork"), "歌手作品 · ${seed.sourceLabel}", portrait = true,
                                                            modifier = Modifier.focusRequester(seedCards[index]).then(trackCard(seedCards[index], seedListRow))
                                                                .then(keepHorizontalFocus(index, seedCards.size))
                                                                .then(if (index == 0) Modifier.focusAnchor(discoverBelowFocus) else Modifier)
                                                                .then(stepRow(Key.DirectionUp, seedCards, index))
                                                                .then(stepRow(Key.DirectionDown, seedCards, index))
                                                                .then(belowUpReveals),
                                                            shelfFocused = focusedShelfCard === seedCards[index],
                                                                onLongPress = {
                                                                openSubject(seed, "artist", "seed:${seed.key}", menu = true)
                                                            }) { openSubject(seed, "artist", "seed:${seed.key}") }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (artistsFirst) {
                                        item(key = "discover-artists-title") { PrismShelfHeader("歌手", null, "", {}) }
                                        item(key = "discover-artists") {
                                            CompositionLocalProvider(LocalBringIntoViewSpec provides defaultReveal) {
                                                LazyRow(state = artistRow, modifier = discoverRow(artistListRow), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
                                                    itemsIndexed(savedArtists) { index, subject ->
                                                        PrismSheetCard(subject.title, subject.artwork, "${subject.kindLabel} · ${subject.sourceName.ifBlank { "已收藏" }}", portrait = subject.kind == "artist",
                                                            modifier = Modifier.focusRequester(artistCards[index]).then(trackCard(artistCards[index], artistListRow))
                                                                .then(keepHorizontalFocus(index, artistCards.size))
                                                                .then(if (artistSeeds.isEmpty() && index == 0) Modifier.focusAnchor(discoverBelowFocus) else Modifier)
                                                                .then(stepRow(Key.DirectionUp, artistCards, index))
                                                                .then(stepRow(Key.DirectionDown, artistCards, index))
                                                                .then(if (artistSeeds.isEmpty()) belowUpReveals else Modifier)
                                                                , shelfFocused = focusedShelfCard === artistCards[index],
                                                                onLongPress = {
                                                                    menuReturnFocus = detailFocus("${subject.kind}:${subject.pluginId}:${subject.data.optString("id")}")
                                                                    subjectMenu = subject
                                                                }) {
                                                            state.plugins.firstOrNull { it.id == subject.pluginId }?.let { openBrowse(it, subject.data, subject.kind) }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (savedAlbums.isNotEmpty()) {
                                        item(key = "discover-albums-title") { PrismShelfHeader("专辑", null, "", {}) }
                                        item(key = "discover-albums") {
                                            CompositionLocalProvider(LocalBringIntoViewSpec provides defaultReveal) {
                                                LazyRow(state = albumRow, modifier = discoverRow(albumListRow), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
                                                    itemsIndexed(savedAlbums) { index, subject ->
                                                        PrismSheetCard(subject.title, subject.artwork, "${subject.kindLabel} · ${subject.sourceName.ifBlank { "已收藏" }}", portrait = subject.kind == "artist",
                                                            modifier = Modifier.focusRequester(albumCards[index]).then(trackCard(albumCards[index], albumListRow))
                                                                .then(keepHorizontalFocus(index, albumCards.size))
                                                                .then(if (artistSeeds.isEmpty() && savedArtists.isEmpty() && index == 0) Modifier.focusAnchor(discoverBelowFocus) else Modifier)
                                                                .then(stepRow(Key.DirectionUp, albumCards, index))
                                                                .then(stepRow(Key.DirectionDown, albumCards, index))
                                                                .then(if (artistSeeds.isEmpty() && savedArtists.isEmpty()) belowUpReveals else Modifier)
                                                                , shelfFocused = focusedShelfCard === albumCards[index],
                                                                onLongPress = {
                                                                    menuReturnFocus = detailFocus("${subject.kind}:${subject.pluginId}:${subject.data.optString("id")}")
                                                                    subjectMenu = subject
                                                                }) {
                                                            state.plugins.firstOrNull { it.id == subject.pluginId }?.let { openBrowse(it, subject.data, subject.kind) }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    item(key = "discover-charts-title") { PrismShelfHeader("音乐榜单", null, "", {}) }
                                    if (discoverySession.charts.isEmpty()) item(key = "chart-loading-or-empty") {
                                        if (discoverySession.chartLoading && (recommendationSource != null || shelfRows.isNotEmpty())) RecommendationSkeleton()
                                        else if (recommendationSource == null && shelfRows.isEmpty()) {
                                            PrismEmptyState(discoverySession.chartStatus, "发现设置", { settingsViewModel.open("推荐"); page = "plugins" },
                                                Modifier.focusAnchor(discoverEntryFocus).focusProperties { up = navTabFocus; down = miniFocus })
                                        } else LoadingStatus(discoverySession.chartStatus, 13)
                                    }
                                    else itemsIndexed(discoverySession.charts.chunked(3), key = { index, group ->
                                        "chart-row:$index:${group.first().optString("id")}"
                                    }) { chunkIndex, group ->
                                        val rowCards = chartCardRows.getOrNull(chunkIndex).orEmpty()
                                        val cardRow = discoverChartsOffset + chunkIndex
                                        Row(
                                            discoverRow(cardRow).fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        ) {
                                            group.forEachIndexed { column, chart ->
                                                val chartId = chart.optString("id")
                                                val cardFocus = detailFocus("chart:$chartId")
                                                PrismChartCard(
                                                    chart.optString("title").ifBlank { "榜单" },
                                                    chart.optString("coverImg").ifBlank { chart.optString("artwork") },
                                                    discoverySession.chartPreviews[chartId].orEmpty(),
                                                    Modifier.weight(1f).focusRequester(cardFocus).then(trackCard(cardFocus, cardRow))
                                                        .focusProperties {
                                                            left = rowCards.getOrNull(column - 1) ?: FocusRequester.Cancel
                                                            right = rowCards.getOrNull(column + 1) ?: FocusRequester.Cancel
                                                        }
                                                        .then(if (artistSeeds.isEmpty() && savedArtists.isEmpty() && savedAlbums.isEmpty() && chart === discoverySession.charts.firstOrNull()) Modifier.focusAnchor(discoverBelowFocus) else Modifier)
                                                        .then(stepRow(Key.DirectionUp, rowCards, column))
                                                        .then(stepRow(Key.DirectionDown, rowCards, column))
                                                        .then(if (artistSeeds.isEmpty() && savedArtists.isEmpty() && savedAlbums.isEmpty() && chunkIndex == 0) belowUpReveals else Modifier)
                                                        , onLongPress = {
                                                            menuReturnFocus = cardFocus
                                                            plugin?.let { subjectMenu = SavedSubject(it.id, "chart", chart, it.displayName, primaryKeys(it.metadata)) }
                                                        },
                                                ) { plugin?.let { discoverySession.openChart(it, chart) } }
                                            }
                                            repeat(3 - group.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                }
                                }
                                }
                            }
                            "search" -> SearchPage(
                                searchViewModel, plugins, activePlugins, pluginSignature, libraryState,
                                searchTypeChoices(activePlugins), searchHistory, recentArtists, hotSearchCache,
                                busy, playerOpen, navFocused, focusSearchResults, queue.getOrNull(queueIndex)?.key, mvCapableIds,
                                SearchPageFocus(
                                    navTabFocus, miniFocus, searchInputFocus, searchSourceFocus, searchChipFocus,
                                    searchResultsFocus, searchMoreFocus, searchListState, searchNavigationScope,
                                    searchFocusMemory, ::detailFocus, ::trackFocus,
                                    { song, requester -> detailCardFocus["song:${song.key}"] = requester },
                                    { bodyFocusMemory.value = it },
                                ),
                                SearchPageActions(
                                    submit = { query, append -> search(query, append = append) },
                                    useMusicType = { searchType = "music" },
                                    changeType = { type, focusResults ->
                                        if (searchType != type) {
                                            searchType = type
                                            searchViewModel.clearResults()
                                            val query = searchViewModel.state.value.draft
                                            if (query.isNotBlank()) search(query, focusResults = focusResults, navigating = true)
                                            else if (!focusResults) searchSession.cancel()
                                        }
                                    },
                                    toggleSource = { id ->
                                        searchSources = if (id == null) emptySet() else if (id in searchSources) searchSources - id else searchSources + id
                                        if (id != null) selectedPlugin = id
                                        focusSearchResults = false
                                        searchViewModel.clearResults()
                                        val query = searchViewModel.state.value.draft
                                        if (query.isNotBlank()) search(query, focusResults = false)
                                    },
                                    cancelResultsFocus = { focusSearchResults = false },
                                    fillOnPhone = { draft, onReturn ->
                                        phoneSessions.openForm(listOf("keyword" to "搜索关键词"), mapOf("keyword" to draft),
                                            title = "填写搜索关键词", message = "填写后回到电视按搜索，不会自动切页或播放。",
                                            onReturn = onReturn) {
                                            searchViewModel.editDraft(it["keyword"].orEmpty().take(120))
                                        }
                                    },
                                    clearHistory = { type ->
                                        if (type == "artist") saveRecentArtists(emptyList())
                                        searchHistory = searchHistory - type
                                        saveSearchHistory()
                                        status = "已清空当前分类的搜索历史"
                                    },
                                    openRecentArtist = { plugin, artist -> openBrowse(plugin, artist.data, "artist", "") },
                                    addSource = { settingsViewModel.open("音源"); page = "plugins" },
                                    play = ::playSingle,
                                    showTrackMenu = { trackMenu = TrackMenuTarget(it, originFocus = "song:${it.key}") },
                                    openEntity = { type, entity ->
                                        state.plugins.firstOrNull { it.id == entity.pluginId }?.let {
                                            if (type == "artist") {
                                                val artist = SavedSubject(it.id, "artist", entity.data, it.displayName, primaryKeys(it.metadata))
                                                saveRecentArtists((listOf(artist) + recentArtists.filterNot { old -> old.key == artist.key }).take(8))
                                            }
                                            openBrowse(it, entity.data, type)
                                        }
                                    },
                                    showEntityMenu = { type, entity, requester ->
                                        state.plugins.firstOrNull { it.id == entity.pluginId }?.let { showSubjectMenu(it, type, entity.data, requester) }
                                    },
                                ),
                            )
                            "library" -> LibraryPage(
                                libraryViewModel, libraryState, queue.getOrNull(queueIndex)?.key, mvCapableIds,
                                LibraryPageFocus(
                                    navTabFocus, miniFocus, libraryContentFocus, libraryHeaderFocus, playlistBackFocus,
                                    libraryEmptyFocus, libraryPlayAllFocus, libraryImportFocus, libraryBatchConfirmFocus, libraryTabs,
                                    libraryPlaylistListState, librarySongListState, librarySubjectListState, ::detailFocus,
                                    ::trackFocus, { bodyFocusMemory.value = it },
                                    { list, requester -> detailCardFocus["playlist:${list.id}"] = requester },
                                    { song, requester -> detailCardFocus["song:${song.key}"] = requester },
                                ),
                                LibraryPageActions(
                                    playSong = { playSingle(it) },
                                    playAll = { playCollection(it) },
                                    openSubject = { subject ->
                                        state.plugins.firstOrNull { it.id == subject.pluginId }?.let { openBrowse(it, subject.data, subject.kind) }
                                    },
                                    openPlaylist = ::openPlaylistTable,
                                    closePlaylist = ::closePlaylistTable,
                                    importPlaylist = { importDialog = true },
                                    fillOnPhone = { field, query, onReturn, onQuery ->
                                        phoneSessions.openForm(
                                            listOf("keyword" to field), mapOf("keyword" to query),
                                            title = "填写查找关键词",
                                            message = "提交后，电视会直接筛选当前列表。",
                                            onReturn = onReturn,
                                        ) { values -> onQuery(values["keyword"].orEmpty().take(120)) }
                                    },
                                    discover = { page = "discover" },
                                    enterManage = ::enterLibraryManage,
                                    exitManage = ::exitLibraryManage,
                                    onBack = { onBackPressedDispatcher.onBackPressed() },
                                    confirmSelection = {
                                        if (libraryViewModel.state.value.selected.isEmpty()) status = "先用确定键选中要处理的条目"
                                        else confirmBatchOpen = true
                                    },
                                    showListMenu = { menuReturnFocus = it; libraryListMenu = true },
                                    showTrackMenu = { song, list -> trackMenu = TrackMenuTarget(song, list, "song:${song.key}") },
                                    showSubjectMenu = { subject, requester -> menuReturnFocus = requester; subjectMenu = subject },
                                    showPlaylistMenu = { list, requester ->
                                        menuReturnFocus = requester
                                        collectionMenu = CollectionMenuTarget(list.name, list.songs, list.id)
                                    },
                                ),
                            )
                            else -> {
                                val selected = state.plugins.firstOrNull { it.id == configured }
                                val settingsScroll = rememberScrollState()
                                /* Each section starts at its own top: a scroll position carried
                                   across would hide the new section's header and entry row. */
                                LaunchedEffect(settingsSection) { settingsScroll.scrollTo(0) }
                                if (selected != null) PluginConfig(
                                    selected, configEntryFocus, bodyFocusMemory, closeConfig,
                                    plugins, busy, lifecycleScope, { status = it },
                                    searchSource = { searchUsingSources(setOf(selected.id)) },
                                    onPhoneForm = { fields, values, onReturn, submit -> phoneSessions.openForm(fields, values, onReturn = onReturn, onSubmit = submit) },
                                )
                                else SettingsPage(
                                    settingsSection, settingsSections, settingsScroll, navTabFocus, sectionFocus, sectionEntryFocus,
                                    selectSection = { settingsViewModel.open(it) },
                                ) { edgeToRail, entryUpScrollsBack, entryEdge ->
                                    when (settingsSection) {
                                        "外观" -> AppearanceSettings(theme, reduceMotion,
                                            sectionEntryFocus.getValue("外观"), sectionFocus.getValue("外观").requester,
                                            navTabFocus, entryUpScrollsBack, edgeToRail, bodyFocusMemory, ::updatePreference)
                                        "播放" -> PlaybackSettings(quality, autoSourceFallback, playMode, pluginWorkerLimit,
                                            PluginWorkers.automatic(this@PrismApp), showMiniLyrics, lyricFontLevel, hideHeading, subLyrics,
                                            lyricLrclib, lyricKugou, lyricNetease,
                                            sectionEntryFocus.getValue("播放"), entryEdge, edgeToRail, bodyFocusMemory,
                                            ::updatePreference, { setAutoSourceFallback(it) })
                                    }
                                    if (settingsSection == "关于") AboutSettings(
                                        updateState, cacheBytes, clearingCache, sectionEntryFocus.getValue("关于"),
                                        entryEdge, edgeToRail, bodyFocusMemory, ::checkAppUpdates, appUpdates::setRemind,
                                        clearCache = ::clearCache,
                                        onBackup = { openPhoneHub("settings") },
                                    )
                                    if (settingsSection == "推荐") DiscoverySettings(
                                        activePlugins, recommendationPluginId, chartPluginId, homeShelfHidden,
                                        sectionEntryFocus.getValue("推荐"), entryEdge, edgeToRail, bodyFocusMemory,
                                        ::updatePreference, ::toggleHomeShelf, ::openPlaylists,
                                    )
                                    if (settingsSection == "音源") SourcesPage(
                                        pluginViewModel, plugins, state, busyState, subscriptionUpdateProgress,
                                        lifecycleScope, navTabFocus, sourceActionFocus, addFocus, entryEdge,
                                        edgeToRail, bodyFocusMemory, ::showPluginImport, { status = it },
                                        ::searchUsingSubscription, ::fillSourceOnPhone,
                                    ) { plugin, owned, onRemoved, edge ->
                                        PluginSourceRow(plugin, owned, { pluginViewModel.configure(plugin.id) }, onRemoved,
                                            sourceConfigFocus.getOrPut(plugin.id) { FocusAnchor() }, edge)
                                    }
                                }
                            }
                        }
                    }
                    }
                    PrismMiniPlayer(
                        song = queue.getOrNull(queueIndex),
                        playbackSource = playbackSong(),
                        playbackMessage = playbackPhase.takeIf { it.holdControls }?.message.orEmpty(),
                        playing = playing && !playerOpen && activeMv == null,
                        // Read the playback clock inside the mini player so progress ticks do not
                        // invalidate the surrounding page content (including home shelf cards).
                        progress = {
                            val total = durationMs
                            if (total > 0) (positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f
                        },
                        lyrics = visibleLyrics,
                        lyricPosition = { positionMs - lyricCalibrationMs },
                        showLyric = showMiniLyrics,
                        visible = !playerOpen && activeMv == null,
                        onOpen = { if (queue.getOrNull(queueIndex) == null) page = "search" else playerOpen = true },
                        onToggle = { togglePlayback() },
                        openFocus = miniFocus,
                        /* After a search result plays, Up should reach the list you were
                           browsing. The text field is only the landing spot while the page
                           has no results to return to. */
                        upTarget = if (page == "search" && hasSearchResults) searchResultsFocus else pageEntryFocus,
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
                            page == "discover" && (browseItem != null || discoverySession.chartSelection != null) -> {
                                val detailList = if (browseItem != null) browseSongListState else chartSongListState
                                {
                                    val memory = detailFocusMemory.value
                                    if (memory == null) false
                                    else {
                                        pageScope.launch {
                                            val (requester, row) = memory
                                            if (!requester.requestAfterScroll { detailList.scrollToItem(row) }
                                                && !detailHeaderFocus.requestIfAttached()) focusActiveTab()
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
                                            if (!runCatching { requester.requestFocus() }.getOrDefault(false)
                                                && !discoverEntryFocus.requestIfAttached()) focusActiveTab()
                                        }
                                        true
                                    }
                                }
                            }
                            else -> null
                        },
                    )
                }
                StatusOverlay({ status }) { page == "search" && busy }
            }
            }
            AnimatedVisibility(visible = playerOpen, enter = fadeIn(tween(if (motionEnabled) 180 else 0)), exit = fadeOut(tween(if (motionEnabled) 120 else 0))) {
                Box(Modifier.fillMaxSize()) {
                    val song = queue.getOrNull(queueIndex)
                    val playerVisible = playerOpen && !playerScreenOff && activeMv == null
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
                    if (!playerScreenOff || !screenOffSettled) CompositionLocalProvider(
                        LocalAuraMotion provides (motionEnabled && playerVisible),
                    ) {
                        PrismBackground {
                            val streamSong = playbackSong() ?: song
                            val otherSources = remember(streamSong, activePlugins) {
                                streamSong?.let { alternateSongSources(it, activePlugins) }.orEmpty()
                            }
                            val externalLyricSources = enabledExternalLyricSources()
                            PrismPlayer(song, visibleLyrics, lyricStatus, { positionMs }, durationMs, playing, playMode,
                                song != null && libraryState.favorites.any { it.key == song.key }, tierFor(song), playingQuality,
                                playbackIndices().indexOf(queueIndex).coerceAtLeast(0), queue.size, playbackPhase, playbackDetail,
                                streamFormat,
                                streamSeekable = streamSeekable,
                                playbackSource = streamSong,
                                onOtherSources = if (streamSong != null && otherSources.isNotEmpty()) {
                                    { playerSourceChooser = streamSong to otherSources }
                                } else null,
                                hasMv = song != null && song.hasMv && song.pluginId in mvCapableIds,
                                mvResolving = mvResolving,
                                mvError = mvError,
                                mvOpen = activeMv != null,
                                visible = playerVisible,
                                overlayOpen = playerSourceChooser != null || queueOpen || radioMenuOpen || phoneSessions.dialogVisible || pendingSong != null ||
                                    trackMenu != null || subjectMenu != null || collectionMenu != null || updateDialogOpen,
                                readPosition = { trustedPosition() },
                                lyricFontLevel = lyricFontLevel,
                                onLyricFontLevel = { level ->
                                    updatePreference("lyricFontLevel", level)
                                },
                                hideHeading = hideHeading, onHideHeading = { updatePreference("hideHeading", it.toString()) },
                                subLyrics = subLyrics,
                                onSubLyrics = { show ->
                                    updatePreference("subLyrics", show.toString())
                                },
                                lyricSource = lyricSourceOverride.orEmpty(),
                                lyricSourceLabel = lyricSourceLabel,
                                lyricActiveSource = lyricActiveSource,
                                lyricLrclib = LyricSources.LRCLIB in externalLyricSources,
                                lyricKugou = LyricSources.KUGOU in externalLyricSources,
                                lyricNetease = LyricSources.NETEASE in externalLyricSources,
                                lyricProbes = lyricProbes,
                                onProbeLyrics = { probeLyricSources() },
                                onLyricSource = { selectLyricSource(it) },
                                lyricOffsetMs = lyricCalibrationMs,
                                onLyricCalibration = { value ->
                                    lyricCalibrationMs = value
                                    playbackSong()?.let { LyricCalibration.save(this@PrismApp, it.key, value) }
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
                                onSongMenu = { current, focus ->
                                    menuReturnFocus = focus
                                    trackMenu = TrackMenuTarget(current, list = "player")
                                },
                                onArtist = song?.let { { openSubject(it, "artist") } },
                                onAlbum = song?.let { { openSubject(it, "album") } },
                                onQueue = { queueOpen = true },
                                onMv = { openMv(song) },
                                /* A tier the plugin left unanswered is dropped, so the pill only cycles
                                   through streams this song can actually play. */
                                tiers = availableTiers.filterNot { it in unsupportedTiers[playbackSong()?.key].orEmpty() },
                                ladder = ladder,
                                /* 本地文件没有可切换的音质，pluginId 是 "local"。行要置灰，而不是
                                   摆一排按了没反应的箭头。 */
                                qualityEnabled = (playbackSong() ?: song)?.pluginId?.let { it != "local" } ?: false,
                                qualitySwitching = qualitySwitching,
                                qualityNotice = qualityNotice,
                                onSelectQuality = { tier -> switchQuality(tier) })
                        }
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
                fun closeRadioMenu() {
                    radioMenuOpen = false
                    pageScope.launch {
                        withFrameNanos { }
                        if (!playerOpen) {
                            if (page == "home") radioCardFocus.requestIfAttached() else discoverEntryFocus.requestIfAttached()
                        }
                    }
                }
                PrismActionMenu("极光电台",
                    if (radioStatus.contains("暂") || radioStatus.contains("失败")) radioStatus
                    else if (playerOpen) "保留当前歌曲和进度，为接下来的队列穿插新发现" else "从收藏与常听出发，穿插榜单和推荐歌单中的曲目",
                    onDismiss = { closeRadioMenu() }, actions = buildList {
                        add(PrismMenuAction(if (ActiveQueue.radio) "更换接下来的歌曲" else "开启电台", "radio") {
                            closeRadioMenu(); startRadio(restart = true, keepCurrent = playerOpen || ActiveQueue.radio)
                        })
                        if (ActiveQueue.radio) add(PrismMenuAction("退出极光电台", "close", description = "保留当前队列") {
                            leaveRadio(); saveResume(); closeRadioMenu()
                        })
                    })
            }
            if (updateDialogOpen) AppUpdateDialog(updateState, appUpdates.releasePage,
                onCheck = { checkAppUpdates() }, onDownload = { downloadAppUpdate() },
                onInstall = { if (updateJob?.isActive != true) updateJob = lifecycleScope.launch { appUpdates.install(this@PrismApp) } },
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
            var subjectHint by remember(resolvingSubject) { mutableStateOf(false) }
            LaunchedEffect(resolvingSubject) {
                if (resolvingSubject.isNotEmpty()) { delay(400); subjectHint = true }
            }
            if (subjectHint) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopEnd) {
                Text(resolvingSubject, Modifier.background(c.panel, RoundedCornerShape(10.dp)).padding(12.dp),
                    color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (queueOpen) {
                val following = when {
                    queue.isEmpty() -> null
                    ActiveQueue.radio || playMode == PlaybackMode.SEQUENTIAL -> queue.getOrNull(queueIndex + 1)
                    playMode == PlaybackMode.REPEAT_ONE -> queue.getOrNull(queueIndex)
                    else -> queue[nextIndex(1)]
                }
                QueueDialog(queue, playbackIndices(), queueIndex, following, ActiveQueue.radio,
                    libraryState.favorites, queuePlaybackFocus, queueRootFocus, ::detailFocus, motionEnabled,
                    onClose = { queueOpen = false },
                    onSongMenu = { song, focus ->
                        menuReturnFocus = focus
                        trackMenu = TrackMenuTarget(song, list = "queue", originFocus = "queue:${song.key}")
                    },
                    onPlay = { playAt(it, openPlayer = false) })
            }
            val alternateFavoriteKeys = if (playerSourceChooser != null || trackMenu != null) {
                remember(libraryState.favorites) { libraryState.favorites.map { it.key }.toSet() }
            } else emptySet()
            playerSourceChooser?.let { (song, sources) ->
                OtherSourcesDialog(song, sources, plugins, alternateFavoriteKeys, onDismiss = { playerSourceChooser = null },
                    onPlay = { selected ->
                        playerSourceChooser = null
                        if (playbackSong()?.key == song.key) {
                            val at = if (playbackPhase == PlaybackPhase.Finished) 0L else trustedPosition()
                            playAt(queueIndex, openPlayer = false, startAt = at, sourceSong = selected)
                        } else status = "当前曲目已切换，请重新打开其他音源"
                    })
            }
            trackMenu?.let { target ->
                var choosingSource by remember(target) { mutableStateOf(false) }
                val available = remember(target, activePlugins) { alternateSongSources(target.song, activePlugins) }
                if (choosingSource) {
                    // Reuse the parent menu's focus restoration when the chooser closes.
                    val sources = remember(target) { available }
                    OtherSourcesDialog(target.song, sources, plugins, alternateFavoriteKeys, onDismiss = { trackMenu = null },
                        onPlay = { song ->
                            trackMenu = null
                            queueOpen = false
                            playAlternateSource(song)
                        })
                } else TrackActionsMenu(target, libraryState, activePlugins,
                    openOtherSources = if (available.isEmpty()) null else ({ choosingSource = true }),
                    openSubject = { song, kind, origin -> openSubject(song, kind, origin) },
                    find = { term, type ->
                        trackMenu = null
                        queueOpen = false
                        playerOpen = false
                        searchViewModel.editDraft(term)
                        searchType = type
                        page = "search"
                        if (term.isNotBlank() && (!busy || searchSession.isRunning)) search(term, navigating = true)
                    },
                    onDismiss = { trackMenu = null })
            }
            subjectMenu?.let { subject ->
                SubjectActionsMenu(subject, libraryState, activePlugins,
                    readOrigin = { detailCardFocus.entries.firstOrNull { it.value === menuReturnFocus }?.key },
                    onDismiss = { subjectMenu = null })
            }
            collectionMenu?.let { CollectionActionsMenu(it, onDismiss = { collectionMenu = null }) }
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
                PrismActionMenu(menuTitle, onDismiss = { libraryListMenu = false }, actions = buildList {
                        if (menuSongs.isNotEmpty()) {
                            add(PrismMenuAction("播放全部", "play") { libraryListMenu = false; playCollection(menuSongs) })
                            add(PrismMenuAction("随机播放", "shuffle") { libraryListMenu = false; playCollection(menuSongs, shuffle = true) })
                        }
                        if (showingPlaylists) {
                            add(PrismMenuAction("导入歌单", "import", navigates = true) { libraryListMenu = false; importDialog = true })
                        }
                        if (menuManageable) {
                            add(PrismMenuAction("批量管理", "list", navigates = true, section = PrismMenuSection.MANAGEMENT) { libraryListMenu = false; enterLibraryManage() })
                        }
                    })
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
                Dialog(onDismissRequest = { if (!batchRunning) confirmBatchOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; cancelFocus.requestFocus() }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(Modifier.width(560.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                            .background(Brush.verticalGradient(listOf(c.panel, c.background.copy(alpha = .985f))))
                            .border(1.dp, c.line.copy(alpha = .75f), androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                            .padding(horizontal = 30.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(batchVerb, color = c.foreground, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                            Text("已选 ${libraryViewModel.state.collectAsState().value.selected.size} $batchUnit，$batchNote", color = c.muted, fontSize = 13.sp)
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                                PrismAction("取消", { if (!batchRunning) confirmBatchOpen = false }, Modifier.focusRequester(cancelFocus))
                                PrismAction(if (batchRunning) "处理中…" else "确认", { runLibraryBatch() }, primary = true)
                            }
                        }
                    }
                }
            }
            var batchWasOpen by remember { mutableStateOf(false) }
            LaunchedEffect(confirmBatchOpen) {
                if (confirmBatchOpen) batchWasOpen = true
                else if (batchWasOpen) {
                    withFrameNanos { }
                    if (!libraryBatchConfirmFocus.requestIfAttached() && !libraryHeaderFocus.requestIfAttached()) {
                        if (!libraryContentFocus.requestIfAttached()) libraryEmptyFocus.requestIfAttached()
                    }
                    batchWasOpen = false
                }
            }
            if (importDialog) PlaylistImportDialog(importDraft,
                onDismiss = { importDialog = false }, onReopen = { importDialog = true },
                onError = { status = it })
            pluginReplacements.firstOrNull()?.let { replacement ->
                key(replacement.incoming.id) {
                    val first = remember { FocusRequester() }
                    val newVersion = replacement.incoming.metadata.optString("version").ifBlank { "未知版本" }
                    PrismContentDialog("发现同名插件", "${replacement.incoming.name} · $newVersion。替换会沿用原配置和登录状态；独立安装使用独立配置，请确认来源可信。", {
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
            if (phoneSessions.dialogVisible) phoneSessions.activeSession?.let { input ->
                PhoneConnectionDialog(input.url, phoneSessions.remoteMode, phoneSessions.remoteEntry, phoneSessions.formTitle,
                    onDismiss = { if (phoneSessions.remoteMode) phoneSessions.dialogVisible = false else phoneSessions.dismissActive() },
                    onEndRemote = phoneSessions::dismissActive)
            }
        }
    }
