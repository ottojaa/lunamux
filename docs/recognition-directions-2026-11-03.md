# Recognising a session at a glance: three directions

**Not for merge.** The owner picks one direction, or says none. Implementing the pick is a later piece of work.
These pages are drawings in HTML of the overview (⌘⇧A) as the build draws it today, with the seeded dev data
(the titles, repositories and ages of `AgentsOverviewSeed`). No client code changes.

## The ask

> it still takes a bit of time to read the titles + when the session last did something to piece together that
> "oh yeah this was that session"

and, after he withdrew "bare minimum": *what makes a session visually distinctive when there are no images or
colours to work with and it's all just text and timing?* The question is recognition, not recall: a cue the eye
takes in before it reads. The target stays "One glance, and I know where all my work stands."

## What the directions keep, and what Desk gives up

- The overview's data and logic, in all three: what each row says, the states, the keys in the footer, search.
- **A and C keep today's shape**: the same bands (NOW, LAST FEW DAYS, LAST TWO WEEKS), the same rows in the same
  order, task rows that fold, member rows, "open" and "+ 2 more". They add one cue to each row.
- **B changes the shape, which is what makes it the radical one.** It drops the bands and the sorted order: a task
  sits in its slot whatever its state or age, age becomes a bar on the tile, and urgency moves from position to a ◆
  banner. An opened task covers its neighbours while it is open. That is the price of "it is always where it was".
- **◆ stays the loudest colour on screen** in all three. Each direction adds quiet cues (checked by grayscale and blur, below; Marks is the one to watch).
- **Colour is never the only cue**: every colour cue is paired with a shape (a pattern, a monogram, a place, a bar).
- The terminal palette and both themes. Neon Cyan and Lunamux Light use the toolkit's own tokens.
- A thin, generic Lunamux side: nothing here knows about any orchestrator. A cue that needs data Lunamux does not
  have says so, and names the smallest generic source.

One change of rule the owner should see: today's stylesheet says *colour says state and nothing else*. Direction A
spends colour on identity too; the others do not.

## At a glance

| | Recognised by | Idea | Data it needs | What it costs |
|---|---|---|---|---|
| **A. Marks** | look | every task or lone session wears one stable mark, wherever it appears | none new | colour now means two things; repositories that share a hue look alike |
| **B. Desk** (radical) | place | the list becomes a board where a task keeps its slot | a slot per task (new, small) | no bands and no sorted order; urgency is no longer position; holes in the grid; a fixed capacity; an opened task hides its neighbours |
| **C. Screens** | content | a miniature of the session's last screen on each row | last screens in bulk (new) | rows are taller; idle screens look alike |

All three may also use the free freshness fade below.

---

## A. Marks: recognise it by how it looks

Every task, and every session that is not in a task, gets a **mark**: a small mirrored 5×5 pattern drawn from its id
(about 23 000 different ones), on a plate tinted by its repository. One rule for the tint: **the repository, and
nothing else**. The pattern is what tells two sessions apart; the hue only says where. The repository word repeats the
tint and gains a two-letter monogram. A task's mark has a second edge behind it, and **a session in a task wears its
task's mark** (a small copy in the overview, full size in the sidebar, the pane header, the chip and search); a session
outside any task wears its own. So the mark is the same on every surface, as the last picture shows.

<table><tr><th>collapsed</th><th>expanded</th></tr><tr><td><img src="recognition-directions-2026-11-03/marks-dark-collapsed.png" width="100%" alt="marks dark collapsed"></td><td><img src="recognition-directions-2026-11-03/marks-dark-expanded.png" width="100%" alt="marks dark expanded"></td></tr><tr><td><img src="recognition-directions-2026-11-03/marks-light-collapsed.png" width="100%" alt="marks light collapsed"></td><td><img src="recognition-directions-2026-11-03/marks-light-expanded.png" width="100%" alt="marks light expanded"></td></tr></table>
<sub>Top row Neon Cyan, bottom row Lunamux Light; both at 1280 wide.</sub>

**What to look at.** Billing and gateway rows differ at once by hue *and* shape; the many "lunamux" rows share one
hue and are told apart by pattern only. That collision is on purpose: seven hues cannot cover every repository, and a
hue means "where", not "which". The hues skip amber (◆), red/pink (error) and cyan (working).

