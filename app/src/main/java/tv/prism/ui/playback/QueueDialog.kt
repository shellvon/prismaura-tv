package tv.prism.ui.playback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.prism.ui.widgets.FocusAnchor
import tv.prism.library.Song
import tv.prism.ui.widgets.requestIfAttached
import tv.prism.ui.widgets.requestAfterScroll
import tv.prism.ui.widgets.LocalPrismColors
import tv.prism.ui.widgets.Glyph
import tv.prism.ui.widgets.auraFocus
import tv.prism.ui.widgets.tvClickable
import tv.prism.ui.widgets.focusAnchor
import tv.prism.ui.widgets.AuraMark
import tv.prism.ui.widgets.PrismQueueRow
import tv.prism.ui.widgets.rowFocus

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun QueueDialog(
    queue: List<Song>,
    order: List<Int>,
    queueIndex: Int,
    following: Song?,
    radio: Boolean,
    favorites: List<Song>,
    queuePlaybackFocus: FocusRequester,
    queueRootFocus: FocusRequester,
    detailFocus: (String) -> FocusRequester,
    motionEnabled: Boolean,
    mvCapableIds: Set<String> = emptySet(),
    onClose: () -> Unit,
    onSongMenu: (Song, FocusRequester) -> Unit,
    onPlay: (Int) -> Unit,
) {
    val c = LocalPrismColors.current
    val favoriteKeys = remember(favorites) { favorites.map { it.key }.toSet() }
    val currentQueueFocus = queuePlaybackFocus
    val queueFocusIndex = order.indexOf(queueIndex).coerceAtLeast(0)
    val queueListState = rememberLazyListState(initialFirstVisibleItemIndex = queueFocusIndex)
    val panel = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    var cursor by remember { mutableIntStateOf(queueFocusIndex) }
    var navigated by remember { mutableStateOf(false) }
    var focusMove by remember { mutableStateOf<Job?>(null) }
    val panelScope = rememberCoroutineScope()
    val queueCloseFocus = remember { FocusAnchor() }
    val closeQueue: () -> Unit = { panelScope.launch { panel.targetState = false; delay(260); onClose() } }
    Dialog(onDismissRequest = closeQueue, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { panel.targetState = true }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            AnimatedVisibility(visibleState = panel, enter = slideInHorizontally(tween(if (motionEnabled) 260 else 0)) { it }, exit = slideOutHorizontally(tween(if (motionEnabled) 260 else 0)) { it }) {
                val window = androidx.compose.ui.platform.LocalWindowInfo.current
                val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
                fun requestRow(position: Int) {
                    focusMove?.cancel()
                    cursor = position
                    focusMove = panelScope.launch {
                        if (position < 0) { queueCloseFocus.requestIfAttached(); return@launch }
                        val index = order.getOrNull(position) ?: return@launch
                        val item = queue.getOrNull(index) ?: return@launch
                        val target = detailFocus("queue:${item.key}:$index")
                        if (queueListState.layoutInfo.visibleItemsInfo.none { it.index == position })
                            queueListState.scrollToItem(position)
                        target.requestAfterScroll { queueListState.scrollToItem(position) }
                    }
                }
                LaunchedEffect(queueIndex, queueFocusIndex, order.isEmpty()) {
                    snapshotFlow { window.isWindowFocused }.first { it }
                    inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                    if (!navigated) {
                        navigated = true
                        requestRow(if (order.isEmpty()) -1 else queueFocusIndex)
                    }
                }
                Column(Modifier.fillMaxWidth(.46f).fillMaxHeight()
                    .focusProperties { enter = {
                        when {
                            order.isEmpty() -> queueCloseFocus.requester
                            !navigated && queueListState.layoutInfo.visibleItemsInfo.any { it.index == queueFocusIndex } -> currentQueueFocus
                            else -> FocusRequester.Default
                        }
                    } }
                    .focusGroup().onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || order.isEmpty() ||
                            (event.key != Key.DirectionUp && event.key != Key.DirectionDown)) return@onPreviewKeyEvent false
                        val from = if (navigated) cursor else queueFocusIndex
                        navigated = true
                        requestRow((from + if (event.key == Key.DirectionDown) 1 else -1).coerceIn(-1, order.lastIndex))
                        true
                    }.background(Brush.verticalGradient(listOf(c.panel.copy(alpha = .98f), c.background.copy(alpha = .98f)))).padding(horizontal = 24.dp, vertical = 28.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AuraMark(Modifier.size(34.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("接下来播放", color = c.foreground, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                            Text(following?.let { "下一曲 · ${it.title}" }
                                ?: when {
                                    radio -> "等待电台续播"
                                    queue.isEmpty() -> "队列为空"
                                    else -> "已到队列末尾"
                                },
                                color = c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        /* Same close affordance as every menu: a bare X in the corner. */
                        Box(Modifier.size(38.dp).focusRequester(queueRootFocus).focusAnchor(queueCloseFocus)
                            .onFocusChanged { if (it.isFocused && focusMove?.isActive != true) cursor = -1 }
                            .auraFocus(RoundedCornerShape(50), 1f)
                            .semantics { contentDescription = "关闭播放列表" }
                            .tvClickable(closeQueue, closeQueue), contentAlignment = Alignment.Center) {
                            Glyph("close", Modifier.size(20.dp), c.muted)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    if (queue.isEmpty()) Text("播放歌曲后显示队列", color = c.muted)
                    LazyColumn(Modifier.weight(1f), state = queueListState) {
                        itemsIndexed(order, key = { _, index -> index }) { position, index ->
                            val item = queue[index]
                            val rowFocus = detailFocus("queue:${item.key}:$index")
                            val rowModifier = Modifier.focusRequester(rowFocus)
                                .then(if (index == queueIndex) Modifier.focusRequester(currentQueueFocus) else Modifier).onFocusChanged {
                                if (it.isFocused && navigated && focusMove?.isActive != true) cursor = position
                            }
                            PrismQueueRow(item, position, index == queueIndex,
                                /* The first row has no neighbour above it inside the panel's
                                   focus space; point Up at 关闭 so it is always reachable. */
                                if (position == 0 && queueCloseFocus.attached) rowModifier.focusProperties { up = queueCloseFocus.requester } else rowModifier,
                                /* Every song row in the app answers a long hold the same
                                   way; the queue is no exception. */
                                onLongPress = { onSongMenu(item, rowFocus) },
                                favorite = item.key in favoriteKeys,
                                showMv = item.pluginId in mvCapableIds,
                            ) { closeQueue(); onPlay(index) }
                        }
                    }
                }
            }
        }
    }
}
