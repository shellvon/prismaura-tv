package tv.prism.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.prism.library.SavedSubject
import tv.prism.library.MusicPlaylist
import tv.prism.ui.widgets.FocusAnchor
import tv.prism.ui.widgets.LocalPrismColors
import tv.prism.ui.widgets.rememberVerticalFocusSearch
import tv.prism.ui.widgets.PrismEmptyState
import tv.prism.ui.widgets.PrismSheetCard
import tv.prism.ui.widgets.keepFocusVisible
import tv.prism.ui.widgets.focusAnchor
import tv.prism.ui.widgets.upTo
import tv.prism.sources.MusicPlatform

@Composable
internal fun ColumnScope.LibraryCollections(
    subjectKind: String?,
    visibleSubjects: List<SavedSubject>,
    filterInput: FocusAnchor?,
    filterMessage: String?,
    playlists: List<MusicPlaylist>,
    savedSheets: List<SavedSubject>,
    managing: Boolean,
    selectedKeys: Set<String>,
    listState: LazyListState,
    libraryContentFocus: FocusAnchor,
    libraryHeaderFocus: FocusAnchor,
    tab: FocusAnchor,
    miniFocus: FocusRequester,
    emptyActionEdges: Modifier,
    detailFocus: (String) -> FocusRequester,
    onFocus: (FocusRequester) -> Unit,
    registerPlaylist: (MusicPlaylist, FocusRequester) -> Unit,
    onDiscover: () -> Unit,
    onImport: () -> Unit,
    onPickSubject: (SavedSubject) -> Unit,
    onSubjectMenu: (SavedSubject, FocusRequester) -> Unit,
    onPickPlaylist: (MusicPlaylist) -> Unit,
    onPlaylistMenu: (MusicPlaylist, FocusRequester) -> Unit,
) {
    val c = LocalPrismColors.current
    val firstSubject = visibleSubjects.firstOrNull()
    val subjectRows = visibleSubjects.chunked(6)
    val (libraryRowLayout, focusLibraryRow) = rememberVerticalFocusSearch()
    val libraryCardRows =
        when {
            subjectKind != null ->
                subjectRows.map { row ->
                    row.map { detailFocus("${it.kind}:${it.pluginId}:${it.data.optString("id")}") }
                }
            else ->
                playlists.chunked(6).map { row -> row.map { detailFocus("playlist:${it.id}") } } +
                    if (managing) emptyList()
                    else
                        savedSheets.chunked(6).map { row ->
                            row.map {
                                detailFocus("sheet:${it.pluginId}:${it.data.optString("id")}")
                            }
                        }
        }
    fun libraryCardEdges(row: Int, column: Int) =
        Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val delta =
                when (event.key) {
                    Key.DirectionUp -> -1
                    Key.DirectionDown -> 1
                    else -> return@onPreviewKeyEvent false
                }
            val next = row + delta
            if (next < 0) return@onPreviewKeyEvent false
            focusLibraryRow(event.key) {
                if (next >= libraryCardRows.size) miniFocus
                else libraryCardRows[next].let { it[column.coerceAtMost(it.lastIndex)] }
            }
        }

    if (subjectKind != null) {
        Spacer(Modifier.height(8.dp))
        if (subjectRows.isEmpty()) {
            PrismEmptyState(
                filterMessage ?: "在${if (subjectKind == "artist") "歌手" else "专辑"}详情页按「收藏」，就会收进这里",
                "去发现".takeIf { filterMessage == null },
                onDiscover,
                emptyActionEdges,
                centered = true,
            )
        } else
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 22.dp),
            ) {
                itemsIndexed(subjectRows, key = { _, row -> "subject-row:${row.first().key}" }) {
                    rowIndex,
                    row ->
                    Row(libraryRowLayout, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEachIndexed { column, subject ->
                            val cardFocus =
                                detailFocus(
                                    "${subject.kind}:${subject.pluginId}:${subject.data.optString("id")}"
                                )
                            PrismSheetCard(
                                subject.title,
                                subject.artwork,
                                "${subject.kindLabel} · ${subject.sourceName.ifBlank { "已收藏" }}",
                                portrait = subject.kind == "artist",
                                modifier =
                                    Modifier.focusRequester(cardFocus)
                                        .keepFocusVisible()
                                        .then(libraryCardEdges(rowIndex, column))
                                        .then(
                                            if (subject === firstSubject)
                                                Modifier.focusAnchor(libraryContentFocus)
                                            else Modifier
                                        )
                                        .focusProperties {
                                            if (subject in visibleSubjects.take(6)) {
                                                if (filterInput != null) upTo(filterInput)
                                                else if (managing) upTo(libraryHeaderFocus, tab)
                                                else upTo(tab)
                                            }
                                        }
                                        .onFocusChanged { if (it.isFocused) onFocus(cardFocus) },
                                onLongPress =
                                    if (managing) null
                                    else {
                                        { onSubjectMenu(subject, cardFocus) }
                                    },
                                selected = if (managing) subject.key in selectedKeys else null,
                            ) {
                                onPickSubject(subject)
                            }
                        }
                    }
                }
            }
    } else {
        Spacer(Modifier.height(8.dp))
        /* 空歌单的提示以前在列表里（多一层 6dp/8dp 的 contentPadding），
        和歌手 tab 的提示差了几个像素，切 tab 时会跳；现在两处同一个组件。 */
        if (playlists.isEmpty() && savedSheets.isEmpty()) {
            PrismEmptyState(
                filterMessage ?: "导入平台歌单，或在歌单详情页收藏，就会出现在这里",
                "导入歌单".takeIf { filterMessage == null },
                onImport,
                emptyActionEdges,
                centered = true,
            )
        } else
            LazyColumn(
                Modifier.weight(1f).padding(bottom = 10.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 22.dp),
            ) {
                itemsIndexed(playlists.chunked(6), key = { _, row -> "local:${row.first().id}" }) {
                    rowIndex,
                    row ->
                    Row(libraryRowLayout, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEachIndexed { column, list ->
                            val platform = MusicPlatform.identify(list.platform)
                            val cardFocus = detailFocus("playlist:${list.id}")
                            registerPlaylist(list, cardFocus)
                            PrismSheetCard(
                                list.name,
                                list.songs
                                    .firstOrNull { it.artwork.startsWith("http") }
                                    ?.artwork
                                    .orEmpty(),
                                "${list.songs.size} 首 · ${platform.label}",
                                modifier =
                                    Modifier.focusRequester(cardFocus)
                                        .keepFocusVisible()
                                        .then(libraryCardEdges(rowIndex, column))
                                        .then(
                                            if (list == playlists.first())
                                                Modifier.focusAnchor(libraryContentFocus)
                                            else Modifier
                                        )
                                        .focusProperties {
                                            if (list in playlists.take(6)) {
                                                if (filterInput != null) upTo(filterInput)
                                                else if (managing) upTo(libraryHeaderFocus, tab)
                                                else upTo(tab)
                                            }
                                        }
                                        .onFocusChanged { if (it.isFocused) onFocus(cardFocus) },
                                selected = if (managing) list.id in selectedKeys else null,
                                onLongPress =
                                    if (managing) null
                                    else {
                                        { onPlaylistMenu(list, cardFocus) }
                                    },
                            ) {
                                onPickPlaylist(list)
                            }
                        }
                    }
                }
                if (!managing && savedSheets.isNotEmpty()) {
                    item(key = "saved-sheets") {
                        Text(
                            "收藏的歌单",
                            color = c.muted,
                            fontSize = 13.sp,
                            letterSpacing = .4.sp,
                            modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                        )
                    }
                    itemsIndexed(
                        savedSheets.chunked(6),
                        key = { _, row -> "saved:${row.first().key}" },
                    ) { rowIndex, row ->
                        Row(libraryRowLayout, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEachIndexed { column, subject ->
                                val key =
                                    "sheet:${subject.pluginId}:${subject.data.optString("id")}"
                                val cardFocus = detailFocus(key)
                                PrismSheetCard(
                                    subject.title,
                                    subject.artwork,
                                    subject.sourceName.ifBlank { "已收藏" },
                                    modifier =
                                        Modifier.focusRequester(cardFocus)
                                            .keepFocusVisible()
                                            .then(
                                                libraryCardEdges(
                                                    (playlists.size + 5) / 6 + rowIndex,
                                                    column,
                                                )
                                            )
                                            .then(
                                                if (
                                                    playlists.isEmpty() &&
                                                        subject == savedSheets.first()
                                                )
                                                    Modifier.focusAnchor(libraryContentFocus)
                                                else Modifier
                                            )
                                            .focusProperties {
                                                if (
                                                    playlists.isEmpty() &&
                                                        subject in savedSheets.take(6)
                                                ) {
                                                    if (filterInput != null) upTo(filterInput)
                                                else if (managing) upTo(libraryHeaderFocus, tab)
                                                    else upTo(tab)
                                                }
                                            }
                                            .onFocusChanged {
                                                if (it.isFocused) onFocus(cardFocus)
                                            },
                                    onLongPress = { onSubjectMenu(subject, cardFocus) },
                                ) {
                                    onPickSubject(subject)
                                }
                            }
                        }
                    }
                }
            }
    }
}
