# Where a session is, who started it, and a list that holds still

The agents overview answers "where does all my work stand?" at a glance. Four things were still
missing from a row: where its pane is, who started it, which words of its title are the part that
differs from its siblings, and a stable position. The row that needs you also looked like any other
apart from its glyph. This page describes what each row now says and how.

All pictures come from the seeded dev server (`scripts/seed-agents-overview.sh`, then seven live
sessions opened on it), in [sigil-cues-2026-11-03/](sigil-cues-2026-11-03/).

## What a row says now

| Cue | Reads | Where it comes from |
|---|---|---|
| **Who** (a column before the status) | `❯` you typed the line that started the session; `⚙` a session or an MCP tool did, and a task file's members; nothing for a record from before this was stored | `started_by` on the session, from `TermSession.inputOrigin` |
| **Place** (before a live row's status) | `1▘` is tab 1, top-left pane; `2` is a pane that fills tab 2 | the pane's tab number and rectangle, worked out by the web client |
| **Dim prefix** | the leading words a row shares with another row of the same thread are drawn dim, the rest bold | the title and the thread key |
| **Order** | NOW and a task's members are in the order their work started, and never move when a state changes | the session's creation time |
| **The ◆ row** | amber tint and an amber bar, the loudest row on screen; it is the row selected when the overview opens | the needs-you state |
| **Footer** | `mark = one thread of work · ❯ you started it · ⚙ a session did · 1▘ tab 1, top-left pane` | fixed text, then the key hints |

### Who started a session

Every terminal session remembers whether the last line that was submitted into it came from a person
or from a tool (`InputOrigin`). The agent tracker copies that onto the session's record as
`started_by` when the record is opened, and keeps it when the session is resumed, so a resumed session
is credited to whoever started it first. The write sites:

| Path | Origin |
|---|---|
| bytes arriving on the `/pty` socket (the person typing in a pane) | user |
| the open-a-tab route's command, typed for the person | user |
| MCP `send_text` with `submit`, `send_keys` with `enter`, `run_command` | tool |
| MCP `interrupt` | tool |
| a pane the MCP `create_window` opens (before anyone types in it) | tool |
| any other new pane | user |

Apart from those, only a submitted line changes the origin: focus reports, partial typing and a
person's Ctrl-C do not, so typing half a word into a session a tool started does not take the credit.
The value read when the session opens is the origin of the last line submitted before it. A pane
opened by `run_command` without a session id is typed into by the same call, so it needs no rule of
its own.

### Place

`placeGlyph` maps a pane's rectangle in its tab (fractions of the tab's width and height) to one
glyph: nothing for a pane that fills the tab; `▌ ▐ ▀ ▄` for a half; `▘ ▝ ▖ ▗` for a quarter. Any other
shape takes the quarter its centre lies in, so a third or a floating window still gets an answer. The
tab number is the tab's position among the visible tabs of the active world, which is what the tab
strip shows. The rectangle comes from the layout the client shell holds (so a drag counts at once),
and from the geometry stored on the pane for a tab the shell holds nothing for. A pane that is
minimized has no place.

### Order

