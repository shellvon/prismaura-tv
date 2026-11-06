package tv.prism

data class LyricWord(val startMs: Long, val text: String, val endMs: Long? = null)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    val words: List<LyricWord>? = null,
    val additionalLines: List<String> = emptyList(),
) {
    val supportingLines: List<String> get() = (listOfNotNull(translation) + additionalLines)
        .filter { it.isNotBlank() && it != text }.distinct()
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
            main.copy(additionalLines = group.flatMap { listOf(it.text) + it.supportingLines }
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
        return lines.sortedBy { it.timeMs }
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
        if (text.isEmpty()) return emptyList()
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
        return listOf(LyricLine((lineStart + adjustment).coerceAtLeast(0), text, words = adjusted))
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
