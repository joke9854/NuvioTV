package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.tracking.TrackingListStatus
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.ListMembershipChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class LibraryTestStorage : FloppySyncStorage {
    val payloads = mutableMapOf<Int, String>()
    override suspend fun load(profileId: Int) = payloads[profileId]
    override suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit) {
        checkScope()
        payloads[profileId] = payload
    }
    override suspend fun remove(profileId: Int, checkScope: () -> Unit) {
        checkScope()
        payloads.remove(profileId)
    }
}

private fun libraryItem(
    type: String,
    mediaId: String,
    status: String?,
    ids: String = "{}",
    inLibrary: Boolean = true,
    season: Int? = null,
    episode: Int? = null,
    title: String = "Title $mediaId",
    image: String? = null
) = """{"type":"$type","source":"tmdb","media_id":"$mediaId","title":"$title","season_number":${season ?: "null"},""" +
    """"image":${image?.let { "\"$it\"" } ?: "null"},""" +
    """"episode_number":${episode ?: "null"},"ids":$ids,"in_library":$inLibrary,"status":${status?.let { "\"$it\"" } ?: "null"},""" +
    """"plays":[]}"""

@OptIn(ExperimentalCoroutinesApi::class)
class FloppyLibraryServiceTest {
    private inner class Fixture(val testScope: TestScope, background: CoroutineScope) {
        val h = FloppyTestHarness().also { it.connect() }
        val storage = LibraryTestStorage()
        var items: List<String> = emptyList()
        var removeCapable = true
        var statusResponse: (RecordedFloppyRequest) -> FloppyHttpResponse = { request ->
            val type = if (request.body.containsKey("movies")) "movie" else "show"
            FloppyHttpResponse(200, """{"results":[{"type":"$type","source":"tmdb","media_id":"1","outcome":"updated"}],"counts":{"updated":1}}""")
        }
        val authenticated = MutableStateFlow(true)

        init {
            h.engine.handler = { request ->
                val path = request.path
                when {
                    path.startsWith("/api/v1/sync/connections/") ->
                        FloppyHttpResponse(201, """{"connection":"origin-1","client":"nuvio","instance_key":"x","oldest_sequence":null,"newest_sequence":0}""")
                    path.startsWith("/api/v1/sync/items/snapshot/") ->
                        FloppyHttpResponse(200, """{"results":[${items.joinToString(",")}],"next_after":null,"cursor":10}""")
                    path.startsWith("/api/v1/sync/items/changes/") ->
                        FloppyHttpResponse(200, """{"results":[],"cursor":10,"has_more":false}""")
                    path.startsWith("/api/v1/playback/progress/") ->
                        FloppyHttpResponse(200, """{"pagination":{"next":null},"results":[]}""")
                    path == "/api/v1/info/" ->
                        FloppyHttpResponse(200, """{"api_extensions":{"sync_status_remove_v1":$removeCapable}}""")
                    path == "/api/v1/sync/status/" -> statusResponse(request)
                    else -> FloppyHttpResponse(404, "")
                }
            }
        }

        val repository = FloppySyncRepository(
            storage, h.store, h.api, MutableStateFlow(1), background, { h.now },
            StandardTestDispatcher(testScope.testScheduler)
        )
        val service = FloppyLibraryService(
            repository, h.store, h.api, FloppyEpisodeTranslator(h.store, h.api), authenticated, { h.now }
        )

        suspend fun sync() = repository.refresh(TrackingRefreshIntent.USER_INITIATED)
        fun requests(path: String) = h.engine.requests.filter { it.path.startsWith(path) }
    }

    private fun TestScope.fixture() = Fixture(this, backgroundScope)

    private fun movie(id: String, imdb: String? = null, tmdb: Int? = null) =
        LibraryEntryInput(itemId = id, itemType = "movie", title = "T", imdbId = imdb, tmdbId = tmdb)

