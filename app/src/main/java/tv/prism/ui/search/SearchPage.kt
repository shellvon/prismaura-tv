package tv.prism.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.key
import kotlinx.coroutines.flow.first
import tv.prism.ui.widgets.FocusAnchor
import tv.prism.ui.widgets.FocusRowRevealSpec
import tv.prism.ui.widgets.LocalAuraMotion
import tv.prism.ui.widgets.revealFocusRow
import tv.prism.library.Song
import tv.prism.sources.Plugin
import tv.prism.library.SavedSubject
import tv.prism.catalog.SearchEntity
import tv.prism.ui.SearchViewModel
import tv.prism.sources.Plugins
import tv.prism.library.LibraryState
import tv.prism.catalog.SearchSuggestion
import tv.prism.ui.widgets.LocalPrismColors
import tv.prism.ui.widgets.LocalListScrolling
import tv.prism.ui.widgets.rememberVerticalFocusSearch
import tv.prism.ui.widgets.requestIfAttached
import tv.prism.catalog.SearchSuggestions
import tv.prism.catalog.matchesSearchHistory
import tv.prism.ui.widgets.Input
import tv.prism.ui.widgets.focusAnchor
import tv.prism.ui.widgets.AuraIconButton
import tv.prism.ui.widgets.PhoneFillButton
import tv.prism.ui.widgets.PrismLink
import tv.prism.ui.widgets.PrismEmptyState
import tv.prism.ui.widgets.PrismAction
import tv.prism.ui.widgets.PlatformAction
import tv.prism.ui.widgets.upTo
import tv.prism.ui.widgets.leftTo
import tv.prism.ui.widgets.PrismSearchSuggestionCard
import tv.prism.ui.widgets.PrismSuggestionRow
import tv.prism.ui.widgets.PrismSheetCard
import tv.prism.ui.widgets.keepFocusVisible
import tv.prism.ui.widgets.PrismTrackHeader
import tv.prism.ui.widgets.PrismTrackRow
import tv.prism.ui.widgets.requestAfterScroll

internal data class SearchPageFocus(
    val nav: FocusRequester,
    val mini: FocusRequester,
    val input: FocusAnchor,
    val source: FocusAnchor,
    val chips: FocusAnchor,
    val results: FocusAnchor,
    val more: FocusAnchor,
    val list: LazyListState,
    val scope: CoroutineScope,
    val memory: MutableState<Triple<FocusRequester, Int, Int>?>,
    val detailFor: (String) -> FocusRequester,
    val trackFor: (String) -> FocusRequester,
    val registerTrack: (Song, FocusRequester) -> Unit,
    val onFocused: (FocusRequester) -> Unit,
)

internal data class SearchPageActions(
    val submit: (query: String, append: Boolean) -> Unit,
    val useMusicType: () -> Unit,
    val changeType: (type: String, focusResults: Boolean) -> Unit,
    val toggleSource: (String?) -> Unit,
    val cancelResultsFocus: () -> Unit,
    val fillOnPhone: (draft: String, onReturn: () -> Unit) -> Unit,
    val clearHistory: (String) -> Unit,
    val openRecentArtist: (Plugin, SavedSubject) -> Unit,
    val addSource: () -> Unit,
    val play: (Song) -> Unit,
    val showTrackMenu: (Song) -> Unit,
    val openEntity: (String, SearchEntity) -> Unit,
    val showEntityMenu: (String, SearchEntity, FocusRequester) -> Unit,
)

