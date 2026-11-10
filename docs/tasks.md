# Tasks

The agents overview (⌘⇧A) lists the **conversations** that are live in Lunamux and the **tasks** they
belong to. A task is one piece of work: a release, a refactor split over branches, one chat. A
conversation is one Claude or Codex dialogue. It keeps its row when it is resumed with
`claude --resume` and when Lunamux restarts, because the row is the conversation, not the pane it
runs in. A task has one **main conversation**, the one that speaks for it, and that conversation's
row stands for the whole task.

A session is in a task when it carries the task's id, and nothing else decides it. Agents put
sessions in tasks through the MCP tools below. A **task file** is optional: it gives a task a title
and, if wanted, an `open` action. It lists no sessions, and Lunamux only ever reads it.

## What the person sees

One row per conversation. A conversation in no task is a row of its own. A task's main conversation
carries the task's row; its other agents are not rows. A **background agent** (one working in a tab
out of sight) is counted in the row's state word and listed under the row while it is selected, and
shown in the task panel.

A live row leads with a **state mark** (the spinner while it works, **◆** when it needs the person,
**✓** when done) and the **name of its tab** when the person named the tab; the title then follows
on a line of its own as the description. A tab nobody named ("Tab 3"), a hidden tab, or one whose
name only repeats the title leaves the title to lead. On the same first line, at the right: the
plan's **progress** (a bar and "13/14", from the task's progress block or its plan's steps), one
**state word** and the **age**. Under them comes the **headline**, two lines at most. Names are
bright for working and needs-you rows, mid-grey for done ones and dim for closed ones.

NOW is **grouped by repo** (the task's repo, else the folder the conversation runs in, home as
`~`). Groups and the rows in each stand in the order their work began, so no row moves when a state
changes. The search line ends in a tally: "◆ 1 needs you · 2 working · ✓ 2 done".

A **closed row** in EARLIER is one line: the title, the last thing the conversation said, where it
ran, "closed" and the age. Progress and folder are hidden when the panel is narrower than 700px. A
conversation is **working**, **needs you** or **done**; Lunamux never guesses from the last words of
a reply that the agent is asking.

| Word | When |
|---|---|
| **needs you** (yellow) | a conversation waits for the person; "2 need you" for more |
| **working** (cyan, with the spinner) | the conversation itself works (for a task: its main conversation) |
| **1 agent working** / **N agents working** | the task's main conversation waits on its agents |
| **done** (muted green) | the row is in NOW and nothing works or waits |
| **closed** (dim) | the row is in EARLIER and nothing works or waits |

**Needs you** means a question the person can see and answer: a permission prompt or elicitation
Claude Code reports, an `AskUserQuestion` call, or Codex's approval overlay. One question counts
once, and only while its conversation has a pane in a window. A reply that merely ends in a question
is done, and so is a conversation just resumed and sitting at its prompt. A task can keep a question
visible while its main conversation is closed through `set_task_status` with `questionWaits: true`:
the row then stays in NOW with the status as its detail, reads **done**, and is not counted as
waiting.

The spinner is the app's working mark: four braille frames, drawn by CSS alone, so a row that is
redrawn keeps its frame and every spinner on screen shows the same one. With reduced motion it is a
still frame. A conversation **works** until a hook or the screen says otherwise, however long it is
silent; no timer ends it. The screen says otherwise when it reads idle for three polls in a row after
the poller's own three-poll hold (about fifteen seconds), with no hook between.

### The headline

Every row has one line that says where it is, and the task panel's header shows the same line:

| State | The headline |
|---|---|
| needs you | the question, in quotes |
| working | the task's status line; else the plan's step in hand; else what the conversation says it is doing (`set_status`); else its tool call in words |
| done, closed | the last thing it said |

A tool call is said **in words, never as the raw command**, and only when it has something specific
to say; a call that has none (a command with no description, a tool with no argument) leaves the
line as it was:

