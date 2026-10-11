package com.discounthunter.android.data.gog

import com.discounthunter.android.core.model.GameIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for a crash that no JVM unit test could see.
 *
 * `GogPageParser` used to declare a regex containing an unescaped `}`. Android's regex engine is
 * ICU, which rejects an unmatched `}`; the JVM's `java.util.regex` accepts it. So the pattern
 * compiled fine in every unit test and then threw `PatternSyntaxException` from the class
 * initialiser the first time a GOG page was parsed **on a device** — `ExceptionInInitializerError`,
 * which kills the process. It showed up as "opening some games crashes the app", including Steam
 * games, because the detail page touches `GogPageParser` for any game that is also sold on GOG.
 *
 * These tests therefore check two things a normal parse test cannot:
 *
 *  1. every regex literal in `src/main` is legal under ICU's stricter brace rules, and
 *  2. the parser reads video entries whose fields are in an unexpected order.
 */
class RegexBraceSafetyTest {

    @Test
    fun `no regex literal in the app uses an unescaped brace`() {
        val offenders = mutableListOf<String>()

        for (file in mainSourceFiles()) {
            val text = file.readText()
            // Only look inside regex literals: `"""..."""` (raw) or "..." with escaped backslashes.
            for (literal in regexLiterals(text)) {
                if (hasUnescapedBrace(literal)) {
                    offenders += "${file.name}: ${literal.take(90)}"
                }
            }
        }

        assertTrue(
            "Regex literals must escape both braces. Android's ICU regex engine rejects a bare '}', " +
                "while the JVM's engine accepts it, so an unescaped one passes every unit test and " +
                "then crashes the app on a device:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `video entries are read whatever order their fields are in`() {
        // The previous single mega-regex demanded exactly provider, url, imageUrl in that order.
        val identity = requireNotNull(GameIdentity.gog("elden_ring"))
        val html = """
            <script>{"videos":[
              {"imageUrl":"https:\/\/images.gog-statics.com\/a{formatter}.jpg",
               "url":"https:\/\/www.youtube.com\/embed\/abcdefghijk","provider":"youtube"},
              {"provider":"vimeo","imageUrl":"https:\/\/i.vimeocdn.com\/b.jpg",
               "url":"https:\/\/vimeo.com\/12345"}
            ]}</script>
        """.trimIndent()

        val trailers = GogPageParser.parseTrailers(html, identity)

        assertEquals(2, trailers.size)
        assertEquals("abcdefghijk", trailers[0].youtubeId)
        assertTrue(trailers[0].webUrl.startsWith("https://www.youtube.com/watch?v="))
        assertTrue(trailers[1].youtubeId.isEmpty())
    }

    @Test
    fun `a video entry with extra keys between the known ones is still read`() {
        val identity = requireNotNull(GameIdentity.gog("elden_ring"))
        val html = """
            <script>{"videos":[{"provider":"youtube","videoId":42,
               "url":"https:\/\/www.youtube.com\/watch?v=zzzzzzzzzz","imageUrl":"https:\/\/x.jpg"}]}</script>
        """.trimIndent()

        val trailer = GogPageParser.parseTrailers(html, identity).single()

        assertEquals("zzzzzzzzzz", trailer.youtubeId)
    }

    // ---------------------------------------------------------------- helpers

    private fun mainSourceFiles(): List<File> {
        val moduleDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "src/main/java").isDirectory }
            ?: error("could not locate the module directory from ${System.getProperty("user.dir")}")

        return File(moduleDir, "src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }

    /** Raw `"""…"""` and ordinary `"…"` literals that are immediately wrapped in `Regex(`. */
    private fun regexLiterals(text: String): List<String> {
        val found = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val call = text.indexOf("Regex(", index)
            if (call < 0) break

            var cursor = call + "Regex(".length
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++

            when {
                text.startsWith("\"\"\"", cursor) -> {
                    val end = text.indexOf("\"\"\"", cursor + 3)
                    if (end < 0) break
                    found += text.substring(cursor + 3, end)
                    index = end + 3
                }

                text.getOrNull(cursor) == '"' -> {
                    val literal = readQuoted(text, cursor) ?: break
                    found += literal
                    index = cursor + literal.length + 2
                }

                else -> index = call + "Regex(".length
            }
        }
        return found
    }

    /** Returns the literal's content, honouring backslash escapes. */
    private fun readQuoted(text: String, start: Int): String? {
        val builder = StringBuilder()
        var i = start + 1
        while (i < text.length) {
            when (val c = text[i]) {
                '\\' -> {
                    if (i + 1 < text.length) {
                        // Keep the escape's meaning: `\{` stays a literal brace, `\\` is a backslash.
                        builder.append(c).append(text[i + 1])
                        i += 2
                    } else {
                        return null
                    }
                }

                '"' -> return builder.toString()
                else -> {
                    builder.append(c)
                    i++
                }
            }
        }
        return null
    }

    /**
     * True when the pattern contains a `{` or `}` that is neither escaped, nor inside a character
     * class (`[^{}]` is a perfectly legal class of two literal braces), nor part of a quantifier.
     *
     * Quantifiers (`{2,}`, `{1,40}`) are legal because the two braces are balanced; that is why
     * `SteamPageParser`'s `{20,600}` is fine and a lone trailing `}` was not.
     */
    private fun hasUnescapedBrace(pattern: String): Boolean {
        var i = 0
        while (i < pattern.length) {
            when (pattern[i]) {
                // An escape makes the next character literal, whatever it is.
                '\\' -> i += 2

                // A character class is a self-contained region: inside it, braces are literals, so
                // `[^{}]` and `[{}]` are fine and must not be flagged. Skip to its close.
                '[' -> {
                    i = classBodyEnd(pattern, i)
                    if (i < 0) return true // unterminated class is a broken pattern anyway
                    i++
                }

                '{' -> {
                    val end = quantifierEnd(pattern, i)
                    if (end > 0) {
                        i = end + 1
                    } else {
                        return true
                    }
                }

                // A balanced quantifier was already skipped above, and a character class was skipped
                // wholesale, so any `}` left over is a closing brace with no opening partner — which
                // is exactly what ICU refuses.
                '}' -> return true

                else -> i++
            }
        }
        return false
    }

    /** The index of the `]` that closes the character class opened at [at], or -1. */
    private fun classBodyEnd(pattern: String, at: Int): Int {
        var i = at + 1
        if (i < pattern.length && pattern[i] == '^') i++
        if (i < pattern.length && pattern[i] == ']') i++ // "[]" is a literal ']'
        while (i < pattern.length) {
            when (pattern[i]) {
                '\\' -> i += 2
                ']' -> return i
                else -> i++
            }
        }
        return -1
    }

    /** Index of the `}` closing the quantifier opened at [at], or -1 when [at] is not a quantifier. */
    private fun quantifierEnd(pattern: String, at: Int): Int {
        var i = at + 1
        var digits = 0
        while (i < pattern.length && pattern[i].isDigit()) {
            i++
            digits++
        }
        // A quantifier must start with a number, so `{` alone or `{,n}` is not one.
        if (digits == 0) return -1

        if (i < pattern.length && pattern[i] == ',') {
            i++
            while (i < pattern.length && pattern[i].isDigit()) i++
        }

        return if (i < pattern.length && pattern[i] == '}') i else -1
    }
}
