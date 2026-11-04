# Tasks

The agents overview (⌘⇧A) shows **tasks**, not sessions. A task is one piece of work: a release,
a refactor split across branches, a batch of reviews, or just one chat. Every agent session
belongs to exactly one task, and a task is always one row, however many sessions it holds.

There are two ways a session gets into a task, and they can be mixed:

- **Agents group their own sessions.** An agent that splits its work across a second session
  says so through the Lunamux MCP tools below. Nobody has to write a file.
- **A tool that runs sessions Lunamux does not host declares them in a task file.** A
  scheduler, an orchestrator or a CI runner writes one JSON file per task into a folder Lunamux
  watches. Lunamux only ever *reads* these files.

## What the person sees

The overview is one aligned list with **one row per task**. A session that is in no task is a task
of one, so it is a row too; a session that belongs to a task is never a row of its own, whether it
runs in a tab or in the background. A row has no mark but one: **◆** at the start of a task that
needs the person (the selected row shows **▸** there instead). Rows are told apart by brightness
and by the colour of the state word: a task that works or needs the person has a bright title, a
done one a mid-grey title, and a finished one in EARLIER a dim one (a task in EARLIER whose
agent still moves stays bright, with the working glyph).

A row reads, left to right: the **title**, **where** (the task's repo, else the folder the session
runs in, home as `~`), one **state word**, a **detail** and the **age**. The state word is one of:

| Word | When |
|---|---|
| **needs you** (yellow) | any session of the task waits for the person |
| **N working** (cyan, with the moving braille glyph before it) | two or more sessions are moving, background ones counted |
| **working** (cyan, with the glyph) | one session is moving |
| **done** (muted green) | the task is in NOW and nothing is moving or waiting |
| **finished** (dim) | the task is in EARLIER and nothing is moving or waiting |

The glyph is the app's own working mark: four braille frames that cross-fade
about three times a second. With reduced motion it is a still frame. It is always drawn at regular
weight, not the bold of the word beside it: the system's braille font draws a ring at all six dot
positions in a bold weight, and the glyph is meant to show two dots turning.

A session is *moving* while it works and has shown a sign of life in the last five minutes (for a
session Lunamux does not host: while its file was refreshed in the last twenty). One that works but
has gone quiet reads as not moving, so a task of quiet sessions reads **done**. A task in EARLIER
whose session is moving anyway still reads **working**, not **finished**.

The **detail** is the first of these that exists: the task's status line, the question of the
session that waits (in quotes), what a moving session is doing, or nothing. A session that is a
task of one follows the same order without the status. The folder and the detail are hidden when
the panel is narrower than 700px.

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

- **The selected row has a second line** that says **tab 2** (the place of the session ↵ opens in
  the tab strip, **tab 2 in Work** when it is in another world). A session in a background tab has
  no tab number, so that part is left out. The line starts with the whole detail only when the row
  cut it short (it ends in "…") or hides it in a panel narrower than 700px, so the same words are
  never shown twice. The line also carries the resume
  note, "nothing to jump to: this session is not in a Lunamux pane", and the message of a resume
  that failed. A row that has nothing to put there has no second line, never an empty one.
- **↵ opens the task's own session.** Every task can have one session that speaks for it: the one
  that made the task, or the one an agent names with `set_task_lead`. ↵ jumps to its pane. When it
  runs in a background tab (see below) ↵ opens its read-only peek instead. When it is gone, ↵ runs
  the task's `open` action if it has one and otherwise resumes the session. A task with no such
  session takes the first of these that applies: jump to a live session in a listed tab (the ◆ that
  has waited longest, else the working session with the freshest sign of life); run the `open`
  action; peek a live background session; resume the newest session. A background session is never
  chosen while a session in a listed tab is live, and a row never does nothing unless no session of
  it is known and it has no `open` action. A session that is a task of one jumps, peeks when it is in the
  background, or resumes. The session is remembered by the pane it ran in, and that pane's id is
  reused after a restart, so it is looked up as the session running in that pane now; only when
  none is running does it stand for the pane's newest ended session. A server restart keeps it:
  the session is then an ended one and ↵ resumes it. It is lost only once that session is older
  than the history the overview loads (14 days, or beyond the newest 300 sessions); the task then
  has no own session until an agent names one again.
- **Three keys are not hinted.** **⌘↵** takes over the background session ↵ would peek: its tab
  becomes an ordinary tab and the app switches to it. **→** at the end of the search text peeks
  the session ↵ would open (**←** or esc goes back). **⌘⌫** asks to close a row that is one
  session, live in a pane; it never closes a task of several sessions, and does nothing while
  the search box has text. The question is the same as
  for closing a tab that is working. The footer is always `↵ open · ↑↓ move · esc close`. esc
  closes the peek, then the question, then the overlay.
