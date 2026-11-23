package tv.prism

import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tv.prism.library.MusicLibrary
import tv.prism.library.Song
import tv.prism.lyrics.LyricStatus
import tv.prism.lyrics.Lyrics
import tv.prism.playback.PlaybackPhase

@RunWith(AndroidJUnit4::class)
class TvExperienceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun start() = instrumentation.startActivitySync(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ) as MainActivity

    private fun nodes(): List<AccessibilityNodeInfo> {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo
        val found = mutableListOf<AccessibilityNodeInfo>()
        automation.rootInActiveWindow?.let(found::add)
        var index = 0
        while (index < found.size) {
            val node = found[index++]
            repeat(node.childCount) { child -> node.getChild(child)?.let(found::add) }
        }
        return found
    }

    private suspend fun <T : Any> await(label: String, timeout: Long = 6_000, find: () -> T?): T {
        try {
            return withTimeout(timeout) {
                var match = find()
                while (match == null) {
                    delay(50)
                    match = find()
                }
                match
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Expected $label; visible " + nodes().mapNotNull {
                val text = it.text ?: it.contentDescription
                if (it.isFocused) "[$text @ ${it.className}]" else text?.toString()
            }.take(45), e)
        }
    }

    private suspend fun label(name: String, focused: Boolean = false) = await(name) {
        val all = nodes()
        val matches = all.filter { it.text?.toString() == name || it.contentDescription?.toString() == name }
        if (!focused) matches.firstOrNull() else matches.firstOrNull { label ->
            val center = Rect().also { label.getBoundsInScreen(it) }
            // Compose can expose the label and the focusable/clickable node separately.
            all.any { target ->
                target.isFocused && target.isClickable &&
                    Rect().also { target.getBoundsInScreen(it) }.contains(center.centerX(), center.centerY())
            }
        }
    }

    @Test fun playerKeepsLyricLayoutAndFirstRemotePressOnlyWakesControls() = runBlocking<Unit> {
        val activity = start()
        val title = "验证布局的曲目"
        val song = Song("fixture", JSONObject().put("id", "layout").put("title", title))
        val lines = Lyrics.parse("[00:00]第一句歌词\n[00:15]第二句歌词")
        suspend fun titleX(): Int = await("player title position") {
            val height = context.resources.displayMetrics.heightPixels
            nodes().filter { it.text?.toString() == title }
                .map { node -> Rect().also { node.getBoundsInScreen(it) } }
                .firstOrNull { it.centerY() in (height * 3 / 10)..(height * 7 / 10) }?.left
        }
        fun publish(status: LyricStatus) = instrumentation.runOnMainSync {
            activity.lyricStatus = status
            activity.lyrics = if (status == LyricStatus.Ready) lines else emptyList()
        }
        try {
            await("player controller") { activity.controller }
            instrumentation.runOnMainSync {
                activity.resumePending = -1
                activity.resolving?.cancel()
                activity.streamSwap?.cancel()
                activity.lyricJob?.cancel()
                activity.lyricToken++
                activity.resetStream(activity.controller)
            }
            await("initial stream detached") {
                var detached = false
                instrumentation.runOnMainSync { detached = activity.controller?.currentMediaItem == null }
                true.takeIf { detached }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            instrumentation.runOnMainSync {
                activity.queue = listOf(song)
                activity.queueIndex = 0
                activity.reduceMotion = true
                activity.positionMs = 0
                activity.durationMs = 60_000
                activity.playbackPhase = PlaybackPhase.Ready
                activity.lyricStatus = LyricStatus.Loading
                activity.playerOpen = true
                activity.playing = true
            }
            label("暂停", focused = true)
            delay(250)
            val centered = titleX()
            publish(LyricStatus.Empty())
            label("暂无歌词")
            assertEquals(centered, titleX())
            publish(LyricStatus.Ready)
            label("第一句歌词")
            val left = titleX()
            assertTrue(left < centered)
            publish(LyricStatus.Loading)
            delay(350)
            assertEquals(left, titleX())
            publish(LyricStatus.Error)
            label(LyricStatus.Error.message)
            assertEquals(centered, titleX())

            await("timeout across lyric changes", 14_000) {
                true.takeIf { nodes().none { it.contentDescription == "暂停" } }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("暂停", focused = true)
            assertEquals(0L, activity.positionMs)
            assertEquals(0, activity.queueIndex)
            assertTrue(activity.playing)
            await("timeout after waking", 14_000) {
                true.takeIf { nodes().none { it.contentDescription == "暂停" } }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            label("暂停", focused = true)
            assertEquals(song.key, activity.queue[activity.queueIndex].key)

            instrumentation.runOnMainSync { activity.playing = false }
            val off = label("熄屏")
            assertTrue(off.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("screen off") { true.takeIf { activity.playerScreenOff } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("播放", focused = true)
            assertFalse(activity.playerScreenOff)

            instrumentation.runOnMainSync {
                activity.playbackPhase = PlaybackPhase.Failed
                activity.playbackDetail = "验证音源原始错误信息"
            }
            val more = label("更多")
            val moreButton = generateSequence(more) { it.parent }.first { it.isClickable }
            assertTrue(moreButton.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("播放错误详情")
            await("player menu focus") { true.takeIf { nodes().any { it.isFocused } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("关闭")
            label("验证音源原始错误信息")
        } finally {
            instrumentation.runOnMainSync {
                activity.exitingAfterResumeSave = true
                activity.queue = emptyList()
                activity.queueIndex = -1
                activity.playbackPhase = PlaybackPhase.Idle
                activity.finish()
            }
        }
    }

    @Test fun topNavigationAndPhoneFillReturnToTheirOrigin() = runBlocking<Unit> {
        val activity = start()
        try {
            await("player controller") { activity.controller }
            instrumentation.runOnMainSync { activity.page = "home" }
            val home = label("首页")
            assertTrue(generateSequence(home) { it.parent }.first { it.isClickable }.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            repeat(4) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("settings entered from the last tab") { true.takeIf { activity.page == "plugins" } }
            label("外观", focused = true)

            instrumentation.runOnMainSync { activity.page = "search" }
            val phone = label("手机代填搜索词")
            val button = generateSequence(phone) { it.parent }.first { it.isClickable }
            assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("返回电视输入", focused = true)
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            label("手机代填搜索词", focused = true)

            instrumentation.runOnMainSync {
                activity.settingsViewModel.open("音源")
                activity.page = "plugins"
                activity.pluginViewModel.showAdd(true)
            }
            label("手机代填音源地址")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun homeUpFromListeningHistoryReturnsToNavigation() = runBlocking<Unit> {
        val storage = object : ContextWrapper(context) {
            private val folder = File(context.cacheDir, "home-focus-${UUID.randomUUID()}").apply { mkdirs() }
            override fun getFilesDir() = folder
        }
        val library = MusicLibrary(storage)
        val song = Song("fixture", JSONObject().put("id", "history").put("title", "焦点验证历史"))
        library.recordListen(song, System.currentTimeMillis())
        library.record(Song("fixture", JSONObject().put("id", "recent").put("title", "焦点验证最近")))
        library.create("焦点验证首页歌单")
        val activity = start()
        try {
            await("player controller") { activity.controller }
            instrumentation.runOnMainSync {
                activity.library = library
                activity.hideHeading = !activity.hideHeading
                activity.homeShelfHidden = emptySet()
                activity.page = "home"
            }
            val playAll = label("播放全部")
            val button = generateSequence(playAll) { it.parent }.first { it.isClickable }
            assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            label("焦点验证历史", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            label("焦点验证最近", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            label("焦点验证首页歌单", focused = true)
            label("1 个歌单")
            val downAt = android.os.SystemClock.uptimeMillis()
            repeat(18) { count ->
                instrumentation.sendKeySync(KeyEvent(
                    downAt, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, count,
                ))
                delay(80)
            }
            instrumentation.sendKeySync(KeyEvent(
                downAt, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0,
            ))
            label("首页", focused = true)
            assertTrue(generateSequence(label("播放全部")) { it.parent }
                .first { it.isClickable }.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            for (name in listOf("焦点验证历史", "焦点验证最近", "焦点验证首页歌单")) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                label(name, focused = true)
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("这个歌单里还没有歌")
            fun back() = instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
            )
            assertTrue(back())
            await("home after leaving a playlist opened on Home") { true.takeIf { activity.page == "home" } }
            label("焦点验证首页歌单", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            label("查看全部", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            val libraryCard = label("焦点验证首页歌单")
            assertTrue(generateSequence(libraryCard) { it.parent }.first { it.isClickable }
                .performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("这个歌单里还没有歌")
            assertTrue(back())
            label("焦点验证首页歌单", focused = true)
            await("library card restoration settled") {
                true.takeIf { activity.libraryViewModel.state.value.returningPlaylist == null }
            }
            instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            await("home after leaving the full playlist list") { true.takeIf { activity.page == "home" } }
            label("查看全部", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("焦点验证首页歌单")
            val home = generateSequence(label("首页")) { it.parent }.first { it.isClickable }
            assertTrue(home.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("home after clicking the navigation tab") { true.takeIf { activity.page == "home" } }
            label("查看全部", focused = true)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun renameCancelAndSystemBackRestoreMenuFocus() = runBlocking<Unit> {
        val activity = start()
        val storage = object : ContextWrapper(context) {
            private val folder = File(context.cacheDir, "rename-${UUID.randomUUID()}").apply { mkdirs() }
            override fun getFilesDir() = folder
        }
        val library = MusicLibrary(storage)
        library.create("焦点验证歌单")
        try {
            await("player controller") { activity.controller }
            instrumentation.runOnMainSync {
                activity.library = library
                activity.libraryViewModel.open("playlists")
                activity.page = "library"
            }
            val card = label("焦点验证歌单")
            val longClick = generateSequence(card) { it.parent }.first { it.isLongClickable }
            assertTrue(longClick.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            suspend fun openRename() {
                val rename = label("重命名歌单")
                val action = generateSequence(rename) { it.parent }.first { it.isClickable }
                assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("rename field") { nodes().firstOrNull { it.className == "android.widget.EditText" && it.isFocused } }
                label("手机代填歌单名称")
            }
            openRename()
            val cancel = label("取消")
            assertTrue(generateSequence(cancel) { it.parent }.first { it.isClickable }.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("重命名歌单", focused = true)
            openRename()
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            label("重命名歌单", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            label("删除歌单", focused = true)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
