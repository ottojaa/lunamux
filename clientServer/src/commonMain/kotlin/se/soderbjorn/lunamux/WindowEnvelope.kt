/**
 * Server-to-client WebSocket message types for the Lunamux terminal emulator.
 * Defines the [WindowEnvelope] sealed hierarchy (pushed over `/window`) and all
 * supporting data types for file browser listings, git diffs, usage data, and
 * UI settings.
 *
 * @see WindowCommand for the client-to-server direction
 * @see windowJson for the shared serialization configuration
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

/**
 * Envelopes pushed over the `/window` websocket from server → client. Each
 * message is a JSON Text frame serialized with [windowJson]. The polymorphic
 * discriminator is `"type"` here — different from the `"kind"` discriminator
 * [windowJson] uses globally for the nested [LeafContent] hierarchy. We
 * override per-class with [JsonClassDiscriminator] so the outer envelope
 * serialises as `{"type":"config", …}` while nested content stays on
 * `{"kind":"terminal" | "fileBrowser" | "git"}`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class WindowEnvelope {
    /**
     * Pushes the full window layout to the client. Sent on initial connection
     * and after every mutation command that changes the layout.
     *
     * @param config the complete window/tab/pane configuration
     * @see WindowConfig
     */
    @Serializable
    @SerialName("config")
    data class Config(val config: WindowConfig) : WindowEnvelope()

    /**
     * Broadcasts terminal session state changes (e.g. shell mode indicators).
     * The map keys are session ids; values are the current state string or `null`.
     *
     * @param states map of session id to its current state (or `null` if cleared)
     */
    @Serializable
    @SerialName("state")
    data class State(val states: Map<String, String?>) : WindowEnvelope()

    /**
     * Response to [WindowCommand.FileBrowserListDir]: a one-level directory listing.
     *
     * @param paneId the file browser pane that requested the listing
     * @param dirRelPath relative path of the listed directory
     * @param entries files and subdirectories found in the directory
     */
    @Serializable
    @SerialName("fileBrowserDir")
    data class FileBrowserDir(
        val paneId: String,
        val dirRelPath: String,
        val entries: List<FileBrowserEntry>,
    ) : WindowEnvelope()

    /**
     * Response to [WindowCommand.FileBrowserOpenFile]: the rendered content of
     * a selected file (markdown, syntax-highlighted text, or a binary placeholder).
     */
    @Serializable
    @SerialName("fileBrowserContent")
    data class FileBrowserContentMsg(
        val paneId: String,
        val relPath: String,
        val kind: FileContentKind,
        /** Rendered or highlighted HTML. Empty when [kind] is [FileContentKind.Binary]. */
        val html: String,
        val language: String? = null,
    ) : WindowEnvelope()

    /**
     * Error response for a file browser operation (e.g. permission denied, path not found).
     *
     * @param paneId the file browser pane that triggered the error
     * @param message human-readable error description
     */
    @Serializable
    @SerialName("fileBrowserError")
    data class FileBrowserError(
        val paneId: String,
        val message: String,
    ) : WindowEnvelope()

    /**
     * Notification that an action requires user approval before proceeding.
     *
     * @param message description of the pending approval request
     */
    @Serializable
    @SerialName("pendingApproval")
    data class PendingApproval(val message: String) : WindowEnvelope()

    /**
     * Pushes the current UI settings to the client as a generic JSON blob.
     * Sent in response to [WindowCommand.OpenSettings] and on initial connection.
     *
     * @param settings the complete UI settings object
     */
    @Serializable
    @SerialName("uiSettings")
    data class UiSettings(val settings: JsonObject) : WindowEnvelope()

    /**
     * Pushes Claude API usage statistics to the client.
     * Sent in response to [WindowCommand.RefreshUsage].
     *
     * @param usage the usage data, or `null` if unavailable
     */
    @Serializable
    @SerialName("claudeUsage")
    data class ClaudeUsage(val usage: ClaudeUsageData?) : WindowEnvelope()

    /**
     * Response to [WindowCommand.GitList]: the list of uncommitted changes in
     * the git working directory.
     *
     * @param paneId the git pane that requested the list
     * @param entries files with uncommitted changes
     */
    @Serializable
    @SerialName("gitList")
    data class GitList(
        val paneId: String,
        val entries: List<GitFileEntry>,
    ) : WindowEnvelope()

    /**
     * Response to [WindowCommand.GitDiff]: parsed diff hunks for a single file,
     * plus optional full file contents for split-mode rendering.
     */
    @Serializable
    @SerialName("gitDiff")
    data class GitDiffResult(
        val paneId: String,
        val filePath: String,
        val hunks: List<DiffHunk>,
        /** Detected language for syntax highlighting, or null. */
        val language: String? = null,
        /** Full old file content (for split mode). */
        val oldContent: String? = null,
        /** Full new file content (for split mode). */
        val newContent: String? = null,
    ) : WindowEnvelope()

    /**
     * Error response for a git operation (e.g. not a git repository, git not installed).
     *
     * @param paneId the git pane that triggered the error
     * @param message human-readable error description
     */
    @Serializable
    @SerialName("gitError")
    data class GitError(
        val paneId: String,
        val message: String,
    ) : WindowEnvelope()

    /**
     * Response to [WindowCommand.GetWorktreeDefaults]: pre-computed default values
     * for the "Create Worktree" dialog so the client can pre-populate path fields
     * and conditionally show the "migrate changes" checkbox.
     *
     * @param paneId the pane that requested the defaults
     * @param repoName the repository name (from the `origin` remote URL, falling back to the repo-root basename)
     * @param siblingPath default worktree path as a sibling of the repo (e.g. `../repo-branch`)
     * @param dotWorktreesPath default worktree path under a `.worktrees/` directory
     * @param hasUncommittedChanges `true` if the working directory has staged, unstaged, or untracked changes
     */
    @Serializable
    @SerialName("worktreeDefaults")
    data class WorktreeDefaults(
        val paneId: String,
        val repoName: String,
        val siblingPath: String,
        val dotWorktreesPath: String,
        val hasUncommittedChanges: Boolean,
    ) : WindowEnvelope()

    /**
     * Success response after a git worktree has been created. The server has
     * already updated the pane's cwd; this envelope is informational.
     *
     * @param paneId the pane whose cwd was changed to the new worktree
     * @param newCwd the absolute path of the newly created worktree
     */
    @Serializable
    @SerialName("worktreeCreated")
    data class WorktreeCreated(
        val paneId: String,
        val newCwd: String,
    ) : WindowEnvelope()

    /**
     * Error response when creating a git worktree fails.
     *
     * @param paneId the pane that initiated the failed worktree creation
     * @param message human-readable error description
     */
    @Serializable
    @SerialName("worktreeError")
    data class WorktreeError(
        val paneId: String,
        val message: String,
    ) : WindowEnvelope()

    /**
     * Transient notification from an MCP agent, broadcast to every
     * connected client. Sent by the MCP `notify` tool so an agent can
     * surface a message ("build done", "need input in window 3") on all
     * devices; clients render it as a toast/banner and do not persist it.
     *
     * @param message the human-readable notification text
     * @param level severity hint: `"info"`, `"warn"`, or `"error"`
     */
    @Serializable
    @SerialName("agentNotify")
    data class AgentNotify(
        val message: String,
        val level: String = "info",
    ) : WindowEnvelope()

    /**
     * Every agentic-CLI session the server tracks: the open ones plus the most recent ended ones,
     * without their saved screen text. Sent on connect and again whenever any record changes, so
     * the agents overview (toolbar chip and overlay) follows a session within a moment of a hook
     * event or a poll result. Older clients fail to decode this frame and drop it, which is
     * harmless.
     */
    @Serializable
    @SerialName("agentSessions")
    data class AgentSessions(
        val sessions: List<AgentSessionSummary>,
        val claudeConnected: Boolean = false,
    ) : WindowEnvelope()

    /**
     * The tasks that have something to say beyond their sessions: every task a task file declares
     * and every task agents made that holds at least one session. A session joins a task through
     * [AgentSessionSummary.taskId]; this frame names the tasks, their titles, their start times and
     * whether the task has an `open` action. The `open` command is deliberately not part of it: the
     * server keeps it and [WindowCommand.OpenTask] names the task instead. Sent on connect, right
     * after [AgentSessions], and again whenever a task file, a task or a session record changes.
     * Older clients fail to decode this frame and drop it, which is harmless.
     */
    @Serializable
    @SerialName("agentTasks")
    data class AgentTasks(val tasks: List<AgentTask>) : WindowEnvelope()

    /**
     * The blocks agents wrote into one task's panel. Sent for every task that has blocks when a
     * window connects, and again for a task whenever its blocks change; after the last block of a
     * task is removed the list is sent empty once so windows drop it. Older clients fail to decode
     * this frame and drop it, which is harmless.
     */
    @Serializable
    @SerialName("taskPanel")
    data class TaskPanel(val taskId: String, val blocks: List<TaskPanelBlock>) : WindowEnvelope()

    /**
     * A failed agents-overview request, sent only to the connection that made it: resuming a
     * session whose folder is gone, or connecting Claude Code when its settings file cannot be
     * edited.
     */
    @Serializable
    @SerialName("agentSessionError")
    data class AgentSessionError(val recordId: String?, val message: String) : WindowEnvelope()

    /** The answer to [WindowCommand.SearchAgentSessions], sent only to the connection that asked. */
    @Serializable
    @SerialName("agentSessionMatches")
    data class AgentSessionMatches(
        val query: String,
        val matches: List<AgentSessionMatch>,
    ) : WindowEnvelope()

    /** The answer to [WindowCommand.InspectAgentSession], sent only to the connection that asked. */
    @Serializable
    @SerialName("agentSessionInspection")
    data class AgentSessionInspection(
        val recordId: String,
        val live: Boolean,
        val screen: List<String> = emptyList(),
        val uncommitted: Int? = null,
        val unpushed: Int? = null,
        val branch: String? = null,
        val resumeNote: String? = null,
    ) : WindowEnvelope()
}

