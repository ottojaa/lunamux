/**
 * The window commands of the agents overview: resume a conversation, open a task, read the details
 * of a conversation or task, search, switch Claude's hooks and leave a shown tab.
 *
 * This file contains [AgentOverview] (what the commands need: the tracker, the task index, the
 * hooks and the shared frame), [TabOpener] (how a tab running a command is made, replaceable in
 * tests), and the functions the window routes call: [resumeAgentError], [openTaskError] and
 * [handleAgentCommand]. The commands answer only the connection that asked: a failure is an
 * [WindowEnvelope.AgentError], never silence. The one broadcast, [WindowEnvelope.Agents], is
 * built by [AgentsFeed].
 *
 * @see WindowCommand.ResumeAgent
 * @see planResume
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File

/**
 * Everything the overview's commands read and change, built once in `main`.
 *
 * @property repo the stored conversations, for search.
 * @property tracker the conversation records.
 * @property tasks the task index.
 * @property hooks Claude Code's hook settings.
 * @property feed the shared frame; the window routes push it to every connection.
 * @property inFlight the guard that drops a repeated details request.
 */
class AgentOverview(
    val repo: SettingsRepository,
    val tracker: AgentSessionTracker,
    val tasks: TaskIndex,
    val hooks: ClaudeHooks,
    val feed: AgentsFeed,
    val inFlight: InFlight = InFlight(),
)

/** How a tab that runs a command is made. */
fun interface TabOpener {
    /**
     * Open a tab for the person.
     *
     * @param folder the folder it starts in.
     * @param title the tab's name.
     * @param command the line typed once the shell is up, or null for a plain shell.
     */
    fun open(folder: String, title: String, command: String?)
}

/** Opens the tab in the active world through [WindowState.openTabRunning]. */
internal val windowTabOpener = TabOpener { folder, title, command ->
    WindowState.openTabRunning(folder, title, command, worldId = WindowState.activeWorldId())
}

/**
 * Find the record of a conversation by the id a row carries: the agent's own id, or the record's.
 * Looks in memory and then in the database, so a conversation from long ago can be opened too.
 *
 * @param tracker the records.
 * @param id the id from the client.
 * @return the record, or null.
 */
internal fun recordOf(tracker: AgentSessionTracker, id: String): AgentSessionRecord? = tracker.find(id)

/**
 * Start a task from the `open` action its file declares. The command and folder come from the file
 * on the server, never from the client, and the new tab is not tied to the task.
 *
 * @param tasks the task index.
 * @param taskId the task to open.
 * @param opener makes the tab.
 * @return the answer to show the person when it cannot be opened, else null.
 */
internal fun openTaskError(tasks: TaskIndex, taskId: String, opener: TabOpener = windowTabOpener): String? {
    val title = tasks.titleOf(taskId) ?: return "That task no longer exists."
    val open = tasks.fileTask(taskId)?.open ?: return "$title does not say how to open it."
    if (!File(open.folder).isDirectory) return "The folder of $title no longer exists: ${open.folder}"
    opener.open(open.folder, title, open.command)
    return null
}

/**
 * Pick a conversation up again, or bring it to the person if it is still open.
 *
 * A closed conversation resumes in a pane that already sits at an idle shell when the person asked
 * for that and [planResume] found one, else in a new tab. An open one is shown (a background tab)
 * or focused.
 *
 * @param o the overview's parts.
 * @param cmd the command.
 * @param layout the layout the plan reads.
 * @param opener makes a tab when none is reused.
 * @return the answer for the asker, or null when it worked.
 */
