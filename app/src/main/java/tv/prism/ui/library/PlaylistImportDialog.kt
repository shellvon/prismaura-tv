package tv.prism.ui.library

import android.net.Uri
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.prism.MainActivity
import tv.prism.sources.Plugin
import tv.prism.ui.widgets.LocalPrismColors
import tv.prism.ui.widgets.PrismAction
import tv.prism.ui.widgets.PhoneFillButton
import tv.prism.ui.widgets.keepFocusVisible
import tv.prism.sources.MusicPlatform
import tv.prism.ui.settings.importHints
import tv.prism.ui.widgets.Input
import tv.prism.ui.widgets.rowFocus
import tv.prism.ui.widgets.Glyph
import tv.prism.ui.widgets.PlatformIcon
import tv.prism.ui.widgets.tvClickable

internal class PlaylistImportDraft {
    var url by mutableStateOf("")
    var name by mutableStateOf("")
    var sourceId by mutableStateOf<String?>(null)
}

@Composable
internal fun MainActivity.PlaylistImportDialog(
    draft: PlaylistImportDraft,
    onDismiss: () -> Unit,
    onReopen: () -> Unit,
    onStarted: () -> Unit,
    onError: (String) -> Unit,
) {
    val c = LocalPrismColors.current
    val urlFocus = remember { FocusRequester() }
    val phoneFocus = remember { FocusRequester() }
    val doorFocus = remember { FocusRequester() }
    val nameFocus = remember { FocusRequester() }
    val primaryFocus = remember { FocusRequester() }
    val firstRowFocus = remember { FocusRequester() }
    var picker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    /* 链接先认平台，平台再给提示：认不出来就不猜，免得把别家的说明挂在你的链接上。
       同平台常有多个源，优先用写了导入说明的那一个——否则同一首链接的提示会时有时无。
       state 要用 collectAsState 订阅：直接取 .value 不会重组，弹窗开着时装好的插件
       不会出现在可选列表里。 */
    val pluginList = plugins.state.collectAsState().value.plugins
    val pageScope = rememberCoroutineScope()
    val capable = pluginList.filter { it.enabled && (it.metadata.optBoolean("importSheet") || it.metadata.optBoolean("importItem")) }
    val detectedPlatform = MusicPlatform.identify(shareUrlOf(draft.url))
    val samePlatform = capable.filter { it.platform == detectedPlatform }
    val detected = if (detectedPlatform == MusicPlatform.OTHER) null
        else samePlatform.firstOrNull { importHints(it).isNotEmpty() } ?: samePlatform.firstOrNull()
    val chosen = draft.sourceId?.let { id -> capable.firstOrNull { it.id == id } }
    /* 默认跟随识别结果；用户点名之后才覆盖。点名不等于必须先选，只是「我就用这一个」。 */
    val effective = chosen ?: detected
    val hints = effective?.let { importHints(it) }.orEmpty()
    /* 换页前先把焦点交给入口行：它不随换页离场，否则新页上再 request 全部失效。 */
    fun leavePicker() { doorFocus.requestFocus(); picker = false }
    Dialog(onDismissRequest = { if (picker) leavePicker() else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; urlFocus.requestFocus() }
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.width(620.dp).heightIn(max = maxHeight).focusGroup().clip(RoundedCornerShape(24.dp))
                .background(Brush.verticalGradient(listOf(c.panel, c.background.copy(alpha = .985f))))
                .border(1.dp, c.line.copy(alpha = .75f), RoundedCornerShape(24.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("导入歌单或单曲", color = c.foreground, fontSize = 22.sp)
                    Text(if (picker) "按确定选中的音源会记住，手机导入也沿用" else "粘贴分享链接，自动识别音源",
                        color = c.muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2)
                }
                ImportDoorRow(picker, effective, chosen != null, capable.size, doorFocus,
                    onOpen = { if (picker) leavePicker() else if (capable.isNotEmpty()) picker = true })
                if (!picker) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Input(draft.url, { draft.url = it }, "歌单、单曲链接或编号",
                            Modifier.weight(1f).focusRequester(urlFocus).keepFocusVisible().focusProperties {
                                up = doorFocus; down = nameFocus; right = phoneFocus
                            },
                            keyboardType = KeyboardType.Uri, manualKeyboard = true, onSubmit = { nameFocus.requestFocus() })
                        PhoneFillButton("手机代填歌单链接", {
                            phoneSessions.openForm(listOf("url" to "歌单、单曲链接或编号", "name" to "歌单名称（选填）"),
                                mapOf("url" to draft.url, "name" to draft.name), title = "填写歌单导入",
                                message = "填写后回到电视确认并开始导入。",
                                onReturn = { pageScope.launch { repeat(2) { withFrameNanos { } }; runCatching { phoneFocus.requestFocus() } } }) { values ->
                                draft.url = values["url"].orEmpty()
                                draft.name = values["name"].orEmpty().take(60)
                            }
                        }, Modifier.focusRequester(phoneFocus).keepFocusVisible().focusProperties {
                            left = urlFocus; up = doorFocus; down = nameFocus
                        })
                    }
                    /* 说明固定两行高：输入时识别结果会变，行数跳动会把整个弹窗上下推。 */
                    Column(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (hints.isNotEmpty()) hints.take(2).forEach { line ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("·", color = c.accent.copy(alpha = .7f), fontSize = 11.sp, lineHeight = 16.sp)
                                Text(line, Modifier.weight(1f), color = c.muted, fontSize = 11.sp,
                                    lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else Text(
                            if (capable.isEmpty()) "还没有支持链接导入的音源，请先添加音源"
                            else "自动 · 将按顺序尝试 ${capable.size} 个音源",
                            color = c.muted, fontSize = 11.sp, lineHeight = 16.sp,
                        )
                    }
                    Input(draft.name, { draft.name = it }, "歌单名称（选填）",
                        Modifier.fillMaxWidth().focusRequester(nameFocus).keepFocusVisible().focusProperties { up = urlFocus; down = primaryFocus },
                        manualKeyboard = true, onSubmit = { primaryFocus.requestFocus() })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrismAction("取消", onDismiss, Modifier.keepFocusVisible().focusProperties { up = nameFocus })
                        PrismAction(if (busy) "导入中…" else "开始导入", {
                            val url = draft.url
                            onDismiss()
                            runCatching { importPlaylist(draft.name, url, chosen) }
                                .onSuccess { onStarted() }
                                .onFailure { onError(it.message ?: "无法开始导入"); onReopen() }
                        }, Modifier.focusRequester(primaryFocus).keepFocusVisible().focusProperties { up = nameFocus },
                            primary = true, enabled = !busy)
                    }
                    Text("导入后保存在「我的 → 歌单」。私有歌单请先登录对应音源。",
                        color = c.muted, fontSize = 11.sp, lineHeight = 16.sp)
                } else {
                    var focusRow by remember { mutableStateOf(0) }
                    /* 只在落点看不见时才滚：每按一下都把它顶到最上面，整列会在遥控器底下滑动。 */
                    fun reveal(index: Int) {
                        val range = listState.layoutInfo.visibleItemsInfo
                        if (range.isEmpty() || index < range.first().index || index > range.last().index) {
                            scrollScope.launch { listState.animateScrollToItem(index) }
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), state = listState,
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        item {
                            ImportOptionRow(selected = chosen == null, title = "自动", meta = "依次尝试全部",
                                modifier = Modifier.focusRequester(firstRowFocus),
                                onClick = { draft.sourceId = null; leavePicker() },
                                onFocus = { focusRow = 0; reveal(0) })
                        }
                        itemsIndexed(capable) { index, plugin ->
                            ImportOptionRow(selected = chosen?.id == plugin.id, title = plugin.displayName,
                                platform = plugin.platform,
                                meta = when {
                                    detected?.id == plugin.id -> "已识别"
                                    plugin.subscriptionManaged -> ""
                                    else -> "单独添加"
                                },
                                onClick = { draft.sourceId = plugin.id; leavePicker() },
                                onFocus = { focusRow = index + 1; reveal(index + 1) })
                        }
                    }
                    /* 说明只在焦点那一行，固定两行高：文字长短不再决定行高。 */
                    Column(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val rowHints = if (focusRow == 0) listOf("从第一个可用音源开始，读到歌单就停")
                            else capable.getOrNull(focusRow - 1)?.let { importHints(it) }.orEmpty()
                        if (rowHints.isEmpty()) Text("这个音源没有可用的导入说明", color = c.muted, fontSize = 11.sp, lineHeight = 16.sp)
                        else rowHints.take(2).forEach { line ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("·", color = c.accent.copy(alpha = .7f), fontSize = 11.sp, lineHeight = 16.sp)
                                Text(line, Modifier.weight(1f), color = c.muted, fontSize = 11.sp,
                                    lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

    /* 入口行兼返回行：同一条槽位只换文案，节点不离开组合，换页时焦点才留得住。 */
@Composable
private fun ImportDoorRow(
        picker: Boolean, plugin: Plugin?, explicit: Boolean, count: Int,
        focus: FocusRequester, onOpen: () -> Unit,
    ) {
        val c = LocalPrismColors.current
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).focusRequester(focus).rowFocus().tvClickable(onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = if (picker) "返回导入" else "选择导入音源" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (picker) {
                Glyph("back", Modifier.size(14.dp), c.muted)
                Text("返回导入", Modifier.weight(1f), color = c.foreground, fontSize = 14.sp, maxLines = 1)
                Text("$count 个音源", color = c.muted, fontSize = 11.sp, maxLines = 1)
            } else {
                if (plugin != null) PlatformIcon(plugin.platform, Modifier.size(26.dp))
                else Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { Glyph("layers", Modifier.size(19.dp), c.muted) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(when { plugin == null -> "导入音源"; explicit -> "指定音源"; else -> "识别为" },
                        color = c.muted, fontSize = 11.sp, lineHeight = 15.sp)
                    Text(plugin?.displayName ?: "自动 · 依次尝试 $count 个",
                        color = c.foreground, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("更换 ›", color = c.muted, fontSize = 12.sp, maxLines = 1)
            }
        }
    }

    /* 音源列表的一行：图标 + 名称 + 标记 + 选中勾。行高固定，说明放到列表下方单独一栏。 */
@Composable
private fun ImportOptionRow(
        selected: Boolean, title: String, meta: String,
        onClick: () -> Unit, onFocus: () -> Unit,
        modifier: Modifier = Modifier, platform: MusicPlatform? = null,
    ) {
        val c = LocalPrismColors.current
        Row(modifier.fillMaxWidth().height(52.dp).rowFocus()
            .onFocusChanged { if (it.isFocused) onFocus() }
            .tvClickable(onClick).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (platform != null) PlatformIcon(platform, Modifier.size(24.dp))
            else Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Glyph("layers", Modifier.size(18.dp), c.muted) }
            Text(title, Modifier.weight(1f), color = c.foreground, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta.isNotBlank()) Text(meta, color = c.muted, fontSize = 11.sp, maxLines = 1)
            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                if (selected) Glyph("check", Modifier.size(13.dp), c.accent)
            }
        }
    }
