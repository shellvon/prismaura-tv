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
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import tv.prism.library.LibraryState
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
import tv.prism.ui.widgets.LocalListScrolling
import tv.prism.ui.widgets.PrismCoverCard

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
    val playShelf: (List<Song>) -> Unit,
    val playSong: (Song) -> Unit,
    val openTrackMenu: (Song, String?, FocusRequester) -> Unit,
)

private data class HomeShelf(val id: String, val title: String, val songs: List<Song>) {
    val covers = songs.distinctBy(Song::key).take(20)
}

private val builtInShelves = setOf("favorites", "recent", "frequent")

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomePage(
    state: HomePageState,
    focus: HomePageFocus,
    actions: HomePageActions,
    onCardFocused: (FocusRequester) -> Unit,
) {
    val c = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    val hiddenShelves = state.hiddenShelves
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
                state.library.playlists
                    .filterNot { it.id in hiddenShelves }
                    .forEach { list -> add(HomeShelf(list.id, list.name, list.songs)) }
            }
        }
    /* 首页每一行都是独立的横向行，而几何搜索只看得到视口里的节点：下一行
    还在屏幕外时按「下」，它找不到正下方的候选，就横向落到同排最右侧
    那张卡片上——方向键看起来"崩了"。所以行与行之间的上下落点在这里
    点名：列号相同，下一行更短时取它的最后一列。 */
    val visibleShelves = remember(homeShelves) { homeShelves.filter { it.covers.isNotEmpty() } }
    val shelfCards =
        visibleShelves.map { shelf ->
            shelf.songs
                .distinctBy { it.key }
                .take(20)
                .map { focus.card("home:${shelf.id}:${it.key}") }
        }
    val focusScope = rememberCoroutineScope()
    val motion = LocalAuraMotion.current
    suspend fun scrollToShelf(index: Int) {
        if (motion) focus.list.animateScrollToItem(index)
        else focus.list.scrollToItem(index)
    }

    fun focusCard(row: Int, column: Int) {
        val cards = shelfCards.getOrNull(row).orEmpty()
        if (cards.isEmpty()) return
        val col = column.coerceIn(0, cards.lastIndex)
        val rowState = focus.rows.getOrPut(visibleShelves[row].id) { LazyListState() }
        focusScope.coroutineContext.cancelChildren()
        focusScope.launch {
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
        FocusRowRevealSpec(defaultReveal) { focusedShelfId != null }
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
                                if (it.isFocused) scope.launch { focus.list.scrollToItem(0) }
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
                        guideEdges,
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
                                    .focusProperties {
                                        up =
                                            if (!state.hasActiveSources && focus.hero.attached)
                                                focus.hero.requester
                                            else if (
                                                state.updateVersion != null && focus.update.attached
                                            )
                                                focus.update.requester
                                            else focus.nav
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

                if (homeShelves.isEmpty() && state.hasActiveSources) {
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
                val shelfActionFocus = remember(shelf.id) { FocusRequester() }
                var returnCard by remember(shelf.id) { mutableStateOf<Pair<String, Int>?>(null) }
                val rowState =
                    rememberSaveable(shelf.id, saver = LazyListState.Saver) {
                        focus.rows[shelf.id] ?: LazyListState()
                    }
                LaunchedEffect(shelf.id, rowState) { focus.rows[shelf.id] = rowState }
                PrismShelfHeader(
                    shelf.title,
                    shelf.songs.size,
                    "播放全部",
                    { actions.playShelf(shelf.songs) },
                    actionFocus = shelfActionFocus,
                    actionIcon = "play",
                    actionEdges = Modifier
                        .onFocusChanged { if (!it.isFocused) returnCard = null }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    val index = returnCard?.let { (songKey, oldIndex) ->
                                        shelf.covers.indexOfFirst { it.key == songKey }
                                            .takeIf { it >= 0 } ?: oldIndex
                                    } ?: 0
                                    focusCard(shelfIndex, index)
                                    true
                                }
                                Key.DirectionUp -> {
                                    if (shelfIndex > 0) focusCard(shelfIndex - 1, 0)
                                    else focusScope.launch {
                                        focus.entry()?.requester?.requestAfterScroll { scrollToShelf(0) }
                                    }
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
                        itemsIndexed(shelf.covers, key = { _, song -> song.key }) { index, song ->
                            val cardFocus = shelfCards[shelfIndex][index]
                            PrismCoverCard(
                                song,
                                index,
                                { actions.playSong(song) },
                                Modifier.focusRequester(cardFocus)
                                    .focusProperties {
                                        up = shelfActionFocus
                                        if (shelfIndex == shelfCards.lastIndex && state.hasQueue)
                                            down = focus.mini
                                    }
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionUp)
                                            return@onPreviewKeyEvent false
                                        returnCard = song.key to index
                                        shelfActionFocus.requestFocus().also {
                                            if (!it) returnCard = null
                                        }
                                    }
                                    .then(downToCard(shelfIndex + 1, index))
                                    .onFocusChanged {
                                        if (it.isFocused) onCardFocused(cardFocus)
                                    },
                                current = state.song?.key == song.key,
                                onLongPress = {
                                    actions.openTrackMenu(
                                        song,
                                        if (shelf.id in builtInShelves) shelf.id
                                        else "playlist:${shelf.id}",
                                        cardFocus,
                                    )
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    }
}