| Tool call | The row says |
|---|---|
| Bash | the call's `description` |
| Edit, Write, Read | the file's name: "Editing card-check" |
| Grep, Glob, web search | the pattern: `Searching for "fun toolLine"` |
| Web fetch | the host: "Fetching example.com" |
| an MCP tool | server and tool in words: "lunamux: list tasks" |
| an agent, a skill | "Running an agent: <description>", "Using the <name> skill" |
| a question to the person | the question ("Asks: …") |

A permission prompt still names the exact command, since that is what the person is asked to allow.

### The selected row

The selected row shows more under it, in this order: a task's live conversations, one line each (its
mark, its name, what it says, its age; the main conversation first), then the question before a
resume or a start, or else any extra text: the conversation's folder when it is not simply the
repo's own (a worktree, a subfolder), where a search matched, a failed resume, why ↵ has nothing to
open. A row with nothing to add has no extra line.

### What a restart stopped

A conversation's record says why it ended: Lunamux stopped under it (`restart`), the agent exited,
its pane went, or another conversation took the pane. Between NOW and EARLIER, **STOPPED BY THE
RESTART** lists the conversations Lunamux cut off in the last day that can be resumed, each with the
state it was in ("was working", "was waiting for you", "was done") and the last thing it said, and
with the name of its tab when the tab is still there. ↵ on one asks to resume it as on any closed
row; **⇧↵** resumes all of them at once, each in its own tab when that tab still sits at an idle
shell in the same folder, else in a new one.

### Where ↵ goes

↵ goes to the main conversation by its **conversation id**, the agent's own session id, which a
restart and a resume keep. The main conversation is the one that made the task, or the one named with
`set_task_lead`. The first of these applies:

1. **A conversation of the task waits and the main one does not:** the oldest waiting one is
   reached. In a tab of the strip ↵ jumps to its pane; in a hidden tab the tab is shown (below).
2. **The main conversation is live in a tab of the strip:** jump to its pane ("↵ open").
3. **It is live in a hidden tab:** the tab is shown as in 1, whether it asks or works, and hides
   again when the person leaves it.
4. **It is closed:** ask "conversation closed / Resume it in a new tab?" and resume on a second ↵.
   When its own pane is still open at an idle shell in the same folder, the question is "Resume it in
   its tab?" and the resume types into that pane. The command is the task file's `open` action when there
   is one and the conversation is a Claude one that ran in the action's folder (a command that does
   not name the conversation gets `--resume <id>` after its program word, and only when it is one
   plain `claude` line with no chaining and no `--resume`, `-r`, `--continue`, `-c` or
   `--session-id`). Every other case runs `claude --resume <id>` in the conversation's own folder
   (`codex resume <id>` for Codex when its id is known), also when the server no longer keeps a
   summary of it. `claude --continue` is never used. When the folder is gone the tab opens in the
   nearest folder above that exists and the row says "Its folder is gone. It resumes in <folder>."
   Lunamux never recreates a removed worktree. A conversation with no recorded folder or no id gets an
   error and no tab.
5. **The task has no main conversation:** a live conversation of the task in a tab of the strip is
   jumped to; else ask "no conversation / Start one in a new tab?" and run the task file's `open`
   action on a second ↵; else resume the newest conversation the task has a record of. A task with
   no conversation and no `open` action has no row.

