/**
 * The rule that decides which mark a tab wears, kept apart from the web page so it can be tested.
 *
 * This file contains [tabMark]. The server gives each session a mark (or none); a pane header
 * wears the mark of the session running in it, and a tab wears one mark only when every marked
 * pane in it wears that same mark.
 *
 * @see se.soderbjorn.lunamux.AgentSessionSummary.mark
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.AgentSessionSummary

/**
 * The mark a tab wears: the one mark every marked pane in it shares.
 *
 * Called by the web client's tab strip for each tab. Only open sessions count (an ended record
 * keeps its pane id, but the pane may since have been reused), and only sessions that have a
 * mark. Marks are compared, not thread keys, because what the tab draws must be what its panes
 * draw: a session in a task wears the task's mark, which can differ from the mark of the thread
 * it runs in, and two threads of one task both wear the task's mark.
 *
 * @param sessions every session the server reports.
 * @param leafIds the layout leaf ids of the tab's panes.
 * @return the shared mark, or null when no pane has one or the panes wear two or more.
 * @see se.soderbjorn.lunamux.AgentSessionSummary.leafId
 */
fun tabMark(sessions: Collection<AgentSessionSummary>, leafIds: Collection<String>): Int? =
    sessions
        .filter { it.leafId in leafIds && it.state != "ended" }
        .mapNotNull { it.mark }
        .toSet()
        .singleOrNull()
