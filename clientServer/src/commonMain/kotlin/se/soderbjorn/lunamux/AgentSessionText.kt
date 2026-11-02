/**
 * Plain-text rules the agents overview applies to what a session says about itself, shared by the
 * server and every client so they agree.
 *
 * This file contains [AgentSessionText]: it turns a line of markdown into the text a reader sees,
 * skips lines that are only a code fence, derives a title from the first words of a prompt, and
 * says whether a stored title actually names the work. The overview's view model uses it to title
 * rows and clean previews, and the server's session search and last-reply capture use it so a
 * title that only says where a session ran is not searched and a fence is not stored as a reply.
 */
package se.soderbjorn.lunamux

/**
 * Pure text helpers for agent session rows: no state, no clock, no I/O.
 *
 * Every function takes the text as it was stored (possibly markdown, possibly a path) and returns
 * what the overview should show, so the same input gives the same row on every platform.
 */
object AgentSessionText {
    /** The most words a title taken from a prompt keeps. */
    private const val TITLE_WORDS = 8

    /** The most characters a title taken from a prompt keeps, before the ellipsis. */
    private const val TITLE_CHARS = 56

    private val FENCE = Regex("""^\s*(`{3,}|~{3,})[\w+#.-]*\s*$""")
    private val RULE = Regex("""^\s*([-*_]\s*){3,}$""")
    private val HEADING = Regex("""^\s{0,3}#{1,6}\s+""")
    private val QUOTE = Regex("""^\s{0,3}>\s?""")
    private val LIST_MARKER = Regex("""^\s*([-*+•]|\d{1,3}[.)])\s+""")
    private val IMAGE = Regex("""!\[([^\]]*)\]\([^)]*\)""")
    private val LINK = Regex("""\[([^\]]+)\]\([^)]*\)""")
    private val CODE = Regex("""`+([^`]+)`+""")
    private val BOLD_STAR = Regex("""\*\*(\S(?:.*?\S)?)\*\*""")
    private val BOLD_UNDERSCORE = Regex("""(^|[\s(])__(\S(?:.*?\S)?)__(?=$|[\s).,;:!?])""")
    private val STAR_ITALIC = Regex("""(^|[\s(])\*(\S(?:[^*]*?\S)?)\*(?=$|[\s).,;:!?])""")
    private val UNDERSCORE_ITALIC = Regex("""(^|[\s(])_(\S(?:[^_]*?\S)?)_(?=$|[\s).,;:!?])""")
    private val STRIKE = Regex("""~~(\S(?:.*?\S)?)~~""")
    private val SPACES = Regex("""\s+""")
    /** A feature-run folder name: `<ticket>-<number>-impl` or `-plan`, optionally with a `-r<round>` suffix. */
    private val RUN_ID = Regex("""^[a-z][a-z0-9]*-\d+-(impl|plan)(-r\d+)?$""")

    /**
     * Whether [line] is only a code fence (``` or ~~~, with or without a language), which says
     * nothing and so is never shown as a preview.
     *
     * @param line one line of text.
     * @return true for a line that is just a fence.
     */
    fun isFenceLine(line: String): Boolean = FENCE.matches(line)

    /**
     * Turn one line of markdown into the text it reads as.
     *
     * Removes a heading, quote or list marker at the start, then bold, italic, strike-through,
     * inline code and the markup of links and images (keeping their text), and collapses runs of
     * white space. Underscores and stars inside a word, such as `snake_case`, are left alone.
     *
     * Called by [previewOf] and [titleFromPrompt]; the view model calls it directly for lines it
     * already has.
     *
     * @param line one line of possibly marked-up text.
     * @return the plain text, possibly empty.
     */
    fun plain(line: String): String {
        var t = line.replace(HEADING, "").replace(QUOTE, "").replace(LIST_MARKER, "")
        t = t.replace(IMAGE) { it.groupValues[1] }.replace(LINK) { it.groupValues[1] }
        t = t.replace(CODE) { it.groupValues[1] }
        t = t.replace(BOLD_STAR) { it.groupValues[1] }.replace(STRIKE) { it.groupValues[1] }
        t = t.replace(BOLD_UNDERSCORE) { it.groupValues[1] + it.groupValues[2] }
        t = t.replace(STAR_ITALIC) { it.groupValues[1] + it.groupValues[2] }
        t = t.replace(UNDERSCORE_ITALIC) { it.groupValues[1] + it.groupValues[2] }
        return t.replace(SPACES, " ").trim()
    }