A conversation in no task does the same for itself. The question before a resume or start is a dim
tag ("conversation closed" for a main conversation, "session closed" for another one, "no
conversation" when none exists), the question, and the footer `↵ resume · esc cancel`. esc, ↑ or ↓,
a change to the search text, or a refresh that changes what ↵ would do, cancels it.

### Other keys

The footer is `↵ open · → peek · ↑↓ move · esc close`, with `⇧↵ resume all N` while a restart's section has rows. **→** at the end of the search text peeks the
conversation ↵ would open (**←** or esc goes back). **⌘⌫** asks to close a row that is one live
conversation in a tab of the strip; it never closes a task of several, and it works from search
results too. A working or waiting conversation says what it is doing or asking, and any uncommitted or unpushed
work in its folder, before the person confirms; Cancel has the focus. esc steps out of the question,
then the confirmation, then the peek, then the overlay.

### NOW and EARLIER

- **NOW is the live conversations.** Every live conversation in an open tab has a row, once. A task
  whose main conversation is closed stays in NOW while a background agent of it is live or its status
  says a question waits; otherwise it goes to EARLIER, as does a conversation that has ended, at
  once. A conversation that needs the person stays in NOW wherever it runs, so a question is never
  hidden by the "+ N more" cut or the fourteen-day window.
- **NOW keeps its order**: the order work started, ascending, ties by id. State never moves a row,
  and the overlay opens with the first row that needs the person selected. A task's start is stamped
  the first time the server sees it, so the order survives a restart.
- **EARLIER is newest first** in two bands, **LAST FEW DAYS** (under three days) and **LAST TWO
  WEEKS** (up to fourteen), and shows the newest five rows, then **+ N more** (a click, or ↓ on the
  last row, shows the rest). An empty band has no heading. A task's age is its newest conversation's.
  Both edges count the older side as inside (exactly three days is the first band, exactly fourteen
  still listed). A task with no conversation (only an `open` action) is aged by its start and leaves
  EARLIER fourteen days after the server first saw it. The server keeps the ended conversations of the
  last fourteen days, at most 300, and pushes them to every window; older ones stay in the database
  for search.
- **The footer counts the same thing.** Under the Claude usage each world shows **▲ N waiting** and,
  beside the spinner, the number working, background agents included. The rows' "needs you" always
  add up to the footer's. Known gap: a conversation in no task that is the main conversation of two
  tasks is in both rows, so both read **needs you** while the footer counts it once.

### Agents out of sight

An agent opens a **background tab** with `create_tab`, `create_window` or `run_command` and
`background: true`. The tab is not in the tab strip or list, never becomes the active tab and is
never where a layout lands. Its conversations are live: they count in the footer and in their task's
state word. A background agent gets no row of its own unless it waits for the person and no task
holds it. Search never finds a background conversation. The agent closes its tab with `close_tab`;
a server restart closes them all.

A background tab stays hidden until the person asks for it: ↵ on the row (or on the agent in the
task panel) lists the tab, makes it active and marks it "background agent · hides when you leave".
This works whether the agent asks or works. The tab before it, and its world, is remembered. One
rule hides it again: the person leaves the tab (another tab or world), or no agent in it is live
any more, in which case the remembered tab returns. It does not hide under the person when the
agent works on after an answer. A restart forgets this. No tab ever appears by itself.

## Closing a tab that is working

Closing a tab with a working agent asks once, whichever way the person closes it (the Close row of the
tab's ⋮ menu, the 3D world's cards). The question names the tab, says what the agent is doing and that
closing stops it, and, when the tab holds a task's own session, lists the task's agents in background
tabs, which keep running, and how to pick the conversation up: open the agents overview and choose
the task. Cancel has the focus, esc cancels and ↵ presses the focused button. A tab with nothing
working gets the toolkit's plain "Are you sure you want to close …?", closing a single pane is not
asked about here, and an agent's `close_tab` never asks. (The toolkit's tab-close confirmation
cannot be switched off in the version we use and shows for every tab, so a capture-phase click
handler on the ⋮ menu's Close row asks ours instead of it for a working tab:
`installTabCloseTakeover`.)

## How state is known

Claude Code sessions report through hooks: a permission prompt or elicitation is **needs you**, a
finished turn is **done**. Claude Code sends no event when a prompt is answered or refused or a turn
is interrupted, so what really happened ends the wait, in this order and never by a timer:

- The transcript ends in `[Request interrupted by user]` (or `… for tool use` after a refused tool
  call), written after the last hook: the turn is over, **done**. It holds even when the prompt was
  refused before Lunamux saw it. Lunamux reads the end of the file only when it has changed.
- The screen showed the prompt and now shows work: it was approved, **working**.
- The screen showed the prompt and now shows neither: it left without a go-ahead, **done**.

Each hook also names the `claude` process that ran it. When a second conversation speaks in a pane
whose own conversation is open, it is a **helper** (a reviewer started inside it, say) exactly when
its process runs underneath that conversation's process; it then never becomes the pane's
conversation or a task's main one, and it ends when its process does. A `claude` that is not
underneath takes the pane. Hooks installed by an older build do not name the process (the server
rewrites them at start, and a `claude` already running keeps the old ones); for those the old
rule stands: it is a helper while the pane still shows an agent, unless it is a `/clear` or
`/resume` start.

