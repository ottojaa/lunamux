/**
 * Tests for how a task panel block's content is read and written as JSON, shared by every platform
 * that decodes a stored or pushed block.
 *
 * Runs on every :clientServer target: the server encodes a block with [windowJson] and stores it,
 * and the web, Android and iOS clients decode it with the same configuration, so all of them must
 * agree on a link's own kind and time and on what a missing one means.
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaskPanelContentTest {

    private fun encode(c: TaskBlockContent): String = windowJson.encodeToString(TaskBlockContent.serializer(), c)

    private fun decode(json: String): TaskBlockContent = windowJson.decodeFromString(TaskBlockContent.serializer(), json)

    @Test
    fun linksRoundTripWithTheirKindAndTime() {
        val content = TaskBlockContent.Links(
            listOf(
                TaskLink("PR", "Fix the footer", "https://github.com/o/r/pull/12", 1_700_000_000_000L),
                TaskLink(label = "Notes", url = "file:///tmp/notes.md"),
            ),
        )
        assertEquals(content, decode(encode(content)))
        assertEquals("links", content.kindName)
    }

    @Test
    fun aLinkIsWrittenWithItsKindAndAnAbsentTime() {
        val json = encode(TaskBlockContent.Links(listOf(TaskLink(label = "A", url = "https://a.b"))))
        assertTrue("\"kind\":\"links\"" in json, "the block keeps its own kind name: $json")
        assertTrue("\"kind\":\"link\"" in json, "the link's kind is written, so every client reads it the same way: $json")
        assertTrue("\"at\":null" in json, "an absent time is written as null: $json")
    }

    @Test
    fun aLinkStoredBeforeKindsAndTimesReadsAsKindLinkWithNoTime() {
        val read = decode("""{"kind":"links","items":[{"label":"A","url":"https://a.b"}]}""")
        assertEquals(TaskLink("link", "A", "https://a.b", null), (read as TaskBlockContent.Links).items.single())
    }

    @Test
    fun aLinkIgnoresFieldsAClientDoesNotKnow() {
        val read = decode("""{"kind":"links","items":[{"kind":"PR","label":"A","url":"https://a.b","at":5,"opens":"inside"}]}""")
        assertEquals(TaskLink("PR", "A", "https://a.b", 5L), (read as TaskBlockContent.Links).items.single())
    }

    @Test
    fun aBlockOfARemovedKindIsNoLongerReadable() {
        assertFailsWith<SerializationException> {
            decode("""{"kind":"text","markdown":"x"}""")
        }
    }
}
