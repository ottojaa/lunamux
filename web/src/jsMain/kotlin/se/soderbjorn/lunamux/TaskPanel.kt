/**
 * The task panel in the web client: a 320px column on the right of the window that sits beside a
 * task's main conversation. Its header says where the work is: the tab's name, the task's title in
 * full, where and since when, then the state and the headline the agents overview shows too. Under
 * it come four blocks: Plan (the step in hand and the bar), Background agents (the task's live
 * sessions: working, waiting on the person or idle), Activity (newest first, under day headings)
 * and Links. A list shows its first five entries and a "+ N more" line that opens the rest in
 * place. With no block to show it says how to set the panel up.
 *
 * ⌘I shows and hides the column in any tab (as does its ×). Beside a main conversation the choice
 * is kept with the other UI settings; elsewhere it holds for that tab only. An agent's line can be
 * focused: ↵ or a click jumps to the session, or shows the hidden tab of a background session as a
 * marked tab.
 *
 * All state and behaviour live in [TaskPanelViewModel]; this file draws its state with DOM nodes
 * and text only (an agent's words are never parsed as HTML). Each block keeps its element and
 * updates its lines with [syncChildren], so scroll, focus and the spinners survive a redraw.
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunamux.PanelSection
import se.soderbjorn.lunamux.client.viewmodel.AgentFacts
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel.AgentLine
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel.LinkRow
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel.MemberAction
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel.Section
import se.soderbjorn.lunula.web.hotkey.Hotkey
import se.soderbjorn.lunula.web.hotkey.HotkeyActionSpec
import se.soderbjorn.lunula.web.hotkey.HotkeyBindings
import se.soderbjorn.lunula.web.shell.AppFrameClassNames

/** Action id of the panel's hotkey (default ⌘I): the key custom chords are stored under. */
internal const val TASK_PANEL_HOTKEY_ACTION_ID: String = "Lunamux.taskPanel.toggle"

/** UI-settings key holding whether the person left the panel open (absent means open). */
private const val KEY_TASK_PANEL_OPEN = "taskPanelOpen"

private val taskPanel: TaskPanelViewModel by lazy {
    TaskPanelViewModel(lunamuxClient.windowState, send = { windowSocket.send(it) })
}

private var taskPanelStarted = false

/** The column and its parts, built once and put in or taken out of the page as the panel shows or hides. */
internal class PanelParts {
    val root = (document.createElement("aside") as HTMLElement).also { it.className = "task-panel" }
    val title = span("tp-title")
    val about = div("tp-about")
    val state = div("tp-state")
    val status = div("tp-status")
    val scroll = div("tp-scroll")
    val blocks = HashMap<String, HTMLElement>()

    init {
        val close = document.createElement("button") as HTMLElement
        close.asDynamic().type = "button"
        close.className = "tp-close"
        close.textContent = "×"
        close.title = "Hide the task panel (⌘I)"
        close.addEventListener("click", { _: Event -> toggleTaskPanel() })
        val head = div("tp-head")
        val side = div("tp-side")
        side.appendChild(close)
        side.appendChild(span("tp-hint", "⌘I"))
        val main = div("tp-main")
        listOf(title, about, state, status).forEach { main.appendChild(it) }
        head.appendChild(main)
        head.appendChild(side)
        root.appendChild(head)
        root.appendChild(scroll)
    }

    fun block(name: String): HTMLElement = blocks.getOrPut(name) {
        div("tp-block").also { it.setAttribute("data-block", name) }
    }
}

private val parts: PanelParts by lazy { PanelParts() }

/** Registers the panel's hotkey with the toolkit, so it shows in the shortcuts sidebar and can be rebound. */
internal fun registerTaskPanelHotkey() {
    HotkeyBindings.registerAction(
        HotkeyActionSpec(
            id = TASK_PANEL_HOTKEY_ACTION_ID,
            label = "Task panel",
            defaults = listOf(Hotkey(key = "i", meta = true)),
        ),
    ) { toggleTaskPanel() }
}

/** Show or hide the column in the active tab. Called for ⌘I and ×. */
private fun toggleTaskPanel() {
    val current = taskPanel.state
    if (current.beside) setTaskPanelOpen(!current.open) else taskPanel.toggleInActiveTab()
}

