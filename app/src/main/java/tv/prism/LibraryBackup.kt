package tv.prism

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 本地备份：曲库、音源（源码 + 配置变量 + 订阅地址）与可迁移的偏好设置。
    自动保存的登录 Cookie 由 Android Keystore 加密，换设备无法解密，因此不写入备份。
    电视上没有文件选择器，导出与恢复走手机遥控页面（H5）。 */
object LibraryBackup {
    private val prefFiles = listOf("appearance", "playback", "search", "home", "source_platforms", "lyric_calibration")

    suspend fun build(context: Context, library: MusicLibrary, plugins: Plugins): JSONObject {
        val preferences = JSONObject()
        prefFiles.forEach { name ->
            val values = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            val out = JSONObject()
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean, is String, is Int, is Long -> out.put(key, value)
                    is Float -> out.put(key, value.toDouble())
                    is Set<*> -> out.put(key, JSONArray(value.filterIsInstance<String>()))
                    else -> Unit
                }
            }
            preferences.put(name, out)
        }
        return JSONObject()
            .put("app", "PrismAura")
            .put("format", 1)
            .put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
            .put("library", library.backupJson())
            .put("plugins", plugins.backupRoot())
            .put("preferences", preferences)
    }

    suspend fun apply(context: Context, library: MusicLibrary, plugins: Plugins, root: JSONObject) {
        require(root.optString("app") == "PrismAura") { "这不是 PrismAura 的备份文件" }
        require(root.optInt("format", 0) in 1..1) { "备份格式版本不受支持" }
        library.restoreBackup(root.getJSONObject("library"))
        plugins.restoreBackup(root.optJSONObject("plugins") ?: JSONObject())
        val preferences = root.optJSONObject("preferences") ?: return
        preferences.keys().forEach { name ->
            if (name !in prefFiles) return@forEach
            val values = preferences.optJSONObject(name) ?: return@forEach
            val edit = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            values.keys().forEach { key ->
                when (val value = values.opt(key)) {
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Double -> edit.putFloat(key, value.toFloat())
                    is String -> edit.putString(key, value)
                    is JSONArray -> edit.putStringSet(key, (0 until value.length()).map { value.optString(it) }.toSet())
                    else -> Unit
                }
            }
            check(edit.commit()) { "无法写入设置" }
        }
    }
}
