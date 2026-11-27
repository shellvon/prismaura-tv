package tv.prism.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import tv.prism.sources.Plugin
import tv.prism.ui.widgets.FocusAnchor
import tv.prism.sources.Plugins
import tv.prism.ui.widgets.LocalPrismColors
import tv.prism.ui.widgets.PlatformIcon
import tv.prism.ui.widgets.PrismAction
import tv.prism.ui.widgets.PhoneFillButton
import tv.prism.ui.widgets.rememberFocus
import tv.prism.ui.widgets.PrismConfigChoice
import tv.prism.ui.widgets.focusAnchor
import tv.prism.ui.widgets.Glyph
import tv.prism.sources.MusicPlatform
import tv.prism.sources.SourcePlatforms
import tv.prism.ui.widgets.AuraIconButton
import tv.prism.sources.credentialKeys
import tv.prism.ui.widgets.revealRow
import tv.prism.ui.widgets.Input
import tv.prism.ui.widgets.PrismConfirmDialog
import tv.prism.sources.PluginClient
import tv.prism.ui.widgets.PrismQrState
import tv.prism.remote.qrCodeBitmap
import tv.prism.ui.widgets.PrismQrFrame
import tv.prism.ui.widgets.LocalAuraMotion
import tv.prism.ui.widgets.SettingsButton
import tv.prism.ui.widgets.ChoicePopup
import tv.prism.ui.widgets.auraFocus
import tv.prism.ui.widgets.PrismContentDialog
import tv.prism.ui.widgets.rowFocus

