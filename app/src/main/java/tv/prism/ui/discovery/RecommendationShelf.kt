package tv.prism.ui.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import tv.prism.catalog.Discovery
import tv.prism.ui.widgets.LocalPrismColors
import tv.prism.ui.widgets.ChoicePopup
import tv.prism.ui.widgets.FocusAnchor
import tv.prism.ui.widgets.PrismShelfHeader
import tv.prism.ui.widgets.PrismAction
import tv.prism.ui.widgets.focusAnchor
import tv.prism.ui.widgets.RecommendationSkeleton
import tv.prism.ui.widgets.PrismSheetCard
import tv.prism.ui.widgets.requestAfterScroll
import tv.prism.ui.widgets.requestIfAttached

@Composable
internal fun RecommendationShelf(
    sourceName: String,
    recommendations: List<JSONObject>,
    recommendationTags: List<JSONObject>,
    recommendationTag: JSONObject,
    recommendationLoading: Boolean,
    recommendationMore: Boolean,
    recommendationStatus: String,
    onSelectTag: (JSONObject) -> Unit,
    onMore: () -> Unit,
    up: FocusRequester, entry: FocusAnchor, firstCard: FocusAnchor, below: FocusAnchor,
                                            cardFocus: (JSONObject) -> FocusRequester, edges: (Int) -> Modifier, rowState: LazyListState,
                                            onMenu: (JSONObject, FocusRequester) -> Unit, modifier: Modifier = Modifier, onOpen: (JSONObject) -> Unit) {
    val c = LocalPrismColors.current
    var focusedIndex by remember { mutableIntStateOf(-1) }
    var pagingIndex by remember(recommendationTag.optString("id"), sourceName) { mutableStateOf<Int?>(null) }
    val moreFocus = remember { FocusRequester() }
    val pendingIndex = pagingIndex
    LaunchedEffect(recommendations.size, recommendationLoading, recommendationMore, pendingIndex) {
        val index = pendingIndex ?: return@LaunchedEffect
        if (recommendationLoading) return@LaunchedEffect
        if (recommendations.size == index && !recommendationMore && focusedIndex == index) {
            val previous = recommendations.lastIndex
            if (previous >= 0) {
                cardFocus(recommendations[previous]).requestAfterScroll { rowState.scrollToItem(previous) }
            }
        }
        pagingIndex = null
    }
    var categoriesOpen by remember { mutableStateOf(false) }
    val defaultCategory = remember { FocusAnchor() }
    val moreCategory = remember { FocusAnchor() }
    val categoryRowState = rememberLazyListState()
    val categoryAnchors = remember { mutableMapOf<String, FocusAnchor>() }
    fun categoryAnchor(id: String): FocusAnchor = when (id) {
        "default" -> defaultCategory
        "more" -> moreCategory
        else -> categoryAnchors.getOrPut(id) { FocusAnchor() }
    }
    var returnCategory by remember(recommendationTag.optString("id"), sourceName) {
        mutableStateOf<String?>(null)
    }
    val scope = rememberCoroutineScope()
    val categoryPositions = remember { mutableMapOf<String, LayoutCoordinates>() }
    val cardPositions = remember { mutableMapOf<Int, LayoutCoordinates>() }
    var enterFrom by remember(recommendationTag.optString("id"), sourceName) { mutableStateOf<String?>(null) }
    fun centerX(coordinates: LayoutCoordinates?): Float? =
        coordinates?.takeIf { it.isAttached }?.let { it.positionInRoot().x + it.size.width / 2f }
    LaunchedEffect(enterFrom, recommendationLoading, recommendations) {
        val category = enterFrom ?: return@LaunchedEffect
        if (recommendationLoading && recommendations.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        if (recommendations.isEmpty()) {
            below.requestIfAttached()
        } else {
            val x = centerX(categoryPositions[category])
            val layout = rowState.layoutInfo
            val closest = layout.visibleItemsInfo
                .filter { it.index in recommendations.indices &&
                    it.offset < layout.viewportEndOffset && it.offset + it.size > layout.viewportStartOffset }
                .mapNotNull { item -> centerX(cardPositions[item.index])?.let { item.index to it } }
                .minByOrNull { (_, cardX) -> if (x == null) 0f else kotlin.math.abs(cardX - x) }
                ?.first
            val target = closest ?: 0
            if (cardFocus(recommendations[target]).requestAfterScroll { rowState.scrollToItem(target) }) {
                returnCategory = category
            }
        }
        enterFrom = null
    }
    fun categoryKeys(id: String, selectedId: String) = Modifier
        .then(if (id == selectedId) Modifier.focusAnchor(entry) else Modifier)
        .focusAnchor(categoryAnchor(id))
        .onPlaced { categoryPositions[id] = it }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (event.key == Key.DirectionDown) {
                enterFrom = id
                true
            } else {
                enterFrom = null
                false
            }
        }
    fun closeCategories() {
        categoriesOpen = false
        scope.launch { withFrameNanos { }; moreCategory.requestIfAttached() }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrismShelfHeader("歌单推荐", null, "", {})
        val scenes = Discovery.scenes(recommendationTags)
        val sceneIds = scenes.map { it.second.optString("id") }.toSet()
        val choices = scenes + recommendationTags.filterNot { it.optString("id") in sceneIds }.map { it.optString("title") to it }
        val tailChips = choices.take(3)
        val moreChip = choices.size > 3
        val categoryIds = listOf("default") + tailChips.map { "tag:${it.second.optString("id")}" } +
            if (moreChip) listOf("more") else emptyList()
        val tagId = recommendationTag.optString("id")
        val selectedCategory = when {
            tagId.isEmpty() -> "default"
            "tag:$tagId" in categoryIds -> "tag:$tagId"
            choices.drop(3).any { it.second.optString("id") == tagId } -> "more"
            else -> "default"
        }
        LaunchedEffect(selectedCategory, categoryIds) {
            val index = categoryIds.indexOf(selectedCategory)
            if (index >= 0 && categoryRowState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                categoryRowState.scrollToItem(index)
            }
        }
        val upToCategory = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionUp) return@onPreviewKeyEvent false
            val id = returnCategory?.takeIf { it in categoryIds } ?: selectedCategory
            scope.launch {
                val anchor = categoryAnchor(id)
                if (!anchor.requester.requestAfterScroll { categoryRowState.scrollToItem(categoryIds.indexOf(id)) }) {
                    if (!entry.requestIfAttached()) up.requestFocus()
                }
            }
            true
        }
        // Keep Right inside the category strip at its last item.
        val stopRight = Modifier.onPreviewKeyEvent { event ->
            event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight
        }
        LazyRow(state = categoryRowState, horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
            item { PrismAction(if (recommendationTag.optString("id").isEmpty()) "✓ 推荐" else "推荐", {
                onSelectTag(JSONObject().put("id", "").put("title", "推荐"))
            }, categoryKeys("default", selectedCategory).focusProperties {
                this.up = up

            }.then(if (tailChips.isEmpty() && !moreChip) stopRight else Modifier)) }
            itemsIndexed(tailChips) { index, (label, tag) ->
                PrismAction("${if (recommendationTag.optString("id") == tag.optString("id")) "✓ " else ""}$label", {
                    onSelectTag(tag)
                }, categoryKeys("tag:${tag.optString("id")}", selectedCategory).focusProperties {
                    this.up = up

                }.then(if (!moreChip && index == tailChips.lastIndex) stopRight else Modifier))
            }
            if (moreChip) item {
                var anchor by remember { mutableStateOf(Rect.Zero) }
                Box(Modifier.onGloballyPositioned { anchor = it.boundsInWindow() }) {
                    val selected = choices.drop(3).firstOrNull { it.second.optString("id") == recommendationTag.optString("id") }
                    PrismAction(selected?.first ?: "全部分类", { categoriesOpen = true },
                        categoryKeys("more", selectedCategory).focusProperties {
                            this.up = up

                        }.then(stopRight), trailingIcon = "chev")
                    if (categoriesOpen) ChoicePopup(
                        options = choices,
                        selectedIndex = choices.indexOfFirst { it.second.optString("id") == recommendationTag.optString("id") },
                        label = { it.first },
                        onDismiss = ::closeCategories,
                        onSelect = { onSelectTag(it.second); closeCategories() },
                        icon = {},
                        anchor = anchor,
                    )
                }
            }
        }
        if (recommendations.isEmpty()) {
            if (recommendationLoading) RecommendationSkeleton()
            else Text(recommendationStatus.ifBlank { "该分类暂无歌单" }, color = c.muted, fontSize = 12.sp)
        } else {
            LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
                // The new first card takes the paging tile's existing lazy item slot.
                val showMore = recommendationMore || pagingIndex == recommendations.size
                items(recommendations.size + if (showMore) 1 else 0) { index ->
                    val sheet = recommendations.getOrNull(index)
                    val focus = if (sheet != null) cardFocus(sheet) else moreFocus
                    val loadingMore = sheet == null && recommendationLoading
                    PrismSheetCard(
                        title = sheet?.optString("title")?.ifBlank { "推荐歌单" }
                            ?: if (loadingMore) "正在加载…" else "更多歌单",
                        artwork = sheet?.optString("artwork")?.ifBlank { sheet.optString("coverImg") }.orEmpty(),
                        subtitle = if (sheet != null) sourceName else if (loadingMore) " " else "继续浏览",
                        modifier = Modifier.focusRequester(focus)
                            .then(if (index == 0) Modifier.focusAnchor(firstCard) else Modifier)
                            .then(upToCategory)
                            .focusProperties {
                                if (sheet == null) {
                                    right = FocusRequester.Cancel
                                    if (below.attached) down = below.requester
                                }
                            }
                            .then(if (sheet != null) Modifier.onPlaced { cardPositions[index] = it }.then(edges(index)) else Modifier),
                        onLongPress = { if (sheet != null) onMenu(sheet, focus) },
                        shelfFocused = focusedIndex == index,
                        onShelfFocusChanged = { focused ->
                            if (focused) focusedIndex = index
                            else if (focusedIndex == index) focusedIndex = -1
                        },
                        artworkIcon = if (sheet == null) "chevR" else null,
                    ) {
                        if (sheet != null) onOpen(sheet)
                        else if (!recommendationLoading && recommendationMore) {
                            pagingIndex = index
                            onMore()
                        }
                    }
                }
            }
            if (recommendationStatus.isNotBlank()) Text(recommendationStatus, color = c.muted, fontSize = 12.sp)
        }
    }
}
