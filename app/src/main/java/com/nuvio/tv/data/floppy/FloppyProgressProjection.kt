package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.tracking.selectPreferredTrackingNextUpSeeds
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

internal const val FLOPPY_PROVIDER_ID = "floppy"

/**
 * Nuvio's view of a Floppy snapshot. Plays are never merged: every play is its own watched event at
 * its own instant, and "watched" means at least one play. Episode numbers are Floppy's (TMDB) numbers.
 */
internal class FloppyProgressProjection private constructor(
    val watchedMovieIds: Set<String>,
    val watchedItems: List<WatchedItem>,
    val watchedShowEpisodes: Map<String, Set<Pair<Int, Int>>>,
    val showIdSiblings: Map<String, Set<String>>,
    val progress: List<WatchProgress>,
    private val episodeProgressById: Map<String, List<WatchProgress>>,
    private val seeds: List<WatchProgress>,
    private val hiddenIds: Set<String>,
    /** Items and resume positions that had no usable id and were left out. Never replaced by a guess. */
    val unresolvedCount: Int
) {
    fun nextUp(preferFurthest: Boolean): List<WatchProgress> =
        selectPreferredTrackingNextUpSeeds(seeds, preferFurthest)

    fun isHidden(contentId: String): Boolean = contentId in hiddenIds

    fun isWatched(contentId: String, season: Int?, episode: Int?): Boolean =
        if (season != null && episode != null) season to episode in watchedShowEpisodes[contentId].orEmpty()
        else contentId in watchedMovieIds

    fun episodeProgress(contentId: String): Map<Pair<Int, Int>, WatchProgress> =
        episodeProgressById[contentId].orEmpty().associateBy { it.season!! to it.episode!! }

    private class Resolved(val contentId: String, val aliases: Set<String>)

    private class Resolver(items: List<FloppySyncItem>) {
        /** Ids of movies and shows only; an episode's own ids are used only when its show is unknown. */
        private val known = HashMap<String, Map<String, String>>()
        private val showTitles = HashMap<String, String>()

        init {
            for (item in items) {
                if (item.type == "episode") continue
                val key = indexKey(item.type, item.source, item.mediaId)
                if (item.ids.isNotEmpty()) known[key] = known[key].orEmpty() + item.ids
                if (item.type == "show" && !item.title.isNullOrBlank()) showTitles[key] = item.title
            }
        }

        fun resolve(type: String, source: String?, mediaId: String?, own: Map<String, String>): Resolved? {
            val indexed = if (source != null && mediaId != null) known[indexKey(type, source, mediaId)] else null
            val ids = if (!indexed.isNullOrEmpty()) indexed else own
            val imdb = ids["imdb"]?.trim()
                ?.takeIf { it.length > 2 && it.startsWith("tt", ignoreCase = true) && ':' !in it }
            val tmdbValue = ids["tmdb"] ?: mediaId?.takeIf { source == "tmdb" }
            val tmdb = tmdbValue?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { "tmdb:$it" }
            val contentId = imdb ?: tmdb ?: return null
            return Resolved(contentId, listOfNotNull(imdb, tmdb).toCollection(linkedSetOf()))
        }

        fun showTitle(source: String?, mediaId: String?): String? =
            if (source != null && mediaId != null) showTitles[indexKey("show", source, mediaId)] else null

        private fun indexKey(type: String, source: String, mediaId: String): String =
            "${if (type == "movie") "movie" else "show"}|$source|$mediaId"
    }

    companion object {
        val Empty = FloppyProgressProjection(
            emptySet(), emptyList(), emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyList(), emptySet(), 0
        )

        operator fun invoke(snapshot: FloppySyncSnapshot): FloppyProgressProjection {
            val resolver = Resolver(snapshot.items)
            var unresolved = 0
            val hidden = linkedSetOf<String>()
            val siblings = HashMap<String, Set<String>>()
            val watchedMovieIds = linkedSetOf<String>()
            val watchedItems = ArrayList<WatchedItem>()
            val watchedEpisodes = HashMap<String, MutableSet<Pair<Int, Int>>>()
            val latestPlay = HashMap<Triple<String, Int?, Int?>, Long>()
            val seedCandidates = ArrayList<Pair<WatchProgress, Resolved>>()
            val tracker = snapshot.trackerEpisodes

            /** Tracker numbering for an IMDb-id show when the server translated it; Floppy's numbers otherwise. */
            fun numbered(contentId: String, source: String?, mediaId: String?, season: Int, episode: Int): Pair<Int, Int> {
                if (source == null || mediaId == null || !isTrackerNumbered(contentId)) return season to episode
                val mapped = tracker[trackerEpisodeKey(source, mediaId, season, episode)] ?: return season to episode
                return mapped.season to mapped.episode
            }

            fun addSiblings(resolved: Resolved) {
                if (resolved.aliases.size > 1) resolved.aliases.forEach { siblings[it] = resolved.aliases }
            }

            for (item in snapshot.items) {
                when (item.type) {
                    "show" -> {
                        val resolved = resolver.resolve("show", item.source, item.mediaId, item.ids) ?: continue
                        addSiblings(resolved)
                        if (item.status == "dropped") hidden += resolved.aliases
                    }
                    "movie", "episode" -> {
                        if (item.plays.isEmpty()) continue
                        val resolved = resolver.resolve(item.type, item.source, item.mediaId, item.ids)
                        if (resolved == null) {
                            unresolved++
                            continue
                        }
                        val isMovie = item.type == "movie"
                        var season = item.seasonNumber
                        var episode = item.episodeNumber
                        if (!isMovie && (season == null || episode == null)) {
                            unresolved++
                            continue
                        }
                        // Floppy's own (TMDB) numbers, kept for ids that are not tracker-numbered.
                        val floppySeason = season
                        val floppyEpisode = episode
                        if (!isMovie) {
                            numbered(resolved.contentId, item.source, item.mediaId, season!!, episode!!).let {
                                season = it.first
                                episode = it.second
                            }
                        }
                        addSiblings(resolved)
                        val times = item.plays.mapNotNull { floppyInstantMillis(it.watchedAt) }
                        val title = if (isMovie) item.title else resolver.showTitle(item.source, item.mediaId)
                        if (isMovie) {
                            watchedMovieIds += resolved.aliases
                        } else {
                            resolved.aliases.forEach { alias ->
                                val own = if (isTrackerNumbered(alias)) season!! to episode!! else floppySeason!! to floppyEpisode!!
                                watchedEpisodes.getOrPut(alias) { linkedSetOf() } += own
                            }
                        }
                        // One watched event per play: a rewatch is a second event, never a merge.
                        times.forEach { watchedAt ->
                            watchedItems += WatchedItem(
                                contentId = resolved.contentId,
                                contentType = if (isMovie) "movie" else "series",
                                title = title?.takeIf(String::isNotBlank) ?: resolved.contentId,
                                season = season,
                                episode = episode,
                                watchedAt = watchedAt,
                                trackingProviderId = FLOPPY_PROVIDER_ID
                            )
                        }
                        val last = times.maxOrNull() ?: continue
                        latestPlay[Triple(resolved.contentId, season, episode)] = last
                        if (!isMovie && season!! > 0) {
                            seedCandidates += progressOf(
                                contentId = resolved.contentId, isMovie = false, name = title,
                                poster = null, season = season, episode = episode, episodeTitle = item.title,
                                positionMs = 0, durationMs = 0, lastWatched = last, percent = 100f,
                                source = WatchProgress.SOURCE_REMOTE_HISTORY
                            ) to resolved
                        }
                    }
                }
            }

            val progress = snapshot.playback.mapNotNull { entry ->
                if (entry.completed) return@mapNotNull null
                val isMovie = entry.mediaType == "movie"
                if (!isMovie && entry.mediaType != "episode") return@mapNotNull null
                val resolved = resolver.resolve(entry.mediaType, entry.source, entry.mediaId, entry.ids)
                if (resolved == null) {
                    unresolved++
                    return@mapNotNull null
                }
                var season = entry.seasonNumber.takeUnless { isMovie }
                var episode = entry.episodeNumber.takeUnless { isMovie }
                if (!isMovie && (season == null || episode == null)) return@mapNotNull null
                if (!isMovie) {
                    numbered(resolved.contentId, entry.source, entry.mediaId, season!!, episode!!).let {
                        season = it.first
                        episode = it.second
                    }
                }
                if (resolved.aliases.any { it in hidden }) return@mapNotNull null
                // The server's own time. Without one the position cannot be ordered and is left out.
                val updated = floppyInstantMillis(entry.updatedAt) ?: return@mapNotNull null
                val watched = latestPlay[Triple(resolved.contentId, season, episode)]
                if (watched != null && updated <= watched) return@mapNotNull null
                val positionMs = ((entry.positionSeconds ?: 0.0) * 1000.0).toLong().coerceAtLeast(0L)
                val durationMs = ((entry.durationSeconds ?: 0.0) * 1000.0).toLong().coerceAtLeast(0L)
                progressOf(
                    contentId = resolved.contentId, isMovie = isMovie,
                    name = if (isMovie) entry.title else entry.seriesTitle ?: resolver.showTitle(entry.source, entry.mediaId),
                    poster = entry.image, season = season, episode = episode,
                    episodeTitle = entry.title.takeUnless { isMovie },
                    positionMs = positionMs, durationMs = durationMs, lastWatched = updated,
                    percent = if (durationMs > 0) {
                        (positionMs.toFloat() / durationMs.toFloat() * 100f).coerceIn(0f, 100f)
                    } else null,
                    source = WatchProgress.SOURCE_REMOTE_PLAYBACK
                )
            }.groupBy { Triple(it.contentId, it.season, it.episode) }
                .map { (_, entries) -> entries.maxBy(WatchProgress::lastWatched) }
                .sortedByDescending(WatchProgress::lastWatched)

            val episodeProgressById = progress.filter { it.season != null && it.episode != null }
                .flatMap { item -> (siblings[item.contentId] ?: setOf(item.contentId)).map { it to item } }
                .groupBy({ it.first }, { it.second })
            val seeds = seedCandidates.filter { (_, resolved) -> resolved.aliases.none { it in hidden } }
                .map { it.first }

            return FloppyProgressProjection(
                watchedMovieIds = watchedMovieIds,
                watchedItems = watchedItems.sortedByDescending(WatchedItem::watchedAt),
                watchedShowEpisodes = watchedEpisodes,
                showIdSiblings = siblings,
                progress = progress,
                episodeProgressById = episodeProgressById,
                seeds = seeds,
                hiddenIds = hidden,
                unresolvedCount = unresolved
            )
        }

        /**
         * TMDB-order coordinates of tracker-numbered (IMDb-id) shows that [snapshot] has no answer for yet,
         * grouped by the show's own Floppy source and media id.
         */
        fun untranslatedEpisodes(snapshot: FloppySyncSnapshot): Map<Pair<String, String>, Set<FloppyEpisodeCoords>> {
            val resolver = Resolver(snapshot.items)
            val wanted = LinkedHashMap<Pair<String, String>, MutableSet<FloppyEpisodeCoords>>()
            fun consider(source: String?, mediaId: String?, ids: Map<String, String>, season: Int?, episode: Int?) {
                if (source == null || mediaId == null || season == null || episode == null || season < 0 || episode < 1) return
                val contentId = resolver.resolve("episode", source, mediaId, ids)?.contentId ?: return
                if (!isTrackerNumbered(contentId)) return
                if (trackerEpisodeKey(source, mediaId, season, episode) in snapshot.trackerEpisodes) return
                wanted.getOrPut(source to mediaId) { linkedSetOf() } += FloppyEpisodeCoords(season, episode)
            }
            for (item in snapshot.items) {
                if (item.type != "episode" || item.plays.isEmpty()) continue
                consider(item.source, item.mediaId, item.ids, item.seasonNumber, item.episodeNumber)
            }
            for (entry in snapshot.playback) {
                if (entry.completed || entry.mediaType != "episode") continue
                consider(entry.source, entry.mediaId, entry.ids, entry.seasonNumber, entry.episodeNumber)
            }
            return wanted
        }

        private fun progressOf(
            contentId: String,
            isMovie: Boolean,
            name: String?,
            poster: String?,
            season: Int?,
            episode: Int?,
            episodeTitle: String?,
            positionMs: Long,
            durationMs: Long,
            lastWatched: Long,
            percent: Float?,
            source: String
        ) = WatchProgress(
            contentId = contentId,
            contentType = if (isMovie) "movie" else "series",
            name = name?.takeIf(String::isNotBlank) ?: contentId,
            poster = poster,
            backdrop = null,
            logo = null,
            videoId = if (isMovie) contentId else "$contentId:$season:$episode",
            season = season,
            episode = episode,
            episodeTitle = episodeTitle,
            position = positionMs,
            duration = durationMs,
            lastWatched = lastWatched,
            progressPercent = percent,
            source = source,
            trackingProviderId = FLOPPY_PROVIDER_ID
        )
    }
}
