/**
 * The task panel in the web client: a 320px column on the right of the window that shows what the
 * agents of a task wrote about it. It appears only in the tab that holds the task's own session,
 * starting with an "Agents" block (every other session of the task, with its state and age) and
 * followed by the blocks agents added with `set_task_panel`, `upsert_task_block` and
 * `remove_task_block`.
 *
 * ⌘I shows and hides the column (as does its ×), and the choice is kept with the other UI
 * settings. A background member's line opens a read-only live view of its terminal over the
 * current pane: typing goes nowhere, no size is ever sent, ⌘↵ takes the tab over and esc goes back.
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
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLElement
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunamux.client.CellMetrics
import se.soderbjorn.lunamux.client.MirrorFit
import se.soderbjorn.lunamux.client.viewmodel.MdBlock
import se.soderbjorn.lunamux.client.viewmodel.MdInline
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel.BlockBody
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel.MemberAction
import se.soderbjorn.lunula.web.hotkey.Hotkey
import se.soderbjorn.lunula.web.hotkey.HotkeyActionSpec
import se.soderbjorn.lunula.web.hotkey.HotkeyBindings
import se.soderbjorn.lunula.web.shell.AppFrameClassNames
import kotlin.js.json

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

/** Smallest and largest font size the peek is drawn at. */
private const val PEEK_MIN_FONT_PX = 6
private const val PEEK_MAX_FONT_PX = 14

/** Width of the column in pixels; the peek stops where the column starts. */
private const val PANEL_WIDTH_PX = 320

/** The shared view model; created on first use, after the window socket exists. */
private val taskPanel: TaskPanelViewModel by lazy {
    TaskPanelViewModel(lunamuxClient.windowState, send = { windowSocket.send(it) })
}

private var taskPanelStarted = false
private var panelElement: HTMLElement? = null
private var panelClockTimer: Int? = null
private var lastPanelState: TaskPanelViewModel.State? = null

/**
 * Everything that belongs to one open peek, so it can be taken down in one go.
 *
 * @property recordId the session record being watched; a different one means a new peek.
 * @property root the overlay element.
 * @property term the read-only terminal, or null when there is no session to mirror.
 * @property socket the viewer socket, or null.
 * @property keyHandler the capture-phase key listener installed on the window.
 * @property returnFocus the element that had focus before the peek opened.
 */
private class OpenPeek(
    val recordId: String,
    val root: HTMLElement,
    val term: Terminal?,
    val socket: WebSocket?,
    val keyHandler: (Event) -> Unit,
    val returnFocus: HTMLElement?,
)

private var openPeek: OpenPeek? = null
private var resizeHandler: ((Event) -> Unit)? = null

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
 * Redraw the column and the peek from [state]; take both down when the panel is not visible.
 *
 * @param state the view model's latest state.
 */
