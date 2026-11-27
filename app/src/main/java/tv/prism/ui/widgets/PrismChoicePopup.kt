package tv.prism.ui.widgets

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Text

/** Shared selection surface: focus starts on the current option; Back dismisses without choosing. */
@Composable
internal fun <T> ChoicePopup(
    options: List<T>,
    selectedIndex: Int,
    label: (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
    description: ((T) -> String)? = null,
    checked: ((T) -> Boolean)? = null,
    icon: @Composable (T) -> Unit,
    /* The trigger's bounds, so the sheet opens under it. A Dialog owns a real window and
       reliably receives the remote on its first OK; a focusable Popup did not. */
    anchor: Rect = Rect.Zero,
) {
    val c = LocalPrismColors.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val original = window?.attributes?.let { WindowManager.LayoutParams().apply { copyFrom(it) } }
            window?.setDimAmount(0f)
            onDispose { if (original != null) window?.attributes = original }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Keep requesters on their rows. An external selection updates the checkmark,
            // without detaching the focused node or interrupting the user's browsing.
            val rowFocus = remember(options.size) { List(options.size) { FocusRequester() } }
            val emptyFocus = remember { FocusRequester() }
            val targetIndex = selectedIndex.takeIf { it in options.indices } ?: 0
            /* Which row actually owns the remote, not which one we asked for: the dialog
               can report its window before its rows accept focus, and that first request is
               sometimes dropped. Keep asking until the selected row really holds it. */
            var focusedIndex by remember { mutableIntStateOf(-1) }
            var userMoved by remember { mutableStateOf(false) }
            val windowFocused = LocalWindowInfo.current.isWindowFocused
            /* A fresh window starts in touch mode on this TV image: the first OK only
               switches input mode and is swallowed, so put the window in keyboard mode. */
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(Unit) {
                withFrameNanos {}
                inputMode.requestInputMode(InputMode.Keyboard)
            }
            LaunchedEffect(rowFocus, options.size, targetIndex) {
                if (options.isEmpty()) {
                    focusedIndex = -1
                    userMoved = false
                    return@LaunchedEffect
                }
                if (focusedIndex !in options.indices) userMoved = false
                repeat(30) {
                    if (userMoved || focusedIndex == targetIndex) return@LaunchedEffect
                    runCatching { (rowFocus.getOrNull(targetIndex) ?: emptyFocus).requestFocus() }
                    withFrameNanos {}
                }
            }
            LaunchedEffect(windowFocused) {
                if (windowFocused && !userMoved && focusedIndex != targetIndex)
                    runCatching { (rowFocus.getOrNull(targetIndex) ?: emptyFocus).requestFocus() }
            }
            val density = LocalDensity.current
            val hasAnchor = anchor != Rect.Zero
            val menuWidthPx = with(density) { 268.dp.toPx() }
            val menuMaxHeightPx = with(density) { 280.dp.toPx() }
            val edgeMarginPx = with(density) { 16.dp.toPx() }
            val endPad = if (!hasAnchor) 24.dp else with(density) {
                val maxEnd = (constraints.maxWidth - menuWidthPx - edgeMarginPx).coerceAtLeast(0f)
                (constraints.maxWidth - anchor.right).coerceIn(edgeMarginPx.coerceAtMost(maxEnd), maxEnd).toDp()
            }
            val topPad = if (!hasAnchor) 24.dp else with(density) {
                val maxTop = (constraints.maxHeight - menuMaxHeightPx - edgeMarginPx).coerceAtLeast(0f)
                (anchor.bottom + 6.dp.toPx()).coerceIn(edgeMarginPx.coerceAtMost(maxTop), maxTop).toDp()
            }
            Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { onDismiss() } })
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier.align(Alignment.TopEnd)
                    .padding(top = topPad, end = endPad)
                    .width(268.dp)
                    .heightIn(max = 280.dp)
                    .onPreviewKeyEvent { event ->
                        /* Once the user starts walking the list, stop steering the cursor. */
                        if (event.type == KeyEventType.KeyDown &&
                            event.key in setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)
                        ) userMoved = true
                        false
                    }
                    .focusGroup()
                    .clip(shape)
                    .background(c.panel)
                    .border(1.dp, c.line, shape)
                    .padding(horizontal = 6.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (checked == null && options.isNotEmpty() && selectedIndex !in options.indices) {
                    Text("当前值不可用，请重新选择", color = c.muted, fontSize = 12.sp, lineHeight = 17.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp))
                }
                if (options.isEmpty())
                    SettingsButton(
                        "暂无可选项 · 返回",
                        onDismiss,
                        Modifier.fillMaxWidth().focusRequester(emptyFocus),
                    )
                options.forEachIndexed { index, option ->
                    val chosen = checked?.invoke(option) ?: (index == selectedIndex)
                    val rowShape = RoundedCornerShape(8.dp)
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .focusRequester(rowFocus[index])
                            .onFocusChanged { if (it.isFocused) focusedIndex = index }
                            .auraFocus(rowShape, 1f)
                            .clip(rowShape)
                            .background(if (chosen) c.accent.copy(alpha = .12f) else Color.Transparent)
                            .then(
                                if (checked == null)
                                    Modifier.selectable(chosen, role = Role.RadioButton, onClick = { onSelect(option) })
                                else Modifier.toggleable(chosen, role = Role.Checkbox) { onSelect(option) }
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        icon(option)
                        Column(Modifier.weight(1f)) {
                            Text(
                                label(option),
                                color = c.foreground,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            description?.let { Text(it(option), color = c.muted, fontSize = 12.sp, lineHeight = 16.sp) }
                        }
                        if (chosen) Glyph("check", Modifier.size(14.dp), c.accent)
                    }
                }
            }
        }
    }
}
