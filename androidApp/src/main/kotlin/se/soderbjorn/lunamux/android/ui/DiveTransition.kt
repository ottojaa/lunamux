/**
 * Shared-element "dive" transition plumbing between the overview's mini panes
 * and the full-screen terminal.
 *
 * Tapping a terminal card in the overview should feel like diving into it: the
 * card's bounds morph into the terminal screen's content box while the truthful
 * thumbnail crossfades into the live [com.termux.view.TerminalView], and the
 * reverse plays on back. This file carries the two composition locals the
 * transition needs ([LunamuxApp] provides them: the app-level
 * [SharedTransitionScope] around the NavHost and each involved destination's
 * [AnimatedVisibilityScope]) and the [diveSharedBounds] modifier both ends
 * attach.
 *
 * The experimental shared-transition opt-in is deliberately confined to this
 * file and [LunamuxApp]; call sites only see a plain [Modifier] extension.
 *
 * IMPORTANT constraint: [diveSharedBounds] keeps `sharedBounds`' default
 * `resizeMode = ScaleToBounds`, which measures the content once at its final
 * bounds and only layer-scales during the flight. The terminal side wraps an
 * `AndroidView` whose relayout fires size votes to the server
 * (see `TerminalScreen`'s layout listener) — `RemeasureToBounds` would relayout
 * it every frame of the animation and must never be used here.
 *
 * @see LunamuxApp
 * @see OverviewContent
 * @see TerminalScreen
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * The app-level [SharedTransitionScope] wrapping the NavHost, or `null` when
 * the current composition is not inside one (previews, tests). Provided by
 * [LunamuxApp].
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/**
 * The nav destination's [AnimatedVisibilityScope] (nav-compose's `composable`
 * content receiver), or `null` outside a destination that provides one.
 * Provided by [LunamuxApp] for the `tree` and `terminal/{sessionId}` routes —
 * the two ends of the dive.
 */
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Shared-content key for the dive between a session's mini card and its
 * full-screen terminal. Keyed by session id; the overview attaches it only to
 * the single tapped pane (see `divePaneId` in [OverviewContent]) so linked
 * panes sharing a session can never register duplicate keys.
 *
 * @param sessionId the PTY session both ends render.
 * @return the shared-content key.
 */
fun diveKey(sessionId: String): String = "terminal-dive/$sessionId"

/**
 * Attach `sharedBounds` for [key] when composed inside a live shared-transition
 * scope AND a nav destination scope; a no-op otherwise, so call sites degrade
 * gracefully wherever the transition can't run (sidebar-originated opens, host
 * previews, tests).
 *
 * Uses the default fade crossfade and the default `ScaleToBounds` resize mode —
 * see the file header for why that default is load-bearing.
 *
 * @param key a [diveKey]; the same key must be attached on both ends.
 * @return this modifier, with `sharedBounds` appended when the scopes exist.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.diveSharedBounds(key: String): Modifier {
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val navScope = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(sharedScope) {
        this@diveSharedBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = navScope,
        )
    }
}
