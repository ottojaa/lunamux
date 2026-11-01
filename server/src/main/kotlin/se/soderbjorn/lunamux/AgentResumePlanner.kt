/**
 * Decides how a closed agent session can be resumed, and brings back a
 * worktree that was removed in the meantime.
 *
 * Claude Code finds a conversation by the folder it ran in, so resuming a
 * session that ran in a linked worktree needs that worktree at its old path.
 * This file contains [AgentResumePlanner] and its result type [ResumePlan].
 * The same plan produces the note shown under a row beforehand and drives the
 * resume itself, so the two can never disagree.
 *
 * @see GitCatalog.addWorktree
 * @see se.soderbjorn.lunamux.persistence.AgentSessionRecord
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import java.io.File
import java.nio.file.Path

/**
 * How a closed session would be resumed.
 *
 * @see AgentResumePlanner.plan
 */
sealed class ResumePlan {
    /** The session's folder still exists; resume runs the agent's command there. */
    object InPlace : ResumePlan()

    /**
     * The session's worktree was removed and can be recreated at its old path.
     *
     * @property repoRoot the main checkout the worktree belongs to.
     * @property path where the worktree is recreated: the old path.
     * @property branch the branch checked out in it.
     * @property from where the branch comes from: `local` (it exists), `remote`
     *   (`origin/<branch>` exists) or `default` (the branch is gone and starts again
     *   from [startPoint]).
     * @property startPoint the commit-ish a new branch starts from; null when [from] is `local`.
     * @property startLabel [startPoint] in words for the note, such as `main`.
     */
    data class Recreate(
        val repoRoot: String,
        val path: String,
        val branch: String,
        val from: String,
        val startPoint: String?,
        val startLabel: String?,
    ) : ResumePlan()

    /**
     * The session cannot be resumed in a folder.
     *
     * @property reason text for the user.
     */
    data class Missing(val reason: String) : ResumePlan()
}

/**
 * Plans and performs the recreation of a removed worktree.
 *
 * Called from `WindowRoutes` for `ResumeAgentSession` (plan, then recreate) and for
 * `InspectAgentSession` and search results (the note only). Runs git, so call it off
 * the socket's coroutine.
 */
object AgentResumePlanner {
    /**
     * Work out how [record] resumes.
     *
     * | Case | Result |
     * |---|---|
     * | the folder exists | [ResumePlan.InPlace] |
     * | the folder is gone and it was a worktree of a repository still on disk | [ResumePlan.Recreate] |
     * | anything else | [ResumePlan.Missing] |
     *
     * The repository is `repoRoot` from the record; older and imported records have none, so
     * the worktree path is walked up to the nearest folder that exists and is inside a repository.
     *
     * @param record the closed session.
     * @return the plan.
     */
    fun plan(record: AgentSessionRecord): ResumePlan {
        val cwd = record.cwd
        if (cwd.isNullOrBlank()) return ResumePlan.Missing("No folder was recorded for this session.")
        if (File(cwd).isDirectory) return ResumePlan.InPlace
        val worktree = record.worktreePath
            ?: return ResumePlan.Missing("Folder no longer exists.")
        val root = repoRootFor(record, worktree)
            ?: return ResumePlan.Missing("Worktree removed and its repository can no longer be found.")
        val branch = record.branch?.takeIf { GitCatalog.isValidBranchName(it) }
            ?: return ResumePlan.Missing("Worktree removed and no branch was recorded for it.")
        val rootPath = Path.of(root)
        return when {
            GitCatalog.refExists(rootPath, "refs/heads/$branch") ->
                ResumePlan.Recreate(root, worktree, branch, "local", null, null)
            GitCatalog.refExists(rootPath, "refs/remotes/origin/$branch") ->
                ResumePlan.Recreate(root, worktree, branch, "remote", "origin/$branch", "origin/$branch")
            else -> {
                val start = GitCatalog.defaultStartPoint(rootPath)
                val label = if (start == "HEAD") {
                    GitCatalog.runGit(rootPath, "branch", "--show-current")?.trim()?.takeIf { it.isNotEmpty() } ?: "HEAD"
                } else start.removePrefix("origin/")
                ResumePlan.Recreate(root, worktree, branch, "default", start, label)
            }
        }
    }

    /**
     * The one-line explanation of [plan] shown under a row, or null when nothing needs saying.
     *
     * @param plan the result of [plan].
     * @return for example "Worktree removed. Resume recreates it from branch aov-12-x.".
     */
    fun note(plan: ResumePlan): String? = when (plan) {
        ResumePlan.InPlace -> null
        is ResumePlan.Missing -> plan.reason
        is ResumePlan.Recreate ->
            if (plan.from == "default") {
                "Worktree removed and branch ${plan.branch} is gone. Resume starts it again from ${plan.startLabel}."
            } else "Worktree removed. Resume recreates it from branch ${plan.branch}."
    }

    /**
     * Recreate the worktree of [plan] at its old path.
     *
     * Forgets stale registrations first (a folder deleted by hand stays registered and would
     * block the add), then checks out the branch, creating it from the start point when it is gone.
     *
     * @param plan what [plan] decided.
     * @return null on success, or git's message (for example when the branch is checked out in
     *   another worktree), in which case nothing was created.
     */
    fun recreate(plan: ResumePlan.Recreate): String? {
        val root = Path.of(plan.repoRoot)
        GitCatalog.pruneWorktrees(root)
        val path = Path.of(plan.path)
        return when (plan.from) {
            "local" -> GitCatalog.addWorktree(root, plan.branch, path)
            else -> GitCatalog.addWorktree(root, plan.branch, path, plan.startPoint)
        }
    }

    /**
     * The folder to open the resumed session in after [recreate] succeeded.
     *
     * Claude finds a conversation by the folder it ran in, so a session that ran in a
     * subfolder of the worktree must resume there, not at the worktree root.
     *
     * Called by `resumeAgentSession` once the worktree exists again.
     *
     * @param record the session being resumed.
     * @param plan the recreate plan that was carried out.
     * @return the record's own folder when it exists again, otherwise the worktree root.
     */
    fun resumeFolder(record: AgentSessionRecord, plan: ResumePlan.Recreate): String =
        record.cwd?.takeIf { File(it).isDirectory } ?: plan.path

    /** The recorded main checkout if it still exists, else the repository found above the old worktree path. */
    private fun repoRootFor(record: AgentSessionRecord, worktree: String): String? {
        record.repoRoot?.takeIf { File(it).isDirectory && isRepo(it) }?.let { return it }
        var dir: File? = File(worktree).parentFile
        while (dir != null) {
            if (dir.isDirectory) {
                val common = GitCatalog.runGit(dir.toPath(), "rev-parse", "--path-format=absolute", "--git-common-dir")?.trim()
                return common?.takeIf { it.isNotEmpty() }?.let { File(it).parentFile?.path }
            }
            dir = dir.parentFile
        }
        return null
    }

    private fun isRepo(path: String): Boolean = GitCatalog.runGit(Path.of(path), "rev-parse", "--git-dir") != null
}
