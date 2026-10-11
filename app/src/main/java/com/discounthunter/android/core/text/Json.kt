package com.discounthunter.android.core.text

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Null-tolerant readers for [JsonElement].
 *
 * Every store payload is treated as untrusted: a missing or wrongly-typed field yields a default
 * instead of throwing, so one malformed product can never abort a whole feed.
 *
 * The numeric readers are hand-written rather than delegating to `JsonPrimitive`'s built-ins
 * because stores routinely publish numbers *as strings* (`"discount": "-50"`,
 * `"final": "1234"`), and a strict accessor would silently drop them.
 */
object Json {

    /** Parses [text], returning null for malformed payloads. */
    fun parse(text: String): JsonElement? = try {
        kotlinx.serialization.json.Json.parseToJsonElement(text)
    } catch (_: Exception) {
        null
    }

    fun obj(element: JsonElement?, name: String): JsonObject? =
        (element as? JsonObject)?.get(name) as? JsonObject

    fun arr(element: JsonElement?, name: String): JsonArray? =
        (element as? JsonObject)?.get(name) as? JsonArray

    /** Enumerates an array property, yielding nothing when absent or the wrong kind. */
    fun items(element: JsonElement?, name: String): List<JsonElement> =
        arr(element, name)?.toList() ?: emptyList()

    /** Treats [element] itself as an array, returning an empty list when it is not one. */
    fun list(element: JsonElement?): List<JsonElement> =
        (element as? JsonArray)?.toList() ?: emptyList()

    /** The named property, or null when absent or JSON `null`. */
    fun element(element: JsonElement?, name: String): JsonElement? =
        (element as? JsonObject)?.get(name)?.takeUnless { it is JsonNull }

    /** Navigates a chain of object properties, e.g. `path(root, "price", "totalPrice")`. */
    fun path(element: JsonElement?, vararg names: String): JsonElement? {
        var current = element ?: return null
        for (name in names) {
            current = (current as? JsonObject)?.get(name)?.takeUnless { it is JsonNull } ?: return null
        }
        return current
    }

    fun str(element: JsonElement?, name: String, fallback: String = ""): String =
        when (val primitive = primitive(element, name)) {
            null -> fallback
            else -> primitive.contentOrNull
                ?: primitive.doubleOrNull?.let { numberToString(it) }
                ?: fallback
        }

    fun dbl(element: JsonElement?, name: String, fallback: Double = 0.0): Double =
        toDouble(primitive(element, name)) ?: fallback

    fun int(element: JsonElement?, name: String, fallback: Int = 0): Int =
        toDouble(primitive(element, name))?.let {
            if (it.isFinite() && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) it.toInt() else fallback
        } ?: fallback

    fun bool(element: JsonElement?, name: String, fallback: Boolean = false): Boolean =
        when (val primitive = primitive(element, name)) {
            null -> fallback
            else -> when (primitive.contentOrNull?.lowercase()) {
                "true" -> true
                "false" -> false
                else -> primitive.doubleOrNull?.let { it != 0.0 } ?: fallback
            }
        }

    /** Walks a chain of properties and reads a double, e.g. `pathDouble(root, 0.0, "price", "final")`. */
    fun pathDouble(element: JsonElement?, fallback: Double, vararg names: String): Double =
        toDouble(path(element, *names) as? JsonPrimitive) ?: fallback

    private fun primitive(element: JsonElement?, name: String): JsonPrimitive? =
        element(element, name) as? JsonPrimitive

    /** Accepts both numeric literals and numeric strings, including store-formatted ones. */
    private fun toDouble(primitive: JsonPrimitive?): Double? {
        if (primitive == null || primitive is JsonNull) return null
        primitive.doubleOrNull?.let { return it }
        val text = primitive.contentOrNull ?: return null
        return text.trim().removeSuffix("%").removePrefix("-").let { TextTools.toDouble(it, Double.NaN) }
            .takeIf { !it.isNaN() }
    }

    /**
     * Collects a list of strings from an array of strings or objects. Objects use [innerProperty]
     * when given, otherwise `description` and then `name` — which covers the Steam and GOG payload
     * shapes with one helper.
     */
    fun stringList(
        element: JsonElement?,
        name: String,
        innerProperty: String? = null,
    ): List<String> = items(element, name).mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.contentOrNull?.trim()
            is JsonObject -> if (innerProperty != null) {
                str(item, innerProperty).trim()
            } else {
                str(item, "description").ifBlank { str(item, "name") }.trim()
            }
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }

    /** Parses an ISO-8601 timestamp to epoch millis, or null when it is missing/unparseable. */
    fun timestampMillis(element: JsonElement?, name: String): Long? {
        val text = str(element, name)
        if (text.isBlank()) return null
        return runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrNull()
    }

    /** Renders a whole double without a trailing `.0`, so ids read naturally. */
    private fun numberToString(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
