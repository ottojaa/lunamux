/**
 * Tests for [AgentResumePlanner] against real throwaway git repositories:
 * how a session whose worktree was removed is planned (from a local branch, a
 * remote branch or the default branch), that recreating puts the worktree back
 * at its old path on the right branch, and what happens when git refuses.
 *
 * @see AgentResumePlanner
 * @see GitCatalog.addWorktree
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentResumePlannerTest {
    private val base = File.createTempFile("lunamux-planner", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val remote = File(base, "remote.git")
    private val main = File(base, "main")

    private fun git(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", *args))
            .directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${args.toList()} failed: $out" }
        return out.trim()
    }

    /** A repo `main` with one commit on `trunk`, cloned from a bare `remote.git` when [withRemote]. */
    private fun makeRepo(withRemote: Boolean = true) {
        if (withRemote) {
            git(base, "init", "--bare", "-b", "trunk", remote.path)
        }
        main.mkdirs()
        git(main, "init", "-b", "trunk")
        File(main, "a.txt").writeText("a")
        git(main, "add", ".")
        git(main, "commit", "-m", "one")
        if (withRemote) {
            git(main, "remote", "add", "origin", remote.path)
            git(main, "push", "-u", "origin", "trunk")
            git(main, "remote", "set-head", "origin", "trunk")
        }
    }

    private fun record(worktree: File, branch: String?, repoRoot: String? = main.path) = AgentSessionRecord(
        id = "r", agent = "claude", state = "ended", stateSince = 1, createdAt = 1, endedAt = 1,
        cwd = worktree.path, worktreePath = worktree.path, branch = branch, repoRoot = repoRoot,
    )

    private fun branchOf(dir: File) = git(dir, "branch", "--show-current")

    @Test
    fun `a folder that still exists resumes in place`() {
        makeRepo()
        val plan = AgentResumePlanner.plan(record(main, "trunk"))
        assertEquals(ResumePlan.InPlace, plan)
        assertNull(AgentResumePlanner.note(plan))
    }

    @Test
    fun `a removed worktree whose branch exists locally is recreated on it at the old path`() {
        makeRepo()
        val wt = File(main, ".claude/worktrees/feat")
        git(main, "worktree", "add", "-b", "feat", wt.path)
        File(wt, "work.txt").writeText("w")
        git(wt, "add", ".")
        git(wt, "commit", "-m", "work")
        wt.deleteRecursively() // removed by hand: git still lists it

        val plan = assertIs<ResumePlan.Recreate>(AgentResumePlanner.plan(record(wt, "feat")))
        assertEquals("local", plan.from)
        assertEquals("Worktree removed. Resume recreates it from branch feat.", AgentResumePlanner.note(plan))
        assertNull(AgentResumePlanner.recreate(plan))
        assertTrue(File(wt, "work.txt").isFile)
        assertEquals("feat", branchOf(wt))
    }

    @Test
    fun `a worktree removed with git worktree remove is recreated too`() {
        makeRepo()
        val wt = File(main, ".claude/worktrees/feat")
        git(main, "worktree", "add", "-b", "feat", wt.path)
        git(main, "worktree", "remove", wt.path)
        val plan = assertIs<ResumePlan.Recreate>(AgentResumePlanner.plan(record(wt, "feat")))
        assertNull(AgentResumePlanner.recreate(plan))
        assertEquals("feat", branchOf(wt))
    }

    @Test
    fun `a branch only on the remote is recreated from it`() {
        makeRepo()
        git(main, "branch", "feat")
        git(main, "push", "origin", "feat")
        git(main, "branch", "-D", "feat")
        val wt = File(main, ".claude/worktrees/feat")
        val plan = assertIs<ResumePlan.Recreate>(AgentResumePlanner.plan(record(wt, "feat")))
        assertEquals("remote", plan.from)
        assertNull(AgentResumePlanner.recreate(plan))
        assertEquals("feat", branchOf(wt))
    }

    @Test
    fun `a branch gone everywhere starts again from the default branch and says so`() {
        makeRepo()
        val wt = File(main, ".claude/worktrees/gone")
        val plan = assertIs<ResumePlan.Recreate>(AgentResumePlanner.plan(record(wt, "gone")))
        assertEquals("default", plan.from)
        assertEquals(
            "Worktree removed and branch gone is gone. Resume starts it again from trunk.",
            AgentResumePlanner.note(plan),
        )
        assertNull(AgentResumePlanner.recreate(plan))
        assertEquals("gone", branchOf(wt))
    }

    @Test
    fun `a repository without a remote falls back to the checked out branch`() {
        makeRepo(withRemote = false)
        val wt = File(main, ".claude/worktrees/gone")
        val plan = assertIs<ResumePlan.Recreate>(AgentResumePlanner.plan(record(wt, "gone")))
        assertEquals("trunk", plan.startLabel)
        assertNull(AgentResumePlanner.recreate(plan))
    }

    @Test
    fun `the repository is found from the old path when no root was recorded`() {
        makeRepo()
        val wt = File(main, ".claude/worktrees/feat")
        git(main, "worktree", "add", "-b", "feat", wt.path)
        wt.deleteRecursively()
        val plan = assertIs<ResumePlan.Recreate>(AgentResumePlanner.plan(record(wt, "feat", repoRoot = null)))
        assertEquals(main.canonicalFile, File(plan.repoRoot).canonicalFile)
    }

    @Test
    fun `missing pieces give a reason instead of a plan`() {
        makeRepo()
        val wt = File(main, ".claude/worktrees/feat")
        val noBranch = assertIs<ResumePlan.Missing>(AgentResumePlanner.plan(record(wt, null)))
        assertTrue("branch" in noBranch.reason)
        val noRepo = File(base, "vanished/.claude/worktrees/x")
        val missing = assertIs<ResumePlan.Missing>(AgentResumePlanner.plan(record(noRepo, "feat", repoRoot = "/nope")))
        assertNotNull(AgentResumePlanner.note(missing))
        val noCwd = assertIs<ResumePlan.Missing>(AgentResumePlanner.plan(record(wt, "feat").copy(cwd = null)))
        assertTrue("folder" in noCwd.reason.lowercase())
        val plain = record(File(base, "plain-gone"), "feat").copy(worktreePath = null)
        assertEquals("Folder no longer exists.", assertIs<ResumePlan.Missing>(AgentResumePlanner.plan(plain)).reason)
    }

    @Test
    fun `a branch checked out in another worktree is reported as a git error`() {
        makeRepo()
        val wt = File(main, ".claude/worktrees/feat")
        git(main, "worktree", "add", "-b", "feat", wt.path)
        val other = File(main, ".claude/worktrees/other")
        // The plan was made for a path that no longer exists, but the branch is still checked out at `wt`.
        val plan = assertIs<ResumePlan.Recreate>(AgentResumePlanner.plan(record(other, "feat")))
        val error = AgentResumePlanner.recreate(plan)
        assertNotNull(error)
        assertTrue(error.isNotBlank())
        assertTrue(!other.exists())
    }
}
