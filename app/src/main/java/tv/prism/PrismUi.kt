package tv.prism

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import coil.request.ImageRequest
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

/* 进度条只有一套语法：已播是 accent→白 的横向渐变，未播是前景 α.15，接缝散开一圈光。
   三处（播放页主轨、沉浸底条、迷你胶囊）以前各自发明了一遍，差别其实只有「谁能被聚焦」
   和「有没有缓冲」；这两个差别用参数表达，形状本身不再分叉。
   主轨额外叠的彗尾、呼吸点、扫光留在 ProgressRail 里——那是「可聚焦」这一档的加料。 */
@Composable
internal fun AuraProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    height: Dp = 2.dp,
    buffered: (() -> Float)? = null,
    seam: Boolean = true,
) {
    val c = LocalPrismColors.current
    Canvas(modifier.fillMaxWidth().height(height)) {
        val y = size.height / 2f
        val end = size.width * progress().coerceIn(0f, 1f)
        drawRect(c.foreground.copy(alpha = .15f))  // 未播段要读得出「还剩多少」
        buffered?.let {
            val inHand = size.width * it().coerceIn(0f, 1f)
            if (inHand > end) drawRect(c.foreground.copy(alpha = .26f), size = Size(inHand, size.height))
        }
        if (end <= 0f) return@Canvas
        drawRect(
            Brush.horizontalGradient(
                listOf(c.accent.copy(alpha = .62f), lerp(c.accent, Color.White, .34f)),
                startX = 0f, endX = end.coerceAtLeast(1f),
            ),
            size = Size(end, size.height),
        )
        /* 接缝散开一圈光：这条线是「亮着」的，不是一个色块压在底边。 */
        if (seam) {
            val bloom = 9.dp.toPx()
            drawCircle(
                Brush.radialGradient(
                    listOf(c.accent.copy(alpha = .42f), c.accent.copy(alpha = .12f), Color.Transparent),
                    center = Offset(end, y), radius = bloom,
                ),
                radius = bloom, center = Offset(end, y),
            )
        }
    }
}

@Composable
internal fun coverRequest(url: String, sampleSize: Int? = null): ImageRequest {
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

@Composable fun AuraClock(modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now)),
        modifier = modifier, color = c.foreground.copy(alpha = .7f), fontSize = 11.sp, letterSpacing = 1.2.sp, maxLines = 1)
}

@Composable fun AuraRemotePill(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalPrismColors.current
    val shape = RoundedCornerShape(50)
    Row(modifier.height(30.dp).auraFocus(shape, 1f)
        .clip(shape)
        .border(1.dp, c.accent.copy(alpha = .24f), shape)
        .background(c.accent.copy(alpha = .035f))
        .clickable(onClick = onClick)
        .padding(horizontal = 8.5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.5.dp)) {
        Glyph("remote", Modifier.size(13.dp), c.muted)
        Text("手机遥控", color = c.muted, fontSize = 11.sp, maxLines = 1)
    }
}

/* Disabled actions stay in place at 42% alpha instead of vanishing, so the row
   layout never shifts under the remote. */
@Composable fun PrismAction(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    primary: Boolean = false, live: Boolean = false,
    enabled: Boolean = true, onLongPress: (() -> Unit)? = null,
    icon: String? = null,
) {
    val c = LocalPrismColors.current
    Box(modifier.heightIn(min = 42.dp)
        .then(if (enabled) Modifier.auraFocus(RoundedCornerShape(50), ring = if (primary) c.foreground else null) else Modifier)
        .clip(RoundedCornerShape(50)).background(if (primary) c.accent else c.panel)
        .then(if (enabled) Modifier.tvClickable(onClick, onLongPress) else Modifier.focusProperties { canFocus = false })
        .alpha(if (enabled) 1f else .42f)
        .padding(horizontal = 18.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (live) NowPlayingBars(if (primary) c.onAccent else c.accent, height = 13.dp, barWidth = 2.5.dp)
            if (icon != null) Glyph(icon, Modifier.size(18.dp), if (primary) c.onAccent else c.accent)
            Text(label, color = if (primary) c.onAccent else c.foreground, fontSize = 16.sp, fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
        }
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
    Box(modifier.heightIn(min = 36.dp).auraFocus(RoundedCornerShape(6.dp), 1.01f).clip(RoundedCornerShape(6.dp))
        .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (active) c.accent else c.muted, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable fun PrismNav(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, dense: Boolean = false, quiet: Boolean = false) {
    val c = LocalPrismColors.current
    val idle = if (quiet) c.muted.copy(alpha = .62f) else c.muted
    Column(modifier.widthIn(min = 64.dp).auraFocus(RoundedCornerShape(50), 1.02f)
        .clip(RoundedCornerShape(50)).clickable(onClick = onClick)
        .semantics { this.selected = selected }
        .padding(horizontal = if (dense) 14.dp else 18.dp, vertical = if (dense) 6.dp else 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = if (selected) c.foreground else idle, fontSize = if (dense) 15.sp else 16.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
            Spacer(Modifier.height(if (dense) 4.dp else 5.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) c.accent else Color.Transparent, CircleShape))
        }
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

/**
 * The record. Label carries the artwork when there is one; otherwise a gold plate
 * with the initial. A hairline sits just outside the disc — that offset line is the
 * whole difference between "a circle" and "a record on a plinth".
 */
@Composable internal fun AuraOrbit(modifier: Modifier = Modifier, active: Boolean = true) {
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
internal fun CoverArt(song: Song?, modifier: Modifier = Modifier, radius: androidx.compose.ui.unit.Dp = 12.dp) {
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

@Composable
internal fun PrismConfirmDialog(
    title: String, message: String, confirmLabel: String,
    onDismiss: () -> Unit, onConfirm: () -> Unit,
    confirmIcon: String? = null,
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
                PrismAction(confirmLabel, onConfirm, Modifier.widthIn(min = 96.dp), primary = true, icon = confirmIcon)
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
                    Text(subtitle, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                AuraIconButton("close", "关闭", onDismiss, Modifier.size(38.dp))
            }
            content()
        }
    }
}
