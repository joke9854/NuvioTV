package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloppyHistoryWriterTest {
    private var counter = 0L
    private val fixedNow = Instant.parse("2026-03-04T05:06:07.891234Z")

    private fun writer(h: FloppyTestHarness) = FloppyTrackingHistoryWriter(
        h.store, h.api, { fixedNow }, { UUID(0L, ++counter) }
    )

    private fun harness(outcome: (Int) -> String = { "created" }): FloppyTestHarness {
        val h = FloppyTestHarness()
        h.connect()
        h.engine.handler = { request ->
            val body = request.body
            val count = (body["movies"]?.jsonArray?.size ?: 0) + (body["episodes"]?.jsonArray?.size ?: 0)
            val results = (0 until count).joinToString(",") { """{"client_event_id":"x","type":"movie","outcome":"${outcome(it)}"}""" }
            FloppyHttpResponse(200, """{"results":[$results],"counts":{}}""")
        }
        return h
    }

    private fun movie(tmdb: Long? = 949, imdb: String? = "tt0113277", title: String? = "Heat") = TrackingMediaReference(
        kind = TrackingMediaKind.MOVIE, title = title, ids = TrackingExternalIds(tmdb = tmdb, imdb = imdb)
    )

    private fun episode(season: Int? = 1, number: Int = 2, tmdb: Long? = 1396) = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW, ids = TrackingExternalIds(tmdb = tmdb, tvdb = "81189"),
        episode = TrackingEpisode(season = season, number = number)
    )

    private fun JsonObject.entries(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.map { it.jsonObject }.orEmpty()

    @Test
    fun `watched_at is the exact instant with millisecond precision`() = runTest {
        val h = harness()
        val result = writer(h).addToHistory(
            1,
            listOf(
                TrackingHistoryItem(movie(), 1_700_000_000_123L),
                TrackingHistoryItem(episode(), 1_700_000_005_000L),
                TrackingHistoryItem(episode(number = 3), 1_700_000_005_001L)
            )
        )

        val body = h.engine.requestsTo("/api/v1/sync/history/").single().body
        assertEquals("2023-11-14T22:13:20.123Z", body.entries("movies").single()["watched_at"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("2023-11-14T22:13:25.000Z", "2023-11-14T22:13:25.001Z"),
            body.entries("episodes").map { it["watched_at"]!!.jsonPrimitive.content }
        )
        assertEquals(3, result.attemptedCount)
        assertEquals(0, result.notFoundCount)
    }

    @Test
    fun `items without a time share one stamp taken once per call`() = runTest {
        val h = harness()
        writer(h).addToHistory(1, listOf(TrackingHistoryItem(movie()), TrackingHistoryItem(episode()), TrackingHistoryItem(episode(number = 9))))

        val body = h.engine.requestsTo("/api/v1/sync/history/").single().body
        val stamps = (body.entries("movies") + body.entries("episodes")).map { it["watched_at"]!!.jsonPrimitive.content }
        assertEquals(listOf("2026-03-04T05:06:07.891Z"), stamps.distinct())
    }

    @Test
    fun `event ids carry the install id and are unique per item`() = runTest {
        val h = harness()
        writer(h).addToHistory(1, (1..6).map { TrackingHistoryItem(episode(number = it), 1_700_000_000_000L + it) })

        val ids = h.engine.requestsTo("/api/v1/sync/history/").single().body.entries("episodes")
            .map { it["client_event_id"]!!.jsonPrimitive.content }
        assertEquals(6, ids.toSet().size)
        val pattern = Regex("nuvio:${Regex.escape(h.store.installId())}:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        assertTrue(ids.all { pattern.matches(it) })
    }

    @Test
    fun `payload carries show ids and episode coordinates`() = runTest {
        val h = harness()
        writer(h).addToHistory(1, listOf(TrackingHistoryItem(episode(season = 0, number = 4), 1_700_000_000_000L), TrackingHistoryItem(movie(), 1_700_000_000_000L)))

        val body = h.engine.requestsTo("/api/v1/sync/history/").single().body
        val ep = body.entries("episodes").single()
        assertEquals("1396", ep["ids"]!!.jsonObject["tmdb"]!!.jsonPrimitive.content)
        assertEquals("81189", ep["ids"]!!.jsonObject["tvdb"]!!.jsonPrimitive.content)
        assertEquals(0, ep["season_number"]!!.jsonPrimitive.content.toInt())
        assertEquals(4, ep["episode_number"]!!.jsonPrimitive.content.toInt())
        val mv = body.entries("movies").single()
        assertEquals("949", mv["ids"]!!.jsonObject["tmdb"]!!.jsonPrimitive.content)
        assertEquals("tt0113277", mv["ids"]!!.jsonObject["imdb"]!!.jsonPrimitive.content)
        assertEquals("Heat", mv["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `requests are chunked at fifty items`() = runTest {
        val h = harness()
        val items = (1..120).map { TrackingHistoryItem(episode(number = it), 1_700_000_000_000L + it) }

        val result = writer(h).addToHistory(1, items)

        val requests = h.engine.requestsTo("/api/v1/sync/history/")
        assertEquals(listOf(50, 50, 20), requests.map { it.body.entries("episodes").size })
        val allIds = requests.flatMap { r -> r.body.entries("episodes").map { it["client_event_id"]!!.jsonPrimitive.content } }
        assertEquals(120, allIds.toSet().size)
        assertEquals(120, result.attemptedCount)
    }

    @Test
    fun `whole series marks and items without ids are skipped and not counted`() = runTest {
        val h = harness()
        val wholeSeries = TrackingMediaReference(TrackingMediaKind.SHOW, ids = TrackingExternalIds(tmdb = 1396))
        val anime = TrackingMediaReference(TrackingMediaKind.ANIME, ids = TrackingExternalIds(tmdb = 5))
        val noIds = movie(tmdb = null, imdb = null)
        val noSeason = episode(season = null)

        val result = writer(h).addToHistory(
            1,
            listOf(
                TrackingHistoryItem(wholeSeries, 1L), TrackingHistoryItem(anime, 1L), TrackingHistoryItem(noIds, 1L),
                TrackingHistoryItem(noSeason, 1L), TrackingHistoryItem(movie(), 1_700_000_000_000L)
            )
        )

        val body = h.engine.requestsTo("/api/v1/sync/history/").single().body
        assertEquals(1, body.entries("movies").size)
        assertTrue(body.entries("episodes").isEmpty())
        assertEquals(1, result.attemptedCount)
    }

    @Test
    fun `nothing sendable means no request`() = runTest {
        val h = harness()
        val wholeSeries = TrackingMediaReference(TrackingMediaKind.SHOW, ids = TrackingExternalIds(tmdb = 1396))

        val result = writer(h).addToHistory(1, listOf(TrackingHistoryItem(wholeSeries, 1L)))

        assertEquals(0, result.attemptedCount)
        assertTrue(h.engine.requests.isEmpty())
    }

    @Test
    fun `a different profile or a signed out store sends nothing`() = runTest {
        val h = harness()
        assertEquals(0, writer(h).addToHistory(2, listOf(TrackingHistoryItem(movie(), 1L))).attemptedCount)
        assertEquals(0, writer(h).removeFromHistory(2, listOf(movie())).attemptedCount)
        h.store.clearAuth()
        assertEquals(0, writer(h).addToHistory(1, listOf(TrackingHistoryItem(movie(), 1L))).attemptedCount)
        assertTrue(h.engine.requests.isEmpty())
    }

    @Test
    fun `outcomes are mapped and not_found is counted`() = runTest {
        val h = harness { index -> if (index % 2 == 1) "not_found" else "already_satisfied" }
        val items = (1..4).map { TrackingHistoryItem(movie(tmdb = it.toLong()), 1_700_000_000_000L) }

        val result = writer(h).addToHistory(1, items)

        assertEquals(4, result.attemptedCount)
        assertEquals(2, result.notFoundCount)
        assertFalse(result.isComplete)
    }

    @Test
    fun `an unreadable result body is an invalid response`() = runTest {
        val h = harness()
        h.engine.handler = { FloppyHttpResponse(200, "<html>") }

        expectFloppyFailure<FloppyApiError.InvalidResponse> {
            writer(h).addToHistory(1, listOf(TrackingHistoryItem(movie(), 1L)))
        }
    }

    @Test
    fun `a 401 retry reuses exactly the same body and event ids`() = runTest {
        val h = FloppyTestHarness()
        h.connect()
        var historyCalls = 0
        h.engine.handler = { request ->
            when (request.path) {
                "/o/token/" -> FloppyHttpResponse(200, tokenBody("access-new", "refresh-new"))
                else -> if (historyCalls++ == 0) FloppyHttpResponse(401, "{}")
                else FloppyHttpResponse(200, """{"results":[{"outcome":"created"}],"counts":{}}""")
            }
        }

        writer(h).addToHistory(1, listOf(TrackingHistoryItem(movie(), 1_700_000_000_123L)))

        val calls = h.engine.requestsTo("/api/v1/sync/history/")
        assertEquals(2, calls.size)
        assertEquals(calls[0].request.json, calls[1].request.json)
        assertEquals("access-one", calls[0].bearer)
        assertEquals("access-new", calls[1].bearer)
    }

    @Test
    fun `removal sends all true without times or event ids`() = runTest {
        val h = harness { "removed" }
        val wholeShow = TrackingMediaReference(TrackingMediaKind.SHOW, ids = TrackingExternalIds(tmdb = 1396))

        val result = writer(h).removeFromHistory(1, listOf(movie(), episode(number = 5), wholeShow))

        val body = h.engine.requestsTo("/api/v1/sync/history/remove/").single().body
        val mv = body.entries("movies").single()
        val ep = body.entries("episodes").single()
        assertTrue(mv["all"]!!.jsonPrimitive.boolean)
        assertTrue(ep["all"]!!.jsonPrimitive.boolean)
        assertEquals(5, ep["episode_number"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, ep["season_number"]!!.jsonPrimitive.content.toInt())
        assertNull(mv["watched_at"])
        assertNull(mv["client_event_id"])
        assertEquals("a whole-show reference never removes a show", 2, result.attemptedCount)
    }

    @Test
    fun `removal maps not_found`() = runTest {
        val h = harness { index -> if (index == 0) "not_found" else "removed" }

        val result = writer(h).removeFromHistory(1, listOf(movie(tmdb = 1), movie(tmdb = 2), movie(tmdb = 3)))

        assertEquals(3, result.attemptedCount)
        assertEquals(1, result.notFoundCount)
    }
}
