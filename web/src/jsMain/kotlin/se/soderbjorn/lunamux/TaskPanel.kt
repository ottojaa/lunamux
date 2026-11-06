/**
 * The task panel in the web client: a 320px column on the right of the window that sits beside a
 * task's main conversation. It has exactly four blocks and no others: Agents (the members working
 * or waiting on the person), Progress (the current task, a bar and "x of y"), Links (every kind of
 * link, newest first, each with its kind) and Recent activity (one line per item). A list shows its
 * first five entries and a "+ N more" line that opens the rest in place; the column scrolls.
 *
 * ⌘I shows and hides the column (as does its ×), and the choice is kept with the other UI
 * settings. An agent's line can be focused: ↵ or a click jumps to a member in a visible tab, shows
 * a waiting background member as a marked tab, or attaches to a member Lunamux does not host.
 *
 * All state and behaviour live in the shared [TaskPanelViewModel]; this file draws its state with
 * DOM nodes and text only (an agent's words are never parsed as HTML) and turns clicks and keys
 * into calls on it. Links are re-checked with [TaskPanelViewModel.linkTarget] when they are clicked.
 *
 * @see TaskPanelViewModel
 * @see registerTaskPanelHotkey
 * @see startTaskPanel
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.browser.window
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
import org.w3c.dom.ParentNode
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel.MemberAction
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel.Section
import se.soderbjorn.lunula.web.hotkey.Hotkey
import se.soderbjorn.lunula.web.hotkey.HotkeyActionSpec
import se.soderbjorn.lunula.web.hotkey.HotkeyBindings
import se.soderbjorn.lunula.web.shell.AppFrameClassNames

/**
 * Stable action id for the **task panel** hotkey (default ⌘I). Persistence key for custom
 * chords, and the id the Keyboard-shortcuts sidebar row references.
 *
 * @see registerTaskPanelHotkey
 */
internal const val TASK_PANEL_HOTKEY_ACTION_ID: String = "Lunamux.taskPanel.toggle"

/**
 * Attribute that gives an element a stable identity across redraws. The panel rebuilds its rows on
 * every state push and puts this on the ones a person can focus, so focus goes back to the row
 * that is drawn again.
 */
internal const val FOCUS_KEY_ATTRIBUTE = "data-focus-key"

/** UI-settings key holding whether the person left the panel open (absent means open). */
private const val KEY_TASK_PANEL_OPEN = "taskPanelOpen"

/** How often the visible panel redraws on its own, so ages move on. */
private const val PANEL_CLOCK_TICK_MS = 30_000

/** The shared view model; created on first use, after the window socket exists. */
private val taskPanel: TaskPanelViewModel by lazy {
    TaskPanelViewModel(lunamuxClient.windowState, send = { windowSocket.send(it) })
}

private var taskPanelStarted = false
private var panelElement: HTMLElement? = null
private var panelClockTimer: Int? = null
private var lastPanelState: TaskPanelViewModel.State? = null

/**
 * Register the configurable **task panel** hotkey (default ⌘I) with the toolkit's
 * [HotkeyBindings], so it shows in the Keyboard-shortcuts sidebar and can be rebound. It only
 * acts in a tab that has a panel; elsewhere it does nothing. Called once at boot from
 * [bootViaToolkitShell].
 *
 * @see TASK_PANEL_HOTKEY_ACTION_ID
 */
internal fun registerTaskPanelHotkey() {
    HotkeyBindings.registerAction(
        HotkeyActionSpec(
            id = TASK_PANEL_HOTKEY_ACTION_ID,
            label = "Task panel",
            defaults = listOf(Hotkey(key = "i", meta = true)),
        ),
    ) {
        val current = taskPanel.state
        if (current.taskId != null) setTaskPanelOpen(!current.open)
    }
}

/**
 * Start drawing the panel: apply the stored open setting, then follow the view model's state.
 * Called once by [bootViaToolkitShell] after the shell is mounted, so the column has a parent to
 * go into.
 *
 * @see renderTaskPanel
 */
@OptIn(DelicateCoroutinesApi::class)
internal fun startTaskPanel() {
    if (taskPanelStarted) return
    taskPanelStarted = true
    taskPanel.setOpen(taskPanelOpenIn(toolkitSettingsSnapshot[KEY_TASK_PANEL_OPEN]))
    GlobalScope.launch { taskPanel.stateFlow.collect { renderTaskPanel(it) } }
}

