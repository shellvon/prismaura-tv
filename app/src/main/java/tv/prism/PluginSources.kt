package tv.prism

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.WeakHashMap

/** Immutable execution copies shared by the app and its private plugin process. */
internal object PluginSources {
    private val mutex = Mutex()
    private val hashes = WeakHashMap<String, String>()
    private val active = mutableMapOf<String, Int>()
    private val hashPattern = Regex("[0-9a-f]{64}")
    private const val MAX_BYTES = 16L * 1024 * 1024
    private const val MAX_FILES = 64
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private fun directory(context: Context) = File(context.filesDir, "plugin-sources")

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    suspend fun acquire(context: Context, source: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val known = hashes[source]
            val bytes = if (known == null) source.toByteArray(Charsets.UTF_8).also {
                require(it.size <= Plugins.MAX_SCRIPT) { "插件文件过大" }
            } else null
            val hash = known ?: digest(checkNotNull(bytes))
            val directory = directory(context)
            check(directory.isDirectory || directory.mkdirs()) { "无法保存插件源码" }
            val file = File(directory, "$hash.js")
            var created = false
            if (!file.isFile) {
                val data = bytes ?: source.toByteArray(Charsets.UTF_8)
                require(data.size <= Plugins.MAX_SCRIPT) { "插件文件过大" }
                val atomic = AtomicFile(file)
                val output = atomic.startWrite()
                try { output.write(data); atomic.finishWrite(output) }
                catch (e: Exception) { atomic.failWrite(output); throw e }
                created = true
            }
            hashes[source] = hash
            val now = System.currentTimeMillis()
            if (now - file.lastModified() > 60 * 60 * 1000) file.setLastModified(now)
            active[hash] = (active[hash] ?: 0) + 1
            if (created) runCatching { prune(directory, now) }
            hash
        }
    }

    suspend fun release(hash: String) = withContext(NonCancellable) {
        mutex.withLock {
            val count = active[hash] ?: return@withLock
            if (count <= 1) active.remove(hash) else active[hash] = count - 1
        }
    }

    // Called on the service's IO executor. A hash is never interpreted as an arbitrary path.
    fun read(context: Context, hash: String): String {
        require(hashPattern.matches(hash)) { "插件源码标识无效" }
        val directory = directory(context).canonicalFile
        val file = File(directory, "$hash.js")
        require(file.canonicalFile == file.absoluteFile) { "插件源码路径无效" }
        val bytes = ByteArrayOutputStream()
        AtomicFile(file).openRead().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= Plugins.MAX_SCRIPT) { "插件文件过大" }
                bytes.write(buffer, 0, count)
            }
        }
        val data = bytes.toByteArray()
        require(digest(data) == hash) { "插件源码校验失败" }
        return data.toString(Charsets.UTF_8)
    }

    private fun prune(directory: File, now: Long) {
        val files = directory.listFiles().orEmpty().filter {
            it.extension == "js" && hashPattern.matches(it.nameWithoutExtension)
        }.sortedBy { it.lastModified() }
        var bytes = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            val hash = file.nameWithoutExtension
            if (hash in active) continue
            if (count <= MAX_FILES && bytes <= MAX_BYTES && now - file.lastModified() <= MAX_AGE_MS) continue
            val size = file.length()
            AtomicFile(file).delete()
            if (!file.exists()) {
                count--; bytes -= size
                hashes.entries.removeAll { it.value == hash }
            }
        }
    }
}
