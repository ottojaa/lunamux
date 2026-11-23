/**
 * The agents board: everything the client knows about the agents overview, built in one place.
 *
 * The server decides each conversation's shown state, since, title, line and whether it is
 * listed ([AgentSessionSummary]); this file only joins those facts with the layout. It holds the
 * look of every [Shown] value ([look]), the board's data ([Work], [Conversation], [Tally],
 * [AgentBoard]) and the one function that builds it ([buildBoard]).
 *
 * The overview, the sidebar footer and the 3D world boxes all read the same board, so what they
 * draw and count agrees. A [Work] holds only what a row draws, as plain values, so two boards built
 * from the same frame are equal and a row that did not change is not redrawn.
 *
 * @see WindowStateRepository.agentBoard
 */
package se.soderbjorn.lunamux.client

import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.AgentWindows
import se.soderbjorn.lunamux.Shown
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.WindowEnvelope
import se.soderbjorn.lunamux.isVisible

/** How a [Shown] state is coloured. */
enum class Tone { ALERT, WORK, WAIT, QUIET }

/**
 * How one [Shown] state is drawn.
 *
 * @property word what the row says ("needs you").
 * @property plural the word for several ("N need you"), or null when it has no plural form.
 * @property mark the glyph that opens the row; empty for none. A working row draws a spinner instead.
 * @property tone the colour family.
 * @property open whether the conversation is still open (not ended).
 */
data class ShownLook(val word: String, val plural: String?, val mark: String, val tone: Tone, val open: Boolean) {
    /**
     * The word for [n] conversations in this state.
     *
     * @param n how many.
     * @return the plural form for two or more, else [word].
     */
    fun count(n: Int): String = if (n >= 2 && plural != null) plural.replace("N", n.toString()) else word
}

/**
 * The one table that says how each [Shown] state looks. Called by the overview rows, the footer
 * and the tests; there is no other place that maps a state to a word, mark or colour.
 *
 * @receiver the state.
 * @return its look. An unknown state looks like [Shown.DONE].
 */
val Shown.look: ShownLook
    get() = when (this) {
        Shown.NEEDS_YOU -> ShownLook("needs you", "N need you", "◆", Tone.ALERT, true)
        Shown.WORKING -> ShownLook("working", "N agents working", "", Tone.WORK, true)
        Shown.WAITING -> ShownLook("waiting", "N waiting", "◐", Tone.WAIT, true)
        Shown.PAUSED -> ShownLook("paused", null, "‖", Tone.WAIT, true)
        Shown.STOPPED -> ShownLook("cut off", null, "", Tone.QUIET, false)
        Shown.CLOSED -> ShownLook("closed", null, "", Tone.QUIET, false)
        Shown.DONE, Shown.UNKNOWN -> ShownLook("done", null, "", Tone.QUIET, true)
    }

/**
 * The word for how a closed conversation ended.
 *
 * @param ended the end reason the server reported, or null.
 * @return "cleared", "quit", "ended", "cut off", "tab closed", "replaced", else "closed".
 */
fun endedWord(ended: String?): String = when (ended) {
    "cleared" -> "cleared"
    "quit" -> "quit"
    "exit" -> "ended"
    "restart" -> "cut off"
    "closed" -> "tab closed"
    "replaced" -> "replaced"
    else -> "closed"
}

/** Where a conversation's pane sits. */
enum class Place {
    /** It has no pane in the layout. */
    NONE,

    /** Its tab is in the strip. */
    VISIBLE,

    /** Its tab is a background tab nobody has opened. */
    HIDDEN,

    /** Its tab is a background tab the person has opened for a look. */
    SHOWN,
}

/** What ↵ does on a row. */
enum class Enter { JUMP, SHOW, OPEN, RESUME, NONE }

/**
 * One conversation as the board knows it: the server's summary and where its pane is.
 *
 * @property summary what the server said.
 * @property place where its pane sits.
 * @property tabId the tab holding its pane, or null.
 * @property worldId the world of that tab, or null when the layout has no worlds.
 * @property tabName the tab's own name when it is a real one (not "Tab 3"), else null.
 * @property paneSessionId the terminal session of its pane, which closing the pane needs, or null.
 */
data class Conversation(
    val summary: AgentSessionSummary,
    val place: Place,
    val tabId: String?,
    val worldId: String?,
    val tabName: String?,
    val paneSessionId: String? = null,
) {
    /** Whether the conversation is open. */
    val live: Boolean get() = summary.ended == null

    /** Whether the person can see its tab right now. */
    val seen: Boolean get() = place == Place.VISIBLE || place == Place.SHOWN
}

