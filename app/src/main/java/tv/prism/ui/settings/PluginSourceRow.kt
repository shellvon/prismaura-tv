package tv.prism.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.prism.MainActivity
import tv.prism.ui.widgets.FocusAnchor
import tv.prism.sources.Plugin
import tv.prism.ui.widgets.requestIfAttached
import tv.prism.ui.widgets.LocalPrismColors
import tv.prism.ui.widgets.focusAnchor
import tv.prism.ui.widgets.PrismActionMenu
import tv.prism.ui.widgets.PrismMenuAction
import tv.prism.ui.widgets.PrismConfirmDialog
import tv.prism.ui.widgets.rowFocus
import tv.prism.ui.widgets.Glyph
import tv.prism.ui.widgets.PlatformIcon
import tv.prism.ui.widgets.tvClickable
import tv.prism.ui.settings.PluginInfoDialog

@Composable
internal fun MainActivity.PluginSourceRow(plugin: Plugin, owned: Boolean, onConfigure: () -> Unit, onRemoved: () -> Unit, configureFocus: FocusAnchor, edge: Modifier = Modifier) {
        val c = LocalPrismColors.current
        var menuOpen by remember(plugin.id) { mutableStateOf(false) }
        var infoOpen by remember(plugin.id) { mutableStateOf(false) }
        var confirmRemoval by remember(plugin.id) { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        fun closeMenu() {
            menuOpen = false; confirmRemoval = false
            scope.launch { withFrameNanos { }; configureFocus.requestIfAttached() }
        }
        Row(edge.fillMaxWidth().focusAnchor(configureFocus).rowFocus()
            .tvClickable({ confirmRemoval = false; menuOpen = true }).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PlatformIcon(plugin.platform, Modifier.size(30.dp))
            Column(Modifier.weight(1f)) {
                Text(plugin.displayName, color = if (plugin.enabled) c.foreground else c.muted,
                    fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = listOfNotNull(plugin.metadata.optString("version").takeIf { it.isNotBlank() }?.let { "v$it" },
                    plugin.metadata.optString("author").takeIf { it.isNotBlank() }).joinToString(" · ")
                if (details.isNotBlank()) Text(details, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!plugin.capabilities.has("search")) Text("不支持搜索", color = c.muted, fontSize = 12.sp)
            Text(if (plugin.enabled) "已启用" else "已停用", color = if (plugin.enabled) c.accent else c.muted, fontSize = 12.sp)
            Glyph("chevR", Modifier.size(16.dp), c.muted)
        }
        if (menuOpen) {
            PrismActionMenu(plugin.displayName, listOfNotNull(
                    plugin.metadata.optString("version").takeIf { it.isNotBlank() }?.let { "v$it" },
                    plugin.metadata.optString("author").takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { "未提供版本与作者" }, onDismiss = { closeMenu() }, actions = buildList {
                        // The label describes the next action; the description states the current status.
                        add(PrismMenuAction(if (plugin.enabled) "停用音源" else "启用音源",
                            if (plugin.enabled) "disabled" else "enabled", description = if (plugin.enabled) "当前：已启用" else "当前：已停用", keepOpen = true) {
                            scope.launch {
                                try { plugins.setEnabled(plugin.id, !plugin.enabled) }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) { status = e.message ?: "操作失败" }
                            }
                        })
                        /* 进去的都是同一页（变量、登录、平台），没有「有没有可改的」这个分岔。 */
                        add(PrismMenuAction("配置音源", "settings", navigates = true) {
                            menuOpen = false; onConfigure()
                        })
                        add(PrismMenuAction("音源信息", "info", navigates = true) { menuOpen = false; infoOpen = true })
                                                /* 这一行常驻、只置灰：菜单开着时条目数量一变，焦点请求会撞上还没挂好的
                           FocusRequester。开关项不关菜单，就必须让列表长度稳定。 */
                        if (plugin.capabilities.has("search")) add(PrismMenuAction("使用此音源搜索", "search", navigates = true, enabled = plugin.enabled && !busy) {
                            menuOpen = false
                            runCatching { searchUsingSources(setOf(plugin.id)) }.onFailure { status = it.message ?: "无法切换音源" }
                        })
                        if (plugin.updateUrl.isNotBlank()) add(PrismMenuAction("更新插件", "refresh", enabled = !busy) {
                            closeMenu()
                            lifecycleScope.launch {
                                try { showPluginImport(plugins.refresh(plugin.id)) }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) { status = "更新失败：${e.message}" }
                            }
                        })
                        if (!owned) add(PrismMenuAction("移除音源", "trash", destructive = true, enabled = !busy, keepOpen = true) { confirmRemoval = true })
                    })
            if (confirmRemoval) PrismConfirmDialog("移除 ${plugin.displayName}？", "本机歌单和收藏会保留", "移除音源",
                onDismiss = { confirmRemoval = false }, onConfirm = {
                    closeMenu()
                    lifecycleScope.launch { try { plugins.remove(plugin.id); status = "已移除音源"; onRemoved() } catch (e: Exception) { status = e.message ?: "移除失败" } }
                }, destructive = true, confirmIcon = "trash")
        }
        if (infoOpen) PluginInfoDialog(plugin, busy, onSearch = {
            runCatching { searchUsingSources(setOf(plugin.id)) }.onSuccess { infoOpen = false }.onFailure { status = it.message ?: "无法切换音源" }
        }) { infoOpen = false; menuOpen = true }
    }
