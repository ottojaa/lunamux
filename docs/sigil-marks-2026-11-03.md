# A mark for every thread of work

Every row in the agents overview that belongs to one thread of work wears the same small mark: one
of 36 (six shapes in six hues), chosen once per thread and stored, so it never changes. A task's
header and its member rows wear the task's mark; a loose session wears its thread's mark; the pane
header and the tab wear it too. The footer says what it means: "mark = one thread of work".

All pictures come from the seeded dev server (`scripts/seed-agents-overview.sh`), taken at 1280 and
1024 px in both themes, in [sigil-marks-2026-11-03/](sigil-marks-2026-11-03/).

## What a thread is

The server derives a *thread key* from where a session runs (`ThreadMarks.keyOf`), in this order:

1. a linked git worktree: `path:<worktree root>`;
2. a session inside a repository (it names a repository root or a branch) on a branch that is not
   the default one (`main`, `master` or whatever `origin/HEAD` names): `branch:<repo root>#<branch>`;
   on the default branch, or with a detached head, there is no thread;
3. a folder that is not a repository: `path:<folder>`, except the home folder, which has none;
4. no folder: no thread, no mark.

Paths are canonicalised first, so a symlink and its target are one thread. A task file's folder
goes through the same rules (`ThreadMarks.folderKey`), so a task whose folder is the home folder or
a default branch has no thread either.

A task takes the thread of its first member (its `match.folder`, else the task's open folder), and
stores it as `task:<id>`, so the header and its members agree even when no session is running.

The mark is `FNV-1a(key) mod 36`. When another thread seen in the last 14 days already holds it,
the server steps by 7 until it finds a free one; when all 36 are held it shares one. The result is
stored in `thread_marks` and read back first, so a restart, a reseed or a thread going quiet never
moves a mark. Shape is `mark / 6` (◧ ■ ◐ ◉ ▲ ▚), hue is `mark % 6`; marks 12 to 17 are the
half-circle.

Only a session that is shown gets a mark assigned. Searching the overview reads the stored mark
(`peekMark`) and never assigns one or renews a thread's last-seen day, so typing a query cannot use
up marks or keep a quiet thread's mark reserved.

## The overview

Collapsed, then with the *Invoice export* task expanded: the header, its two members and the five
loose sessions further down all wear the magenta half-circle (mark 14). *Rebuild the search index* and
*Show the migration* run in the same repository on other branches and wear other marks; the
home-folder session wears none.

| | Dark | Light |
|---|---|---|
| Collapsed, 1280 | ![](sigil-marks-2026-11-03/overview-dark-collapsed-1280.png) | ![](sigil-marks-2026-11-03/overview-light-collapsed-1280.png) |
| Expanded, 1280 | ![](sigil-marks-2026-11-03/overview-dark-expanded-1280.png) | ![](sigil-marks-2026-11-03/overview-light-expanded-1280.png) |
| Collapsed, 1024 | ![](sigil-marks-2026-11-03/overview-dark-collapsed-1024.png) | ![](sigil-marks-2026-11-03/overview-light-collapsed-1024.png) |
| Expanded, 1024 | ![](sigil-marks-2026-11-03/overview-dark-expanded-1024.png) | ![](sigil-marks-2026-11-03/overview-light-expanded-1024.png) |

The six hues are blue, violet, magenta, green, rose and orange: `#6aa6f2 #b38af2 #e070e0 #52c98b
#f0799e #ee8c5c` on dark and `#2f6fd0 #7a4fd0 #ac2fb0 #1f8f58 #cc3a68 #d0602a` on light. Neither
theme's warn yellow (`#f2c14e`, `#FFD60A`) nor the accent cyan (`#5fd4d0`) is among them, so a mark
is never mistaken for a status colour. The hue families come from the design mockups; an earlier
palette used a lime in place of the rose, but on light it was hard to tell from the green and sat
close to the warn yellow.

## The pane header and the tab

A pane running a session in the *Invoice export* worktree wears the half-circle in its header; the
tab wears it when that is the only thread in it. When the tab holds two threads, each pane keeps its
own mark and the tab wears none, because one mark would say something untrue.

| | Dark | Light |
|---|---|---|
| One thread in the tab | ![](sigil-marks-2026-11-03/pane-tab-one-thread-dark.png) | ![](sigil-marks-2026-11-03/pane-tab-one-thread-light.png) |
| Two threads in the tab | ![](sigil-marks-2026-11-03/pane-tab-two-threads-dark.png) | ![](sigil-marks-2026-11-03/pane-tab-two-threads-light.png) |

The mark sits before the pane title (header) and after the tab's name (tab strip); the status dot
keeps its own place.

## Code

| Where | What |
|---|---|
| `server/.../ThreadMarks.kt` | key rules (`keyOf`, `folderKey`), `markFor`, `peekMark`, `markForTask`, the `thread_marks` table |
| `clientServer/.../WindowEnvelope.kt` | `mark` and `threadKey` on the session summary, `mark` on the task (both default to null, so old summaries and old clients decode) |
| `client/.../AgentsOverviewBackingViewModel.kt` | `Row.mark` |
| `client/.../viewmodel/PaneMarks.kt` | `tabMark`: the one mark a tab wears, or none when its panes hold two threads (tested in `PaneMarksTest`) |
| `web/.../SigilMarks.kt`, `styles.css` | the mark element (`.ao-sigil`, tokens `--ao-mark-0…5`), pane and tab slots |