/**
 * One row of the agents overview: a run of Claude Code, Codex or Gemini in a pane, as the server's
 * `agent_sessions` table records it.
 */
@Serializable
data class AgentSessionSummary(
    val id: String,
    val agent: String,
    val state: String,
    val stateSince: Long,
    val title: String? = null,
    val links: List<String> = emptyList(),
    val repo: String? = null,
    val branch: String? = null,
    val cwd: String? = null,
    val cwdHome: Boolean = false,
    val cwdShown: String? = null,
    val nowLine: String? = null,
    val question: String? = null,
    val leafId: String? = null,
    val endedAt: Long? = null,
    val lastSaid: String? = null,
    val lastActivityAt: Long? = null,
    val firstPrompt: String? = null,
    val taskId: String? = null,
    val background: Boolean = false,
    val startedAt: Long? = null,
    val conversationId: String = id,
    /** A closed conversation whose own pane is still at an idle shell in the same folder, so Resume can reuse it. */
    val inTab: Boolean = false,
)

/**
 * One search result in [WindowEnvelope.AgentSessionMatches]: a session and where the query was
 * found in it.
 */
@Serializable
data class AgentSessionMatch(
    val summary: AgentSessionSummary,
    val field: String,
    val snippet: String? = null,
    val start: Int = 0,
    val length: Int = 0,
    val resumeNote: String? = null,
    val taskTitle: String? = null,
)

