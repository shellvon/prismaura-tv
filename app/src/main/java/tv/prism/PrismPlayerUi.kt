package tv.prism

import android.app.Activity
import android.graphics.drawable.BitmapDrawable
import androidx.palette.graphics.Palette
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

private fun time(ms: Long): String = "%02d:%02d".format(ms / 60_000, (ms / 1000) % 60)

@Composable
private fun rememberLyricPosition(songKey: String?, position: Long, playing: Boolean, readPosition: () -> Long): State<Long> {
    val readCurrent by rememberUpdatedState(readPosition)
    return key(songKey) {
        produceState(initialValue = position, position, playing) {
            if (!playing) value = position
            while (playing) withFrameNanos { value = readCurrent() }
        }
    }
}

/* Cover colours, one palette per URL; concurrent requests share one pending task. */
private object AmbienceCache {
    private val cache = object : LinkedHashMap<String, List<Int>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Int>>?) = size > 24
    }
    private val pending = HashMap<String, Deferred<List<Int>>>()
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
                task.invokeOnCompletion {
                    synchronized(this@AmbienceCache) { if (pending[url] === task) pending.remove(url) }
                }
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

@Composable
private fun AuraAmbience(song: Song?, centerX: Float, centerY: Float, modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    val raw = rememberAmbience(song)
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
    /* No artwork, no palette: light the room with the app's own lamp instead of a per-song
       random hue, so an artwork-less track still belongs to this player. */
    val lamp = listOf(
        lerp(c.accent, Color.White, .22f),
        lerp(c.accent, Color.Black, .12f),
        c.accent,
    )
    /* 每个采样色先压到 L≤0.16 再当光用：原样的亮封面会把歌词压到 4.3:1。
       灯芯单独放行到 L≤0.30 —— 它只盖住封面自己那一块，碰不到歌词。 */
    val base = stops.ifEmpty { lamp }.map { dimTo(it) }
    val core by animateColorAsState(
        vivid(liftTo(dimTo(stops.ifEmpty { lamp }.first(), .34f), .24f), 1.3f),
        tween(if (motion) 1600 else 0), label = "ambience core",
    )
    val c0 by animateColorAsState(base.getOrElse(0) { c.accent }, tween(if (motion) 1600 else 0), label = "ambience a0")
    val c1 by animateColorAsState(liftTo(base.getOrElse(1) { base[0] }, .09f), tween(if (motion) 1600 else 0), label = "ambience a1")
    val c2 by animateColorAsState(liftTo(base.getOrElse(2) { base[0] }, .09f), tween(if (motion) 1600 else 0), label = "ambience a2")
    val phase = if (motion) rememberInfiniteTransition(label = "ambience drift").animateFloat(
        0f, 1f, infiniteRepeatable(tween(44_000, easing = LinearEasing)), label = "ambience phase",
    ) else null
    Canvas(modifier) {
        /* 三段式的径向渐变会在衰减到一半时留一道能看见的圆边；按指数采样到 0.92 再收到透明，
           光才像从某个地方散出来的，而不是贴了一个圆。 */
        fun glow(color: Color, alpha: Float, cx: Float, cy: Float, r: Float) {
            val falloff = List(7) { i ->
                val t = i / 6.5f
                color.copy(alpha = alpha * exp(-3.2f * t * t))
            } + Color.Transparent
            drawRect(
                Brush.radialGradient(falloff, center = Offset(size.width * cx, size.height * cy), radius = size.width * r),
            )
        }
        val drift = .012f * cos((phase?.value ?: 0f) * 2f * PI.toFloat())
        /* 主灯分两层：灯芯贴住封面，外面再套一层大半径的扩散。一层画不出「光从封面里散出来」，
           只会得到一个边缘看得见的圆。 */
        /* 光贴着卡片漏出来：半径只比封面大一点，溢出约一百像素就落进封面自己的模糊里。
           铺到半屏就变成给整间屋子染色，图也没了。 */
        glow(core, .72f, centerX + drift, centerY, .21f)        // 卡片辉光：贴着封面边缘
        glow(c0, .13f, centerX + drift, centerY + .02f, .62f)   // 底光：只负责把角落填一点色
        glow(c1, .18f, .72f, .52f - drift, .42f)                // 副灯：只在歌词那一栏积一滩
        glow(c2, .12f, .36f, 1.04f, .48f)                       // 地面：托住控制条
    }
}

private data class LyricSizes(val text: Int, val line: Int, val support: Int)

/* 熄屏稳定后整个播放页会离场（黑屏下不再合成），醒回来是一次全新的组合。入场镜头若
   跟着再来一遍，封面就会从屏幕中间飞回左边——用户看到的是「位置被重置了」。
   入场只属于本次启动的第一次进入播放页。 */
private object SleeveEntrance { var done = false }

private fun lyricSizes(level: String, immersion: Boolean): LyricSizes = when (level) {
    "小" -> if (immersion) LyricSizes(38, 48, 15) else LyricSizes(28, 37, 12)
    "大" -> if (immersion) LyricSizes(68, 84, 22) else LyricSizes(46, 60, 17)
    "特大" -> if (immersion) LyricSizes(80, 98, 24) else LyricSizes(54, 70, 19)
    else -> if (immersion) LyricSizes(54, 68, 19) else LyricSizes(38, 52, 15)
}

/* 模糊封面只当氛围，不当画面。遮罩左轻右重：封面那一侧留得下图，歌词那一侧压到能读字。
   注意 Android 12 以下 Modifier.blur 是空操作，真正让它糊的是 128px 的解码尺寸。 */
private fun coverScrim(colors: PrismColors): Brush =
    Brush.horizontalGradient(listOf(.56f, .76f, .93f).map { colors.background.copy(alpha = it) })

/* Ceiling on sampled colours before they become light: 0.16 keeps lyrics above 5.9:1 on
   any cover, where the raw sample lands at 4.3:1. */

/* A night-time sleeve samples to a near-black blue; used as-is the light equals the room and
   disappears. Lift it to a floor before drawing, keeping the hue. */
/* A sleeve that averages out to grey still has a hue; pushing saturation is what keeps the
   light from reading as "the blurred photo got brighter". */
