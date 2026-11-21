package tv.prism.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.prism.ui.widgets.FocusAnchor
import tv.prism.ui.widgets.LocalPrismColors
import tv.prism.ui.widgets.AuraBrand
import tv.prism.ui.widgets.PrismNav
import tv.prism.ui.widgets.AuraRemotePill
import tv.prism.ui.widgets.AuraClock

@Composable
internal fun TopNavigation(
    page: String,
    navTabs: List<Pair<String, FocusRequester>>,
    pageEntryFocus: FocusAnchor?,
    onPreviewPage: (String) -> Unit,
    onEnterPage: (String) -> Unit,
    onNavFocusChanged: (Boolean) -> Unit,
    onOpenPhone: () -> Unit,
) {
    val c = LocalPrismColors.current
    val pageScope = rememberCoroutineScope()
    val remotePillFocus = remember { FocusRequester() }
    var navBarFocused by remember { mutableStateOf(false) }
    var focusedTab by remember { mutableStateOf<String?>(null) }
    var remotePillFocused by remember { mutableStateOf(false) }
    var pendingTab by remember { mutableStateOf<String?>(null) }
    var tabPreview by remember { mutableStateOf<Job?>(null) }
    fun clearTabPreview() {
        tabPreview?.cancel()
        tabPreview = null
        pendingTab = null
    }
    fun commitPage(page: String) {
        clearTabPreview()
        onEnterPage(page)
    }
    fun navModifier(index: Int): Modifier {
        val (tab, requester) = navTabs[index]
        return Modifier.focusRequester(requester)
            .onFocusChanged {
                if (it.isFocused) focusedTab = tab
                else if (focusedTab == tab) focusedTab = null
            }
            .focusProperties { pageEntryFocus?.takeIf { it.attached }?.let { down = it.requester } }
    }

    Row(Modifier.fillMaxWidth().onFocusChanged { state ->
            navBarFocused = state.hasFocus
            onNavFocusChanged(state.hasFocus)
        }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (event.key == Key.DirectionDown && !remotePillFocused) {
                pendingTab?.let { commitPage(it); return@onPreviewKeyEvent true }
            }
            val step = when (event.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> return@onPreviewKeyEvent false }
            /* The remote pill is the bar's right end but an action, not a page:
               Left returns to the current tab without previewing; Right is a wall. */
            if (remotePillFocused) {
                if (step < 0) navTabs.firstOrNull { it.first == page }?.second?.requestFocus()
                return@onPreviewKeyEvent true
            }
            val index = navTabs.indexOfFirst { it.first == (focusedTab ?: page) }
            val next = navTabs.getOrNull(index + step)
            if (next == null) {
                if (step > 0 && index == navTabs.lastIndex) {
                    pendingTab?.let(onPreviewPage)
                    clearTabPreview()
                    remotePillFocus.requestFocus()
                }
                return@onPreviewKeyEvent index >= 0
            }
            clearTabPreview()
            if (next.second.requestFocus()) {
                pendingTab = next.first
                val repeating = event.nativeKeyEvent.repeatCount > 0
                tabPreview = pageScope.launch {
                    if (repeating) delay(150)
                    // Render the focus indicator before composing the next page.
                    repeat(2) { withFrameNanos {} }
                    if (navBarFocused && focusedTab == next.first) onPreviewPage(next.first)
                    pendingTab = null
                }
            }
            true
        }.focusGroup(), verticalAlignment = Alignment.CenterVertically) {
        AuraBrand()
        Spacer(Modifier.width(38.dp))
        PrismNav("首页", (focusedTab ?: page) == "home", { commitPage("home") }, navModifier(0), focused = focusedTab == "home")
        PrismNav("搜索", (focusedTab ?: page) == "search", { commitPage("search") }, navModifier(1), focused = focusedTab == "search")
        PrismNav("我的", (focusedTab ?: page) == "library", { commitPage("library") }, navModifier(2), focused = focusedTab == "library")
        PrismNav("发现", (focusedTab ?: page) == "discover", { commitPage("discover") }, navModifier(3), focused = focusedTab == "discover")
        Spacer(Modifier.width(9.dp))
        Box(Modifier.width(1.dp).height(13.dp).background(c.foreground.copy(alpha = .12f)))
        Spacer(Modifier.width(9.dp))
        PrismNav("设置", (focusedTab ?: page) == "plugins", { commitPage("plugins") }, navModifier(4), focused = focusedTab == "plugins", quiet = true)
        Spacer(Modifier.weight(1f))
        AuraRemotePill(Modifier.focusRequester(remotePillFocus)
            .onFocusChanged { remotePillFocused = it.isFocused }
            .focusProperties { pageEntryFocus?.takeIf { it.attached }?.let { down = it.requester } },
            onClick = { onOpenPhone() })
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(0.5.dp).height(9.dp).background(c.foreground.copy(alpha = .12f)))
        Spacer(Modifier.width(12.dp))
        AuraClock()
    }
}
