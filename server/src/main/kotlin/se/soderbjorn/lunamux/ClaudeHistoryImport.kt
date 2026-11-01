/**
 * Brings Claude Code sessions that ran before Lunamux recorded them into the
 * `agent_sessions` table, so search can find them.
 *
 * Claude Code keeps every conversation as a transcript file under
 * `~/.claude/projects/`. This file contains [ClaudeHistoryImport], which turns
 * the interactive ones into ended records with `source = "import"`. Those
 * records are kept out of the "Earlier" list (it shows only sessions run in
 * Lunamux) and are found by search alone.
 *
 * @see AgentTranscriptText
 * @see AgentSearchIndexer
 * @see se.soderbjorn.lunamux.persistence.AgentSessionRecord
 */
package se.soderbjorn.lunamux

import org.slf4j.LoggerFactory
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import java.util.UUID

/**
 * Imports Claude Code transcripts as ended session records.
 *
 * Called by [AgentSearchIndexer] on its background thread, once at start-up and again
 * whenever Claude Code becomes connected, and only then (reading the transcripts of an
 * agent the user has not connected would surprise them).
 */
object ClaudeHistoryImport {
    private val log = LoggerFactory.getLogger(ClaudeHistoryImport::class.java)

    /** Folder inside a repository that Claude Code puts its linked worktrees in. */
    private const val WORKTREES = "/.claude/worktrees/"

    /**
     * The folder holding Claude Code's per-project transcript folders.
     *
     * The `lunamux.claudeProjects` system property overrides it, so tests and dev servers can
     * point at a copy.
     *
     * @return `~/.claude/projects` unless overridden.
     */
    fun projectsDir(): File =
        System.getProperty("lunamux.claudeProjects")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".claude/projects")

    /**
     * Import every interactive transcript that has no record yet.
     *
     * A transcript counts when its first `entrypoint` is `cli` (the kind Lunamux panes host;
     * `claude -p` and background sessions are skipped). A session that already has a record
     * (whether Lunamux ran it or an earlier import made it) is left alone. Each import writes
     * the record and its search text; one unreadable file does not stop the rest.
     *
     * Files not modified since the previous complete run are skipped without being opened: that
     * run already imported them or decided to leave them out. The time the previous run began is
     * kept in the settings store under [WATERMARK_KEY], and moves forward only when no file
     * failed or was held back, so an unreadable one is tried again.
     *
     * A transcript whose folder has a Claude session open in a pane that Lunamux has seen only
     * on screen (no session id yet) is held back: that session may be this very transcript, and
     * importing it would leave two records for one conversation once its first hook arrives.
     *
     * @param repo the store to write records to.
     * @param projects the folder of per-project transcript folders.
     * @param runningIn tells whether an unidentified Claude session is open in a folder; held-back
     *   transcripts are those for which it returns true.
     * @param newId mints record ids; replaced in tests.
     * @return how many records were created.
     */
    fun run(
        repo: SettingsRepository,
        projects: File = projectsDir(),
        runningIn: (String) -> Boolean = { false },
        newId: () -> String = { UUID.randomUUID().toString() },
    ): Int {
        val startedAt = System.currentTimeMillis()
        val lastRun = repo.getString(WATERMARK_KEY)?.toLongOrNull() ?: 0L
        var held = false
        var failed = false
        val files = projects.listFiles { f -> f.isDirectory }.orEmpty()
            .flatMap { dir -> dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }.orEmpty().toList() }
        var imported = 0
        for (file in files) {
            if (file.lastModified() < lastRun) continue
            runCatching {
                val sessionId = file.name.removeSuffix(".jsonl")
                if (repo.findAgentSession(CLAUDE, sessionId) != null) return@runCatching
                val info = AgentTranscriptText.read(file, onlyEntrypoint = "cli") ?: return@runCatching
                if (info.entrypoint != "cli") return@runCatching
                if (info.cwd?.let(runningIn) == true) {
                    held = true
                    return@runCatching
                }
                val record = recordFor(file, sessionId, info, newId())
                repo.saveAgentSession(record)
                repo.setAgentSessionSearchText(record.id, info.text)
                imported++
            }.onFailure {
                failed = true
                log.warn("Could not import transcript {}", file, it)
            }
        }
        if (!failed && !held) repo.putString(WATERMARK_KEY, startedAt.toString())
        if (imported > 0) log.info("Imported {} Claude Code sessions from {}", imported, projects)
        return imported
    }

    /** The ended record describing [info]; the worktree is recognised by its folder convention alone. */
    private fun recordFor(file: File, sessionId: String, info: TranscriptInfo, id: String): AgentSessionRecord {
        val cwd = info.cwd
        val at = cwd?.indexOf(WORKTREES) ?: -1
        val repoRoot = if (cwd != null && at >= 0) cwd.substring(0, at) else null
        val worktreePath = if (cwd != null && at >= 0) {
            cwd.substring(0, at + WORKTREES.length) + cwd.substring(at + WORKTREES.length).substringBefore('/')
        } else null
        val ended = info.endedAt ?: file.lastModified()
        return AgentSessionRecord(
            id = id,
            agent = CLAUDE,
            agentSessionId = sessionId,
            source = "import",
            cwd = cwd,
            repo = (repoRoot ?: cwd)?.let { File(it).name }?.takeIf { it.isNotEmpty() },
            branch = info.gitBranch,
            worktreePath = worktreePath,
            title = info.title,
            firstPrompt = info.firstPrompt,
            transcriptPath = file.path,
            links = (ticketKeys(info.gitBranch) + ticketKeys(info.title)).distinct(),
            state = "ended",
            stateSince = ended,
            createdAt = info.startedAt ?: ended,
            endedAt = ended,
            repoRoot = repoRoot,
        )
    }

    private const val CLAUDE = "claude"

    /** Settings key holding the epoch millis at which the last complete import began. */
    private const val WATERMARK_KEY = "claudeHistoryImportedAt"
}
