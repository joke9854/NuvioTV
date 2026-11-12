package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import java.time.OffsetDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val T1 = "2026-03-04T05:06:07.891234+00:00"
private const val T2 = "2026-05-06T07:08:09.123456+00:00"
private const val PAGE_CURSOR_FIRST = 40L

private fun millis(value: String) = OffsetDateTime.parse(value).toInstant().toEpochMilli()

private fun play(at: String?, key: String = "p-${at.hashCode()}") =
    """{"key":"$key","watched_at":${at?.let { "\"$it\"" } ?: "null"},"client_event_id":null}"""

private fun itemJson(
    type: String,
    mediaId: String,
    season: Int? = null,
    episode: Int? = null,
    ids: String = "{}",
    plays: List<String> = emptyList(),
    inLibrary: Boolean = true,
    status: String? = null,
    title: String = "Title $mediaId",
    source: String = "tmdb"
) = """{"type":"$type","source":"$source","media_id":"$mediaId","title":"$title","season_number":${season ?: "null"},""" +
    """"episode_number":${episode ?: "null"},"ids":$ids,"in_library":$inLibrary,"status":${status?.let { "\"$it\"" } ?: "null"},""" +
    """"score":null,"plays":[${plays.joinToString(",")}]}"""

private class FakeFloppySyncStorage : FloppySyncStorage {
    val payloads = mutableMapOf<Int, String>()
    var saves = 0
    var removes = 0
    var failSaveAtCall: Int? = null
    override suspend fun load(profileId: Int) = payloads[profileId]
    override suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit) {
        checkScope()
        saves++
        if (failSaveAtCall == saves) throw java.io.IOException("disk full")
        payloads[profileId] = payload
    }
    override suspend fun remove(profileId: Int, checkScope: () -> Unit) {
        checkScope()
        removes++
        payloads.remove(profileId)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FloppySyncRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    private inner class Fixture(scope: TestScope) {
        val h = FloppyTestHarness().also { it.connect() }
        val storage = FakeFloppySyncStorage()
        val profile = MutableStateFlow(1)
        val testScope = scope
        var snapshotPages: (Long) -> String = { """{"results":[],"next_after":null,"cursor":0}""" }
        var changes: (Long) -> FloppyHttpResponse = { FloppyHttpResponse(200, """{"results":[],"cursor":$it,"has_more":false}""") }
        var playback = """{"pagination":{"next":null},"results":[]}"""
        var deleteStatus = 204
        var translateCapable = true
        var translate: (RecordedFloppyRequest) -> FloppyHttpResponse = { FloppyHttpResponse(404, "") }

        init {
            h.engine.handler = { request ->
                val path = request.path
                when {
                    path.startsWith("/api/v1/sync/connections/") ->
                        FloppyHttpResponse(201, """{"connection":"origin-1","client":"nuvio","instance_key":"x","oldest_sequence":null,"newest_sequence":0}""")
                    path.startsWith("/api/v1/sync/items/snapshot/") ->
                        FloppyHttpResponse(200, snapshotPages(query(path, "after")))
                    path.startsWith("/api/v1/sync/items/changes/") -> changes(query(path, "cursor"))
                    path == "/api/v1/info/" ->
                        FloppyHttpResponse(200, "{\"api_extensions\":{\"cinetrack_episode_translate_v1\":$translateCapable}}")
                    path.endsWith("/episodes/translate/") -> translate(request)
                    path.startsWith("/api/v1/playback/progress/") && request.request.method == FloppyHttpMethod.GET ->
                        FloppyHttpResponse(200, playback)
                    path.startsWith("/api/v1/playback/progress/") -> FloppyHttpResponse(deleteStatus, "")
                    else -> FloppyHttpResponse(404, "")
                }
            }
        }

        fun repository(dispatcherScope: TestScope = testScope, background: CoroutineScope): FloppySyncRepository =
            FloppySyncRepository(
                storage, h.store, h.api, profile, background, { h.now },
                StandardTestDispatcher(dispatcherScope.testScheduler)
            )

        fun requests(prefix: String) = h.engine.requests.filter { it.path.startsWith(prefix) }
    }

    private fun query(path: String, name: String): Long =
        path.substringAfter('?').split('&').first { it.startsWith("$name=") }.substringAfter('=').toLong()

    private fun TestScope.fixture() = Fixture(this)

    private suspend fun FloppySyncRepository.sync() = refresh(TrackingRefreshIntent.USER_INITIATED)

    @Test
    fun `connection is registered once, stored with the snapshot and reused after a restart`() = runTest {
        val f = fixture()
        val repo = f.repository(background = backgroundScope)
        repo.sync()
        repo.sync()
        val posts = f.requests("/api/v1/sync/connections/")
        assertEquals(1, posts.size)
        assertEquals("nuvio", posts[0].body["client"]!!.jsonPrimitive.content)
        assertEquals(f.h.store.installId(), posts[0].body["instance_key"]!!.jsonPrimitive.content)
        assertEquals("Nuvio TV", posts[0].body["label"]!!.jsonPrimitive.content)
        assertEquals("origin-1", repo.currentSnapshot()!!.connection)

        val restarted = f.repository(background = backgroundScope)
        restarted.sync()
        assertEquals(1, f.requests("/api/v1/sync/connections/").size)
        assertEquals("origin-1", json.decodeFromString<FloppySyncSnapshot>(f.storage.payloads[1]!!).connection)
    }

    @Test
    fun `snapshot is paged by next_after, keeps the first cursor and the feed follows it`() = runTest {
        val f = fixture()
        f.snapshotPages = { after ->
            if (after == 0L) {
                """{"results":[${itemJson("movie", "1", ids = """{"imdb":"tt1"}""", plays = listOf(play(T1)))}],"next_after":7,"cursor":$PAGE_CURSOR_FIRST}"""
            } else {
                """{"results":[${itemJson("movie", "2", ids = """{"imdb":"tt2"}""", plays = listOf(play(T2)))}],"next_after":null,"cursor":99}"""
            }
        }
        val repo = f.repository(background = backgroundScope)
        repo.sync()

        val snapshotCalls = f.requests("/api/v1/sync/items/snapshot/")
        assertEquals(listOf("after=0", "after=7"), snapshotCalls.map { it.path.substringAfter('?').substringBefore('&') })
        assertTrue(snapshotCalls.all { it.path.contains("limit=500") })
        assertEquals(PAGE_CURSOR_FIRST, repo.currentSnapshot()!!.cursor)
        assertEquals(setOf("tt1", "tmdb:1", "tt2", "tmdb:2"), repo.currentProjection().watchedMovieIds)
        assertEquals("cursor=$PAGE_CURSOR_FIRST&limit=500", f.requests("/api/v1/sync/items/changes/").single().path.substringAfter('?'))
        val stored = json.decodeFromString<FloppySyncSnapshot>(f.storage.payloads[1]!!)
        assertEquals(PAGE_CURSOR_FIRST, stored.cursor)
        assertEquals(2, stored.items.size)
    }

    @Test
    fun `changes replace an item, remove it only when not in library, and absence is not a delete`() = runTest {
        val f = fixture()
        f.snapshotPages = {
            """{"results":[
                ${itemJson("movie", "1", ids = """{"imdb":"tt1"}""", plays = listOf(play(T1)))},
                ${itemJson("movie", "2", ids = """{"imdb":"tt2"}""", plays = listOf(play(T1)))},
                ${itemJson("movie", "3", ids = """{"imdb":"tt3"}""", plays = listOf(play(T1)))}
            ],"next_after":null,"cursor":10}"""
        }
        val repo = f.repository(background = backgroundScope)
        repo.sync()
        assertEquals(setOf("tt1", "tmdb:1", "tt2", "tmdb:2", "tt3", "tmdb:3"), repo.currentProjection().watchedMovieIds)

        f.changes = {
            FloppyHttpResponse(
                200,
                """{"results":[
                    ${itemJson("movie", "1", ids = """{"imdb":"tt1"}""", plays = listOf(play(T1), play(T2)))},
                    ${itemJson("movie", "2", ids = """{"imdb":"tt2"}""", inLibrary = false)}
                ],"cursor":12,"has_more":false}"""
            )
        }
        f.h.now += 1_000L
        repo.sync()

        val items = repo.currentSnapshot()!!.items.associateBy { it.mediaId }
        assertEquals(2, items["1"]!!.plays.size)
        assertNull(items["2"])
        assertNotNull("tt3 was not mentioned by the page and must stay", items["3"])
        assertEquals(setOf("tt1", "tmdb:1", "tt3", "tmdb:3"), repo.currentProjection().watchedMovieIds)
        assertEquals(12L, repo.currentSnapshot()!!.cursor)
    }

    @Test
    fun `a not-in-library item that still has plays is kept`() = runTest {
        val f = fixture()
        f.snapshotPages = { """{"results":[],"next_after":null,"cursor":10}""" }
        f.changes = {
            FloppyHttpResponse(200, """{"results":[${itemJson("movie", "1", ids = """{"imdb":"tt1"}""", inLibrary = false, plays = listOf(play(T1)))}],"cursor":11,"has_more":false}""")
        }
        val repo = f.repository(background = backgroundScope)
        repo.sync()
        f.h.now += 1_000L
        repo.sync()
        assertEquals(setOf("tt1", "tmdb:1"), repo.currentProjection().watchedMovieIds)
    }

    @Test
    fun `cursor and items are stored together and a failed page keeps the previous cursor`() = runTest {
        val f = fixture()
        f.snapshotPages = {
            """{"results":[${itemJson("movie", "1", ids = """{"imdb":"tt1"}""", plays = listOf(play(T1)))}],"next_after":null,"cursor":10}"""
        }
        val repo = f.repository(background = backgroundScope)
        repo.sync()

        f.changes = { cursor ->
            if (cursor == 10L) {
                FloppyHttpResponse(
                    200,
                    """{"results":[${itemJson("movie", "2", ids = """{"imdb":"tt2"}""", plays = listOf(play(T2)))}],"cursor":20,"has_more":true}"""
                )
            } else FloppyHttpResponse(500, "boom")
        }
        f.h.now += 1_000L
        repo.sync()

        val stored = json.decodeFromString<FloppySyncSnapshot>(f.storage.payloads[1]!!)
        assertEquals(20L, stored.cursor)
        assertEquals(setOf("1", "2"), stored.items.map { it.mediaId }.toSet())
        assertEquals(FloppySyncError.UNAVAILABLE, repo.state.value.error)
        // The next attempt resumes from the cursor of the last applied page.
        f.changes = { FloppyHttpResponse(200, """{"results":[],"cursor":$it,"has_more":false}""") }
        f.h.now += 1_000L
        repo.sync()
        assertEquals("cursor=20&limit=500", f.requests("/api/v1/sync/items/changes/").last().path.substringAfter('?'))
    }

    @Test
    fun `a page that cannot be stored does not advance the cursor`() = runTest {
        val f = fixture()
        f.snapshotPages = { """{"results":[],"next_after":null,"cursor":10}""" }
        val repo = f.repository(background = backgroundScope)
        repo.sync()
        f.changes = {
            FloppyHttpResponse(200, """{"results":[${itemJson("movie", "9", ids = """{"imdb":"tt9"}""", plays = listOf(play(T1)))}],"cursor":30,"has_more":false}""")
        }
        f.storage.failSaveAtCall = f.storage.saves + 1
        f.h.now += 1_000L
        repo.sync()

        assertEquals(10L, json.decodeFromString<FloppySyncSnapshot>(f.storage.payloads[1]!!).cursor)
        assertEquals(10L, repo.currentSnapshot()!!.cursor)
        assertTrue(repo.currentProjection().watchedMovieIds.isEmpty())
    }

    @Test
    fun `cursor_expired discards the cursor and redoes the snapshot`() = runTest {
        val f = fixture()
        var generation = 0
        f.snapshotPages = {
            generation++
            val mediaId = if (generation == 1) "old" else "fresh"
            """{"results":[${itemJson("movie", mediaId, ids = """{"imdb":"tt$mediaId"}""", plays = listOf(play(T1)))}],"next_after":null,"cursor":${generation * 100}}"""
        }
        val repo = f.repository(background = backgroundScope)
        repo.sync()
        assertEquals(setOf("old"), repo.currentSnapshot()!!.items.map { it.mediaId }.toSet())

        var expired = false
        f.changes = { cursor ->
            if (!expired) {
                expired = true
                FloppyHttpResponse(409, """{"code":"cursor_expired","detail":"too old","oldest_sequence":5,"newest_sequence":500}""")
            } else FloppyHttpResponse(200, """{"results":[],"cursor":$cursor,"has_more":false}""")
        }
        f.h.now += 1_000L
        repo.sync()

        assertEquals(2, f.requests("/api/v1/sync/items/snapshot/").size)
        assertEquals(setOf("fresh"), repo.currentSnapshot()!!.items.map { it.mediaId }.toSet())
        assertEquals(200L, repo.currentSnapshot()!!.cursor)
        assertNull(repo.state.value.error)
    }

    @Test
    fun `a conflict that is not cursor_expired is an error and keeps the data`() = runTest {
        val f = fixture()
        f.snapshotPages = {
            """{"results":[${itemJson("movie", "1", ids = """{"imdb":"tt1"}""", plays = listOf(play(T1)))}],"next_after":null,"cursor":10}"""
        }
        val repo = f.repository(background = backgroundScope)
        repo.sync()
        f.changes = { FloppyHttpResponse(409, """{"code":"something_else"}""") }
        f.h.now += 1_000L
        repo.sync()
        assertEquals(1, f.requests("/api/v1/sync/items/snapshot/").size)
        assertEquals(setOf("tt1", "tmdb:1"), repo.currentProjection().watchedMovieIds)
        assertEquals(FloppySyncError.UNAVAILABLE, repo.state.value.error)
    }

    @Test
    fun `a rewatch keeps both plays at their exact instants and never dedupes by coordinates`() = runTest {
        val f = fixture()
        f.snapshotPages = {
            """{"results":[
                ${itemJson("show", "1396", ids = """{"imdb":"tt0903747","tmdb":"1396"}""", status = "in_progress", title = "Breaking Bad")},
                ${itemJson("episode", "1396", season = 1, episode = 2, plays = listOf(play(T1, "episode:1"), play(T2, "episode:2")), title = "Cat's in the Bag")}
            ],"next_after":null,"cursor":10}"""
        }
        val repo = f.repository(background = backgroundScope)
        repo.sync()

        val projection = repo.currentProjection()
        val watched = projection.watchedItems
        assertEquals(listOf(millis(T2), millis(T1)), watched.map { it.watchedAt })
        assertTrue(watched.all { it.contentId == "tt0903747" && it.season == 1 && it.episode == 2 && it.title == "Breaking Bad" })
        assertEquals(setOf(1 to 2), projection.watchedShowEpisodes["tt0903747"])
        assertEquals(setOf(1 to 2), projection.watchedShowEpisodes["tmdb:1396"])
        assertTrue(projection.isWatched("tt0903747", 1, 2))
        assertFalse(projection.isWatched("tt0903747", 1, 3))
        val seed = projection.nextUp(false).single()
        assertEquals(millis(T2), seed.lastWatched)
        assertEquals("tt0903747:1:2", seed.videoId)
        assertEquals("floppy", seed.trackingProviderId)
        // The microsecond-precise instant is persisted as the server sent it.
        assertTrue(f.storage.payloads[1]!!.contains("05:06:07.891234"))
        assertEquals(millis(T1) % 1000, 891L)
    }

    @Test
    fun `content ids prefer imdb, fall back to tmdb, episodes use the show and unresolvable items are skipped`() = runTest {
        val f = fixture()
        f.snapshotPages = {
            """{"results":[
                ${itemJson("movie", "10", ids = """{"imdb":"tt10","tmdb":"10"}""", plays = listOf(play(T1)))},
                ${itemJson("movie", "11", ids = """{"tmdb":"11"}""", plays = listOf(play(T1)))},
                ${itemJson("movie", "12", plays = listOf(play(T1)))},
                ${itemJson("movie", "m-13", source = "mal", plays = listOf(play(T1)))},
                ${itemJson("show", "20", ids = """{"imdb":"tt20","tmdb":"20"}""")},
                ${itemJson("episode", "20", season = 2, episode = 3, plays = listOf(play(T1)))},
                ${itemJson("show", "21", ids = """{"tmdb":"21"}""")},
                ${itemJson("episode", "21", season = 1, episode = 1, plays = listOf(play(T1)))},
                ${itemJson("episode", "22", season = 1, episode = 1, plays = listOf(play(T1)))},
                ${itemJson("episode", "x-30", season = 1, episode = 1, source = "mal", plays = listOf(play(T1)))}
            ],"next_after":null,"cursor":10}"""
        }
        val repo = f.repository(background = backgroundScope)
        repo.sync()

        val projection = repo.currentProjection()
        assertEquals(setOf("tt10", "tmdb:10", "tmdb:11", "tmdb:12"), projection.watchedMovieIds)
        assertEquals(
            setOf("tt20", "tmdb:20", "tmdb:21", "tmdb:22"),
            projection.watchedShowEpisodes.keys
        )
        assertEquals(setOf(2 to 3), projection.watchedShowEpisodes["tt20"])
        assertEquals(setOf("tt20", "tmdb:20"), projection.showIdSiblings["tt20"])
        assertEquals(2, projection.unresolvedCount)
        assertTrue(projection.watchedItems.none { it.contentId.contains("m-13") || it.contentId.contains("x-30") })
    }

    @Test
    fun `continue watching maps seconds to milliseconds, percent and video ids`() = runTest {
        val f = fixture()
        f.snapshotPages = {
            """{"results":[${itemJson("show", "1396", ids = """{"imdb":"tt0903747","tmdb":"1396"}""", title = "Breaking Bad")}],"next_after":null,"cursor":10}"""
        }
        f.playback = """{"pagination":{"next":null},"results":[
            {"media_type":"movie","source":"tmdb","media_id":"603","season_number":null,"episode_number":null,"ids":{"imdb":"tt0133093","tmdb":"603"},
             "title":"The Matrix","series_title":null,"image":"https://img/matrix.jpg","position_seconds":1800,"duration_seconds":7200,
             "completed":false,"updated_at":"$T2"},
            {"media_type":"episode","source":"tmdb","media_id":"1396","season_number":1,"episode_number":2,"ids":{},
             "title":"Cat's in the Bag","series_title":"Breaking Bad","image":"https://img/bb.jpg","position_seconds":600.5,"duration_seconds":2400,
             "completed":false,"updated_at":"$T1"},
            {"media_type":"movie","source":"tmdb","media_id":"700","ids":{"imdb":"tt700"},"title":"Done","position_seconds":10,"duration_seconds":10,
             "completed":true,"updated_at":"$T1"}
        ]}"""
        val repo = f.repository(background = backgroundScope)
        repo.sync()

        val playbackUrl = f.requests("/api/v1/playback/progress/").single().path
        assertTrue(playbackUrl.contains("completed=false") && playbackUrl.contains("media_type=movie,episode"))
        val progress = repo.currentProjection().progress
        assertEquals(2, progress.size)
        val movie = progress.first()
        assertEquals("tt0133093", movie.contentId)
        assertEquals("tt0133093", movie.videoId)
        assertEquals("movie", movie.contentType)
        assertEquals(1_800_000L, movie.position)
        assertEquals(7_200_000L, movie.duration)
        assertEquals(25f, movie.progressPercent!!, 0.001f)
        assertEquals(millis(T2), movie.lastWatched)
        assertEquals("floppy", movie.trackingProviderId)
        val episode = progress.last()
        assertEquals("tt0903747", episode.contentId)
        assertEquals("tt0903747:1:2", episode.videoId)
        assertEquals("Breaking Bad", episode.name)
        assertEquals("Cat's in the Bag", episode.episodeTitle)
        assertEquals(600_500L, episode.position)
        assertEquals(millis(T1), episode.lastWatched)
        assertEquals(episode, repo.currentProjection().episodeProgress("tt0903747")[1 to 2])
        assertEquals(episode, repo.currentProjection().episodeProgress("tmdb:1396")[1 to 2])
    }

    @Test
    fun `a position older than the latest play is not shown, a newer one is`() = runTest {
        val f = fixture()
        f.snapshotPages = {
            """{"results":[${itemJson("movie", "603", ids = """{"imdb":"tt0133093"}""", plays = listOf(play(T2)))}],"next_after":null,"cursor":10}"""
        }
        f.playback = """{"pagination":{"next":null},"results":[
            {"media_type":"movie","source":"tmdb","media_id":"603","ids":{"imdb":"tt0133093"},"title":"The Matrix","position_seconds":60,"duration_seconds":600,"completed":false,"updated_at":"$T1"}
        ]}"""
        val repo = f.repository(background = backgroundScope)
        repo.sync()
        assertTrue(repo.currentProjection().progress.isEmpty())
    }

    @Test
    fun `dropped shows are hidden from progress and next up`() = runTest {
        val f = fixture()
        f.snapshotPages = {
            """{"results":[
                ${itemJson("show", "1396", ids = """{"imdb":"tt0903747"}""", status = "dropped")},
                ${itemJson("episode", "1396", season = 1, episode = 2, plays = listOf(play(T1)))}
            ],"next_after":null,"cursor":10}"""
        }
        val repo = f.repository(background = backgroundScope)
        repo.sync()
        assertTrue(repo.currentProjection().isHidden("tt0903747"))
        assertTrue(repo.currentProjection().nextUp(false).isEmpty())
        assertTrue(repo.currentProjection().isWatched("tt0903747", 1, 2))
    }

    @Test
    fun `clearing a position sends DELETE and re-reads the list`() = runTest {
        val f = fixture()
        val repo = f.repository(background = backgroundScope)
        repo.sync()
        repo.clearPlayback("tt0903747", 1, 2)

        val delete = f.h.engine.requests.single { it.request.method == FloppyHttpMethod.DELETE }
        assertEquals("/api/v1/playback/progress/", delete.path)
        assertEquals("access-one", delete.bearer)
        assertEquals("episode", delete.body["media_type"]!!.jsonPrimitive.content)
        assertEquals("tt0903747", delete.body["ids"]!!.jsonObject["imdb"]!!.jsonPrimitive.content)
        assertEquals(1, delete.body["season_number"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, delete.body["episode_number"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, f.requests("/api/v1/playback/progress/").count { it.request.method == FloppyHttpMethod.GET })
    }

    @Test
    fun `clearing a position without a resolvable id sends nothing`() = runTest {
        val f = fixture()
        val repo = f.repository(background = backgroundScope)
        repo.sync()
        repo.clearPlayback("custom-id", 1, 2)
        assertTrue(f.h.engine.requests.none { it.request.method == FloppyHttpMethod.DELETE })
    }

    @Test
    fun `disconnecting drops the snapshot, cursor and connection`() = runTest {
        val f = fixture()
        f.snapshotPages = {
            """{"results":[${itemJson("movie", "1", ids = """{"imdb":"tt1"}""", plays = listOf(play(T1)))}],"next_after":null,"cursor":10}"""
        }
        val repo = f.repository(background = backgroundScope)
        runCurrent()
        repo.sync()
        assertNotNull(f.storage.payloads[1])

        f.h.store.clearAuth()
        runCurrent()
        repo.ensureLoaded()

        assertNull(f.storage.payloads[1])
        assertNull(repo.currentSnapshot())
        assertTrue(repo.currentProjection().watchedMovieIds.isEmpty())

        // A new sign-in starts from nothing: registers again and takes a fresh snapshot.
        f.h.connect()
        runCurrent()
        repo.sync()
        assertEquals(2, f.requests("/api/v1/sync/connections/").size)
        assertEquals(2, f.requests("/api/v1/sync/items/snapshot/").size)
    }

    @Test
    fun `a different server never reuses a stored snapshot`() = runTest {
        val f = fixture()
        f.snapshotPages = {
            """{"results":[${itemJson("movie", "1", ids = """{"imdb":"tt1"}""", plays = listOf(play(T1)))}],"next_after":null,"cursor":10}"""
        }
        val repo = f.repository(background = backgroundScope)
        repo.sync()
        val stale = json.decodeFromString<FloppySyncSnapshot>(f.storage.payloads[1]!!)
        f.storage.payloads[1] = Json.encodeToString(FloppySyncSnapshot.serializer(), stale.copy(serverUrl = "https://other.example.com"))

        val restarted = f.repository(background = backgroundScope)
        restarted.ensureLoaded()
        assertTrue(restarted.currentSnapshot()!!.items.isEmpty())
        assertNull(restarted.currentSnapshot()!!.cursor)
        assertNull(restarted.currentSnapshot()!!.connection)
    }

    private fun translated(vararg rows: String) =
        FloppyHttpResponse(200, """{"results":[${rows.joinToString(",")}]}""")

    private fun translatedRow(season: Int, episode: Int, tvdbSeason: Int, tvdbEpisode: Int) =
        """{"season":$season,"episode":$episode,"status":"translated","tmdb":null,"absolute":null,""" +
            """"tvdb":{"season":$tvdbSeason,"episode":$tvdbEpisode},"reason":null}"""

    private fun anime(f: Fixture) {
        f.snapshotPages = {
            """{"results":[
                ${itemJson("show", "1", ids = """{"imdb":"tt100","tmdb":"1"}""")},
                ${itemJson("episode", "1", season = 2, episode = 3, plays = listOf(play(T1)))}
            ],"next_after":null,"cursor":10}"""
        }
    }

    @Test
    fun `episodes of an imdb show are projected with the tracker numbers the server returns`() = runTest {
        val f = fixture()
        anime(f)
        f.translate = { translated(translatedRow(2, 3, 1, 27)) }
        val repo = f.repository(background = backgroundScope)
        repo.sync()

        val request = f.requests("/api/v1/media/tv/tmdb/1/episodes/translate/").single()
        assertEquals("tmdb", request.body["from"]!!.jsonPrimitive.content)
        assertEquals(2, request.body["episodes"]!!.jsonArray[0].jsonObject["season"]!!.jsonPrimitive.content.toInt())
        val projection = repo.currentProjection()
        assertEquals(setOf(1 to 27), projection.watchedShowEpisodes["tt100"])
        // The tmdb alias keeps Floppy's own numbers.
        assertEquals(setOf(2 to 3), projection.watchedShowEpisodes["tmdb:1"])
        assertEquals(1, projection.watchedItems.single().season)
        assertEquals(27, projection.watchedItems.single().episode)
        assertEquals(1 to 27, repo.currentSnapshot()!!.trackerEpisodes.values.single().let { it.season to it.episode })

        // Already known: the next sync asks nothing more.
        repo.sync()
        assertEquals(1, f.requests("/api/v1/media/tv/tmdb/1/episodes/translate/").size)
    }

    @Test
    fun `a failed translate keeps the tmdb numbers and still commits the cursor`() = runTest {
        val f = fixture()
        anime(f)
        f.translate = { FloppyHttpResponse(404, "") }
        val repo = f.repository(background = backgroundScope)
        repo.sync()

        assertEquals(10L, repo.currentSnapshot()!!.cursor)
        assertNull(repo.state.value.error)
        assertEquals(setOf(2 to 3), repo.currentProjection().watchedShowEpisodes["tt100"])
        assertTrue(repo.currentSnapshot()!!.trackerEpisodes.isEmpty())
        assertEquals(10L, json.decodeFromString<FloppySyncSnapshot>(f.storage.payloads[1]!!).cursor)
    }

    @Test
    fun `without the capability flag nothing is translated`() = runTest {
        val f = fixture()
        anime(f)
        f.translateCapable = false
        val repo = f.repository(background = backgroundScope)
        repo.sync()

        assertTrue(f.requests("/api/v1/media/tv/").isEmpty())
        assertEquals(setOf(2 to 3), repo.currentProjection().watchedShowEpisodes["tt100"])
    }

    @Test
    fun `tmdb shows are never translated`() = runTest {
        val f = fixture()
        f.snapshotPages = {
            """{"results":[
                ${itemJson("show", "5", ids = """{"tmdb":"5"}""")},
                ${itemJson("episode", "5", season = 2, episode = 3, plays = listOf(play(T1)))}
            ],"next_after":null,"cursor":10}"""
        }
        val repo = f.repository(background = backgroundScope)
        repo.sync()
        assertTrue(f.requests("/api/v1/media/tv/").isEmpty())
        assertEquals(setOf(2 to 3), repo.currentProjection().watchedShowEpisodes["tmdb:5"])
    }

    @Test
    fun `resume positions of an imdb show are projected with tracker numbers`() = runTest {
        val f = fixture()
        f.snapshotPages = {
            """{"results":[${itemJson("show", "1", ids = """{"imdb":"tt100","tmdb":"1"}""")}],"next_after":null,"cursor":10}"""
        }
        f.playback = """{"pagination":{"next":null},"results":[
            {"media_type":"episode","source":"tmdb","media_id":"1","season_number":2,"episode_number":3,"ids":{},
             "title":"E","series_title":"S","position_seconds":60,"duration_seconds":600,"completed":false,"updated_at":"$T1"}
        ]}"""
        f.translate = { translated(translatedRow(2, 3, 1, 27)) }
        val repo = f.repository(background = backgroundScope)
        repo.sync()
        val progress = repo.currentProjection().progress.single()
        assertEquals("tt100", progress.contentId)
        assertEquals(1, progress.season)
        assertEquals(27, progress.episode)
    }

    @Test
    fun `a snapshot stored before tracker numbers existed still decodes`() {
        val old = """{"serverUrl":"https://floppy.example.com","connection":"c","cursor":4,"items":[],"playback":[],"checkedAtEpochMs":1}"""
        val snapshot = json.decodeFromString<FloppySyncSnapshot>(old)
        assertEquals(4L, snapshot.cursor)
        assertTrue(snapshot.trackerEpisodes.isEmpty())
    }
}