/**
 * One model-specific weekly usage row from the `/usage` screen, e.g.
 * "Current week (Fable)" or "Current week (Sonnet)". The set of rows shown
 * varies with the user's plan and Anthropic's current model lineup, so the
 * label is carried verbatim rather than hardcoded per model.
 *
 * Produced by [ClaudeUsageMonitor.parseUsageScreen] and rendered by the web
 * usage bar (`ClaudeUsageBar.kt`) as one compact row per entry.
 *
 * @param label the model name inside the section header's parentheses,
 *   e.g. "Fable", "Sonnet", "Opus"
 * @param percent usage percentage (0-100) for this model's weekly window
 * @param resetTime raw "Resets ..." text for this row, or empty when the row
 *   shares the all-models weekly reset and prints none of its own
 * @see ClaudeUsageData.modelUsages
 */
@Serializable
data class ClaudeModelUsage(
    val label: String,
    val percent: Int,
    val resetTime: String = "",
)

/**
 * Snapshot of Claude API usage data retrieved from the Claude CLI.
 * Contains session-level and weekly usage percentages plus reset times.
 */
@Serializable
data class ClaudeUsageData(
    val sessionPercent: Int,
    val sessionResetTime: String,
    val weeklyAllPercent: Int,
    val weeklyAllResetTime: String,
    /**
     * Weekly usage for the specific model tracked separately from the
     * all-models pool. Historically this was always Sonnet; newer CLI
     * versions label the section per model (e.g. "Current week (Fable)").
     * Kept — and still populated when a Sonnet row is present — so clients
     * built before [modelUsages] keep working. Prefer [modelUsages].
     */
    val weeklySonnetPercent: Int = 0,
    val extraUsageEnabled: Boolean,
    val extraUsageInfo: String?,
    /** ISO-8601 instant when the data was fetched from the Claude CLI. */
    val fetchedAt: String = "",
    /**
     * All model-specific weekly rows in screen order, one per
     * "Current week (<model>)" section that is not the all-models pool.
     * Empty when the server predates this field or the screen shows no
     * per-model rows.
     */
    val modelUsages: List<ClaudeModelUsage> = emptyList(),
)

