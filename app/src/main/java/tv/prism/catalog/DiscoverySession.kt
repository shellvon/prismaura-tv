package tv.prism.catalog

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import androidx.compose.runtime.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import tv.prism.library.Song
import tv.prism.sources.Plugin
import tv.prism.sources.requestRevision
import tv.prism.DISCOVERY_FRESH_MS
import tv.prism.sources.PluginClient
import kotlinx.coroutines.CoroutineScope

/** Activity-scoped recommendation and chart requests, cache and detail state. */
internal class DiscoverySession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: () -> PluginClient,
) {
    private var tagsLoadedAt: Long? = null
    private var recommendationsLoadedAt: Long? = null

    internal var recommendations by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    internal var recommendationStatus by mutableStateOf("")
        private set
    internal var recommendationTags by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    internal var recommendationTag by mutableStateOf(JSONObject().put("id", "").put("title", "推荐"))
    private var recommendationPage by mutableIntStateOf(1)
    internal var recommendationMore by mutableStateOf(false)
        private set
    internal var recommendationLoading by mutableStateOf(false)
        private set
    /* Startup subscription sync publishes new Plugin instances over and over. Effects keyed on
       the list identity restarted and blanked the rail on every write, which is the flicker. */
    private var recommendationTagsOwner = ""
    internal var recommendationContext = ""
        private set
    private var recommendationGeneration = 0

    internal var charts by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    internal var chartStatus by mutableStateOf("")
        private set
    internal var chartPreviews by mutableStateOf<Map<String, List<Song>>>(emptyMap())
    internal var chartSelection by mutableStateOf<JSONObject?>(null)
        private set
    internal var chartSongs by mutableStateOf<List<Song>>(emptyList())
        private set
    internal var chartDetailStatus by mutableStateOf("")
        private set
    private var chartJob: Job? = null
    private var chartEntryRefresh: Job? = null
    private var chartGeneration = 0
    internal var chartLoading by mutableStateOf(false)
        private set

    suspend fun prepareTags(plugin: Plugin?) {
        if (plugin == null || recommendationTagsOwner != plugin.requestRevision()) {
            recommendationTags = emptyList()
            recommendationTagsOwner = plugin?.requestRevision().orEmpty()
            tagsLoadedAt = null
            recommendationTag = defaultTag()
        }
        if (plugin != null && !fresh(tagsLoadedAt)) loadRecommendationTags(plugin)
    }

    suspend fun refreshRecommendations(plugin: Plugin?) {
        if (plugin == null) {
            recommendations = emptyList()
            recommendationContext = ""
            recommendationStatus = "添加支持推荐歌单的音源后显示"
            recommendationMore = false
            return
        }
        val nextContext = "${plugin.requestRevision()}|${recommendationTag.optString("id")}"
        val reset = nextContext != recommendationContext
        if (!reset && fresh(recommendationsLoadedAt)) return
        if (reset) recommendationsLoadedAt = null
        recommendationContext = nextContext
        loadRecommendations(plugin, reset = reset)
    }

    private fun fresh(loadedAt: Long?): Boolean = loadedAt != null &&
        android.os.SystemClock.elapsedRealtime() - loadedAt < DISCOVERY_FRESH_MS

    private fun defaultTag() = JSONObject().put("id", "").put("title", "推荐")

    suspend fun changeChartSource(plugin: Plugin?) {
        ++chartGeneration
        chartEntryRefresh?.cancel()
        closeChart()
        chartSongs = emptyList()
        invalidateChartCache()
        chartLoading = false
        if (plugin == null) chartStatus = "暂无提供榜单的音源，可在设置中添加"
        else loadCharts(plugin, force = false)
    }

    fun invalidateChartCache() {
        chartFreshUntil = 0L
        charts = emptyList()
        chartPreviews = emptyMap()
    }

    fun refreshChartsOnEntry(plugin: Plugin?) {
        if (plugin != null && !chartLoading && chartEntryRefresh?.isActive != true) {
            chartEntryRefresh = scope.launch { loadCharts(plugin, force = false) }
        }
    }

    fun closeChart() {
        chartJob?.cancel()
        chartJob = null
        chartSelection = null
    }

    suspend fun loadChartPreviews(plugin: Plugin) {
        val generation = chartGeneration
        coroutineScope {
            charts.take(3).drop(1).filter { chartPreviews[it.optString("id")].isNullOrEmpty() }
                .map { chart -> async {
                    val songs = try { withTimeout(8_000) { fetchChart(plugin, chart) } }
                    catch (_: kotlinx.coroutines.TimeoutCancellationException) { emptyList() }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { emptyList() }
                    if (generation == chartGeneration && songs.isNotEmpty()) {
                        chartPreviews = chartPreviews + (chart.optString("id") to songs)
                    }
                } }.awaitAll()
        }
    }

    internal suspend fun loadRecommendations(plugin: Plugin, tag: JSONObject = recommendationTag, append: Boolean = false, reset: Boolean = false) {
        if (!plugin.capabilities.recommendations) return
        if (append && recommendationLoading) return
        recommendationLoading = true
        val generation = ++recommendationGeneration
        if (reset && !append) {
            recommendations = emptyList()
            recommendationPage = 1
            recommendationMore = false
        }
        if (recommendations.isEmpty()) recommendationStatus = "正在加载推荐歌单…"
        try {
            val nextPage = if (append) recommendationPage + 1 else 1
            val result = client().call(plugin.source, plugin.variables, "getRecommendSheetsByTag", JSONArray().put(tag).put(nextPage), plugin.id) as? JSONObject
                ?: error("插件没有返回推荐歌单")
            if (generation != recommendationGeneration) return
            val data = result.optJSONArray("data") ?: JSONArray()
            val items = (0 until minOf(data.length(), 100)).mapNotNull { data.optJSONObject(it) }
            recommendations = if (append) recommendations + items else items
            recommendationPage = nextPage
            if (!append) recommendationsLoadedAt = android.os.SystemClock.elapsedRealtime()
            recommendationMore = items.isNotEmpty() && !result.optBoolean("isEnd", true)
            recommendationStatus = if (recommendations.isEmpty()) "该音源暂无推荐歌单" else ""
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            if (generation != recommendationGeneration) return
            recommendationStatus = if (e.message?.contains("不支持 getRecommendSheetsByTag") == true)
                "该音源暂不支持推荐歌单" else "推荐歌单加载失败：${e.message ?: "未知错误"}"
        } finally { if (generation == recommendationGeneration) recommendationLoading = false }
    }

    internal suspend fun loadRecommendationTags(plugin: Plugin) {
        if (!plugin.capabilities.has("recommendTags")) { recommendationTags = emptyList(); return }
        try {
            val result = client().call(plugin.source, plugin.variables, "getRecommendSheetTags", JSONArray(), plugin.id) as? JSONObject ?: return
            val collected = mutableListOf<JSONObject>()
            fun add(array: JSONArray?) { if (array != null) for (i in 0 until minOf(array.length(), 100)) array.optJSONObject(i)?.let { collected.add(it) } }
            add(result.optJSONArray("pinned"))
            val groups = result.optJSONArray("data") ?: JSONArray()
            for (i in 0 until minOf(groups.length(), 30)) add(groups.optJSONObject(i)?.optJSONArray("data"))
            if (recommendationTagsOwner != plugin.requestRevision()) return
            tagsLoadedAt = android.os.SystemClock.elapsedRealtime()
            recommendationTags = collected.filter { it.optString("id").isNotBlank() && it.optString("title").isNotBlank() }.distinctBy { it.optString("id") }.take(100)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { /* Tags are optional in MusicFree. */ }
    }


    private var chartFreshUntil = 0L
    private var chartOwner = ""

    internal suspend fun loadCharts(plugin: Plugin, force: Boolean = true) {
        val owner = "${plugin.id}:${plugin.source.hashCode()}:${plugin.variables.toString().hashCode()}"
        if (!force && chartOwner == owner &&
            (chartLoading || System.currentTimeMillis() < chartFreshUntil)
        ) return
        chartOwner = owner
        chartFreshUntil = 0L
        val generation = ++chartGeneration
        val cache = context.getSharedPreferences("discovery", Context.MODE_PRIVATE)
        val saved = if (charts.isEmpty()) runCatching {
            JSONObject(cache.getString("charts", "").orEmpty()).takeIf { it.optString("owner") == owner }
        }.getOrNull() else null
        saved?.let { root ->
            val list = root.optJSONArray("items") ?: JSONArray()
            charts = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            val previews = root.optJSONObject("previews") ?: JSONObject()
            chartPreviews = previews.keys().asSequence().associateWith { id ->
                val songs = previews.optJSONArray(id) ?: JSONArray()
                (0 until songs.length()).mapNotNull { songs.optJSONObject(it)?.let(plugin::song) }
            }
            val age = System.currentTimeMillis() - root.optLong("at")
            if (!force && age in 0 until DISCOVERY_FRESH_MS && (charts.isNotEmpty() && chartPreviews[charts.first().optString("id")].orEmpty().isNotEmpty())) {
                chartFreshUntil = root.optLong("at") + DISCOVERY_FRESH_MS
                return
            }
        }
        if (!plugin.capabilities.charts) {
            chartStatus = "该音源暂不支持榜单，可在设置中选择其它音源"
            return
        }
        chartStatus = "正在加载榜单…"
        chartLoading = true
        try {
            val groups = client().call(plugin.source, plugin.variables, "getTopLists", JSONArray(), plugin.id) as? JSONArray ?: error("音源未返回榜单")
            if (generation != chartGeneration) return
            charts = (0 until groups.length()).flatMap { i ->
                val data = groups.optJSONObject(i)?.optJSONArray("data") ?: JSONArray()
                (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            }
            chartStatus = if (charts.isEmpty()) "该音源暂无榜单" else ""
            val ids = charts.map { it.optString("id") }.toSet()
            chartPreviews = chartPreviews.filterKeys { it in ids }
            // The home card needs one preview; fetch the rest when discovery is opened.
            for (chart in charts.take(1)) {
                val songs = try { kotlinx.coroutines.withTimeout(8_000) { fetchChart(plugin, chart) } }
                catch (e: kotlinx.coroutines.TimeoutCancellationException) { emptyList() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { emptyList() }
                if (generation != chartGeneration) return
                if (songs.isNotEmpty()) chartPreviews = chartPreviews + (chart.optString("id") to songs)
            }
            val previews = JSONObject().apply {
                chartPreviews.entries.take(3).forEach { (id, songs) -> put(id, JSONArray(songs.take(10).map { it.data })) }
            }
            chartFreshUntil = System.currentTimeMillis() + DISCOVERY_FRESH_MS
            val snapshot = JSONObject().put("owner", owner).put("at", System.currentTimeMillis())
                .put("items", JSONArray(charts.take(60))).put("previews", previews).toString()
            if (snapshot.length < 512_000) cache.edit().putString("charts", snapshot).apply()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { if (generation == chartGeneration) chartStatus = "榜单加载失败，请稍后重试：${e.message ?: "未知错误"}" }
        finally { if (generation == chartGeneration) chartLoading = false }
    }

    internal suspend fun fetchChart(plugin: Plugin, chart: JSONObject): List<Song> {
        val result = client().call(plugin.source, plugin.variables, "getTopListDetail", JSONArray().put(chart), plugin.id) as? JSONObject ?: error("音源未返回榜单详情")
        val items = result.optJSONArray("musicList") ?: JSONArray()
        return (0 until minOf(items.length(), 5000)).mapNotNull { items.optJSONObject(it)?.let(plugin::song) }
    }

    internal fun openChart(plugin: Plugin, chart: JSONObject) {
        chartJob?.cancel()
        chartSelection = chart
        chartSongs = chartPreviews[chart.optString("id")] ?: emptyList()
        chartDetailStatus = if (chartSongs.isEmpty()) "正在加载榜单…" else ""
        chartJob = scope.launch {
            try {
                chartSongs = fetchChart(plugin, chart)
                chartDetailStatus = if (chartSongs.isEmpty()) "榜单没有歌曲" else ""
                if (chartSongs.isNotEmpty()) chartPreviews = chartPreviews + (chart.optString("id") to chartSongs)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { chartDetailStatus = "加载榜单失败：${e.message}" }
        }
    }
}
