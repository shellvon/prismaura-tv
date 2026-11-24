package tv.prism.ui.library

import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tv.prism.MainActivity
import tv.prism.library.Song
import tv.prism.TrackMenuTarget
import tv.prism.library.SavedSubject
import tv.prism.CollectionMenuTarget
import tv.prism.sources.Plugin
import tv.prism.ui.widgets.PrismActionMenu
import tv.prism.ui.widgets.PrismMenuAction
import tv.prism.ui.widgets.PrismMenuSection
import tv.prism.ui.widgets.PrismConfirmDialog
import tv.prism.library.LibraryState

@Composable
internal fun MainActivity.TrackActionsMenu(
    target: TrackMenuTarget,
    libraryState: LibraryState,
    activePlugins: List<Plugin>,
    openSubject: (Song, String, String) -> Unit,
    find: (String, String) -> Unit,
    openOtherSources: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val song = target.song
    val canEnqueue = target.list != "player" && song.key != queue.getOrNull(queueIndex)?.key
    var pickingPlaylist by remember(song.key) { mutableStateOf(false) }
    val picker: (@Composable () -> Unit)? = if (pickingPlaylist) {
        { PlaylistPickerPanel(song, libraryState, onDismiss) }
    } else null
    var favoritePending by remember(song.key) { mutableStateOf(false) }
    var menuNotice by remember(song.key) { mutableStateOf("") }
    val saved = libraryState.favorites.any { it.key == song.key }
    val songPlugin = activePlugins.firstOrNull { it.id == song.pluginId }
    val artistDetail = supportsSubjectSearch(songPlugin, "artist")
    val albumDetail = supportsSubjectSearch(songPlugin, "album")
    /* Search-type support is declared per plugin. Asking an album search of a
       music-only source returns nothing, so fall back to a song search. */
    val albumSearch = activePlugins.any { supportsSubjectSearch(it, "album") }
    PrismActionMenu(song.title, song.artist, song, onDismiss = onDismiss, notice = menuNotice, replacement = picker, actions = buildList {
            if (canEnqueue) add(PrismMenuAction("下一首播放", "queueNext") { onDismiss(); enqueueSong(song, next = true) })
            add(PrismMenuAction(if (favoritePending) "正在保存…" else if (saved) "取消收藏" else "收藏歌曲", if (saved) "heartFilled" else "heart", active = saved, keepOpen = true) {
                if (!favoritePending) {
                    favoritePending = true; menuNotice = "正在保存…"
                    toggleFavorite(song, onSuccess = {
                        favoritePending = false
                        menuNotice = if (library.state.value.favorites.any { it.key == song.key }) "已收藏" else "已取消收藏"
                        status = "$menuNotice：${song.title}"
                    }, onFailure = { favoritePending = false; menuNotice = it; status = it })
                }
            })
            add(PrismMenuAction("添加到歌单", "list", navigates = true, keepOpen = true) { pickingPlaylist = true })
            if (canEnqueue) add(PrismMenuAction("添加到播放队列", "queueAdd") { onDismiss(); enqueueSong(song, next = false) })
            if (openOtherSources != null) add(PrismMenuAction("其他音源", "layers", section = PrismMenuSection.RELATED) {
                openOtherSources()
            })
            if (song.artist.isNotBlank() && song.artist != "未知歌手") add(PrismMenuAction(if (artistDetail) "歌手详情" else "搜歌手", "mic", description = song.artist, navigates = true, section = PrismMenuSection.RELATED) {
                onDismiss()
                if (artistDetail) openSubject(song, "artist", target.originFocus) else find(song.artist, "music")
            })
            if (song.album.isNotBlank()) add(PrismMenuAction(if (albumDetail) "专辑详情" else "搜专辑", "album", description = song.album, navigates = true, section = PrismMenuSection.RELATED) {
                onDismiss()
                if (albumDetail) openSubject(song, "album", target.originFocus) else find(song.album, if (albumSearch) "album" else "music")
            })
            if (target.list?.startsWith("playlist:") == true) add(PrismMenuAction("移出歌单", "trash", destructive = true) {
                onDismiss()
                lifecycleScope.launch { try { library.removeSong(target.list.removePrefix("playlist:"), song); status = "已移出歌单：${song.title}" } catch (e: Exception) { status = e.message ?: "操作失败" } }
            })
            if (target.list == "recent") add(PrismMenuAction("删除记录", "trash", destructive = true) {
                onDismiss()
                lifecycleScope.launch { try { library.removeRecent(song); status = "已删除播放记录" } catch (e: Exception) { status = e.message ?: "删除失败" } }
            })
            if (target.list in setOf("player", "queue") && queue.any { it.key == song.key }) add(PrismMenuAction("移出播放队列", "trash", section = PrismMenuSection.MANAGEMENT) {
                onDismiss()
                removeQueueItem(queue.indexOfFirst { it.key == song.key })
                status = "已移出播放队列：${song.title}"
            })
        })
}

