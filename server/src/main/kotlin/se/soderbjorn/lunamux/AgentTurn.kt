/**
 * The rules that move an agent conversation between `working`, `needs_you` and `done`, and the rule
 * that says which record a Claude Code hook belongs to, as pure functions.
 *
 * This file contains [Turn] (everything the tracker remembers about one open record's running turn,
 * in one value), [step] (folds a screen poll or a hook event into a record and its [Turn]) and
 * [route] (picks the record a hook event belongs to). Neither reads a clock, a file or a process:
 * the tracker feeds them facts and applies what they return, so every rule here is tested by
 * calling the function.
 *
 * @see AgentSessionTracker
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.json.JsonObject
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.AgentSessionState.DONE
import se.soderbjorn.lunamux.persistence.AgentSessionState.NEEDS_YOU
import se.soderbjorn.lunamux.persistence.AgentSessionState.WORKING

/** The words the tracker, the readers and the tests share. */
internal object AgentWords {
    /** The one agent whose conversations hooks and transcripts can follow. */
    const val CLAUDE = "claude"

    /** A record whose screen decides its state. */
    const val SOURCE_SCREEN = "screen"

    /** A record whose Claude Code hook events decide its state. */
    const val SOURCE_HOOKS = "hooks"

    /** A record read from Claude's own transcript files; only search finds it. */
    const val SOURCE_IMPORT = "import"

    /** Lunamux stopped under the conversation. */
    const val END_RESTART = "restart"

    /** The agent exited. */
    const val END_EXIT = "exit"

    /** The person ran `/clear`. */
    const val END_CLEARED = "cleared"

    /** The person left at the agent's prompt. */
    const val END_QUIT = "quit"

    /** Its pane went away. */
    const val END_CLOSED = "closed"

    /** Another conversation took its pane. */
    const val END_REPLACED = "replaced"

    /** `SessionStart` sources that replace the pane's conversation rather than start a helper beside it. */
    val REPLACING_STARTS = setOf("clear", "resume")
}

/**
 * What a conversation waits for once its turn has ended (`set_status` with `keep`), and since when.
 *
 * @property text what it waits for.
 * @property needsYou whether what it waits for is the person.
 * @property since when this wording was first kept.
 */
internal data class Kept(val text: String, val needsYou: Boolean, val since: Long)

/**
 * The background shells a conversation left running at its prompt.
 *
 * @property count how many.
 * @property since when the oldest began, or null when not known.
 */
data class BackgroundShells(val count: Int, val since: Long?) {
    companion object {
        /** No shell running. */
        val NONE = BackgroundShells(0, null)
    }
}

/**
 * Everything the tracker remembers about one open record's running turn. Memory only: after a
 * restart every open record is closed, so nothing here outlives the server.
 *
 * @property inFlight the tool call a `PreToolUse` announced and no `PostToolUse` has finished, as
 *   [rawToolLine] words it.
 * @property promptSeen whether a poll has seen the prompt of a `needs_you` record on the screen.
 * @property idlePolls polls in a row, since the last hook, that showed no agent on a working record's pane.
 * @property lastActivity the last sign of life, from a hook or from a working screen.
 * @property lastHookAt when the last hook event arrived.
 * @property pid the process id of the `claude` behind the record, as its hooks report it.
 * @property status what the conversation says it is doing (`set_status`); goes with the next prompt.
 * @property kept what the conversation waits for after its turn (`set_status` with `keep`).
 * @property shells the background shells left running while the conversation sits at its prompt.
 * @property hostId for a helper: the record of the conversation it runs inside.
 * @property follower the incremental reader of the conversation's transcript, for its recap and interrupt.
 */
internal data class Turn(
    val inFlight: String? = null,
    val promptSeen: Boolean = false,
    val idlePolls: Int = 0,
    val lastActivity: Long? = null,
    val lastHookAt: Long? = null,
    val pid: Long? = null,
    val status: String? = null,
    val kept: Kept? = null,
    val shells: BackgroundShells? = null,
    val hostId: String? = null,
    val follower: TranscriptFollower? = null,
)

/**
 * One fact fed to [step].
 *
 * @property now the time it was taken at.
 */
internal sealed interface Signal {
    val now: Long
}

/**
 * One screen poll of the record's pane.
 *
 * @property screen what the pane's detector says: `waiting`, `working`, or null when no agent shows.
 * @property interruptedAt when the person interrupted the turn, if the transcript's last line says so.
 * @property shells what the process tree showed under the conversation's `claude`; null when its
 *   process is not known.
 * @property footerShells reads the background-shell count off the screen's footer; asked only when
 *   [shells] is null and the record sits at its prompt.
 * @property programTitle the pane's program-set title, which names a screen-fed record at its first work.
 */
