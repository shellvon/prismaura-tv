package tv.prism.ui.widgets

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.BeyondBoundsLayout
import androidx.compose.ui.layout.ModifierLocalBeyondBoundsLayout
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.withFrameNanos

/* Long titles scroll only on the focused tile: a shelf of moving text is noise, and the
   focused item is the one being read. Motion-off users get the ellipsized static version. */
@Composable
fun Modifier.focusedMarquee(active: Boolean): Modifier =
    if (active && LocalAuraMotion.current && !LocalMarqueeSuspended.current) basicMarquee(iterations = 3, initialDelayMillis = 1200, repeatDelayMillis = 1200)
    else this

/* A FocusRequester that knows whether anything is bound to it. Compose throws when a
   directional move is routed to a requester whose node has left composition, and every
   focusProperties target here points across pages, so the target has to be checked first. */
class FocusAnchor {
    val requester = FocusRequester()
    private var holders by mutableIntStateOf(0)
    val attached: Boolean get() = holders > 0
    internal fun hold() { holders += 1 }
    internal fun release() { holders -= 1 }
}

fun Modifier.focusAnchor(anchor: FocusAnchor): Modifier =
    then(FocusAnchorElement(anchor)).focusRequester(anchor.requester)

private data class FocusAnchorElement(val anchor: FocusAnchor) : ModifierNodeElement<FocusAnchorNode>() {
    override fun create() = FocusAnchorNode(anchor)
    override fun update(node: FocusAnchorNode) = node.update(anchor)
    override fun InspectorInfo.inspectableProperties() { name = "focusAnchor" }
}

private class FocusAnchorNode(private var anchor: FocusAnchor) : Modifier.Node() {
    override fun onAttach() = anchor.hold()
    override fun onDetach() = anchor.release()

    fun update(next: FocusAnchor) {
        if (anchor === next) return
        if (isAttached) anchor.release()
        anchor = next
        if (isAttached) anchor.hold()
    }
}

fun FocusAnchor?.requestIfAttached(): Boolean {
    val anchor = this ?: return false
    if (!anchor.attached) return false
    return runCatching { anchor.requester.requestFocus() }.getOrDefault(false)
}

/** Reveal the card plus the focus border. */
internal fun Modifier.keepFocusVisible(): Modifier = then(FocusRevealElement(includeDescendants = false))

/** Keep a form label and its focused control visible together. */
internal fun Modifier.revealRow(): Modifier = then(FocusRevealElement(includeDescendants = true))

private data class FocusRevealElement(val includeDescendants: Boolean) : ModifierNodeElement<FocusRevealNode>() {
    override fun create() = FocusRevealNode(includeDescendants)
    override fun update(node: FocusRevealNode) { node.includeDescendants = includeDescendants }
    override fun InspectorInfo.inspectableProperties() { name = "focusReveal" }
}

private class FocusRevealNode(var includeDescendants: Boolean) : Modifier.Node(),
    FocusEventModifierNode, LayoutAwareModifierNode {
    private var itemSize = IntSize.Zero
    private var focused = false
    private var scrollJob: Job? = null

    override fun onRemeasured(size: IntSize) { itemSize = size }

    override fun onFocusEvent(focusState: FocusState) {
        val active = if (includeDescendants) focusState.hasFocus else focusState.isFocused
        if (focused == active) return
        focused = active
        scrollJob?.cancel()
        scrollJob = if (active) coroutineScope.launch {
            withFrameNanos { }
            bringIntoView {
                val margin = with(requireDensity()) { (if (includeDescendants) 8.dp else 10.dp).toPx() }
                Rect(-margin, -margin, itemSize.width + margin, itemSize.height + margin)
            }
        } else null
    }

    override fun onDetach() {
        focused = false
        scrollJob = null
    }
}

/** Mount without moving the viewport. Apply the modifier outside any nested horizontal list. */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun rememberVerticalFocusSearch(): Pair<Modifier, (Key, () -> FocusRequester?) -> Boolean> {
    var layout by remember { mutableStateOf<BeyondBoundsLayout?>(null) }
    val modifier = Modifier.modifierLocalConsumer { ModifierLocalBeyondBoundsLayout.current?.let { layout = it } }
    val request: (Key, () -> FocusRequester?) -> Boolean = { direction, target ->
        fun focus() = target()?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
        val searchDirection = when (direction) {
            Key.DirectionUp -> BeyondBoundsLayout.LayoutDirection.Above
            Key.DirectionDown -> BeyondBoundsLayout.LayoutDirection.Below
            else -> null
        }
        when {
            searchDirection == null -> false
            focus() -> true
            else -> layout?.layout(searchDirection) {
                when {
                    focus() -> true
                    !hasMoreContent -> false
                    else -> null
                }
            } == true
        }
    }
    return modifier to request
}

