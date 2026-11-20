package tv.prism.ui.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.withTimeoutOrNull
import tv.prism.library.Song
import tv.prism.playback.AlternateSong
import tv.prism.playback.findAlternateSongs
import tv.prism.sources.Plugin
import tv.prism.sources.Plugins
import tv.prism.ui.widgets.LocalPrismColors
import tv.prism.ui.widgets.PrismContentDialog
import tv.prism.ui.widgets.PrismQueueRow

@androidx.compose.runtime.Composable
internal fun OtherSourcesDialog(
    original: Song,
    sources: List<Plugin>,
    plugins: Plugins,
    favoriteKeys: Set<String>,
    onDismiss: () -> Unit,
    onPlay: (Song) -> Unit,
) {
    val closeFocus = remember { FocusRequester() }
    var candidates by remember(original.key, sources) { mutableStateOf(emptyList<AlternateSong>()) }
    var complete by remember(original.key, sources) { mutableIntStateOf(0) }
    var failed by remember(original.key, sources) { mutableIntStateOf(0) }
    var loading by remember(original.key, sources) { mutableStateOf(true) }

    LaunchedEffect(original.key, sources) {
        val collected = linkedMapOf<String, AlternateSong>()
        withTimeoutOrNull(15_000) {
            findAlternateSongs(original, sources, plugins) { result ->
                if (result == null) {
                    failed++
                } else {
                    result.forEach { collected.getOrPut(it.song.key) { it } }
                    candidates = collected.values.toList()
                }
                complete++
            }
        }
        failed += sources.size - complete
        loading = false
    }

    val c = LocalPrismColors.current
    val (matched, uncertain) = candidates.partition { it.matchesRecording }
    PrismContentDialog("其他音源", "${original.title} · ${original.artist}", onDismiss,
        initialFocus = closeFocus, closeFocus = closeFocus) {
        val status = when {
            loading && candidates.isNotEmpty() -> "可立即播放 · 仍在查找 $complete/${sources.size}"
            loading -> "正在查找音源 $complete/${sources.size}"
            failed > 0 -> "$failed 个音源未返回结果"
            matched.isEmpty() && uncertain.isNotEmpty() -> "相似版本 · 确定键试听"
            else -> "确定键播放所选来源"
        }
        val displayStatus = if (failed > 0 && !loading && matched.isEmpty() && uncertain.isNotEmpty()) {
            "$status · 仅找到相似版本"
        } else status
        Text(displayStatus, color = c.muted, fontSize = 12.sp)
        Box(Modifier.fillMaxWidth().height(226.dp), contentAlignment = Alignment.TopStart) {
            when {
                candidates.isNotEmpty() -> LazyColumn(Modifier.matchParentSize()) {
                    alternateSourceGroup(matched, "相同版本".takeIf { matched.isNotEmpty() }, 0, closeFocus, favoriteKeys, onPlay)
                    alternateSourceGroup(uncertain, "相似版本".takeIf { uncertain.isNotEmpty() }, matched.size, closeFocus, favoriteKeys, onPlay)
                }
                loading -> PrismLoadingSkeleton("正在查找其他音源")
                else -> Text(if (failed == sources.size) "暂时无法查询其他音源" else "没有找到同名同歌手的歌曲",
                    color = c.muted)
            }
        }
    }
}


private fun LazyListScope.alternateSourceGroup(
    candidates: List<AlternateSong>,
    heading: String?,
    startIndex: Int,
    closeFocus: FocusRequester,
    favoriteKeys: Set<String>,
    onPlay: (Song) -> Unit,
) {
    if (heading != null) item(key = "heading:$heading") {
        Text(heading, Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
            color = LocalPrismColors.current.muted, fontSize = 12.sp)
    }
    itemsIndexed(candidates, key = { _, it -> it.song.key }) { index, candidate ->
        val song = candidate.song
        val position = startIndex + index
        PrismQueueRow(
            song, position, current = false,
            modifier = Modifier.focusProperties { if (position == 0) up = closeFocus },
            favorite = song.key in favoriteKeys,
            subtitle = listOf(song.sourceLabel, song.artist, song.album)
                .filter { it.isNotBlank() }.joinToString(" · "),
        ) { onPlay(song) }
    }
}