/**
 * One line of a task row's list of agents.
 *
 * @property id the conversation's id.
 * @property title its name; "Main conversation" for the task's main.
 * @property line what it says now, or empty.
 * @property shown its state.
 * @property since when it entered that state.
 * @property hidden whether it runs in a tab nobody has opened.
 */
data class AgentLine(val id: String, val title: String, val line: String, val shown: Shown, val since: Long, val hidden: Boolean)

/**
 * One row of the overview: a task, or a conversation that belongs to none. Only drawn values, so
 * `==` says whether the row looks different.
 *
 * @property id `task:<id>` for a task, else the conversation's id.
 * @property taskId the task, or null for a lone conversation.
 * @property mainId the task's main conversation, when it has one.
 * @property memberIds the conversations of the row, by start.
 * @property name the title.
 * @property shown the state the row is drawn in.
 * @property word what the state says (for several agents "2 need you", for a closed row how it ended).
 * @property since when the row entered its state; its age counts from here.
 * @property recency when the row was last alive, for ordering the history.
 * @property started when the work began, for ordering NOW.
 * @property line the one line under the name, empty for none.
 * @property lineSet whether the server wrote [line] for this state (a question, a status) rather than taking it from the last reply.
 * @property lastReply the last reply of a done row with no line of its own; shown when selected.
 * @property recap the speaker's recap, while it is open.
 * @property where the repository or folder the row is grouped under.
 * @property folder the folder, when it says more than [where].
 * @property tabName the tab's name, which leads the row, or null.
 * @property agents a task's open conversations, one line each; empty for a lone conversation.
 * @property inNow whether the row belongs under NOW.
 * @property asks whether the task asks for the person through its own status.
 * @property stopped whether Lunamux's restart stopped it within the day and it can be resumed.
 * @property live whether the conversation ↵ acts on is open.
 * @property canClose whether the row is one open conversation in a tab the person can see.
 * @property enter what ↵ does.
 * @property targetId the conversation ↵ acts on, or null.
 * @property resumeInTab whether a resume can reuse the conversation's own pane.
 * @property why why ↵ does nothing, when [enter] is [Enter.NONE].
 */
data class Work(
    val id: String,
    val taskId: String?,
    val mainId: String?,
    val memberIds: List<String>,
    val name: String,
    val shown: Shown,
    val word: String,
    val since: Long,
    val recency: Long,
    val started: Long,
    val line: String,
    val lineSet: Boolean,
    val lastReply: String?,
    val recap: String?,
    val where: String?,
    val folder: String?,
    val tabName: String?,
    val agents: List<AgentLine>,
    val inNow: Boolean,
    val asks: Boolean,
    val stopped: Boolean,
    val live: Boolean,
    val canClose: Boolean,
    val enter: Enter,
    val targetId: String?,
    val resumeInTab: Boolean,
    val why: String?,
)

/**
 * The counts the overview, the footer and the 3D boxes show.
 *
 * @property needsYou conversations that need the person (hidden and unplaced ones too), plus tasks asking through their status.
 * @property working conversations at work.
 * @property waiting conversations at their prompt while something they started goes on, not counting the mains of asking tasks.
 * @property paused paused tasks in NOW.
 * @property done done rows in NOW.
 */
data class Tally(val needsYou: Int = 0, val working: Int = 0, val waiting: Int = 0, val paused: Int = 0, val done: Int = 0)

/**
 * Everything the screens read about agents. Built by [buildBoard] from one `Agents` frame and one
 * layout, so no reader sees half of a pair.
 *
 * @property works the rows, unordered; the overview sorts and sections them.
 * @property conversations every conversation of the frame by id.
 * @property tally the total counts.
 * @property tallyByWorld the counts per world id, only for worlds with a need or work.
 * @property activeWorldId the layout's active world when the board was built.
 * @property subjects the work each tab's side panel is about, by tab id (work ids).
 * @property focus the work of each tab's focused pane when the board was built, by tab id.
 */
