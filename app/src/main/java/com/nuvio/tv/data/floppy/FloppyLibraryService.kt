package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingListStatus
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import com.nuvio.tv.domain.model.PosterShape
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val FLOPPY_STATUS_SELECTION_GROUP = "floppy:status"
internal const val FLOPPY_STATUS_REMOVE_EXTENSION = "sync_status_remove_v1"
private const val FLOPPY_REMOVE_STATUS = "none"
private const val OVERLAY_TTL_MS = 10L * 60 * 1_000

/** One Floppy status as Nuvio's Library shows it. [wire] is the value in `/sync/items/` and `/sync/status/`. */
internal data class FloppyLibraryStatusDefinition(
    val wire: String,
    val key: String,
    val title: String,
    val trackingStatus: TrackingListStatus,
    val supportedContentTypes: Set<String>,
    val isMembershipDestination: Boolean = true
)

internal val floppyLibraryStatusDefinitions = listOf(
    FloppyLibraryStatusDefinition(
        "in_progress", "floppy:status:watching", "Watching", TrackingListStatus.WATCHING, setOf("series")
    ),
    FloppyLibraryStatusDefinition(
        "planning", "floppy:status:plantowatch", "Plan to Watch", TrackingListStatus.PLAN_TO_WATCH,
        setOf("movie", "series")
    ),
    FloppyLibraryStatusDefinition(
        "paused", "floppy:status:hold", "On Hold", TrackingListStatus.ON_HOLD, setOf("series")
    ),
    FloppyLibraryStatusDefinition(
        "completed", "floppy:status:completed", "Completed", TrackingListStatus.COMPLETED,
        setOf("movie", "series"), isMembershipDestination = false
    ),
    FloppyLibraryStatusDefinition(
        "dropped", "floppy:status:dropped", "Dropped", TrackingListStatus.DROPPED, setOf("movie", "series")
    )
)

internal fun floppyLibraryStatusDefinition(key: String) = floppyLibraryStatusDefinitions.firstOrNull { it.key == key }

internal fun floppyLibraryStatusDefinition(status: TrackingListStatus) =
    floppyLibraryStatusDefinitions.first { it.trackingStatus == status }

internal fun floppyLibraryTabs(): List<LibraryListTab> = floppyLibraryStatusDefinitions.map { definition ->
    LibraryListTab(
        key = definition.key,
        title = definition.title,
        type = if (definition.trackingStatus == TrackingListStatus.PLAN_TO_WATCH) {
            LibraryListTab.Type.WATCHLIST
        } else {
            LibraryListTab.Type.STATUS
        },
        trackingProviderId = TrackingProviderId.FLOPPY.storageId,
        selectionGroup = FLOPPY_STATUS_SELECTION_GROUP,
        supportedContentTypes = definition.supportedContentTypes,
        isMembershipDestination = definition.isMembershipDestination
    )
}

/** A movie or show in the library with a status. [type] is Nuvio's: `movie` or `series`. */
internal data class FloppyLibraryRow(
    val contentId: String,
    val aliases: Set<String>,
    val type: String,
    val title: String?,
    val ids: Map<String, String>,
    val source: String?,
    val mediaId: String?,
    val status: String,
    val listedAt: Long,
    /** Poster URL from Floppy (or, until the next sync, the one Nuvio had when the title was added). */
    val poster: String? = null
) {
    val tmdbId: Int? get() = aliases.firstOrNull { it.startsWith("tmdb:") }?.substringAfter(':')?.toIntOrNull()
    val imdbId: String? get() = aliases.firstOrNull { it.startsWith("tt", ignoreCase = true) }
}

private fun String.libraryType(): String = when (trim().lowercase()) {
    "show", "tv", "anime", "series" -> "series"
    else -> trim().lowercase()
}

/**
 * Movies and shows of [snapshot] that are in the library with a known status. Episodes, seasons and items
 * without a usable id are left out. The content id is the one the progress projection uses.
 */
