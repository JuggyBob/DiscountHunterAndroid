package com.discounthunter.android.core.text

/**
 * Text helpers shared by the store parsers. Everything here is defensive: store payloads are
 * untrusted input and may be missing, malformed or in an unexpected shape at any point.
 */
object TextTools {

    private val WHITESPACE = Regex("[ \\t\\u00A0]+")
    private val WORD = Regex("[A-Za-z0-9']+")
    private val ROMAN = Regex("^[IVXLCDM]+$")
    private val ROMAN_VALUES = mapOf(
        'I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000,
    )

    /** Articles and prepositions that carry no identifying power in a title. */
    private val TITLE_NOISE = setOf(
        "the", "a", "an", "of", "and", "for", "with", "from", "to", "in", "on",
    )

    /**
     * Packaging qualifiers stores append inconsistently. Only unambiguous ones live here: an
     * over-eager list would merge genuinely different products such as
     * "Sonic Origins" and "Sonic Origins Collection".
     */
    private val EDITION_TOKENS = setOf(
        "edition", "editions", "deluxe", "definitive", "remaster", "remastered", "ultimate",
        "complete", "gold", "premium", "goty", "gameoftheyear", "standard", "digital",
        "enhanced", "expanded", "anniversary", "signature", "steamdeck", "reviewcopy",
        "gotyedition",
    )

    /** Region / language markers that are packaging noise rather than part of the product name. */
    private val REGION_TOKENS = setOf(
        "eu", "us", "usa", "uk", "row", "global", "worldwide", "english", "german", "french",
        "italian", "spanish", "jp", "japan", "kr", "korea", "cn", "china", "ru", "russian",
    )

    /**
     * Multi-word qualifiers, matched longest first at the end of a title.
     *
     * "Game of the Year Edition" must be removed as a phrase; trimming word-by-word would have to
     * treat "game", "of", "the" and "year" as noise, which would corrupt titles like
     * "Game of Thrones".
     */
    private val QUALIFIER_PHRASES = listOf(
        "game of the year edition",
        "game of the year",
        "definitive game of the year edition",
        "complete game of the year edition",
        "anniversary edition",
        "collector s edition",
        "collector s edition",
        "game of the year",
        "deluxe edition",
        "digital deluxe edition",
        "ultimate edition",
        "standard edition",
        "enhanced edition",
        "expanded edition",
        "definitive edition",
        "complete edition",
        "gold edition",
        "premium edition",
        "steam deck edition",
        "game of the year edition",
    )

    /**
     * The squashed, qualifier-free title used as a wishlist identity: `Baldur's Gate 3 (Steam Deck
     * Edition)` becomes `baldursgate3`.
     *
     * Compared for *identity*, not for similarity — that is [TitleMatcher]'s job.
     */
    fun normalize(title: String?): String = coreWords(title).joinToString("")

