/**
 * The question asked before a person closes a tab with an agent working in it.
 *
 * Closing a tab ends its terminals, so a conversation still working in it stops. This file draws
 * the dialog from the facts [TabCloseCheck] holds and answers a coroutine with the person's
 * choice. It reuses the toolkit's `.dt-modal*` classes so it looks like the other confirmations.
 * Focus starts on Cancel, esc cancels, and ↵ presses whichever button has focus.
 *
 * @see TabCloseCheck
 * @see closeTabFromPerson
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunamux.client.TabCloseCheck
import se.soderbjorn.lunamux.client.closeTabAsking
import kotlin.coroutines.resume

/**
 * Close [tabId] for a person: asks first when an agent is working in it, then sends the close.
 *
 * Called by the tab strip's × and the 3D view's remove. Closing a pane, and an agent closing a
 * tab through MCP, do not come through here.
 *
 * @param tabId the tab to close.
 * @return true when the close was sent, false when the person kept the tab.
 * @see closeTabAsking
 */
internal suspend fun closeTabFromPerson(tabId: String): Boolean =
    closeTabAsking(lunamuxClient.windowState, tabId, { windowSocket.send(it) }) { showTabCloseDialog(it) }

/**
 * Show the dialog for [check] and wait for the person's answer.
 *
 * Only one is shown at a time; asking again while one is open answers false at once so the first
 * stays the one in front. Cancel, esc and a click outside the card keep the tab.
 *
 * @param check what the dialog says.
 * @return true when the person chose to close the tab.
 */
internal suspend fun showTabCloseDialog(check: TabCloseCheck): Boolean {
    if (document.getElementById(DIALOG_ID) != null) return false
    return suspendCancellableCoroutine { cont ->
        val backdrop = element("div", "dt-modal-backdrop").also { it.id = DIALOG_ID }
        val card = element("div", "dt-modal")
        card.appendChild(element("h2", "dt-modal-title").also { it.textContent = check.headline })
        card.appendChild(element("p", "dt-modal-message").also { it.textContent = check.warning })
        check.keepsRunningIntro?.let { intro ->
            card.appendChild(element("p", "dt-modal-message").also { it.textContent = intro })
            val list = element("ul", "tab-close-list")
            check.keepsRunning.forEach { running ->
                list.appendChild(element("li", "").also {
                    it.textContent = "${running.title} · ${stateWords(running.state)}, ${ageWords(running.sinceMillis)}"
                })
            }
            card.appendChild(list)
        }
        check.pickUp?.let { text -> card.appendChild(element("p", "dt-modal-message").also { it.textContent = text }) }

        val buttons = element("div", "dt-modal-buttons")
        val cancel = element("button", "dt-modal-btn dt-modal-btn-cancel") as HTMLButtonElement
        cancel.type = "button"
        cancel.textContent = "Cancel"
        val close = element("button", "dt-modal-btn dt-modal-btn-confirm") as HTMLButtonElement
        close.type = "button"
        close.textContent = "Close tab"
        buttons.appendChild(cancel)
        buttons.appendChild(close)
        card.appendChild(buttons)
        backdrop.appendChild(card)
        document.body?.appendChild(backdrop)
        cancel.focus()

        var done = false
        lateinit var keys: (Event) -> Unit
        fun finish(closeIt: Boolean) {
            if (done) return
            done = true
            document.removeEventListener("keydown", keys, true)
            backdrop.parentNode?.removeChild(backdrop)
            if (cont.isActive) cont.resume(closeIt)
        }
        keys = { ev ->
            val k = ev as KeyboardEvent
            when (k.key) {
                "Escape" -> { k.preventDefault(); k.stopPropagation(); finish(false) }
                // ↵ presses the focused button; the browser would also click it, so stop it here.
                "Enter" -> {
                    k.preventDefault(); k.stopPropagation()
                    finish(document.activeElement === close)
                }
            }
        }
        // Capture phase so the terminal and the shell's shortcuts never see these keys while asking.
        document.addEventListener("keydown", keys, true)
        cancel.addEventListener("click", { finish(false) })
        close.addEventListener("click", { finish(true) })
        backdrop.addEventListener("click", { ev -> if (ev.target === backdrop) finish(false) })
        cont.invokeOnCancellation { finish(false) }
    }
}

/** The id of the dialog's backdrop, so a second question does not stack on the first. */
private const val DIALOG_ID = "tab-close-dialog"

/**
 * The state of a session in the dialog's list: `working`, `needs you` or `idle`.
 *
 * @param state the session's state as the server names it.
 * @return the words shown.
 */
private fun stateWords(state: String): String = when (state) {
    "needs_you" -> "needs you"
    else -> state
}

/**
 * How long a session has been in its state, as "now", "4 min", "3 h" or "2 d".
 *
 * @param sinceMillis epoch millis the session entered its state.
 * @return the age words.
 */
private fun ageWords(sinceMillis: Long): String {
    val seconds = ((kotlin.js.Date.now() - sinceMillis) / 1000).toLong().coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60} min"
        seconds < 86_400 -> "${seconds / 3600} h"
        else -> "${seconds / 86_400} d"
    }
}

private fun element(tag: String, className: String): HTMLElement =
    (document.createElement(tag) as HTMLElement).also { if (className.isNotEmpty()) it.className = className }
