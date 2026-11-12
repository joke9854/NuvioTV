package com.nuvio.tv.data.floppy

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloppyOptimisticProgressTest {
    private var clock = 1_000_000L
    private val overlay = FloppyOptimisticProgress(ttlMs = 60_000L, now = { clock })

    private fun progress(
        contentId: String = "tt0903747",
        season: Int? = 2,
        episode: Int? = 5,
        position: Long,
        lastWatched: Long
    ) = WatchProgress(
        contentId = contentId,
        contentType = "series",
        name = "Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "$contentId:$season:$episode",
        season = season,
        episode = episode,
        episodeTitle = null,
        position = position,
        duration = 3_000_000L,
        lastWatched = lastWatched
    )

    @Test
    fun `a newer local position replaces the server's older one`() {
        overlay.put(progress(position = 1_200_000L, lastWatched = 500L))

        val merged = overlay.merge(listOf(progress(position = 60_000L, lastWatched = 100L)))

        assertEquals(listOf(1_200_000L), merged.map { it.position })
    }

    @Test
    fun `the server wins once it reports the same item as new or newer`() {
        overlay.put(progress(position = 1_200_000L, lastWatched = 500L))

        val merged = overlay.merge(listOf(progress(position = 1_210_000L, lastWatched = 500L)))

        assertEquals(listOf(1_210_000L), merged.map { it.position })
    }

    @Test
    fun `an item Floppy does not know yet is added`() {
        overlay.put(progress(contentId = "tt1", position = 10_000L, lastWatched = 900L))

        val merged = overlay.merge(listOf(progress(position = 60_000L, lastWatched = 100L)))

        assertEquals(listOf("tt1", "tt0903747"), merged.map { it.contentId })
    }

    @Test
    fun `entries expire`() {
        overlay.put(progress(position = 1_200_000L, lastWatched = 500L))
        clock += 60_000L

        assertTrue(overlay.merge(emptyList()).isEmpty())
    }

    @Test
    fun `removal drops one episode or a whole title`() {
        overlay.put(progress(season = 2, episode = 5, position = 1L, lastWatched = 1L))
        overlay.put(progress(season = 2, episode = 6, position = 1L, lastWatched = 1L))
        overlay.put(progress(contentId = "tt1", position = 1L, lastWatched = 1L))

        overlay.remove("tt0903747", 2, 5)
        assertEquals(setOf(6, 5), overlay.merge(emptyList()).map { it.episode }.toSet())

        overlay.remove("tt0903747", null, null)
        assertEquals(listOf("tt1"), overlay.merge(emptyList()).map { it.contentId })
    }

    @Test
    fun `episode positions of the asked show are laid over the server's`() {
        overlay.put(progress(season = 2, episode = 5, position = 1_200_000L, lastWatched = 500L))
        overlay.put(progress(contentId = "tt1", season = 1, episode = 1, position = 5L, lastWatched = 500L))

        val merged = overlay.mergeEpisodes(
            "tt0903747",
            mapOf((2 to 4) to progress(season = 2, episode = 4, position = 3_000_000L, lastWatched = 100L))
        )

        assertEquals(setOf(2 to 4, 2 to 5), merged.keys)
        assertEquals(1_200_000L, merged.getValue(2 to 5).position)
    }
}