/** Applies the stored open setting, then draws the view model's state. Called once at boot after the shell is mounted. */
@OptIn(DelicateCoroutinesApi::class)
internal fun startTaskPanel() {
    if (taskPanelStarted) return
    taskPanelStarted = true
    startAgeClock()
    taskPanel.setOpen(taskPanelOpenIn(toolkitSettingsSnapshot[KEY_TASK_PANEL_OPEN]))
    GlobalScope.launch { taskPanel.stateFlow.collect { renderTaskPanel(it) } }
}

/** Applies the open setting the server sent with the UI settings, so a change made in another window takes effect here. */
internal fun applyTaskPanelOpenSetting(payload: JsonObject) {
    if (!taskPanelStarted) return
    // A push that does not carry the key (an older copy, sent before our own write landed) must
    // not undo the choice just made here.
    val stored = payload[KEY_TASK_PANEL_OPEN] ?: return
    taskPanel.setOpen(taskPanelOpenIn(stored))
}

/** Reads the open setting: a JSON boolean or the strings "true" and "false"; anything else means open. */
private fun taskPanelOpenIn(element: JsonElement?): Boolean {
    val primitive = element as? JsonPrimitive ?: return true
    primitive.booleanOrNull?.let { return it }
    return primitive.content != "false"
}

/** Shows or hides the column beside a main conversation and keeps the choice with the other UI settings. */
@OptIn(DelicateCoroutinesApi::class)
private fun setTaskPanelOpen(open: Boolean) {
    taskPanel.setOpen(open)
    val merged = toolkitSettingsSnapshot.toMutableMap()
    merged[KEY_TASK_PANEL_OPEN] = JsonPrimitive(open)
    toolkitSettingsSnapshot = JsonObject(merged)
    GlobalScope.launch {
        webSettingsPersister.putJsonSettings(buildJsonObject { put(KEY_TASK_PANEL_OPEN, JsonPrimitive(open)) })
    }
}

/** What a block's element holds, top to bottom; each has a key for [syncChildren]. */
private sealed interface TpLine { val key: String }
private data class TpTitle(val name: String) : TpLine { override val key get() = "title" }
private data class TpMuted(val text: String) : TpLine { override val key get() = "muted" }
private data class TpAgent(val line: AgentLine) : TpLine { override val key get() = "a:${line.recordId}" }
private data class TpMore(val section: Section, val more: Int) : TpLine { override val key get() = "more:${more > 0}" }
private data class TpProgress(val view: TaskPanelViewModel.ProgressView) : TpLine { override val key get() = "progress" }
private data class TpLink(val index: Int, val link: LinkRow) : TpLine { override val key get() = "l:$index" }
private data class TpActivity(val index: Int, val item: TaskPanelViewModel.ActivityRow) : TpLine { override val key get() = "t:$index" }
private data class TpTurn(val index: Int, val turn: TaskPanelViewModel.TurnRow) : TpLine { override val key get() = "u:$index" }
private data class TpWork(val text: String) : TpLine { override val key get() = "w:$text" }
private data class TpDay(val day: String) : TpLine { override val key get() = "d:$day" }

