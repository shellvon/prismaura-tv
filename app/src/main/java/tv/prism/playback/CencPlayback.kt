package tv.prism.playback

import android.util.Base64
import androidx.media3.common.C
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import org.json.JSONObject
import java.util.UUID

private val hexKey = Regex("[a-fA-F0-9]{32}")
private val encodedKey = Regex("[a-zA-Z0-9_+/\\-]{22}(==)?")

internal fun clearKeyValue(input: String): String {
    val value = input.trim()
    val bytes = when {
        hexKey.matches(value) -> value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        encodedKey.matches(value) -> Base64.decode(value.replace('-', '+').replace('_', '/'), Base64.DEFAULT)
        else -> throw UnsupportedAudioEncryptionException("CENC 密钥需要 16 字节的十六进制或 Base64 编码")
    }
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun clearKeySessionManager(key: String) = DefaultDrmSessionManager.Builder()
    .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
    .build(object : MediaDrmCallback {
        override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): MediaDrmCallback.Response =
            throw UnsupportedOperationException("ClearKey 不支持在线配置")

        override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): MediaDrmCallback.Response {
            val kids = JSONObject(request.data.toString(Charsets.UTF_8)).getJSONArray("kids")
            require(kids.length() == 1) { "当前音源只提供单个 CENC 密钥" }
            val kid = kids.getString(0)
            require(encodedKey.matches(kid)) { "CENC 密钥标识无效" }
            val entry = JSONObject().put("kty", "oct").put("kid", kid).put("k", key)
            val response = JSONObject().put("keys", org.json.JSONArray().put(entry)).put("type", "temporary")
            return MediaDrmCallback.Response(response.toString().toByteArray(Charsets.UTF_8))
        }
    })