    private fun show(id: String, imdb: String? = null, tmdb: Int? = null) =
        LibraryEntryInput(itemId = id, itemType = "series", title = "T", imdbId = imdb, tmdbId = tmdb)

    private fun desire(key: String?) = ListMembershipChanges(
        floppyLibraryStatusDefinitions.associate { it.key to (it.key == key) }
    )

    @Test
    fun `snapshot becomes entries with the status mapping and skips episodes and items outside the library`() = runTest {
        val f = fixture()
        f.items = listOf(
            libraryItem("movie", "1", "planning", ids = """{"imdb":"tt1"}"""),
            libraryItem("show", "2", "in_progress", ids = """{"imdb":"tt2"}"""),
            libraryItem("show", "3", "paused", ids = """{"imdb":"tt3"}"""),
            libraryItem("movie", "4", "completed", ids = """{"imdb":"tt4"}"""),
            libraryItem("movie", "5", "dropped", ids = """{"imdb":"tt5"}"""),
            libraryItem("episode", "2", "completed", ids = """{"imdb":"tt2"}""", season = 1, episode = 1),
            libraryItem("movie", "6", "planning", ids = """{"imdb":"tt6"}""", inLibrary = false),
            libraryItem("movie", "7", null, ids = """{"imdb":"tt7"}""")
        )
        f.sync()

        val byId = f.service.items.first().associateBy { it.id }
        assertEquals(setOf("tt1", "tt2", "tt3", "tt4", "tt5"), byId.keys)
        assertEquals(setOf("floppy:status:plantowatch"), byId.getValue("tt1").listKeys)
        assertEquals(setOf("floppy:status:watching"), byId.getValue("tt2").listKeys)
        assertEquals(setOf("floppy:status:hold"), byId.getValue("tt3").listKeys)
        assertEquals(setOf("floppy:status:completed"), byId.getValue("tt4").listKeys)
        assertEquals(setOf("floppy:status:dropped"), byId.getValue("tt5").listKeys)
        assertEquals("movie", byId.getValue("tt1").type)
        assertEquals("series", byId.getValue("tt2").type)
        assertEquals("floppy", byId.getValue("tt1").trackingProviderId)
        assertEquals(null, byId.getValue("tt1").poster)

        assertEquals(
            floppyLibraryStatusDefinitions.map { it.trackingStatus },
            listOf(
                TrackingListStatus.WATCHING, TrackingListStatus.PLAN_TO_WATCH, TrackingListStatus.ON_HOLD,
                TrackingListStatus.COMPLETED, TrackingListStatus.DROPPED
            )
        )
        assertEquals(
            listOf("in_progress", "planning", "paused", "completed", "dropped"),
            floppyLibraryStatusDefinitions.map { it.wire }
        )
        val tabs = f.service.tabs.first()
        assertEquals(5, tabs.size)
        assertEquals(LibraryListTab.Type.WATCHLIST, tabs.single { it.key == "floppy:status:plantowatch" }.type)
        assertTrue(f.service.recognizesListKey("floppy:status:hold"))
        assertFalse(f.service.recognizesListKey("simkl:status:hold"))
        assertEquals(setOf("floppy:status:watching"), f.service.observeMembership("tt2", "series").first())
        assertEquals(emptySet<String>(), f.service.observeMembership("tt7", "movie").first())
    }

    @Test
    fun `content id prefers imdb, falls back to tmdb`() = runTest {
        val f = fixture()
        f.items = listOf(
            libraryItem("movie", "10", "planning", ids = """{"imdb":"tt10","tmdb":"10"}"""),
            libraryItem("movie", "11", "planning", ids = """{"tmdb":"11"}"""),
            libraryItem("movie", "12", "planning", ids = "{}")
        )
        f.sync()
        assertEquals(setOf("tt10", "tmdb:11", "tmdb:12"), f.service.items.first().map { it.id }.toSet())
    }

