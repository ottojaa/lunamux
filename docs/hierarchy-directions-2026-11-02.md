# Hierarchy directions for the agents overview

The owner's complaint about the overview: "my eyes are wandering all over the view". Every row
speaks at the same volume, so nothing tells you where to look first. The goal is one glance, and
you know where all your work stands.

This page holds three real, switchable directions beside today's drawing, so the pictures show the
same seeded data and the pick can be made on them. They differ in how the page is organised, not in
colour. Nothing was deleted and no data or logic changed: the view model is untouched, and a
direction only changes how the same rows are drawn. All shots are from the seeded dev server
(`scripts/seed-agents-overview.sh`), one pass, 1280 wide unless a name says 1024, in
[hierarchy-directions-2026-11-02/](hierarchy-directions-2026-11-02/).

## Try them

The overview is drawn as before unless you ask. Three ways to ask, strongest first:

- `?ao=a`, `?ao=b`, `?ao=c` or `?ao=0` (today) in the address. It wins over everything else and is
  not remembered.
- ⌥1, ⌥2, ⌥3, ⌥0 while the overview is open. The choice is remembered in the browser
  (`lunamux.overview.direction`); a footer tag names the direction you are in.
- With neither, the overview is today's drawing, with no footer tag and no ⌥ keys.

## What each direction does about the six points and the pill

| | 0 · today | A · quiet table | B · answer first | C · narrow feed |
|---|---|---|---|---|
| Loudness | every title has the same weight; the ◆ row is told apart by its small amber glyph | only ◆ is loud (title 700, amber glyph); task titles next (600); folder, preview and age recede | the same quiet table; ◆ is loud in the card, muted in the list | ◆ rows carry an amber bar; task titles bold; the second line is the quiet one |
| Scan width | folder, preview, age, action spread over the full panel | table capped: title 40ch, then preview, age, folder, action | table capped, titles 56ch; the folder moves next to the title | panel 760px; two lines per row, nothing to scan across |
| Glyph budget | ○ ‖ ✓ · ◆ ⠶ ⠿ | ○ ‖ ✓ · are not drawn; the status word says it; ◆ ⠶ ⠿ stay | same as A | all glyphs stay, quieter |
| Done children | one line per done member | 3 or more done members of a task fold into one "✓ 8 done" line with the PR numbers | same as A | same as A |
| Search rhythm | one line for a session hit; a second line when the match is in a task, a prompt or a branch | one line per hit; the context goes in the preview column, and a task hit says "in task X" | same as A, folder beside the title | two lines per hit, the second one quiet |
| Answer at the top | the pill only | a line above NOW: "1 needs you · 1 working" | an amber card: who needs you, the question, "+ N more" and the working count; a calm card when nothing waits | a sticky line pinned to the top: the first ◆ session and "+ N more" |
| Toolbar pill | text, one border | text, no border; ◆ amber | a filled amber pill when something needs you | `◆ 1 ⠶ 1`, `○ idle` |

Search matches never fold, in any direction, and a fold opens when you click it, or when ↑↓ moves
the selection into it, and it stays open when the selection moves on. It folds again when the overview closes.

## Side by side: collapsed

| 0 · today | A · quiet table |
|---|---|
| ![](hierarchy-directions-2026-11-02/0-collapsed-1280.png) | ![](hierarchy-directions-2026-11-02/a-collapsed-1280.png) |
| **B · answer first** | **C · narrow feed** |
| ![](hierarchy-directions-2026-11-02/b-collapsed-1280.png) | ![](hierarchy-directions-2026-11-02/c-collapsed-1280.png) |

## Side by side: expanded

Three tasks opened, one of them "Lunamux: agents overview" with eight finished members. Today draws
all eight; A, B and C draw one "✓ 8 done" line.

| 0 · today | A · quiet table |
|---|---|
| ![](hierarchy-directions-2026-11-02/0-expanded-1280.png) | ![](hierarchy-directions-2026-11-02/a-expanded-1280.png) |
| **B · answer first** | **C · narrow feed** |
| ![](hierarchy-directions-2026-11-02/b-expanded-1280.png) | ![](hierarchy-directions-2026-11-02/c-expanded-1280.png) |

## Side by side: when nothing needs you

