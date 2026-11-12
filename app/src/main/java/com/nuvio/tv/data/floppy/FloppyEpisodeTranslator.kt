package com.nuvio.tv.data.floppy

import android.util.Log
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingMediaReference
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val TRANSLATE_BATCH_SIZE = 400
internal const val TRANSLATE_FROM_TRACKER = "tracker"
internal const val TRANSLATE_FROM_TMDB = "tmdb"
private const val CAPABILITY_TTL_MS = 10L * 60 * 1_000
private const val CACHE_LIMIT = 5_000

/** A season and episode number pair, in whichever numbering the surrounding code says. */
@Serializable
data class FloppyEpisodeCoords(val season: Int, val episode: Int)

/** One answer from `/episodes/translate/`. [status] is the server's, e.g. `same` or `translated`. */
internal class FloppyEpisodeTranslation(
    val status: String,
    val tmdb: FloppyEpisodeCoords?,
    val tvdb: FloppyEpisodeCoords?
)

/** True when Nuvio numbers this show's episodes the way a tracker (IMDb/TVDB) does: its content id is an IMDb id. */
internal fun isTrackerNumbered(contentId: String?): Boolean =
    contentId != null && contentId.startsWith("tt", ignoreCase = true)

/**
 * Client side of Floppy's read-only episode translate endpoint. Never throws for a failed lookup: a
 * caller that gets nothing back keeps the coordinates it had. Successful answers are cached in memory.
 */
