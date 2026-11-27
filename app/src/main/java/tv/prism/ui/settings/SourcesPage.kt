package tv.prism.ui.settings

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.prism.ui.PluginViewModel
import tv.prism.sources.Plugins
import tv.prism.sources.PluginsState
import tv.prism.ui.widgets.FocusAnchor
import tv.prism.sources.SyncResult
import tv.prism.sources.Plugin
import tv.prism.ui.widgets.LocalPrismColors
import tv.prism.ui.widgets.requestIfAttached
import tv.prism.ui.widgets.SettingsCard
import tv.prism.ui.widgets.SettingsButton
import tv.prism.ui.widgets.focusAnchor
import tv.prism.ui.widgets.rememberFocus
import tv.prism.ui.widgets.leftTo
import tv.prism.ui.widgets.Input
import tv.prism.ui.widgets.PrismAction
import tv.prism.ui.widgets.PhoneFillButton
import tv.prism.ui.widgets.PrismConfirmDialog
import tv.prism.ui.widgets.SettingsDivider
import tv.prism.ui.widgets.auraFocus
import tv.prism.ui.widgets.tvClickable
import tv.prism.ui.widgets.Glyph
import tv.prism.ui.widgets.PrismActionMenu
import tv.prism.ui.widgets.PrismMenuAction
import tv.prism.ui.widgets.RestoreFocusAfterDialog
import tv.prism.ui.widgets.PrismMenuSection

