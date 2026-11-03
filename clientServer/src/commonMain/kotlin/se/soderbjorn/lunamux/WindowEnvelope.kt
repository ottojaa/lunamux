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
     * Every agentic-CLI session the server tracks: the open ones plus the
     * most recent ended ones, without their saved screen text. Sent on
     * connect and again whenever any record changes, so the agents overview
     * (toolbar chip and overlay) follows a session within a moment of a hook
     * event or a poll result. Older clients fail to decode this frame and
     * drop it, which is harmless.
     *
     * @param sessions the sessions, in no particular order
     * @param claudeConnected whether Lunamux's hooks are present in the user's
     *   Claude Code settings file (drives the App Settings Connect/Disconnect row)
     * @see AgentSessionSummary
     */
    @Serializable
    @SerialName("agentSessions")
    data class AgentSessions(
        val sessions: List<AgentSessionSummary>,
        val claudeConnected: Boolean = false,
    ) : WindowEnvelope()

    /**
     * The tasks that have something to say beyond their sessions: every task a task file
     * declares and every task agents made that holds at least one session. A session joins a
     * task through [AgentSessionSummary.taskId]; this frame names the tasks, their titles, their
     * file-declared members and whether the task has an `open` action. The `open` command is
     * deliberately not part of it: the server keeps it and [WindowCommand.OpenTask] names the
     * task instead. Sent on connect, right after [AgentSessions], and again whenever a task file,
     * a task or a session record changes. Older clients fail to decode this frame and drop it,
     * which is harmless.
     *
     * @param tasks the tasks, in no particular order
     * @see AgentTask
     */
    @Serializable
    @SerialName("agentTasks")
    data class AgentTasks(val tasks: List<AgentTask>) : WindowEnvelope()

    /**
     * A failed agents-overview request, sent only to the connection that made
     * it: resuming a session whose folder is gone, or connecting Claude Code
     * when its settings file cannot be edited.
     *
     * @param recordId the [AgentSessionSummary.id] the request was about, or
     *   null for the Claude Code connection
     * @param message text for the user, such as "folder no longer exists"
     * @see WindowCommand.ResumeAgentSession
     * @see WindowCommand.SetClaudeHooks
     */
    @Serializable
    @SerialName("agentSessionError")
    data class AgentSessionError(val recordId: String?, val message: String) : WindowEnvelope()

    /**
     * The answer to [WindowCommand.SearchAgentSessions], sent only to the
     * connection that asked.
     *
     * @param query the query this answers, so the client can drop a reply that
     *   arrives after the user typed something else
     * @param matches at most 50, best first: title, first prompt, branch or
     *   links, then transcript or last screen; open sessions before ended ones,
     *   newest first within each. A session whose task's title holds the query
     *   comes after the title matches and before the first-prompt ones.
     * @see AgentSessionMatch
     */
    @Serializable
    @SerialName("agentSessionMatches")
    data class AgentSessionMatches(
        val query: String,
        val matches: List<AgentSessionMatch>,
    ) : WindowEnvelope()

    /**
     * The answer to [WindowCommand.InspectAgentSession], sent only to the
     * connection that asked.
     *
     * @param recordId the session this describes
     * @param live true while the session's pane is open, so [screen] is its
     *   current screen rather than the saved last screen
     * @param screen the last 12 non-blank screen lines; empty when nothing is saved
     * @param uncommitted how many files have uncommitted changes in the session's
     *   folder; null when the folder is gone or not a git folder
     * @param unpushed how many commits are on the branch but on no remote; null when unknown
     * @param branch the branch checked out in the folder now, else the one recorded
     * @param resumeNote what Resume would do when it is not simply "run the agent's resume
     *   command in the folder" (for example "Worktree removed. Resume recreates it from
     *   branch x."); null when nothing needs saying
     */
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
 * One row of the agents overview: a run of Claude Code, Codex or Gemini in a
 * pane, as the server's `agent_sessions` table records it.
 *
 * Built by the server's session tracker, pushed in
 * [WindowEnvelope.AgentSessions] and ordered and titled by the client's
 * agents overview view model.
 *
 * @param id Lunamux's record id; also what [WindowCommand.ResumeAgentSession] names.
 * @param agent `claude`, `codex` or `gemini`.
 * @param state `needs_you`, `working`, `idle` or `ended`.
 * @param stateSince epoch millis when [state] last changed.
 * @param title the agent's own title for the session, frozen once seen; null if none.
 * @param links ticket keys such as `ABC-12` found in the branch or title.
 * @param repo name of the git repository folder, or null outside one.
 * @param branch branch the agent was started on, or null.
 * @param cwd folder the agent ran in.
 * @param cwdHome true when [cwd] is the home folder of the user running the server; the overview
 *   shows such a row's folder as "~" (a client cannot tell, because it does not know the home path).
 * @param nowLine the last tool line ("Bash ./gradlew test"), or null.
 * @param question what the agent is asking while [state] is `needs_you`.
 * @param leafId the pane the agent runs in (meaningful while the record is open).
 * @param endedAt epoch millis when the record ended, or null while open.
 * @param lastSaid first line of the last paragraph of the agent's latest reply, at most 160
 *   characters, or null before it has said anything; a row shows it when the session has no
 *   tool line.
 * @param lastActivityAt epoch millis of the last hook event the server folded into this record
 *   (a prompt, a tool starting or finishing, a stop), or null when none has arrived since the
 *   server started. [stateSince] cannot stand in for it, because it does not move while an
 *   agent keeps working; the overview uses this to tell a busy session from a hung one.
 * @param firstPrompt first line of what the user first asked the session, at most 160 characters,
 *   or null before a prompt was seen; the overview shows it after "❯" when a row has no tool line
 *   or last reply of its own.
 * @param taskId the id of the [AgentTask] this session belongs to, or null when it is a task of
 *   its own. Decided by the server's task index (an agent's explicit choice, then a task file's
 *   match); a client never works it out itself.
 * @param mark the mark of the thread of work this session belongs to, 0 until 35 (shape
 *   `mark / 6`, hue `mark % 6`), or null when the session belongs to no thread (the home folder, a
 *   repository's default branch). For a session in a task it is the task's mark. Decided by the
 *   server and stored there, so it never changes; a client draws it and never works it out.
 * @param threadKey the key of that thread (a worktree or folder path, or a repository and branch),
 *   or null; what the pane and tab marks use to tell whether two sessions are one thread.
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
    val nowLine: String? = null,
    val question: String? = null,
    val leafId: String? = null,
    val endedAt: Long? = null,
    val lastSaid: String? = null,
    val lastActivityAt: Long? = null,
    val firstPrompt: String? = null,
    val taskId: String? = null,
    val mark: Int? = null,
    val threadKey: String? = null,
)