private fun vivid(c: Color, k: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[1] = (hsv[1] * k).coerceAtMost(1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun liftTo(c: Color, floor: Float): Color {
    if (c.luminance() >= floor) return c
    fun at(k: Float) = Color(
        (c.red * k).coerceAtMost(1f), (c.green * k).coerceAtMost(1f), (c.blue * k).coerceAtMost(1f),
    )
    var lo = 0f
    var hi = 8f
    repeat(16) {
        val m = (lo + hi) / 2f
        if (at(m).luminance() > floor) hi = m else lo = m
    }
    return at(lo)
}
private fun dimTo(c: Color, ceiling: Float = .16f): Color {
    if (c.luminance() <= ceiling) return c
    var lo = 0f
    var hi = 1f
    repeat(12) {
        val m = (lo + hi) / 2f
        if (Color(c.red * m, c.green * m, c.blue * m).luminance() > ceiling) hi = m else lo = m
    }
    return Color(c.red * lo, c.green * lo, c.blue * lo)
}

internal val lyricFontLevels = listOf("小", "标准", "大", "特大")

private fun hueFor(seed: String): Float = (abs(seed.hashCode()) % 360).toFloat()

private fun tierLabel(tier: String): String = when (tier) {
    "low" -> "流畅"; "standard" -> "标准"; "high" -> "高品"; "super" -> "无损优先"
    "flac" -> "无损"; "flac24bit" -> "24-bit 无损"; "hires" -> "Hi-Res"; "dolby" -> "杜比"
    "atmos" -> "Atmos"; "atmos_plus" -> "Atmos+"; else -> tier
}

private fun calibrationText(ms: Long): String =
    if (ms == 0L) "0.0s" else "%+.1fs".format(ms / 1000f)

/* The plate is painted first and the photo lands on top of it, so a sleeve that is still
   downloading — or that never arrives — is a printed cover, not a black rectangle. Skipping
   tracks is the case that matters: the next song's plate is there on the same frame. */
@Composable
private fun CoverPlate(song: Song?, modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    val shape = RoundedCornerShape(14.dp)
    val url = song?.artwork.orEmpty()
    val hasArt = url.startsWith("http") || url.startsWith("content")
    val hue = hueFor(song?.key ?: song?.title.orEmpty())
    var loaded by remember(url) { mutableStateOf(false) }
    val photo by animateFloatAsState(if (loaded) 1f else 0f, tween(if (motion) 320 else 0), label = "sleeve photo")
    Box(modifier.clip(shape).background(c.panel).border(1.dp, c.foreground.copy(alpha = .07f), shape)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(
                listOf(Color.hsv(hue, .18f, .24f), Color.hsv(hue, .12f, .13f)),
                center = Offset(size.width * .2f, size.height * .08f), radius = size.maxDimension * 1.1f,
            ))
            var y = 3.dp.toPx()
            while (y < size.height) {
                drawLine(c.foreground.copy(alpha = .014f), Offset(0f, y), Offset(size.width, y), 1f)
                y += 4.dp.toPx()
            }
        }
        /* The plate prints the title's initial, so it must leave as the photo arrives: fading a
           photo in *over* the glyph let the letter ghost through for a third of a second on
           every track change. Two layers, one crossfade. */
        Column(Modifier.fillMaxSize().padding(20.dp).graphicsLayer { alpha = 1f - photo },
            verticalArrangement = Arrangement.SpaceBetween) {
            Text(
                song?.title.orEmpty().take(1).ifBlank { "—" },
                color = Color.hsv(hue, .30f, .72f),
                fontSize = 92.sp, lineHeight = 92.sp, fontFamily = FontFamily.Serif,
            )
            Column {
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.foreground.copy(alpha = .14f)))
                Spacer(Modifier.height(10.dp))
                Text(
                    listOfNotNull(
                        song?.title.orEmpty().takeIf { it.isNotBlank() },
                        song?.album.orEmpty().takeIf { it.isNotBlank() } ?: "无专辑信息",
                    ).joinToString("\n"),
                    color = c.muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (hasArt) {
            AsyncImage(
                coverRequest(url), null,
                Modifier.fillMaxSize().graphicsLayer { alpha = photo },
                contentScale = ContentScale.Crop,
                onState = { loaded = it is AsyncImagePainter.State.Success },
            )
        }
    }
}

@Composable
fun PrismPlayer(
    song: Song?, lyrics: List<LyricLine>, lyricStatus: String, position: Long, duration: Long,
    playing: Boolean, playMode: PlaybackMode, favorite: Boolean, quality: String, playingQuality: String,
    queueIndex: Int, queueSize: Int, status: String,
    streamFormat: String,
    hasMv: Boolean,
    mvResolving: Boolean = false,
    mvOpen: Boolean = false,
    overlayOpen: Boolean = false,
    readPosition: () -> Long,
    onScreenOff: () -> Unit,
    onBack: () -> Unit, onPlay: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onShuffle: () -> Unit, onFavorite: () -> Unit, onQueue: () -> Unit, onMv: () -> Unit,
    onModeStep: (Int) -> Unit = {},
    onRadioMenu: () -> Unit = {},
    onRadioToggle: () -> Unit = {},
    radioActive: Boolean = false,
    radioLoading: Boolean = false,
    onSeek: (Long) -> Unit,
    onArtist: (() -> Unit)? = null,
    onAlbum: (() -> Unit)? = null,
    tiers: List<String> = emptyList(),
    ladder: List<String> = emptyList(),
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
    val motion = LocalAuraMotion.current
    val playingTier = playingQuality.ifBlank { quality }
    val currentSeek by rememberUpdatedState(onSeek)
    val artistName = song?.artist.orEmpty().takeIf { it.isNotBlank() && it != "未知歌手" }
    val albumName = song?.album.orEmpty().takeIf { it.isNotBlank() }
    val sourceSong = playbackSource ?: song
    val stream = streamFormat.ifBlank { tierLabel(playingTier) }
    /* The room shows the tier in words; codec and bitrate live in the ⋯ header. */
    val qualityText = tierLabel(playingTier).takeIf { it != playingTier } ?: stream

    var immersive by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf("") }
    var wakeKey by remember { mutableStateOf<Key?>(null) }
    var progressFocused by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var l2Tick by remember { mutableIntStateOf(0) }
    var l2Level by remember { mutableIntStateOf(0) }
    var l2Bar by remember { mutableStateOf(true) }
    var l2SeekText by remember { mutableStateOf("") }
    var l2SeekSeq by remember { mutableIntStateOf(0) }

    val parkFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val prevFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    val favFocus = remember { FocusRequester() }
    val queueFocus = remember { FocusRequester() }
    val dotsFocus = remember { FocusRequester() }
    val mvFocus = remember { FocusRequester() }
    val immersiveFocus = remember { FocusRequester() }
    val offFocus = remember { FocusRequester() }
    val l2Focus = remember { FocusRequester() }

    val rawLyricPosition = if (lyrics.isNotEmpty()) rememberLyricPosition(song?.key, position, playing, readPosition)
        else rememberUpdatedState(position)
    // Calibration affects lyric timing only; transport controls use the playback clock.
    val lyricPosition = remember(rawLyricPosition, lyricOffsetMs) {
        derivedStateOf { rawLyricPosition.value - lyricOffsetMs }
    }
    val active by remember(lyrics, lyricPosition) {
        derivedStateOf(structuralEqualityPolicy()) { lyrics.indexOfLast { it.timeMs <= lyricPosition.value } }
    }

    val idleTimeout = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        10_000L, containsIcons = true, containsText = true, containsControls = true,
    ) ?: 10_000L
    val needsAttention = status.startsWith("正在解析") || status.startsWith("正在尝试") ||
        status.startsWith("正在打开") || status.startsWith("正在加载") || status.startsWith("MV") ||
        status.contains("失败") || status.contains("不可用")

    LaunchedEffect(interaction, playing, lyrics.isEmpty(), immersive, menuOpen, mvOpen, overlayOpen, needsAttention, idleTimeout) {
        controlsVisible = true
        if (playing && lyrics.isNotEmpty() && !immersive && !menuOpen && !mvOpen && !overlayOpen && !needsAttention) {
            delay(idleTimeout)
            controlsVisible = false
        }
    }
    val controlsAlpha = animateFloatAsState(
        if (controlsVisible) 1f else 0f, tween(if (motion) 350 else 0), label = "player chrome",
    )
    // Reveal the cover centrally before moving it beside the lyrics.
    val entrance = remember { Animatable(if (motion && !SleeveEntrance.done) 0f else 1f) }
    LaunchedEffect(motion) {
        if (motion && entrance.value < 1f) {
            delay(120)
            entrance.animateTo(1f, keyframes {
                durationMillis = 1180
                0f at 0
                .40f at 430
                1f at 1180
            })
        } else entrance.snapTo(1f)
        SleeveEntrance.done = true
    }

    var menuWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(menuOpen) {
        if (menuOpen) menuWasOpen = true
        else if (menuWasOpen) {
            withFrameNanos { }
            runCatching { dotsFocus.requestFocus() }
        }
    }
    LaunchedEffect(immersive) {
        if (immersive) {
            // Wait for the immersion focus target to attach.
            repeat(4) {
                withFrameNanos { }
                if (runCatching { l2Focus.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
            }
        }
    }
    LaunchedEffect(mvOpen, overlayOpen, controlsVisible, immersive, menuOpen) {
        if (mvOpen || overlayOpen || menuOpen || immersive) return@LaunchedEffect
        // Acquire the stable root immediately, including when returning from a detail page.
        parkFocus.requestAfterScroll { }
        val target = if (controlsVisible) playFocus else parkFocus
        // Touch mode can defer focus until the first remote key.
        repeat(30) {
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(120)
        }
    }
    LaunchedEffect(immersive, l2Tick) {
        if (!immersive) return@LaunchedEffect
        l2Bar = true
        l2Level = 0
        delay(3000)
        l2Bar = false
        l2Level = 1
        delay(5000)
        l2Level = 2
    }
    LaunchedEffect(l2SeekSeq) {
        if (l2SeekSeq == 0) return@LaunchedEffect
        delay(1_200)
        l2SeekText = ""
    }

    fun seekBy(delta: Long) {
        if (duration <= 0) return
        currentSeek((rawLyricPosition.value + delta).coerceIn(0, duration))
    }

    fun immersiveSeek(delta: Long) {
        if (duration <= 0) return
        val from = rawLyricPosition.value
        val to = (from + delta).coerceIn(0, duration)
        if (to == from) return
        currentSeek(to)
        val seconds = abs(to - from) / 1000
        l2SeekText = if (to < from) "‹ $seconds 秒" else "$seconds 秒 ›"
        l2SeekSeq++
    }

    val controlSemantics = if (controlsVisible) Modifier else Modifier.clearAndSetSemantics { }
    val sizes = lyricSizes(lyricFontLevel, immersion = false)
    val immersionSizes = lyricSizes(lyricFontLevel, immersion = true)
    val padX = 32.dp
    val padTop = 18.dp

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .focusRequester(parkFocus)
            .onPreviewKeyEvent { event ->
                if (wakeKey == event.key) {
                    if (event.type == KeyEventType.KeyUp) wakeKey = null
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (immersive) {
                    l2Tick++
                    when (event.key) {
                        Key.Back, Key.Escape -> { immersive = false; true }
                        Key.DirectionLeft -> { immersiveSeek(-10_000); true }
                        Key.DirectionRight -> { immersiveSeek(10_000); true }
                        Key.DirectionUp, Key.DirectionDown -> true
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            onPlay(); wakeKey = event.key; true
                        }
                        else -> false
                    }
                } else {
                    if (!controlsVisible) {
                        if (event.key in setOf(
                                Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Tab, Key.Spacebar,
                            )) {
                            controlsVisible = true
                            interaction++
                            wakeKey = event.key
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (controlsVisible) interaction++
                    when {
                        event.key in setOf(Key.Back, Key.Escape) -> { onBack(); true }
                        else -> false
                    }
                }
            }
            .focusable()
            .pointerInput(Unit) { detectTapGestures { if (!immersive) { controlsVisible = true; interaction++ } } },
    ) {
        val w = maxWidth
        val h = maxHeight
        /* While the lyric request is still out the two-column layout stands as it is; the layout
           only moves once the answer is known to be "no lyrics", never while it is still coming. */
        val lyricPending = lyricStatus.contains("加载中") || lyricStatus.contains("正在")
        val noLyrics = lyrics.isEmpty() && !lyricPending
        val sleeveBig = minOf(w * .29f, h * .50f)
        // Reserve measured title height without changing the column width.
        val columnWidth = minOf(w * .30f, h * .52f)
        val identityTop = h * .12f
        val sleeveSmall = minOf(w * .292f, h * .49f)
        val artSize by animateDpAsState(
            if (noLyrics) sleeveBig else sleeveSmall,
            tween(if (motion) 460 else 0, easing = FastOutSlowInEasing), label = "sleeve",
        )
        val textMax = minOf(w * .33f, 420.dp)
        var textPx by remember { mutableIntStateOf(0) }
        val textWidth = with(LocalDensity.current) { textPx.toDp() }
        val lockupText = if (textWidth > 0.dp) textWidth else textMax * .7f
        /* The sleeve breathes: it sits a screen-fraction in from the edge, not glued to it. */
        val identityMargin = w * .146f
        val identityX by animateDpAsState(
            if (noLyrics) (w - (sleeveBig + 40.dp + lockupText)) / 2 else identityMargin,
            tween(if (motion) 460 else 0, easing = FastOutSlowInEasing), label = "identity x",
        )
        val identityY by animateDpAsState(
            if (noLyrics) h * .44f - sleeveBig / 2 else identityTop,
            tween(if (motion) 460 else 0, easing = FastOutSlowInEasing), label = "identity y",
        )
        val freeLeft = identityMargin + columnWidth
        val freeRight = w - padX
        val freeWidth = (freeRight - freeLeft).coerceAtLeast(160.dp)
        val lyricWidth = minOf(freeWidth * .88f, w * .47f)
        val lyricLeft = freeLeft + (freeWidth - lyricWidth) / 2
        val lyricTop = h * .16f
        val lyricHeight = h * .62f

        /* 房间的底子还是这张封面：模糊后铺满，光晕叠在它上面。两者都不能省——只留模糊图，
           页脚是一块能认出内容的糊影；只留光，房间又空得没有来处。 */
        Box(Modifier.fillMaxSize().background(c.background))
        val artUrl = song?.artwork.orEmpty()
        if (artUrl.startsWith("http") || artUrl.startsWith("content")) {
            /* 换歌时上一张先留在原地，新图解码完再换上，避免中间闪一帧黑。 */
            var wash by remember { mutableStateOf<Painter?>(null) }
            AsyncImage(
                coverRequest(artUrl, 128), null,
                Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = 1.10f; scaleY = 1.10f }
                    .blur(30.dp)
                    .graphicsLayer { alpha = .42f },
                contentScale = ContentScale.Crop,
                placeholder = wash,
                onSuccess = { wash = it.painter },
            )
            Box(Modifier.fillMaxSize().background(coverScrim(c)))
        }
        AuraAmbience(
            song,
            centerX = (identityX + artSize / 2) / w,
            centerY = (identityY + artSize / 2) / h,
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = padX, vertical = padTop)
                .graphicsLayer { alpha = controlsAlpha.value }.then(controlSemantics),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AuraBrand()
            Spacer(Modifier.width(18.dp))
            Text("正在聆听", color = c.muted, fontSize = 12.sp)
            if (queueSize > 0) {
                Spacer(Modifier.width(10.dp))
                Text("%02d / %d".format(queueIndex + 1, queueSize), color = c.muted.copy(alpha = .7f), fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            val chromeDown = Modifier.focusProperties { down = railFocus }
            if (hasMv) {
                PlayerIcon("mv", if (mvResolving) "正在解析 MV" else "MV", { onMv() },
                    Modifier.focusRequester(mvFocus).then(chromeDown), onFocused = { hint = "观看 MV" },
                    text = if (mvResolving) "…" else "MV")
                Spacer(Modifier.width(10.dp))
            }
            PlayerIcon("expand", "沉浸", { immersive = true; interaction++ },
                Modifier.focusRequester(immersiveFocus).then(chromeDown),
                onFocused = { hint = "沉浸播放 · 歌词占满屏幕" })
            Spacer(Modifier.width(10.dp))
            PlayerIcon("bulbOff", "熄屏", onScreenOff,
                Modifier.focusRequester(offFocus).then(chromeDown),
                onFocused = { hint = "熄屏播放 · 确定或返回恢复" })
        }

        val textAlpha: () -> Float = {
            val settle = ((entrance.value - .44f) / .56f).coerceIn(0f, 1f)
            (settle * settle * (3f - 2f * settle) * 1.3f).coerceIn(0f, 1f)
        }

        val sleeveMotion = Modifier.graphicsLayer {
            val appear = (entrance.value / .40f).coerceIn(0f, 1f)
            val settle = ((entrance.value - .40f) / .60f).coerceIn(0f, 1f)
            val eased = settle * settle * (3f - 2f * settle)
            translationX = (w / 2 - (identityX + artSize / 2)).toPx() * (1f - eased)
            translationY = (h / 2 - (identityY + artSize / 2)).toPx() * (1f - eased)
            val s = .94f + .06f * appear
            scaleX = s
            scaleY = s
            alpha = appear
        }

        if (noLyrics) {
            Row(
                Modifier.offset(x = identityX, y = identityY).height(artSize),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(artSize).then(sleeveMotion)) { CoverPlate(song, Modifier.fillMaxSize()) }
                Spacer(Modifier.width(40.dp))
                IdentityText(
                    title = song?.title ?: "音乐，即将响起",
                    artistName = artistName, stream = qualityText,
                    platform = sourceSong?.platform,
                    status = lyricStatus, stacked = true, textAlpha = textAlpha,
                    modifier = Modifier.widthIn(max = textMax).onSizeChanged { textPx = it.width },
                )
            }
        } else {
            Column(Modifier.offset(x = identityX, y = identityY).width(columnWidth)) {
                Box(Modifier.size(artSize).then(sleeveMotion)) { CoverPlate(song, Modifier.fillMaxSize()) }
                Spacer(Modifier.height(14.dp))
                IdentityText(
                    title = song?.title ?: "音乐，即将响起",
                    artistName = artistName, stream = qualityText,
                    platform = sourceSong?.platform,
                    status = "", stacked = false, textAlpha = textAlpha,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (!noLyrics) {
            RollingLyrics(
                lyrics, active, lyricStatus, lyricPosition,
                sizes.text, sizes.line, sizes.support, subLyrics,
                Modifier.offset(x = lyricLeft, y = lyricTop).width(lyricWidth).height(lyricHeight)
                    .graphicsLayer {
                        val settle = ((entrance.value - .44f) / .56f).coerceIn(0f, 1f)
                        val eased = settle * settle * (3f - 2f * settle)
                        alpha = eased
                        translationX = 14.dp.toPx() * (1f - eased)
                        translationY = 18.dp.toPx() * (1f - eased)
                    },
                centered = true, fade = .20f,
            )
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .graphicsLayer { alpha = controlsAlpha.value }.then(controlSemantics)
                .padding(start = padX, end = padX, bottom = 14.dp),
        ) {
                Box(Modifier.fillMaxWidth().height(26.dp).focusRequester(railFocus)
                    .onFocusChanged {
                        progressFocused = it.isFocused
                        if (it.isFocused) hint = "← →  快退 / 快进 10 秒"
                    }
                    .pointerInput(duration) {
                        detectTapGestures { if (duration > 0 && size.width > 0) currentSeek((duration * (it.x / size.width).coerceIn(0f, 1f)).toLong()) }
                    }
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key in setOf(Key.DirectionLeft, Key.DirectionRight)) {
                            seekBy(if (e.key == Key.DirectionLeft) -10_000 else 10_000)
                            true
                        } else false
                    }
                    .focusProperties { down = playFocus; up = when { hasMv -> mvFocus; else -> offFocus } }
                    .semantics {
                        contentDescription = "播放进度"
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f, 0f..1f,
                        )
                        setProgress { v ->
                            if (duration > 0) { currentSeek((duration * v.coerceIn(0f, 1f)).toLong()); true } else false
                        }
                    }
                    .focusable(),
            ) {
                ProgressRail(
                    progress = { if (duration > 0) (rawLyricPosition.value.toFloat() / duration).coerceIn(0f, 1f) else 0f },
                    focused = progressFocused,
                    playing = playing && controlsVisible,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ClockText(rawLyricPosition, duration, forward = true)
                Text(
                    if (needsAttention) status else hint,
                    color = if (needsAttention) c.muted else c.accent.copy(alpha = .58f),
                    fontSize = 11.sp, letterSpacing = 1.1.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                )
                ClockText(rawLyricPosition, duration, forward = false)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    PlayerIcon(
                        if (favorite) "heartFilled" else "heart",
                        if (favorite) "取消收藏" else "收藏",
                        onFavorite,
                        rim = if (favorite) c.accent.copy(alpha = .45f) else null,
                        modifier = Modifier.focusRequester(favFocus)
                            .focusProperties { right = prevFocus; up = railFocus },
                        onFocused = { hint = if (favorite) "取消收藏" else "收藏" },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayerIcon("prev", "上一首", onPrevious,
                        modifier = Modifier.focusRequester(prevFocus)
                            .focusProperties { left = favFocus; right = playFocus; up = railFocus },
                        onFocused = { hint = "上一首" })
                    PlayButton(playing, onPlay,
                        Modifier.focusRequester(playFocus)
                            .focusProperties { left = prevFocus; right = nextFocus; up = railFocus },
                        onFocused = { hint = if (playing) "暂停" else "播放" })
                    PlayerIcon("next", "下一首", onNext,
                        modifier = Modifier.focusRequester(nextFocus)
                            .focusProperties { left = playFocus; right = queueFocus; up = railFocus },
                        onFocused = { hint = "下一首" })
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayerIcon("list", "播放列表", onQueue,
                            modifier = Modifier.focusRequester(queueFocus)
                                .focusProperties { left = nextFocus; right = dotsFocus; up = railFocus },
                            onFocused = { hint = "播放列表 · $queueSize 首" })
                        MoreDots(
                            onClick = { interaction++; menuOpen = true },
                            modifier = Modifier.focusRequester(dotsFocus)
                                .focusProperties { left = queueFocus; up = railFocus }
                                .onFocusChanged { if (it.isFocused) hint = "更多 · 歌手、专辑、音质与歌词" },
                        )
                    }
                }
            }
        }

        if (!controlsVisible && !immersive) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { controlsVisible = true; interaction++ }
            })
        }

        // Read animation values in graphicsLayer to avoid frame-by-frame recomposition.
        val roomDim = remember { Animatable(0f) }
        LaunchedEffect(immersive, motion) {
            val target = if (immersive) .62f else 0f
            if (motion) roomDim.animateTo(target, tween(620, easing = FastOutSlowInEasing)) else roomDim.snapTo(target)
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = roomDim.value }.background(Color.Black))

        AnimatedVisibility(
            visible = immersive,
            enter = fadeIn(tween(if (motion) 200 else 0, easing = LinearEasing)) +
                scaleIn(tween(if (motion) 620 else 0, easing = FastOutSlowInEasing), initialScale = .968f),
            exit = fadeOut(tween(if (motion) 260 else 0)) +
                scaleOut(tween(if (motion) 300 else 0, easing = FastOutSlowInEasing), targetScale = 1.025f),
        ) {
            Immersion(
                song = song, lyrics = lyrics, lyricStatus = lyricStatus,
                clock = rawLyricPosition, lyricClock = lyricPosition, duration = duration,
                active = active, sizes = immersionSizes, subLyrics = subLyrics, playing = playing,
                level = l2Level, barVisible = l2Bar, motion = motion, focus = l2Focus,
                onPlay = onPlay, onInteraction = { l2Tick++ }, seekHint = l2SeekText,
            )
        }

        if (menuOpen) {
            PlayerMenu(
                artistName = artistName, albumName = albumName,
                sourceLabel = sourceSong?.sourceLabel.orEmpty(), stream = stream, playingTier = playingTier,
                platform = sourceSong?.platform,
                ladder = ladder, tiers = tiers,
                playMode = playMode, radioActive = radioActive, radioLoading = radioLoading,
                canArtist = artistName != null && onArtist != null, canAlbum = albumName != null && onAlbum != null,
                fontLevel = lyricFontLevel, subLyrics = subLyrics, offsetMs = lyricOffsetMs,
                canCalibrate = lyrics.isNotEmpty(),
                onClose = { menuOpen = false },
                onQuality = { onSelectQuality(it) },
                onArtist = { onArtist?.invoke() }, onAlbum = { onAlbum?.invoke() },
                onFontLevel = onLyricFontLevel, onSubLyrics = onSubLyrics, onCalibration = onLyricCalibration,
                onMode = onShuffle, onModeStep = onModeStep, onRadio = onRadioToggle,
            )
        }
    }
}