The same overview after the one ◆ question ("Role model") has been answered: nothing waits, three
sessions work. This is how the overview looks most of the time. A says "Nothing needs you · 3
working" in one line; B's card shrinks to the same line in a quiet outline, so its height cost is
paid only while something waits; C pins the same line to the top; today says nothing, and its pill
only counts "3 working".

| 0 · today | A · quiet table |
|---|---|
| ![](hierarchy-directions-2026-11-02/0-calm-collapsed-1280.png) | ![](hierarchy-directions-2026-11-02/a-calm-collapsed-1280.png) |
| **B · answer first** | **C · narrow feed** |
| ![](hierarchy-directions-2026-11-02/b-calm-collapsed-1280.png) | ![](hierarchy-directions-2026-11-02/c-calm-collapsed-1280.png) |

To see it yourself on the seeded dev server, change the `state` of "Role model" in
`seed-access-control.json` in the scratch task folder from `needs_you` to `working`, and with
every `working` member set to `idle` as well you get the fully idle state (the last column of the
pill shots below).

## Side by side: search for "overview" (24 matches)

All four are taken at the same window height (900 px), so the pictures show how many hits fit on one
screen: A and B all 24, today 16, C 13.

| 0 · today | A · quiet table |
|---|---|
| ![](hierarchy-directions-2026-11-02/0-search-1280.png) | ![](hierarchy-directions-2026-11-02/a-search-1280.png) |
| **B · answer first** | **C · narrow feed** |
| ![](hierarchy-directions-2026-11-02/b-search-1280.png) | ![](hierarchy-directions-2026-11-02/c-search-1280.png) |

## The toolbar pill

Top to bottom: today, A, B, C. Left to right: a ◆ waiting with one session working; the ◆ answered
and three working; nothing waiting and nothing working.

![](hierarchy-directions-2026-11-02/pills.png)

## At 1024 wide, collapsed

| 0 · today | A · quiet table |
|---|---|
| ![](hierarchy-directions-2026-11-02/0-collapsed-1024.png) | ![](hierarchy-directions-2026-11-02/a-collapsed-1024.png) |
| **B · answer first** | **C · narrow feed** |
| ![](hierarchy-directions-2026-11-02/b-collapsed-1024.png) | ![](hierarchy-directions-2026-11-02/c-collapsed-1024.png) |

## Trade-offs

**A · quiet table.** The cheapest change and the least to learn: it is still today's table, now with
one loud thing in it. The cost is that the answer is one line of text, so you read the table to find
who needs you, and the plain glyphs are gone, so a finished session says so only in words.

**B · answer first.** The strongest answer to "where does my work stand": the question the agent
asked is the biggest thing on the screen, so you do not even scan the table. The cost is height
(three to four rows while something waits, one line when nothing does), and a ◆ row appears twice, once in the card and once, muted, in the list, so
that the list and the keys stay what they were. A long title can push the folder off the line.

**C · narrow feed.** Reads like a feed, so there is no table to scan and a narrow window is no worse
than a wide one; the sticky line keeps the answer in view while you scroll. The cost is density: two
lines per row halves the rows per screen.

## Recommendation

**B**, with A as the fallback. The owner's test is one glance; B is the only direction where the
glance ends before the table starts, and below the card it is the same quiet table as A. B earns its
height only while something waits: when nothing does, its card is one line and B reads like A. If the
card's height turns out to be too much on the owner's laptop, A gives most of the hierarchy for none
of it, and the answer line can be promoted to B's card later without touching the table. C is the
right shape for a narrow side panel, not for this overlay. The pick is the owner's.

## Limits, said plainly

- The data is seeded, not the owner's. A real list with several ◆ rows is untested visually; B's
  card shows at most three and says "+ N more".
- The calm and idle states are shot from the same seeded server with its task file edited (see
  "when nothing needs you"), not a second seed.
- Three things the directions bend on purpose, each a trade-off and not an omission: A and B do not
  draw the plain glyphs (○ ‖ ✓ ·), the status word carries them; finished members fold into one
  line until opened (click, or arrow into them); B shows the ◆ row twice. Search never folds.
- Peek, the confirm bar, the resume note, errors staying undimmed, stale rows receding and "+ N
  more" are drawn the same in every direction; they were not re-shot per direction.
- In C the footer's status text ("nothing to jump to…", which a headless browser always shows) is
  the one segment that is cut when the panel is narrow; the key hints keep their words.
- The shots are headless Chrome; the console shows its WebGL noise and nothing else.