internal fun resumeAgentError(
    o: AgentOverview,
    cmd: WindowCommand.ResumeAgent,
    layout: WindowConfig = WindowState.config.value,
    opener: TabOpener = windowTabOpener,
): String? {
    val rec = when {
        cmd.recordId != null -> recordOf(o.tracker, cmd.recordId!!)
        cmd.taskId != null -> o.tasks.storedMain(cmd.taskId!!)
            ?.let { recordOf(o.tracker, it) }
            ?: return "That task has no conversation to resume."
        else -> null
    }
    if (rec != null && !rec.isEnded) {
        val leaf = rec.leafId ?: return "That conversation is not in a window."
        val shown = if (WindowState.isBackgroundLeaf(leaf)) WindowState.showLeaf(leaf) else {
            if (WindowState.focusPane(leaf)) WindowState.ShowResult.Done
            else WindowState.ShowResult.Refused("That conversation's window no longer exists.")
        }
        return (shown as? WindowState.ShowResult.Refused)?.reason
    }
    return when (val plan = planResume(rec, rec?.let(o.tasks::openOfMain), layout)) {
        is ResumeError -> plan.text
        is ResumePlan -> {
            val pane = plan.pane?.takeIf { cmd.inTab }
            if (pane != null && plan.paneLine != null) {
                TerminalSessions.get(pane)?.write((plan.paneLine + "\r").toByteArray(Charsets.UTF_8))
                rec?.leafId?.let(WindowState::focusPane)
            } else {
                opener.open(plan.folder, plan.title, plan.command)
            }
            null
        }
    }
}

/**
 * Run one overview command for the connection [ctx].
 *
 * Called by [handleWindowCommand] for the commands in its `when` that belong to the overview.
 * Slow ones (details, search) run in the connection's scope so the socket keeps reading.
 *
 * @param cmd the command.
 * @param ctx the connection, whose [WindowConnectionContext.send] reaches the asker only.
 * @param o the overview's parts; without them the command is ignored.
 */
internal suspend fun handleAgentCommand(cmd: WindowCommand, ctx: WindowConnectionContext, o: AgentOverview?) {
    if (o == null) return
    when (cmd) {
        is WindowCommand.ResumeAgent -> resumeAgentError(o, cmd)
            ?.let { ctx.send(WindowEnvelope.AgentError(cmd.recordId ?: cmd.taskId, it)) }
        is WindowCommand.OpenTask -> openTaskError(o.tasks, cmd.taskId)
            ?.let { ctx.send(WindowEnvelope.AgentError(cmd.taskId, it)) }
        is WindowCommand.LeaveShownTab -> WindowState.leaveShownTab()
        is WindowCommand.SetClaudeHooks -> {
            val failure = if (cmd.connected) o.hooks.connect() else o.hooks.disconnect()
            o.hooks.refresh()
            failure?.let { ctx.send(WindowEnvelope.AgentError(null, it)) }
        }
        is WindowCommand.SearchAgents -> ctx.scope.launch {
            val matches = withContext(Dispatchers.IO) {
                AgentSessionSearch.search(
                    o.repo, o.tracker, cmd.query,
                    tabNameOf = { r -> r.leafId?.let(WindowState::tabTitleOfLeaf) },
                    taskTitleOf = { r -> o.tasks.taskIdOf(r)?.let(o.tasks::titleOf) },
                    resumeNoteOf = { r -> resumeNoteOf(r, o.tasks.openOfMain(r)) },
                )
            }
            ctx.send(WindowEnvelope.AgentMatches(cmd.query, matches))
        }
        is WindowCommand.ReadAgentDetails -> {
            val about = cmd.recordId ?: cmd.taskId ?: return
            ctx.scope.launch {
                o.inFlight.once(ctx to about) {
                    val answer = withTimeoutOrNull(INSPECT_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { detailsOf(o, cmd) }
                    }
                    ctx.send(answer ?: WindowEnvelope.AgentError(about, "Reading the details took too long."))
                }
            }
        }
        else -> Unit
    }
}

/** The details answer for [cmd], or an [WindowEnvelope.AgentError] when what it names is gone. */
private fun detailsOf(o: AgentOverview, cmd: WindowCommand.ReadAgentDetails): WindowEnvelope =
    when {
        cmd.recordId != null -> recordOf(o.tracker, cmd.recordId!!)
            ?.let { AgentDetailsReader.ofRecord(it, o.tasks) }
            ?: WindowEnvelope.AgentError(cmd.recordId, "That conversation no longer exists.")
        cmd.taskId != null ->
            if (o.tasks.titleOf(cmd.taskId!!) == null) WindowEnvelope.AgentError(cmd.taskId, "That task no longer exists.")
            else AgentDetailsReader.ofTask(cmd.taskId!!, o.tracker.snapshot())
        else -> WindowEnvelope.AgentError(null, "Nothing to read.")
    }