    /**
     * The title's words exactly as written: lower-cased, punctuation dropped, **no qualifier
     * trimming**.
     *
     * Used when *ranking* search results, where "Elden Ring" should outrank "Elden Ring Remastered"
     * even though both name the same product.
     */
    fun rawWords(title: String?): List<String> {
        if (title.isNullOrBlank()) return emptyList()
        return WORD.findAll(title.replace('\u2019', '\''))
            .map { it.value.filter { c -> c.isLetterOrDigit() }.lowercase() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    /** Significant tokens of the title as written, without trimming packaging qualifiers. */
    fun rawSignificantTokens(title: String?): Set<String> = rawWords(title)
        .asSequence()
        .filter { it !in TITLE_NOISE }
        .filter { it.length >= 2 || it[0].isDigit() }
        .toSet()

    /**
     * The title's words in reading order, punctuation stripped, edition / region qualifiers trimmed
     * and roman numerals folded.
     *
     * This is the unit of comparison for [TitleMatcher]; working on words (rather than on the
     * squashed normalised string) is what makes a genuine word-boundary prefix test possible.
     *
     * Roman-numeral folding needs the *original* casing, so the words are read from the title as
     * written: `Resident Evil IV` folds to `4` while `Resident Evil Mix` stays a word.
     */
    fun coreWords(title: String?): List<String> {
        if (title.isNullOrBlank()) return emptyList()

        // Titles are compared case-insensitively, so words are lower-cased up front. The one piece
        // of casing that still matters — "IV" versus "iv" — is captured before lowering.
        val words = WORD.findAll(title.replace('\u2019', '\''))
            .map { match ->
                val raw = match.value
                Word(
                    // Apostrophes are dropped so "Baldur's Gate" and "Baldurs Gate" agree.
                    text = raw.filter { it.isLetterOrDigit() }.lowercase(),
                    looksLikeRomanNumeral = raw.length > 1 && raw.uppercase() == raw,
                )
            }
            .filter { it.text.isNotEmpty() }
            .toList()

        return trimTrailingQualifiers(words).map { it.fold() }
    }

    /**
     * Removes packaging qualifiers from the end of a title, longest phrase first and then
     * word-by-word, never touching the significant head of the title.
     */
    private fun trimTrailingQualifiers(words: List<Word>): List<Word> {
        var result = words.toMutableList()

        // Longest phrase first, then single words. `matchingPhraseLength` is what makes
        // "Game of the Year Edition" disappear as a unit instead of corrupting "Game of Thrones"
        // word by word.
        while (result.size > 1) {
            val phraseLength = matchingPhraseLength(result)
            if (phraseLength > 0) {
                result = result.dropLast(phraseLength).toMutableList()
                continue
            }

            val last = result.last().text
            if (last in EDITION_TOKENS || last in REGION_TOKENS) {
                result.removeAt(result.size - 1)
            } else {
                break
            }
        }
        return result
    }

    /** The longest qualifier phrase matching the tail of [words], in words; 0 when none does. */
    private fun matchingPhraseLength(words: List<Word>): Int {
        val tail = words.takeLast(PHRASE_MAX_WORDS).joinToString(" ") { it.text }
        return QUALIFIER_PHRASES
            .filter { phrase -> tail == phrase || tail.endsWith(" $phrase") }
            .maxOfOrNull { it.split(' ').size }
            ?: 0
    }

    /** Longest qualifier phrase, in words; bounds how far back a phrase match may reach. */
    private const val PHRASE_MAX_WORDS = 6

    /** One word of a title, remembering whether it was written in upper case. */
    private data class Word(val text: String, val looksLikeRomanNumeral: Boolean) {
        /**
         * Folds a roman numeral into its arabic value.
         *
         * The upper-case check is what keeps ordinary words that happen to consist of roman letters
         * — "mix", "civil", "did", "mid" — from being mangled into numbers.
         */
        fun fold(): String =
            if (looksLikeRomanNumeral && ROMAN.matches(text.uppercase())) {
                toRomanInt(text).takeIf { it > 0 }?.toString() ?: text
            } else {
                text
            }
    }

    /** The normalised, qualifier-free title, e.g. `Baldurs Gate 3` -> `baldursgate3`. */
    fun coreTitle(title: String?): String = coreWords(title).joinToString("")

    /**
     * The significant words of a title, used for set-based comparison. Filler words are dropped;
     * single characters survive only when they are digits.
     */
    fun significantTokens(title: String?): Set<String> = coreWords(title)
        .asSequence()
        .filter { it !in TITLE_NOISE }
        .filter { it.length >= 2 || it[0].isDigit() }
        .toSet()

    /** Words in reading order, punctuation removed, no qualifier trimming. */
    fun words(text: String?): List<String> =
        if (text.isNullOrEmpty()) emptyList()
        else WORD.findAll(text).map { it.value }.toList()

    /**
     * A shorter, more searchable form of a title: the first few words with filler and edition
     * words removed. Used to widen catalogue queries without losing the game's identity.
     */
    fun shortQuery(title: String?): String {
        val all = coreWords(title)
        if (all.isEmpty()) return ""
        val kept = all.filter { it !in TITLE_NOISE && it !in EDITION_TOKENS }.ifEmpty { all }
        return kept.take(3).joinToString(" ").trim()
    }

    fun slugify(title: String?): String =
        (title ?: "").lowercase()
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
            .replace(Regex("_+"), "_")
            .trim('_')

    /**
     * Resolves GOG's `{formatter}` placeholder in an image URL to a concrete width.
     *
     * GOG's CDN serves any width the formatter names, so the gallery asks for a phone-sized image
     * instead of downloading the multi-megabyte original. URLs without the placeholder pass
     * through unchanged apart from escaped slashes.
     */
    fun resolveImageFormatter(url: String?, formatter: String = "w_679"): String {
        if (url.isNullOrBlank()) return ""
        val cleaned = url.replace("\\/", "/")
        return if ("{formatter}" in cleaned) cleaned.replace("{formatter}", formatter) else cleaned
    }

    /** Collapses runs of spaces/tabs without touching paragraph breaks. */
    fun collapseSpaces(text: String?): String =
        if (text.isNullOrEmpty()) "" else WHITESPACE.replace(text, " ").trim()

    /**
     * Parses store-formatted numbers such as `"$1,234.56"`, `"-75%"`, `"€ 9,99"`.
     *
     * Returns [fallback] for anything unparseable. Thousands and decimal separators are
     * disambiguated the way a shopper reads them: the last separator is the decimal point unless
     * it is followed by exactly three digits in a short number.
     */
    fun toDouble(value: String?, fallback: Double = 0.0): Double {
        if (value.isNullOrBlank()) return fallback

        val cleaned = buildString(value.length) {
            for (ch in value) {
                if (ch.isDigit() || ch == '.' || ch == ',') append(ch)
            }
        }
        if (cleaned.isEmpty()) return fallback

        val decimalAt = maxOf(cleaned.lastIndexOf('.'), cleaned.lastIndexOf(','))
        if (decimalAt < 0) return cleaned.toDoubleOrNull() ?: fallback

        val integerPart = cleaned.substring(0, decimalAt).replace(".", "").replace(",", "")
        val fractionPart = cleaned.substring(decimalAt + 1).replace(".", "").replace(",", "")

        // "1,234" / "1.234" with exactly three trailing digits is a thousands group, not cents.
        if (fractionPart.length == 3 && integerPart.length in 1..3) {
            return (integerPart + fractionPart).toDoubleOrNull() ?: fallback
        }

        return "$integerPart.$fractionPart".toDoubleOrNull() ?: fallback
    }

    /** Truncates on a word boundary and appends an ellipsis when needed. */
    fun ellipsize(text: String?, maxLength: Int): String {
        val value = text?.trim().orEmpty()
        if (value.length <= maxLength) return value
        var cut = value.take(maxLength)
        val space = cut.lastIndexOf(' ')
        if (space > maxLength / 2) cut = cut.substring(0, space)
        return cut.trimEnd() + "\u2026"
    }

    /** Removes the duplicated "Minimum:" / "Recommended:" label left over from store HTML. */
    fun cleanRequirements(html: String?): String =
        htmlToPlainText(html)
            .replace(
                Regex(
                    "^\\s*(minimum|recommended)(\\s+system\\s+requirements)?\\s*:?\\s*",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .trim()

    /**
     * Converts store HTML into readable plain text with real paragraph breaks: block-level tags
     * become newlines, list items become bullets, and entities are decoded.
     */
    fun htmlToPlainText(html: String?): String {
        if (html.isNullOrEmpty()) return ""

        var text = Regex("<(h[1-4])[^>]*>", RegexOption.IGNORE_CASE).replace(html, "\n\n")
        text = Regex("</h[1-4]>", RegexOption.IGNORE_CASE).replace(text, "\n")
        text = Regex(
            "</?(p|div|blockquote|table|ul|ol|tr|section|article|header|figure)[^>]*>",
            RegexOption.IGNORE_CASE,
        ).replace(text, "\n")
        text = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE).replace(text, "\n")
        text = Regex("<li[^>]*>", RegexOption.IGNORE_CASE).replace(text, "\n\u2022 ")
        text = Regex("</li>", RegexOption.IGNORE_CASE).replace(text, "")
        text = Regex("</?t[dh][^>]*>", RegexOption.IGNORE_CASE).replace(text, "  ")
        text = Regex("<[^>]+>").replace(text, "")

        // Double-decoded entities can survive one pass.
        text = HtmlEntities.decode(HtmlEntities.decode(text))
        text = text.replace('\u00A0', ' ').replace("\r", "")

        val output = ArrayList<String>()
        var blanks = 0
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) {
                // Collapse a run of blank lines into a single paragraph break.
                if (blanks == 0 && output.isNotEmpty()) output.add("")
                blanks++
            } else {
                blanks = 0
                output.add(line)
            }
        }
        while (output.isNotEmpty() && output.first().isEmpty()) output.removeAt(0)
        while (output.isNotEmpty() && output.last().isEmpty()) output.removeAt(output.size - 1)

        return output.joinToString("\n").trim()
    }

    /** Strips tags and decodes entities, collapsing the result onto one line. */
    fun stripTags(html: String?): String = htmlToPlainText(html).replace("\n", " ").trim()

    /**
     * Converts a roman numeral to its integer value.
     *
     * Subtracts a symbol when it precedes a larger one (`IV` -> 4) by comparing each symbol with the
     * one *after* it, which is the direction the notation actually reads in.
     */
    private fun toRomanInt(text: String): Int {
        val values = text.uppercase().map { ROMAN_VALUES[it] ?: return 0 }
        var total = 0
        for ((index, value) in values.withIndex()) {
            val next = values.getOrNull(index + 1) ?: 0
            total += if (value < next) -value else value
        }
        return total
    }
}
