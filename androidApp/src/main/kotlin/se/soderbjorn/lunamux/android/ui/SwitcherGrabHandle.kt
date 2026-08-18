/**
 * Grab handle for the swipe-up return gesture on the terminal screen.
 *
 * The vendored TerminalView consumes every touch inside its box (pan,
 * scrollback, pinch, selection), and the very bottom screen edge belongs to
 * system gesture navigation — so the in-app swipe-up needs its own small,
 * dedicated strip. This pill sits bottom-center over the terminal, above the
 * view in the compose z-order, and fully owns its touches: drag up = the
 * return gesture (tracking the finger), tap = open the switcher outright.
 *
 * Composed by `TerminalScreen` inside the terminal box (the same layering as
 * its jump-to-bottom pill), only when a [ReturnGestureState] is provided.
 *
 * @see ReturnGestureState
 * @see TerminalScreen
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * The pill affordance that starts the swipe-up return gesture.
 *
 * A 160×28dp hit area (generous for touch, small against the prompt line)
 * containing a 36×4dp pill. All callbacks are supplied by `TerminalScreen`,
 * which owns the IME hiding and the [ReturnGestureState] calls.
 *
 * @param onDragStart   the finger went down and started dragging (hide the IME
 *   here — an adjustResize inset shift mid-gesture would hop the layer).
 * @param onDrag        vertical drag delta in px (negative = up).
 * @param onDragStopped release with velocity in px/s (negative = upward fling).
 * @param onTap         plain tap: open the switcher without a drag.
 * @param modifier      alignment modifier from the caller (bottom-center).
 */
@Composable
fun SwitcherGrabHandle(
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragStopped: (Float) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(160.dp)
            .height(28.dp)
            .draggable(
                state = rememberDraggableState { delta -> onDrag(delta) },
                orientation = Orientation.Vertical,
                startDragImmediately = false,
                onDragStarted = { onDragStart() },
                onDragStopped = { velocity -> onDragStopped(velocity) },
            )
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .padding(bottom = 6.dp)
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(SidebarTextSecondary.copy(alpha = 0.6f)),
        )
    }
}
