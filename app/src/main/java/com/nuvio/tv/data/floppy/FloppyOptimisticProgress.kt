package com.nuvio.tv.data.floppy

import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Positions saved on this device that Floppy may not have returned yet. Floppy is only re-read on a
 * sync, so without this the resume point and Continue Watching would lag until the next one.
 *
 * An entry shows only while it is newer than what Floppy reports for the same item, and only until it
 * expires: once a sync brings the server's copy (as new or newer), the server's copy wins.
 */
internal class FloppyOptimisticProgress(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val now: () -> Long = System::currentTimeMillis
) {
    private data class Key(val contentId: String, val season: Int?, val episode: Int?)
    private class Entry(val progress: WatchProgress, val expiresAtMs: Long)

    private val entries = MutableStateFlow<Map<Key, Entry>>(emptyMap())

    /** Changes whenever an entry is added or removed; combine with it to re-merge. */
    val changes: StateFlow<*> get() = entries.asStateFlow()

    fun put(progress: WatchProgress) {
        val key = Key(progress.contentId, progress.season, progress.episode)
        entries.update { it + (key to Entry(progress, now() + ttlMs)) }
    }

    fun remove(contentId: String, season: Int?, episode: Int?) {
        entries.update { current ->
            current.filterKeys { key ->
                when {
                    key.contentId != contentId -> true
                    season != null && episode != null -> key.season != season || key.episode != episode
                    else -> false
                }
            }
        }
    }

    fun clear() {
        entries.value = emptyMap()
    }

    /** [remote] with this device's newer positions laid over it, newest first. */
    fun merge(remote: List<WatchProgress>): List<WatchProgress> {
        val live = live()
        if (live.isEmpty()) return remote
        val merged = LinkedHashMap<Key, WatchProgress>()
        remote.forEach { merged[Key(it.contentId, it.season, it.episode)] = it }
        live.forEach { (key, progress) ->
            val server = merged[key]
            if (server == null || server.lastWatched < progress.lastWatched) merged[key] = progress
        }
        return merged.values.sortedByDescending(WatchProgress::lastWatched)
    }

    /** [remote] episode positions of [contentId] with this device's newer ones laid over them. */
    fun mergeEpisodes(
        contentId: String,
        remote: Map<Pair<Int, Int>, WatchProgress>
    ): Map<Pair<Int, Int>, WatchProgress> {
        val live = live().filterKeys { it.contentId == contentId && it.season != null && it.episode != null }
        if (live.isEmpty()) return remote
        val merged = remote.toMutableMap()
        live.forEach { (key, progress) ->
            val episode = key.season!! to key.episode!!
            val server = merged[episode]
            if (server == null || server.lastWatched < progress.lastWatched) merged[episode] = progress
        }
        return merged
    }

    private fun live(): Map<Key, WatchProgress> {
        val time = now()
        return entries.value.filterValues { it.expiresAtMs > time }.mapValues { it.value.progress }
    }

    companion object {
        const val DEFAULT_TTL_MS = 30L * 60 * 1_000
    }
}
