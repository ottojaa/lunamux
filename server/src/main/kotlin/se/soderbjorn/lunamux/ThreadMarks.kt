/**
 * Which "thread of work" a session belongs to, and the mark that thread wears everywhere.
 *
 * This file contains [ThreadMarks]. A thread of work is what several sessions of one person's
 * attention have in common: a linked worktree, else a branch other than the repository's default,
 * else a plain folder. [ThreadMarks.keyOf] names the thread, and [ThreadMarks.markFor] gives it a
 * mark, one of 36 (six shapes in six hues) that is stored the first time it is seen and then
 * never changes, so a person learns "the half-circle one" once. The mark is a number: the shape
 * is `mark / 6` and the hue `mark % 6`; the clients draw it.
 *
 * Nothing here knows who started a session or with which tool. A folder a programme works in is
 * a folder like any other.
 *
 * @see AgentSessionTracker
 * @see TaskIndex
 */
package se.soderbjorn.lunamux

import org.slf4j.LoggerFactory
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import se.soderbjorn.lunamux.persistence.SettingsRepository.ThreadMarkRow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Names the thread of work a session belongs to and keeps the mark each thread wears.
 *
 * The marks live in the `thread_marks` table of the server's own database, so every window and
 * device agrees on them, and the whole table is held in memory: a lookup never touches the
 * database, and a write happens only when a thread is first seen and then at most once a day to
 * renew its `last_seen_at`. Tasks keep a row of their own under the key `task:<id>`.
 *
 * All state is behind one private lock that never calls out, so it is safe to use while the
 * tracker's or the task index's lock is held. The path rules touch only the file system's
 * `canonicalPath` and one small file per repository, both cached.
 *
 * Created once in `main()` and handed to [AgentSessionTracker] and [TaskIndex]; tests build one
 * over a temporary database.
 *
 * @param repo the store the marks are persisted to.
 * @param gitInfo reads the git facts of a task file's folder; replaced in tests.
 * @param home the home folder of the user running the server; a session started there has no
 *   thread. Replaced in tests.
 * @param defaultBranchOf the name `origin/HEAD` gives for a repository root, or null when it names
 *   none; replaced in tests.
 * @see AgentSessionRecord
 */
