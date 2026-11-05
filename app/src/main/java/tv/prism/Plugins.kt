package tv.prism

import android.content.Context
import android.net.ConnectivityManager
import android.util.AtomicFile
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The original JSON song object and plugin code are kept intact for later plugin calls. */
data class Plugin(
    val id: String, val url: String, val source: String, val metadata: JSONObject, val variables: JSONObject,
    val enabled: Boolean = true,
    /* Which subscription produced this plugin. Empty means it was imported from a local file
       and can be deleted outright; a subscription-owned plugin is only disabled, because
       deleting it would just come back on the next sync. */
    val origin: String = "",
) {
    val capabilities get() = PluginCapabilities(metadata)
    // origin == url is a standalone online JS; a different origin is its JSON subscription.
    val subscriptionManaged get() = origin.isNotBlank() && origin != url
    fun song(data: JSONObject) = Song(id, data, displayName, primaryKeys(metadata))
    val updateUrl: String get() {
        val declared = metadata.optString("srcUrl").ifBlank { url }
        return runCatching {
            if (url.isBlank()) declared.toHttpUrl().toString()
            else url.toHttpUrl().resolve(declared)?.toString().orEmpty()
        }.getOrDefault("")
    }
    val name get() = metadata.optString("platform", "未知插件")
    val platform get() = SourcePlatforms.get(id) ?: MusicPlatform.identify(name, url)
    /* Plugins name themselves inconsistently ("QQ_music", "qqmusic", "QQ音乐"). The platform is
       the identity users recognise; anything unknown is at least made readable. */
    val displayName get() = if (platform != MusicPlatform.OTHER) platform.label
        else name.replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ").trim().ifBlank { "未命名音源" }
}

/* Mask potentially sensitive configuration fields in the TV and phone interfaces. */
val credentialKeys = setOf("password", "cookie", "token", "secret", "sessdata", "api_key", "apikey", "ck")
data class PluginsState(
    val plugins: List<Plugin> = emptyList(),
    val subscriptions: List<String> = emptyList(),
    val error: String? = null,
    /* address → id of a removed source. Favorites and history are keyed by plugin id, so
       re-importing the same address reuses the old id and those entries attach again. */
    val removed: Map<String, String> = emptyMap(),
)
/** A confirmation binds the exact code and the installed revision, never a re-download. */
data class PluginReplacement(val candidates: List<Plugin>, val incoming: Plugin) {
    fun replacementBlocker(target: Plugin): String? = when {
        target.subscriptionManaged -> "此音源由订阅管理，请通过原订阅更新"
        (compareVersions(incoming.metadata.optString("version"), target.metadata.optString("version")) ?: 0) < 0 -> "已安装版本较新，不能降级覆盖"
        else -> null
    }
}
data class SyncResult(val message: String, val failed: Boolean = false, val replacements: List<PluginReplacement> = emptyList())

class Plugins(private val context: Context, val client: PluginClient) {
    private val mutex = Mutex()
    private val updateMutex = Mutex()
    private val file = AtomicFile(File(context.filesDir, "plugins.json"))
    private val mediaCache = MediaSourceCache(File(context.cacheDir, "media-sources"))
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val mutable = MutableStateFlow(read())
    val state: StateFlow<PluginsState> = mutable

    private fun read(): PluginsState = try {
        if (!file.baseFile.exists()) PluginsState() else parse(JSONObject(file.openRead().bufferedReader().use { it.readText() }))
    } catch (e: Exception) { PluginsState(error = "插件数据读取失败，已停止写入：${e.message}") }

