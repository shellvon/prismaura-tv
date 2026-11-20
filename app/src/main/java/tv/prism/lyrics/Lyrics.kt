package tv.prism.lyrics

import android.content.Context
import org.json.JSONArray
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.math.BigDecimal
import java.math.RoundingMode

sealed interface LyricStatus {
    val message: String

    data object Loading : LyricStatus { override val message = "歌词加载中…" }
    data object Ready : LyricStatus { override val message = "" }
    data class Empty(override val message: String = "暂无歌词", val local: Boolean = false) : LyricStatus
    data object Error : LyricStatus { override val message = "歌词加载失败" }
}

data class LyricWord(val startMs: Long, val text: String, val endMs: Long? = null)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    val words: List<LyricWord>? = null,
    val additionalLines: List<String> = emptyList(),
    val endMs: Long? = null,
) {
    val supportingLines: List<String> get() = (listOfNotNull(translation) + additionalLines)
        .filter { it.isNotBlank() && it != text }.distinct()

    // Only source-provided timings count as a known vocal end.
    internal val knownEndMs: Long? get() = endMs ?: words
        ?.takeIf { it.lastOrNull()?.endMs != null }
        ?.maxOfOrNull { it.endMs ?: it.startMs }
}

/** Display cues keep instrumental intervals separate from the lyric text and translations. */
internal sealed interface LyricCue {
    val timeMs: Long
    data class Line(override val timeMs: Long, val index: Int) : LyricCue
    data class Gap(override val timeMs: Long, val endMs: Long) : LyricCue
}

internal fun lyricCues(lines: List<LyricLine>): List<LyricCue> = buildList {
    var latestKnownEnd = 0L
    var precedingEndKnown = true
    lines.forEachIndexed { index, line ->
        if (precedingEndKnown && line.timeMs - latestKnownEnd >= 4_000L) {
            add(LyricCue.Gap(latestKnownEnd, line.timeMs))
        }
        if (!precedingEndKnown && line.timeMs - lines[index - 1].timeMs >= 15_000L &&
            line.timeMs - latestKnownEnd >= 4_000L) {
            // Plain LRC has no release time. Cue only the final four seconds of a long interval.
            add(LyricCue.Gap(line.timeMs - 4_000L, line.timeMs))
        }
        add(LyricCue.Line(line.timeMs, index))
        val end = line.knownEndMs?.takeIf { it > line.timeMs }
        precedingEndKnown = end != null
        if (end != null) latestKnownEnd = maxOf(latestKnownEnd, end)
    }
}

/** Last line whose timestamp is at or before [ms]; -1 before the first line. */
internal fun List<LyricLine>.lineIndexAt(ms: Long): Int {
    if (isEmpty() || ms < first().timeMs) return -1
    var low = 0
    var high = lastIndex
    while (low < high) {
        val mid = (low + high + 1) ushr 1
        if (this[mid].timeMs <= ms) low = mid else high = mid - 1
    }
    return low
}

private val headingSeparator = Regex("""\s*[-－–—]\s*""")
private val performerAlias = Regex("""^(.+?)\s*[(（]([^()（）]+)[)）]$""")

internal fun List<LyricLine>.withoutOpeningHeading(title: String, artist: String): List<LyricLine> {
    val first = firstOrNull() ?: return this
    if (size < 2 || first.timeMs > 2000 || !first.words.isNullOrEmpty() ||
        title.isBlank() || artist.isBlank()) return this
    fun names(value: String): List<String> {
        val text = value.trim().lowercase()
        val alias = performerAlias.matchEntire(text) ?: return listOf(text)
        return alias.groupValues.map { it.trim() }
    }
    val performers = names(artist)
    val heading = headingSeparator.findAll(first.text).any { delimiter ->
        val left = first.text.substring(0, delimiter.range.first).trim()
        val right = first.text.substring(delimiter.range.last + 1).trim()
        (left.equals(title.trim(), true) && names(right).any { it in performers }) ||
            (right.equals(title.trim(), true) && names(left).any { it in performers })
    }
    return if (heading) drop(1) else this
}