/** Draws [state] into the column, or takes the column out of the page when the panel is not visible. */
private fun renderTaskPanel(state: TaskPanelViewModel.State) {
    val p = parts
    if (!state.visible) {
        p.root.remove()
        return
    }
    if (!p.root.isConnected) (document.querySelector(".${AppFrameClassNames.BODY}") as? HTMLElement)?.appendChild(p.root) ?: return
    renderHead(p, state)
    val written = state.sections.map { sectionBlock(p, it) }
    val story = state.story
    if (story?.key != lastStoryKey) {
        lastStoryKey = story?.key
        if (story != null) GlobalScope.launch { taskPanel.readStory() }
    }
    val setup = state.setup
    if (setup != null) {
        // A session with no task: what Lunamux sees by itself, then how to get more.
        val seen = listOfNotNull(
            story?.takeIf { it.turns.total > 0 }?.let { p.block("You asked") to turnLines(it.turns) },
            story?.takeIf { it.work.isNotEmpty() }?.let { p.block("Changed") to (listOf<TpLine>(TpTitle("Changed")) + it.work.map(::TpWork)) },
        )
        seen.forEach { (el, lines) -> syncChildren(el, lines, TpLine::key) { buildLine(it) } }
        val setupEl = p.blocks.getOrPut("setup") { div("tp-setup-host").also { it.setAttribute("data-block", "setup") } }
        syncChildren(setupEl, listOf(setup), { "setup" }) { buildSetup(it) }
        // The line saying how agents write here is for a panel no agent has written to.
        val hint = listOf(setupEl).filter { written.isEmpty() || !setup.compact }
        syncChildren(p.scroll, written + seen.map { it.first } + hint, { it.getAttribute("data-block")!! }) { it }
        return
    }
    // A block with nothing in it is left out: an empty heading tells the person nothing.
    val wanted = listOfNotNull(
        state.progress?.let { p.block("Plan") to progressLines(it) },
        (p.block("Background agents") to agentLines(state.agents)).takeIf { state.agents.total > 0 },
        (p.block("Activity") to activityLines(state.activity)).takeIf { state.activity.total > 0 },
        (p.block("Links") to linkLines(state.links)).takeIf { state.links.total > 0 },
    )
    wanted.forEach { (el, lines) -> syncChildren(el, lines, TpLine::key) { buildLine(it) } }
    syncChildren(p.scroll, written + wanted.map { it.first }, { it.getAttribute("data-block")!! }) { it }
}

/**
 * The block of one agent-written section: its heading with the age of the writing at the right,
 * then the body. The element is kept per key and its content rebuilt only when the section was
 * written again.
 */
private fun sectionBlock(p: PanelParts, section: PanelSection): HTMLElement {
    val el = p.block("section:${section.key}")
    val stamp = "${section.writtenAtMillis}/${section.heading}"
    if (el.getAttribute("data-stamp") != stamp) {
        el.setAttribute("data-stamp", stamp)
        el.textContent = ""
        val top = div("tp-block-title tp-section-top")
        top.appendChild(span("tp-section-heading", section.heading))
        top.appendChild(ageSpan("tp-agent-age", section.writtenAtMillis))
        el.appendChild(top)
        el.appendChild(buildSectionBody(section.body))
    }
    return el
}

/**
 * A section's body in the simple formatting agents may use: blank lines part paragraphs, lines
 * starting "- " or "* " make a list, `**bold**` and backticked code are set as such. Everything is
 * put in as text nodes, so whatever an agent writes is shown and never run.
 */
internal fun buildSectionBody(body: String): HTMLElement {
    val root = div("tp-section-body")
    var list: HTMLElement? = null
    var para: HTMLElement? = null
    for (raw in body.lines()) {
        val line = raw.trim()
        val bullet = line.startsWith("- ") || line.startsWith("* ")
        when {
            line.isEmpty() -> { list = null; para = null }
            bullet -> {
                para = null
                val host = list ?: (document.createElement("ul") as HTMLElement).also { root.appendChild(it); list = it }
                host.appendChild((document.createElement("li") as HTMLElement).also { appendInline(it, line.drop(2).trim()) })
            }
            else -> {
                list = null
                val host = para ?: (document.createElement("p") as HTMLElement).also { root.appendChild(it); para = it }
                if (host.childNodes.length > 0) host.appendChild(document.createTextNode(" "))
                appendInline(host, line)
            }
        }
    }
    return root
}

private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")

/** Appends [text] to [host], with `**bold**` as <b> and backticked runs as <code>. */
private fun appendInline(host: HTMLElement, text: String) {
    var at = 0
    for (m in INLINE.findAll(text)) {
        if (m.range.first > at) host.appendChild(document.createTextNode(text.substring(at, m.range.first)))
        val bold = m.groups[1]?.value
        val el = document.createElement(if (bold != null) "b" else "code") as HTMLElement
        el.textContent = bold ?: m.groups[2]!!.value
        host.appendChild(el)
        at = m.range.last + 1
    }
    if (at < text.length) host.appendChild(document.createTextNode(text.substring(at)))
}

/** The story key the server was last asked about, so one state asks once. */
private var lastStoryKey: String? = null

private fun turnLines(block: TaskPanelViewModel.Block<TaskPanelViewModel.TurnRow>): List<TpLine> = listOf<TpLine>(TpTitle("You asked")) +
    block.shown.mapIndexed { i, t -> TpTurn(i, t) } + listOfNotNull(moreLine(Section.TURNS, block))