/** A card row has one vertical reveal when entered. Child requests belong to its own axis. */
@OptIn(ExperimentalFoundationApi::class)
internal class FocusRowRevealSpec(
    private val delegate: BringIntoViewSpec,
    private val isRowFocused: () -> Boolean,
) : BringIntoViewSpec by delegate {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        if (isRowFocused()) 0f else delegate.calculateScrollDistance(offset, size, containerSize)
}

internal suspend fun LazyListState.revealFocusRow(index: Int, motion: Boolean) {
    val layout = layoutInfo
    val shelf = layout.visibleItemsInfo.firstOrNull { it.index == index }
    if (shelf == null) {
        if (motion) animateScrollToItem(index) else scrollToItem(index)
        return
    }
    val distance = when {
        shelf.offset < layout.viewportStartOffset -> shelf.offset - layout.viewportStartOffset
        shelf.offset + shelf.size > layout.viewportEndOffset ->
            (shelf.offset + shelf.size - layout.viewportEndOffset)
                .coerceAtMost((shelf.offset - layout.viewportStartOffset).coerceAtLeast(0))
        else -> 0
    }
    if (distance != 0) {
        if (motion) animateScrollBy(distance.toFloat())
        else scrollBy(distance.toFloat())
    }
}

/** Reveal a lazy item before retrying; leave composition changes time to attach its target. */
internal suspend fun FocusRequester.requestAfterScroll(scroll: suspend () -> Unit): Boolean {
    if (runCatching { requestFocus() }.getOrDefault(false)) return true
    return withTimeoutOrNull(1000) {
        scroll()
        repeat(8) {
            withFrameNanos { }
            if (runCatching { requestFocus() }.getOrDefault(false)) return@withTimeoutOrNull true
        }
        false
    } ?: false
}

/* A named edge may only point at a node that is still composed: when the user presses the
   key, Compose dereferences the stored requester inside moveFocus and throws
   "FocusRequester is not initialized" for a ghost — killing the whole activity, because the
   exception escapes from the key dispatch, not from our handler. Anchors know whether they
   own a node, so edges are named through these helpers: the first live candidate wins, and
   when none is alive the edge stays unnamed and the geometric search takes over. */
fun FocusProperties.upTo(vararg candidates: FocusAnchor?) {
    candidates.firstOrNull { it != null && it.attached }?.let { up = it.requester }
}

fun FocusProperties.downTo(vararg candidates: FocusAnchor?) {
    candidates.firstOrNull { it != null && it.attached }?.let { down = it.requester }
}

fun FocusProperties.leftTo(vararg candidates: FocusAnchor?) {
    candidates.firstOrNull { it != null && it.attached }?.let { left = it.requester }
}

fun FocusProperties.rightTo(vararg candidates: FocusAnchor?) {
    candidates.firstOrNull { it != null && it.attached }?.let { right = it.requester }
}

/* Remembers the last focused node in a shared slot so a later key (the mini player's Up)
   can hand focus back to the body control the remote came from. The slot may hold a
   requester whose node has left composition; the read side must tolerate that. */
@Composable
fun Modifier.rememberFocus(memory: MutableState<FocusRequester?>): Modifier {
    val requester = remember { FocusRequester() }
    return then(Modifier.focusRequester(requester).onFocusChanged { if (it.isFocused) memory.value = requester })
}

