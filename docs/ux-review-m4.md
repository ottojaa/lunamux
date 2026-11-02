# UX/UI review of the tasks build (milestone 4)

An independent look at the agents overview (⌘⇧A) with its tasks and EARLIER bands, judged against
the owner's north star:

> **One glance, and I know where all my work stands.** What is waiting on me (a ◆ I can trust),
> what is moving (each task's sessions, live), what I've been working on (a few days up front, two
> weeks behind, the rest through search), and from any of them one key takes me there.

The reviewer did not build any of it. Everything below was seen on a running dev server, in the web
client at 1280 and 1024 px wide, on a scratch database and tasks directory (never the owner's
Lunamux), seeded with `scripts/seed-agents-overview.sh`. Findings are classed **broken** (it
contradicts the north star or is a defect: fixed in this PR with a test) or **taste** (left for the
owner, listed with a suggestion).

## Verdict in one paragraph

The overview does what it is for. Opened cold on the seed it answers the three questions in one
screen: one ◆ on top, tasks grouped with their sessions, the last days in order, a quiet band for
older work, and search finds a closed session by the task it belonged to. Both live ◆ cases
worked, a chat that only finished its turn never showed ◆, and an agent told to split a job across
two panes created a task through the MCP tools without being asked to. Two things were broken and
are fixed: a finished-but-not-ended file task sat in NOW forever, and every ◆ row read "Claude
needs your permission", whatever it was really asking.

## Findings

### F1 · broken · A file task whose sessions are all done stays in NOW (the owner's input, GI-33)

![The seed before the fix: "Release train", three done sessions, sits in NOW](ux-review-m4/01-before-overview.png)

"Release train" is a file task whose three sessions are all `done` and none has ended, the way a
programme looks while its pull requests wait for a merge rule. It sat in NOW under the working
tasks, expanded to three ✓ rows with nothing moving or waiting in them
([expanded](ux-review-m4/03-before-release-train.png)). With five or six finished PRs held that
way, NOW would never be quiet, and "what's moving" would be answered with work that is not moving.

Verdict: **broken**. The rule was "a file task stays while any session has not ended". It confused
*not ended* with *has work left*. A member that reads `done` has no work left, whether or not its
session still exists.

Fix: a task goes to EARLIER the moment no session needs the person or works and every session reads
done (a task file's `done`/`ready`, or a session that ended). Open work that is neither (a session
in review, queued, idle) still keeps the task in NOW, because something is left to do and the task
should not jump between bands as its sessions start and stop. The seed now holds both cases:
"Release train" (all done, lands in LAST FEW DAYS) and a new "Billing cleanup" (one in review, one
queued, stays in NOW). Tests: `aFileTaskWhoseSessionsAllReadDoneMovesToEarlierEvenThoughNoneEnded`,
`aFileTaskWithADoneSessionStaysInNowWhileAnotherNeedsYouWorksOrIsStillOpenWork`,
`aDoneOnlyFileTaskGoesToEarlierAtOnceWithoutTheGraceAHostedTaskGets`; `docs/tasks.md` and the seed
header say the new rule. Shown fixed in [the last screenshot](ux-review-m4/07-after-fix.png).

### F2 · broken · Every ◆ row says "Claude needs your permission"

![Two ◆ rows with the same generic text, one a question and one a permission prompt](ux-review-m4/02-before-live-cases.png)

Two real sessions were left waiting: one asked a plain question through `AskUserQuestion`, one hit
a permission prompt for a shell command. The ◆ and the count in the toolbar chip were right (3 need
you, matching the three ◆ rows), but both rows read *Claude needs your permission*, the hook's
generic text. The north star asks for a ◆ the person can trust and act on from a glance: with two ◆
rows saying the same thing, they cannot tell the one worth dropping everything for from the one that
is a routine `echo`, without jumping into each pane.

Verdict: **broken** (the seeded task's "Should admins inherit viewer rights?" shows what the row is
for; the live rows did not do it).

Fix: the tracker keeps the tool call that arrived just before the prompt. A question put through
`AskUserQuestion` shows the question itself; a permission prompt shows `Allow <command>?`. With no
tool call pending (a plain `Notification`) the hook's own text stays. Tests in
`AgentSessionTrackerTest`: the `AskUserQuestion` case, the keeps-the-hook-text case, and the
permission case, which now expects the command. Shown fixed in
[the last screenshot](ux-review-m4/07-after-fix.png): *Allow Bash echo hello > /tmp/aov21-play/perm.txt?*
and *Which release name do you prefer: Aurora or Borealis?*.

### F3 · pass · The four live cases

All four were started by hand in panes of the scratch server (real Claude Code sessions, hooks on).

| Case | Result |
|---|---|
| A real question (`AskUserQuestion`) | ◆ in NOW, in the chip, on the tab and in the sidebar; text fixed by F2 |
| A real permission prompt | ◆ the same way; text fixed by F2 |
| A chat that only finished its turn | idle ○ with its last line "hello", **no ◆** ([screenshot](ux-review-m4/02-before-live-cases.png)) |
| An agent asked to split a job across two panes (the prompt never says "task") | created the task "Count .kt files (server + web)" through the MCP tools with both panes in it, unprompted; it shows as one task with two sessions and, once idle past the grace, moves to EARLIER (last screenshot) |

Jumping from the overview lands in the pane with its tab and sidebar entry selected
([screenshot](ux-review-m4/08-after-jump.png)).

### F4 · pass · Search finds a closed session by its task

![Searching "invoice" finds three closed sessions through their task](ux-review-m4/04-search-by-task.png)

Searching a word that only the task's title holds finds its closed sessions, each with an
`in task …` line and **↵ resume**. Width 1024 still reads cleanly
([screenshot](ux-review-m4/05-width-1024.png)): the repo column shrinks, the right edge holds.

## Taste (left unchanged; for the owner)

- **T1 · The chip counts a session that is not moving as working.** The toolbar says "2 working",
  and the seeded "Login audit trail" (*quiet 19m*) and "Sync worker" (*stale 44m*) are the two. The
  rows themselves are honest (dimmed, with the age), but the chip and the task line "1 working"
  count a session whose file stopped speaking. Suggestion: count only sessions that are not quiet or
  stale as working.
- **T2 · The first selected row cannot be jumped to on the seed.** The overview opens on the first
  ◆ (a seeded member with no pane), so the footer says *nothing to open: this session is not in a
  Lunamux pane* ([screenshot](ux-review-m4/06-jump-and-peek-hint.png) shows a hosted ◆ with
  **↵ jump** instead). A real world has hosted sessions first; a seed artifact.
- **T3 · An idle session with nothing to say shows "—"** (the freshly started "split" row). Fine for
  a few seconds; an empty line would read calmer.
- **T4 · An expanded task's footer says "← collapse" and "space collapse"** side by side
  ([screenshot](ux-review-m4/03-before-release-train.png)). One is enough.
- **T5 · Ended rows with no summary show the raw first prompt**, prefixed with `❯` and cut at the
  column. Readable, a little noisy.
- **Limit, not a finding · Claude Code's own menus.** A "Teach auto mode about your environment?"
  menu blocked a pane while its session read idle: no hook fires for it, so Lunamux cannot know it
  is waiting. Nothing the overview can fix.

## What the review could not see

Android and iOS (out of scope for this milestone), the Electron shell (needs a desktop session),
and a real programme's long-lived sessions over hours. The seed's file-declared members have no
pane, so ↵ on them is "nothing to open" by construction.

## Re-look after the fixes

One re-look on the seeded server after the fixes ([screenshot](ux-review-m4/07-after-fix.png)):
"Release train" has left NOW and sits in LAST FEW DAYS ("3 sessions"); "Billing cleanup" (in review,
queued) holds its place in NOW; both ◆ rows say what they are asking; "Count .kt files" sits in
LAST FEW DAYS with its two sessions. Nothing new was found.

## First things to try tomorrow

1. Open ⌘⇧A with nothing running: it should be calm, no ◆, the last days on top.
2. Start a Claude session and ask it something that needs a shell command: wait for the permission
   prompt, open ⌘⇧A, and read the ◆ row. It should say `Allow <the command>?`.
3. Ask a session to use `AskUserQuestion`; the ◆ row should be the question.
4. Let a chat finish a turn and check it has **no** ◆.
5. Say "split this job across two panes with a Claude Code session in each" and see whether a task
   appears with both panes in it. Then ↵ on the task header and on a session.
6. With a programme that holds finished PRs for the merge rule, check that it is in LAST FEW DAYS
   and that a task with a PR still in review stays in NOW.
7. Search a word from an old task's title and press ↵ to resume one of its closed sessions.
8. Try T1 (the chip counting quiet sessions as working) and tell me whether it matters in your day.
