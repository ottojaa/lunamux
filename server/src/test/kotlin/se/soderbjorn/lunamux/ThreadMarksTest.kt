/**
 * Tests for [ThreadMarks]: the rules that name a session's thread (worktree, non-default branch,
 * plain folder, the home folder, a default branch, a task file's folder), how a thread gets its
 * mark (probing past a mark another thread holds, sharing once all 36 are held), that a stored
 * mark survives a restart, and that a task copies the mark of its first member's thread.
 *
 * Runs against a real SQLite database in a temporary folder; no git process is started.
 *
 * @see ThreadMarks
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadMarksTest {
    private val dir = Files.createTempDirectory("lunamux-thread-marks").toFile().canonicalFile
        .apply { deleteOnExit() }
    private val homeDir = File(dir, "home").apply { mkdirs() }.canonicalPath
    private val db = File(dir, "test.db")
    private val day = 24L * 60 * 60 * 1000
    private var gitFacts = mapOf<String, GitInfo>()

    private fun marks(
        repo: SettingsRepository = SettingsRepository(db),
        defaultBranch: String? = null,
    ) = ThreadMarks(
        repo,
        gitInfo = { gitFacts[it] ?: GitInfo() },
        home = homeDir,
        defaultBranchOf = { defaultBranch },
    )

    private fun folder(name: String) = File(dir, name).apply { mkdirs() }.canonicalPath

    // --- key rules ---------------------------------------------------------------------------

    @Test
    fun aLinkedWorktreeIsOneThreadWhateverItsBranch() {
        val m = marks()
        val wt = folder("billing/invoice-export")
        assertEquals("path:$wt", m.keyOf(wt, folder("billing"), "invoice-export", wt))
        assertEquals("path:$wt", m.keyOf(wt, folder("billing"), "main", "$wt/src"))
    }

    @Test
    fun aBranchOtherThanTheDefaultIsAThreadOfItsRepository() {
        val m = marks()
        val root = folder("search")
        assertEquals("branch:$root#reindex", m.keyOf(null, root, "reindex", root))
    }

    @Test
    fun theDefaultBranchOfAMainCheckoutHasNoThread() {
        val root = folder("search")
        assertNull(marks().keyOf(null, root, "main", root))
        assertNull(marks().keyOf(null, root, "master", root))
        assertNull(marks().keyOf(null, root, null, root), "a detached HEAD names no thread")
    }

    @Test
    fun theBranchOriginHeadNamesIsTheDefaultToo() {
        val root = folder("search")
        assertNull(marks(defaultBranch = "develop").keyOf(null, root, "develop", root))
        assertEquals("branch:$root#develop", marks().keyOf(null, root, "develop", root))
    }

    @Test
    fun aPlainFolderOutsideGitIsAThreadOfItsOwn() {
        val plain = folder("notes/trip")
        assertEquals("path:$plain", marks().keyOf(null, null, null, plain))
    }

    @Test
    fun theHomeFolderAndASessionWithNoFolderHaveNoThread() {
        val m = marks()
        assertNull(m.keyOf(null, null, null, homeDir))
        assertNull(m.keyOf(null, null, null, "$homeDir/"))
        assertNull(m.keyOf(null, null, null, null))
        assertNull(m.keyOf(null, null, null, ""))
    }

    @Test
    fun aRecordThatKnowsItsBranchButNotItsRepositoryRootIsStillInsideGit() {
        val m = marks()
        val checkout = folder("imported/search")
        assertNull(m.keyOf(null, null, "main", checkout), "the default branch of a main checkout has no thread")
        assertNull(m.keyOf(null, null, "master", checkout))
        val reindex = m.keyOf(null, null, "reindex", checkout)
        assertEquals("branch:$checkout#reindex", reindex)
        assertNotEquals(reindex, m.keyOf(null, null, "other", checkout), "each branch is a thread of its own")
        assertEquals("branch:$checkout#reindex", m.keyOf(null, null, "reindex", "$checkout/"))
    }

    @Test
    fun aTaskFilesFolderOnTheHomeFolderOrADefaultBranchHasNoThread() {
        val main = folder("search")
        val other = folder("search-reindex")
        gitFacts = mapOf(
            main to GitInfo(repo = "search", branch = "main", repoRoot = main),
            other to GitInfo(repo = "search", branch = "reindex", repoRoot = other),
            homeDir to GitInfo(),
        )
        val m = marks()
        assertNull(m.folderKey(main), "a repository's default branch names no thread")
        assertNull(m.folderKey(homeDir), "neither does the home folder")
        assertEquals("branch:$other#reindex", m.folderKey(other))
    }

    @Test
    fun aSymlinkedFolderAndItsTargetAreOneThread() {
        val target = folder("real/project")
        val link = File(dir, "link").also { Files.createSymbolicLink(it.toPath(), File(target).toPath()) }
        val m = marks()
        assertEquals(m.keyOf(null, null, null, target), m.keyOf(null, null, null, link.path))
        assertEquals("path:$target", m.keyOf(null, null, null, link.path))
    }

    @Test
    fun aTaskFilesFolderInALinkedWorktreeKeysAsTheWorktreeRoot() {
        val wt = folder("billing/invoice-export")
        val sub = folder("billing/invoice-export/src")
        gitFacts = mapOf(sub to GitInfo(repo = "billing", branch = "invoice-export", worktreePath = wt, repoRoot = folder("billing")))
        val m = marks()
        assertEquals("path:$wt", m.folderKey(sub))
        assertEquals("path:$wt", m.folderKey(wt))
    }

    @Test
    fun aTaskFilesFolderThatIsNotAWorktreeKeysAsItself() {
        val plain = folder("notes/trip")
        assertEquals("path:$plain", marks().folderKey(plain))
        assertEquals("path:/does/not/exist", marks().folderKey("/does/not/exist"))
    }

    // --- marks --------------------------------------------------------------------------------

    @Test
    fun aNewThreadGetsItsHashMarkAndKeepsIt() {
        val m = marks()
        val mark = m.markFor("path:/a", 1_000)
        assertEquals(ThreadMarks.hashMark("path:/a"), mark)
        assertTrue(mark in 0 until 36)
        assertEquals(mark, m.markFor("path:/a", 2_000))
    }

    @Test
    fun hashMarkMatchesTheKnownFnv1aValues() {
        // FNV-1a 32 of "" is its offset basis 0x811c9dc5 = 2166136261; of "a" it is 0xe40c292c.
        assertEquals((2166136261L % 36).toInt(), ThreadMarks.hashMark(""))
        assertEquals((0xe40c292cL % 36).toInt(), ThreadMarks.hashMark("a"))
    }

    @Test
    fun aThreadProbesPastAMarkAnotherThreadHolds() {
        val m = marks()
        val first = ThreadMarks.hashMark("path:/a")
        // Find another key whose hash lands on the same mark.
        val clash = (0..10_000).map { "path:/b$it" }.first { ThreadMarks.hashMark(it) == first }
        assertEquals(first, m.markFor("path:/a", 1_000))
        val second = m.markFor(clash, 2_000)
        assertNotEquals(first, second)
        assertEquals((first + 7) % 36, second, "steps by seven to the next free mark")
        assertNotEquals(first / 6, second / 6, "a step changes the shape")
        assertNotEquals(first % 6, second % 6, "and the hue")
    }

    @Test
    fun aMarkHeldByAThreadNotSeenForFourteenDaysIsFreeAgain() {
        val m = marks()
        val first = ThreadMarks.hashMark("path:/a")
        val clash = (0..10_000).map { "path:/b$it" }.first { ThreadMarks.hashMark(it) == first }
        m.markFor("path:/a", 1_000)
        assertEquals(first, m.markFor(clash, 1_000 + 15 * day))
    }

    @Test
    fun threadsShareAMarkOnceAll36AreHeld() {
        val m = marks()
        val keys = generateSequence(0) { it + 1 }.map { "path:/t$it" }.take(36).toList()
        val marks = keys.map { m.markFor(it, 1_000) }
        assertEquals(36, marks.toSet().size, "36 threads wear 36 different marks")
        val extra = "path:/one-too-many"
        assertEquals(ThreadMarks.hashMark(extra), m.markFor(extra, 2_000), "the 37th keeps its hash mark")
        assertEquals(marks, keys.map { m.markFor(it, 3_000) }, "and nobody else is moved")
    }

    @Test
    fun aStoredMarkSurvivesARestart() {
        val first = ThreadMarks.hashMark("path:/a")
        val clash = (0..10_000).map { "path:/b$it" }.first { ThreadMarks.hashMark(it) == first }
        val before = marks(SettingsRepository(db))
        before.markFor("path:/a", 1_000)
        val probed = before.markFor(clash, 1_000)
        assertNotEquals(first, probed, "the second thread was probed to another mark")
        val task = before.markForTask("t1", "path:/a", 1_000)

        // After a restart the probed thread is met before its rival: only the stored row knows its mark.
        val after = marks(SettingsRepository(db))
        assertEquals(probed, after.markFor(clash, 9_000_000_000))
        assertEquals(first, after.markFor("path:/a", 9_000_000_000))
        assertEquals(task, after.markOfTask("t1"))
    }

    @Test
    fun aRestartDoesNotReassignAMarkWhoseThreadHasGoneQuiet() {
        val first = ThreadMarks.hashMark("path:/a")
        val clash = (0..10_000).map { "path:/b$it" }.first { ThreadMarks.hashMark(it) == first }
        val before = marks()
        before.markFor("path:/a", 1_000)
        val taken = before.markFor(clash, 1_000 + 15 * day)
        assertEquals(first, taken, "the quiet thread's mark was free for the newcomer")

        // The newcomer is met first after the restart, so the quiet thread's only claim is its stored row.
        val after = marks(SettingsRepository(db))
        assertEquals(first, after.markFor(clash, 1_000 + 16 * day))
        assertEquals(first, after.markFor("path:/a", 1_000 + 16 * day), "the first thread still wears its own")
    }

    @Test
    fun peekingAtAMarkNeitherAssignsItNorRenewsTheThread() {
        val m = marks()
        assertNull(m.peekMark("path:/a"), "a thread nobody has met has no mark to read")
        val first = ThreadMarks.hashMark("path:/a")
        val clash = (0..10_000).map { "path:/b$it" }.first { ThreadMarks.hashMark(it) == first }
        assertNull(m.peekMark("path:/a"))
        assertEquals(first, m.markFor(clash, 1_000), "peeking at path:/a held nothing: the clash keeps its hash mark")
        assertEquals(first, m.peekMark(clash))
        // A peek 15 days later renews nothing, so the thread's mark is free for a newcomer.
        m.peekMark(clash)
        val third = (0..10_000).map { "path:/c$it" }.first { ThreadMarks.hashMark(it) == first }
        assertEquals(first, m.markFor(third, 1_000 + 15 * day))
    }

    // --- tasks --------------------------------------------------------------------------------

    @Test
    fun aTaskCopiesTheMarkOfItsFirstMembersThread() {
        val m = marks()
        val thread = m.markFor("path:/wt", 1_000)
        assertEquals(thread, m.markForTask("task-1", "path:/wt", 2_000))
        assertEquals(thread, m.markOfTask("task-1"))
    }

    @Test
    fun aTaskKeepsItsMarkWhateverItsMembersDoLater() {
        val m = marks()
        val first = m.markForTask("task-1", "path:/wt", 1_000)
        assertEquals(first, m.markForTask("task-1", "path:/elsewhere", 2_000))
        assertEquals(first, m.markForTask("task-1", null, 3_000))
    }

    @Test
    fun aTaskWithNoThreadGetsAMarkOfItsOwn() {
        val m = marks()
        val mark = m.markForTask("task-2", null, 1_000)
        assertTrue(mark in 0 until 36)
        assertEquals(mark, m.markOfTask("task-2"))
        assertNull(m.markOfTask("task-unknown"))
    }

    @Test
    fun aTaskRowHoldsItsMarkAgainstNewThreads() {
        val m = marks()
        val loneTask = m.markForTask("task-3", null, 1_000)
        val clash = (0..10_000).map { "path:/c$it" }.first { ThreadMarks.hashMark(it) == loneTask }
        assertNotEquals(loneTask, m.markFor(clash, 2_000))
    }
}
