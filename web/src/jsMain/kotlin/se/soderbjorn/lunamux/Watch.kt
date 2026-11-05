/**
 * The watch in the web client: a read-only live copy of a background agent's terminal, laid over
 * the pane the person is looking at. The task panel opens it from an agent's line; any other list
 * of agents opens the same one through [watchViewModel], so there is one view, one header and one
 * set of keys.
 *
 * The copy is a terminal with input switched off, fed by its own viewer socket. Nothing is ever
 * sent on that socket: no keys, no size. The grid follows the size the server reports, and only
 * the font is fitted to the room. While it shows, a capture-phase key listener on the window takes
 * every key: ⌘↵ shows the agent's tab as an ordinary tab, esc goes back, the rest is dropped.
 *
 * State and behaviour live in the shared [WatchViewModel]; this file draws its state and turns
 * keys into calls on it. It follows the view model alone, so it needs no task panel to be present.
 *
 * @see WatchViewModel
 * @see startWatch
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLElement
import org.w3c.dom.ParentNode
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunamux.client.CellMetrics
import se.soderbjorn.lunamux.client.MirrorFit
import se.soderbjorn.lunamux.client.viewmodel.WatchViewModel
import se.soderbjorn.lunula.web.shell.AppFrameClassNames
import kotlin.js.json

/** Smallest and largest font size the watch is drawn at. */
private const val WATCH_MIN_FONT_PX = 6
private const val WATCH_MAX_FONT_PX = 14

/**
 * Attribute that gives an element a stable identity across redraws. A list that rebuilds its rows
 * puts it on the ones a person can focus, so focus can be handed back to the row that is drawn
 * again after the watch closes.
 */
internal const val FOCUS_KEY_ATTRIBUTE = "data-focus-key"

/**
 * The shared view model of the watch; created on first use, after the window socket exists. Every
 * surface that starts a watch calls [WatchViewModel.watch] on this one instance.
 */
internal val watchViewModel: WatchViewModel by lazy {
    WatchViewModel(lunamuxClient.windowState, send = { windowSocket.send(it) })
}

/**
 * Everything that belongs to the open watch, so it can be taken down in one go.
 *
 * @property recordId the session record being watched; a different one means a new watch.
 * @property root the overlay element.
 * @property head the header element, whose words follow [WatchViewModel.Watch.header].
 * @property term the read-only terminal, or null when there is no session to mirror.
 * @property socket the viewer socket, or null.
 * @property keyHandler the capture-phase key listener installed on the window.
 * @property stopPlacing stops the loop that keeps the overlay over its pane.
 * @property returnFocus the element that had focus before the watch opened.
 */
private class OpenWatch(
    val recordId: String,
    val root: HTMLElement,
    val head: HTMLElement,
    val term: Terminal?,
    val socket: WebSocket?,
    val keyHandler: (Event) -> Unit,
    val stopPlacing: () -> Unit,
    val returnFocus: HTMLElement?,
)

private var openWatch: OpenWatch? = null
private var watchStarted = false

/**
 * Start drawing the watch: from here on the overlay follows [watchViewModel], opening when a
 * watch is started anywhere and going away when it is closed or its tab or session goes. Called
 * once by [bootViaToolkitShell] after the shell is mounted, so the overlay has a parent to go into.
 *
 * @see syncWatch
 */
@OptIn(DelicateCoroutinesApi::class)
internal fun startWatch() {
    if (watchStarted) return
    watchStarted = true
    GlobalScope.launch { watchViewModel.stateFlow.collect { syncWatch(it) } }
}

/** Open, update or close the overlay so it matches [watch]. */
private fun syncWatch(watch: WatchViewModel.Watch?) {
    if (watch == null) {
        closeWatchView(restoreFocus = false)
        return
    }
    val current = openWatch
    if (current?.recordId == watch.recordId) {
        current.head.textContent = watch.header
        return
    }
    closeWatchView(restoreFocus = false)
    openWatchView(watch)
}

/**
 * Cover the current pane with a read-only live copy of a background agent's terminal. The header
 * reads [WatchViewModel.Watch.header] and the agent's name sits beside it with the key that goes back.
 *
 * @param watch the watch to draw.
 */
