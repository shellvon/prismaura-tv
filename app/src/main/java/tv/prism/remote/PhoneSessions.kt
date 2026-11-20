package tv.prism.remote

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class PhoneSessions(
    private val context: Context,
    private val onStatus: (String) -> Unit,
) {
    internal var remoteSession by mutableStateOf<PhoneInput?>(null)
        private set
    private var formSession by mutableStateOf<PhoneInput?>(null)
    internal val activeSession: PhoneInput? get() = if (remoteMode) remoteSession else formSession
    /* The QR dialog is only the way in. Hiding it must not stop the server: the whole point
       of the remote is to use the phone while the TV stays unobstructed. */
    internal var dialogVisible by mutableStateOf(false)
    internal var remoteMode by mutableStateOf(false)
    internal var remoteEntry by mutableStateOf("play")
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var remoteTimeout: Runnable? = null
    private var formTimeout: Runnable? = null
    internal var formTitle by mutableStateOf("手机代填")
    private var formReturn: (() -> Unit)? = null
    internal fun dismissActive() {
        val returnToForm = if (remoteMode) null else formReturn
        closeSession(remoteMode)
        returnToForm?.invoke()
    }

    private fun closeSession(remote: Boolean) {
        val session = if (remote) remoteSession else formSession
        if (activeSession === session) dialogVisible = false
        val timeout = if (remote) remoteTimeout else formTimeout
        timeout?.let { handler.removeCallbacks(it) }
        session?.close()
        if (remote) { remoteSession = null; remoteTimeout = null }
        else { formSession = null; formTimeout = null; formReturn = null }
    }

    internal fun openForm(
        fields: List<Pair<String, String>>, initialValues: Map<String, String> = emptyMap(),
        title: String = "填写音源配置", message: String = "留空的配置项不修改。填写后回到电视确认并保存。",
        onReturn: () -> Unit = {}, onSubmit: (Map<String, String>) -> Unit,
    ) {
        closeSession(false)
        try {
            lateinit var form: PhoneInput
            form = PhoneInput(context, fields, initialValues = initialValues, formTitle = title, formMessage = message) { values ->
                if (formSession === form) {
                    closeSession(false)
                    onSubmit(values)
                    onReturn()
                }
            }
            formSession = form
            formTitle = title
            formReturn = onReturn
            remoteMode = false
            dialogVisible = true
            formTimeout = Runnable { closeSession(false); onStatus("扫码输入已过期，请重新打开") }.also { handler.postDelayed(it, 300_000) }
        } catch (e: Exception) { onStatus("无法启动手机输入：${e.message}") }
    }

    /* The remote panel is meant to stay open while someone browses the TV. It lives half an
       hour; hiding the QR only hides the dialog. */
    internal fun openRemote(snapshot: () -> String, command: (String) -> String) {
        closeSession(true)
        try {
            remoteSession = PhoneInput(context, snapshot = snapshot, command = command)
            remoteMode = true
            dialogVisible = true
            remoteTimeout = Runnable { closeSession(true); onStatus("手机遥控已过期，请重新打开") }.also { handler.postDelayed(it, 1_800_000) }
        } catch (e: Exception) { onStatus("无法启动手机遥控：${e.message}") }
    }

    fun showRemoteQr(): Boolean {
        if (remoteSession == null) return false
        remoteMode = true
        dialogVisible = true
        return true
    }

    fun closeForm() = closeSession(false)

    fun close() {
        closeSession(false)
        closeSession(true)
    }
}
