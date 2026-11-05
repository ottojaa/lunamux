/**
 * Tests for how a task panel block's content is read and written as JSON, shared by every
 * platform that decodes a stored or pushed block.
 *
 * Runs on every :clientServer target: the server encodes a block with [windowJson] and stores it,
 * and the web, Android and iOS clients decode it with the same configuration, so all of them
 * must agree on the documents block, its `kind` name and what a missing time means.
 *
 * @see TaskBlockContent.Documents
 * @see TaskDocument
 */
package se.soderbjorn.lunamux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskPanelContentTest {

    private fun encode(c: TaskBlockContent): String = windowJson.encodeToString(TaskBlockContent.serializer(), c)

    private fun decode(json: String): TaskBlockContent = windowJson.decodeFromString(TaskBlockContent.serializer(), json)

    @Test
    fun documentsRoundTripWithAndWithoutATime() {
        val content = TaskBlockContent.Documents(
            listOf(
                TaskDocument("Design page", "https://example.com/design", 1_700_000_000_000L),
                TaskDocument("Notes", "file:///tmp/notes.md"),
            ),
        )
        assertEquals(content, decode(encode(content)))
        assertEquals("documents", content.kindName)
    }

    @Test
    fun documentsAreWrittenUnderTheirOwnKind() {
        val json = encode(TaskBlockContent.Documents(listOf(TaskDocument("A", "https://a.b"))))
        assertTrue("\"kind\":\"documents\"" in json, json)
        assertTrue("\"updatedMillis\":null" in json, "an absent time is written, so every client reads it the same way: $json")
    }

    @Test
    fun aStoredDocumentWithNoTimeReadsAsNull() {
        val read = decode("""{"kind":"documents","items":[{"title":"A","url":"https://a.b"}]}""")
        val item = (read as TaskBlockContent.Documents).items.single()
        assertEquals("A", item.title)
        assertNull(item.updatedMillis)
    }

    @Test
    fun aDocumentIgnoresFieldsAClientDoesNotKnow() {
        val read = decode("""{"kind":"documents","items":[{"title":"A","url":"https://a.b","updatedMillis":5,"opens":"inside"}]}""")
        assertEquals(TaskDocument("A", "https://a.b", 5L), (read as TaskBlockContent.Documents).items.single())
    }

    @Test
    fun everyOtherKindKeepsItsName() {
        assertEquals("links", decode("""{"kind":"links","items":[{"label":"x","url":"https://a.b"}]}""").let { (it as TaskBlockContent.Links).kindName })
        assertEquals("text", decode("""{"kind":"text","markdown":"x"}""").let { (it as TaskBlockContent.Text).kindName })
    }
}