object Lyrics {
    private val stamp = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
    private val wordTag = Regex("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>")
    private val msHeader = Regex("^\\[(\\d+),(\\d+)]")
    private val yrcWord = Regex("\\((\\d+),(\\d+),\\d+\\)")
    private val qrcWord = Regex("\\((\\d+),(\\d+)\\)")
    /* Credit lines are timestamped like lyrics but are not sung: "词：…" on screen reads as a
       bug, not as a lyric. Roles come in endless combinations ("歌曲统筹/监制：…"), so the
       test is on the head before the colon: every slash-separated part must be a known role.
       A sung line that starts 曲终人散 has no colon there and stays. */
    private val roles = listOf("作词", "作曲", "编曲", "制作人", "配唱制作人", "配唱", "人声", "监唱", "录音师", "混音师", "母带师", "词", "曲", "原唱", "演唱", "混音", "母带", "监制", "出品", "录音", "和声", "吉他", "贝斯", "鼓", "键盘", "弦乐", "发行", "统筹", "策划", "宣传", "推广", "设计")
    private fun isCredit(text: String): Boolean {
        val colon = text.indexOfFirst { it == ':' || it == '：' }
        if (colon <= 0 || colon > 20) return false
        return text.substring(0, colon).split('/', '／', '、').all { part ->
            part.trim().let { role -> roles.any { role.endsWith(it) } }
        }
    }

    fun parse(raw: String, translation: String = ""): List<LyricLine> {
        val original = groupLines(parseLines(raw))
        val translated = groupLines(parseLines(translation))
        return original.map { line ->
            val match = translated.minByOrNull { kotlin.math.abs(it.timeMs - line.timeMs) }
                ?.takeIf { kotlin.math.abs(it.timeMs - line.timeMs) <= 150 }
            val text = match?.text ?: line.translation
            line.copy(translation = text,
                additionalLines = (line.supportingLines + match?.supportingLines.orEmpty()).filter { it != text }.distinct())
        }
    }

    private fun groupLines(lines: List<LyricLine>): List<LyricLine> = lines
        .filter { it.text.isNotBlank() && !isCredit(it.text) }
        .sortedBy { it.timeMs }.groupBy { it.timeMs }.values.map { group ->
            val main = group.firstOrNull { !it.words.isNullOrEmpty() } ?: group.first()
            main.copy(endMs = group.mapNotNull { it.knownEndMs }.maxOrNull(),
                additionalLines = group.flatMap { listOf(it.text) + it.supportingLines }
                .filter { it != main.text && it != main.translation }.distinct())
        }

    private fun parseLines(raw: String): List<LyricLine> {
        val content = raw.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (content.startsWith("<")) return TtmlLyrics.parse(content)
        val adjustment = offset.find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val lines = mutableListOf<LyricLine>()
        raw.lineSequence().take(5000).forEach { line ->
            lines.addAll(parseLine(line, adjustment))
        }
        // A bare LRC stamp closes the preceding vocal group. A sung line at the
        // same stamp takes precedence, and repeated blank stamps use the earliest.
        var nextBlank: Long? = null
        return lines.groupBy { it.timeMs }.toSortedMap(reverseOrder()).values.map { group ->
            val vocals = group.filter { it.text.isNotBlank() }
            val end = nextBlank
            nextBlank = if (vocals.isEmpty()) group.first().timeMs else null
            vocals.map { if (end != null && it.knownEndMs == null) it.copy(endMs = end) else it }
        }.asReversed().flatten()
    }

    private fun parseLine(line: String, adjustment: Long): List<LyricLine> {
        val content = line.replace("\uFEFF", "").trimStart()
        if (content.isEmpty() || content.startsWith("{")) return emptyList()
        if (wordTag.containsMatchIn(content)) return parseEnhanced(content, adjustment)
        if (msHeader.containsMatchIn(content)) return parseMsLine(content, adjustment)
        return parsePlain(content, adjustment)
    }

