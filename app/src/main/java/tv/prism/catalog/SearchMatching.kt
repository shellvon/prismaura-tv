package tv.prism.catalog

import android.icu.text.Transliterator
import android.os.Build
import androidx.annotation.RequiresApi

internal fun matchesSearchHistory(value: String, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty() || value.lowercase().contains(q)) return true
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    return runCatching { PinyinMatcher.matches(value, q) }.getOrDefault(false)
}

@RequiresApi(Build.VERSION_CODES.Q)
private object PinyinMatcher {
    private val transliterator = object : ThreadLocal<Transliterator>() {
        override fun initialValue() = Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower")
    }

    fun matches(value: String, query: String): Boolean {
        val converter = checkNotNull(transliterator.get())
        val parts = value.map { converter.transliterate(it.toString()).trim() }
        return parts.joinToString("").contains(query) ||
            parts.mapNotNull { it.firstOrNull() }.joinToString("").startsWith(query)
    }
}
