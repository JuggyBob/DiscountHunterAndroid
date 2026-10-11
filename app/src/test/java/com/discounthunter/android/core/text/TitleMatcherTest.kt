package com.discounthunter.android.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-store title matching — the rule that decides whether one game's media may be shown on
 * another game's page.
 *
 * These tests exist because of a real defect: the desktop build accepted *Elden Ring* matching
 * *Metal Eden* at a 0.5556 similarity threshold, and every screenshot on the page then belonged to
 * the wrong game.
 */
class TitleMatcherTest {

    // ---------------------------------------------------------------- the reported bug

    @Test
    fun `elden ring never matches metal eden`() {
        val verdict = TitleMatcher.verdict("Elden Ring", "Metal Eden")

        assertFalse(
            "Elden Ring must never resolve to Metal Eden",
            TitleMatcher.isSameGame("Elden Ring", "Metal Eden"),
        )
        assertEquals(TitleMatcher.Verdict.CONFLICTING_TOKENS, verdict)
    }

    @Test
    fun `elden ring does not match metal eden in any direction or casing`() {
        assertFalse(TitleMatcher.isSameGame("Metal Eden", "Elden Ring"))
        assertFalse(TitleMatcher.isSameGame("ELDEN RING", "metal   eden"))
        assertFalse(TitleMatcher.isSameGame("elden ring", "  Metal   Eden  "))
    }

    @Test
    fun `a trailing subtitle is accepted even when it is not a colon subtitle`() {
        // Steam writes "Elden Ring: Shadow of the Erdtree"; other stores write
        // "Elden Ring of Shadow". Same product, so the page still gets its media.
        assertTrue(TitleMatcher.isSameGame("Elden Ring", "Elden Ring of Shadow"))
        assertEquals(
            TitleMatcher.Verdict.PREFIX_SUBTITLE,
            TitleMatcher.verdict("Elden Ring", "Elden Ring of Shadow"),
        )
    }

    /**
     * The desktop matcher scored this pair **0.5556** with a Levenshtein ratio and accepted it,
     * which is how *Elden Ring* ended up displaying *Metal Eden*'s screenshots.
     *
     * This test reproduces that score here and then asserts the new matcher rejects the pair
     * regardless — a high edit-distance score must never be able to attach media.
     */
    @Test
    fun `the pair the desktop build accepted is rejected despite a high edit-distance score`() {
        val desktopScore = desktopSimilarity("eldenring", "metaleden")

        // The desktop rule was `score >= 0.5`; this pair scored 0.5556 and cleared it.
        assertEquals(0.5556, desktopScore, 0.001)
        assertTrue(
            "expected the desktop matcher to have accepted this pair",
            desktopScore >= 0.5,
        )

        // The new matcher rejects it on structural grounds, not on a threshold.
        assertEquals(
            TitleMatcher.Verdict.CONFLICTING_TOKENS,
            TitleMatcher.verdict("Elden Ring", "Metal Eden"),
        )
        assertFalse(TitleMatcher.isSameGame("Elden Ring", "Metal Eden"))
    }

