package com.discounthunter.android.core.text

/**
 * Cross-store title matching.
 *
 * ## Why this exists
 *
 * The desktop build decided which Steam app a GOG/Epic title referred to using a
 * Levenshtein-ratio similarity with a `0.5` acceptance threshold. Under that rule
 * `score("Elden Ring", "Metal Eden") = 0.5556`, so opening *Elden Ring* adopted *Metal Eden*'s
 * Steam app id — and with it every screenshot, trailer, description, genre and price on the page.
 * The header still read "Elden Ring" because the view model never refreshed its title, which is
 * exactly the reported symptom: **Elden Ring displayed, Metal Eden screenshots shown**.
 *
 * ## The rule now
 *
 * A candidate is accepted as the *same product* only on structural evidence, never on "the letters
 * look similar":
 *
 *  1. the qualifier-free titles are identical, or
 *  2. the significant-token sets are identical (catches `Baldur's Gate III` vs `Baldur's Gate 3`), or
 *  3. one token set is a **strict subset** of the other, and
 *     * both sides have at least [MIN_SUBSET_TOKENS] significant tokens — so `Portal` can never
 *       match `Portal 2`, and `Hades` can never match `Hades II`; and
 *     * the shorter title covers at least [SUBSET_LENGTH_RATIO] of the longer one's length.
 *
 * Genuine re-releases need one more rule, because the storefront name carries a subtitle
 * (`Elden Ring` vs `Elden Ring: Shadow of the Erdtree`). Those are accepted only when the shorter
 * title is a literal **word-boundary prefix** of the longer one *and* the words it adds do not
 * begin with a number — so `Stardew Valley` / `Stardew Valley 2` stay apart.
 *
 * There is deliberately no edit-distance threshold in the accept path. Edit distance survives only
 * in [similarity], which is used exclusively to *rank* search results — never to attach media.
 */
object TitleMatcher {

    /** Minimum share of the longer title's length that the shorter title must cover. */
    private const val SUBSET_LENGTH_RATIO = 0.7

    /**
     * Minimum number of significant tokens on each side of a subset match.
     *
     * Below this a title is a single generic word, and franchises reuse those names across
     * sequels and spin-offs.
     */
    private const val MIN_SUBSET_TOKENS = 2

    /**
     * True only when [candidate] is confidently the same product as [reference].
     *
     * This is the gate protecting screenshots and trailers: media is never attached to a game
     * unless the store's own search proved that the app id belongs to *this* game.
     */
    fun isSameGame(reference: String?, candidate: String?): Boolean =
        verdict(reference, candidate).isAccepted

    /** The same decision as [isSameGame], plus the reason — used by tests and diagnostics. */
    fun verdict(reference: String?, candidate: String?): Verdict {
        val referenceWords = TextTools.coreWords(reference)
        val candidateWords = TextTools.coreWords(candidate)
        if (referenceWords.isEmpty() || candidateWords.isEmpty()) return Verdict.EMPTY_INPUT

        if (referenceWords == candidateWords) return Verdict.EXACT

        val referenceTokens = referenceWords.toSet()
        val candidateTokens = candidateWords.toSet()
        if (referenceTokens.isEmpty() || candidateTokens.isEmpty()) return Verdict.NO_EVIDENCE

        if (referenceTokens == candidateTokens) return Verdict.TOKEN_SET_EQUAL

        val referenceIsSubset = candidateTokens.containsAll(referenceTokens)
        val candidateIsSubset = referenceTokens.containsAll(candidateTokens)
        if (!referenceIsSubset && !candidateIsSubset) {
            // The significant words genuinely disagree: a different product, however similar it
            // looks. "Elden Ring" vs "Metal Eden" lands exactly here.
            return Verdict.CONFLICTING_TOKENS
        }

        val shorterWords = if (referenceIsSubset) referenceWords else candidateWords
        val longerWords = if (referenceIsSubset) candidateWords else referenceWords
        val shorterTokens = if (referenceIsSubset) referenceTokens else candidateTokens
        val extraWords = longerWords.drop(shorterWords.size)

        // A single-word title such as "Portal" or "Hades" is too generic to match loosely at all:
        // franchises reuse the name for sequels, spin-offs and collections.
        if (shorterTokens.size < MIN_SUBSET_TOKENS) return Verdict.TOO_SHORT

        // A longer title whose first added word is a number names a different product, however
        // close the rest of it is: "Stardew Valley" -> "Stardew Valley 2", "Mass Effect" ->
        // "Mass Effect 2". Checked before the length ratio so that a short sequel is never rescued
        // by being mostly the same letters as its predecessor.
        if (extraWords.firstOrNull()?.firstOrNull()?.isDigit() == true) return Verdict.SEQUEL_NUMBER

        // The shorter title's words must survive in the same order; a shuffled title
        // ("Ring Elden Shadow") is a different product.
        if (!isWordPrefix(shorterWords, longerWords)) return Verdict.NOT_A_PREFIX

        // What remains is a shared title plus a subtitle — a re-release of the same game:
        // "Elden Ring" -> "Elden Ring: Shadow of the Erdtree".
        return Verdict.PREFIX_SUBTITLE
    }

