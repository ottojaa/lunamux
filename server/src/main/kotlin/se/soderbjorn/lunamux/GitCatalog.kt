/**
 * Server-side git status and diff operations for the git pane.
 *
 * This file contains [GitCatalog], which shells out to the local `git` CLI
 * to list uncommitted working-tree changes and produce structured diffs.
 * It deliberately avoids a JGit dependency so it uses the exact same git
 * binary the user has installed.
 *
 * Called by:
 *  - [handleWindowCommand] in Application.kt for `GitList`, `GitDiff`,
 *    `GetWorktreeDefaults`, and `CreateWorktree` commands received over
 *    the `/window` WebSocket.
 *  - [buildGitListEnvelope] to construct the file-change list envelope.
 *  - [GitWatcher] callbacks to refresh the git pane on filesystem changes.
 *
 * @see DiffParser
 * @see GitWatcher
 * @see SyntaxHighlighter
 */
package se.soderbjorn.lunamux

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Server-side git operations: list uncommitted changes and produce diffs.
 * All work is done via the `git` CLI through [ProcessBuilder] — no JGit
 * dependency, same git the user has installed.
 *
 * Every method takes an explicit [cwd] so multiple panes can point at
 * different repositories.
 */
object GitCatalog {
    private val log = LoggerFactory.getLogger(GitCatalog::class.java)
    private const val TIMEOUT_SECONDS = 10L
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_FILES = 1000

    /**
     * List uncommitted changes in the working tree (staged, unstaged, and
     * untracked files). Returns `null` if [cwd] is not inside a git repo.
     */
    fun listChanges(cwd: Path): List<GitFileEntry>? {
        val output = runGit(cwd, "status", "--porcelain=v1", "-uall") ?: return null
        val entries = mutableListOf<GitFileEntry>()
        for (line in output.lines()) {
            if (line.length < 4) continue
            val xy = line.substring(0, 2)
            val filePath = line.substring(3).trim()
            if (filePath.isEmpty()) continue

            val status = parseStatus(xy) ?: continue
            val directory = filePath.substringBeforeLast('/', missingDelimiterValue = "")
            entries.add(GitFileEntry(filePath = filePath, status = status, directory = directory))
            if (entries.size >= MAX_FILES) break
        }
        return entries
    }

    /**
     * Produce a parsed diff for [filePath] in the working tree at [cwd].
     * Returns hunks from `git diff` for modified tracked files, or a
     * synthetic all-additions/all-deletions diff for new/deleted files.
     */
    fun readDiff(cwd: Path, filePath: String): List<DiffHunk>? {
        // Path traversal check.
        val base = runCatching { cwd.toRealPath() }.getOrNull() ?: return null
        val resolved = runCatching { base.resolve(filePath).normalize().toRealPath() }.getOrNull()
        if (resolved != null && !resolved.startsWith(base)) return null

        // Determine the file's status to pick the right diff strategy.
        val statusLine = runGit(cwd, "status", "--porcelain=v1", "--", filePath)
            ?.lines()?.firstOrNull { it.length >= 4 }
        val status = if (statusLine != null) parseStatus(statusLine.substring(0, 2)) else null

        return when (status) {
            GitFileStatus.Untracked, GitFileStatus.Added -> {
                val content = readWorkingFile(cwd, filePath) ?: return null
                DiffParser.syntheticAdd(content)
            }
            GitFileStatus.Deleted -> {
                val content = readGitFile(cwd, filePath, "HEAD") ?: return null
                DiffParser.syntheticDelete(content)
            }
            else -> {
                // Modified, Renamed, or unknown — use git diff.
                val diffOutput = runGit(
                    cwd,
                    "diff", "--ignore-space-change", "--ignore-blank-lines",
                    "--no-color", "--", filePath,
                ) ?: return null
                DiffParser.parse(diffOutput)
            }
        }
    }

    /**
     * Read a file's content from the HEAD revision. Returns null on failure.
     */
    fun readGitFile(cwd: Path, filePath: String, revision: String = "HEAD"): String? {
        return runGit(cwd, "show", "$revision:$filePath")
    }

