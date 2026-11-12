package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.tracking.TrackingCatalogReference
import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloppyEpisodeTranslateTest {
    private val translatePath = "/api/v1/media/tv/tmdb/1396/episodes/translate/"
    private var counter = 0L

    private class Server(var capable: Boolean = true) {
        var translate: (RecordedFloppyRequest) -> FloppyHttpResponse = { FloppyHttpResponse(404, "") }
    }

    private fun harness(server: Server = Server()): FloppyTestHarness {
        val h = FloppyTestHarness()
        h.connect()
        h.engine.handler = { request ->
            when {
                request.path == "/api/v1/info/" ->
                    FloppyHttpResponse(200, """{"api_extensions":{"cinetrack_episode_translate_v1":${server.capable}}}""")
                request.path.endsWith("/episodes/translate/") -> server.translate(request)
                request.path == "/api/v1/sync/history/" -> {
                    val count = request.body["episodes"]?.jsonArray?.size ?: 0
                    val results = (0 until count).joinToString(",") { """{"client_event_id":"x","type":"episode","outcome":"created"}""" }
                    FloppyHttpResponse(200, """{"results":[$results],"counts":{}}""")
                }
                else -> FloppyHttpResponse(200, """{"action":"start"}""")
            }
        }
        return h
    }

    private fun row(season: Int, episode: Int, status: String, tmdb: Pair<Int, Int>?) =
        """{"season":$season,"episode":$episode,"status":"$status","tmdb":${
            tmdb?.let { """{"season":${it.first},"episode":${it.second}}""" } ?: "null"
        },"absolute":null,"tvdb":null,"reason":null}"""

    /** Answers every asked episode with [status]; "translated" moves it to season 1, episode +100. */
    private fun answering(status: String): (RecordedFloppyRequest) -> FloppyHttpResponse = { request ->
        val rows = request.body["episodes"]!!.jsonArray.joinToString(",") {
            val season = it.jsonObject["season"]!!.jsonPrimitive.content.toInt()
            val episode = it.jsonObject["episode"]!!.jsonPrimitive.content.toInt()
            row(season, episode, status, if (status == "translated") 1 to episode + 100 else null)
        }
        FloppyHttpResponse(200, """{"results":[$rows]}""")
    }

    private fun show(contentId: String = "tt0903747", season: Int = 2, number: Int = 5, tmdb: Long? = 1396) =
        TrackingMediaReference(
            kind = TrackingMediaKind.SHOW,
            ids = TrackingExternalIds(tmdb = tmdb, imdb = "tt0903747"),
            episode = TrackingEpisode(season = season, number = number),
            catalog = TrackingCatalogReference(contentId, "series")
        )

    private fun scrobbler(h: FloppyTestHarness) = FloppyTrackingScrobbler(h.store, h.api)

    private fun writer(h: FloppyTestHarness) =
        FloppyTrackingHistoryWriter(h.store, h.api, { Instant.parse("2026-03-04T05:06:07Z") }, { UUID(0L, ++counter) })

    private suspend fun scrobbled(h: FloppyTestHarness, media: TrackingMediaReference): JsonObject {
        scrobbler(h).scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(media, 1.0))
        return h.engine.requestsTo("/api/v1/scrobble/").last().body
    }

    private fun JsonObject.coords() =
        this["season_number"]!!.jsonPrimitive.content.toInt() to this["episode_number"]!!.jsonPrimitive.content.toInt()

    @Test
    fun `a tracker numbered scrobble is sent with the translated tmdb coordinates`() = runTest {
        val server = Server().apply { translate = answering("translated") }
        val h = harness(server)

        assertEquals(1 to 105, scrobbled(h, show()).coords())

        val request = h.engine.requestsTo(translatePath).single()
        assertEquals("tracker", request.body["from"]!!.jsonPrimitive.content)
        val asked = request.body["episodes"]!!.jsonArray.single().jsonObject
        assertEquals("2", asked["season"]!!.jsonPrimitive.content)
        assertEquals("5", asked["episode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `same, not_found, metadata_unavailable, a 404 and a network error keep the original coordinates`() = runTest {
        for (status in listOf("same", "not_found", "metadata_unavailable")) {
            val server = Server().apply { translate = answering(status) }
            assertEquals(status, 2 to 5, scrobbled(harness(server), show()).coords())
        }
        val notFound = Server().apply { translate = { FloppyHttpResponse(404, "{}") } }
        assertEquals(2 to 5, scrobbled(harness(notFound), show()).coords())
        val offline = Server().apply { translate = { throw IOException("offline") } }
        assertEquals(2 to 5, scrobbled(harness(offline), show()).coords())
    }

    @Test
    fun `tmdb content ids, movies and shows without a tmdb id make no translate call`() = runTest {
        val server = Server().apply { translate = answering("translated") }
        val h = harness(server)

        assertEquals(2 to 5, scrobbled(h, show(contentId = "tmdb:1396")).coords())
        assertEquals(2 to 5, scrobbled(h, show(tmdb = null).copy(ids = TrackingExternalIds(imdb = "tt0903747"))).coords())
        val movie = TrackingMediaReference(
            TrackingMediaKind.MOVIE, ids = TrackingExternalIds(tmdb = 949, imdb = "tt0113277"),
            catalog = TrackingCatalogReference("tt0113277", "movie")
        )
        scrobbled(h, movie)

        assertTrue(h.engine.requests.none { it.path.endsWith("/episodes/translate/") || it.path == "/api/v1/info/" })
    }

    @Test
    fun `a server without the capability flag is never asked to translate`() = runTest {
        val server = Server(capable = false).apply { translate = answering("translated") }
        val h = harness(server)

        assertEquals(2 to 5, scrobbled(h, show()).coords())
        assertTrue(h.engine.requestsTo(translatePath).isEmpty())
    }

    @Test
    fun `start then stop for one episode ask the server once`() = runTest {
        val server = Server().apply { translate = answering("translated") }
        val h = harness(server)

        val scrobbler = scrobbler(h)
        scrobbler.scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(show(), 1.0))
        scrobbler.scrobble(TrackingScrobbleAction.PAUSE, TrackingScrobbleEvent(show(), 50.0))
        scrobbler.scrobble(TrackingScrobbleAction.STOP, TrackingScrobbleEvent(show(), 97.0))

        assertEquals(1, h.engine.requestsTo(translatePath).size)
        assertTrue(h.engine.requestsTo("/api/v1/scrobble/").all { it.body.coords() == 1 to 105 })
        assertEquals(3, h.engine.requestsTo("/api/v1/scrobble/").size)
    }

    @Test
    fun `a failed translate is not cached`() = runTest {
        val server = Server().apply { translate = { FloppyHttpResponse(404, "") } }
        val h = harness(server)

        val scrobbler = scrobbler(h)
        scrobbler.scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(show(), 1.0))
        scrobbler.scrobble(TrackingScrobbleAction.STOP, TrackingScrobbleEvent(show(), 1.0))

        assertEquals(2, h.engine.requestsTo(translatePath).size)
    }

    @Test
    fun `history add translates the whole call once and keeps every event id with one set of coordinates`() = runTest {
        val server = Server().apply { translate = answering("translated") }
        val h = harness(server)
        val items = (1..120).map { TrackingHistoryItem(show(season = 2, number = it), watchedAtEpochMs = 1_700_000_000_000L + it) }

        val result = writer(h).addToHistory(1, items)

        assertEquals(120, result.attemptedCount)
        val translateRequest = h.engine.requestsTo(translatePath).single()
        assertEquals(120, translateRequest.body["episodes"]!!.jsonArray.size)
        val sent = h.engine.requestsTo("/api/v1/sync/history/").flatMap { it.body["episodes"]!!.jsonArray.map { e -> e.jsonObject } }
        assertEquals(120, sent.size)
        assertEquals(120, sent.map { it["client_event_id"]!!.jsonPrimitive.content }.toSet().size)
        sent.forEachIndexed { index, entry ->
            assertEquals(1 to (index + 1 + 100), entry.coords())
        }
    }

    @Test
    fun `a retried history request carries the same event ids and coordinates`() = runTest {
        val server = Server().apply { translate = answering("translated") }
        val h = harness(server)
        val inner = h.engine.handler
        h.engine.handler = { request ->
            when {
                request.path == "/o/token/" -> FloppyHttpResponse(200, tokenBody("access-new", "refresh-new"))
                request.path == "/api/v1/sync/history/" && request.bearer == "access-one" -> FloppyHttpResponse(401, "{}")
                else -> inner(request)
            }
        }

        writer(h).addToHistory(1, listOf(TrackingHistoryItem(show(), watchedAtEpochMs = 1_700_000_000_000L)))

        val posts = h.engine.requestsTo("/api/v1/sync/history/")
        assertEquals(2, posts.size)
        assertEquals(posts[0].body, posts[1].body)
        assertEquals(1 to 105, posts[1].body["episodes"]!!.jsonArray.single().jsonObject.coords())
        assertEquals(1, h.engine.requestsTo(translatePath).size)
    }

    @Test
    fun `history add keeps two episodes as two events`() = runTest {
        val server = Server().apply { translate = answering("same") }
        val h = harness(server)

        writer(h).addToHistory(
            1,
            listOf(
                TrackingHistoryItem(show(number = 5), watchedAtEpochMs = 1_700_000_000_000L),
                TrackingHistoryItem(show(number = 5), watchedAtEpochMs = 1_700_000_100_000L)
            )
        )

        val sent = h.engine.requestsTo("/api/v1/sync/history/").single().body["episodes"]!!.jsonArray
        assertEquals(2, sent.size)
        assertEquals(2, sent.map { it.jsonObject["client_event_id"]!!.jsonPrimitive.content }.toSet().size)
    }

    @Test
    fun `history removal is translated too`() = runTest {
        val server = Server().apply { translate = answering("translated") }
        val h = harness(server)
        h.engine.handler.let { inner ->
            h.engine.handler = { request ->
                if (request.path == "/api/v1/sync/history/remove/") FloppyHttpResponse(200, """{"results":[{"outcome":"removed"}]}""")
                else inner(request)
            }
        }

        writer(h).removeFromHistory(1, listOf(show()))

        val sent = h.engine.requestsTo("/api/v1/sync/history/remove/").single().body["episodes"]!!.jsonArray.single().jsonObject
        assertEquals(1 to 105, sent.coords())
    }
}