    /**
     * The first line of [text] worth showing as a preview, as plain text.
     *
     * Blank lines, lines that are only a code fence and rules are passed over, so a reply whose
     * stored first line is a bare fence still gives the line after it.
     *
     * @param text a stored reply, prompt or status, possibly several lines; null for none.
     * @return the plain first line with content, or null when there is none.
     */
    fun previewOf(text: String?): String? =
        text?.lineSequence()
            ?.filterNot { isFenceLine(it) || RULE.matches(it) }
            ?.map(::plain)
            ?.firstOrNull { it.isNotEmpty() }

    /**
     * A title made from the first words of a prompt.
     *
     * Takes the first line with content, drops a leading slash command and any word that is a
     * path (starts with `/`, `~/` or `./`), strips markdown, and keeps the first few words,
     * ending with an ellipsis when it cut something off.
     *
     * Called by the view model for a session that has no stored title worth showing.
     *
     * @param prompt the first prompt or its first line; null for none.
     * @return the title, or null when the prompt holds nothing but commands, paths or markup.
     */
    fun titleFromPrompt(prompt: String?): String? {
        val line = previewOf(prompt) ?: return null
        val words = line.split(' ').filter { it.isNotEmpty() }
        // A leading slash command ("/review ...") is how the prompt was typed, not what it asks.
        val first = words.first()
        val prose = (if (first.startsWith("/") && !isPathWord(first)) words.drop(1) else words).filterNot(::isPathWord)
        if (prose.isEmpty()) return null
        var kept = prose.take(TITLE_WORDS).joinToString(" ")
        var cut = prose.size > TITLE_WORDS
        if (kept.length > TITLE_CHARS) {
            kept = kept.take(TITLE_CHARS).substringBeforeLast(' ', kept.take(TITLE_CHARS)).trimEnd(',', ';', ':', '.', '-')
            cut = true
        }
        return if (cut) "$kept…" else kept
    }

    /**
     * Whether a stored title says what the work is, as opposed to where it ran.
     *
     * A title that is the home folder (`~`), a detached `HEAD`, a path, the session's own branch,
     * repository or folder name, or a run id (`<ticket>-<number>-impl` or `-plan`, as in `abc-21-impl`) is what a terminal or a git
     * checkout calls itself; none of them tells the person what the session was for.
     *
     * Called by the view model's title rule.
     *
     * @param title the stored title; null or blank is never meaningful.
     * @param branch the session's branch, if any.
     * @param repo the session's repository folder name, if any.
     * @param folder the last segment of the session's working folder, if any.
     * @return true when [title] can be shown as it is.
     */
    fun isMeaningfulTitle(title: String?, branch: String?, repo: String?, folder: String?): Boolean {
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return false
        if (t == "~" || t.equals("HEAD", ignoreCase = true) || (t.none { it.isWhitespace() } && isPathWord(t))) return false
        if (listOf(branch, repo, folder).any { it != null && it.isNotBlank() && it.trim().equals(t, ignoreCase = true) }) return false
        return !RUN_ID.matches(t)
    }

    /** Whether [word] reads as a file path or the home folder. */
    private fun isPathWord(word: String): Boolean =
        word == "~" || word.startsWith("~/") || word.startsWith("./") || word.startsWith("../") ||
            (word.startsWith("/") && word.length > 1 && word.drop(1).contains('/'))
}