@Composable
internal fun PluginConfig(
    plugin: Plugin,
    entry: FocusAnchor,
    memory: MutableState<FocusRequester?>,
    onBack: () -> Unit,
    plugins: Plugins,
    busy: Boolean,
    operationScope: kotlinx.coroutines.CoroutineScope,
    onStatus: (String) -> Unit,
    searchSource: () -> Unit,
    onPhoneForm:
        (List<Pair<String, String>>, Map<String, String>, () -> Unit, (Map<String, String>) -> Unit) -> Unit,
) {
    val c = LocalPrismColors.current
    val fields = plugin.metadata.optJSONArray("userVariables") ?: JSONArray()
    /* 缺 key 的字段不画，也不进焦点链：两边数量对不上会留下一环永远按不到的空位。 */
    val entries =
        remember(plugin.id) {
            buildList {
                for (i in 0 until fields.length()) {
                    val declared = fields.optJSONObject(i) ?: continue
                    val declaredKey = declared.optString("key")
                    if (declaredKey.isNotBlank()) add(declared to declaredKey)
                }
            }
        }
    val values =
        remember(plugin.id) {
            mutableStateMapOf<String, String>().also { map ->
                for (i in 0 until fields.length()) fields.optJSONObject(i)?.let { f ->
                    val k = f.optString("key")
                    if (k.isNotBlank()) map[k] = plugin.variables.optString(k)
                }
            }
        }
    val canLoginQr = plugin.metadata.optBoolean("loginQr")
    val hasFields = entries.isNotEmpty()
    val loginOpen = remember(plugin.id) { mutableStateOf(false) }
    var manualOpen by remember(plugin.id) { mutableStateOf(false) }
    var infoOpen by remember(plugin.id) { mutableStateOf(false) }
    var platformOpen by remember(plugin.id) { mutableStateOf(false) }
    var resetOpen by remember(plugin.id) { mutableStateOf(false) }
    var resetting by remember(plugin.id) { mutableStateOf(false) }
    var saving by remember(plugin.id) { mutableStateOf(false) }
    var error by remember(plugin.id) { mutableStateOf("") }
    val backFocus = remember(plugin.id) { FocusRequester() }
    val manualFocus = remember(plugin.id) { FocusRequester() }
    val infoFocus = remember(plugin.id) { FocusRequester() }
    val resetFocus = remember(plugin.id) { FocusRequester() }
    val platformFocus = remember(plugin.id) { FocusRequester() }
    val fieldFocuses = remember(plugin.id, entries.size) { List(entries.size) { FocusRequester() } }
    val loginFocus = remember(plugin.id) { FocusRequester() }
    val phoneFocus = remember(plugin.id) { FocusRequester() }
    val manualCloseFocus = remember(plugin.id) { FocusRequester() }
    val manualSaveFocus = remember(plugin.id) { FocusRequester() }
    val bottomFocus = remember(plugin.id) { FocusRequester() }
    val scope = rememberCoroutineScope()
    val firstFocus =
        when {
            canLoginQr -> loginFocus
            hasFields -> manualFocus
            else -> infoFocus
        }
    val beforeSettings = firstFocus.takeIf { canLoginQr || hasFields } ?: backFocus
    val dirty = entries.any { (_, key) -> values[key].orEmpty() != plugin.variables.optString(key) }
    fun closeManual() {
        if (saving) return
        values.keys.toList().forEach { key -> values[key] = plugin.variables.optString(key) }
        manualOpen = false
        error = ""
        scope.launch {
            withFrameNanos {}
            manualFocus.requestFocus()
        }
    }
    fun saveManual() {
        if (saving) return
        if (!dirty) {
            closeManual()
            return
        }
        operationScope.launch {
            saving = true
            error = ""
            try {
                val merged = JSONObject(plugin.variables.toString())
                values.forEach { (key, value) -> merged.put(key, value) }
                plugins.setVariables(plugin.id, merged)
                onStatus("配置已保存")
                manualOpen = false
                scope.launch {
                    withFrameNanos {}
                    manualFocus.requestFocus()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "保存失败"
            } finally {
                saving = false
            }
        }
    }
    LaunchedEffect(plugin.id) {
        repeat(2) { withFrameNanos {} }
        firstFocus.requestFocus()
    }
    /* Reset restores a freshly-installed source: variables emptied, cookies cleared, cached
    URLs invalidated. Partial success must report exactly which half failed. */
    fun resetConfiguration() {
        if (resetting) return
        operationScope.launch {
            resetting = true
            error = ""
            try {
                val failures = plugins.resetConfiguration(plugin.id)
                val stored =
                    plugins.state.value.plugins.firstOrNull { it.id == plugin.id }?.variables
                values.keys.toList().forEach { key ->
                    values[key] = stored?.optString(key).orEmpty()
                }
                if (failures.isEmpty()) onStatus("配置已重置") else error = failures.joinToString("；")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "重置失败"
            } finally {
                resetting = false
                scope.launch {
                    withFrameNanos {}
                    runCatching { resetFocus.requestFocus() }
                }
            }
        }
    }
    fun closeReset() {
        resetOpen = false
        scope.launch {
            withFrameNanos {}
            runCatching { resetFocus.requestFocus() }
        }
    }
    fun closeQr() {
        loginOpen.value = false
        scope.launch {
            withFrameNanos {}
            loginFocus.requestFocus()
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlatformIcon(plugin.platform, Modifier.size(42.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${plugin.displayName}配置",
                    fontSize = 22.sp,
                    color = c.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                            plugin.metadata
                                .optString("version")
                                .takeIf { it.isNotBlank() }
                                ?.let { "v$it" },
                            if (plugin.enabled) "已启用" else "已停用",
                        )
                        .joinToString(" · "),
                    color = c.muted,
                    fontSize = 12.sp,
                )
            }
            PrismAction(
                "返回音源",
                { if (!saving) onBack() },
                Modifier.focusRequester(backFocus)
                    .focusProperties { down = firstFocus }
                    .rememberFocus(memory),
            )
        }
        Column(
            Modifier.weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (canLoginQr || hasFields) {
                /* The count only counts keys with a value: it neither validates required
                fields nor blocks saving. */
                if (canLoginQr)
                    PrismConfigChoice(
                        "扫码登录",
                        "使用对应平台 App 扫码，成功后自动保存",
                        "qr",
                        {
                            manualOpen = false
                            loginOpen.value = true
                        },
                        Modifier.fillMaxWidth()
                            .focusRequester(loginFocus)
                            .focusAnchor(entry)
                            .focusProperties {
                                up = backFocus
                                down = if (hasFields) manualFocus else infoFocus
                            }
                            .rememberFocus(memory),
                    )
                if (hasFields) {
                    val filled =
                        entries.count { plugin.variables.optString(it.second).isNotBlank() }
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .focusRequester(manualFocus)
                            .then(if (!canLoginQr) Modifier.focusAnchor(entry) else Modifier)
                            .focusProperties {
                                up = if (canLoginQr) loginFocus else backFocus
                                down = infoFocus
                            }
                            .rememberFocus(memory)
                            .rowFocus()
                            .clickable {
                                values.keys.toList().forEach { key ->
                                    values[key] = plugin.variables.optString(key)
                                }
                                error = ""
                                manualOpen = true
                            }
                            .padding(start = 12.dp, top = 8.dp, end = 10.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph("keyboard", Modifier.size(18.dp), c.muted)
                        Spacer(Modifier.width(12.dp))
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                "手动填写",
                                color = c.foreground,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "${entries.size} 项 · 已填 $filled",
                                color = c.muted,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            )
                        }
                        Text("›", color = c.accent, fontSize = 20.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrismAction(
                    "音源信息",
                    {
                        infoOpen = true
                        platformOpen = false
                    },
                    Modifier.focusRequester(infoFocus)
                        .then(
                            if (!canLoginQr && !hasFields) Modifier.focusAnchor(entry) else Modifier
                        )
                        .focusProperties {
                            up = beforeSettings
                            down = bottomFocus
                            right = platformFocus
                        }
                        .rememberFocus(memory),
                    icon = "info",
                )
                var platformAnchor by remember { mutableStateOf(Rect.Zero) }
                Box(Modifier.onGloballyPositioned { platformAnchor = it.boundsInWindow() }) {
                    val platforms = remember {
                        listOf<MusicPlatform?>(null) +
                            MusicPlatform.entries.filterNot {
                                it in
                                    setOf(
                                        MusicPlatform.LOCAL,
                                        MusicPlatform.CUSTOM,
                                        MusicPlatform.OTHER,
                                    )
                            }
                    }
                    fun closePlatforms() {
                        platformOpen = false
                        scope.launch {
                            withFrameNanos {}
                            platformFocus.requestFocus()
                        }
                    }
                    val currentPlatform = SourcePlatforms.get(plugin.id)
                    PrismAction(
                        "平台 · ${currentPlatform?.label ?: "自动识别"}",
                        {
                            platformOpen = true
                            infoOpen = false
                        },
                        Modifier.focusRequester(platformFocus)
                            .focusProperties {
                                up = beforeSettings
                                down = bottomFocus
                                left = infoFocus
                                right = resetFocus
                            }
                            .rememberFocus(memory),
                        leading = {
                            if (currentPlatform == null) Glyph("compass", Modifier.size(18.dp), c.accent)
                            else PlatformIcon(currentPlatform, Modifier.size(18.dp))
                        },
                        trailingIcon = "chev",
                    )
                    if (platformOpen)
                        ChoicePopup(
                            platforms,
                            platforms.indexOf(SourcePlatforms.get(plugin.id)),
                            label = { it?.label ?: "自动识别" },
                            onDismiss = { closePlatforms() },
                            onSelect = {
                                SourcePlatforms.set(plugin.id, it)
                                closePlatforms()
                            },
                            icon = {
                                if (it == null) Glyph("compass", Modifier.size(22.dp), c.muted)
                                else PlatformIcon(it, Modifier.size(22.dp))
                            },
                            anchor = platformAnchor,
                        )
                }
                PrismAction(
                    if (resetting) "重置中…" else "重置配置",
                    { if (!resetting) resetOpen = true },
                    Modifier.focusRequester(resetFocus)
                        .focusProperties {
                            left = platformFocus
                            up = beforeSettings
                            down = bottomFocus
                        }
                        .rememberFocus(memory),
                    icon = "trash",
                )
            }
        }
        if (error.isNotBlank())
            Text(
                error,
                color = c.accent,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line.copy(alpha = .35f)))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("配置保存在本机", color = c.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            PrismAction(
                "完成",
                onBack,
                Modifier.focusRequester(bottomFocus)
                    .focusProperties { up = resetFocus }
                    .rememberFocus(memory),
                primary = true,
            )
        }
    }
    if (manualOpen)
        Dialog(
            onDismissRequest = { closeManual() },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            LaunchedEffect(plugin.id) {
                repeat(2) { withFrameNanos {} }
                fieldFocuses.first().requestFocus()
            }
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.widthIn(max = 800.dp)
                        .fillMaxWidth(.86f)
                        .heightIn(max = maxHeight - 40.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                        .background(c.panel)
                        .padding(24.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "手动配置 · ${plugin.displayName}",
                            color = c.foreground,
                            fontSize = 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        PhoneFillButton(
                            "手机代填音源配置",
                            {
                                onPhoneForm(
                                    entries.map { (field, key) ->
                                        key to
                                            field.optString("title").ifBlank {
                                                field.optString("name").ifBlank { key }
                                            }
                                    },
                                    values.toMap(),
                                    { operationScope.launch { repeat(2) { withFrameNanos { } }; runCatching { phoneFocus.requestFocus() } } },
                                ) { submitted ->
                                    submitted.forEach { (key, value) ->
                                        if (value.isNotBlank() && key in values) values[key] = value
                                    }
                                }
                            },
                            Modifier.focusRequester(phoneFocus).focusProperties {
                                down = fieldFocuses.first()
                                right = manualCloseFocus
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        AuraIconButton(
                            "close",
                            "取消手动配置",
                            { closeManual() },
                            Modifier.focusRequester(manualCloseFocus).focusProperties {
                                down = fieldFocuses.first()
                                left = phoneFocus
                            },
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val filledCount = entries.count { values[it.second].orEmpty().isNotBlank() }
                        Text(
                            "${entries.size} 项 · 已填 $filledCount",
                            color = c.muted,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text("确定键开始输入，方向键离开", color = c.muted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .height(1.dp)
                            .background(c.foreground.copy(alpha = .10f))
                    )
                    Spacer(Modifier.height(4.dp))
                    /* 上下各留 12dp：滚到最上时标题不会贴着分隔线，滚到最下时最后一个
                    输入框的下边框不会正好卡在弹窗边缘（电视上看起来像是被切掉了）。 */
                    Column(
                        Modifier.weight(1f, fill = false)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 6.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        entries.forEachIndexed { position, (field, key) ->
                            val title =
                                field.optString("title").ifBlank {
                                    field.optString("name").ifBlank { key }
                                }
                            val secret = credentialKeys.any { key.contains(it, ignoreCase = true) }
                            val filled = values[key].orEmpty().isNotBlank()
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(vertical = 2.dp).revealRow(),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        title,
                                        color = c.foreground,
                                        fontSize = 14.sp,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (filled) "已填写" else "未填写",
                                        color = if (filled) c.accent else c.muted,
                                        fontSize = 11.sp,
                                    )
                                }
                                Input(
                                    values[key].orEmpty(),
                                    { values[key] = it },
                                    field.optString("hint").ifBlank { "未填写" },
                                    Modifier.fillMaxWidth()
                                        .focusRequester(fieldFocuses[position])
                                        .focusProperties {
                                            up = fieldFocuses.getOrNull(position - 1) ?: phoneFocus
                                            down =
                                                fieldFocuses.getOrNull(position + 1)
                                                    ?: manualSaveFocus
                                        },
                                    secret = secret,
                                    manualKeyboard = true,
                                    onSubmit = {
                                        (fieldFocuses.getOrNull(position + 1) ?: manualSaveFocus)
                                            .requestFocus()
                                    },
                                )
                            }
                        }
                    }
                    if (error.isNotBlank())
                        Text(
                            error,
                            color = c.accent,
                            fontSize = 12.sp,
                            maxLines = 2,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .height(1.dp)
                            .background(c.foreground.copy(alpha = .10f))
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        PrismAction(
                            "取消",
                            { closeManual() },
                            Modifier.focusProperties {
                                up = fieldFocuses.last()
                                right = manualSaveFocus
                            },
                        )
                        Spacer(Modifier.width(10.dp))
                        PrismAction(
                            if (saving) "保存中…" else "保存配置",
                            { saveManual() },
                            Modifier.focusRequester(manualSaveFocus).focusProperties {
                                up = fieldFocuses.last()
                            },
                            primary = true,
                        )
                    }
                }
            }
        }
    if (loginOpen.value)
        PluginLoginQr(
            plugin,
            plugins.client,
            onClose = { closeQr() },
            onApplied = { returned ->
                val current =
                    plugins.state.value.plugins.firstOrNull { it.id == plugin.id } ?: error("音源已移除")
                val merged = JSONObject(current.variables.toString())
                returned.keys().forEach { key -> merged.put(key, returned.optString(key)) }
                // Let the login dialog show completion only after the settings have been saved.
                plugins.setVariables(plugin.id, merged)
                returned.keys().forEach { key ->
                    if (key in values) values[key] = returned.optString(key)
                }
                onStatus(
                    if (returned.length() > 0) "扫码成功，已写入 ${returned.length()} 项配置"
                    else "扫码成功，本次未返回配置项"
                )
            },
        )
    if (infoOpen)
        PluginInfoDialog(
            plugin,
            busy,
            onSearch = {
                runCatching { searchSource() }
                    .onSuccess { infoOpen = false }
                    .onFailure { onStatus(it.message ?: "无法切换音源") }
            },
        ) {
            infoOpen = false
            scope.launch {
                withFrameNanos {}
                infoFocus.requestFocus()
            }
        }
    if (resetOpen)
        PrismConfirmDialog(
            "重置音源配置？",
            "将清除该音源填写的参数和本机保存的登录信息，之后可重新登录或填写配置。音源、收藏和歌单会保留。",
            "重置配置",
            onDismiss = { closeReset() },
            onConfirm = {
                resetOpen = false
                resetConfiguration()
            },
            destructive = true, confirmIcon = "trash",
        )
}

/* 弹窗只留四行给提示，而平台原话可能是一整个响应体；这里裁短，完整文本留在 logcat。 */
private fun loginReason(why: String) = if (why.length <= 60) why else why.take(60) + "…"

/* 扫码登录是 Prism 的扩展能力（非 MusicFree 标准）：插件负责取二维码、报状态，界面只管展示与写入。
任何一步失败都只停在这个弹窗里，音源已有的 Cookie 和手填路径不受影响。 */
@Composable
private fun PluginLoginQr(
    plugin: Plugin,
    client: PluginClient,
    onClose: () -> Unit,
    onApplied: suspend (JSONObject) -> Unit,
) {
    val c = LocalPrismColors.current
    /* Prism 扩展：插件可以声明同一个登录入口里的几种出码方式（QQ/微信、桌面端/网页端）。
    这是登录流程中的临时选择，所以画在二维码上方，不占配置项；没有声明的插件这一行不存在。 */
    val methods = plugin.metadata.optJSONArray("loginMethods") ?: JSONArray()
    val choosable = methods.length() > 1
    var methodKey by
        remember(plugin.id) { mutableStateOf(methods.optJSONObject(0)?.optString("key").orEmpty()) }
    var methodSwitch by remember(plugin.id) { mutableStateOf(false) }
    val methodFocus = remember { FocusRequester() }
    var attempt by remember(plugin.id) { mutableStateOf(0) }
    var image by remember(plugin.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var link by remember(plugin.id) { mutableStateOf("") }
    var message by remember(plugin.id) { mutableStateOf("正在获取二维码…") }
    var qrState by remember(plugin.id) { mutableStateOf(PrismQrState.Loading) }
    var confirmedVariables by remember(plugin.id) { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()
    val refreshFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    suspend fun applyLogin(returned: JSONObject) {
        qrState = PrismQrState.Saving
        message = "已在手机确认，正在保存登录信息…"
        try {
            onApplied(returned)
            message = if (returned.length() > 0) "登录信息已保存，可以返回音源配置" else "已在手机确认，本次未返回配置项"
            qrState = PrismQrState.Confirmed
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            message = "登录已确认，但保存失败：${loginReason(e.message ?: "请重试保存")}"
            qrState = PrismQrState.Failed
        }
    }
    /* A dialog opens with no focus owner, so the remote would be dead until Back. */
    Dialog(
        onDismissRequest = { if (qrState != PrismQrState.Saving) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        LaunchedEffect(qrState) {
            if (qrState == PrismQrState.Confirmed) {
                withFrameNanos {}
                refreshFocus.requestFocus()
            }
        }
        LaunchedEffect(attempt, methodKey, plugin.id) {
            /* 换登录方式是自己按的，重新出码后焦点必须还停在那一行上；
            把它推回「刷新二维码」会让人以为界面在抢操作。
            出码也不算用户操作，所以这里不监听出码状态：以前码一出现焦点就跳到「关闭」，
            那一刻整张卡片像是自己动了一下。 */
            val target =
                if (methodSwitch && choosable) {
                    methodSwitch = false
                    methodFocus
                } else refreshFocus
            /* 按钮比弹窗晚一帧合成，第一次要焦点可能落在还没挂载的节点上，重试到挂上为止。 */
            repeat(2) { withFrameNanos {} }
            target.requestFocus()
        }
        LaunchedEffect(attempt, methodKey, plugin.id) {
            image = null
            link = ""
            qrState = PrismQrState.Loading
            confirmedVariables = null
            message = "正在获取二维码…"
            android.util.Log.i(
                "PrismLogin",
                "获取二维码：${plugin.displayName} 方式=${methodKey.ifBlank { "默认" }}",
            )
            val session =
                runCatching {
                        client.call(
                            plugin.source,
                            plugin.variables,
                            "getLoginSession",
                            JSONArray().put(methodKey),
                            plugin.id,
                        ) as? JSONObject
                    }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            val opened = session.getOrNull()
            if (opened == null) {
                /* The plugin's own error usually names the platform response (风控、参数错误、超时),
                which is the only clue the user can act on, so show it instead of a generic phrase. */
                val why = session.exceptionOrNull()?.message ?: "该音源没有返回登录会话"
                android.util.Log.w("PrismLogin", "获取二维码失败：$why")
                message = "获取二维码失败：" + loginReason(why)
                qrState = PrismQrState.Failed
                return@LaunchedEffect
            }
            fun renderQr(payload: JSONObject) {
                link = payload.optString("qrText")
                image =
                    if (link.isNotBlank()) runCatching { qrCodeBitmap(link) }.getOrNull()
                    else
                        payload
                            .optString("qrImageBase64")
                            .takeIf { it.isNotBlank() }
                            ?.let { encoded ->
                                runCatching {
                                        val bytes =
                                            android.util.Base64.decode(
                                                encoded,
                                                android.util.Base64.NO_WRAP,
                                            )
                                        android.graphics.BitmapFactory.decodeByteArray(
                                            bytes,
                                            0,
                                            bytes.size,
                                        )
                                    }
                                    .getOrNull()
                            }
            }
            renderQr(opened)
            message =
                if (image == null) "该音源没有返回可用的二维码"
                else loginReason(opened.optString("hint")).ifBlank { "用手机 App 扫码，并在手机上确认登录" }
            if (image == null) {
                qrState = PrismQrState.Failed
                return@LaunchedEffect
            }
            var sessionKey = opened.optString("session")
            if (sessionKey.isBlank()) {
                qrState = PrismQrState.Failed
                message = "该音源没有返回登录会话标识"
                return@LaunchedEffect
            }
            fun deadline(payload: JSONObject): Long {
                val seconds = payload.optLong("expiresIn", opened.optLong("expiresIn", 0))
                return if (seconds > 0)
                    android.os.SystemClock.elapsedRealtime() + seconds.coerceAtMost(86400) * 1000
                else Long.MAX_VALUE
            }
            var expiresAt = deadline(opened)
            qrState = PrismQrState.Waiting
            android.util.Log.i("PrismLogin", "二维码已出，开始每 2 秒轮询")
            while (isActive) {
                delay(2000)
                if (android.os.SystemClock.elapsedRealtime() >= expiresAt) {
                    qrState = PrismQrState.Expired
                    message = "请重新获取二维码后扫码登录"
                    return@LaunchedEffect
                }
                val poll =
                    runCatching {
                            client.call(
                                plugin.source,
                                plugin.variables,
                                "pollLoginStatus",
                                JSONArray().put(sessionKey),
                                plugin.id,
                            ) as? JSONObject
                        }
                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                val failure = poll.exceptionOrNull()?.message
                val state = poll.getOrNull() ?: JSONObject()
                if (failure != null) android.util.Log.w("PrismLogin", "轮询调用失败：$failure")
                /* 平台对某一类登录接口风控时，插件可以在轮询里直接换一张二维码（新的 session + qrText），
                界面必须接受并重画，否则用户只能关掉弹窗自己去改配置——那等于没有扫码登录。 */
                if (state.optString("state") != "confirmed") {
                    val rotated = state.optString("session")
                    if (rotated.isNotBlank() && rotated != sessionKey) {
                        sessionKey = rotated
                        renderQr(state)
                        if (image == null) {
                            qrState = PrismQrState.Failed
                            message = "新的二维码无法显示，请重新获取"
                            return@LaunchedEffect
                        }
                        expiresAt = deadline(state)
                        android.util.Log.i("PrismLogin", "插件轮换二维码，继续轮询")
                    }
                }
                when (state.optString("state")) {
                    "pending" -> {
                        val next = state.optString("message")
                        if (next.isNotBlank()) message = loginReason(next)
                    }
                    "confirmed" -> {
                        val returned = state.optJSONObject("variables") ?: JSONObject()
                        confirmedVariables = returned
                        android.util.Log.i("PrismLogin", "登录成功，写入配置键：${returned.names()}")
                        applyLogin(returned)
                        return@LaunchedEffect
                    }
                    "expired" -> {
                        qrState = PrismQrState.Expired
                        message =
                            loginReason(state.optString("message")).ifBlank { "请重新获取二维码后扫码登录" }
                        android.util.Log.w("PrismLogin", "二维码失效：$message")
                        return@LaunchedEffect
                    }
                    "failed" -> {
                        qrState = PrismQrState.Failed
                        message =
                            loginReason(state.optString("message")).ifBlank { "本次登录未完成，请重新获取二维码" }
                        return@LaunchedEffect
                    }
                    else -> {
                        qrState = PrismQrState.Failed
                        /* A plugin that threw, or one that answers with a state we don't know, must still
                        tell the user why — the platform's own wording is what makes 风控 actionable. */
                        val why = state.optString("message").takeIf { it.isNotBlank() } ?: failure
                        message =
                            "无法确认登录状态" +
                                (why?.let { "：" + loginReason(it) } ?: "") +
                                "，也可以直接填写 Cookie"
                        android.util.Log.w(
                            "PrismLogin",
                            "登录状态未知：${state.optString("message")} / ${failure ?: "无"}",
                        )
                        return@LaunchedEffect
                    }
                }
            }
        }
        Column(
            Modifier.width(680.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .background(c.panel)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("扫码登录 · ${plugin.displayName}", color = c.foreground, fontSize = 22.sp)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                /* 码到不到都画同一个槽位：等待时是一块浅底方框，明确留着这块地方；码出现时淡入填进
                同一个位置，不是凭空多出一个元素——原来的空框到二维码是硬切，看着像闪了一下。 */
                PrismQrFrame(state = qrState, onConfirmationFinished = onClose) {
                    Box(
                        Modifier.size(206.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .background(
                                c.line.copy(alpha = .30f),
                                androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            )
                            .border(
                                1.dp,
                                c.line.copy(alpha = .75f),
                                androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        val qr = image
                        val reveal by
                            animateFloatAsState(
                                if (qr != null || link.isNotBlank()) 1f else 0f,
                                tween(if (LocalAuraMotion.current) 220 else 0),
                                label = "二维码淡入",
                            )
                        if (qr != null)
                            Box(
                                Modifier.fillMaxSize().background(Color.White),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    qr.asImageBitmap(),
                                    contentDescription = "扫码登录二维码",
                                    modifier = Modifier.size(190.dp).alpha(reveal),
                                )
                            }
                        else if (link.isNotBlank())
                            Text(
                                link,
                                color = c.muted,
                                fontSize = 10.sp,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.alpha(reveal),
                            )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (choosable)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            for (index in 0 until methods.length()) {
                                val item = methods.optJSONObject(index) ?: continue
                                val key = item.optString("key")
                                val selected = key == methodKey
                                PrismAction(
                                    item.optString("name").ifBlank { key },
                                    {
                                        if (!selected) {
                                            methodSwitch = true
                                            methodKey = key
                                            attempt++
                                        }
                                    },
                                    (if (selected) Modifier.focusRequester(methodFocus)
                                        else Modifier)
                                        .focusProperties { down = refreshFocus },
                                    primary = selected,
                                    enabled =
                                        qrState != PrismQrState.Saving &&
                                            qrState != PrismQrState.Confirmed,
                                )
                            }
                        }
                    // Reserve the whole status slot so neither the card nor its focus rows jump.
                    val statusHeight = with(LocalDensity.current) { 88.sp.toDp() }
                    Box(
                        Modifier.fillMaxWidth().height(statusHeight).semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                when (qrState) {
                                    PrismQrState.Loading -> "正在获取二维码"
                                    PrismQrState.Waiting -> "等待扫码登录"
                                    PrismQrState.Saving -> "正在保存登录信息"
                                    PrismQrState.Confirmed -> "登录成功"
                                    PrismQrState.Expired -> "二维码已失效"
                                    PrismQrState.Failed ->
                                        if (confirmedVariables != null) "登录信息未保存" else "扫码登录未完成"
                                },
                                color = c.foreground,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                message,
                                color = c.muted,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val complete = qrState == PrismQrState.Confirmed
                        val savingLogin = qrState == PrismQrState.Saving
                        val retrySave = qrState == PrismQrState.Failed && confirmedVariables != null
                        val retryQr =
                            qrState == PrismQrState.Expired || qrState == PrismQrState.Failed
                        PrismAction(
                            when {
                                complete -> "完成"
                                savingLogin -> "保存中…"
                                retrySave -> "重试保存"
                                retryQr -> "重新获取"
                                else -> "刷新二维码"
                            },
                            {
                                when {
                                    complete -> onClose()
                                    retrySave ->
                                        confirmedVariables?.let { returned ->
                                            scope.launch { applyLogin(returned) }
                                        }
                                    else -> attempt++
                                }
                            },
                            Modifier.focusRequester(refreshFocus).focusProperties {
                                if (choosable && !complete) up = methodFocus
                                if (!complete) right = closeFocus
                            },
                            primary = complete || retryQr,
                            enabled = !savingLogin,
                        )
                        if (!complete)
                            PrismAction(
                                "返回配置",
                                onClose,
                                Modifier.focusRequester(closeFocus).focusProperties {
                                    if (choosable) up = methodFocus
                                    left = refreshFocus
                                },
                                enabled = !savingLogin,
                            )
                    }
                }
            }
        }
    }
}

internal class SourceAddress(val label: String, val url: String)

private fun sourceAddresses(plugin: Plugin): List<SourceAddress> {
    val update = plugin.updateUrl
    return buildList {
        if (plugin.url.isNotBlank() && plugin.url == update)
            add(SourceAddress("插件／更新地址", plugin.url))
        else {
            if (plugin.url.isNotBlank()) add(SourceAddress("插件地址", plugin.url))
            if (update.isNotBlank()) add(SourceAddress("更新地址", update))
        }
        if (plugin.subscriptionManaged) add(SourceAddress("订阅来源", plugin.origin))
    }
}

/* 音源信息、订阅信息是同一类东西：只读，都需要「先看概览、再往下读」。
外壳只做一份——钉住的概览卡、分段正文、上下渐隐、一次两行的滚动、底部动作，
两边只各自交代一张卡和几段内容，排版不会再各长一套。 */
@Composable
internal fun PrismInfoDialog(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    header: @Composable () -> Unit,
    footer: (@Composable RowScope.() -> Unit)? = null,
    focusClose: Boolean = false,
    body: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalPrismColors.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val bodyFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    val scrollable = scroll.maxValue > 0
    /* 按一下只挪两行（正文 20sp 行高），配一小段动画；按住时走系统连发的 KeyDown，
    每次同样两行、跳过动画，速度跟着按键节奏走。不再一按就是一屏。 */
    val stepPx = with(LocalDensity.current) { 40.dp.toPx() }
    val holdStepPx = with(LocalDensity.current) { 24.dp.toPx() }
    PrismContentDialog(title, subtitle, onDismiss, if (focusClose) closeFocus else bodyFocus, width = 560.dp, closeFocus = closeFocus) {
        /* 概览卡钉在顶部：滚到「地址」时还看得见是哪个音源、哪个订阅。
        只有卡片下面的正文滚动，卡片按 panel 不透明铺底，文字从下面的渐隐里穿过去。 */
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 420.dp)
                .then(
                    if (!focusClose || scrollable) {
                        Modifier.focusRequester(bodyFocus)
                            .infoBodyFocus()
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val direction =
                                    when {
                                        event.key == Key.DirectionDown && scroll.canScrollForward -> 1
                                        event.key == Key.DirectionUp && scroll.canScrollBackward -> -1
                                        else -> return@onPreviewKeyEvent false
                                    }
                                if (event.nativeKeyEvent.repeatCount > 0)
                                    scope.launch { scroll.scrollBy(direction * holdStepPx) }
                                else
                                    scope.launch {
                                        scroll.animateScrollBy(
                                            direction * stepPx,
                                            tween(150, easing = LinearOutSlowInEasing),
                                        )
                                    }
                                true
                            }
                            .focusable()
                    } else Modifier
                )
        ) {
            header()
            Box(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth()
                        .verticalScroll(scroll)
                        .padding(bottom = 2.dp)
                ) {
                    body()
                }
                /* 上下渐隐代替描边：内容还有多长是看得见的，而不用把整块画成边框。
                顶部这层同时是钉住卡片的交界，正文滚上来时在它下面淡出。 */
                if (scroll.canScrollBackward)
                    Box(
                        Modifier.align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(28.dp)
                            .background(
                                Brush.verticalGradient(
                                    0f to c.panel,
                                    .3f to c.panel,
                                    .62f to c.panel.copy(alpha = .55f),
                                    1f to Color.Transparent,
                                )
                            )
                    )
                if (scroll.canScrollForward)
                    Box(
                        Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(26.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, c.panel.copy(alpha = .82f), c.panel)
                                )
                            )
                    )
            }
        }
        if (footer != null) Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            footer()
        }
    }
}