private fun renderTaskPanel(state: TaskPanelViewModel.State) {
    if (state == lastPanelState && panelElement?.isConnected == true) return
    lastPanelState = state
    if (!state.visible) {
        panelElement?.remove()
        panelElement = null
        closePeekView(restoreFocus = false)
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
    while (panel.firstChild != null) panel.removeChild(panel.firstChild!!)
    panel.appendChild(buildHeader(state))
    val content = div("tp-scroll")
    content.appendChild(buildAgents(state))
    state.blocks.forEach { content.appendChild(buildBlock(it)) }
    state.emptyText?.let { content.appendChild(div("tp-empty").also { e -> e.textContent = it }) }
    panel.appendChild(content)
    content.scrollTop = scroll
    keepPanelClockTicking(true)
    syncPeek(state.peek)
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

/** The "Agents" block: one line per other session of the task. */
@OptIn(DelicateCoroutinesApi::class)
private fun buildAgents(state: TaskPanelViewModel.State): HTMLElement {
    val block = div("tp-block tp-agents")
    block.appendChild(div("tp-block-title").also { it.textContent = "Agents" })
    if (state.agents.isEmpty()) {
        block.appendChild(div("tp-muted").also { it.textContent = "No other agents in this task." })
        return block
    }
    for (line in state.agents) {
        val row = div("tp-agent tp-state-${line.state}" + if (line.dimmed) " tp-dim" else "")
        val glyph = when (line.state) {
            "working" -> "●"
            "needs_you" -> "◆"
            "idle" -> "○"
            else -> "·"
        }
        row.appendChild(span("tp-glyph", glyph))
        val text = div("tp-agent-text")
        text.appendChild(div("tp-agent-title").also { it.textContent = line.title })
        text.appendChild(div("tp-agent-meta").also { it.textContent = "${line.stateWord} · ${line.age}" })
        line.nowLine?.let { text.appendChild(div("tp-agent-now").also { n -> n.textContent = it }) }
        row.appendChild(text)
        val verb = when (line.action) {
            MemberAction.PEEK -> "peek"
            MemberAction.JUMP -> "jump"
            MemberAction.OPEN -> "open"
            MemberAction.NONE -> null
        }
        if (verb != null) {
            val button = document.createElement("button") as HTMLElement
            button.asDynamic().type = "button"
            button.className = "tp-verb"
            button.textContent = verb
            button.addEventListener("click", { _: Event -> GlobalScope.launch { taskPanel.activateMember(line) } })
            row.appendChild(button)
        }
        block.appendChild(row)
    }
    return block
}

/** One stored block: its title, its body and who wrote it and when. */
@OptIn(DelicateCoroutinesApi::class)
private fun buildBlock(view: TaskPanelViewModel.BlockView): HTMLElement {
    val block = div("tp-block")
    view.title?.let { block.appendChild(div("tp-block-title").also { t -> t.textContent = it }) }
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
    block.appendChild(div("tp-byline").also { it.textContent = view.byline })
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

// ---- peek --------------------------------------------------------------------------------------

/** Open, replace or close the peek so it matches [peek]. */
private fun syncPeek(peek: TaskPanelViewModel.PanelPeek?) {
    if (peek == null) {
        closePeekView(restoreFocus = false)
        return
    }
    if (openPeek?.recordId == peek.recordId) return
    closePeekView(restoreFocus = false)
    openPeekView(peek)
}

/**
 * Cover the current pane with a read-only live copy of a background member's terminal.
 *
 * The copy is a terminal with input switched off, fed by its own viewer socket. Nothing is ever
 * sent on that socket: no keys, no size. The grid follows the size the server reports, and only
 * the font is fitted to the room. A key listener on the window, in the capture phase, takes every
 * key while the peek shows: ⌘↵ takes the tab over, esc goes back, the rest is dropped.
 */
@OptIn(DelicateCoroutinesApi::class)
private fun openPeekView(peek: TaskPanelViewModel.PanelPeek) {
    val body = document.querySelector(".${AppFrameClassNames.BODY}") as? HTMLElement ?: return
    val returnFocus = document.activeElement as? HTMLElement
    val root = div("tp-peek")
    val bar = div("tp-peek-bar")
    bar.appendChild(span("tp-peek-live", "◉ ${peek.title} · read-only"))
    val keys = if (peek.canTakeOver) "⌘↵ take over · esc back" else "esc back"
    bar.appendChild(span("tp-peek-keys", keys))
    val screen = div("tp-peek-screen")
    root.appendChild(bar)
    root.appendChild(screen)
    body.appendChild(root)
    placePeek(root, body)

    var term: Terminal? = null
    var socket: WebSocket? = null
    if (peek.sessionId != null && !isDemoClient) {
        val t = Terminal(
            json(
                "cursorBlink" to false, "disableStdin" to true,
                "fontFamily" to "Menlo, Monaco, 'Courier New', monospace",
                "fontSize" to 12, "scrollback" to 400, "theme" to runCatching { buildXtermTheme() }.getOrNull(),
            ),
        )
        t.open(screen)
        (t.asDynamic().textarea as? HTMLElement)?.blur()
        // Its own query: a viewer never governs the size, so the server never waits on us for one.
        val query = "auth=" + encodeUriComponent(authTokenForSending()) +
            "&clientType=" + encodeUriComponent(clientTypeAtStart) + "&posture=viewer"
        val s = WebSocket("$proto://$backendHost/pty/${peek.sessionId}?$query")
        s.asDynamic().binaryType = "arraybuffer"
        s.onmessage = { event ->
            val data = event.asDynamic().data
            if (data is String) {
                runCatching {
                    when (val msg = windowJson.decodeFromString<PtyServerMessage>(data)) {
                        is PtyServerMessage.Size -> {
                            if (msg.cols > 0 && msg.rows > 0) {
                                runCatching { t.asDynamic().resize(msg.cols, msg.rows) }
                                fitPeekFont(t, screen, msg.cols, msg.rows)
                            }
                        }
                        is PtyServerMessage.Governance -> Unit
                    }
                }
            } else {
                t.write(Uint8Array(data as ArrayBuffer))
            }
        }
        term = t
        socket = s
    } else {
        screen.appendChild(div("tp-muted").also { it.textContent = "There is no screen to show for this agent." })
    }

    val handler: (Event) -> Unit = { e ->
        val key = e as KeyboardEvent
        e.stopImmediatePropagation()
        e.preventDefault()
        if (key.type == "keydown") {
            when {
                key.key == "Escape" -> {
                    taskPanel.closePeek()
                    closePeekView(restoreFocus = true)
                }
                key.key == "Enter" && (key.metaKey || key.ctrlKey) && peek.canTakeOver ->
                    GlobalScope.launch { taskPanel.takeOver() }
            }
        }
    }
    window.addEventListener("keydown", handler, true)
    window.addEventListener("keypress", handler, true)
    window.addEventListener("keyup", handler, true)
    val onResize: (Event) -> Unit = { placePeek(root, body) }
    window.addEventListener("resize", onResize)
    resizeHandler = onResize
    openPeek = OpenPeek(peek.recordId, root, term, socket, handler, returnFocus)
}

/** Put the peek over the area left of the column. */
private fun placePeek(root: HTMLElement, body: HTMLElement) {
    val rect = body.getBoundingClientRect()
    root.style.left = "${rect.left}px"
    root.style.top = "${rect.top}px"
    root.style.height = "${rect.height}px"
    root.style.width = "${(rect.width - PANEL_WIDTH_PX).coerceAtLeast(0.0)}px"
}

/**
 * Pick the largest font at which the whole server grid is drawn inside the peek. Only the font
 * changes; the grid stays what the server says.
 */
private fun fitPeekFont(term: Terminal, screen: HTMLElement, cols: Int, rows: Int) {
    val width = screen.clientWidth
    val height = screen.clientHeight
    if (width <= 0 || height <= 0) return
    fun metrics(px: Int): CellMetrics {
        term.options.fontSize = px
        runCatching { term.asDynamic()._core._charSizeService.measure() }
        val cell = term.asDynamic()._core?._renderService?.dimensions?.css?.cell
        val cw = (cell?.width as? Number)?.toFloat() ?: (px * 0.6f)
        val ch = (cell?.height as? Number)?.toInt() ?: (px * 1.25).toInt()
        return CellMetrics(cw, ch, 0)
    }
    val byWidth = MirrorFit.solveFitWidthFont(width, cols, PEEK_MIN_FONT_PX, PEEK_MAX_FONT_PX, ::metrics)
    val byHeight = MirrorFit.solveFillHeightFont(height, rows, PEEK_MIN_FONT_PX, PEEK_MAX_FONT_PX, ::metrics)
    term.options.fontSize = minOf(byWidth, byHeight)
}

/**
 * Take the peek down: stop the key listener, close the socket with the terminal and remove the
 * overlay.
 *
 * @param restoreFocus whether to give focus back to what had it before the peek opened.
 */
private fun closePeekView(restoreFocus: Boolean) {
    val peek = openPeek ?: return
    openPeek = null
    window.removeEventListener("keydown", peek.keyHandler, true)
    window.removeEventListener("keypress", peek.keyHandler, true)
    window.removeEventListener("keyup", peek.keyHandler, true)
    resizeHandler?.let { window.removeEventListener("resize", it) }
    resizeHandler = null
    peek.socket?.let { s ->
        s.onmessage = null
        runCatching { s.close() }
    }
    peek.term?.let { runCatching { it.dispose() } }
    peek.root.remove()
    if (restoreFocus) peek.returnFocus?.takeIf { it.isConnected }?.focus()
}
