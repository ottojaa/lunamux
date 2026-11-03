# UX review of Sigil (the marks, the place and who cues, the ◆ row)

A look at the agents overview (⌘⇧A) as the owner will see it, after Sigil: a mark for every thread
of work, a who cue (`❯` you started it, `⚙` a session did), a place cue (`1▘`: tab 1, top-left),
the dim task prefix, and the footer legend. Judged against the north star:

> **One glance, and I know where all my work stands.** What is waiting on me (a ◆ I can trust),
> what is moving (each task's sessions, live), what I've been working on (a few days up front, two
> weeks behind, the rest through search), and from any of them one key takes me there.

Everything below was seen on a running dev server in the web client, at 1280 and 1024 px wide, in
both themes. The server was a scratch one (its own port, database and tasks directory, never the
owner's Lunamux), seeded with `scripts/seed-agents-overview.sh`, plus a few live sessions started
by hand so the place cues had real panes behind them. Pictures are in
[ux-review-sigil/](ux-review-sigil/); nothing in them comes from the owner's own data.
Findings are classed **broken** (fixed in this PR) or **taste** (left for the owner, with the reason
and a suggestion).

## Verdict in one paragraph

Sigil does what it was built for. A task's header and its members wear one mark, a loose session
wears its thread's mark, and the pane header and the tab wear the same one, so a row leads to its
pane by its place and its mark. Four things were off and are fixed: the selected ◆ row went khaki
and the ◆ tint went olive on the dark theme (it should read amber), violet and magenta were close
enough to be taken for each other, the ring-with-a-dot and the triangle read finer than the filled
shapes, and at 1024 px the footer legend was cut off in the middle of an entry. Nothing here needed
a server or view-model change, so no Kotlin test was added; each fix is checked by measurement and
by the pictures below. What is left for the owner: where a task's loose sessions sit, and one
thing that is more than taste. Inside a shape the marks differ only by hue, and under a simulated
colour-vision deficiency some of those hues collapse into one (F9). Fixing that takes a new cue or
a new palette, both out of this ticket's scope, so it is named and shown, not decided.

## Findings

### F1 · broken · The ◆ row's tint turns olive in dark and khaki when selected

| Before (dark) | After (dark) |
|---|---|
| ![](ux-review-sigil/before-dark-expanded-1280.png) | ![](ux-review-sigil/overview-dark-expanded-1280.png) |

| Before (light) | After (light) |
|---|---|
| ![](ux-review-sigil/before-light-collapsed-1024.png) | ![](ux-review-sigil/overview-light-collapsed-1024.png) |

The ◆ row is the one thing on this screen the person must not miss. Its band was the warn colour
mixed into the panel surface with `color-mix`, and the panel surface on the dark theme is a deep
teal, so the mix drifted toward green: the band read olive, not amber, and the selected ◆ row
(accent bar plus tint) was the colour of khaki. The loudest row looked muddy and, selected, like
the dimmer rows around it.

Why it matters: this is the "◆ I can trust". A band that is the wrong colour is a band that is
easy to take for a faded row.

Done, in this PR: the tint is mixed into a neutral grey of the surface's own lightness
(`--ao-tint-base`, using CSS relative colour, with the plain surface as fallback where the browser
cannot do that), so the hue stays amber: about 46° on dark. The ◆ band is now ΔE 26 from the
surface; a plain selected row is 14.6. Two strengths moved with the change of base, and both
change what you see: the hover tint of a ◆ row goes from 21% to 19% amber, and the selected ◆
row's tint from 26% to 22% (the base is no longer teal, so less amber reads as amber). The selected ◆ row keeps both bars: the accent one on the outer edge and the amber one
just inside it ([the selected ◆ row at 1024](ux-review-sigil/overview-dark-collapsed-1024.png), top
◆ row). See [the ◆ rows close up](ux-review-sigil/need-rows-dark.png) and
[light](ux-review-sigil/need-rows-light.png).

### F2 · broken · Violet and magenta can be mistaken for each other

| Before (dark, 1x sheet, enlarged 6x) | After (dark, same sheet, same enlargement) |
|---|---|
| ![](ux-review-sigil/before-marks-sheet-dark.png) | ![](ux-review-sigil/after-marks-sheet-dark-1x-upscaled.png) |

Marks of one shape differ only by hue, so neighbouring hues must stay apart. Measured in CIE Lab
(ΔE76) on the palette as it was, the closest pair was violet and magenta: 24.0 on dark and 25.7 on
light. At 12 px, next to each other in one list, those two read as one.

Why it matters: two threads of work that look like one thread break "does a family read as one
thread" the other way round.

Done, in this PR: violet moved to `#b58df3` and magenta to `#e761d5` on dark, to `#6e50d5` and
`#af2bab` on light. Every pair of neighbouring hues is now at least 32 ΔE apart
(blue–violet 33.2, violet–magenta 32.2 on dark; 32.4 and 32.4 on light), the stylesheet's marks
comment says so, and no hue is within 37 of the warn colour on dark (orange is the nearest, at 37)
or 65 on light. Which mark a thread gets did not change, only how two hues look.

### F3 · broken · The ring with a dot and the triangle read finer than the filled shapes

| Before (dark, 1x sheet, enlarged 6x) | After (dark, same sheet, same enlargement) |
|---|---|
| ![](ux-review-sigil/before-marks-sheet-dark.png) | ![](ux-review-sigil/after-marks-sheet-dark-1x-upscaled.png) |

(Both are the pixels the browser draws at 1x, enlarged without smoothing, so the weights compare
like with like; the crisp vector renders are [dark](ux-review-sigil/marks-sheet-dark.png) and
[light](ux-review-sigil/marks-sheet-light.png).)

Measured as ink on the 12 px mark (a filled square is 100): the outline shapes were as low as 48
and the ◉ 53. A mark that reads lighter than its neighbours reads as fainter, as if that thread
mattered less.

Done, in this PR: the outline stroke goes from 1.2 to 1.5 (`.ao-sigil-line`), the ◉ dot from r 2.2
to 2.7 and the triangle about a fifth larger (it now reaches past the square's 10 px box, which a
triangle must to look as big). Ink now runs from 56 (▲) to 100 (■): ◧ 94, ◐ 74, ◉ 68, ▚ 94.
Measured at 1x, identical in every hue.

The triangle stays the lightest shape by area; a triangle cannot carry more ink without being
bigger than its row. It still reads as a solid shape beside the others, so it was left (see taste
matter 3).

### F4 · broken · The footer legend is cut in the middle of an entry at 1024

![The footer at 1024 after the fix, dark: the whole legend and the widest key hints fit](ux-review-sigil/footer-1024-dark.png)

The legend (`mark = one thread of work · ❯ you started it · ⚙ a session did · 1▘ tab 1, top-left
pane`) is the key to the new cues, and it shares the footer with the key hints. Drawn in full it is
607 px wide at 1280. On a row whose hints are the widest ones (`no pane to jump to`, `← back to
the task`, selected on a ◆ member of a task) the footer at 1024 px could not hold both, and the
legend was cut off wherever the width ran out, in the middle of an entry. I measured the widths and
saw the cut while working on it, but did not keep a picture of the old footer, so there is no
"before" here, only the numbers.

Why it matters: half a legend entry is worse than none; it teaches the wrong thing.

Done, in this PR: three changes. The gap between footer entries goes from 3ch to 2ch, for every
key hint, not only the legend, which leaves the legend more room. The text is shorter ("mark = one
thread", "tab 1, top-left"; about 511–518 px), so at 1024 px with the widest hints all four entries fit
([dark](ux-review-sigil/footer-1024-dark.png), [light](ux-review-sigil/footer-1024-light.png)). And
each entry is its own element, so a panel that is still too narrow drops whole entries from the
right instead of cutting one: at 880 px three entries show, at 780 px two, with the keys intact
([880](ux-review-sigil/footer-880-light.png), [780](ux-review-sigil/footer-780-light.png), light).
The entries are separated by a drawn "·", so a dropped entry leaves no stray dot. (The `move` hint
is the first of the keys to drop at those widths; the overview's own width rule, not the legend's.)
Not covered: below the widths shown I did not test. Once the panel is narrower than the first entry
("mark = one thread") plus the keys, that entry is clipped on its own, so the claim holds down to
about 780 px and no further.

### F5 · pass · The place cue and the mark match the pane

![Two rows, then the two panes they lead to](ux-review-sigil/place-rows-dark.png)
![The panes' headers, dark](ux-review-sigil/place-panes-dark.png)

Rows for *Review the CSV columns* (`3▌`) and *Document the export format* (`3▐`) lead to the left
and right pane of tab 3, and both panes show the magenta half-circle that the rows wear. Enter on
each row opens the right tab with that pane focused. A session with no pane says "no pane to jump
to" in the footer, rather than pretending (shown in the 1024 pictures). Light:
[rows](ux-review-sigil/place-rows-light.png), [panes](ux-review-sigil/place-panes-light.png).

### F6 · pass · The tab strip and pane headers

| One thread in the tab (tab 3) | Two threads in the tab (tab 1) |
|---|---|
| ![](ux-review-sigil/tabstrip-one-thread-dark.png) | ![](ux-review-sigil/tabstrip-two-threads-dark.png) |
| ![](ux-review-sigil/tabstrip-one-thread-light.png) | ![](ux-review-sigil/tabstrip-two-threads-light.png) |

A tab wears a mark only when every marked pane in it shares one, and wears none when its panes hold
two threads (tab 1): a tab mark would then say something untrue. The pane headers each wear their
own mark in both cases: [one thread](ux-review-sigil/paneheader-one-thread-dark.png),
[two](ux-review-sigil/paneheader-two-threads-dark.png), and light:
[one](ux-review-sigil/paneheader-one-thread-light.png), [two](ux-review-sigil/paneheader-two-threads-light.png).
Nothing to fix.

### F7 · pass · The dim task prefix

A member named *Invoice export: parse the ledger csv* under the task *Invoice export* shows the
shared words dim and the rest bold, so the part that tells the members apart is what the eye
lands on (in the expanded pictures). A task with no shared words, and a lone member, show their
whole name as before.

### F8 · pass · Stress data

| Case | Where to see it |
|---|---|
| A long name | `invoice-ex…` truncated in the folder column; *Login audit trail*'s row text cut with `…` ([1024](ux-review-sigil/overview-dark-collapsed-1024.png)); nothing wraps or pushes the cues |
| A row with no name of its own | *Why did the nightly backup email nev…* and *Owner test follow-up*, home-folder sessions: no mark (no thread), and the prompt text stands in for a title ([1024](ux-review-sigil/before-light-collapsed-1024.png), bottom rows) |
| A group of one | *Nightly sync* ([1280](ux-review-sigil/overview-light-collapsed-1280.png)) |
| A group of several | *Release train* (3 sessions), and *Access control* when expanded |
| Nothing found | [1280 dark](ux-review-sigil/search-empty-dark-1280.png), [1280 light](ux-review-sigil/search-empty-light-1280.png), [1024 dark](ux-review-sigil/search-empty-dark-1024.png), [1024 light](ux-review-sigil/search-empty-light-1024.png): the legend stays and the keys it needs |

## The five questions

**Does a family read as one thread?** Yes. *Invoice export*'s header, its two members and the
loose sessions in the same worktree all wear the magenta half-circle; the dim prefix makes
the two members read as parts of one task.
![](ux-review-sigil/overview-dark-expanded-1280.png)
![](ux-review-sigil/overview-light-expanded-1280.png)

**Does a row's place cue and mark match its pane?** Yes, shown in F5. The pane header, the tab
(when it holds one thread) and the row agree on the mark, and the place glyph names the tab and
quarter.

**Is the ◆ row the loudest thing, in both themes?** Yes since F1. The band, the bar and the
bold text carry it; the task header above it says "1 needs you" in bold amber.
![](ux-review-sigil/overview-dark-collapsed-1280.png)
![](ux-review-sigil/overview-light-collapsed-1280.png)

**Does any shape read finer than the others at the overview's size?** The ◉ and the outline
shapes did; now no shape is below 56 where a filled square is 100 (F3).
![](ux-review-sigil/marks-sheet-dark.png)

**Does anything rely on colour alone?** Mostly no, and in one place yes.
- The ◆ is a glyph, a bar and bold text as well as amber. The who and place cues are glyphs. Status
  is said in words (stale, quiet, done, in review). Families differ by shape.
- Inside one shape, only the hue differs: six marks per shape, told apart by colour. That is the
  one place the design rests on colour, and it does not hold for a reader with red-green colour
  blindness (F9).

Below, the same screen as drawn, under a simulated deuteranopia, and in greyscale. The ◆ rows, the
shapes and the who and place cues survive both. What does not survive is the difference between two
marks of one shape. On dark, *Nightly sync* (blue ▲) and *Bump kotlinx-serialization* (violet ▲)
are 33 apart as drawn and about 2 under the simulation: the same colour. *Billing cleanup* (violet
◉) and *Access control* (magenta ◉) stay apart in both (15.5 and 31.3, F9's table). On light, the
screen holds no pair that collapses: the blue and violet ▲ are 6.3 apart under the simulation, close
but not equal, and the pair that does collapse there, green and rose, is not on this screen (it is
in the marks sheet after the pictures).
![](ux-review-sigil/colour-alone-dark.png)
![](ux-review-sigil/colour-alone-light.png)

The pairs of F9's table across every shape, as drawn and simulated. Blue and violet are one colour
on dark (the first two columns); green and rose are one colour on light (the fourth and fifth).
![](ux-review-sigil/colour-alone-marks-dark.png)
![](ux-review-sigil/colour-alone-marks-light.png)

### F9 · open · Inside a shape, hue is the only difference, and some hues collapse for deuteranopes

I ran every pair of hues through a deuteranopia simulation (Machado 2009, full severity) and measured
ΔE76 again. The closest pairs are:

| | Dark | Light |
|---|---|---|
| blue–violet | **1.8** | 6.9 |
| green–rose | 11.3 | **3.6** |
| violet–magenta | 15.5 | 31.3 |
| blue–magenta | 17.0 | 25.2 |

Under a simulated deuteranopia, blue and violet are the same colour on dark, and green and rose on
light. The simulation is full-severity deuteranopia (no working green cones), which is about 1% of
men; red-green colour-vision deficiency of every degree is about one man in twelve, and the milder
forms sit between the two. For those readers, two threads wearing the same shape in those hues are
the same mark (or close to it): they would take two threads for one. F2's work holds for normal vision; this is the other
reader the north star's "nothing relies on colour alone" asks about, and the answer is that one
thing does.

Why it is left: the fix is a new cue inside the shape (a notch, a dot, a corner) or a new palette
picked for lightness as well as hue, and both are a design choice and a new cue, which this ticket
puts out of scope. It is the first thing I would give the owner: if their eyes are normal it may not
matter to them, but it will to someone else who installs Lunamux.
What already helps: shapes carry the family, and two threads never share a whole mark (the
server probes a collision away), so a lookalike pair is always two different marks of one shape
that happen to share a look; with six shapes and about two dozen threads, such pairs will exist.

## Left for the owner (taste)

1. **Loose sessions sit away from their task.** A session that shares the task's mark but not a
   task member's folder is a loose row further down, not under the task (the seeded members have no
   folder to match). The mark joins them; the position doesn't. I left it: moving it would change
   how rows are grouped, which is outside this review. If it bothers you, group by mark under the
   task.
2. **Hue is the only difference inside a shape** (F9, with the numbers). Held at ΔE ≥ 32 for normal
   vision, but not for deuteranopia. If it matters, the fix is a cue inside the shape or a palette
   chosen for lightness too.
3. **The triangle is the lightest shape** (56 against the square's 100). Enlarging it more would
   make it taller than its row.
4. **The stale ◆ member fades.** *Role model* is a ◆ member of a stale task, and the older
   `.ao-stale` fade dims it while the task header says "1 needs you" in bold amber. The ◆ row
   is still readable (the band stays), but the fade says "ignore me" to a row that is asking for
   you. This is not Sigil's; I left it.

## What the review could not see

- A real person with colour-vision deficiency: I used a simulation (F9), which is a model, not a person.
- Real, long-lived history: the seed is a few dozen rows, not two weeks of work.
- The two `~` rows labelled *Reconcile March payouts* and *Add CSV export for invoices* in the
  pictures are ended rows left when the seeded events were sent again under a new session id; they
  are an artefact of reseeding, not of Sigil.
- Mobile screens, the lunula toolkit and the owner's own data were out of scope.
- The web layer has no test harness for the stylesheet or the drawn marks, so F1–F4 are checked by
  measurement and by the pictures, not by a test. No view-model or server code changed.

## Redundancy pass

Looked for dead code in the six Sigil files (`SigilMarks.kt`, `SigilPlace.kt`, `PaneMarks.kt`,
`SigilRows.kt`, `ThreadMarks.kt`, and the overview's use of them): no declared-but-unused symbol.
`ao-sigil-badge` and `ao-sigil-slot` have no style but are DOM markers the code finds by name, so
they stay. Nothing was deleted.

## Notes on earlier documents

`sigil-marks-2026-11-03.md` lists the old hues (`#b38af2`, `#e070e0`, light `#7a4fd0`, `#ac2fb0`),
and `sigil-cues-2026-11-03.md` the old footer text. Both are dated records and are left as they
were; the values above supersede them.

## Where the code is

| What | Where |
|---|---|
| tint base and ◆ row rules, hue tokens, outline weight, footer legend | `web/src/jsMain/resources/styles.css` |
| dot and triangle geometry | `web/src/jsMain/kotlin/se/soderbjorn/lunamux/SigilMarks.kt` (`shapeParts`) |
| legend split into entries, shortened text | `web/src/jsMain/kotlin/se/soderbjorn/lunamux/AgentsOverview.kt` (`renderFooter`, `CUE_LEGEND`) |