/**
 * One search result in [WindowEnvelope.AgentSessionMatches]: a session and
 * where the query was found in it.
 *
 * @param summary the session, with its live state when it is open
 * @param field where the best match is: `title`, `task`, `prompt`, `branch`,
 *   `links`, `transcript` or `screen`
 * @param snippet about 40 characters either side of the match on one line, or
 *   null for a `title` or `task` match (the title itself is highlighted)
 * @param start where the match begins inside [snippet], or inside the title
 *   for a `title` match, or inside [taskTitle] for a `task` match
 * @param length how many characters the match covers
 * @param resumeNote what Resume would do for an ended session whose folder is gone
 *   (see [WindowEnvelope.AgentSessionInspection.resumeNote]); null otherwise
 * @param taskTitle the title of the task the session belongs to, set on every match
 *   of a session that is in a task, so the result can say "in task X"; null otherwise
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
 * One task as the agents overview draws it: a name, the sessions that belong to it (they carry
 * its id in [AgentSessionSummary.taskId]) and, for a task a tool declares in a task file, the
 * members that file lists.
 *
 * Built by the server's task index from task files, the tasks agents made and the session
 * records, pushed in [WindowEnvelope.AgentTasks] and ordered by the client's agents overview
 * view model. A task with several sessions, or with an `open` action, is drawn as an expansion
 * panel; a task with one session and no `open` is drawn as that session's plain row.
 *
 * @param id the task's id: a file's `id`, or the id the `create_task` tool returned; what
 *   [WindowCommand.OpenTask] names.
 * @param title the task's name. A file's title wins over the title of an agent-made task with the
 *   same id.
 * @param repo repository name shown beside the title, or null.
 * @param source `file` when a task file declares the task (also when agents added sessions to it),
 *   `agents` when only the MCP tools made it.
 * @param hasOpen whether the task file gives an `open` action the header can run.
 * @param members the members the task file lists, in the file's order; empty for an agent-made task.
 * @param refreshedAtMillis epoch millis the task file was last written or touched (its modification
 *   time), or null when no file declares the task. A file nobody has refreshed for a while no longer
 *   says anything about its members; the overview marks such rows stale from this alone.
 * @param mark the task's mark, 0 until 35 (see [AgentSessionSummary.mark]). It copies the mark of
 *   the thread of the task's first member the first time the server sees the task and is stored, so
 *   the header and every member row wear the same mark for good. Null from an older server.
 */
@Serializable
data class AgentTask(
    val id: String,
    val title: String,
    val repo: String? = null,
    val source: String = "agents",
    val hasOpen: Boolean = false,
    val members: List<AgentTaskMember> = emptyList(),
    val refreshedAtMillis: Long? = null,
    val mark: Int? = null,
)

/**
 * One member a task file lists for an [AgentTask]. A member Lunamux hosts takes its state, line
 * and age from its session record; any other shows what the file says.
 *
 * @param key the member's id inside its task, unique within it: the file's `sessionId` when that is
 *   given and not repeated, else a number from the member's place in the file. It names the member in
 *   [WindowCommand.OpenTaskMember] and keeps a row's identity through a redraw.
 * @param title the member's name, always the file's.
 * @param links ticket keys or other short labels shown beside the title.
 * @param state `needs_you`, `working`, `idle` or `ended`.
 * @param label a state word the file gave ("in review"), used in the task's summary line; null for the default.
 * @param line one line about what the member is doing or waiting for, or null.
 * @param sinceMillis epoch millis when the member entered [state].
 * @param recordId the session record the member matched, or null when Lunamux does not host it.
 * @param sessionId the id of the member's background session as the file gave it, or null. The
 *   command that attaches to it is built by the server from this id and never travels.
 */
@Serializable
data class AgentTaskMember(
    val key: String,
    val title: String,
    val links: List<String> = emptyList(),
    val state: String,
    val label: String? = null,
    val line: String? = null,
    val sinceMillis: Long = 0,
    val recordId: String? = null,
    val sessionId: String? = null,
)
