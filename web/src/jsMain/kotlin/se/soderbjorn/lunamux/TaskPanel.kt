/**
 * The task panel in the web client: a 320px column on the right of the window that shows what the
 * agents of a task wrote about it. It appears only in the tab that holds the task's own session,
 * starting with an "Agents" block (every other session of the task, with its state and age) and
 * followed by the blocks agents added with `set_task_panel`, `upsert_task_block` and
 * `remove_task_block` (a documents block draws one plain row per document, which opens outside
 * the app).
 *
 * ⌘I shows and hides the column (as does its ×), and the choice is kept with the other UI
 * settings. A member's line can be focused: ↵ on a background member starts the watch (see
 * [startWatch]), ⌘↵ shows its tab as an ordinary tab, and ↵ on a member in a visible tab jumps to it.
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
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunamux.client.viewmodel.MdBlock
import se.soderbjorn.lunamux.client.viewmodel.MdInline
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel.BlockBody
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel.MemberAction
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

/** UI-settings key holding whether the person left the panel open (absent means open). */
private const val KEY_TASK_PANEL_OPEN = "taskPanelOpen"

/** How often the visible panel redraws on its own, so ages move on. */
private const val PANEL_CLOCK_TICK_MS = 30_000

/** The shared view model; created on first use, after the window socket exists. */
private val taskPanel: TaskPanelViewModel by lazy {
    TaskPanelViewModel(lunamuxClient.windowState, watchViewModel, send = { windowSocket.send(it) })
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
    content.appendChild(buildAgents(state))
    state.blocks.forEach { content.appendChild(buildBlock(it)) }
    state.emptyText?.let { content.appendChild(div("tp-empty").also { e -> e.textContent = it }) }
    panel.appendChild(content)
    content.scrollTop = scroll
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

/** The column's header: the task's name, the key that hides it, and the close button. */
private fun buildHeader(state: TaskPanelViewModel.State): HTMLElement {
    val head = div("tp-head")
    head.appendChild(span("tp-title", state.title).also { it.title = state.title })
    head.appendChild(span("tp-hint", "⌘I"))
    val close = document.createElement("button") as HTMLElement
    close.asDynamic().type = "button"
    close.className = "tp-close"
    close.textContent = "×"
    close.title = "Hide the task panel (⌘I)"
    close.addEventListener("click", { _: Event -> setTaskPanelOpen(false) })
    head.appendChild(close)
    return head
}

/** A block's header: its title on the left, who wrote it and when (or [right]) on the right. */
private fun blockHead(title: String?, right: String, rightClass: String): HTMLElement {
    val head = div("tp-block-head")
    head.appendChild(span("tp-block-title", title ?: ""))
    head.appendChild(span(rightClass, right))
    return head
}

/**
 * The "Agents" block: one line per other session of the task. A line shows the member's state
 * mark, name and age on the first row and what it is doing (or its state word) under the name.
 * A line with an action can be focused: ↵ or a click runs it, ⌘↵ shows a background member's tab,
 * and a hint row under the name says which keys do what while the line is hovered or focused.
 */
@OptIn(DelicateCoroutinesApi::class)
private fun buildAgents(state: TaskPanelViewModel.State): HTMLElement {
    val block = div("tp-block tp-agents")
    block.appendChild(blockHead("Agents", "live", "tp-byline"))
    if (state.agents.isEmpty()) {
        block.appendChild(div("tp-muted").also { it.textContent = "No other agents in this task." })
        return block
    }
    for (line in state.agents) {
        val row = div("tp-agent tp-state-${line.state}" + if (line.dimmed) " tp-dim" else "")
        val glyph = when {
            line.state == "needs_you" -> "◆"
            line.state == "ended" -> "○"
            else -> "●"
        }
        row.appendChild(span("tp-glyph", glyph))
        val text = div("tp-agent-text")
        val top = div("tp-agent-top")
        top.appendChild(span("tp-agent-title", line.title))
        top.appendChild(span("tp-agent-age", line.age))
        text.appendChild(top)
        val sub = if (line.state == "ended") line.stateWord else line.nowLine ?: line.stateWord
        text.appendChild(div("tp-agent-now").also { it.textContent = sub })
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
                    GlobalScope.launch {
                        if (key.metaKey || key.ctrlKey) taskPanel.showAsTab(line) else taskPanel.activateMember(line)
                    }
                }
            })
        }
        block.appendChild(row)
    }
    return block
}

