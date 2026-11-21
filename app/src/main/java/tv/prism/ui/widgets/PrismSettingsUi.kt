package tv.prism.ui.widgets

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import tv.prism.updates.AppUpdateState
import tv.prism.BuildConfig
import tv.prism.remote.qrCodeBitmap

/* One switch style everywhere: the whole row owns focus, OK toggles, the capsule slides. */
@Composable
internal fun PrismSwitchRow(
    label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, description: String? = null,
) {
    val c = LocalPrismColors.current
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).rowFocus()
        .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = c.foreground, fontSize = 15.sp, lineHeight = 21.sp)
            if (description != null) Text(description, color = c.muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PrismCapsule(checked)
    }
}

@Composable
internal fun PrismCapsule(checked: Boolean) {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    val t by animateFloatAsState(if (checked) 1f else 0f, tween(if (motion) 180 else 0), label = "capsule switch")
    Box(Modifier.size(42.dp, 22.dp).clip(RoundedCornerShape(50))
        .background(Brush.verticalGradient(listOf(
            lerp(c.foreground.copy(alpha = .09f), lerp(c.accent, Color.White, .05f), t),
            lerp(c.foreground.copy(alpha = .09f), lerp(c.accent, c.glow, .35f), t))))
        .border(1.dp, lerp(c.foreground.copy(alpha = .09f), c.accent.copy(alpha = .75f), t), RoundedCornerShape(50)),
        contentAlignment = Alignment.CenterStart) {
        Box(Modifier.padding(start = 2.dp).offset(x = 20.dp * t).size(17.dp)
            .clip(CircleShape).background(lerp(c.muted, c.onAccent, t)))
    }
}

@Composable
internal fun SettingsCard(
    title: String? = null, hint: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalPrismColors.current
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 8.dp)) {
                Text(title, Modifier.weight(1f).alignByBaseline(), color = c.muted, fontSize = 13.sp, lineHeight = 18.sp,
                    letterSpacing = .4.sp, maxLines = 1)
                if (hint != null) Text(hint, Modifier.alignByBaseline(), color = c.muted, fontSize = 12.sp, lineHeight = 18.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(c.foreground.copy(alpha = .04f)).padding(4.dp), content = content)
    }
}

@Composable
internal fun SettingsDivider() {
    val c = LocalPrismColors.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(c.foreground.copy(alpha = .06f)))
}

/* The whole row owns focus: left gold bar + row highlight. Read-only rows (device,
   WebView) pass no callback and are never focusable. 左右键不归行：设置页左键是回导航栏，
   行内只认 OK——所以这里没有 onLeft/onRight，别再把左键借给「改个值」。 */
@Composable
internal fun SettingsRow(
    label: String, description: String? = null, labelDim: Boolean = false,
    modifier: Modifier = Modifier,
    onActivate: (() -> Unit)? = null,
    control: @Composable RowScope.() -> Unit,
) {
    val c = LocalPrismColors.current
    val actionable = onActivate != null
    Row(modifier.fillMaxWidth().heightIn(min = if (actionable) 56.dp else 48.dp)
        .then(if (!actionable) Modifier else Modifier.rowFocus()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false
                else when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (onActivate != null) { onActivate(); true } else false
                    }
                    else -> false
                }
            }
            .focusable())
        .padding(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = if (labelDim) c.muted else c.foreground, fontSize = 15.sp, lineHeight = 21.sp)
            if (description != null) Text(description, color = c.muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        control()
    }
}

/* 设置页是两栏：左边那一栏是导航，所以「左键」属于左栏，行内不能再借它改值。
   改值只剩 OK（原地循环），控件也就得长成「可按的东西」——一枚显示当前值的胶囊，
   右边一个小循环标记，和布尔开关同族：按下去，这一格自己变。
   （播放页的弹层没有左栏，左键空闲，那里仍然用 ‹ 值 › 让左右键直接改。）
   值夹在胶囊里，宽度给下限，循环时左边缘不会跳。 */
@Composable
internal fun <T> SettingsSeg(
    options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPrismColors.current
    val index = options.indexOfFirst { it.first == selected }
    val shape = RoundedCornerShape(9.dp)
    Row(modifier.height(34.dp).clip(shape)
        .background(c.foreground.copy(alpha = .06f))
        .border(1.dp, c.foreground.copy(alpha = .10f), shape)
        .focusProperties { canFocus = false }
        .clickable { if (options.isNotEmpty()) onSelect(options[(index + 1).mod(options.size)].first) }
        .padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(options.getOrNull(index)?.second.orEmpty(), Modifier.widthIn(min = 72.dp),
            color = c.foreground, fontSize = 14.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1)
        Glyph("refresh", Modifier.size(11.dp), c.accent.copy(alpha = .55f))
    }
}

