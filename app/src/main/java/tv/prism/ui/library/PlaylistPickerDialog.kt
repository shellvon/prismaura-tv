package tv.prism.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.prism.MainActivity
import tv.prism.library.LibraryState
import tv.prism.library.Song
import tv.prism.ui.widgets.Glyph
import tv.prism.ui.widgets.Input
import tv.prism.ui.widgets.LocalPrismColors
import tv.prism.ui.widgets.PrismAction
import tv.prism.ui.widgets.PhoneFillButton
import tv.prism.ui.widgets.tvClickable

@Composable
internal fun MainActivity.PlaylistPickerPanel(song: Song, libraryState: LibraryState, onDismiss: () -> Unit) {
    val c = LocalPrismColors.current
    val playlists = libraryState.playlists
    var pending by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(playlists.isEmpty()) }
    var draft by remember {
        mutableStateOf(
            if (playlists.isEmpty()) {
                var index = playlists.size + 1
                while (playlists.any { it.name == "我的歌单 $index" }) index++
                "我的歌单 $index"
            } else ""
        )
    }
    var creatingNow by remember { mutableStateOf(false) }
    val rowFocus = remember(playlists.size) { List(playlists.size) { FocusRequester() } }
    val nameFocus = remember { FocusRequester() }
    val newFocus = remember { FocusRequester() }
    val doneFocus = remember { FocusRequester() }

    fun suggestedName(): String {
        var index = playlists.size + 1
        while (playlists.any { it.name == "我的歌单 $index" }) index++
        return "我的歌单 $index"
    }

    fun startCreating() {
        draft = suggestedName()
        creating = true
    }

    fun create(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) { notice = "名字不能为空"; return }
        if (creatingNow) return
        creatingNow = true
        lifecycleScope.launch {
            try {
                library.create(trimmed, listOf(song))
                notice = "已新建「$trimmed」"
                creating = false
            } catch (e: Exception) {
                notice = e.message ?: "创建失败"
            } finally {
                creatingNow = false
            }
        }
    }

    LaunchedEffect(creating, playlists.size) {
        repeat(3) { withFrameNanos { } }
        /* 默认名已经写好，先把焦点放在「创建并添加」；要改名再按上进入输入框。 */
        runCatching { if (creating) newFocus.requestFocus() else (rowFocus.firstOrNull() ?: newFocus).requestFocus() }
    }

    /* 电视上按确定键才开始输入。键盘一出来会占掉半屏，这时先把歌单列表收起来，
       只留新建这一块，输入框和按钮才不会钻到键盘底下；收起键盘后再恢复列表。 */
    var editingName by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().padding(top = if (editingName) 12.dp else 0.dp),
        contentAlignment = if (editingName) Alignment.TopCenter else Alignment.Center) {
        Column(Modifier.width(600.dp).heightIn(max = maxHeight - 24.dp).focusGroup().clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(c.panel, c.background.copy(alpha = .985f))))
            .border(1.dp, c.line.copy(alpha = .75f), RoundedCornerShape(24.dp))
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(horizontal = 28.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("添加到歌单", color = c.foreground, fontSize = 22.sp)
                Text("${song.title} · ${song.artist}", color = c.muted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (playlists.isNotEmpty() && !editingName) Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).focusGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                playlists.forEachIndexed { index, playlist ->
                    val contains = playlist.songs.any { it.key == song.key }
                    PlaylistPickRow(
                        title = playlist.name,
                        meta = "${playlist.songs.size} 首",
                        checked = contains,
                        busy = pending == playlist.id,
                        modifier = Modifier.focusRequester(rowFocus[index]),
                    ) {
                        if (pending.isBlank()) {
                            pending = playlist.id
                            lifecycleScope.launch {
                                try {
                                    if (contains) library.removeSong(playlist.id, song) else library.add(playlist.id, song)
                                    notice = if (contains) "已移出「${playlist.name}」" else "已添加到「${playlist.name}」"
                                } catch (e: Exception) {
                                    notice = e.message ?: "操作失败"
                                } finally {
                                    pending = ""
                                }
                            }
                        }
                    }
                }
            }
            if (creating) {
                Text("新建歌单", color = c.muted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Input(
                        value = draft,
                        onChange = { draft = it },
                        hint = "歌单名称",
                        modifier = Modifier.weight(1f).focusRequester(nameFocus),
                        /* 聚焦只落到输入框，不直接弹键盘：这样右方向键可以先去
                           「手机代填」，想用遥控器改字再按确定键开始输入。 */
                        manualKeyboard = true,
                        onSubmit = { create(draft) },
                        onEditingChange = { editingName = it },
                    )
                    /* 名字就在输入框里，手机代填也贴着输入框：先给一个好改的默认值，
                       再决定是在电视上删改，还是扫码到手机上接着改。 */
                    PhoneFillButton("手机代填歌单名称", {
                        phoneSessions.openForm(
                            listOf("name" to "歌单名称"),
                            mapOf("name" to draft.ifBlank { suggestedName() }),
                            title = "新建歌单",
                            message = "提交后回到电视确认歌单名称。",
                            onReturn = { lifecycleScope.launch { repeat(2) { withFrameNanos { } }; runCatching { nameFocus.requestFocus() } } },
                        ) { values -> draft = values["name"].orEmpty().take(60) }
                    })
                }
            }
            if (notice.isNotBlank() || !creating) {
                Text(notice.ifBlank { "确定键加入或移出，可连续勾选。" },
                    color = if (notice.isBlank()) c.muted.copy(alpha = .7f) else c.accent,
                    fontSize = 12.sp, lineHeight = 17.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (creating) {
                    PrismAction(if (creatingNow) "创建中…" else "创建并添加", {
                        create(draft)
                    }, Modifier.focusRequester(newFocus), icon = "plus", primary = true)
                    Spacer(Modifier.weight(1f))
                    PrismAction("取消", { creating = false; notice = "" })
                } else {
                    PrismAction("新建歌单", { startCreating() }, Modifier.focusRequester(newFocus), icon = "plus")
                    Spacer(Modifier.weight(1f))
                    PrismAction("完成", onDismiss, Modifier.focusRequester(doneFocus), primary = true)
                }
            }
        }
    }
}

