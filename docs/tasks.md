# Tasks

The agents overview (⌘⇧A) shows the **conversations** that are live in Lunamux, each as one row,
and the **tasks** they belong to. A task is one piece of work: a release, a refactor split across
branches, a batch of reviews, or just one chat. A conversation is one Claude or Codex dialogue; it
keeps its row when it is resumed with `claude --resume` and when Lunamux restarts, because the row is
the conversation, not the pane it happens to run in. A task has one **main conversation**, the one
that speaks for it, and that conversation's row stands for the whole task.

A session is in a task when it carries the task's id, and nothing else decides it. Agents put their
sessions in a task through the Lunamux MCP tools below; nobody has to write a file. A **task file**
is optional: it gives a task a title and, if wanted, an `open` action that starts work for it. It
lists no sessions. A tool outside Lunamux writes one JSON file per task into a folder Lunamux
watches, and Lunamux only ever *reads* these files.

## What the person sees

The overview is one aligned list with **one row per conversation**. A conversation that is in no
task is a row of its own. The **main conversation** of a task carries the task's row, and the task's
other agents are not rows: a **background agent** (one that works in a tab out of sight) is only
counted, in the row's state word ("2 working") and in the task panel. An agent that runs in a tab of
the strip and belongs to a task also shows on the task's row, not as a row of its own. A row has no
mark but one: **◆** at the start of a row that needs the person (the selected row shows **▸** there
instead). Rows are told apart by brightness and by the colour of the state word: a row that works or
needs the person has a bright title, a done one a mid-grey title, and a finished one in EARLIER a dim
one.

A row reads, left to right: the **title**, **where** (the task's repo, else the folder the
conversation runs in, home as `~`), one **state word**, a **detail** and the **age**. A conversation
is in exactly one of three states: **working**, **needs you** or **done**. A finished turn is done;
Lunamux never guesses from the last words of a reply that the agent is asking. The state word is
one of:

| Word | When |
|---|---|
| **needs you** (yellow) | one conversation waits for the person; with more than one it reads "2 need you" |
| **N working** (cyan, with the moving braille glyph before it) | two or more of the task's conversations are moving, background ones counted |
| **working** (cyan, with the glyph) | one conversation is moving |
| **done** (muted green) | the row is in NOW and nothing is moving or waiting |
| **finished** (dim) | the row is in EARLIER and nothing is moving or waiting |

**Needs you** always means a question the person can see and answer: a permission prompt or an
elicitation that Claude Code reports, an `AskUserQuestion` call, or the approval overlay of Codex.
One question counts once. Only a conversation that has a pane in a window counts, in the footer and in the
state words alike: a conversation that has ended, or whose pane the window no longer holds, is counted
nowhere, and a task whose conversations all lack a pane sits in EARLIER and reads **finished**. Known gap: a conversation that carries no task id and is the main conversation of two tasks is in both rows, so both can read **needs you** while the footer counts it once. A reply that merely ends in a question ("Want me to go ahead?") is done,
and so is a conversation that was just resumed and sits at its prompt.

The glyph is the app's own working mark: four braille frames that cross-fade
about three times a second. With reduced motion it is a still frame. It is always drawn at regular
weight, not the bold of the word beside it: the system's braille font draws a ring at all six dot
positions in a bold weight, and the glyph is meant to show two dots turning. Its frame is read from
the clock, never from when the row was drawn, so a row that is redrawn (a new status line, a new
age) keeps the frame it had, and the footer's spinner shows the same frame as the rows.

A conversation is *moving* while it works and has shown a sign of life in the last five minutes. One that works but
has gone quiet reads as not moving, so a task of quiet conversations reads **done**.

The **detail** is the first of these that exists: the task's status line, the question of the
conversation that waits (in quotes), what a moving conversation is doing, or nothing. The folder
and the detail are hidden when the panel is narrower than 700px.

A question counts only while the conversation that asks has a pane. When the main conversation is
closed, its last question is not counted; ↵ resumes the conversation, which asks again, and the
count rises then. A task can say so through its status (`set_task_status` with `questionWaits: true`):
the row then stays in NOW with the status as its detail and reads **done**, without counting as
waiting, until a later status drops the flag.

