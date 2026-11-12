package com.nuvio.tv.data.floppy

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderDescriptor
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingScrobbler
import com.nuvio.tv.core.tracking.TrackingSeekScrobblePolicy
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val HISTORY_CHUNK_SIZE = 50

@Singleton
class FloppyTrackingProvider @Inject constructor(
    auth: FloppyAuthStore,
    profiles: ProfileManager,
    override val scrobbler: FloppyTrackingScrobbler
) : TrackingProvider {
    override val descriptor = TrackingProviderDescriptor(
        TrackingProviderId.FLOPPY,
        "Floppy",
        setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE
        )
    )
    override val isAuthenticated = combine(auth.state, profiles.activeProfileId) { authorization, profileId ->
        authorization.isAuthenticated && authorization.scope.profileId == profileId
    }.stateIn(
        CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly,
        auth.state.value.isAuthenticated && auth.scope().profileId == profiles.activeProfileId.value
    )
}

@Singleton
class FloppyTrackingScrobbler @Inject constructor(
    private val store: FloppyAuthStore,
    private val api: FloppyApiClient,
    private val translator: FloppyEpisodeTranslator
) : TrackingScrobbler {
    internal constructor(store: FloppyAuthStore, api: FloppyApiClient) :
        this(store, api, FloppyEpisodeTranslator(store, api))

    override val providerId = TrackingProviderId.FLOPPY

    // A START with the new position re-anchors Now Playing; a STOP mid-episode would end the session.
    override val seekScrobblePolicy = TrackingSeekScrobblePolicy.POSITION_UPDATE

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        if (!store.state.value.isAuthenticated) {
            debug("floppy scrobble skipped action=${action.wireValue} reason=not_authenticated")
            return
        }
        // Translation never blocks a scrobble: any failure keeps the coordinates as they are.
        val translated = event.copy(media = translator.toFloppyNumbering(event.media))
        val body = scrobbleBody(action, translated)
        if (body == null) {
            debug("floppy scrobble skipped action=${action.wireValue} reason=no_usable_ids_or_coordinates")
            return
        }
        api.post(
            scope = store.scope(),
            path = "/api/v1/scrobble/",
            body = body,
            headers = mapOf("Idempotency-Key" to "nuvio:${store.installId()}:${UUID.randomUUID()}")
        )
    }
}

/** Scrobble payload, or null when there is nothing Floppy can resolve. Episode ids are the show's ids. */
internal fun scrobbleBody(action: TrackingScrobbleAction, event: TrackingScrobbleEvent): JsonObject? {
    val media = event.media
    val ids = media.ids.toFloppyIds() ?: return null
    if (!event.progressPercent.isFinite()) return null
    val episode = media.episode
    val isMovie = episode == null && media.kind == TrackingMediaKind.MOVIE
    if (!isMovie && (episode == null || episode.season == null)) return null
    return buildJsonObject {
        put("action", action.wireValue)
        put("media_type", if (isMovie) "movie" else "episode")
        put("ids", ids)
        if (!isMovie) {
            put("season_number", episode!!.season!!)
            put("episode_number", episode.number)
        }
        put("progress", event.progressPercent.coerceIn(0.0, 100.0))
        // Floppy keeps a resume point and the live Now Playing time only when it knows where playback is.
        val durationSeconds = event.durationMs?.takeIf { it > 0L }?.let { it / 1000L }?.takeIf { it > 0L }
        event.positionMs?.takeIf { it >= 0L }?.let { positionMs ->
            val seconds = positionMs / 1000L
            put("position_seconds", if (durationSeconds != null) seconds.coerceAtMost(durationSeconds) else seconds)
        }
        durationSeconds?.let { put("duration_seconds", it) }
    }
}

internal fun TrackingExternalIds.toFloppyIds(): JsonObject? {
    val tmdb = tmdb?.toString()
    val imdb = imdb?.takeIf(String::isNotBlank)
    val tvdb = tvdb?.takeIf(String::isNotBlank)
    if (tmdb == null && imdb == null && tvdb == null) return null
    return buildJsonObject {
        tmdb?.let { put("tmdb", it) }
        imdb?.let { put("imdb", it) }
        tvdb?.let { put("tvdb", it) }
    }
}

