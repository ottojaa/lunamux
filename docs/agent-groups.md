# Agent groups

A tool that runs several agent sessions as one piece of work (a release, a refactor split
across branches, a batch of reviews) can show them in the agents overview (⌘⇧A) as **one
box**. It does so by writing one JSON file per group into a folder Lunamux watches. Lunamux
only ever *reads* these files; it never creates, edits or deletes one.

```
~/.lunamux/agent-groups/<anything>.json
```

Set `-Dlunamux.agentGroupsDir=/some/folder` to watch another folder. A dev server and the
real Lunamux read the same `~/.lunamux/agent-groups` unless this is set, so a dev server must
always set it. Files must end in `.json`; names starting with `.` are ignored, so a writer can
build a file under a dot-name and rename it into place.

## What the person sees

The overview is one terminal-style table. Column two is the state and the only place a glyph
appears: **◆** needs you (amber), a turning braille glyph for work in progress, **○** waiting or
idle, **‖** paused, **✓** done. A programme is an expansion panel drawn as a tree.

- The programme's header row has the title, the repo, the counts of its runs in a fixed order
  (**1 needs you** · 1 working · 1 in review · 2 queued · 1 paused · 3 done, zero counts left out),
  and **open** at the right end. It has no fill, badge, link chip, age or ◆.
- Collapsed, only the runs that need the user show under the header. Expanded (click on the
  header, →, or space; ← collapses) every run shows, most urgent first: needs you, working, in
  review, queued, other labels, paused, done. There is no "N more" line. An expansion survives
  closing and reopening the overview.
- Each run's status is its **live line**: the question for a run that needs the user, what it is
  doing for one that works, and for a finished one the word `done` plus its pull request
  (`done · #22`). An empty or whitespace-only `line` shows as nothing, never as a bare separator,
  and the row falls back to the label or a dash.
- A run whose `sessionId` is set opens with **open** (↵): a new tab in `lead.folder` running
  `claude attach <session id>`. The id comes from the file; Lunamux builds the command. A run
  whose session Lunamux hosts jumps to its pane instead, and a closed one resumes. A run with
  neither has no action.
- A programme stays in **NOW** until all its runs have ended, even when nothing is running: it
  does not change section as runs start and stop. Within NOW, units rank by their most urgent run
  (needs you, working, open work with nothing running). Only a programme whose runs have all
  ended moves to **EARLIER**. A single idle session stays in NOW for ten minutes after its last
  activity, shown idle with its age, and then moves to EARLIER.
- ↵ on the header goes to the **lead session**: it jumps there if the lead is open, otherwise
  Lunamux opens a new tab in `lead.folder` and types `lead.command` into it. "open" at the right
  end of the header does the same.
- A live session that is the lead (see `lead.sessionId`) is the header, not a second row. A
  lead that needs the user or works is also a row among the runs, titled `<Tag> session`
  ("Programme session"), with its own question or last tool line.
- A working run shows the turning glyph while there is a recent sign of life, and the glyph stops
  and dims otherwise. For a run Lunamux hosts the sign is its session's activity; for a run
  outside Lunamux it is the file's modified time, so a fresh file keeps its working runs live.
  After five minutes without a sign the age reads `quiet 6m`; a file untouched for twenty
  minutes reads `stale 25m` on every working run it declares. The glyph is still when the
  browser asks for reduced motion.
- Members count in the topbar chip next to ordinary sessions: `◆ 2 need you · 1 working`,
  `3 working`, or `agents idle`. Paused runs never count as needing the user.

## Format

One object per file.