    @Test
    fun `moving a title sends only its status, ids and snapshot reference`() = runTest {
        val f = fixture()
        f.items = listOf(libraryItem("show", "20", "planning", ids = """{"imdb":"tt20","tmdb":"20"}"""))
        f.sync()

        f.service.applyMembershipChanges(show("tt20"), desire("floppy:status:dropped"))

        val post = f.requests("/api/v1/sync/status/").single()
        val entry = post.body["shows"]!!.jsonArray.single().jsonObject
        assertEquals("dropped", entry["status"]!!.jsonPrimitive.content)
        assertEquals("tt20", entry["ids"]!!.jsonObject["imdb"]!!.jsonPrimitive.content)
        assertEquals("20", entry["ids"]!!.jsonObject["tmdb"]!!.jsonPrimitive.content)
        assertEquals("tmdb", entry["source"]!!.jsonPrimitive.content)
        assertEquals("20", entry["media_id"]!!.jsonPrimitive.content)
        assertFalse(post.body.containsKey("movies"))
        // The change is visible at once, even though the refreshed snapshot still says planning.
        assertEquals(setOf("floppy:status:dropped"), f.service.observeMembership("tt20", "series").first())
        assertTrue(f.service.getMembershipSnapshot(show("tt20")).listMembership.getValue("floppy:status:dropped"))
    }

    @Test
    fun `posters come from the snapshot and only http urls are used`() = runTest {
        val f = fixture()
        f.items = listOf(
            libraryItem("movie", "40", "planning", ids = """{"imdb":"tt40"}""", image = "https://image.tmdb.org/t/p/w500/a.jpg"),
            libraryItem("movie", "41", "planning", ids = """{"imdb":"tt41"}"""),
            libraryItem("movie", "42", "planning", ids = """{"imdb":"tt42"}""", image = "/relative.jpg")
        )
        f.sync()
        val posters = f.service.items.first().associate { it.id to it.poster }
        assertEquals("https://image.tmdb.org/t/p/w342/a.jpg", posters["tt40"])
        assertEquals(null, posters["tt41"])
        assertEquals(null, posters["tt42"])
    }

    @Test
    fun `a title added from Nuvio keeps Nuvio's poster until the snapshot has one`() = runTest {
        val f = fixture()
        f.sync()
        f.service.applyMembershipChanges(
            LibraryEntryInput(itemId = "tt31", itemType = "movie", title = "T", poster = "https://example.org/p.jpg"),
            desire("floppy:status:plantowatch")
        )
        assertEquals("https://example.org/p.jpg", f.service.items.first().single { it.id == "tt31" }.poster)
    }

    @Test
    fun `a title that is not in the library yet is added by its content id`() = runTest {
        val f = fixture()
        f.sync()
        f.service.applyMembershipChanges(movie("tt30"), desire("floppy:status:plantowatch"))
        val entry = f.requests("/api/v1/sync/status/").single().body["movies"]!!.jsonArray.single().jsonObject
        assertEquals("planning", entry["status"]!!.jsonPrimitive.content)
        assertEquals("tt30", entry["ids"]!!.jsonObject["imdb"]!!.jsonPrimitive.content)
        assertEquals(setOf("floppy:status:plantowatch"), f.service.observeMembership("tt30", "movie").first())
    }

    @Test
    fun `completed sends only the status and never touches history`() = runTest {
        val f = fixture()
        f.items = listOf(libraryItem("show", "40", "in_progress", ids = """{"imdb":"tt40"}"""))
        f.sync()

        f.service.applyMembershipChanges(show("tt40"), desire("floppy:status:completed"))

        assertEquals("completed", f.requests("/api/v1/sync/status/").single().body["shows"]!!.jsonArray.single()
            .jsonObject["status"]!!.jsonPrimitive.content)
        assertTrue(f.requests("/api/v1/sync/history").isEmpty())
        assertTrue(f.h.engine.requests.none { it.path.contains("history") })
    }

