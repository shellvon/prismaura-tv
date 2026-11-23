package tv.prism.lyrics

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

internal fun parseLyricsFile(raw: String): List<LyricLine>? {
    if (raw.length !in 1..524288) return null
    val options = LoaderOptions().apply { maxAliasesForCollections = 0 }
    val document = try {
        Yaml(SafeConstructor(options)).load<Any?>(raw) as? Map<*, *>
    } catch (_: RuntimeException) {
        null
    } ?: return null
    val rows = document["lines"] as? List<*> ?: return null
    val lines = rows.map {
        val row = it as? Map<*, *> ?: return null
        val text = row["text"] as? String ?: return null
        val start = row.millis("start_ms") ?: return null
        val end = row.millis("end_ms")?.takeIf { it > start }
        val words = (row["words"] as? List<*>)?.mapNotNull { entry ->
            val word = entry as? Map<*, *> ?: return@mapNotNull null
            val time = word.millis("start_ms") ?: return@mapNotNull null
            val part = word["text"] as? String ?: return@mapNotNull null
            LyricWord(time, part, word.millis("end_ms")?.takeIf { it > time })
        }?.takeIf { parts ->
            parts.size > 1 && parts.first().startMs >= start &&
                (end == null || parts.last().startMs < end) &&
                parts.zipWithNext().all { (a, b) -> a.startMs < b.startMs } &&
                parts.joinToString("") { it.text }.trim() == text.trim()
        }
        LyricLine(start, text, words = words, endMs = end)
    }
    return lines.sortedBy { it.timeMs }.takeIf { lines.any { it.words != null } }
}

private fun Map<*, *>.millis(key: String) =
    (this[key] as? Number)?.toLong()?.takeIf { it >= 0 }