A prompt that no poll ever showed is settled only by the transcript rule or a hook: a poll can be
taken before the prompt is drawn, and the "background agents" line reads "working" whatever else is
on screen. A prompt nothing has answered keeps waiting as long as it takes. For Codex and Gemini the
state comes from the screen, so it is a best guess and lags a little; Codex's conversation id has not
been confirmed against a real session, so a resumed Codex session may show as a new row.

## Searching sessions

The search box looks through every session ever hosted, however old. A session matches on its own
title, its **task's title**, its first prompt, its branch and links, its conversation or its last
screen, ranked in that order. A match through the task reads "in task title" on the selected row. A
search shows one row per task: the better match stands for the task and the second line says where
("in transcript of Reviewer"). ↵ and → open the session that matched. Search covers closed sessions
and never background ones, and is never cut by the EARLIER window.

## Grouping sessions from an agent

Seven MCP tools are registered. All but `list_tasks` change tasks and need a `read+write` token;
`list_tasks` needs `read`. A session that never calls them is a task of its own.

| Tool | Arguments | What it does |
|---|---|---|
| `create_task` | `title`, `sessionIds?` | Makes a task and puts the named sessions in it. Returns `{ taskId, sessions }` and the main conversation. |
| `add_to_task` | `taskId`, `sessionIds` | Moves sessions into a task. The id can name a task file's task. |
| `remove_from_task` | `sessionIds` | Takes sessions out, so each is a task of its own again. Returns where each one ended up. |
| `rename_task` | `taskId`, `title` | Renames a task made through the tools. A file's task is named by its file, so renaming it is refused. |
| `set_task_lead` | `taskId`, exactly one of `sessionId` or `conversationId` | Names the conversation that speaks for the task. A `sessionId` (the pane's `$LUNAMUX_SESSION_ID`) becomes the conversation running in it now; a `conversationId` is the agent's own id, which a closed conversation still has. `create_task` already names the conversation of the session that made the task. Returns `taskId` and `mainConversation`. |
| `set_task_status` | `taskId`, `text`, `questionWaits?` | Sets the one-line status; empty clears it. Newlines become spaces, the ends are trimmed, at most 120 characters. `questionWaits: true` keeps the row among the live ones while its main conversation is closed; each call sets it afresh, and it survives a restart. |
| `list_tasks` | none | Lists every task, including one nothing is in yet, with its sessions and their states, `mainConversation` (`{conversationId, sessionId?}`, the `sessionId` missing while it is closed), status, `questionWaits` and start. |

`sessionIds` are terminal session ids: the `$LUNAMUX_SESSION_ID` of the pane a session runs in. An
agent's own is in its environment, and `list_sessions` shows the others. `create_window` and
`run_command` also take an optional `taskId`: the agent session that starts in the new pane joins
that task as soon as it appears (`run_command` only when it creates the window itself).

A window made for a task opens where the task already is. The tab is the first that applies: the
`tabId` the call names; else the tab holding most of the task's sessions (or the window a call made
for it earlier that has no agent yet), the first in the tab bar on a tie, only that world's tabs
given a `worldId`; else the active tab.

An agent told to split its work does this: `create_task` with a title and its own
`$LUNAMUX_SESSION_ID` → `taskId`; `create_window` with that `taskId`; the overview then shows one row
reading "2 working", and a conversation that needs the person turns it into ◆ "needs you". Resuming a
conversation keeps its task, even in another pane. A task made through the tools is shown only once a
session is in it, and disappears when it loses its last one.

## Task files

```
~/.lunamux/tasks/<anything>.json
```

`-Dlunamux.tasksDir=/some/folder` watches another folder. A dev server and the real Lunamux read the
same folder unless an override is set, so a dev server must always set one. Files end in `.json`;
names starting with `.` are ignored, so a writer can build a file under a dot-name and rename it
into place (the safe way to replace one: Lunamux notices changes by modified time and size, about
every three seconds). To remove a task, delete its file; its sessions become tasks of their own
again unless the task was also made through the tools.

