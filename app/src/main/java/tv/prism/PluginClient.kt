package tv.prism

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** How many JS sandboxes may run at once. 0 in prefs means "decide from the device". */
object PluginWorkers {
    const val MAX = 4
    private const val PREFS = "plugins"
    private const val KEY = "workers"

    /* Sandbox count trades memory for search latency: every worker is another WebView frame
       and another in-flight call. Small boxes get one or two; a 4-core TV with a normal heap
       is the point where three no longer costs anything noticeable. */
    fun automatic(context: Context): Int {
        val memoryClass = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores >= 6 && memoryClass >= 256 -> 4
            cores >= 4 && memoryClass >= 192 -> 3
            cores >= 2 && memoryClass >= 128 -> 2
            else -> 1
        }
    }

    /** Raw preference: 0 means automatic. */
    fun saved(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0)

    fun resolve(context: Context): Int {
        val saved = saved(context)
        return if (saved in 1..MAX) saved else automatic(context)
    }

    /** 0 stores "automatic"; the service is told the resolved number with every call. */
    fun save(context: Context, count: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY, if (count in 1..MAX) count else 0).apply()
    }
}

/** Any number of calls may be in flight; the service runs them on its sandbox workers. */
class PluginClient(private val context: Context) {
    private var remote: Messenger? = null
    private var binding: CompletableDeferred<Messenger>? = null
    /* Keyed by call id: several plugins can now be queried at once, so there is no single
       "current" reply slot to overwrite. */
    private val pending = mutableMapOf<String, CompletableDeferred<String>>()
    private var pluginPid = 0
    private val handler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            0 -> { pluginPid = msg.data.getInt("pid"); remote?.let { binding?.complete(it) } }
            2 -> msg.data.getString("id")?.let { id -> pending.remove(id)?.complete(msg.data.getString("result") ?: "") }
        }
        true
    }
    private val replies = Messenger(handler)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) { binding?.completeExceptionally(IllegalStateException("插件服务未连接")); return }
            remote = Messenger(binder)
            runCatching { remote?.send(Message.obtain(null, 0).apply { replyTo = replies }) }
                .onFailure { binding?.completeExceptionally(it) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            pluginPid = 0
            binding = null
            pending.values.forEach { it.completeExceptionally(IllegalStateException("插件进程已退出")) }
            pending.clear()
        }
        override fun onBindingDied(name: ComponentName?) { onServiceDisconnected(name) }
    }

    /* Waiting for a slot here keeps the timeout tied to actual execution instead of to time
       spent queued behind other plugins. Replacing the semaphore when the setting changes is
       safe: calls already holding the old one finish and release it on their own. */
    private var slots = Semaphore(3)
    private var slotsFor = 3

    private fun gate(limit: Int): Semaphore = synchronized(this) {
        if (limit != slotsFor) { slots = Semaphore(limit); slotsFor = limit }
        slots
    }

    suspend fun call(
        source: String,
        variables: JSONObject,
        method: String,
        args: JSONArray = JSONArray(),
        pluginId: String = "",
        /* A playlist import pages through track details, so one call can legitimately run far
           longer than a search; bulk methods get their own budget. */
        timeoutMs: Long = if (method == "importMusicSheet") 180_000 else 35_000,
    ): Any? {
        val limit = PluginWorkers.resolve(context)
        return gate(limit).withPermit {
            callInSlot(source, variables, method, args, pluginId, timeoutMs, limit)
        }
    }

    /** Acquire one slot before starting the budget, then use it for the whole page batch. */
    internal suspend fun callPage(
        plugin: Plugin,
        method: String,
        firstPage: Int,
        itemsKey: String,
        args: (Int) -> JSONArray,
    ): PluginPage {
        val limit = PluginWorkers.resolve(context)
        return gate(limit).withPermit {
            loadPluginPage(firstPage, itemsKey) { page ->
                callInSlot(plugin.source, plugin.variables, method, args(page), plugin.id, 35_000, limit) as? JSONObject
                    ?: error("音源未返回有效分页结果")
            }
        }
    }

    private suspend fun callInSlot(
        source: String,
        variables: JSONObject,
        method: String,
        args: JSONArray,
        pluginId: String,
        timeoutMs: Long,
        limit: Int,
    ): Any? = withContext(Dispatchers.Main) {
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<String>()
        var sourceHash: String? = null
        try {
            // Finish acquiring the file reference even if this call is cancelled mid-write.
            sourceHash = withContext(NonCancellable) { PluginSources.acquire(context, source) }
            val service = connect()
            pending[id] = result
            service.send(Message.obtain(null, 1).apply {
                replyTo = replies
                data = Bundle().apply {
                    putString("id", id); putString("sourceHash", sourceHash); putString("variables", variables.toString())
                    putString("method", method); putString("args", args.toString()); putString("pluginId", pluginId)
                    /* The service process has its own SharedPreferences cache, so the
                       resolved count travels with the call instead of being read there. */
                    putInt("workers", limit)
                }
            })
            val raw = withTimeout(timeoutMs) { result.await() }
            val response = JSONObject(raw)
            if (!response.optBoolean("ok")) error(response.optString("error", "插件执行失败"))
            if (response.isNull("value")) null else response.get("value")
        } catch (e: TimeoutCancellationException) {
            /* Only this call is abandoned. Killing the process here would take down the
               other searches that share it. */
            cancel(id)
            throw IllegalStateException("插件执行超时", e)
        } catch (e: CancellationException) {
            cancel(id)
            throw e
        } finally {
            pending.remove(id)
            sourceHash?.let { PluginSources.release(it) }
        }
    }

    private fun cancel(id: String) {
        val service = remote ?: return
        runCatching { service.send(Message.obtain(null, 3).apply { data = Bundle().apply { putString("id", id) } }) }
    }

    private suspend fun connect(): Messenger {
        if (remote != null && pluginPid > 0) return remote!!
        val wait = binding ?: CompletableDeferred<Messenger>().also {
            binding = it
            if (!context.bindService(Intent(context, PluginService::class.java), connection, Context.BIND_AUTO_CREATE)) {
                it.completeExceptionally(IllegalStateException("无法启动插件服务"))
            }
        }
        return withTimeout(15_000) { wait.await() }
    }
}
