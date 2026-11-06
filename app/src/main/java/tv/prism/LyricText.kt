package tv.prism

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

internal fun lyricWordProgress(word: LyricWord, nextStart: Long?, position: Long): Float {
    val end = word.endMs ?: nextStart ?: (word.startMs + 400)
    return ((position - word.startMs).toFloat() / (end - word.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
}

private data class LyricRegion(val word: Int, val rect: Rect, val before: Float, val width: Float, val rtl: Boolean)

/** Keep shaping and accessibility in Text; only the paint mask changes with playback. */
@Composable
internal fun LyricText(
    line: LyricLine, position: Long, modifier: Modifier = Modifier,
    fontSize: TextUnit = 26.sp, lineHeight: TextUnit = 35.sp, textAlign: TextAlign = TextAlign.Start,
) {
    val colors = LocalPrismColors.current
    val playback = rememberUpdatedState(position)
    val words = line.words.orEmpty()
    val text = remember(line) { if (words.isEmpty()) line.text else words.joinToString("") { it.text } }
    var layout by remember(text, fontSize, lineHeight) { mutableStateOf<TextLayoutResult?>(null) }
    val fontPx = with(LocalDensity.current) { fontSize.toPx() }
    val regions = remember(words, layout) {
        val measured = layout
        if (measured == null) emptyList() else buildList {
            var offset = 0
            words.forEachIndexed { index, word ->
                val boxes = (offset until offset + word.text.length).groupBy {
                    measured.getLineForOffset(it) to measured.getBidiRunDirection(it)
                }.map { (direction, offsets) ->
                    val bounds = offsets.map { measured.getBoundingBox(it) }
                    Rect(bounds.minOf { it.left }, bounds.minOf { it.top },
                        bounds.maxOf { it.right }, bounds.maxOf { it.bottom }) to direction.second
                }
                val width = boxes.sumOf { it.first.width.toDouble() }.toFloat()
                var before = 0f
                boxes.forEach { (rect, direction) ->
                    if (width > 0f) add(LyricRegion(index, rect, before, width, direction == ResolvedTextDirection.Rtl))
                    before += rect.width
                }
                offset += word.text.length
            }
        }
    }
    val paint = remember(regions, words, colors, fontPx) {
        Modifier.drawWithCache {
            val halo = Shadow(colors.foreground.copy(alpha = .14f), Offset.Zero, fontPx * .15f)
            onDrawWithContent {
                drawContent()
                val measured = layout ?: return@onDrawWithContent
                val now = playback.value
                val completed = Path()
                regions.forEach { region ->
                    val word = words[region.word]
                    val progress = lyricWordProgress(word, words.getOrNull(region.word + 1)?.startMs, now)
                    val filled = (progress * region.width - region.before).coerceIn(0f, region.rect.width)
                    if (filled >= region.rect.width) completed.addRect(region.rect)
                }
                clipPath(completed) { drawText(measured, color = colors.foreground, shadow = halo) }
                regions.forEach { region ->
                    val word = words[region.word]
                    val end = word.endMs ?: words.getOrNull(region.word + 1)?.startMs
                    if (now < word.startMs || (end != null && now >= end)) return@forEach
                    val progress = lyricWordProgress(word, end, now)
                    val filled = (progress * region.width - region.before).coerceIn(0f, region.rect.width)
                    if (filled <= 0f) return@forEach
                    val feather = fontPx * .24f
                    val edge = if (region.rtl) region.rect.right - filled else region.rect.left + filled
                    val direction = if (region.rtl) -1f else 1f
                    val brush = Brush.linearGradient(
                        listOf(colors.foreground, colors.foreground.copy(alpha = 0f)),
                        Offset(edge - feather * direction, 0f), Offset(edge + feather * direction, 0f),
                    )
                    clipRect(region.rect.left, region.rect.top, region.rect.right, region.rect.bottom) {
                        drawText(measured, brush = brush, alpha = 1f, shadow = halo)
                    }
                }
            }
        }
    }
    val resting = when {
        colors.background.luminance() > .5f -> .70f
        colors.background == androidx.compose.ui.graphics.Color(0xFF0A0A0A) -> .34f
        else -> .48f
    }
    Text(text, color = colors.foreground.copy(alpha = if (words.isEmpty()) 1f else resting),
        fontSize = fontSize, lineHeight = lineHeight, fontWeight = FontWeight.SemiBold,
        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = textAlign,
        onTextLayout = { layout = it }, modifier = modifier.then(paint))
}