    /**
     * Read a file from the working tree with path traversal protection.
     */
    fun readWorkingFile(cwd: Path, filePath: String): String? {
        val base = runCatching { cwd.toRealPath() }.getOrNull() ?: return null
        val resolved = runCatching { base.resolve(filePath).normalize() }.getOrNull() ?: return null
        val real = runCatching { resolved.toRealPath() }.getOrNull() ?: return null
        if (!real.startsWith(base)) return null
        if (!Files.isRegularFile(real)) return null
        val size = runCatching { Files.size(real) }.getOrNull() ?: return null
        if (size > MAX_FILE_BYTES) return null
        return runCatching { Files.readString(real) }.getOrNull()
    }

    /**
     * Detect language from file extension for syntax highlighting.
     * Delegates to [LanguageDetector] so the file-browser pane sees the same
     * mapping.
     */
    fun detectLanguage(filePath: String): String? = LanguageDetector.detect(filePath)

    /**
     * Map a two-character git porcelain v1 status code to a [GitFileStatus].
     *
     * @param xy the two-character XY status code from `git status --porcelain=v1`
     * @return the parsed status, or null if both characters indicate a clean file
     */
    private fun parseStatus(xy: String): GitFileStatus? {
        val x = xy[0]
        val y = xy[1]
        return when {
            x == '?' && y == '?' -> GitFileStatus.Untracked
            x == 'A' || y == 'A' -> GitFileStatus.Added
            x == 'D' || y == 'D' -> GitFileStatus.Deleted
            x == 'R' || y == 'R' -> GitFileStatus.Renamed
            x == 'M' || y == 'M' || x == 'U' || y == 'U' -> GitFileStatus.Modified
            x == ' ' && y == ' ' -> null // clean
            else -> GitFileStatus.Modified
        }
    }