/** Sets [el]'s text, and hides it when there is none. */
private fun show(el: HTMLElement, text: String?) {
    if (el.textContent != text.orEmpty()) el.textContent = text.orEmpty()
    el.style.display = if (text.isNullOrEmpty()) "none" else ""
}

/**
 * The header: the name, the title under it when the tab's name led, then one line of facts (state
 * mark, word and age, where, since when) and, when an agent set one, its status. The state line is rebuilt only when its words change,
 * so a working glyph keeps turning.
 */
private fun renderHead(p: PanelParts, state: TaskPanelViewModel.State) {
    val head = state.head
    show(p.title, head?.name ?: state.title)
    show(p.about, head?.about)
    show(p.status, head?.headline)
    // That the line is the agent's own status, drawn as a small label over it (see `.tp-status` in styles.css).
    p.status.setAttribute("data-label", head?.headlineLabel.orEmpty())
    val stateKey = head?.let { "${it.state}/${it.stateWord}/${it.sinceMillis}/${it.meta}" }.orEmpty()
    if (p.state.getAttribute("data-key") != stateKey) {
        p.state.setAttribute("data-key", stateKey)
        p.state.textContent = ""
        p.state.className = "tp-state tp-state-${head?.state.orEmpty()}"
        if (head != null) {
            val mark = div("tp-glyph")
            when (head.state) {
                AgentFacts.NEEDS_YOU -> mark.textContent = "◆"
                AgentFacts.WORKING -> mark.appendChild(spinner())
                else -> mark.textContent = "○"
            }
            p.state.appendChild(mark)
            p.state.appendChild(span("tp-state-word", head.stateWord))
            p.state.appendChild(ageSpan("tp-agent-age", head.sinceMillis))
            // Where and since when, on the same line: the header's facts read as one sentence.
            if (head.meta.isNotEmpty()) p.state.appendChild(span("tp-meta", "· ${head.meta}"))
        }
    }
    p.state.style.display = if (head == null) "none" else ""
}

private fun agentLines(block: TaskPanelViewModel.Block<AgentLine>): List<TpLine> = listOf<TpLine>(TpTitle("Background agents")) +
    block.shown.map { TpAgent(it) } +
    listOfNotNull(moreLine(Section.AGENTS, block))

/** The plan: one line when every step is done ("Plan · 15 of 15 done"), else the bar and the steps. */
private fun progressLines(progress: TaskPanelViewModel.ProgressView): List<TpLine> =
    if (progress.finished) listOf(TpTitle(listOfNotNull("Plan", progress.count?.let { "$it done" } ?: "done").joinToString(" · ")))
    else listOf(TpTitle("Plan"), TpProgress(progress))

private fun linkLines(block: TaskPanelViewModel.Block<LinkRow>): List<TpLine> = listOf<TpLine>(TpTitle("Links")) +
    block.shown.mapIndexed { i, l -> TpLink(i, l) } +
    listOfNotNull(moreLine(Section.LINKS, block))

/** The activity entries, each day's under a heading of its own. */
private fun activityLines(block: TaskPanelViewModel.Block<TaskPanelViewModel.ActivityRow>): List<TpLine> = listOf<TpLine>(TpTitle("Activity")) +
    block.shown.flatMapIndexed { i, a -> listOfNotNull(TpDay(a.day).takeIf { i == 0 || block.shown[i - 1].day != a.day }, TpActivity(i, a)) } +
    listOfNotNull(moreLine(Section.ACTIVITY, block))

private fun buildLine(line: TpLine): HTMLElement = when (line) {
    is TpTitle -> div("tp-block-title").also { it.textContent = line.name }
    is TpMuted -> div("tp-muted").also { it.textContent = line.text }
    is TpAgent -> buildAgent(line.line)
    is TpMore -> buildMore(line)
    is TpProgress -> buildProgress(line.view)
    is TpLink -> buildLink(line.link)
    is TpDay -> div("tp-day").also { it.textContent = line.day }
    is TpWork -> div("tp-muted").also { it.textContent = line.text }
    is TpTurn -> div("tp-tl").also {
        it.appendChild(span("tp-tl-age", line.turn.time))
        val both = div("tp-turn")
        val text = span("tp-tl-text", line.turn.prompt)
        if (line.turn.took.isNotEmpty()) text.appendChild(span("tp-tl-took", " · ${line.turn.took}"))
        both.appendChild(text)
        // What came back, so a short prompt ("go") still says what the turn was.
        if (line.turn.reply.isNotEmpty()) both.appendChild(div("tp-turn-reply").also { r -> r.textContent = "→ ${line.turn.reply}" })
        it.appendChild(both)
    }
    is TpActivity -> div("tp-tl").also {
        it.appendChild(span("tp-tl-age", line.item.time))
        it.appendChild(span("tp-tl-text", line.item.text))
    }
}

