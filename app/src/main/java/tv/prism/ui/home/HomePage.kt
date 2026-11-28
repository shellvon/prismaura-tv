package tv.prism.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tv.prism.library.LibraryState
import tv.prism.library.MusicPlaylist
import tv.prism.library.Song
import tv.prism.ui.widgets.FocusAnchor
import tv.prism.ui.widgets.FocusRowRevealSpec
import tv.prism.ui.widgets.LocalPrismColors
import tv.prism.ui.widgets.LocalAuraMotion
import tv.prism.ui.widgets.revealFocusRow
import tv.prism.ui.widgets.requestAfterScroll
import tv.prism.ui.widgets.requestIfAttached
import tv.prism.ui.widgets.focusAnchor
import tv.prism.ui.widgets.downTo
import tv.prism.ui.widgets.PrismAction
import tv.prism.ui.widgets.HomeSourceGuide
import tv.prism.ui.widgets.PrismHero
import tv.prism.ui.widgets.AuraRadioCard
import tv.prism.ui.widgets.AuraHotCard
import tv.prism.ui.widgets.upTo
import tv.prism.ui.widgets.PrismShelfHeader
import tv.prism.ui.widgets.RestoreFocusAfterDialog
import tv.prism.ui.widgets.LocalListScrolling
import tv.prism.ui.widgets.PrismCoverCard
import tv.prism.ui.widgets.PrismSheetCard

internal data class HomePageState(
    val library: LibraryState,
    val hiddenShelves: Set<String>,
    val hasActiveSources: Boolean,
    val hasInstalledSources: Boolean,
    val hasQueue: Boolean,
    val song: Song?,
    val playing: Boolean,
    val updateVersion: String?,
    val radioActive: Boolean,
    val radioLoading: Boolean,
    val radioStatus: String,
    val hasChartSource: Boolean,
    val chartLoading: Boolean,
    val chartTitle: String,
    val chartStatus: String,
)

internal data class HomePageFocus(
    val nav: FocusRequester,
    val mini: FocusRequester,
    val hero: FocusAnchor,
    val guideHelp: FocusRequester,
    val body: FocusAnchor,
    val player: FocusAnchor,
    val update: FocusAnchor,
    val radio: FocusAnchor,
    val list: LazyListState,
    val rows: MutableMap<String, LazyListState>,
    val card: (String) -> FocusRequester,
    val entry: () -> FocusAnchor?,
)

internal data class HomePageActions(
    val showUpdate: () -> Unit,
    val skipUpdate: () -> Unit,
    val openSources: (Boolean) -> Unit,
    val showGuide: () -> Unit,
    val continuePlayback: () -> Unit,
    val startRadio: () -> Unit,
    val openRadioMenu: () -> Unit,
    val openChart: () -> Unit,
    val discover: () -> Unit,
    val manageShelves: () -> Unit,
    val openPlaylists: (HomePlaylistOrigin) -> Unit,
    val playShelf: (List<Song>) -> Unit,
    val playSong: (Song) -> Unit,
    val openTrackMenu: (Song, String?, FocusRequester) -> Unit,
)

private data class HomeShelf(
    val id: String, val title: String, val songs: List<Song> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(),
) {
    val covers = songs.distinctBy(Song::key).take(20)
    val cardKeys = if (playlists.isEmpty()) covers.map(Song::key) else playlists.map(MusicPlaylist::id)
}