NOW, and the members of an expanded task, are in the order their work started, then by id. Before,
they were sorted by state, so a session that began to need you jumped. Now it keeps its row and
only its glyph and tint change. A task's members keep the order its file lists them in (the file is
written by another tool and says so; a member's `since` moves with its state, so it cannot order
anything), and a session of the task that only Lunamux knows follows them by start time. A task
sits where its work began: the server stamps that time the first time it sees the task (the
earliest start of the sessions it holds then, else the earliest time a member of its file gives,
else the file's modification time) and never changes it, so no state change, file write or
later match of a member to a session moves the task, and every window and a restarted server
agree. A conversation resumed after days keeps its original start, so when it re-enters NOW it
sits above the rows that began since. What NOW contains, the grace before an ended row leaves
it, and which members a collapsed task shows (those that need you or work) are unchanged.

## The pictures

The scratch setup has three tabs. Tab 1 is split left and right (`1▌`, `1▐`). Tab 2 is split into
four quarters. Tab 3 has one pane on the left and two on the right.

| Row | Who | Place | Where the pane is |
|---|---|---|---|
| Tidy the release notes | `❯` | `1▌` | tab 1, left half |
| Rewrite the changelog | `⚙` | `1▐` | tab 1, right half |
| Migrate the billing tables | `⚙` | `2▘` | tab 2, top-left |
| Review the invoice PDF layout | `❯` | `2▝` | tab 2, top-right |
| Profile the export job | `⚙` | `2▖` | tab 2, bottom-left |
| Check the staging deploy | `❯` | `2▗` | tab 2, bottom-right |
| Audit the access log | `⚙` | `3▝` | tab 3, top-right |

The panes behind those rows, one picture per tab (the shells run a plain prompt, so the pictures
carry no machine or user name). The pane header shows each row's title, so each place cue can be
checked against where that pane really sits:

| | Dark | Light |
|---|---|---|
| Tab 1, two halves | ![](sigil-cues-2026-11-03/dark-5-tab-1-panes.png) | ![](sigil-cues-2026-11-03/light-5-tab-1-panes.png) |
| Tab 2, four quarters | ![](sigil-cues-2026-11-03/dark-5-tab-2-panes.png) | ![](sigil-cues-2026-11-03/light-5-tab-2-panes.png) |
| Tab 3, one pane left, two right | ![](sigil-cues-2026-11-03/dark-5-tab-3-panes.png) | ![](sigil-cues-2026-11-03/light-5-tab-3-panes.png) |

The overview opened over tab 2, with the seven live rows in the order they started, and a zoom of
their who and place columns (`⚙` or `❯`, then the tab and the part of it):

| | Dark | Light |
|---|---|---|
| The overview | ![](sigil-cues-2026-11-03/dark-6-overview-over-tab-2.png) | ![](sigil-cues-2026-11-03/light-6-overview-over-tab-2.png) |
| Who and place, zoomed | ![](sigil-cues-2026-11-03/dark-7-place-cues-zoomed.png) | ![](sigil-cues-2026-11-03/light-7-place-cues-zoomed.png) |

| | Dark | Light |
|---|---|---|
| The ◆ row, selected | ![](sigil-cues-2026-11-03/dark-1-selected-needs-you-row.png) | ![](sigil-cues-2026-11-03/light-1-selected-needs-you-row.png) |
| The ◆ row, not selected | ![](sigil-cues-2026-11-03/dark-2-needs-you-row-unselected.png) | ![](sigil-cues-2026-11-03/light-2-needs-you-row-unselected.png) |
| Dimmed prefixes (scrolled to *Invoice export*) | ![](sigil-cues-2026-11-03/dark-3-invoice-export-dim-prefix.png) | ![](sigil-cues-2026-11-03/light-3-invoice-export-dim-prefix.png) |

*Invoice export:* is dim and the rest of each title bold; "first run against staging" and "first draft
of the spec" also dim "first", because another row of the thread starts with it too, and they keep
"run" and "draft" bold.

The footer when the panel is narrower: the legend gives way first (with an ellipsis) and the key
hints stay whole.

| 820 px | 560 px |
|---|---|
| ![](sigil-cues-2026-11-03/dark-4-footer-at-820px.png) | ![](sigil-cues-2026-11-03/dark-4-footer-at-560px.png) |

## Code

| Where | What |
|---|---|
| `server/.../TerminalSessionManager.kt`, `PtyRoutes.kt`, `WindowRoutes.kt`, `mcp/McpWriteTools.kt` | `InputOrigin`, `TermSession.noteInput`, and each write site |
| `server/.../AgentSessionTracker.kt`, `persistence/*`, `Settings.sq` | `started_by` stored on open and kept on resume; a column added to `agent_sessions`, null for older records |
| `clientServer/.../WindowEnvelope.kt` | `startedBy` and `startedAt` on the session summary (both default to null) |
| `client/.../viewmodel/SigilRows.kt` | `startOf`, `whoOf`, `dimPrefixLengths`, `placeGlyph` |
| `client/.../AgentsOverviewBackingViewModel.kt` | `Row.who`, `Row.place`, `Row.dimPrefix`; the start-order sorts; first ◆ selected on open |
| `web/.../SigilPlace.kt` | `placesFlow`: tab number and glyph per pane |
| `web/.../AgentsOverview.kt`, `styles.css` | the who column, the place span, the dim spans, the amber row, the footer legend |