What a moving session is doing is said **in words, never as the raw command**, because a column of
shell commands tells nobody anything at a glance. Lunamux words each tool call as it arrives:

| Tool call | The row says |
|---|---|
| Bash | the `description` Claude Code sends with the call ("Run the server tests"); without one, "Running" and the program's name ("Running gradlew") |
| Edit, Write, Read | the file's name only: "Editing card-check", "Writing notes.md", "Reading tick.py" |
| Grep, Glob, web search | the pattern: `Searching for "fun toolLine"` |
| Web fetch | the host: "Fetching example.com" |
| an MCP tool | the server and the tool in words: "lunamux: list tasks" |
| an agent, stopping a background task, the task list | "Running an agent: <its description>", "Stopping a background task", "Updating the task list" |
| a tool Lunamux does not know | "Using <its name>", never its arguments |
| a question put to the person | the question itself ("Asks: …"; a row that needs you shows it in quotes) |

A permission prompt is the exception: "Allow Bash ./gradlew test?" still names the exact command,
since that is what the person is asked to allow.

- **The selected row has more under it.** In this order: the whole status, only when the row cut
  it short (it ends in "…") or hides it in a panel narrower than 700px, so the same words are
  never shown twice; then up to two chips about the session ↵ goes to; then any text the model
  put on the row (where a search matched, the message of a resume that failed, why ↵ has
  nothing to open for a conversation in a background tab). The **folder chip** is that session's working folder, the
  home folder written `~`. The **tab chip** names the session's tab, and shows only when the
  tab's name does not already contain every word of the row's title (a word is a run of letters
  and digits; case and punctuation are ignored; whole words only, no fuzzy matching); a tab in
  another world adds the world's name after it ("Checkout · Work"). There is no other chip, and
  a row with nothing to put under it has no extra line, never an empty one.
- **↵ goes to the main conversation, by its conversation id.** The row's main conversation is the
  task's `mainConversation`: the conversation that made the task, or the one an agent names with
  `set_task_lead`. It is stored by the agent's own session id, which a restart and a resume keep, and
  never by the pane it ran in (pane ids are handed out again at every start). Return does the first
  of these that applies:
  1. **A conversation of the task waits for you, and the main conversation does not:** the oldest
     waiting one is reached. In a tab of the strip ↵ jumps to its pane; in a hidden tab the tab is
     shown (see "A waiting agent in a hidden tab" below). A jump is the ordinary "↵ open"; only a shown
     tab has the hint "↵ answer it · opens as a tab until it works on". A working main conversation
     never hides a waiting member this way.
  2. **The main conversation is live in a tab of the strip:** jump to its pane. The footer reads "↵ open".
  3. **The main conversation is live in a hidden tab:** while it asks, its tab is shown as in 1. While it
     does not ask there is nothing to answer there and no tab to jump to, so ↵ goes to a conversation
     of the task in a tab of the strip (the one with the freshest sign of life), else offers nothing:
     the row says "it works in a background tab: ↵ has nothing to open until it asks" while the
     conversation works, and "it has finished in a background tab: ↵ has nothing to open, → shows
     what it said" once it is done; the footer leaves ↵ out, and → peeks at the conversation. A
     hidden tab opens only for a waiting agent, so a finished main conversation in a hidden tab
     can be read by the peek but not opened by ↵; nothing here changes that.
  4. **The main conversation is closed:** ask "conversation closed / Resume it in a new tab?"; a
     second ↵ resumes it in a new tab. When the task's file declares an `open` action, that action's
     command is typed in the action's folder, so the conversation starts the way the task always
     starts it: a command that names the conversation (`--resume <id>`) is typed as written, and one
     that does not gets `--resume <conversation id>` after its first word. Only a Claude conversation
     takes the declared command, and only its task's main conversation does: any other conversation,
     a task without an `open` action (every task an agent made) and a declared folder that is gone
     run `claude --resume <conversation id>` in the conversation's own folder (`codex resume <id>` for
     a Codex conversation whose id is known). A declared command that begins with something other than
     the program (an environment assignment, a `cd`) gets `--resume` in the wrong place; write the
     program first. The same holds when the server no longer keeps a summary of the conversation: it
     is resumed by the id the task stores. `claude --continue` is never used: it picks the newest
     conversation of the folder, not this one.
  5. **The task has no main conversation on record:** a live conversation of the task in a tab of the
     strip is jumped to (the one waiting longest, else the busiest); else ask "no conversation /
     Start one in a new tab?" and run the task file's `open` action on a second ↵; else the newest
     conversation the task has a record of is resumed after the same kind of question. A task with
     no conversation and no `open` action has no row.
  A row for a conversation that is in no task does the same for itself: it jumps to a live one in a tab
  of the strip, shows the tab of a waiting one in a hidden tab, and resumes a closed one. A resumed
  conversation is the task's main conversation again, in the same row.
  `list_tasks` reports `mainConversation: {conversationId, sessionId?}`; the `sessionId` is the pane
  the conversation runs in now and is missing while it is closed.