/** Remote and pointer activation share one gesture owner, including cancellation and key release. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Modifier.tvClickable(onClick: () -> Unit, onLongPress: (() -> Unit)? = null): Modifier {
    if (onLongPress == null) return clickable(onClick = onClick)
    val click by rememberUpdatedState(onClick)
    val menu by rememberUpdatedState(onLongPress)
    var pressedAt by remember { mutableLongStateOf(0L) }
    var pressedKey by remember { mutableIntStateOf(-1) }
    var nativeLong by remember { mutableStateOf(false) }
    return onFocusChanged { if (!it.isFocused) { pressedAt = 0L; pressedKey = -1; nativeLong = false } }
        .onPreviewKeyEvent { event ->
            if (event.key !in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu)) return@onPreviewKeyEvent false
            val code = event.nativeKeyEvent.keyCode
            if (event.type == KeyEventType.KeyDown) {
                if (event.nativeKeyEvent.repeatCount == 0) {
                    pressedAt = android.os.SystemClock.uptimeMillis(); pressedKey = code; nativeLong = false
                }
                if (event.nativeKeyEvent.isLongPress) nativeLong = true
            } else if (event.type == KeyEventType.KeyUp) {
                val held = pressedKey == code && pressedAt > 0L
                val long = nativeLong || android.os.SystemClock.uptimeMillis() - pressedAt >= android.view.ViewConfiguration.getLongPressTimeout()
                pressedAt = 0L; pressedKey = -1; nativeLong = false
                if (held && !event.nativeKeyEvent.isCanceled) {
                    if (event.key == Key.Menu || long) menu() else click()
                }
            }
            true
        }.combinedClickable(onClick = { click() }, onLongClick = { menu() })
}

/* The ring is painted after the content, not as a border before it. A border sits underneath
   whatever background the caller adds next, so a gold ring on a gold primary pill vanished
   entirely. `ring` lets the primary button use warm white: the same light, one step up. */
private const val FOCUS_TRANSITION_MS = 180
private const val CARD_FOCUS_SCALE = 1.05f

@Composable internal fun Modifier.auraFocus(
    shape: RoundedCornerShape = RoundedCornerShape(12.dp), scale: Float = CARD_FOCUS_SCALE, ring: Color? = null,
    instantRing: Boolean = false,
): Modifier {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    val ringColor = ring ?: c.accent
    var focused by remember { mutableStateOf(false) }
    val rapidNavigation = LocalMarqueeSuspended.current
    val listScrolling = LocalListScrolling.current
    val emphasis by animateFloatAsState(if (focused) 1f else 0f, tween(if (motion && !rapidNavigation) FOCUS_TRANSITION_MS else 0, easing = FastOutSlowInEasing), label = "focus light")
    return this.onFocusChanged { focused = it.isFocused }
        .graphicsLayer {
            val zoom = if (rapidNavigation || listScrolling()) 0f else emphasis
            scaleX = 1f + (scale - 1f) * zoom
            scaleY = scaleX
        }
        .drawWithContent {
            val radius = CornerRadius(shape.topStart.toPx(size, this))
            val amount = if (instantRing) { if (focused) 1f else 0f } else emphasis
            if (amount > 0f) drawRoundRect(c.accent.copy(alpha = amount * .09f), cornerRadius = radius)
            drawContent()
            if (amount > 0f) {
                val stroke = 2.dp.toPx()
                val bounds = androidx.compose.ui.geometry.Size(size.width, size.height)
                drawRoundRect(
                    color = ringColor.copy(alpha = amount),
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = androidx.compose.ui.geometry.Size(bounds.width - stroke, bounds.height - stroke),
                    cornerRadius = radius,
                    style = Stroke(stroke),
                )
            }
        }
}

@Composable internal fun Modifier.rowFocus(): Modifier {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    var focused by remember { mutableStateOf(false) }
    val emphasis by animateFloatAsState(if (focused) 1f else 0f, tween(if (motion) FOCUS_TRANSITION_MS else 0, easing = FastOutSlowInEasing), label = "row focus")
    val shape = RoundedCornerShape(10.dp)
    return this.onFocusChanged { focused = it.isFocused }
        .clip(shape)
        .drawWithContent {
            if (emphasis > 0f) drawRoundRect(c.accent.copy(alpha = emphasis * .085f), cornerRadius = CornerRadius(10.dp.toPx()))
            drawContent()
            if (emphasis <= 0f) return@drawWithContent
            /* The bar lives inside the row's left padding so focused text never shifts —
               focusable and read-only rows in one card must stay aligned. */
            val barW = 2.dp.toPx()
            val barH = 18.dp.toPx()
            drawRoundRect(
                color = c.accent.copy(alpha = emphasis),
                topLeft = Offset(3.dp.toPx(), (size.height - barH) / 2f),
                size = androidx.compose.ui.geometry.Size(barW, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f),
            )
            val stroke = 1.dp.toPx()
            drawRoundRect(
                color = c.accent.copy(alpha = emphasis * .34f),
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(shape.topStart.toPx(androidx.compose.ui.geometry.Size(size.width, size.height), this)),
                style = Stroke(stroke),
            )
        }
}
