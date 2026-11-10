package tv.prism

import android.app.Activity
import android.graphics.drawable.BitmapDrawable
import androidx.palette.graphics.Palette
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.BeyondBoundsLayout
import androidx.compose.ui.layout.ModifierLocalBeyondBoundsLayout
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import coil.request.ImageRequest
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

data class PrismColors(
    val background: Color, val panel: Color, val foreground: Color, val muted: Color,
    val accent: Color, val onAccent: Color, val line: Color, val glow: Color,
)

val LocalPrismColors = staticCompositionLocalOf { prismColors("gold") }
val LocalAuraMotion = staticCompositionLocalOf { true }

private fun artworkScrim(colors: PrismColors): Brush {
    val opacity = when {
        colors.background.luminance() > .5f -> listOf(.94f, .96f, .98f)
        // Compose blur is unavailable before Android 12. Keep unblurred artwork subdued.
        android.os.Build.VERSION.SDK_INT < 31 -> listOf(.82f, .90f, .97f)
        else -> listOf(.58f, .76f, .93f)
    }
    return Brush.verticalGradient(opacity.map { colors.background.copy(alpha = it) })
}

/* Long titles scroll only on the focused tile: a shelf of moving text is noise, and the
   focused item is the one being read. Motion-off users get the ellipsized static version. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.focusedMarquee(active: Boolean): Modifier = composed {
    if (active && LocalAuraMotion.current) basicMarquee(iterations = 3, delayMillis = 1200) else this
}

/* The marker for the track that is actually playing. Equalizer bars rather than a static
   note: the list already has one row highlighted, and this says the highlight is live. */
@Composable
fun NowPlayingBars(color: Color, modifier: Modifier = Modifier, height: Dp = 14.dp, barWidth: Dp = 3.dp, active: Boolean = true) {
    Box(modifier.height(height), contentAlignment = Alignment.BottomStart) {
        val fractions = if (LocalAuraMotion.current && active) {
            val transition = rememberInfiniteTransition(label = "now playing")
            listOf(380, 520, 310).map { duration ->
                transition.animateFloat(
                    initialValue = .25f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(duration, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                    label = "bar",
                )
            }.map { it.value }
        } else listOf(.55f, 1f, .35f)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            fractions.forEach { fraction ->
                Box(Modifier.width(barWidth).height(height * fraction).clip(RoundedCornerShape(1.dp)).background(color))
            }
        }
    }
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

@Composable
private fun coverRequest(url: String, sampleSize: Int? = null): ImageRequest {
    val context = LocalContext.current
    return remember(context, url, sampleSize) {
        ImageRequest.Builder(context).data(url).apply { if (sampleSize != null) size(sampleSize) }.build()
    }
}

fun prismColors(theme: String): PrismColors = when (theme) {
    "porcelain" -> PrismColors(Color(0xFFF4F1EA), Color(0xFFE9E4DA), Color(0xFF252119), Color(0xFF756E60), Color(0xFF8A6626), Color.White, Color(0xFFC9BDAA), Color(0xFFCEB477))
    "mono" -> PrismColors(Color(0xFF0A0A0A), Color(0xFF1A1A1A), Color(0xFFF3F3F3), Color(0xFF9A9A9A), Color(0xFFF3F3F3), Color(0xFF111111), Color(0xFF3B3B3B), Color(0xFF777777))
    else -> PrismColors(Color(0xFF08090B), Color(0xFF151619), Color(0xFFF4F0E8), Color(0xFFAAA69E), Color(0xFFE8C78D), Color(0xFF17120B), Color(0xFF333230), Color(0xFFBD863C))
}

@Composable fun AuraMark(modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    Canvas(modifier) {
        val s = size.minDimension
        val origin = center
        drawCircle(Brush.radialGradient(listOf(Color.Transparent, c.glow.copy(alpha = .34f), Color.Transparent), center = origin, radius = s * .5f), radius = s * .5f)
        drawCircle(Brush.sweepGradient(listOf(c.accent.copy(alpha = .04f), c.accent.copy(alpha = .8f), c.glow.copy(alpha = .12f), c.accent.copy(alpha = .04f))), radius = s * .34f, style = Stroke(s * .014f))
        val prism = Path().apply {
            moveTo(s * .5f, s * .29f); lineTo(s * .31f, s * .66f)
            lineTo(s * .63f, s * .66f); moveTo(s * .69f, s * .66f); lineTo(s * .5f, s * .29f)
        }
        drawPath(prism, Brush.linearGradient(listOf(c.foreground, c.accent, c.glow)), style = Stroke(s * .018f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(c.accent.copy(alpha = .55f), Offset(s * .60f, s * .57f), Offset(s * .83f, s * .72f), s * .009f, StrokeCap.Round)
    }
}

@Composable fun AuraBrand(modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = LocalPrismColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        AuraMark(Modifier.size(if (compact) 34.dp else 42.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text("PrismAura", color = c.foreground, fontSize = if (compact) 16.sp else 20.sp, fontWeight = FontWeight.Medium, letterSpacing = .3.sp)
            if (!compact) Text("极 光 音 乐", color = c.muted, fontSize = 10.sp, letterSpacing = 2.sp)
        }
    }
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

@Composable fun PlatformIcon(platform: MusicPlatform, modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(when (platform) {
        MusicPlatform.NETEASE -> Color(0xFFD43C33)
        MusicPlatform.BILIBILI -> Color(0xFF00A1D6)
        else -> c.panel
    }).semantics { contentDescription = platform.label }, contentAlignment = Alignment.Center) {
        if (platform.icon != null) Image(painterResource(platform.icon), null, Modifier.fillMaxSize(when (platform) {
            MusicPlatform.NETEASE, MusicPlatform.BILIBILI -> .72f
            MusicPlatform.SPOTIFY, MusicPlatform.YOUTUBE -> .86f
            else -> 1f
        }))
        else Canvas(Modifier.fillMaxSize(.62f)) {
            val stroke = 2.dp.toPx()
            if (platform == MusicPlatform.WEBDAV) {
                drawCircle(c.accent, radius = size.minDimension * .22f, center = Offset(size.width * .42f, size.height * .43f), style = Stroke(stroke))
                drawLine(c.accent, Offset(size.width * .16f, size.height * .72f), Offset(size.width * .86f, size.height * .72f), stroke)
                drawLine(c.accent, Offset(size.width * .67f, size.height * .43f), Offset(size.width * .86f, size.height * .72f), stroke)
            } else if (platform == MusicPlatform.OTHER) {
                drawLine(c.accent, Offset(size.width * .62f, size.height * .16f), Offset(size.width * .62f, size.height * .68f), stroke, StrokeCap.Round)
                drawLine(c.accent, Offset(size.width * .62f, size.height * .16f), Offset(size.width * .86f, size.height * .10f), stroke, StrokeCap.Round)
                drawCircle(c.accent, radius = size.minDimension * .16f, center = Offset(size.width * .46f, size.height * .72f))
            } else {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * .5f, 0f); lineTo(size.width, size.height * .5f)
                    lineTo(size.width * .5f, size.height); lineTo(0f, size.height * .5f); close()
                }
                drawPath(path, c.accent, style = Stroke(stroke))
            }
        }
    }
}

@Composable fun PlatformAction(platform: MusicPlatform, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalPrismColors.current
    val currentClick by rememberUpdatedState(onClick)
    Button(onClick = onClick, modifier = modifier.pointerInput(Unit) { detectTapGestures { currentClick() } }, colors = ButtonDefaults.colors(containerColor = c.panel, contentColor = c.foreground,
        focusedContainerColor = c.accent, focusedContentColor = c.onAccent), shape = ButtonDefaults.shape(RoundedCornerShape(50))) {
        PlatformIcon(platform, Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.sp, maxLines = 1)
    }
}

/* The room is dark and there is one light. It rakes in from the top-left corner, so every
   surface is lit along the same axis — near edge bright, far edge gone — and the floor below
   falls back into black. A flat fill reads as decoration; a directional falloff reads as
   lighting, which is the whole premise of 一道光照进房间. */
@Composable fun PrismBackground(content: @Composable BoxScope.() -> Unit) {
    val c = LocalPrismColors.current
    Box(Modifier.fillMaxSize().background(c.background).drawBehind {
        drawRect(Brush.linearGradient(
            0f to c.glow.copy(alpha = .14f), .34f to c.glow.copy(alpha = .045f), 1f to Color.Transparent,
            start = Offset.Zero, end = Offset(size.width * .78f, size.height)))
        drawRect(Brush.radialGradient(listOf(c.glow.copy(alpha = .075f), Color.Transparent),
            center = Offset(size.width * .76f, size.height * .2f), radius = size.width * .52f))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, c.background.copy(alpha = .6f)),
            startY = size.height * .52f, endY = size.height))
    }, content = content)
}

@Composable fun AuraSourcePill(count: Int, modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (count > 0) c.accent else c.muted.copy(alpha = .45f)))
        Spacer(Modifier.width(7.dp))
        Text(if (count > 0) "$count 个音源已启用" else "未接入音源", color = c.muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable fun AuraClock(modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now)),
        modifier = modifier, color = c.foreground.copy(alpha = .72f), fontSize = 15.sp, letterSpacing = 1.5.sp, maxLines = 1)
}

/* An irregular comb of light, not a music visualiser: it stands for the strand the station was
   folded from, so the heights are fixed rather than sampled — nothing here is pretending to
   track audio. */
@Composable fun AuraSpectrum(modifier: Modifier = Modifier, active: Boolean = true) {
    val c = LocalPrismColors.current
    Canvas(modifier) {
        val bars = 26
        val step = size.width / bars
        val barWidth = step * .52f
        for (i in 0 until bars) {
            val n = ((i * 37 + 11) % 17) / 16f
            val h = size.height * (.16f + .84f * n)
            val alpha = if (active) .28f + .5f * (h / size.height) else .1f
            drawRoundRect(
                color = c.accent.copy(alpha = alpha),
                topLeft = Offset(i * step, size.height - h),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
            )
        }
    }
}

/** One home entry for the same continuous FM session used by discovery and the player. */
@Composable fun AuraRadioCard(active: Boolean, loading: Boolean, status: String, onPlay: () -> Unit,
                             modifier: Modifier = Modifier, onLongPress: (() -> Unit)? = null) {
    val c = LocalPrismColors.current
    Column(modifier.auraFocus().clip(RoundedCornerShape(12.dp))
        .background(Brush.linearGradient(listOf(c.panel.copy(alpha = .95f), c.background.copy(alpha = .5f))))
        .border(1.dp, c.line.copy(alpha = .7f), RoundedCornerShape(12.dp))
        .tvClickable(onPlay, onLongPress).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Glyph("radio", Modifier.size(18.dp), c.accent)
            Spacer(Modifier.width(7.dp))
            Text("极光电台", color = c.foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
            Text(if (loading) "准备中…" else if (active) "继续收听" else "确定开启", color = c.accent, fontSize = 11.sp, maxLines = 1)
        }
        Spacer(Modifier.height(2.dp))
        Text(status.ifBlank { "熟悉的旋律，穿插一些新发现" },
            color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        AuraSpectrum(Modifier.fillMaxWidth().height(18.dp), active = active || loading)
    }
}

/* 平台热榜 — the first loaded chart, three lines of it. The whole card is one focus target: four
   nested focusables inside a 90dp card would cost more navigation than the list is worth.
   A chart whose preview failed still opens: the detail page fetches it on demand, and a card
   that refuses focus is a card the remote cannot reach. */
@Composable fun AuraHotCard(title: String?, songs: List<Song>, onOpen: () -> Unit, modifier: Modifier = Modifier, onLongPress: (() -> Unit)? = null, status: String = "") {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    Column(modifier.auraFocus().onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(12.dp))
        .background(c.panel.copy(alpha = .92f))
        .border(1.dp, c.line.copy(alpha = .7f), RoundedCornerShape(12.dp))
        .tvClickable(onOpen, onLongPress).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text("平台热榜", color = c.foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(title?.ifBlank { null } ?: status.ifBlank { "选择一个音源后显示" }, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        if (songs.isEmpty()) {
            /* A chart with no preview rows is a normal answer, not an error: some sources list
               the chart but serve it only on demand. Say what OK will do instead of leaving a
               blank card. */
            Text(if (title.isNullOrBlank()) "连接音源后显示每日热榜" else "按确定打开完整榜单",
                color = c.muted.copy(alpha = .8f), fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // The compact rail is 88dp high: heading + subtitle leave room for one readable row.
        // The card opens the complete chart; a second row was being clipped at the bottom.
        } else songs.take(1).forEachIndexed { index, song ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("%02d".format(index + 1), color = c.accent.copy(alpha = .8f), fontSize = 9.sp, modifier = Modifier.width(22.dp))
                Text(song.title, color = c.foreground.copy(alpha = .86f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).focusedMarquee(focused))
                Spacer(Modifier.width(8.dp))
                Text(song.sourceLabel, color = c.muted.copy(alpha = .85f), fontSize = 9.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 72.dp))
            }
        }
    }
}

@Composable fun PrismAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, live: Boolean = false) {
    val c = LocalPrismColors.current
    Box(modifier.heightIn(min = 42.dp).auraFocus(RoundedCornerShape(50), ring = if (primary) c.foreground else null)
        .clip(RoundedCornerShape(50)).background(if (primary) c.accent else c.panel)
        .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        if (live) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NowPlayingBars(if (primary) c.onAccent else c.accent, height = 13.dp, barWidth = 2.5.dp)
            Text(label, color = if (primary) c.onAccent else c.foreground, fontSize = 14.sp, fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
        } else Text(label, color = if (primary) c.onAccent else c.foreground, fontSize = 14.sp, fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
    }
}

/* The film mark is what separates "watch the MV" from a quality or source pill that happens to
   read "MV". Text alone was read as a badge, not an action. */
@Composable fun MvAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    /* Two letters, no capsule. Next to the round transport icons a framed pill read as a
       section tab; the focus ring is enough. */
    Box(modifier.size(40.dp).auraFocus(RoundedCornerShape(50), 1.08f)
        .onFocusChanged { focused = it.isFocused }
        .clip(RoundedCornerShape(50))
        .background(if (focused) c.accent.copy(alpha = .12f) else Color.Transparent)
        .semantics { contentDescription = "MV" }
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text("MV", color = if (focused) c.accent else c.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = .8.sp)
    }
}