- **A question before a resume.** When the conversation ↵ would go to is not running, the first ↵
  turns the selected row's extra part into a question: a dim tag, "Resume it in a new tab?", and
  "↵ resume · esc cancel" (the footer reads the same). The tag says what is missing and never
  sounds like the state of the whole task: "conversation closed" for the main conversation,
  "session closed" for any other ended conversation. When there is no conversation at all the tag is
  "no conversation" and the question "Start one in a new tab?" (↵ start). A second ↵ does it. esc, ↑ or
  ↓, a change to the search text, or a refresh that changes what ↵ would do, cancels the question.
- **Two keys are not hinted.** **→** at the end of the search text peeks the conversation ↵ would
  open (**←** or esc goes back). **⌘⌫** asks to close a row that is one conversation, live in a
  pane; it never closes a task of several, and does nothing while the search box has text. The
  question is the same as for closing a tab that is working. The footer is `↵ open · ↑↓ move · esc
  close` (`↵ resume · esc cancel` while the question is up). esc closes the question, then the
  confirmation, then the peek, then the overlay.
- **A task has a one-line status.** An agent sets it with `set_task_status` and the row shows it as
  its detail. Line breaks become spaces, the ends are trimmed, and at most 120 characters are
  kept. An empty text clears it. Passing `questionWaits: true` says a question waits for the person:
  the flag is stored with the status, `list_tasks` and the task's `questionWaits` report it, every
  call sets it afresh (a status without it, or an empty text, clears it) and it survives a restart.
- **NOW is the live conversations.** Every live conversation in a tab of the strip has a row in NOW,
  once. A task's main conversation carries the task's row. A task whose main conversation is closed
  stays in NOW while a background agent of it is live (it reads "2 working") or while its status says
  a question waits; otherwise it goes to EARLIER. A conversation that needs the person stays in NOW wherever it runs, because a question
  must not wait where the **+ N more** cut or the fourteen-day window can hide it. A tab counts as
  open whatever the tab strip shows of it. There is no grace period: a conversation that has ended
  is in EARLIER at once.
- **Agents can work out of sight.** An agent opens a **background tab** with `create_tab`,
  `create_window` or `run_command` and `background: true`. The tab is not in the tab strip or the
  tab list, never becomes the active tab and is never where the layout lands on its own. Its
  conversations are live: they count in the footer and in their task's state word, and one that
  needs the person still asks for the person. They are counted, not listed: a background agent
  gets no row of its own while it works or rests; a task that holds it shows it. One that waits for
  the person and that no task holds has a row in NOW, and ↵ on it lists its tab (see below). A search never finds a background conversation while it is out of sight. A
  background tab is in no tab list, so the agent that opened it closes it with `close_tab` when
  its work is done; a server restart closes every background tab, as the agents in them ended with
  the old process.
- **A waiting agent in a hidden tab.** When a background agent asks the person, the tab stays hidden
  until the person asks for it. ↵ on the row (or on the agent in the task panel) lists the tab, makes
  it the active tab and marks it with the header "background agent · hides again once it works on".
  The tab before it, and its world, is remembered. When the agent leaves needs-you, by an answer or by a refusal, the
  tab hides again and the remembered tab and world return. A server restart forgets all of this. No other tab
  ever appears by itself.
- **NOW keeps its order.** Rows are in the order their work started, ascending, a task at the
  start of its first conversation, ties by id. The state never moves a row: a conversation that starts
  needing the person stays where it was, and the overlay opens with the selection on the first row in
  list order that needs the person. A task's start is stamped the first time the server sees it
  and never changes, so the order survives a restart. EARLIER is newest first.
