package com.discounthunter.android.update

/**
 * A deliberately small Markdown renderer for release notes.
 *
 * ## Why not a full renderer
 *
 * Release notes come from a GitHub release body, which is text somebody else wrote. A general
 * purpose Markdown renderer would bring a parser that can execute or fetch things, and the notes
 * are not worth that. Everything here is plain text and spans: no HTML is passed through, no image
 * is fetched, and no link is made clickable.
 *
 * ## What it understands
 *
 *  * `##` and `###` headings, rendered as bold text in the primary colour.
 *  * `-` and `*` bullet lists, rendered with a bullet.
 *  * `**bold**`, `*italic*` and `` `code` ``.
 *  * `---` horizontal rules.
 *  * HTML tags, which GitHub's own release bodies sometimes contain, stripped rather than shown.
 *
 * ## What it deliberately does not do
 *
 * A link in a release body is shown as its text. It is never turned into something tappable that
 * could open an arbitrary address. The only link the about screen offers is the official release
 * page, and that URL comes from the API response, not from the body.
 */
object ReleaseNotesFormatter {

    /** A line of the notes, already reduced to what the UI needs. */
    data class Line(
        val text: String,
        val style: Style,
    )

    enum class Style { HEADING, SUBHEADING, BULLET, TEXT, RULE }

    /**
     * Splits [markdown] into styled lines.
     *
     * The result is a flat list rather than a tree, because that is all a list of paragraphs and
     * bullets needs, and a tree would make the renderer harder to reason about for no benefit.
     */
    fun parse(markdown: String): List<Line> {
        val lines = mutableListOf<Line>()
        for (raw in markdown.replace("\r\n", "\n").split('\n')) {
            val line = stripHtml(raw).trimEnd()
            when {
                line.isBlank() -> continue
                line.trim().startsWith("---") && line.trim().all { it == '-' } -> {
                    lines += Line("", Style.RULE)
                }
                line.trimStart().startsWith("### ") -> {
                    lines += Line(line.trimStart().removePrefix("### ").trim(), Style.SUBHEADING)
                }
                line.trimStart().startsWith("## ") -> {
                    lines += Line(line.trimStart().removePrefix("## ").trim(), Style.HEADING)
                }
                line.trimStart().startsWith("# ") -> {
                    lines += Line(line.trimStart().removePrefix("# ").trim(), Style.HEADING)
                }
                isBullet(line) -> {
                    lines += Line(bulletText(line), Style.BULLET)
                }
                else -> lines += Line(line.trim(), Style.TEXT)
            }
        }
        return lines
    }

    /** True when [line] starts a list item. */
    private fun isBullet(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")
    }

    /** The text of a list item, without its marker. */
    private fun bulletText(line: String): String =
        line.trimStart().drop(1).trimStart()

    /**
     * Removes HTML tags.
     *
     * GitHub renders a release body as Markdown, but the body itself can contain HTML, and showing
     * `<b>` to a user is worse than dropping the formatting.
     */
    private fun stripHtml(text: String): String =
        text.replace(Regex("<[^>]*>"), "")

    /**
     * One run of text with a single style.
     *
     * The inline styles are expressed as segments rather than as a finished `AnnotatedString` so
     * that the parsing can be tested without a Compose runtime, and so the UI does not have to know
     * anything about Markdown.
     */
    data class Segment(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
    )

    /**
     * Splits one line into styled segments.
     *
     * Bold, italic and code are recognised. A Markdown link is reduced to its text with the
     * address in brackets, and is **never** a separate segment that could be made tappable: the
     * notes are somebody else's text, and the only link the about screen offers is the official
     * release page, whose URL comes from the API response.
     */
    fun inline(text: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        var rest = text
        while (rest.isNotEmpty()) {
            val bold = rest.indexOf("**")
            if (bold >= 0) {
                val end = rest.indexOf("**", bold + 2)
                if (end > 0) {
                    if (bold > 0) segments += Segment(rest.substring(0, bold))
                    segments += Segment(rest.substring(bold + 2, end), bold = true)
                    rest = rest.substring(end + 2)
                    continue
                }
            }
            val code = rest.indexOf('`')
            if (code >= 0) {
                val end = rest.indexOf('`', code + 1)
                if (end > 0) {
                    if (code > 0) segments += Segment(rest.substring(0, code))
                    segments += Segment(rest.substring(code + 1, end), code = true)
                    rest = rest.substring(end + 1)
                    continue
                }
            }
            val italic = rest.indexOf('*')
            if (italic >= 0) {
                val end = rest.indexOf('*', italic + 1)
                if (end > 0) {
                    if (italic > 0) segments += Segment(rest.substring(0, italic))
                    segments += Segment(rest.substring(italic + 1, end), italic = true)
                    rest = rest.substring(end + 1)
                    continue
                }
            }
            val link = LINK.find(rest)
            if (link != null) {
                if (link.range.first > 0) segments += Segment(rest.substring(0, link.range.first))
                val label = link.groupValues[1]
                val target = link.groupValues[2]
                segments += Segment(if (label.isNotBlank()) label else target)
                if (label.isNotBlank() && target.isNotBlank()) {
                    segments += Segment(" ($target)")
                }
                rest = rest.substring(link.range.last + 1)
                continue
            }
            segments += Segment(rest)
            rest = ""
        }
        return segments
    }

    /** A Markdown link. */
    private val LINK = Regex("\\[([^\\]]*)]\\(([^)]*)\\)")
}
