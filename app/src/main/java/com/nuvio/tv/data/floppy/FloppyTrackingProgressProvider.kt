package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.domain.model.WatchProgress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * Phase 1 is write-only. This provider exists solely because the watch-progress repository forwards
 * history adds and removals to the history writers of providers it finds in the progress-provider
 * registry. It reads nothing and owns no state.
 *
 * To be replaced by the `sync/items` reader in Phase 2.
 */
@Singleton
class FloppyTrackingProgressProvider @Inject constructor(
    auth: FloppyAuthStore,
    profiles: ProfileManager
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.FLOPPY
    override val isAuthenticated: Flow<Boolean> = combine(auth.state, profiles.activeProfileId) { state, profileId ->
        state.isAuthenticated && state.scope.profileId == profileId
    }
    override val allProgress: Flow<List<WatchProgress>> = flowOf(emptyList())
    override val remoteProgressLoaded: Flow<Boolean> = flowOf(true)
    override val nextUpSeeds: Flow<List<WatchProgress>> = flowOf(emptyList())
    override val watchedMovieIds: Flow<Set<String>> = flowOf(emptySet())
    override val ownsCompletedHistoryProjection: Boolean = false
    override val clearsLocalProgressOnSelection: Boolean = false

    override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> = flowOf(emptyMap())
    override fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> = flowOf(emptyList())
    override fun isWatched(contentId: String, videoId: String?, season: Int?, episode: Int?): Flow<Boolean> =
        flowOf(false)

    override suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> = emptyMap()
    override suspend fun showIdSiblings(): Map<String, Set<String>> = emptyMap()
    override suspend fun refresh(intent: TrackingRefreshIntent) = Unit
    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) = Unit
    override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) = Unit
    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) = Unit
    override fun clearOptimistic() = Unit
    override fun isHiddenFromProgress(contentId: String): Boolean = false
    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress = progress
}