<table><tr><td><img src="recognition-directions-2026-11-03/marks-dark-surfaces.png" width="100%" alt="marks on the sidebar, pane header, chip and search"></td>
<td><img src="recognition-directions-2026-11-03/marks-light-surfaces.png" width="100%" alt="the same in the light theme"></td></tr></table>
<sub>The same marks on the sidebar, the pane header, the toolbar chip and search results. The two Access control sessions
wear the task's mark in all of them.</sub>

- **Trade-off:** it reverses "colour says state and nothing else" (hue now also means *where*); a mark only helps
  once it has been seen a few times, which is why it is the same everywhere; sessions of one task look alike (their
  titles and glyphs tell them apart, and that is the point of a task); rows grow from 30 px to 34 px; and a 24 px
  mark is larger than the ◆ glyph, so the ◆ stays the loudest *colour* but not the largest shape (see Checks).
- **Data:** pattern from the task id, or the session id outside a task (**exists**); tint from the repository
  (**exists** on the session record); freshness from `stateSince`/`endedAt` (**exists**). A pure function in the
  shared client view model; nothing on the server.

## B. Desk: recognise it by where it is (the radical one)

Today a row moves every time its state changes, so a session is somewhere new each time you open the overview. The
Desk drops the sorted list. Each task takes the first free **slot** on a board when it first shows up and keeps it
until it leaves the two-week window; a new task takes the first hole. "Billing is bottom-left" stays true. Urgency
is not position any more: a ◆ banner across the top says who asks you, and a tile asking you is outlined in amber.
Age is a bar on the tile's edge (full = just now). A task opens over its neighbours (in the picture it covers three
tiles and part of the next row); nothing on the board moves, but what it covers is hidden while it is open.

<table><tr><th>collapsed</th><th>expanded</th></tr><tr><td><img src="recognition-directions-2026-11-03/desk-dark-collapsed.png" width="100%" alt="desk dark collapsed"></td><td><img src="recognition-directions-2026-11-03/desk-dark-expanded.png" width="100%" alt="desk dark expanded"></td></tr><tr><td><img src="recognition-directions-2026-11-03/desk-light-collapsed.png" width="100%" alt="desk light collapsed"></td><td><img src="recognition-directions-2026-11-03/desk-light-expanded.png" width="100%" alt="desk light expanded"></td></tr></table>
<sub>Top row Neon Cyan, bottom row Lunamux Light; both at 1280 wide.</sub>

- **Trade-off:** the glance moves from "read down the list" to "know where to look", which pays after a few days and
  costs at first; the bands and the sorted order are gone, and urgency no longer equals position (the banner carries
  it); an opened task hides its neighbours; the grid has holes, and a board has a capacity, so the thirty-first task
  needs an answer (a scroll, or a "no slot yet" strip).
- **Data:** a slot per task is **new**. Smallest generic source: a client-side map of `task id or session id → slot`
  kept in the browser's storage for this device. It needs no server change, and it makes the slot per-device,
  which is the honest limit. Everything else on a tile is existing data.

## C. Screens: recognise it by what it looked like

Each row carries a **silhouette of the session's last screen**, drawn as bars: line lengths, indents, the diff
lines (a bar to the left for `+`, another for `−`), the dialog box of a question, the ❯ prompt. The eye can tell
"the long diff", "the test run" and "the question box" before it reads a word. A task shows a stack of its
most urgent member's, to the left of the row's text. Rows are 44 px high (30 px today).

These silhouettes are **synthetic**: the seeded sessions have no stored screen, so each live session was given the
template of what it is doing (a diff, a test run, a question box). A finished session is drawn as a finished screen
mostly is, a final message above the prompt box, so most of the older rows look alike. That is what a real
`lastScreen` would give, not a flattering variety.