- **EARLIER shows the five newest, then "+ N more".** **LAST FEW DAYS** holds the rows last active
  within three days and **LAST TWO WEEKS** those between three and fourteen days old. Together
  they show the newest five rows, then one **+ N more** line (a click on it, or ↓ on the last row,
  shows the rest). A band with nothing to show has no heading. Older rows are not listed, but
  search still finds older *conversations*, and search is never cut. A task with no conversation
  (only an `open` action) is aged by its start, which never moves: fourteen days after the server
  first saw it, its row leaves EARLIER and search does not return it, even if the file was
  rewritten since. It comes back as soon as the task has a conversation.
  - A task's age is its newest conversation's.
  - Both edges are inclusive on the older side: exactly three days old is still in the first band,
    exactly fourteen still listed.
  - The server keeps the ended conversations of the last fourteen days, at most 300, and pushes them
    to every window; older records stay in the database for search only.
- **The footer counts the same thing.** Under the Claude usage in the footer, each world shows
  **▲ N waiting** for the conversations that need the person and, beside the spinner, the number that
  work. Background agents are counted. The count of "needs you" on the rows always adds up to
  the footer's. There is no counter in the top bar.
  Paused conversations never count as needing the person.

## Closing a tab that is working

Closing a tab whose session is working asks first, whichever way the person closes it (the × on the
tab, the tab list). The question names the tab, says the agent stops, and when the tab holds a
task's own session it lists the agents that task has in background tabs, which keep running, and how
to pick up the conversation: open the agents overview and choose the task. Cancel has the focus, Esc
cancels and ↵ acts on the focused button. A tab with nothing working in it closes at once, closing a
single pane is not asked about, and an agent's `close_tab` never asks.

Sessions of Claude Code report their state through hooks: a prompt that asks for permission or
elicits an answer is **needs you**, a finished turn is **done**, and nothing is guessed from the text of
a reply. Claude Code sends no event when a permission prompt is answered or refused, so the screen
ends the wait: once Lunamux has seen the prompt on screen, work on screen means it was approved (the
row is working) and a screen with neither the prompt nor work means it was refused (the row is done).
A prompt refused before Lunamux's next look at the screen (about three seconds) was never seen, so the
row keeps waiting until the conversation's next event; Lunamux does not clear it on a timer, because a
question it failed to recognise on screen would then vanish from the count while still unanswered.
For Codex and Gemini the state comes from reading the screen, so it is a best guess and lags a
little. Codex is its own conversation per session: its own conversation id has not been confirmed
against a real session, so a resumed Codex session may show as a new row.

## Searching sessions

The search box over the list looks through every session ever hosted, however old. A session
matches on its own title, its **task's title**, its first prompt, its branch and links, its
conversation or its last screen, and results are ranked in that order. A match through the task
reads "in task title" on the selected row's second line, so a session whose own words
never mention the work can still be found by what the work was called. A session is found through
its task when it carries the task's id.

A search shows one row per task: when two sessions of a task match, the better match stands for
the task and the second line names where and in which session it matched ("in transcript of
Reviewer"). ↵ and → on a result open the session that matched. A search covers closed sessions and
never the background ones.

## Trying it out

`scripts/seed-agents-overview.sh <db file> <tasks folder>` fills a scratch database and folder
with about twenty ended conversations (last active today, 1, 2, 6, 12 and 30 days ago), six
agent-made tasks (one with its own main conversation, a status and two background agents) and six
task files. Seed while the scratch server is stopped, then start it on its own port, with a
throwaway `-Duser.home` (the settings file lives there), `-Dlunamux.dbPath`, `-Dlunamux.tasksDir`
and `-Dlunamux.claudeProjects` pointing at scratch paths. The script's
header lists what to look at, and the cases that need a running agent.
`scripts/seed-agents-overview-live.sh <server> <token> <moment>` then makes the usual case (1), a
background agent that asks (4) or a closed main conversation with two agents working (5) happen on
the running server, with a panel of four blocks that has more than five of each.

## Checking it

`./gradlew :client:overviewChecks -Purl=<server> -Ptoken=<device token> [-Pwatch=<seconds>]
[-Pmain=<task id>=<conversation id start>]` connects as an ordinary client, never sends a command,
and prints one PASS or FAIL line per rule, exiting non-zero on a FAIL. The token must be one the
server already trusts as a device (not the tool-endpoint token). It checks that:

1. the footer count equals the sum of "needs you" on the rows;
2. every live conversation in a visible tab is in NOW exactly once, and a background agent has a row only while it waits for you;
3. no two rows are the same conversation;
4. Return on a task's row reaches or resumes its main conversation by conversation id (`-Pmain` names
   the conversation a task must have), or leads to another conversation of the task that asks, or, when
   the main conversation runs in a hidden tab and does not ask, to one in a tab of the strip. A
   conversation Return jumps to is never in a hidden tab, every target (a shown tab included) belongs to the task, and a row that opens nothing is right only while the main conversation is in a hidden tab;