data class AgentBoard(
    val works: List<Work> = emptyList(),
    val conversations: Map<String, Conversation> = emptyMap(),
    val tally: Tally = Tally(),
    val tallyByWorld: Map<String, Tally> = emptyMap(),
    val activeWorldId: String? = null,
    val subjects: Map<String, String> = emptyMap(),
    val focus: Map<String, String> = emptyMap(),
) {
    /**
     * The row for a work id.
     *
     * @param id a [Work.id].
     * @return the row, or null.
     */
    fun work(id: String): Work? = works.firstOrNull { it.id == id }

    /**
     * The work a tab's panel is about. It changes only when the tab's focused pane moves to another
     * work, or when no member of it has a pane in the tab any more; a change of state never moves it.
     *
     * @param tabId the tab.
     * @return the row, or null when the tab has none.
     */
    fun subjectOf(tabId: String): Work? = subjects[tabId]?.let(::work)

    /**
     * The row a search hit belongs to.
     *
     * @param match one hit from the server.
     * @return the task's row when the conversation belongs to a task, else the conversation's own
     *   row, else null (a closed conversation the overview does not list).
     */
    fun workOf(match: se.soderbjorn.lunamux.AgentSessionMatch): Work? = workOfConversation(match.summary)

    /**
     * The row of a conversation.
     *
     * @param s the conversation's summary.
     * @return its task's row, or its own row, or null when it has none.
     */
    fun workOfConversation(s: AgentSessionSummary): Work? = work(workIdOf(s))

    /**
     * The commands that carry out ↵ on [work].
     *
     * @param work the row.
     * @return the commands to send, empty when ↵ does nothing ([Work.why] says why).
     */
    fun enter(work: Work): List<WindowCommand> {
        val target = work.targetId?.let(conversations::get)
        return when (work.enter) {
            Enter.JUMP -> target?.let { go(it, focus = true) }.orEmpty()
            Enter.SHOW -> target?.let { go(it, focus = false) }.orEmpty()
            Enter.RESUME -> listOf(WindowCommand.ResumeAgent(recordId = work.targetId, inTab = work.resumeInTab))
            Enter.OPEN -> work.taskId?.let { listOf(WindowCommand.OpenTask(it)) }.orEmpty()
            Enter.NONE -> emptyList()
        }
    }

    private fun go(c: Conversation, focus: Boolean): List<WindowCommand> {
        val tab = c.tabId ?: return emptyList()
        return listOfNotNull(
            c.worldId?.takeIf { it != activeWorldId }?.let { WindowCommand.SetActiveWorld(it) },
            WindowCommand.SetActiveTab(tab),
            c.summary.leafId?.takeIf { focus }?.let { WindowCommand.SetFocusedPane(tab, it) },
        )
    }
}

/** The id of the row a conversation belongs to. */
internal fun workIdOf(s: AgentSessionSummary): String = s.taskId?.let { "$TASK_PREFIX$it" } ?: s.id

/** Prefix of a task row's id. */
internal const val TASK_PREFIX = "task:"

private val DEFAULT_TAB_NAME = Regex("^Tab \\d+$")
private const val MAIN_TITLE = "Main conversation"
private const val NOTHING_TO_RESUME = "nothing to resume: its transcript is gone"

/**
 * Builds the board from one frame and one layout. Pure: the same inputs give an equal board, so a
 * repeated frame changes nothing.
 *
 * @param frame the last `Agents` frame, or null before the first one.
 * @param config the last layout, or null before the first one.
 * @param now the time, for the day within which a restart's stop is still offered for resuming.
 * @param previous the board before this one, which remembers each tab's subject.
 * @return the board.
 * @see WindowStateRepository.agentBoard
 */
fun buildBoard(frame: WindowEnvelope.Agents?, config: WindowConfig?, now: Long, previous: AgentBoard = AgentBoard()): AgentBoard {
    if (frame == null) return AgentBoard(activeWorldId = config?.activeWorldId)
    val spots = spotsOf(config)
    val conversations = frame.sessions.associate { s ->
        val spot = s.leafId?.let(spots::get)
        val place = when {
            spot == null -> Place.NONE
            !config!!.isVisible(spot.tab) -> Place.HIDDEN
            spot.tab.isBackground -> Place.SHOWN
            else -> Place.VISIBLE
        }
        val name = spot?.tab?.title?.trim()?.takeIf { place != Place.HIDDEN && it.isNotEmpty() && !DEFAULT_TAB_NAME.matches(it) }
        s.id to Conversation(s, place, spot?.tab?.id, spot?.worldId, name, spot?.sessionId)
    }
    val byTask = frame.sessions.filter { it.taskId != null }.groupBy { it.taskId!! }
    val taskRows = frame.tasks.mapNotNull { taskWork(it, byTask[it.id].orEmpty(), conversations, now) }
    val claimed = frame.tasks.mapTo(HashSet()) { it.id }
    val singles = frame.sessions
        .filter { it.taskId == null || it.taskId !in claimed }
        .filter { s ->
            val c = conversations.getValue(s.id)
            (c.live || s.listed) && (c.place != Place.HIDDEN || (c.live && s.shown == Shown.NEEDS_YOU))
        }
        .map { singleWork(conversations.getValue(it.id), now) }
    val works = taskRows + singles
    val (tally, byWorld) = tallyOf(works, conversations, config?.activeWorldId)
    val (subjects, focus) = subjectsOf(config, conversations, works, previous)
    return AgentBoard(works, conversations, tally, byWorld, config?.activeWorldId, subjects, focus)
}