    private fun stampMs(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLongOrNull() ?: return -1
        val seconds = match.groupValues[2].toLongOrNull() ?: return -1
        if (seconds >= 60) return -1
        val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return minutes * 60_000 + seconds * 1000 + fraction
    }

    private fun parsePlain(line: String, adjustment: Long): List<LyricLine> {
        val matches = stamp.findAll(line).toList()
        /* Some LRC files carry a BOM inside the line, right before the first timestamp;
           it used to leave "词：…" alive and push the credits on screen. */
        val text = stamp.replace(line, "").trim()
        val lines = mutableListOf<LyricLine>()
        matches.forEach timestamp@ { match ->
            val time = stampMs(match)
            if (time < 0) return@timestamp
            lines.add(LyricLine((time + adjustment).coerceAtLeast(0), text))
        }
        return lines
    }

    /* Enhanced LRC: words are separated by absolute <mm:ss.xx> tags inside the line; the text
       before the first tag starts with the line. A trailing tag after the last word only marks
       the line's end and carries no text. */
    private fun parseEnhanced(line: String, adjustment: Long): List<LyricLine> {
        val stamps = stamp.findAll(line).toList()
        if (stamps.isEmpty()) return emptyList()
        val firstTime = stampMs(stamps.first())
        if (firstTime < 0) return emptyList()
        val parts = line.split(wordTag)
        val tags = wordTag.findAll(line).toList()
        if (parts.size != tags.size + 1) return emptyList()
        val times = tags.map { stampMs(it) }
        if (times.any { it < 0 }) return emptyList()
        val segments = mutableListOf(LyricWord(firstTime, parts[0].replace(stamp, ""), times.first()))
        for (i in tags.indices) {
            segments.add(LyricWord(times[i], parts[i + 1], times.getOrNull(i + 1)))
        }
        val trimmed = trimWords(segments)
        if (trimmed.isEmpty()) return emptyList()
        val text = trimmed.joinToString("") { it.text }
        return stamps.mapNotNull { match ->
            val time = stampMs(match)
            if (time < 0) return@mapNotNull null
            val shift = time - firstTime + adjustment
            val words = trimmed.map { word ->
                word.copy(startMs = (word.startMs + shift).coerceAtLeast(0),
                    endMs = word.endMs?.plus(shift)?.coerceAtLeast(0))
            }
            LyricLine((time + adjustment).coerceAtLeast(0), text, words = words)
        }
    }

