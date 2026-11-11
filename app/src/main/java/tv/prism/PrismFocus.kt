package tv.prism

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.BeyondBoundsLayout
import androidx.compose.ui.layout.ModifierLocalBeyondBoundsLayout
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.withFrameNanos

/* Long titles scroll only on the focused tile: a shelf of moving text is noise, and the
   focused item is the one being read. Motion-off users get the ellipsized static version. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.focusedMarquee(active: Boolean): Modifier = composed {
    if (active && LocalAuraMotion.current) basicMarquee(iterations = 3, delayMillis = 1200) else this
}

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

fun Modifier.focusAnchor(anchor: FocusAnchor): Modifier = composed {
    DisposableEffect(anchor) {
        anchor.hold()
        onDispose { anchor.release() }
    }
    focusRequester(anchor.requester)
}

fun FocusAnchor?.requestIfAttached(): Boolean {
    val anchor = this ?: return false
    if (!anchor.attached) return false
    return runCatching { anchor.requester.requestFocus() }.isSuccess
}

/** Let the scroll container animate focus visibility, including the painted border. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.keepFocusVisible(): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val margin = with(LocalDensity.current) { 10.dp.toPx() }
    var itemSize by remember { mutableStateOf(IntSize.Zero) }
    this.bringIntoViewRequester(requester).onSizeChanged { itemSize = it }.onFocusChanged { state ->
        scope.coroutineContext.cancelChildren()
        if (state.isFocused) scope.launch {
            withFrameNanos { }
            requester.bringIntoView(Rect(-margin, -margin, itemSize.width + margin, itemSize.height + margin))
        }
    }
}

/** Mount without moving the viewport. Apply the modifier outside any nested horizontal list. */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun rememberVerticalFocusSearch(): Pair<Modifier, (Key, () -> FocusRequester?) -> Boolean> {
    var layout by remember { mutableStateOf<BeyondBoundsLayout?>(null) }
    val modifier = Modifier.modifierLocalConsumer { ModifierLocalBeyondBoundsLayout.current?.let { layout = it } }
    val request: (Key, () -> FocusRequester?) -> Boolean = { direction, target ->
        fun focus() = target()?.let { runCatching { it.requestFocus() }.isSuccess } ?: false
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

/** Reveal a lazy item before retrying; leave composition changes time to attach its target. */
internal suspend fun FocusRequester.requestAfterScroll(scroll: suspend () -> Unit): Boolean {
    if (runCatching { requestFocus() }.isSuccess) return true
    return withTimeoutOrNull(1000) {
        scroll()
        repeat(8) {
            withFrameNanos { }
            if (runCatching { requestFocus() }.isSuccess) return@withTimeoutOrNull true
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

/* A card inside a horizontal LazyRow never asks the page's vertical scroller to reveal it:
   its bringIntoView request is consumed by the row's own scrollable. The row itself carries
   the requester, and a focused card calls reveal() so the page scrolls the whole row into
   view (fully visible border included) — e.g. the last shelf above the mini bar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberRowRevealer(scope: CoroutineScope): Pair<Modifier, () -> Unit> {
    val requester = remember { BringIntoViewRequester() }
    val modifier = Modifier.bringIntoViewRequester(requester)
    val reveal: () -> Unit = { scope.launch { requester.bringIntoView() } }
    return modifier to reveal
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
@Composable internal fun Modifier.auraFocus(shape: RoundedCornerShape = RoundedCornerShape(12.dp), scale: Float = 1.035f, ring: Color? = null): Modifier {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    val ringColor = ring ?: c.accent
    var focused by remember { mutableStateOf(false) }
    val emphasis by animateFloatAsState(if (focused) 1f else 0f, tween(if (motion) 180 else 0), label = "focus light")
    return this.onFocusChanged { focused = it.isFocused }
        .graphicsLayer { scaleX = 1f + (scale - 1f) * emphasis; scaleY = scaleX }
        .background(c.accent.copy(alpha = emphasis * .09f), shape)
        .drawWithContent {
            drawContent()
            if (emphasis > 0f) {
                val stroke = 2.dp.toPx()
                val bounds = androidx.compose.ui.geometry.Size(size.width, size.height)
                drawRoundRect(
                    color = ringColor.copy(alpha = emphasis),
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = androidx.compose.ui.geometry.Size(bounds.width - stroke, bounds.height - stroke),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(shape.topStart.toPx(bounds, this)),
                    style = Stroke(stroke),
                )
            }
        }
}

@Composable internal fun Modifier.rowFocus(): Modifier {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    var focused by remember { mutableStateOf(false) }
    val emphasis by animateFloatAsState(if (focused) 1f else 0f, tween(if (motion) 160 else 0), label = "row focus")
    val shape = RoundedCornerShape(10.dp)
    return this.onFocusChanged { focused = it.isFocused }
        .clip(shape)
        .background(c.accent.copy(alpha = emphasis * .085f), shape)
        .drawWithContent {
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
