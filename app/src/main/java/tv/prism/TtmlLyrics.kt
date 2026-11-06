package tv.prism

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.math.BigDecimal
import java.math.RoundingMode

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
                            uri.contains("apple.com/") || uri == "http://www.example.com/ns/amll"
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
                }, (translations.drop(1) + roman + listOf(ruby) + background).filter { it.isNotBlank() }.distinct())
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