```json
{
  "version": 1,
  "id": "access-control",
  "title": "Access control",
  "tag": "Programme",
  "repo": "work-app",
  "link": { "label": "ACC", "url": "https://tracker.example.com/projects/acc" },
  "updatedAt": "2026-11-01T09:30:00Z",
  "lead": {
    "command": "claude --resume programme-lead",
    "folder": "~/code/work-app",
    "sessionId": "d6e66e3c-a412-4020-b4c9-0d001ae7faba"
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
| `version` | no | `1`. |
| `id` | yes | Unique, stable id of the group. If two files use one id, the newer file wins and a warning is logged. |
| `title` | yes | The box's name. |
| `tag` | no | What the programme is called in the lead's row ("Programme session"). Without it the row says "Lead session". |
| `repo` | no | Repository name shown beside the title. |
| `link` | no | `{ "label", "url" }`. Accepted and ignored: the overview draws no link chips. |
| `updatedAt` | no | ISO-8601 time of the writer's last change. Informational. |
| `lead.command` | yes | Typed into a new shell (followed by ↵) when the lead is not open. |
| `lead.folder` | yes | Absolute path, or one starting with `~/`. The new tab opens here. |
| `lead.sessionId` | no | The agent session id of the programme's home chat, the same kind of id as `members[].match.agentSessionId`. The live session with this id is drawn as the header instead of as a separate row. Without it nothing is folded. |
| `members[]` | no | The group's sessions, shown in the order listed. |
| `members[].title` | yes | What the member is called in the box. A matched session keeps this title. |
| `members[].state` | yes | `needs_you`, `working`, `idle` or `ended`. |
| `members[].label` | no | A short status word shown in front of the line and used in the counts ("1 in review"). `in review`, `queued`, `paused` (or `held`) and `done` have a glyph of their own; any other label counts as given. Use plain statuses, not ticket keys. |
| `members[].line` | no | One line of detail: the question for `needs_you`, otherwise what it is doing. Blank means none. |
| `members[].sessionId` | no | The id `claude attach` takes for the run's background session. Gives the run an **open** action when Lunamux hosts no session for it. Only letters, digits, `-` and `_` are accepted; anything else is never typed into a shell. |
| `members[].since` | no (recommended) | ISO-8601 time the member entered its state; drives the age and the ordering. When missing or unreadable, the file's modified time is used. |
| `members[].links` | no | Ticket ids and pull requests such as `ACC-12` or `#212`. A `#`-number is the run's pull request, shown after `done`; Lunamux adds none of its own. |
| `members[].match` | no | How to find the member's real session; see below. |

Unknown fields are ignored. Paths must be absolute or start with `~/`; a relative path makes
the file invalid. Symbolic links in paths are resolved before comparing.

A file that cannot be used (broken JSON, a missing required field, an unknown `state`, a relative
path) is **skipped whole**, with one warning in the server log each time the file changes. The
other groups are unaffected, and a later fixed version of the file is picked up within about
three seconds.

## Matching members to real sessions

Without `match`, a member is drawn from the file alone: its state, line and age are exactly what
the file says, and it has no action (there is no session of its own to open or jump to). Whether it is
alive is told by the file's freshness alone (see below).

With `match`, Lunamux looks for the member's session among the ones it tracks:

1. `agentSessionId` — the newest session with that agent session id.
2. else `folder` — the newest *open* session whose working folder is that folder or inside it.

A matched member takes the session's live state, line and age while the session is open, and ↵
jumps to it (or opens it in a new tab once closed). Its title stays the file's. A session claimed by a
member is not listed a second time outside the group, and one session can belong to only one
member.

The lead is the newest session whose `agentSessionId` equals `lead.sessionId`, wherever it runs.
That session is the header rather than a row of its own, and it is not matched to members,
whatever their `match.folder`. Without that id, or when no session has it, nothing is folded: the
header's ↵ and "open" still go to the newest open session whose working folder equals
`lead.folder` (or start `lead.command` when there is none), but that session stays an ordinary
row. The lead is *open* when its session is running in a pane that is on screen in the layout.
Other chats the person once declared as the programme's home are real chats: they stay in the
list, and only the session with `lead.sessionId` is folded.

## Writing and removing files

- **Replace atomically.** Write the new content to a temporary name (starting with `.`) in the
  same folder and rename it over the old file. Lunamux notices changes by modified time and size
  and re-reads about every three seconds, so a state change shows up within a poll.
- **Keep the file fresh while the programme runs.** Staleness is read from the file's modified
  time, not from `updatedAt`. A writer that has nothing new to say must still touch the file
  (set its modified time, leaving content and format alone) at least every two minutes while a
  run works, or its working runs read as quiet after five minutes and stale after twenty. The orchestrator's writer does this on every controller tick.
- **To remove a group**, delete its file. The box disappears within a poll. Lunamux keeps nothing
  about it.

## Trust

`lead.command` is typed into the user's shell when they press ↵ on the header. Anyone who can
write into the folder can therefore run a command as the user. Only the user's own tools should
write there, and the folder should not be shared or writable by others. The command comes from the
file on the server and is never taken from the browser.
