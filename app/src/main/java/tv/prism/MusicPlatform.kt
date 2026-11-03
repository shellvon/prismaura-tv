package tv.prism

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/** A user-selected default applies only when the item does not identify its platform. */
object SourcePlatforms {
    private val overrides = mutableStateMapOf<String, MusicPlatform>()
    private var preferences: android.content.SharedPreferences? = null

    fun initialize(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("source_platforms", Context.MODE_PRIVATE)
        preferences = prefs
        overrides.clear()
        prefs.all.forEach { (id, value) -> MusicPlatform.entries.firstOrNull { it.id == value && it != MusicPlatform.OTHER }?.let { overrides[id] = it } }
    }

    fun get(pluginId: String): MusicPlatform? = overrides[pluginId]

    fun set(pluginId: String, platform: MusicPlatform?) {
        if (platform == null) overrides.remove(pluginId) else overrides[pluginId] = platform
        preferences?.edit()?.apply { if (platform == null) remove(pluginId) else putString(pluginId, platform.id) }?.apply()
    }
}

/** IDs from MusicFree items, common aliases, and source URLs map to a single display identity. */
enum class MusicPlatform(val id: String, val label: String, val icon: Int?) {
    QQ("qqmusic", "QQ音乐", R.drawable.platform_qqmusic),
    NETEASE("netease", "网易云音乐", R.drawable.platform_netease),
    KUGOU("kugou", "酷狗音乐", R.drawable.platform_kugou),
    FIVESING("fivesing", "5sing", R.drawable.platform_fivesing),
    QISHUI("qishui", "汽水音乐", R.drawable.platform_qishui),
    KUWO("kuwo", "酷我音乐", R.drawable.platform_kuwo),
    MIGU("migu", "咪咕音乐", R.drawable.platform_migu),
    BILIBILI("bilibili", "哔哩哔哩", R.drawable.platform_bilibili),
    SPOTIFY("spotify", "Spotify", R.drawable.platform_spotify),
    YOUTUBE("youtube", "YouTube", R.drawable.platform_youtube),
    JOOX("joox", "JOOX", R.drawable.platform_joox),
    WEBDAV("webdav", "WebDAV", null),
    LOCAL("local", "本地音乐", null),
    CUSTOM("custom", "自建歌单", null),
    OTHER("other", "其他音源", null);

    companion object {
        fun identify(vararg values: String): MusicPlatform {
            values.forEach { value ->
                val v = value.trim().lowercase()
                val direct = when (v) {
                    "qqmusic", "qq_music", "qq音乐", "qq", "tx", "qq_wx" -> QQ
                    "netease", "net_easy", "neteasecloudmusic", "网易云", "网易云音乐", "wy" -> NETEASE
                    "kugou", "酷狗", "酷狗音乐", "kg" -> KUGOU
                    "5sing", "5sing音乐", "原创音乐基地", "我爱音乐" -> FIVESING
                    "qishui", "soda", "汽水", "汽水音乐" -> QISHUI
                    "kuwo", "coowoo", "酷我", "酷我音乐", "kw" -> KUWO
                    "migu", "咪咕", "咪咕音乐", "mg" -> MIGU
                    "bilibili", "b站", "哔哩哔哩", "bili" -> BILIBILI
                    "spotify", "sp" -> SPOTIFY
                    "youtube", "yt", "youtube music", "ytmusic" -> YOUTUBE
                    "joox" -> JOOX
                    "webdav" -> WEBDAV
                    "local", "本地", "本地音乐" -> LOCAL
                    "custom" -> CUSTOM
                    else -> null
                }
                if (direct != null) return direct
                val host = runCatching { java.net.URI(v).host?.lowercase() }.getOrNull() ?: return@forEach
                fun under(domain: String) = host == domain || host.endsWith(".$domain")
                when {
                    under("y.qq.com") || under("music.qq.com") -> return QQ
                    under("music.163.com") || under("163cn.tv") -> return NETEASE
                    /* 5sing 挂在 kugou.com 域下，必须排在酷狗自己的域名判断前面。 */
                    under("5sing.kugou.com") -> return FIVESING
                    under("kugou.com") -> return KUGOU
                    under("music.douyin.com") || under("qishui.com") -> return QISHUI
                    under("kuwo.cn") -> return KUWO
                    under("migu.cn") -> return MIGU
                    under("bilibili.com") || under("b23.tv") -> return BILIBILI
                    under("spotify.com") -> return SPOTIFY
                    under("youtube.com") || under("youtu.be") -> return YOUTUBE
                    under("joox.com") -> return JOOX
                }
            }
            return OTHER
        }

        fun forSong(pluginId: String, sourceName: String, data: org.json.JSONObject): MusicPlatform {
            val explicit = identify(data.optString("source"), data.optString("platform"), data.optJSONObject("_rawData")?.optString("source").orEmpty(), data.optString("url"))
            if (explicit != OTHER) return explicit
            return SourcePlatforms.get(pluginId) ?: identify(sourceName)
        }
    }
}
