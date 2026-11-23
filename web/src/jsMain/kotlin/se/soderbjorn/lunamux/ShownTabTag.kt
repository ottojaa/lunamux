/* ShownTabTag.kt (jsMain)
 * The floating tag over the window body that marks the active tab as a background agent's tab
 * the person opened from the agents overview. It says the tab hides when they leave it and ends
 * in a "← back" button that leaves it at once ([WindowCommand.LeaveShownTab]). One function,
 * [updateShownTabTag], shows, moves or removes it. */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import se.soderbjorn.lunula.web.shell.AppFrameClassNames

/** The words on the tag over a background tab that is shown for now. */
internal const val SHOWN_TAB_TAG_TEXT = "background agent · hides when you leave"

/** The button at the end of that tag, which leaves the tab ([WindowCommand.LeaveShownTab]). */
internal const val SHOWN_TAB_BACK_TEXT = "← back"

/** The tag element while it is on the page, else null. */
private var shownTabTag: HTMLElement? = null

/** The shown tab the tag's button leaves; read on the click, so a tag kept across redraws acts on the tab in front. */
private var shownTabId: String? = null

/**
 * Show, move or remove the tag. The tag floats over the top edge of the window body, centred, and
 * lets clicks through except on its button. Called by [lunamuxTabSource] each time the layout
 * arrives, so it follows the window when it resizes or the sidebar opens.
 *
 * @param tabId the active tab when it is the background tab shown for now, else null (removes the tag).
 * @see WindowConfig.shownTabId
 */
internal fun updateShownTabTag(tabId: String?) {
    val body = document.querySelector(".${AppFrameClassNames.BODY}") as? HTMLElement
    shownTabId = tabId
    if (tabId == null || body == null) {
        shownTabTag?.remove()
        shownTabTag = null
        return
    }
    val tag = shownTabTag?.takeIf { it.isConnected } ?: div("shown-tab-tag").also {
        it.appendChild(span("shown-tab-tag-text", SHOWN_TAB_TAG_TEXT))
        it.appendChild(document.createTextNode(" · "))
        val back = document.createElement("button") as HTMLElement
        back.className = "shown-tab-back"
        back.textContent = SHOWN_TAB_BACK_TEXT
        back.title = "Hide this tab and go back to where you were"
        back.addEventListener("click", { _: Event -> shownTabId?.let { id -> launchCmd(WindowCommand.LeaveShownTab(id)) } })
        it.appendChild(back)
        it.setAttribute("role", "status")
        document.body?.appendChild(it)
        shownTabTag = it
    }
    val box = body.getBoundingClientRect()
    tag.style.top = "${box.top + 6}px"
    tag.style.left = "${box.left + box.width / 2}px"
}
