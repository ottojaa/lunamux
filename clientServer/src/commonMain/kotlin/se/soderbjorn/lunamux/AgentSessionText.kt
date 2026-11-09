/**
 * Plain-text rules the agents overview applies to what a session says about itself, shared by the
 * server and every client so they agree.
 *
 * This file contains [AgentSessionText]: it turns a line of markdown into the text a reader sees,
 * skips lines that are only a code fence, derives a title from the first words of a prompt, and
 * says whether a stored title actually names the work. The overview's view model uses it to title
 * rows and clean previews, and the server's session search and last-reply capture use it so a title
 * that only says where a session ran is not searched and a fence is not stored as a reply.
 */
package se.soderbjorn.lunamux

/** Pure text helpers for agent session rows: no state, no clock, no I/O. */
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
     */
    fun isFenceLine(line: String): Boolean = FENCE.matches(line)

    /** Turn one line of markdown into the text it reads as. */
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

    /** The first line of [text] worth showing as a preview, as plain text. */
    fun previewOf(text: String?): String? =
        text?.lineSequence()
            ?.filterNot { isFenceLine(it) || RULE.matches(it) }
            ?.map(::plain)
            ?.firstOrNull { it.isNotEmpty() }

    /** A title made from the first words of a prompt. */
    fun titleFromPrompt(prompt: String?): String? {
        val line = previewOf(prompt) ?: return null
        val words = line.split(' ').filter { it.isNotEmpty() }
        // Only the words the prompt starts with are dropped (a slash command, a path): they say how it
        // was started, not what it asks. The same words later on are code the person typed and stay.
        val prose = words.dropWhile { it.startsWith("/") || isPathWord(it) }
        if (prose.isEmpty()) return null
        var kept = prose.take(TITLE_WORDS).joinToString(" ")
        var cut = prose.size > TITLE_WORDS
        if (kept.length > TITLE_CHARS) {
            kept = kept.take(TITLE_CHARS).substringBeforeLast(' ', kept.take(TITLE_CHARS)).trimEnd(',', ';', ':', '.', '-')
            cut = true
        }
        return if (cut) "$kept…" else kept
    }

    /** Whether a stored title says what the work is, as opposed to where it ran. */
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
