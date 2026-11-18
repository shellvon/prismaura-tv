package tv.prism

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import java.util.function.Consumer

internal enum class PrismMenuSection(val title: String) {
    COMMON("常用"), RELATED("相关"), MANAGEMENT("管理"),
}

internal data class PrismMenuAction(
    val label: String, val icon: String, val active: Boolean = false,
    val description: String = "", val navigates: Boolean = false,
    val destructive: Boolean = false, val enabled: Boolean = true,
    val keepOpen: Boolean = false,
    val section: PrismMenuSection = if (destructive) PrismMenuSection.MANAGEMENT else PrismMenuSection.COMMON,
    val run: () -> Unit,
)

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun PrismActionMenu(
    title: String, subtitle: String = "", song: Song? = null,
    actions: List<PrismMenuAction>, onDismiss: () -> Unit, notice: String = "",
) {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    val reveal = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val closeFocus = remember { FocusRequester() }
    val rowFocus = remember(actions.size) { List(actions.size) { FocusRequester() } }
    val enabledRows = actions.indices.filter { actions[it].enabled }
    val first = enabledRows.firstOrNull { !actions[it].destructive }
    val showSections = actions.map { it.section }.distinct().size > 1
    var focusedRow by remember { mutableIntStateOf(-1) }
    var previousCount by remember { mutableIntStateOf(actions.size) }
    val scroll = rememberScrollState()
    val rowCenters = remember { mutableMapOf<Int, Float>() }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var visibleNotice by remember { mutableStateOf("") }
    LaunchedEffect(notice) {
        visibleNotice = notice
        if (notice.isNotBlank()) { delay(2200); visibleNotice = "" }
    }
    LaunchedEffect(focusedRow, viewportHeight) {
        if (focusedRow < 0 || viewportHeight == 0) return@LaunchedEffect
        withFrameNanos { }
        val center = rowCenters[focusedRow] ?: return@LaunchedEffect
        val target = (center - viewportHeight / 2f).roundToInt().coerceIn(0, scroll.maxValue)
        if (motion) scroll.animateScrollTo(target) else scroll.scrollTo(target)
    }
    LaunchedEffect(enabledRows, rowFocus) {
        val changed = previousCount != actions.size
        previousCount = actions.size
        if (!closing && focusedRow >= 0 && (changed || focusedRow !in enabledRows)) {
            /* 条目数量变了，rowFocus 就是新建的一批 FocusRequester，要等这一帧的重组+布局
               把它们挂上去。withFrameNanos 的回调跑在重组之前，只等一帧会拿到未初始化的
               requester（IllegalStateException），所以等两帧。 */
            repeat(2) { withFrameNanos { } }
            if (!closing) (first?.let { rowFocus.getOrNull(it) } ?: closeFocus).requestFocus()
        }
    }
    // Actions own their state transition: close, navigate, or replace this menu with a confirmation.
    val dismiss: ((() -> Unit)?) -> Unit = { action ->
        if (!closing) {
            closing = true
            scope.launch {
                reveal.animateTo(0f, tween(if (motion) 140 else 0))
                if (action == null) onDismiss() else action()
            }
        }
    }
    Dialog(onDismissRequest = { dismiss(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val original = window?.attributes?.let { WindowManager.LayoutParams().apply { copyFrom(it) } }
            window?.setDimAmount(.28f)
            // Android controls blur support and can disable it at runtime; dimming remains available.
            val manager = view.context.getSystemService(WindowManager::class.java)
            val listener = if (Build.VERSION.SDK_INT >= 31 && window != null) Consumer<Boolean> { enabled ->
                if (enabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.apply { blurBehindRadius = 12 }
                } else window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            }.also { manager.addCrossWindowBlurEnabledListener(it) } else null
            onDispose {
                if (Build.VERSION.SDK_INT >= 31 && listener != null) manager.removeCrossWindowBlurEnabledListener(listener)
                if (original != null) window?.attributes = original
            }
        }
        LaunchedEffect(Unit) {
            repeat(2) { withFrameNanos {} }
            if (closing) return@LaunchedEffect
            (first?.let { rowFocus[it] } ?: closeFocus).requestFocus()
            reveal.animateTo(1f, tween(if (motion) 200 else 0, easing = FastOutSlowInEasing))
        }
        BoxWithConstraints(Modifier.fillMaxSize().onPreviewKeyEvent {
            if (it.key == Key.Menu) {
                if (it.type == KeyEventType.KeyUp) dismiss(null)
                true
            } else false
        }, contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { dismiss(null) } })
            val shape = RoundedCornerShape(24.dp)
            Column(Modifier.padding(24.dp).width(560.dp).heightIn(max = (maxHeight - 48.dp).coerceAtLeast(120.dp)).graphicsLayer {
                alpha = reveal.value
                translationY = (1f - reveal.value) * 40.dp.toPx()
            }.clip(shape).background(Brush.verticalGradient(listOf(lerp(c.panel, c.foreground, .035f), c.panel, lerp(c.panel, c.background, .25f))))
                .border(1.dp, c.line.copy(alpha = .6f), shape)
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (song != null) CoverArt(song, Modifier.size(42.dp), radius = 6.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(title, color = c.foreground, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle.isNotBlank()) Text(subtitle, color = c.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.size(38.dp).focusRequester(closeFocus).focusProperties {
                        up = FocusRequester.Cancel; down = enabledRows.firstOrNull()?.let { rowFocus[it] } ?: FocusRequester.Cancel
                        left = FocusRequester.Cancel; right = FocusRequester.Cancel
                    }.onFocusChanged { if (it.isFocused) focusedRow = -1 }
                        .auraFocus(RoundedCornerShape(50), 1.04f).semantics { contentDescription = "关闭菜单" }
                        .tvClickable({ dismiss(null) }, { dismiss(null) }), contentAlignment = Alignment.Center) {
                        Glyph("close", Modifier.size(20.dp), c.muted)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line.copy(alpha = .45f)))
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).onSizeChanged { viewportHeight = it.height }
                    .verticalScroll(scroll).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    actions.forEachIndexed { index, action ->
                        val at = enabledRows.indexOf(index)
                        if (showSections && (index == 0 || action.section != actions[index - 1].section)) {
                            if (index > 0) Box(Modifier.fillMaxWidth().padding(vertical = 5.dp).height(1.dp).background(c.line.copy(alpha = .5f)))
                            Text(action.section.title, color = c.muted, fontSize = 11.sp,
                                modifier = Modifier.padding(start = 12.dp, top = 3.dp, bottom = 3.dp))
                        }
                        ActionMenuRow(action, { if (action.keepOpen) action.run() else dismiss(action.run) }, { dismiss(null) }, Modifier.focusRequester(rowFocus[index])
                            .onGloballyPositioned { rowCenters[index] = it.positionInParent().y + it.size.height / 2f }
                            .onFocusChanged { if (it.isFocused) focusedRow = index }.focusProperties {
                            canFocus = action.enabled
                            up = enabledRows.getOrNull(at - 1)?.let { rowFocus[it] } ?: closeFocus
                            down = enabledRows.getOrNull(at + 1)?.let { rowFocus[it] } ?: FocusRequester.Cancel
                            left = FocusRequester.Cancel; right = FocusRequester.Cancel
                        })
                    }
                }
                Text(visibleNotice.ifBlank { "↑ ↓ 选择    OK 确认    返回或长按关闭" },
                    color = if (visibleNotice.isBlank()) c.muted else c.foreground, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().heightIn(min = 18.dp))
            }
        }
    }
}

@Composable
private fun ActionMenuRow(action: PrismMenuAction, onClick: () -> Unit, onLongPress: () -> Unit, modifier: Modifier) {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    val press = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val ink = if (action.destructive) {
        if (c.panel.luminance() > .5f) Color(0xFF9B3C30) else Color(0xFFE3A098)
    } else c.foreground
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .then(if (action.enabled) Modifier.auraFocus(RoundedCornerShape(14.dp), 1.035f, ring = if (action.destructive) ink else null).tvClickable({
            if (motion) scope.launch { press.snapTo(.975f); press.animateTo(1f, tween(160)) }
            onClick()
        }, onLongPress) else Modifier)
        .graphicsLayer { alpha = if (action.enabled) 1f else .42f; scaleX = press.value; scaleY = press.value }
        .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(32.dp).background((if (action.destructive) ink else c.accent).copy(alpha = .10f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Glyph(action.icon, Modifier.size(20.dp), if (action.destructive) ink else c.accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(action.label, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (action.description.isNotBlank()) Text(action.description, color = c.muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (action.navigates) Glyph("chevR", Modifier.size(12.dp), c.muted)
        else if (action.active) Glyph("check", Modifier.size(15.dp), c.accent)
    }
}
