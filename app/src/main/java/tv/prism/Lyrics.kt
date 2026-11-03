package tv.prism

data class LyricLine(val timeMs: Long, val text: String, val translation: String? = null)

/** LRC can have multiple timestamps on one line and a global offset. */
object Lyrics {
    private val stamp = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
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
        val original = parseLines(raw)
        val translated = parseLines(translation)
        return original.map { line ->
            val match = translated.minByOrNull { kotlin.math.abs(it.timeMs - line.timeMs) }
            line.copy(translation = match?.takeIf { kotlin.math.abs(it.timeMs - line.timeMs) <= 150 }?.text)
        }
    }

    private fun parseLines(raw: String): List<LyricLine> {
        val adjustment = offset.find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val lines = mutableListOf<LyricLine>()
        raw.lineSequence().take(5000).forEach { line ->
            val matches = stamp.findAll(line).toList()
            /* Some LRC files carry a BOM inside the line, right before the first timestamp;
               it used to leave "词：…" alive and push the credits on screen. */
            val text = stamp.replace(line, "").replace("\uFEFF", "").trim()
            if (text.isEmpty() || isCredit(text)) return@forEach
            matches.forEach timestamp@ { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: return@timestamp
                val seconds = match.groupValues[2].toLongOrNull() ?: return@timestamp
                if (seconds >= 60) return@timestamp
                val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                lines.add(LyricLine((minutes * 60_000 + seconds * 1000 + fraction + adjustment).coerceAtLeast(0), text))
            }
        }
        return lines.sortedBy { it.timeMs }
    }
}