/**
 * A single entry (file or directory) in a file browser directory listing.
 *
 * @see WindowEnvelope.FileBrowserDir
 */
@Serializable
data class FileBrowserEntry(
    val name: String,
    val relPath: String,
    val isDir: Boolean,
    /** File size in bytes. 0 for directories. */
    val sizeBytes: Long,
    val mtimeEpochMs: Long,
)

/**
 * The rendering treatment applied to a file's content in the file browser preview.
 */
@Serializable
enum class FileContentKind {
    /** Rendered as HTML from Markdown source. */
    Markdown,
    /** Syntax-highlighted plain text. */
    Text,
    /** Binary file; no content preview available. */
    Binary,
    /**
     * An HTML document that should be rendered inline in an iframe.
     *
     * The `html` field of the envelope carries the file's raw HTML source.
     * The web client renders it via a sandboxed `srcdoc` iframe; the Electron
     * client loads the file through its `tt-file://` custom protocol so
     * relative assets (images, CSS, JS) resolve against the file's directory.
     */
    Html
}

/**
 * A file with uncommitted changes in the git working directory.
 *
 * @see WindowEnvelope.GitList
 */
@Serializable
data class GitFileEntry(
    val filePath: String,
    val status: GitFileStatus,
    val directory: String,
)

/** The git status of a changed file in the working directory. */
@Serializable
enum class GitFileStatus { Added, Modified, Deleted, Renamed, Untracked }

/**
 * A single hunk from a unified diff, representing a contiguous region of changes.
 *
 * @param oldStart starting line number in the old file
 * @param oldCount number of lines from the old file in this hunk
 * @param newStart starting line number in the new file
 * @param newCount number of lines from the new file in this hunk
 * @param lines the individual diff lines within this hunk
 * @see WindowEnvelope.GitDiffResult
 */
@Serializable
data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>,
)

/**
 * A single line within a [DiffHunk], carrying its type (context, addition, or
 * deletion), optional line numbers in the old and new files, and the text content.
 */
@Serializable
data class DiffLine(
    val type: DiffLineType,
    val oldLineNo: Int? = null,
    val newLineNo: Int? = null,
    val content: String,
)

/** Classification of a [DiffLine] within a hunk. */
@Serializable
enum class DiffLineType {
    /** Unchanged context line present in both old and new files. */
    Context,
    /** Line added in the new file. */
    Addition,
    /** Line removed from the old file. */
    Deletion
}

/**
 * One task as the agents overview draws it: a name and the sessions that belong to it, which are
 * the ones that carry its id in [AgentSessionSummary.taskId]. A task has no member list of its own;
 * the sessions are the only source of who is in it.
 *
 * A task with several sessions, or with an `open` action, is drawn as one task row; a task with one
 * session and no `open` is drawn as that session's plain row.
 */
@Serializable
data class AgentTask(
    val id: String,
    val title: String,
    val repo: String? = null,
    val source: String = "agents",
    val hasOpen: Boolean = false,
    val mainConversation: String? = null,
    val status: String? = null,
    val statusAtMillis: Long? = null,
    val questionWaits: Boolean = false,
    val startedAtMillis: Long? = null,
)
