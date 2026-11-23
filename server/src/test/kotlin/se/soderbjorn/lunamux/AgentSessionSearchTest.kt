/**
 * Tests for [AgentSessionSearch]: which conversations a query finds and how each hit reads. Two
 * of them pin fixes by name: a hit found only on disk keeps the reason its conversation ended, and
 * the month window is applied by the database before the row cap.
 *
 * @see AgentSessionSearch
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentSessionSearchTest {
    private val dir = File.createTempFile("lunamux-search", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val repo = SettingsRepository(File(dir, "test.db"))
    private val day = 24L * 60 * 60_000L
    private val now = 100 * day

    private fun ended(
        id: String, title: String? = null, prompt: String? = null, endedAt: Long = now - day, reason: String = "closed",
        text: String? = null, conversation: String = id,
    ) = AgentSessionRecord(
        id = id, agent = "claude", agentSessionId = conversation, source = "hooks", title = title, firstPrompt = prompt,
        state = "ended", stateSince = endedAt, createdAt = endedAt - 1, endedAt = endedAt, endReason = reason,
    ).also {
        repo.saveAgentSession(it)
        if (text != null) repo.setAgentSessionSearchText(id, text)
    }

    @Test
    fun `a title hit shows where it matched and ranks above a transcript hit`() {
        ended("a", title = "Fix the zebra crossing")
        ended("b", prompt = "unrelated", text = "we talked about a zebra here")
        val found = AgentSessionSearch.search(repo, null, "zebra", now = now)
        assertEquals(listOf("title", "transcript"), found.map { it.field })
        assertEquals("zebra", found[1].snippet!!.substring(found[1].start, found[1].start + found[1].length))
    }

    @Test
    fun `a query under two characters finds nothing`() {
        ended("a", title = "Fix the zebra crossing")
        assertTrue(AgentSessionSearch.search(repo, null, "z", now = now).isEmpty())
    }

    @Test
    fun `a search hit outside memory keeps its end reason`() {
        ended("a", title = "Fix the zebra crossing", reason = "restart", endedAt = now - 1_000)
        ended("b", title = "Another zebra", reason = "closed")
        val byId = AgentSessionSearch.search(repo, null, "zebra", now = now).associate { it.summary.id to it.summary }
        assertEquals("restart", byId.getValue("a").ended)
        assertEquals("closed", byId.getValue("b").ended)
    }

    @Test
    fun `the month window runs before the row cap`() {
        // 210 old title hits outrank one recent transcript hit, so a cap applied first would cut it.
        repeat(210) { ended("old$it", title = "needle old $it", endedAt = now - 60 * day) }
        ended("recent", prompt = "something else", text = "found the needle here", endedAt = now - day)
        val found = AgentSessionSearch.search(repo, null, "needle", now = now)
        assertEquals(listOf("recent"), found.map { it.summary.id })
    }

    @Test
    fun `one result per conversation`() {
        ended("a1", title = "zebra one", conversation = "same")
        ended("a2", title = "zebra two", conversation = "same", endedAt = now - 2 * day)
        assertEquals(1, AgentSessionSearch.search(repo, null, "zebra", now = now).size)
    }

    @Test
    fun `the tab name finds an open conversation`() {
        val t = AgentSessionTracker(repo, folderOf = { null }, clock = { now }).also { it.start() }
        t.onHook(
            PaneSnapshot("s1", "leaf-1", "/work", null),
            kotlinx.serialization.json.buildJsonObject {
                put("hook_event_name", kotlinx.serialization.json.JsonPrimitive("UserPromptSubmit"))
                put("session_id", kotlinx.serialization.json.JsonPrimitive("c-1"))
                put("prompt", kotlinx.serialization.json.JsonPrimitive("hello"))
            },
        )
        val found = AgentSessionSearch.search(repo, t, "walrus", tabNameOf = { "Walrus tab" }, now = now)
        assertEquals(listOf("tab"), found.map { it.field })
    }
}