private class Spot(val worldId: String?, val tab: TabConfig, val sessionId: String)

private fun spotsOf(config: WindowConfig?): Map<String, Spot> {
    val out = HashMap<String, Spot>()
    if (config == null) return out
    fun add(worldId: String?, tabs: List<TabConfig>) = tabs.forEach { t -> t.panes.forEach { out[it.leaf.id] = Spot(worldId, t, it.leaf.sessionId) } }
    if (config.worlds.isEmpty()) add(null, config.tabs) else config.worlds.forEach { add(it.id, it.tabs) }
    return out
}

private fun stoppedByRestart(c: Conversation, now: Long): Boolean =
    !c.live && c.summary.ended == "restart" && c.summary.resumable && now - c.summary.since <= AgentWindows.STOPPED_MS

/** Where ↵ takes the person to reach [c], or why it can't. */
private fun reach(c: Conversation): Pair<Enter, String?> = when {
    c.seen -> Enter.JUMP to null
    c.place == Place.HIDDEN -> Enter.SHOW to null
    else -> Enter.NONE to "it is not in any tab"
}

private fun singleWork(c: Conversation, now: Long): Work {
    val s = c.summary
    val look = s.shown.look
    val stopped = stoppedByRestart(c, now)
    val (enter, why) = when {
        c.live -> reach(c)
        s.resumable -> Enter.RESUME to null
        else -> Enter.NONE to NOTHING_TO_RESUME
    }
    val where = s.repo ?: s.folder
    val reply = s.line.takeIf { c.live && s.shown == Shown.DONE }
    return Work(
        id = s.id, taskId = null, mainId = null, memberIds = listOf(s.id), name = s.title,
        shown = s.shown, word = if (c.live) look.word else endedWord(s.ended),
        since = s.since, recency = s.since, started = s.startedAt,
        line = if (c.live && s.shown != Shown.DONE) s.line.orEmpty() else "",
        lineSet = s.lineSet, lastReply = reply, recap = s.recap.takeIf { c.live },
        where = where, folder = s.folder?.takeIf { it != where },
        tabName = tabNameOf(c, s.title, stopped), agents = emptyList(),
        inNow = c.live, asks = false, stopped = stopped, live = c.live,
        canClose = c.live && c.seen, enter = enter, targetId = s.id, resumeInTab = s.inTab, why = why,
    )
}

private fun tabNameOf(c: Conversation?, title: String, stopped: Boolean): String? {
    if (c == null || !(c.live || (stopped && c.summary.inTab))) return null
    return c.tabName?.takeIf { !it.equals(title.trim(), ignoreCase = true) }
}