- **A task has a one-line status.** An agent sets it with `set_task_status` and the row shows it as
  its detail. Line breaks become spaces, the ends are trimmed, and at most 120 characters are
  kept. An empty text clears it.
- **NOW is the open tabs.** A session is in NOW only while its pane is in a tab that is open,
  and a task only while one of its sessions is. A session whose pane is not in any open tab is in
  EARLIER with the rest of what is not happening on screen, however busy it is. The one exception
  is a session that needs the person (a ◆): it stays in NOW wherever it runs, because a question
  must not wait where the **+ N more** cut or the fourteen-day window can hide it. A task is one
  unit: while one of its sessions is in an open tab, the task stays in NOW, and a session that
  needs the person keeps its whole task there, wherever it runs. When neither holds, the task goes
  to EARLIER. The chip counts what NOW shows, so a working session of a task that is in EARLIER is
  not counted as working. A tab counts as open whatever the tab strip shows of it.
- **Agents can work out of sight.** An agent opens a **background tab** with `create_tab`,
  `create_window` or `run_command` and `background: true`. The tab is not in the tab strip or the
  tab list, never becomes the active tab and is never where the layout lands on its own. Its
  sessions are still sessions: they are in NOW (a background pane counts as open), they count in
  the topbar chip and in their task's state word, and a ◆ in one still asks for the person. They
  are counted, not listed: a background session of a task is no row of its own. A background
  session that is in no task is a row, and ↵ on it opens the read-only peek of its screen, **⌘↵**
  takes the tab over. A search never finds a background session while it is out of sight, live or
  closed. A background tab is in no tab list, so the agent that opened it closes it with
  `close_tab` when its work is done; a server restart closes every background tab, as the agents
  in them ended with the old process.
- **A session or task that has just ended stays in NOW for ten minutes.** With the ◆ above, this
  is the other thing in NOW that is in no tab: a session that has ended less than ten minutes ago,
  and a task that has just finished, so a row never reads "now" under a heading that says days.
  After the ten minutes it goes to EARLIER by its age. A session that is still running but idle,
  in no tab, gets no such grace.
- **NOW keeps its order.** Rows are in the order their work started, ascending, a task at the
  start of its first session, ties by id. The state never moves a row: a session that starts
  needing the person stays where it was, and the overlay opens with the selection on the first row in
  list order that needs the person. A task's start is stamped the first time the server sees it
  and never changes, so the order survives a restart. EARLIER is newest first as before.
- **Inside NOW, a task stays while it has work.** A task stays in NOW while a session needs the
  person or works. A task that a task file declares also stays there while it has open work, such
  as a session in review or queued, even when nothing is running at the moment, so it does not
  change section as sessions start and stop. Once every session of such a task reads done, nothing
  is moving or waiting and the task goes to EARLIER, even if none of its sessions has ended, once
  ten minutes have passed since its newest activity. A task made only of Lunamux's own sessions
  behaves like one idle session in a tab: it stays in NOW for ten minutes after the last activity
  and then moves to EARLIER. A task whose sessions have all ended is in EARLIER after the same
  ten minutes.
- **EARLIER is two bands, newest first; finished rows are dimmed.** **LAST FEW DAYS** holds the rows last active
  within three days. **LAST TWO WEEKS** holds those between three and fourteen days old. Together
  they show the newest five rows, then one **+ N more** line (a click on it, or ↓ on the last row,
  shows the rest). A band with nothing to show has no heading. Older rows are not listed, but
  search still finds them, and search is never cut.
  - A task's age is its newest session's: a task with a session from yesterday and one from ten
    days ago is in the first band. A single session is a task of its own.
  - Both edges are inclusive on the older side: exactly three days old is still in the first band,
    exactly fourteen still listed.
  - An idle pane that has been open for more than fourteen days is no longer listed either. It
    is still hosted, and search finds it.
  - The server keeps the ended sessions of the last fourteen days, at most 300, and pushes them
    to every window; older records stay in the database for search only.
- Sessions count in the topbar chip next to ordinary ones: `◆ 2 need you · 1 working`,
  `3 working`, or `agents idle`. Paused sessions never count as needing the person.

## Closing a tab that is working

Closing a tab whose session is working asks first, whichever way the person closes it (the × on the
tab, the tab list). The question names the tab, says the agent stops, and when the tab holds a
task's own session it lists the agents that task has in background tabs, which keep running, and how
to pick up the conversation: open the agents overview and choose the task. Cancel has the focus, Esc
cancels and ↵ acts on the focused button. A tab with nothing working in it closes at once, closing a
single pane is not asked about, and an agent's `close_tab` never asks.

Sessions of Claude Code report their state through hooks. For Codex and Gemini the state comes
from reading the screen, so it is a best guess and lags a little.

