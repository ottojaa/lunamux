#!/usr/bin/env bash
#
# Seed a scratch database and task folder for the agents overview (⌘⇧A).
#
#   scripts/seed-agents-overview.sh <db file> <tasks folder>
#
# Writes 26 ended agent sessions last active today, 1, 2, 6, 12 and 30 days ago, five
# agent-made tasks, seven task files, and 8 sessions that are awkward to name or to read (listed
# below). Run it again to refresh the ages (it replaces its own records and touches nothing else).
# The ages are counted from the moment it runs, so seed again on the day you look. Seed while the
# scratch server is STOPPED: the server reads its sessions and tasks once when it starts, and it
# ends any session still open. Two seeded sessions are left open on purpose, to show that.
#
# It refuses ~/Library/Application Support/Termtastic, a database named termtastic.db or
# lunamux-dev.db, and the real task folders (~/.lunamux/tasks, ~/.lunamux/agent-groups).
#
# Start a scratch server on its own port, database, folders and transcript folder, so it reads
# none of the real ones:
#
#   DB=/tmp/aov-seed/lunamux.db; TASKS=/tmp/aov-seed/tasks
#   scripts/seed-agents-overview.sh "$DB" "$TASKS"
#   ./gradlew :server:run -Plunula.toolkit.useArtifacts=true \
#       -Dlunamux.port=8471 -Dlunamux.dbPath="$DB" -Dlunamux.tasksDir="$TASKS" \
#       -Dlunamux.agentGroupsDir=/tmp/aov-seed/groups -Dlunamux.claudeProjects=/tmp/aov-seed/projects
#
# Open https://localhost:8471, press ⌘⇧A and look at:
#
#   NOW              "Access control" (◆ needs you, one working, one in review) and
#                    "Nightly sync", whose file is 25 minutes old, so its worker reads "stale 25m",
#                    and "Billing cleanup", a file task whose two sessions are in review and queued:
#                    nothing moves or waits on you, but work is left, so it stays in NOW.
#   LAST FEW DAYS    everything finished within 3 days, all listed, newest first: "Release train",
#                    a file task whose three sessions are all done but none has ended; today's two
#                    sessions, "Rate limiter rewrite" and "Auth token refresh" (a session from
#                    yesterday and one from 10 days ago: the task is as recent as its newest),
#                    "Release 4.2" and the 2-day-old session.
#   LAST TWO WEEKS   3 to 14 days: five units and "+ 2 more" ("Flaky checkout tests", the 6- and
#                    12-day sessions, "Onboarding emails", "Docs sweep"). Click the more line to see all.
#   An EARLIER task  a collapsed panel (title, repo, "3 sessions", age). → expands it; ↵ on a
#                    session resumes it.
#   Awkward rows     "seed-home" (run from the home folder), "seed-branch-only" (only a run-named
#                    branch), "seed-run-id-title" and "seed-folder-title" (a stored title that is a
#                    run id or a folder name): each is named by its first prompt, never "~", the
#                    branch or the folder. "Owner test follow-up" has a bold preview and "Show the
#                    migration" a preview that is only a code fence: both read as plain text.
#   A restart        "Trace the slow login" (open, idle for 2 days) and "Rebuild the search index"
#                    (open, working for 5 hours) are ended by the server when it starts. They are
#                    aged from when they last did anything (2d and 5h), not from the restart.
#   Marks            "Invoice export" (a task file, in NOW) and its two members, plus five loose
#                    sessions further down ("Invoice export: fix the rounding in totals" and four
#                    more), all wear the same half-circle mark: one thread of work, one mark. "Rebuild
#                    the search index" and "Show the migration" (same repository, other branches)
#                    wear other marks; the home-folder session wears none. The footer says
#                    "mark = one thread of work". The marks are stored, so seeding again and
#                    restarting shows the same ones.
#   Search           type "invoice": three sessions of "Invoice export migration", 30 days old,
#                    found only through their task's title ("in task Invoice export migration").
#                    "token" also matches by task; "limiter" matches by title first.
#
# Hand-started cases (these need a running agent, so they are not seeded): open a pane in the
# scratch server and start `claude` in it for a live NOW row. To make that agent group its own
# sessions, give its MCP client TLS verification off (NODE_TLS_REJECT_UNAUTHORIZED=0, because the
# dev server's certificate is self-signed; localhost only) and tell it to split the work in two.
# Hook events can also be posted by a script to the scratch server for panes that run no agent.

set -euo pipefail

if [ "$#" -ne 2 ]; then
    echo "usage: $0 <db file> <tasks folder>" >&2
    exit 2
fi

cd "$(dirname "$0")/.."
exec ./gradlew -q :server:seedAgentsOverview -Plunula.toolkit.useArtifacts=true \
    --args="$(printf '%q %q' "$1" "$2")"
