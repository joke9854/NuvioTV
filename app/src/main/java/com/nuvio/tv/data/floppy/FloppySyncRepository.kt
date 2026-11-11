package com.nuvio.tv.data.floppy

import android.util.Log
import com.nuvio.tv.core.tracking.TrackingRefreshGate
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val PAGE_LIMIT = 500
private const val PLAYBACK_LIMIT = 200
private const val MAX_PAGES = 10_000
private const val LOG_TAG = "FloppySync"

/** The changes feed no longer reaches back to our cursor; only a fresh snapshot can resume. */
private class FloppyCursorExpired : Exception("Floppy change cursor expired")

/**
 * Reads the user's watched history and Continue Watching from Floppy.
 *
 * History: one full snapshot, then the changes feed from the snapshot's cursor. Items and the cursor
 * they cover are persisted in one write, and the cursor never moves past a page that was not applied.
 * Absence from a page is never a delete; only an item reported as no longer in the library is removed.
 * Everything is dropped when the account, server or profile generation changes.
 */
class FloppySyncRepository(
    private val storage: FloppySyncStorage,
    private val auth: FloppyAuthStore,
    private val api: FloppyApiClient,
    private val activeProfileId: StateFlow<Int>,
    private val coroutineScope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val gate = TrackingRefreshGate()
    private val mutableState = MutableStateFlow(FloppySyncState())
    private val mutableProjection = MutableStateFlow(FloppyProgressState())
    private var lastSeenScope: FloppyAuthScope? = null
    val state = mutableState.asStateFlow()
    internal val progressState = mutableProjection.asStateFlow()

    init {
        coroutineScope.launch {
            combine(activeProfileId, auth.state) { profileId, authorization ->
                Triple(profileId, authorization.scope, authorization.isAuthenticated to authorization.serverUrl)
            }.distinctUntilChanged().collectLatest {
                dropStaleGeneration()
                if (!matchesCurrent(mutableState.value.scope)) clearPublished()
                try {
                    ensureLoaded()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (matchesCurrent(auth.scope())) mutableState.value = FloppySyncState(
                        scope = auth.scope(), error = error.toFloppySyncError()
                    )
                }
            }
        }
    }

    fun currentScope(): FloppyAuthScope = auth.scope().also(::checkScope)

    fun currentSnapshot(): FloppySyncSnapshot? = mutableState.value.takeIf { matchesCurrent(it.scope) }?.snapshot

    internal fun currentProjection(): FloppyProgressProjection = mutableProjection.value
        .takeIf { matchesCurrent(it.scope) && auth.state.value.isAuthenticated }?.projection
        ?: FloppyProgressProjection.Empty

    suspend fun ensureLoaded() = withContext(dispatcher) { mutex.withLock { loadCurrent() } }

    fun refreshAsync(intent: TrackingRefreshIntent) {
        coroutineScope.launch { refresh(intent) }
    }

    suspend fun refresh(intent: TrackingRefreshIntent) = withContext(dispatcher) {
        val scope = auth.scope()
        gate.runIfNeeded(scope.generation, shouldRun = {
            auth.state.value.isAuthenticated && matchesCurrent(scope) && shouldRefresh(intent)
        }) {
            mutex.withLock {
                checkScope(scope)
                val attemptedAt = now()
                try {
                    val snapshot = loadCurrent() ?: return@withLock
                    mutableState.value = mutableState.value.copy(
                        isLoading = true, error = null, retryAtEpochMs = null, attemptedAtEpochMs = attemptedAt
                    )
                    synchronize(scope, snapshot)
                    if (matchesCurrent(scope)) mutableState.value = mutableState.value.copy(isLoading = false)
                } catch (error: CancellationException) {
                    if (matchesCurrent(scope)) mutableState.value = mutableState.value.copy(isLoading = false)
                    throw error
                } catch (error: Exception) {
                    if (matchesCurrent(scope)) mutableState.value = mutableState.value.copy(
                        scope = scope,
                        isLoading = false,
                        error = error.toFloppySyncError(),
                        retryAtEpochMs = (error as? FloppyApiError.RateLimited)?.retryAfterSeconds
                            ?.let { attemptedAt + it * 1_000L },
                        attemptedAtEpochMs = attemptedAt
                    )
                }
            }
        }
    }

    /**
     * Clears the resume position on the server, then re-reads the positions. [contentId] is the show's
     * for an episode. A 404 means the server held nothing to clear and counts as done. Nothing is sent
     * when [contentId] carries no id Floppy can resolve; the position then stays in the server's list.
     */
    suspend fun clearPlayback(contentId: String, season: Int?, episode: Int?) = withContext(dispatcher) {
        val scope = auth.scope()
        if ((season == null) != (episode == null)) return@withContext
        val ids = parseTrackingExternalIds(contentId).toFloppyIds() ?: return@withContext
        val body = buildJsonObject {
            put("media_type", if (season != null) "episode" else "movie")
            put("ids", ids)
            if (season != null && episode != null) {
                put("season_number", season)
                put("episode_number", episode)
            }
        }
        checkScope(scope)
        try {
            api.delete(scope, "/api/v1/playback/progress/", body)
        } catch (_: FloppyApiError.NotFound) {
            // Nothing to clear on the server.
        }
        mutex.withLock {
            checkScope(scope)
            val snapshot = loadCurrent() ?: return@withLock
            val playback = readPlayback(scope)
            commit(scope, snapshot.copy(playback = playback))
        }
    }

    private suspend fun synchronize(scope: FloppyAuthScope, start: FloppySyncSnapshot) {
        var snapshot = start
        if (snapshot.connection == null) {
            // Registering turns the change log on, so it comes before the first snapshot.
            snapshot = snapshot.copy(connection = register(scope))
            commit(scope, snapshot)
        }
        if (snapshot.cursor == null) snapshot = takeSnapshot(scope, snapshot)
        snapshot = try {
            followChanges(scope, snapshot)
        } catch (_: FloppyCursorExpired) {
            // The log no longer reaches our cursor: start over from a fresh snapshot.
            followChanges(scope, takeSnapshot(scope, snapshot))
        }
        val playback = readPlayback(scope)
        commit(scope, snapshot.copy(playback = playback, checkedAtEpochMs = now()))
    }

    private suspend fun register(scope: FloppyAuthScope): String {
        val response = api.post(
            scope,
            "/api/v1/sync/connections/",
            buildJsonObject {
                put("client", "nuvio")
                put("instance_key", auth.installId())
                put("label", "Nuvio TV")
            }
        )
        checkScope(scope)
        return decode<FloppyConnectionResponse>(response.body).connection?.takeIf(String::isNotBlank)
            ?: throw FloppyApiError.InvalidResponse()
    }

    /**
     * Reads the whole library. The cursor is the first page's, so a change made while paging is still
     * delivered by the feed afterwards. Nothing is stored until every page has been read.
     */
    private suspend fun takeSnapshot(scope: FloppyAuthScope, base: FloppySyncSnapshot): FloppySyncSnapshot {
        val items = LinkedHashMap<FloppyItemKey, FloppySyncItem>()
        var cursor: Long? = null
        var after = 0L
        var pages = 0
        while (true) {
            val response = api.get(scope, "/api/v1/sync/items/snapshot/?after=$after&limit=$PAGE_LIMIT")
            checkScope(scope)
            val page = decode<FloppySnapshotPage>(response.body)
            if (cursor == null) cursor = page.cursor
            page.results.filter(::isKnownItem).filterNot(FloppySyncItem::isRemoved).forEach { items[it.key] = it }
            val next = page.nextAfter ?: break
            if (next <= after || ++pages > MAX_PAGES) throw FloppyApiError.InvalidResponse()
            after = next
        }
        val snapshot = base.copy(cursor = cursor, items = items.values.toList())
        commit(scope, snapshot)
        return snapshot
    }

    /**
     * Applies the feed page by page. Each page is stored together with its cursor before the next one
     * is requested, so a failure leaves the cursor at the last page that was applied.
     */
    private suspend fun followChanges(scope: FloppyAuthScope, base: FloppySyncSnapshot): FloppySyncSnapshot {
        var snapshot = base
        var pages = 0
        while (true) {
            val cursor = snapshot.cursor ?: throw FloppyApiError.InvalidResponse()
            val response = api.get(
                scope, "/api/v1/sync/items/changes/?cursor=$cursor&limit=$PAGE_LIMIT", returnConflict = true
            )
            checkScope(scope)
            if (response.status == 409) {
                if (response.errorCode() == "cursor_expired") throw FloppyCursorExpired()
                response.requireSuccess()
            }
            val page = decode<FloppyChangesPage>(response.body)
            if (page.cursor < cursor || (page.hasMore && page.cursor == cursor) || ++pages > MAX_PAGES) {
                throw FloppyApiError.InvalidResponse()
            }
            val merged = LinkedHashMap<FloppyItemKey, FloppySyncItem>(snapshot.items.size + page.results.size)
            snapshot.items.forEach { merged[it.key] = it }
            for (item in page.results) {
                if (!isKnownItem(item)) continue
                if (item.isRemoved) merged.remove(item.key) else merged[item.key] = item
            }
            snapshot = snapshot.copy(items = merged.values.toList(), cursor = page.cursor)
            commit(scope, snapshot)
            if (!page.hasMore) return snapshot
        }
    }

    private suspend fun readPlayback(scope: FloppyAuthScope): List<FloppyPlayback> {
        val entries = ArrayList<FloppyPlayback>()
        var offset = 0
        var pages = 0
        while (true) {
            val response = api.get(
                scope,
                "/api/v1/playback/progress/?completed=false&media_type=movie,episode&limit=$PLAYBACK_LIMIT&offset=$offset"
            )
            checkScope(scope)
            val page = decode<FloppyPlaybackPage>(response.body)
            entries += page.results
            if (page.pagination.next == null || page.results.isEmpty()) return entries
            offset += page.results.size
            if (++pages > MAX_PAGES) throw FloppyApiError.InvalidResponse()
        }
    }

    private fun isKnownItem(item: FloppySyncItem) = item.type == "movie" || item.type == "episode" || item.type == "show"

    private suspend fun loadCurrent(): FloppySyncSnapshot? {
        val scope = auth.scope()
        checkScope(scope, requireAuthorization = false)
        if (!auth.state.value.isAuthenticated) {
            clearPublished()
            storage.remove(scope.profileId) { checkScope(scope, requireAuthorization = false) }
            return null
        }
        val server = auth.serverAddress()?.baseUrl ?: return null
        val current = mutableState.value
        if (current.scope == scope && current.snapshot?.serverUrl == server) return current.snapshot
        val persisted = storage.load(scope.profileId)
            ?.let { payload -> runCatching { json.decodeFromString<FloppySyncSnapshot>(payload) }.getOrNull() }
            ?.takeIf { it.serverUrl == server }
        checkScope(scope)
        val projection = runCatching { FloppyProgressProjection(persisted ?: FloppySyncSnapshot(server)) }.getOrNull()
        val usable = if (projection != null && persisted != null) persisted else FloppySyncSnapshot(server)
        publish(scope, usable, projection ?: FloppyProgressProjection.Empty)
        return usable
    }

    private suspend fun commit(scope: FloppyAuthScope, snapshot: FloppySyncSnapshot) {
        checkScope(scope)
        val payload = json.encodeToString(snapshot)
        storage.save(scope.profileId, payload) { checkScope(scope) }
        checkScope(scope)
        publish(scope, snapshot, FloppyProgressProjection(snapshot))
    }

    private fun publish(scope: FloppyAuthScope, snapshot: FloppySyncSnapshot, projection: FloppyProgressProjection) {
        if (projection.unresolvedCount > 0) {
            runCatching { Log.d(LOG_TAG, "floppy skipped ${projection.unresolvedCount} items without a usable id") }
        }
        mutableProjection.value = FloppyProgressState(scope, projection)
        mutableState.value = mutableState.value.copy(scope = scope, snapshot = snapshot)
    }

    /** A new generation for the same profile means another account or server: its data is not ours. */
    private suspend fun dropStaleGeneration() {
        val scope = auth.scope()
        val previous = lastSeenScope
        lastSeenScope = scope
        if (previous == null || previous.profileId != scope.profileId || previous.generation == scope.generation) return
        try {
            storage.remove(scope.profileId) { checkScope(scope, requireAuthorization = false) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The server address is stored with the snapshot, so a stale one is also rejected when loaded.
        }
        clearPublished()
    }

    private fun shouldRefresh(intent: TrackingRefreshIntent): Boolean {
        val current = mutableState.value.takeIf { matchesCurrent(it.scope) } ?: return true
        val now = now()
        if (current.retryAtEpochMs?.let { it > now } == true) return false
        if (intent != TrackingRefreshIntent.AUTOMATIC) return true
        if (current.error != null) return current.attemptedAtEpochMs?.let { now - it !in 0 until ERROR_RETRY_MS } ?: true
        return current.snapshot?.checkedAtEpochMs?.let { now - it !in 0 until AUTOMATIC_INTERVAL_MS } ?: true
    }

    private fun checkScope(scope: FloppyAuthScope, requireAuthorization: Boolean = true) {
        if (!matchesCurrent(scope)) throw CancellationException("Floppy account changed")
        if (requireAuthorization && !auth.state.value.isAuthenticated) {
            throw FloppyAuthException(FloppyAuthError.AUTHORIZATION_REVOKED)
        }
    }

    private fun matchesCurrent(scope: FloppyAuthScope?): Boolean =
        scope != null && scope.profileId == activeProfileId.value && auth.isCurrent(scope)

    private fun clearPublished() {
        mutableProjection.value = FloppyProgressState()
        mutableState.value = FloppySyncState()
    }

    private fun FloppyHttpResponse.errorCode(): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private inline fun <reified T> decode(body: String): T =
        runCatching { json.decodeFromString<T>(body) }.getOrElse { throw FloppyApiError.InvalidResponse() }

    companion object {
        const val AUTOMATIC_INTERVAL_MS = 15L * 60 * 1_000
        const val ERROR_RETRY_MS = 60_000L
    }
}
