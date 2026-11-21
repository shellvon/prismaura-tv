package tv.prism.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.prism.ui.widgets.FocusAnchor
import tv.prism.library.MusicPlaylist
import tv.prism.library.Song
import tv.prism.library.SavedSubject
import tv.prism.ui.LibraryViewModel
import tv.prism.library.LibraryState
import tv.prism.ui.widgets.requestIfAttached
import tv.prism.ui.widgets.requestAfterScroll
import tv.prism.ui.widgets.LocalPrismColors
import tv.prism.ui.widgets.LocalListScrolling
import tv.prism.ui.widgets.upTo
import tv.prism.ui.widgets.PrismPageHead
import tv.prism.ui.widgets.PrismAction
import tv.prism.ui.widgets.focusAnchor
import tv.prism.ui.widgets.PrismLibraryTab
import tv.prism.ui.widgets.PlatformIcon
import tv.prism.sources.MusicPlatform
import tv.prism.ui.widgets.PrismTrackHeader
import tv.prism.ui.widgets.PrismEmptyState
import tv.prism.ui.widgets.PrismTrackRow
import tv.prism.catalog.matchesSearchHistory
import tv.prism.ui.widgets.Input
import tv.prism.ui.widgets.AuraIconButton

internal data class LibraryPageFocus(
    val nav: FocusRequester,
    val mini: FocusRequester,
    val content: FocusAnchor,
    val header: FocusAnchor,
    val back: FocusAnchor,
    val empty: FocusAnchor,
    val playAll: FocusAnchor,
    val importPlaylist: FocusAnchor,
    val tabs: Map<String, FocusAnchor>,
    val playlists: LazyListState,
    val songs: LazyListState,
    val subjects: LazyListState,
    val detailFor: (String) -> FocusRequester,
    val trackFor: (String) -> FocusRequester,
    val onFocused: (FocusRequester) -> Unit,
    val registerPlaylist: (MusicPlaylist, FocusRequester) -> Unit,
    val registerTrack: (Song, FocusRequester) -> Unit,
)

