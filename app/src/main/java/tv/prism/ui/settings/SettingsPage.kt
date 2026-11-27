package tv.prism.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.flow.first
import tv.prism.ui.widgets.FocusAnchor
import tv.prism.ui.widgets.SettingsRailItem
import tv.prism.ui.widgets.requestIfAttached
import tv.prism.ui.widgets.focusAnchor
import tv.prism.ui.widgets.LocalListScrolling
import tv.prism.updates.AppUpdateState
import tv.prism.ui.widgets.LocalPrismColors
import tv.prism.ui.widgets.SettingsCard
import tv.prism.ui.widgets.SettingsRow
import tv.prism.ui.widgets.rememberFocus
import tv.prism.ui.widgets.SettingsButton
import tv.prism.ui.widgets.SettingsDivider
import tv.prism.ui.widgets.PrismSwitchRow
import tv.prism.sources.Plugin
import tv.prism.ui.widgets.SettingsChip
import tv.prism.ui.widgets.auraFocus
import tv.prism.ui.widgets.Glyph
import tv.prism.ui.widgets.PlatformIcon
import tv.prism.ui.widgets.AuraThemeCard
import tv.prism.ui.widgets.prismColors
import tv.prism.playback.PlaybackMode
import tv.prism.ui.widgets.RestoreFocusAfterDialog
import tv.prism.ui.widgets.ChoicePopup
import tv.prism.ui.playback.lyricFontLevels
import tv.prism.ui.playback.LYRIC_HEADING_HINT