/**
 * Apply the open setting the server sent with the UI settings, so a change made in another
 * window takes effect here. Called from `onServerUiSettingsApplied`; does nothing before
 * [startTaskPanel] has run (which reads the stored value itself).
 *
 * @param payload the UI settings the server pushed.
 */
internal fun applyTaskPanelOpenSetting(payload: JsonObject) {
    if (!taskPanelStarted) return
    // A push that does not carry the key (an older copy, sent before our own write landed) must
    // not undo the choice just made here.
    val stored = payload[KEY_TASK_PANEL_OPEN] ?: return
    taskPanel.setOpen(taskPanelOpenIn(stored))
}

/**
 * Read the open setting. Accepts a JSON boolean or the strings "true" and "false"; a missing or
 * unreadable value means open.
 */
private fun taskPanelOpenIn(element: JsonElement?): Boolean {
    val primitive = element as? JsonPrimitive ?: return true
    primitive.booleanOrNull?.let { return it }
    return primitive.content != "false"
}

/**
 * Show or hide the column and keep the choice with the other UI settings. Called for ⌘I and ×.
 *
 * @param open true to show.
 */
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

/**
 * Redraw the column from [state]; take it down when the panel is not visible. A redraw rebuilds
 * every node, so the focused agent line is found again by its focus key and given focus back.
 *
 * @param state the view model's latest state.
 */
private fun renderTaskPanel(state: TaskPanelViewModel.State) {
    if (state == lastPanelState && panelElement?.isConnected == true) return
    lastPanelState = state
    if (!state.visible) {
        panelElement?.remove()
        panelElement = null
        keepWorkingGlyphs("panel:", emptySet())
        keepPanelClockTicking(false)
        return
    }
    val body = document.querySelector(".${AppFrameClassNames.BODY}") as? HTMLElement ?: return
    val panel = panelElement?.takeIf { it.isConnected } ?: (document.createElement("aside") as HTMLElement).also {
        it.className = "task-panel"
        body.appendChild(it)
        panelElement = it
    }
    val scroll = panel.querySelector(".tp-scroll")?.scrollTop ?: 0.0
    val focusedKey = (document.activeElement as? HTMLElement)
        ?.takeIf { panel.contains(it) }?.getAttribute(FOCUS_KEY_ATTRIBUTE)
    while (panel.firstChild != null) panel.removeChild(panel.firstChild!!)
    panel.appendChild(buildHeader(state))
    val content = div("tp-scroll")
    val glyphKeys = HashSet<String>()
    content.appendChild(buildAgents(state.agents, glyphKeys))
    content.appendChild(buildProgress(state.progress))
    content.appendChild(buildLinks(state.links))
    content.appendChild(buildActivity(state.activity))
    panel.appendChild(content)
    content.scrollTop = scroll
    keepWorkingGlyphs("panel:", glyphKeys)
    focusedKey?.let { key ->
        findByFocusKey(panel, key)?.focus()
    }
    keepPanelClockTicking(true)
}

/** Start or stop the timer that makes ages move on while the column is shown. */
private fun keepPanelClockTicking(needed: Boolean) {
    if (needed && panelClockTimer == null) {
        panelClockTimer = window.setInterval({ taskPanel.tick() }, PANEL_CLOCK_TICK_MS)
    } else if (!needed) {
        panelClockTimer?.let { window.clearInterval(it) }
        panelClockTimer = null
    }
}

private fun div(cls: String): HTMLElement = (document.createElement("div") as HTMLElement).also { it.className = cls }

private fun span(cls: String, text: String): HTMLElement =
    (document.createElement("span") as HTMLElement).also { it.className = cls; it.textContent = text }

/** The column's header: "<title> · ⌘I hides" with the close button at the far end. */
private fun buildHeader(state: TaskPanelViewModel.State): HTMLElement {
    val head = div("tp-head")
    head.appendChild(span("tp-title", state.title).also { it.title = state.title })
    head.appendChild(span("tp-hint", "· ⌘I hides"))
    val close = document.createElement("button") as HTMLElement
    close.asDynamic().type = "button"
    close.className = "tp-close"
    close.textContent = "×"
    close.title = "Hide the task panel (⌘I)"
    close.addEventListener("click", { _: Event -> setTaskPanelOpen(false) })
    head.appendChild(close)
    return head
}

