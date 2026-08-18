/**
 * State machine for the swipe-up "return to the switcher" gesture.
 *
 * From a full-screen terminal, dragging the grab handle upward shrinks the
 * live screen toward the switcher's centered-card geometry, tracking the
 * finger (the OS app-switcher gesture, replicated in-app). Releasing past the
 * threshold — or flinging up — commits: the app pops back to the tree route's
 * card overview, with a last-frame thumbnail card covering the route swap.
 * Releasing early springs back to the full-screen terminal, which never
 * stopped rendering.
 *
 * This class owns the pure gesture state: mode, the 0→1 progress
 * ([progress]; 0 = full screen, 1 = card rect), the commit thresholds and
 * springs, and the handoff flags the host reads. All drawing — the scrim, the
 * NavHost's graphicsLayer transform, the thumbnail card — lives in
 * [LunamuxApp]'s overlay; the entry affordances live in `TerminalScreen`
 * (grab handle + app-bar button). Everything here animates layers only: the
 * live terminal is never relaid out (a relayout would fire its size vote to
 * the server — see `TerminalScreen`'s layout listener).
 *
 * @see LunamuxApp
 * @see SwitcherGrabHandle
 * @see OverviewContent
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Fraction of the screen height a drag must cover to reach progress 1. */
internal const val RETURN_COMMIT_DISTANCE_FRACTION = 0.35f

/** Upward fling velocity (px/s, negative dy) that commits regardless of progress. */
private const val COMMIT_VELOCITY_PX_PER_S = 2400f

/**
 * The phases of the return gesture.
 */
enum class ReturnMode {
    /** No gesture in flight; the overlay is not composed. */
    Idle,

    /** The finger is down: progress tracks the drag 1:1 with rubber-banding. */
    Dragging,

    /**
     * The gesture committed: the thumbnail card owns the screen while the
     * route pops underneath, then the overlay fades out.
     */
    Committing,
}

/**
 * CompositionLocal exposing the app's [ReturnGestureState], or `null` when the
 * host doesn't provide one (previews, tests). Provided by [LunamuxApp];
 * consumed by `TerminalScreen` (entries) and [OverviewContent] (arrival
 * centering).
 */
val LocalReturnGesture = compositionLocalOf<ReturnGestureState?> { null }

/**
 * Owns the swipe-up return gesture's progress and choreography.
 *
 * @param scope           the app-level coroutine scope animations run in.
 * @param onCommitNavigate pops navigation back to the tree route with nav
 *   transitions suppressed; invoked exactly once per committed gesture, after
 *   the thumbnail card is already covering the screen.
 */
