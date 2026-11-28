package tv.prism

import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
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
import tv.prism.ui.widgets.ChoicePopup
import tv.prism.ui.widgets.LocalAuraMotion
import tv.prism.ui.widgets.PrismAction
import tv.prism.ui.playback.MvPlayer
import tv.prism.playback.VideoSource
import tv.prism.ui.settings.PrismInfoDialog
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.util.concurrent.atomic.AtomicBoolean
import tv.prism.sources.Plugin
import tv.prism.sources.PluginsState
import kotlinx.coroutines.flow.MutableStateFlow

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
            val labelBounds = Rect().also { label.getBoundsInScreen(it) }
            // Selectable rows may expose focus and click semantics on separate nodes.
            val owner = generateSequence(label) { it.parent }.firstOrNull { it.isFocusable }
            val bounds = owner?.let { Rect().also(it::getBoundsInScreen) }
            val tolerance = context.resources.displayMetrics.density.roundToInt().coerceAtLeast(1)
            owner?.isFocused == true || all.any { target ->
                if (!target.isFocused || bounds == null) false else {
                    val actual = Rect().also(target::getBoundsInScreen)
                    actual.contains(labelBounds.centerX(), labelBounds.centerY()) &&
                        abs(actual.left - bounds.left) <= tolerance && abs(actual.top - bounds.top) <= tolerance &&
                        abs(actual.right - bounds.right) <= tolerance && abs(actual.bottom - bounds.bottom) <= tolerance
                }
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
            label("播放进度", focused = true)
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
            label("播放进度", focused = true)
            assertEquals(0L, activity.positionMs)
            assertEquals(0, activity.queueIndex)
            assertTrue(activity.playing)
            await("timeout after waking", 14_000) {
                true.takeIf { nodes().none { it.contentDescription == "暂停" } }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            label("播放进度", focused = true)
            assertEquals(song.key, activity.queue[activity.queueIndex].key)

            instrumentation.runOnMainSync { activity.playing = false }
            val off = label("熄屏")
            assertTrue(off.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("screen off") { true.takeIf { activity.playerScreenOff } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("播放进度", focused = true)
            assertFalse(activity.playerScreenOff)

            instrumentation.runOnMainSync {
                activity.playbackPhase = PlaybackPhase.Failed
                activity.playbackDetail = "验证音源原始错误信息"
            }
            val more = label("更多")
            val moreButton = generateSequence(more) { it.parent }.first { it.isClickable }
            assertTrue(moreButton.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            label("更多", focused = true)
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
            val remote = label("手机遥控")
            assertTrue(generateSequence(remote) { it.parent }.first { it.isClickable }
                .performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("隐藏二维码", focused = true)
            assertTrue(instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            label("手机遥控", focused = true)
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
            label("播放全部", focused = true)
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
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("取消", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            label("删除歌单", focused = true)
            assertEquals(1, library.state.value.playlists.size)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("取消", focused = true)
            assertTrue(instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            label("删除歌单", focused = true)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
    @Test fun settingsChoicesCancelWithoutChangingAndReturnToTheSameRow() = runBlocking<Unit> {
        val activity = start()
        try {
            await("player controller") { activity.controller }
            instrumentation.runOnMainSync {
                activity.reduceMotion = true
                activity.settingsViewModel.open("播放")
                activity.page = "plugins"
            }
            val before = activity.quality
            val row = generateSequence(label("首选音质")) { it.parent }.first { it.isClickable }
            assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            label("播放", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            label("首选音质", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            val names = mapOf("low" to "流畅", "standard" to "标准", "high" to "高品", "super" to "无损优先")
            label(names.getValue(before), focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            label("首选音质", focused = true)
            assertEquals(before, activity.quality)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label(names.getValue(before), focused = true)
            val next = if (before == "low") "standard" else "low"
            val option = generateSequence(label(names.getValue(next))) { it.parent }.first { it.isClickable }
            assertTrue(option.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("首选音质", focused = true)
            assertEquals(next, activity.quality)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            label("播放", focused = true)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun settingsChoicesHandleUnavailableAndExternalValuesWithoutMovingFocus() = runBlocking<Unit> {
        val activity = start()
        var previous: String? = null
        try {
            await("player controller") { activity.controller }
            previous = activity.quality
            instrumentation.runOnMainSync {
                activity.reduceMotion = true
                activity.quality = "removed-quality"
                activity.settingsViewModel.open("播放")
                activity.page = "plugins"
            }
            val row = generateSequence(label("首选音质")) { it.parent }.first { it.isClickable }
            assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            label("当前值不可用")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("当前值不可用，请重新选择")
            label("流畅", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            label("首选音质", focused = true)
            assertEquals("removed-quality", activity.quality)

            instrumentation.runOnMainSync { activity.updatePreference("quality", "standard") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("标准", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            label("高品", focused = true)
            // A phone preference update changes the current value while the TV user browses.
            instrumentation.runOnMainSync { activity.updatePreference("quality", "super") }
            await("external selection marker") {
                nodes().firstOrNull { node -> node.text?.toString() == "无损优先" &&
                    generateSequence(node) { it.parent }.any { it.isChecked } }
            }
            label("高品", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            label("首选音质", focused = true)
            assertEquals("super", activity.quality)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("无损优先", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            label("高品", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("首选音质", focused = true)
            assertEquals("high", activity.quality)
        } finally {
            instrumentation.runOnMainSync {
                previous?.let { activity.updatePreference("quality", it) }
                activity.finish()
            }
        }
    }

    @Test fun choicePopupKeepsFocusWhenOptionsChangeAndRecoversWhenTheFocusedRowIsRemoved() = runBlocking<Unit> {
        val activity = start()
        val options = mutableStateOf(listOf("选项一", "选项二", "选项三"))
        val selected = mutableIntStateOf(0)
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    CompositionLocalProvider(LocalAuraMotion provides false) {
                        ChoicePopup(options.value, selected.intValue, { it }, {}, {}, icon = {})
                    }
                }
            }
            label("选项一", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            label("选项二", focused = true)
            instrumentation.runOnMainSync {
                options.value += "选项四"
                selected.intValue = 3
            }
            label("选项四")
            label("选项二", focused = true)
            instrumentation.runOnMainSync {
                options.value = options.value.take(3)
                selected.intValue = 0
            }
            await("unfocused option removed") { true.takeIf { nodes().none { it.text?.toString() == "选项四" } } }
            label("选项二", focused = true)
            instrumentation.runOnMainSync { options.value = options.value.take(1) }
            label("选项一", focused = true)
            instrumentation.runOnMainSync { options.value = emptyList() }
            label("暂无可选项 · 返回", focused = true)
            instrumentation.runOnMainSync {
                options.value = listOf("选项一", "选项二")
                selected.intValue = 1
            }
            label("选项二", focused = true)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun recommendationPickerStartsOnTheSavedSourceAndClosesWithOneConfirm() = runBlocking<Unit> {
        val activity = start()
        val state = activity.plugins.state as MutableStateFlow<PluginsState>
        val original = state.value
        val previous = activity.recommendationPluginId
        val sources = listOf("第一测试音源", "第二测试音源", "第三测试音源").mapIndexed { i, name ->
            Plugin("picker-$i", "", "", JSONObject().put("platform", name)
                .put("recommendSheets", true).put("sheetInfo", true), JSONObject())
        }
        try {
            await("player controller") { activity.controller }
            instrumentation.runOnMainSync {
                activity.reduceMotion = true
                state.value = original.copy(plugins = sources)
                activity.updatePreference("recommendationSource", sources[1].id)
                activity.settingsViewModel.open("推荐")
                activity.page = "plugins"
            }
            val row = generateSequence(label("推荐歌单")) { it.parent }.first { it.isClickable }
            assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label(sources[1].displayName, focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("推荐歌单", focused = true)
            assertEquals(sources[1].id, activity.recommendationPluginId)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label(sources[1].displayName, focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            label(sources[2].displayName, focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("推荐歌单", focused = true)
            assertEquals(sources[2].id, activity.recommendationPluginId)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label(sources[2].displayName, focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            label("推荐歌单", focused = true)
            assertEquals(sources[2].id, activity.recommendationPluginId)
        } finally {
            instrumentation.runOnMainSync {
                state.value = original
                activity.recommendationPluginId = previous
                activity.getSharedPreferences("home", 0).edit().putString("recommendation_plugin", previous).apply()
                activity.finish()
            }
        }
    }

    @Test fun playerTopControlsRememberTheirPositionAndSubjectSearchReturnsToPlayer() = runBlocking<Unit> {
        val activity = start()
        val state = activity.plugins.state as MutableStateFlow<PluginsState>
        val original = state.value
        val song = Song("missing-fixture", JSONObject().put("id", "player-nav").put("title", "播放器导航验证")
            .put("artist", "导航测试歌手").put("album", "导航测试专辑").put("mvId", "fixture-mv"))
        try {
            await("player controller") { activity.controller }
            instrumentation.runOnMainSync {
                activity.resetStream(activity.controller)
                activity.resumePending = -1
                activity.reduceMotion = true
                state.value = original.copy(plugins = listOf(Plugin(song.pluginId, "", "",
                    JSONObject().put("platform", "导航测试音源").put("mvSource", true), JSONObject())))
                activity.queue = listOf(song)
                activity.queueIndex = 0
                activity.playing = false
                activity.playbackPhase = PlaybackPhase.Ready
                activity.lyrics = emptyList()
                activity.lyricStatus = LyricStatus.Empty()
                activity.page = "library"
                activity.playerOpen = true
            }
            label("播放进度", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            label("沉浸", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            label("熄屏", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            label("播放进度", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            label("熄屏", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            label("沉浸", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            label("MV", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            label("播放进度", focused = true)
            instrumentation.runOnMainSync { activity.queue = listOf(song.copy(data = JSONObject(song.data.toString()).apply { remove("mvId") })) }
            await("MV control removed") { true.takeIf { nodes().none { it.contentDescription?.toString() == "MV" } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            label("沉浸", focused = true)
            instrumentation.runOnMainSync { activity.queue = listOf(song) }
            label("MV")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            label("MV", focused = true)
            instrumentation.runOnMainSync { activity.queue = listOf(song.copy(data = JSONObject(song.data.toString()).apply { remove("mvId") })) }
            label("沉浸", focused = true)
            val more = generateSequence(label("更多")) { it.parent }.first { it.isClickable }
            assertTrue(more.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            label("更多", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("查看歌手")
            fun headerTop() = Rect().also { labelBounds ->
                nodes().first { it.text?.toString() == song.artist }.getBoundsInScreen(labelBounds)
            }.top
            val top = headerTop()
            repeat(10) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN) }
            label("极光电台")
            assertEquals(top, headerTop())
            assertTrue(nodes().none { it.text?.toString() in setOf("关闭菜单", "返回上一级") })
            val artist = generateSequence(label("查看歌手")) { it.parent }.first { it.isClickable }
            assertTrue(artist.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("artist search fallback") { true.takeIf { activity.page == "search" && !activity.playerOpen } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("return to player") { true.takeIf { activity.playerOpen && activity.page == "library" } }
            label("查看歌手")
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            delay(250)
            label("查看歌手")
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            label("更多", focused = true)
            assertTrue(activity.playerOpen)
        } finally {
            instrumentation.runOnMainSync {
                state.value = original
                activity.exitingAfterResumeSave = true
                activity.queue = emptyList()
                activity.queueIndex = -1
                activity.finish()
            }
        }
    }

    private fun mvServer(available: AtomicBoolean): MockWebServer {
        val video = instrumentation.context.assets.open("mv-test.mp4").use { it.readBytes() }
        return MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (!available.get()) return MockResponse().setResponseCode(404)
                    val range = request.getHeader("Range")?.removePrefix("bytes=")
                    val first = range?.substringBefore('-')?.toIntOrNull() ?: 0
                    val last = (range?.substringAfter('-')?.toIntOrNull() ?: video.lastIndex).coerceAtMost(video.lastIndex)
                    if (first !in video.indices || last < first) return MockResponse().setResponseCode(416)
                    val response = MockResponse().setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes")
                        .setBody(Buffer().write(video, first, last - first + 1))
                    if (range != null) response.setResponseCode(206).setHeader("Content-Range", "bytes $first-$last/${video.size}")
                    return response
                }
            }
            start()
        }
    }

    private suspend fun click(name: String) {
        val button = generateSequence(label(name)) { it.parent }.first { it.isClickable }
        assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun mvCanSeekDragAndReopenWithoutReturningToTheOldResumePosition() = runBlocking<Unit> {
        val server = mvServer(AtomicBoolean(true))
        val source = VideoSource(server.url("/mv.mp4").toString())
        val activity = start()
        val opened = mutableStateOf(true)
        val progressKey = "mv-reentry-${UUID.randomUUID()}"
        fun progress(): Float = nodes().firstOrNull { it.contentDescription?.toString() == "MV 播放进度" }?.rangeInfo?.current ?: -1f
        fun setProgress(value: Float) {
            val rail = nodes().first { it.contentDescription?.toString() == "MV 播放进度" }
            assertTrue(rail.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                android.os.Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }))
        }
        try {
            await("player controller") { activity.controller }
            instrumentation.runOnMainSync {
                activity.resetStream(activity.controller)
                activity.activeMv = source
                activity.setContent {
                    CompositionLocalProvider(LocalAuraMotion provides false) {
                        if (opened.value) MvPlayer(source, "MV 本地验证", "测试画面", progressKey) { activity.activeMv = null; opened.value = false }
                        else PrismAction("打开 MV", { activity.activeMv = source; opened.value = true })
                    }
                }
            }
            label("MV 播放进度", focused = true)
            label("0:30")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("播放")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY)
            label("暂停")
            instrumentation.runOnMainSync { assertFalse(activity.controller?.playWhenReady == true) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
            label("播放")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("10 second MV seek") { true.takeIf { progress() in .30f.. .45f } }
            setProgress(.3f)
            await("accessible MV seek") { true.takeIf { progress() in .28f.. .34f } }
            val bounds = Rect().also { nodes().first { it.contentDescription?.toString() == "MV 播放进度" }.getBoundsInScreen(it) }
            val downAt = android.os.SystemClock.uptimeMillis()
            fun pointer(action: Int, fraction: Float) {
                val event = android.view.MotionEvent.obtain(downAt, android.os.SystemClock.uptimeMillis(), action,
                    bounds.left + bounds.width() * fraction, bounds.exactCenterY(), 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            pointer(android.view.MotionEvent.ACTION_DOWN, .3f)
            repeat(8) { pointer(android.view.MotionEvent.ACTION_MOVE, .3f + .4f * (it + 1) / 8); delay(16) }
            pointer(android.view.MotionEvent.ACTION_UP, .7f)
            await("touch MV drag") { true.takeIf { progress() in .66f.. .75f } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            label("打开 MV")
            val saved = context.getSharedPreferences("mv_progress", 0).getLong(progressKey, 0)
            assertTrue(saved in 19_000..23_000)
            click("打开 MV")
            label("MV 播放进度", focused = true)
            await("reopened MV resumes") { true.takeIf { progress() in .65f.. .8f } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("播放")
            setProgress(.4f)
            await("new MV seek after resume") { true.takeIf { progress() in .38f.. .46f } }
            await("decoded paused MV") { true.takeIf { nodes().any { it.text?.toString()?.contains("p · 已暂停") == true } } }
            delay(500)
            assertTrue(progress() in .38f.. .46f)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.cacheDir, "mv-ui-review.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally {
            instrumentation.runOnMainSync { activity.activeMv = null; activity.finish() }
            context.getSharedPreferences("mv_progress", 0).edit().remove(progressKey).apply()
            server.shutdown()
        }
    }

    @Test fun mvRetryPreparesTheSourceAgainAfterAnError() = runBlocking<Unit> {
        val available = AtomicBoolean(false)
        val server = mvServer(available)
        val source = VideoSource(server.url("/mv.mp4").toString())
        val activity = start()
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    CompositionLocalProvider(LocalAuraMotion provides false) {
                        MvPlayer(source, "MV 重试验证", "") {}
                    }
                }
            }
            label("MV 暂不可用")
            label("重试", focused = true)
            available.set(true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("MV 播放进度", focused = true)
            label("0:30")
            await("MV retry renders video") { true.takeIf { nodes().any { it.text?.toString()?.contains("p · 正在播放") == true } } }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            server.shutdown()
        }
    }

    @Test fun mvSupportsSpeedLoopFillAndLyricsOverlay() = runBlocking<Unit> {
        val server = mvServer(AtomicBoolean(true))
        val source = VideoSource(server.url("/mv.mp4").toString())
        val lines = Lyrics.parse("[00:00]MV 第一句歌词\n[00:15]MV 第二句歌词")
        val activity = start()
        try {
            await("player controller") { activity.controller }
            instrumentation.runOnMainSync {
                activity.resetStream(activity.controller)
                activity.setContent {
                    CompositionLocalProvider(LocalAuraMotion provides false) {
                        MvPlayer(
                            source = source,
                            title = "MV 增强验证",
                            artist = "测试歌手",
                            lyrics = lines,
                        ) {}
                    }
                }
            }
            label("MV 播放进度", focused = true)
            await("MV lyrics off by default") { true.takeIf { nodes().none { it.text?.toString() == "MV 第一句歌词" } } }
            click("更多")
            label("播放倍速")
            await("mv menu focus") { true.takeIf { nodes().any { it.isFocused } } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            label("1.25x")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            label("画面比例")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            label("铺满屏幕")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            label("单曲循环")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            label("参考歌词")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            label("MV 第一句歌词")
            label("单曲循环")
            label("1.25x")
            label("铺满")
        } finally {
            context.getSharedPreferences("mv_progress", 0).edit().remove("pref_fill_screen").apply()
            instrumentation.runOnMainSync { activity.finish() }
            server.shutdown()
        }
    }

    @Test fun detailPrimaryFocusAndExplicitDiscoverRootDoNotKeepAStalePlayerReturn() = runBlocking<Unit> {
        val activity = start()
        val plugin = Plugin("detail-fixture", "", "", JSONObject().put("platform", "详情测试音源"), JSONObject())
        val album = JSONObject().put("id", "album").put("title", "详情焦点验证")
        val song = Song(plugin.id, JSONObject().put("id", "track").put("title", "详情歌曲"))
        suspend fun showDetail() {
            instrumentation.runOnMainSync { activity.page = "home"; activity.playerOpen = true }
            label("播放进度", focused = true)
            instrumentation.runOnMainSync {
                activity.openBrowse(plugin, album, "album")
                activity.browseJob?.cancel()
                activity.browseLoading = true
            }
        }
        try {
            await("player controller") { activity.controller }
            instrumentation.runOnMainSync {
                activity.resetStream(activity.controller)
                activity.resolving?.cancel()
                activity.resumePending = -1
                activity.reduceMotion = true
                activity.playing = false
                activity.playbackPhase = PlaybackPhase.Ready
                activity.queue = listOf(song)
                activity.queueIndex = 0
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            showDetail()
            label("详情焦点验证")
            assertTrue(nodes().filter { it.isFocused }.none { it.text?.toString() == "收藏" })
            instrumentation.runOnMainSync {
                activity.browseSongs = listOf(song)
                activity.browseStatus = ""
                activity.browseLoading = false
            }
            label("播放全部", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("detail returns to its player") { true.takeIf { activity.playerOpen } }
            showDetail()
            label("详情焦点验证")
            click("发现")
            await("explicit discovery root") { true.takeIf { activity.browseItem == null && activity.browseStack.isEmpty() && !activity.playerOpen } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("root Back goes home") { true.takeIf { activity.page == "home" && !activity.playerOpen } }
        } finally {
            instrumentation.runOnMainSync { activity.exitingAfterResumeSave = true; activity.queue = emptyList(); activity.queueIndex = -1; activity.finish() }
        }
    }

    @Test fun subscriptionInfoHasOneCloseActionAndStartsThere() = runBlocking<Unit> {
        val activity = start()
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    CompositionLocalProvider(LocalAuraMotion provides false) {
                        PrismInfoDialog("订阅信息", "本地验证", {}, header = {
                            androidx.tv.material3.Text("测试订阅组")
                        }, focusClose = true) {
                            androidx.tv.material3.Text("这是一条简短的订阅说明。")
                        }
                    }
                }
            }
            label("关闭", focused = true)
            assertEquals(1, nodes().count { it.isClickable && it.contentDescription?.toString() == "关闭" })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun cancellingImportReturnsToTheMenuThenItsOriginalTab() = runBlocking<Unit> {
        val activity = start()
        val storage = object : ContextWrapper(context) {
            private val folder = File(context.cacheDir, "import-return-${UUID.randomUUID()}").apply { mkdirs() }
            override fun getFilesDir() = folder
        }
        try {
            await("player controller") { activity.controller }
            instrumentation.runOnMainSync {
                activity.reduceMotion = true
                activity.library = MusicLibrary(storage)
                activity.libraryViewModel.open("playlists")
                activity.page = "library"
            }
            val tab = generateSequence(label("歌单")) { it.parent }.first { it.isLongClickable }
            assertTrue(tab.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            label("导入歌单")
            await("menu focus") { true.takeIf { nodes().any { it.isFocused } } }
            val action = generateSequence(label("导入歌单")) { it.parent }.first { it.isClickable }
            assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("导入歌单或单曲")
            assertTrue(instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            label("导入歌单", focused = true)
            assertTrue(instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            label("歌单", focused = true)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun closingPlayerReturnsToTheMiniPlayerThatOpenedIt() = runBlocking<Unit> {
        val activity = start()
        try {
            await("player controller") { activity.controller }
            instrumentation.runOnMainSync {
                activity.reduceMotion = true
                activity.queue = listOf(Song("fixture", JSONObject().put("id", "return").put("title", "返回入口验证")))
                activity.queueIndex = 0
                activity.playing = false
                activity.playbackPhase = PlaybackPhase.Ready
                activity.page = "home"
            }
            val opener = generateSequence(label("打开正在播放")) { it.parent }.first { it.isClickable }
            assertTrue(opener.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("player opened") { true.takeIf { activity.playerOpen } }
            label("更多")
            assertTrue(instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            label("打开正在播放", focused = true)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun firstInstallGuideRestoresFocusOnDismissAndOpensAddSource() = runBlocking<Unit> {
        instrumentation.setInTouchMode(false)
        val activity = start()
        val state = activity.plugins.state as MutableStateFlow<PluginsState>
        val original = state.value
        try {
            await("player controller") { activity.controller }
            instrumentation.runOnMainSync {
                activity.reduceMotion = true
                activity.queue = emptyList()
                activity.queueIndex = -1
                state.value = PluginsState()
                activity.page = "home"
            }
            label("添加音源", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            label("我还没有音源", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("添加音源指南")
            label("我有地址，去添加", focused = true)
            assertTrue(instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            label("我还没有音源", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            label("我有地址，去添加", focused = true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("add source dialog opens") { true.takeIf { activity.page == "plugins" } }
            label("插件 .js 或订阅 .json 地址")
        } finally {
            instrumentation.runOnMainSync {
                state.value = original
                activity.finish()
            }
        }
    }

}