/**
 * How to get more into the panel. A session with no task gets one faint line, since that is the
 * usual case; a panel with nothing at all to show gets the lead, the prompt to copy and the note.
 */
private fun buildSetup(setup: TaskPanelViewModel.Setup): HTMLElement {
    if (setup.compact) return div("tp-setup tp-setup-compact").also { it.textContent = setup.lead }
    val root = div("tp-setup")
    root.appendChild(div("tp-setup-lead").also { it.textContent = setup.lead })
    root.appendChild(div("tp-setup-prompt").also { it.textContent = setup.prompt })
    root.appendChild(div("tp-muted").also { it.textContent = TaskPanelViewModel.SETUP_NOTE })
    return root
}

/** The line under a cut list ("+ 8 more") or under one opened in full ("show less"); none for a short list. */
private fun moreLine(section: Section, block: TaskPanelViewModel.Block<*>): TpLine? = when {
    block.more > 0 -> TpMore(section, block.more)
    block.opened -> TpMore(section, 0)
    else -> null
}

/** The button that opens a list in full, or, when [TpMore.more] is 0, folds an opened one again. */
private fun buildMore(line: TpMore): HTMLElement {
    val button = document.createElement("button") as HTMLElement
    button.asDynamic().type = "button"
    button.className = "tp-more"
    button.textContent = if (line.more > 0) "+ ${line.more} more" else "show less"
    button.addEventListener("click", { _: Event -> if (line.more > 0) taskPanel.showMore(line.section) else taskPanel.showLess(line.section) })
    return button
}

/**
 * One agent: the state mark (spinner while it works, ◆ while it waits, ○ while idle), its title
 * (two lines at most) with its age, and under it one activity line that never wraps, so the row is
 * as tall whatever the agent does.
 */
@OptIn(DelicateCoroutinesApi::class)
internal fun buildAgent(line: AgentLine): HTMLElement {
    val row = div("tp-agent tp-state-${line.state}")
    val mark = div("tp-glyph")
    when (line.state) {
        AgentFacts.NEEDS_YOU -> mark.textContent = "◆"
        AgentFacts.WORKING -> mark.appendChild(spinner())
        else -> mark.textContent = "○"
    }
    row.appendChild(mark)
    val text = div("tp-agent-text")
    val top = div("tp-agent-top")
    top.appendChild(span("tp-agent-title", line.title))
    top.appendChild(ageSpan("tp-agent-age", line.sinceMillis))
    text.appendChild(top)
    val sub = div("tp-agent-sub")
    sub.appendChild(div("tp-agent-now").also { it.textContent = line.doing; it.title = line.doing })
    line.hint?.let { sub.appendChild(buildHint(it)) }
    text.appendChild(sub)
    row.appendChild(text)
    if (line.action != MemberAction.NONE) {
        row.classList.add("tp-agent-active")
        row.setAttribute("tabindex", "0")
        row.setAttribute("role", "button")
        row.addEventListener("click", { _: Event -> GlobalScope.launch { taskPanel.activateMember(line) } })
        // Acts on the release: showing a tab moves keyboard focus into its terminal, and a key still
        // held down would then repeat into the agent.
        var pressed = false
        row.addEventListener("keydown", { e: Event ->
            val key = e as KeyboardEvent
            if (key.key == "Enter") {
                key.preventDefault()
                key.stopPropagation()
                pressed = true
            }
        })
        row.addEventListener("keyup", { e: Event ->
            if ((e as KeyboardEvent).key == "Enter" && pressed) {
                pressed = false
                GlobalScope.launch { taskPanel.activateMember(line) }
            }
        })
    }
    return row
}