/**
 * The hint under an agent's line. Each part of "↵ watch · ⌘↵ show as tab" is a key and the words
 * it does; the keys are set in bold.
 *
 * @param hint the words from [TaskPanelViewModel.AgentLine.hint].
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

/** One stored block: a header with its title and who wrote it and when, then its body. */
@OptIn(DelicateCoroutinesApi::class)
private fun buildBlock(view: TaskPanelViewModel.BlockView): HTMLElement {
    val block = div("tp-block")
    block.appendChild(blockHead(view.title, view.byline, "tp-byline"))
    when (val body = view.body) {
        is BlockBody.Text -> block.appendChild(buildMarkdown(body.nodes))
        is BlockBody.Facts -> {
            val grid = div("tp-facts")
            for ((label, value) in body.items) {
                grid.appendChild(span("tp-fact-label", label))
                grid.appendChild(span("tp-fact-value", value))
            }
            block.appendChild(grid)
        }
        is BlockBody.Links -> for ((label, url) in body.items) {
            block.appendChild(buildLink(label, url))
        }
        is BlockBody.Documents -> body.items.forEachIndexed { i, doc ->
            block.appendChild(buildDocument(doc, "doc:${view.id}:$i"))
        }
        is BlockBody.Timeline -> for (entry in body.items) {
            val row = div("tp-tl")
            row.appendChild(span("tp-tl-age", entry.age))
            row.appendChild(span("tp-tl-text", entry.text))
            block.appendChild(row)
        }
        is BlockBody.Progress -> {
            body.fraction?.let { f ->
                val bar = div("tp-bar")
                bar.appendChild(div("tp-bar-fill").also { it.style.width = "${(f.coerceIn(0.0, 1.0) * 100).toInt()}%" })
                block.appendChild(bar)
            }
            block.appendChild(div("tp-progress-text").also { it.textContent = body.text })
            body.label?.let { block.appendChild(div("tp-muted").also { m -> m.textContent = it }) }
        }
        is BlockBody.Actions -> {
            val row = div("tp-actions")
            for (b in body.buttons) {
                val button = document.createElement("button") as HTMLElement
                button.asDynamic().type = "button"
                button.className = "tp-action"
                button.textContent = b.label
                if (!b.enabled) button.asDynamic().disabled = true
                button.addEventListener("click", { _: Event ->
                    GlobalScope.launch { taskPanel.press(view.id, b.id) }
                })
                row.appendChild(button)
            }
            block.appendChild(row)
        }
    }
    return block
}

/** A link the person can click; it opens only after the address passes [TaskPanelViewModel.linkTarget]. */
private fun buildLink(label: String, url: String): HTMLElement {
    val link = span("tp-link", label)
    link.setAttribute("role", "link")
    link.setAttribute("tabindex", "0")
    link.title = url
    val open: (Event) -> Unit = { taskPanel.linkTarget(url)?.let { target -> openExternalUrl(target) } }
    link.addEventListener("click", open)
    link.addEventListener("keydown", { e: Event -> if ((e as KeyboardEvent).key == "Enter") open(e) })
    return link
}

/**
 * One document row: a dim document mark, the title, the time, and ↗. The whole row is the link: a
 * click or ↵ opens the address outside the app, after it passes [TaskPanelViewModel.linkTarget].
 * The row keeps focus across a redraw through its focus key.
 *
 * @param doc the row from the view model.
 * @param focusKey the row's key for [FOCUS_KEY_ATTRIBUTE]: its block id and position.
 */
private fun buildDocument(doc: TaskPanelViewModel.DocumentRow, focusKey: String): HTMLElement {
    val row = div("tp-doc")
    row.appendChild(div("tp-doc-mark"))
    row.appendChild(span("tp-doc-title", doc.title))
    row.appendChild(span("tp-doc-time", doc.age ?: ""))
    row.appendChild(span("tp-doc-go", "↗"))
    row.setAttribute("role", "link")
    row.setAttribute("tabindex", "0")
    row.setAttribute(FOCUS_KEY_ATTRIBUTE, focusKey)
    row.title = doc.url
    val open: (Event) -> Unit = { taskPanel.linkTarget(doc.url)?.let { target -> openExternalUrl(target) } }
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

/** Draw parsed markdown as elements; no node is ever made from an agent's text but a text node. */
private fun buildMarkdown(nodes: List<MdBlock>): HTMLElement {
    val root = div("tp-md")
    for (node in nodes) {
        when (node) {
            is MdBlock.Paragraph -> root.appendChild(inlineHost("p", "tp-md-p", node.inlines))
            is MdBlock.Heading -> root.appendChild(inlineHost("div", "tp-md-h tp-md-h${node.level}", node.inlines))
            is MdBlock.ListBlock -> {
                val list = document.createElement(if (node.ordered) "ol" else "ul") as HTMLElement
                list.className = "tp-md-list"
                node.items.forEach { list.appendChild(inlineHost("li", "", it)) }
                root.appendChild(list)
            }
            is MdBlock.Code -> {
                val pre = document.createElement("pre") as HTMLElement
                pre.className = "tp-md-code"
                pre.textContent = node.text
                root.appendChild(pre)
            }
        }
    }
    return root
}

private fun inlineHost(tag: String, cls: String, inlines: List<MdInline>): HTMLElement {
    val host = document.createElement(tag) as HTMLElement
    if (cls.isNotEmpty()) host.className = cls
    appendInlines(host, inlines)
    return host
}

private fun appendInlines(parent: HTMLElement, inlines: List<MdInline>) {
    for (inline in inlines) {
        when (inline) {
            is MdInline.Text -> parent.appendChild(document.createTextNode(inline.text))
            is MdInline.Bold -> parent.appendChild((document.createElement("strong") as HTMLElement).also { appendInlines(it, inline.children) })
            is MdInline.Italic -> parent.appendChild((document.createElement("em") as HTMLElement).also { appendInlines(it, inline.children) })
            is MdInline.Code -> parent.appendChild((document.createElement("code") as HTMLElement).also { it.textContent = inline.text })
            is MdInline.Link -> parent.appendChild(buildLink(inline.label, inline.url))
        }
    }
}
