package tv.prism

/** Numeric versions and SemVer prereleases; short legacy versions such as "1" stay usable. */
internal fun compareVersions(candidate: String, installed: String): Int? {
    val pattern = Regex("^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")
    fun parse(value: String) = pattern.matchEntire(value.ifBlank { "0.0.0" })
    val next = parse(candidate) ?: return null
    val old = parse(installed) ?: return null
    for (i in 1..3) {
        val difference = next.groupValues[i].ifBlank { "0" }.toBigInteger().compareTo(old.groupValues[i].ifBlank { "0" }.toBigInteger())
        if (difference != 0) return difference
    }
    val nextPre = next.groupValues[4]
    val oldPre = old.groupValues[4]
    if (nextPre == oldPre) return 0
    if (nextPre.isEmpty()) return 1
    if (oldPre.isEmpty()) return -1
    val left = nextPre.split('.')
    val right = oldPre.split('.')
    for (i in 0 until minOf(left.size, right.size)) {
        val a = left[i]; val b = right[i]
        val numberA = a.toBigIntegerOrNull(); val numberB = b.toBigIntegerOrNull()
        val difference = when {
            numberA != null && numberB != null -> numberA.compareTo(numberB)
            numberA != null -> -1
            numberB != null -> 1
            else -> a.compareTo(b)
        }
        if (difference != 0) return difference
    }
    return left.size.compareTo(right.size)
}