class ReturnGestureState(
    private val scope: CoroutineScope,
    private val onCommitNavigate: () -> Unit,
) {
    /** Current phase; drives what [LunamuxApp]'s overlay composes. */
    var mode by mutableStateOf(ReturnMode.Idle)
        private set

    /**
     * Gesture progress: 0 = full screen, 1 = the switcher's centered-card rect.
     * Reads are state-backed, so graphicsLayer blocks can consume it at draw
     * time without recomposition.
     */
    val progress = Animatable(0f)

    /** Overlay opacity for the post-pop fade-out of the thumbnail card + scrim. */
    val overlayAlpha = Animatable(1f)

    /** The session the gesture started from; keys the thumbnail card's frame. */
    var originSessionId: String? = null
        private set

    /**
     * True once [onCommitNavigate] has run for the in-flight commit: the
     * NavHost must render at identity (the overview is composing underneath
     * the overlay card).
     */
    var poppedUnderOverlay by mutableStateOf(false)
        private set

    /** Raw (un-rubber-banded) drag progress since the gesture began. */
    private var rawDrag = 0f

    /** Session the overview should center on arrival; consumed once. */
    private var pendingCenterSessionId: String? = null

    private var settleJob: Job? = null

    /**
     * Take (and clear) the session id the overview should center on. Called by
     * [OverviewContent] when it composes after a committed return.
     *
     * @return the origin session id, or `null` when no return is pending.
     */
    fun consumePendingCenterSession(): String? {
        val value = pendingCenterSessionId
        pendingCenterSessionId = null
        return value
    }

    /**
     * Begin tracking a drag from the terminal for [sessionId]. No-op while a
     * commit is still playing out.
     *
     * @param sessionId the session the gesture starts from.
     */
    fun beginDrag(sessionId: String) {
        if (mode == ReturnMode.Committing) return
        settleJob?.cancel()
        originSessionId = sessionId
        rawDrag = progress.value
        mode = ReturnMode.Dragging
    }

    /**
     * Track a drag delta.
     *
     * @param dyPx             the vertical pointer delta in px (negative = up).
     * @param commitDistancePx the drag distance mapping to progress 1
     *   (0.35 × screen height; see [RETURN_COMMIT_DISTANCE_FRACTION]).
     */
    fun dragBy(dyPx: Float, commitDistancePx: Float) {
        if (mode != ReturnMode.Dragging) return
        rawDrag = (rawDrag - dyPx / commitDistancePx).coerceAtLeast(0f)
        val shown = rubberBand(rawDrag)
        scope.launch { progress.snapTo(shown) }
    }

    /**
     * Finish the drag: commit past half progress or on an upward fling, cancel
     * on a downward fling or an early release.
     *
     * @param velocityPxPerS release velocity (negative = upward).
     */
    fun endDrag(velocityPxPerS: Float) {
        if (mode != ReturnMode.Dragging) return
        val flingUp = velocityPxPerS <= -COMMIT_VELOCITY_PX_PER_S
        val flingDown = velocityPxPerS >= COMMIT_VELOCITY_PX_PER_S
        when {
            flingDown -> cancel()
            flingUp || progress.value >= 0.5f -> commit()
            else -> cancel()
        }
    }

    /**
     * Open the switcher without a drag (the app-bar button, or a tap on the
     * grab handle): run the full commit choreography from progress 0.
     *
     * @param sessionId the session the return starts from.
     */
    fun open(sessionId: String) {
        if (mode == ReturnMode.Committing) return
        settleJob?.cancel()
        originSessionId = sessionId
        mode = ReturnMode.Dragging
        commit()
    }

    /**
     * Commit: hand the screen to the thumbnail card, pop underneath, settle
     * the card into the centered-card rect, fade out, reset.
     */
    private fun commit() {
        mode = ReturnMode.Committing
        pendingCenterSessionId = originSessionId
        settleJob = scope.launch {
            // The card (drawn by LunamuxApp while Committing) now covers the
            // live screen at identical geometry, so the route swap under it is
            // invisible. Pop first, then settle: the overview gets the whole
            // settle+fade duration to compose and paint its cached thumbnails.
            poppedUnderOverlay = true
            onCommitNavigate()
            progress.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 380f))
            overlayAlpha.animateTo(0f, tween(durationMillis = 120))
            progress.snapTo(0f)
            overlayAlpha.snapTo(1f)
            poppedUnderOverlay = false
            originSessionId = null
            rawDrag = 0f
            mode = ReturnMode.Idle
        }
    }

    /** Spring back to the full-screen terminal; nothing was navigated. */
    private fun cancel() {
        settleJob = scope.launch {
            progress.animateTo(0f, spring(dampingRatio = 1f, stiffness = 600f))
            rawDrag = 0f
            originSessionId = null
            mode = ReturnMode.Idle
        }
    }

    /**
     * Soft-clamp drag progress past 1 so overshoot reads as resistance, not
     * motion: an asymptote at 1.15.
     *
     * @param raw the unclamped progress.
     * @return the displayed progress.
     */
    private fun rubberBand(raw: Float): Float =
        if (raw <= 1f) raw else 1f + 0.15f * (raw - 1f) / (1f + (raw - 1f))
}
