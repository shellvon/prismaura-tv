package tv.prism

import java.util.ArrayDeque

/** A stable playback permutation; the source list and its indices never change when shuffled. */
internal class PlaybackOrder private constructor(private val keys: List<String>, val indices: List<Int>) {
    constructor() : this(emptyList(), emptyList())

    fun updated(nextKeys: List<String>): PlaybackOrder {
        if (keys == nextKeys) return this
        // Match occurrences separately, so repeated songs still occupy separate queue positions.
        val positions = mutableMapOf<String, ArrayDeque<Int>>()
        nextKeys.forEachIndexed { index, key -> positions.getOrPut(key) { ArrayDeque() }.addLast(index) }
        val kept = indices.mapNotNull { positions[keys[it]]?.pollFirst() }
        val added = positions.values.flatMap { it.toList() }.sorted()
        return PlaybackOrder(nextKeys.toList(), kept + added)
    }

    fun randomized(current: Int): PlaybackOrder = PlaybackOrder(keys,
        listOfNotNull(current.takeIf { it in keys.indices }) + keys.indices.filter { it != current }.shuffled())

    fun restored(saved: List<Int>, current: Int, shuffle: Boolean): PlaybackOrder =
        if (saved.size == keys.size && saved.toSet().size == keys.size && saved.all { it in keys.indices })
            PlaybackOrder(keys, saved.toList())
        else if (shuffle) randomized(current) else this

    fun placed(index: Int, current: Int, next: Boolean): PlaybackOrder {
        if (index !in keys.indices || index == current) return this
        val updated = indices.filterNot { it == index }.toMutableList()
        val at = updated.indexOf(current)
        updated.add(if (next && at >= 0) at + 1 else updated.size, index)
        return PlaybackOrder(keys, updated)
    }
}