internal data class LibraryPageActions(
    val playSong: (Song) -> Unit,
    val playAll: (List<Song>) -> Unit,
    val openSubject: (SavedSubject) -> Unit,
    val openPlaylist: (String) -> Unit,
    val closePlaylist: () -> Unit,
    val importPlaylist: () -> Unit,
    val fillOnPhone: (field: String, query: String, onReturn: () -> Unit, onQuery: (String) -> Unit) -> Unit,
    val discover: () -> Unit,
    val enterManage: () -> Unit,
    val exitManage: () -> Unit,
    val onBack: () -> Unit,
    val confirmSelection: () -> Unit,
    val showListMenu: (FocusRequester) -> Unit,
    val showTrackMenu: (Song, String) -> Unit,
    val showSubjectMenu: (SavedSubject, FocusRequester) -> Unit,
    val showPlaylistMenu: (MusicPlaylist, FocusRequester) -> Unit,
)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun LibraryPage(
    libraryViewModel: LibraryViewModel,
    libraryState: LibraryState,
    currentSongKey: String?,
    mvCapableIds: Set<String>,
    focus: LibraryPageFocus,
    actions: LibraryPageActions,
) {
    val ui by libraryViewModel.state.collectAsState()
    val selectedList = ui.list
    val libraryManage = ui.managing
    val librarySelected = ui.selected
    val showingPlaylists = selectedList == "playlists"
    val isPlaylistDetail = selectedList.startsWith("playlist:")
    val selectedPlaylist =
        if (isPlaylistDetail)
            libraryState.playlists.firstOrNull { "playlist:${it.id}" == selectedList }
        else null
    val libraryTabKey = if (isPlaylistDetail) "playlists" else selectedList
    LaunchedEffect(selectedPlaylist?.id) {
        if (selectedPlaylist != null) {
            withFrameNanos {}
            if (!focus.content.requestIfAttached()) focus.back.requestIfAttached()
        }
    }
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(ui.returningPlaylist) {
        val id = ui.returningPlaylist ?: return@LaunchedEffect
        inputMode.requestInputMode(InputMode.Keyboard)
        withFrameNanos {}
        val index = libraryState.playlists.indexOfFirst { it.id == id }
        val restored =
            index >= 0 &&
                focus.detailFor("playlist:$id").requestAfterScroll {
                    focus.playlists.scrollToItem(index / 6)
                }
        if (!restored) focus.tabs["playlists"].requestIfAttached()
        libraryViewModel.finishPlaylistReturn(id)
    }
    val c = LocalPrismColors.current
    val scrollingList =
        when (selectedList) {
            "artists",
            "albums" -> focus.subjects
            "playlists" -> focus.playlists
            else -> focus.songs
        }
    var findingSong by remember(selectedList) { mutableStateOf(false) }
    var query by remember(selectedList) { mutableStateOf("") }
    val filterButton = remember { FocusAnchor() }
    val filterInput = remember { FocusAnchor() }
    fun closeFilter() {
        filterButton.requestIfAttached()
        findingSong = false
        query = ""
    }
    LaunchedEffect(libraryManage) {
        if (libraryManage) {
            findingSong = false
            query = ""
        }
    }
    CompositionLocalProvider(LocalListScrolling provides { scrollingList.isScrollInProgress }) {
        val backKey = Modifier.onPreviewKeyEvent { event ->
            if (event.key != Key.Back || (!findingSong && selectedPlaylist == null)) return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyUp && !event.nativeKeyEvent.isCanceled) {
                if (findingSong) closeFilter() else actions.onBack()
            }
            true
        }
        Column(Modifier.fillMaxSize().then(backKey)) {
            // Each tab owns one kind of collection; album favorites are saved album entities.
            val showingSubjects = selectedList in setOf("artists", "albums")
            val subjectKind = if (selectedList == "artists") "artist" else "album"
            val visibleSubjects = libraryState.subjects.filter { it.kind == subjectKind }
            val savedSheets = libraryState.subjects.filter { it.kind == "sheet" }
            val songs =
                when {
                    showingSubjects -> emptyList()
                    selectedList == "recent" -> libraryState.recent
                    selectedPlaylist != null -> selectedPlaylist.songs
                    else -> libraryState.favorites
                }
            val listName =
                when {
                    selectedList == "recent" -> "最近播放"
                    selectedList == "artists" -> "收藏的歌手"
                    selectedList == "albums" -> "收藏的专辑"
                    selectedPlaylist != null -> selectedPlaylist.name
                    showingPlaylists -> "我的歌单"
                    else -> "收藏的歌曲"
                }
            val canFind = !libraryManage && when {
                showingSubjects -> visibleSubjects.isNotEmpty()
                showingPlaylists -> libraryState.playlists.isNotEmpty() || savedSheets.isNotEmpty()
                else -> songs.isNotEmpty()
            }
            val filterActive = findingSong && canFind
            val term = if (filterActive) query.trim() else ""
            val findLabel = when {
                showingPlaylists -> "查找歌单"
                showingSubjects && subjectKind == "artist" -> "查找歌手"
                showingSubjects -> "查找专辑"
                else -> "查找歌曲"
            }
            val matches = filteredLibraryItems(songs, term.takeUnless { showingSubjects || showingPlaylists }.orEmpty()) { song, query ->
                matchesSearchHistory(song.title, query) || matchesSearchHistory(song.artist, query)
            }
            val subjectMatches = filteredLibraryItems(visibleSubjects, term.takeIf { showingSubjects }.orEmpty()) { subject, query ->
                matchesSearchHistory(subject.title, query)
            }
            val playlistTerm = term.takeIf { showingPlaylists }.orEmpty()
            val playlistMatches = filteredLibraryItems(libraryState.playlists, playlistTerm) { playlist, query ->
                matchesSearchHistory(playlist.name, query)
            }
            val sheetMatches = filteredLibraryItems(savedSheets, playlistTerm) { sheet, query ->
                matchesSearchHistory(sheet.title, query)
            }
            LaunchedEffect(filterActive) {
                if (filterActive) {
                    repeat(4) {
                        withFrameNanos {}
                        if (filterInput.requestIfAttached()) return@LaunchedEffect
                    }
                }
            }
            LaunchedEffect(term) {
                if (filterActive) scrollingList.scrollToItem(0)
            }
            val manageTargets =
                when {
                    showingSubjects -> visibleSubjects.map { it.key }
                    showingPlaylists -> libraryState.playlists.map { it.id }
                    else -> songs.map { it.key }
                }
            val allChosen =
                manageTargets.isNotEmpty() && manageTargets.all { it in librarySelected }
            val headerEdges =
                Modifier.focusProperties {
                    if (selectedPlaylist != null) upTo(focus.back) else up = focus.nav
                    if (focus.content.attached) down = focus.content.requester
                    else down = focus.mini
                }
            val batchVerb =
                when {
                    selectedList == "recent" -> "删除记录"
                    showingSubjects -> "移除收藏"
                    showingPlaylists -> "删除歌单"
                    selectedPlaylist != null -> "移出歌单"
                    else -> "取消收藏"
                }
            val batchUnit =
                when {
                    selectedList == "recent" -> "条"
                    showingSubjects || showingPlaylists -> "个"
                    else -> "首"
                }
            if (selectedPlaylist != null) {
                PrismPageHead("歌单详情") {
                    PrismAction(
                        "返回歌单",
                        { actions.closePlaylist() },
                        Modifier.focusAnchor(focus.back).onPreviewKeyEvent {
                            it.key == Key.DirectionUp
                        },
                    )
                }
            }
            val belowTabs =
                Modifier.focusProperties {
                    up = focus.nav
                    when {
                        focus.content.attached -> down = focus.content.requester
                        focus.empty.attached -> down = focus.empty.requester
                        focus.header.attached -> down = focus.header.requester
                        else -> down = focus.mini
                    }
                }
            /* The empty-collection exit keeps the same spot and padding
            across the five tabs; Up returns to the active tab. */
            val emptyActionEdges =
                Modifier.focusAnchor(focus.empty).focusProperties {
                    val tab = focus.tabs.getValue(libraryTabKey)
                    if (tab.attached) up = tab.requester
                    down = focus.mini
                }
            if (selectedPlaylist == null) {
                // Each category shows only its applicable actions; OK on a tab
                // enters the first available action.
                val canPlayAll = !showingSubjects && !showingPlaylists && songs.isNotEmpty()
                val canImport = showingPlaylists
                val canManage = manageTargets.isNotEmpty()
                val firstAction =
                    when {
                        libraryManage -> focus.header
                        canPlayAll -> focus.playAll
                        canFind -> filterButton
                        canImport -> focus.importPlaylist
                        canManage -> focus.header
                        else -> null
                    }
                /* Tabs and actions share one row: browse left, act right.
                ←/→ walk this row, ↓ enters the list, and the first row's
                ↑ returns to the active tab. */
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    /* The four collection tabs are things you saved; 最近播放
                    is system history — the divider marks the two kinds. */
                    val collectionTabs =
                        listOf(
                            Triple("favorites", "歌曲", libraryState.favorites.size),
                            Triple(
                                "albums",
                                "专辑",
                                libraryState.subjects.count { it.kind == "album" },
                            ),
                            Triple(
                                "artists",
                                "歌手",
                                libraryState.subjects.count { it.kind == "artist" },
                            ),
                            Triple(
                                "playlists",
                                "歌单",
                                libraryState.playlists.size + savedSheets.size,
                            ),
                        )
                    val recentTab = Triple("recent", "最近", libraryState.recent.size)
                    val tabs = collectionTabs + recentTab
                    val tabModifier: (String) -> Modifier = { id ->
                        Modifier.focusAnchor(focus.tabs.getValue(id))
                            .then(belowTabs)
                            .semantics { selected = selectedList == id }
                            .focusProperties {
                                val index = tabs.indexOfFirst { it.first == id }
                                left =
                                    tabs.getOrNull(index - 1)?.let {
                                        focus.tabs.getValue(it.first).requester
                                    } ?: FocusRequester.Cancel
                                right =
                                    tabs.getOrNull(index + 1)?.let {
                                        focus.tabs.getValue(it.first).requester
                                    }
                                        ?: firstAction?.takeIf { it.attached }?.requester
                                        ?: FocusRequester.Cancel
                            }
                            .onFocusChanged {
                                if (
                                    it.isFocused &&
                                        libraryViewModel.state.value.returningPlaylist == null
                                )
                                    libraryViewModel.open(id)
                            }
                    }
                    val tabLongPress: (String) -> (() -> Unit)? = { id ->
                        if (libraryManage) null
                        else {
                            {
                                /* No menu when all three actions are unavailable —
                                an empty menu is worse than no response. */
                                val anyAction =
                                    songs.isNotEmpty() ||
                                        showingPlaylists ||
                                        manageTargets.isNotEmpty()
                                if (anyAction) {
                                    actions.showListMenu(focus.tabs.getValue(id).requester)
                                }
                            }
                        }
                    }
                    collectionTabs.forEach { (id, label, count) ->
                        PrismLibraryTab(
                            label,
                            count,
                            selectedList == id,
                            {
                                if (selectedList != id) libraryViewModel.open(id)
                                else if (
                                    !firstAction.requestIfAttached() &&
                                        !focus.content.requestIfAttached()
                                ) {
                                    focus.empty.requestIfAttached()
                                }
                            },
                            modifier = tabModifier(id),
                            onLongPress = tabLongPress(id),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Box(
                        Modifier.padding(horizontal = 8.dp)
                            .width(1.dp)
                            .height(18.dp)
                            .background(c.foreground.copy(alpha = .14f))
                    )
                    PrismLibraryTab(
                        recentTab.second,
                        recentTab.third,
                        selectedList == recentTab.first,
                        {
                            if (selectedList != recentTab.first)
                                libraryViewModel.open(recentTab.first)
                            else if (
                                !firstAction.requestIfAttached() &&
                                    !focus.content.requestIfAttached()
                            ) {
                                focus.empty.requestIfAttached()
                            }
                        },
                        modifier = tabModifier(recentTab.first),
                        onLongPress = tabLongPress(recentTab.first),
                    )
                    Spacer(Modifier.weight(1f))
                    if (libraryManage) {
                        PrismAction(
                            if (allChosen) "取消全选" else "全选",
                            {
                                libraryViewModel.select(
                                    if (allChosen) emptySet() else manageTargets.toSet()
                                )
                            },
                            headerEdges,
                        )
                        Spacer(Modifier.width(8.dp))
                        PrismAction(
                            if (librarySelected.isNotEmpty())
                                "$batchVerb ${librarySelected.size} $batchUnit"
                            else batchVerb,
                            { actions.confirmSelection() },
                            modifier = headerEdges,
                            primary = librarySelected.isNotEmpty(),
                        )
                        Spacer(Modifier.width(8.dp))
                        PrismAction(
                            "退出管理",
                            { actions.exitManage() },
                            Modifier.focusAnchor(focus.header).then(headerEdges),
                        )
                    } else {
                        // Only the actions this category supports are shown;
                        // in empty lists they stay in place, disabled.
                        if (!showingSubjects && !showingPlaylists)
                            PrismAction(
                                "播放全部",
                                { actions.playAll(songs) },
                                headerEdges
                                    .focusAnchor(focus.playAll)
                                    .then(
                                        if (firstAction === focus.playAll)
                                            Modifier.focusProperties {
                                                left = focus.tabs.getValue(libraryTabKey).requester
                                            }
                                        else Modifier
                                    ),
                                primary = canPlayAll,
                                enabled = canPlayAll,
                                icon = "play",
                            )
                        if (!showingSubjects && !showingPlaylists) Spacer(Modifier.width(8.dp))
                        if (canFind) {
                            PrismAction(
                                if (filterActive) "收起查找" else findLabel,
                                { if (filterActive) closeFilter() else findingSong = true },
                                Modifier.focusAnchor(filterButton)
                                    .focusProperties {
                                        if (filterInput.attached) down = filterInput.requester
                                        if (firstAction === filterButton) left = focus.tabs.getValue(libraryTabKey).requester
                                    }
                                    .then(headerEdges),
                                icon = if (filterActive) "close" else "search",
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        if (showingPlaylists)
                            PrismAction(
                                "导入歌单",
                                { actions.importPlaylist() },
                                headerEdges
                                    .focusAnchor(focus.importPlaylist)
                                    .then(
                                        if (firstAction === focus.importPlaylist)
                                            Modifier.focusProperties {
                                                left = focus.tabs.getValue(libraryTabKey).requester
                                            }
                                        else Modifier
                                    ),
                                enabled = canImport,
                                icon = "import",
                            )
                        if (showingPlaylists) Spacer(Modifier.width(8.dp))
                        PrismAction(
                            "管理",
                            { actions.enterManage() },
                            Modifier.focusAnchor(focus.header)
                                .then(headerEdges)
                                .then(
                                    if (firstAction === focus.header)
                                        Modifier.focusProperties {
                                            left = focus.tabs.getValue(libraryTabKey).requester
                                        }
                                    else Modifier
                                ),
                            enabled = canManage,
                            icon = "checklist",
                        )
                    }
                }
                Text(
                    if (libraryManage) "确定键选择条目 · 返回键退出管理" else "左右切换分类 · 确定键进入右侧操作 · 长按打开菜单",
                    color = c.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp),
                )
            }
            /* 歌单详情没有 tab 行，列表名和条目数留在这条标题里；
            tab 页的列表名与条数已经写在 tab 上，这里不再重复。 */
            if (selectedPlaylist != null) {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PlatformIcon(MusicPlatform.CUSTOM, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    if (libraryManage) {
                        Text(
                            "管理",
                            color = c.accent,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (librarySelected.isEmpty()) "选中条目后再处理"
                            else "已选 ${librarySelected.size} / ${manageTargets.size} $batchUnit",
                            color = c.muted,
                            fontSize = 11.sp,
                        )
                    } else {
                        Text(
                            listName,
                            color = c.foreground,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 320.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                showingSubjects -> "${visibleSubjects.size} 个"
                                showingPlaylists ->
                                    "${libraryState.playlists.size + savedSheets.size} 个"
                                else -> "${songs.size} 首"
                            },
                            color = c.muted,
                            fontSize = 11.sp,
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
                        PrismAction(
                            if (allChosen) "取消全选" else "全选",
                            {
                                libraryViewModel.select(
                                    if (allChosen) emptySet() else manageTargets.toSet()
                                )
                            },
                            headerEdges,
                        )
                        Spacer(Modifier.width(8.dp))
                        PrismAction(
                            if (librarySelected.isNotEmpty())
                                "$batchVerb ${librarySelected.size} $batchUnit"
                            else batchVerb,
                            { actions.confirmSelection() },
                            modifier = headerEdges,
                            primary = librarySelected.isNotEmpty(),
                        )
                        Spacer(Modifier.width(8.dp))
                        PrismAction(
                            "退出管理",
                            { actions.exitManage() },
                            Modifier.focusAnchor(focus.header).then(headerEdges),
                        )
                    } else {
                        val detailCanAct = manageTargets.isNotEmpty()
                        PrismAction(
                            "播放全部",
                            { actions.playAll(songs) },
                            headerEdges.then(
                                if (detailCanAct)
                                    Modifier.focusAnchor(focus.header).focusProperties {
                                        left = focus.back.requester
                                    }
                                else Modifier
                            ),
                            enabled = detailCanAct,
                            icon = "play",
                        )
                        Spacer(Modifier.width(8.dp))
                        if (canFind) {
                            PrismAction(
                                if (filterActive) "收起查找" else findLabel,
                                { if (filterActive) closeFilter() else findingSong = true },
                                Modifier.focusAnchor(filterButton)
                                    .focusProperties { if (filterInput.attached) down = filterInput.requester }
                                    .then(headerEdges),
                                icon = if (filterActive) "close" else "search",
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        PrismAction(
                            "管理",
                            { actions.enterManage() },
                            headerEdges,
                            enabled = detailCanAct,
                            icon = "checklist",
                        )
                        if (!detailCanAct) {
                            Spacer(Modifier.width(8.dp))
                            PrismAction(
                                "返回歌单",
                                { actions.closePlaylist() },
                                Modifier.focusAnchor(focus.header).then(headerEdges),
                            )
                        }
                    }
                }
            }
            if (filterActive) {
                val field = when {
                    showingPlaylists -> "输入歌单名"
                    showingSubjects && subjectKind == "artist" -> "输入歌手名"
                    showingSubjects -> "输入专辑名"
                    else -> "输入歌名或歌手"
                }
                LibraryFilterField(
                    query, { query = it }, field, filterInput, filterButton.requester, focus.content,
                    focus.mini, actions.fillOnPhone,
                )
            }
            if (showingSubjects || showingPlaylists) {
                LibraryCollections(
                    subjectKind = subjectKind.takeIf { showingSubjects },
                    visibleSubjects = subjectMatches.orEmpty(),
                    filterInput = filterInput.takeIf { filterActive },
                    filterMessage = if (filterActive) {
                        when {
                            showingPlaylists && (playlistMatches == null || sheetMatches == null) -> "正在查找…"
                            showingPlaylists -> "没有匹配的歌单"
                            subjectMatches == null -> "正在查找…"
                            subjectKind == "artist" -> "没有匹配的歌手"
                            else -> "没有匹配的专辑"
                        }
                    } else null,
                    playlists = playlistMatches.orEmpty(),
                    savedSheets = sheetMatches.orEmpty(),
                    managing = libraryManage,
                    selectedKeys = librarySelected,
                    listState = if (showingSubjects) focus.subjects else focus.playlists,
                    libraryContentFocus = focus.content,
                    libraryHeaderFocus = focus.header,
                    tab = focus.tabs.getValue(libraryTabKey),
                    miniFocus = focus.mini,
                    emptyActionEdges = emptyActionEdges,
                    detailFocus = focus.detailFor,
                    onFocus = focus.onFocused,
                    registerPlaylist = focus.registerPlaylist,
                    onDiscover = actions.discover,
                    onImport = { actions.importPlaylist() },
                    onPickSubject = { subject ->
                        if (libraryManage) libraryViewModel.toggle(subject.key)
                        else actions.openSubject(subject)
                    },
                    onSubjectMenu = actions.showSubjectMenu,
                    onPickPlaylist = {
                        if (libraryManage) libraryViewModel.toggle(it.id)
                        else actions.openPlaylist(it.id)
                    },
                    onPlaylistMenu = actions.showPlaylistMenu,
                )
            } else {
                LibraryTrackList(
                    songs = matches.orEmpty(),
                    highlight = term,
                    filterInput = filterInput.takeIf { filterActive },
                    managing = libraryManage,
                    selectedKeys = librarySelected,
                    currentSongKey = currentSongKey,
                    mvCapableIds = mvCapableIds,
                    emptyMessage =
                        when {
                            matches == null -> "正在查找歌曲…"
                            filterActive -> "没有匹配的歌曲"
                            selectedPlaylist != null -> "这个歌单里还没有歌"
                            selectedList == "favorites" -> "播放时按收藏，歌曲会出现在这里"
                            else -> "播过的歌会自动记在这里"
                        },
                    emptyAction = if (selectedPlaylist == null && !filterActive) actions.discover else null,
                    emptyActionEdges = emptyActionEdges,
                    listState = focus.songs,
                    entry = focus.content,
                    tab =
                        focus.tabs.getValue(libraryTabKey).takeIf {
                            selectedPlaylist == null && !libraryManage
                        },
                    header = focus.header,
                    trackFocus = focus.trackFor,
                    onFocus = focus.onFocused,
                    registerTrack = focus.registerTrack,
                    onPick = {
                        if (libraryManage) libraryViewModel.toggle(it.key) else actions.playSong(it)
                    },
                    onMenu = { actions.showTrackMenu(it, selectedList) },
                )
            }
        }
    }
}

@Composable
internal fun ColumnScope.LibraryTrackList(
    songs: List<Song>,
    highlight: String,
    filterInput: FocusAnchor?,
    managing: Boolean,
    selectedKeys: Set<String>,
    currentSongKey: String?,
    mvCapableIds: Set<String>,
    emptyMessage: String,
    emptyAction: (() -> Unit)?,
    emptyActionEdges: Modifier,
    listState: LazyListState,
    entry: FocusAnchor,
    tab: FocusAnchor?,
    header: FocusAnchor,
    trackFocus: (String) -> FocusRequester,
    onFocus: (FocusRequester) -> Unit,
    registerTrack: (Song, FocusRequester) -> Unit,
    onPick: (Song) -> Unit,
    onMenu: (Song) -> Unit,
) {
    val c = LocalPrismColors.current
    Spacer(Modifier.height(8.dp))
    PrismTrackHeader(managing = managing)
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line.copy(alpha = .5f)))
    if (songs.isEmpty()) {
        PrismEmptyState(
            emptyMessage,
            if (emptyAction != null) "去发现" else null,
            { emptyAction?.invoke() },
            emptyActionEdges,
            centered = true,
        )
    } else
        LazyColumn(Modifier.weight(1f).padding(top = 4.dp, bottom = 10.dp), state = listState) {
            itemsIndexed(songs, key = { _, song -> song.key }) { index, song ->
                val rowFocus = if (index == 0) entry.requester else trackFocus(song.key)
                registerTrack(song, rowFocus)
                val rowMenu: (() -> Unit)? = if (managing) null else { -> onMenu(song) }
                PrismTrackRow(
                    song,
                    index,
                    { onPick(song) },
                    modifier =
                        (if (index == 0)
                                Modifier.focusAnchor(entry).focusProperties {
                                    when {
                                        filterInput != null -> upTo(filterInput)
                                        tab != null -> upTo(tab)
                                        else -> upTo(header)
                                    }
                                }
                            else Modifier.focusRequester(rowFocus))
                            /* 迷你条按上回到离开时的那一行，而不是页首 tab。 */
                            .onFocusChanged { if (it.isFocused) onFocus(rowFocus) },
                    current = currentSongKey == song.key,
                    showMv = song.pluginId in mvCapableIds,
                    onLongPress = rowMenu,
                    selected = if (managing) song.key in selectedKeys else null,
                    highlight = highlight,
                )
            }
        }
}

@Composable
private fun LibraryFilterField(
    query: String,
    onQuery: (String) -> Unit,
    field: String,
    input: FocusAnchor,
    button: FocusRequester,
    results: FocusAnchor,
    mini: FocusRequester,
    fillOnPhone: (field: String, query: String, onReturn: () -> Unit, onQuery: (String) -> Unit) -> Unit,
) {
    val phone = remember { FocusAnchor() }
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Input(
            query, onQuery,
            if (android.os.Build.VERSION.SDK_INT >= 29) "${field}或拼音首字母" else field,
            Modifier.weight(1f).focusAnchor(input).focusProperties {
                up = button
                down = if (results.attached) results.requester else mini
                if (phone.attached) right = phone.requester
            },
            manualKeyboard = true,
            downTarget = results,
            onSubmit = { if (!results.requestIfAttached()) mini.requestFocus() },
        )
        AuraIconButton(
            "qr", "手机代填查找关键词",
            {
                fillOnPhone(field, query, {
                    scope.launch {
                        repeat(2) { withFrameNanos {} }
                        phone.requestIfAttached()
                    }
                }, onQuery)
            },
            Modifier.focusAnchor(phone).focusProperties {
                if (input.attached) left = input.requester
                up = button
                down = if (results.attached) results.requester else mini
            },
            size = 42.dp,
        )
    }
}

@Composable
private fun <T> filteredLibraryItems(
    items: List<T>,
    term: String,
    matches: (T, String) -> Boolean,
): List<T>? = key(items, term) {
    produceState<List<T>?>(if (term.isEmpty()) items else null) {
        if (term.isNotEmpty()) {
            value = withContext(Dispatchers.Default) {
                items.filter {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    matches(it, term)
                }
            }
        }
    }.value
}