    internal fun trimWords(words: List<LyricWord>): List<LyricWord> {
        val trimmed = words.dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() }.toMutableList()
        if (trimmed.isEmpty()) return emptyList()
        trimmed[0] = trimmed.first().copy(text = trimmed.first().text.trimStart())
        trimmed[trimmed.lastIndex] = trimmed.last().copy(text = trimmed.last().text.trimEnd())
        return trimmed.filter { it.text.isNotEmpty() }
    }

    /* QRC and the three-number YRC share the [start,duration] ms header; QRC puts each word's
       text before its (start,dur) tag, YRC puts it after (start,dur,0). Both are absolute ms. */
    private fun parseMsLine(line: String, adjustment: Long): List<LyricLine> {
        val header = msHeader.find(line) ?: return emptyList()
        val lineStart = header.groupValues[1].toLongOrNull() ?: return emptyList()
        val rest = line.substring(header.value.length)
        val yrc = yrcWord.findAll(rest).toList()
        val qrc = qrcWord.findAll(rest).toList()
        val words = trimWords(when {
            yrc.isNotEmpty() -> parseYrcWords(rest, yrc)
            qrc.isNotEmpty() -> parseQrcWords(rest, qrc)
            else -> emptyList()
        })
        val text = if (yrc.isEmpty() && qrc.isEmpty()) rest.trim() else words.joinToString("") { it.text }
        if (text.isEmpty()) return emptyList()
        val adjusted = words.takeIf { it.isNotEmpty() }?.map { word ->
            LyricWord((word.startMs + adjustment).coerceAtLeast(0), word.text,
                word.endMs?.plus(adjustment)?.coerceAtLeast(0))
        }
        val lineEnd = header.groupValues[2].toLongOrNull()
            ?.takeIf { it > 0 && lineStart <= Long.MAX_VALUE - it }
            ?.let { runCatching { Math.addExact(lineStart + it, adjustment) }.getOrNull()?.coerceAtLeast(0) }
        return listOf(LyricLine((lineStart + adjustment).coerceAtLeast(0), text, words = adjusted,
            endMs = lineEnd.takeIf { adjusted == null }))
    }

    private fun parseYrcWords(rest: String, tags: List<MatchResult>): List<LyricWord> {
        val words = mutableListOf<LyricWord>()
        for (i in tags.indices) {
            val tag = tags[i]
            val start = tag.groupValues[1].toLongOrNull() ?: continue
            val duration = tag.groupValues[2].toLongOrNull() ?: continue
            val after = tag.range.last + 1
            val until = if (i + 1 < tags.size) tags[i + 1].range.first else rest.length
            /* Text before the first tag belongs to it, so the line stays complete. */
            val leading = if (i == 0) rest.substring(0, tag.range.first) else ""
            val segment = leading + rest.substring(after, until)
            if (segment.isEmpty()) continue
            words.add(LyricWord(start, segment, start + duration))
        }
        return words
    }

    private fun parseQrcWords(rest: String, tags: List<MatchResult>): List<LyricWord> {
        val words = mutableListOf<LyricWord>()
        for (i in tags.indices) {
            val tag = tags[i]
            val start = tag.groupValues[1].toLongOrNull() ?: continue
            val duration = tag.groupValues[2].toLongOrNull() ?: continue
            val from = if (i == 0) 0 else tags[i - 1].range.last + 1
            val segment = rest.substring(from, tag.range.first)
            if (segment.isEmpty()) continue
            words.add(LyricWord(start, segment, start + duration))
        }
        val trailing = rest.substring(tags.last().range.last + 1)
        if (trailing.isNotEmpty() && words.isNotEmpty()) {
            val last = words.last()
            words[words.size - 1] = last.copy(text = last.text + trailing)
        }
        return words
    }
}

/* 歌词时间校准：按「音源＋歌曲」记，正值=延后（歌词出现得更晚），负值=提前。
   判断当前行用 播放时间 − d。文件自身的 [offset:] 已在解析时应用，这里是额外修正。
   只保留最近 50 条，同名不同歌的 key 不同，不会串。 */
object LyricCalibration {
    const val LIMIT_MS = 5000L
    private const val MAX_ENTRIES = 50
    private const val PREFS = "lyric_calibration"

    private fun entries(context: Context): JSONArray = runCatching {
        JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("entries", "[]"))
    }.getOrDefault(JSONArray())

    fun load(context: Context, key: String): Long {
        val array = entries(context)
        for (i in 0 until array.length()) {
            val pair = array.optJSONArray(i) ?: continue
            if (pair.optString(0) == key) return pair.optLong(1, 0L).coerceIn(-LIMIT_MS, LIMIT_MS)
        }
        return 0L
    }

    fun save(context: Context, key: String, ms: Long) {
        val clamped = ms.coerceIn(-LIMIT_MS, LIMIT_MS)
        val array = entries(context)
        val next = JSONArray()
        next.put(JSONArray().put(key).put(clamped))
        for (i in 0 until array.length()) {
            val pair = array.optJSONArray(i) ?: continue
            if (pair.optString(0) == key) continue
            next.put(pair)
            if (next.length() >= MAX_ENTRIES) break
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("entries", next.toString()).apply()
    }

    fun describe(ms: Long): String = when {
        ms > 0 -> "延后 %.1f 秒".format(ms / 1000f)
        ms < 0 -> "提前 %.1f 秒".format(-ms / 1000f)
        else -> "无校准"
    }
}

