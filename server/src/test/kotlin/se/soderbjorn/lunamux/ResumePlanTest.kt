/**
 * Tests for [planResume] and its helpers: which folder and line a closed conversation resumes
 * with, when the task's declared command may be used, how a missing folder walks up to the
 * nearest one that exists, and when a pane the conversation ran in is typed into instead of a
 * new tab being opened. Disk and terminal facts come in through [ResumeProbe], so nothing here
 * touches the machine.
 *
 * @see planResume
 * @see resumeNoteOf
 * @see declaredResumeFor
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.AgentSessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ResumePlanTest {
    private val id = "0a1b2c3d-4e5f-6789-abcd-ef0123456789"

    private fun rec(
        agent: String = "claude",
        agentSessionId: String? = id,
        cwd: String? = "/work/app",
        leafId: String? = null,
    ) = AgentSessionRecord(
        id = "r1", agent = agent, agentSessionId = agentSessionId, cwd = cwd, leafId = leafId,
        title = "Fix it", state = AgentSessionState.ENDED,
    )

    private fun probe(
        dirs: Set<String> = setOf("/work/app", "/work", "/"),
        idle: Set<String> = emptySet(),
        cwdOf: Map<String, String> = emptyMap(),
    ) = ResumeProbe({ it in dirs }, { it in idle }, { cwdOf[it] })

    private fun plan(
        rec: AgentSessionRecord?,
        open: TaskOpen? = null,
        layout: WindowConfig = WindowConfig(),
        probe: ResumeProbe = probe(),
    ) = planResume(rec, open, layout, probe)

    private fun layoutWith(leafId: String, sessionId: String, background: Boolean = false, shown: Boolean = false): WindowConfig {
        val leaf = LeafNode(id = leafId, sessionId = sessionId, title = "x")
        val tab = TabConfig(
            id = "t1", title = "T", isBackground = background,
            panes = listOf(Pane(leaf = leaf, x = 0.0, y = 0.0, width = 1.0, height = 1.0, z = 1L)),
        )
        return WindowConfig(
            worlds = listOf(WorldConfig(id = "w1", name = "Home", tabs = listOf(tab), activeTabId = "t1")),
            activeWorldId = "w1",
            shownTabId = if (shown) "t1" else null,
        )
    }

    /** The plain case: the conversation's own folder and `claude --resume <id>`. */
    @Test
    fun `a claude conversation resumes by id in its folder`() {
        val p = assertIs<ResumePlan>(plan(rec()))
        assertEquals("/work/app", p.folder)
        assertEquals("claude --resume $id", p.command)
        assertEquals("Fix it", p.title)
        assertNull(p.note)
        assertNull(p.pane)
    }

    /** Every refusal says its reason in the exact words the overview shows. */
    @Test
    fun `refusals use the exact words`() {
        assertEquals(ResumeError("session no longer exists"), plan(null))
        assertEquals(ResumeError("No folder was recorded for this session."), plan(rec(cwd = null)))
        assertEquals(ResumeError("No folder was recorded for this session."), plan(rec(cwd = "  ")))
        assertEquals(ResumeError("Its folder is gone, and no folder above it exists."), plan(rec(), probe = probe(dirs = emptySet())))
        assertEquals(ResumeError("This conversation has no session id Lunamux can resume."), plan(rec(agentSessionId = "bad id; rm")))
        assertEquals(ResumeError("This conversation has no session id Lunamux can resume."), plan(rec(agentSessionId = null)))
        val gone = rec().copy(transcriptPath = "/nonexistent/transcript.jsonl")
        assertEquals(ResumeError("Its transcript is gone, so it can't be resumed."), plan(gone))
    }

    /** A session id with anything but letters, digits, `-` and `_`, or over 100 characters, is never typed. */
    @Test
    fun `session ids are checked before they are typed`() {
        assertEquals(true, isPlainSessionId("abc-DEF_123"))
        assertEquals(false, isPlainSessionId(""))
        assertEquals(false, isPlainSessionId("a".repeat(101)))
        assertEquals(false, isPlainSessionId("a b"))
        assertEquals(false, isPlainSessionId("a;b"))
        assertEquals(false, isPlainSessionId("é"))
    }

    /** The task's own command is used when it is a plain `claude` line; the id is put in after the program. */
    @Test
    fun `a declared claude line gets the id inserted`() {
        val open = TaskOpen("claude --model opus", "/work/app")
        val p = assertIs<ResumePlan>(plan(rec(), open))
        assertEquals("claude --resume $id --model opus", p.command)
        assertEquals("/work/app", p.folder)
    }

    /** A declared line that names the id already is used as written, wherever the record ran. */
    @Test
    fun `a declared line that has the id is used as written`() {
        val open = TaskOpen("claude --resume $id --model opus", "/work")
        assertEquals("claude --resume $id --model opus", declaredResumeFor(rec(cwd = "/elsewhere"), open))
    }

    /** The declared command is only used when the conversation ran in the declared folder. */
    @Test
    fun `a declared line is not used when the conversation ran elsewhere`() {
        val open = TaskOpen("claude --model opus", "/work")
        assertNull(declaredResumeFor(rec(cwd = "/work/app"), open))
        val p = assertIs<ResumePlan>(plan(rec(cwd = "/work/app"), open))
        assertEquals("claude --resume $id", p.command)
        assertEquals("/work/app", p.folder)
        assertEquals(true, sameFolder("/work/../work/", "/work"))
        assertEquals(false, sameFolder(null, "/work"))
    }

    /** Only a plain `claude` line is ever extended: no chaining, expansion, other programs or conversation picks. */
    @Test
    fun `an unsafe declared line is refused`() {
        for (line in listOf("claude; rm -rf /", "claude \$(whoami)", "other --x", "claude --continue", "claude -r abc", "claude | tee x", "claude `id`")) {
            assertNull(declaredResumeLine(line, id), line)
        }
        assertEquals("/bin/claude --resume $id", declaredResumeLine("/bin/claude", id))
        assertNull(declaredResumeFor(rec(agent = "codex"), TaskOpen("claude", "/work/app")))
    }

    /** A missing folder walks up to the nearest one that exists and says so in one line. */
    @Test
    fun `a missing folder resumes in the nearest folder above`() {
        val r = rec(cwd = "/work/app/gone/deeper")
        val p = assertIs<ResumePlan>(plan(r))
        assertEquals("/work/app", p.folder)
        assertEquals("Its folder is gone. It resumes in /work/app.", p.note)
        assertEquals("Its folder is gone. It resumes in /work/app.", resumeNoteOf(r, null, probe()))
        assertNull(resumeNoteOf(rec(), null, probe()))
        assertEquals("No folder was recorded for this session.", resumeNoteOf(rec(cwd = null), null, probe()))
        assertEquals("Its folder is gone, and no folder above it exists.", resumeNoteOf(rec(), null, probe(dirs = emptySet())))
        assertNull(nearestExistingFolder("/nope/x") { false })
        assertEquals("/", nearestExistingFolder("/nope/x") { it == "/" })
    }

    /** A pane the conversation ran in, idle and in a visible tab, takes the line instead of a new tab. */
    @Test
    fun `an idle pane in a visible tab is reused`() {
        val layout = layoutWith("n5", "s5")
        val p = assertIs<ResumePlan>(plan(rec(leafId = "n5"), layout = layout, probe = probe(idle = setOf("s5"), cwdOf = mapOf("s5" to "/work/app"))))
        assertEquals("s5", p.pane)
        assertEquals("claude --resume $id", p.paneLine)
    }

    /** A shell somewhere else gets a `cd` to the resume folder first, quoted. */
    @Test
    fun `a pane elsewhere is sent to the folder first`() {
        val layout = layoutWith("n5", "s5")
        val r = rec(leafId = "n5", cwd = "/work/it's app")
        val p = assertIs<ResumePlan>(plan(r, layout = layout, probe = probe(dirs = setOf("/work/it's app"), idle = setOf("s5"), cwdOf = mapOf("s5" to "/tmp"))))
        assertEquals("cd '/work/it'\\''s app' && claude --resume $id", p.paneLine)
        assertEquals("'a b'", shellQuoted("a b"))
    }

    /** No pane is reused when it is busy, gone, has no leaf, or sits in a background tab nobody has opened. */
    @Test
    fun `a pane that cannot take the line is not reused`() {
        val visible = layoutWith("n5", "s5")
        assertNull(assertIs<ResumePlan>(plan(rec(leafId = "n5"), layout = visible, probe = probe(idle = emptySet()))).pane, "busy")
        assertNull(assertIs<ResumePlan>(plan(rec(leafId = "n9"), layout = visible, probe = probe(idle = setOf("s5")))).pane, "no such leaf")
        assertNull(assertIs<ResumePlan>(plan(rec(leafId = null), layout = visible, probe = probe(idle = setOf("s5")))).pane, "no leaf")
        val hidden = layoutWith("n5", "s5", background = true)
        assertNull(assertIs<ResumePlan>(plan(rec(leafId = "n5"), layout = hidden, probe = probe(idle = setOf("s5")))).pane, "unshown background tab")
        val shown = layoutWith("n5", "s5", background = true, shown = true)
        assertEquals("s5", assertIs<ResumePlan>(plan(rec(leafId = "n5"), layout = shown, probe = probe(idle = setOf("s5"), cwdOf = mapOf("s5" to "/work/app")))).pane)
    }

    /** A gemini conversation has no command to type: a plain shell is opened in the folder. */
    @Test
    fun `an agent with no resume command opens a plain shell`() {
        val p = assertIs<ResumePlan>(plan(rec(agent = "gemini", agentSessionId = null)))
        assertNull(p.command)
        assertNull(p.pane)
    }
}
