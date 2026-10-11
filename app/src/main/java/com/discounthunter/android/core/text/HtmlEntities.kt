package com.discounthunter.android.core.text

/**
 * A small HTML entity decoder.
 *
 * `Html.fromHtml` exists on Android but not in plain JVM unit tests, and the store payloads only
 * ever use a handful of named entities plus numeric references — so the app carries its own
 * decoder. That keeps every parser unit-testable on the JVM.
 */
object HtmlEntities {

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "hellip" to "\u2026", "mdash" to "\u2014", "ndash" to "\u2013",
        "lsquo" to "\u2018", "rsquo" to "\u2019", "ldquo" to "\u201C", "rdquo" to "\u201D",
        "bull" to "\u2022", "middot" to "\u00B7", "copy" to "\u00A9", "reg" to "\u00AE",
        "trade" to "\u2122", "deg" to "\u00B0", "euro" to "\u20AC", "pound" to "\u00A3",
        "yen" to "\u00A5", "cent" to "\u00A2", "times" to "\u00D7", "laquo" to "\u00AB",
        "raquo" to "\u00BB", "eacute" to "\u00E9", "egrave" to "\u00E8", "agrave" to "\u00E0",
        "uuml" to "\u00FC", "ouml" to "\u00F6", "auml" to "\u00E4", "szlig" to "\u00DF",
        "ntilde" to "\u00F1", "aacute" to "\u00E1", "iacute" to "\u00ED", "oacute" to "\u00F3",
        "uacute" to "\u00FA", "a" to "\u00E1",
    )

    private val ENTITY = Regex("&(#x?[0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]*);")

    fun decode(input: String): String {
        if ('&' !in input) return input
        return ENTITY.replace(input) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x", ignoreCase = true) ->
                    body.drop(2).toIntOrNull(16)?.let(::codePoint) ?: match.value
                body.startsWith("#") ->
                    body.drop(1).toIntOrNull()?.let(::codePoint) ?: match.value
                else -> NAMED[body.lowercase()] ?: match.value
            }
        }
    }

    private fun codePoint(value: Int): String =
        if (value in 1..0x10FFFF) String(Character.toChars(value)) else ""
}