/** Apple/AMLL lyric content, normalized into the same model as LRC. */
internal object TtmlLyrics {
    private const val TTML = "http://www.w3.org/ns/ttml"
    private const val METADATA = "$TTML#metadata"
    private const val STYLING = "$TTML#styling"
    private const val XML = "http://www.w3.org/XML/1998/namespace"
    private val number = Regex("\\d+(?:\\.\\d+)?")

    private data class Note(val role: String, val text: String)
    private class Capture(val role: String, val key: String? = null) { val text = StringBuilder() }
    private class Line(val start: Long, val end: Long?, val key: String?) {
        val words = mutableListOf<LyricWord>()
        val notes = mutableListOf<Note>()
        var timed = false
    }
    private data class Frame(
        val name: String, val start: Long, val end: Long?, val body: Boolean,
        val wordStart: Long?, val wordEnd: Long?, val preserveSpace: Boolean,
        val capture: Capture?, val ownsCapture: Boolean,
    )

    fun parse(raw: String): List<LyricLine> = try {
        require(raw.length <= 1_000_000)
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(StringReader(raw))
        }
        val frames = mutableListOf<Frame>()
        val lines = mutableListOf<Line>()
        val sidecar = mutableMapOf<String, MutableList<Note>>()
        var line: Line? = null
        var absolute = false
        var events = 0
        while (parser.nextToken() != XmlPullParser.END_DOCUMENT) {
            require(++events <= 200_000)
            when (parser.eventType) {
                XmlPullParser.DOCDECL -> error("DTD is not supported")
                XmlPullParser.START_TAG -> {
                    require(parser.depth <= 64)
                    val name = parser.name
                    val parent = frames.lastOrNull()
                    if (parent == null) {
                        require(name == "tt" && parser.namespace in listOf("", TTML, "http://www.w3.org/2006/10/ttaf1"))
                        absolute = (0 until parser.getNamespaceCount(parser.depth)).any {
                            val uri = parser.getNamespaceUri(it)
                            uri.contains("apple.com/") || uri == "http://www.example.com/ns/amll" ||
                                uri == "http://lrc.red/lyric-ttml-internal"
                        }
                    }
                    fun attr(name: String): String? = parser.getAttributeValue(null, name)
                    val origin = if (absolute) 0L else parent?.start ?: 0L
                    val start = attr("begin")?.let { addTime(origin, time(it)) } ?: parent?.start ?: 0L
                    val explicitEnd = attr("end")?.let { addTime(origin, time(it)) }
                        ?: attr("dur")?.let { addTime(start, time(it)) }
                    val end = listOfNotNull(explicitEnd, parent?.end).minOrNull()
                    require(end == null || end >= start)
                    val body = parent?.body == true || name == "body"
                    if (body && name == "p") {
                        require(line == null && lines.size < 5000)
                        val key = (0 until parser.attributeCount).firstOrNull { parser.getAttributeName(it) == "key" }
                            ?.let { parser.getAttributeValue(it) } ?: parser.getAttributeValue(XML, "id")
                        line = Line(start, end, key)
                    }
                    val role = parser.getAttributeValue(METADATA, "role")
                    val ruby = parser.getAttributeValue(STYLING, "ruby")
                    val reference = attr("for")
                    val sideRole = when {
                        frames.any { it.name == "translation" } -> "x-translation"
                        frames.any { it.name == "transliteration" } -> "x-roman"
                        else -> null
                    }
                    val capture = when {
                        !body && name == "text" && reference != null && sideRole != null -> Capture(sideRole, reference)
                        body && role in listOf("x-translation", "x-roman", "x-bg") -> Capture(role!!)
                        body && ruby in listOf("text", "textContainer") && parent?.capture?.role != "ruby" -> Capture("ruby")
                        else -> parent?.capture
                    }
                    val wordTimed = body && name == "span" && attr("begin") != null && capture == null
                    if (wordTimed) line?.timed = true
                    frames.add(Frame(name, start, end, body,
                        if (wordTimed) start else parent?.wordStart,
                        if (wordTimed) explicitEnd else parent?.wordEnd,
                        when (parser.getAttributeValue(XML, "space")) { "preserve" -> true; "default" -> false; else -> parent?.preserveSpace == true },
                        capture, capture != null && capture !== parent?.capture))
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF, XmlPullParser.IGNORABLE_WHITESPACE -> {
                    val frame = frames.lastOrNull() ?: continue
                    val value = parser.text ?: error("Unresolved XML entity")
                    // Ignore XML indentation, but retain actual spaces between timed words.
                    if (!frame.preserveSpace && value.isBlank() && value.any { it == '\n' || it == '\r' }) continue
                    if (frame.capture != null) frame.capture.text.append(value)
                    else line?.let { current ->
                        val word = LyricWord(frame.wordStart ?: current.start, value, frame.wordEnd)
                        val previous = current.words.lastOrNull()
                        if (previous != null && ((frame.wordStart == null && value.isBlank()) ||
                                previous.startMs == word.startMs && previous.endMs == word.endMs)) {
                            current.words[current.words.lastIndex] = previous.copy(text = previous.text + value)
                        } else current.words.add(word)
                    }
                }
                XmlPullParser.END_TAG -> {
                    val frame = frames.removeAt(frames.lastIndex)
                    if (frame.ownsCapture) {
                        val capture = frame.capture!!
                        val text = capture.text.toString().trim()
                        if (text.isNotEmpty()) {
                            val note = Note(capture.role, text)
                            if (capture.key != null) sidecar.getOrPut(capture.key) { mutableListOf() }.add(note)
                            else line?.notes?.add(note)
                        }
                    }
                    if (frame.body && frame.name == "p") {
                        lines.add(line!!)
                        line = null
                    }
                }
            }
        }
        require(frames.isEmpty())
        lines.mapNotNull { source ->
            val words = Lyrics.trimWords(source.words)
            if (words.isEmpty()) return@mapNotNull null
            val notes = source.notes + sidecar[source.key].orEmpty()
            val translations = notes.filter { it.role == "x-translation" }.map { it.text }
            val roman = notes.filter { it.role == "x-roman" }.map { it.text }
            val ruby = notes.filter { it.role == "ruby" }.joinToString("") { it.text }
            val background = notes.filter { it.role == "x-bg" }.map { it.text }
            LyricLine(source.start, words.joinToString("") { it.text }, translations.firstOrNull(),
                words.takeIf { source.timed }?.mapIndexed { index, word ->
                    word.copy(endMs = word.endMs ?: words.getOrNull(index + 1)?.startMs ?: source.end)
                }, (translations.drop(1) + roman + listOf(ruby) + background).filter { it.isNotBlank() }.distinct(),
                endMs = source.end.takeIf { !source.timed })
        }
    } catch (_: Exception) { emptyList() }