/* 概览卡两处共用：左边 34dp 图标位，中间两行说明，右边状态胶囊。
胶囊挂在第二行行尾、不占标题那一行：组名往往很长，两者挤在同一行时
省略号会抵到胶囊上，看着像被它挡住；第二行只有数量，让出位置不心疼。 */
@Composable
internal fun InfoHeaderCard(
    primary: String,
    secondary: String,
    pill: String,
    pillColor: Color,
    icon: @Composable () -> Unit,
) {
    val c = LocalPrismColors.current
    Row(
        Modifier.fillMaxWidth()
            .background(c.panel)
            .clip(RoundedCornerShape(14.dp))
            .background(c.foreground.copy(alpha = .04f))
            .border(1.dp, c.line.copy(alpha = .55f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                primary,
                color = c.foreground,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    secondary,
                    Modifier.weight(1f),
                    color = c.muted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (pill.isNotBlank()) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        pill,
                        color = pillColor,
                        fontSize = 11.sp,
                        maxLines = 1,
                        modifier =
                            Modifier.clip(RoundedCornerShape(6.dp))
                                .background(pillColor.copy(alpha = .12f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PluginInfoDialog(
    plugin: Plugin,
    busy: Boolean,
    onSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalPrismColors.current
    val addresses = remember(plugin) { sourceAddresses(plugin) }
    val sizeKb = remember(plugin.source) { plugin.source.toByteArray(Charsets.UTF_8).size / 1024.0 }
    val version = plugin.metadata.optString("version").removePrefix("v").removePrefix("V")
    val author = plugin.metadata.optString("author")
    val description = plugin.metadata.optString("description")
    val searchHints =
        remember(plugin) {
            plugin.metadata
                .optJSONObject("hints")
                ?.optJSONArray("search")
                ?.let { lines -> (0 until lines.length()).map { lines.optString(it) } }
                ?.filter { it.isNotBlank() }
                .orEmpty()
        }
    val importLines = remember(plugin) { importHints(plugin) }
    PrismInfoDialog(
        plugin.displayName,
        "音源信息",
        onDismiss,
        header = {
            InfoHeaderCard(
                primary =
                    listOfNotNull(
                            version.takeIf { it.isNotBlank() }?.let { "v$it" },
                            author.takeIf { it.isNotBlank() },
                        )
                        .joinToString(" · ")
                        .ifBlank { "未标注版本与作者" },
                secondary = "%.1f KiB".format(sizeKb),
                pill = if (plugin.enabled) "已启用" else "已停用",
                pillColor = if (plugin.enabled) c.accent else c.muted,
            ) {
                PlatformIcon(plugin.platform, Modifier.size(34.dp))
            }
        },
        footer = {
            if (plugin.enabled && plugin.capabilities.has("search"))
                PrismAction("使用此音源搜索", onSearch, enabled = !busy, icon = "search")
        },
    ) {
        if (description.isNotBlank())
            InfoSection("简介") {
                Text(
                    description,
                    color = c.foreground.copy(alpha = .9f),
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                )
            }
        InfoSection("支持能力") {
            val labels = plugin.capabilities.labels
            if (labels.isEmpty()) Text("未识别到支持的能力", color = c.muted, fontSize = 13.sp)
            else
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    labels.forEach { CapabilityChip(it) }
                }
        }
        if (searchHints.isNotEmpty()) InfoSection("搜索说明") { HintList(searchHints) }
        if (importLines.isNotEmpty()) InfoSection("导入说明") { HintList(importLines) }
        if (addresses.isNotEmpty()) InfoSection("地址") { SourceAddressList(addresses, full = true) }
    }
}

/* 段标题与正文之间（8dp）比段与段之间（20dp）更近，否则整屏看下来全是等距段落。 */
@Composable
internal fun InfoSection(label: String, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalPrismColors.current
    Column(
        Modifier.fillMaxWidth().padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            color = c.muted.copy(alpha = .9f),
            fontSize = 11.sp,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.Medium,
        )
        content()
    }
}

@Composable
private fun CapabilityChip(text: String) {
    val c = LocalPrismColors.current
    Text(
        text,
        color = c.foreground.copy(alpha = .86f),
        fontSize = 12.sp,
        modifier =
            Modifier.clip(RoundedCornerShape(7.dp))
                .background(c.accent.copy(alpha = .07f))
                .border(1.dp, c.accent.copy(alpha = .16f), RoundedCornerShape(7.dp))
                .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

/* 每条例句独立成行并带悬挂缩进：长句子换行后仍对齐正文，不会顶到项目符号下面。 */
@Composable
private fun HintList(lines: List<String>) {
    val c = LocalPrismColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        lines.forEach { line ->
            Row(Modifier.fillMaxWidth()) {
                Box(
                    Modifier.padding(top = 8.dp)
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(c.accent.copy(alpha = .7f))
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    line,
                    Modifier.weight(1f),
                    color = c.foreground.copy(alpha = .88f),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}

/* 只读滚动区不该被画成一个输入框。焦点只留左上角一根 2dp 竖条：贴着概览卡，
不跟随正文，也不会像段落里多出来的记号。 */
@Composable
private fun Modifier.infoBodyFocus(): Modifier {
    val c = LocalPrismColors.current
    val motion = LocalAuraMotion.current
    var focused by remember { mutableStateOf(false) }
    val emphasis by
        animateFloatAsState(
            if (focused) 1f else 0f,
            tween(if (motion) 160 else 0),
            label = "info body focus",
        )
    return this.onFocusChanged { focused = it.isFocused }
        .drawWithContent {
            drawContent()
            if (emphasis <= 0f) return@drawWithContent
            val barWidth = 2.dp.toPx()
            val barHeight = 26.dp.toPx()
            drawRect(
                c.accent.copy(alpha = emphasis * .8f),
                topLeft = Offset(2.dp.toPx(), 14.dp.toPx()),
                size = Size(barWidth, barHeight),
            )
        }
}

/* 地址只读：电视上没有粘贴的地方，所以按「可读的代码块」排版——等宽字体、只在路径段之间换行。 */
@Composable
internal fun SourceAddressList(addresses: List<SourceAddress>, full: Boolean = false) {
    val c = LocalPrismColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        addresses.forEach { address ->
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.foreground.copy(alpha = .04f))
                    .border(1.dp, c.line.copy(alpha = .5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    address.label,
                    color = c.muted,
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp,
                    lineHeight = 15.sp,
                )
                Text(
                    address.url.breakableUrl(),
                    color = c.foreground.copy(alpha = .86f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    maxLines = if (full) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/* 等宽字体下长 URL 会在任意字符处折行，看起来像被截断；在分隔符后插一个零宽空格，
换行就只发生在路径段之间。 */
private fun String.breakableUrl(): String =
    buildString(length + 16) {
        for (ch in this@breakableUrl) {
            append(ch)
            if (ch == '/' || ch == '?' || ch == '&' || ch == '=') append('\u200B')
        }
    }

internal fun importHints(plugin: Plugin): List<String> {
    val hints = plugin.metadata.optJSONObject("hints") ?: return emptyList()
    return listOf("importMusicSheet", "importMusicItem")
        .mapNotNull { key -> hints.optJSONArray(key) }
        .flatMap { array ->
            (0 until array.length()).mapNotNull { i ->
                array.optString(i).takeIf { it.isNotBlank() }
            }
        }
        .distinct()
}