@Composable
private fun ClockText(clock: State<Long>, duration: Long, forward: Boolean) {
    val c = LocalPrismColors.current
    val ms by remember(clock, duration, forward) {
        derivedStateOf { if (forward) clock.value.coerceAtLeast(0) else duration }
    }
    Text(time(ms), color = c.muted, fontSize = 11.sp)
}

@Composable
private fun MoreDots(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.size(44.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(if (focused) c.accent.copy(alpha = .16f) else Color.Transparent)
            .border(if (focused) 2.dp else 1.dp,
                if (focused) c.accent else c.accent.copy(alpha = if (c.background.luminance() > .5f) .3f else .15f),
                CircleShape)
            .semantics { contentDescription = "更多" }
            .tvClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val r = size.minDimension * .085f
            val cy = size.height / 2f
            listOf(.18f, .5f, .82f).forEach { f ->
                drawCircle(if (focused) c.accent else c.muted, radius = r, center = Offset(size.width * f, cy))
            }
        }
    }
}

@Composable
private fun PlayerIcon(
    icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    active: Boolean = false, rim: Color? = null, onFocused: () -> Unit = {},
    text: String? = null,
) {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    /* The gold hairline has to sit a step darker on porcelain or it disappears into the paper. */
    val idle = c.accent.copy(alpha = if (c.background.luminance() > .5f) .3f else .15f)
    Box(
        modifier.size(44.dp).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .clip(CircleShape)
            .background(if (focused) c.accent.copy(alpha = .16f) else Color.Transparent)
            .border(
                if (focused) 2.dp else 1.dp,
                when {
                    focused -> c.accent
                    rim != null -> rim
                    else -> idle
                },
                CircleShape,
            )
            .semantics { contentDescription = label; selected = active }
            .tvClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (text != null) {
            /* Two letters read as "this is a video", which a screen-with-a-play glyph does not
               at 20dp — it reads as "fullscreen" next to the real fullscreen mark. The disc and
               the focus ring are shared with the icon buttons so the row stays one rhythm. */
            Text(text, color = if (focused) c.accent else c.muted, fontSize = 11.5f.sp,
                fontWeight = FontWeight.Medium, letterSpacing = .9.sp, maxLines = 1)
        } else {
            Glyph(icon, Modifier.size(if (icon.startsWith("heart")) 19.dp else 20.dp),
                if (focused || icon == "heartFilled") c.accent else c.muted)
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onFocused: () -> Unit = {}) {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.size(58.dp)
            .semantics { contentDescription = if (playing) "暂停" else "播放" }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (focused) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(c.accent.copy(alpha = .12f))
                .border(2.dp, c.foreground.copy(alpha = .9f), CircleShape))
        }
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(c.accent),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(if (playing) "pause" else "play", Modifier.size(20.dp), c.onAccent)
        }
    }
}

