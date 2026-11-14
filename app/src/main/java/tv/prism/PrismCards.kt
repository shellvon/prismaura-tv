package tv.prism

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size

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
fun PrismEmptyState(hint: String, action: String? = null, onAction: (() -> Unit)? = null, actionModifier: Modifier = Modifier,
                    centered: Boolean = false) {
    val c = LocalPrismColors.current
    Column(if (centered) Modifier.fillMaxWidth().padding(top = 84.dp) else Modifier.padding(start = 6.dp, top = 4.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Text(hint, color = c.muted, fontSize = if (centered) 15.sp else 12.sp)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(if (centered) 18.dp else 10.dp))
            PrismAction(action, onAction, actionModifier, primary = centered)
        }
    }
}

/* Selected is a quiet fill, not gold: on this page gold means focus or the primary action. */
@Composable
fun PrismLibraryTab(label: String, count: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
                    onLongPress: (() -> Unit)? = null) {
    val c = LocalPrismColors.current
    val shape = RoundedCornerShape(50)
    Row(modifier.height(42.dp).auraFocus(shape, 1f).clip(shape)
        .background(if (selected) c.foreground.copy(alpha = .10f) else Color.Transparent)
        .tvClickable(onClick, onLongPress)
        .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = if (selected) c.foreground else c.muted, fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
        if (count > 0) Text("$count", color = if (selected) c.accent else c.muted.copy(alpha = .7f), fontSize = 11.sp, maxLines = 1)
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
                                Glyph("heartFilled", Modifier.size(18.dp), c.accent)
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
fun PrismQueueRow(song: Song, index: Int, current: Boolean, modifier: Modifier = Modifier, onLongPress: (() -> Unit)? = null, favorite: Boolean = false, onClick: () -> Unit) {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    val emphasis by animateFloatAsState(if (focused) 1f else if (current) .5f else 0f, tween(if (LocalAuraMotion.current) 170 else 0), label = "queue light")
    Row(modifier.fillMaxWidth().height(52.dp).onFocusChanged { focused = it.isFocused }
        .clip(RoundedCornerShape(8.dp)).background(c.accent.copy(alpha = emphasis * .12f))
        .drawBehind { if (emphasis > 0f) drawRect(c.accent.copy(alpha = emphasis), size = Size(2.dp.toPx(), size.height)) }
        .tvClickable(onClick, onLongPress).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (current) Box(Modifier.width(28.dp)) { NowPlayingBars(c.accent) }
        else Text("%02d".format(index + 1), Modifier.width(28.dp), color = if (focused) c.accent else c.muted, fontSize = 12.sp)
        CoverArt(song, Modifier.size(38.dp), 6.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, color = if (current) c.accent else c.foreground, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.focusedMarquee(focused))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PlatformIcon(song.platform, Modifier.size(12.dp))
                Text(song.artist, Modifier.weight(1f), color = c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            if (favorite) Glyph("heartFilled", Modifier.size(18.dp).semantics { contentDescription = "已收藏" }, c.accent)
        }
        Text(song.durationLabel, Modifier.width(44.dp), color = c.muted, fontSize = 11.sp, textAlign = TextAlign.End, maxLines = 1)
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
/* 音质标签固定槽位：标签出现或消失都不挤窄标题列。 */
private val ColQuality = 46.dp

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
        Spacer(Modifier.width(ColQuality))
    }
}

/** One row of a real table. `index` is the position in THIS list. */
@Composable
fun PrismTrackRow(song: Song, index: Int, onClick: () -> Unit, modifier: Modifier = Modifier, current: Boolean = false,
                  qualityTag: String? = null, showMv: Boolean = true, onFocus: () -> Unit = {}, onLongPress: (() -> Unit)? = null,
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
            Text(song.artist.ifBlank { "未知" }, color = c.muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(song.album.ifBlank { "—" }, Modifier.weight(1f), color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.width(ColSource), contentAlignment = Alignment.CenterStart) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformIcon(song.platform, Modifier.size(18.dp))
                if (showMv && song.hasMv) {
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
            Box(Modifier.width(ColQuality), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.border(1.dp, c.accent.copy(alpha = .42f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp)) {
                    Text(qualityTag, color = c.accent, fontSize = 9.sp, letterSpacing = .6.sp, maxLines = 1)
                }
            }
        } else Spacer(Modifier.width(ColQuality))
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
