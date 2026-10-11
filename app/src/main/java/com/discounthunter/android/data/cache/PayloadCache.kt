package com.discounthunter.android.data.cache

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A two-tier TTL cache for raw store payloads.
 *
 * Raw text is stored rather than parsed objects, so the cache stays independent of the models and
 * a parser improvement never requires invalidating anything. The cache is best effort by design:
 * a full disk or an unreadable file degrades to a live fetch, never to a crash.
 *
 * Payloads live in `cacheDir`, which Android may reclaim under pressure. Snapshots — the per-day
 * deal history — live in `filesDir` instead, because losing those would lose data the user can
 * still browse offline.
 */
class PayloadCache(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * The in-memory tier, so scrolling back and forth over a loaded feed never re-reads disk.
     *
     * The write timestamp travels with the body. A memory hit that skipped the TTL check would let a
     * stale payload outlive its lifetime for as long as the process lived — which on a
     * frequently-used app is hours, and would leave the user looking at yesterday's prices with no
     * way to tell.
     */
    private val memory = LinkedHashMap<String, Entry>(64, 0.75f, true)

    /** Approximate character count held in [memory], used to bound the in-memory tier. */
    private var memoryWeight: Int = 0

    private class Entry(val storedAt: Long, val body: String)

    /** Time-to-live values, chosen per payload class. */
    object Ttl {
        /** Feeds the user expects to be live (featured deals, Epic giveaways). */
        const val SHORT_MILLIS = 20 * 60 * 1000L

        /** A specific app's metadata. */
        const val DETAILS_MILLIS = 30 * 60 * 1000L

        /** Store search results. */
        const val SEARCH_MILLIS = 10 * 60 * 1000L

        /** Scraped store pages: large, slow to fetch, and rarely change. */
        const val PAGE_MILLIS = 24 * 60 * 60 * 1000L

        /** A cheap page title probe used to validate a slug. */
        const val PROBE_MILLIS = 7 * 24 * 60 * 60 * 1000L
    }

    /** Returns the cached payload, or null when absent, expired, empty or unreadable. */
    suspend fun read(key: CacheKey, ttlMillis: Long): String? = withContext(io) {
        // In-memory tier, still subject to the TTL.
        val cached = synchronized(this@PayloadCache) { memory[key.digest] }
        if (cached != null) {
            val fresh = clock() - cached.storedAt <= ttlMillis && cached.body.isNotEmpty()
            if (fresh) return@withContext cached.body

            // Expired: drop it from memory *and* remove the disk file. Returning null while leaving
            // the file behind would leave a stale entry on disk indefinitely, because a later read
            // would keep hitting the (now missing) memory entry and never reach the file to delete it.
            forget(key.digest)
            runCatching { fileFor(key).delete() }
            return@withContext null
        }

        val file = fileFor(key)
        val envelope = try {
            if (!file.isFile) return@withContext null
            file.readText()
        } catch (_: Exception) {
            // An unreadable cache file is treated as a miss; the live fetch will overwrite it.
            runCatching { file.delete() }
            return@withContext null
        }

        // The envelope is "<storedAtMillis>\n<payload>"; the timestamp is written together with the
        // body so an interrupted write can never look like a fresh, valid entry.
        val storedAt = envelope.substringBefore('\n').toLongOrNull()
        if (storedAt == null) {
            runCatching { file.delete() }
            return@withContext null
        }
        if (clock() - storedAt > ttlMillis) {
            runCatching { file.delete() }
            return@withContext null
        }

        val body = envelope.substringAfter('\n', "")
        if (body.isEmpty()) return@withContext null
        remember(key.digest, storedAt, body)
        body
    }

    /**
     * Stores a payload. Failures are swallowed — caching must never break the app.
     *
     * An empty payload is not stored at all: caching "nothing" would make the next read look like a
     * live hit and hide a store that is genuinely returning nothing.
     */
    suspend fun write(key: CacheKey, payload: String) = withContext(io) {
        if (payload.isEmpty()) return@withContext
        val storedAt = clock()
        remember(key.digest, storedAt, payload)
        try {
            if (!directory.isDirectory) directory.mkdirs()
            val file = fileFor(key)
            // Write to a temporary file and rename, so a process death mid-write cannot leave a
            // half-written payload that later parses as valid-but-truncated content.
            val temp = File(directory, "${key.digest}.tmp")
            temp.writeText("$storedAt\n$payload")
            if (!temp.renameTo(file)) {
                file.writeText("$storedAt\n$payload")
                temp.delete()
            }
            schedulePruneIfNeeded()
        } catch (_: Exception) {
            // Best effort.
        }
    }

    /** Drops every cached payload. Used by the "clear cache" action in Settings. */
    suspend fun clear() = withContext(io) {
        synchronized(this@PayloadCache) {
            memory.clear()
            memoryWeight = 0
        }
        try {
            directory.listFiles()?.forEach { runCatching { it.delete() } }
        } catch (_: Exception) {
            // Nothing to do.
        }
    }

    suspend fun sizeBytes(): Long = withContext(io) {
        try {
            directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Exception) {
            0L
        }
    }

    private fun fileFor(key: CacheKey) = File(directory, "${key.digest}.payload")

    /** Stores a payload in the memory tier, evicting the least recently used entries. */
    @Synchronized
    private fun remember(digest: String, storedAt: Long, body: String) {
        val previous = memory.put(digest, Entry(storedAt, body))
        memoryWeight += body.length - (previous?.body?.length ?: 0)
        val iterator = memory.entries.iterator()
        while ((memory.size > MEMORY_ENTRIES || memoryWeight > MEMORY_CHARS) && iterator.hasNext()) {
            memoryWeight -= iterator.next().value.body.length
            iterator.remove()
        }
        if (memoryWeight < 0) memoryWeight = 0
    }

    /** Drops one entry from the memory tier. */
    @Synchronized
    private fun forget(digest: String) {
        memory.remove(digest)?.let { removed ->
            memoryWeight -= removed.body.length
            if (memoryWeight < 0) memoryWeight = 0
        }
    }

    @Synchronized
    private fun schedulePruneIfNeeded() {
        if (++writesSincePrune < PRUNE_EVERY_WRITES) return
        writesSincePrune = 0
        // Opportunistic, off the critical path; the cache works fine if pruning never runs.
        Thread { prune() }.apply { isDaemon = true }.start()
    }

    /** Drops the oldest payloads once the folder outgrows its budget. */
    private fun prune() {
        try {
            val files = directory.listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= BUDGET_BYTES) return
            val target = (total * 0.75).toLong()
            for (file in files.sortedBy { it.lastModified() }) {
                if (total <= target) break
                val length = file.length()
                if (file.delete()) total -= length
            }
        } catch (_: Exception) {
            // Pruning is optional.
        }
    }

    private companion object {
        const val MEMORY_ENTRIES = 48
        const val MEMORY_CHARS = 4 * 1024 * 1024
        const val BUDGET_BYTES = 96L * 1024 * 1024
        const val PRUNE_EVERY_WRITES = 40
        var writesSincePrune = 0
    }
}
