/**
 * Brings Claude Code conversations that ran before Lunamux recorded them into the
 * `agent_sessions` table, so search can find them.
 *
 * Claude Code keeps every conversation as a transcript file under `~/.claude/projects/`. This file
 * contains [ClaudeHistoryImport], which turns the interactive ones into ended records with
 * `source = "import"`. The overview does not list those; search finds them. A conversation that
 * already has a record (found by its Claude id) is left alone.
 *
 * @see AgentSearchIndexer.importHistory
 */
package se.soderbjorn.lunamux

import org.slf4j.LoggerFactory
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.AgentSessionState
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import java.util.UUID

/** Imports Claude Code transcripts as ended records. */
object ClaudeHistoryImport {
    private val log = LoggerFactory.getLogger(ClaudeHistoryImport::class.java)

    /** Folder inside a repository that Claude Code puts its linked worktrees in. */
    private const val WORKTREES = "/.claude/worktrees/"

    /** Settings key holding the epoch millis at which the last complete import began. */
    private const val WATERMARK_KEY = "claudeHistoryImportedAt"

    /**
     * The folder holding Claude Code's per-project transcript folders.
     *
     * @return the `lunamux.claudeProjects` property when set, else `~/.claude/projects`.
     */
    fun projectsDir(): File =
        System.getProperty("lunamux.claudeProjects")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".claude/projects")

    /**
     * Import every interactive transcript that has no record yet. Called by [AgentSearchIndexer] on
     * its worker thread when the hooks connect and at start.
     *
     * @param repo where the records and their text go.
     * @param projects Claude Code's projects folder.
     * @param runningIn whether a Claude conversation is open in a folder; its transcript is skipped
     *   (and the pass is not marked complete) so it is not imported as a second record.
     * @param now the time; the watermark and the month for which text is kept.
     * @param newId makes a record id.
     * @return how many records were added.
     */
    fun run(
        repo: SettingsRepository,
        projects: File = projectsDir(),
        runningIn: (String) -> Boolean = { false },
        now: Long = System.currentTimeMillis(),
        newId: () -> String = { UUID.randomUUID().toString() },
    ): Int {
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
                if (repo.findAgentSessionByConversation(AgentWords.CLAUDE, sessionId) != null) return@runCatching
                val info = TranscriptLines.read(file, onlyEntrypoint = "cli") ?: return@runCatching
                if (info.entrypoint != "cli") return@runCatching
                if (info.cwd?.let(runningIn) == true) {
                    held = true
                    return@runCatching
                }
                val record = recordFor(file, sessionId, info, newId())
                repo.saveAgentSession(record)
                if ((record.endedAt ?: 0L) >= now - AgentSessionSearch.SEARCH_WINDOW_MS) repo.setAgentSessionSearchText(record.id, info.text)
                imported++
            }.onFailure {
                failed = true
                log.warn("Could not import transcript {}", file, it)
            }
        }
        if (!failed && !held) repo.putString(WATERMARK_KEY, now.toString())
        if (imported > 0) log.info("Imported {} Claude Code conversations from {}", imported, projects)
        return imported
    }

    /** The ended record describing [info]; the repo is the folder's name, or the repository a Claude worktree belongs to. */
    private fun recordFor(file: File, sessionId: String, info: TranscriptInfo, id: String): AgentSessionRecord {
        val cwd = info.cwd
        val root = cwd?.substringBefore(WORKTREES, missingDelimiterValue = cwd)
        val ended = info.endedAt ?: file.lastModified()
        return AgentSessionRecord(
            id = id,
            agent = AgentWords.CLAUDE,
            agentSessionId = sessionId,
            source = AgentWords.SOURCE_IMPORT,
            cwd = cwd,
            repo = root?.let { File(it).name }?.takeIf { it.isNotEmpty() },
            branch = info.gitBranch,
            title = info.title,
            firstPrompt = info.firstPrompt,
            transcriptPath = file.path,
            state = AgentSessionState.ENDED,
            stateSince = ended,
            createdAt = info.startedAt ?: ended,
            endedAt = ended,
        )
    }
}