/* The pill says what is playing; the platform glyph is the whole "from". The ⋯ menu carries
   the source in words, so the room stays quiet. */
@Composable
private fun StreamPill(platform: MusicPlatform?, text: String) {
    val c = LocalPrismColors.current
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(c.accent.copy(alpha = .07f))
            .border(1.dp, c.accent.copy(alpha = .24f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (platform != null) {
            PlatformIcon(platform, Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, color = c.accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/* stacked = nothing beside the sleeve, so the words sit in a column of their own and centre
   themselves; inline = the words hang under a corner-placed sleeve and stay left-aligned. */
@Composable
private fun IdentityText(
    title: String, artistName: String?, stream: String,
    platform: MusicPlatform?, status: String, stacked: Boolean,
    textAlpha: () -> Float, modifier: Modifier = Modifier,
) {
    val c = LocalPrismColors.current
    Column(modifier.graphicsLayer { alpha = textAlpha() }) {
        if (stacked) {
            Text(
                title, color = c.foreground, fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (artistName != null) {
                Spacer(Modifier.height(9.dp))
                Text(
                    artistName, color = c.muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            StreamPill(platform, stream)
            if (status.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(status, color = c.muted.copy(alpha = .55f), fontSize = 11.sp, letterSpacing = .8.sp)
            }
        } else {
            /* 左对齐，贴住封面的左边线：这块是偏轴的（封面在左、歌词在右），居中会多出一条
               自己的轴线；而且歌名一变长，居中会把参差的边左右推，左对齐只有右端在变。 */
            Text(
                title, color = c.foreground, fontSize = 22.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (artistName != null || stream.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (artistName != null) {
                        Text(
                            artistName, color = c.muted, fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    StreamPill(platform, stream)
                }
            }
        }
    }
}

@Composable
private fun Immersion(
    song: Song?, lyrics: List<LyricLine>, lyricStatus: String, clock: State<Long>, lyricClock: State<Long>, duration: Long,
    active: Int, sizes: LyricSizes, subLyrics: Boolean, playing: Boolean,
    level: Int, barVisible: Boolean, motion: Boolean, focus: FocusRequester, onPlay: () -> Unit,
    onInteraction: () -> Unit, seekHint: String,
) {
    val c = LocalPrismColors.current
    val barAlpha by animateFloatAsState(if (barVisible) 1f else 0f, tween(if (motion) 240 else 0), label = "immersion bar")
    val noLyrics = lyrics.isEmpty()
    val identityAlpha by animateFloatAsState(if (level > 1) .38f else 1f, tween(if (motion) 900 else 0), label = "immersion identity")
    val enter = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (motion) enter.animateTo(1f, tween(820, easing = LinearEasing)) }
    fun beat(from: Float, to: Float) =
        ((enter.value - from) / (to - from)).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

    /* The light *is* the cover: three clamped radials plus a vignette, on from the first frame
       whether or not the lyrics have landed. Nothing about the room waits for the text. */
    val wash = rememberAmbience(song)
    val light = remember(wash) {
        val base = wash.map { dimTo(Color(it)) }.ifEmpty {
            listOf(dimTo(c.accent), dimTo(lerp(c.accent, Color.Black, .5f)), dimTo(lerp(c.accent, Color.White, .3f)))
        }
        List(3) { base[it % base.size] }
    }
    val lamp = remember { Animatable(0f) }
    LaunchedEffect(noLyrics, motion) {
        if (noLyrics) {
            delay(if (motion) 500 else 0)
            lamp.animateTo(1f, tween(if (motion) 700 else 0))
        } else lamp.animateTo(0f, tween(if (motion) 260 else 0))
    }
    val note = when {
        lyricStatus.contains("加载中") || lyricStatus.contains("正在") -> "正在找这首歌的歌词"
        lyricStatus.contains("本地") -> "本地文件没有歌词"
        else -> "纯音乐 · 暂无歌词"
    }

    Box(
        Modifier.fillMaxSize().background(c.background)
            .focusRequester(focus).focusable()
            .pointerInput(Unit) { detectTapGestures { onInteraction() } },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            fun glow(color: Color, alpha: Float, cx: Float, cy: Float, r: Float) {
                drawRect(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * .5f), Color.Transparent),
                        center = Offset(size.width * cx, size.height * cy), radius = size.width * r,
                    ),
                )
            }
            drawRect(
                Brush.radialGradient(
                    listOf(light[0].copy(alpha = .45f), c.background.copy(alpha = .9f)),
                    center = Offset(size.width * .5f, size.height * .5f), radius = size.width * .85f,
                ),
            )
            glow(light[2], .55f, .48f, .06f, .62f)
            glow(light[0], .50f, .29f, .35f, .46f)
            glow(light[1], .38f, .71f, .63f, .52f)

            if (lamp.value > 0f) {
                /* One continuous falloff from centre to edge: any stop that lands on a visible
                   level reads as a ring around the lamp. */
                val centre = Offset(size.width * .5f, size.height * .46f)
                val r = size.minDimension * .34f
                val warm = lerp(Color(0xFFF0D9A8), c.accent, .3f)
                val tail = exp(-5.6f)
                val stops = (0..26).map { i ->
                    val t = i / 26f
                    warm.copy(alpha = (((exp(-5.6f * t * t) - tail) / (1f - tail)) * .9f * lamp.value).coerceIn(0f, 1f))
                }
                drawCircle(Brush.radialGradient(stops, center = centre, radius = r), radius = r, center = centre)
            }
        }
        /* Vignette on the lyric column only: it fixes the local case where a highlight lands
           exactly under a line, which the global ceiling cannot reach. */
        Canvas(Modifier.align(Alignment.Center).fillMaxWidth(.86f).fillMaxHeight(.66f)) {
            val guard = 1f - .55f * lamp.value
            drawRect(
                Brush.radialGradient(
                    (0..6).map { i ->
                        val t = i / 6f
                        Color.Black.copy(alpha = .44f * (1f - t) * (1f - t) * guard)
                    },
                    center = center, radius = size.minDimension * .62f,
                ),
            )
        }

        val meta = song?.artist.orEmpty().takeIf { it.isNotBlank() && it != "未知歌手" }.orEmpty()
        Row(
            Modifier.align(Alignment.TopStart).padding(start = 32.dp, top = 24.dp)
                .fillMaxWidth(.72f).graphicsLayer { alpha = identityAlpha * beat(.55f, 1f) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                song?.title.orEmpty(), color = c.foreground.copy(alpha = .84f), fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (meta.isNotBlank()) {
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(3.dp).clip(CircleShape).background(c.foreground.copy(alpha = .28f)))
                Spacer(Modifier.width(12.dp))
                Text(meta, color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (noLyrics) {
            Text(
                note, color = c.muted.copy(alpha = .62f), fontSize = 14.sp, letterSpacing = 1.4.sp,
                modifier = Modifier.align(Alignment.Center).offset(y = 108.dp)
                    .graphicsLayer { alpha = lamp.value },
            )
        } else {
            RollingLyrics(
                lyrics, active, lyricStatus, lyricClock,
                sizes.text, sizes.line, sizes.support, subLyrics,
                Modifier.align(Alignment.Center).fillMaxWidth(.72f).fillMaxHeight(.62f)
                    .graphicsLayer {
                        val e = beat(.16f, 1f)
                        alpha = e
                        translationY = 24.dp.toPx() * (1f - e)
                    },
                centered = true, fade = .36f,
            )
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 32.dp, end = 32.dp, bottom = 26.dp)
                .graphicsLayer { alpha = if (level > 1) 0f else beat(.34f, 1f) },
        ) {
            AuraProgress(
                progress = { if (duration > 0) (clock.value.toFloat() / duration).coerceIn(0f, 1f) else 0f },
                height = 2.dp,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).alpha(if (level > 0) 0f else 1f)) {
                ClockText(clock, duration, forward = true)
                Spacer(Modifier.weight(1f))
                ClockText(clock, duration, forward = false)
            }
        }

        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 52.dp).size(44.dp)
                .graphicsLayer { alpha = barAlpha * beat(.42f, 1f) }
                .clip(CircleShape).background(c.accent)
                .clearAndSetSemantics {
                    if (barVisible) {
                        contentDescription = if (playing) "暂停" else "播放"
                        onClick { onPlay(); true }
                    }
                }
                .pointerInput(barVisible) {
                    detectTapGestures { if (barVisible) onPlay() else onInteraction() }
                },
            contentAlignment = Alignment.Center,
        ) {
            Glyph(if (playing) "pause" else "play", Modifier.size(22.dp), c.onAccent)
        }

        val hintAlpha by animateFloatAsState(if (seekHint.isNotEmpty()) 1f else 0f, tween(160), label = "seek hint")
        if (hintAlpha > 0f) {
            Text(
                seekHint, color = c.accent, fontSize = 13.sp, letterSpacing = 1.6.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp)
                    .graphicsLayer { alpha = hintAlpha * beat(.30f, 1f) },
            )
        }
    }
}

/* 一行只做一件事，控件形状说出是哪件：Step = 左右键改值（‹ v ›），Open = OK 进下一层（›），
   Toggle = 开关（胶囊）。以前 极光电台 是光秃秃一个「关」字、歌词是圆圈里的 ⌄、布尔值又画成
   ‹ 开 ›，同一件事三种画法——这里按种类定形，不再按页面定形。 */
private enum class RowKind { Step, Open, Toggle }

private data class MenuEntry(
    val label: String, val value: String, val dim: Boolean, val kind: RowKind,
    val on: Boolean = false, val icon: String = "",
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerMenu(
    artistName: String?, albumName: String?, sourceLabel: String, stream: String, playingTier: String,
    platform: MusicPlatform?,
    ladder: List<String>, tiers: List<String>,
    playMode: PlaybackMode, radioActive: Boolean, radioLoading: Boolean,
    canArtist: Boolean, canAlbum: Boolean,
    fontLevel: String, subLyrics: Boolean, offsetMs: Long, canCalibrate: Boolean,
    onClose: () -> Unit, onQuality: (String) -> Unit, onArtist: () -> Unit, onAlbum: () -> Unit,
    onFontLevel: (String) -> Unit, onSubLyrics: (Boolean) -> Unit, onCalibration: (Long) -> Unit,
    onMode: () -> Unit, onModeStep: (Int) -> Unit, onRadio: () -> Unit,
) {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    var level by remember { mutableIntStateOf(1) }
    var row by remember { mutableIntStateOf(0) }
    // Returning from lyric settings restores the parent row.
    var parentRow by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; runCatching { focus.requestFocus() } }
    val enter = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (motion) enter.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
    var closing by remember { mutableStateOf(false) }
    var afterClose by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun dismiss(action: (() -> Unit)? = null) {
        if (!closing) { afterClose = action; closing = true }
    }
    LaunchedEffect(closing) {
        if (closing) {
            enter.animateTo(0f, tween(if (motion) 180 else 0))
            onClose()
            afterClose?.invoke()
        }
    }
    val revealRow = remember { BringIntoViewRequester() }
    LaunchedEffect(level, row) {
        repeat(2) { withFrameNanos { } }
        revealRow.bringIntoView()
    }

    // Exclude tiers already found unavailable for this song.
    val rungs = ladder.ifEmpty { tiers }
    val usable = rungs.filter { tiers.isEmpty() || it in tiers }
    val current = playingTier.ifBlank { usable.lastOrNull().orEmpty() }

    val level1 = listOf(
        MenuEntry("音频质量", tierLabel(current), usable.size < 2, RowKind.Step, icon = "fader"),
        MenuEntry("查看歌手", "", !canArtist, RowKind.Open, icon = "mic"),
        MenuEntry("查看专辑", "", !canAlbum, RowKind.Open, icon = "album"),
        MenuEntry("歌词", fontLevel, false, RowKind.Open, icon = "lyric"),
        /* 播放模式是行内步进（左右键直接切模式），和音频质量一样，不是二级菜单。 */
        MenuEntry("播放模式", playMode.label, false, RowKind.Step, icon = "shuffle"),
        MenuEntry("极光电台", if (radioLoading) "准备中" else "", radioLoading, RowKind.Toggle, on = radioActive, icon = "radio"),
    )
    val level2 = listOf(
        MenuEntry("字号", fontLevel, false, RowKind.Step, icon = "lyric"),
        MenuEntry("翻译与注音", "", false, RowKind.Toggle, on = subLyrics, icon = "layers"),
        MenuEntry("时间校准", calibrationText(offsetMs), !canCalibrate, RowKind.Step, icon = "history"),
    )
    val rows = if (level == 1) level1 else level2
    val index = row.coerceIn(0, rows.lastIndex)

    fun stepFont(dir: Int) {
        val options = lyricFontLevels
        val i = options.indexOf(fontLevel).coerceAtLeast(0)
        onFontLevel(options[(i + dir).coerceIn(0, options.lastIndex)])
    }

    fun stepQuality(dir: Int) {
        if (usable.isEmpty()) return
        val at = usable.indexOfFirst { it == current }.takeIf { it >= 0 } ?: usable.lastIndex
        val next = usable[(at + dir).coerceIn(0, usable.lastIndex)]
        if (next != current) onQuality(next)
    }

    Dialog(onDismissRequest = { dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = .42f + .58f * enter.value }
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .42f), Color.Black.copy(alpha = .68f))))
                    .pointerInput(Unit) { detectTapGestures { dismiss() } },
            )
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 56.dp).width(448.dp)
                    .graphicsLayer {
                        alpha = enter.value
                        translationY = 22.dp.toPx() * (1f - enter.value)
                    }
                    .clip(RoundedCornerShape(12.dp)).background(c.panel.copy(alpha = .97f))
                    .border(1.dp, c.foreground.copy(alpha = .12f), RoundedCornerShape(12.dp))
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        if (closing) return@onPreviewKeyEvent true
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val n = rows.size
                        fun back() { if (level == 2) { level = 1; row = parentRow } else dismiss() }
                        fun activate() {
                            if (level == 2) {
                                when (index) {
                                    0 -> stepFont(1)
                                    1 -> onSubLyrics(!subLyrics)
                                    2 -> if (canCalibrate) onCalibration((offsetMs + 100).coerceIn(-LyricCalibration.LIMIT_MS, LyricCalibration.LIMIT_MS))
                                }
                            } else {
                                when (index) {
                                    0 -> stepQuality(1)
                                    1 -> if (canArtist) dismiss(onArtist)
                                    2 -> if (canAlbum) dismiss(onAlbum)
                                    3 -> { parentRow = index; level = 2; row = 0 }
                                    4 -> onMode()
                                    5 -> if (!radioLoading) onRadio()
                                }
                            }
                        }
                        fun adjust(dir: Int) {
                            if (level == 2) {
                                when (index) {
                                    0 -> stepFont(dir)
                                    1 -> onSubLyrics(dir > 0)
                                    2 -> if (canCalibrate) onCalibration((offsetMs + dir * 100L).coerceIn(-LyricCalibration.LIMIT_MS, LyricCalibration.LIMIT_MS))
                                }
                            } else {
                                when (index) {
                                    0 -> stepQuality(dir)
                                    4 -> onModeStep(dir)
                                    5 -> if (!radioLoading && (dir > 0) != radioActive) onRadio()
                                    /* 别的行不认左右：一级页按左不该把整个面板收掉——那和
                                       「退出」是两件事，却被同一个键做了。 */
                                    else -> Unit
                                }
                            }
                        }
                        when (event.key) {
                            Key.Back, Key.Escape -> { back(); true }
                            Key.DirectionUp -> { row = (index - 1 + n) % n; true }
                            Key.DirectionDown -> { row = (index + 1) % n; true }
                            Key.DirectionLeft -> { adjust(-1); true }
                            Key.DirectionRight -> {
                                if (level == 1 && index == 3) { parentRow = index; level = 2; row = 0 } else adjust(1)
                                true
                            }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { activate(); true }
                            else -> false
                        }
                    }
                    .focusable().padding(6.dp).verticalScroll(rememberScrollState()),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (level == 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                artistName ?: "未知歌手", color = c.foreground, fontSize = 16.sp, maxLines = 1,
                                modifier = Modifier.weight(1f, fill = false)
                                    .then(if (motion) Modifier.basicMarquee(delayMillis = 1600) else Modifier),
                            )
                            Text("  ·  ", color = c.muted.copy(alpha = .5f), fontSize = 16.sp)
                            Text(
                                if (albumName != null) "《$albumName》" else "无专辑", color = c.muted, fontSize = 16.sp, maxLines = 1,
                                modifier = Modifier.weight(1f, fill = false)
                                    .then(if (motion) Modifier.basicMarquee(delayMillis = 1600) else Modifier),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (platform != null) {
                                PlatformIcon(platform, Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("$sourceLabel · $stream", color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.foreground.copy(alpha = .09f)))
                        Spacer(Modifier.height(4.dp))
                    } else {
                        Text("‹　歌词", color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.foreground.copy(alpha = .09f)))
                        Spacer(Modifier.height(4.dp))
                    }
                    rows.forEachIndexed { i, entry ->
                        val on = i == index
                        /* ‹ › stays quiet until the row has focus: the brackets are the
                           affordability, not decoration on every line. */
                        val mark = when {
                            entry.dim -> .2f
                            on -> .85f
                            else -> .28f
                        }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp)
                                .graphicsLayer {
                                    val gap = .09f * i
                                    val a = ((enter.value - gap) / (1f - gap)).coerceIn(0f, 1f)
                                    alpha = a
                                    translationY = 12.dp.toPx() * (1f - a)
                                }
                                .then(if (on) Modifier.bringIntoViewRequester(revealRow) else Modifier)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (on) c.accent.copy(alpha = .11f) else Color.Transparent)
                                .then(if (on) Modifier.border(2.dp, c.accent.copy(alpha = .5f), RoundedCornerShape(8.dp)) else Modifier)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (entry.icon.isNotEmpty()) {
                                Box(
                                    Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))
                                        .background(if (on) c.accent.copy(alpha = .14f) else c.foreground.copy(alpha = .05f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Glyph(entry.icon, Modifier.size(19.dp),
                                        if (entry.dim) c.muted.copy(alpha = .35f) else if (on) c.accent else c.muted)
                                }
                                Spacer(Modifier.width(14.dp))
                            }
                            Text(
                                entry.label, color = when {
                                    entry.dim -> c.muted.copy(alpha = .4f)
                                    on -> c.accent
                                    else -> c.foreground
                                },
                                fontSize = 18.sp, maxLines = 1,
                            )
                            Spacer(Modifier.weight(1f))
                            val settle = when {
                                entry.dim -> c.muted.copy(alpha = .4f)
                                on -> c.accent
                                else -> c.foreground.copy(alpha = .6f)
                            }
                            if (entry.kind == RowKind.Toggle) {
                                if (entry.value.isNotEmpty()) {
                                    Text(entry.value, color = settle, fontSize = 15.sp, maxLines = 1)
                                    Spacer(Modifier.width(12.dp))
                                }
                                PrismCapsule(entry.on)
                            } else {
                                if (entry.kind == RowKind.Step) {
                                    Text("‹", color = c.accent.copy(alpha = mark), fontSize = 18.sp)
                                    Spacer(Modifier.width(9.dp))
                                }
                                if (entry.value.isNotEmpty()) {
                                    Text(entry.value, color = settle, fontSize = 17.sp, maxLines = 1)
                                }
                                if (entry.kind == RowKind.Step) Spacer(Modifier.width(9.dp))
                                else Spacer(Modifier.width(10.dp))
                                Text("›", color = c.accent.copy(alpha = mark), fontSize = 18.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val EaseOutCubic = Easing { t -> 1f - (1f - t) * (1f - t) * (1f - t) }
private val EaseInQuad = Easing { t -> t * t }

@Composable
internal fun PrismScreenOff(
    keepAwake: Boolean, motion: Boolean, settled: Boolean, onWake: () -> Unit,
) {
    val window = (LocalContext.current as? Activity)?.window
    val view = LocalView.current
    val focus = remember { FocusRequester() }
    val wake by rememberUpdatedState(onWake)
    val ember = remember { Animatable(0f) }
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
    /* The lamp comes up with the curtain, not after it: waiting for black reads as two
       separate events (screen dies, then something appears), which is what made the fade
       feel staged instead of continuous. */
    LaunchedEffect(motion) {
        if (motion) ember.animateTo(1f, tween(620, easing = EaseOutCubic)) else ember.snapTo(1f)
    }
    LaunchedEffect(settled, motion) {
        if (!settled) return@LaunchedEffect
        delay(2200)
        if (motion) ember.animateTo(0f, tween(1500, easing = EaseInQuad)) else ember.snapTo(0f)
        if (window != null) window.attributes = window.attributes.apply { screenBrightness = 0f }
    }
    val accent = LocalPrismColors.current.accent
    BoxWithConstraints(Modifier.fillMaxSize()
        .focusRequester(focus).focusable()
        .pointerInput(Unit) { detectTapGestures { wake() } }) {
        /* 呼吸：灯亮着就应该有起伏。9 秒一轮、幅度只有几个百分点——看得见「活着」，
           看不出在「闪」。相位只写在 draw 里读，所以不重组。 */
        val breath = remember { mutableFloatStateOf(0f) }
        LaunchedEffect(motion) {
            if (!motion) return@LaunchedEffect
            var start = 0L
            var lit = false
            while (true) {
                withFrameNanos { now ->
                    if (start == 0L) start = now
                    breath.floatValue = ((now - start) / 1_000_000_000f) * (2f * PI.toFloat() / 9f)
                }
                if (ember.value > 0f) lit = true else if (lit) break
            }
        }
        /* 半径收在 0.24 屏高：铺到 0.32 时它不再是灯，而是一块背景板；提示语跟着灯心走，
           不再甩到屏幕下方另起一行。 */
        val glowR = maxHeight * .26f
        Canvas(Modifier.fillMaxSize()) {
            val a = ember.value
            if (a <= 0f) return@Canvas
            val pulse = if (motion) sin(breath.floatValue) else 0f
            /* One gradient from core to edge. Two overlapping gradients — a bright disc
               inside a wider halo — is what left the ring: the inner stop fell to a level
               brighter than the halo under it. 往下压一点，它就是洒在屋里的一片暖光，
               而不是悬在空中的一颗球。 */
            val r = size.height * .26f * (1f + .035f * pulse)
            val warm = Color(0xFFF0D9A8)
            val tail = exp(-5.2f)
            val stops = (0..30).map { i ->
                val t = i / 30f
                val g = (exp(-5.2f * t * t) - tail) / (1f - tail)
                lerp(warm, accent, (t / .62f).coerceIn(0f, 1f))
                    .copy(alpha = (g * .84f * a * (1f + .07f * pulse)).coerceIn(0f, 1f))
            }
            scale(1f, .84f, pivot = center) {
                drawCircle(Brush.radialGradient(stops, center = center, radius = r), radius = r, center = center)
            }
        }
        Text(
            "播放继续 · 确定或返回亮屏",
            color = accent.copy(alpha = .58f),
            fontSize = 13.sp, letterSpacing = 1.4.sp,
            modifier = Modifier.align(Alignment.Center).offset(y = glowR * .76f)
                .graphicsLayer { alpha = ember.value * (.88f + .12f * sin(breath.floatValue)) },
        )
    }
}

@Composable
private fun RollingLyrics(
    lyrics: List<LyricLine>, active: Int, status: String, position: State<Long>,
    textSp: Int, lineSp: Int, supportSp: Int, subLyrics: Boolean,
    modifier: Modifier = Modifier, centered: Boolean = false, fade: Float = .12f,
) {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    if (lyrics.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(status.ifBlank { "暂无歌词" }, color = c.muted, fontSize = if (centered) 20.sp else 15.sp,
                textAlign = TextAlign.Center, maxLines = 8, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    val targetIndex = active.coerceAtLeast(0)
    // New lyrics get their own scroll state instead of inheriting the previous song's index.
    val state = remember(lyrics) { LazyListState(firstVisibleItemIndex = targetIndex) }
    val cut = remember(lyrics) { Animatable(0f) }
    var previousTarget by remember(lyrics) { mutableStateOf(targetIndex) }
    BoxWithConstraints(
        modifier.clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; alpha = cut.value }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Transparent, fade to Color.Black, 1f - fade to Color.Black, 1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = maxHeight / 2),
            verticalArrangement = Arrangement.spacedBy(if (centered) 20.dp else 12.dp),
            userScrollEnabled = false,
        ) {
            itemsIndexed(lyrics) { index, line ->
                RollingLyricRow(line, index, active, position, textSp, lineSp, supportSp, subLyrics, centered)
            }
        }
        LaunchedEffect(targetIndex, lyrics, textSp, lineSp, subLyrics, maxHeight, motion) {
            while (state.layoutInfo.viewportSize.height == 0) withFrameNanos { }
            withFrameNanos { }
            val previous = previousTarget
            val sequential = targetIndex == previous + 1
            // Seek jumps fade briefly; consecutive lines scroll normally.
            val jump = abs(targetIndex - previous) > 1
            previousTarget = targetIndex
            if (jump && motion) cut.animateTo(.06f, tween(90, easing = LinearEasing))
            if (!sequential || !motion || state.layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) {
                state.scrollToItem(targetIndex)
                withFrameNanos { }
            }
            val info = state.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == targetIndex } ?: return@LaunchedEffect
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            val delta = item.offset + item.size / 2f - center
            if (sequential && motion) {
                cut.snapTo(1f)
                state.animateScrollBy(delta, tween(620, easing = FastOutSlowInEasing))
            } else {
                state.scrollBy(delta)
                // This effect owns both fades, so a cancelled seek cannot leave lyrics dimmed.
                cut.animateTo(1f, tween(if (motion) 240 else 0, easing = FastOutSlowInEasing))
            }
        }
    }
}

