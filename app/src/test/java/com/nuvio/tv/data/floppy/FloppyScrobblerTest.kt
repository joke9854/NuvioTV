package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloppyScrobblerTest {
    private fun harness(): FloppyTestHarness {
        val h = FloppyTestHarness()
        h.connect()
        h.engine.handler = { FloppyHttpResponse(200, """{"action":"start"}""") }
        return h
    }

    private fun scrobbler(h: FloppyTestHarness) = FloppyTrackingScrobbler(h.store, h.api)

    private val movie = TrackingMediaReference(
        TrackingMediaKind.MOVIE, ids = TrackingExternalIds(tmdb = 949, imdb = "tt0113277")
    )
    private val episode = TrackingMediaReference(
        TrackingMediaKind.SHOW, ids = TrackingExternalIds(tmdb = 1396, imdb = "tt0903747", tvdb = "81189"),
        episode = TrackingEpisode(season = 2, number = 5)
    )

    @Test
    fun `movie scrobble body`() = runTest {
        val h = harness()

        scrobbler(h).scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 12.5))

        val request = h.engine.requestsTo("/api/v1/scrobble/").single()
        val body = request.body
        assertEquals("start", body["action"]!!.jsonPrimitive.content)
        assertEquals("movie", body["media_type"]!!.jsonPrimitive.content)
        assertEquals("949", body["ids"]!!.jsonObject["tmdb"]!!.jsonPrimitive.content)
        assertEquals("tt0113277", body["ids"]!!.jsonObject["imdb"]!!.jsonPrimitive.content)
        assertNull(body["ids"]!!.jsonObject["tvdb"])
        assertEquals(12.5, body["progress"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertNull(body["season_number"])
        assertEquals("access-one", request.bearer)
    }

    @Test
    fun `episode scrobble body uses the show ids and coordinates`() = runTest {
        val h = harness()

        scrobbler(h).scrobble(TrackingScrobbleAction.STOP, TrackingScrobbleEvent(episode, 97.0))

        val body = h.engine.requestsTo("/api/v1/scrobble/").single().body
        assertEquals("stop", body["action"]!!.jsonPrimitive.content)
        assertEquals("episode", body["media_type"]!!.jsonPrimitive.content)
        assertEquals("1396", body["ids"]!!.jsonObject["tmdb"]!!.jsonPrimitive.content)
        assertEquals("tt0903747", body["ids"]!!.jsonObject["imdb"]!!.jsonPrimitive.content)
        assertEquals("81189", body["ids"]!!.jsonObject["tvdb"]!!.jsonPrimitive.content)
        assertEquals(2, body["season_number"]!!.jsonPrimitive.content.toInt())
        assertEquals(5, body["episode_number"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `progress is clamped to the 0 to 100 range`() = runTest {
        val h = harness()

        scrobbler(h).scrobble(TrackingScrobbleAction.PAUSE, TrackingScrobbleEvent(movie, 140.0))
        scrobbler(h).scrobble(TrackingScrobbleAction.PAUSE, TrackingScrobbleEvent(movie, -3.0))

        val progress = h.engine.requestsTo("/api/v1/scrobble/").map { it.body["progress"]!!.jsonPrimitive.content.toDouble() }
        assertEquals(listOf(100.0, 0.0), progress)
    }

    @Test
    fun `every event carries a fresh Idempotency-Key`() = runTest {
        val h = harness()

        repeat(2) { scrobbler(h).scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 1.0)) }

        val keys = h.engine.requestsTo("/api/v1/scrobble/").map { it.headers["Idempotency-Key"]!! }
        val pattern = Regex("nuvio:${Regex.escape(h.store.installId())}:[0-9a-f-]{36}")
        assertTrue(keys.all { pattern.matches(it) })
        assertEquals(2, keys.toSet().size)
    }

    @Test
    fun `events without a usable id or episode coordinates send nothing`() = runTest {
        val h = harness()
        val noIds = TrackingMediaReference(TrackingMediaKind.MOVIE, title = "Untitled", ids = TrackingExternalIds(trakt = 5, simkl = 6))
        val showOnly = TrackingMediaReference(TrackingMediaKind.SHOW, ids = TrackingExternalIds(tmdb = 1396))
        val noSeason = episode.copy(episode = TrackingEpisode(season = null, number = 3))

        listOf(noIds, showOnly, noSeason).forEach {
            scrobbler(h).scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(it, 10.0))
        }
        scrobbler(h).scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, Double.NaN))

        assertTrue(h.engine.requests.isEmpty())
    }

    @Test
    fun `signed out scrobbles send nothing`() = runTest {
        val h = harness()
        h.store.clearAuth()

        scrobbler(h).scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 10.0))

        assertTrue(h.engine.requests.isEmpty())
    }
}
