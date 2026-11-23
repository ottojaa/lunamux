/*
 * Resume: how a closed agent conversation is picked up again.
 *
 * This file holds [planResume], one pure function that decides what the Resume button does for a
 * record: which folder to start in, which line to type, whether a pane that already exists can
 * take it, and the one-line note the overview shows when the folder is gone. Nothing here touches
 * window state or terminals; the facts it needs about the disk and the panes come in through
 * [ResumeProbe], so tests can supply their own. The caller (the window command handler) turns a
 * [ResumePlan] into a tab or a typed line, and a [ResumeError] into an `AgentError` answer.
 *
 * The task's declared `open` command is honoured only in the narrow case where it is safe: a plain
 * `claude` line, in the folder the conversation ran in, with a plain session id.
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AgentSessionRecord as Record
import java.io.File
import java.nio.file.Paths

/** What the Resume button answers when the record is no longer known. */
internal const val RESUME_UNKNOWN_RECORD = "session no longer exists"

/** What a row says when its conversation never recorded a folder; also the answer to Resume. */
internal const val RESUME_NO_FOLDER_RECORDED = "No folder was recorded for this session."

/** What a closed row says when its transcript file is gone; also the answer to Resume. */
internal const val RESUME_NO_TRANSCRIPT = "Its transcript is gone, so it can't be resumed."

/** What a row says when its folder is gone and nothing above it exists either. */
internal const val RESUME_NO_FOLDER_ABOVE = "Its folder is gone, and no folder above it exists."

/** What Resume answers for a conversation whose id cannot safely be typed into a terminal. */
internal const val RESUME_NO_SESSION_ID = "This conversation has no session id Lunamux can resume."

/**
 * The facts about the machine a resume plan depends on.
 *
 * @property folderIsDirectory whether a path is a folder that exists.
 * @property isIdleShell whether terminal session id runs a shell with nothing under it.
 * @property sessionCwd the folder a terminal session id is in right now, or null.
 * @see planResume
 */
internal class ResumeProbe(
    val folderIsDirectory: (String) -> Boolean = { File(it).isDirectory },
    val isIdleShell: (String) -> Boolean = { TerminalSessions.get(it)?.isIdleShell() == true },
    val sessionCwd: (String) -> String? = { TerminalSessions.get(it)?.cwd?.value },
)

/** The answer of [planResume]: a [ResumePlan] or a [ResumeError]. */
internal sealed interface ResumeResult

/**
 * What to do to resume a conversation.
 *
 * @property folder the folder to start in: the nearest one that exists.
 * @property command the line that resumes, to type into a fresh shell in [folder]; null for an
 *   agent that cannot be resumed by a command (a plain shell is opened).
 * @property title the name for a new tab.
 * @property note the one line the overview shows when the folder is gone, or null.
 * @property pane the terminal session id of an idle shell the conversation ran in, still in a tab
 *   the person can see; null when there is none and a tab is opened.
 * @property paneLine the line to type into [pane] (with a `cd` first when the shell is elsewhere).
 */
internal data class ResumePlan(
    val folder: String,
    val command: String?,
    val title: String,
    val note: String?,
    val pane: String?,
    val paneLine: String?,
) : ResumeResult

/** Why a conversation cannot be resumed, in words the person reads. */
internal data class ResumeError(val text: String) : ResumeResult

/**
 * Decide how to resume [rec].
 *
 * Called by the Resume command handler and by the open-task action; the overview's note under a
 * row comes from [resumeNoteOf], which shares the same folder rules.
 *
 * @param rec the closed record (or null when the id is unknown).
 * @param taskOpen the `open` action of the task the record belongs to, or null.
 * @param layout the live window layout, used to find the pane the conversation ran in.
 * @param probe the facts about the disk and the terminals.
 * @return a [ResumePlan] or a [ResumeError].
 * @see ResumeProbe
 */
internal fun planResume(
    rec: Record?,
    taskOpen: TaskOpen?,
    layout: WindowConfig,
    probe: ResumeProbe = ResumeProbe(),
): ResumeResult {
    if (rec == null) return ResumeError(RESUME_UNKNOWN_RECORD)
    if (rec.transcriptMissing()) return ResumeError(RESUME_NO_TRANSCRIPT)
    val recorded = startFolderOf(rec, taskOpen) ?: return ResumeError(RESUME_NO_FOLDER_RECORDED)
    val folder = nearestExistingFolder(recorded, probe.folderIsDirectory) ?: return ResumeError(RESUME_NO_FOLDER_ABOVE)
    val command = taskOpen?.let { declaredResumeFor(rec, it) } ?: rec.resumeCommand()
    if (command == null && rec.agent != "gemini") return ResumeError(RESUME_NO_SESSION_ID)
    val note = resumeNoteOf(rec, taskOpen, probe)
    val title = rec.title ?: rec.branch ?: File(recorded).name
    val pane = if (command != null) reusablePane(rec, layout, probe) else null
    val paneLine = pane?.let {
        if (sameFolder(probe.sessionCwd(it), folder)) command else "cd ${shellQuoted(folder)} && $command"
    }
    return ResumePlan(folder, command, title, note, pane, paneLine)
}

