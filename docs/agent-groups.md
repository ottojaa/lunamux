# Agent groups

A tool that runs several agent sessions as one piece of work (a release, a refactor split
across branches, a batch of reviews) can show them in the agents overview (⌘⇧A) as **one
box**. It does so by writing one JSON file per group into a folder Lunamux watches. Lunamux
only ever *reads* these files; it never creates, edits or deletes one.

```
~/.lunamux/agent-groups/<anything>.json
```

Set `-Dlunamux.agentGroupsDir=/some/folder` to watch another folder (for example on a dev
server). Files must end in `.json`; names starting with `.` are ignored, so a writer can
build a file under a dot-name and rename it into place.

## What the person sees

- The box has a header (title, a tag pill, the repo, a link chip) and a count line such as
  **1 needs you** · 1 working · 1 in review.
- Collapsed, only the members that need the user show, followed by a `+ 1 working, 1 in review`
  line. ↵ on it, or a click on the chevron or the count line, shows every member.
- The box sits in **NOW** while any member needs the user or works, ranked by its most urgent
  member and then by how long that member has waited, like a single session. When nothing is
  urgent its header leads **EARLIER**, collapsed.
- ↵ on the header goes to the **lead session**: it jumps there if the lead is open, otherwise
  Lunamux opens a new tab in `lead.folder` and types `lead.command` into it. The selected header
  reads `↵ opens the <tag> session · it last said: <last words>`.
- Members count in the topbar chip next to ordinary sessions.

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
      "line": "PR #212 is waiting for review",
      "since": "2026-11-01T08:10:00Z"
    }
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `version` | no | `1`. |
| `id` | yes | Unique, stable id of the group. If two files use one id, the newer file wins and a warning is logged. |
| `title` | yes | The box's name. |
| `tag` | no | Pill text; shown upper-case, and used lower-case in "↵ opens the … session". Without it the line says "lead". |
| `repo` | no | Repository name shown beside the title. |
| `link` | no | `{ "label", "url" }` chip. Only `http://` and `https://` URLs are shown; anything else drops the chip. |
| `updatedAt` | no | ISO-8601 time of the writer's last change. Informational. |
| `lead.command` | yes | Typed into a new shell (followed by ↵) when the lead is not open. |
| `lead.folder` | yes | Absolute path, or one starting with `~/`. The new tab opens here. |
| `members[]` | no | The group's sessions, shown in the order listed. |
| `members[].title` | yes | What the member is called in the box. A matched session keeps this title. |
| `members[].state` | yes | `needs_you`, `working`, `idle` or `ended`. |
| `members[].label` | no | A short word shown in front of the line and used in the `+ … in review` count. |
| `members[].line` | no | One line of detail: the question for `needs_you`, otherwise what it is doing. |
| `members[].since` | no (recommended) | ISO-8601 time the member entered its state; drives the age and the ordering. When missing or unreadable, the file's modified time is used. |
| `members[].links` | no | Short text chips such as ticket ids. |
| `members[].match` | no | How to find the member's real session; see below. |

Unknown fields are ignored. Paths must be absolute or start with `~/`; a relative path makes
the file invalid. Symbolic links in paths are resolved before comparing.

A file that cannot be used (broken JSON, a missing required field, an unknown `state`, a relative
path) is **skipped whole**, with one warning in the server log each time the file changes. The
other groups are unaffected, and a later fixed version of the file is picked up within about
three seconds.

## Matching members to real sessions

Without `match`, a member is drawn from the file alone: its state, line and age are exactly what
the file says, and ↵ on it opens the lead (there is no session of its own to jump to).

With `match`, Lunamux looks for the member's session among the ones it tracks:

1. `agentSessionId` — the newest session with that agent session id.
2. else `folder` — the newest *open* session whose working folder is that folder or inside it.

A matched member takes the session's live state, line and age while the session is open, and ↵
jumps to it (or resumes it once closed). Its title stays the file's. A session claimed by a
member is not listed a second time outside the group, and one session can belong to only one
member.

The lead is found the same way: the newest open session whose working folder equals
`lead.folder`. It is *open* when that session is running in a pane that is on screen in the
layout. That session is not matched to members, whatever their `match.folder`, and what it last
said is shown on the header.

## Writing and removing files

- **Replace atomically.** Write the new content to a temporary name (starting with `.`) in the
  same folder and rename it over the old file. Lunamux notices changes by modified time and size
  and re-reads about every three seconds, so a state change shows up within a poll.
- **To remove a group**, delete its file. The box disappears within a poll. Lunamux keeps nothing
  about it.

## Trust

`lead.command` is typed into the user's shell when they press ↵ on the header. Anyone who can
write into the folder can therefore run a command as the user. Only the user's own tools should
write there, and the folder should not be shared or writable by others. The command comes from the
file on the server and is never taken from the browser.