    private fun parse(root: JSONObject): PluginsState {
        val arr = root.optJSONArray("plugins") ?: JSONArray()
        val plugins = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            Plugin(p.getString("id"), p.optString("url"), p.getString("source"), p.getJSONObject("metadata"), p.optJSONObject("variables") ?: JSONObject(),
                p.optBoolean("enabled", true), p.optString("origin"))
        }
        val subscriptions = root.optJSONArray("subscriptions") ?: JSONArray()
        val removedRoot = root.optJSONObject("removed")
        val removed = removedRoot?.let { obj ->
            obj.keys().asSequence().mapNotNull { url -> obj.optString(url).takeIf { it.isNotBlank() }?.let { url to it } }.toMap()
        } ?: emptyMap()
        return PluginsState(plugins, (0 until subscriptions.length()).map { subscriptions.getString(it) }, removed = removed)
    }

    private fun write(state: PluginsState) {
        check(mutable.value.error == null) { mutable.value.error ?: "数据读取失败" }
        val plugins = JSONArray()
        state.plugins.forEach { p -> plugins.put(JSONObject().put("id", p.id).put("url", p.url).put("source", p.source).put("metadata", p.metadata).put("variables", p.variables)
            .put("enabled", p.enabled).put("origin", p.origin)) }
        val removed = JSONObject().apply { state.removed.forEach { (url, id) -> put(url, id) } }
        val root = JSONObject().put("plugins", plugins).put("subscriptions", JSONArray(state.subscriptions)).put("removed", removed)
        val stream = file.startWrite()
        try { stream.write(root.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        mutable.value = state
    }

    /* 备份保留插件源码、配置变量和订阅地址；恢复时整份替换，不做合并。 */
    fun backupRoot(): JSONObject {
        val state = mutable.value
        val plugins = JSONArray()
        state.plugins.forEach { p -> plugins.put(JSONObject().put("id", p.id).put("url", p.url).put("source", p.source)
            .put("metadata", p.metadata).put("variables", p.variables).put("enabled", p.enabled).put("origin", p.origin)) }
        val removed = JSONObject().apply { state.removed.forEach { (url, id) -> put(url, id) } }
        return JSONObject().put("plugins", plugins).put("subscriptions", JSONArray(state.subscriptions)).put("removed", removed)
    }

    suspend fun restoreBackup(root: JSONObject) = mutex.withLock {
        val next = parse(root)
        withContext(Dispatchers.IO) { write(next) }
    }

    suspend fun importSource(source: String, url: String = "", origin: String = ""): SyncResult =
        updateMutex.withLock { storeSource(source, url, origin) }

    private suspend fun storeSource(rawSource: String, url: String, origin: String, replacingId: String? = null): SyncResult {
        val source = rawSource.removePrefix("\uFEFF")
        require(source.isNotBlank()) { "插件文件为空" }
        require(source.toByteArray(Charsets.UTF_8).size <= MAX_SCRIPT) { "插件文件过大" }
        val installed = state.value.plugins
        val targets = if (replacingId != null) installed.filter { it.id == replacingId }
            else installed.filter { url.isNotBlank() && (it.url == url || it.updateUrl == url) }
        require(targets.size <= 1) { "多个音源使用同一更新地址，无法确定更新对象" }
        val previous = targets.singleOrNull()
        check(replacingId == null || previous != null) { "音源已移除，已取消更新" }
        // A selected update belongs to its UUID; another independent instance must not swallow it.
        val duplicate = if (previous != null) previous.takeIf { it.source.removePrefix("\uFEFF") == source }
            else installed.firstOrNull { it.source.removePrefix("\uFEFF") == source }
        duplicate?.let { return SyncResult("插件已安装：${it.displayName}") }
        // Metadata execution receives neither installed variables nor an installed Cookie identity.
        val metadata = client.call(source, JSONObject(), "describe") as? JSONObject ?: error("插件格式无效")
        if (url.isNotBlank() && metadata.optString("srcUrl").isNotBlank()) {
            url.toHttpUrl().resolve(metadata.optString("srcUrl"))?.let { metadata.put("srcUrl", it.toString()) }
        }
        val incoming = Plugin(UUID.randomUUID().toString(), url, source, metadata, JSONObject(), origin = origin)
        return mutex.withLock {
            val state = mutable.value
            check(state.error == null) { state.error ?: "插件数据读取失败" }
            val old = previous?.let { expected ->
                state.plugins.firstOrNull { it.id == expected.id }.also { checkRevision(expected, it) }
            }
            val existing = if (old != null) old.takeIf { it.source.removePrefix("\uFEFF") == source }
                else state.plugins.firstOrNull { it.source.removePrefix("\uFEFF") == source }
            existing?.let { return@withLock SyncResult("插件已安装：${it.displayName}") }
            val named = if (old == null) state.plugins.filter { it.name == incoming.name } else emptyList()
            check(old == null || old.name == incoming.name || state.plugins.none { it.id != old.id && it.name == incoming.name }) {
                "插件名称与另一个已安装音源冲突，已保留原插件"
            }
            if (old == null && named.isNotEmpty()) {
                SyncResult("发现同名插件「${incoming.name}」，请选择替换或独立安装", replacements = listOf(PluginReplacement(named, incoming)))
            } else {
                checkVersion(incoming, old)
                val identified = if (old == null) incoming.copy(id = state.removed[url] ?: incoming.id) else incoming
                savePlugin(state, identified, old)
            }
        }
    }

    suspend fun confirmReplacement(replacement: PluginReplacement, targetId: String = replacement.candidates.single().id): SyncResult = updateMutex.withLock {
        mutex.withLock {
            val state = mutable.value
            val expected = replacement.candidates.firstOrNull { it.id == targetId } ?: error("请选择已有音源作为替换对象")
            val current = state.plugins.firstOrNull { it.id == targetId } ?: error("音源已移除，请重新导入")
            checkRevision(expected, current)
            replacement.replacementBlocker(current)?.let { error(it) }
            savePlugin(state, replacement.incoming, current, adoptRemoteChannel = true)
        }
    }

    suspend fun installIndependent(replacement: PluginReplacement): SyncResult = updateMutex.withLock {
        mutex.withLock install@{
            val state = mutable.value
            state.plugins.firstOrNull { it.source.removePrefix("\uFEFF") == replacement.incoming.source }?.let {
                return@install SyncResult("插件已安装：${it.displayName}")
            }
            // A separate install must never revive a removed UUID and its old Cookie namespace.
            savePlugin(state, replacement.incoming.copy(id = UUID.randomUUID().toString()), null)
        }
    }

    private fun checkRevision(expected: Plugin, current: Plugin?) {
        check(current != null && current.source == expected.source && current.url == expected.url && current.origin == expected.origin) {
            "音源已移除或更新，请重新导入"
        }
    }

    private fun checkVersion(incoming: Plugin, previous: Plugin?) {
        if (previous != null) require((compareVersions(incoming.metadata.optString("version"), previous.metadata.optString("version")) ?: 0) >= 0) {
            "插件版本低于已安装版本，已保留 ${previous.metadata.optString("version")}"
        }
    }

    /** Caller holds mutex. File replacement retains the installed online channel, if any. */
    private suspend fun savePlugin(state: PluginsState, incoming: Plugin, previous: Plugin?, adoptRemoteChannel: Boolean = false): SyncResult {
        val keepChannel = previous != null && (!adoptRemoteChannel || incoming.url.isBlank())
        val plugin = incoming.copy(
            id = previous?.id ?: incoming.id,
            url = if (keepChannel) previous!!.url.ifBlank { incoming.url } else incoming.url,
            origin = if (keepChannel) previous!!.origin.ifBlank { incoming.origin } else incoming.origin,
            variables = previous?.variables ?: JSONObject(), enabled = previous?.enabled ?: true,
        )
        val updated = if (previous == null) state.plugins + plugin else state.plugins.map { if (it.id == previous.id) plugin else it }
        // A different remote import switches channel only after confirmation; an obsolete single-URL
        // subscription must not later try to reinstall the old channel. Shared manifests stay intact.
        val obsolete = previous?.origin?.takeIf { it == previous.url && it != plugin.origin && updated.none { p -> p.origin == it } }
        val subscriptions = (state.subscriptions.filterNot { it == obsolete } + listOf(plugin.origin).filter { it.isNotBlank() }).distinct()
        withContext(Dispatchers.IO) { write(state.copy(plugins = updated, subscriptions = subscriptions, removed = state.removed - plugin.url)) }
        return SyncResult("已${if (previous == null) "导入" else "更新"}插件：${plugin.displayName}")
    }

    suspend fun importUrl(url: String): SyncResult = updateMutex.withLock {
        val normalized = normalizeUrl(url)
        val raw = download(normalized, MAX_MANIFEST)
        if (raw.trimStart().startsWith("{")) {
            val manifest = JSONObject(raw)
            val list = manifest.optJSONArray("plugins") ?: error("订阅文件缺少 plugins 列表")
            require(list.length() in 1..200) { "订阅应包含 1 至 200 个插件" }
            val base = normalized.toHttpUrl()
            val urls = (0 until list.length()).map { i ->
                val link = list.getJSONObject(i).getString("url")
                base.resolve(link)?.toString() ?: error("无效的插件地址")
            }.distinct()
            // Keep the explicitly added subscription even if every child is temporarily unavailable.
            mutex.withLock {
                val state = mutable.value
                withContext(Dispatchers.IO) { write(state.copy(subscriptions = (state.subscriptions + normalized).distinct())) }
            }
            return@withLock syncUrls(urls, normalized)
        }
        storeSource(raw, normalized, normalized)
    }

    suspend fun syncAll(): SyncResult {
        val subscriptions = state.value.subscriptions
        if (subscriptions.isEmpty()) return SyncResult("没有订阅")
        val results = mutableListOf<String>()
        val replacements = mutableListOf<PluginReplacement>()
        var failed = false
        var overflow = false
        var pendingBytes = 0
        for (url in subscriptions) {
            val result = try {
                val direct = state.value.plugins.firstOrNull { it.origin == url && it.url == url }
                if (direct != null && direct.updateUrl != url) refresh(direct.id) else importUrl(url)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { SyncResult("${url.take(70)}：${e.message}", true) }
            results += result.message
            failed = failed || result.failed
            for (replacement in result.replacements) {
                val size = replacement.incoming.source.toByteArray(Charsets.UTF_8).size
                if (pendingBytes + size > MAX_PENDING_SCRIPTS) { failed = true; overflow = true; continue }
                pendingBytes += size
                replacements += replacement
            }
        }
        return SyncResult(results.joinToString("；") + if (overflow) "；待确认文件过多，请分批导入" else "", failed, replacements)
    }

    /* Follow srcUrl while retaining the original install identity and configuration. */
    suspend fun refresh(id: String): SyncResult = updateMutex.withLock {
        val plugin = state.value.plugins.firstOrNull { it.id == id } ?: error("音源不存在")
        require(plugin.updateUrl.isNotBlank()) { "该音源没有更新地址" }
        val source = download(normalizeUrl(plugin.updateUrl), MAX_SCRIPT)
        if (source == plugin.source.removePrefix("\uFEFF")) SyncResult("已是最新版本：${plugin.displayName}")
        else storeSource(source, plugin.url, plugin.origin, plugin.id)
    }

    suspend fun mediaSource(plugin: Plugin, song: Song, quality: String): JSONObject? {
        val policy = plugin.metadata.optString("cacheControl", "no-cache")
        val online = (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.activeNetwork != null
        if (policy == "cache" || (policy == "no-cache" && !online)) mediaCache.read(plugin, song, quality)?.let { return it }
        val result = client.call(plugin.source, plugin.variables, "getMediaSource", JSONArray().put(song.data).put(quality), plugin.id) as? JSONObject
        if (policy != "no-store" && result != null) {
            try { mediaCache.write(plugin, song, quality, result) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* A cache failure must not prevent a resolved stream playing. */ }
        }
        return result
    }

    /** The MV extension has one marker, one method and one object result. */
    suspend fun resolveVideo(song: Song): VideoSource? {
        val plugin = state.value.plugins.firstOrNull { it.id == song.pluginId && it.enabled } ?: return null
        if (!song.hasMv || !plugin.capabilities.has("mvSource")) return null
        return videoSource(client.call(plugin.source, plugin.variables, "getMvSource", JSONArray().put(song.data), plugin.id))
    }

    private suspend fun syncUrls(urls: List<String>, origin: String): SyncResult {
        var ok = 0
        val errors = mutableListOf<String>()
        val replacements = mutableListOf<PluginReplacement>()
        var pendingBytes = 0
        for (url in urls) {
            try {
                val update = state.value.plugins.firstOrNull { it.url == url }?.updateUrl?.ifBlank { url } ?: url
                val result = storeSource(download(normalizeUrl(update), MAX_SCRIPT), url, origin)
                if (result.replacements.isEmpty()) ok++
                else for (replacement in result.replacements) {
                    val size = replacement.incoming.source.toByteArray(Charsets.UTF_8).size
                    if (pendingBytes + size > MAX_PENDING_SCRIPTS) errors += "待确认文件过多，请分批导入"
                    else { pendingBytes += size; replacements += replacement }
                }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors.add("${url.substringAfterLast('/').take(40)}：${e.message}") }
        }
        val message = "更新成功 $ok/${urls.size}" +
            (if (replacements.isEmpty()) "" else "；${replacements.size} 个同名插件待确认") +
            (if (errors.isEmpty()) "" else "；失败：${errors.joinToString("、")}")
        return SyncResult(message, errors.isNotEmpty(), replacements)
    }

    suspend fun setVariables(id: String, variables: JSONObject) = mutex.withLock {
        val state = mutable.value
        withContext(Dispatchers.IO) { write(state.copy(plugins = state.plugins.map { if (it.id == id) it.copy(variables = variables) else it })) }
    }
    suspend fun setEnabled(id: String, enabled: Boolean) = mutex.withLock {
        val state = mutable.value
        withContext(Dispatchers.IO) { write(state.copy(plugins = state.plugins.map { if (it.id == id) it.copy(enabled = enabled) else it })) }
    }
    suspend fun remove(id: String) = mutex.withLock {
        val state = mutable.value
        val gone = state.plugins.firstOrNull { it.id == id }
        /* Keep the address → id mapping. The library entries stay, and importing the same
           address again gives the source its old id back. */
        val removed = if (gone != null && gone.url.isNotBlank()) state.removed + (gone.url to gone.id) else state.removed
        withContext(Dispatchers.IO) { write(state.copy(plugins = state.plugins.filterNot { it.id == id }, removed = removed)) }
    }
    /* Removing a subscription removes what it installed. Leaving those plugins behind would
       present a source that can never be updated again but looks identical to a live one. */
    suspend fun removeSubscription(url: String) = updateMutex.withLock {
        mutex.withLock {
            val state = mutable.value
            val dropped = state.plugins.filter { it.origin == url || (it.origin.isBlank() && it.url == url) }
            val removed = state.removed + dropped.filter { it.url.isNotBlank() }.associate { it.url to it.id }
            withContext(Dispatchers.IO) {
                write(state.copy(
                    subscriptions = state.subscriptions.filterNot { it == url },
                    plugins = state.plugins.filterNot { it.origin == url || (it.origin.isBlank() && it.url == url) },
                    removed = removed,
                ))
            }
        }
    }

    private suspend fun download(url: String, limit: Int): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("User-Agent", "PrismTV/1.0").build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body ?: error("空响应")
            require(body.contentLength() <= limit) { "文件过大" }
            val bytes = body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= limit) { "文件过大" }; output.write(buffer, 0, n) }
                output.toByteArray()
            }
            bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        }
    }
    private fun normalizeUrl(url: String): String {
        val parsed = url.trim().toHttpUrl()
        require(parsed.scheme == "http" || parsed.scheme == "https") { "只支持 HTTP(S)" }
        return parsed.toString()
    }
    companion object {
        const val MAX_SCRIPT = 384 * 1024
        const val MAX_MANIFEST = 384 * 1024
        const val MAX_PENDING_SCRIPTS = 4 * 1024 * 1024
    }
}

object AppPlugins {
    @Volatile private var instance: Plugins? = null
    fun get(context: Context): Plugins = instance ?: synchronized(this) {
        instance ?: Plugins(context.applicationContext, PluginClient(context.applicationContext)).also { instance = it }
    }
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SubscriptionWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("plugin-subscriptions", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
class SubscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (AppPlugins.get(applicationContext).syncAll().failed) Result.retry() else Result.success()
    } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.retry() }
}
