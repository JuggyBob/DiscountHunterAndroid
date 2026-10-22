package com.discounthunter.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rendering release notes safely.
 *
 * Release notes are somebody else's text. The formatter turns a small, known subset of Markdown
 * into styled lines and nothing else: no HTML is passed through, no image is fetched, and no link
 * is made tappable.
 */
class ReleaseNotesFormatterTest {

    @Test
    fun `a heading becomes a heading line`() {
        val lines = ReleaseNotesFormatter.parse("## Added")
        assertEquals(1, lines.size)
        assertEquals("Added", lines[0].text)
        assertEquals(ReleaseNotesFormatter.Style.HEADING, lines[0].style)
    }

    @Test
    fun `a subheading is distinguished from a heading`() {
        val lines = ReleaseNotesFormatter.parse("### Fixed")
        assertEquals(ReleaseNotesFormatter.Style.SUBHEADING, lines[0].style)
    }

    @Test
    fun `a bullet becomes a bullet line`() {
        val lines = ReleaseNotesFormatter.parse("- Deal alerts")
        assertEquals(ReleaseNotesFormatter.Style.BULLET, lines[0].style)
        assertEquals("Deal alerts", lines[0].text)
    }

    @Test
    fun `an asterisk bullet is also a bullet`() {
        val lines = ReleaseNotesFormatter.parse("* Another change")
        assertEquals(ReleaseNotesFormatter.Style.BULLET, lines[0].style)
    }

    @Test
    fun `plain text stays plain text`() {
        val lines = ReleaseNotesFormatter.parse("Just a sentence.")
        assertEquals(ReleaseNotesFormatter.Style.TEXT, lines[0].style)
        assertEquals("Just a sentence.", lines[0].text)
    }

    @Test
    fun `a horizontal rule is recognised`() {
        val lines = ReleaseNotesFormatter.parse("---")
        assertEquals(ReleaseNotesFormatter.Style.RULE, lines[0].style)
    }

    @Test
    fun `blank lines are dropped`() {
        val lines = ReleaseNotesFormatter.parse("\n\nOne\n\n\nTwo\n")
        assertEquals(2, lines.size)
    }

    @Test
    fun `html tags are stripped rather than shown`() {
        val lines = ReleaseNotesFormatter.parse("<b>Bold</b> and <script>alert(1)</script>")
        assertEquals("Bold and alert(1)", lines[0].text)
        assertTrue(lines[0].text.none { it == '<' })
    }

    @Test
    fun `an empty body produces nothing`() {
        assertTrue(ReleaseNotesFormatter.parse("").isEmpty())
        assertTrue(ReleaseNotesFormatter.parse("   \n  \n").isEmpty())
    }

    @Test
    fun `a long body keeps every line`() {
        val body = (1..200).joinToString("\n") { "Line $it" }
        assertEquals(200, ReleaseNotesFormatter.parse(body).size)
    }

    @Test
    fun `a link keeps its text and shows the address`() {
        val segments = ReleaseNotesFormatter.inline("See [the changelog](https://example.invalid/x)")
        assertEquals(
            "See the changelog (https://example.invalid/x)",
            segments.joinToString("") { it.text },
        )
    }

    @Test
    fun `a link is never a separate tappable segment`() {
        // The address is shown as plain text. A link in somebody else's release notes is not
        // something to make clickable.
        val segments = ReleaseNotesFormatter.inline("[click me](https://example.invalid/evil)")
        assertEquals(2, segments.size)
        assertEquals("click me", segments[0].text)
        assertTrue(segments.none { it.text == "https://example.invalid/evil" })
    }

    @Test
    fun `bold text is marked as bold`() {
        val segments = ReleaseNotesFormatter.inline("a **bold** word")
        assertTrue(segments.any { it.bold && it.text == "bold" })
    }

    @Test
    fun `inline code is marked as code`() {
        val segments = ReleaseNotesFormatter.inline("run `./gradlew test`")
        assertTrue(segments.any { it.code && it.text == "./gradlew test" })
    }

    @Test
    fun `plain text is one segment`() {
        val segments = ReleaseNotesFormatter.inline("nothing special")
        assertEquals(1, segments.size)
        assertEquals("nothing special", segments[0].text)
    }
}
