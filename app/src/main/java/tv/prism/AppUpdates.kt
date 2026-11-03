package tv.prism

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class AppRelease(
    val tag: String, val notes: String, val downloadUrl: String, val size: Long,
    val sha256: String, val pageUrl: String, val source: String,
)
internal data class AppUpdateState(
    val release: AppRelease? = null, val message: String = "可检查新版本并查看更新说明",
    val checking: Boolean = false, val downloading: Boolean = false,
    val received: Long = 0, val total: Long = 0, val ready: File? = null, val skipped: Boolean = false,
) { val busy get() = checking || downloading }

/** Owns release metadata, network fallback, APK verification and the installer handoff. */
internal class AppUpdates(
    private val context: Context,
    private val repository: String = BuildConfig.UPDATE_REPOSITORY,
    private val mirrorBase: String = BuildConfig.UPDATE_MIRROR_BASE,
) {
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences("app_updates_${repository.hashCode()}_${mirrorBase.hashCode()}", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).followSslRedirects(false).build()
    @Volatile private var activeCall: Call? = null
    private val directory = File(context.cacheDir, "app-updates/downloads")
    private val mutable = MutableStateFlow(AppUpdateState())
    val state: StateFlow<AppUpdateState> = mutable
    val releasePage get() = "https://github.com/$repository/releases/latest"
    private val githubMetadata get() = "https://api.github.com/repos/$repository/releases/latest"
    private val mirrorMetadata get() = mirrorBase.takeIf { it.isNotBlank() }?.let { "$it/latest.json" }

    fun cancelNetwork() { activeCall?.cancel() }
    fun skip() {
        if (mutable.value.busy) return
        mutable.value.release?.let { prefs.edit().putString("skip", it.tag).apply() }
        mutable.value = mutable.value.copy(skipped = true, message = "已跳过此版本")
    }

    suspend fun check(force: Boolean = false) = mutex.withLock {
        val now = System.currentTimeMillis()
        val last = prefs.getLong("attempt", 0)
        if (!force && now >= last && now - last < DAY) {
            val cachedUrl = prefs.getString("last_url", "").orEmpty()
            val source = if (cachedUrl == githubMetadata) "GitHub" else "备用站"
            val raw = prefs.getString("body_${cachedUrl.hashCode()}", null)
            if (raw != null) runCatching { parse(JSONObject(raw), source) }.getOrNull()?.let { release ->
                if ((compareVersions(release.tag, BuildConfig.VERSION_NAME) ?: 0) > 0) {
                    mutable.value = mutable.value.copy(release = release, message = "发现新版本 ${release.tag}", skipped = prefs.getString("skip", "") == release.tag,
                        ready = File(directory, "PrismAura.apk").takeIf { it.isFile && it.length() == release.size && prefs.getString("ready_digest", "") == release.sha256 })
                }
            }
            return@withLock
        }
        prefs.edit().putLong("attempt", now).apply()
        mutable.value = mutable.value.copy(checking = true, message = "正在检查更新…")
        try {
            var metadataUrl = githubMetadata
            var source = "GitHub"
            val root = try { metadata(metadataUrl) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                metadataUrl = mirrorMetadata ?: throw e; source = "备用站"
                mutable.value = mutable.value.copy(message = "正在尝试备用更新站…")
                metadata(metadataUrl)
            }
            val release = try { parse(root, source) } catch (e: Exception) {
                val mirror = mirrorMetadata
                if (source != "GitHub" || mirror == null) throw e
                currentCoroutineContext().ensureActive()
                metadataUrl = mirror; source = "备用站"
                parse(metadata(mirror), source)
            }
            prefs.edit().putString("last_url", metadataUrl).apply()
            val difference = compareVersions(release.tag, BuildConfig.VERSION_NAME) ?: error("发布版本号格式无效")
            if (difference > 0) {
                val unchanged = mutable.value.release?.let { it.tag == release.tag && it.sha256 == release.sha256 } == true
                mutable.value = AppUpdateState(release, "发现新版本 ${release.tag}",
                    ready = mutable.value.ready?.takeIf { unchanged && it.exists() },
                    skipped = !force && prefs.getString("skip", "") == release.tag)
            } else mutable.value = AppUpdateState(message = "当前已是最新版本（${BuildConfig.VERSION_NAME}）")
        } catch (e: CancellationException) {
            mutable.value = mutable.value.copy(checking = false, message = "已取消检查")
            throw e
        } catch (e: Exception) {
            mutable.value = mutable.value.copy(checking = false, message = "检查失败：${e.message ?: "网络不可用"}；可稍后重试或用手机下载")
        } finally { activeCall = null }
    }

    private fun safeUrl(value: String): String {
        val url = value.toHttpUrlOrNull() ?: error("更新地址无效")
        val localPreview = BuildConfig.DEBUG && url.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
        require((url.isHttps || localPreview) && url.username.isEmpty() && url.password.isEmpty()) { "更新地址必须使用 HTTPS" }
        return url.toString()
    }

    private suspend fun metadata(url: String): JSONObject = withContext(Dispatchers.IO) {
        val address = safeUrl(url)
        val key = address.hashCode().toString()
        val request = Request.Builder().url(address).header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PrismAura/${BuildConfig.VERSION_NAME}")
        prefs.getString("etag_$key", null)?.let { request.header("If-None-Match", it) }
        val call = http.newCall(request.build()); call.timeout().timeout(12, TimeUnit.SECONDS); activeCall = call
        try {
            call.execute().use { response ->
                if (response.code == 304) {
                    val cached = prefs.getString("body_$key", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    if (cached == null) {
                        prefs.edit().remove("etag_$key").remove("body_$key").apply()
                        throw IOException("更新缓存已失效，请重试")
                    }
                    return@withContext cached
                }
                if (!response.isSuccessful) throw IOException(when (response.code) {
                    404 -> "仓库或正式 Release 尚未发布"
                    403, 429 -> "更新服务暂时限流"
                    else -> "更新服务返回 HTTP ${response.code}"
                })
                safeUrl(response.request.url.toString())
                val body = response.body ?: throw IOException("更新信息为空")
                require(body.contentLength() <= MAX_METADATA) { "更新信息过大" }
                val bytes = body.byteStream().use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer); if (count < 0) break
                        require(output.size() + count <= MAX_METADATA) { "更新信息过大" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val raw = bytes.toString(Charsets.UTF_8)
                val result = JSONObject(raw)
                prefs.edit().putString("body_$key", raw).putString("etag_$key", response.header("ETag")).apply()
                result
            }
        } finally { activeCall = null }
    }

    internal fun parse(root: JSONObject, source: String): AppRelease {
        require(!root.optBoolean("draft") && !root.optBoolean("prerelease")) { "当前仅接收正式版" }
        val tag = root.optString("tag_name")
        require(tag.length <= 64 && tag.matches(Regex("v?\\d+\\.\\d+\\.\\d+(?:\\+[0-9A-Za-z.-]+)?"))) { "版本标签需为 v主.次.修订号" }
        val assets = root.optJSONArray("assets") ?: error("Release 没有 APK 附件")
        val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .firstOrNull { it.optString("name") == APK_NAME && it.optString("state", "uploaded") == "uploaded" }
            ?: error("Release 缺少 $APK_NAME")
        val digest = apk.optString("digest").removePrefix("sha256:").lowercase()
        require(digest.matches(Regex("[0-9a-f]{64}"))) { "APK 缺少有效 SHA-256 摘要" }
        val size = apk.optLong("size")
        require(size in 1..MAX_APK) { "APK 大小无效或超过 256 MiB" }
        val download = safeUrl(apk.optString("browser_download_url"))
        if (source == "GitHub") {
            val path = download.toHttpUrlOrNull()!!
            require(path.host == "github.com" && path.encodedPath.startsWith("/$repository/releases/download/")) { "APK 不属于配置的发布仓库" }
        }
        val page = root.optString("html_url").takeIf { it.isNotBlank() && it != "null" }
            ?.let { runCatching { safeUrl(it) }.getOrNull() } ?: releasePage
        val notes = root.optString("body").takeUnless { it == "null" }.orEmpty().take(16_000)
        return AppRelease(tag, notes, download, size, digest, page, source)
    }

    suspend fun download() = mutex.withLock {
        val release = mutable.value.release ?: return@withLock
        mutable.value = mutable.value.copy(downloading = true, received = 0, total = release.size, ready = null, message = "正在从${release.source}下载…")
        val part = File(directory, "update.part")
        try {
            check(directory.isDirectory || directory.mkdirs()) { "无法创建更新缓存" }
            part.delete()
            try { transfer(release, part) }
            catch (e: CancellationException) { throw e }
            catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                part.delete()
                val alternate = if (release.source == "GitHub") {
                    val mirror = mirrorMetadata ?: throw e
                    mutable.value = mutable.value.copy(message = "GitHub 下载失败，正在切换备用站…", received = 0)
                    parse(metadata(mirror), "备用站").also {
                        require(it.tag == release.tag && it.sha256 == release.sha256 && it.size == release.size) { "备用站版本或摘要不一致，已停止下载" }
                    }
                } else {
                    mutable.value = mutable.value.copy(message = "备用站下载失败，正在尝试 GitHub…", received = 0)
                    parse(metadata(githubMetadata), "GitHub").also {
                        require(it.tag == release.tag && it.sha256 == release.sha256 && it.size == release.size) { "更新来源版本不一致" }
                    }
                }
                transfer(alternate, part)
            }
            verify(part, release)
            val ready = File(directory, "PrismAura.apk")
            check(!ready.exists() || ready.delete()) { "无法替换旧的更新缓存" }
            check(part.renameTo(ready)) { "无法保存安装包" }
            prefs.edit().putString("ready_digest", release.sha256).apply()
            mutable.value = mutable.value.copy(downloading = false, ready = ready, message = "下载与校验完成，可以安装")
        } catch (e: CancellationException) {
            mutable.value = mutable.value.copy(downloading = false, ready = null, message = "已取消下载")
            throw e
        } catch (e: Exception) {
            mutable.value = mutable.value.copy(downloading = false, ready = null, message = "下载失败：${e.message ?: "网络不可用"}；可重试或用手机下载")
        } finally { part.delete(); activeCall = null }
    }

    private suspend fun transfer(release: AppRelease, file: File) = withContext(Dispatchers.IO) {
        val call = http.newCall(Request.Builder().url(safeUrl(release.downloadUrl)).header("User-Agent", "PrismAura/${BuildConfig.VERSION_NAME}").build())
        activeCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("下载服务返回 HTTP ${response.code}")
                safeUrl(response.request.url.toString())
                val body = response.body ?: throw IOException("下载内容为空")
                val declared = body.contentLength()
                require(declared < 0 || declared == release.size) { "安装包长度与更新信息不一致" }
                body.byteStream().use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var received = 0L; var lastProgress = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        received += count
                        require(received <= release.size && received <= MAX_APK) { "安装包超过声明大小" }
                        output.write(buffer, 0, count)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastProgress >= 150) {
                            mutable.value = mutable.value.copy(received = received); lastProgress = now
                        }
                    }
                    if (received != release.size) throw IOException("下载未完成")
                    mutable.value = mutable.value.copy(received = received)
                } }
            }
        } finally { activeCall = null }
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { signature -> hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())) }?.toSet().orEmpty()

    @Suppress("DEPRECATION")
    private suspend fun verify(file: File, release: AppRelease) = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() == release.size) { "安装包已丢失或不完整，请重新下载" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer); if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(hex(digest.digest()) == release.sha256) { "SHA-256 校验失败，已拒绝安装" }
        // Some Android versions only collect archive certificates when GET_SIGNATURES is also set.
        val flags = PackageManager.GET_SIGNATURES or
            (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("文件不是有效的 APK")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName) { "APK 包名不匹配" }
        require(archive.versionName?.removePrefix("v") == release.tag.removePrefix("v")) { "APK 版本与更新信息不一致" }
        require(PackageInfoCompat.getLongVersionCode(archive) > PackageInfoCompat.getLongVersionCode(installed)) { "APK versionCode 未递增，已拒绝覆盖" }
        if (Build.VERSION.SDK_INT >= 24) require((archive.applicationInfo?.minSdkVersion ?: 0) <= Build.VERSION.SDK_INT) { "新版本不支持此 Android 系统" }
        val current = signatures(installed)
        require(current.isNotEmpty() && signatures(archive) == current) { "APK 签名与当前应用不一致，无法直接升级" }
    }

    suspend fun install(activity: Activity) {
        val release = mutable.value.release ?: return
        val apk = mutable.value.ready ?: return
        try { verify(apk, release) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            apk.delete()
            mutable.value = mutable.value.copy(ready = null, message = "安装校验失败：${e.message}；请重新下载")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            // Let the system installer own source permission and confirmation on each TV firmware.
            mutable.value = mutable.value.copy(message = "请在系统安装器中确认；如有提示，请允许安装此来源的应用")
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.value = mutable.value.copy(message = "无法安装：${e.message ?: "系统未提供安装器"}") }
    }

    companion object {
        const val APK_NAME = "PrismAura.apk"
        private const val DAY = 24 * 60 * 60 * 1000L
        private const val MAX_METADATA = 256 * 1024L
        private const val MAX_APK = 256 * 1024 * 1024L
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
