/**
 * Runs a suspending test body to its end on the calling thread. The view models under test do all
 * their work without waiting on a clock, so the body finishes before this returns.
 */
package se.soderbjorn.lunamux.client

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** Runs [block] to completion and rethrows what it threw. */
internal fun runNow(block: suspend () -> Unit) {
    var outcome: Result<Unit>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    outcome!!.getOrThrow()
}
