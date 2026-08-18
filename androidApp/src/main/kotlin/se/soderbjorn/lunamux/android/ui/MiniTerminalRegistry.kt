/**
 * Overview-scoped registry of live, read-only terminal miniatures.
 *
 * The overview shows a terminal thumbnail per visible pane. Opening (and
 * closing) a PTY socket from each thumbnail's own composition lifecycle made
 * rendering unpredictable: switching tabs tore down every socket and rebuilt
 * them on return, and the rapid close→reopen to the same session raced with the
 * server's attach/detach handling, so thumbnails came up blank.
 *
 * This registry decouples the PTY socket + headless emulator lifecycle from the
 * pane composables. It opens at most one socket per session id, keeps it alive
 * for as long as the overview is on screen (across tab switches and
 * recompositions), and exposes each session's screen as a [StateFlow] of
 * [TerminalFrame] snapshots — the resolved colored grid at the server's own
 * cols×rows, published at most every [THUMB_FRAME_MIN_INTERVAL_MS] ms. A
 * thumbnail composable simply collects that flow, so leaving and re-entering a
 * tab re-attaches to an already-populated emulator and renders instantly — no
 * reconnect, no churn. Panes that share a session (linked views) share a
 * single socket.
 *
 * The registry is created by [OverviewContent], provided via
 * [LocalMiniTerminalRegistry], and [close]d when the overview leaves
 * composition. [OverviewContent] also pushes the resolved theme's default
 * colors through [setDefaultColors] — the headless emulators otherwise keep
 * the Termux stock palette for the default fg/bg/cursor slots, which the
 * full-screen terminal overrides via `applyTerminalColors`.
 *
 * Read-only invariant: like [MiniTerminalPane], entries never call
 * [se.soderbjorn.lunamux.client.PtySocket.resize]/`send`, so a thumbnail can
 * never shrink the real PTY for other clients.
 *
 * @see MiniTerminalPane
 * @see TerminalFrame
 * @see OverviewContent
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import se.soderbjorn.lunamux.client.PtyEvent
import se.soderbjorn.lunamux.client.PtySocket
import se.soderbjorn.lunamux.client.LunamuxClient

/**
 * Minimum interval between published frames per session (~10 fps). Bursty
 * output coalesces in the conflated dirty channel; the first event after an
 * idle stretch snapshots immediately.
 */
private const val THUMB_FRAME_MIN_INTERVAL_MS = 100L

/**
 * CompositionLocal exposing the active [MiniTerminalRegistry], or `null` when
 * not inside an overview that provides one.
 */
val LocalMiniTerminalRegistry = compositionLocalOf<MiniTerminalRegistry?> { null }

/**
 * Holds one live emulator + PTY socket per session id for the lifetime of the
 * overview.
 *
 * @param client the connected client used to open PTY sockets.
 * @param scope  the overview-scoped coroutine scope; collectors run here and are
 *   cancelled when the overview leaves composition (also explicitly torn down by
 *   [close]).
 */