    private fun addTime(origin: Long, offset: Long): Long {
        require(origin <= Long.MAX_VALUE - offset)
        return origin + offset
    }

    private fun time(value: String): Long {
        require(value.length <= 32)
        val parts = value.split(':')
        val seconds = if (parts.size > 1) {
            require(parts.size in 2..3 && parts.dropLast(1).all { it.all(Char::isDigit) && it.isNotEmpty() })
            require(number.matches(parts.last()) && parts.last().toBigDecimal() < BigDecimal(60))
            require(parts[parts.lastIndex - 1].toBigDecimal() < BigDecimal(60))
            parts.fold(BigDecimal.ZERO) { total, part -> total * BigDecimal(60) + part.toBigDecimal() } * BigDecimal(1000)
        } else {
            val unit = when {
                value.endsWith("ms") -> "ms" to 1L
                value.endsWith("h") -> "h" to 3_600_000L
                value.endsWith("m") -> "m" to 60_000L
                value.endsWith("s") -> "s" to 1000L
                else -> "" to 1000L
            }
            val numeric = value.removeSuffix(unit.first)
            require(number.matches(numeric))
            numeric.toBigDecimal() * BigDecimal(unit.second)
        }
        return seconds.setScale(0, RoundingMode.DOWN).longValueExact()
    }
}