@Composable
internal fun SourcesPage(
    pluginViewModel: PluginViewModel,
    plugins: Plugins,
    state: PluginsState,
    busyState: MutableState<Boolean>,
    updateProgress: MutableState<Pair<String, String>?>,
    operationScope: CoroutineScope,
    pluginsFocus: FocusRequester,
    sourceActionFocus: FocusAnchor,
    addFocus: FocusRequester,
    entryEdge: Modifier,
    edgeToRail: Modifier,
    bodyFocusMemory: MutableState<FocusRequester?>,
    onImport: (SyncResult) -> Unit,
    onStatus: (String) -> Unit,
    searchSubscription: (String) -> Unit,
    fillOnPhone: (() -> Unit) -> Unit,
    sourceRow: @Composable (Plugin, Boolean, () -> Unit, Modifier) -> Unit,
) {
    val ui by pluginViewModel.state.collectAsState()
    val subscription = ui.address
    var busy by busyState
    var subscriptionUpdateProgress by updateProgress
    val pageScope = rememberCoroutineScope()
    val c = LocalPrismColors.current
    DisposableEffect(Unit) { onDispose { pluginViewModel.showAdd(false) } }
    var removingSubscription by remember { mutableStateOf<String?>(null) }
    var addProgress by remember { mutableStateOf("") }
    var sourceNotice by remember { mutableStateOf("") }
    val phoneFocus = remember { FocusRequester() }
    val addressFocus = remember { FocusRequester() }
    val updateSourcesFocus = remember { FocusRequester() }
    val subscriptionGroups =
        state.subscriptions.map { url ->
            url to
                state.plugins.filter { it.origin == url || (it.origin.isBlank() && it.url == url) }
        }
    val standalone = state.plugins.filter { it.origin.isBlank() && it.url !in state.subscriptions }
    fun closeAddSource() {
        pluginViewModel.showAdd(false)
        pageScope.launch {
            withFrameNanos {}
            sourceActionFocus.requestIfAttached()
        }
    }
    fun sourceRemoved() {
        pageScope.launch {
            withFrameNanos {}
            sourceActionFocus.requestIfAttached()
        }
    }
    fun addSource() {
        if (busy) return
        val address = pluginViewModel.state.value.address.trim()
        if (address.isBlank()) {
            pluginViewModel.showAddressError("请输入地址")
            return
        }
        operationScope.launch {
            busy = true
            pluginViewModel.showAddressError("")
            addProgress = "正在读取音源…"
            try {
                val result = plugins.importUrl(address) { done, total, label ->
                    addProgress = "正在导入 $done/$total：$label"
                }
                onImport(result)
                sourceNotice = result.message
                if (result.failed) pluginViewModel.showAddressError(result.message)
                else {
                    pluginViewModel.editAddress("")
                    closeAddSource()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                pluginViewModel.showAddressError(e.message ?: "添加失败，请检查地址")
            } finally {
                busy = false
                addProgress = ""
            }
        }
    }
    SettingsCard("添加") {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("添加音源", color = c.foreground, fontSize = 15.sp, lineHeight = 21.sp)
                Text("请只导入可信来源的插件", color = c.muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            PrismAction(
                "添加",
                { pluginViewModel.showAdd(true) },
                Modifier.focusAnchor(sourceActionFocus)
                    .then(entryEdge)
                    .focusProperties { right = updateSourcesFocus }
                    .rememberFocus(bodyFocusMemory),
                primary = true,
                icon = "plus",
            )
            PrismAction(
                if (busy) "更新中…" else "全部更新",
                {
                    if (!busy)
                        operationScope.launch {
                            busy = true
                            try {
                                val result = plugins.syncAll { done, total, label ->
                                    sourceNotice = "正在更新 $done/$total：$label"
                                }
                                sourceNotice = result.message
                                onImport(result)
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) {
                                sourceNotice = "同步失败：${e.message}"
                                onStatus(sourceNotice)
                            } finally {
                                busy = false
                            }
                        }
                },
                Modifier.focusRequester(updateSourcesFocus)
                    .focusProperties {
                        up = pluginsFocus
                        leftTo(sourceActionFocus)
                    }
                    .rememberFocus(bodyFocusMemory),
                icon = "refresh",
            )
        }
        if (sourceNotice.isNotBlank()) {
            Text(sourceNotice, color = c.muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp))
        }
    }
    SettingsCard("已安装", "已启用 ${state.plugins.count { it.enabled }} / ${state.plugins.size}") {
        if (state.plugins.isEmpty() && state.subscriptions.isEmpty())
            Text(
                "还没有音源。添加 MusicFree 插件或订阅后就能开始听歌",
                color = c.muted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            )

        if (ui.adding)
            Dialog(onDismissRequest = { closeAddSource() }) {
                LaunchedEffect(Unit) {
                    repeat(2) { withFrameNanos {} }
                    addressFocus.requestFocus()
                }
                Column(
                    Modifier.width(580.dp)
                        .background(
                            c.panel,
                            androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                        )
                        .padding(26.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("添加音源", color = c.foreground, fontSize = 22.sp)
                    Text("同一局域网的手机可选「手机代填」，扫码粘贴后回到电视确认。", color = c.muted, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Input(
                            subscription, { pluginViewModel.editAddress(it) }, "插件 .js 或订阅 .json 地址",
                            Modifier.weight(1f).focusRequester(addressFocus).focusProperties { down = addFocus; right = phoneFocus },
                            keyboardType = KeyboardType.Uri, manualKeyboard = true, onSubmit = { addFocus.requestFocus() },
                        )
                        PhoneFillButton("手机代填音源地址", {
                            fillOnPhone {
                                pageScope.launch {
                                    repeat(2) { withFrameNanos { } }
                                    runCatching { phoneFocus.requestFocus() }
                                }
                            }
                        }, Modifier.focusRequester(phoneFocus).focusProperties { left = addressFocus; down = addFocus })
                    }
                    if (addProgress.isNotBlank()) Text(addProgress, color = c.muted, fontSize = 12.sp, maxLines = 2)
                    if (ui.addressError.isNotBlank())
                        Text(
                            ui.addressError,
                            color = c.danger,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrismAction("取消", { closeAddSource() }, Modifier.focusProperties { up = addressFocus })
                        PrismAction(
                            if (addProgress.isNotBlank()) "添加中…" else if (ui.addressError.isNotBlank()) "重试添加" else "添加",
                            { addSource() },
                            Modifier.focusRequester(addFocus).focusProperties { up = addressFocus },
                            primary = true,
                            icon = "plus",
                        )
                    }
                }
            }
        removingSubscription?.let { url ->
            PrismConfirmDialog(
                "移除订阅？",
                "同时移除订阅内音源，保留本机歌单和收藏",
                "移除订阅",
                destructive = true,
                onDismiss = { removingSubscription = null },
                onConfirm = {
                    removingSubscription = null
                    operationScope.launch {
                        try {
                            plugins.removeSubscription(url)
                            onStatus("已移除订阅及其音源")
                            sourceRemoved()
                        } catch (e: Exception) {
                            onStatus(e.message ?: "移除失败")
                        }
                    }
                },
            )
        }

        /* A subscription is the thing that can be updated, so it is the row
        that owns removal. Its plugins are only switched on or off:
        deleting one would just bring it back on the next sync. */
        val firstRowEdge = edgeToRail.rememberFocus(bodyFocusMemory)
        var firstRow = true
        subscriptionGroups.forEach { (url, owned) ->
            val expanded = url !in ui.collapsed
            val enabledCount = owned.count { it.enabled }
            val allEnabled = owned.isNotEmpty() && enabledCount == owned.size
            val searchable = owned.filter { it.enabled && it.capabilities.has("search") }
            var infoOpen by remember(url) { mutableStateOf(false) }
            var groupMenu by remember(url) { mutableStateOf(false) }
            var groupPending by remember(url) { mutableStateOf(false) }
            var groupNotice by remember(url) { mutableStateOf("") }
            val titleFocus = remember(url) { FocusRequester() }
            val moreFocus = remember(url) { FocusRequester() }
            val subscriptionUri = remember(url) { Uri.parse(url) }
            val groupTitle = plugins.subscriptionLabel(url)
            RestoreFocusAfterDialog(groupMenu || infoOpen, moreFocus, moreFocus)
            fun updateGroup() {
                if (!busy && subscriptionUpdateProgress == null)
                    operationScope.launch {
                        busy = true
                        groupNotice = "更新中…"
                        subscriptionUpdateProgress = url to "更新中…"
                        try {
                            val result = plugins.importUrl(url) { current, total, _ ->
                                val text = "更新中 $current/$total"
                                subscriptionUpdateProgress = url to text
                                groupNotice = text
                            }
                            groupNotice = result.message
                            onImport(result)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            groupNotice = "更新失败：${e.message}"
                            onStatus(groupNotice)
                        } finally {
                            subscriptionUpdateProgress = null
                            busy = false
                        }
                    }
            }
            if (!firstRow) SettingsDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f)
                        .focusRequester(titleFocus)
                        .then(edgeToRail)
                        .rememberFocus(bodyFocusMemory)
                        .focusProperties { right = moreFocus }
                        .auraFocus(RoundedCornerShape(8.dp), 1f)
                        .clip(RoundedCornerShape(8.dp))
                        .tvClickable({ pluginViewModel.toggleSubscription(url) })
                        .padding(6.dp)
                        .semantics { contentDescription = if (expanded) "折叠此订阅" else "展开此订阅" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Glyph(if (expanded) "chev" else "chevR", Modifier.size(12.dp), c.muted)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            groupTitle,
                            color = c.foreground,
                            fontSize = 15.sp,
                            lineHeight = 21.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(
                                    subscriptionUpdateProgress?.takeIf { it.first == url }?.second
                                        ?: "${owned.size} 个音源 · 已启用 $enabledCount",
                                    subscriptionUri.lastPathSegment?.takeIf { it.isNotBlank() },
                                )
                                .joinToString(" · "),
                            color = c.muted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                SettingsButton(
                    "管理",
                    { groupMenu = true },
                    Modifier.focusRequester(moreFocus)
                        .focusProperties { left = titleFocus }
                        .rememberFocus(bodyFocusMemory),
                )
            }
            if (groupMenu)
                PrismActionMenu(
                    groupTitle,
                    "${owned.size} 个音源 · 已启用 $enabledCount",
                    onDismiss = {
                        groupMenu = false
                    },
                    notice = groupNotice,
                    actions =
                        listOf(
                            PrismMenuAction(
                                "使用此组搜索",
                                "search",
                                navigates = true,
                                enabled = !busy && !groupPending && searchable.isNotEmpty(),
                                description =
                                    if (searchable.isEmpty()) "组内没有已启用且支持搜索的音源"
                                    else "使用 ${searchable.size} 个已启用音源",
                            ) {
                                groupMenu = false
                                runCatching { searchSubscription(url) }
                                    .onFailure { onStatus(it.message ?: "无法切换音源") }
                            },
                            PrismMenuAction("订阅信息", "info", navigates = true) {
                                groupMenu = false
                                infoOpen = true
                            },
                            // This is a command, not a selected option; state belongs in its description.
                            PrismMenuAction(
                                if (groupPending) "正在保存…" else if (allEnabled) "全部停用" else "全部启用",
                                if (allEnabled) "disabled" else "enabled",
                                description = when {
                                    allEnabled -> "当前：已全部启用"
                                    enabledCount == 0 -> "当前：已全部停用"
                                    else -> "当前：已启用 $enabledCount / ${owned.size}"
                                },
                                enabled = !busy && owned.isNotEmpty(),
                                keepOpen = true,
                                section = PrismMenuSection.MANAGEMENT,
                            ) {
                                if (!busy && !groupPending) {
                                    groupPending = true
                                    groupNotice = "正在保存…"
                                    operationScope.launch {
                                        try {
                                            plugins.setEnabled(owned.map { it.id }, !allEnabled)
                                            groupNotice =
                                                if (allEnabled) "已停用 ${owned.size} 个音源"
                                                else "已启用 ${owned.size} 个音源"
                                            onStatus(groupNotice)
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            groupNotice = e.message ?: "操作失败"
                                            onStatus(groupNotice)
                                        } finally {
                                            groupPending = false
                                        }
                                    }
                                }
                            },
                            /* Refreshing a subscription is upkeep, not a frequent action:
                               it belongs with the management commands, not the first row.
                               No extra dialog — the notice line reports progress. */
                            PrismMenuAction(
                                if (subscriptionUpdateProgress?.first == url) "正在更新…" else "更新分组",
                                "refresh",
                                description =
                                    if (subscriptionUpdateProgress?.first == url) "正在获取最新音源"
                                    else "重新拉取此订阅的音源",
                                keepOpen = true,
                                section = PrismMenuSection.MANAGEMENT,
                            ) { updateGroup() },
                            PrismMenuAction(
                                "移除订阅",
                                "trash",
                                description = "同时移除订阅内音源，保留本机歌单和收藏",
                                destructive = true,
                                enabled = !busy,
                            ) {
                                groupMenu = false
                                removingSubscription = url
                            },
                        ),
                )
            /* 分组信息走和音源信息同一套外壳：同一张概览卡、同样的分段与
            滚动手感，只是内容换成「这组有什么、能做什么、地址在哪」。 */
            if (infoOpen) {
                val groupClose = {
                    infoOpen = false
                }
                val description = state.subscriptionDescriptions[url]?.takeIf { it.isNotBlank() }
                /* 订阅的「名字」就是那段 desc，放进卡片第一行；标题只留通用名，
                否则 22sp 的标题会和「简介」里的同一句话重复一遍。 */
                PrismInfoDialog(
                    "订阅信息",
                    url.substringAfter("://").substringBefore('/'),
                    groupClose,
                    header = {
                        InfoHeaderCard(
                            primary = groupTitle,
                            secondary = "${owned.size} 个音源 · 已启用 $enabledCount",
                            pill =
                                when {
                                    owned.isEmpty() -> "空分组"
                                    allEnabled -> "已全部启用"
                                    enabledCount == 0 -> "已全部停用"
                                    else -> "部分启用"
                                },
                            pillColor = if (owned.isNotEmpty() && allEnabled) c.accent else c.muted,
                        ) {
                            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                                Glyph("layers", Modifier.size(22.dp), c.muted)
                            }
                        }
                    },
                    focusClose = true,
                ) {
                    /* 订阅不是音源：这里只交代「这组是什么、地址在哪」。
                    能力是组内每个音源各自的事，求并集只会得到一个「什么都有」的假象。 */
                    if (description != null && description != groupTitle)
                        InfoSection("简介") {
                            Text(
                                description,
                                color = c.foreground.copy(alpha = .9f),
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                            )
                        }
                    InfoSection("地址") {
                        SourceAddressList(listOf(SourceAddress("订阅地址", url)), full = true)
                    }
                }
            }
            if (expanded)
                owned.forEach { plugin ->
                    key(plugin.id) {
                        SettingsDivider()
                        Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            sourceRow(
                                plugin,
                                true,
                                ::sourceRemoved,
                                if (firstRow) firstRowEdge
                                else edgeToRail.rememberFocus(bodyFocusMemory),
                            )
                        }
                        firstRow = false
                    }
                }
        }

        if (standalone.isNotEmpty() && state.subscriptions.isNotEmpty()) {
            SettingsDivider()
            Text(
                "单独添加",
                color = c.muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp),
            )
        }
        standalone.forEach { plugin ->
            key(plugin.id) {
                if (!firstRow) SettingsDivider()
                Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    sourceRow(
                        plugin,
                        false,
                        ::sourceRemoved,
                        if (firstRow) firstRowEdge else edgeToRail.rememberFocus(bodyFocusMemory),
                    )
                }
                firstRow = false
            }
        }
    }
}