private fun taskWork(t: AgentTask, members: List<AgentSessionSummary>, all: Map<String, Conversation>, now: Long): Work? {
    val mine = members.sortedWith(compareBy({ it.startedAt }, { it.id })).map { all.getValue(it.id) }
    val main = t.main?.let(all::get)
    val mainLive = main?.live == true
    val questionWaits = t.questionWaits && !mainLive && main != null
    val paused = t.pausedAt != null
    if (mine.isEmpty() && !t.canOpen && !questionWaits && !paused) return null

    val open = mine.filter { it.live }
    val needs = open.filter { it.summary.shown == Shown.NEEDS_YOU }
    val working = open.filter { it.summary.shown == Shown.WORKING }
    val onHold = open.filter { it.summary.shown == Shown.WAITING }
    val inNow = open.isNotEmpty() || mainLive || questionWaits || paused
    val asks = inNow && t.questionWaits && !t.status.isNullOrBlank() && needs.isEmpty() &&
        mainLive && main?.summary?.shown != Shown.WORKING

    val (target, enter, why) = taskTarget(t, main, open, needs, mine)
    val ended = (target ?: mine.maxByOrNull { it.summary.since })?.summary
    val (shown, word) = when {
        needs.size >= 2 -> Shown.NEEDS_YOU to Shown.NEEDS_YOU.look.count(needs.size)
        needs.isNotEmpty() || asks -> Shown.NEEDS_YOU to Shown.NEEDS_YOU.look.word
        working.any { it.summary.id == t.main } || (t.main == null && working.size == 1) -> Shown.WORKING to Shown.WORKING.look.word
        working.isNotEmpty() -> Shown.WORKING to Shown.WORKING.look.count(working.size)
        paused -> Shown.PAUSED to Shown.PAUSED.look.word
        onHold.isNotEmpty() && inNow -> Shown.WAITING to Shown.WAITING.look.count(onHold.size)
        !inNow -> (ended?.shown ?: Shown.CLOSED) to endedWord(ended?.ended)
        else -> Shown.DONE to Shown.DONE.look.word
    }

    val recency = mine.maxOfOrNull { it.summary.since } ?: t.startedAt ?: 0L
    val since = when {
        !inNow -> recency
        shown == Shown.NEEDS_YOU && needs.isNotEmpty() -> needs.minOf { it.summary.since }
        asks -> t.statusAt ?: recency
        shown == Shown.WAITING -> onHold.minOf { it.summary.since }
        shown == Shown.PAUSED -> t.pausedAt ?: recency
        working.isNotEmpty() -> working.minOf { it.summary.since }
        else -> recency
    }

    // The line: the oldest question, the task's own status, the newest working agent, a waiting one.
    val said: Pair<String, Boolean>? =
        needs.minByOrNull { it.summary.since }?.summary?.line?.let { it to true }
            ?: t.status?.trim()?.takeIf { it.isNotEmpty() }?.let { it to true }
            ?: working.sortedByDescending { it.summary.since }.firstNotNullOfOrNull { c -> c.summary.line?.let { it to c.summary.lineSet } }
            ?: onHold.firstNotNullOfOrNull { c -> c.summary.line?.let { it to c.summary.lineSet } }
    val speaker = (main ?: mine.maxByOrNull { it.summary.since })?.takeIf { it.live }
    val spoken = speaker?.summary?.line
    val where = t.repo ?: main?.summary?.repo ?: mine.mapNotNull { it.summary.repo }.distinct().singleOrNull()
        ?: main?.summary?.folder ?: mine.mapNotNull { it.summary.folder }.distinct().singleOrNull()
    val lead = (main?.takeIf { it.live } ?: target)
    val stopped = !inNow && enter == Enter.RESUME && target != null && stoppedByRestart(target, now)
    val single = mine.singleOrNull()
    val lines = open.takeIf { it.size > 1 || it.any { c -> c.summary.id != t.main } }.orEmpty()
        .sortedWith(compareBy({ it.summary.id != t.main }, { it.summary.id != t.main && it.place == Place.HIDDEN }))
        .map { AgentLine(it.summary.id, if (it.summary.id == t.main) MAIN_TITLE else it.summary.title, it.summary.line.orEmpty(), it.summary.shown, it.summary.since, it.summary.id != t.main && it.place == Place.HIDDEN) }
    return Work(
        id = "$TASK_PREFIX${t.id}", taskId = t.id, mainId = t.main?.takeIf { it in all }, memberIds = mine.map { it.summary.id },
        name = t.title, shown = shown, word = word, since = since, recency = recency,
        started = t.startedAt ?: mine.minOfOrNull { it.summary.startedAt } ?: since,
        line = (said?.first ?: spoken.takeUnless { shown == Shown.DONE }).orEmpty(),
        lineSet = said?.second == true,
        lastReply = spoken.takeIf { shown == Shown.DONE && said == null },
        recap = speaker?.summary?.recap, where = where,
        folder = target?.summary?.folder?.takeIf { it != where },
        tabName = tabNameOf(lead, t.title, stopped), agents = lines,
        inNow = inNow, asks = asks, stopped = stopped, live = target?.live == true,
        canClose = single != null && single.live && single.seen,
        enter = enter, targetId = target?.summary?.id, resumeInTab = target?.summary?.inTab == true, why = why,
    )
}

/**
 * Where ↵ goes on a task: a helper that needs the person while the main does not, else a live main,
 * else a closed main with a transcript, else the most urgent open conversation in a tab the person
 * can see, else the task's own open command, else the newest conversation that can be resumed.
 */