@Singleton
class FloppyTrackingHistoryWriter internal constructor(
    private val store: FloppyAuthStore,
    private val api: FloppyApiClient,
    private val now: () -> Instant,
    private val newId: () -> UUID,
    private val translator: FloppyEpisodeTranslator = FloppyEpisodeTranslator(store, api)
) : TrackingHistoryWriter {
    @Inject
    constructor(store: FloppyAuthStore, api: FloppyApiClient, translator: FloppyEpisodeTranslator) :
        this(store, api, Instant::now, UUID::randomUUID, translator)

    override val providerId = TrackingProviderId.FLOPPY
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        val scope = store.scope()
        if (scope.profileId != profileId || !store.state.value.isAuthenticated) return TrackingMutationResult(0)
        // One stamp per call, used only for items without a time of their own. Never spread over items.
        val markedNow = now().truncatedTo(ChronoUnit.MILLIS)
        val install = store.installId()
        // Translate the whole call once, before any event id exists: every chunk and retry of this call
        // then carries the same coordinates for the same client_event_id.
        val marks = items.filterNot(TrackingHistoryItem::isWholeSeriesMark)
        val medias = translator.toFloppyNumbering(marks.map(TrackingHistoryItem::media))
        val entries = marks.mapIndexedNotNull { index, item ->
            val watchedAt = WATCHED_AT_FORMAT.format(item.watchedAtEpochMs?.let(Instant::ofEpochMilli) ?: markedNow)
            historyEntry(medias[index], watchedAt, "nuvio:$install:${newId()}")
        }
        if (entries.isEmpty()) return TrackingMutationResult(0)

        var attempted = 0
        var notFound = 0
        for (chunk in entries.chunked(HISTORY_CHUNK_SIZE)) {
            val response = api.post(scope, "/api/v1/sync/history/", chunk.toRequestBody())
            notFound += countNotFound(response)
            attempted += chunk.size
        }
        return TrackingMutationResult(attemptedCount = attempted, notFoundCount = notFound)
    }

    override suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>): TrackingMutationResult {
        val scope = store.scope()
        if (scope.profileId != profileId || !store.state.value.isAuthenticated) return TrackingMutationResult(0)
        // A reference without episode coordinates is skipped unless it is a film: never remove a whole show.
        val entries = translator.toFloppyNumbering(items.toList())
            .mapNotNull { media -> historyEntry(media, watchedAt = null, eventId = null) { put("all", true) } }
        if (entries.isEmpty()) return TrackingMutationResult(0)

        var attempted = 0
        var notFound = 0
        for (chunk in entries.chunked(HISTORY_CHUNK_SIZE)) {
            val response = api.post(scope, "/api/v1/sync/history/remove/", chunk.toRequestBody())
            notFound += countNotFound(response)
            attempted += chunk.size
        }
        return TrackingMutationResult(attemptedCount = attempted, notFoundCount = notFound)
    }

    private class Entry(val isEpisode: Boolean, val body: JsonObject)

    private fun historyEntry(
        media: TrackingMediaReference,
        watchedAt: String?,
        eventId: String?,
        extra: JsonObjectBuilder.() -> Unit = {}
    ): Entry? {
        val ids = media.ids.toFloppyIds() ?: return null
        val episode = media.episode
        val isMovie = episode == null && media.kind == TrackingMediaKind.MOVIE
        if (!isMovie && (episode == null || episode.season == null)) return null
        val body = buildJsonObject {
            put("ids", ids)
            if (!isMovie) {
                put("season_number", episode!!.season!!)
                put("episode_number", episode.number)
            }
            watchedAt?.let { put("watched_at", it) }
            eventId?.let { put("client_event_id", it) }
            if (isMovie && watchedAt != null) media.title?.takeIf(String::isNotBlank)?.let { put("title", it) }
            extra()
        }
        return Entry(isEpisode = !isMovie, body = body)
    }

    private fun List<Entry>.toRequestBody(): JsonObject {
        val movies = filterNot(Entry::isEpisode).map(Entry::body)
        val episodes = filter(Entry::isEpisode).map(Entry::body)
        return buildJsonObject {
            if (movies.isNotEmpty()) put("movies", JsonArray(movies))
            if (episodes.isNotEmpty()) put("episodes", JsonArray(episodes))
        }
    }

    private fun countNotFound(response: FloppyHttpResponse): Int {
        val results = runCatching { json.parseToJsonElement(response.body).jsonObject["results"]?.jsonArray }
            .getOrNull() ?: throw FloppyApiError.InvalidResponse()
        return results.count { result ->
            (result as? JsonObject)?.get("outcome")?.jsonPrimitive?.contentOrNull == "not_found"
        }
    }

    private companion object {
        /** Always three fractional digits, so the persisted instant matches the local one to the millisecond. */
        val WATCHED_AT_FORMAT: DateTimeFormatter = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
    }
}

private fun debug(message: String) {
    runCatching { Log.d(TRACKING_SCROBBLE_DIAGNOSTIC_TAG, message) }
}