@OptIn(DelicateCoroutinesApi::class)
private fun openWatchView(watch: WatchViewModel.Watch) {
    val body = document.querySelector(".${AppFrameClassNames.BODY}") as? HTMLElement ?: return
    val returnFocus = document.activeElement as? HTMLElement
    val root = watchDiv("watch")
    val bar = watchDiv("watch-bar")
    val head = watchDiv("watch-head").also { it.textContent = watch.header }
    bar.appendChild(head)
    val who = watchDiv("watch-who")
    who.appendChild(watchDiv("watch-who-title").also {
        it.textContent = watch.title
        it.title = watch.title
    })
    who.appendChild(watchDiv("watch-who-esc").also { it.textContent = " · esc back" })
    bar.appendChild(who)
    val screen = watchDiv("watch-screen")
    root.appendChild(bar)
    root.appendChild(screen)
    body.appendChild(root)
    placeWatch(root, body)

    var term: Terminal? = null
    var socket: WebSocket? = null
    if (watch.sessionId != null && !isDemoClient) {
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
        val s = WebSocket("$proto://$backendHost/pty/${watch.sessionId}?$query")
        s.asDynamic().binaryType = "arraybuffer"
        s.onmessage = { event ->
            val data = event.asDynamic().data
            if (data is String) {
                runCatching {
                    when (val msg = windowJson.decodeFromString<PtyServerMessage>(data)) {
                        is PtyServerMessage.Size -> {
                            if (msg.cols > 0 && msg.rows > 0) {
                                runCatching { t.asDynamic().resize(msg.cols, msg.rows) }
                                fitWatchFont(t, screen, msg.cols, msg.rows)
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
        screen.appendChild(watchDiv("watch-empty").also { it.textContent = "There is no screen to show for this agent." })
    }

    val handler: (Event) -> Unit = { e ->
        val key = e as KeyboardEvent
        e.stopImmediatePropagation()
        e.preventDefault()
        if (key.type == "keydown") {
            when {
                key.key == "Escape" -> {
                    watchViewModel.close()
                    closeWatchView(restoreFocus = true)
                }
                key.key == "Enter" && (key.metaKey || key.ctrlKey) ->
                    GlobalScope.launch { watchViewModel.showAsTab() }
            }
        }
    }
    window.addEventListener("keydown", handler, true)
    window.addEventListener("keypress", handler, true)
    window.addEventListener("keyup", handler, true)
    openWatch = OpenWatch(watch.recordId, root, head, term, socket, handler, keepWatchPlaced(root, body), returnFocus)
}

/**
 * Keep the watch over its pane for as long as it is open. The pane's rectangle changes without any
 * event the watch could listen to (the task panel closes, the sidebar toggles, a pane is split), so
 * the rectangle is read every frame and the overlay is moved only when it differs.
 *
 * @param root the overlay element.
 * @param body the app frame body, used when no pane has focus.
 * @return a function that stops the loop; called when the watch is taken down.
 * @see placeWatch
 */
private fun keepWatchPlaced(root: HTMLElement, body: HTMLElement): () -> Unit {
    var stopped = false
    var frame = 0
    lateinit var tick: (Double) -> Unit
    tick = {
        if (!stopped) {
            placeWatch(root, body)
            frame = window.requestAnimationFrame(tick)
        }
    }
    frame = window.requestAnimationFrame(tick)
    return {
        stopped = true
        window.cancelAnimationFrame(frame)
    }
}

/**
 * Put the watch over the pane the person is looking at: the focused pane's own rectangle, cut off
 * where the task panel starts. With no focused pane it covers the body up to the panel, so the
 * watch never lies over the panel or over a sidebar that sits in the body beside it.
 *
 * @param root the overlay element.
 * @param body the app frame body, used when no pane has focus.
 */
private fun placeWatch(root: HTMLElement, body: HTMLElement) {
    val area = (document.querySelector(".dt-pane.dt-pane-focused") as? HTMLElement ?: body).getBoundingClientRect()
    val limit = (document.querySelector(".task-panel") as? HTMLElement)?.getBoundingClientRect()?.left ?: area.right
    val right = minOf(area.right, limit)
    val left = "${area.left}px"
    val top = "${area.top}px"
    val height = "${area.height}px"
    val width = "${(right - area.left).coerceAtLeast(0.0)}px"
    // Written only when it differs, so a steady layout costs two reads a frame and no style work.
    if (root.style.left != left) root.style.left = left
    if (root.style.top != top) root.style.top = top
    if (root.style.height != height) root.style.height = height
    if (root.style.width != width) root.style.width = width
}

/**
 * Pick the largest font at which the whole server grid is drawn inside the watch. Only the font
 * changes; the grid stays what the server says.
 *
 * @param term the read-only terminal.
 * @param screen the element the terminal is drawn in.
 * @param cols the server's column count.
 * @param rows the server's row count.
 */
private fun fitWatchFont(term: Terminal, screen: HTMLElement, cols: Int, rows: Int) {
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
    val byWidth = MirrorFit.solveFitWidthFont(width, cols, WATCH_MIN_FONT_PX, WATCH_MAX_FONT_PX, ::metrics)
    val byHeight = MirrorFit.solveFillHeightFont(height, rows, WATCH_MIN_FONT_PX, WATCH_MAX_FONT_PX, ::metrics)
    term.options.fontSize = minOf(byWidth, byHeight)
}

/**
 * Take the watch down: stop the key listener, close the socket with the terminal and remove the
 * overlay.
 *
 * @param restoreFocus whether to give focus back to what had it before the watch opened. A list
 *   that was redrawn meanwhile no longer holds that element, so focus goes to the element that
 *   carries the same [FOCUS_KEY_ATTRIBUTE].
 */
private fun closeWatchView(restoreFocus: Boolean) {
    val watch = openWatch ?: return
    openWatch = null
    window.removeEventListener("keydown", watch.keyHandler, true)
    window.removeEventListener("keypress", watch.keyHandler, true)
    window.removeEventListener("keyup", watch.keyHandler, true)
    watch.stopPlacing()
    watch.socket?.let { s ->
        s.onmessage = null
        runCatching { s.close() }
    }
    watch.term?.let { runCatching { it.dispose() } }
    watch.root.remove()
    if (restoreFocus) watch.returnFocus?.let(::redrawnTwinOf)?.focus()
}

/**
 * The element to give focus back to: [element] itself while it is still on the page, else the one
 * drawn in its place that carries the same [FOCUS_KEY_ATTRIBUTE], else null.
 *
 * @param element the element that had focus.
 */
private fun redrawnTwinOf(element: HTMLElement): HTMLElement? {
    if (element.isConnected) return element
    val key = element.getAttribute(FOCUS_KEY_ATTRIBUTE) ?: return null
    return findByFocusKey(document, key)
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

private fun watchDiv(cls: String): HTMLElement = (document.createElement("div") as HTMLElement).also { it.className = cls }
