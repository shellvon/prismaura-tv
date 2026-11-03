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

    private fun file(plugin: Plugin, song: Song, quality: String): File {
        val identity = listOf(plugin.id, plugin.source, plugin.variables.toString(), song.key, quality).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory, "$digest.json")
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

    suspend fun write(plugin: Plugin, song: Song, quality: String, source: JSONObject) = mutex.withLock {
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
