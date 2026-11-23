# Tasks

A **task** is one piece of work that one or more agent conversations belong to: a release, a
refactor split over branches, one chat. A conversation is one Claude, Codex or Gemini dialogue,
and it is the unit the server tracks (`AgentSessionTracker`). A task has one **main conversation**,
the one that speaks for it.

The server decides everything about tasks in one place, `TaskIndex`. It reads the conversation
summaries the tracker publishes and the task files, and publishes one `TaskState`: the tasks to
show (`AgentTask`) and the ids of every task that exists. Clients draw that and decide nothing.

## Which tasks exist

There are two sources, and one id from both is one task.

- An **agent-made task** is created through `create_task`. It is a titled row of the `tasks`
  table. The table is read once at startup and written through on every change.
- A **task file** declares a task by file (see below). It is always published.

A conversation belongs to the task whose id its record carries, and nothing else decides it. An id
that no file declares and no titled row holds is not a task.

An agent-made task is published only while at least one conversation carries it, so a task nobody is
in leaves the overview. A row with no title is not a task: it is what is left when a task file
is deleted. Tasks are published in id order.

A task's id is `<slug>-<4 hex>`: the title lowercased, every run of characters other than
`a-z0-9` turned into a single `-`, trimmed and cut to 40, or `task` when nothing is left, then a random
suffix that is drawn again on a collision. A title is trimmed, its whitespace collapsed and cut to 120
characters. An empty title is refused with "A task needs a title.".

**Start.** `startedAt` is stamped once, the first time the task is published, and never moves
afterwards: the earliest start of a conversation that carries it, else the task file's modified time,
else when the row was made. It is stored, so a restart keeps it.

## The main conversation

The conversation that speaks for a task is stored as its id. `create_task` names the caller's own:
the pane given by the `X-Lunamux-Session` header when Lunamux knows that pane, else the first of
`sessionIds`, and only when an agent runs there now. `set_task_lead` names another.

- A conversation speaks for one task at a time, the one it is in. A manager that starts its next
  task from the same conversation stops speaking for the one before.
- A conversation started inside another one (a helper, for example a plan reviewer) cannot speak.
  Naming one stores its host instead.
- A closed conversation can still speak, and then has no pane.

## The task's status

`set_task_status` sets a one-line status. Newlines (with the space around them) become one space,
the ends are trimmed, and it is cut to 120 characters. An empty text clears it.

- `questionWaits: true` says a question waits for the person. It is set afresh by every call and is
  kept only together with a status.
- `paused: true` says the work was stopped on purpose. It is set afresh by every call too, but an
  empty text does not clear it. A repeated pause keeps the time it began.
- A status that only counts states ("2 working, 1 waiting") is stored but not published, since the
  overview counts them itself. `list_tasks` still shows what was set.

Status, pause and the main conversation survive a restart.

## Task files

```
~/.lunamux/tasks/<anything>.json
```

`-Dlunamux.tasksDir=/some/folder` watches another folder. A dev server and the real Lunamux read the
same folder unless one is set, so a dev server should always set one. Files end in `.json`; names
starting with `.` are ignored, so a writer can build a file under a dot-name and rename it into
place. The folder is polled every three seconds, and a file is read again when its modified time or
length changes. To remove a task, delete its file. Lunamux only reads the folder.

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
| `id` | yes | Unique, stable id. If two files use one id the newest wins and one warning is logged. A task made through the tools with the same id is the same task, and the file's title wins. |
| `title` | yes | The task's name. |
| `repo` | no | Repository name. |
| `open` | no | How to start a session for the task. |
| `open.command` | if `open` | The command to type into a new shell. |
| `open.folder` | if `open` | An absolute path, or one starting with `~/`. Kept in canonical form. |

Other keys are ignored. A file that cannot be used (broken JSON, a missing `id` or `title`, an `open`
without a command or with a relative folder) is skipped whole with one warning in the server log per
change; other tasks are unaffected and a fixed file is picked up on the next poll.

**Trust:** `open.command` is typed into the user's shell. Anyone who can write into the folder can run
a command as the user, so only the user's own tools should write there. The command comes from the
file on the server and is never taken from a client.

## MCP tools

All but `list_tasks` change something and need a `read+write` token; `list_tasks` needs `read`.
`sessionIds` are terminal session ids: the `$LUNAMUX_SESSION_ID` of the pane a session runs in.
An agent's own is in its environment, and `list_sessions` shows the others. A request that names an
unknown task or session is refused before anything changes.

| Tool | Arguments | Result |
|---|---|---|
| `create_task` | `title`, `sessionIds?` | `{taskId, sessions, mainConversation}`: `sessions` is how many were given, `mainConversation` is `{conversationId, sessionId}` or null. |
| `add_to_task` | `taskId`, `sessionIds` | Text "Added N session(s) to <taskId>". The id can name a task file's task. A pane with no agent yet is promised the task for the session that starts in it. |
| `remove_from_task` | `sessionIds` | `{sessions: {<sessionId>: <taskId or null>}}`. A promise is cancelled. |
| `rename_task` | `taskId`, `title` | Text "Renamed <taskId>". A file's task is refused: the file owns its title. |
| `set_task_lead` | `taskId`, exactly one of `sessionId` or `conversationId` | `{taskId, mainConversation}`. A `sessionId` becomes the conversation running in that pane now. |
| `set_task_status` | `taskId`, `text`, `questionWaits?`, `paused?` | `{taskId, status, questionWaits, paused}`. |
| `list_tasks` | none | Every task, including one nothing is in: file tasks first (`source: "file"`), then agent-made ones (`source: "agents"`), each sorted by id. Each entry has `taskId`, `title`, `source`, `mainConversation`, `status`, `questionWaits`, `paused`, `startedAt` and `sessions: [{sessionId, title, state}]` (`sessionId` is null once a conversation has ended). |
| `set_status` | `text`, `sessionId?`, `conversationId?`, `keep?`, `needsYou?` | `{status, keep}`. One conversation's own line; not a task's. |

`create_window` and `run_command` also take an optional `taskId`. An unknown task is refused before
a pane opens. The agent session that starts in the new pane joins the task when it appears;
`run_command` applies it only when it creates the window itself.

A window made for a task opens where the task already is. The tab is the first that applies: the
`tabId` the call names; else the tab holding most of the task's sessions (counting panes that were
promised the task and have no agent yet), the first in tab order on a tie; else the usual default tab.

Resuming a conversation keeps its task, also in another pane.
