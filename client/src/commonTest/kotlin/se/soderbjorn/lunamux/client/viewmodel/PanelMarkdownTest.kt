/**
 * Tests for [PanelMarkdown]: what the subset reads (paragraphs, headings, lists, bold, italic,
 * code, fenced code, links), and, as important, what it leaves as literal text: HTML, unknown
 * markup, unmatched markers and links to anything but http, https and file.
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PanelMarkdownTest {
    private fun t(s: String) = MdInline.Text(s)

    @Test
    fun `paragraphs split at blank lines and join their own lines with a space`() {
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(t("one two"))), MdBlock.Paragraph(listOf(t("three")))),
            PanelMarkdown.parse("one\ntwo\n\nthree"),
        )
        assertEquals(emptyList(), PanelMarkdown.parse("  \n\n"))
    }

    @Test
    fun `headings take one to three hashes and need a space`() {
        assertEquals(
            listOf(
                MdBlock.Heading(1, listOf(t("A"))), MdBlock.Heading(2, listOf(t("B"))), MdBlock.Heading(3, listOf(t("C"))),
                MdBlock.Paragraph(listOf(t("#### D"))), MdBlock.Paragraph(listOf(t("#E"))),
            ),
            PanelMarkdown.parse("# A\n## B\n### C\n\n#### D\n\n#E"),
        )
    }

    @Test
    fun `lists group by kind`() {
        assertEquals(
            listOf(
                MdBlock.ListBlock(false, listOf(listOf(t("a")), listOf(t("b")), listOf(t("c")))),
                MdBlock.ListBlock(true, listOf(listOf(t("x")), listOf(t("y")))),
            ),
            PanelMarkdown.parse("- a\n* b\n- c\n1. x\n2. y"),
        )
    }

    @Test
    fun `bold italic and code nest as expected`() {
        assertEquals(
            listOf(t("a "), MdInline.Bold(listOf(t("b "), MdInline.Italic(listOf(t("c"))), t(" d"))), t(" "), MdInline.Code("d**e")),
            PanelMarkdown.parseInline("a **b *c* d** `d**e`"),
        )
    }

    @Test
    fun `unmatched markers stay as written`() {
        assertEquals(listOf(t("**open and `tick and [x](")), PanelMarkdown.parseInline("**open and `tick and [x]("))
        assertEquals(listOf(t("2 * 3 * 4")), PanelMarkdown.parseInline("2 * 3 * 4"))
    }

    @Test
    fun `fenced code is kept exactly, markup inside included`() {
        assertEquals(
            listOf(MdBlock.Code("a **b**\n  <b>c</b>"), MdBlock.Paragraph(listOf(t("after")))),
            PanelMarkdown.parse("```kotlin\na **b**\n  <b>c</b>\n```\nafter"),
        )
    }

    @Test
    fun `links open only http, https and file`() {
        for (url in listOf("http://a.example/x", "https://a.example/x?y=1", "file:///tmp/a.txt", "HTTPS://A.EXAMPLE")) {
            assertEquals(listOf(MdInline.Link("go", url)), PanelMarkdown.parseInline("[go]($url)"), url)
            assertTrue(PanelMarkdown.isSafeUrl(url))
        }
        for (url in listOf("javascript:alert(1)", "data:text/html,x", "vbscript:x", "ftp://a.example", "//a.example", "/rel", "https://a b", " https://a.example", "")) {
            assertFalse(PanelMarkdown.isSafeUrl(url), url)
            val md = "[go]($url)"
            assertEquals(listOf(t(md)), PanelMarkdown.parseInline(md), "left literal: $url")
        }
    }

    @Test
    fun `html is shown as literal text and never becomes a node`() {
        val evil = "<img src=x onerror=alert(1)>"
        assertEquals(listOf(MdBlock.Paragraph(listOf(t(evil)))), PanelMarkdown.parse(evil))
        val mixed = PanelMarkdown.parse("**<script>alert(1)</script>** and <a href=\"javascript:x\">y</a>")
        val inlines = (mixed.single() as MdBlock.Paragraph).inlines
        assertEquals(MdInline.Bold(listOf(t("<script>alert(1)</script>"))), inlines[0])
        assertEquals(t(" and <a href=\"javascript:x\">y</a>"), inlines[1])
        assertTrue(inlines.none { it is MdInline.Link })
    }
}