    /**
     * A 0..1 similarity used **only** for ordering search results.
     *
     * It blends token overlap, substring containment and a normalised edit-distance ratio. It must
     * never decide whether media belongs to a game — see the class documentation.
     *
     * Qualifier trimming deliberately does *not* apply here. Trimming is what makes "Elden Ring" and
     * "Elden Ring Remastered" the same product for identity, but for ranking the user searching
     * "Elden Ring" plainly wants the row called exactly that above the remastered edition, so the
     * titles are compared as written.
     */
    fun similarity(left: String?, right: String?): Double {
        val a = TextTools.rawWords(left).joinToString("")
        val b = TextTools.rawWords(right).joinToString("")
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0

        val tokensA = TextTools.rawSignificantTokens(left)
        val tokensB = TextTools.rawSignificantTokens(right)
        val tokenScore = if (tokensA.isEmpty() || tokensB.isEmpty()) {
            0.0
        } else {
            val shared = tokensA.count { it in tokensB }.toDouble()
            (2.0 * shared) / (tokensA.size + tokensB.size)
        }

        val containsScore = if (a.contains(b) || b.contains(a)) 0.75 else 0.0

        // An exact match after qualifier trimming is a strong ranking signal even though the raw
        // strings differ: it means the candidate *is* what the user asked for, plus packaging noise.
        val trimmedExact = TextTools.coreTitle(left) == TextTools.coreTitle(right)

        return if (trimmedExact) {
            maxOf(0.95, tokenScore, containsScore, 0.8 * editRatio(a, b))
        } else {
            maxOf(tokenScore, containsScore, 0.8 * editRatio(a, b))
        }
    }

    /** Ranks candidates for [query]; ties break deterministically by title. */
    fun <T> rankBy(query: String?, candidates: List<T>, titleOf: (T) -> String): List<T> =
        candidates
            .map { it to similarity(query, titleOf(it)) }
            .sortedWith(
                compareByDescending<Pair<T, Double>> { it.second }
                    .thenBy { titleOf(it.first) },
            )
            .map { it.first }

    enum class Verdict(val isAccepted: Boolean) {
        /** Qualifier-free titles are word-for-word identical. */
        EXACT(true),

        /** Same significant tokens, different spelling (`III` vs `3`). */
        TOKEN_SET_EQUAL(true),

        /**
         * One title is a word-boundary prefix of the other and the words it adds are a subtitle
         * rather than a sequence number.
         */
        PREFIX_SUBTITLE(true),

        /** Fewer than two significant tokens on the shorter side — too weak to trust. */
        TOO_SHORT(false),

        /** The longer title adds a sequence number: `Portal` vs `Portal 2`. */
        SEQUEL_NUMBER(false),

        /** Subset, but not a prefix — e.g. `Baldur's Gate 3` vs `Revenant Gate 3`. */
        NOT_A_PREFIX(false),

        /** Different significant tokens: this is a different product. */
        CONFLICTING_TOKENS(false),

        /** Neither side carried usable words. */
        NO_EVIDENCE(false),

        /** One of the titles was blank. */
        EMPTY_INPUT(false),
    }

    /** True when every word of [prefix] matches [full] in order, word for word. */
    private fun isWordPrefix(prefix: List<String>, full: List<String>): Boolean {
        if (prefix.size >= full.size) return false
        return prefix.indices.all { prefix[it] == full[it] }
    }

    /** Length-normalised Levenshtein similarity in `[0, 1]`, using one rolling row. */
    private fun editRatio(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < b.length) return editRatio(b, a)
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
    }
}