/* A filter is not an action. It keeps footnote weight until it is open, otherwise it competes
   with the primary button sitting next to it. */
@Composable fun PrismLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    val c = LocalPrismColors.current
    Box(modifier.heightIn(min = 28.dp).auraFocus(RoundedCornerShape(6.dp), 1.01f).clip(RoundedCornerShape(6.dp))
        .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (active) c.accent else c.muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable fun PrismNav(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, dense: Boolean = false) {
    val c = LocalPrismColors.current
    Column(modifier.widthIn(min = 64.dp).auraFocus(RoundedCornerShape(50), 1.02f)
        .clip(RoundedCornerShape(50)).clickable(onClick = onClick)
        .padding(horizontal = if (dense) 14.dp else 18.dp, vertical = if (dense) 6.dp else 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = if (selected) c.foreground else c.muted, fontSize = if (dense) 14.sp else 15.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
        Spacer(Modifier.height(if (dense) 4.dp else 5.dp))
        Box(Modifier.width(14.dp).height(2.dp).background(if (selected) c.accent else Color.Transparent, CircleShape))
    }
}

@Composable fun PrismArtwork(song: Song?, modifier: Modifier = Modifier, record: Boolean = false) {
    val c = LocalPrismColors.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension * .48f
            drawCircle(Brush.radialGradient(listOf(c.glow.copy(alpha = .48f), Color.Transparent), radius = radius), radius = radius)
            val disk = radius * .78f
            drawCircle(Brush.radialGradient(listOf(lerp(c.panel, c.accent, .24f), c.background, Color.Black), radius = disk), radius = disk)
            if (record) {
                for (i in 1..18) drawCircle(c.line.copy(alpha = .20f), radius = disk * (.35f + i * .035f), style = Stroke(1f))
                for (i in 0 until 48) {
                    val angle = i * Math.PI * 2 / 48
                    val a = disk * 1.10f; val b = disk * if (i % 4 == 0) 1.15f else 1.12f
                    drawLine(c.muted.copy(alpha = .45f), Offset(center.x + cos(angle).toFloat() * a, center.y + sin(angle).toFloat() * a), Offset(center.x + cos(angle).toFloat() * b, center.y + sin(angle).toFloat() * b), 1.5f)
                }
            }
        }
        val url = song?.artwork.orEmpty()
        if (url.startsWith("http://") || url.startsWith("https://")) {
            AsyncImage(model = coverRequest(url), contentDescription = "${song?.title}封面", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(if (record) .46f else .75f).clip(if (record) CircleShape else RoundedCornerShape(16.dp)))
        } else {
            Canvas(Modifier.fillMaxSize(.32f)) { drawCircle(c.background); drawCircle(c.accent.copy(alpha = .55f), radius = size.minDimension * .07f) }
        }
    }
}

/* A pay hint from the source, not a lock: it says why a track may refuse to resolve without
   the listener's own Cookie. Sources that do not expose a pay flag show nothing. */
@Composable
private fun VipBadge(modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(c.accent.copy(alpha = .16f))
        .border(1.dp, c.accent.copy(alpha = .32f), RoundedCornerShape(4.dp))
        .padding(horizontal = 4.dp, vertical = 1.dp)) {
        Text("VIP", color = c.accent, fontSize = 8.sp, letterSpacing = .6.sp, maxLines = 1)
    }
}