/**
 * A block's heading: its name and nothing else (no writer, no age). The block carries its name in
 * `data-block` so a check can count the blocks.
 *
 * @param name the block's name: "Agents", "Progress", "Links" or "Recent activity".
 * @return the block's root element with its heading drawn.
 */
private fun block(name: String): HTMLElement {
    val root = div("tp-block")
    root.setAttribute("data-block", name)
    root.appendChild(div("tp-block-title").also { it.textContent = name })
    return root
}

/**
 * The "+ N more" line under a cut list. A press opens the rest of the list in place.
 *
 * @param section which list the line belongs to.
 * @param more how many entries are not drawn.
 * @return the button.
 */
private fun moreButton(section: Section, more: Int): HTMLElement {
    val button = document.createElement("button") as HTMLElement
    button.asDynamic().type = "button"
    button.className = "tp-more"
    button.textContent = "+ $more more"
    button.setAttribute(FOCUS_KEY_ATTRIBUTE, "more:${section.name}")
    button.addEventListener("click", { _: Event -> taskPanel.showMore(section) })
    return button
}

/**
 * The "Agents" block: one line per member that is working or waiting on the person, the waiting
 * ones first. A line shows the state mark (a turning glyph while it works, ◆ while it waits), the
 * name and the age, then what it is doing in words. A line with an action can be focused: ↵ or a
 * click runs it, and a hint under the name says what that does.
 *
 * @param agents the block from the view model.
 * @param glyphKeys collects the keys of the working glyphs drawn, so unused ones can be dropped.
 * @return the block element.
 */
@OptIn(DelicateCoroutinesApi::class)
private fun buildAgents(agents: TaskPanelViewModel.Block<TaskPanelViewModel.AgentLine>, glyphKeys: MutableSet<String>): HTMLElement {
    val root = block("Agents")
    if (agents.total == 0) {
        root.appendChild(div("tp-muted").also { it.textContent = "No agents working." })
        return root
    }
    for (line in agents.shown) {
        val row = div("tp-agent tp-state-${line.state}")
        val mark = div("tp-glyph")
        if (line.state == "needs_you") {
            mark.textContent = "◆"
        } else {
            val key = "panel:agent:${line.key}"
            glyphKeys += key
            mark.appendChild(workingGlyph(key))
        }
        row.appendChild(mark)
        val text = div("tp-agent-text")
        val top = div("tp-agent-top")
        top.appendChild(span("tp-agent-title", line.title))
        top.appendChild(span("tp-agent-age", line.age))
        text.appendChild(top)
        text.appendChild(div("tp-agent-now").also { it.textContent = line.nowLine ?: line.stateWord })
        line.hint?.let { text.appendChild(buildHint(it)) }
        row.appendChild(text)
        if (line.action != MemberAction.NONE) {
            row.classList.add("tp-agent-active")
            row.setAttribute("tabindex", "0")
            row.setAttribute("role", "button")
            row.setAttribute(FOCUS_KEY_ATTRIBUTE, "agent:${line.key}")
            row.addEventListener("click", { _: Event -> GlobalScope.launch { taskPanel.activateMember(line) } })
            row.addEventListener("keydown", { e: Event ->
                val key = e as KeyboardEvent
                if (key.key == "Enter") {
                    key.preventDefault()
                    key.stopPropagation()
                    GlobalScope.launch { taskPanel.activateMember(line) }
                }
            })
        }
        root.appendChild(row)
    }
    if (agents.more > 0) root.appendChild(moreButton(Section.AGENTS, agents.more))
    return root
}

/**
 * The hint under an agent's line. Each part of "↵ answer it · opens as a tab until it works on" is
 * a key and the words it does; the key is set in bold.
 *
 * @param hint the words from [TaskPanelViewModel.AgentLine.hint].
 * @return the hint element.
 */
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

/**
 * The "Progress" block: the task in hand above the bar, "x of y" below it.
 *
 * @param progress the view model's progress, or null when no agent wrote one.
 * @return the block element.
 */