private fun taskTarget(
    t: AgentTask,
    main: Conversation?,
    open: List<Conversation>,
    needs: List<Conversation>,
    members: List<Conversation>,
): Triple<Conversation?, Enter, String?> {
    val asking = needs.minByOrNull { it.summary.since }
    val mainLive = main?.live == true
    fun reaching(c: Conversation) = reach(c).let { Triple(c, it.first, it.second) }
    return when {
        main != null && mainLive && (asking == null || main.summary.shown == Shown.NEEDS_YOU) -> reaching(main)
        asking != null -> reaching(asking)
        main != null && main.summary.resumable -> Triple(main, Enter.RESUME, null)
        else -> {
            val seen = open.filter { it.seen }.minWithOrNull(
                compareBy<Conversation>({ if (it.summary.shown == Shown.WORKING) 1 else 2 }, { -it.summary.since }),
            )
            val newest = members.filter { it.summary.resumable }.maxByOrNull { it.summary.since }
            when {
                seen != null -> Triple(seen, Enter.JUMP, null)
                t.canOpen -> Triple(null, Enter.OPEN, null)
                newest != null -> Triple(newest, Enter.RESUME, null)
                else -> Triple(main ?: members.maxByOrNull { it.summary.since }, Enter.NONE, "nothing to open or resume")
            }
        }
    }
}

private fun tallyOf(works: List<Work>, all: Map<String, Conversation>, activeWorld: String?): Pair<Tally, Map<String, Tally>> {
    val askingMains = works.filter { it.asks }.mapNotNull { it.mainId }.toSet()
    val byWorld = LinkedHashMap<String, Tally>()
    fun world(c: Conversation?) = c?.worldId ?: activeWorld ?: ""
    fun bump(world: String, edit: (Tally) -> Tally) {
        byWorld[world] = edit(byWorld[world] ?: Tally())
    }
    var total = Tally(
        paused = works.count { it.inNow && it.shown == Shown.PAUSED },
        done = works.count { it.inNow && it.shown == Shown.DONE },
    )
    for (c in all.values.filter { it.live }) {
        when (c.summary.shown) {
            Shown.NEEDS_YOU -> { total = total.copy(needsYou = total.needsYou + 1); bump(world(c)) { it.copy(needsYou = it.needsYou + 1) } }
            Shown.WORKING -> { total = total.copy(working = total.working + 1); bump(world(c)) { it.copy(working = it.working + 1) } }
            Shown.WAITING -> if (c.summary.id !in askingMains) total = total.copy(waiting = total.waiting + 1)
            else -> Unit
        }
    }
    // A task asking through its status needs the person though no conversation of it is counted as asking.
    for (w in works.filter { it.asks }) {
        total = total.copy(needsYou = total.needsYou + 1)
        bump(world(w.mainId?.let(all::get))) { it.copy(needsYou = it.needsYou + 1) }
    }
    return total to byWorld
}

/** Each tab's panel subject and focused work; see [AgentBoard.subjectOf] for the rule. */
private fun subjectsOf(
    config: WindowConfig?,
    all: Map<String, Conversation>,
    works: List<Work>,
    previous: AgentBoard,
): Pair<Map<String, String>, Map<String, String>> {
    if (config == null) return emptyMap<String, String>() to emptyMap()
    val worksById = works.mapTo(HashSet()) { it.id }
    val tabs = (if (config.worlds.isEmpty()) config.tabs else config.worlds.flatMap { it.tabs })
    val placedIn = HashMap<String, List<Conversation>>()
    all.values.filter { it.seen }.groupBy { it.tabId!! }.forEach { (tab, cs) -> placedIn[tab] = cs }
    val subjects = HashMap<String, String>()
    val focus = HashMap<String, String>()
    for (tab in tabs) {
        val here = placedIn[tab.id].orEmpty()
        val focused = tab.focusedPaneId?.let { leaf -> here.firstOrNull { it.summary.leafId == leaf } }
            ?.let { workIdOf(it.summary) }?.takeIf { it in worksById }
        focused?.let { focus[tab.id] = it }
        val before = previous.subjects[tab.id]
        val stays = before != null && before in worksById && here.any { workIdOf(it.summary) == before }
        val moved = focused != null && focused != previous.focus[tab.id]
        val next = when {
            stays && !moved -> before
            else -> focused ?: before.takeIf { stays }
                ?: here.firstOrNull { it.live && workIdOf(it.summary) in worksById }?.let { workIdOf(it.summary) }
        }
        next?.let { subjects[tab.id] = it }
    }
    return subjects to focus
}