class MiniTerminalRegistry(
    private val client: LunamuxClient,
    private val scope: CoroutineScope,
) {
    /**
     * A single live miniature: the PTY socket, its externally-fed emulator, the
     * single-thread dispatcher serialising emulator access, the collector +
     * publisher jobs, the dirty signal between them, and the published frames.
     */
    private class Entry(
        val socket: PtySocket,
        val emulator: TerminalEmulator,
        val dispatcher: kotlinx.coroutines.ExecutorCoroutineDispatcher,
        val job: Job,
        val publishJob: Job,
        val dirty: Channel<Unit>,
        val frame: MutableStateFlow<TerminalFrame?>,
    )

    private val lock = Any()
    private val entries = HashMap<String, Entry>()
    private var closed = false

    /**
     * The resolved theme's default fg/bg/cursor, applied to every entry's
     * emulator (see [setDefaultColors]). Null until the theme first resolves;
     * entries created before that keep the Termux defaults until it lands.
     */
    @Volatile
    private var defaultColors: IntArray? = null

    /**
     * Set the default foreground/background/cursor colors on every live
     * emulator (present and future) — the same three palette slots the
     * full-screen terminal overrides via `applyTerminalColors` — and republish
     * each session's frame so already-rendered thumbnails repaint.
     *
     * Called by [OverviewContent] whenever the resolved theme changes.
     *
     * @param fg     resolved ARGB default foreground.
     * @param bg     resolved ARGB default background.
     * @param cursor resolved ARGB cursor color.
     */
    fun setDefaultColors(fg: Int, bg: Int, cursor: Int) {
        val trio = intArrayOf(fg, bg, cursor)
        defaultColors = trio
        val snapshot = synchronized(lock) { entries.values.toList() }
        for (entry in snapshot) {
            scope.launch {
                withContext(entry.dispatcher) {
                    synchronized(entry.emulator) { applyDefaultColors(entry.emulator, trio) }
                }
                entry.dirty.trySend(Unit)
            }
        }
    }

    /**
     * Return the frame flow for [sessionId], creating and starting the
     * underlying socket + emulator on first request. Subsequent calls (and
     * other panes sharing the session) get the same flow.
     *
     * @param sessionId the PTY session to mirror.
     * @return a hot [StateFlow] of resolved screen snapshots; `null` until the
     *   first frame is published after attach.
     */
    fun frameFor(sessionId: String): StateFlow<TerminalFrame?> = synchronized(lock) {
        entries.getOrPut(sessionId) { createEntry(sessionId) }.frame.asStateFlow()
    }

    /**
     * Build and start a live entry for [sessionId]: open the socket, wire a
     * headless emulator, collect size + output events, and publish throttled
     * [TerminalFrame] snapshots.
     */
    private fun createEntry(sessionId: String): Entry {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val socket = client.openPtySocket(sessionId)
        // No view backs a registry emulator; the ref stays null. The session never votes a
        // size on its own (only the layout listener in TerminalScreen does, and there is no
        // view here), and a thumbnail never takes input, so its take-over gate is a no-op.
        val viewRef = mutableStateOf<TerminalView?>(null)
        val session = createExternalTerminalSession(
            scope = scope,
            emulatorDispatcher = dispatcher,
            terminalViewRef = viewRef,
            ptySocket = socket,
            // No view drives updateSize here, so the pin is never read; the
            // thumbnail sizes its emulator directly in the Size collector below.
            // A thumbnail never takes input, so the input handler is a no-op.
            serverGridPin = java.util.concurrent.atomic.AtomicReference(null),
            handleInput = {},
        )
        val emulator = createSyncedEmulator(session)
        val frame = MutableStateFlow<TerminalFrame?>(null)
        val dirty = Channel<Unit>(Channel.CONFLATED)
        val revision = AtomicLong(0)

        // Theme the fresh emulator if the resolved theme already landed; a
        // theme arriving later reaches it via setDefaultColors.
        defaultColors?.let { trio ->
            scope.launch {
                withContext(dispatcher) { synchronized(emulator) { applyDefaultColors(emulator, trio) } }
                dirty.trySend(Unit)
            }
        }

        // One ordered collector: size, output and reconnect resets applied to
        // the headless preview emulator in the order the server produced them
        // (the old split size/output collectors could interleave and mangle the
        // thumbnail's wrap). A thumbnail never votes a size, so this only reads.
        val job = scope.launch {
            socket.events.collect { ev ->
                withContext(dispatcher) {
                    synchronized(emulator) {
                        when (ev) {
                            is PtyEvent.Size ->
                                // The thumbnail passes no grid on connect, so the
                                // server synthesizes the redraw at the PTY dims — the
                                // thumbnail just renders at exactly that width.
                                runCatching {
                                    emulator.resize(ev.cols, ev.rows, 1, 1)
                                }
                            is PtyEvent.Bytes -> emulator.append(ev.data, ev.data.size)
                            // A thumbnail renders at whatever width the server sends and
                            // never votes, so it is never the governor and has nothing to
                            // change when governance moves.
                            is PtyEvent.Governance -> Unit
                            PtyEvent.Reset -> {
                                val ris = byteArrayOf(0x1b, 'c'.code.toByte())
                                emulator.append(ris, ris.size)
                            }
                        }
                    }
                }
                dirty.trySend(Unit)
            }
        }

        // Throttled publisher: one snapshot per dirty signal, then a floor
        // interval so a byte storm coalesces instead of snapshotting per event.
        // The snapshot runs on the same dispatcher (and under the same lock) as
        // the mutations above, so the published DTO is a consistent copy.
        val publishJob = scope.launch {
            for (unit in dirty) {
                frame.value = withContext(dispatcher) {
                    synchronized(emulator) { snapshotFrame(emulator, revision.incrementAndGet()) }
                }
                delay(THUMB_FRAME_MIN_INTERVAL_MS)
            }
        }
        return Entry(socket, emulator, dispatcher, job, publishJob, dirty, frame)
    }

    /**
     * Tear down every live entry: cancel collectors and publishers, close
     * sockets (detached so the close reaches the server even as the overview's
     * scope unwinds), and release the dispatchers. Idempotent. Called by
     * [OverviewContent] on dispose.
     */
    fun close() {
        val toClose = synchronized(lock) {
            if (closed) return
            closed = true
            val snapshot = entries.values.toList()
            entries.clear()
            snapshot
        }
        for (entry in toClose) {
            entry.job.cancel()
            entry.publishJob.cancel()
            entry.dirty.close()
            entry.socket.closeDetached()
            runCatching { entry.dispatcher.close() }
        }
    }
}

/**
 * Write the resolved theme's default fg/bg/cursor into [emulator]'s color
 * table — the exact mutation `applyTerminalColors` performs for the
 * full-screen view's emulator. Must be called on the emulator's dispatcher
 * under its lock.
 *
 * @param emulator the headless emulator to theme.
 * @param trio     `[fg, bg, cursor]` resolved ARGB values.
 */
private fun applyDefaultColors(emulator: TerminalEmulator, trio: IntArray) {
    emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = trio[0]
    emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = trio[1]
    emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = trio[2]
}
