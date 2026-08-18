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
 * springs, and the card rect the flight aims at ([cardBounds], reported by the
 * switcher row itself so the landing is the real card and not an estimate).
 * [returnFlight] turns progress into the one transform both moving layers use,
 * which is what makes the live→thumbnail handoff at commit invisible. All
 * drawing — the scrim, the NavHost's graphicsLayer transform, the thumbnail
 * card — lives in [LunamuxApp]'s overlay; the entry affordances live in
 * `TerminalScreen` (grab handle + app-bar button). Everything here animates
 * layers only: the live terminal is never relaid out (a relayout would fire its
 * size vote to the server — see `TerminalScreen`'s layout listener).
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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

    /** Where a resting switcher card sits, in root coordinates. */
    private var cardBoundsInRoot by mutableStateOf<Rect?>(null)

    /** Where [LunamuxApp]'s content box sits, in root coordinates. */
    private var contentOriginInRoot by mutableStateOf(Offset.Zero)

    /**
     * The switcher's centered-card rect in the coordinate space of the moving
     * layers — [LunamuxApp]'s content box — or `null` until a card row has
     * measured (a terminal opened from list view, on a cold start).
     *
     * Read by [returnFlight] as the flight's landing rect. The two halves are
     * reported from different composables, so they are combined on read rather
     * than at report time.
     */
    val cardBounds: Rect?
        get() = cardBoundsInRoot?.translate(-contentOriginInRoot)

    /**
     * Report where a resting card sits. Called by `SwitcherCardRow` on every
     * layout of the row: the switcher knows its own geometry, and having it say
     * so is what lets the flight land on the card the user is about to see
     * instead of on an estimate derived from screen chrome.
     *
     * @param boundsInRoot the centered card's rect in root coordinates.
     */
    fun reportCardBounds(boundsInRoot: Rect) {
        cardBoundsInRoot = boundsInRoot
    }

    /**
     * Report where the app's content box sits, so a card rect in root
     * coordinates can be expressed in the moving layers' own space (the content
     * box is inset from the root by the system bars).
     *
     * @param originInRoot the content box's top-left in root coordinates.
     */
    fun reportContentOrigin(originInRoot: Offset) {
        contentOriginInRoot = originInRoot
    }

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
        // [rawDrag] is up to date with the last pointer delta; progress.value
        // lags it by however many deltas the deferred snapTo has not applied
        // yet, which is exactly the batch of the frame the finger left in — so
        // a release right at the threshold would decide on stale distance.
        val shown = rubberBand(rawDrag)
        when {
            flingDown -> cancel()
            flingUp || shown >= 0.5f -> commit()
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
        // Both happen before any frame can be composed: the card (drawn by
        // LunamuxApp while Committing) takes over the screen at exactly the
        // geometry the live layer had, and the route pops underneath it with
        // nav transitions suppressed. Popping here rather than from the settle
        // coroutine keeps "Committing" and "already popped" the same fact, so
        // the overlay needs no second flag to tell them apart.
        onCommitNavigate()
        settleJob = scope.launch {
            // The overview now has the whole settle+fade duration to compose
            // and paint its cached thumbnails underneath the card.
            progress.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 380f))
            overlayAlpha.animateTo(0f, tween(durationMillis = 120))
            progress.snapTo(0f)
            overlayAlpha.snapTo(1f)
            originSessionId = null
            rawDrag = 0f
            // Nothing consumed the arrival handoff (a return into list view, or
            // a tab that has since gone): drop it rather than leave it armed for
            // whenever the overview next composes.
            pendingCenterSessionId = null
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

/**
 * The draw-time transform of one flight step: how far the full-screen content
 * has shrunk toward the card, and how far it has slid to reach the card's
 * center.
 *
 * @property scaleX       horizontal scale to apply to the full-screen layer.
 * @property scaleY       vertical scale (equal to [scaleX] only when the card
 *   shares the screen's aspect).
 * @property translationX horizontal slide, in px, applied after the scale.
 * @property translationY vertical slide, in px, applied after the scale.
 */
internal data class ReturnFlight(
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float,
)

/**
 * Interpolate the flight from "fills the screen" ([p] = 0) to "is the switcher's
 * centered card" ([p] = 1).
 *
 * Called from the `graphicsLayer` blocks of both moving layers — the live
 * shrinking screen and the thumbnail card that replaces it at commit — so the
 * handoff happens at identical bounds whatever the progress, and the settle ends
 * on the card the user is about to see rather than near it.
 *
 * @param p         gesture progress; may overshoot 1 slightly (rubber-banding).
 * @param appSize   the app content box's size in px.
 * @param cardRect  the card's rect in that box, or `null` to fall back to a
 *   centered rect at [SWITCHER_CARD_FRACTION] of the screen (no card row has
 *   measured yet — a terminal opened from list view). A rect that cannot fit in
 *   [appSize] is treated as absent: it was measured for a layout that no longer
 *   exists, e.g. before a rotation.
 * @return the transform for this step; identity when [appSize] is empty.
 */
internal fun returnFlight(p: Float, appSize: Size, cardRect: Rect?): ReturnFlight {
    if (appSize.width <= 0f || appSize.height <= 0f) return ReturnFlight(1f, 1f, 0f, 0f)
    val usable = cardRect?.takeIf {
        it.left >= 0f && it.top >= 0f && it.right <= appSize.width && it.bottom <= appSize.height
    }
    val target = usable ?: Rect(
        offset = Offset(
            appSize.width * (1f - SWITCHER_CARD_FRACTION) / 2f,
            appSize.height * (1f - SWITCHER_CARD_FRACTION) / 2f,
        ),
        size = Size(appSize.width * SWITCHER_CARD_FRACTION, appSize.height * SWITCHER_CARD_FRACTION),
    )
    return ReturnFlight(
        scaleX = 1f - p * (1f - target.width / appSize.width),
        scaleY = 1f - p * (1f - target.height / appSize.height),
        translationX = p * (target.center.x - appSize.width / 2f),
        translationY = p * (target.center.y - appSize.height / 2f),
    )
}
