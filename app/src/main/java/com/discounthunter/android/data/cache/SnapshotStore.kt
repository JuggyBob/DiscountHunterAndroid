package com.discounthunter.android.data.cache

import com.discounthunter.android.core.model.Deal
import com.discounthunter.android.core.model.GameIdentity
import com.discounthunter.android.core.model.PricePoint
import com.discounthunter.android.core.model.ScheduleStatus
import com.discounthunter.android.core.model.StoreId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Per-day snapshots of the deal feed, so browsing to a past date still shows something useful.
 *
 * The desktop build kept these under `%LOCALAPPDATA%`; here they live in `filesDir` because they
 * are user-visible history rather than a disposable cache. Only the fields a card needs are
 * stored — a snapshot is a browsing aid, not a second source of truth.
 */
class SnapshotStore(
    private val directory: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE

    suspend fun save(date: LocalDate, deals: List<Deal>, country: String = DEFAULT_COUNTRY) = withContext(io) {
        if (deals.isEmpty()) return@withContext
        try {
            if (!directory.isDirectory) directory.mkdirs()
            val array = JsonArray(
                deals.take(MAX_SNAPSHOT_DEALS).map { deal ->
                    buildJsonObject {
                        put("id", deal.id)
                        put("title", deal.title)
                        put("thumb", deal.thumbUrl)
                        put("salePrice", deal.salePrice)
                        put("normalPrice", deal.normalPrice)
                        put("savings", deal.savingsPercent)
                        put("store", deal.store.id)
                        put("identity", deal.identity?.key.orEmpty())
                        put("url", deal.storeUrl)
                        put("schedule", deal.scheduleStatus.name)
                    }
                },
            )
            fileFor(date, country).writeText(array.toString())
        } catch (_: Exception) {
            // History is a nicety; failing to write it must not disturb the feed.
        }
    }

    suspend fun load(date: LocalDate, country: String = DEFAULT_COUNTRY): List<Deal>? = withContext(io) {
        try {
            val file = fileFor(date, country)
            if (!file.isFile) return@withContext null
            val array = Json.parseToJsonElement(file.readText()).jsonArray
            val deals = array.mapNotNull { element -> parseDeal(element as? JsonObject) }
            deals.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Prices this device has actually *seen* for [identity], oldest day first.
     *
     * This is the one price history the app can honestly produce: the daily snapshots it already
     * writes for the calendar. It needs no scraping, works offline, and gets more useful the more
     * days the user browses. The cheapest price recorded on each day wins, because a day can hold
     * several rows for the same game across stores.
     */
    suspend fun priceHistory(identity: GameIdentity, country: String = DEFAULT_COUNTRY): List<PricePoint> = withContext(io) {
        try {
            val perDay = mutableMapOf<Long, Double>()
            val files = filesFor(country)
            for (file in files) {
                if (!file.isFile || !file.name.endsWith(".json")) continue
                val day = runCatching { LocalDate.parse(file.name.removeSuffix(".json"), formatter) }
                    .getOrNull()
                    ?.toEpochDay()
                    ?: continue
                val array = runCatching {
                    Json.parseToJsonElement(file.readText()).jsonArray
                }.getOrNull() ?: continue
                for (element in array) {
                    val deal = parseDeal(element as? JsonObject) ?: continue
                    if (deal.identity != identity || deal.salePrice < 0) continue
                    val current = perDay[day]
                    if (current == null || deal.salePrice < current) perDay[day] = deal.salePrice
                }
            }
            perDay.entries.sortedBy { it.key }.map { PricePoint(it.key, it.value) }
        } catch (_: Exception) {
            emptyList()
        }
    }
    /** Drops snapshots older than [keepDays]; called opportunistically after a save. */
    suspend fun prune(keepDays: Long = 30) = withContext(io) {
        try {
            val cutoff = System.currentTimeMillis() - keepDays * 24 * 60 * 60 * 1000
            // Every region folder, not just the snapshot directory itself. These three methods used
            // to walk one flat level and they were all written when it *was* one flat level.
            regionDirectories().forEach { region ->
                region.listFiles()?.forEach { file ->
                    if (file.isFile && file.lastModified() < cutoff) runCatching { file.delete() }
                }
            }
        } catch (_: Exception) {
            // Optional maintenance.
        }
    }

    suspend fun sizeBytes(): Long = withContext(io) {
        try {
            regionDirectories()
                .flatMap { region -> region.listFiles()?.filter { it.isFile }.orEmpty() }
                .sumOf { it.length() }
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Deletes every snapshot, for every region.
     *
     * This is what "Delete all app data" ends up calling, so it has to actually remove the files.
     * `File.delete` on a non-empty directory fails silently on Android, which meant the day
     * snapshots survived a delete-everything the moment the region folders existed — the user was
     * told their data was gone while a month of price history stayed on disk.
     */
    suspend fun clear() = withContext(io) {
        try {
            regionDirectories().forEach { region -> runCatching { region.deleteRecursively() } }
            // Remove the now-empty region folders too, so the tree matches what a fresh install has.
            runCatching { directory.listFiles()?.forEach { runCatching { it.delete() } } }
        } catch (_: Exception) {
            // Nothing to do.
        }
    }

    /** Every region folder that currently exists. */
    private fun regionDirectories(): List<File> =
        directory.listFiles()?.filter { it.isDirectory }.orEmpty()

    /**
     * Snapshot files for one region.
     *
     * Snapshots are scoped by region because store prices and availability are per-region. A
     * snapshot taken for one currency is simply the wrong data once the user switches to another,
     * and it was being served anyway: the file was named after the day alone, so switching to EUR
     * still returned the USD figures the app had cached earlier that session. One folder per region
     * makes it impossible to hand back prices from a currency the user is no longer using.
     */
    private fun filesFor(country: String): Array<File> {
        val dir = regionDirectory(country)
        return dir.listFiles() ?: emptyArray()
    }

    private fun regionDirectory(country: String): File =
        File(directory, country.trim().uppercase().ifEmpty { DEFAULT_COUNTRY })

    private fun fileFor(date: LocalDate, country: String): File {
        val dir = regionDirectory(country)
        if (!dir.isDirectory) dir.mkdirs()
        return File(dir, "${formatter.format(date)}.json")
    }

    private fun parseDeal(json: JsonObject?): Deal? {
        if (json == null) return null
        return try {
            val store = StoreId.fromId(json.int("store") ?: return null) ?: return null
            val identityKey = json.str("identity").orEmpty()
            val identity = if (identityKey.isBlank()) {
                null
            } else {
                val storeName = identityKey.substringBefore(':')
                val id = identityKey.substringAfter(':')
                StoreId.entries.firstOrNull { it.name.equals(storeName, ignoreCase = true) }
                    ?.takeIf { id.isNotBlank() }
                    ?.let { com.discounthunter.android.core.model.GameIdentity(it, id) }
            }
            Deal(
                id = json.str("id").orEmpty(),
                title = json.str("title").orEmpty(),
                thumbUrl = json.str("thumb").orEmpty(),
                salePrice = json.double("salePrice") ?: 0.0,
                normalPrice = json.double("normalPrice") ?: 0.0,
                savingsPercent = json.double("savings") ?: 0.0,
                store = store,
                identity = identity,
                storeUrl = json.str("url").orEmpty(),
                scheduleStatus = runCatching {
                    ScheduleStatus.valueOf(json.str("schedule").orEmpty())
                }.getOrDefault(ScheduleStatus.NONE),
            )
        } catch (_: Exception) {
            // One unreadable row must not invalidate the whole snapshot.
            null
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }

    private companion object {
        const val MAX_SNAPSHOT_DEALS = 200
        /** Region used when a caller does not name one; matches the app's default currency. */
        const val DEFAULT_COUNTRY = "US"
    }
}