internal data class PollSignal(
    override val now: Long,
    val screen: String?,
    val interruptedAt: Long? = null,
    val shells: BackgroundShells? = null,
    val footerShells: () -> Int = { 0 },
    val programTitle: String? = null,
) : Signal

/**
 * One Claude Code hook event (`SessionStart` and `SessionEnd` are the tracker's, not [step]'s).
 *
 * @property event the event's JSON as the hook posted it.
 * @property programTitle the pane's program-set title, which names the record at its first prompt.
 */
internal data class HookSignal(
    override val now: Long,
    val event: JsonObject,
    val programTitle: String? = null,
) : Signal

/** What [step] returns: the record and its turn after the signal. */
internal data class Stepped(val rec: AgentSessionRecord, val turn: Turn)

/** How much earlier than a hook's arrival an interrupt line may be stamped and still count as newer. */
internal const val HOOK_LAG_MS = 1_000L

/** Polls in a row that must show no agent on a working record's pane before its turn counts as over. */
internal const val IDLE_POLLS_TO_DONE = 3

/** How old a screen-derived activity stamp must be before a working poll renews it. */
private const val ACTIVITY_REFRESH_MS = 30_000L

private const val PROMPT_MAX = 500
private const val QUESTION_MAX = 400

/**
 * Fold [signal] into the open record [rec] and its [turn].
 *
 * A screen-fed record takes its state from the pane. A hook-fed record takes it from its hooks, and
 * a poll only settles what the hooks cannot report (Claude Code sends no event when a prompt is
 * refused, none when a turn is interrupted):
 *  - the transcript ends in the person's interrupt, stamped no earlier than just before the last
 *    hook: the turn is over, done;
 *  - the screen showed the prompt and now shows work: it was answered, working;
 *  - the screen showed the prompt and now shows neither: it left without a go-ahead, done. A
 *    question put through `AskUserQuestion` is held while the call is in flight, since a dialog
 *    with several questions is redrawn between them;
 *  - a working record whose pane shows no agent for [IDLE_POLLS_TO_DONE] polls: done.
 * A prompt the screen never showed is not settled by a working screen, and no timer ends `working`
 * or `needs_you`.
 *
 * Called by [AgentSessionTracker] for every open record on every poll and for every hook event.
 *
 * @param rec the open record.
 * @param turn what is remembered about its turn.
 * @param signal the poll or hook.
 * @return the record and turn afterwards (the same values when nothing changed).
 * @see route
 */
internal fun step(rec: AgentSessionRecord, turn: Turn, signal: Signal): Stepped = when (signal) {
    is HookSignal -> stepHook(rec, turn, signal)
    is PollSignal -> if (rec.source == AgentWords.SOURCE_SCREEN) stepScreen(rec, turn, signal) else stepHooked(rec, turn, signal)
}

/** A screen-fed record: the pane's state is the record's. */
private fun stepScreen(rec: AgentSessionRecord, turn: Turn, s: PollSignal): Stepped {
    val state = when (s.screen) {
        "waiting" -> NEEDS_YOU
        "working" -> WORKING
        else -> DONE
    }
    var next = rec
    var t = turn
    if (state == WORKING) t = t.noteWork(s.now)
    if (state == WORKING && rec.title == null) next = next.copy(title = cleanTitle(s.programTitle))
    if (state != rec.state) next = next.copy(state = state, stateSince = s.now, nowLine = null, question = null)
    return Stepped(next, t.withShells(next, s, found = null))
}

/** A hook-fed record: settle what the hooks cannot report. */
private fun stepHooked(rec: AgentSessionRecord, turn: Turn, s: PollSignal): Stepped {
    var r = rec
    var t = turn
    val open = r.state == WORKING || r.state == NEEDS_YOU
    if (r.state != WORKING || s.screen != null) t = t.copy(idlePolls = 0)
    fun settle() {
        r = r.copy(state = DONE, stateSince = s.now, nowLine = null, question = null)
        t = t.copy(promptSeen = false, idlePolls = 0, lastActivity = s.now, inFlight = null)
    }
    val interrupted = open && s.interruptedAt != null && s.interruptedAt + HOOK_LAG_MS >= (t.lastHookAt ?: Long.MAX_VALUE)
    when {
        interrupted -> settle()
        r.state == WORKING -> {
            t = t.copy(promptSeen = false)
            if (s.screen == null) {
                val idle = t.idlePolls + 1
                if (idle >= IDLE_POLLS_TO_DONE) settle() else t = t.copy(idlePolls = idle)
            }
        }
        r.state != NEEDS_YOU -> t = t.copy(promptSeen = false)
        s.screen == "waiting" -> t = t.copy(promptSeen = true)
        t.promptSeen && !t.askOnScreen() -> {
            t = t.copy(promptSeen = false)
            if (s.screen == "working") {
                t = t.copy(lastActivity = s.now)
                r = r.copy(state = WORKING, stateSince = s.now, question = null)
            } else settle()
        }
    }
    if (r.state == WORKING && s.screen == "working") t = t.noteWork(s.now)
    return Stepped(r, t.withShells(r, s, found = s.shells))
}