@Composable
internal fun SearchPage(
    searchViewModel: SearchViewModel,
    plugins: Plugins,
    activePlugins: List<Plugin>,
    pluginSignature: String,
    libraryState: LibraryState,
    searchTypeOptions: List<Pair<String, String>>,
    searchHistory: Map<String, List<String>>,
    recentArtists: List<SavedSubject>,
    hotSearchCache: MutableMap<String, Pair<Long, List<SearchSuggestion>>>,
    busy: Boolean,
    playerOpen: Boolean,
    navBarFocused: Boolean,
    focusSearchResults: Boolean,
    currentSongKey: String?,
    mvCapableIds: Set<String>,
    focus: SearchPageFocus,
    actions: SearchPageActions,
) {
    val ui by searchViewModel.state.collectAsState()
    val (
        keyword,
        searchFiltersOpen,
        searchTerm,
        results,
        entities,
        hasMore,
        searchType,
        searchSources) =
        ui
    val searchSuggestionsVisible =
        !busy && (keyword.isBlank() || keyword.trim() != searchTerm.trim())
    val pageScope = rememberCoroutineScope()
    val phoneFillFocus = remember { FocusAnchor() }
    val c = LocalPrismColors.current
    fun search(query: String, append: Boolean = false) = actions.submit(query, append)
    CompositionLocalProvider(LocalListScrolling provides { focus.list.isScrollInProgress }) {
        Column(Modifier.fillMaxSize()) {
            val focusScope = focus.scope
            val (searchRowLayout, focusSearchRow) = rememberVerticalFocusSearch()
            var moreFocused by
                remember(searchTerm, searchType, pluginSignature, searchSources) {
                    mutableStateOf(false)
                }
            val showMore = hasMore || moreFocused
            fun focusLastResult() {
                val last = if (searchType == "music") results.lastIndex else entities.lastIndex
                if (last < 0) {
                    focus.input.requestIfAttached()
                    return
                }
                val target =
                    if (searchType == "music") {
                        if (last == 0) focus.results.requester
                        else focus.trackFor(results[last].key)
                    } else
                        entities[last].let {
                            focus.detailFor("$searchType:${it.pluginId}:${it.data.optString("id")}")
                        }
                if (!focusSearchRow(Key.DirectionUp) { target }) focus.input.requestIfAttached()
            }
            // Keep the focused footer until focus has actually returned to a result.
            LaunchedEffect(hasMore, busy, moreFocused) {
                if (!hasMore && !busy && moreFocused && !playerOpen) focusLastResult()
            }
            val moreEdges =
                Modifier.onFocusChanged { moreFocused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                            focusScope.coroutineContext.cancelChildren()
                            focusLastResult()
                            true
                        } else false
                    }
                    .focusProperties { down = focus.mini }
            /* Pressing 搜索 is the end of typing. Leaving the IME up covers half the
            results you just asked for — the same mistake as opening a page and
            covering it with a dialog. */
            val ime = LocalSoftwareKeyboardController.current
            val firstSuggestionFocus = remember { FocusAnchor() }
            val clearHistoryFocus = remember { FocusAnchor() }
            // Source code/configuration and selection own the cache; each type has its own results.
            var hotTerms by
                remember(pluginSignature, searchSources) {
                    mutableStateOf<Map<String, List<SearchSuggestion>>>(emptyMap())
                }
            var hotLoading by remember(pluginSignature, searchSources) { mutableStateOf(false) }
            LaunchedEffect(pluginSignature, searchSources, searchType) {
                if (hotTerms.containsKey(searchType)) {
                    hotLoading = false
                    return@LaunchedEffect
                }
                val type = searchType
                val cacheKey = "${pluginSignature.hashCode()}:${searchSources.sorted()}:$type"
                hotSearchCache[cacheKey]
                    ?.takeIf { System.currentTimeMillis() - it.first in 0 until 10 * 60_000 }
                    ?.let {
                        hotTerms = hotTerms + (type to it.second)
                        hotLoading = false
                        return@LaunchedEffect
                    }
                val providers =
                    activePlugins
                        .filter {
                            it.capabilities.has("hotSearch") &&
                                (searchSources.isEmpty() || it.id in searchSources)
                        }
                        .take(3)
                hotLoading = providers.isNotEmpty()
                try {
                    var received = emptyList<SearchSuggestion>()
                    coroutineScope {
                        providers
                            .map { plugin ->
                                async {
                                    val items =
                                        try {
                                            val result =
                                                withTimeout(8000) {
                                                    plugins.client.call(
                                                        plugin.source,
                                                        plugin.variables,
                                                        "getHotSearch",
                                                        JSONArray().put(type),
                                                        plugin.id,
                                                    )
                                                }
                                                    as? JSONArray ?: JSONArray()
                                            SearchSuggestions.parse(result, plugin.displayName)
                                        } catch (
                                            e: kotlinx.coroutines.TimeoutCancellationException) {
                                            emptyList()
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            emptyList()
                                        }
                                    received =
                                        (received + items)
                                            .distinctBy {
                                                it.keyword.lowercase(java.util.Locale.ROOT)
                                            }
                                            .take(12)
                                    hotTerms = hotTerms + (type to received)
                                }
                            }
                            .awaitAll()
                    }
                    if (received.isNotEmpty())
                        hotSearchCache[cacheKey] = System.currentTimeMillis() to received
                    while (hotSearchCache.size > 16) hotSearchCache.remove(
                        hotSearchCache.keys.first()
                    )
                } finally {
                    hotLoading = false
                }
            }
            val visibleHot = hotTerms[searchType].orEmpty()
            val typeHistory = searchHistory[searchType].orEmpty()
            val libraryTerms =
                remember(libraryState, searchType) {
                    SearchSuggestions.local(searchType, libraryState)
                }
            val localTerms =
                remember(keyword, searchHistory, libraryTerms, searchType) {
                    val history = typeHistory.map { SearchSuggestion(it, badge = "历史") }
                    (history + libraryTerms)
                        .filter { keyword.isBlank() || matchesSearchHistory(it.keyword, keyword) }
                        .distinctBy { it.keyword.lowercase(java.util.Locale.ROOT) }
                        .take(12)
                }
            val availableSuggestions =
                remember(
                    keyword,
                    visibleHot,
                    localTerms,
                    recentArtists,
                    activePlugins,
                    searchSources,
                    searchType,
                ) {
                    val direct =
                        if (searchType == "artist")
                            recentArtists
                                .filter { artist ->
                                    activePlugins.any { it.id == artist.pluginId } &&
                                        (searchSources.isEmpty() ||
                                            artist.pluginId in searchSources) &&
                                        (keyword.isBlank() ||
                                            matchesSearchHistory(artist.title, keyword))
                                }
                                .map {
                                    SearchSuggestion(
                                        it.title,
                                        artwork = it.artwork,
                                        subtitle = it.sourceName,
                                        badge = "最近打开",
                                        artist = it,
                                    )
                                }
                        else emptyList()
                    (direct +
                            (if (keyword.isBlank()) visibleHot + localTerms else localTerms)
                                .distinctBy { it.keyword.lowercase(java.util.Locale.ROOT) })
                        .take(12)
                }
            // Network arrivals must not replace the card currently under the remote.
            var pinnedSuggestions by
                remember(searchType, keyword, pluginSignature, searchSources) {
                    mutableStateOf<List<SearchSuggestion>?>(null)
                }
            val suggestionRows = pinnedSuggestions ?: availableSuggestions
            fun selectSuggestion(item: SearchSuggestion) {
                ime?.hide()
                focus.input.requestIfAttached()
                val artist = item.artist
                val plugin = activePlugins.firstOrNull { it.id == artist?.pluginId }
                if (artist != null && plugin != null) actions.openRecentArtist(plugin, artist)
                else {
                    searchViewModel.editDraft(item.keyword)
                    if (!busy) search(item.keyword)
                }
            }
            val suggestionsVisible = searchSuggestionsVisible
            /* 下键总要落到真实节点：联想与结果都可能为空，逐个退让到迷你条。 */
            val searchContentFocus =
                when {
                    suggestionsVisible && firstSuggestionFocus.attached -> firstSuggestionFocus
                    focus.results.attached -> focus.results
                    focus.more.attached -> focus.more
                    firstSuggestionFocus.attached -> firstSuggestionFocus
                    else -> focus.results
                }
            LaunchedEffect(
                focusSearchResults,
                busy,
                results.isNotEmpty(),
                entities.isNotEmpty(),
                hasMore,
                navBarFocused,
                searchFiltersOpen,
            ) {
                if (!focusSearchResults || navBarFocused || playerOpen || searchFiltersOpen)
                    return@LaunchedEffect
                if (results.isEmpty() && entities.isEmpty() && busy) return@LaunchedEffect
                if (results.isNotEmpty() || entities.isNotEmpty() || hasMore) {
                    focus.list.scrollToItem(0)
                    /* 首条可能同帧还没挂上；多等几帧，失败退回输入框。 */
                    repeat(12) {
                        withFrameNanos {}
                        if (focus.results.requestIfAttached()) {
                            actions.cancelResultsFocus()
                            return@LaunchedEffect
                        }
                    }
                    focus.input.requestIfAttached()
                } else {
                    withFrameNanos {}
                    focus.input.requestIfAttached()
                }
                actions.cancelResultsFocus()
            }
            /* Only offer types some enabled source can answer. A type that every
            source ignores would always land on an empty list. */
            LaunchedEffect(searchTypeOptions) {
                if (searchTypeOptions.none { it.first == searchType }) actions.useMusicType()
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
            fun revealOnDown(target: FocusAnchor): Modifier =
                Modifier.onPreviewKeyEvent { event ->
                    if (
                        event.type == KeyEventType.KeyDown &&
                            event.key == Key.DirectionDown &&
                            !target.attached
                    ) {
                        focusScope.coroutineContext.cancelChildren()
                        focusScope.launch {
                            val reached =
                                target.requester.requestAfterScroll {
                                    if (
                                        !suggestionsVisible &&
                                            (results.isNotEmpty() || entities.isNotEmpty())
                                    )
                                        focus.list.scrollToItem(0)
                                }
                            /* 迷你条是这一屏最后的落点，它总在。 */
                            if (
                                !reached &&
                                    !runCatching {
                                            focus.mini.requestFocus()
                                            true
                                        }
                                        .getOrDefault(false)
                            ) {
                                focus.input.requestIfAttached()
                            }
                        }
                        true
                    } else false
                }
            val resultMotion = LocalAuraMotion.current
            // Keep the column across rows; the last row exits to the footer or mini player.
            fun stepRows(
                index: Int,
                unit: Int,
                total: Int,
                requester: (Int) -> FocusRequester,
            ): Modifier =
                Modifier.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val delta =
                        when (event.key) {
                            Key.DirectionUp -> -1
                            Key.DirectionDown -> 1
                            else -> return@onPreviewKeyEvent false
                        }
                    val nextRow = index / unit + delta
                    if (nextRow < 0) return@onPreviewKeyEvent false
                    if (nextRow > (total - 1) / unit) {
                        if (unit > 1 && showMore) {
                            focusScope.coroutineContext.cancelChildren()
                            focusScope.launch {
                                focus.list.revealFocusRow(nextRow, resultMotion)
                                focus.more.requestIfAttached()
                            }
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent focusSearchRow(Key.DirectionDown) {
                            if (showMore) focus.more.requester else focus.mini
                        }
                    }
                    val next = (nextRow * unit + index % unit).coerceAtMost(total - 1)
                    focusScope.coroutineContext.cancelChildren()
                    if (unit > 1) {
                        focusScope.launch {
                            focus.list.revealFocusRow(nextRow, resultMotion)
                            requester(next).requestAfterScroll { focus.list.revealFocusRow(nextRow, resultMotion) }
                        }
                        true
                    } else focusSearchRow(event.key) { requester(next) }
                }
            /* Input first, then the source row. The source is a filter on the
            search, not a separate page — you pick it before you type, because
            switching it afterwards silently changes what you're looking at. */
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Input(
                    keyword,
                    searchViewModel::editDraft,
                    "搜索歌曲、歌手、专辑或歌单",
                    Modifier.weight(1f).focusAnchor(focus.input).focusProperties {
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
                        up = focus.nav
                    },
                    manualKeyboard = true,
                    onSubmit = { if (!busy && keyword.isNotBlank()) search(keyword) },
                    downTarget =
                        if (searchContentFocus.attached) searchContentFocus
                        else chipAnchor(searchType),
                )
                AuraIconButton(
                    "search",
                    if (busy) "搜索中" else "搜索",
                    {
                        /* Do not clearFocus here: this button already holds focus, and
                        clearing it leaves the whole screen without a focus target.
                        Hiding the IME is enough to get it out of the way. */
                        if (!busy) {
                            ime?.hide()
                            if (keyword.isBlank()) focus.input.requestIfAttached()
                            else search(keyword)
                        }
                    },
                    Modifier.focusProperties {
                            if (searchContentFocus.attached) down = searchContentFocus.requester
                            up = focus.nav
                        }
                        .then(revealOnDown(searchContentFocus)),
                    primary = true,
                )
                PhoneFillButton(
                    "手机代填搜索词",
                    {
                        actions.fillOnPhone(keyword) {
                            pageScope.launch {
                                repeat(2) { withFrameNanos {} }
                                if (!phoneFillFocus.requestIfAttached()) focus.input.requestIfAttached()
                            }
                        }
                    },
                    Modifier.focusAnchor(phoneFillFocus).focusProperties {
                        up = focus.nav
                        if (searchContentFocus.attached) down = searchContentFocus.requester
                    }.then(revealOnDown(searchContentFocus)),
                )
            }
            /* Filters are footnotes: a quiet link row under the field, not a strip
            of buttons competing with the thing you actually came to do. */
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "类型",
                    color = c.muted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(end = 6.dp),
                )
                searchTypeOptions.forEachIndexed { _, (type, label) ->
                    /* 聚焦即切换：遥控落在 tab 上就实时生效，无需确定。结果与联想区的
                    上键回到当前激活的 tab（聚焦已选中的 tab 不触发切换），再按上
                    才到输入框，回去换类型不用多按一次下。 */
                    PrismLink(
                        label,
                        { if (!busy && searchType != type) actions.changeType(type, true) },
                        Modifier.focusAnchor(chipAnchor(type))
                            .onFocusChanged { focused ->
                                if (focused.isFocused && searchType != type) {
                                    actions.changeType(type, false)
                                }
                            }
                            /* From the strip the remote should be able to leave upward as
                            well as downward: geometric search would otherwise strand it
                            in the row itself, since the field is the only thing above. */
                            .focusProperties {
                                if (focus.input.attached) up = focus.input.requester
                                val target =
                                    if (searchFiltersOpen) focus.chips else searchContentFocus
                                if (target.attached) down = target.requester
                            }
                            .then(
                                revealOnDown(
                                    if (searchFiltersOpen) focus.chips else searchContentFocus
                                )
                            ),
                        active = searchType == type,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Spacer(Modifier.weight(1f))
                PrismLink(
                    if (searchFiltersOpen) "音源 ▴"
                    else if (searchSources.isEmpty()) "全部音源 ▾" else "音源 ${searchSources.size} ▾",
                    {
                        /* Opening the row is an invitation to use it, so hand the remote to
                        the chips — but only on the press that opened it. From a
                        LaunchedEffect the same request re-fired whenever the page
                        recomposed, so merely switching to 搜索 parked focus on the
                        first chip and the bar's left/right walk stopped working. */
                        val open = !searchFiltersOpen
                        searchViewModel.showFilters(open)
                        if (open) actions.cancelResultsFocus()
                        if (open)
                            pageScope.launch {
                                repeat(3) { withFrameNanos {} }
                                focus.chips.requestIfAttached()
                            }
                    },
                    Modifier.focusAnchor(focus.source)
                        .focusProperties {
                            if (focus.input.attached) up = focus.input.requester
                            /* 收起时正下方最近的是「清空历史」，先落它；没有历史或已出
                            结果时它不存在，再退回内容区第一条。 */
                            if (searchFiltersOpen && focus.chips.attached)
                                down = focus.chips.requester
                            else if (clearHistoryFocus.attached) down = clearHistoryFocus.requester
                            else if (searchContentFocus.attached)
                                down = searchContentFocus.requester
                        }
                        .then(
                            revealOnDown(
                                if (searchFiltersOpen) focus.chips
                                else if (clearHistoryFocus.attached) clearHistoryFocus
                                else searchContentFocus
                            )
                        ),
                    active = searchFiltersOpen || searchSources.isNotEmpty(),
                )
            }
            if (searchFiltersOpen) {
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("音源", color = c.muted, fontSize = 10.sp, modifier = Modifier.width(30.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        item {
                            PrismAction(
                                if (searchSources.isEmpty()) "✓ 全部音源" else "全部音源",
                                { actions.toggleSource(null) },
                                Modifier.focusAnchor(focus.chips).focusProperties {
                                    if (focus.source.attached) up = focus.source.requester
                                },
                            )
                        }
                        itemsIndexed(activePlugins) { chipIndex, p ->
                            val platform = p.platform
                            val name = p.displayName
                            PlatformAction(
                                platform,
                                if (p.id in searchSources) "✓ $name" else name,
                                if (chipIndex == 0 && !focus.chips.attached)
                                    Modifier.focusAnchor(focus.chips)
                                else Modifier,
                            ) {
                                /* Checking sources is a browsing act: the remote stays on the
                                chips, not on the first result that re-arrives. */
                                actions.toggleSource(p.id)
                            }
                        }
                        if (activePlugins.isEmpty()) item { PrismAction("添加音源", actions.addSource) }
                    }
                }
            }
            if (suggestionsVisible) {
                /* On a remote the suggestions are a list, not a row of chips: Down
                moves into it and OK runs that search. A table header above an
                empty table was noise, so it only exists with real results. */
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (keyword.isNotBlank()) "搜索联想"
                        else if (suggestionRows.any { it.badge == "热搜" })
                            when (searchType) {
                                "artist" -> "热门歌手与最近搜索"
                                "album" -> "热门专辑与最近搜索"
                                "sheet" -> "热门歌单与最近搜索"
                                else -> "热搜与最近"
                            }
                        else
                            when (searchType) {
                                "artist" -> "常听歌手与最近搜索"
                                "album" -> "曲库专辑与最近搜索"
                                "sheet" -> "收藏歌单与最近搜索"
                                else -> "常听与最近"
                            },
                        color = c.foreground,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (hotLoading || suggestionRows.isNotEmpty())
                        Text(
                            if (hotLoading) "正在获取热门内容…"
                            else if (suggestionRows.any { it.artist != null }) "最近打开可直达歌手页"
                            else "确定即搜索",
                            color = c.muted,
                            fontSize = 11.sp,
                        )
                    Spacer(Modifier.weight(1f))
                    if (
                        typeHistory.isNotEmpty() ||
                            (searchType == "artist" && recentArtists.isNotEmpty())
                    ) {
                        /* 联想区是独立焦点组，几何搜索进不去：上下都点名。 */
                        PrismLink(
                            "清空历史",
                            {
                                actions.clearHistory(searchType)
                                pinnedSuggestions = null
                                pageScope.launch {
                                    repeat(2) { withFrameNanos {} }
                                    if (
                                        !firstSuggestionFocus.requestIfAttached() &&
                                            !chipAnchor(searchType).requestIfAttached()
                                    ) {
                                        focus.input.requestIfAttached()
                                    }
                                }
                            },
                            Modifier.focusAnchor(clearHistoryFocus).focusProperties {
                                upTo(focus.source, chipAnchor(searchType), focus.input)
                                leftTo(chipAnchor(searchType), focus.input)
                                if (searchContentFocus.attached) down = searchContentFocus.requester
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (suggestionRows.isEmpty())
                    Text(
                        if (keyword.isBlank()) "还没有搜索记录。用遥控器输入，或扫码用手机遥控搜索"
                        else "没有匹配的联想，按搜索键直接搜索「$keyword」",
                        color = c.muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                else
                    Column(
                        Modifier.weight(1f)
                            .onFocusChanged {
                                pinnedSuggestions =
                                    if (it.hasFocus) pinnedSuggestions ?: availableSuggestions
                                    else null
                            }
                            .focusGroup()
                            .padding(bottom = 10.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (keyword.isBlank() && searchType != "music")
                            suggestionRows.chunked(4).forEachIndexed { rowIndex, group ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    group.forEachIndexed { columnIndex, item ->
                                        val suggestionFocus =
                                            remember(
                                                item.keyword,
                                                item.badge,
                                                rowIndex,
                                                columnIndex,
                                            ) {
                                                FocusRequester()
                                            }
                                        PrismSearchSuggestionCard(
                                            item,
                                            searchType,
                                            { selectSuggestion(item) },
                                            Modifier.weight(1f)
                                                .focusRequester(suggestionFocus)
                                                .then(
                                                    if (rowIndex == 0 && columnIndex == 0)
                                                        Modifier.focusAnchor(firstSuggestionFocus)
                                                    else Modifier
                                                )
                                                .focusProperties {
                                                    if (rowIndex == 0)
                                                        upTo(
                                                            clearHistoryFocus,
                                                            chipAnchor(searchType),
                                                            focus.input,
                                                        )
                                                    if (rowIndex == suggestionRows.lastIndex / 4)
                                                        down = focus.mini
                                                }
                                                .onFocusChanged {
                                                    if (it.isFocused)
                                                        focus.onFocused(suggestionFocus)
                                                },
                                        )
                                    }
                                    repeat(4 - group.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        else
                            suggestionRows.forEachIndexed { index, item ->
                                val suggestionFocus =
                                    remember(item.keyword, item.badge, index) { FocusRequester() }
                                PrismSuggestionRow(
                                    item.title,
                                    item.badge,
                                    /* The row that was focused vanishes once the list collapses;
                                    hand focus back to the field so Down still reaches results. */
                                    { selectSuggestion(item) },
                                    Modifier.focusRequester(suggestionFocus)
                                        .then(
                                            if (index == 0)
                                                Modifier.focusAnchor(firstSuggestionFocus)
                                            else Modifier
                                        )
                                        .focusProperties {
                                            if (index == 0)
                                                upTo(
                                                    clearHistoryFocus,
                                                    chipAnchor(searchType),
                                                    focus.input,
                                                )
                                            if (index == suggestionRows.lastIndex) down = focus.mini
                                        }
                                        .onFocusChanged {
                                            if (it.isFocused) focus.onFocused(suggestionFocus)
                                        },
                                    artwork = item.artwork,
                                    subtitle = item.subtitle,
                                )
                            }
                    }
            } else {
                SearchResults(
                    SearchResultsState(
                        searchType,
                        keyword,
                        results,
                        entities,
                        busy,
                        hasMore,
                        showMore,
                        currentSongKey,
                        mvCapableIds,
                    ),
                    SearchResultsFocus(
                        focus.list,
                        focus.results,
                        focus.more,
                        chipAnchor(searchType),
                        focus.input,
                        searchRowLayout,
                        moreEdges,
                        focus.detailFor,
                        focus.trackFor,
                        ::stepRows,
                        focus.memory,
                        focus.registerTrack,
                    ),
                    onPlay = actions.play,
                    onTrackMenu = actions.showTrackMenu,
                    onOpenEntity = { actions.openEntity(searchType, it) },
                    onEntityMenu = { entity, requester ->
                        actions.showEntityMenu(searchType, entity, requester)
                    },
                    onLoadMore = { search(searchTerm, append = true) },
                )
            }
        }
    }
}

internal data class SearchResultsState(
    val type: String,
    val keyword: String,
    val results: List<Song>,
    val entities: List<SearchEntity>,
    val busy: Boolean,
    val hasMore: Boolean,
    val showMore: Boolean,
    val currentSongKey: String?,
    val mvCapableIds: Set<String>,
)

internal data class SearchResultsFocus(
    val list: LazyListState,
    val entry: FocusAnchor,
    val more: FocusAnchor,
    val chip: FocusAnchor,
    val input: FocusAnchor,
    val rowLayout: Modifier,
    val moreEdges: Modifier,
    val card: (String) -> FocusRequester,
    val track: (String) -> FocusRequester,
    val stepRows: (Int, Int, Int, (Int) -> FocusRequester) -> Modifier,
    val memory: MutableState<Triple<FocusRequester, Int, Int>?>,
    val registerTrack: (Song, FocusRequester) -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ColumnScope.SearchResults(
    state: SearchResultsState,
    focus: SearchResultsFocus,
    onPlay: (Song) -> Unit,
    onTrackMenu: (Song) -> Unit,
    onOpenEntity: (SearchEntity) -> Unit,
    onEntityMenu: (SearchEntity, FocusRequester) -> Unit,
    onLoadMore: () -> Unit,
) {
    val c = LocalPrismColors.current
    val (searchType, keyword, results, entities, busy, hasMore, showMore) = state
    fun entityFocus(entry: SearchEntity) =
        focus.card("$searchType:${entry.pluginId}:${entry.data.optString("id")}")
    /* 条目数在页头已经写着，网格结果不需要再单开一行标题：
    这一行连同上下的留白会从可视区里抢走接近一整行卡片的高度，
    网格恰好只放得下一行时，聚焦边框就被下沿裁掉一截。 */
    Spacer(Modifier.height(if (searchType == "music") 18.dp else 14.dp))
    if (searchType == "music") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("搜索结果", color = c.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            val resultCount = results.size
            if (resultCount > 0) Text("$resultCount 条", color = c.muted, fontSize = 11.sp)
            if (resultCount > 0) {
                Spacer(Modifier.weight(1f))
                Text("长按确定键：收藏 / 找歌手", color = c.muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (searchType != "music") {
        if (entities.isEmpty() && busy) SearchPlaceholder(true)
        else if (entities.isEmpty())
            PrismEmptyState("没有找到「$keyword」的结果。可以换关键词或搜索类型。", "修改关键词",
                { focus.input.requestIfAttached() }, actionModifier = Modifier.focusAnchor(focus.entry))
        else {
            var focusedRow by remember { mutableStateOf<Int?>(null) }
            val defaultReveal = LocalBringIntoViewSpec.current
            val reveal = remember(defaultReveal) { FocusRowRevealSpec(defaultReveal) { focusedRow != null } }
            val motion = LocalAuraMotion.current
            LaunchedEffect(focusedRow) {
                val row = focusedRow ?: return@LaunchedEffect
                withFrameNanos {}
                focus.list.revealFocusRow(row, motion)
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides reveal) {
            LazyColumn(
                Modifier.weight(1f).padding(bottom = 10.dp),
                state = focus.list,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                /* 末排聚焦边框要能完整滚入视口，底部留白与发现页榜单一致。 */
                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 22.dp),
            ) {
                itemsIndexed(entities.chunked(6)) { rowIndex, row ->
                    Row(
                        focus.rowLayout.onFocusChanged {
                            if (it.hasFocus) focusedRow = rowIndex
                            else if (focusedRow == rowIndex) focusedRow = null
                        }.focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEachIndexed { col, entry ->
                            PrismSheetCard(
                                entry.data.optString("title").ifBlank {
                                    entry.data.optString("name").ifBlank { "未命名" }
                                },
                                entry.data.optString("artwork").ifBlank {
                                    entry.data.optString("coverImg")
                                }.ifBlank { if (searchType == "artist") entry.data.optString("avatar") else "" },
                                entry.sourceName,
                                portrait = searchType == "artist",
                                modifier =
                                    Modifier.focusRequester(entityFocus(entry))
                                        .then(
                                            if (entry === entities.first())
                                                Modifier.focusAnchor(focus.entry)
                                            else Modifier
                                        )
                                        /* 只有首排按上回分类 tab；其余排在行内点名上下，
                                        不再被绑死在页头。 */
                                        .then(
                                            if (rowIndex == 0)
                                                Modifier.focusProperties {
                                                    upTo(focus.chip, focus.input)
                                                }
                                            else Modifier
                                        )
                                        .then(
                                            focus.stepRows(rowIndex * 6 + col, 6, entities.size) { i
                                                ->
                                                val target = entities[i]
                                                entityFocus(target)
                                            }
                                        )
                                        /* 末排按下去落「加载更多」，而不是靠几何搜索碰运气。 */
                                        .onFocusChanged { focused ->
                                            if (focused.isFocused) {
                                                focus.memory.value =
                                                    Triple(entityFocus(entry), rowIndex, col)
                                            }
                                        },
                                onLongPress = { onEntityMenu(entry, entityFocus(entry)) },
                            ) {
                                onOpenEntity(entry)
                            }
                        }
                    }
                }
                if (showMore)
                    item(key = "search-more") {
                        /* 加载下一页是用户明确按的：以前滚到末排自动追加，
                        与歌手/专辑/歌单网格的「加载更多」不是一套交互。 */
                        if (busy) SearchPlaceholder(cards = true)
                        SearchPageFooter(
                            busy,
                            hasMore,
                            onLoadMore,
                            focus.rowLayout
                                .keepFocusVisible()
                                .focusAnchor(focus.more)
                                .then(focus.moreEdges),
                        )
                    }
            }
            }
        }
    } else {
        if (results.isNotEmpty()) {
            PrismTrackHeader()
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line.copy(alpha = .5f)))
        }
        if (results.isEmpty() && busy) SearchPlaceholder(false)
        else if (results.isEmpty()) {
            PrismEmptyState(
                if (hasMore) "本页暂无歌曲" else "没有找到「$keyword」的歌曲。可以换关键词或音源。",
                if (hasMore) "加载更多" else "修改关键词",
                if (hasMore) onLoadMore else ({ focus.input.requestIfAttached(); Unit }),
                actionModifier = Modifier.focusAnchor(focus.entry),
            )
        } else
            LazyColumn(
                Modifier.weight(1f).padding(top = 4.dp, bottom = 10.dp),
                state = focus.list,
                contentPadding = PaddingValues(vertical = 10.dp),
            ) {
                itemsIndexed(results, key = { _, song -> song.key }) { index, song ->
                    val rowFocus = if (index == 0) focus.entry.requester else focus.track(song.key)
                    focus.registerTrack(song, rowFocus)
                    /* A search pick joins the queue playing now; it does not
                    wipe the songs you were listening to. */
                    PrismTrackRow(
                        song,
                        index,
                        { onPlay(song) },
                        current = state.currentSongKey == song.key,
                        showMv = song.pluginId in state.mvCapableIds,
                        onLongPress = { onTrackMenu(song) },
                        /* 首行按上回分类 tab；其余行按行号上下点名，回到第一行再按上才离开列表。 */
                        modifier =
                            (if (index == 0) Modifier.focusAnchor(focus.entry)
                                else Modifier.focusRequester(rowFocus))
                                // The row draws its border inside its bounds. Let LazyColumn
                                // handle focus scrolling without a second reveal request.
                                .then(focus.rowLayout)
                                .then(
                                    if (index == 0)
                                        Modifier.focusProperties { upTo(focus.chip, focus.input) }
                                    else Modifier
                                )
                                .then(
                                    focus.stepRows(index, 1, results.size) { i ->
                                        if (i == 0) focus.entry.requester
                                        else focus.track(results[i].key)
                                    }
                                )
                                /* 末行按下去落「加载更多」。 */
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        focus.memory.value = Triple(rowFocus, index, -1)
                                    }
                                },
                    )
                }
                if (showMore)
                    item(key = "search-more") {
                        if (busy) SearchPlaceholder(cards = false)
                        SearchPageFooter(
                            busy,
                            hasMore,
                            onLoadMore,
                            focus.rowLayout
                                .keepFocusVisible()
                                .focusAnchor(focus.more)
                                .then(focus.moreEdges),
                        )
                    }
            }
    }
    if (showMore && results.isEmpty() && entities.isEmpty()) {
        SearchPageFooter(
            busy,
            hasMore,
            onLoadMore,
            Modifier.focusAnchor(focus.entry).focusAnchor(focus.more).then(focus.moreEdges),
        )
    }
}

@Composable
internal fun SearchPageFooter(
    loading: Boolean,
    hasMore: Boolean,
    onLoad: () -> Unit,
    modifier: Modifier,
) {
    val c = LocalPrismColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(c.line))
        PrismAction(
            if (loading) "正在加载…" else if (hasMore) "加载更多" else "已加载全部",
            onLoad,
            modifier,
            enabled = hasMore && !loading,
            icon = if (loading) "history" else if (hasMore) "chev" else "check",
        )
        Box(Modifier.weight(1f).height(1.dp).background(c.line))
    }
}

@Composable
internal fun SearchPlaceholder(cards: Boolean) {
    val tint = LocalPrismColors.current.line.copy(alpha = .45f)
    if (cards)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(6) {
                Box(Modifier.weight(1f).aspectRatio(1f).background(tint, RoundedCornerShape(10.dp)))
            }
        }
    else
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(36.dp).background(tint, RoundedCornerShape(6.dp)))
                    Box(
                        Modifier.fillMaxWidth(.45f)
                            .height(10.dp)
                            .background(tint, RoundedCornerShape(5.dp))
                    )
                }
            }
        }
}
