# Tasks

The agents overview (⌘⇧A) shows **tasks**, not sessions. A task is one piece of work: a release,
a refactor split across branches, a batch of reviews, or just one chat. Every agent session
belongs to exactly one task. A task with a single session is an ordinary row. A task with
several sessions is an expansion panel that shows what each of them is doing.

There are two ways a session gets into a task, and they can be mixed:

- **Agents group their own sessions.** An agent that splits its work across a second session
  says so through the Lunamux MCP tools below. Nobody has to write a file.
- **A tool that runs sessions Lunamux does not host declares them in a task file.** A
  scheduler, an orchestrator or a CI runner writes one JSON file per task into a folder Lunamux
  watches. Lunamux only ever *reads* these files.

## What the person sees

The overview is one terminal-style table. Column two is the state and the only place a glyph
appears: **◆** needs you (amber), a turning braille glyph for work in progress, **○** waiting or
idle, **‖** paused, **✓** done.

- **One session, one row.** A task with a single session and no `open` action is that session's
  plain row. Nothing about it says "task".
- **Several sessions, one panel.** The header row has the title, the repo, the counts of its
  sessions in a fixed order (**1 needs you** · 1 working · 1 in review · 2 queued · 1 paused ·
  3 done, zero counts left out) and, when the task has an `open` action, **open** at the right
  end. It has no fill, badge, link chip, age or ◆.
- **Collapsed**, a panel shows the sessions that need the person (longest waiting first) and the
  ones that are working. **Expanded** (click on the header, →, or space; ← collapses) it shows
  every session, most urgent first: needs you, working, in review, queued, other labels, paused,
  done. There is no "N more" line. An expansion survives closing and reopening the overview.
- **A session's status is its live line**: the question for one that needs the person, what it
  is doing for one that works, and for a finished one the word `done` plus its pull request
  (`done · #22`). An empty line shows as nothing, never as a bare separator.
- **↵ on the header goes where the person is needed.** It jumps to the open session that most
  needs them: the ◆ that has waited longest, else the working session with the freshest sign of
  life. With no open session it runs the task's `open` action, if the task has one. With neither
  it expands or collapses the panel. A task has no "lead" session.
- **↵ on a session** jumps to its pane, or resumes it when it has ended. A session Lunamux does
  not host and that has a `sessionId` opens in a new tab with `claude attach <session id>`.
- **NOW and EARLIER.** A task stays in NOW while a session needs the person or works. A task
  that a task file declares also stays there while it has sessions that have not ended, even when
  nothing is running at the moment, so it does not change section as sessions start and stop. A
  task made only of Lunamux's own sessions behaves like one idle session: it stays in NOW for ten
  minutes after the last activity and then moves to EARLIER. A task whose sessions have all ended
  is in EARLIER.
- A working session shows the turning glyph while there is a recent sign of life, and the glyph
  stops and dims otherwise. For a session Lunamux hosts the sign is its activity; for one outside
  Lunamux it is the file's modified time. After five minutes without a sign the age reads
  `quiet 6m`; a file untouched for twenty minutes reads `stale 25m` on each working session it
  declares. The glyph is still when the browser asks for reduced motion.
- Sessions count in the topbar chip next to ordinary ones: `◆ 2 need you · 1 working`,
  `3 working`, or `agents idle`. Paused sessions never count as needing the person.

Sessions of Claude Code report their state through hooks. For Codex and Gemini the state comes
from reading the screen, so it is a best guess and lags a little.

## Grouping sessions from an agent

Five MCP tools are registered. The first four change tasks and need a `read+write` token;
`list_tasks` needs only `read`. A session that never calls them is simply a task of its own.

| Tool | Arguments | What it does |
|---|---|---|
| `create_task` | `title`, `sessionIds?` | Makes a task and puts the named sessions in it. Returns `{ taskId, sessions }`. |
| `add_to_task` | `taskId`, `sessionIds` | Moves sessions into a task. The id can name a task file's task: the session is then in that task. |
| `remove_from_task` | `sessionIds` | Takes sessions out, so each is a task of its own again. Returns where each one ended up; a session that a task file still matches stays in that file's task. |
| `rename_task` | `taskId`, `title` | Renames a task made through the tools. A file's task is named by its file, so renaming it is refused with that reason. |
| `list_tasks` | none | Lists every task with its sessions and their states, including a task nothing is in yet. |

`sessionIds` are terminal session ids: the `$LUNAMUX_SESSION_ID` of the pane a session runs in.
An agent's own id is in its environment, and `list_sessions` shows the others. `create_window`
also takes an optional `taskId`: the agent session that starts in the new pane then joins that
task as soon as it appears, even though no agent runs there yet when the window is created.

An agent told to split its work in two does this:

1. `create_task` with a title and its own `$LUNAMUX_SESSION_ID` in `sessionIds` → `taskId`.
2. `create_window` with that `taskId`, then start the second agent in the new pane.
3. The overview now shows one panel with both sessions, and one row's ◆ counts for the task.

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
| `lead` | no | The older spelling of `open`, read as `open` when `open` is absent. Its `sessionId` is ignored: a task has no lead. |
| `tag`, `link`, `updatedAt` | no | Accepted and ignored. |
| `members[]` | no | The task's sessions that tools outside Lunamux run, shown in the order listed. |
| `members[].title` | yes | What the member is called. A matched session keeps this title. |
| `members[].state` | yes | `needs_you`, `working`, `idle` or `ended`. |
| `members[].label` | no | A short status word shown in front of the line and used in the counts ("1 in review"). `in review`, `queued`, `waiting`, `paused` and `done` (or `ready`) have a glyph and a place in the order; any other label, `held` included, counts as given. Use plain statuses, not ticket keys. |
| `members[].line` | no | One line of detail: the question for `needs_you`, otherwise what it is doing. Blank means none. |
| `members[].sessionId` | no | The id `claude attach` takes for the member's background session. Gives the member an **open** action when Lunamux hosts no session for it. Only letters, digits, `-` and `_` are accepted; anything else is never typed into a shell. |
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

`open` is optional. With it, the header shows **open**, and ↵ on a header with no open session
types `open.command` into a new tab started in `open.folder`. The tab is named like the task.
Without it, a task has no such action and ↵ falls back to expanding or collapsing the panel.

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