private fun buildProgress(progress: TaskPanelViewModel.ProgressView?): HTMLElement {
    val root = block("Progress")
    if (progress == null) {
        root.appendChild(div("tp-muted").also { it.textContent = "No progress written yet." })
        return root
    }
    progress.current?.let { root.appendChild(div("tp-progress-current").also { e -> e.textContent = it }) }
    progress.fraction?.let { f ->
        val bar = div("tp-bar")
        bar.appendChild(div("tp-bar-fill").also { it.style.width = "${(f.coerceIn(0.0, 1.0) * 100).toInt()}%" })
        root.appendChild(bar)
    }
    progress.count?.let { root.appendChild(div("tp-progress-text").also { e -> e.textContent = it }) }
    return root
}

/**
 * The "Links" block: one row per link, newest first, each with its kind (slack, PR, doc, …), its
 * words and its clock time. The whole row opens the address outside the app, after the address
 * passes [TaskPanelViewModel.linkTarget].
 *
 * @param links the block from the view model.
 * @return the block element.
 */
private fun buildLinks(links: TaskPanelViewModel.Block<TaskPanelViewModel.LinkRow>): HTMLElement {
    val root = block("Links")
    if (links.total == 0) {
        root.appendChild(div("tp-muted").also { it.textContent = "No links yet." })
        return root
    }
    links.shown.forEachIndexed { i, link ->
        val row = div("tp-link-row")
        row.appendChild(span("tp-kind", link.kind))
        row.appendChild(span("tp-link-title", link.title))
        row.appendChild(span("tp-link-time", link.time))
        row.setAttribute("role", "link")
        row.setAttribute("tabindex", "0")
        row.setAttribute(FOCUS_KEY_ATTRIBUTE, "link:$i")
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
        root.appendChild(row)
    }
    if (links.more > 0) root.appendChild(moreButton(Section.LINKS, links.more))
    return root
}

/**
 * The "Recent activity" block: one spaced line per item, the time on the left and the item in plain
 * words next to it.
 *
 * @param activity the block from the view model.
 * @return the block element.
 */
internal fun buildActivity(activity: TaskPanelViewModel.Block<TaskPanelViewModel.ActivityRow>): HTMLElement {
    val root = block("Recent activity")
    if (activity.total == 0) {
        root.appendChild(div("tp-muted").also { it.textContent = "Nothing yet." })
        return root
    }
    for (item in activity.shown) {
        val row = div("tp-tl")
        row.appendChild(span("tp-tl-age", item.time))
        row.appendChild(span("tp-tl-text", item.text))
        root.appendChild(row)
    }
    if (activity.more > 0) root.appendChild(moreButton(Section.ACTIVITY, activity.more))
    return root
}

/**
 * The element under [root] that carries [key] in its [FOCUS_KEY_ATTRIBUTE], or null. Keys can hold
 * any character (a member's key may be a session id from a task file), so they are compared as
 * strings, never spliced into a selector, where a quote or backslash would make the query throw.
 *
 * @param root the part of the page to search.
 * @param key the focus key to find.
 * @return the first element carrying [key], or null when none does.
 */
internal fun findByFocusKey(root: ParentNode, key: String): HTMLElement? {
    val candidates = root.querySelectorAll("[$FOCUS_KEY_ATTRIBUTE]")
    for (i in 0 until candidates.length) {
        val candidate = candidates.item(i) as? HTMLElement ?: continue
        if (candidate.getAttribute(FOCUS_KEY_ATTRIBUTE) == key) return candidate
    }
    return null
}

/** The words on the tag over a background agent's tab that is shown while it waits. */
internal const val WAITING_TAB_TAG_TEXT = "background agent · hides again once it works on"

private var waitingTabTag: HTMLElement? = null

/**
 * Show or remove the tag that marks the active tab as a background agent's tab shown while it
 * waits. The tag floats over the top edge of the window body, centred, and says the tab hides
 * again once the agent works on. Called whenever the tab list is pushed, with whether the active
 * tab is such a tab.
 *
 * @param shown true while the active tab is a background agent's tab shown while it waits.
 * @see TabConfig.shownWhileWaiting
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
