package com.discounthunter.android.alerts

import com.discounthunter.android.core.text.Json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Remembers what has already been announced.
 *
 * ## Why a file and not DataStore
 *
 * DataStore is right for settings the user changes. This is a small machine-written record of what
 * the app has already told the user, it is written from a background worker, and it must never
 * block a notification. A plain file, written atomically and read leniently, is a better fit: it
 * cannot deadlock the notification path behind a DataStore write.
 *
 * ## Failure is always safe
 *
 * A corrupt or missing file reads as [WatchState.initialised] `false`, which means the next check
 * re-establishes the baseline instead of announcing a flood. Losing the record costs at most one
 * missed change; it can never cause a wrong price to be announced.
 */
class WatchStateStore(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Reads the record, treating anything unreadable as "never checked". */
    suspend fun load(): WatchState = withContext(io) {
        if (!file.isFile) return@withContext WatchState()
        val text = runCatching { file.readText() }.getOrNull() ?: return@withContext WatchState()
        parse(text)
    }

    /** Writes the record. A failure here is logged and swallowed: the next check re-reads the old
     *  one, and the worst case is a repeat announcement rather than a crash in a background job. */
    suspend fun save(state: WatchState) = withContext(io) {
        runCatching {
            file.parentFile?.mkdirs()
            // Written to a sibling and moved into place, so a crash mid-write cannot leave a
            // half-written file that reads as corrupt.
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(serialize(state))
            if (!temp.renameTo(file)) {
                file.writeText(serialize(state))
                temp.delete()
            }
        }
    }

    private fun parse(text: String): WatchState {
        val root = Json.parse(text) as? JsonObject ?: return WatchState()
        val sales = mutableMapOf<String, String>()
        (root["announcedSales"] as? JsonObject)?.forEach { (key, value) ->
            val fingerprint = (value as? JsonPrimitive)?.content ?: return@forEach
            sales[key] = fingerprint
        }
        val free = LinkedHashSet<String>()
        (root["seenFreeGames"] as? JsonArray)?.forEach { element ->
            (element as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { free += it }
        }
        return WatchState(
            initialised = (root["initialised"] as? JsonPrimitive)?.content == "true",
            announcedSales = sales,
            seenFreeGames = free,
        )
    }

    private fun serialize(state: WatchState): String = buildString {
        append('{')
        append("\"initialised\":").append(state.initialised)
        append(",\"announcedSales\":{")
        var first = true
        for ((key, value) in state.announcedSales) {
            if (!first) append(',')
            first = false
            append('"').append(escape(key)).append("\":\"").append(escape(value)).append('"')
        }
        append("},\"seenFreeGames\":[")
        first = true
        for (key in state.seenFreeGames) {
            if (!first) append(',')
            first = false
            append('"').append(escape(key)).append('"')
        }
        append("]}")
    }

    /** Escapes the two characters that would break the JSON. Keys are store ids and fingerprints,
     *  so in practice they contain neither — but a store id with a quote in it must not corrupt
     *  the whole file. */
    private fun escape(value: String): String = buildString {
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }
}