@Composable
internal fun SettingsPage(
    sectionName: String,
    sections: List<String>,
    settingsScroll: ScrollState,
    navFocus: FocusRequester,
    sectionFocus: Map<String, FocusAnchor>,
    sectionEntryFocus: Map<String, FocusAnchor>,
    selectSection: (String) -> Unit,
    content:
        @Composable
        ColumnScope.(
            edgeToRail: Modifier, entryUpScrollsBack: Modifier, entryEdge: Modifier,
        ) -> Unit,
) {
    val pageScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        /* The rail and the panel are separate focus spaces, so a
        geometric Left from the content falls back to whichever
        rail row is nearest and swaps the section under the
        remote. Every left edge names the open section. */
        val edgeToRail =
            Modifier.focusProperties {
                val anchor = sectionFocus.getValue(sectionName)
                if (anchor.attached) left = anchor.requester
            }
        /* Walking back up only half-reveals a scrolled section:
        bringIntoView stops once the first control is visible, so
        the header texts above it stay off-screen and the next Up
        leaves the panel. On a scrolled panel, Up from the first
        control scrolls back to the top first; the next Up leaves. */
        val entryUpScrollsBack =
            Modifier.onPreviewKeyEvent { event ->
                if (
                    event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionUp &&
                        settingsScroll.value > 0
                ) {
                    pageScope.launch { settingsScroll.animateScrollTo(0) }
                    true
                } else false
            }
        /* Up from the top row used to fall back to the nearest rail
        row, which swapped the section under the remote. It goes
        to the bar instead, like every other page. */
        val entryEdge =
            Modifier.focusProperties {
                    val anchor = sectionFocus.getValue(sectionName)
                    if (anchor.attached) left = anchor.requester
                    up = navFocus
                }
                .then(entryUpScrollsBack)
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(top = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            Column(Modifier.width(164.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val railIcons = listOf("palette", "compass", "layers", "play", "info")
                sections.forEachIndexed { index, section ->
                    SettingsRailItem(
                        section,
                        railIcons[index],
                        sectionName == section,
                        {
                            selectSection(section)
                            /* The panel for the new section is composed in
                            the next frame. Ask for its first control by
                            name instead of trusting a geometric Right,
                            which has no target during recomposition. */
                            pageScope.launch {
                                repeat(4) {
                                    withFrameNanos {}
                                    if (sectionEntryFocus.getValue(section).requestIfAttached())
                                        return@launch
                                }
                                focusManager.moveFocus(FocusDirection.Right)
                            }
                        },
                        Modifier.focusAnchor(sectionFocus.getValue(section))
                            /* Only the selected section's content is composed; pointing
                            Right anywhere else would target a node that isn't there. */
                            .focusProperties {
                                val entry = sectionEntryFocus.getValue(section)
                                if (section == sectionName && entry.attached)
                                    right = entry.requester
                                if (index == 0) up = navFocus
                            }
                            /* An unselected section's content isn't on
                            screen yet: Right would find no candidate
                            and drop focus off the screen. */
                            .onPreviewKeyEvent {
                                it.type == KeyEventType.KeyDown &&
                                    it.key == Key.DirectionRight &&
                                    section != sectionName
                            },
                        onFocused = { if (sectionName != section) selectSection(section) },
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Column(
                    Modifier.weight(1f)
                        .verticalScroll(settingsScroll)
                        .padding(end = 6.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(26.dp),
                ) {
                    CompositionLocalProvider(
                        LocalListScrolling provides { settingsScroll.isScrollInProgress }
                    ) {
                        content(edgeToRail, entryUpScrollsBack, entryEdge)
                    }
                }
            }
        }
    }
}

@Composable
internal fun AboutSettings(
    updateState: AppUpdateState,
    cacheBytes: Long?,
    clearingCache: Boolean,
    entry: FocusAnchor,
    entryEdge: Modifier,
    edgeToRail: Modifier,
    bodyFocusMemory: MutableState<FocusRequester?>,
    checkAppUpdates: () -> Unit,
    onReminder: (Boolean) -> Unit,
    clearCache: () -> Unit,
    onBackup: () -> Unit,
) {
    val context = LocalContext.current
    val c = LocalPrismColors.current
    val versionName =
        remember(context) {
            runCatching {
                    context.packageManager
                        .getPackageInfo(context.packageName, 0)
                        .versionName
                        .orEmpty()
                }
                .getOrDefault("")
        }
    val deviceInfo = remember {
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}"
    }
    val webViewInfo = remember {
        runCatching {
                val provider =
                    if (android.os.Build.VERSION.SDK_INT >= 26)
                        android.webkit.WebView.getCurrentWebViewPackage()
                    else
                        listOf("com.google.android.webview", "com.android.webview")
                            .firstNotNullOfOrNull { name ->
                                runCatching { context.packageManager.getPackageInfo(name, 0) }
                                    .getOrNull()
                            }
                provider?.let { "${it.packageName} ${it.versionName.orEmpty()}" } ?: "未检测到 WebView"
            }
            .getOrDefault("未检测到 WebView")
    }
    SettingsCard("版本") {
        SettingsRow(
            "PrismAura $versionName",
            updateState.message,
            modifier = Modifier.focusAnchor(entry).then(entryEdge).rememberFocus(bodyFocusMemory),
            onActivate = { checkAppUpdates() },
        ) {
            SettingsButton(
                if (updateState.checking) "正在检查…"
                else if (updateState.release != null) "查看更新" else "检查更新",
                { checkAppUpdates() },
                icon = "refresh",
                passive = true,
            )
        }
        SettingsDivider()
        PrismSwitchRow(
            "新版本提醒",
            updateState.remind,
            onReminder,
            edgeToRail.rememberFocus(bodyFocusMemory),
        )
    }
    SettingsCard("运行环境") {
        SettingsRow("设备") {
            Text(
                deviceInfo,
                color = c.muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SettingsDivider()
        SettingsRow("WebView") {
            Text(
                webViewInfo,
                color = c.muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    SettingsCard("缓存") {
        SettingsRow(
            "磁盘缓存",
            modifier = edgeToRail.rememberFocus(bodyFocusMemory),
            onActivate = clearCache,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    cacheBytes?.let(::formatBytes) ?: "…",
                    color = c.muted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 1,
                )
                SettingsButton(
                    if (clearingCache) "清理中…" else "清理",
                    { clearCache() },
                    icon = "trash",
                    passive = true,
                )
            }
        }
    }
    SettingsCard("数据") {
        SettingsRow(
            "备份与恢复",
            "曲库、音源和设置",
            modifier = edgeToRail.rememberFocus(bodyFocusMemory),
            onActivate = onBackup,
        ) {
            SettingsButton("用手机管理", onBackup, icon = "qr", passive = true)
        }
    }
}

@Composable
internal fun DiscoverySettings(
    activePlugins: List<Plugin>,
    recommendationPluginId: String,
    chartPluginId: String,
    homeShelfHidden: Set<String>,
    entry: FocusAnchor,
    entryEdge: Modifier,
    edgeToRail: Modifier,
    bodyFocusMemory: MutableState<FocusRequester?>,
    updatePreference: (String, String) -> Unit,
    onToggleShelf: (String) -> Unit,
    onLibrary: () -> Unit,
) {
    SettingsCard("内容来源") {
        val recOptions = activePlugins.filter { it.capabilities.recommendations }
        SettingsChoiceRow("推荐歌单", recOptions.map { it.id to it.displayName },
            recOptions.firstOrNull { it.id == recommendationPluginId }?.id ?: recOptions.firstOrNull()?.id.orEmpty(),
            { updatePreference("recommendationSource", it) },
            Modifier.focusAnchor(entry).then(entryEdge).rememberFocus(bodyFocusMemory),
            optionIcon = { option -> recOptions.firstOrNull { it.id == option.first }?.let { PlatformIcon(it.platform, Modifier.size(22.dp)) } })
        SettingsDivider()
        val chartOptions = activePlugins.filter { it.capabilities.charts }
        SettingsChoiceRow("排行榜", chartOptions.map { it.id to it.displayName },
            chartOptions.firstOrNull { it.id == chartPluginId }?.id ?: chartOptions.firstOrNull()?.id.orEmpty(),
            { updatePreference("chartSource", it) },
            edgeToRail.rememberFocus(bodyFocusMemory),
            optionIcon = { option -> chartOptions.firstOrNull { it.id == option.first }?.let { PlatformIcon(it.platform, Modifier.size(22.dp)) } })
    }
    val shelfOptions = tv.prism.ui.home.homeSectionOptions
    SettingsCard(
        "首页栏目",
        "已显示 ${shelfOptions.count { it.first !in homeShelfHidden }} / ${shelfOptions.size}",
    ) {
        SettingsRow("显示在首页") {
            FlowRow(
                Modifier.weight(1f, fill = false),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                shelfOptions.forEachIndexed { index, (id, label) ->
                    SettingsChip(
                        label,
                        id !in homeShelfHidden,
                        { onToggleShelf(id) },
                        (if (index == 0) edgeToRail else Modifier).rememberFocus(bodyFocusMemory),
                    )
                }
            }
        }
        SettingsDivider()
        SettingsRow(
            "管理歌单",
            modifier = edgeToRail.rememberFocus(bodyFocusMemory),
            onActivate = onLibrary,
        ) {
            SettingsButton("打开「我的」", onLibrary, passive = true)
        }
    }
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1L shl 30 -> "%.1f GB".format(java.util.Locale.US, bytes / 1073741824.0)
        bytes >= 1L shl 20 -> "%.1f MB".format(java.util.Locale.US, bytes / 1048576.0)
        bytes >= 1L shl 10 -> "%d KB".format(java.util.Locale.US, bytes / 1024)
        else -> "$bytes B"
    }

@Composable
internal fun AppearanceSettings(
    theme: String,
    reduceMotion: Boolean,
    entry: FocusAnchor,
    rail: FocusRequester,
    pluginsFocus: FocusRequester,
    entryUpScrollsBack: Modifier,
    edgeToRail: Modifier,
    bodyFocusMemory: MutableState<FocusRequester?>,
    updatePreference: (String, String) -> Unit,
) {
    val themes =
        listOf(
            Triple("gold", "极夜暖金", "温暖、沉浸"),
            Triple("porcelain", "晨光白瓷", "明亮、柔和"),
            Triple("mono", "静谧黑白", "纯粹、克制"),
        )
    fun pickTheme(value: String) {
        updatePreference("theme", value)
    }
    SettingsCard("主题") {
        val themeTargets = remember { themes.map { FocusRequester() } }
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            themes.forEachIndexed { index, (value, label, detail) ->
                AuraThemeCard(
                    label,
                    detail,
                    prismColors(value),
                    theme == value,
                    { pickTheme(value) },
                    Modifier.weight(1f)
                        .focusRequester(themeTargets[index])
                        .then(if (theme == value) Modifier.focusAnchor(entry) else Modifier)
                        .focusProperties {
                            left = if (index > 0) themeTargets[index - 1] else rail
                            right = themeTargets.getOrNull(index + 1) ?: FocusRequester.Cancel
                            up = pluginsFocus
                        }
                        .then(entryUpScrollsBack)
                        .rememberFocus(bodyFocusMemory),
                )
            }
        }
    }
    SettingsCard("播放界面") {
        PrismSwitchRow(
            "减少动态效果",
            reduceMotion,
            { updatePreference("reduceMotion", it.toString()) },
            edgeToRail.rememberFocus(bodyFocusMemory),
            description = "关闭流光与过渡动画",
        )
    }
}

@Composable
internal fun PlaybackSettings(
    quality: String,
    autoSourceFallback: Boolean,
    playMode: PlaybackMode,
    pluginWorkerLimit: Int,
    automaticWorkers: Int,
    showMiniLyrics: Boolean,
    lyricFontLevel: String,
    hideHeading: Boolean,
    subLyrics: Boolean,
    lyricLrclib: Boolean,
    lyricKugou: Boolean,
    lyricNetease: Boolean,
    entry: FocusAnchor,
    entryEdge: Modifier,
    edgeToRail: Modifier,
    bodyFocusMemory: MutableState<FocusRequester?>,
    updatePreference: (String, String) -> Unit,
    setAutoSourceFallback: (Boolean) -> Unit,
) {
    val qualityTiers = listOf("low" to "流畅", "standard" to "标准", "high" to "高品", "super" to "无损优先")
    fun pickQuality(value: String) {
        updatePreference("quality", value)
    }
    SettingsCard("音质") {
        SettingsChoiceRow("首选音质", qualityTiers, quality, ::pickQuality,
            Modifier.focusAnchor(entry).then(entryEdge).rememberFocus(bodyFocusMemory))
        SettingsDivider()
        PrismSwitchRow(
            "播放失败自动换源",
            autoSourceFallback,
            { setAutoSourceFallback(it) },
            edgeToRail.rememberFocus(bodyFocusMemory),
            description = "改用其他音源的同一首歌",
        )
    }
    val playModes = PlaybackMode.entries.map { it to it.label }
    fun pickMode(value: PlaybackMode) {
        updatePreference("mode", value.id)
    }
    SettingsCard("播放与搜索") {
        SettingsChoiceRow("播放模式", playModes, playMode, ::pickMode,
            edgeToRail.rememberFocus(bodyFocusMemory))
        SettingsDivider()
        val workerOptions =
            listOf(
                0 to "自动 · ${automaticWorkers} 个",
                1 to "1 个",
                2 to "2 个",
                3 to "3 个",
                4 to "4 个",
            )
        fun pickWorkers(value: Int) {
            updatePreference("pluginWorkerLimit", value.toString())
        }
        SettingsChoiceRow("搜索并发", workerOptions, pluginWorkerLimit, ::pickWorkers,
            edgeToRail.rememberFocus(bodyFocusMemory), description = "调大更快，也更占内存")
    }

    /* All lyric-display preferences live in this card. Time calibration
    stays on the player page — it only makes sense while listening. */
    SettingsCard("歌词") {
        PrismSwitchRow(
            "播放条歌词",
            showMiniLyrics,
            { updatePreference("miniLyrics", it.toString()) },
            edgeToRail.rememberFocus(bodyFocusMemory),
            description = "底部播放条显示当前一句",
        )
        SettingsDivider()
        val levels = lyricFontLevels.map { it to it }
        fun pickFont(level: String) {
            updatePreference("lyricFontLevel", level)
        }
        SettingsChoiceRow("字号", levels, lyricFontLevel, ::pickFont,
            edgeToRail.rememberFocus(bodyFocusMemory))
        SettingsDivider()
        PrismSwitchRow(
            "隐藏片头信息",
            hideHeading,
            { updatePreference("hideHeading", it.toString()) },
            edgeToRail.rememberFocus(bodyFocusMemory),
            description = LYRIC_HEADING_HINT,
        )
        SettingsDivider()
        PrismSwitchRow(
            "翻译与注音",
            subLyrics,
            { updatePreference("subLyrics", it.toString()) },
            edgeToRail.rememberFocus(bodyFocusMemory),
            description = "歌词来源提供翻译或注音时显示",
        )
    }
    SettingsCard("自动查找歌词") {
        val sources = listOf(
            LyricChoice("LRCLIB", "开放词库，部分歌曲支持逐词高亮", "lyricLrclib", lyricLrclib),
            LyricChoice("酷狗", "可查找中文歌曲的逐行歌词", "lyricKugou", lyricKugou),
            LyricChoice("网易云", "部分歌曲提供逐字歌词和翻译", "lyricNetease", lyricNetease),
        )
        var open by remember { mutableStateOf(false) }
        val trigger = remember { FocusRequester() }
        val scope = rememberCoroutineScope()
        val names = sources.filter { it.checked }.map { it.name }
        val summary = names.take(3).joinToString("、").ifEmpty { "未选择" } +
            if (names.size > 3) " +${names.size - 3}" else ""
        SettingsRow("补充词库", "缺少歌词时自动查找，可在播放页「更多」中切换来源。",
            modifier = edgeToRail.focusRequester(trigger).rememberFocus(bodyFocusMemory),
            onActivate = { open = true },
        ) {
            var anchor by remember { mutableStateOf(Rect.Zero) }
            Box(Modifier.onGloballyPositioned { anchor = it.boundsInWindow() }) {
                SettingsButton(summary, { open = true }, passive = true, trailingIcon = "chev")
                if (open) ChoicePopup(
                    options = sources, selectedIndex = 0, label = { it.name },
                    description = { it.description }, checked = { it.checked },
                    onSelect = { updatePreference(it.preference, (!it.checked).toString()) },
                    onDismiss = {
                        open = false
                        scope.launch { withFrameNanos {}; trigger.requestFocus() }
                    },
                    icon = {},
                    anchor = anchor,
                )
            }
        }
    }
}

private data class LyricChoice(val name: String, val description: String, val preference: String, val checked: Boolean)

/** Left remains navigation; only OK opens choices, and Back never commits a preview. */
@Composable
private fun <T> SettingsChoiceRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    optionIcon: (@Composable (Pair<T, String>) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val origin = remember { FocusRequester() }
    val selectedIndex = options.indexOfFirst { it.first == selected }
    SettingsRow(label, description, modifier = modifier.focusRequester(origin),
        onActivate = { if (options.isNotEmpty()) open = true }) {
        var anchor by remember { mutableStateOf(Rect.Zero) }
        Box(Modifier.onGloballyPositioned { anchor = it.boundsInWindow() }) {
            val selectedOption = options.getOrNull(selectedIndex)
            /* Every value row opens a sheet, so it wears the same down chevron;
               the glyph says "opens a list", not "this setting is a list". A source
               row also keeps its platform mark after choosing, matching the sheet. */
            SettingsButton(selectedOption?.second ?: if (options.isEmpty()) "暂无可选项" else "当前值不可用",
                { if (options.isNotEmpty()) open = true }, passive = true, trailingIcon = "chev",
                leading = if (selectedOption != null && optionIcon != null) {
                    { optionIcon.invoke(selectedOption) }
                } else null)
            if (open) ChoicePopup(
                options = options,
                selectedIndex = selectedIndex,
                label = { it.second },
                onDismiss = { open = false },
                onSelect = { onSelect(it.first); open = false },
                icon = optionIcon ?: {},
                anchor = anchor,
            )
        }
    }
    RestoreFocusAfterDialog(open, origin, origin)
}