/**
 * The terminal session id of the pane [rec] ran in, when resuming there is safe: the pane still
 * exists, its tab is one the person can see, and nothing runs under its shell. Found by the
 * record's layout leaf, which survives a restart; the terminal session id a record stored does
 * not (ids are handed out again at every start), so a record with no leaf has no pane.
 */
internal fun reusablePane(rec: Record, layout: WindowConfig, probe: ResumeProbe): String? {
    val leafId = rec.leafId ?: return null
    val tab = layout.worlds.asSequence().flatMap { it.tabs }.firstOrNull { t -> t.panes.any { it.leaf.id == leafId } }
        ?: return null
    if (!layout.isVisible(tab)) return null
    val session = tab.panes.first { it.leaf.id == leafId }.leaf.sessionId.takeIf { it.isNotEmpty() } ?: return null
    return session.takeIf(probe.isIdleShell)
}

/**
 * The one line shown under a closed row, and in its inspection, when Resume would not simply run
 * in the folder the conversation ran in; null when it would.
 *
 * @param rec the closed record.
 * @param open the task's declared open action, or null.
 * @param probe the facts about the disk.
 * @return the note, or null.
 */
internal fun resumeNoteOf(rec: Record, open: TaskOpen?, probe: ResumeProbe = ResumeProbe()): String? {
    // No note: the overview itself says there is nothing to resume, and a folder note would mislead.
    if (rec.transcriptMissing()) return null
    val recorded = startFolderOf(rec, open) ?: return RESUME_NO_FOLDER_RECORDED
    if (probe.folderIsDirectory(recorded)) return null
    val nearest = nearestExistingFolder(recorded, probe.folderIsDirectory) ?: return RESUME_NO_FOLDER_ABOVE
    return "Its folder is gone. It resumes in $nearest."
}

/**
 * The folder text a closed conversation is picked up from, before any walk up to a folder that
 * exists: the task's declared folder when its command is used, else the folder the record ran in.
 */
internal fun startFolderOf(rec: Record, open: TaskOpen?): String? =
    (if (open != null && declaredResumeFor(rec, open) != null) open.folder else rec.cwd)?.takeIf { it.isNotBlank() }

/** The first folder at or above [path] that [isDirectory] accepts, or null when none is. */
internal fun nearestExistingFolder(path: String, isDirectory: (String) -> Boolean = { File(it).isDirectory }): String? {
    if (isDirectory(path)) return path
    var dir: File? = File(path).parentFile
    while (dir != null && !isDirectory(dir.path)) dir = dir.parentFile
    return dir?.path
}

/** A task's declared `open` command made to resume conversation [id], or null when it cannot be. */
internal fun declaredResumeLine(command: String?, id: String): String? {
    val line = command?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (id in line) return line
    if (!isPlainClaudeLine(line)) return null
    val firstWordEnd = line.indexOfFirst { it.isWhitespace() }.takeIf { it >= 0 } ?: line.length
    return line.substring(0, firstWordEnd) + " --resume " + id + line.substring(firstWordEnd)
}

/** One `claude` command whose words are plain or quoted text, with nothing that chains or expands. */
private val PLAIN_CLAUDE = Regex("""^(\S*/)?claude(\s+([\w./:@=+-]+|'[^']*'|"[^"$`\\]*"))*$""")

/** A flag that already picks a conversation. */
private val PICKS_CONVERSATION = Regex("""\s(-r|-c|--continue|--resume|--session-id)(\s|=|$)""")

/** Whether [line] is a plain `claude` command that does not yet resume or continue a conversation. */
internal fun isPlainClaudeLine(line: String): Boolean =
    PLAIN_CLAUDE.matches(line) && !PICKS_CONVERSATION.containsMatchIn(line)

/** Whether [id] may be typed into a terminal as a conversation id (1-100 of letters, digits, `-`, `_`). */
internal fun isPlainSessionId(id: String): Boolean =
    id.length in 1..100 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }

/**
 * The line to type, in the task's declared folder, to resume [rec] as the task's main
 * conversation, or null when the conversation is to be resumed the plain way. Claude only, with a
 * plain id. A line that already names the id is used as written; otherwise the record must have
 * run in the declared folder, so the declared command does not start somewhere else.
 */
internal fun declaredResumeFor(rec: Record, open: TaskOpen): String? {
    if (rec.agent != "claude") return null
    val id = rec.agentSessionId?.takeIf(::isPlainSessionId) ?: return null
    val line = declaredResumeLine(open.command, id) ?: return null
    if (line != open.command.trim() && !sameFolder(rec.cwd, open.folder)) return null
    return line
}

/**
 * Whether two folder paths name the same folder, compared as written after tidying (`.`, `..`, a
 * trailing slash). No disk access; a null or blank path is never the same as another.
 */
internal fun sameFolder(a: String?, b: String?): Boolean {
    if (a.isNullOrBlank() || b.isNullOrBlank()) return false
    return Paths.get(a).normalize() == Paths.get(b).normalize()
}

/** [text] as one single-quoted shell word. */
internal fun shellQuoted(text: String): String = "'" + text.replace("'", "'\\''") + "'"