```json
{
  "id": "access-control",
  "title": "Access control",
  "repo": "work-app",
  "open": {
    "command": "claude --resume access-control",
    "folder": "~/code/work-app"
  }
}
```

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | Unique, stable id. If two files use one id the newer wins and a warning is logged. A task made through the tools with the same id is the same task, and the file's title wins. |
| `title` | yes | The task's name. |
| `repo` | no | Repository name shown beside the title. |
| `open` | no | The task's **open** action. |
| `open.command` | if `open` | Typed into a new shell (and ↵) when the row has no conversation. |
| `open.folder` | if `open` | Absolute path, or one starting with `~/`. The new tab opens here. |

Other keys are ignored; a file from an earlier version (`version`, `tag`, `link`, `updatedAt`,
`lead`, `members[]`) still parses and those keys do nothing. A file that cannot be used (broken JSON,
a missing `id` or `title`, an `open` without a command or with a relative folder) is **skipped
whole** with one warning in the server log per change; other tasks are unaffected and a fixed file is
picked up within about three seconds. A task with a file but no session shows a row only when the
file declares `open`.

With `open`, ↵ on a row with no conversation (after "Start one in a new tab?") types `open.command`
in a new tab started in `open.folder`, named like the task. Lunamux only types the command; it does
not tie the new session to the task, so the command has to join the task itself (for example by
starting an agent that calls `add_to_task`). Otherwise the new session gets a row of its own.

**Trust:** `open.command` is typed into the user's shell. Anyone who can write into the folder can
run a command as the user, so only the user's own tools should write there. The command comes from the
file on the server and is never taken from the browser.

## The task panel

A 320px column on the right of the window showing what a task's agents wrote about it. It shows by
itself in the tab where the task's main conversation is live. ⌘I opens it in any other tab too,
including a lone session or a plain terminal; there it holds for that tab only and is not kept across
a restart. It shows the task of the focused session, else of the main conversation in the tab. The
header reads "<title> · ⌘I hides" with a × at the end; ⌘I and the × both hide it, and beside a main
conversation the choice is kept with the other UI settings. The header says where the work is: the
tab's name (the title, when the tab has none), the title in full under it, the repo and when it
started, then the state (mark, word, age) and the headline, which are the overview row's own. The shortcut is listed in the
Keyboard-shortcuts sidebar and can be rebound there (off macOS the default is Ctrl+I, which a terminal
reads as Tab).

Four blocks, in this order, each headed by its name alone:

1. **Plan**: a bar with "x of y" beside it, then the step in hand. When an agent wrote the plan's
   steps (a `steps` block) they are listed under the bar: the finished ones folded into "N earlier
   steps" but for the last, the one in hand bright with the spinner, the rest dim; the count then
   comes from the steps.
2. **Background agents**: the task's live agents but for the main conversation. Each line has its
   state mark (the spinner, ◆ when it waits, ○ when idle), its title with its age at the right and,
   as a quieter second line that never wraps, what it is doing: its own status (`set_status`), else
   its tool call in words, else the plain state word. Lines can be reached with Tab and pressed; on
   hover or focus a line shows what ↵ does.
3. **Activity**: under a heading for each day ("Today", "Yesterday", "Mon 9 Nov"), one line per
   item with its time at the left; a long item wraps under itself. Newest first.
4. **Links**: newest first, each with the writer's kind word when one was given (up to 8
   characters), its label as a link (accent colour, ↗, underlined on hover) and its time. Links
   without a time come after the dated ones and show none. Pressing one opens it.

In the tab of **a session that has no task** the panel shows what Lunamux sees by itself: the same
header, then **You asked** (each thing the person asked in that conversation, its time or day, and
how long the turn took, read from the transcript) and **Work in its folder** (uncommitted changes
and unpushed commits), then how to ask the agent for a plan, links and activity.

Every list shows five entries, then **+ N more**, which opens the rest in place; the panel scrolls.
The blocks agents write are kept in the database, survive a restart and are not removed when the task
ends. Only the types below exist; any other is refused.