    /**
     * Execute a `git` command in [cwd] with the given [args] and return
     * its stdout as a string. Returns null on non-zero exit, timeout, or
     * any exception.
     *
     * @param cwd the working directory to run `git` in
     * @param args the git subcommand and arguments (e.g. `"status"`, `"--porcelain=v1"`)
     * @return the command's stdout, or null on failure
     */
    internal fun runGit(cwd: Path, vararg args: String): String? {
        return try {
            val cmd = listOf("git") + args.toList()
            val proc = ProcessBuilder(cmd)
                .directory(cwd.toFile())
                .redirectErrorStream(false)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            val finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                log.warn("Git command timed out: {}", cmd)
                return null
            }
            if (proc.exitValue() != 0) return null
            output
        } catch (t: Throwable) {
            log.debug("Git command failed in {}: {}", cwd, t.message)
            null
        }
    }

    // ---- Worktree operations ------------------------------------------------

    /**
     * Return the absolute path to the repository root, or null if [cwd] is
     * not inside a git repository.
     *
     * @param cwd any directory inside the repository
     * @return the repo root as a [Path], or null
     * @see handleWindowCommand
     */
    fun getRepoRoot(cwd: Path): Path? {
        val output = runGit(cwd, "rev-parse", "--show-toplevel") ?: return null
        val trimmed = output.trim()
        return if (trimmed.isNotEmpty()) Path.of(trimmed) else null
    }

    /**
     * Return the repository name derived from the `origin` remote URL — the
     * last path segment with any `.git` suffix stripped. Returns null if
     * there is no `origin` remote or the URL can't be parsed.
     *
     * Handles the common URL forms:
     *  - `git@host:owner/repo.git`
     *  - `https://host/owner/repo.git`
     *  - `/local/path/repo.git`
     *
     * Used by the worktree dialog to derive a suggested folder-name prefix
     * that reflects the project ("Lunamux") rather than the active
     * checkout directory ("main"), which would otherwise produce misleading
     * sibling paths when the repo is laid out as `<parent>/<repo>/<branch>`.
     *
     * @param cwd any directory inside the repository
     * @return the repo slug from the remote, or null
     * @see handleWindowCommand
     */
    fun getRepoNameFromRemote(cwd: Path): String? {
        val url = runGit(cwd, "remote", "get-url", "origin")?.trim().orEmpty()
        if (url.isEmpty()) return null
        val afterColonOrSlash = url.substringAfterLast(':').substringAfterLast('/')
        val stripped = afterColonOrSlash.removeSuffix(".git").trim()
        return stripped.ifEmpty { null }
    }

    /**
     * Check whether the working tree at [cwd] has any uncommitted changes
     * (staged, unstaged, or untracked files).
     *
     * @param cwd the working directory to inspect
     * @return `true` if there are uncommitted changes
     * @see handleWindowCommand
     */
    fun hasUncommittedChanges(cwd: Path): Boolean {
        val output = runGit(cwd, "status", "--porcelain=v1", "-uall") ?: return false
        return output.isNotBlank()
    }

    /**
     * Validate a git branch name using `git check-ref-format`.
     *
     * @param name the proposed branch name
     * @return `true` if the name is a valid git branch name
     * @see handleWindowCommand
     */
    fun isValidBranchName(name: String): Boolean {
        if (name.isBlank()) return false
        // Use a temporary directory-independent check; cwd doesn't matter here
        // but we need one for ProcessBuilder. Use the system temp dir.
        val tmpDir = Path.of(System.getProperty("java.io.tmpdir"))
        return runGit(tmpDir, "check-ref-format", "--branch", name) != null
    }

    /**
     * Create a new git worktree with a new branch.
     *
     * @param cwd the repository working directory
     * @param branchName name for the new branch
     * @param worktreePath absolute path where the worktree directory will be created
     * @return null on success, or an error message string on failure
     * @see handleWindowCommand
     */
    fun createWorktree(cwd: Path, branchName: String, worktreePath: Path): String? {
        val result = runGitWithStderr(cwd, "worktree", "add", "-b", branchName, worktreePath.toString())
        return if (result.success) null else (result.stderr.ifBlank { "Failed to create worktree" })
    }

    /**
     * What git says about a folder: where it is and what is left undone in it. One shape for every
     * reader (the tracker when a record opens, the overview's peek, a conversation's story, a task's
     * work).
     *
     * @property repo the repository's name (the main checkout's folder, also for a linked worktree).
     * @property root the top of the working tree the folder is in.
     * @property branch the branch checked out; null when detached.
     * @property changed tracked files changed, staged or not.
     * @property added lines added against the last commit; null when git cannot say (no commit yet).
     * @property removed lines removed against the last commit; null likewise.
     * @property untracked new paths git does not track; a new folder counts once.
     * @property unpushed commits on no remote; null when the repository has no remote.
     */
    data class FolderWork(
        val repo: String,
        val root: String,
        val branch: String? = null,
        val changed: Int = 0,
        val added: Int? = 0,
        val removed: Int? = 0,
        val untracked: Int = 0,
        val unpushed: Int? = null,
    )

    /** How long a [folderWork] answer is reused when the caller does not say. */
    const val FOLDER_WORK_TTL_MS = 5_000L

    private val folderWorkCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, FolderWork?>>()

    /**
     * The repository facts and uncommitted work of the folder [dir], read with `git` and kept for
     * [maxAgeMs] so several readers asking within a moment cost one read.
     *
     * Called when a tracker record opens (with a long [maxAgeMs]: only the repository and branch are
     * wanted), by the overview's peek and by a conversation's or task's work block.
     *
     * @param dir a folder, or null.
     * @param maxAgeMs the oldest an answer may be and still be reused.
     * @return the facts, or null when [dir] is not a folder inside a git working tree.
     */
    fun folderWork(dir: String?, maxAgeMs: Long = FOLDER_WORK_TTL_MS): FolderWork? {
        if (dir.isNullOrBlank() || !java.io.File(dir).isDirectory) return null
        val now = System.currentTimeMillis()
        folderWorkCache[dir]?.takeIf { now - it.first < maxAgeMs }?.let { return it.second }
        val read = readFolderWork(Path.of(dir))
        if (folderWorkCache.size > 256) folderWorkCache.clear()
        folderWorkCache[dir] = now to read
        return read
    }

    private fun readFolderWork(cwd: Path): FolderWork? {
        val status = runGit(cwd, "status", "--porcelain=v1")?.lines()?.filter { it.isNotBlank() } ?: return null
        val top = runGit(cwd, "rev-parse", "--show-toplevel")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val common = runGit(cwd, "rev-parse", "--path-format=absolute", "--git-common-dir")?.trim()
        val repoDir = common?.let { java.io.File(it).parentFile } ?: java.io.File(top)
        val untracked = status.count { it.startsWith("??") }
        val changed = status.size - untracked
        var added: Int? = 0
        var removed: Int? = 0
        if (changed > 0) {
            val numstat = runGit(cwd, "diff", "HEAD", "--numstat")
            if (numstat == null) { added = null; removed = null }
            // A binary file says "-" for both; it has no lines to count.
            else numstat.lines().map { it.split('\t') }.forEach { cols ->
                cols.getOrNull(0)?.toIntOrNull()?.let { added = added!! + it }
                cols.getOrNull(1)?.toIntOrNull()?.let { removed = removed!! + it }
            }
        }
        val unpushed = if (runGit(cwd, "remote").isNullOrBlank()) null
        else runGit(cwd, "rev-list", "--count", "HEAD", "--not", "--remotes")?.trim()?.toIntOrNull()
        return FolderWork(
            repo = repoDir.name,
            root = top,
            branch = runGit(cwd, "branch", "--show-current")?.trim()?.takeIf { it.isNotEmpty() },
            changed = changed, added = added, removed = removed, untracked = untracked, unpushed = unpushed,
        )
    }

    /**
     * Stash all uncommitted changes (including untracked files) with a message.
     *
     * @param cwd the working directory to stash changes from
     * @param message the stash message
     * @return `true` if the stash was created successfully
     * @see handleWindowCommand
     */
    fun stashPush(cwd: Path, message: String): Boolean {
        return runGit(cwd, "stash", "push", "-u", "-m", message) != null
    }

    /**
     * Pop the most recent stash entry. Intended to be called in a worktree
     * directory after [stashPush] was called in the original repo.
     *
     * @param cwd the working directory to apply the stash in (typically the new worktree)
     * @return null on success, or an error message on failure
     * @see handleWindowCommand
     */
    fun stashPop(cwd: Path): String? {
        val result = runGitWithStderr(cwd, "stash", "pop")
        return if (result.success) null else (result.stderr.ifBlank { "Failed to pop stash" })
    }

    /**
     * Result of a git command that captures both stdout and stderr separately,
     * along with the exit status.
     *
     * @param success `true` if the process exited with code 0
     * @param stdout the command's standard output
     * @param stderr the command's standard error output
     */
    data class GitResult(val success: Boolean, val stdout: String, val stderr: String)

    /**
     * Execute a git command capturing both stdout and stderr. Unlike [runGit],
     * this never returns null — it always returns a [GitResult] with the exit
     * status and both output streams. Used by worktree operations that need
     * error messages from stderr.
     *
     * @param cwd the working directory to run `git` in
     * @param args the git subcommand and arguments
     * @return a [GitResult] with stdout, stderr, and success flag
     */
    private fun runGitWithStderr(cwd: Path, vararg args: String): GitResult {
        return try {
            val cmd = listOf("git") + args.toList()
            val proc = ProcessBuilder(cmd)
                .directory(cwd.toFile())
                .redirectErrorStream(false)
                .start()
            val stdout = proc.inputStream.bufferedReader().readText()
            val stderr = proc.errorStream.bufferedReader().readText()
            val finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return GitResult(false, "", "Git command timed out")
            }
            GitResult(proc.exitValue() == 0, stdout, stderr)
        } catch (t: Throwable) {
            GitResult(false, "", t.message ?: "Unknown error")
        }
    }
}