<table><tr><th>collapsed</th><th>expanded</th></tr><tr><td><img src="recognition-directions-2026-11-03/screens-dark-collapsed.png" width="100%" alt="screens dark collapsed"></td><td><img src="recognition-directions-2026-11-03/screens-dark-expanded.png" width="100%" alt="screens dark expanded"></td></tr><tr><td><img src="recognition-directions-2026-11-03/screens-light-collapsed.png" width="100%" alt="screens light collapsed"></td><td><img src="recognition-directions-2026-11-03/screens-light-expanded.png" width="100%" alt="screens light expanded"></td></tr></table>
<sub>Top row Neon Cyan, bottom row Lunamux Light; both at 1280 wide.</sub>

- **Trade-off:** rows are about 1.5 times taller than today's (44 px against 30 px), so fewer fit; a finished Claude screen mostly looks like any other,
  so this separates *what it was doing* more than *which project*; and the stored screen has no colour, only text,
  so silhouettes are structure and grey.
- **Data:** `lastScreen` (the last 200 plain-text lines, written when a session ends) **exists** on the stored
  record, but list rows do not carry it (a search result's copy is always null), so a bulk read is **new**. The peek
  already reads the live screen of one session.

---

## The free layer: freshness

Every direction can fade a mark, a silhouette or a bar by how long ago the session last did anything, on a log
scale (1 = now, about a tenth at two weeks). It is time seen, not read, and needs only the timestamps the overview
already has. The age text stays on the row.

## Checks

<img src="recognition-directions-2026-11-03/checks-gray-blur.png" width="100%" alt="grayscale and blurred renders of the three directions, Neon Cyan on the top two rows, Lunamux Light on the bottom two">

Columns are Marks, Desk, Screens. Rows, top to bottom: Neon Cyan in grayscale, Neon Cyan blurred, Lunamux Light in
grayscale, Lunamux Light blurred (the blur stands for a squint). In grayscale Marks stay apart by pattern and
monogram, Desk by position and the heavier amber outline, Screens by shape. Blurred, the ◆ and the amber text are the
brightest colour in all three, in both themes. In Desk and Screens they are also the biggest thing. In Marks they are
not: a 24 px mark (drawn here, after trying 28) is a bigger shape than the ◆ glyph, so the ◆ wins by colour (amber is
the only warm hue, and the marks' hues skip it) and not by size, and finished sessions fade further, to about half at two weeks. That is
Marks' real risk. If the owner picks Marks, the first build starts from this check and blurs the real build again.

## Considered, not built

- **Words:** a short handle the owner types once (F2 rename) or a branch slug as the first word. Cheap and strong,
  but it is recall, not recognition; kept as a follow-up to any direction.
- **A time strip or sparkline** as the identity cue. Time cues drift, so they say how alive a session is, not which
  one it is. It was tried on real minute-level activity (kept private): it separates a long hands-on session from a
  short one, not one short session from the next. Left out.

## Recommendation

**A, Marks, with the freshness fade, now. Keep B, Desk, as the radical option to revisit.**

- It is the only cue that follows a session onto every other surface (chip, sidebar, pane header, search) and it
  needs no new data and no server change. (Sessions of one task wear the same mark: it says *which task*, and the
  title and glyph say which session.)
- It cuts the title-reading step: where today a row's identity is its words, a row now has a face, and a
  run of rows with the same words starts to look different.
- Its cost is the one the owner already decided once (colour for state only). If he would rather keep that rule, B is
  the cue that needs no colour at all, at the price of a new screen and a new datum.
- C is the weakest on its own, because idle screens look alike; its silhouette would sit better in the peek.
- The recommendation holds only while the ◆ stays the loudest colour at 24 px in the blur check above; if it does not
  in the real build, Marks gets smaller or fainter, and if that is not enough, B is the answer.

## How these were drawn, and what was not checked

- `mock.js` draws all three from one data set; `marks.html`, `desk.html`, `screens.html` take
  `?theme=dark|light&mode=collapsed|expanded` (and `&filter=gray|blur`; `marks.html` also takes `mode=surfaces`). Fonts are the repo's JetBrains Mono.
- They are drawings of the build's CSS, not the build: the dark panel colour is taken from the build's own
  screenshot; the light panel is white (the toolkit's surface) and was not run in the build.
- 1280 only. 1024 and phone width were not drawn: the overview is a desktop overlay with a 1180 px panel.