@Singleton
class FloppyEpisodeTranslator internal constructor(
    private val store: FloppyAuthStore,
    private val api: FloppyApiClient,
    private val now: () -> Long
) {
    @Inject
    constructor(store: FloppyAuthStore, api: FloppyApiClient) : this(store, api, System::currentTimeMillis)

    private data class CacheKey(
        val source: String,
        val mediaId: String,
        val direction: String,
        val season: Int,
        val episode: Int
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var cacheScope: FloppyAuthScope? = null
    private val cache = HashMap<CacheKey, FloppyEpisodeTranslation>()
    private var capable: Boolean? = null
    private var capableAt = 0L

    /**
     * Writes: [medias] with the episode coordinates of tracker-numbered shows replaced by Floppy's (TMDB)
     * numbers where the server translated them. Same size and order as the input; nothing is merged.
     */
    internal suspend fun toFloppyNumbering(medias: List<TrackingMediaReference>): List<TrackingMediaReference> {
        val result = medias.toMutableList()
        val byShow = LinkedHashMap<Long, MutableList<Int>>()
        medias.forEachIndexed { index, media ->
            val tmdb = media.ids.tmdb ?: return@forEachIndexed
            val episode = media.episode ?: return@forEachIndexed
            if (episode.season == null || !isTrackerNumbered(media.catalog?.contentId)) return@forEachIndexed
            byShow.getOrPut(tmdb) { ArrayList() } += index
        }
        if (byShow.isEmpty()) return result
        val scope = store.scope()
        for ((tmdb, indexes) in byShow) {
            val coords = indexes.map { FloppyEpisodeCoords(medias[it].episode!!.season!!, medias[it].episode!!.number) }
            val answers = lookup(scope, "tmdb", tmdb.toString(), TRANSLATE_FROM_TRACKER, coords)
            indexes.forEachIndexed { position, index ->
                val target = answers[coords[position]]?.takeIf { it.status == "translated" }?.tmdb ?: return@forEachIndexed
                val media = medias[index]
                result[index] = media.copy(
                    episode = media.episode!!.copy(season = target.season, number = target.episode)
                )
            }
        }
        return result
    }

    /** Convenience for a single reference. */
    internal suspend fun toFloppyNumbering(media: TrackingMediaReference): TrackingMediaReference =
        toFloppyNumbering(listOf(media)).first()

    /**
     * Reads: asks about TMDB-numbered [coords] of the show at [source]/[mediaId]. Only answered
     * coordinates are in the result.
     */
    internal suspend fun fromTmdb(
        scope: FloppyAuthScope,
        source: String,
        mediaId: String,
        coords: List<FloppyEpisodeCoords>
    ): Map<FloppyEpisodeCoords, FloppyEpisodeTranslation> =
        lookup(scope, source, mediaId, TRANSLATE_FROM_TMDB, coords)

    private suspend fun lookup(
        scope: FloppyAuthScope,
        source: String,
        mediaId: String,
        direction: String,
        coords: List<FloppyEpisodeCoords>
    ): Map<FloppyEpisodeCoords, FloppyEpisodeTranslation> {
        val answers = HashMap<FloppyEpisodeCoords, FloppyEpisodeTranslation>()
        val missing = LinkedHashSet<FloppyEpisodeCoords>()
        synchronized(lock) {
            resetIfScopeChanged(scope)
            for (coord in coords) {
                val hit = cache[CacheKey(source, mediaId, direction, coord.season, coord.episode)]
                if (hit != null) answers[coord] = hit else missing += coord
            }
        }
        if (missing.isEmpty() || !isCapable(scope)) return answers
        for (batch in missing.chunked(TRANSLATE_BATCH_SIZE)) {
            val fetched = fetch(scope, source, mediaId, direction, batch) ?: continue
            synchronized(lock) {
                if (cacheScope == scope) {
                    if (cache.size + fetched.size > CACHE_LIMIT) cache.clear()
                    fetched.forEachIndexed { index, answer ->
                        cache[CacheKey(source, mediaId, direction, batch[index].season, batch[index].episode)] = answer
                    }
                }
            }
            fetched.forEachIndexed { index, answer -> answers[batch[index]] = answer }
        }
        return answers
    }

    private suspend fun fetch(
        scope: FloppyAuthScope,
        source: String,
        mediaId: String,
        direction: String,
        batch: List<FloppyEpisodeCoords>
    ): List<FloppyEpisodeTranslation>? {
        val path = "/api/v1/media/tv/${encode(source)}/${encode(mediaId)}/episodes/translate/"
        val body = buildJsonObject {
            put("from", direction)
            put("episodes", JsonArray(batch.map { coord ->
                buildJsonObject {
                    put("season", coord.season)
                    put("episode", coord.episode)
                }
            }))
        }
        return try {
            val response = api.post(scope, path, body)
            val results = json.parseToJsonElement(response.body).jsonObject["results"]?.jsonArray
            if (results == null || results.size != batch.size) {
                debug("floppy translate skipped reason=invalid_response")
                null
            } else {
                results.map { parseAnswer(it.jsonObject) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            debug("floppy translate failed direction=$direction reason=${error::class.simpleName}")
            null
        }
    }

    private fun parseAnswer(row: JsonObject) = FloppyEpisodeTranslation(
        status = row["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        tmdb = row["tmdb"].coords(),
        tvdb = row["tvdb"].coords()
    )

    private fun kotlinx.serialization.json.JsonElement?.coords(): FloppyEpisodeCoords? {
        val obj = this as? JsonObject ?: return null
        val season = (obj["season"] as? JsonPrimitive)?.intOrNull ?: return null
        val episode = (obj["episode"] as? JsonPrimitive)?.intOrNull ?: return null
        return FloppyEpisodeCoords(season, episode)
    }

    private suspend fun isCapable(scope: FloppyAuthScope): Boolean {
        synchronized(lock) {
            val known = capable
            if (known != null && now() - capableAt in 0 until CAPABILITY_TTL_MS) return known
        }
        val value = try {
            val response = api.get(scope, "/api/v1/info/")
            json.parseToJsonElement(response.body).jsonObject["api_extensions"]?.jsonObject
                ?.get("cinetrack_episode_translate_v1")?.jsonPrimitive?.contentOrNull == "true"
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
        synchronized(lock) {
            if (cacheScope == scope) {
                capable = value
                capableAt = now()
            }
        }
        return value
    }

    private fun resetIfScopeChanged(scope: FloppyAuthScope) {
        if (cacheScope == scope) return
        cacheScope = scope
        cache.clear()
        capable = null
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun debug(message: String) {
        runCatching { Log.d(TRACKING_SCROBBLE_DIAGNOSTIC_TAG, message) }
    }
}