    /**
     * The exact measure the desktop build used: `difflib.SequenceMatcher(None, a, b).ratio()`,
     * which is `2 * longestCommonSubsequence / (len(a) + len(b))`.
     *
     * Reproduced here so the regression stays honest — this is the number that scored 0.5556.
     */
    private fun desktopSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < b.length) return desktopSimilarity(b, a)
        val n = a.length
        val m = b.length
        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) {
            for (j in 1..m) {
                table[i][j] = if (a[i - 1] == b[j - 1]) {
                    table[i - 1][j - 1] + 1
                } else {
                    maxOf(table[i - 1][j], table[i][j - 1])
                }
            }
        }
        return 2.0 * table[n][m] / (n + m)
    }

    // ---------------------------------------------------------------- genuine matches

    @Test
    fun `identical titles match`() {
        assertTrue(TitleMatcher.isSameGame("Elden Ring", "Elden Ring"))
        assertTrue(TitleMatcher.isSameGame("ELDEN RING", "  elden ring  "))
        assertEquals(TitleMatcher.Verdict.EXACT, TitleMatcher.verdict("Elden Ring", "elden ring"))
    }

    @Test
    fun `roman numerals fold to arabic`() {
        assertTrue(TitleMatcher.isSameGame("Baldur's Gate III", "Baldur's Gate 3"))
        assertTrue(TitleMatcher.isSameGame("Resident Evil 4", "Resident Evil IV"))
        assertTrue(TitleMatcher.isSameGame("Final Fantasy 16", "Final Fantasy XVI"))
        // Folding happens before comparison, so the two spellings are simply the same words.
        assertEquals(
            TitleMatcher.Verdict.EXACT,
            TitleMatcher.verdict("Baldur's Gate 3", "Baldur's Gate III"),
        )
    }

    @Test
    fun `roman numerals are not folded when they are not a numeral`() {
        // "Mix" and "civil" contain roman letters but are ordinary words.
        assertEquals(listOf("resident", "evil", "mix"), TextTools.coreWords("Resident Evil Mix"))
        assertEquals(listOf("the", "civil", "war"), TextTools.coreWords("The Civil War"))
    }

    @Test
    fun `roman numeral values are correct`() {
        assertEquals(listOf("resident", "evil", "4"), TextTools.coreWords("Resident Evil IV"))
        assertEquals(listOf("resident", "evil", "9"), TextTools.coreWords("Resident Evil IX"))
        assertEquals(listOf("resident", "evil", "14"), TextTools.coreWords("Resident Evil XIV"))
        assertEquals(listOf("baldurs", "gate", "3"), TextTools.coreWords("Baldur's Gate III"))
    }

    @Test
    fun `edition and region qualifiers are ignored`() {
        assertTrue(TitleMatcher.isSameGame("Baldur's Gate 3", "Baldur's Gate 3 (Steam Deck Edition)"))
        assertTrue(TitleMatcher.isSameGame("Hollow Knight", "Hollow Knight: Deluxe Edition"))
        assertTrue(TitleMatcher.isSameGame("Portal 2", "Portal 2 (Game of the Year Edition)"))
        assertTrue(TitleMatcher.isSameGame("Terraria", "Terraria (EU)"))
    }

    @Test
    fun `a subtitle does not break a match`() {
        // Same product, longer storefront name.
        assertTrue(TitleMatcher.isSameGame("Elden Ring", "Elden Ring: Shadow of the Erdtree"))
        assertTrue(TitleMatcher.isSameGame("Cyberpunk 2077", "Cyberpunk 2077: Phantom Liberty"))
        assertEquals(
            TitleMatcher.Verdict.PREFIX_SUBTITLE,
            TitleMatcher.verdict("Elden Ring", "Elden Ring: Shadow of the Erdtree"),
        )
    }

    @Test
    fun `a dropped sequence number is treated as a different product`() {
        // "Baldur's Gate" and "Baldur's Gate 3" are separate releases, so a missing number must not
        // be read as a subtitle. Rejecting is the safe direction: the page simply shows no
        // cross-store enrichment rather than showing the wrong game's media.
        assertFalse(TitleMatcher.isSameGame("Baldur's Gate 3", "Baldur's Gate"))
        assertEquals(
            TitleMatcher.Verdict.SEQUEL_NUMBER,
            TitleMatcher.verdict("Baldur's Gate 3", "Baldur's Gate"),
        )
    }

    // ---------------------------------------------------------------- different products

    @Test
    fun `sequels never match their predecessor`() {
        assertFalse(TitleMatcher.isSameGame("Stardew Valley", "Stardew Valley 2"))
        assertFalse(TitleMatcher.isSameGame("Hades", "Hades II"))
        assertFalse(TitleMatcher.isSameGame("Baldur's Gate", "Baldur's Gate 3"))
        assertFalse(TitleMatcher.isSameGame("Portal", "Portal 2"))
        assertEquals(
            TitleMatcher.Verdict.SEQUEL_NUMBER,
            TitleMatcher.verdict("Stardew Valley", "Stardew Valley 2"),
        )
    }

    @Test
    fun `short single-word titles are never loosely matched`() {
        // This is the rule that stops *Hades* from lighting up *Hades' Star*.
        assertEquals(TitleMatcher.Verdict.TOO_SHORT, TitleMatcher.verdict("Hades", "Hades Star"))
        assertFalse(TitleMatcher.isSameGame("Hades", "Hades Star"))
        assertFalse(TitleMatcher.isSameGame("Doom", "Doom Eternal"))
        assertFalse(TitleMatcher.isSameGame("Portal", "Portal Stories"))
    }

    @Test
    fun `different games with similar names do not match`() {
        assertFalse(TitleMatcher.isSameGame("Assassin's Creed", "Assassin's Creed II"))
        assertFalse(TitleMatcher.isSameGame("The Witcher 3", "The Witcher 2"))
        assertFalse(TitleMatcher.isSameGame("Mass Effect", "Mass Effect 2"))
        assertFalse(TitleMatcher.isSameGame("Halo", "Halo Infinite"))
        assertFalse(TitleMatcher.isSameGame("Final Fantasy VII", "Final Fantasy XVI"))
        assertFalse(TitleMatcher.isSameGame("Resident Evil 4", "Resident Evil Village"))
        assertFalse(TitleMatcher.isSameGame("Dead Space", "Dead Space 2"))
    }

    @Test
    fun `a word-shuffled title is not a match`() {
        // The desktop build's prefix rule would have accepted this one.
        assertFalse(TitleMatcher.isSameGame("Revenant Gate 3", "Baldur's Gate 3"))
        assertFalse(TitleMatcher.isSameGame("Ring Elden Shadow", "Elden Ring"))
    }

    // ---------------------------------------------------------------- input hygiene

    @Test
    fun `blank titles never match`() {
        assertEquals(TitleMatcher.Verdict.EMPTY_INPUT, TitleMatcher.verdict("", "Elden Ring"))
        assertEquals(TitleMatcher.Verdict.EMPTY_INPUT, TitleMatcher.verdict("Elden Ring", null))
        assertEquals(TitleMatcher.Verdict.EMPTY_INPUT, TitleMatcher.verdict(null, null))
        assertFalse(TitleMatcher.isSameGame("", ""))
    }

    @Test
    fun `punctuation-only titles are rejected`() {
        assertFalse(TitleMatcher.isSameGame("!!!", "???"))
    }

    @Test
    fun `a short word does not match a longer title that contains it`() {
        // Containment alone scored 0.65+ in the desktop build, which is how unrelated titles leaked
        // into each other's results. A single generic word must never be enough.
        assertFalse(TitleMatcher.isSameGame("Ring", "Elden Ring of Darkness"))
        assertFalse(TitleMatcher.isSameGame("Souls", "Dark Souls"))
        assertEquals(
            TitleMatcher.Verdict.TOO_SHORT,
            TitleMatcher.verdict("Ring", "Elden Ring of Darkness"),
        )
    }

    @Test
    fun `stacked edition qualifiers are stripped`() {
        // Every word after the product name is packaging noise, however it is stacked.
        assertTrue(TitleMatcher.isSameGame("Dark Souls", "Dark Souls Remastered Premium Edition Deluxe"))
        assertEquals(
            TitleMatcher.Verdict.EXACT,
            TitleMatcher.verdict("Dark Souls", "Dark Souls Remastered Premium Edition Deluxe"),
        )
    }

    // ---------------------------------------------------------------- ranking only

    @Test
    fun `similarity ranks the exact title first`() {
        val candidates = listOf("Metal Eden", "Elden Ring", "Elden Ring II", "Elden Ring of Shadow")

        val ranked = TitleMatcher.rankBy("Elden Ring", candidates) { it }

        assertEquals("Elden Ring", ranked.first())
    }

    @Test
    fun `similarity is symmetric and bounded`() {
        assertEquals(
            TitleMatcher.similarity("Elden Ring", "Metal Eden"),
            TitleMatcher.similarity("Metal Eden", "Elden Ring"),
            0.0001,
        )
        assertEquals(1.0, TitleMatcher.similarity("Elden Ring", "elden ring"), 0.0001)
        assertEquals(0.0, TitleMatcher.similarity("", "Elden Ring"), 0.0001)
    }

    @Test
    fun `verdicts are stable for the documented cases`() {
        // A regression table: each row is a real pair, so a change in behaviour is obvious.
        val cases = listOf(
            Triple("Elden Ring", "Elden Ring", TitleMatcher.Verdict.EXACT),
            Triple("Elden Ring", "Metal Eden", TitleMatcher.Verdict.CONFLICTING_TOKENS),
            Triple("Baldur's Gate 3", "Baldur's Gate III", TitleMatcher.Verdict.EXACT),
            Triple("Portal 2", "Portal 2 (Deluxe Edition)", TitleMatcher.Verdict.EXACT),
            Triple("Portal", "Portal 2", TitleMatcher.Verdict.TOO_SHORT),
            Triple("Stardew Valley", "Stardew Valley 2", TitleMatcher.Verdict.SEQUEL_NUMBER),
            Triple("Hades", "Hades II", TitleMatcher.Verdict.TOO_SHORT),
            Triple("Ring Elden Shadow", "Elden Ring", TitleMatcher.Verdict.NOT_A_PREFIX),
            Triple("", "Elden Ring", TitleMatcher.Verdict.EMPTY_INPUT),
        )

        for ((reference, candidate, expected) in cases) {
            assertEquals(
                "verdict for \"$reference\" vs \"$candidate\"",
                expected,
                TitleMatcher.verdict(reference, candidate),
            )
        }
    }
}