| Tool | Arguments | What it does |
| --- | --- | --- |
| `set_status` | `text`, optional `sessionId`, `conversationId` | Says in one line what a conversation is doing this turn (120 characters; empty clears it). It shows as its headline while it works and goes with the next prompt. An agent started inside another conversation's pane passes its own `conversationId`. |
| `set_task_panel` | `taskId`, `blocks`, optional `by` | Replaces all of the task's blocks in the order given. Nothing changes if any block breaks a rule. |
| `upsert_task_block` | `taskId`, `block`, optional `by` | Replaces the block with that id where it stands, or adds it at the end. |
| `remove_task_block` | `taskId`, `id` | Removes one block. |
| `get_task_panel` | `taskId` | Reads the blocks back (read-only). |

`by` names the writer (the calling session's title when missing, at most 40 characters). It is kept
with the block and not shown. The panel follows each call within a second. A block has an `id`
(letters, digits, `.`, `_`, `:`, `-`; at most 64; `agents` is reserved), a `type`, an optional `title`
(80) and the fields of its type:

| `type` | Fields | Limits |
|---|---|---|
| `links` | `items`: `{kind?, label, url, at?}` | 30 items; label 80; `http`, `https` or `file` addresses only, 2000 characters; `at` is an ISO time or epoch milliseconds. A link stored before `kind` and `at` existed reads as kind `link` with no time. |
| `timeline` | `items`: `{at, text}` | 50 items; `at` an ISO time or epoch milliseconds; text 200. |
| `progress` | `done` and `total`, and/or `phase`; optional `label` | A bar when there is a count. Phase and label 200. |
| `steps` | `items`: `{text, state?}` | The plan in order; `state` is `done`, `current` or `todo` (`todo` when none). 60 steps; text 200. The panel lists and counts them, so no `progress` block is needed beside it. |

A panel holds at most 24 blocks. Every refusal names the block, field or id at fault. A stored block
of a type that no longer exists is skipped when the server starts; the rest of the panel is kept.

## Trying it out and checking it

`scripts/seed-agents-overview.sh <db file> <tasks folder>` fills a scratch database and folder with
ended conversations (last active within the hour and 2, 6, 12 and 30 days ago), three agent-made
tasks, two task files and some awkward rows to name and read, and turns the usage poll off so a
scratch server never launches the real `claude`. Seed while the scratch server is stopped, then
start it on its own port with a throwaway `-Duser.home` and `-Dlunamux.dbPath`, `-Dlunamux.tasksDir`
and `-Dlunamux.claudeProjects` pointing at scratch paths. `scripts/seed-agents-overview-live.sh
<server> <moment>` then puts live conversations in tabs and makes the moments that need a running
agent happen on that server. The seeder's code is `server/src/seed/`, its own Gradle source set, run
by `./gradlew :server:seedAgentsOverview`.

`./gradlew :client:overviewChecks -Purl=<server> -Ptoken=<device token> [-Pwatch=<seconds>]
[-Pmain=<task id>=<conversation id start>]` connects as an ordinary client, sends no command and
prints one PASS or FAIL per rule, exiting non-zero on a FAIL. The token must be one the server trusts
as a device. It checks that:

1. the footer count equals the sum of "needs you" on the rows;
2. every live conversation in a visible tab is in NOW exactly once, and a background agent has a row
   only while it waits;
3. no two rows are the same conversation;
4. ↵ on a task's row reaches or resumes its main conversation by id (`-Pmain` names the conversation a
   task must have), or leads to one of the task that asks, or, when the main one is in a hidden tab
   and does not ask, to one in a tab of the strip; every target belongs to the task;
5. every needs-you leads to a visible question, showing the tab when it is hidden;
6. no hidden tab appears on its own (`-Pwatch` keeps watching the layout);
7. the panel's agents are only working or waiting ones, and each list has at most five entries
   before "+ N more".

That a spinner never restarts when its row redraws is checked by `SpinnerTest` (`./gradlew
:web:jsBrowserTest`, in headless Chrome).
