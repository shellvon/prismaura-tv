package tv.prism

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import java.io.File
import java.util.UUID
import android.os.Bundle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CoreTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolatedContext(): Context = object : ContextWrapper(context) {
        private val dir = File(context.cacheDir, "plugin-test-${UUID.randomUUID()}").apply { mkdirs() }
        override fun getFilesDir(): File = dir
    }

    @Test fun searchHistoryMatchesPinyinInitialsAndText() {
        assertEquals(android.os.Build.VERSION.SDK_INT >= 29, matchesSearchHistory("周杰伦", "zjl"))
        assertEquals(android.os.Build.VERSION.SDK_INT >= 29, matchesSearchHistory("周杰伦", "zhoujie"))
        assertTrue(matchesSearchHistory("周杰伦", "杰伦"))
        assertTrue(matchesSearchHistory("Hello", "ell"))
        assertFalse(matchesSearchHistory("周杰伦", "xyz"))
    }

    @Test fun fmDeduplicatesSourcesAndPrefersUnheardTracks() {
        fun song(id: Int, source: String = "one") = Song(source, JSONObject().put("id", id).put("title", "歌曲$id").put("artist", "歌手"))
        val local = (1..100).map { song(it) }
        val full = Discovery.radio(local, (101..200).map { song(it) } + song(1, "two"), kotlin.random.Random(1))
        assertEquals(60, full.size)
        assertEquals(20, full.count { it.data.optInt("id") > 100 })
        assertEquals(full.size, full.map(Discovery::radioIdentity).toSet().size)
        val recent = listOf(Discovery.radioIdentity(song(1)), Discovery.radioIdentity(song(2)))
        val batch = Discovery.radio(local.take(3), listOf(song(1, "two"), song(4)), kotlin.random.Random(1),
            recent = recent, excluded = setOf(Discovery.radioIdentity(song(3))))
        assertEquals(4, batch.first().data.optInt("id"))
        assertFalse(batch.any { it.data.optInt("id") == 3 })
        assertEquals(batch.size, batch.map(Discovery::radioIdentity).toSet().size)
    }

    @Test fun hotSuggestionsValidateObjectsAndKeepTypesSeparate() {
        val items = SearchSuggestions.parse(JSONArray().put("旧字符串").put(JSONObject().put("title", 123))
            .put(JSONObject().put("title", "  热门歌手  ").put("keyword", "搜索词").put("artwork", "file:///private/photo"))
            .put(JSONObject().put("title", "专辑").put("artwork", "https://example.test/cover.png")), "来源")
        assertEquals(2, items.size)
        assertEquals("搜索词", items.first().keyword)
        assertEquals("", items.first().artwork)
        assertEquals("https://example.test/cover.png", items.last().artwork)
        val library = LibraryState(favorites = listOf(Song("p", JSONObject().put("id", "1").put("title", "歌曲名").put("artist", "歌手名").put("album", "专辑名"))))
        assertEquals(listOf("歌手名"), SearchSuggestions.local("artist", library).map { it.title })
        assertEquals(listOf("专辑名"), SearchSuggestions.local("album", library).map { it.title })
        assertTrue(SearchSuggestions.local("sheet", library).isEmpty())
    }

    @Test fun verticalListsNavigateUncomposedRowsAndReturnFromMini() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val libraryField = MainActivity::class.java.getDeclaredField("library").apply { isAccessible = true }
        val originalRepository = libraryField.get(activity) as MusicLibrary
        val repository = MusicLibrary(isolatedContext())
        val state = repository.state as kotlinx.coroutines.flow.MutableStateFlow<LibraryState>
        val original = state.value
        fun nodes(root: android.view.accessibility.AccessibilityNodeInfo?): List<android.view.accessibility.AccessibilityNodeInfo> {
            val result = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            root?.let { result.add(it) }
            var index = 0
            while (index < result.size) {
                val node = result[index++]
                for (i in 0 until node.childCount) node.getChild(i)?.let { result.add(it) }
            }
            return result
        }
        suspend fun awaitNode(description: String, predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo {
            return try {
                withTimeout(5000) {
                    var found: android.view.accessibility.AccessibilityNodeInfo?
                    do {
                        // Resetting the service info clears cached nodes, including focus before a scroll.
                        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
                        found = nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull(predicate)
                        if (found == null) delay(50)
                    } while (found == null)
                    requireNotNull(found)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.cacheDir, "navigation-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                val visible = nodes(instrumentation.uiAutomation.rootInActiveWindow)
                val focus = visible.filter { it.isFocused }.flatMap { nodes(it) }.mapNotNull { it.text ?: it.contentDescription }
                throw AssertionError("Expected $description; focused=$focus; visible=${visible.mapNotNull { it.text }.take(40)}", e)
            }
        }
        suspend fun focused(label: String) = awaitNode("focus on $label") { node ->
            node.isFocused && nodes(node).any { it.text?.toString() == label || it.contentDescription?.toString() == label }
        }
        suspend fun key(code: Int, label: String) {
            instrumentation.sendKeyDownUpSync(code)
            focused(label)
        }
        try {
            instrumentation.runOnMainSync {
                libraryField.set(activity, repository)
                state.value = original.copy(subjects = (0 until 14).map { index ->
                    SavedSubject("fixture", "album", JSONObject().put("id", index).put("title", "验证专辑 $index"))
                })
                MainActivity::class.java.getDeclaredMethod("setPage", String::class.java).apply { isAccessible = true }.invoke(activity, "library")
            }
            val tab = awaitNode("album tab") { node -> node.isFocusable && nodes(node).any { it.text?.toString() == "专辑 14" } }
            assertTrue(tab.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS))
            focused("专辑 14")
            key(android.view.KeyEvent.KEYCODE_DPAD_DOWN, "管理")
            key(android.view.KeyEvent.KEYCODE_DPAD_UP, "专辑 14")
            key(android.view.KeyEvent.KEYCODE_DPAD_DOWN, "管理")
            key(android.view.KeyEvent.KEYCODE_DPAD_DOWN, "验证专辑 0")
            key(android.view.KeyEvent.KEYCODE_DPAD_UP, "管理")
            key(android.view.KeyEvent.KEYCODE_DPAD_DOWN, "验证专辑 0")
            for (i in 1..5) key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, "验证专辑 $i")
            key(android.view.KeyEvent.KEYCODE_DPAD_DOWN, "验证专辑 11")
            key(android.view.KeyEvent.KEYCODE_DPAD_DOWN, "验证专辑 13")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            awaitNode("mini player after albums") { node -> node.isFocused && nodes(node).any {
                it.contentDescription?.toString() in listOf("打开正在播放", "搜索音乐")
            } }
            key(android.view.KeyEvent.KEYCODE_DPAD_UP, "验证专辑 13")
            key(android.view.KeyEvent.KEYCODE_DPAD_UP, "验证专辑 7")
            instrumentation.runOnMainSync {
                fun set(name: String, type: Class<*>, value: Any) {
                    MainActivity::class.java.getDeclaredMethod(name, type).apply { isAccessible = true }.invoke(activity, value)
                }
                set("setSearchTerm", String::class.java, "验证歌曲")
                set("setResults", List::class.java, (0 until 15).map {
                    Song("fixture", JSONObject().put("id", it).put("title", "验证歌曲 $it"))
                })
                set("setHasMore", Boolean::class.javaPrimitiveType!!, false)
                set("setFocusSearchResults", Boolean::class.javaPrimitiveType!!, true)
                set("setPage", String::class.java, "search")
            }
            focused("验证歌曲 0")
            for (i in 1..14) key(android.view.KeyEvent.KEYCODE_DPAD_DOWN, "验证歌曲 $i")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            awaitNode("mini player after songs") { node -> node.isFocused && nodes(node).any {
                it.contentDescription?.toString() in listOf("打开正在播放", "搜索音乐")
            } }
            key(android.view.KeyEvent.KEYCODE_DPAD_UP, "验证歌曲 14")
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("setHasMore", Boolean::class.javaPrimitiveType!!)
                    .apply { isAccessible = true }.invoke(activity, true)
            }
            delay(100) // Let Compose apply the injected pagination state before the next key.
            key(android.view.KeyEvent.KEYCODE_DPAD_DOWN, "加载更多")
            key(android.view.KeyEvent.KEYCODE_DPAD_UP, "验证歌曲 14")
        } finally {
            instrumentation.runOnMainSync { activity.finish(); libraryField.set(activity, originalRepository) }
        }
    }

    @Test fun mvExtensionOnlyAcceptsExplicitNetworkVideoUrls() {
        assertTrue(Song("plugin", JSONObject().put("mvId", "12345")).hasMv)
        assertFalse(Song("plugin", JSONObject().put("mvId", "")).hasMv)
        assertNull(videoSource("https://example.test/video"))
        assertNull(videoSource(JSONObject().put("url", "file:///sdcard/video.mp4")))
        assertNull(videoSource(JSONObject().put("url", "https://user:pass@example.test/video.mp4")))
    }

    @Test fun mvAndQualityContracts() {
        val plugin = Plugin("p", "", "", JSONObject().put("supportedQualities", JSONArray(listOf("128k", "320k", "flac", "hires"))), JSONObject())
        val song = Song("p", JSONObject().put("id", "one").put("mvId", "123").put("qualities", JSONObject().put("128k", JSONObject()).put("flac", JSONObject())))
        assertTrue(song.hasMv)
        assertEquals(listOf("128k", "flac"), qualityOptions(plugin, song))
        assertEquals(listOf("flac", "128k"), qualityAttempts(qualityOptions(plugin, song), "super"))
        assertFalse(Song("p", JSONObject().put("mv", 0)).hasMv)
        val source = videoSource(JSONObject().put("url", "https://example.com/video").put("headers", JSONObject().put("Referer", "https://example.com/").put("Invalid Header", "bad")))!!
        assertEquals("https://example.com/", source.headers["Referer"])
        assertFalse(source.headers.containsKey("Invalid Header"))
        assertNull(videoSource(JSONObject().put("url", "https://user:pass@example.com/video")))
    }

    @Test fun pluginSearchPreservesTrackIdForMediaLookup() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val api = server.url("/api").toString()
            val source = """
                const axios = require('axios');
                module.exports = {
                    platform: 'Mock fixture',
                    async search(query) {
                        const response = await axios.get('$api', {params: {query}});
                        return {data: response.data, isEnd: true};
                    },
                    async getMediaSource(song) {
                        const response = await axios.get('$api', {params: {id: song.id}});
                        return response.data;
                    }
                };
            """.trimIndent()
            val client = PluginClient(context)
            val pluginId = UUID.randomUUID().toString()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""[{"id":"track-1","opaque":"kept","title":"模拟歌曲"}]"""))
            val response = client.call(source, JSONObject(), "search", JSONArray().put("模拟查询").put(1).put("music"), pluginId) as JSONObject
            assertEquals("模拟查询", server.takeRequest(3, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("query"))
            val song = response.getJSONArray("data").getJSONObject(0)
            assertEquals("kept", song.getString("opaque"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"url":"https://media.example.test/track.wav"}"""))
            val media = client.call(source, JSONObject(), "getMediaSource", JSONArray().put(song).put("standard"), pluginId) as JSONObject
            assertEquals("track-1", server.takeRequest(3, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("id"))
            assertEquals("https://media.example.test/track.wav", media.getString("url"))
        } finally { server.shutdown() }
    }

    @Test fun phoneInputAcceptsOneLocalFormAndCloses() {
        val received = CountDownLatch(1)
        var submitted: Map<String, String>? = null
        val phone = PhoneInput(context, listOf("q" to "搜索"), submit = { values -> submitted = values; received.countDown() })
        try {
            val client = OkHttpClient()
            client.newCall(Request.Builder().url(phone.url).build()).execute().use { assertEquals(200, it.code) }
            client.newCall(Request.Builder().url(phone.url).post(FormBody.Builder().add("q", "周杰伦").build()).build()).execute().use { assertEquals(200, it.code) }
            assertTrue(received.await(3, TimeUnit.SECONDS))
            assertEquals("周杰伦", submitted?.get("q"))
        } finally { phone.close() }
    }

    @Test fun phoneRemoteServesLiveStateAndOnlyAcceptsJsonCommands() {
        var lastCommand = ""
        val phone = PhoneInput(context,
            snapshot = { JSONObject().put("title", "晴天").put("theme", "gold").put("playing", true).toString() },
            command = { raw -> lastCommand = raw; JSONObject().put("ok", true).toString() })
        try {
            val client = OkHttpClient()
            client.newCall(Request.Builder().url(phone.url + "/state").build()).execute().use {
                assertEquals(200, it.code)
                val body = JSONObject(it.body!!.string())
                assertEquals("晴天", body.getString("title"))
                assertEquals("gold", body.getString("theme"))
            }
            client.newCall(Request.Builder().url(phone.url + "/action")
                .post("""{"type":"next"}""".toRequestBody("application/json".toMediaType())).build()).execute().use {
                assertEquals(200, it.code)
            }
            assertEquals("next", JSONObject(lastCommand).getString("type"))
            /* A cross-site HTML form must not be able to drive playback. */
            client.newCall(Request.Builder().url(phone.url + "/action").post(FormBody.Builder().add("type", "next").build()).build()).execute().use {
                assertEquals(400, it.code)
            }
        } finally { phone.close() }
    }

    @Test fun cookiesPersistAndAreIsolatedByPlugin() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val url = server.url("/account").toString()
            val pluginId = UUID.randomUUID().toString()
            val script = "const axios=require('axios'); module.exports={platform:'Cookie',async search(){await axios.get('$url');return {data:[]}}}"
            val client = PluginClient(context)
            server.enqueue(MockResponse().setHeader("Set-Cookie", "session=secret; Path=/; Max-Age=3600; HttpOnly").setBody("{}"))
            client.call(script, JSONObject(), "search", JSONArray().put("a"), pluginId)
            server.takeRequest(3, TimeUnit.SECONDS)
            server.enqueue(MockResponse().setBody("{}"))
            client.call(script, JSONObject(), "search", JSONArray().put("b"), pluginId)
            assertEquals("session=secret", server.takeRequest(3, TimeUnit.SECONDS)?.getHeader("Cookie"))
            server.enqueue(MockResponse().setBody("{}"))
            client.call(script, JSONObject(), "search", JSONArray().put("c"), UUID.randomUUID().toString())
            assertNull(server.takeRequest(3, TimeUnit.SECONDS)?.getHeader("Cookie"))
            assertEquals("secret", PluginCookies(context, pluginId).loadForRequest(server.url("/account")).single().value)
            assertFalse(context.getSharedPreferences("plugin_cookies", Context.MODE_PRIVATE).getString(pluginId, "")!!.contains("secret"))
            client.call("", JSONObject(), "clearCookies", pluginId = pluginId)
            server.enqueue(MockResponse().setBody("{}"))
            client.call(script, JSONObject(), "search", JSONArray().put("d"), pluginId)
            assertNull(server.takeRequest(3, TimeUnit.SECONDS)?.getHeader("Cookie"))
        } finally { server.shutdown() }
    }

    @Test fun musicFreeCookieModuleUsesThePluginCookieJar() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val url = server.url("/account").toString()
            val script = """
                const cookies = require('@react-native-cookies/cookies').default;
                const axios = require('axios');
                module.exports = {platform:'CookieModule', async search() {
                    await cookies.setFromResponse('$url', 'manual=hello; Path=/');
                    const saved = await cookies.get('$url');
                    await axios.get('$url');
                    return {data:[{id:saved.manual.value}]};
                }};
            """.trimIndent()
            server.enqueue(MockResponse().setBody("{}"))
            val result = PluginClient(context).call(script, JSONObject(), "search", JSONArray(), UUID.randomUUID().toString()) as JSONObject
            assertEquals("hello", result.getJSONArray("data").getJSONObject(0).getString("id"))
            assertEquals("manual=hello", server.takeRequest(3, TimeUnit.SECONDS)?.getHeader("Cookie"))
        } finally { server.shutdown() }
    }

    @Test fun lyricsRespectOffsetsAndTranslation() {
        val result = Lyrics.parse("[offset:100]\n[00:01.20][00:02.345]原文\n[00:03.0]第二行", "[00:01.30]翻译")
        assertEquals(listOf(1300L, 2445L, 3100L), result.map { it.timeMs })
        assertEquals(listOf("原文", "原文", "第二行"), result.map { it.text })
        assertEquals("翻译", result.first().translation)
        assertNull(result[1].translation)
        assertTrue(result.all { it.words == null })
    }

    @Test fun simultaneousLyricsKeepOriginalTranslationAndPronunciationTogether() {
        val lines = Lyrics.parse("""
            [01:23.45]暗涌
            [01:23.45]Undercurrent
            [01:23.45]am cung
            [01:23.45]暗涌
            [01:25.00]下一句
        """.trimIndent())
        assertEquals(listOf(83450L, 85000L), lines.map { it.timeMs })
        assertEquals("暗涌", lines.first().text)
        assertEquals(listOf("Undercurrent", "am cung"), lines.first().supportingLines)
        assertTrue(lines.last().supportingLines.isEmpty())
    }

    @Test fun simultaneousLyricsPreferTimedOriginalAndDeduplicateSeparateTranslation() {
        val line = Lyrics.parse("[00:01.00]译文\n[00:01.00]<00:01.00>原<00:01.50>文<00:02.00>",
            "[00:01.00]译文\n[00:01.00]注音").single()
        assertEquals("原文", line.text)
        assertEquals(listOf("译文", "注音"), line.supportingLines)
        assertEquals(listOf(1000L, 1500L), line.words!!.map { it.startMs })
    }

    @Test fun ttmlKeepsInlineTranslationRomanizationAndEnglishSpaces() {
        val line = Lyrics.parse("""
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:itunes="http://itunes.apple.com/lyric-ttml-extensions">
              <head><metadata><ttm:title>Not a lyric</ttm:title></metadata></head>
              <body dur="3s"><div begin="00:01.00" end="00:03.00">
                <p begin="00:01.00" end="00:02.00">
                  <span begin="00:01.00" end="00:01.50">Hello</span> <span begin="00:01.50" end="00:02.00">world</span>
                  <span ttm:role="x-translation" xml:lang="zh-Hans">你好，世界</span>
                  <span ttm:role="x-roman">həˈləʊ wɜːld</span>
                </p>
              </div></body>
            </tt>
        """.trimIndent()).single()
        assertEquals(1000L, line.timeMs)
        assertEquals("Hello world", line.text)
        assertEquals("你好，世界", line.translation)
        assertEquals(listOf("你好，世界", "həˈləʊ wɜːld"), line.supportingLines)
        assertEquals(listOf(LyricWord(1000, "Hello ", 1500), LyricWord(1500, "world", 2000)), line.words)
    }

    @Test fun ttmlSupportsRelativeTimingDurationsAndSeparateTranslation() {
        val line = Lyrics.parse("""
            <tt xmlns="http://www.w3.org/ns/ttml"><body begin="1s" dur="3s"><div>
              <p begin="500ms" dur="2s"><span begin="0s" dur="0.5s">Hello </span><span begin="1s" dur="0.5s">world</span></p>
            </div></body></tt>
        """.trimIndent(), "[00:01.60]你好，世界").single()
        assertEquals(1500L, line.timeMs)
        assertEquals(listOf(LyricWord(1500, "Hello ", 2000), LyricWord(2500, "world", 3000)), line.words)
        assertEquals("你好，世界", line.translation)
    }

    @Test fun ttmlResolvesAppleMetadataByLineKeyAndKeepsUntimedLinesPlain() {
        val lines = Lyrics.parse("""
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:i="http://music.apple.com/lyric-ttml-internal">
              <head><metadata><i:iTunesMetadata>
                <i:translations><i:translation xml:lang="zh-Hans"><i:text for="L1">摇滚乐</i:text></i:translation></i:translations>
                <i:transliterations><i:transliteration><i:text for="L1">rock and roll</i:text></i:transliteration></i:transliterations>
              </i:iTunesMetadata></metadata></head>
              <body><div>
                <p i:key="L1" begin="01:00:01.25" end="01:00:02.25">Rock &amp; roll</p>
                <p i:key="L2" begin="01:00:03.25" end="01:00:04.25"><![CDATA[Next <line>]]></p>
              </div></body>
            </tt>
        """.trimIndent())
        assertEquals(listOf(3601250L, 3603250L), lines.map { it.timeMs })
        assertEquals(listOf("Rock & roll", "Next <line>"), lines.map { it.text })
        assertEquals(listOf("摇滚乐", "rock and roll"), lines.first().supportingLines)
        assertTrue(lines.last().supportingLines.isEmpty())
        assertTrue(lines.all { it.words == null })
    }

    @Test fun ttmlKeepsRubyAndBackgroundTextOutOfTheMainLyric() {
        val line = Lyrics.parse("""
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:tts="http://www.w3.org/ns/ttml#styling" xmlns:i="http://music.apple.com/lyric-ttml-internal">
              <body><div><p begin="1" end="2">
                <span tts:ruby="container"><span tts:ruby="base">所</span><span tts:ruby="textContainer"><span tts:ruby="text">しょ</span></span></span>
                <span tts:ruby="container"><span tts:ruby="base">詮</span><span tts:ruby="text">せん</span></span>
                <span ttm:role="x-bg">(oh)</span>
              </p></div></body>
            </tt>
        """.trimIndent()).single()
        assertEquals("所詮", line.text)
        assertEquals(listOf("しょせん", "(oh)"), line.supportingLines)
    }

    @Test fun ttmlRejectsDtdMalformedTimingAndExcessiveNesting() {
        val root = "<tt xmlns=\"http://www.w3.org/ns/ttml\">"
        val invalid = listOf(
            "<!DOCTYPE tt [<!ENTITY external SYSTEM 'file:///prismaura-nonexistent.test'>]>$root<body><p begin='1s' end='2s'>&external;</p></body></tt>",
            "$root<body><p begin='2s' end='1s'>Invalid</p></body></tt>",
            "$root<body><p begin='00:00:01:12'>Unsupported frames</p></body></tt>",
            "$root<body><p begin='${"9".repeat(100_000)}s'>Oversized timestamp</p></body></tt>",
            "$root<body><p begin='1s' end='2s'>" + "<span>".repeat(70) + "Deep" + "</span>".repeat(70) + "</p></body></tt>",
            "<html><body>Not TTML</body></html>",
            "$root<body><p begin='1s' end='2s'>" + "x".repeat(1_000_000) + "</p></body></tt>",
        )
        invalid.forEach { assertTrue(Lyrics.parse(it).isEmpty()) }
    }

    @Test fun bilingualPlayerShowsTranslationAndPronunciationBelowTheOriginal() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context.stopService(Intent(context, PlaybackService::class.java))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val lyrics = Lyrics.parse("[00:00.00]<00:00.00>暗<00:00.50>涌<00:02.00>\n" +
                "[00:00.00]Undercurrent\n[00:00.00]am cung")
            instrumentation.runOnMainSync {
                fun set(name: String, type: Class<*>, value: Any) {
                    MainActivity::class.java.getDeclaredMethod(name, type).apply { isAccessible = true }.invoke(activity, value)
                }
                set("setTheme", String::class.java, "mono")
                set("setLyrics", List::class.java, lyrics)
                set("setPositionMs", Long::class.javaPrimitiveType!!, 0L)
                set("setPlayerOpen", Boolean::class.javaPrimitiveType!!, true)
            }
            withTimeout(5000) {
                while (true) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    val pending = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                    root?.let { pending.add(it) }
                    var original: android.view.accessibility.AccessibilityNodeInfo? = null
                    var supporting: android.view.accessibility.AccessibilityNodeInfo? = null
                    while (pending.isNotEmpty()) {
                        val node = pending.removeFirst()
                        when (node.text?.toString()) {
                            "暗涌" -> original = node
                            "Undercurrent\nam cung" -> supporting = node
                        }
                        for (i in 0 until node.childCount) node.getChild(i)?.let { pending.add(it) }
                    }
                    if (original != null && supporting != null) {
                        val originalBounds = android.graphics.Rect().also { original.getBoundsInScreen(it) }
                        val supportingBounds = android.graphics.Rect().also { supporting.getBoundsInScreen(it) }
                        assertTrue(supportingBounds.top >= originalBounds.bottom)
                        break
                    }
                    delay(50)
                }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun wordTimedLyricsKeepEnglishSpacesAndAbsoluteTimes() {
        val sources = listOf(
            "[00:01.00]<00:01.20> Hello <00:01.60>world <00:02.00>",
            "[1000,1000](1200,400,0) Hello (1600,400,0)world ",
            "[1000,1000] Hello (1200,400)world (1600,400)",
            "[00:01.00]<00:01.20>Hello<00:01.60> world<00:02.00>",
            "[1000,1000](1200,400,0)Hello(1600,400,0) world",
            "[1000,1000]Hello(1200,400) world(1600,400)",
        )
        sources.forEach { source ->
            val line = Lyrics.parse(" \uFEFF\t$source  ").single()
            val words = line.words!!
            assertEquals(1000L, line.timeMs)
            assertEquals("Hello world", line.text)
            assertEquals(line.text, words.joinToString("") { it.text })
            assertEquals(listOf(1200L, 1600L), words.map { it.startMs })
            assertEquals(listOf(1600L, 2000L), words.map { it.endMs })
        }
    }

    @Test fun wordTimedLyricsKeepWhitespaceOnlySegments() {
        val sources = listOf(
            "[00:01.00]<00:01.00>Hello<00:01.30> \t <00:01.50>world<00:02.00>",
            "[1000,1000](1000,300,0)Hello(1300,200,0) \t (1500,500,0)world",
            "[1000,1000]Hello(1000,300) \t (1300,200)world(1500,500)",
        )
        sources.forEach { source ->
            val line = Lyrics.parse(source).single()
            assertEquals("Hello \t world", line.text)
            assertEquals(listOf(LyricWord(1000, "Hello", 1300), LyricWord(1300, " \t ", 1500),
                LyricWord(1500, "world", 2000)), line.words)
            assertEquals(line.text, line.words!!.joinToString("") { it.text })
        }
        listOf("[00:01.00]<00:01.00> <00:02.00>", "[1000,1000](1000,1000,0) ",
            "[1000,1000] (1000,1000)").forEach { assertTrue(Lyrics.parse(it).isEmpty()) }
    }

    @Test fun wordTimedLyricsApplyPositiveAndNegativeOffsets() {
        val sources = listOf(
            "[00:01.00]<00:01.00>Hello <00:01.50>world<00:02.00>",
            "[1000,1000](1000,500,0)Hello (1500,500,0)world",
            "[1000,1000]Hello (1000,500)world(1500,500)",
        )
        sources.forEach { source ->
            listOf(250L, -250L, -1750L, -2500L).forEach { adjustment ->
                val offset = if (adjustment > 0) "+$adjustment" else "$adjustment"
                val line = Lyrics.parse("[offset:$offset]\n$source").single()
                val words = line.words!!
                assertEquals((1000L + adjustment).coerceAtLeast(0), line.timeMs)
                assertEquals(listOf(1000L, 1500L).map { (it + adjustment).coerceAtLeast(0) },
                    words.map { it.startMs })
                assertEquals(listOf(1500L, 2000L).map { (it + adjustment).coerceAtLeast(0) },
                    words.map { it.endMs })
                assertEquals("Hello world", line.text)
                assertEquals(line.text, words.joinToString("") { it.text })
            }
        }
    }

    @Test fun enhancedLyricsShiftRepeatedChorusTimestampsWithOffsets() {
        listOf(250L, -1250L).forEach { adjustment ->
            val lines = Lyrics.parse("[offset:$adjustment]\n" +
                "[00:10.00][00:01.00][00:20.00]<00:10.00>Hello <00:10.50>world<00:11.00>")
            val times = listOf(1000L, 10000L, 20000L)
            assertEquals(times.map { (it + adjustment).coerceAtLeast(0) }, lines.map { it.timeMs })
            lines.zip(times).forEach { (line, time) ->
                assertEquals(listOf(
                    LyricWord((time + adjustment).coerceAtLeast(0), "Hello ", (time + 500 + adjustment).coerceAtLeast(0)),
                    LyricWord((time + 500 + adjustment).coerceAtLeast(0), "world", (time + 1000 + adjustment).coerceAtLeast(0)),
                ), line.words)
                assertEquals("Hello world", line.text)
                assertEquals(line.text, line.words!!.joinToString("") { it.text })
            }
        }
    }

    @Test fun enhancedLyricsUseEmptyTagsAsEndBoundaries() {
        val trailing = Lyrics.parse("[00:01.00]<00:01.00>A<00:02.00>").single()
        assertEquals(listOf(LyricWord(1000, "A", 2000)), trailing.words)
        assertEquals("A", trailing.text)
        val gaps = Lyrics.parse("[00:01.00]<00:01.00>A<00:02.00><00:02.50> <00:03.00>B<00:04.00><00:05.00>  ").single()
        assertEquals(listOf(LyricWord(1000, "A", 2000), LyricWord(2500, " ", 3000),
            LyricWord(3000, "B", 4000)), gaps.words)
        assertEquals("A B", gaps.text)
        assertEquals(gaps.text, gaps.words!!.joinToString("") { it.text })
        val prefix = Lyrics.parse("[00:01.00] Hello <00:01.50>world ").single()
        assertEquals(listOf(LyricWord(1000, "Hello ", 1500), LyricWord(1500, "world")), prefix.words)
        assertEquals("Hello world", prefix.text)
        assertEquals(prefix.text, prefix.words!!.joinToString("") { it.text })
    }

    @Test fun lyricsIgnoreJsonMetadataButKeepTimestampedBracesAndTranslations() {
        val metadata = "\uFEFF  " + """{"t":0,"c":[{"tx":"歌曲信息","t":0},{"tx":"作者","t":500}]}"""
        assertTrue(Lyrics.parse(metadata).isEmpty())
        val yrc = Lyrics.parse("$metadata\n[1000,1000](1000,500,0)Hello (1500,500,0)world",
            "$metadata\n[00:01.00]{译文}").single()
        assertEquals("Hello world", yrc.text)
        assertEquals("{译文}", yrc.translation)
        assertEquals(yrc.text, yrc.words!!.joinToString("") { it.text })
        val jsonText = """{"c":[{"tx":"literal","t":5}]}"""
        val plain = Lyrics.parse("$metadata\n[00:01.00]{}\n[00:02.00]{hello}\n[00:03.00]$jsonText\n{hello}",
            "$metadata\n[00:01.00]{你好}\n[00:02.00]{}\n[00:03.00]{翻译}")
        assertEquals(listOf("{}", "{hello}", jsonText), plain.map { it.text })
        assertEquals(listOf(1000L, 2000L, 3000L), plain.map { it.timeMs })
        assertEquals(listOf("{你好}", "{}", "{翻译}"), plain.map { it.translation })
        assertTrue(plain.all { it.words == null })
    }

    @Test fun plainLyricsKeepSortingPrecisionAndCreditFiltering() {
        val result = Lyrics.parse("""
            [OFFSET:-200]
            [00:03.045][01:02] Hello world
            [00:00.1]零
            [00:02:34]二
            [00:60.00]无效
            [00:04.00]作词/作曲：作者
            \uFEFF [00:05.00]词：作者
            [00:06.00]曲终人散
            [00:07.00]
            没有时间戳
        """.trimIndent().replace("\\uFEFF", "\uFEFF"))
        assertEquals(listOf(0L, 2140L, 2845L, 5800L, 61800L), result.map { it.timeMs })
        assertEquals(listOf("零", "二", "Hello world", "曲终人散", "Hello world"), result.map { it.text })
        assertTrue(result.all { it.words == null && it.translation == null })
    }

    @Test fun wordSweepRespectsGapsAndBackwardSeeks() {
        val first = LyricWord(1000, "Hello ", 1200)
        val second = LyricWord(2000, "world", 2200)
        assertEquals(0f, lyricWordProgress(first, second.startMs, 999))
        assertEquals(.5f, lyricWordProgress(first, second.startMs, 1100))
        assertEquals(1f, lyricWordProgress(first, second.startMs, 1500))
        assertEquals(0f, lyricWordProgress(second, null, 1500))
        assertEquals(.5f, lyricWordProgress(second, null, 2100))
        assertEquals(0f, lyricWordProgress(second, null, 1100))
    }

    @Test fun wordSweepUsesNextStartAndHandlesUnknownOrZeroDuration() {
        assertEquals(.5f, lyricWordProgress(LyricWord(1000, "A"), 2000, 1500))
        assertEquals(.5f, lyricWordProgress(LyricWord(1000, "A"), null, 1200))
        assertEquals(1f, lyricWordProgress(LyricWord(1000, "A", 1000), null, 1001))
    }

    @Test fun updateReminderCanBeSkippedDuringDownloadWithoutCancellingIt() = runBlocking {
        val server = MockWebServer(); server.start()
        val directory = File(context.cacheDir, "update-test-${UUID.randomUUID()}").apply { mkdirs() }
        val storage = object : ContextWrapper(context) { override fun getCacheDir() = directory }
        val repository = "fixture/${UUID.randomUUID()}"
        val mirror = server.url("/").toString().trimEnd('/')
        val metadataUrl = "$mirror/latest.json"
        val prefs = storage.getSharedPreferences("app_updates_${repository.hashCode()}_${mirror.hashCode()}", Context.MODE_PRIVATE)
        val manifest = JSONObject().put("tag_name", "v9999.0.0").put("assets", JSONArray().put(
            JSONObject().put("name", "PrismAura.apk").put("size", 32).put("digest", "sha256:${"0".repeat(64)}")
                .put("browser_download_url", server.url("/PrismAura.apk").toString()),
        ))
        prefs.edit().putLong("attempt", System.currentTimeMillis()).putString("last_url", metadataUrl)
            .putString("body_${metadataUrl.hashCode()}", manifest.toString()).apply()
        val updates = AppUpdates(storage, repository, mirror)
        try {
            updates.check() // Use cached metadata; this test never contacts GitHub.
            assertNotNull(updates.state.value.release)
            server.enqueue(MockResponse().setBody("x".repeat(32)).throttleBody(1, 100, TimeUnit.MILLISECONDS))
            val download = launch(Dispatchers.Default) { updates.download() }
            try {
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                withTimeout(5000) { while (updates.state.value.received < 1) delay(10) }
                val message = updates.state.value.message
                updates.skip()
                updates.setRemind(false)
                withTimeout(5000) { while (updates.state.value.received < 2) delay(10) }
                val state = updates.state.value
                assertTrue(state.skipped)
                assertFalse(state.remind)
                assertTrue(state.downloading)
                assertEquals(message, state.message)
                assertTrue(download.isActive)
            } finally {
                download.cancel(); updates.cancelNetwork(); download.cancelAndJoin()
            }
            val restored = AppUpdates(storage, repository, mirror)
            restored.check()
            assertTrue(restored.state.value.skipped)
            assertFalse(restored.state.value.remind)
        } finally {
            server.shutdown(); prefs.edit().clear().apply(); directory.deleteRecursively()
        }
    }

    @Test fun musicFreeLyricsAndPlaylistImportMethodsAreCallable() = runBlocking {
        val script = """
            module.exports = {
                platform: '网易云音乐',
                async getLyric(song) { return { rawLrc: '[00:01.00]原歌词', translation: '[00:01.00]译文' }; },
                async importMusicSheet(url) { return [{ id: 'track-1', title: '歌单歌曲', source: 'netease', url }]; }
            };
        """.trimIndent()
        val client = PluginClient(context)
        val lyrics = client.call(script, JSONObject(), "getLyric", JSONArray().put(JSONObject().put("id", "track-1")), UUID.randomUUID().toString()) as JSONObject
        assertEquals("[00:01.00]原歌词", lyrics.getString("rawLrc"))
        assertEquals("[00:01.00]译文", lyrics.getString("translation"))
        val playlist = client.call(script, JSONObject(), "importMusicSheet", JSONArray().put("https://music.163.com/playlist/1"), UUID.randomUUID().toString()) as JSONArray
        assertEquals("歌单歌曲", playlist.getJSONObject(0).getString("title"))
        assertEquals("netease", playlist.getJSONObject(0).getString("source"))
    }

    @Test fun platformIdentityRecognizesAliasesAndKeepsUnknownAsOther() {
        assertEquals(MusicPlatform.QQ, MusicPlatform.identify("qqmusic"))
        assertEquals(MusicPlatform.NETEASE, MusicPlatform.identify("wy"))
        assertEquals(MusicPlatform.KUGOU, MusicPlatform.identify("https://www.kugou.com/song/1"))
        assertEquals(MusicPlatform.QISHUI, MusicPlatform.identify("soda"))
        assertEquals(MusicPlatform.KUWO, MusicPlatform.identify("kuwo"))
        assertEquals(MusicPlatform.MIGU, MusicPlatform.identify("migu"))
        assertEquals(MusicPlatform.BILIBILI, MusicPlatform.identify("bilibili"))
        assertEquals(MusicPlatform.NETEASE, MusicPlatform.identify("Net_Easy"))
        assertEquals(MusicPlatform.QQ, MusicPlatform.identify("QQ_music"))
        assertEquals(MusicPlatform.KUWO, MusicPlatform.identify("CooWoo"))
        assertEquals("好听轻音乐", Song("test", JSONObject().put("id", "1"), "好听轻音乐").sourceLabel)
        assertEquals(MusicPlatform.WEBDAV, MusicPlatform.identify("webdav"))
        assertEquals(MusicPlatform.OTHER, MusicPlatform.identify("https://notqq.com/music"))
    }

    @Test fun sourcePlatformDefaultDoesNotOverrideAnExplicitSongPlatform() {
        val id = UUID.randomUUID().toString()
        SourcePlatforms.initialize(context)
        try {
            SourcePlatforms.set(id, MusicPlatform.NETEASE)
            assertEquals(MusicPlatform.NETEASE, Song(id, JSONObject(), "自定义").platform)
            assertEquals(MusicPlatform.QQ, Song(id, JSONObject().put("source", "qqmusic"), "自定义").platform)
            assertEquals(MusicPlatform.QQ, Song(id, JSONObject().put("url", "https://y.qq.com/song/1"), "自定义").platform)
            assertEquals(MusicPlatform.NETEASE, Song(id, JSONObject().put("url", "https://notqq.com/song/1"), "自定义").platform)
            SourcePlatforms.initialize(context)
            assertEquals(MusicPlatform.NETEASE, Song(id, JSONObject(), "自定义").platform)
            SourcePlatforms.set(id, null)
            assertEquals(MusicPlatform.OTHER, Song(id, JSONObject(), "自定义").platform)
        } finally { SourcePlatforms.set(id, null) }
    }

    @Test fun libraryPersistsPluginItemsAndPlaylists() = runBlocking {
        val storage = isolatedContext()
        val song = Song("plugin-1", JSONObject().put("id", "track").put("title", "歌曲").put("custom", "kept"))
        val library = MusicLibrary(storage)
        library.toggleFavorite(song)
        library.record(song)
        library.create("旅途", listOf(song), "netease")
        val restored = MusicLibrary(storage).state.value
        assertEquals("kept", restored.favorites.single().data.getString("custom"))
        assertEquals("plugin-1", restored.playlists.single().songs.single().pluginId)
        assertEquals("netease", restored.playlists.single().platform)
        assertEquals("歌曲", restored.recent.single().title)
        library.removeSong(restored.playlists.single().id, song)
        assertTrue(MusicLibrary(storage).state.value.playlists.single().songs.isEmpty())
    }

    @Test fun songsWithoutArtworkStayWithoutArtworkInHistory() = runBlocking {
        val storage = isolatedContext()
        val data = JSONObject().put("id", "track").put("title", "歌曲")
        val song = Song("plugin", data, "source")
        val library = MusicLibrary(storage)
        library.record(song)
        val restored = MusicLibrary(storage).state.value.recent.single()
        assertEquals("", restored.artwork)
        assertFalse(restored.data.has("artwork"))
    }

    @Test fun pluginSearchAndSubscriptionRollback() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val api = server.url("/search").toString()
            val script = """
                const axios=require('axios');
                module.exports={platform:'测试音源', version:'1', userVariables:[{key:'source'}],
                async search(q){ const r=await axios.get('$api',{params:{name:q,source:env.getUserVariables().source}}); return {data:r.data}; },
                async getMediaSource(song){return {url:song.url,headers:{'X-Prism-Test':'ok'}}}};
            """.trimIndent()
            val client = PluginClient(context)
            val metadata = client.call(script, JSONObject(), "describe") as JSONObject
            assertEquals("测试音源", metadata.getString("platform"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[{\"id\":\"1\",\"title\":\"测试歌\",\"extra\":\"保留\"}]"))
            val found = client.call(script, JSONObject().put("source", "chosen"), "search", JSONArray().put("hello").put(1).put("music")) as JSONObject
            assertEquals("保留", found.getJSONArray("data").getJSONObject(0).getString("extra"))
            val request = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("chosen", request.requestUrl!!.queryParameter("source"))
            server.enqueue(MockResponse().setBody(script))
            val repository = Plugins(isolatedContext(), client)
            repository.importUrl(server.url("/source.js").toString())
            assertEquals("1", repository.state.value.plugins.first { it.name == "测试音源" }.metadata.getString("version"))
            server.enqueue(MockResponse().setResponseCode(500))
            val report = repository.syncAll()
            assertTrue(report.failed)
            assertTrue(report.message.contains("HTTP 500"))
            assertEquals("1", repository.state.value.plugins.first { it.name == "测试音源" }.metadata.getString("version"))
        } finally { server.shutdown() }
    }

    @Test fun manifestSyncKeepsConfigurationAndLastWorkingVersion() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val client = PluginClient(context)
            val repository = Plugins(isolatedContext(), client)
            val manifest = """{"plugins":[{"name":"fixture","url":"../fixture.js"}]}"""
            val v1 = "module.exports={platform:'Subscription',version:'1',userVariables:[{key:'token'}]}"
            val v2 = "module.exports={platform:'Subscription',version:'2',userVariables:[{key:'token'}]}"
            server.enqueue(MockResponse().setBody(manifest)); server.enqueue(MockResponse().setBody(v1))
            val report = repository.importUrl(server.url("/list/index.json").toString())
            assertFalse(report.failed)
            val plugin = repository.state.value.plugins.single()
            repository.setVariables(plugin.id, JSONObject().put("token", "saved"))
            server.enqueue(MockResponse().setBody(manifest)); server.enqueue(MockResponse().setBody(v2))
            assertFalse(repository.syncAll().failed)
            assertEquals("2", repository.state.value.plugins.single().metadata.getString("version"))
            assertEquals("saved", repository.state.value.plugins.single().variables.getString("token"))
            server.enqueue(MockResponse().setBody(manifest)); server.enqueue(MockResponse().setBody("not valid JS @"))
            assertTrue(repository.syncAll().failed)
            assertEquals("2", repository.state.value.plugins.single().metadata.getString("version"))
        } finally { server.shutdown() }
    }

    @Test fun playerAcceptsAudioAndForwardsPluginHeaders() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wave())))
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            lateinit var future: ListenableFuture<MediaController>
            instrumentation.runOnMainSync {
                future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            }
            val controller = future.get(20, TimeUnit.SECONDS)
            val audioUrl = server.url("/audio.wav").toString()
            val ready = CountDownLatch(1)
            instrumentation.runOnMainSync {
                controller.addListener(object : Player.Listener { override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) ready.countDown() } })
                val headers = Bundle().apply { putString("X-Prism-Test", "ok") }
                val metadata = MediaMetadata.Builder().setTitle("WAV").setExtras(Bundle().apply { putBundle("headers", headers) }).build()
                controller.setMediaItem(MediaItem.Builder().setUri(audioUrl).setMediaMetadata(metadata).build())
                controller.prepare(); controller.play()
            }
            assertTrue("播放器未进入 READY", ready.await(25, TimeUnit.SECONDS))
            assertEquals("ok", server.takeRequest(5, TimeUnit.SECONDS)?.getHeader("X-Prism-Test"))
            instrumentation.runOnMainSync { MediaController.releaseFuture(future) }
        } finally { server.shutdown() }
    }

    @Test fun backFromHomeStopsPlayback() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wave(30))))
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            lateinit var future: ListenableFuture<MediaController>
            instrumentation.runOnMainSync { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
            val controller = future.get(20, TimeUnit.SECONDS)
            val audioUrl = server.url("/audio.wav").toString()
            val ready = CountDownLatch(1)
            val stopped = CountDownLatch(1)
            instrumentation.runOnMainSync {
                controller.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) ready.countDown()
                        if (state == Player.STATE_IDLE && ready.count == 0L) stopped.countDown()
                    }
                })
                controller.setMediaItem(MediaItem.fromUri(audioUrl))
                controller.prepare(); controller.play()
            }
            assertTrue("播放器未进入 READY", ready.await(25, TimeUnit.SECONDS))
            Thread.sleep(1000) // Allow the activity's controller to connect before pressing Back.
            // Injected BACK events carry no FLAG_SYSTEM, so Compose's key flow consumes the down
            // event and the platform never reaches the activity's back callback. Drive that
            // callback directly to cover the app's own back handling.
            instrumentation.runOnMainSync { @Suppress("DEPRECATION") activity.onBackPressed() }
            assertTrue("退出首页后播放服务未停止", stopped.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { MediaController.releaseFuture(future); if (!activity.isFinishing) activity.finish() }
        } finally { server.shutdown() }
    }

    @Test fun compoundKeysMigrateAndSurviveLibraryAndResumeStorage() = runBlocking {
        val storage = isolatedContext()
        val plugin = Plugin("compound", "", "", JSONObject().put("primaryKey", JSONArray(listOf("id", "cid"))), JSONObject())
        val first = JSONObject().put("id", "same").put("cid", "first").put("title", "第一首")
        val second = JSONObject().put("id", "same").put("cid", "second").put("title", "第二首")
        val library = MusicLibrary(storage)
        library.toggleFavorite(Song(plugin.id, first)) // An entry saved before compound-key support.
        library.alignPrimaryKeys(listOf(plugin))
        library.toggleFavorite(plugin.song(second))
        val restored = MusicLibrary(storage).state.value.favorites
        assertEquals(2, restored.size)
        assertEquals(setOf(plugin.song(first).key, plugin.song(second).key), restored.map { it.key }.toSet())
        assertEquals(listOf("id", "cid"), restored.first().primaryKey)
        val queue = (0 until 650).map { plugin.song(JSONObject().put("id", "same").put("cid", it)) }
        val resume = ResumeStore(storage)
        resume.write(queue, 455, 12345, radio = true, radioRecent = listOf("recent-track"))
        val snapshot = resume.read()!!
        assertEquals(300, snapshot.songs.size)
        assertEquals(queue[455].key, snapshot.songs[snapshot.index].key)
        assertEquals(12345L, snapshot.position)
        assertTrue(snapshot.radio)
        assertEquals(listOf("recent-track"), snapshot.radioRecent)
    }

    @Test fun pluginUpdatesFollowSrcUrlAndRejectVersionDowngrades() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            assertTrue(compareVersions("1.0.0", "1.0.0-rc.2")!! > 0)
            assertTrue(compareVersions("1.0.0-beta.10", "1.0.0-beta.2")!! > 0)
            assertEquals(0, compareVersions("1.0.0+new", "1.0.0+old"))
            val repository = Plugins(isolatedContext(), PluginClient(context))
            val source = "module.exports={platform:'Version fixture',version:'2.0.0',srcUrl:'/updates/current.js'}"
            repository.importSource(source, server.url("/original.js").toString())
            val plugin = repository.state.value.plugins.single()
            repository.setVariables(plugin.id, JSONObject().put("label", "kept"))
            server.enqueue(MockResponse().setBody(source.replace("2.0.0", "2.1.0")))
            repository.refresh(plugin.id)
            assertEquals("/updates/current.js", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
            server.enqueue(MockResponse().setBody(source.replace("2.0.0", "1.9.0")))
            assertTrue(runCatching { repository.refresh(plugin.id) }.isFailure)
            assertEquals("2.1.0", repository.state.value.plugins.single().metadata.getString("version"))
            assertEquals("kept", repository.state.value.plugins.single().variables.getString("label"))
            val localSource = source.replace("/updates/current.js", server.url("/local-update.js").toString()).replace("2.0.0", "2.2.0")
            val replacement = repository.importSource(localSource).replacements.single()
            repository.confirmReplacement(replacement)
            val local = repository.state.value.plugins.single()
            assertEquals(plugin.id, local.id)
            server.enqueue(MockResponse().setBody(localSource.replace("2.2.0", "2.3.0")))
            repository.refresh(local.id)
            assertEquals("/updates/current.js", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
            assertEquals("/local-update.js", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
            assertEquals(1, repository.state.value.plugins.size)
            assertEquals("2.3.0", repository.state.value.plugins.single().metadata.getString("version"))
        } finally { server.shutdown() }
    }

    @Test fun mediaCacheHonorsPolicyAndInvalidatesChangedConfiguration() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val repository = Plugins(isolatedContext(), PluginClient(context))
            val source = "const axios=require('axios');module.exports={platform:'Cache fixture',cacheControl:'cache',getMediaSource:async function(){return (await axios.get('${server.url("/resolve")}')).data}}"
            repository.importSource(source)
            val plugin = repository.state.value.plugins.single()
            val song = plugin.song(JSONObject().put("id", "one"))
            server.enqueue(MockResponse().setBody("""{"url":"https://media.example.test/first"}"""))
            server.enqueue(MockResponse().setBody("""{"url":"https://media.example.test/second"}"""))
            server.enqueue(MockResponse().setBody("""{"url":"https://media.example.test/third"}"""))
            assertEquals("https://media.example.test/first", repository.mediaSource(plugin, song, "standard")!!.getString("url"))
            assertEquals("https://media.example.test/first", repository.mediaSource(plugin, song, "standard")!!.getString("url"))
            assertEquals(1, server.requestCount)
            assertEquals("https://media.example.test/second", repository.mediaSource(plugin, song, "standard", refresh = true)!!.getString("url"))
            assertEquals("https://media.example.test/second", repository.mediaSource(plugin, song, "standard")!!.getString("url"))
            val configured = plugin.copy(variables = JSONObject().put("label", "new"))
            assertEquals("https://media.example.test/third", repository.mediaSource(configured, song, "standard")!!.getString("url"))
            assertEquals(3, server.requestCount)
            val replacement = repository.importSource(source.replace("cacheControl:'cache'", "cacheControl:'no-store'")).replacements.single()
            repository.confirmReplacement(replacement)
            val fresh = repository.state.value.plugins.single()
            repeat(2) { server.enqueue(MockResponse().setBody("""{"url":"https://media.example.test/uncached"}""")) }
            repeat(2) { repository.mediaSource(fresh, fresh.song(JSONObject().put("id", "one")), "standard") }
            assertEquals(5, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun fetchArrayBufferPreservesBytesThroughAndroidBridge() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(0, 1, 127, -128, -1))))
            val script = "module.exports={platform:'Binary fixture',search:async function(){const body=await (await fetch('${server.url("/bytes")}')).arrayBuffer();return {data:Array.from(new Uint8Array(body))}}}"
            val result = PluginClient(context).call(script, JSONObject(), "search", pluginId = UUID.randomUUID().toString()) as JSONObject
            assertEquals("[0,1,127,128,255]", result.getJSONArray("data").toString())
        } finally { server.shutdown() }
    }

    private fun wave(seconds: Int = 2): ByteArray {
        val samples = 44100 * seconds
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(44100); putInt(88200); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
            repeat(samples) { putShort((kotlin.math.sin(it * 2 * Math.PI * 440 / 44100) * 12000).toInt().toShort()) }
        }.array()
    }
}