class ThreadMarks(
    private val repo: SettingsRepository,
    private val gitInfo: (String?) -> GitInfo = ::readGitInfo,
    home: String = System.getProperty("user.home").orEmpty(),
    private val defaultBranchOf: (String) -> String? = ::originHeadBranch,
) {
    private val log = LoggerFactory.getLogger(ThreadMarks::class.java)
    private val lock = Any()

    /** What is stored for one key, mirrored in memory. */
    private class Entry(val mark: Int, var lastSeenAt: Long)

    private val entries = HashMap<String, Entry>().also { map ->
        runCatching { repo.loadThreadMarks() }
            .onFailure { log.warn("Could not load the thread marks", it) }
            .getOrDefault(emptyList())
            .forEach { map[it.key] = Entry(it.mark, it.lastSeenAt) }
    }

    private val canonicalCache = ConcurrentHashMap<String, String>()
    private val defaultBranchCache = ConcurrentHashMap<String, Set<String>>()
    private val homeCanonical = home.takeIf { it.isNotBlank() }?.let(::canonicalOf)

    /**
     * The key of the thread a session's record belongs to.
     *
     * Called by [AgentSessionTracker] for every summary it publishes and by [TaskIndex] when a
     * task takes its first member's mark. The rules, first match wins:
     *  1. the session ran in a linked worktree: `path:<worktree root>`;
     *  2. it ran in a git checkout (it has a repository root, or only a branch, as a record
     *     imported from Claude's own history does) on a branch that is not the repository's
     *     default (`main`, `master`, or what `origin/HEAD` names):
     *     `branch:<repository root, else the folder>#<branch>`;
     *  3. it ran in no git checkout and its folder is not the home folder: `path:<folder>`;
     *  4. otherwise none. The home folder, a repository's default branch in its main checkout, a
     *     detached HEAD and a session with no folder are not one thread of work; a mark there
     *     would tie unrelated work together.
     *
     * Every path is canonicalised, so a symlinked folder and its target are one thread.
     *
     * @param record the stored record.
     * @return the thread key, or null when the session belongs to no thread.
     * @see folderKey
     */
    fun keyOf(record: AgentSessionRecord): String? =
        keyOf(record.worktreePath, record.repoRoot, record.branch, record.cwd)

    /**
     * The thread key for the facts [keyOf] needs, without a record.
     *
     * @param worktreePath root of the linked worktree the session ran in; null in a main checkout.
     * @param repoRoot the repository's main checkout; null outside git.
     * @param branch the branch, null on a detached HEAD or outside git.
     * @param cwd the folder the session ran in.
     * @return the thread key, or null when the session belongs to no thread.
     */
    fun keyOf(worktreePath: String?, repoRoot: String?, branch: String?, cwd: String?): String? {
        if (!worktreePath.isNullOrBlank()) return "path:${canonicalOf(worktreePath)}"
        if (!repoRoot.isNullOrBlank() || !branch.isNullOrBlank()) {
            // A record can know its branch without knowing its repository root; the folder stands in.
            val root = repoRoot?.takeIf { it.isNotBlank() } ?: cwd?.takeIf { it.isNotBlank() } ?: return null
            val onDefault = branch.isNullOrBlank() || isDefaultBranch(root, branch)
            return if (onDefault) null else "branch:${canonicalOf(root)}#$branch"
        }
        if (cwd.isNullOrBlank()) return null
        val folder = canonicalOf(cwd)
        return if (folder == homeCanonical) null else "path:$folder"
    }

    /**
     * The thread key of a folder a task file names (`members[].match.folder`, `open.folder`).
     *
     * Called by [TaskIndex] the first time it meets a task. It asks git about the folder and
     * applies the same rules as [keyOf]: a folder in a linked worktree keys as the worktree
     * root, one on a non-default branch of a repository keys as that branch, a plain folder keys
     * as itself (so a seeded folder that does not exist on disk still keys as `path:<folder>`),
     * and the home folder or a repository's default branch has no key.
     *
     * @param folder an absolute folder.
     * @return the thread key, or null when the folder belongs to no thread (the task then gets a
     *   mark of its own).
     * @see keyOf
     */
    fun folderKey(folder: String): String? {
        val info = gitInfo(folder)
        return keyOf(info.worktreePath, info.repoRoot, info.branch, folder)
    }

    /**
     * The mark already stored for a thread, without assigning one or renewing the thread.
     *
     * Called by the session search, which must show the mark a row has but must not make a
     * thread that only matched a query count as seen: a broad search would otherwise hold
     * marks for threads nobody is working in.
     *
     * @param key a thread key from [keyOf] or [folderKey].
     * @return the stored mark, or null when the thread has none yet.
     * @see markFor
     */
    fun peekMark(key: String): Int? = synchronized(lock) { entries[key]?.mark }

    /**
     * The mark of a thread, assigned the first time the thread is seen.
     *
     * A stored mark is returned as it is and never reassigned. A new thread starts from the
     * FNV-1a 32-bit hash of its key modulo 36, and steps by 7 (coprime with 36, so shape and hue
     * both change) to the first mark that no thread seen in the last 14 days holds. When all 36
     * are held it keeps the hash mark and shares it. `last_seen_at` is renewed at most once a day.
     *
     * Called by [AgentSessionTracker] for every published summary and by [markForTask].
     *
     * @param key a thread key from [keyOf] or [folderKey].
     * @param now epoch millis.
     * @return the mark, 0 until 35.
     */
    fun markFor(key: String, now: Long): Int = synchronized(lock) {
        entries[key]?.let { renew(key, it, now); return@synchronized it.mark }
        val raw = hashMark(key)
        val held = entries.values.filter { now - it.lastSeenAt < ACTIVE_MS }.mapTo(HashSet()) { it.mark }
        val mark = (0 until MARKS).map { (raw + STEP * it) % MARKS }.firstOrNull { it !in held } ?: raw
        store(key, mark, now)
        mark
    }

    /**
     * The mark of a task, assigned the first time the task is seen.
     *
     * On first sight the task copies the thread mark of [firstKey], so a task and the loose
     * sessions of its thread wear the same mark; with no key it gets a fresh mark of its own.
     * After that its row is read back unchanged, whatever its members do.
     *
     * Called by [TaskIndex] for every task it publishes.
     *
     * @param taskId the task's id.
     * @param firstKey the thread key of the task's first member, or null when it has none.
     * @param now epoch millis.
     * @return the task's mark, 0 until 35.
     * @see markOfTask
     */
    fun markForTask(taskId: String, firstKey: String?, now: Long): Int = synchronized(lock) {
        markOfTask(taskId, now)?.let { return@synchronized it }
        val mark = if (firstKey != null) markFor(firstKey, now) else markFor("$TASK_PREFIX$taskId", now)
        if ("$TASK_PREFIX$taskId" !in entries) store("$TASK_PREFIX$taskId", mark, now)
        mark
    }

    /**
     * The stored mark of a task, without assigning one.
     *
     * Called by [TaskIndex] to skip the work of finding a task's first key once it has a mark,
     * and by the session search to put a task's mark on a result.
     *
     * @param taskId the task's id.
     * @param now epoch millis, to renew the row's `last_seen_at`; null to only read.
     * @return the mark, or null when the task has none yet.
     */
    fun markOfTask(taskId: String, now: Long? = null): Int? = synchronized(lock) {
        val key = "$TASK_PREFIX$taskId"
        entries[key]?.also { if (now != null) renew(key, it, now) }?.mark
    }

    private fun renew(key: String, entry: Entry, now: Long) {
        if (now - entry.lastSeenAt < RENEW_MS) return
        entry.lastSeenAt = now
        persist(key, entry)
    }

    private fun store(key: String, mark: Int, now: Long) {
        val entry = Entry(mark, now)
        entries[key] = entry
        persist(key, entry)
    }

    private fun persist(key: String, entry: Entry) {
        runCatching { repo.saveThreadMark(ThreadMarkRow(key, entry.mark, entry.lastSeenAt)) }
            .onFailure { log.warn("Could not store the mark of {}", key, it) }
    }

    private fun isDefaultBranch(repoRoot: String, branch: String): Boolean =
        branch in defaultBranchCache.getOrPut(repoRoot) {
            setOfNotNull("main", "master", defaultBranchOf(repoRoot))
        }

    private fun canonicalOf(path: String): String =
        canonicalCache.getOrPut(path.trimEnd('/').ifEmpty { "/" }) { canonical(path.trimEnd('/').ifEmpty { "/" }) }

    internal companion object {
        /** How many marks there are: six shapes in six hues. */
        const val MARKS = 36

        /** Coprime with [MARKS], so stepping by it changes both the shape and the hue. */
        private const val STEP = 7
        private const val TASK_PREFIX = "task:"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val RENEW_MS = DAY_MS

        /** A thread seen within this long still holds its mark against newcomers. */
        private const val ACTIVE_MS = 14 * DAY_MS

        /**
         * The first mark a key would get with nothing else held: its FNV-1a 32-bit hash modulo 36.
         *
         * @param key the key to hash, as UTF-8.
         * @return 0 until 35.
         */
        fun hashMark(key: String): Int {
            var h = 0x811c9dc5L
            for (b in key.toByteArray(Charsets.UTF_8)) {
                h = (h xor (b.toLong() and 0xff)) * 0x01000193L and 0xffffffffL
            }
            return (h % MARKS).toInt()
        }
    }
}

/**
 * The branch a repository's `origin/HEAD` names, read from the small ref file git keeps, so no
 * process is started.
 *
 * Used by [ThreadMarks] to tell a repository's default branch from any other. Returns null when
 * the repository has no remote, `origin/HEAD` was never set, or the file is unreadable.
 *
 * @param repoRoot the repository's main checkout.
 * @return the branch name, such as `develop`, or null.
 */
internal fun originHeadBranch(repoRoot: String): String? =
    runCatching {
        File(repoRoot, ".git/refs/remotes/origin/HEAD").takeIf { it.isFile }?.readText()?.trim()
            ?.removePrefix("ref:")?.trim()?.removePrefix("refs/remotes/origin/")?.takeIf { it.isNotEmpty() }
    }.getOrNull()