/** The hint at the end of an agent's activity line: each part of "↵ open · …" is a key, set in bold, and the words it does. */
private fun buildHint(hint: String): HTMLElement {
    val host = div("tp-agent-hint")
    hint.split(" · ").forEachIndexed { i, part ->
        if (i > 0) host.appendChild(document.createTextNode(" · "))
        val key = part.substringBefore(' ')
        host.appendChild((document.createElement("b") as HTMLElement).also { it.textContent = key })
        host.appendChild(document.createTextNode(part.removePrefix(key)))
    }
    return host
}

/** The bar with "x of y" beside it, then the step in hand. */
private fun buildProgress(progress: TaskPanelViewModel.ProgressView): HTMLElement {
    val root = div("tp-progress")
    progress.fraction?.let { f ->
        val line = div("tp-bar-line")
        val bar = div("tp-bar")
        bar.appendChild(div("tp-bar-fill").also { it.style.width = "${(f.coerceIn(0.0, 1.0) * 100).toInt()}%" })
        line.appendChild(bar)
        progress.count?.let { line.appendChild(span("tp-progress-text", it)) }
        root.appendChild(line)
    }
    progress.current?.let { root.appendChild(div("tp-progress-current").also { e -> e.textContent = it }) }
    if (progress.earlierDone > 0) root.appendChild(buildStep(TaskStep.DONE, "${progress.earlierDone} earlier ${if (progress.earlierDone == 1) "step" else "steps"}"))
    progress.steps.forEach { root.appendChild(buildStep(it.state, it.text)) }
    return root
}

/** One step of the plan: ✓ and dim when done, the turning glyph and bright when in hand, dim when still to do. */
private fun buildStep(state: String, text: String): HTMLElement {
    val row = div("tp-step tp-step-$state")
    val mark = div("tp-glyph")
    when (state) {
        TaskStep.DONE -> mark.textContent = "✓"
        TaskStep.CURRENT -> mark.appendChild(spinner())
        else -> mark.textContent = "·"
    }
    row.appendChild(mark)
    row.appendChild(div("tp-step-text").also { it.textContent = text })
    return row
}

/**
 * A link row: its kind, its words in the accent colour with ↗ (underlined on hover) and its clock
 * time. The address passes [TaskPanelViewModel.linkTarget] when the row is pressed.
 */
private fun buildLink(link: LinkRow): HTMLElement {
    val row = div("tp-link-row")
    // The default kind says nothing; only a kind the writer chose is worth its column.
    row.appendChild(span("tp-kind", link.kind.takeIf { it != TaskLink.DEFAULT_KIND }.orEmpty()))
    row.appendChild(span("tp-link-title", link.title).also { it.appendChild(span("tp-link-arrow", " ↗")) })
    row.appendChild(span("tp-link-time", link.time))
    row.setAttribute("role", "link")
    row.setAttribute("tabindex", "0")
    row.title = link.url
    val open: (Event) -> Unit = { taskPanel.linkTarget(link.url)?.let { target -> openExternalUrl(target) } }
    row.addEventListener("click", open)
    row.addEventListener("keydown", { e: Event ->
        val key = e as KeyboardEvent
        if (key.key == "Enter") {
            key.preventDefault()
            open(e)
        }
    })
    return row
}

/** The words on the tag over a background agent's tab that is shown for now. */
internal const val WAITING_TAB_TAG_TEXT = "background agent · hides when you leave"

private var waitingTabTag: HTMLElement? = null

/**
 * Show or remove the tag that marks the active tab as a background agent's tab shown for now. The
 * tag floats over the top edge of the window body, centred, and says the tab hides when the person leaves it.
 */
internal fun updateWaitingTabTag(shown: Boolean) {
    val body = document.querySelector(".${AppFrameClassNames.BODY}") as? HTMLElement
    if (!shown || body == null) {
        waitingTabTag?.remove()
        waitingTabTag = null
        return
    }
    val tag = waitingTabTag?.takeIf { it.isConnected } ?: div("waiting-tab-tag").also {
        it.textContent = WAITING_TAB_TAG_TEXT
        it.setAttribute("role", "status")
        document.body?.appendChild(it)
        waitingTabTag = it
    }
    val box = body.getBoundingClientRect()
    tag.style.top = "${box.top + 6}px"
    tag.style.left = "${box.left + box.width / 2}px"
}