## Searching sessions

The search box over the list looks through every session ever hosted, however old. A session
matches on its own title, its **task's title**, its first prompt, its branch and links, its
conversation or its last screen, and results are ranked in that order. A match through the task
reads "in task title" on the selected row's second line, so a session whose own words
never mention the work can still be found by what the work was called. A session is found through
its task when it was put in it through the tools, or when a task file places it and it is still
known to the server; an old session that a task file once placed by folder is found by its own
words only.

A search shows one row per task: when two sessions of a task match, the better match stands for
the task and the second line names where and in which session it matched ("in transcript of
Reviewer"). ↵ and → on a result open the session that matched. A search covers closed sessions and
never the background ones.

## Trying it out

`scripts/seed-agents-overview.sh <db file> <tasks folder>` fills a scratch database and folder
with about twenty ended sessions (last active today, 1, 2, 6, 12 and 30 days ago), six
agent-made tasks (one with its own session, a status and two background members) and six task files. Seed while the scratch server is stopped, then start it on
its own port with `-Dlunamux.dbPath`, `-Dlunamux.tasksDir`, `-Dlunamux.agentGroupsDir` and
`-Dlunamux.claudeProjects` pointing at scratch paths (`./gradlew :server:run` forwards all four).
The script's header lists what to look at, and the cases that need a running agent.

## Grouping sessions from an agent

Seven MCP tools are registered. All but `list_tasks` change tasks and need a `read+write` token;
`list_tasks` needs only `read`. A session that never calls them is simply a task of its own.

| Tool | Arguments | What it does |
|---|---|---|
| `create_task` | `title`, `sessionIds?` | Makes a task and puts the named sessions in it. Returns `{ taskId, sessions }`. |
| `add_to_task` | `taskId`, `sessionIds` | Moves sessions into a task. The id can name a task file's task: the session is then in that task. |
| `remove_from_task` | `sessionIds` | Takes sessions out, so each is a task of its own again. Returns where each one ended up; a session that a task file still matches stays in that file's task. |
| `rename_task` | `taskId`, `title` | Renames a task made through the tools. A file's task is named by its file, so renaming it is refused with that reason. |
| `set_task_lead` | `taskId`, `sessionId` | Names the session that speaks for the task: the pane's `$LUNAMUX_SESSION_ID`. Refused for an unknown task or pane. `create_task` already sets it to the session that made the task (the pane named in the `X-Lunamux-Session` header, else the first one in `sessionIds`). |
| `set_task_status` | `taskId`, `text` | Sets the task's one-line status; empty clears it. Newlines become spaces, the ends are trimmed, at most 120 characters. |
| `list_tasks` | none | Lists every task with its sessions and their states, including a task nothing is in yet, with each task's own session, status and start. |

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
3. The overview now shows one row for the task, reading "2 working", and a session that needs the
   person turns the row into a ◆ "needs you".

Resuming a conversation keeps its task, even in another pane. A task made through the tools is
shown only once a session is in it; a task that loses its last session disappears.

## Task files

```
~/.lunamux/tasks/<anything>.json
```

Set `-Dlunamux.tasksDir=/some/folder` to watch another folder. Earlier versions read
`~/.lunamux/agent-groups`; that folder is still read when no override is set, so files written
for it keep working, and `-Dlunamux.agentGroupsDir` still names a folder to read instead. A dev
server and the real Lunamux read the same folders unless an override is set, so a dev server
must always set one. Files must end in `.json`; names starting with `.` are ignored, so a writer
can build a file under a dot-name and rename it into place.

### Format

One object per file.

```json
{
  "version": 2,
  "id": "access-control",
  "title": "Access control",
  "repo": "work-app",
  "open": {
    "command": "claude --resume access-control",
    "folder": "~/code/work-app"
  },
  "members": [
    {
      "title": "Role model",
      "links": ["ACC-12"],
      "match": { "folder": "~/code/work-app/.worktrees/acc-12" },
      "state": "needs_you",
      "line": "Should admins inherit viewer rights?",
      "since": "2026-11-01T09:12:00Z"
    },
    {
      "title": "Login audit trail",
      "match": { "agentSessionId": "5b0f6d2c-1c53-4a49-8f0e-3f6b1c0f7a11" },
      "state": "working",
      "since": "2026-11-01T08:50:00Z"
    },
    {
      "title": "Permissions page",
      "state": "idle",
      "label": "in review",
      "links": ["#212"],
      "since": "2026-11-01T08:10:00Z",
      "sessionId": "9a3c1e72-6d0b-4c11-b2f4-0c5d8e7a1b30"
    }
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `version` | no | `2`. Accepted and ignored; a `1` file works too. |
| `id` | yes | Unique, stable id of the task. If two files use one id, the newer file wins and a warning is logged. A task made through the tools with the same id is the same task, and the file's title wins. |
| `title` | yes | The task's name. |
| `repo` | no | Repository name shown beside the title. |
| `open` | no | The task's optional **open** action, see below. |
| `open.command` | if `open` | Typed into a new shell (followed by ↵) when the header's ↵ has no open session to jump to. |
| `open.folder` | if `open` | Absolute path, or one starting with `~/`. The new tab opens here. |
| `lead` | no | The older spelling of `open`, read as `open` when `open` is absent. Its `sessionId` is ignored: a file cannot name the task's own session, an agent does that with `set_task_lead`. |
| `tag`, `link`, `updatedAt` | no | Accepted and ignored. |
| `members[]` | no | The task's sessions that tools outside Lunamux run, shown in the order listed. |
| `members[].title` | yes | What the member is called. A matched session keeps this title. |
| `members[].state` | yes | `needs_you`, `working`, `idle` or `ended`. |
| `members[].label` | no | A short status word shown in front of the line and used in the counts ("1 in review"). `in review`, `queued`, `waiting`, `paused` and `done` (or `ready`) have a glyph and a place in the order; any other label, `held` included, counts as given. Use plain statuses, not ticket keys. |
| `members[].line` | no | One line of detail: the question for `needs_you`, otherwise what it is doing. Blank means none. |
| `members[].sessionId` | no | The id `claude attach` takes for the member's background session. Gives the member a **resume** action when Lunamux hosts no session for it. Only letters, digits, `-` and `_` are accepted; anything else is never typed into a shell. |
| `members[].since` | no (recommended) | ISO-8601 time the member entered its state; drives the age and the ordering. When missing or unreadable, the file's modified time is used. |
| `members[].links` | no | Ticket ids and pull requests such as `ACC-12` or `#212`. A `#`-number is the member's pull request, shown after `done`; Lunamux adds none of its own. |
| `members[].match` | no | How to find the member's real session; see below. |

Unknown fields are ignored. Paths must be absolute or start with `~/`; a relative path makes the
file invalid. Symbolic links in paths are resolved before comparing.

A file that cannot be used (broken JSON, a missing `id` or `title`, an `open` without a command
or an absolute folder, an unknown `state`, a relative path) is **skipped whole**, with one
warning in the server log each time the file changes. The other tasks are unaffected, and a later
fixed version of the file is picked up within about three seconds.

### The `open` action

`open` is optional. With it, ↵ on a row whose own session is gone, or that has no session in a
listed tab, types `open.command` into a new tab started in `open.folder`. The tab is named like
the task. Without it, ↵ peeks a live background session or resumes the newest session, and does
nothing only for a task none of whose sessions Lunamux knows.

### Matching members to real sessions

Without `match`, a member is drawn from the file alone: its state, line and age are exactly what
the file says. It has no action unless it has a `sessionId`. Whether it is alive is told by the
file's freshness alone (see below).

Every session record gets its task by the first of these that applies:

1. **An explicit join.** The agent put it in a task through the tools above (or `create_window`
   promised it one). This beats everything else, and a task id that no file or agent-made task
   knows is ignored.
2. **A file member's `match.agentSessionId`**: the newest session with that agent session id.
3. **A file member's `match.folder`**: the newest *open* session whose working folder is that
   folder or inside it.
4. Otherwise the session is a task of its own.

A matched member takes the session's live state, line and age while the session is open, and ↵
jumps to it (or resumes it once closed). Its title stays the file's. A session is in exactly one
task and fills at most one member, so two members never take one session. A session an agent
put into a file's task that no member matches belongs to the task all the same, and shows as an
extra session after the members.

Two members of one file may carry the same `sessionId`. Each still gets its own row and its own
open action, because the server numbers a repeated id by the member's place in the file.

### Writing and removing files

- **Replace atomically.** Write the new content to a temporary name (starting with `.`) in the
  same folder and rename it over the old file. Lunamux notices changes by modified time and size
  and re-reads about every three seconds, so a state change shows up within a poll.
- **Keep the file fresh while the work runs.** Staleness is read from the file's modified time.
  A writer that has nothing new to say must still touch the file (set its modified time, leaving
  content and format alone) at least every two minutes while a member works, or its working
  members read as quiet after five minutes and stale after twenty.
- **To remove a task**, delete its file. The task disappears within a poll, and its matched
  sessions become tasks of their own. Lunamux keeps nothing about it.

### Trust

`open.command` is typed into the user's shell when they press ↵ on the header. Anyone who can
write into the folder can therefore run a command as the user. Only the user's own tools should
write there, and the folder should not be shared or writable by others. The command comes from
the file on the server and is never taken from the browser, and the `claude attach` command for
a member is built by the server from the member's `sessionId` alone.