/** A hook event: the exact word on what the conversation is doing. */
private fun stepHook(rec: AgentSessionRecord, turn: Turn, s: HookSignal): Stepped {
    val event = s.event
    val name = event.str("hook_event_name") ?: return Stepped(rec, turn)
    var r = rec
    var t = turn
    fun set(state: String, nowLine: String? = r.nowLine, question: String? = null) {
        r = r.copy(
            state = state,
            stateSince = if (state != r.state) s.now else r.stateSince,
            nowLine = nowLine,
            question = question,
        )
    }
    when (name) {
        "UserPromptSubmit" -> {
            t = t.copy(inFlight = null, status = null)
            set(WORKING, nowLine = null)
            if (r.firstPrompt == null) r = r.copy(firstPrompt = event.str("prompt")?.take(PROMPT_MAX))
            if (r.title == null && t.hostId == null) r = r.copy(title = cleanTitle(s.programTitle))
        }
        "PreToolUse" -> {
            t = t.copy(inFlight = rawToolLine(event))
            // A question put through AskUserQuestion is a prompt on screen the moment it starts.
            val asked = askedQuestionOf(event)
            if (asked != null) set(NEEDS_YOU, nowLine = null, question = asked.take(QUESTION_MAX))
            else set(WORKING, nowLine = toolLine(event) ?: r.nowLine)
        }
        "PostToolUse" -> {
            t = t.copy(inFlight = null)
            set(WORKING)
        }
        // The dialog asking the person to allow a tool call is on screen now: Claude Code sends this
        // when it opens, so the row needs the person at once and no late notice can reopen it.
        "PermissionRequest" -> set(
            NEEDS_YOU, nowLine = null,
            question = (askedQuestionOf(event) ?: rawToolLine(event)?.let { "Allow $it?" } ?: "Permission needed").take(QUESTION_MAX),
        )
        // Only an elicitation dialog needs the person here. A permission prompt's notice arrives
        // seconds after the prompt opened (and after a quick answer), and the idle reminder and the
        // sign-in notice are not requests, so none of them moves the row.
        "Notification" -> {
            if (event.str("notification_type") != "elicitation_dialog") return Stepped(rec, turn)
            set(NEEDS_YOU, nowLine = null, question = event.str("message")?.take(QUESTION_MAX))
        }
        "Stop" -> {
            t = t.copy(inFlight = null)
            val last = event.str("last_assistant_message")?.trim().orEmpty()
            if (last.isNotEmpty()) r = r.copy(lastSaid = AgentSessionText.saidOf(last).orEmpty())
            // A finished turn is done, whatever its last line says: a reply that ends in
            // "Want me to go ahead?" blocks nothing. Only a prompt on screen needs the person.
            set(DONE, nowLine = null)
        }
        else -> return Stepped(rec, turn)
    }
    t = t.copy(
        lastActivity = s.now,
        lastHookAt = s.now,
        idlePolls = 0,
        promptSeen = r.state == NEEDS_YOU && t.promptSeen,
    )
    return Stepped(r, t)
}

/** Whether the tool call in flight is a question put to the person, whose dialog stays up until it is answered. */
private fun Turn.askOnScreen(): Boolean = inFlight?.startsWith(ASK_PREFIX) == true

/** Note that the screen shows the agent at work: the one sign of life both feeds give. */
private fun Turn.noteWork(now: Long): Turn =
    if (lastActivity == null || now - lastActivity >= ACTIVITY_REFRESH_MS) copy(lastActivity = now) else this

/**
 * Keeps what [rec] has left running in the background while it sits at its prompt: [found] when the
 * process tree was read, else what the footer of its screen says. A record that works or asks has
 * none, so a shell of its own turn never reads as background work.
 */