internal fun floppyLibraryRows(snapshot: FloppySyncSnapshot?): List<FloppyLibraryRow> {
    snapshot ?: return emptyList()
    val resolver = FloppyProgressProjection.Resolver(snapshot.items)
    return snapshot.items.asSequence()
        .filter { it.type == "movie" || it.type == "show" || it.type == "tv" }
        .filter { item -> item.inLibrary && floppyLibraryStatusDefinitions.any { it.wire == item.status } }
        .mapNotNull { item ->
            val resolved = resolver.resolve(item.type, item.source, item.mediaId, item.ids) ?: return@mapNotNull null
            FloppyLibraryRow(
                contentId = resolved.contentId,
                aliases = resolved.aliases,
                type = item.type.libraryType(),
                title = item.title?.takeIf(String::isNotBlank),
                ids = item.ids,
                source = item.source,
                mediaId = item.mediaId,
                status = item.status!!,
                listedAt = item.plays.mapNotNull { floppyInstantMillis(it.watchedAt) }.maxOrNull() ?: 0L,
                poster = floppyPosterUrl(item.image)
            )
        }
        .distinctBy { it.type to it.contentId }
        .toList()
}

private class FloppyLibraryOverlay(
    val type: String,
    val aliases: Set<String>,
    /** The row after the write, or null when the title left the library. */
    val row: FloppyLibraryRow?,
    val at: Long
)

private fun FloppyLibraryRow.matches(type: String, aliases: Set<String>) =
    this.type == type && this.aliases.any { it in aliases }

private fun List<FloppyLibraryRow>.withOverlays(overlays: List<FloppyLibraryOverlay>, now: Long): List<FloppyLibraryRow> {
    var rows = this
    for (overlay in overlays) {
        if (now - overlay.at !in 0 until OVERLAY_TTL_MS) continue
        rows = rows.filterNot { it.matches(overlay.type, overlay.aliases) }
        overlay.row?.let { rows = rows + it }
    }
    return rows
}

private fun FloppyLibraryRow.toLibraryEntry(): LibraryEntry {
    val definition = floppyLibraryStatusDefinitions.first { it.wire == status }
    return LibraryEntry(
        id = contentId,
        type = type,
        name = title ?: contentId,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        addonBaseUrl = null,
        listKeys = setOf(definition.key),
        listedAt = listedAt,
        imdbId = imdbId,
        tmdbId = tmdbId,
        rawPosterUrl = poster,
        trackingProviderId = TrackingProviderId.FLOPPY.storageId,
        trackingProviderItemId = source?.let { "floppy:$it:$mediaId" }
    )
}

/**
 * A poster for a Library card: http(s) only, anything else (blank, a relative path) is dropped.
 * Floppy stores TMDB posters at w500; a card needs w342, about half the bytes, so larger TMDB sizes
 * are asked for at w342.
 */
internal fun floppyPosterUrl(value: String?): String? = value?.trim()?.takeIf {
    it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
}?.replace(TMDB_LARGE_POSTER, "$1$FLOPPY_CARD_POSTER_SIZE/")

private const val FLOPPY_CARD_POSTER_SIZE = "w342"
private val TMDB_LARGE_POSTER = Regex("^(https?://image\\.tmdb\\.org/t/p/)(?:w500|w780|original)/", RegexOption.IGNORE_CASE)

/** Floppy refused to remove a title because it has watch history. Floppy never deletes history. */
class FloppyLibraryHistoryConflictException : IllegalStateException(
    "Floppy keeps titles with watch history; mark them Dropped instead"
)

/** This server does not advertise `sync_status_remove_v1`, so nothing was sent. */
class FloppyLibraryRemovalUnsupportedException : IllegalStateException(
    "This Floppy server cannot remove titles from the library. Update the server, or mark the title Dropped instead"
)