5. every needs-you leads to a visible question: the row's Return opens a conversation that asks, and
   shows the tab when that conversation is in a hidden one. When two conversations wait on one row,
   Return opens one (the main conversation first) and the other is reached once it is answered;
6. no hidden tab appears on its own: with `-Pwatch` the checker keeps watching the layout, and a tab
   that becomes listed without a mark, or stays listed after nothing in it waits, fails;
7. the panel's agents are only working or waiting ones; and the panel is four blocks with at most five
   entries before "+ N more".

That a spinner never restarts when its row redraws is checked by `WorkingGlyphRedrawTest` (run by
`./gradlew :web:jsBrowserTest`: a keyed spinner is drawn again every 100 ms for two seconds in a
headless Chrome, and its frame must follow the clock and only ever advance one place), by
`WorkingGlyphStyleTest` (no rule restarts the animation) and by a page probe that logs the frames
for thirty seconds. The same browser tests hold the space between the panel's activity entries
(`TaskPanelSpacingTest`).

## Grouping sessions from an agent

Seven MCP tools are registered. All but `list_tasks` change tasks and need a `read+write` token;
`list_tasks` needs only `read`. A session that never calls them is simply a task of its own.

| Tool | Arguments | What it does |
|---|---|---|
| `create_task` | `title`, `sessionIds?` | Makes a task and puts the named sessions in it. Returns `{ taskId, sessions }`. |
| `add_to_task` | `taskId`, `sessionIds` | Moves sessions into a task. The id can name a task file's task: the session is then in that task. |
| `remove_from_task` | `sessionIds` | Takes sessions out, so each is a task of its own again. Returns where each one ended up. |
| `rename_task` | `taskId`, `title` | Renames a task made through the tools. A file's task is named by its file, so renaming it is refused with that reason. |
| `set_task_lead` | `taskId`, and exactly one of `sessionId` or `conversationId` | Names the session that speaks for the task. A `sessionId` (the pane's `$LUNAMUX_SESSION_ID`) is turned into the conversation running in it now; a `conversationId` is the agent's own session id, which a closed conversation still has. Refused for an unknown task, pane or both arguments. `create_task` already names the conversation of the session that made the task (the pane named in the `X-Lunamux-Session` header, else the first one in `sessionIds`). Returns the `taskId` and the `mainConversation` now named. |
| `set_task_status` | `taskId`, `text`, `questionWaits?` | Sets the task's one-line status; empty clears it. Newlines become spaces, the ends are trimmed, at most 120 characters. `questionWaits: true` says a question waits for the person, which keeps the task's row among the live ones while its main conversation is closed; each call sets it afresh. |
| `list_tasks` | none | Lists every task with its sessions and their states, including a task nothing is in yet, with each task's `mainConversation` (`{conversationId, sessionId?}`), status, `questionWaits` and start. |

`sessionIds` are terminal session ids: the `$LUNAMUX_SESSION_ID` of the pane a session runs in.
An agent's own id is in its environment, and `list_sessions` shows the others. `create_window`
and `run_command` also take an optional `taskId`: the agent session that starts in the new pane
then joins that task as soon as it appears, even though no agent runs there yet when the window
is created. `run_command` takes it only when it creates the window itself; with a `sessionId` it
types into a pane that already exists.

A tab is one task's workspace, so a window made for a task opens where the task already is. The
tab is the first of these that applies:

1. The `tabId` the call names. An explicit tab always wins.
2. The tab the task's sessions are in: where its open sessions are, or the window a call made
   for it earlier that has no agent yet. If they are in several tabs, the one holding most of
   them, and the tab first in the tab bar on a tie. Given a `worldId`, only that world's tabs
   count.
3. The active tab, as for a call that names no task.

An agent told to split its work in two does this:

1. `create_task` with a title and its own `$LUNAMUX_SESSION_ID` in `sessionIds` → `taskId`.
2. `create_window` with that `taskId`, then start the second agent in the new pane.
3. The overview now shows one row for the task, reading "2 working", and a conversation that needs
   the person turns the row into a ◆ "needs you".

Resuming a conversation keeps its task, even in another pane. A task made through the tools is
shown only once a session is in it; a task that loses its last session disappears.

## Task files

```
~/.lunamux/tasks/<anything>.json
```

Set `-Dlunamux.tasksDir=/some/folder` to watch another folder. A dev server and the real Lunamux
read the same folder unless an override is set, so a dev server must always set one. Files must
end in `.json`; names starting with `.` are ignored, so a writer can build a file under a dot-name
and rename it into place.

A task file declares a task: its id, its name and, if wanted, how to start work for it. It names no
sessions. The sessions of a task are the ones that carry its id, put there through the tools above;
a session that merely runs in the folder the file opens in is not taken into the task and keeps its
own row.

### Format

One object per file.

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
| `id` | yes | Unique, stable id of the task. If two files use one id, the newer file wins and a warning is logged. A task made through the tools with the same id is the same task, and the file's title wins. |
| `title` | yes | The task's name. |
| `repo` | no | Repository name shown beside the title. |
| `open` | no | The task's optional **open** action, see below. |
| `open.command` | if `open` | Typed into a new shell (followed by ↵) when the header's ↵ has no open session to jump to. |
| `open.folder` | if `open` | Absolute path, or one starting with `~/`. The new tab opens here. |

Other keys are ignored. A file written for an earlier version, with `version`, `tag`, `link`,
`updatedAt`, `lead` or `members[]`, still parses; those keys do nothing. Paths must be absolute or
start with `~/`; a relative path makes the file invalid.

A file that cannot be used (broken JSON, a missing `id` or `title`, an `open` without a command or
an absolute folder, a relative path) is **skipped whole**, with one warning in the server log each
time the file changes. The other tasks are unaffected, and a later fixed version of the file is
picked up within about three seconds.

A task that has a file but no session yet shows a row only when the file declares `open`: that row
is how a person starts the work. A task with neither has no row.

### The `open` action

`open` is optional. With it, ↵ on a row that has no conversation at all (after the question "Start
one in a new tab?") types `open.command` into a new tab started in `open.folder`. The tab is named
like the task. Without it, a task with no conversation has no row.

Lunamux only types the command; it does not tie the new session to the task. The command has to join
the task itself (for example by starting an agent that calls the `add_to_task` MCP tool).
Otherwise the new session gets a row of its own, and the task's row stays without a conversation.

### Writing and removing files

- **Replace atomically.** Write the new content to a temporary name (starting with `.`) in the
  same folder and rename it over the old file. Lunamux notices changes by modified time and size
  and re-reads about every three seconds.
- **To remove a task**, delete its file. The task disappears within a poll; its sessions become
  tasks of their own again, unless the task was also made through the tools. Lunamux keeps nothing
  about the file.

### Trust

`open.command` is typed into the user's shell when they press ↵ on the header. Anyone who can
write into the folder can therefore run a command as the user. Only the user's own tools should
write there, and the folder should not be shared or writable by others. The command comes from
the file on the server and is never taken from the browser.

## The task panel

A task can have a panel: a 320px column on the right of the window that shows what its agents
wrote about it. It appears only in the tab where the task's main conversation is live (a task with
none has no panel), and in no other tab. Its header reads "<task title> · ⌘I hides" with the × at the far end. ⌘I shows and hides it, and so does its ×; the choice is
kept with the other UI settings. The shortcut is listed in the Keyboard-shortcuts sidebar and can be
rebound there. (Off macOS the default chord is Ctrl+I, which a terminal reads as Tab; rebind it there
if that gets in the way.)

The panel is **four blocks and no other**, in this order, each headed by its name alone (no writer,
no byline):

1. **Agents** lists the task's agents that work or wait, and only those: each with its state mark
   (the working glyph, or ◆ when it waits), its name, its age at the right and, under the name, what
   it is doing in words, never a tool name. A waiting agent shows the question it asks. An agent
   that has finished is not listed. Each line is a row you can reach and press: click the panel,
   then Tab to move between lines. A line shows what ↵ does once it is hovered or focused: for an
   agent in a hidden tab "↵ answer it · opens as a tab until it works on" (the tab appears, marked,
   until the agent works on); for one that already sits in a tab of the strip "↵ open", which jumps to
   that tab.
2. **Progress** shows the task in hand above a bar, and "x of y" below it. It is built from the
   `progress` block.
3. **Links** takes any kind of link: a Slack thread, a GitHub pull request or issue, a Claude
   artifact, a document, a brief, and each of the owner's decisions linking to its record. Each row
   shows the kind, label and time its writer sent with it, newest first; a link sent without a time
   comes after the dated ones, in the order written, and shows no time. Lunamux knows no list of
   kinds: it shows the writer's word as sent (up to 8 characters), `link` when there is none. It is
   built from every `links` block.
4. **Recent activity** has one line per item with the time at the left and the text beside it; a
   long item wraps under itself, and the next item stands one full line below it. It is built from
   the `timeline` block, newest first.

Every list shows five entries and then **+ N more**, which opens the rest in place; the panel
scrolls. A panel with 19 agents, 19 links and 19 lines of activity shows five of each and "+ 14 more".

The blocks agents write are kept in the database and survive a restart; they are not removed when
the task ends. Blocks of the other types below are stored and read back by `get_task_panel`, but
the panel does not draw them.

| Tool | Arguments | What it does |
| --- | --- | --- |
| `set_task_panel` | `taskId`, `blocks`, optional `by` | Replaces all of the task's blocks, in the order given. Nothing changes if any block breaks a rule. |
| `upsert_task_block` | `taskId`, `block`, optional `by` | Replaces the block with that id where it stands, or adds it at the end. |
| `remove_task_block` | `taskId`, `id` | Removes one block. |
| `get_task_panel` | `taskId` | Reads the blocks back (read-only; needs only a read token). |

`by` names the writer; without it the calling session's title is used, else nothing (at most 40
characters). It is kept with the block, and the panel does not show it. The panel follows each call
within a second.