private fun Turn.withShells(rec: AgentSessionRecord, s: PollSignal, found: BackgroundShells?): Turn {
    if (rec.state != DONE) return copy(shells = null)
    val seen = found ?: run {
        val count = if (rec.agent == AgentWords.CLAUDE) s.footerShells() else 0
        // The screen does not say when a shell began: it counts from the first poll that saw it.
        BackgroundShells(count, if (count > 0) shells?.since ?: s.now else null)
    }
    return copy(shells = seen.takeIf { it.count > 0 })
}

/**
 * What a hook event is about: the facts [route] needs from it.
 *
 * @property agentId the Claude conversation id in the event, or null.
 * @property name the hook event's name.
 * @property source for `SessionStart`: why it started (`startup`, `resume`, `clear`, `compact`).
 * @property pid the process id of the `claude` that ran the hook, or null.
 * @property transcript the transcript path the event names, or null.
 */
internal data class HookRef(
    val agentId: String?,
    val name: String,
    val source: String?,
    val pid: Long?,
    val transcript: String?,
)

/** Which record a hook event belongs to, as [route] decides. */
internal sealed interface Route {
    /** The event is a late word of a helper that has ended, or of a conversation that cannot exist: ignore it. */
    data object Drop : Route

    /** The pane's own open record carries the event. */
    data object Current : Route

    /** A second `claude` runs inside the pane's conversation: open a helper record beside it. */
    data object Helper : Route

    /** The screen opened a provisional record before hooks named the conversation: it becomes hook-fed. */
    data object Adopt : Route

    /** The pane has no record: reopen [earlier], the closed (or elsewhere open) record of this conversation. */
    data class Reopen(val earlier: AgentSessionRecord) : Route

    /** Another conversation takes the pane: close its record, then reopen [earlier] or, without one, open a new record. */
    data class Replace(val earlier: AgentSessionRecord?) : Route

    /** The pane has no record and the conversation is new: open one. */
    data object Open : Route
}

/**
 * Pick the record a hook event [hook] belongs to.
 *
 * A conversation that speaks in a pane whose identified conversation is open is a helper when its
 * `claude` runs underneath that conversation's `claude` in the process tree, and only then: with
 * either process unknown it is taken for a replacement, never guessed to be a helper. Otherwise the
 * pane's own record carries the event; a conversation that has a record elsewhere or earlier gets it
 * back; and a new one gets a new record.
 *
 * Called by [AgentSessionTracker] for every hook whose conversation has no open record in its pane.
 *
 * @param hook what the event says about itself.
 * @param current the pane's open record, if any.
 * @param earlier the record that already carries this conversation, in this pane or elsewhere.
 * @param hostPid the process id of the `claude` behind [current], when known.
 * @param isUnder whether the first process runs underneath the second.
 * @param fileExists whether a transcript file is on disk.
 * @return where the event goes.
 * @see step
 */
internal fun route(
    hook: HookRef,
    current: AgentSessionRecord?,
    earlier: AgentSessionRecord?,
    hostPid: Long?,
    isUnder: (Long, Long) -> Boolean,
    fileExists: (String) -> Boolean,
): Route {
    val claudeHere = current != null && current.agent == AgentWords.CLAUDE
    val second = claudeHere && current!!.agentSessionId != null && hook.agentId != null && current.agentSessionId != hook.agentId
    val pid = hook.pid
    if (second && pid != null && hostPid != null && pid != hostPid && isUnder(pid, hostPid)) {
        // A helper that already ended has nothing more to say, unless it starts again.
        val gone = earlier != null && earlier.isEnded && earlier.terminalSessionId == current!!.terminalSessionId
        return if (gone && hook.name != "SessionStart") Route.Drop else Route.Helper
    }
    if (claudeHere && (hook.agentId == null || current!!.agentSessionId == hook.agentId)) return Route.Current
    // A conversation whose transcript is gone (a failed `claude --resume`) must not pull its old
    // record into this pane. Unless the hook's own transcript exists: Claude writes the file
    // lazily, so a conversation closed before anything was said is real once the person types.
    if (earlier != null && earlier.transcriptMissing() && (hook.transcript == null || !fileExists(hook.transcript))) return Route.Drop
    val provisional = claudeHere && current!!.agentSessionId == null && current.source == AgentWords.SOURCE_SCREEN
    if (provisional && earlier == null) return Route.Adopt
    if (current != null) return Route.Replace(earlier)
    return if (earlier != null) Route.Reopen(earlier) else Route.Open
}
