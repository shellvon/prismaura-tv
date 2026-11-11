package tv.prism

import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Caches resolved URLs, not audio. Script/configuration changes naturally select a new key. */
internal class MediaSourceCache(private val directory: File) {
    private val mutex = Mutex()
    private val revisions = mutableMapOf<String, Long>()

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    // Hash restored plugin IDs as well as cache identities so neither can introduce path segments.
    private fun file(plugin: Plugin, song: Song, quality: String): File {
        val identity = listOf(plugin.id, plugin.source, plugin.variables.toString(), song.key, quality).joinToString("\u0000")
        return File(directory, "${digest(plugin.id)}-${digest(identity)}.json")
    }

    suspend fun revision(pluginId: String): Long = mutex.withLock { revisions[pluginId] ?: 0L }

    /** Invalidate pending writes before deleting this plugin's cached URLs. */
    suspend fun clearFor(pluginId: String) = mutex.withLock {
        require(pluginId.isNotBlank()) { "未选择音源" }
        revisions[pluginId] = (revisions[pluginId] ?: 0L) + 1L
        withContext(Dispatchers.IO) {
            val files = directory.listFiles()
            check(files != null || !directory.exists()) { "无法读取播放地址缓存" }
            val prefix = "${digest(pluginId)}-"
            files.orEmpty().filter { it.name.startsWith(prefix) }.forEach {
                AtomicFile(it).delete()
                check(!it.exists()) { "无法清除播放地址缓存" }
            }
        }
    }

    suspend fun read(plugin: Plugin, song: Song, quality: String): JSONObject? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val path = file(plugin, song, quality)
            runCatching {
                if (!path.exists()) null else JSONObject(AtomicFile(path).openRead().bufferedReader().use { it.readText() })
                    .takeIf { it.optString("url").isNotBlank() }
            }.getOrNull()
        }
    }

    suspend fun write(plugin: Plugin, song: Song, quality: String, source: JSONObject, revision: Long) = mutex.withLock {
        if (revision != (revisions[plugin.id] ?: 0L)) return@withLock
        withContext(Dispatchers.IO) {
            val bytes = source.toString().toByteArray()
            if (bytes.size > 64 * 1024 || source.optString("url").isBlank()) return@withContext
            check(directory.isDirectory || directory.mkdirs()) { "无法创建播放地址缓存" }
            val atomic = AtomicFile(file(plugin, song, quality))
            val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) }
            catch (e: Exception) { atomic.failWrite(output); throw e }
            directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }
                ?.drop(128)?.forEach { AtomicFile(it).delete() }
        }
    }
}