A block is an object with an `id` (letters, digits, `.`, `_`, `:` and `-`; at most 64; `agents` is reserved),
a `type`, an optional `title` (80) and the fields of its type:

| `type` | Fields | Limits |
| --- | --- | --- |
| `text` | `text` | 4000 characters of markdown: paragraphs, `#` to `###` headings, `-` / `1.` lists, fenced code, `**bold**`, `*italic*`, `` `code` `` and `[label](url)`. Anything else, HTML included, is shown as written. |
| `facts` | `items`: `{label, value}` | 30 items; label 80, value 200. |
| `links` | `items`: `{kind?, label, url, at?}` | 30 items; `kind` is one short word of up to 8 characters, `link` when missing or blank; label 80; `http`, `https` or `file` addresses only, 2000 characters; `at` is when the linked thing was made or changed, an ISO time or epoch milliseconds. Drawn with its time of day (with the day when it is not from today). A link stored before `kind` and `at` existed reads as kind `link` with no time. |
| `timeline` | `items`: `{at, text}` | 50 items; `at` is an ISO time or epoch milliseconds; text 200. Shown newest first. |
| `progress` | `done` and `total`, and/or `phase`; optional `label` | A bar when there is a count. |
| `actions` | `buttons`: `{id, label, text}` | 8 buttons; label 40, text 500, one line. |

A panel holds at most 24 blocks. Every refusal names the block, field or id at fault.

An `actions` block is stored and a pressed button still types its `text` and Enter into the task's
main conversation, exactly as if the person had typed it there, but the panel draws no buttons. The
text is the one stored with the block; the window only says which button was pressed. The server
refuses quietly while that conversation has ended, so the text never runs as a shell command.