/** Floppy answered 200 but did not apply the status. [reason] is the server's short code, when it is one. */
class FloppyLibraryWriteException(val outcome: String?, val reason: String?) : IllegalStateException(
    "Floppy could not update this title" + (reason?.let { " ($it)" } ?: "")
)

/**
 * The Library's Floppy status tabs. Reads the persisted `/sync/items/` snapshot, writes through
 * `/sync/status/`. A status change never writes watch history. Custom lists do not exist for Floppy.
 */
@Singleton
class FloppyLibraryService internal constructor(
    private val syncRepository: FloppySyncRepository,
    private val store: FloppyAuthStore,
    private val api: FloppyApiClient,
    private val capabilities: FloppyEpisodeTranslator,
    override val isAuthenticated: Flow<Boolean>,
    private val now: () -> Long
) : TrackingLibraryProvider {
    @Inject
    constructor(
        syncRepository: FloppySyncRepository,
        store: FloppyAuthStore,
        api: FloppyApiClient,
        capabilities: FloppyEpisodeTranslator,
        tracking: FloppyTrackingProvider
    ) : this(syncRepository, store, api, capabilities, tracking.isAuthenticated, System::currentTimeMillis)

    override val providerId = TrackingProviderId.FLOPPY
    private val json = Json { ignoreUnknownKeys = true }
    private val overlays = MutableStateFlow<List<FloppyLibraryOverlay>>(emptyList())

    override val isRefreshing = syncRepository.state.map { it.isLoading }.distinctUntilChanged()

    private val rows: Flow<List<FloppyLibraryRow>> = combine(
        syncRepository.state, overlays, isAuthenticated
    ) { state, pending, authenticated ->
        if (authenticated) floppyLibraryRows(state.snapshot).withOverlays(pending, now()) else emptyList()
    }

    private fun currentRows(): List<FloppyLibraryRow> =
        floppyLibraryRows(syncRepository.currentSnapshot()).withOverlays(overlays.value, now())

    override val items: Flow<List<LibraryEntry>> = rows
        .map { list -> list.map(FloppyLibraryRow::toLibraryEntry).sortedByDescending(LibraryEntry::listedAt) }
        .onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()

    override val tabs: Flow<List<LibraryListTab>> = isAuthenticated
        .map { authenticated -> if (authenticated) floppyLibraryTabs() else emptyList() }
        .distinctUntilChanged()

    override fun recognizesListKey(key: String): Boolean = floppyLibraryStatusDefinition(key) != null

    override fun observeMembership(itemId: String, itemType: String): Flow<Set<String>> {
        val aliases = setOf(itemId)
        val type = itemType.libraryType()
        return rows.map { list ->
            list.firstOrNull { it.matches(type, aliases) }
                ?.let { row -> setOfNotNull(floppyLibraryStatusDefinitions.firstOrNull { it.wire == row.status }?.key) }
                .orEmpty()
        }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
            .distinctUntilChanged()
    }

    override fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean> =
        currentMembership.mapValues { false }.toMutableMap().apply {
            if (currentMembership.values.none { selected -> selected }) {
                this[floppyLibraryStatusDefinition(TrackingListStatus.PLAN_TO_WATCH).key] = true
            }
        }

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        syncRepository.ensureLoaded()
        val selected = currentRows().firstOrNull { it.matches(item.itemType.libraryType(), item.aliases()) }?.status
        return ListMembershipSnapshot(
            floppyLibraryStatusDefinitions.associate { definition -> definition.key to (definition.wire == selected) }
        )
    }

    override suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges,
        destructiveRemovalConfirmed: Boolean
    ) {
        require(isAuthenticated.first()) { "Floppy authentication is required" }
        syncRepository.ensureLoaded()
        val type = item.itemType.libraryType()
        val aliases = item.aliases()
        val current = currentRows().firstOrNull { it.matches(type, aliases) }
        val desiredDefinitions = floppyLibraryStatusDefinitions.filter { changes.desiredMembership[it.key] == true }
        require(desiredDefinitions.size <= 1) { "A Floppy item can have only one list status" }
        val desired = desiredDefinitions.singleOrNull()
        require(desired == null || type in desired.supportedContentTypes) { "${desired?.title} does not support ${item.itemType}" }
        if (desired?.wire == current?.status) return
        val scope = store.scope()
        if (desired == null && !capabilities.hasExtension(scope, FLOPPY_STATUS_REMOVE_EXTENSION)) {
            throw FloppyLibraryRemovalUnsupportedException()
        }

        val entry = statusEntry(item, current, desired?.wire ?: FLOPPY_REMOVE_STATUS)
        val label = if (type == "movie") "movies" else "shows"
        val response = api.post(scope, "/api/v1/sync/status/", buildJsonObject { put(label, JsonArray(listOf(entry))) })
        val result = runCatching {
            json.parseToJsonElement(response.body).jsonObject["results"]?.jsonArray?.firstOrNull() as? JsonObject
        }.getOrNull() ?: throw FloppyApiError.InvalidResponse()
        val outcome = result["outcome"]?.jsonPrimitive?.contentOrNull
        val reason = result["reason"]?.jsonPrimitive?.contentOrNull
        when (outcome) {
            "created", "updated", "already_satisfied", "removed" -> Unit
            "conflict" ->
                if (reason == "has_history") throw FloppyLibraryHistoryConflictException()
                else throw FloppyLibraryWriteException(outcome, reason?.takeIf { it.matches(REASON_CODE) })
            else -> throw FloppyLibraryWriteException(outcome, reason?.takeIf { it.matches(REASON_CODE) })
        }

        // Show the change at once; the INVALIDATED refresh then brings the snapshot up to date.
        val base = current ?: FloppyLibraryRow(
            contentId = item.itemId, aliases = aliases, type = type, title = item.title.takeIf(String::isNotBlank),
            ids = emptyMap(), source = null, mediaId = null, status = "", listedAt = 0L,
            poster = floppyPosterUrl(item.poster)
        )
        val overlay = FloppyLibraryOverlay(type, aliases + base.aliases, desired?.let { base.copy(status = it.wire) }, now())
        overlays.update { it + overlay }
        syncRepository.refresh(TrackingRefreshIntent.INVALIDATED)
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = syncRepository.refresh(intent)

    private fun statusEntry(item: LibraryEntryInput, current: FloppyLibraryRow?, status: String): JsonObject {
        val ids = LinkedHashMap<String, String>()
        current?.ids?.forEach { (name, value) -> if (name in ID_NAMES && value.isNotBlank()) ids[name] = value }
        val imdb = item.imdbId ?: item.itemId.takeIf { it.length > 2 && it.startsWith("tt", ignoreCase = true) && ':' !in it }
        val tmdb = item.tmdbId?.toString() ?: item.itemId.takeIf { it.startsWith("tmdb:") }?.substringAfter(':')
        imdb?.takeIf(String::isNotBlank)?.let { ids.putIfAbsent("imdb", it) }
        tmdb?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { ids.putIfAbsent("tmdb", it) }
        val source = current?.source
        val mediaId = current?.mediaId
        require(ids.isNotEmpty() || (source != null && mediaId != null)) { "Floppy cannot identify this title" }
        return buildJsonObject {
            if (ids.isNotEmpty()) put("ids", buildJsonObject { ids.forEach { (name, value) -> put(name, value) } })
            if (source != null && mediaId != null) {
                put("source", source)
                put("media_id", mediaId)
            }
            put("status", status)
        }
    }

    private fun LibraryEntryInput.aliases(): Set<String> =
        setOfNotNull(itemId, imdbId?.takeIf(String::isNotBlank), tmdbId?.let { "tmdb:$it" })

    private companion object {
        val ID_NAMES = setOf("tmdb", "imdb", "tvdb")
        val REASON_CODE = Regex("[a-z0-9_]{1,40}")
    }
}