/* Checked state must read as a Checkbox to accessibility, not just a gold fill with a tick. */
@Composable
internal fun SettingsChip(label: String, checked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    val shape = RoundedCornerShape(6.dp)
    Row(modifier.auraFocus(RoundedCornerShape(10.dp), 1f, ring = c.foreground)
        .clip(RoundedCornerShape(10.dp))
        .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onClick() })
        .padding(4.dp).height(32.dp)
        .clip(shape)
        .then(if (checked) Modifier.background(Brush.verticalGradient(listOf(c.accent, lerp(c.accent, c.glow, .35f))))
            else Modifier.border(1.dp, c.foreground.copy(alpha = .10f), shape))
        .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.5.dp)) {
        if (checked) Glyph("check", Modifier.size(8.dp), c.onAccent)
        Text(label, color = if (checked) c.onAccent else c.muted, fontSize = 12.sp,
            fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
internal fun SettingsRailItem(label: String, icon: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onFocused: () -> Unit) {
    val c = LocalPrismColors.current
    val shape = RoundedCornerShape(10.dp)
    var focused by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().height(46.dp).auraFocus(shape, 1f)
        .clip(shape)
        .then(if (selected) Modifier.background(Brush.horizontalGradient(listOf(c.accent.copy(alpha = .17f), c.accent.copy(alpha = .05f), Color.Transparent))) else Modifier)
        .onFocusChanged {
            focused = it.isFocused
            if (focused) onFocused()
        }
        .clickable(onClick = onClick)
        .semantics { this.selected = selected }) {
        /* The gold bar sits where the focus ring is, so both together would read as a
           double line; when focused, the ring alone marks the item. */
        if (selected && !focused) Box(Modifier.align(Alignment.CenterStart).padding(start = 1.dp).size(2.dp, 24.dp).background(c.accent))
        Row(Modifier.fillMaxSize().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Glyph(icon, Modifier.size(20.dp), if (selected) c.accent else c.muted)
            Text(label, color = if (selected) c.foreground else c.muted, fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
        }
    }
}

/* Settings page actions. passive shows the button inline: focus lives on the whole
   row and the button is not its own focus stop (mouse clicks still work). */
@Composable
internal fun SettingsButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    primary: Boolean = false, icon: String? = null, passive: Boolean = false,
) {
    val c = LocalPrismColors.current
    val shape = RoundedCornerShape(9.dp)
    val contentColor = if (primary) c.onAccent else c.foreground
    Row(modifier.height(36.dp).then(if (passive) Modifier else Modifier.auraFocus(shape, 1f, ring = if (primary) c.foreground else null))
        .clip(shape)
        .then(if (passive) Modifier.focusProperties { canFocus = false } else Modifier)
        .then(if (primary) Modifier.background(Brush.verticalGradient(listOf(lerp(c.accent, Color.White, .05f), lerp(c.accent, c.glow, .35f))))
            else Modifier.background(c.foreground.copy(alpha = .07f)))
        .clickable(onClick = onClick)
        .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Glyph(icon, Modifier.size(18.dp), if (primary) c.onAccent else c.muted)
        Text(label, color = contentColor, fontSize = 15.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable fun AuraThemeCard(label: String, subtitle: String, colors: PrismColors, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.auraFocus(shape, 1f).clip(shape)
        .background(if (selected) c.accent.copy(alpha = .07f) else c.background.copy(alpha = .5f))
        .border(1.dp, if (selected) c.accent.copy(alpha = .65f) else c.line.copy(alpha = .6f), shape)
        .semantics { this.selected = selected }
        .clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().height(86.dp).background(colors.background)) {
            Canvas(Modifier.align(Alignment.Center).size(26.dp)) {
                val prism = Path().apply {
                    moveTo(size.width / 2, size.height * .16f)
                    lineTo(size.width * .17f, size.height * .84f)
                    lineTo(size.width * .83f, size.height * .84f)
                    close()
                }
                drawPath(prism, colors.accent, style = Stroke(1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Column(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 5.dp)) {
                Box(Modifier.size(35.dp, 2.dp).background(colors.accent.copy(alpha = .34f)))
                Spacer(Modifier.height(3.dp))
                Box(Modifier.size(21.dp, 2.dp).background(colors.accent.copy(alpha = .18f)))
            }
            if (selected) Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(14.dp).clip(CircleShape).background(c.accent),
                contentAlignment = Alignment.Center) {
                Glyph("check", Modifier.size(9.dp), c.onAccent)
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = c.foreground, fontSize = 14.sp, lineHeight = 20.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
            Text(subtitle, color = c.muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1)
        }
    }
}

@Composable
internal fun PrismConfigChoice(title: String, subtitle: String, icon: String, onClick: () -> Unit,
                               modifier: Modifier = Modifier) {
    val c = LocalPrismColors.current
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.heightIn(min = 68.dp).auraFocus(shape, scale = 1f).clip(shape)
        .background(c.background.copy(alpha = .55f))
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, Modifier.size(24.dp), c.accent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = c.foreground, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = c.accent, fontSize = 23.sp)
    }
}

/** Remote-friendly release notes; downloaded content is never rendered as active HTML. */
@Composable
internal fun AppUpdateDialog(
    state: AppUpdateState, fallbackPage: String,
    onCheck: () -> Unit, onDownload: () -> Unit, onInstall: () -> Unit,
    onCancel: () -> Unit, onSkip: () -> Unit, onDismiss: () -> Unit,
) {
    val c = LocalPrismColors.current
    val focus = remember { FocusRequester() }
    var showQr by remember { mutableStateOf(false) }
    val release = state.release
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.checking, state.downloading, state.ready, showQr) {
        withFrameNanos { }; runCatching { focus.requestFocus() }
    }
    Dialog(onDismissRequest = { if (state.busy) onCancel(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(650.dp).heightIn(max = 470.dp)
            .background(c.panel, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .border(1.dp, c.line, androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (release != null) "PrismAura ${release.tag}" else "检查更新", color = c.foreground, fontSize = 23.sp)
                    Text("当前 ${BuildConfig.VERSION_NAME}" + (release?.let { " · ${updateSize(it.size)} · ${it.source}" } ?: ""), color = c.muted, fontSize = 12.sp)
                }
                AuraIconButton("close", "关闭", { if (state.busy) onCancel(); onDismiss() }, Modifier.size(38.dp))
            }
            if (showQr) {
                val url = release?.pageUrl ?: fallbackPage
                val bitmap = remember(url) { runCatching { qrCodeBitmap(url) }.getOrNull() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "手机下载页面二维码", modifier = Modifier.size(175.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("用手机打开下载页", color = c.foreground, fontSize = 18.sp)
                        Text("电视下载失败时，可换用手机网络下载 APK，再通过 U 盘或局域网工具安装到电视。", color = c.muted, fontSize = 13.sp)
                        Text(url, color = c.muted, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        PrismAction("返回更新说明", { showQr = false }, Modifier.focusRequester(focus))
                    }
                }
            } else {
                if (release != null) {
                    val notes = remember(release.notes) { readableNotes(release.notes) }
                    Box(Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 190.dp).auraFocus(scale = 1f)
                        .verticalScroll(scroll).onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val delta = when (event.key) { Key.DirectionDown -> 90f; Key.DirectionUp -> -90f; else -> return@onPreviewKeyEvent false }
                            if ((delta > 0 && scroll.canScrollForward) || (delta < 0 && scroll.canScrollBackward)) {
                                scope.launch { scroll.scrollTo((scroll.value + delta.toInt()).coerceIn(0, scroll.maxValue)) }; true
                            } else false
                        }.focusable().padding(8.dp)) {
                        Text(notes, color = c.foreground, fontSize = 14.sp)
                    }
                }
                Text(state.message, color = c.muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (state.downloading) {
                    val fraction = if (state.total > 0) (state.received.toFloat() / state.total).coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxWidth().height(5.dp).background(c.line)) {
                        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(c.accent))
                    }
                    Text("${(fraction * 100).toInt()}% · ${updateSize(state.received)} / ${updateSize(state.total)}", color = c.muted, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        state.busy -> PrismAction(if (state.downloading) "取消下载" else "取消检查", onCancel, Modifier.focusRequester(focus))
                        state.ready != null -> PrismAction("安装更新", onInstall, Modifier.focusRequester(focus), primary = true)
                        release != null -> PrismAction("下载更新", onDownload, Modifier.focusRequester(focus), primary = true)
                        else -> PrismAction("重新检查", onCheck, Modifier.focusRequester(focus), primary = true)
                    }
                    if (!state.busy) {
                        PrismAction("稍后", onDismiss)
                        if (release != null) PrismAction("跳过此版本", onSkip)
                        PrismAction("手机下载", { showQr = true })
                    }
                }
            }
        }
    }
}

private fun updateSize(bytes: Long) = java.lang.String.format(java.util.Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
private fun readableNotes(raw: String): String = raw.lineSequence().take(120).map { line ->
    line.trim().replace(Regex("^#{1,6}\\s+"), "").replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1").replace("**", "").replace("`", "")
}.filterNot { it.startsWith("<") }.joinToString("\n").trim().ifBlank { "此版本未提供更新说明，可在手机下载页面查看发布信息。" }
