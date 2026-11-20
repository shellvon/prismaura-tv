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
import androidx.compose.ui.graphics.lerp
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
import tv.prism.library.LibraryState
import tv.prism.ui.widgets.SettingsChip
import tv.prism.ui.widgets.auraFocus
import tv.prism.ui.widgets.Glyph
import tv.prism.ui.widgets.PlatformIcon
import tv.prism.ui.widgets.AuraThemeCard
import tv.prism.ui.widgets.prismColors
import tv.prism.playback.PlaybackMode
import tv.prism.ui.widgets.SettingsSeg
import tv.prism.ui.playback.lyricFontLevels
import tv.prism.ui.playback.LYRIC_HEADING_HINT

@Composable
internal fun SettingsPage(
    currentSection: () -> String,
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
    val sectionName = currentSection()
    val pageScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        /* The rail and the panel are separate focus spaces, so a
        geometric Left from the content falls back to whichever
        rail row is nearest and swaps the section under the
        remote. Every left edge names the open section. */
        val edgeToRail =
            Modifier.focusProperties {
                val anchor = sectionFocus.getValue(currentSection())
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
                    val anchor = sectionFocus.getValue(currentSection())
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
                            /* Same contract as the top bar: the panel changes
                            with the highlight, so one press both previews
                            and selects. */
                            .onFocusChanged {
                                if (it.isFocused && currentSection() != section)
                                    selectSection(section)
                            }
                            /* Only the selected section's content is composed; pointing
                            Right anywhere else would target a node that isn't there. */
                            .focusProperties {
                                val entry = sectionEntryFocus.getValue(section)
                                if (section == currentSection() && entry.attached)
                                    right = entry.requester
                                if (index == 0) up = navFocus
                            }
                            /* An unselected section's content isn't on
                            screen yet: Right would find no candidate
                            and drop focus off the screen. */
                            .onPreviewKeyEvent {
                                it.type == KeyEventType.KeyDown &&
                                    it.key == Key.DirectionRight &&
                                    section != currentSection()
                            },
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
    cacheSize: Long,
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
    SettingsCard("数据") {
        SettingsRow(
            "缓存",
            "清理不影响收藏、歌单与登录",
            modifier = edgeToRail.rememberFocus(bodyFocusMemory),
            onActivate = clearCache,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (cacheSize < 0) "…" else formatBytes(cacheSize),
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
        SettingsDivider()
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
    libraryState: LibraryState,
    homeShelfHidden: Set<String>,
    entry: FocusAnchor,
    entryEdge: Modifier,
    edgeToRail: Modifier,
    bodyFocusMemory: MutableState<FocusRequester?>,
    updatePreference: (String, String) -> Unit,
    onToggleShelf: (String) -> Unit,
    onLibrary: () -> Unit,
) {
    val pageScope = rememberCoroutineScope()
    SettingsCard("内容来源") {
        /* When the popup closes, focus must return to this row — a popup
        is its own focus window and the top bar is otherwise a dead end. */
        val restoreRowFocus: (Boolean) -> Unit = { open ->
            if (!open)
                pageScope.launch {
                    withFrameNanos {}
                    bodyFocusMemory.value?.let { requester ->
                        runCatching { requester.requestFocus() }
                    }
                }
        }
        var recOpen by remember { mutableStateOf(false) }
        val recOptions = activePlugins.filter { it.capabilities.recommendations }
        SettingsRow(
            "推荐歌单",
            modifier = Modifier.focusAnchor(entry).then(entryEdge).rememberFocus(bodyFocusMemory),
            onActivate = { if (recOptions.isNotEmpty()) recOpen = true },
        ) {
            SourcePicker(
                recOptions,
                recommendationPluginId,
                open = recOpen,
                onOpenChange = {
                    recOpen = it
                    restoreRowFocus(it)
                },
                passive = true,
            ) { plugin ->
                updatePreference("recommendationSource", plugin.id)
            }
        }
        SettingsDivider()
        var chartOpen by remember { mutableStateOf(false) }
        val chartOptions = activePlugins.filter { it.capabilities.charts }
        SettingsRow(
            "排行榜",
            modifier = edgeToRail.rememberFocus(bodyFocusMemory),
            onActivate = { if (chartOptions.isNotEmpty()) chartOpen = true },
        ) {
            SourcePicker(
                chartOptions,
                chartPluginId,
                open = chartOpen,
                onOpenChange = {
                    chartOpen = it
                    restoreRowFocus(it)
                },
                passive = true,
            ) { plugin ->
                updatePreference("chartSource", plugin.id)
            }
        }
    }
    /* Every shelf is offered, hidden ones included, so a playlist
    imported after the last choice can be brought back onto 首页
    instead of staying invisible. */
    val shelfOptions =
        listOf("favorites" to "我的收藏", "recent" to "最近播放", "frequent" to "近四周常听") +
            libraryState.playlists.map { it.id to it.name }
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

/* open is hoisted to the caller: focus lives on the whole row and OK opens the sheet
there. passive skips the focus ring and pointer events — it only shows the value. */
@Composable
private fun SourcePicker(
    options: List<Plugin>,
    selected: String,
    modifier: Modifier = Modifier,
    open: Boolean = false,
    onOpenChange: (Boolean) -> Unit = {},
    passive: Boolean = false,
    onSelect: (Plugin) -> Unit,
) {
    val c = LocalPrismColors.current
    val current = options.firstOrNull { it.id == selected } ?: options.firstOrNull()
    Box {
        /* 选择音源只影响对应的推荐或榜单。 */
        Row(
            modifier
                .height(34.dp)
                .then(if (passive) Modifier else Modifier.auraFocus(RoundedCornerShape(7.dp), 1f))
                .clip(RoundedCornerShape(7.dp))
                .border(1.dp, c.accent.copy(alpha = .24f), RoundedCornerShape(7.dp))
                .background(c.accent.copy(alpha = .055f))
                .then(
                    if (passive) Modifier.focusProperties { canFocus = false }
                    else Modifier.clickable { onOpenChange(true) }
                )
                .padding(start = 11.dp, end = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                current?.displayName ?: "暂无支持此能力的音源",
                color = c.foreground.copy(alpha = .9f),
                fontSize = 12.sp,
                maxLines = 1,
            )
            Glyph("chev", Modifier.size(10.dp), lerp(c.accent, c.glow, .5f))
        }
        if (open)
            ChoicePopup(
                options,
                options.indexOfFirst { it.id == current?.id },
                label = { it.displayName },
                onDismiss = { onOpenChange(false) },
                onSelect = {
                    onSelect(it)
                    onOpenChange(false)
                },
                icon = { PlatformIcon(it.platform, Modifier.size(22.dp)) },
            )
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
    /* 两栏布局里左键归左栏（按左就回导航），行内用 OK 循环改值。 */
    fun cycleQuality() {
        val i = qualityTiers.indexOfFirst { it.first == quality }
        if (i < 0) return
        pickQuality(qualityTiers[(i + 1) % qualityTiers.size].first)
    }
    SettingsCard("音质") {
        SettingsRow(
            "首选音质",
            modifier = Modifier.focusAnchor(entry).then(entryEdge).rememberFocus(bodyFocusMemory),
            onActivate = { cycleQuality() },
        ) {
            SettingsSeg(qualityTiers, quality, ::pickQuality)
        }
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
    fun cycleMode() {
        val i = playModes.indexOfFirst { it.first == playMode }
        if (i < 0) return
        pickMode(playModes[(i + 1) % playModes.size].first)
    }
    SettingsCard("播放与搜索") {
        SettingsRow(
            "播放模式",
            modifier = edgeToRail.rememberFocus(bodyFocusMemory),
            onActivate = { cycleMode() },
        ) {
            SettingsSeg(playModes, playMode, ::pickMode)
        }
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
        fun cycleWorkers() {
            val i = workerOptions.indexOfFirst { it.first == pluginWorkerLimit }
            if (i < 0) return
            pickWorkers(workerOptions[(i + 1) % workerOptions.size].first)
        }
        SettingsRow(
            "搜索并发",
            "调大更快，也更占内存",
            modifier = edgeToRail.rememberFocus(bodyFocusMemory),
            onActivate = { cycleWorkers() },
        ) {
            SettingsSeg(workerOptions, pluginWorkerLimit, ::pickWorkers)
        }
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
        fun cycleFont() {
            val i = levels.indexOfFirst { it.first == lyricFontLevel }
            if (i < 0) return
            pickFont(levels[(i + 1) % levels.size].first)
        }
        SettingsRow(
            "字号",
            modifier = edgeToRail.rememberFocus(bodyFocusMemory),
            onActivate = { cycleFont() },
        ) {
            SettingsSeg(levels, lyricFontLevel, ::pickFont)
        }
        SettingsDivider()
        PrismSwitchRow(
            "自动隐藏片头署名",
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
        )
    }
}
