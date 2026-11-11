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
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
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
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import coil.request.ImageRequest
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

private fun artworkScrim(colors: PrismColors): Brush {
    val opacity = when {
        colors.background.luminance() > .5f -> listOf(.94f, .96f, .98f)
        // Compose blur is unavailable before Android 12. Keep unblurred artwork subdued.
        android.os.Build.VERSION.SDK_INT < 31 -> listOf(.82f, .90f, .97f)
        else -> listOf(.58f, .76f, .93f)
    }
    return Brush.verticalGradient(opacity.map { colors.background.copy(alpha = it) })
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

/* One palette per cover URL, cached; concurrent requests share one pending task.
   Per frame only a gradient is drawn, never a decode. */
private object AmbienceCache {
    private val cache = object : LinkedHashMap<String, List<Int>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Int>>?) = size > 24
    }
    private val pending = HashMap<String, Deferred<List<Int>>>()
    /* Extraction runs on a scope that outlives any page, so one page leaving
       can't cancel another page's pending task. */
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

@Composable
private fun AuraAmbience(song: Song?, modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    val raw = rememberAmbience(song)
    /* Keep the old glow while a new cover is being analyzed, but only for the same
       artwork — borrowing the previous song's colours would be worse than no glow. */
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
    /* With motion off, skip the infinite animation entirely — it ticks every frame even
       when unread; reading the phase at draw time keeps updates out of composition. */
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

/** Lyric size tiers shared by the player and full-screen lyrics; sp values. */
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
    playing: Boolean, playMode: PlaybackMode, favorite: Boolean, quality: String, playingQuality: String,
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
    var standby by remember { mutableStateOf(false) }
    val standbyFocus = remember { FocusRequester() }
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
    /* Calibration shifts only the lyric's judging time (playhead − offset); negative is
       allowed so an early line stays hidden at 0:00. */
    val lyricPosition = rawLyricPosition - lyricOffsetMs
    val fontSizes = lyricSizesFor(lyricFontLevel)
    LaunchedEffect(playMode, favorite, playing, playingTier) {
        focusedControl = when {
            focusedControl.startsWith("播放模式") -> "播放模式：${playMode.label}"
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
    /* Full-screen lyrics are an opaque overlay: stop the spin, orbit light, ambience and
       wave underneath instead of burning frames invisibly. */
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
        /* The lyric column is capped by the space left above the controls, so long lines
           never push into the progress bar. */
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
                    /* At the large tier, drop the previous line so the current lyric
                       gets the whole column. */
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
                Text(if (status.startsWith("正在解析") || status.startsWith("正在尝试") || status.startsWith("正在打开") || status.startsWith("正在加载") || status.contains("失败")) status else if (radioFocused) radioHint else focusedControl,
                    color = c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp).weight(1f, fill = false), textAlign = TextAlign.Center)
                Text(time(duration), color = c.muted, fontSize = 11.sp)
            }
            /* Fixed-height slot: the control row (72dp) and the strip (58dp) are different
               heights, and swapping them directly would bounce the progress bar. The
               crossfade is alpha-only with a snap size transform. */
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
                            val modeHint = if (radioActive) "切换播放模式将退出电台" else "播放模式：${playMode.label} · 确定键切换"
                            PlayerIcon(if (radioActive) "list" else when (playMode) { PlaybackMode.SHUFFLE -> "shuffle"; PlaybackMode.REPEAT_ONE -> "repeatOne"; PlaybackMode.REPEAT_ALL -> "repeat"; else -> "list" }, "播放模式", onShuffle, upToRail,
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
    /* Brightness drops to zero only after the hint fades, so the hint is visible
       on any TV before true black. */
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
    /* Transparent on purpose: the curtain below paints the black; this layer only
       takes focus and taps, and shows the hint. */
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
    /* A very slow zoom keeps the still frame alive without turning the cover into a widget. */
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
                /* At the large tier, lines two rows away are not rendered at all,
                   leaving room for the current line. */
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
        /* A tier change re-wraps lines, so centring must be recomputed. */
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

@Composable
private fun FontChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, textSp: Int = 14, height: Dp = 40.dp) {
    val c = LocalPrismColors.current
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.height(height).auraFocus(shape, 1.05f)
        .clip(shape)
        .background(if (selected) c.accent.copy(alpha = .2f) else c.background.copy(alpha = .5f))
        .border(1.dp, if (selected) c.accent else c.line.copy(alpha = .6f), shape)
        .clickable(onClick = onClick)
        .semantics { contentDescription = "歌词字号 $label"; this.selected = selected },
        contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) c.accent else c.foreground, fontSize = textSp.sp)
    }
}

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
            /* The chips preview with an exaggerated relative scale (11/14/18sp), not the
               real lyric sizes — the real gaps are barely visible and would shout over
               the lyric. */
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
            /* Fixed-width value slot: toggling between 无校准 and a delay must not
               move the focused button. */
            Text(LyricCalibration.describe(offsetMs), color = c.foreground, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(72.dp))
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
        Row(Modifier.auraFocus(RoundedCornerShape(50), 1f, ring = c.foreground).clip(RoundedCornerShape(50))
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
        Glyph(icon, Modifier.size(if (icon == "radio") 20.dp else 18.dp), if (focused || active || icon == "heartFilled") c.accent else c.muted)
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
