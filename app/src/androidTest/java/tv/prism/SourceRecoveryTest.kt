package tv.prism

import tv.prism.sources.Plugin

import tv.prism.library.LibraryBackup
import tv.prism.library.MusicLibrary
import tv.prism.sources.PluginClient
import tv.prism.sources.Plugins
import tv.prism.playback.ResumeStore
import tv.prism.playback.ActiveQueue
import tv.prism.playback.PlaybackMode
import tv.prism.playback.PlaybackPhase
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import tv.prism.library.Song
import tv.prism.playback.SourceRecovery
import tv.prism.playback.recoveryQualities
import tv.prism.playback.sameRecording

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SourceRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun storage(): Context = object : ContextWrapper(context) {
        private val id = "recovery-${UUID.randomUUID()}"
        private val root = File(context.cacheDir, id).apply { mkdirs() }
        override fun getFilesDir() = root
        override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("$id-$name", mode)
    }

    @Test fun strictRecordingIdentityAndCompleteQualityOrder() {
        fun song(title: String = "验证歌曲", artist: String = "验证歌手", duration: Double = 216.0, album: String = "专辑") =
            Song("test", JSONObject().put("id", "id").put("title", title).put("artist", artist).put("duration", duration).put("album", album))
        val original = song()
        assertTrue(sameRecording(original, song(title = " 验证 歌曲 ", duration = 218.0)))
        assertTrue(sameRecording(original, song(duration = 216_000.0)))
        assertFalse(sameRecording(original, song(title = "验证歌曲 (Live)")))
        assertFalse(sameRecording(original, song(album = "现场演唱会")))
        assertFalse(sameRecording(song(title = "验证歌曲 Live", album = "2000 演唱会"), song(title = "验证歌曲 Live", album = "2020 演唱会")))
        assertFalse(sameRecording(original, song(title = "验证歌曲 伴奏")))
        assertFalse(sameRecording(original, song(artist = "另一位歌手")))
        assertFalse(sameRecording(original, song(duration = 220.0)))
        assertFalse(sameRecording(original, song(duration = 0.0)))
        assertFalse(sameRecording(song(artist = "未知歌手"), song(artist = "未知歌手")))
        assertEquals(listOf("standard", "low", "high", "super"), recoveryQualities(listOf("low", "standard", "high", "super"), "standard"))
    }

    @Test fun fallbackPreferenceDefaultsOffAndRoundTripsThroughBackup() = runBlocking {
        val isolated = storage()
        val prefs = isolated.getSharedPreferences("playback", Context.MODE_PRIVATE)
        val repository = Plugins(isolated, PluginClient(context))
        val library = MusicLibrary(isolated)
        assertFalse(prefs.getBoolean(SourceRecovery.PREFERENCE, false))
        prefs.edit().putBoolean(SourceRecovery.PREFERENCE, true).commit()
        val backup = LibraryBackup.build(isolated, library, repository)
        assertTrue(backup.getJSONObject("preferences").getJSONObject("playback").getBoolean(SourceRecovery.PREFERENCE))
        prefs.edit().putBoolean(SourceRecovery.PREFERENCE, false).commit()
        LibraryBackup.apply(isolated, library, repository, backup)
        assertTrue(prefs.getBoolean(SourceRecovery.PREFERENCE, false))
        backup.getJSONObject("preferences").getJSONObject("playback").remove(SourceRecovery.PREFERENCE)
        LibraryBackup.apply(isolated, library, repository, backup)
        assertFalse(prefs.getBoolean(SourceRecovery.PREFERENCE, false))
    }

    @Test fun backupRejectsInvalidInputAndRollsBackOnWriteFailure() = runBlocking {
        val incoming = storage()
        val incomingLibrary = MusicLibrary(incoming)
        incomingLibrary.create("备份里的歌单")
        val incomingPlugins = Plugins(incoming, PluginClient(context))
        val backup = LibraryBackup.build(incoming, incomingLibrary, incomingPlugins)

        val local = storage()
        val library = MusicLibrary(local)
        library.create("本机歌单")
        val plugins = Plugins(local, PluginClient(context))
        plugins.importSource("module.exports={platform:'保留的音源', getMediaSource(){return {}}}")
        val malformed = JSONObject(backup.toString()).apply { remove("plugins") }
        val malformedType = JSONObject(backup.toString()).put("plugins", JSONObject().put("plugins", "invalid"))
        for (invalid in listOf(malformed, malformedType)) {
            assertTrue(runCatching { LibraryBackup.apply(local, library, plugins, invalid) }.isFailure)
            assertEquals("本机歌单", library.state.value.playlists.single().name)
            assertEquals("保留的音源", plugins.state.value.plugins.single().name)
        }

        val failingPrefs = object : ContextWrapper(local) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val saved = local.getSharedPreferences(name, mode)
                return if (name != "playback") saved else object : android.content.SharedPreferences by saved {
                    override fun edit(): android.content.SharedPreferences.Editor {
                        val editor = saved.edit()
                        return object : android.content.SharedPreferences.Editor by editor {
                            override fun clear(): android.content.SharedPreferences.Editor {
                                editor.clear()
                                return this
                            }

                            override fun commit() = false
                        }
                    }
                }
            }
        }
        assertTrue(runCatching { LibraryBackup.apply(failingPrefs, library, plugins, backup) }.isFailure)
        assertEquals("本机歌单", library.state.value.playlists.single().name)
        assertEquals("本机歌单", MusicLibrary(local).state.value.playlists.single().name)
        assertEquals("保留的音源", plugins.state.value.plugins.single().name)
        assertEquals("保留的音源", Plugins(local, PluginClient(context)).state.value.plugins.single().name)
    }

    @Test fun exportCanBeRestoredWhileLibraryWrites() = runBlocking {
        val local = storage()
        val library = MusicLibrary(local)
        val plugins = Plugins(local, PluginClient(context))
        library.create("大量曲目", (1..1000).map { Song("fixture", JSONObject().put("id", it)) })
        val writes = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            repeat(6) { library.rename(library.state.value.playlists.single().id, "名称 $it") }
        }
        try {
            val backup = LibraryBackup.build(local, library, plugins)
            val restored = storage()
            val restoredLibrary = MusicLibrary(restored)
            LibraryBackup.apply(restored, restoredLibrary, Plugins(restored, PluginClient(context)), JSONObject(backup.toString()))
            assertEquals(1000, restoredLibrary.state.value.playlists.single().songs.size)
        } finally { writes.join() }
    }

    private inner class Fixture : AutoCloseable {
        val store = storage()
        val repository = Plugins(store, PluginClient(context))
        val requests = CopyOnWriteArrayList<String>()
        @Volatile var mode = "fallback"
        private val server = MockWebServer()
        lateinit var a: Plugin
        lateinit var b: Plugin
        private val audio = wave()
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    requests += url.encodedPath + "?" + url.encodedQuery.orEmpty()
                    if (url.encodedPath.startsWith("/search/")) return json(JSONObject().put("data", JSONArray()
                        .put(item("live", "验证歌曲 (Live)"))
                        .put(item("wrong-artist").put("artist", "另一歌手"))
                        .put(item("wrong-duration").put("duration", 90))
                        .put(item("matched"))).put("isEnd", true))
                    if (url.encodedPath.startsWith("/resolve/")) {
                        val id = url.queryParameter("id")
                        val quality = url.queryParameter("quality")
                        val source = url.encodedPath.substringAfterLast('/')
                        if (id != "next" && mode == "unresolvable") return json(JSONObject())
                        val good = id == "next" || mode == "same" && quality == "high" || mode == "fallback" && source == "B"
                        val media = server.url(if (good) "/good.wav" else "/bad.wav").newBuilder()
                            .addQueryParameter("source", source).addQueryParameter("quality", quality).build()
                        val result = json(JSONObject().put("url", media.toString()))
                        return if (id != "next" && mode == "slow") result.setBodyDelay(2, TimeUnit.SECONDS) else result
                    }
                    return if (url.encodedPath == "/good.wav") MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(audio))
                    else MockResponse().setResponseCode(403)
                }
            }
            server.start()
        }
        private fun json(value: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
        fun item(id: String, title: String = "验证歌曲") = JSONObject().put("id", id).put("title", title).put("artist", "验证歌手").put("duration", 30)
        suspend fun install() {
            for (source in listOf("A", "disabled", "B")) repository.importSource("""
                const axios=require('axios');
                module.exports={platform:'Recovery $source',cacheControl:'no-store',supportedQualities:['standard','high'],
                  search:async function(){return (await axios.get('${server.url("/search/$source")}')).data},
                  getMediaSource:async function(song,quality){return (await axios.get('${server.url("/resolve/$source")}',{params:{id:song.id,quality}})).data},
                  getLyric:function(){return {rawLrc:'[00:00]来自 $source 的歌词'}}};
            """.trimIndent())
            val installed = repository.state.value.plugins
            a = installed.first { it.name == "Recovery A" }
            b = installed.first { it.name == "Recovery B" }
            repository.setEnabled(installed.first { it.name == "Recovery disabled" }.id, false)
        }
        override fun close() { server.shutdown() }
    }

    @Test fun recoveryTriesEveryOriginalTierAndSkipsDisabledSources() = runBlocking {
        Fixture().use { f ->
            f.install()
            val original = f.a.song(f.item("original"))
            val recovery = SourceRecovery(f.repository, original, original, "standard")
            assertEquals("standard", recovery.next()!!.quality)
            recovery.failed()
            assertEquals("high", recovery.next()!!.quality)
            recovery.failed()
            val replacement = recovery.next()!!
            assertEquals(f.b.id, replacement.song.pluginId)
            assertEquals("matched", replacement.song.data.getString("id"))
            assertEquals("standard", replacement.quality)
            assertTrue(f.requests.indexOfFirst { it.startsWith("/resolve/A?") && it.contains("quality=high") } <
                f.requests.indexOfFirst { it.startsWith("/search/B?") })
            recovery.failed(); assertEquals("high", recovery.next()!!.quality)
            recovery.failed(); assertNull(recovery.next())
            assertFalse(f.requests.any { it.contains("/disabled") || it.startsWith("/search/A") })
        }
    }

    @Test fun nativePlaybackRecoveryKeepsQueueIdentityAndExistingFailureBehavior() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        Fixture().use { f ->
            f.install()
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            fun player() = activity.controller
            suspend fun await(label: String, check: () -> Boolean) {
                try {
                    withTimeout(35_000) {
                        while (true) {
                            var done = false
                            instrumentation.runOnMainSync { done = check() }
                            if (done) return@withTimeout
                            delay(50)
                        }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    throw AssertionError("$label: status=${activity.status} requests=${f.requests}", e)
                }
            }
            val original = f.a.song(f.item("original"))
            val next = f.a.song(f.item("next", "下一首"))
            await("controller connection") { player() != null }
            val originalLibrary = activity.library
            val originalPlugins = activity.plugins
            val originalResume = activity.resumeStore
            try {
                instrumentation.runOnMainSync {
                    activity.plugins = f.repository
                    activity.library = MusicLibrary(f.store)
                    activity.resumeStore = ResumeStore(f.store)
                }
                fun start(mode: String, enabled: Boolean) {
                    instrumentation.runOnMainSync {
                        player()!!.pause()
                        f.mode = mode; f.requests.clear()
                        activity.setAutoSourceFallback(enabled, false)
                        activity.queue = listOf(original, next)
                        activity.playAt(0, openPlayer = false, startAt = 1L)
                    }
                }
                fun loaded(song: Song) = activity.loadedKey == song.key && player()?.isPlaying == true
                start("same", true)
                await("same-source quality recovery") { loaded(original) && activity.playingQuality == "high" }
                assertFalse(f.requests.any { it.startsWith("/search/") })
                start("fallback", true)
                await("cross-source recovery after HTTP 403") { loaded(original) && activity.playbackSong()?.pluginId == f.b.id }
                instrumentation.runOnMainSync { assertEquals(original.key, activity.queue.first().key) }
                assertFalse(f.requests.any { it.startsWith("/search/A") || it.contains("/disabled") })
                start("unresolvable", true)
                await("recovery exhaustion advances queue") { loaded(next) && activity.queueIndex == 1 }
                assertTrue(f.requests.any { it.startsWith("/search/B") })
                start("unresolvable", false)
                await("disabled recovery retains automatic skip") { loaded(next) && activity.queueIndex == 1 }
                assertFalse(f.requests.any { it.startsWith("/search/") })
                start("httpFail", false)
                await("disabled recovery retains HTTP error behavior") { player()?.playerError != null }
                instrumentation.runOnMainSync { assertEquals(0, activity.queueIndex) }
                assertFalse(f.requests.any { it.startsWith("/search/") })
                val oldTrackId = ActiveQueue.attempt.item!!.mediaId
                start("slow", true)
                await("resolution starts before pause") { f.requests.any { it.startsWith("/resolve/A") } }
                instrumentation.runOnMainSync { activity.togglePlayback() }
                delay(2300)
                instrumentation.runOnMainSync { assertFalse(player()!!.playWhenReady); assertEquals("", activity.loadedKey) }
                assertFalse(f.requests.any { it.startsWith("/search/") })
                start("slow", true)
                await("resolution starts before manual next") { f.requests.any { it.startsWith("/resolve/A") } }
                instrumentation.runOnMainSync { activity.playAt(1, openPlayer = false) }
                await("manual next wins") { loaded(next) }
                delay(2300)
                fun rejectLateEvents(oldId: String) {
                    instrumentation.runOnMainSync {
                        val phase = activity.playbackPhase
                        val position = activity.positionMs
                        val duration = activity.durationMs
                        assertNotEquals(oldId, ActiveQueue.attempt.item?.mediaId)
                        activity.onAttemptEvent(Bundle().apply {
                            putString("mediaId", oldId)
                            putBundle("error", PlaybackException("old connection", null,
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED).toBundle())
                        })
                        for (state in listOf(Player.STATE_ENDED, Player.STATE_READY)) {
                            activity.onAttemptEvent(Bundle().apply {
                                putString("mediaId", oldId)
                                putInt("state", state)
                                putLong("duration", 111L)
                            })
                        }
                        assertEquals(next.key, activity.queue[activity.queueIndex].key)
                        assertEquals(next.key, activity.loadedKey)
                        assertEquals(phase, activity.playbackPhase)
                        assertEquals(position, activity.positionMs)
                        assertEquals(duration, activity.durationMs)
                    }
                }
                rejectLateEvents(oldTrackId)
                for (tier in listOf("high", "standard")) {
                    val previous = ActiveQueue.attempt.item!!.mediaId
                    instrumentation.runOnMainSync { activity.switchQuality(tier) }
                    await("accepted $tier") {
                        activity.qualitySwitching.isEmpty() && activity.playingQuality == tier &&
                            ActiveQueue.attempt.item?.mediaId != previous && activity.playbackPhase == PlaybackPhase.Ready
                    }
                    rejectLateEvents(previous)
                }
                instrumentation.runOnMainSync {
                    activity.controller!!.pause()
                    activity.seekPlayback(9_000)
                    activity.saveResume()
                }
                withTimeout(5_000) {
                    while (activity.resumeStore.read()?.position != 9_000L) delay(30)
                }
                instrumentation.runOnMainSync {
                    activity.seekPlayback(0)
                    activity.saveResume()
                }
                withTimeout(5_000) {
                    while (activity.resumeStore.read()?.position != 0L) delay(30)
                }
                instrumentation.runOnMainSync {
                    activity.playMode = PlaybackMode.SEQUENTIAL
                    activity.seekPlayback(activity.durationMs - 250)
                    activity.resumePlayback()
                }
                await("sequential completion keeps final track selected") {
                    activity.playbackPhase == PlaybackPhase.Finished && activity.queueIndex == 1
                }
                instrumentation.runOnMainSync { activity.togglePlayback() }
                await("play after completion replays final track") { loaded(next) && activity.trustedPosition() < 5_000L }
                instrumentation.runOnMainSync { activity.removeQueueItem(1) }
                await("removing the final track stops at the preceding track") {
                    activity.playbackPhase == PlaybackPhase.Idle && activity.queueIndex == 0 && player()?.isPlaying == false
                }
            } finally {
                instrumentation.runOnMainSync {
                    activity.pauseRecoveryIfPending()
                    activity.exitingAfterResumeSave = true
                    activity.resetStream(player())
                    activity.playbackPhase = PlaybackPhase.Idle
                    activity.queue = emptyList(); activity.queueIndex = -1
                    activity.library = originalLibrary
                    activity.plugins = originalPlugins
                    activity.resumeStore = originalResume
                    activity.finish()
                }
            }
        }
    }

    private fun wave(): ByteArray {
        val samples = 22_050 * 30
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(22_050); putInt(44_100); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }.array()
    }
}
