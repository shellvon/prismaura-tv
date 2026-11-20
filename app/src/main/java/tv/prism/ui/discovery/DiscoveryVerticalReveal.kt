package tv.prism.ui.discovery

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.ui.input.key.Key

@OptIn(ExperimentalFoundationApi::class)
internal class DiscoveryVerticalReveal(
    private val delegate: BringIntoViewSpec,
) : BringIntoViewSpec by delegate {
    private var horizontalUntil = 0L

    fun onKey(key: Key) {
        horizontalUntil = when (key) {
            Key.DirectionLeft, Key.DirectionRight -> SystemClock.uptimeMillis() + 350
            else -> 0L
        }
    }

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // A sibling's focus reveal can arrive a frame after the D-pad event.
        if (SystemClock.uptimeMillis() < horizontalUntil) return 0f
        return delegate.calculateScrollDistance(offset, size, containerSize)
    }
}
