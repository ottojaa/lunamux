/**
 * Tests that [ThreadMarks] reaches the rows people see: the summaries [AgentSessionTracker]
 * publishes carry a thread key and mark (and none for a session in no thread), and the tasks
 * [TaskIndex] publishes copy the mark of their first member's thread and keep it.
 *
 * Runs against a real SQLite database with fake git, clock and ids, like [TaskIndexTest].
 *
 * @see ThreadMarks
 * @see AgentSessionTracker.summaryOf
 * @see TaskIndex
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ThreadMarksWiringTest {
    private var now = 1_000L
    private var nextId = 0
    private val dir = Files.createTempDirectory("lunamux-marks-wiring").toFile().canonicalFile.apply { deleteOnExit() }
    private val homeDir = File(dir, "home").apply { mkdirs() }.canonicalPath
    private val repo = SettingsRepository(File(dir, "test.db"))
    private val files = MutableStateFlow<List<TaskFile>>(emptyList())
    private val knownPanes = mutableSetOf<String>()
    private val gitFacts = mutableMapOf<String, GitInfo>()

    private val marks = ThreadMarks(repo, gitInfo = { gitFacts[it] ?: GitInfo() }, home = homeDir, defaultBranchOf = { null })

    private val tracker = AgentSessionTracker(
        repo,
        gitInfo = { gitFacts[it] ?: GitInfo() },
        clock = { now },
        newId = { "r${++nextId}" },
        marks = marks,
    ).also { it.start() }

    private val index = TaskIndex(
        repo, tracker, files,
        leafOfSession = { if (it in knownPanes) "leaf-$it" else null },
        clock = { now },
        marks = marks,
    )

    private fun folder(name: String) = File(dir, name).apply { mkdirs() }.canonicalPath

    /** A linked worktree named [name] of the repository `billing`, with git facts to match. */
    private fun worktree(name: String): String = folder("billing-wt/$name").also {
        gitFacts[it] = GitInfo(repo = "billing", branch = name, worktreePath = it, repoRoot = folder("billing"))
    }

    private fun event(name: String, id: String): JsonObject = buildJsonObject {
        put("hook_event_name", name)
        put("session_id", id)
    }

    private fun startSession(id: String, cwd: String) {
        val pane = PaneSnapshot(id, leafId = "leaf-$id", cwd = cwd, programTitle = null, screenText = { "screen" })
        knownPanes += id
        tracker.onHook(pane, event("SessionStart", "a-$id"))
        now += 10
    }

    private fun summaryIn(cwd: String) = tracker.summaries.value.single { it.cwd == cwd }

    @Test
    fun aSessionInALinkedWorktreeWearsThatThreadsMark() {
        val wt = worktree("invoice-export")
        startSession("p1", wt)
        val s = summaryIn(wt)
        assertEquals("path:$wt", s.threadKey)
        assertEquals(marks.markFor("path:$wt", now), s.mark)
    }

    @Test
    fun twoSessionsOfOneWorktreeWearTheSameMarkAndAnotherWorktreeDoesNot() {
        val a = worktree("invoice-export")
        val b = worktree("search-index")
        startSession("p1", a)
        startSession("p2", File(a, "src").apply { mkdirs() }.canonicalPath.also {
            gitFacts[it] = GitInfo(repo = "billing", branch = "invoice-export", worktreePath = a, repoRoot = folder("billing"))
        })
        startSession("p3", b)
        val all = tracker.summaries.value
        val marksOfA = all.filter { it.threadKey == "path:$a" }.map { it.mark }
        assertEquals(2, marksOfA.size)
        assertEquals(1, marksOfA.toSet().size)
        assertNotEquals(marksOfA.first(), all.single { it.threadKey == "path:$b" }.mark)
    }

    @Test
    fun aSessionInTheHomeFolderOrOnADefaultBranchWearsNoMark() {
        val main = folder("billing")
        gitFacts[main] = GitInfo(repo = "billing", branch = "main", repoRoot = main)
        startSession("p1", homeDir)
        startSession("p2", main)
        val all = tracker.summaries.value
        assertEquals(2, all.size)
        all.forEach {
            assertNull(it.mark)
            assertNull(it.threadKey)
        }
    }

    @Test
    fun aTrackerWithoutMarksPublishesNone() {
        val plain = AgentSessionTracker(repo, gitInfo = { gitFacts[it] ?: GitInfo() }, clock = { now }, newId = { "x${++nextId}" })
        val wt = worktree("invoice-export")
        plain.onHook(PaneSnapshot("q", leafId = "leaf-q", cwd = wt, programTitle = null, screenText = { "s" }), event("SessionStart", "a-q"))
        assertNull(plain.summaryOf(plain.openRecordOf("q")!!).mark)
    }

    private fun member(key: String, folder: String?) =
        TaskFileMember(AgentTaskMember(key, key, state = "idle"), matchSessionId = null, matchFolder = folder)

    private fun taskFile(id: String, vararg members: TaskFileMember, openFolder: String? = null) =
        TaskFile(id, "Task $id", null, openFolder?.let { TaskOpen("cmd", it) }, members.toList(), modified = 1)

    @Test
    fun aTaskCopiesTheMarkOfItsFirstMembersWorktreeEvenWhenNoSessionRuns() {
        val wt = worktree("invoice-export")
        files.value = listOf(taskFile("inv", member("parse", wt), member("migrate", wt), openFolder = wt))
        index.recompute()
        val task = index.state.value.tasks.single()
        assertEquals(marks.markFor("path:$wt", now), task.mark)
    }

    @Test
    fun aTaskAndTheLooseSessionsOfItsThreadWearOneMark() {
        val wt = worktree("invoice-export")
        startSession("p1", wt)
        files.value = listOf(taskFile("inv", member("parse", wt)))
        index.recompute()
        val task = index.state.value.tasks.single()
        assertEquals(summaryIn(wt).mark, task.mark)
        assertNotNull(task.mark)
    }

    @Test
    fun aTaskFallsBackToItsOpenFolderWhenItsMembersNameNone() {
        val wt = worktree("invoice-export")
        files.value = listOf(taskFile("inv", member("parse", null), openFolder = wt))
        index.recompute()
        assertEquals(marks.markFor("path:$wt", now), index.state.value.tasks.single().mark)
    }

    @Test
    fun aTaskKeepsItsMarkAfterItsFirstMemberMovesToAnotherFolder() {
        val first = worktree("invoice-export")
        val second = worktree("search-index")
        files.value = listOf(taskFile("inv", member("parse", first)))
        index.recompute()
        val before = index.state.value.tasks.single().mark
        files.value = listOf(taskFile("inv", member("parse", second)))
        index.recompute()
        assertEquals(before, index.state.value.tasks.single().mark)
        assertEquals(before, index.markOfTask("inv"))
    }

    @Test
    fun aTaskKeepsItsMarkAcrossARestart() {
        val wt = worktree("invoice-export")
        // Another thread holds the worktree's hash mark first, so the task's mark is a probed one
        // that a restart can only know from the stored row.
        val hash = ThreadMarks.hashMark("path:$wt")
        marks.markFor((0..10_000).map { "path:/rival$it" }.first { ThreadMarks.hashMark(it) == hash }, now)
        files.value = listOf(taskFile("inv", member("parse", wt)))
        index.recompute()
        val before = index.state.value.tasks.single().mark
        assertNotEquals(hash, before, "the task's mark was probed past the rival's")

        val restarted = ThreadMarks(SettingsRepository(File(dir, "test.db")), home = homeDir, defaultBranchOf = { null })
        val index2 = TaskIndex(repo, tracker, files, leafOfSession = { null }, clock = { now + 1 }, marks = restarted)
        assertEquals(before, index2.state.value.tasks.single().mark)
    }

    @Test
    fun aSearchShowsTheMarkAThreadHasButNeverGivesOrRenewsOne() {
        val seen = worktree("invoice-export")
        val unseen = worktree("search-index")
        startSession("p1", seen)
        val stored = marks.peekMark("path:$seen")
        assertNotNull(stored)
        val record = AgentSessionRecord(
            id = "old-1", agent = "claude", agentSessionId = "11111111-0000-4000-8000-000000000001", source = "hooks",
            cwd = unseen, repo = "billing", branch = "search-index", title = "needle in the search index",
            firstPrompt = "find the needle", state = "ended", stateSince = 100, createdAt = 50, endedAt = 100,
            repoRoot = folder("billing"), worktreePath = unseen,
        )
        repo.saveAgentSession(record)
        repo.saveAgentSession(record.copy(id = "old-2", cwd = seen, branch = "invoice-export", worktreePath = seen, title = "needle in the invoice export"))

        val hits = AgentSessionSearch.search(repo, tracker, "needle").associateBy { it.summary.id }
        assertEquals(stored, hits.getValue("old-2").summary.mark, "a thread with a stored mark shows it")
        assertNull(hits.getValue("old-1").summary.mark, "a thread no one has met gets no mark from a search")
        assertEquals("path:$unseen", hits.getValue("old-1").summary.threadKey)
        assertNull(marks.peekMark("path:$unseen"), "and nothing was stored for it")
    }
}
