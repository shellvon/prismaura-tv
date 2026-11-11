package tv.prism

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Cookies belong to a plugin, not to every script, and are encrypted at rest. */
class PluginCookies(context: Context, private val pluginId: String) : CookieJar {
    private val prefs = context.getSharedPreferences("plugin_cookies", Context.MODE_PRIVATE)
    private var cookies: MutableList<Cookie>? = null
    private var retired = false

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (retired) return emptyList()
        val now = System.currentTimeMillis()
        return load().filter { it.expiresAt > now && it.matches(url) }
    }

    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (retired || cookies.isEmpty()) return
        val all = load()
        for (cookie in cookies) {
            all.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) all.add(cookie)
        }
        persist(all)
    }

    @Synchronized fun clear() {
        check(prefs.edit().remove(pluginId).commit()) { "无法清除登录状态" }
        cookies = mutableListOf()
    }

    /** Responses from requests started before a reset must not restore the old cookies. */
    @Synchronized fun retire() {
        clear()
        retired = true
    }

    private fun load(): MutableList<Cookie> {
        cookies?.let { return it }
        val encrypted = prefs.getString(pluginId, null)
        val list = if (encrypted == null) mutableListOf() else {
            val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
            require(bytes.size > 12) { "登录状态损坏" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val data = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            (0 until data.length()).mapNotNull { index ->
                val item = data.getJSONObject(index)
                val builder = Cookie.Builder().name(item.getString("name")).value(item.getString("value"))
                    .path(item.getString("path")).expiresAt(item.getLong("expires"))
                if (item.getBoolean("hostOnly")) builder.hostOnlyDomain(item.getString("domain"))
                else builder.domain(item.getString("domain"))
                if (item.getBoolean("secure")) builder.secure()
                if (item.getBoolean("httpOnly")) builder.httpOnly()
                builder.build().takeIf { it.expiresAt > System.currentTimeMillis() }
            }.toMutableList()
        }
        return list.also { cookies = it }
    }

    private fun persist(all: List<Cookie>) {
        val data = JSONArray()
        all.forEach { cookie ->
            data.put(JSONObject().put("name", cookie.name).put("value", cookie.value)
                .put("domain", cookie.domain).put("path", cookie.path).put("expires", cookie.expiresAt)
                .put("hostOnly", cookie.hostOnly).put("secure", cookie.secure).put("httpOnly", cookie.httpOnly))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = Base64.encodeToString(cipher.iv + cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString(pluginId, encrypted).commit()) { "无法保存登录状态" }
    }

    private fun key(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    companion object {
        private const val KEY_ALIAS = "prism.plugin.cookies.v1"
        private val KEY_LOCK = Any()
    }
}
