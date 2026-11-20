package tv.prism

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Bundle
import android.util.AtomicFile
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import tv.prism.updates.AppUpdates
import tv.prism.remote.qrCodeBitmap

/** Records uncaught Java exceptions in each process; Android still handles the fatal crash. */
class PrismApplication : Application() {
    internal val appUpdates by lazy { AppUpdates(this) }

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val file = AtomicFile(File(filesDir, "crash-${android.os.Process.myPid()}.txt"))
                val output = file.startWrite()
                try { output.write(trace.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
                catch (e: Exception) { file.failWrite(output) }
            } catch (_: Exception) { }
            previous?.uncaughtException(thread, error)
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var showing = false
            override fun onActivityResumed(activity: Activity) {
                if (showing || activity !is MainActivity) return
                val file = filesDir.listFiles()?.filter { it.name.startsWith("crash-") && it.name.endsWith(".txt") }?.minByOrNull { it.lastModified() } ?: return
                val trace = runCatching { file.readText().take(50_000) }.getOrNull() ?: return
                showing = true
                val content = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(30, 16, 30, 8)
                    addView(TextView(activity).apply {
                        text = "上次发生异常。扫码可写邮件反馈，二维码只包含反馈邮箱和应用版本。分享下方堆栈前，请先检查其中的私人信息。"
                    })
                    val subject = android.net.Uri.encode("PrismAura TV 崩溃反馈 v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    val qr = runCatching { qrCodeBitmap("mailto:tv-music-support@240524.xyz?subject=$subject") }.getOrNull()
                    if (qr != null) {
                        addView(ImageView(activity).apply {
                            setImageBitmap(qr)
                            contentDescription = "邮件反馈二维码"
                        }, LinearLayout.LayoutParams(300, 300))
                    }
                    addView(TextView(activity).apply { text = trace })
                }
                /* Selectable text and the scroll view are focusable on TV, and a D-pad that
                   lands in them never reaches the dialog buttons. The trace stays readable;
                   only the buttons take focus. */
                val body = ScrollView(activity).apply {
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    addView(content)
                }
                val dialog = AlertDialog.Builder(activity).setTitle("崩溃诊断")
                    .setView(body)
                    .setPositiveButton("关闭并清除") { _, _ -> file.delete() }
                    .setNegativeButton("稍后处理") { _, _ -> }
                    .setOnDismissListener { showing = false }
                    .create()
                /* The platform dialog leaves focus on the window, so the remote has nothing
                   to select until one button claims it. */
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
                }
                dialog.show()
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