internal const val HOME_PLAYLISTS = "playlists"
internal data class HomePlaylistOrigin(val playlistId: String? = null)
internal val homeSectionOptions = listOf(
    "favorites" to "我的收藏", "recent" to "最近播放", "frequent" to "近四周常听",
    HOME_PLAYLISTS to "我的歌单",
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomePage(
    state: HomePageState,
    focus: HomePageFocus,
    actions: HomePageActions,
    returnToPlaylist: HomePlaylistOrigin?,
    onPlaylistFocusRestored: () -> Unit,
    onCardFocused: (FocusRequester) -> Unit,
) {
    val c = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    val hiddenShelves = state.hiddenShelves
    val showPlaylists = HOME_PLAYLISTS !in hiddenShelves && state.library.playlists.isNotEmpty()
    val frequent =
        remember(state.library.listens) { state.library.frequent(System.currentTimeMillis()) }
    val homeShelves =
        remember(state.library, frequent, hiddenShelves) {
            buildList {
                if (frequent.isNotEmpty() && "frequent" !in hiddenShelves)
                    add(HomeShelf("frequent", "近四周常听", frequent))
                if ("favorites" !in hiddenShelves)
                    add(HomeShelf("favorites", "我的收藏", state.library.favorites))
                if ("recent" !in hiddenShelves)
                    add(HomeShelf("recent", "最近播放", state.library.recent))
                if (showPlaylists)
                    add(HomeShelf(HOME_PLAYLISTS, "我的歌单", playlists = state.library.playlists))
            }
        }
    /* 首页每一行都是独立的横向行，而几何搜索只看得到视口里的节点：下一行
    还在屏幕外时按「下」，它找不到正下方的候选，就横向落到同排最右侧
    那张卡片上——方向键看起来"崩了"。所以行与行之间的上下落点在这里
    点名：列号相同，下一行更短时取它的最后一列。 */
    val visibleShelves = remember(homeShelves) { homeShelves.filter { it.cardKeys.isNotEmpty() } }
    val shelfCards =
        remember(visibleShelves, focus.card) {
            visibleShelves.map { shelf -> shelf.cardKeys.map { focus.card("home:${shelf.id}:$it") } }
        }
    val allPlaylistsFocus = focus.card("home:$HOME_PLAYLISTS:view-all")
    LaunchedEffect(returnToPlaylist) {
        val origin = returnToPlaylist ?: return@LaunchedEffect
        val shelfIndex = visibleShelves.indexOfFirst { it.id == HOME_PLAYLISTS }
        var restored = false
        if (shelfIndex >= 0) {
            val shelf = visibleShelves[shelfIndex]
            val cardIndex = shelf.cardKeys.indexOf(origin.playlistId)
            val rowState = focus.rows.getOrPut(HOME_PLAYLISTS) { LazyListState() }
            val target = shelfCards[shelfIndex].getOrNull(cardIndex) ?: allPlaylistsFocus
            withFrameNanos {}
            restored = target.requestAfterScroll {
                focus.list.revealFocusRow(shelfIndex + 1, motion = false)
                if (cardIndex >= 0) rowState.scrollToItem(cardIndex)
            }
        }
        if (!restored && !focus.entry().requestIfAttached()) focus.nav.requestFocus()
        onPlaylistFocusRestored()
    }
    val rowMemory = remember { mutableMapOf<String, Pair<String, Int>>() }
    val focusScope = rememberCoroutineScope()
    val motion = LocalAuraMotion.current
    var holdReveal by remember { mutableStateOf(false) }
    var focusMove by remember { mutableStateOf<Job?>(null) }
    fun focusHeader() {
        if (focusMove?.isActive == true) return
        holdReveal = false
        focusMove = focusScope.launch {
            focus.list.scrollToItem(0)
            // Resolve the target after the lazy header has been brought back into view.
            withFrameNanos {}
            if (!focus.update.requestIfAttached() && !focus.hero.requestIfAttached() &&
                !focus.player.requestIfAttached()
            ) focus.nav.requestFocus()
        }
    }

    fun focusCard(row: Int, column: Int) {
        if (focusMove?.isActive == true) return
        val cards = shelfCards.getOrNull(row).orEmpty()
        if (cards.isEmpty()) return
        val shelf = visibleShelves[row]
        val remembered = rowMemory[shelf.id]?.let { (key, oldIndex) ->
            shelf.cardKeys.indexOf(key).takeIf { it >= 0 } ?: oldIndex
        }
        val col = (remembered ?: column).coerceIn(0, cards.lastIndex)
        val rowState = focus.rows.getOrPut(shelf.id) { LazyListState() }
        holdReveal = false
        focusMove = focusScope.launch {
            // A prefetched row can accept focus before it is visible. Reveal it first.
            focus.list.revealFocusRow(row + 1, motion)
            if (!cards[col].requestAfterScroll { rowState.scrollToItem(col) }) {
                focus.entry().requestIfAttached()
            }
        }
    }
    fun downToCard(row: Int, column: Int) =
        Modifier.onPreviewKeyEvent { event ->
            if (
                event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionDown &&
                    row in shelfCards.indices
            ) {
                focusCard(row, column)
                true
            } else false
        }
    var focusedShelfId by remember { mutableStateOf<String?>(null) }
    val defaultReveal = LocalBringIntoViewSpec.current
    val verticalReveal = remember(defaultReveal) {
        FocusRowRevealSpec(defaultReveal) { focusedShelfId != null || holdReveal }
    }
    LaunchedEffect(focusedShelfId) {
        val id = focusedShelfId ?: return@LaunchedEffect
        withFrameNanos {}
        val index = visibleShelves.indexOfFirst { it.id == id }
        if (index >= 0) focus.list.revealFocusRow(index + 1, motion)
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides verticalReveal) {
    LazyColumn(
        modifier =
            Modifier.fillMaxSize()
                .padding(bottom = 10.dp)
                .focusAnchor(focus.body)
                .focusRestorer()
                .focusGroup(),
        state = focus.list,
    ) {
        item(key = "home-header") {
            Column {
                if (state.updateVersion != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val promptEdges =
                            Modifier.focusProperties {
                                up = focus.nav
                                downTo(focus.hero)
                            }
                        Text("新版本 ${state.updateVersion}", color = c.accent, fontSize = 13.sp)
                        PrismAction(
                            "查看更新",
                            actions.showUpdate,
                            modifier = Modifier.focusAnchor(focus.update).then(promptEdges),
                        )
                        PrismAction(
                            "忽略",
                            {
                                actions.skipUpdate()
                                scope.launch {
                                    withFrameNanos {}
                                    focus.hero.requestIfAttached()
                                }
                            },
                            promptEdges,
                        )
                    }
                }
                val showHomePlayer = state.hasActiveSources || state.hasQueue
                if (!state.hasActiveSources) {
                    val installed = state.hasInstalledSources
                    val guideEdges =
                        Modifier.onFocusChanged {
                                if (it.isFocused) {
                                    scope.launch { focus.list.scrollToItem(0) }
                                }
                            }
                            .focusProperties {
                                up =
                                    if (state.updateVersion != null && focus.update.attached)
                                        focus.update.requester
                                    else focus.nav
                                if (showHomePlayer) downTo(focus.player)
                            }
                    val helpEdges =
                        Modifier.focusRequester(focus.guideHelp)
                            .onFocusChanged {
                                if (it.isFocused) {
                                    scope.launch { focus.list.scrollToItem(0) }
                                }
                            }
                            .focusProperties {
                                up =
                                    if (state.updateVersion != null && focus.update.attached)
                                        focus.update.requester
                                    else focus.nav
                                if (showHomePlayer) downTo(focus.player)
                            }
                    HomeSourceGuide(
                        installed,
                        Modifier.focusAnchor(focus.hero).then(guideEdges),
                        helpEdges,
                        onAdd = { actions.openSources(!installed) },
                        onHelp = actions.showGuide,
                    )
                    Spacer(Modifier.height(14.dp))
                }
                if (showHomePlayer)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        PrismHero(
                            song = state.song,
                            onContinue = actions.continuePlayback,
                            continueAnchor =
                                if (!state.hasActiveSources) focus.player else focus.hero,
                            /* Keep the card clear of the clipped page edge so the focus
                            ring reads as a frame, not as something the screen cut. */
                            modifier =
                                Modifier.weight(1f)
                                    .padding(start = 10.dp)
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionUp)
                                            return@onPreviewKeyEvent false
                                        (!state.hasActiveSources && focus.hero.requestIfAttached()) ||
                                            focus.update.requestIfAttached() || focus.nav.requestFocus()
                                    }
                                    .then(downToCard(0, 0)),
                            hasSources = state.hasActiveSources,
                            favorite =
                                state.song?.let { s ->
                                    state.library.favorites.any { it.key == s.key }
                                } == true,
                            playing = state.playing,
                            onLongPress =
                                state.song?.let { current ->
                                    {
                                        actions.openTrackMenu(
                                            current,
                                            null,
                                            if (!state.hasActiveSources) focus.player.requester
                                            else focus.hero.requester,
                                        )
                                    }
                                },
                        )
                        /* The rail carries what the hero cannot: the strand you were just
                        listening to, and what is moving right now. The padding keeps
                        the cards' focus scale inside the page clip, like the hero. */
                        Column(
                            Modifier.width(288.dp)
                                .height(172.dp)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AuraRadioCard(
                                state.radioActive,
                                state.radioLoading,
                                state.radioStatus,
                                actions.startRadio,
                                Modifier.weight(1f).focusAnchor(focus.radio).focusProperties {
                                    up =
                                        if (!state.hasActiveSources && focus.hero.attached)
                                            focus.hero.requester
                                        else if (
                                            state.updateVersion != null && focus.update.attached
                                        )
                                            focus.update.requester
                                        else focus.nav
                                },
                                onLongPress = actions.openRadioMenu,
                            )
                            AuraHotCard(
                                title = state.chartTitle,
                                onOpen = actions.openChart,
                                status =
                                    if (!state.hasChartSource) "添加支持榜单的音源"
                                    else state.chartStatus.ifBlank { "暂未获取榜单，确定重试" },
                                loading = state.chartLoading,
                                modifier =
                                    Modifier.weight(1f)
                                        .focusProperties { upTo(focus.radio) }
                                        .then(downToCard(0, 0)),
                            )
                        }
                    }
                Spacer(Modifier.height(14.dp))

                if (visibleShelves.isEmpty() && state.hasActiveSources) {
                    Text("把喜欢的音乐留在首页，随时接着听", color = c.muted, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrismAction("去发现挑选音乐", actions.discover, primary = true)
                        PrismAction("管理首页内容", actions.manageShelves)
                    }
                }
            }
        }
        itemsIndexed(visibleShelves, key = { _, shelf -> "shelf:${shelf.id}" }) { shelfIndex, shelf
            ->
            Column {
                val playlistShelf = shelf.id == HOME_PLAYLISTS
                val shelfActionFocus = if (playlistShelf) allPlaylistsFocus else remember(shelf.id) { FocusRequester() }
                var returnCard by remember(shelf.id) { mutableStateOf<Pair<String, Int>?>(null) }
                val rowState =
                    rememberSaveable(shelf.id, saver = LazyListState.Saver) {
                        focus.rows[shelf.id] ?: LazyListState()
                    }
                LaunchedEffect(shelf.id, rowState) { focus.rows[shelf.id] = rowState }
                PrismShelfHeader(
                    shelf.title,
                    if (playlistShelf) shelf.playlists.size else shelf.songs.size,
                    if (playlistShelf) "查看全部" else "播放全部",
                    { if (playlistShelf) actions.openPlaylists(HomePlaylistOrigin()) else actions.playShelf(shelf.songs) },
                    actionFocus = shelfActionFocus,
                    actionIcon = if (playlistShelf) null else "play",
                    primaryAction = true,
                    countUnit = if (playlistShelf) "个歌单" else "首",
                    actionEdges = Modifier
                        .onFocusChanged { if (!it.isFocused) returnCard = null }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    val index = returnCard?.let { (key, oldIndex) ->
                                        shelf.cardKeys.indexOf(key)
                                            .takeIf { it >= 0 } ?: oldIndex
                                    } ?: 0
                                    focusCard(shelfIndex, index)
                                    true
                                }
                                Key.DirectionUp -> {
                                    if (shelfIndex > 0) focusCard(shelfIndex - 1, 0)
                                    else focusHeader()
                                    true
                                }
                                else -> false
                            }
                        },
                )
                CompositionLocalProvider(
                    LocalBringIntoViewSpec provides defaultReveal,
                    LocalListScrolling provides
                        {
                            rowState.isScrollInProgress || focus.list.isScrollInProgress
                        }
                ) {
                    LazyRow(
                        Modifier
                            .onFocusChanged {
                                if (it.hasFocus) focusedShelfId = shelf.id
                                else if (focusedShelfId == shelf.id) focusedShelfId = null
                            }
                            .focusRestorer()
                            .focusGroup(),
                        state = rowState,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    ) {
                        itemsIndexed(shelf.cardKeys, key = { _, key -> key }) { index, key ->
                            val cardFocus = shelfCards[shelfIndex][index]
                            val cardModifier = Modifier.focusRequester(cardFocus)
                                    .focusProperties {
                                        up = shelfActionFocus
                                        if (shelfIndex == shelfCards.lastIndex && state.hasQueue)
                                            down = focus.mini
                                    }
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionUp)
                                            return@onPreviewKeyEvent false
                                        returnCard = key to index
                                        holdReveal = true
                                        val moved = shelfActionFocus.requestFocus()
                                        if (!moved) {
                                            returnCard = null
                                            holdReveal = false
                                        } else focusScope.launch {
                                            withFrameNanos {}
                                            withFrameNanos {}
                                            withFrameNanos {}
                                            holdReveal = false
                                        }
                                        moved
                                    }
                                    .then(downToCard(shelfIndex + 1, index))
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            rowMemory[shelf.id] = key to index
                                            onCardFocused(cardFocus)
                                        }
                                    }
                            if (playlistShelf) {
                                val playlist = shelf.playlists[index]
                                PrismSheetCard(
                                    playlist.name,
                                    playlist.songs.firstOrNull { it.artwork.isNotBlank() }?.artwork.orEmpty(),
                                    "${playlist.songs.size} 首",
                                    cardModifier,
                                    onClick = { actions.openPlaylists(HomePlaylistOrigin(playlist.id)) },
                                )
                            } else {
                                val song = shelf.covers[index]
                                PrismCoverCard(
                                    song, index, { actions.playSong(song) }, cardModifier,
                                    current = state.song?.key == song.key,
                                    onLongPress = { actions.openTrackMenu(song, shelf.id, cardFocus) },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    }
}