@Composable
internal fun MainActivity.SubjectActionsMenu(
    subject: SavedSubject,
    libraryState: LibraryState,
    activePlugins: List<Plugin>,
    readOrigin: () -> String?,
    onDismiss: () -> Unit,
) {
    var favoritePending by remember(subject.key) { mutableStateOf(false) }
    var menuNotice by remember(subject.key) { mutableStateOf("") }
    val saved = libraryState.subjects.any { it.key == subject.key }
    val plugin = activePlugins.firstOrNull { it.id == subject.pluginId }
    PrismActionMenu(subject.title, if (subject.kind == "chart") "榜单" else subject.kindLabel,
        onDismiss = onDismiss, notice = menuNotice, actions = buildList {
            val detailLabel = when (subject.kind) {
                "artist" -> "歌手详情"
                "album" -> "专辑详情"
                "chart" -> "榜单详情"
                else -> "歌单详情"
            }
            val detailIcon = when (subject.kind) { "artist" -> "mic"; "album" -> "album"; else -> "list" }
            if (plugin != null) add(PrismMenuAction(detailLabel, detailIcon, navigates = true) {
                val origin = readOrigin()
                onDismiss()
                if (subject.kind == "chart") {
                    page = "discover"
                    discoverySession.openChart(plugin, subject.data)
                } else openBrowse(plugin, subject.data, subject.kind, origin)
            })
            if (subject.kind != "chart") add(PrismMenuAction(if (favoritePending) "正在保存…" else if (saved) "取消收藏" else "收藏", if (saved) "heartFilled" else "heart", active = saved, keepOpen = true) {
                if (!favoritePending) {
                    favoritePending = true; menuNotice = "正在保存…"
                    toggleSubject(subject.pluginId, subject.kind, subject.data, subject.sourceName,
                        onSuccess = { favoritePending = false; menuNotice = status },
                        onFailure = { favoritePending = false; menuNotice = it; status = it })
                }
            })
            if (subject.kind == "chart" && plugin != null) add(PrismMenuAction("刷新曲目", "refresh") {
                onDismiss()
                lifecycleScope.launch {
                    try {
                        val songs = discoverySession.fetchChart(plugin, subject.data)
                        discoverySession.chartPreviews = discoverySession.chartPreviews + (subject.data.optString("id") to songs)
                        status = "已刷新榜单"
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { status = "刷新失败：${e.message}" }
                }
            })
        })
}

@Composable
internal fun MainActivity.CollectionActionsMenu(
    target: CollectionMenuTarget,
    onDismiss: () -> Unit,
) {
    var confirming by remember(target) { mutableStateOf(false) }
    var removing by remember(target) { mutableStateOf(false) }
    var renaming by remember(target) { mutableStateOf(false) }
    var error by remember(target) { mutableStateOf("") }
    val renamePanel: (@Composable () -> Unit)? =
        if (renaming && target.playlistId != null) {
            {
                RenamePlaylistPanel(
                    playlistId = target.playlistId,
                    currentName = target.title,
                    onDone = { renaming = false; onDismiss() },
                    onCancel = { renaming = false },
                )
            }
        } else null
    val verb = if (target.playlistId != null) "删除歌单" else "取消专辑收藏"
    if (confirming) PrismConfirmDialog(verb,
        (if (target.playlistId != null) "删除本机歌单「${target.title}」？此操作无法撤销。" else "取消这张专辑中 ${target.songs.size} 首歌曲的收藏？") +
            error.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty(),
        if (removing) "处理中…" else "确认$verb",
        onDismiss = { if (!removing) confirming = false }, onConfirm = {
            if (!removing) {
                removing = true; error = ""
                lifecycleScope.launch {
                    try {
                        if (target.playlistId != null) library.delete(target.playlistId)
                        else library.removeFavorites(target.songs.map { it.key }.toSet())
                        onDismiss(); status = "已$verb"
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "操作失败" }
                    finally { removing = false }
                }
            }
        }, confirmIcon = if (target.playlistId != null) "trash" else "heart")
    else PrismActionMenu(target.title, "本机 · ${target.songs.size} 首", onDismiss = onDismiss,
        replacement = renamePanel, onBackFromReplacement = { renaming = false }, actions = buildList {
        add(PrismMenuAction("播放全部", "play", enabled = target.songs.isNotEmpty(), description = if (target.songs.isEmpty()) "这个歌单还没有歌曲" else "") {
            onDismiss(); playCollection(target.songs)
        })
        add(PrismMenuAction("随机播放", "shuffle", enabled = target.songs.isNotEmpty()) {
            onDismiss(); playCollection(target.songs, shuffle = true)
        })
        if (target.playlistId != null) {
            add(PrismMenuAction("重命名歌单", "keyboard", navigates = true, keepOpen = true, section = PrismMenuSection.MANAGEMENT) {
                renaming = true
            })
        }
        add(PrismMenuAction(verb, if (target.playlistId != null) "trash" else "heart", destructive = true) { error = ""; confirming = true })
    })
}