    @Test
    fun `removal sends none when the server advertises it`() = runTest {
        val f = fixture()
        f.items = listOf(libraryItem("movie", "50", "planning", ids = """{"imdb":"tt50"}"""))
        f.sync()
        f.statusResponse = { FloppyHttpResponse(200, """{"results":[{"type":"movie","outcome":"removed","removed_count":1}],"counts":{"removed":1}}""") }

        f.service.applyMembershipChanges(movie("tt50"), desire(null))

        val entry = f.requests("/api/v1/sync/status/").single().body["movies"]!!.jsonArray.single().jsonObject
        assertEquals("none", entry["status"]!!.jsonPrimitive.content)
        assertEquals(emptySet<String>(), f.service.observeMembership("tt50", "movie").first())
    }

    @Test
    fun `removal sends nothing and fails clearly when the capability is absent`() = runTest {
        val f = fixture()
        f.items = listOf(libraryItem("movie", "51", "planning", ids = """{"imdb":"tt51"}"""))
        f.removeCapable = false
        f.sync()

        val error = expectFloppyFailure<FloppyLibraryRemovalUnsupportedException> {
            f.service.applyMembershipChanges(movie("tt51"), desire(null))
        }
        assertTrue(error.message!!.contains("cannot remove"))
        assertTrue(f.requests("/api/v1/sync/status/").isEmpty())
        assertEquals(setOf("floppy:status:plantowatch"), f.service.observeMembership("tt51", "movie").first())
    }

    @Test
    fun `a has_history conflict surfaces the typed error and no history call is made`() = runTest {
        val f = fixture()
        f.items = listOf(libraryItem("show", "60", "in_progress", ids = """{"imdb":"tt60"}"""))
        f.sync()
        f.statusResponse = { FloppyHttpResponse(200, """{"results":[{"type":"show","outcome":"conflict","reason":"has_history"}]}""") }

        val error = expectFloppyFailure<FloppyLibraryHistoryConflictException> {
            f.service.applyMembershipChanges(show("tt60"), desire(null))
        }
        assertTrue(error.message!!.contains("Dropped"))
        assertEquals(1, f.requests("/api/v1/sync/status/").size)
        assertTrue(f.h.engine.requests.none { it.path.contains("history") })
        assertEquals(setOf("floppy:status:watching"), f.service.observeMembership("tt60", "series").first())
    }

    @Test
    fun `a failed outcome and an http error are errors and leave the membership alone`() = runTest {
        val f = fixture()
        f.items = listOf(libraryItem("movie", "70", "planning", ids = """{"imdb":"tt70"}"""))
        f.sync()
        f.statusResponse = { FloppyHttpResponse(200, """{"results":[{"type":"movie","outcome":"failed","reason":"provider_unavailable"}]}""") }
        expectFloppyFailure<FloppyLibraryWriteException> {
            f.service.applyMembershipChanges(movie("tt70"), desire("floppy:status:dropped"))
        }
        f.statusResponse = { FloppyHttpResponse(503, "") }
        expectFloppyFailure<FloppyApiError.Server> {
            f.service.applyMembershipChanges(movie("tt70"), desire("floppy:status:dropped"))
        }
        assertEquals(setOf("floppy:status:plantowatch"), f.service.observeMembership("tt70", "movie").first())
    }

    @Test
    fun `a successful write requests an invalidated refresh despite the automatic throttle`() = runTest {
        val f = fixture()
        f.items = listOf(libraryItem("movie", "80", "planning", ids = """{"imdb":"tt80"}"""))
        f.sync()
        val before = f.requests("/api/v1/sync/items/changes/").size
        f.h.now += 1_000L

        f.service.refresh(TrackingRefreshIntent.AUTOMATIC)
        assertEquals("automatic refresh is throttled", before, f.requests("/api/v1/sync/items/changes/").size)

        f.service.applyMembershipChanges(movie("tt80"), desire("floppy:status:dropped"))
        assertEquals(before + 1, f.requests("/api/v1/sync/items/changes/").size)
    }
}
