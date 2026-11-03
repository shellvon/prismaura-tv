package tv.prism

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

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
