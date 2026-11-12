package com.nuvio.tv.data.floppy

import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class FloppySyncError { UNAVAILABLE, INVALID_RESPONSE, RATE_LIMIT, AUTHORIZATION_REVOKED }

/** One play of an item. [watchedAt] is kept as the server sent it, so no precision is lost on disk. */
@Serializable
data class FloppySyncPlay(
    val key: String = "",
    @SerialName("watched_at") val watchedAt: String? = null,
    @SerialName("client_event_id") val clientEventId: String? = null
)

/** Identity of an item in the feeds: the same tuple always means the same item. */
data class FloppyItemKey(
    val type: String,
    val source: String,
    val mediaId: String,
    val season: Int?,
    val episode: Int?
)

/** The whole sync state of one movie, episode or show, exactly as `/sync/items/` reports it. */
@Serializable
data class FloppySyncItem(
    val type: String,
    val source: String,
    @SerialName("media_id") val mediaId: String,
    val title: String? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    val ids: Map<String, String> = emptyMap(),
    @SerialName("in_library") val inLibrary: Boolean = true,
    val status: String? = null,
    val plays: List<FloppySyncPlay> = emptyList()
) {
    val key: FloppyItemKey get() = FloppyItemKey(type, source, mediaId, seasonNumber, episodeNumber)

    /** True when the server says the item is gone: not in the library and nothing left to show. */
    val isRemoved: Boolean get() = !inLibrary && plays.isEmpty()
}

/** One resume position from `/playback/progress/`. */
@Serializable
data class FloppyPlayback(
    @SerialName("media_type") val mediaType: String,
    val source: String? = null,
    @SerialName("media_id") val mediaId: String? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    val ids: Map<String, String> = emptyMap(),
    val title: String? = null,
    @SerialName("series_title") val seriesTitle: String? = null,
    val image: String? = null,
    @SerialName("position_seconds") val positionSeconds: Double? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    val completed: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null
)

/**
 * Everything persisted for one profile. Items and [cursor] are written together: the cursor never
 * moves past items that were not stored with it. A null [cursor] means no full snapshot was taken yet.
 */
@Serializable
data class FloppySyncSnapshot(
    val serverUrl: String,
    val connection: String? = null,
    val cursor: Long? = null,
    val items: List<FloppySyncItem> = emptyList(),
    val playback: List<FloppyPlayback> = emptyList(),
    val checkedAtEpochMs: Long? = null,
    /**
     * Tracker (TVDB) numbering learned for episodes of IMDb-id shows, keyed by [trackerEpisodeKey] of
     * the TMDB-order coordinates Floppy reports. Kept while the cursor advances.
     */
    val trackerEpisodes: Map<String, FloppyEpisodeCoords> = emptyMap()
)

internal fun trackerEpisodeKey(source: String, mediaId: String, season: Int, episode: Int): String =
    "$source|$mediaId|$season|$episode"

@Serializable
internal data class FloppyConnectionResponse(val connection: String? = null)

@Serializable
internal data class FloppySnapshotPage(
    val results: List<FloppySyncItem> = emptyList(),
    @SerialName("next_after") val nextAfter: Long? = null,
    val cursor: Long
)

@Serializable
internal data class FloppyChangesPage(
    val results: List<FloppySyncItem> = emptyList(),
    val cursor: Long,
    @SerialName("has_more") val hasMore: Boolean = false
)

@Serializable
internal data class FloppyPaginationInfo(val next: String? = null)

@Serializable
internal data class FloppyPlaybackPage(
    val results: List<FloppyPlayback> = emptyList(),
    val pagination: FloppyPaginationInfo = FloppyPaginationInfo()
)

/** The server's play instant in epoch milliseconds, or null when it is missing or unreadable. */
internal fun floppyInstantMillis(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        .getOrNull()
}

internal fun Throwable.toFloppySyncError(): FloppySyncError = when (this) {
    is FloppyAuthException, is FloppyApiError.Unauthorized -> FloppySyncError.AUTHORIZATION_REVOKED
    is FloppyApiError.InvalidResponse -> FloppySyncError.INVALID_RESPONSE
    is FloppyApiError.RateLimited -> FloppySyncError.RATE_LIMIT
    else -> FloppySyncError.UNAVAILABLE
}

internal data class FloppyProgressState(
    val scope: FloppyAuthScope? = null,
    val projection: FloppyProgressProjection = FloppyProgressProjection.Empty
)

data class FloppySyncState(
    val scope: FloppyAuthScope? = null,
    val snapshot: FloppySyncSnapshot? = null,
    val isLoading: Boolean = false,
    val error: FloppySyncError? = null,
    val retryAtEpochMs: Long? = null,
    val attemptedAtEpochMs: Long? = null
) {
    val hasLoaded: Boolean get() = snapshot?.checkedAtEpochMs != null
}
