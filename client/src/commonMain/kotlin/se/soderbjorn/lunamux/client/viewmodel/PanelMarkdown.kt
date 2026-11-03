/**
 * The small markdown subset a task panel's text block is written in, parsed into a node tree the
 * views draw with their own elements. Contains [PanelMarkdown] (the parser) and the node types
 * [MdBlock] and [MdInline].
 *
 * The parser never produces markup: it reads paragraphs, `#` to `###` headings, `-`, `*` and `1.`
 * lists, fenced code, `**bold**`, `*italic*`, `` `code` `` and `[label](url)` links, and keeps
 * everything else, HTML included, as literal text. A link only becomes a link when its address
 * starts with `http://`, `https://` or `file://`; any other scheme (`javascript:` and the like)
 * leaves the whole `[label](url)` as written. Because the result is data, a view that sets
 * element text from it cannot be made to run what an agent wrote.
 *
 * @see TaskPanelViewModel
 * @see se.soderbjorn.lunamux.TaskBlockContent.Text
 */
package se.soderbjorn.lunamux.client.viewmodel

/**
 * One block of parsed panel text.
 *
 * @see PanelMarkdown.parse
 */
sealed class MdBlock {
    /**
     * A run of text lines with no blank line between them.
     *
     * @property inlines the text of the lines, lines joined by a space.
     */
    data class Paragraph(val inlines: List<MdInline>) : MdBlock()

    /**
     * A `#`, `##` or `###` heading.
     *
     * @property level 1 to 3.
     * @property inlines the heading text.
     */
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock()

    /**
     * Consecutive list items of one kind.
     *
     * @property ordered true for `1.` items, false for `-` and `*` items.
     * @property items the text of each item.
     */
    data class ListBlock(val ordered: Boolean, val items: List<List<MdInline>>) : MdBlock()

    /**
     * A fenced code block, shown as written.
     *
     * @property text the lines between the fences, joined by newlines.
     */
    data class Code(val text: String) : MdBlock()
}

/**
 * One piece of inline text.
 *
 * @see PanelMarkdown.parse
 */
sealed class MdInline {
    /** Plain text, shown exactly as written. @property text the characters. */
    data class Text(val text: String) : MdInline()

    /** Bold text. @property children what is inside the `**` pair. */
    data class Bold(val children: List<MdInline>) : MdInline()

    /** Italic text. @property children what is inside the `*` pair. */
    data class Italic(val children: List<MdInline>) : MdInline()

    /** Inline code, shown as written. @property text what is between the backticks. */
    data class Code(val text: String) : MdInline()

    /**
     * A link the person can open.
     *
     * @property label the words shown, as plain text.
     * @property url the address; always starts with `http://`, `https://` or `file://`.
     */
    data class Link(val label: String, val url: String) : MdInline()
}

/**
 * Parser for the panel's markdown subset.
 *
 * Called by [TaskPanelViewModel] for every text block, so each client draws the same nodes, and
 * by tests that hold what stays literal.
 */
object PanelMarkdown {
    private val heading = Regex("^(#{1,3}) +(.*)$")
    private val bullet = Regex("^[-*] +(.*)$")
    private val numbered = Regex("^[0-9]+\\. +(.*)$")
    private val safeUrl = Regex("^(?:https?|file)://\\S+$", RegexOption.IGNORE_CASE)

    /**
     * Whether [url] is an address a link may open: `http`, `https` or `file`, with no spaces.
     *
     * Called by [parse] for each link and by views before they open one, so a link is checked
     * where it is made and again where it is used.
     *
     * @param url the address to check.
     * @return true when the scheme is one of the three.
     */
    fun isSafeUrl(url: String): Boolean = safeUrl.matches(url)

    /**
     * Parse [markdown] into blocks.
     *
     * @param markdown the text an agent wrote.
     * @return the blocks in order; empty for blank text.
     * @see parseInline
     */
    fun parse(markdown: String): List<MdBlock> {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<MdBlock>()
        val paragraph = ArrayList<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) out += MdBlock.Paragraph(parseInline(paragraph.joinToString(" ")))
            paragraph.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val code = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) code += lines[i++]
                    out += MdBlock.Code(code.joinToString("\n"))
                }
                trimmed.isEmpty() -> flush()
                heading.matches(trimmed) -> {
                    flush()
                    val m = heading.matchEntire(trimmed)!!
                    out += MdBlock.Heading(m.groupValues[1].length, parseInline(m.groupValues[2].trim()))
                }
                bullet.matches(trimmed) || numbered.matches(trimmed) -> {
                    flush()
                    val ordered = numbered.matches(trimmed)
                    val items = ArrayList<List<MdInline>>()
                    while (i < lines.size) {
                        val t = lines[i].trim()
                        val m = (if (ordered) numbered else bullet).matchEntire(t) ?: break
                        items += parseInline(m.groupValues[1].trim())
                        i++
                    }
                    i--
                    out += MdBlock.ListBlock(ordered, items)
                }
                else -> paragraph += trimmed
            }
            i++
        }
        flush()
        return out
    }

    /**
     * Parse one line of inline text. A marker without its partner, and a link whose address is not
     * allowed, stay as the characters written.
     *
     * @param text the text to read.
     * @return the pieces in order, with neighbouring plain text merged.
     */
    fun parseInline(text: String): List<MdInline> {
        val out = ArrayList<MdInline>()
        val plain = StringBuilder()
        fun flushPlain() {
            if (plain.isNotEmpty()) out += MdInline.Text(plain.toString())
            plain.setLength(0)
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    flushPlain(); out += MdInline.Code(text.substring(i + 1, end)); i = end + 1; continue
                }
            } else if (c == '*' && text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    flushPlain(); out += MdInline.Bold(parseInline(text.substring(i + 2, end))); i = end + 2; continue
                }
            } else if (c == '*') {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1 && !text[i + 1].isWhitespace() && !text[end - 1].isWhitespace()) {
                    flushPlain(); out += MdInline.Italic(parseInline(text.substring(i + 1, end))); i = end + 1; continue
                }
            } else if (c == '[') {
                val close = text.indexOf("](", i + 1)
                val end = if (close > i) text.indexOf(')', close + 2) else -1
                if (close > i && end > close + 2) {
                    val url = text.substring(close + 2, end)
                    if (isSafeUrl(url)) {
                        flushPlain(); out += MdInline.Link(text.substring(i + 1, close), url); i = end + 1; continue
                    }
                }
            }
            plain.append(c)
            i++
        }
        flushPlain()
        return out
    }
}