/* 重命名走和新建同一套写法：名字直接写在输入框里，旁边是「手机代填」。
   想用遥控器改就在电视上改，懒得打字就扫码到手机上接着改。 */
@Composable
internal fun MainActivity.RenamePlaylistPanel(
    playlistId: String,
    currentName: String,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = LocalPrismColors.current
    var draft by remember(playlistId) { mutableStateOf(currentName) }
    var notice by remember(playlistId) { mutableStateOf("") }
    var saving by remember(playlistId) { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }

    fun save(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) { notice = "名字不能为空"; return }
        if (trimmed == currentName) { notice = "名字没有变化"; return }
        if (saving) return
        saving = true
        notice = "正在保存…"
        lifecycleScope.launch {
            try {
                library.rename(playlistId, trimmed)
                status = "已重命名为「$trimmed」"
                onDone()
            } catch (e: Exception) {
                notice = e.message ?: "重命名失败"
            } finally {
                saving = false
            }
        }
    }

    LaunchedEffect(playlistId) {
        repeat(3) { withFrameNanos { } }
        /* 名字已经填好，焦点先落在输入框上；键盘不会自动弹出，按确定键才开始输入。 */
        runCatching { nameFocus.requestFocus() }
    }

    /* 键盘弹出来会盖住底部按钮，这时把面板整体上移，保存/取消始终露在键盘上方。
       对话框窗口里不一定收得到 IME 的 insets，所以以输入框的编辑态为准。 */
    var editing by remember(playlistId) { mutableStateOf(false) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val lifted = editing || imeVisible
    Box(Modifier.fillMaxSize().padding(top = if (lifted) 12.dp else 0.dp),
        contentAlignment = if (lifted) Alignment.TopCenter else Alignment.Center) {
        Column(Modifier.width(560.dp).focusGroup().clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(c.panel, c.background.copy(alpha = .985f))))
            .border(1.dp, c.line.copy(alpha = .75f), RoundedCornerShape(24.dp))
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(horizontal = 28.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("重命名歌单", color = c.foreground, fontSize = 22.sp)
                Text(currentName, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Input(
                    value = draft,
                    onChange = { draft = it },
                    hint = "歌单名称",
                    modifier = Modifier.weight(1f).focusRequester(nameFocus),
                    manualKeyboard = true,
                    onSubmit = { save(draft) },
                    onEditingChange = { editing = it },
                )
                PhoneFillButton("手机代填歌单名称", {
                    phoneSessions.openForm(
                        listOf("name" to "歌单名称"),
                        mapOf("name" to draft.ifBlank { currentName }),
                        title = "重命名歌单",
                        message = "提交后回到电视确认歌单名称。",
                        onReturn = { lifecycleScope.launch { repeat(2) { withFrameNanos { } }; runCatching { nameFocus.requestFocus() } } },
                    ) { values -> draft = values["name"].orEmpty().take(60) }
                })
            }
            Text(
                notice.ifBlank { if (draft.trim() == currentName) "改好后按保存。" else "确定键开始输入，右方向键可转到手机代填。" },
                color = if (notice.isBlank()) c.muted.copy(alpha = .7f) else c.accent,
                fontSize = 12.sp, lineHeight = 17.sp,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PrismAction(if (saving) "保存中…" else "保存", { save(draft) },
                    Modifier.focusRequester(saveFocus), icon = "check", primary = true)
                Spacer(Modifier.weight(1f))
                PrismAction("取消", onCancel)
            }
        }
    }
}

@Composable
private fun PlaylistPickRow(
    title: String, meta: String, checked: Boolean, busy: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val c = LocalPrismColors.current
    var focused by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp)
        .onFocusChanged { focused = it.isFocused }
        .clip(RoundedCornerShape(12.dp))
        .background(if (focused) c.accent.copy(alpha = .12f) else c.foreground.copy(alpha = .035f))
        .border(1.dp, if (focused) c.accent.copy(alpha = .55f) else c.line.copy(alpha = .4f), RoundedCornerShape(12.dp))
        .tvClickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
            .background(if (checked) c.accent else c.foreground.copy(alpha = .08f))
            .border(1.dp, if (checked) c.accent else c.line.copy(alpha = .7f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center) {
            if (checked) Glyph("check", Modifier.size(14.dp), c.onAccent)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = c.foreground, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, color = c.muted, fontSize = 12.sp, maxLines = 1)
        }
        if (busy) Text("保存中…", color = c.muted, fontSize = 12.sp)
    }
}