@Composable
private fun RollingLyricRow(
    line: LyricLine, index: Int, active: Int, position: State<Long>,
    textSp: Int, lineSp: Int, supportSp: Int, subLyrics: Boolean, centered: Boolean,
) {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    // Show the first line during the intro without advancing its word highlighting.
    val stage = active.coerceAtLeast(0)
    val current = index == stage
    val waiting = active < 0 && index == stage
    val handoff by animateFloatAsState(
        if (waiting && !line.words.isNullOrEmpty()) 1f else 0f,
        tween(if (motion) 420 else 0), label = "first line handoff",
    )
    val size = textSp.sp
    val leading = lineSp.sp
    val distance = abs(index - stage)
    val scale = animateFloatAsState(if (current) 1f else if (distance <= 1) .84f else .78f,
        tween(if (motion) 620 else 0, easing = FastOutSlowInEasing), label = "lyric scale")
    val opacity = animateFloatAsState(when {
        current -> 1f
        index < stage -> if (distance == 1) .28f else .16f
        distance <= 1 -> if (c.background.luminance() > .5f) .56f else .36f
        else -> if (c.background.luminance() > .5f) .38f else .22f
    }, tween(if (motion) 620 else 0), label = "lyric opacity")
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(Modifier.fillMaxWidth().graphicsLayer {
        scaleX = scale.value; scaleY = scale.value; alpha = opacity.value
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (centered) .5f else 0f, .5f)
    }, horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        if (current) {
            Box(Modifier.fillMaxWidth()) {
                ActiveLyricText(line, position, size, leading, align)
                if (handoff > 0f) {
                    Text(line.text, color = c.foreground.copy(alpha = handoff), fontSize = size, lineHeight = leading,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = align, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        else Text(line.text, color = c.foreground, fontSize = size, lineHeight = leading,
            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = align, modifier = Modifier.fillMaxWidth())
        if (subLyrics) line.supportingLines.takeIf { it.isNotEmpty() }?.let { supporting ->
            Text(supporting.joinToString("\n"), color = c.foreground.copy(alpha = .66f),
                fontSize = supportSp.sp, lineHeight = (supportSp + 5).sp, maxLines = 3,
                overflow = TextOverflow.Ellipsis, textAlign = align,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

@Composable
private fun ActiveLyricText(line: LyricLine, position: State<Long>, textSize: TextUnit, lineHeight: TextUnit, align: TextAlign) {
    LyricText(line, position.value, Modifier.fillMaxWidth(), textSize, lineHeight, align)
}

/* One target, one verdict: this capsule says what is playing and opens the player. Every
   transport button that used to live here split focus into five stops for one job. */
@Composable
fun PrismMiniPlayer(
    song: Song?, playing: Boolean, position: Long, duration: Long, lyric: String, showLyric: Boolean,
    onOpen: () -> Unit, onToggle: () -> Unit,
    openFocus: FocusRequester,
    upTarget: FocusAnchor? = null,
    upMemory: MutableState<FocusRequester?>? = null,
    upRestore: (() -> Boolean)? = null,
    playbackSource: Song? = null,
) {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    var focused by remember { mutableStateOf(false) }
    val light by animateFloatAsState(if (focused) 1f else 0f, tween(if (motion) 180 else 0), label = "capsule light")
    val shape = RoundedCornerShape(50)
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val toPageEntry = Modifier.focusProperties { upTarget?.takeIf { it.attached }?.let { up = it.requester } }
    val upMemoryRoute = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
            if (upRestore?.invoke() == true) return@onPreviewKeyEvent true
            val target = upMemory?.value
            if (target != null && runCatching { target.requestFocus() }.isSuccess) return@onPreviewKeyEvent true
        }
        false
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier.width(if (showLyric) 520.dp else 300.dp).height(48.dp)
                .graphicsLayer { translationY = -2.dp.toPx() * light }
                .drawWithContent {
                    drawContent()
                    if (light <= 0f) return@drawWithContent
                    val stroke = 1.dp.toPx()
                    drawRoundRect(
                        c.accent.copy(alpha = .5f * light),
                        topLeft = Offset(stroke / 2f, stroke / 2f),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(size.height / 2f), style = Stroke(stroke),
                    )
                    /* Focus is light, not a border: three fading rings outside the shape read
                       as the capsule warming up rather than being outlined. */
                    for (k in 1..3) {
                        val pad = 2.dp.toPx() * k
                        drawRoundRect(
                            c.accent.copy(alpha = .07f * light / k),
                            topLeft = Offset(-pad, -pad),
                            size = Size(size.width + pad * 2, size.height + pad * 2),
                            cornerRadius = CornerRadius(size.height / 2f + pad),
                            style = Stroke(2.dp.toPx()),
                        )
                    }
                }
                .focusRequester(openFocus).then(toPageEntry)
                .onFocusChanged { focused = it.isFocused }
                /* No menu of its own: the Menu key must not fall through to 暂停. */
                .onPreviewKeyEvent { it.key == Key.Menu && it.type == KeyEventType.KeyDown }
                .then(upMemoryRoute)
                .clip(shape).background(c.panel.copy(alpha = .97f))
                .semantics { contentDescription = if (song == null) "搜索音乐" else "打开正在播放" }
                .tvClickable(onOpen, if (song == null) null else onToggle),
        ) {
            Row(
                Modifier.fillMaxSize().padding(start = 6.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverArt(song, Modifier.size(36.dp), 18.dp)
                Spacer(Modifier.width(12.dp))
                if (song == null) {
                    Text("此刻，适合听点什么？", color = c.foreground, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                } else if (showLyric && lyric.isNotBlank()) {
                    Text(
                        lyric, color = c.foreground.copy(alpha = .92f), fontSize = 14.sp, maxLines = 1,
                        modifier = Modifier.weight(1f).focusedMarquee(focused),
                    )
                } else {
                    Column(Modifier.weight(1f)) {
                        Text(song.title, color = c.foreground, fontSize = 14.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(
                            song.artist.ifBlank { (playbackSource ?: song).sourceLabel },
                            color = c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (song != null) {
                    Spacer(Modifier.width(12.dp))
                    NowPlayingBars(c.accent, Modifier.height(13.dp), barWidth = 2.5.dp, active = playing)
                    Spacer(Modifier.width(10.dp))
                    Glyph("chevR", Modifier.size(11.dp), c.accent.copy(alpha = .28f + .62f * light))
                }
            }
            AuraProgress(progress = { progress }, height = 2.dp, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/* 一条直轨，但让它有生命：未播段是可数的短刻度（还没录上去的音轨），已播段是实心金线，
   线尾拖一小段彗尾，接缝一颗会呼吸的点，播放时还有一道很淡的光沿已播段扫过去。
   这些都是叠加在「播到哪了」之上的细部，读数永远优先——所以轨道本体始终是平直的。 */
@Composable
private fun ProgressRail(progress: () -> Float, focused: Boolean, playing: Boolean, modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    val thick by animateDpAsState(if (focused) 3.dp else 2.dp, tween(if (motion) 180 else 0), label = "rail thickness")
    val knob by animateDpAsState(if (focused) 13.dp else 7.dp, tween(if (motion) 180 else 0), label = "rail knob")
    /* 播放时钟一秒一跳，直画会一格一格地窜；让它用 300ms 追过去，读数才对得上「连续」。 */
    val shown by animateFloatAsState(
        progress().coerceIn(0f, 1f), tween(if (motion) 300 else 0, easing = LinearEasing), label = "rail progress",
    )
    val beat = rememberInfiniteTransition(label = "rail")
    val cycle by if (motion && playing) beat.animateFloat(
        0f, 1f, infiniteRepeatable(tween(5600, easing = LinearEasing)), label = "rail cycle",
    ) else remember { mutableFloatStateOf(0f) }
    Canvas(modifier.fillMaxHeight()) {
        val y = size.height / 2f
        val end = size.width * shown
        val w = thick.toPx()
        val breath = 1f + .16f * sin(cycle * 4f * PI.toFloat())
        val tick = 2.dp.toPx() to 5.dp.toPx()

        drawLine(
            c.foreground.copy(alpha = if (focused) .24f else .16f),
            Offset(end, y), Offset(size.width, y), w * .8f, StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(tick.first, tick.second)),
        )
        if (end > .5f) {
            drawLine(
                Brush.horizontalGradient(
                    listOf(c.accent.copy(alpha = .85f), lerp(c.accent, Color.White, .32f)),
                    startX = 0f, endX = end.coerceAtLeast(1f),
                ),
                Offset(0f, y), Offset(end, y), w, StrokeCap.Round,
            )
            // 彗尾：端头后面一小段更亮，眼睛先落在这里
            val tail = 52.dp.toPx()
            if (end > tail) {
                drawLine(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, c.foreground.copy(alpha = .22f)),
                        startX = end - tail, endX = end,
                    ),
                    Offset(end - tail, y), Offset(end, y), w, StrokeCap.Round,
                )
            }
            // 扫光：只在播放时，且只在已经播过的那一段里走，不越过接缝
            if (motion && playing && end > w * 8) {
                val band = size.width * .07f
                val x = end * (cycle * 1.3f - .15f)
                clipRect(0f, y - w * 2.2f, end, y + w * 2.2f) {
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = .12f), Color.Transparent),
                            startX = x - band, endX = x + band,
                        ),
                        topLeft = Offset(0f, y - w * 2.2f), size = Size(end, w * 4.4f),
                    )
                }
            }
        }
        val glowR = knob.toPx() * (if (focused) 1.9f else 1.5f) * breath
        drawCircle(
            Brush.radialGradient(
                listOf(c.accent.copy(alpha = .34f), c.accent.copy(alpha = .10f), Color.Transparent),
                center = Offset(end, y), radius = glowR,
            ),
            radius = glowR, center = Offset(end, y),
        )
        drawCircle(c.foreground, radius = knob.toPx() / 2f, center = Offset(end, y))
    }
}
