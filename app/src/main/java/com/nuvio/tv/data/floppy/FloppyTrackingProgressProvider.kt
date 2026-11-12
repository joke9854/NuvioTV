package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Watched history and Continue Watching read from Floppy. Behaves like the MDBList provider, except
 * that episodes keep Floppy's own (TMDB) numbering: there is no episode-mapping step, so a show an
 * addon numbers differently will not line up. That is a known limit.
 *
 * Writes (history, scrobbles) go through the Phase 1 writer and scrobbler; a resume position is cleared
 * on the server through [removeProgress].
 */
@Singleton
class FloppyTrackingProgressProvider @Inject constructor(
    private val sync: FloppySyncRepository,
    auth: FloppyAuthStore,
    profiles: ProfileManager,
    layout: LayoutPreferenceDataStore
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.FLOPPY
    override val isAuthenticated: Flow<Boolean> = combine(auth.state, profiles.activeProfileId) { state, profileId ->
        state.isAuthenticated && state.scope.profileId == profileId
    }.distinctUntilChanged()
    private val projection = combine(sync.progressState, auth.state, profiles.activeProfileId) { state, authorization, profileId ->
        if (authorization.isAuthenticated && authorization.scope.profileId == profileId &&
            state.scope == authorization.scope) state.projection else FloppyProgressProjection.Empty
    }.distinctUntilChanged()

    /** Positions saved here that Floppy has not returned yet; see [FloppyOptimisticProgress]. */
    private val optimistic = FloppyOptimisticProgress()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingRefresh: Job? = null

    override val allProgress: Flow<List<WatchProgress>> =
        combine(projection, optimistic.changes) { projection, _ -> optimistic.merge(projection.progress) }
            .loadOnStart()
    override val watchedItems: Flow<List<WatchedItem>> = projection.map { it.watchedItems }.loadOnStart()
    override val watchedMovieIds: Flow<Set<String>> = projection.map { it.watchedMovieIds }.loadOnStart()
    override val nextUpSeeds: Flow<List<WatchProgress>> =
        combine(projection, layout.nextUpFromFurthestEpisode) { projection, furthest ->
            projection.nextUp(furthest)
        }.loadOnStart()
    override val remoteProgressLoaded: Flow<Boolean> =
        combine(sync.state, auth.state, profiles.activeProfileId) { state, authorization, profileId ->
            state.hasLoaded && state.scope == authorization.scope && authorization.scope.profileId == profileId &&
                authorization.isAuthenticated
        }.distinctUntilChanged()

    // Same values as MDBList: local progress is kept and merged with the provider's, not wiped on selection.
    override val ownsCompletedHistoryProjection: Boolean = false
    override val clearsLocalProgressOnSelection: Boolean = false

    override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        combine(projection, optimistic.changes) { projection, _ ->
            optimistic.mergeEpisodes(contentId, projection.episodeProgress(contentId))
        }.loadOnStart()

    override fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> = flowOf(emptyList())

    override fun isWatched(contentId: String, videoId: String?, season: Int?, episode: Int?): Flow<Boolean> =
        projection.map { it.isWatched(contentId, season, episode) }.loadOnStart()

    override suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        sync.refresh(TrackingRefreshIntent.AUTOMATIC)
        return sync.currentProjection().watchedShowEpisodes
    }

    override suspend fun showIdSiblings(): Map<String, Set<String>> {
        sync.ensureLoaded()
        return sync.currentProjection().showIdSiblings
    }

    override fun isWatchedByVideoId(videoId: String, episode: Int): Boolean? {
        val parts = videoId.split(':')
        if (parts.size < 3 || parts.last().toIntOrNull() != episode) return null
        val season = parts[parts.lastIndex - 1].toIntOrNull() ?: return null
        val parent = parts.dropLast(2).joinToString(":")
        if (retainsLocalProgress(parent)) return null
        return sync.currentProjection().isWatched(parent, season, episode)
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = sync.refresh(intent)

    /** Clears the resume position on the server (`DELETE /playback/progress/`), then re-reads the list. */
    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) =
        sync.clearPlayback(contentId, season, episode)

    override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) {
        optimistic.put(progress)
        if (!quiet) refreshSoon()
    }

    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) =
        optimistic.remove(contentId, season, episode)

    override fun clearOptimistic() = optimistic.clear()

    /**
     * Re-reads Floppy shortly after a save, past the automatic 15-minute throttle, so the server's copy
     * (stored by the scrobble that goes out at the same time) replaces the optimistic one.
     */
    private fun refreshSoon() {
        pendingRefresh?.cancel()
        pendingRefresh = refreshScope.launch {
            delay(REFRESH_AFTER_SAVE_MS)
            runCatching { sync.refresh(TrackingRefreshIntent.INVALIDATED) }
        }
    }
    override fun isHiddenFromProgress(contentId: String): Boolean = sync.currentProjection().isHidden(contentId)

    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress = progress

    override fun retainsLocalProgress(contentId: String): Boolean = !hasFloppyId(contentId)
    override fun retainsLocalWatchedEpisode(item: WatchedItem): Boolean = !hasFloppyId(item.contentId)

    private fun hasFloppyId(contentId: String): Boolean = parseTrackingExternalIds(contentId).let {
        it.imdb != null || it.tmdb != null
    }

    private fun <T> Flow<T>.loadOnStart(): Flow<T> = onStart {
        sync.ensureLoaded()
        sync.refreshAsync(TrackingRefreshIntent.AUTOMATIC)
    }.distinctUntilChanged()

    private companion object {
        const val REFRESH_AFTER_SAVE_MS = 4_000L
    }
}