@Composable fun PrismSongRow(song: Song, index: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().height(52.dp).onFocusChanged { focused = it.isFocused }
        .clip(RoundedCornerShape(8.dp)).background(if (focused) c.accent.copy(alpha = .17f) else Color.Transparent)
        .drawBehind { if (focused) drawRect(c.accent, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
        .clickable(onClick = onClick).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("%02d".format(index + 1), color = c.muted, fontSize = 12.sp, modifier = Modifier.width(35.dp))
        PrismArtwork(song, Modifier.size(42.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(song.title, color = c.foreground, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).focusedMarquee(focused))
                if (song.isVip) { Spacer(Modifier.width(6.dp)); VipBadge() }
            }
            Text(song.artist, color = c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PlatformIcon(song.platform, Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(song.album, color = c.muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.width(130.dp), overflow = TextOverflow.Ellipsis)
    }
}

private fun time(ms: Long): String = "%02d:%02d".format(ms / 60_000, (ms / 1000) % 60)

@Composable
private fun rememberLyricPosition(position: Long, playing: Boolean, readPosition: () -> Long): State<Long> {
    val readCurrent by rememberUpdatedState(readPosition)
    return produceState(initialValue = position, position, playing) {
        value = readCurrent()
        while (playing) withFrameNanos { value = readCurrent() }
    }
}

/* 氛围取色：每个封面只分析一次，结果按 URL 缓存；同一封面的并发请求（播放页与全屏
   歌词可能同时在等）共享同一个提取任务。逐帧只画渐变，不解码图片。 */
private object AmbienceCache {
    private val cache = object : LinkedHashMap<String, List<Int>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Int>>?) = size > 24
    }
    private val pending = HashMap<String, Deferred<List<Int>>>()
    /* 提取挂在独立作用域：一个页面离场不能取消另一个页面还在等的任务。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Synchronized fun lookup(url: String): List<Int>? = cache[url]
    @Synchronized private fun store(url: String, colors: List<Int>) { cache[url] = colors }
    suspend fun resolve(url: String, extract: suspend () -> List<Int>): List<Int> {
        lookup(url)?.let { return it }
        val job = synchronized(this) {
            cache[url]?.let { return it }
            pending.getOrPut(url) {
                scope.async(start = CoroutineStart.LAZY) { extract().also { if (it.isNotEmpty()) store(url, it) } }
            }.also { task ->
                task.invokeOnCompletion { synchronized(this@AmbienceCache) {
                    if (pending[url] === task) pending.remove(url)
                } }
            }
        }
        job.start()
        return job.await()
    }
}

@Composable
private fun rememberAmbience(song: Song?): List<Int> {
    val context = LocalContext.current.applicationContext
    val artwork = song?.artwork.orEmpty()
    return produceState(initialValue = AmbienceCache.lookup(artwork).orEmpty(), artwork) {
        if (!artwork.startsWith("http")) return@produceState
        value = AmbienceCache.resolve(artwork) {
            runCatching {
                val request = ImageRequest.Builder(context).data(artwork).size(96).allowHardware(false).build()
                val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                if (bitmap == null) emptyList() else {
                    val palette = Palette.from(bitmap).generate()
                    listOfNotNull(
                        palette.vibrantSwatch?.rgb, palette.mutedSwatch?.rgb, palette.darkMutedSwatch?.rgb,
                    ).ifEmpty { listOfNotNull(palette.dominantSwatch?.rgb) }
                }
            }.getOrDefault(emptyList())
        }
    }.value
}

/* 唱片的颜色漫进房间：一层极淡的径向光晕，缓慢移动。歌词永远是前景，光晕只在最暗处
   呼吸。减少动效时静止；页面离场（含息屏全黑）时整个合成树不再绘制。 */
@Composable
private fun AuraAmbience(song: Song?, modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    val raw = rememberAmbience(song)
    /* 换歌时新封面还在分析，旧光晕多留一会儿，避免颜色突然消失——但只对同一张封面。
       换到没有封面（或取色失败）的歌还留着上一首的颜色，等于把别人的唱片光打在这张
       脸上，比暂时没有光晕更错。 */
    val artwork = song?.artwork.orEmpty()
    var previous by remember { mutableStateOf("" to emptyList<Color>()) }
    LaunchedEffect(artwork, raw) {
        when {
            raw.isNotEmpty() -> previous = artwork to raw.map { Color(it) }
            previous.first != artwork -> previous = "" to emptyList()
        }
    }
    val stops = if (raw.isNotEmpty()) raw.map { Color(it) }
        else previous.second.takeIf { previous.first == artwork }.orEmpty()
    if (stops.isEmpty()) return
    val a0 by animateColorAsState(stops.getOrElse(0) { c.accent }, tween(if (motion) 1600 else 0), label = "ambience a0")
    val a1 by animateColorAsState(stops.getOrElse(1) { stops[0] }, tween(if (motion) 1600 else 0), label = "ambience a1")
    val a2 by animateColorAsState(stops.getOrElse(2) { stops[0] }, tween(if (motion) 1600 else 0), label = "ambience a2")
    /* 减少动效时不创建无限动画：值不被读取也会每帧请求刷新。相位留到绘制期读取，只重绘不重组。 */
    val phase = if (motion) rememberInfiniteTransition(label = "ambience drift").animateFloat(
        0f, 1f, infiniteRepeatable(tween(44_000, easing = LinearEasing)), label = "ambience phase",
    ) else null
    Canvas(modifier) {
        val angle = (phase?.value ?: 0f) * 2f * PI.toFloat()
        val center = Offset(size.width * (.30f + .035f * cos(angle)), size.height * (.42f + .035f * sin(angle)))
        val radius = size.maxDimension * .8f
        drawRect(Brush.radialGradient(
            listOf(a0.copy(alpha = .26f), a1.copy(alpha = .15f), a2.copy(alpha = .08f), Color.Transparent),
            center, radius,
        ))
    }
}

/* ─────────────────────────────────────────────────────────────────────────────
   Player.

   Four things: the cover, the title, the words, the thread. Everything else is
   gone — the status line, the quality label, the queue counter, the row of eight
   text buttons. On a TV this is read from three metres, and at three metres
   every extra label is noise.

   Gold appears exactly three times: the eyebrow mark, the artist line, the play
   button. One gold object reads as expensive; ten read as a theme.

   The icons are drawn, not imported. Material's glyphs are filled and heavy, and
   this screen is hairlines on black — the line weight IS the tone.
   ───────────────────────────────────────────────────────────────────────────── */

@Composable
internal fun Glyph(kind: String, modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        val w = 1.5.dp.toPx()
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun path(block: Path.() -> Unit) = Path().apply(block)
        when (kind) {
            "back" -> drawPath(path { moveTo(s * .60f, s * .22f); lineTo(s * .38f, s * .5f); lineTo(s * .60f, s * .78f) },
                color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            "play" -> drawPath(path { moveTo(s * .34f, s * .18f); lineTo(s * .82f, s * .5f); lineTo(s * .34f, s * .82f); close() }, color)
            "pause" -> {
                drawRoundRect(color, topLeft = p(.33f, .20f), size = Size(s * .11f, s * .60f), cornerRadius = CornerRadius(s * .02f))
                drawRoundRect(color, topLeft = p(.56f, .20f), size = Size(s * .11f, s * .60f), cornerRadius = CornerRadius(s * .02f))
            }
            "prev" -> {
                drawRoundRect(color, topLeft = p(.22f, .20f), size = Size(s * .07f, s * .60f), cornerRadius = CornerRadius(s * .02f))
                drawPath(path { moveTo(s * .80f, s * .20f); lineTo(s * .36f, s * .5f); lineTo(s * .80f, s * .80f); close() }, color)
            }
            "next" -> {
                drawRoundRect(color, topLeft = p(.71f, .20f), size = Size(s * .07f, s * .60f), cornerRadius = CornerRadius(s * .02f))
                drawPath(path { moveTo(s * .20f, s * .20f); lineTo(s * .64f, s * .5f); lineTo(s * .20f, s * .80f); close() }, color)
            }
            "heart", "heartFilled" -> {
                val h = path {
                    moveTo(s * .5f, s * .80f)
                    cubicTo(s * .12f, s * .58f, s * .16f, s * .20f, s * .5f, s * .34f)
                    cubicTo(s * .84f, s * .20f, s * .88f, s * .58f, s * .5f, s * .80f)
                    close()
                }
                if (kind == "heartFilled") drawPath(h, color)
                else drawPath(h, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            "aurora" -> {
                val upper = path {
                    moveTo(s * .08f, s * .60f)
                    cubicTo(s * .30f, s * .16f, s * .46f, s * .86f, s * .92f, s * .36f)
                }
                val lower = path {
                    moveTo(s * .08f, s * .78f)
                    cubicTo(s * .30f, s * .34f, s * .46f, s * .98f, s * .92f, s * .54f)
                }
                drawPath(upper, color, style = Stroke(w, cap = StrokeCap.Round))
                drawPath(lower, color.copy(alpha = .45f), style = Stroke(w, cap = StrokeCap.Round))
            }
            "radio" -> {
                drawLine(color, p(.27f, .26f), p(.72f, .10f), w, StrokeCap.Round)
                drawRoundRect(color, p(.15f, .30f), Size(s * .70f, s * .53f), CornerRadius(s * .08f), style = Stroke(w))
                drawCircle(color, s * .12f, p(.36f, .57f), style = Stroke(w))
                drawLine(color, p(.60f, .49f), p(.73f, .49f), w, StrokeCap.Round)
                drawLine(color, p(.60f, .65f), p(.73f, .65f), w, StrokeCap.Round)
            }
            "keyboard" -> {
                drawRoundRect(color, p(.08f, .24f), Size(s * .84f, s * .52f), CornerRadius(s * .05f), style = Stroke(w))
                for (x in listOf(.25f, .42f, .59f, .76f)) for (y in listOf(.39f, .53f))
                    drawCircle(color, s * .025f, p(x, y))
                drawLine(color, p(.34f, .66f), p(.66f, .66f), w, StrokeCap.Round)
            }
            "list" -> for (y in listOf(.28f, .5f, .72f)) drawLine(color, p(.22f, y), p(.78f, y), w, StrokeCap.Round)
            "add" -> {
                drawLine(color, p(.5f, .2f), p(.5f, .8f), w, StrokeCap.Round)
                drawLine(color, p(.2f, .5f), p(.8f, .5f), w, StrokeCap.Round)
            }
            "shuffle" -> {
                drawLine(color, p(.15f, .30f), p(.32f, .30f), w, StrokeCap.Round)
                drawLine(color, p(.32f, .30f), p(.68f, .70f), w, StrokeCap.Round)
                drawLine(color, p(.68f, .70f), p(.85f, .70f), w, StrokeCap.Round)
                drawLine(color, p(.15f, .70f), p(.32f, .70f), w, StrokeCap.Round)
                drawLine(color, p(.32f, .70f), p(.68f, .30f), w, StrokeCap.Round)
                drawLine(color, p(.68f, .30f), p(.85f, .30f), w, StrokeCap.Round)
                drawLine(color, p(.74f, .20f), p(.85f, .30f), w, StrokeCap.Round)
                drawLine(color, p(.85f, .30f), p(.74f, .40f), w, StrokeCap.Round)
                drawLine(color, p(.74f, .60f), p(.85f, .70f), w, StrokeCap.Round)
                drawLine(color, p(.85f, .70f), p(.74f, .80f), w, StrokeCap.Round)
            }
            "repeat", "repeatOne" -> {
                drawLine(color, p(.2f, .32f), p(.8f, .32f), w, StrokeCap.Round)
                drawLine(color, p(.65f, .17f), p(.8f, .32f), w, StrokeCap.Round)
                drawLine(color, p(.8f, .32f), p(.65f, .47f), w, StrokeCap.Round)
                drawLine(color, p(.8f, .68f), p(.2f, .68f), w, StrokeCap.Round)
                drawLine(color, p(.35f, .53f), p(.2f, .68f), w, StrokeCap.Round)
                drawLine(color, p(.2f, .68f), p(.35f, .83f), w, StrokeCap.Round)
                if (kind == "repeatOne") drawLine(color, p(.5f, .42f), p(.5f, .58f), w * 1.5f, StrokeCap.Round)
            }
            "diamond" -> drawPath(path {
                moveTo(s * .5f, s * .10f); lineTo(s * .90f, s * .5f); lineTo(s * .5f, s * .90f); lineTo(s * .10f, s * .5f); close()
            }, color, style = Stroke(w * .8f, join = StrokeJoin.Round))
            "search" -> {
                drawCircle(color, radius = s * .27f, center = p(.44f, .44f), style = Stroke(w))
                drawLine(color, p(.64f, .64f), p(.84f, .84f), w, StrokeCap.Round)
            }
            "history" -> {
                drawCircle(color, radius = s * .30f, center = p(.5f, .52f), style = Stroke(w))
                drawLine(color, p(.5f, .52f), p(.5f, .34f), w, StrokeCap.Round)
                drawLine(color, p(.5f, .52f), p(.66f, .60f), w, StrokeCap.Round)
            }
            "undo" -> {
                drawArc(color, startAngle = -90f, sweepAngle = -300f, useCenter = false,
                    topLeft = p(.24f, .26f), size = Size(s * .52f, s * .52f), style = Stroke(w, cap = StrokeCap.Round))
                val tip = p(.5f, .26f)
                drawLine(color, tip, Offset(tip.x + s * .13f, tip.y - s * .06f), w, StrokeCap.Round)
                drawLine(color, tip, Offset(tip.x + s * .11f, tip.y + s * .10f), w, StrokeCap.Round)
            }
            "qr" -> {
                for (corner in listOf(p(.18f, .18f), p(.56f, .18f), p(.18f, .56f))) {
                    drawRoundRect(color, topLeft = corner, size = Size(s * .26f, s * .26f),
                        cornerRadius = CornerRadius(s * .04f), style = Stroke(w * .9f))
                }
                drawRoundRect(color, topLeft = p(.62f, .62f), size = Size(s * .08f, s * .08f))
                drawRoundRect(color, topLeft = p(.76f, .62f), size = Size(s * .08f, s * .08f))
                drawRoundRect(color, topLeft = p(.62f, .76f), size = Size(s * .08f, s * .08f))
            }
            "mv" -> {
                drawRoundRect(color, topLeft = p(.14f, .24f), size = Size(s * .72f, s * .52f),
                    cornerRadius = CornerRadius(s * .06f), style = Stroke(w))
                drawPath(path { moveTo(s * .42f, s * .38f); lineTo(s * .66f, s * .5f); lineTo(s * .42f, s * .62f); close() }, color)
            }
            "artist" -> {
                drawCircle(color, radius = s * .14f, center = p(.5f, .34f), style = Stroke(w))
                drawPath(path {
                    moveTo(s * .22f, s * .80f)
                    cubicTo(s * .24f, s * .58f, s * .38f, s * .53f, s * .5f, s * .53f)
                    cubicTo(s * .62f, s * .53f, s * .76f, s * .58f, s * .78f, s * .80f)
                }, color, style = Stroke(w, cap = StrokeCap.Round))
            }
            /* A microphone reads as "the singer" faster than a bust at 22dp. */
            "mic" -> {
                drawRoundRect(color, topLeft = p(.385f, .12f), size = Size(s * .23f, s * .36f),
                    cornerRadius = CornerRadius(s * .115f), style = Stroke(w))
                drawArc(color, startAngle = 0f, sweepAngle = 180f, useCenter = false,
                    topLeft = p(.30f, .30f), size = Size(s * .40f, s * .34f), style = Stroke(w, cap = StrokeCap.Round))
                drawLine(color, p(.5f, .64f), p(.5f, .80f), w, StrokeCap.Round)
                drawLine(color, p(.34f, .82f), p(.66f, .82f), w, StrokeCap.Round)
            }
            "album" -> {
                drawCircle(color, radius = s * .30f, center = p(.5f, .5f), style = Stroke(w))
                drawCircle(color, radius = s * .20f, center = p(.5f, .5f), style = Stroke(w * .7f))
                drawCircle(color, radius = s * .075f, center = p(.5f, .5f))
            }
            "trash" -> {
                drawLine(color, p(.2f, .30f), p(.8f, .30f), w, StrokeCap.Round)
                drawLine(color, p(.4f, .22f), p(.6f, .22f), w, StrokeCap.Round)
                drawPath(path {
                    moveTo(s * .3f, s * .3f); lineTo(s * .34f, s * .82f); lineTo(s * .66f, s * .82f); lineTo(s * .7f, s * .3f)
                }, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawLine(color, p(.44f, .42f), p(.45f, .7f), w * .8f, StrokeCap.Round)
                drawLine(color, p(.56f, .42f), p(.55f, .7f), w * .8f, StrokeCap.Round)
            }
            "close" -> {
                drawLine(color, p(.28f, .28f), p(.72f, .72f), w, StrokeCap.Round)
                drawLine(color, p(.72f, .28f), p(.28f, .72f), w, StrokeCap.Round)
            }
            /* One stroke, no frame: the selection control already draws the box around it. */
            "check" -> drawPath(path {
                moveTo(s * .26f, s * .53f); lineTo(s * .43f, s * .71f); lineTo(s * .75f, s * .29f)
            }, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            /* Four corner brackets: "give this the whole screen". */
            "expand" -> {
                fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                    drawLine(color, p(x, y), p(x + dx, y), w, StrokeCap.Round)
                    drawLine(color, p(x, y), p(x, y + dy), w, StrokeCap.Round)
                }
                corner(.18f, .18f, .16f, .16f)
                corner(.82f, .18f, -.16f, .16f)
                corner(.18f, .82f, .16f, -.16f)
                corner(.82f, .82f, -.16f, -.16f)
            }
            "bulbOff" -> {
                drawPath(path {
                    moveTo(s * .34f, s * .62f)
                    cubicTo(s * .34f, s * .51f, s * .22f, s * .47f, s * .22f, s * .34f)
                    cubicTo(s * .22f, s * .19f, s * .35f, s * .10f, s * .50f, s * .10f)
                    cubicTo(s * .65f, s * .10f, s * .78f, s * .19f, s * .78f, s * .34f)
                    cubicTo(s * .78f, s * .47f, s * .66f, s * .51f, s * .66f, s * .62f)
                }, color, style = Stroke(w, cap = StrokeCap.Round))
                drawLine(color, p(.34f, .72f), p(.66f, .72f), w, StrokeCap.Round)
                drawLine(color, p(.40f, .84f), p(.60f, .84f), w, StrokeCap.Round)
                drawLine(color, p(.14f, .14f), p(.86f, .86f), w, StrokeCap.Round)
            }
            /* A sung note over two text lines: the word being sung, not just text. */
            "lyric" -> {
                drawCircle(color, radius = s * .10f, center = p(.30f, .28f), style = Stroke(w))
                drawLine(color, p(.38f, .26f), p(.38f, .46f), w, StrokeCap.Round)
                drawLine(color, p(.38f, .46f), p(.55f, .41f), w, StrokeCap.Round)
                drawLine(color, p(.58f, .63f), p(.84f, .63f), w, StrokeCap.Round)
                drawLine(color, p(.50f, .82f), p(.84f, .82f), w, StrokeCap.Round)
            }
        }
    }
}

/* An icon is only legible when it is the only thing in the row; that is the point of replacing
   搜索 and 手机输入 with a magnifier and a QR mark. The ring carries focus so the glyph itself
   never has to change colour. */
@Composable fun AuraIconButton(icon: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                               primary: Boolean = false, size: Dp = 38.dp) {
    val c = LocalPrismColors.current
    Box(modifier.size(size).auraFocus(CircleShape, 1.06f, ring = if (primary) c.foreground else null)
        .clip(CircleShape).background(if (primary) c.accent else c.panel)
        .clickable(onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Glyph(icon, Modifier.size(size * .46f), if (primary) c.onAccent else c.foreground.copy(alpha = .82f))
    }
}

/** A labelled icon tile for a dialog row: one glyph, one short label. */
@Composable fun PrismIconAction(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.width(112.dp).height(94.dp)
            .auraFocus(shape, 1.045f)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) c.accent.copy(alpha = .17f) else c.background.copy(alpha = .5f))
            .border(1.dp, if (focused) c.accent else c.line.copy(alpha = .6f), shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape)
            .background(if (active) c.accent else c.foreground.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
            Glyph(icon, Modifier.size(22.dp), if (active) c.onAccent else c.foreground.copy(alpha = .88f))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = if (focused || active) c.accent else c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A glyph with no chrome at all. The focus ring is a 1dp circle, not a filled pill. */
@Composable
private fun BareButton(icon: String, size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.size(size + 12.dp).onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(if (focused) Color.White.copy(alpha = .05f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(icon, Modifier.size(size * .52f), if (focused) c.foreground else c.foreground.copy(alpha = .45f))
    }
}

/**
 * The record. Label carries the artwork when there is one; otherwise a gold plate
 * with the initial. A hairline sits just outside the disc — that offset line is the
 * whole difference between "a circle" and "a record on a plinth".
 */
@Composable private fun AuraOrbit(modifier: Modifier = Modifier, active: Boolean = true) {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active, motion) {
        if (active && motion) {
            var last = withFrameNanos { it }
            while (true) withFrameNanos { now ->
                phase.floatValue = (phase.floatValue + (now - last) / 1_000_000_000f * 4f) % 360f
                last = now
            }
        }
    }
    Canvas(modifier) {
        val r = size.minDimension * .49f
        drawCircle(Brush.radialGradient(listOf(Color.Transparent, c.glow.copy(alpha = .18f), Color.Transparent), center = center, radius = r), radius = r)
        rotate(phase.floatValue) {
            val light = Brush.sweepGradient(listOf(c.accent.copy(alpha = .02f), c.accent.copy(alpha = .65f), c.glow.copy(alpha = .12f), Color.Transparent, c.accent.copy(alpha = .02f)), center)
            drawCircle(light, radius = r * .87f, style = Stroke(1.dp.toPx()))
            drawCircle(c.accent.copy(alpha = .08f), radius = r * .96f, style = Stroke(.5.dp.toPx()))
        }
    }
}

/** 歌词字号三档：播放页与全屏歌词共用档位，各自保留基础字号。sp 值。 */
private data class PlayerLyricSizes(
    val prevSp: Int, val currentSp: Int, val currentLineSp: Int, val supportSp: Int,
    val fullCurrentSp: Int, val fullLineSp: Int, val fullNearSp: Int, val fullFarSp: Int, val fullSupportSp: Int,
)

private fun lyricSizesFor(level: String): PlayerLyricSizes = when (level) {
    "小" -> PlayerLyricSizes(12, 22, 30, 11, 30, 38, 18, 15, 13)
    "大" -> PlayerLyricSizes(16, 30, 40, 13, 42, 54, 22, 19, 17)
    else -> PlayerLyricSizes(14, 26, 35, 12, 36, 46, 20, 17, 15)
}

@Composable fun PrismPlayer(
    song: Song?, lyrics: List<LyricLine>, lyricStatus: String, position: Long, duration: Long,
    playing: Boolean, playMode: String, favorite: Boolean, quality: String, playingQuality: String,
    queueIndex: Int, queueSize: Int, status: String,
    streamFormat: String,
    hasMv: Boolean,
    mvOpen: Boolean = false,
    readPosition: () -> Long,
    onScreenOff: () -> Unit,
    onBack: () -> Unit, onPlay: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onShuffle: () -> Unit, onFavorite: () -> Unit, onQueue: () -> Unit, onMv: () -> Unit,
    onRadioMenu: () -> Unit = {},
    onRadioToggle: () -> Unit = {},
    radioActive: Boolean = false,
    radioLoading: Boolean = false,
    onSeek: (Long) -> Unit,
    onArtist: (() -> Unit)? = null,
    onAlbum: (() -> Unit)? = null,
    tiers: List<String> = emptyList(),
    onSelectQuality: (String) -> Unit = {},
    lyricFontLevel: String = "标准",
    onLyricFontLevel: (String) -> Unit = {},
    subLyrics: Boolean = true,
    onSubLyrics: (Boolean) -> Unit = {},
    lyricOffsetMs: Long = 0L,
    onLyricCalibration: (Long) -> Unit = {},
    playbackSource: Song? = null,
) {
    val c = LocalPrismColors.current
    /* The preference is what to ask for; this is what is actually coming out of the speakers,
       and it is the one worth printing. */
    val playingTier = playingQuality.ifBlank { quality }
    val canCycle = tiers.size > 1
    val playFocus = remember { FocusRequester() }
    val progressFocus = remember { FocusRequester() }
    val qualityFocus = remember { FocusRequester() }
    val artistFocus = remember { FocusRequester() }
    val albumFocus = remember { FocusRequester() }
    val artistName = song?.artist.orEmpty().takeIf { it.isNotBlank() && it != "未知歌手" }
    val albumName = song?.album.orEmpty().takeIf { it.isNotBlank() }
    val artistLink = artistName != null && onArtist != null
    val albumLink = albumName != null && onAlbum != null
    val belowLinks = if (canCycle) qualityFocus else progressFocus
    /* The header sits outside the transport's geometric reach, so the queue button gets its
       own requester and a declared path back down. */
    val topFocus = remember { FocusRequester() }
    var focusedControl by remember { mutableStateOf("") }
    var radioFocused by remember { mutableStateOf(false) }
    val radioHint = when {
        radioActive -> "电台已开启 · 确定关闭，长按更换待播"
        radioLoading -> "正在准备电台 · 确定取消"
        else -> "确定开启电台，保留当前歌曲 · 长按更多"
    }
    /* True while focus sits in a row that needs left/right to move between its own items — the
       chrome icons. Everywhere else left/right is the seek key, which is what a remote user
       expects when the player is open. */
    var chromeFocus by remember { mutableStateOf(false) }
    /* 全屏歌词：只留封面、歌词和进度。它不是另一个页面，所以播放状态、焦点返回和
       Back 语义都沿用播放器本身；退出只是把界面切回来。 */
    var standby by remember { mutableStateOf(false) }
    val standbyFocus = remember { FocusRequester() }
    /* 歌词调整：字号、时间校准、副歌词收在底部调整条里，控制栏原地变身，不遮歌词。 */
    var lyricPanelOpen by remember { mutableStateOf(false) }
    val lyricEntryFocus = remember { FocusRequester() }
    val stripFirstFocus = remember { FocusRequester() }
    val panelScope = rememberCoroutineScope()
    val closeLyricPanel: () -> Unit = {
        lyricPanelOpen = false
        panelScope.launch {
            withFrameNanos { }
            runCatching { lyricEntryFocus.requestFocus() }
        }
    }
    LaunchedEffect(lyricPanelOpen) {
        if (lyricPanelOpen) {
            repeat(3) { withFrameNanos { } }
            runCatching { stripFirstFocus.requestFocus() }
        }
    }
    LaunchedEffect(standby, mvOpen) {
        if (mvOpen) return@LaunchedEffect
        val target = when {
            standby -> standbyFocus
            lyricPanelOpen -> stripFirstFocus
            else -> playFocus
        }
        target.requestAfterScroll { }
    }
    val hasWordLyrics = remember(lyrics) { lyrics.any { !it.words.isNullOrEmpty() } }
    val rawLyricPosition = if (hasWordLyrics) rememberLyricPosition(position, playing, readPosition).value else position
    /* 校准只移动歌词的判定时间，不动音频：播放时间 − d，d 为延后量（提前为负）。
       允许为负：延后 2 秒时歌曲开头判定时间 < 0，首句（timeMs=0）就该还没出现。 */
    val lyricPosition = rawLyricPosition - lyricOffsetMs
    val fontSizes = lyricSizesFor(lyricFontLevel)
    LaunchedEffect(playMode, favorite, playing, playingTier) {
        focusedControl = when {
            focusedControl.startsWith("播放模式") -> "播放模式：$playMode"
            focusedControl.startsWith("音质") -> "音质 ${tierLabel(playingTier)}" + if (canCycle) " · 确定键切换" else ""
            focusedControl in setOf("收藏", "取消收藏") -> if (favorite) "取消收藏" else "收藏"
            focusedControl in setOf("播放", "暂停") -> if (playing) "暂停" else "播放"
            else -> focusedControl
        }
    }
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val currentSeek by rememberUpdatedState(onSeek)
    /* The record turns while it plays and stops when it pauses. */
    val spin = remember { mutableFloatStateOf(0f) }
    val motion = LocalAuraMotion.current
    /* 全屏歌词是不透明的整页覆盖：底下被完全遮住的唱片旋转、轨道光、背景光晕和
       波形动画全部停掉，退出时再恢复，别让它们在看不见的地方烧帧。 */
    LaunchedEffect(playing, motion, standby) {
        if (!playing || !motion || standby) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) spin.floatValue = (spin.floatValue + (now - last) / 1_000_000_000f * 12f) % 360f
            last = now
        }
    }
    val density = LocalDensity.current
    var controlsHeight by remember { mutableStateOf(0.dp) }
    BoxWithConstraints(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        /* In standby any key is the way out, including Back: Back still means "leave the
           player", but leaving a screensaver should not also close the player. */
        if (standby) { standby = false; true }
        else when {
            event.key in setOf(Key.Back, Key.Escape) -> { if (lyricPanelOpen) closeLyricPanel() else onBack(); true }
            !chromeFocus && event.key in setOf(Key.DirectionLeft, Key.DirectionRight) -> {
                if (duration > 0) currentSeek((position + if (event.key == Key.DirectionLeft) -10_000 else 10_000).coerceIn(0, duration))
                true
            }
            else -> false
        }
    }) {
        val w = maxWidth
        val h = maxHeight
        /* The room takes the colour of the record. A walkman-era sleeve in your hands, not a
           framed thumbnail on a wall: the artwork becomes the light in the room, heavily held
           back so the lyric stays the brightest thing on screen. */
        if (song != null && !standby) {
            AsyncImage(coverRequest(song.artwork, 128), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(artworkScrim(c)))
            AuraAmbience(song, Modifier.fillMaxSize())
        }
        /* The mark lives at the same x it does on every other page. No back button here: Back
           on the remote already means "leave the player", and a button beside the logo pushed
           the brand off its fixed position. */
        Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            AuraBrand()
            Spacer(Modifier.width(22.dp))
            Text("正在聆听", color = c.muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text("${if (queueSize > 0) "%02d".format(queueIndex + 1) else "00"} · $queueSize", color = c.muted, fontSize = 12.sp)
            Spacer(Modifier.width(20.dp))
            if (hasMv) {
                MvAction(onMv, Modifier.focusProperties { down = topFocus }.onFocusChanged {
                    if (it.isFocused) { chromeFocus = true; focusedControl = "MV" }
                })
                Spacer(Modifier.width(10.dp))
            }
            PlayerIcon("lyric", "歌词", { lyricPanelOpen = !lyricPanelOpen },
                Modifier.focusRequester(lyricEntryFocus).focusProperties { down = when { canCycle -> qualityFocus; albumLink -> albumFocus; artistLink -> artistFocus; else -> progressFocus } },
                active = lyricPanelOpen,
                onFocused = { chromeFocus = true; focusedControl = "歌词 · 字号、时间校准与副歌词" })
            Spacer(Modifier.width(10.dp))
            PlayerIcon("expand", "全屏歌词", { standby = true },
                Modifier.focusProperties { down = when { canCycle -> qualityFocus; albumLink -> albumFocus; artistLink -> artistFocus; else -> progressFocus } },
                onFocused = { chromeFocus = true; focusedControl = "全屏歌词" })
            Spacer(Modifier.width(10.dp))
            PlayerIcon("bulbOff", "熄屏播放", onScreenOff,
                Modifier.focusRequester(topFocus).focusProperties { down = when { canCycle -> qualityFocus; albumLink -> albumFocus; artistLink -> artistFocus; else -> progressFocus } },
                onFocused = { chromeFocus = true; focusedControl = "熄屏播放 · 确定或返回恢复" })
        }
        Box(Modifier.offset(x = w * .035f, y = h * .135f).size(h * .52f), contentAlignment = Alignment.Center) {
            AuraOrbit(Modifier.fillMaxSize(), playing && !standby)
                /* A record, not a framed thumbnail: it turns while it plays, and the grooves and
                   spindle are painted on top so the rotation reads even on flat artwork. */
                Box(Modifier.size(h * .34f).clip(CircleShape).background(c.panel)
                    .border(1.dp, c.accent.copy(alpha = .3f), CircleShape)) {
                    CoverArt(song, Modifier.fillMaxSize().graphicsLayer { rotationZ = spin.floatValue }, 0.dp)
                    Canvas(Modifier.fillMaxSize()) {
                        val r = size.minDimension / 2f
                        listOf(.72f, .64f, .57f).forEach { ring ->
                            drawCircle(c.background.copy(alpha = .16f), radius = r * ring, center = center, style = Stroke(1.dp.toPx()))
                        }
                        drawCircle(Brush.radialGradient(listOf(Color.Transparent, c.background.copy(alpha = .55f)), radius = r, center = center), radius = r, center = center)
                    }
                    Box(Modifier.size(h * .075f).align(Alignment.Center).clip(CircleShape)
                        .background(c.background.copy(alpha = .92f)).border(1.dp, c.accent.copy(alpha = .4f), CircleShape),
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(c.accent.copy(alpha = .8f)))
                    }
                }
        }
        /* 歌词区按实际剩余空间收口：右列顶到进度条之上，歌词块只占标题区剩下的部分。
           大字号长句 + 副歌词放不下时先裁掉末尾的下一行/副歌词，绝不压到进度和控制栏。 */
        Column(Modifier.offset(x = w * .46f, y = h * .185f).width(w * .48f)
            .heightIn(max = (h - h * .185f - controlsHeight - 12.dp).coerceAtLeast(0.dp))) {
            Text(song?.title ?: "音乐，即将响起", color = c.foreground, fontSize = 32.sp, lineHeight = 40.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            /* Artist and album stay one line of context, but each name is its own target:
               OK on 周杰伦 opens the artist, OK on 《叶惠美》 opens the album. */
            if (artistName != null || albumName != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (artistName != null) {
                        PlayerSubjectLink(
                            artistName, onArtist,
                            Modifier.focusRequester(artistFocus)
                                .then(if (albumLink) Modifier.focusProperties { right = albumFocus } else Modifier)
                                .focusProperties { up = topFocus; down = belowLinks },
                            onFocused = { chromeFocus = albumLink; focusedControl = "歌手：$artistName · 确定键查看" },
                        )
                    }
                    if (artistName != null && albumName != null) Text("  ·  ", color = c.muted, fontSize = 15.sp)
                    if (albumName != null) {
                        PlayerSubjectLink(
                            "《$albumName》", onAlbum,
                            Modifier.focusRequester(albumFocus)
                                .then(if (artistLink) Modifier.focusProperties { left = artistFocus } else Modifier)
                                .focusProperties { up = topFocus; down = belowLinks },
                            onFocused = { chromeFocus = artistLink; focusedControl = "专辑：$albumName · 确定键查看" },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (song != null) {
                /* One pill, not two. The source and the tier used to sit in separate capsules,
                   each naming the same stream; a separator says it at half the visual weight
                   and keeps the cycle affordance on the whole pill. */
                val sourceSong = playbackSource ?: song
                val index = tiers.indexOf(playingTier).takeIf { it >= 0 } ?: tiers.indexOf(quality)
                val next = if (tiers.size > 1) tiers.getOrNull(index + 1) ?: tiers.first() else null
                val stream = streamFormat.ifBlank { tierLabel(playingTier) }
                Box((if (next != null) Modifier.focusRequester(qualityFocus).auraFocus(RoundedCornerShape(50), 1.04f)
                        .onFocusChanged { if (it.isFocused) { chromeFocus = false; focusedControl = "音质 ${tierLabel(playingTier)} · 确定键切换" } }
                        .focusProperties { down = progressFocus; up = when { albumLink -> albumFocus; artistLink -> artistFocus; else -> topFocus } }
                        .clickable { onSelectQuality(next) }
                    else Modifier)
                    .clip(RoundedCornerShape(50)).background(c.panel)
                    .border(1.dp, if (next != null) c.accent.copy(alpha = .28f) else c.line.copy(alpha = .7f), RoundedCornerShape(50))
                    .semantics {
                        contentDescription = when {
                            next != null -> "${sourceSong.sourceLabel}，音质 ${tierLabel(playingTier)}，$stream，确定键切换为${tierLabel(next)}"
                            tiers.isEmpty() -> "${sourceSong.sourceLabel}，音质 ${tierLabel(playingTier)}，正在确认可用档位"
                            else -> "${sourceSong.sourceLabel}，音质 ${tierLabel(playingTier)}，该音源只提供这一档"
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlatformIcon(sourceSong.platform, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        /* The tier is a preference; the codec and bitrate are what the decoder
                           actually got. Printing both let "高品" sit next to 128 kbps, which
                           is the duplicate (and the lie) the design called out. */
                        Text("${sourceSong.sourceLabel} · $stream" + if (next != null) "  ⇄" else "",
                            color = c.accent, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            val active = lyrics.indexOfLast { it.timeMs <= lyricPosition }
                Column(Modifier.weight(1f, fill = false).clipToBounds()) {
                    val line = active
                    /* 大字号优先减少相邻行：先让上一行离场，把整行留给当前歌词。 */
                    if (lyricFontLevel != "大") {
                        Text(lyrics.getOrNull(line - 1)?.text ?: " ", color = c.muted, fontSize = fontSizes.prevSp.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(12.dp))
                    }
                    val current = lyrics.getOrNull(line)
                    AnimatedContent(current, contentKey = { it?.timeMs }, label = "lyric line",
                        transitionSpec = {
                            (fadeIn(tween(if (motion) 300 else 0)) + slideInVertically(tween(if (motion) 300 else 0)) { it / 5 }) togetherWith
                                fadeOut(tween(if (motion) 140 else 0))
                        }) { displayed ->
                        Column {
                            if (displayed != null) LyricText(displayed, lyricPosition,
                                fontSize = fontSizes.currentSp.sp, lineHeight = fontSizes.currentLineSp.sp)
                            else Text(if (lyrics.isNotEmpty()) "静候前奏，慢慢入境" else lyricStatus, color = c.muted, fontSize = 16.sp,
                                maxLines = 5, overflow = TextOverflow.Ellipsis)
                            displayed?.supportingLines?.takeIf { subLyrics && it.isNotEmpty() }?.let { supporting ->
                                Text(supporting.joinToString("\n"), color = c.muted, fontSize = fontSizes.supportSp.sp, maxLines = 3,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(lyrics.getOrNull(line + 1)?.text ?: " ", color = c.muted, fontSize = fontSizes.prevSp.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
        }
        var progressFocused by remember { mutableStateOf(false) }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .onSizeChanged { controlsHeight = with(density) { it.height.toDp() } }
            .padding(start = 42.dp, end = 42.dp, top = 18.dp, bottom = 34.dp)) {
            Box(Modifier.fillMaxWidth().height(32.dp).focusRequester(progressFocus)
                .onFocusChanged { progressFocused = it.isFocused; if (it.isFocused) { chromeFocus = false; focusedControl = "← → 快退 / 快进 10 秒" } }
                .pointerInput(duration) { detectTapGestures { if (duration > 0 && size.width > 0) currentSeek((duration * (it.x / size.width).coerceIn(0f, 1f)).toLong()) } }
                .focusProperties {
                    down = if (lyricPanelOpen) stripFirstFocus else playFocus
                    /* Up leaves the rail for the quality pill, then the artist/album line, then
                       the header. Without this the top-right queue button had no route from
                       anywhere on the player. */
                    up = when { canCycle -> qualityFocus; albumLink -> albumFocus; artistLink -> artistFocus; else -> topFocus }
                }.semantics {
                    contentDescription = "播放进度"
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                    setProgress { v -> if (duration > 0) { currentSeek((duration * v.coerceIn(0f, 1f)).toLong()); true } else false }
                }.focusable()) {
                WaveGroove(progress, playing && !standby, Modifier.fillMaxWidth(), height = 32.dp, seed = song?.key?.hashCode() ?: 0, showPlayhead = progressFocused,
                    beat = if (lyrics.isNotEmpty()) lyrics.indexOfLast { it.timeMs <= lyricPosition } else -1)
                if (progressFocused) Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.BottomCenter).background(c.accent.copy(alpha = .6f)))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(time(position), color = c.muted, fontSize = 11.sp)
                /* Opening an artist or album can mean a network search before the detail
                   page exists. That wait used to be silent because the line only surfaced
                   parse failures; the press looked ignored for a second or two. */
                Text(if (status.startsWith("正在解析") || status.startsWith("正在打开") || status.startsWith("正在加载") || status.contains("失败")) status else if (radioFocused) radioHint else focusedControl,
                    color = c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp).weight(1f, fill = false), textAlign = TextAlign.Center)
                Text(time(duration), color = c.muted, fontSize = 11.sp)
            }
            /* 固定高度槽：控制行(72dp)与调整行(58dp)不同高，直接互换会改变底栏总高、
               把进度条顶得上下抖。槽高锁死，槽内交叉淡入淡出（纯 alpha、无布局动画，
               两层只在过渡的百余毫秒共存）；减少动效直接互换。关闭时反向淡出换回控制行。 */
            Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(lyricPanelOpen, modifier = Modifier.fillMaxWidth(),
                    transitionSpec = {
                        (fadeIn(tween(if (motion) 180 else 0)) togetherWith fadeOut(tween(if (motion) 120 else 0)))
                            .using(SizeTransform(sizeAnimationSpec = { _, _ -> snap() }))
                    }, label = "control strip") { open ->
                    if (open) {
                        LyricStrip(
                            fontLevel = lyricFontLevel,
                            onFontLevel = onLyricFontLevel,
                            subLyrics = subLyrics,
                            onSubLyrics = onSubLyrics,
                            offsetMs = lyricOffsetMs,
                            onCalibration = onLyricCalibration,
                            canCalibrate = lyrics.isNotEmpty(),
                            onClose = closeLyricPanel,
                            firstFocus = stripFirstFocus,
                            railFocus = progressFocus,
                            onFocusHint = { chromeFocus = true; focusedControl = it },
                        )
                    } else Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (chromeFocus) "← →  选择" else "", color = c.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            /* Every control points its Up edge at the rail: the geometric search finds
                               nothing above the transport row and would otherwise leave the player. */
                            val upToRail = Modifier.focusProperties { up = progressFocus }
                            val modeHint = if (radioActive) "切换播放模式将退出电台" else "播放模式：$playMode · 确定键切换"
                            PlayerIcon(if (radioActive) "list" else when (playMode) { "随机播放" -> "shuffle"; "单曲循环" -> "repeatOne"; "列表循环" -> "repeat"; else -> "list" }, "播放模式", onShuffle, upToRail,
                                onFocused = { chromeFocus = true; focusedControl = modeHint })
                            PlayerIcon("prev", "上一首", onPrevious, upToRail, onFocused = { chromeFocus = true; focusedControl = "上一首" })
                            PlayButton(playing, onPlay, Modifier.focusRequester(playFocus).focusProperties { up = progressFocus },
                                onFocused = { chromeFocus = true; focusedControl = if (playing) "暂停" else "播放" })
                            PlayerIcon("next", "下一首", onNext, upToRail, onFocused = { chromeFocus = true; focusedControl = "下一首" })
                            PlayerIcon(if (favorite) "heartFilled" else "heart", if (favorite) "取消收藏" else "收藏", onFavorite, upToRail,
                                onFocused = { chromeFocus = true; focusedControl = if (favorite) "取消收藏" else "收藏" })
                        }
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            PlayerIcon("list", "播放列表 $queueSize", onQueue,
                                Modifier.focusProperties { up = progressFocus },
                                onFocused = { chromeFocus = true; focusedControl = "播放列表 · $queueSize 首" })
                            PlayerIcon("radio", if (radioActive) "关闭电台" else if (radioLoading) "取消准备电台" else "开启电台", onRadioToggle,
                                Modifier.focusProperties { up = progressFocus }.onFocusChanged {
                                    radioFocused = it.isFocused
                                }, onLongPress = onRadioMenu, active = radioActive || radioLoading,
                                onFocused = { chromeFocus = true })
                        }
                    }
                }
            }
        }
        if (standby) PrismStandby(song, lyrics, lyricStatus, position, duration, lyricPosition,
            fontSizes, lyricFontLevel, subLyrics, focus = standbyFocus)
    }
}

/* 熄屏不是故障：播放器先被幕布盖住淡出，黑底上留一行低亮度提示约两秒，提示再淡出，
   最后才是纯黑。每次进入只提示一次；切歌、调音量不会重来。过渡期间任何唤醒键都能立即
   恢复。减少动效只是取消动画，最终同样什么都不显示。黑色由外层幕布统一绘制，本层透明。 */
@Composable
internal fun PrismScreenOff(
    keepAwake: Boolean, motion: Boolean, settled: Boolean, onWake: () -> Unit,
) {
    val window = (LocalContext.current as? Activity)?.window
    val view = LocalView.current
    val focus = remember { FocusRequester() }
    val wake by rememberUpdatedState(onWake)
    val hint = remember { Animatable(0f) }
    val entryBrightness = remember { window?.attributes?.screenBrightness }

    DisposableEffect(view, keepAwake) {
        val previous = view.keepScreenOn
        view.keepScreenOn = previous || keepAwake
        onDispose { view.keepScreenOn = previous }
    }
    DisposableEffect(window, entryBrightness) {
        onDispose {
            if (window != null && entryBrightness != null) {
                window.attributes = window.attributes.apply { screenBrightness = entryBrightness }
            }
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        focus.requestFocus()
    }
    /* 提示退场后才压到零亮度，之前黑底靠像素、提示靠自身低透明度可见；这样提示在
       任何电视上都看得见，之后才是真正的纯黑。 */
    LaunchedEffect(settled, motion) {
        if (!settled) return@LaunchedEffect
        if (motion) {
            hint.animateTo(1f, tween(200))
            delay(2000)
            hint.animateTo(0f, tween(600))
        } else {
            hint.snapTo(1f)
            delay(2000)
            hint.snapTo(0f)
        }
        if (window != null) window.attributes = window.attributes.apply { screenBrightness = 0f }
    }
    /* 背景透明：黑色由下方的幕布统一绘制，这里只接焦点/点击并在幕布之上显示提示。 */
    Box(Modifier.fillMaxSize()
        .focusRequester(focus).focusable()
        .pointerInput(Unit) { detectTapGestures { wake() } }) {
        if (hint.value > 0f) {
            Text("熄屏播放中 · 按确定或返回恢复",
                modifier = Modifier.align(Alignment.Center).alpha(hint.value * .42f),
                color = Color.White, fontSize = 15.sp)
        }
    }
}

/* 全屏歌词：电视不该让一块亮着的界面一直要求你操作。封面铺满整屏，像房间里的光；歌词是
   唯一的前景，底部只有一条进度。没有转动的唱片，也没有任何按钮。 */
@Composable
private fun PrismStandby(
    song: Song?, lyrics: List<LyricLine>, lyricStatus: String, position: Long, duration: Long,
    lyricPosition: Long, fontSizes: PlayerLyricSizes, fontLevel: String, subLyrics: Boolean,
    focus: FocusRequester,
) {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val active = lyrics.indexOfLast { it.timeMs <= lyricPosition }
    var hintVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(4200); hintVisible = false }
    /* A very slow zoom keeps the still frame alive without turning the cover into a widget.
       减少动效时不创建无限动画，帧循环不再空转；缩放值在绘制期读取。 */
    val zoom = if (motion) rememberInfiniteTransition(label = "standby drift").animateFloat(
        initialValue = 1.02f, targetValue = 1.09f,
        animationSpec = infiniteRepeatable(tween(48_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "cover drift",
    ) else null
    BoxWithConstraints(Modifier.fillMaxSize().background(c.background).focusRequester(focus).focusable()) {
        val w = maxWidth
        if (song != null) {
            CoverArt(song, Modifier.fillMaxSize().graphicsLayer {
                val factor = zoom?.value ?: 1.04f
                scaleX = factor; scaleY = factor
            }.blur(36.dp, BlurredEdgeTreatment.Unbounded), 0.dp)
            /* The artwork stays a photograph; the words stay the brightest thing on screen. */
            Box(Modifier.fillMaxSize().background(artworkScrim(c)))
            AuraAmbience(song, Modifier.fillMaxSize())
        }
        /* The title is a film credit: present, but never competing with the lyric. */
        Column(Modifier.align(Alignment.TopStart).padding(start = 56.dp, top = 40.dp).width(w * .55f)) {
            Text(song?.title ?: "音乐，即将响起", color = c.foreground.copy(alpha = .92f), fontSize = 22.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Text(song?.artist.orEmpty(), color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StandbyLyrics(lyrics, active, lyricStatus, lyricPosition, fontSizes, fontLevel, subLyrics,
            Modifier.align(Alignment.Center).width(w * .78f))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 56.dp, end = 56.dp, bottom = 38.dp)) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(c.line.copy(alpha = .45f))) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(c.accent.copy(alpha = .9f)))
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(time(position), color = c.muted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(time(duration), color = c.muted, fontSize = 12.sp)
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            hintVisible, modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 56.dp),
            enter = fadeIn(tween(400)), exit = fadeOut(tween(600)),
        ) {
            Text("按任意键返回", color = c.muted.copy(alpha = .7f), fontSize = 12.sp)
        }
    }
}

/* NetEase-style lyric column: the sung line sits in the middle, neighbours dim with distance,
   and the column rolls one line at a time. A vertical DstIn mask softens both edges so lines
   enter and leave the screen instead of being clipped. */
@Composable
private fun StandbyLyrics(lyrics: List<LyricLine>, active: Int, status: String, position: Long,
                          fontSizes: PlayerLyricSizes, fontLevel: String, subLyrics: Boolean,
                          modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    if (lyrics.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(status.ifBlank { "暂无歌词" }, color = c.muted, fontSize = 19.sp, textAlign = TextAlign.Center,
                maxLines = 8, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    val state = rememberLazyListState()
    Box(
        modifier.height(340.dp).clipToBounds()
            /* Offscreen: the DstIn mask must erase only this column's pixels, not the cover
               and its gradients underneath. */
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Transparent, .2f to Color.Black, .8f to Color.Black, 1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            /* Equal top and bottom padding makes "scroll to item" land the line on the centre
               line; it also gives the first and last lines somewhere to scroll to. */
            contentPadding = PaddingValues(vertical = 146.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(lyrics) { index, line ->
                val current = index == active
                val distance = if (active < 0) Int.MAX_VALUE else kotlin.math.abs(index - active)
                /* 大字号优先减少相邻行：隔两行以上的直接不渲染，留出空间给当前行。 */
                if (!current && fontLevel == "大" && distance >= 3) return@itemsIndexed
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (current) LyricText(line, position, Modifier.fillMaxWidth(),
                        fontSizes.fullCurrentSp.sp, fontSizes.fullLineSp.sp, TextAlign.Center)
                    else Text(line.text,
                        color = c.muted.copy(alpha = when {
                            distance == 1 -> .55f; distance == 2 -> .34f; distance == 3 -> .2f; else -> .1f
                        }), fontSize = (if (distance == 1) fontSizes.fullNearSp else fontSizes.fullFarSp).sp, lineHeight = 24.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth())
                    if (current && subLyrics) line.supportingLines.takeIf { it.isNotEmpty() }?.let { supporting ->
                        Text(supporting.joinToString("\n"), color = c.muted.copy(alpha = .9f), fontSize = fontSizes.fullSupportSp.sp, maxLines = 3,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
        /* 字号档位改变会重排行高与换行，居中也要跟着重算。 */
        LaunchedEffect(active, lyrics, fontSizes, subLyrics) {
            if (active < 0 || active >= lyrics.size) return@LaunchedEffect
            while (state.layoutInfo.viewportSize.height == 0) withFrameNanos { }
            /* Let the highlight resize its row before the distance is measured. */
            withFrameNanos { }
            val target = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == active }
            if (target != null) {
                val info = state.layoutInfo
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                val delta = target.offset + target.size / 2f - center
                if (kotlin.math.abs(delta) > .5f) {
                    state.animateScrollBy(delta, tween(if (motion) 520 else 0, easing = FastOutSlowInEasing))
                }
            } else {
                state.scrollToItem(active)
                withFrameNanos { }
                val info = state.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.index == active }
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                if (item != null) state.scrollBy(item.offset + item.size / 2f - center)
            }
        }
    }
}

/* 统一的开关行：整行聚焦、确定键切换，胶囊滑块左右滑动；减少动效时直接到位。
   新版本提醒、播放条歌词、减少动效、翻译注音共用这一种样式。 */
@Composable
internal fun PrismSwitchRow(
    label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, description: String? = null,
) {
    val c = LocalPrismColors.current
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.fillMaxWidth().auraFocus(shape, 1f)
        .clip(shape).background(c.panel)
        .border(1.dp, c.line.copy(alpha = .35f), shape)
        .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = c.foreground, fontSize = 14.sp)
            if (description != null) Text(description, color = c.muted, fontSize = 12.sp)
        }
        PrismCapsule(checked)
    }
}

/* 胶囊滑块本身：设置行与播放页底部调整条共用同一种开关。 */
@Composable
internal fun PrismCapsule(checked: Boolean) {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    val t by animateFloatAsState(if (checked) 1f else 0f, tween(if (motion) 180 else 0), label = "capsule switch")
    Box(Modifier.size(44.dp, 24.dp).clip(RoundedCornerShape(50))
        .background(lerp(c.background, c.accent, t))
        .border(1.dp, lerp(c.line, Color.Transparent, t), RoundedCornerShape(50)),
        contentAlignment = Alignment.CenterStart) {
        Box(Modifier.padding(start = 3.dp).offset(x = 20.dp * t).size(18.dp)
            .clip(CircleShape).background(lerp(c.muted, c.onAccent, t)))
    }
}

/* 歌词面板：字号、时间校准、副歌词收在同一个面板里，遥控器上下就能走完。 */
@Composable
private fun FontChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, textSp: Int = 14, height: Dp = 40.dp) {
    val c = LocalPrismColors.current
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.height(height).auraFocus(shape, 1.05f)
        .clip(shape)
        .background(if (selected) c.accent.copy(alpha = .2f) else c.background.copy(alpha = .5f))
        .border(1.dp, if (selected) c.accent else c.line.copy(alpha = .6f), shape)
        .clickable(onClick = onClick)
        .semantics { contentDescription = "歌词字号 $label" },
        contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) c.accent else c.foreground, fontSize = textSp.sp)
    }
}

/* 底部调整条：歌词入口不再侧弹面板，控制栏原地变成调整条——唱片与歌词零遮挡，
   焦点落在熟悉的底部控制区，而不是横穿半屏。返回键/返回按钮交还播放控制。 */
@Composable
private fun LyricStrip(
    fontLevel: String, onFontLevel: (String) -> Unit,
    subLyrics: Boolean, onSubLyrics: (Boolean) -> Unit,
    offsetMs: Long, onCalibration: (Long) -> Unit,
    canCalibrate: Boolean, onClose: () -> Unit,
    firstFocus: FocusRequester, railFocus: FocusRequester, onFocusHint: (String) -> Unit,
) {
    val c = LocalPrismColors.current
    val upToRail = Modifier.focusProperties { up = railFocus }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("字号", color = c.muted, fontSize = 12.sp)
        listOf("小", "标准", "大").forEach { label ->
            /* 预览用拉开的相对比例（11/14/18sp）而非真实歌词字号：真实字号太大喧宾夺主，
               且 22/26/30 的差距肉眼难辨。调整件必须比歌词安静。 */
            FontChip(label, fontLevel == label, { onFontLevel(label) },
                Modifier.width(when (label) { "标准" -> 60.dp; "大" -> 44.dp; else -> 40.dp })
                    .then(if (label == fontLevel) Modifier.focusRequester(firstFocus) else Modifier)
                    .then(upToRail)
                    .onFocusChanged { if (it.isFocused) onFocusHint("歌词字号 $label · 左右移动，即时生效") },
                textSp = when (label) { "小" -> 11; "大" -> 18; else -> 14 }, height = 36.dp)
        }
        Spacer(Modifier.width(8.dp))
        Text("校准", color = c.muted, fontSize = 12.sp)
        if (canCalibrate) {
            /* 值槽固定宽：「无校准」↔「延后 X.X 秒」切换时整条布局不动，
               正在聚焦的按钮不会跟着位移。 */
            Text(LyricCalibration.describe(offsetMs), color = c.foreground, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(72.dp))
            /* 校准按钮直接写明步长：媒体键造型的 |◀ ▶| 会被读成切歌。 */
            listOf(-100L to "-0.1s", 100L to "+0.1s").forEach { (delta, label) ->
                Text(label, color = c.foreground, fontSize = 12.sp, modifier = Modifier
                    .auraFocus(RoundedCornerShape(8.dp), 1.05f).clip(RoundedCornerShape(8.dp))
                    .background(c.background.copy(alpha = .5f))
                    .border(1.dp, c.line.copy(alpha = .6f), RoundedCornerShape(8.dp))
                    .clickable { onCalibration((offsetMs + delta).coerceIn(-LyricCalibration.LIMIT_MS, LyricCalibration.LIMIT_MS)) }
                    .then(upToRail)
                    .onFocusChanged { if (it.isFocused) onFocusHint("歌词校准 · $label") }
                    .padding(horizontal = 10.dp, vertical = 8.dp))
            }
            PlayerIcon("undo", "重置校准", { onCalibration(0) }, upToRail,
                onFocused = { onFocusHint("歌词校准 · 重置为零") })
        } else {
            Text("无时间轴，无法校准", color = c.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Row(Modifier.auraFocus(RoundedCornerShape(50), 1.04f).clip(RoundedCornerShape(50))
            .then(upToRail)
            .onFocusChanged { if (it.isFocused) onFocusHint("翻译与注音 · ${if (subLyrics) "显示" else "隐藏"}") }
            .toggleable(subLyrics, role = Role.Switch, onValueChange = onSubLyrics)
            .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("译/注音", color = c.muted, fontSize = 12.sp)
            PrismCapsule(subLyrics)
        }
        Spacer(Modifier.weight(1f))
        PlayerIcon("close", "收起调整，返回播放控制", onClose, upToRail,
            onFocused = { onFocusHint("收起调整，返回播放控制") })
    }
}

/* Names, not buttons: the artist and album read as part of the caption until the remote
   lands on them, then the name turns gold and underlines itself on the same baseline. */
@Composable
private fun PlayerSubjectLink(label: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier, onFocused: () -> Unit = {}) {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    if (onClick == null) {
        Text(label, color = c.muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    Text(
        label,
        color = if (focused) c.accent else c.muted,
        fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .drawWithContent {
                drawContent()
                if (focused) drawLine(c.accent, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), 1.dp.toPx())
            }
            .padding(horizontal = 3.dp, vertical = 2.dp),
    )
}

private fun tierLabel(tier: String): String = when (tier) {
    "low" -> "流畅"; "standard" -> "标准"; "high" -> "高品"; "super" -> "无损优先"
    "flac" -> "无损"; "flac24bit" -> "24-bit 无损"; "hires" -> "Hi-Res"; "dolby" -> "杜比"; "atmos" -> "Atmos"; "atmos_plus" -> "Atmos+"; else -> tier
}

@Composable
private fun PlayerIcon(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, onLongPress: (() -> Unit)? = null, active: Boolean = false, onFocused: () -> Unit = {}) {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    Box(modifier.size(44.dp).auraFocus(RoundedCornerShape(50), 1.08f).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
        .background(if (focused || active) c.accent.copy(alpha = .12f) else Color.Transparent, CircleShape)
        .border(1.dp, if (focused || active) c.accent else Color.Transparent, CircleShape)
        .clip(CircleShape).semantics { contentDescription = label; selected = active }.tvClickable(onClick, onLongPress), contentAlignment = Alignment.Center) {
        Glyph(icon, Modifier.size(if (icon.startsWith("heart") || icon == "radio") 20.dp else 18.dp), if (focused || active || icon == "heartFilled") c.accent else c.muted)
    }
}

/** The primary object uses the active theme's accent. The glyph reads faster than the word
    did, and an icon-only circle keeps the transport row from looking like a settings page. */
@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onFocused: () -> Unit = {}) {
    val c = LocalPrismColors.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier.size(58.dp).auraFocus(shape, 1.07f, ring = c.foreground)
            .semantics { contentDescription = if (playing) "暂停" else "播放" }
            .onFocusChanged { if (it.isFocused) onFocused() }
            .clip(shape)
            .background(Brush.linearGradient(listOf(lerp(c.accent, c.foreground, .22f), lerp(c.accent, c.background, .20f))))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(if (playing) "pause" else "play", Modifier.size(24.dp), c.onAccent)
    }
}

/* ─────────────────────────────────────────────────────────────────────────────
   Home, rebuilt to the v2 design's structure.

   The old home was a vertical stack of chips: a title, a strip of pill buttons
   for recommended playlists, then shelves of 72dp covers with a button label
   under each. Nothing was a single object you could point at, and the screen
   had no bottom edge — no mini player, so you lost the song the moment you
   left the player.

   What follows is the same three-part hero the prototype has (words / queue /
   cover), shelves made of real cover cards, and a mini player pinned to the
   bottom. Type sizes stay at the app's existing TV scale rather than the
   prototype's denser editorial scale — structure first.
   ───────────────────────────────────────────────────────────────────────────── */

/** 16:9 TV safe margins: 56px in the prototype = 28dp here. */
val PrismPagePadding = 28.dp

@Composable
private fun CoverArt(song: Song?, modifier: Modifier = Modifier, radius: androidx.compose.ui.unit.Dp = 12.dp) {
    val c = LocalPrismColors.current
    val hasArt = song?.artwork?.startsWith("http") == true || song?.artwork?.startsWith("content") == true
    /* No artwork is not an error state, so it doesn't get an error border. It gets the light
       field the prototype uses: a soft bloom with the song's first character inside it. */
    Box(
        modifier.clip(RoundedCornerShape(radius))
            .background(c.panel)
            .drawBehind {
                if (!hasArt) drawRect(
                    Brush.radialGradient(
                        listOf(c.accent.copy(alpha = .22f), c.accent.copy(alpha = .05f), Color.Transparent),
                        center = Offset(size.width * .38f, size.height * .32f),
                        radius = size.maxDimension * .78f,
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val url = song?.artwork.orEmpty()
        /* The letter is painted first and the photo lands on top of it: an AsyncImage that is
           still loading — or that failed — draws nothing, so the fallback is what you see.
           One decision, same as the prototype: does this track have artwork, yes or no. */
        AuraMark(Modifier.fillMaxSize(.78f))
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("content://")) {
            AsyncImage(
                model = coverRequest(url), contentDescription = "${song?.title}封面", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** One cover card on a shelf. The whole tile is the target, not a button under it. */
@Composable
fun PrismCoverCard(song: Song, index: Int, onClick: () -> Unit, modifier: Modifier = Modifier, current: Boolean = false, onLongPress: (() -> Unit)? = null) {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .width(132.dp)
            .auraFocus(RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) c.accent.copy(alpha = .12f) else Color.Transparent)
            .border(1.dp, if (focused) c.accent.copy(alpha = .55f) else Color.Transparent, RoundedCornerShape(14.dp))
            .tvClickable(onClick, onLongPress)
            .padding(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            CoverArt(song, Modifier.fillMaxSize(), 10.dp)
            if (current) Box(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = .55f)).padding(horizontal = 7.dp, vertical = 5.dp),
            ) { NowPlayingBars(c.accent, height = 11.dp, barWidth = 2.dp) }
        }
        Spacer(Modifier.height(8.dp))
        Text(song.title, color = c.foreground, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.focusedMarquee(focused))
        Spacer(Modifier.height(2.dp))
        Text(song.artist.ifBlank { "未知" }, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


/** The control 管理模式 puts in front of every row and card. Empty when unselected so the
    list still reads as a list while you browse for what to remove. */
@Composable
fun PrismCheckmark(checked: Boolean, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val c = LocalPrismColors.current
    val shape = RoundedCornerShape(size * .3f)
    Box(modifier.size(size).clip(shape)
        .background(if (checked) c.accent else Color.Transparent)
        .border(if (checked) 0.dp else 1.dp, c.muted.copy(alpha = .55f), shape),
        contentAlignment = Alignment.Center) {
        if (checked) Glyph("check", Modifier.size(size * .76f), c.onAccent)
    }
}

/** A recommended sheet: same tile as a song card, but the subtitle is the source. */
@Composable
fun PrismSheetCard(title: String, artwork: String, subtitle: String, modifier: Modifier = Modifier,
                   selected: Boolean? = null, portrait: Boolean = false, onLongPress: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.width(132.dp)
            .auraFocus(RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused || selected == true) c.accent.copy(alpha = .12f) else Color.Transparent)
            .border(1.dp, if (selected == true) c.accent else if (focused) c.accent.copy(alpha = .55f) else Color.Transparent, RoundedCornerShape(14.dp))
            .tvClickable(onClick, onLongPress)
            .padding(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(if (portrait) CircleShape else RoundedCornerShape(10.dp)).background(c.panel),
            contentAlignment = Alignment.Center) {
            Text(title.take(1), fontSize = 26.sp, color = c.accent.copy(alpha = .55f))
            if (artwork.startsWith("http://") || artwork.startsWith("https://")) {
                AsyncImage(model = coverRequest(artwork), contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (selected != null) PrismCheckmark(selected, Modifier.align(Alignment.TopEnd).padding(6.dp), 22.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(title, color = c.foreground, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Section header: name, count, and the way out. */
@Composable
fun PrismShelfHeader(title: String, count: Int?, action: String, onAction: () -> Unit, actionFocus: FocusRequester? = null, actionEdges: Modifier = Modifier) {
    val c = LocalPrismColors.current
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = c.foreground, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text("$count 首", color = c.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        /* The action sits flush against the page's clipped edge; its focus scale would push
           the ring past the clip, so keep a small inset the scale can live inside. */
        if (action.isNotBlank()) PrismAction(action, onAction, (if (actionFocus != null) Modifier.focusRequester(actionFocus) else Modifier).then(actionEdges).padding(end = 4.dp))
    }
}

/** Empty collection: the sentence and its way out sit together, at one fixed
    spot with one fixed padding. Each tab used to lay out its own version, so
    switching tabs made the hint jump sideways and up/down. */
@Composable
fun PrismEmptyState(hint: String, action: String? = null, onAction: (() -> Unit)? = null, actionModifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    /* 起手留出和列表卡片一样的 6dp：胶囊的聚焦环按 1.035 放大，贴在页面
       裁剪边上时左边那一圈会被切掉。 */
    Column(Modifier.padding(start = 6.dp, top = 4.dp)) {
        Text(hint, color = c.muted, fontSize = 12.sp)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(10.dp))
            PrismAction(action, onAction, actionModifier)
        }
    }
}

@Composable private fun AuraChip(text: String, modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    Box(modifier.border(1.dp, c.line.copy(alpha = .8f), RoundedCornerShape(6.dp)).padding(horizontal = 9.dp, vertical = 4.dp)) {
        Text(text, color = c.muted, fontSize = 11.sp, letterSpacing = 0.4.sp, maxLines = 1)
    }
}

@Composable
fun PrismHero(
    song: Song?, onContinue: () -> Unit,
    continueAnchor: FocusAnchor? = null,
    modifier: Modifier = Modifier,
    hasSources: Boolean = true,
    favorite: Boolean = false,
    playing: Boolean = false,
    onLongPress: (() -> Unit)? = null,
) {
    val c = LocalPrismColors.current
    val shape = RoundedCornerShape(18.dp)
    /* Home keeps a rail to the right of this card, so the card is never as wide as it looks in
       a design mock. The card itself is the control. The progress rail lives in the mini
       player only; repeating it here made two bars say the same thing, so the hero says it
       with a moving note and a saved marker instead. */
    BoxWithConstraints(modifier.fillMaxWidth().heightIn(min = 172.dp)) {
        val compact = maxWidth < 470.dp
        var focused by remember { mutableStateOf(false) }
        Box(
            (if (continueAnchor != null) Modifier.focusAnchor(continueAnchor) else Modifier)
                .fillMaxWidth().heightIn(min = 172.dp)
                /* The page clips its bounds, so any scale would push the rounded corner and the
                   focus ring past the clip edge at top-left. The ring alone must carry focus. */
                .auraFocus(shape, 1f)
                .onFocusChanged { focused = it.isFocused }
                .clip(shape)
                .background(c.panel)
                .border(1.dp, c.line, shape)
                .semantics {
                    contentDescription = when {
                        song != null -> "继续播放 ${song.title}"
                        hasSources -> "探索音乐"
                        else -> "连接音乐世界"
                    }
                }
                .tvClickable(onContinue, onLongPress),
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 172.dp)
                    .padding(start = if (compact) 22.dp else 26.dp, end = 16.dp, top = 20.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song?.title ?: "让音乐，有光。", color = c.foreground, fontSize = if (compact) 27.sp else 34.sp, lineHeight = if (compact) 34.sp else 42.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.focusedMarquee(song != null && focused))
                    Spacer(Modifier.height(6.dp))
                    /* The source is the last word on this line, so its icon belongs right after it —
                       in the play row below the icon only repeated what the text already says. */
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            song?.let { s -> listOfNotNull(s.artist.takeIf(String::isNotBlank),
                                s.album.takeIf { it.isNotBlank() && it != s.title }, s.sourceLabel).joinToString(" · ") }
                                ?: "把喜欢的声音，留给此刻的自己。",
                            color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (song != null) {
                            Spacer(Modifier.width(7.dp))
                            PlatformIcon(song.platform, Modifier.size(15.dp))
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    if (song != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NowPlayingBars(if (playing) c.accent else c.muted, height = 14.dp, barWidth = 2.5.dp, active = playing)
                            Spacer(Modifier.width(9.dp))
                            Text(if (playing) "正在播放" else "继续播放", color = if (playing) c.accent else c.muted, fontSize = 11.sp, letterSpacing = .4.sp)
                            if (favorite) {
                                Spacer(Modifier.weight(1f))
                                /* A saved song is a state, not a task: a filled heart says it
                                   without another focus target. Toggling stays in the player
                                   and the mini bar. */
                                Glyph("heartFilled", Modifier.size(14.dp), c.accent)
                            }
                        }
                    } else {
                        Text(if (hasSources) "探索音乐  →" else "连接音乐世界  →", color = c.accent, fontSize = 12.sp, letterSpacing = .4.sp)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(if (compact) 104.dp else 124.dp), contentAlignment = Alignment.Center) {
                    if (song != null) {
                        CoverArt(song, Modifier.fillMaxSize(.9f), 12.dp)
                    } else {
                        AuraOrbit(Modifier.fillMaxSize())
                        AuraMark(Modifier.fillMaxSize(.8f))
                    }
                }
            }
        }
    }
}

@Composable fun AuraEmptyState(title: String, detail: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    Row(modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        AuraMark(Modifier.size(58.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.foreground, fontSize = 17.sp)
            Spacer(Modifier.height(6.dp))
            Text(detail, color = c.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(20.dp))
        PrismAction(action, onAction)
    }
}

@Composable fun AuraThemeCard(label: String, subtitle: String, colors: PrismColors, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    Column(modifier.auraFocus().clip(RoundedCornerShape(12.dp)).background(colors.background)
        .border(1.dp, if (selected) c.accent else c.line, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(14.dp)) {
        CompositionLocalProvider(LocalPrismColors provides colors) { AuraMark(Modifier.size(54.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(label + if (selected) " · 已选" else "", color = colors.foreground, fontSize = 14.sp)
        Spacer(Modifier.height(5.dp))
        Text(subtitle, color = colors.muted, fontSize = 12.sp)
    }
}

/** Pinned to the bottom of every page. Losing the song on navigation is not acceptable. */
@Composable
fun PrismMiniPlayer(
    song: Song?, playing: Boolean, position: Long, duration: Long, lyric: String, favorite: Boolean,
    onOpen: () -> Unit, onToggle: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onFavorite: () -> Unit, onQueue: () -> Unit,
    openFocus: FocusRequester,
    upTarget: FocusAnchor? = null,
    upMemory: MutableState<FocusRequester?>? = null,
    /* For a page whose body is a scrolling list: the remembered requester may belong to a row
       that is no longer composed, so the page gets to scroll it back first. Returning false lets
       the plain memory/entry routes run. */
    upRestore: (() -> Boolean)? = null,
    onLongPress: (() -> Unit)? = null,
    playbackSource: Song? = null,
) {
    val c = LocalPrismColors.current
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val toPageEntry = Modifier.focusProperties { upTarget?.takeIf { it.attached }?.let { up = it.requester } }
    /* Up prefers the body control the remote came from (e.g. a settings row) over the page
       entry. The remembered requester may belong to a node that has left composition; a
       failed request falls through to upTarget. */
    val upMemoryRoute = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
            if (upRestore?.invoke() == true) return@onPreviewKeyEvent true
            val target = upMemory?.value
            if (target != null && runCatching { target.requestFocus() }.isSuccess) return@onPreviewKeyEvent true
        }
        false
    }
    Box(Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(14.dp))
        .background(c.panel).border(1.dp, c.line.copy(alpha = .65f), RoundedCornerShape(14.dp))
        .then(upMemoryRoute)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(song, Modifier.size(36.dp), 7.dp)
            if (song != null) { Spacer(Modifier.width(8.dp)); PlatformIcon((playbackSource ?: song).platform, Modifier.size(20.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).focusRequester(openFocus).then(toPageEntry).auraFocus(scale = 1f).clip(RoundedCornerShape(12.dp))
                .semantics { contentDescription = if (song == null) "搜索音乐" else "打开正在播放" }
                .tvClickable(onOpen, onLongPress).padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text(song?.title ?: "此刻，适合听点什么？", color = c.foreground, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                /* The platform mark already names the source. Repeating its name and the
                   stream format turned this into a metadata strip; one line of context is
                   all the bar needs. */
                Text(song?.artist ?: "选择一首歌，让房间慢下来", color = c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (song != null) {
                /* Every stop in this bar leads back to the page body on Up; without it the
                   lyric and the transport buttons are a dead end. */
                val exitUp = toPageEntry
                if (lyric.isNotBlank()) {
                    Text(lyric, modifier = Modifier.weight(1f).then(exitUp).tvClickable(onOpen, onLongPress).padding(horizontal = 12.dp), color = c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(time(position) + " / " + time(duration), color = c.foreground.copy(alpha = .8f), fontSize = 13.sp)
                Spacer(Modifier.width(10.dp))
                PlayerIcon("prev", "上一首", onPrevious, exitUp)
                Spacer(Modifier.width(4.dp))
                PlayerIcon(if (playing) "pause" else "play", if (playing) "暂停" else "播放", onToggle, exitUp)
                Spacer(Modifier.width(4.dp))
                PlayerIcon("next", "下一首", onNext, exitUp)
                Spacer(Modifier.width(4.dp))
                PlayerIcon(if (favorite) "heartFilled" else "heart", if (favorite) "取消收藏" else "收藏", onFavorite, exitUp)
                Spacer(Modifier.width(4.dp))
                PlayerIcon("list", "播放列表", onQueue, exitUp)
            } else PrismAction("搜索音乐", onOpen)
        }
        if (song != null) {
        WaveGroove(
            progress = progress,
            playing = playing,
            height = 6.dp,
            seed = song?.key?.hashCode() ?: 0,
            showPlayhead = false,
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
        )
        }
    }
}

@Composable
fun PrismChartCard(title: String, artwork: String, preview: List<Song>, modifier: Modifier = Modifier, onLongPress: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    Row(modifier.height(132.dp).onFocusChanged { focused = it.isFocused }
        .clip(RoundedCornerShape(14.dp)).background(c.panel)
        .border(1.dp, if (focused) c.accent else c.line.copy(alpha = .5f), RoundedCornerShape(14.dp))
        .tvClickable(onClick, onLongPress).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(76.dp).clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(listOf(c.accent.copy(alpha = .22f), c.background))), contentAlignment = Alignment.Center) {
            if (artwork.startsWith("http://") || artwork.startsWith("https://")) {
                AsyncImage(model = coverRequest(artwork), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text("♪", color = c.accent.copy(alpha = .8f), fontSize = 28.sp)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("榜单", color = c.accent, fontSize = 10.sp)
            Spacer(Modifier.height(6.dp))
            Text(title, color = c.foreground, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            preview.take(3).forEachIndexed { index, song ->
                Text("${index + 1}  ${song.title} · ${song.artist}", color = c.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun PrismQueueRow(song: Song, index: Int, current: Boolean, modifier: Modifier = Modifier, onLongPress: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    val emphasis by animateFloatAsState(if (focused) 1f else if (current) .5f else 0f, tween(if (LocalAuraMotion.current) 170 else 0), label = "queue light")
    Row(modifier.fillMaxWidth().height(52.dp).onFocusChanged { focused = it.isFocused }
        .clip(RoundedCornerShape(8.dp)).background(c.accent.copy(alpha = emphasis * .12f))
        .drawBehind { if (emphasis > 0f) drawRect(c.accent.copy(alpha = emphasis), size = Size(2.dp.toPx(), size.height)) }
        .tvClickable(onClick, onLongPress).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (current) Box(Modifier.width(35.dp)) { NowPlayingBars(c.accent) }
        else Text("%02d".format(index + 1), Modifier.width(35.dp), color = if (focused) c.accent else c.muted, fontSize = 12.sp)
        CoverArt(song, Modifier.size(38.dp), 6.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, color = if (current) c.accent else c.foreground, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.focusedMarquee(focused))
            Text(song.artist, color = c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PlatformIcon(song.platform, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(song.sourceLabel, color = c.muted, fontSize = 10.sp, maxLines = 1, modifier = Modifier.widthIn(max = 100.dp), overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(16.dp))
        if (song.durationLabel != "—") Text(song.durationLabel, color = c.muted, fontSize = 11.sp)
    }
}

/* ─────────────────────────────────────────────────────────────────────────────
   Table primitives.

   The library used to be `PrismSongRow` with no columns: a title, an artist, a
   platform icon and an album squeezed into whatever space was left. You could
   not scan down a column, which is the only reason a table exists. These three
   composables share one set of column widths so the header and the rows line up
   — the alignment is the feature, not the decoration.
   ───────────────────────────────────────────────────────────────────────────── */

private val ColIndex = 34.dp
private val ColCover = 38.dp
private val ColGap = 10.dp
private val ColSource = 58.dp

/**
 * The head of an artist/album/playlist page: the source's own image, the name, a short
 * introduction, and the actions that belong to the subject. Text sits on a dark scrim so a
 * bright photo never swallows it, and the actions stay inside the card instead of floating
 * in a separate toolbar row.
 */
@Composable
fun PrismDetailHero(
    title: String,
    eyebrow: String,
    meta: String,
    description: String,
    artwork: String,
    modifier: Modifier = Modifier,
    portrait: Boolean = false,
    actions: @Composable RowScope.() -> Unit,
) {
    val c = LocalPrismColors.current
    val shape = RoundedCornerShape(20.dp)
    val hasArt = artwork.startsWith("http://") || artwork.startsWith("https://")
    BoxWithConstraints(modifier.fillMaxWidth().height(248.dp).clip(shape)) {
        val compact = maxWidth < 640.dp
        Box(Modifier.matchParentSize().clip(shape).background(Brush.linearGradient(listOf(c.panel, c.background))))
        if (hasArt) {
            AsyncImage(
                model = coverRequest(artwork), contentDescription = title, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().graphicsLayer { alpha = .55f },
            )
        }
        Box(Modifier.matchParentSize().clip(shape).background(
            Brush.horizontalGradient(listOf(c.background.copy(alpha = .94f), c.background.copy(alpha = .72f), c.background.copy(alpha = .18f))),
        ))
        Box(Modifier.matchParentSize().clip(shape).background(
            Brush.verticalGradient(listOf(Color.Transparent, c.background.copy(alpha = .55f))),
        ))
        Box(Modifier.matchParentSize().border(1.dp,
            Brush.linearGradient(0f to c.accent.copy(alpha = .28f), .45f to Color.Transparent, 1f to Color.Transparent), shape))
        Row(Modifier.fillMaxSize().padding(horizontal = if (compact) 22.dp else 30.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text(eyebrow, color = c.accent, fontSize = 10.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                Text(title, color = c.foreground, fontSize = if (compact) 28.sp else 34.sp, lineHeight = if (compact) 34.sp else 42.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(meta, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (description.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(description, color = c.foreground.copy(alpha = .76f), fontSize = 12.sp, lineHeight = 18.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
            }
            if (hasArt && !compact) {
                Spacer(Modifier.width(24.dp))
                Box(Modifier.size(150.dp).clip(if (portrait) CircleShape else RoundedCornerShape(14.dp))
                    .background(c.panel).border(1.dp, c.accent.copy(alpha = .25f), if (portrait) CircleShape else RoundedCornerShape(14.dp))) {
                    AsyncImage(model = coverRequest(artwork), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
fun PrismPageHead(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    val c = LocalPrismColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = c.foreground)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/* No duration column: on a TV the row is scanned for title, album and source, while the
   player owns elapsed/remaining time. The freed width goes to the title. */
@Composable
fun PrismTrackHeader(modifier: Modifier = Modifier, managing: Boolean = false) {
    val c = LocalPrismColors.current
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (managing) Box(Modifier.width(ColIndex)) { PrismCheckmark(false, Modifier.align(Alignment.CenterStart), 16.dp) }
        else Text("#", Modifier.width(ColIndex), color = c.muted, fontSize = 10.sp)
        Spacer(Modifier.width(ColCover + ColGap))
        Text("标题", Modifier.weight(1.5f), color = c.muted, fontSize = 10.sp)
        Text("专辑", Modifier.weight(1f), color = c.muted, fontSize = 10.sp, maxLines = 1)
        Text("来源", Modifier.width(ColSource), color = c.muted, fontSize = 10.sp)
    }
}

/** One row of a real table. `index` is the position in THIS list — see SYSTEM.md §11.2. */
@Composable
fun PrismTrackRow(song: Song, index: Int, onClick: () -> Unit, modifier: Modifier = Modifier, current: Boolean = false,
                  qualityTag: String? = null, onFocus: () -> Unit = {}, onLongPress: (() -> Unit)? = null,
                  selected: Boolean? = null) {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    val emphasis by animateFloatAsState(if (focused) 1f else if (current) .45f else 0f, tween(if (LocalAuraMotion.current) 170 else 0), label = "row light")
    /* A checked row keeps its light even when the remote has moved on, so a selection made
       across a long list is still readable from the top. */
    val lit = if (selected == true) 1f else emphasis
    Row(
        modifier.fillMaxWidth().height(56.dp)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .clip(RoundedCornerShape(10.dp))
            .background(c.accent.copy(alpha = lit * .13f))
            .border(if (focused) 2.dp else 1.dp, if (focused) c.accent else Color.Transparent, RoundedCornerShape(10.dp))
            .tvClickable(onClick, onLongPress)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) Box(Modifier.width(ColIndex)) { PrismCheckmark(selected, Modifier.align(Alignment.CenterStart)) }
        else if (current) Box(Modifier.width(ColIndex)) { NowPlayingBars(c.accent) }
        else Text("%02d".format(index + 1), Modifier.width(ColIndex), color = if (emphasis > 0f) c.accent else c.muted, fontSize = 11.sp)
        CoverArt(song, Modifier.size(ColCover), 8.dp)
        Spacer(Modifier.width(ColGap))
        Column(Modifier.weight(1.5f).padding(end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(song.title, color = c.foreground, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).focusedMarquee(focused))
                if (song.isVip) { Spacer(Modifier.width(6.dp)); VipBadge() }
            }
            Text(song.artist.ifBlank { "未知" }, color = c.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(song.album.ifBlank { "—" }, Modifier.weight(1f), color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.width(ColSource), contentAlignment = Alignment.CenterStart) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformIcon(song.platform, Modifier.size(18.dp))
                if (song.hasMv) {
                    Spacer(Modifier.width(5.dp))
                    Row(Modifier.border(1.dp, c.accent.copy(alpha = .45f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 3.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("mv", Modifier.size(9.dp), c.accent)
                        Spacer(Modifier.width(2.dp))
                        Text("MV", color = c.accent, fontSize = 8.sp, letterSpacing = .4.sp)
                    }
                }
            }
        }
        if (qualityTag != null) {
            Box(Modifier.border(1.dp, c.accent.copy(alpha = .42f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp)) {
                Text(qualityTag, color = c.accent, fontSize = 9.sp, letterSpacing = .6.sp, maxLines = 1)
            }
        }
    }
}

/** A suggestion is a promise: OK starts that search, no second field to confirm. */
@Composable
fun PrismSuggestionRow(term: String, badge: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                       artwork: String = "", subtitle: String = "") {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    val emphasis by animateFloatAsState(if (focused) 1f else 0f, tween(if (LocalAuraMotion.current) 170 else 0), label = "suggestion light")
    Row(
        modifier.fillMaxWidth().height(if (subtitle.isNotBlank() || artwork.isNotBlank()) 54.dp else 46.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(c.accent.copy(alpha = emphasis * .13f))
            .border(if (focused) 2.dp else 1.dp, if (focused) c.accent else Color.Transparent, RoundedCornerShape(10.dp))
            .semantics { contentDescription = "$term，$badge，确定即搜索" }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (artwork.isNotBlank()) Box(Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)).background(c.panel)) {
            Glyph("music", Modifier.align(Alignment.Center).size(17.dp), c.muted)
            AsyncImage(coverRequest(artwork), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else Glyph(if (badge == "历史") "history" else "search", Modifier.size(15.dp), if (emphasis > 0f) c.accent else c.muted)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(term, color = c.foreground, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = c.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(badge, color = c.muted, fontSize = 10.sp)
        Spacer(Modifier.width(12.dp))
        Text(if (emphasis > 0f) "确定搜索" else "", Modifier.width(52.dp), color = c.accent, fontSize = 10.sp, textAlign = TextAlign.End)
    }
}

@Composable
internal fun PrismSearchSuggestionCard(item: SearchSuggestion, type: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    val imageShape = if (type == "artist") CircleShape else RoundedCornerShape(8.dp)
    Column(modifier.keepFocusVisible().auraFocus().clip(RoundedCornerShape(12.dp)).background(c.panel)
        .clickable(onClick = onClick).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(72.dp).clip(imageShape).background(c.accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
            Text(item.title.take(1), color = c.accent, fontSize = 28.sp)
            if (item.artwork.isNotBlank()) AsyncImage(coverRequest(item.artwork), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Text(item.title, color = c.foreground, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.subtitle.ifBlank { when (type) { "artist" -> "搜索歌手作品"; "album" -> "搜索专辑"; else -> "搜索歌单" } },
            color = c.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.badge, color = c.accent, fontSize = 9.sp, maxLines = 1)
    }
}

/* ─────────────────────────────────────────────────────────────────────────────
   The thread.

   The old control was a glass slab with a bar spectrum inside it: a widget with
   its own frame, its own corners, its own little world. This is the opposite —
   one hairline of light lying on the black, and the played half of it is simply
   brighter. There is no track, no box, no corner radius to argue about.

   Two threads at different speeds and periods are drawn on top of each other.
   Their interference is what makes the motion feel liquid instead of like a
   marquee; a single travelling sine reads as a conveyor belt.

   Both paths are built once, twice as wide as the view, with whole cycles across
   the width — so scrolling by exactly one width loops without a seam. Per frame
   the cost is two translates, four clipped strokes and one line: no rebuild, no
   recomposition, no relayout. The phase is read inside the draw lambda, so
   ticking it invalidates the draw phase only.
   ───────────────────────────────────────────────────────────────────────────── */

private fun threadPhases(seed: Int): Pair<Float, Float> {
    val rnd = java.util.Random(seed.toLong() * 7919L + 13L)
    return rnd.nextFloat() * 6.2832f to rnd.nextFloat() * 6.2832f
}

/* Sampling the same formula the path is built from lets the glow sit on the thread at the
   progress edge instead of floating on the centre line. */
private fun threadY(w: Float, h: Float, phases: Pair<Float, Float>, cycles: Int, amp: Float, x: Float): Float {
    val t = (x / w) * 6.2832f * cycles
    val v = sin(t + phases.first) * .74f + sin(2f * t + phases.second) * .26f
    return h * .5f + h * .46f * amp * v
}

private fun buildThread(w: Float, h: Float, phases: Pair<Float, Float>, cycles: Int, amp: Float): Path {
    val steps = 320
    val p = Path()
    for (i in 0..steps) {
        val x = w * 2f * i / steps
        val y = threadY(w, h, phases, cycles, amp, x)
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    return p
}

@Composable
fun WaveGroove(
    progress: Float,
    playing: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
    seed: Int = 0,
    showPlayhead: Boolean = true,
    beat: Int = -1,
) {
    val c = LocalPrismColors.current
    val phase = remember { mutableFloatStateOf(0f) }
    /* We can't hear the music from here without an audio Visualizer in the player process, so
       the ribbon is driven by an envelope built from two incommensurate sines. They never line
       up, which is what stops the motion from reading as a looping conveyor. */
    val energy = remember { mutableFloatStateOf(.55f) }
    val motion = LocalAuraMotion.current
    /* Every new lyric line lands as a swell: the one cue the player already knows about the
       vocal, so the ribbon breathes with the song instead of with the clock. */
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(beat, playing, motion) {
        if (!playing || !motion || beat < 0) return@LaunchedEffect
        pulse.snapTo(1f)
        pulse.animateTo(0f, tween(900, easing = LinearEasing))
    }
    LaunchedEffect(playing, motion) {
        if (!playing || !motion) return@LaunchedEffect
        var last = 0L
        var t = 0f
        while (true) withFrameNanos { now ->
            if (last != 0L) {
                val dt = (now - last) / 1_000_000_000f
                t += dt
                phase.floatValue = (phase.floatValue + dt / 9f) % 1f
                energy.floatValue = (.5f + .5f * sin(t * 2.3f) * sin(t * .9f + 1.1f)).coerceIn(0f, 1f)
            }
            last = now
        }
    }

    Box(
        modifier.height(height).clipToBounds().drawWithCache {
            val w = size.width
            val h = size.height
            val phasesA = threadPhases(seed)
            val a = buildThread(w, h, phasesA, 3, .60f)
            val b = buildThread(w, h, threadPhases(seed + 41), 5, .34f)
            val gold = Brush.horizontalGradient(
                listOf(Color(0xFFF0D9A4), Color(0xFFC79E52), Color(0xFFE4C489)),
            )
            val brightA = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
            val brightB = Stroke(1.dp.toPx(), cap = StrokeCap.Round)
            onDrawBehind {
                val swell = (energy.floatValue + pulse.value * .5f).coerceIn(0f, 1f)
                val sy = .5f + .55f * swell
                val dxA = -phase.floatValue * w
                val dxB = -phase.floatValue * w * 1.65f
                val edge = w * progress
                /* The threads breath around the centre line. Without this, a travelling sine
                   reads as a static shape sliding sideways. */
                scale(1f, sy, pivot = Offset(0f, h / 2f)) {
                    translate(dxA, 0f) {
                        /* The halo first, wide and almost invisible: it is what makes a 1px line
                           look lit rather than drawn. */
                        clipRect(0f, 0f, edge - dxA, h) {
                            drawPath(a, c.accent.copy(alpha = .10f), style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
                            drawPath(a, gold, style = brightA)
                            drawPath(b, c.accent.copy(alpha = .30f), style = brightB)
                        }
                        clipRect(edge - dxA, 0f, w - dxA, h) {
                            drawPath(a, c.accent.copy(alpha = .13f), style = brightA)
                            drawPath(b, c.accent.copy(alpha = .06f), style = brightB)
                        }
                    }
                    translate(dxB, 0f) {
                        clipRect(0f, 0f, edge - dxB, h) { drawPath(b, c.accent.copy(alpha = .42f), style = brightB) }
                        clipRect(edge - dxB, 0f, w - dxB, h) { drawPath(b, c.accent.copy(alpha = .05f), style = brightB) }
                    }
                }
                /* The lit thread's clip ends at edge - dxA in path space; read its height there
                   so the light rides the wave as it swells and travels. */
                val waveY = threadY(w, h, phasesA, 3, .60f, (edge - dxA).coerceIn(0f, w * 2f))
                val glowCenter = Offset(edge.coerceIn(0f, w), (h / 2f + (waveY - h / 2f) * sy).coerceIn(0f, h))
                val glowRadius = (14.dp.toPx() * (.8f + .5f * swell))
                drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = .55f), c.accent.copy(alpha = .12f), Color.Transparent), center = glowCenter, radius = glowRadius), radius = glowRadius, center = glowCenter)
                drawLine(c.foreground.copy(alpha = .9f), Offset((glowCenter.x - 3.dp.toPx()).coerceAtLeast(0f), glowCenter.y), Offset((glowCenter.x + 3.dp.toPx()).coerceAtMost(w), glowCenter.y), 1.5.dp.toPx(), StrokeCap.Round)
                if (showPlayhead && progress > 0f) {
                    drawLine(c.accent.copy(alpha = .55f), Offset(edge, 0f), Offset(edge, h), 1.dp.toPx())
                }
            }
        },
    )
}

@Composable
internal fun PrismConfigChoice(title: String, subtitle: String, icon: String, onClick: () -> Unit,
                               modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.heightIn(min = 78.dp).auraFocus(shape, scale = 1.015f).clip(shape)
        .background(c.background.copy(alpha = .55f))
        .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, Modifier.size(24.dp), c.accent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = c.foreground, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = c.accent, fontSize = 23.sp)
    }
}

@Composable
internal fun PrismConfirmDialog(
    title: String, message: String, confirmLabel: String,
    onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    val c = LocalPrismColors.current
    val cancelFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; cancelFocus.requestFocus() }
        Column(Modifier.width(420.dp).clip(RoundedCornerShape(20.dp)).background(c.panel)
            .border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(24.dp)) {
            Text(title, color = c.foreground, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(message, color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PrismAction("取消", onDismiss, Modifier.widthIn(min = 96.dp).focusRequester(cancelFocus))
                PrismAction(confirmLabel, onConfirm, Modifier.widthIn(min = 96.dp), primary = true)
            }
        }
    }
}

@Composable
fun PrismContextMenu(title: String, subtitle: String, onDismiss: () -> Unit, initialFocus: FocusRequester? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalPrismColors.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(initialFocus) { repeat(2) { withFrameNanos { } }; initialFocus?.requestFocus() }
        Column(Modifier.width(560.dp).clip(RoundedCornerShape(24.dp)).background(c.panel)
            .border(1.dp, c.line, RoundedCornerShape(24.dp)).padding(26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = c.foreground, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, color = c.muted, fontSize = 12.sp)
                }
                AuraIconButton("close", "关闭", onDismiss, Modifier.size(38.dp))
            }
            content()
        }
    }
}
