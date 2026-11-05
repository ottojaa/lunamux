#!/usr/bin/env bash
#
# Seed a scratch database and task folder for the agents overview (⌘⇧A).
#
#   scripts/seed-agents-overview.sh <db file> <tasks folder>
#
# Writes 24 ended agent sessions last active today, 1, 2, 6, 12 and 30 days ago, six
# agent-made tasks, six task files, and 8 sessions that are awkward to name or to read (listed
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
#   NOW              "Access control" only: its main conversation is closed and one of its background agents
#                    waits on a question, so the row says a question waits and counts in the footer once
#                    that conversation is resumed (↵ asks first, then resumes). NOW is the live conversations
#                    in open tabs, and nothing here is in a tab. "Nightly sync" (its file is 25 minutes old)
#                    and "Billing cleanup" have no conversation in an open tab, so they are in the first
#                    EARLIER band. Run scripts/seed-agents-overview-live.sh to put live conversations in the
#                    tabs and see them in NOW.
#   LAST FEW DAYS    everything finished within 3 days, newest first: "Release train",
#                    a file task whose three sessions are all done but none has ended; today's two
#                    sessions, "Rate limiter rewrite" and "Auth token refresh" (a session from
#                    yesterday and one from 10 days ago: the task is as recent as its newest),
#                    "Release 4.2" and the 2-day-old session.
#   EARLIER's cut    only the newest five EARLIER rows show, whichever band they are in, then one
#                    "+ N more" line. Click it, or press ↓ on the last row, to see the rest, among
#                    them the LAST TWO WEEKS band (3 to 14 days: "Flaky checkout tests", the 6- and
#                    12-day sessions, "Onboarding emails", "Docs sweep"). Search is never cut.
#   An EARLIER task  one dimmed row reading "finished" (title, repo, age), however many sessions it
#                    holds; ↵ asks, then a second ↵ resumes its own session, or its newest when it has none.
#   Awkward rows     "seed-home" (run from the home folder), "seed-branch-only" (only a run-named
#                    branch), "seed-run-id-title" and "seed-folder-title" (a stored title that is a
#                    run id or a folder name): each is named by its first prompt, never "~", the
#                    branch or the folder. "Owner test follow-up" has a bold preview and "Show the
#                    migration" a preview that is only a code fence: both read as plain text.
#   A restart        "Trace the slow login" (open, idle for 2 days) and "Rebuild the search index"
#                    (open, working for 5 hours) are ended by the server when it starts. They are
#                    aged from when they last did anything (2d and 5h), not from the restart.
#   Release 4.2 notes  a task with its own conversation, the status "Notes drafted, waiting on the
#                    changelog check" and two agents that ran in background tabs, all ended, so it is one
#                    "finished" row in the first EARLIER band. Search finds its own conversation but never the
#                    two background ones. Run scripts/seed-agents-overview-live.sh for the live version in NOW.
#                    It also holds the panel's four blocks (Agents, Progress, Links, Recent activity),
#                    each listing five and then "+ N more", and a long activity entry followed by a short one
#                    to show the gap between them at the panel's narrowest width. The panel shows once the
#                    task's own conversation is in a tab, which the live script arranges for its own task.
#   Search           type "invoice": three sessions of "Invoice export migration", 30 days old,
#                    found only through their task's title ("in task Invoice export migration").
#                    "token" also matches by task; "limiter" matches by title first.
#
# Live cases: scripts/seed-agents-overview-live.sh puts sessions in the tabs of the running scratch
# server (plain shell panes that post the hook events an agent would), and shows a task's second
# window landing in the task's tab. For a real agent, open a pane in the scratch server and start
# `claude` in it. To make that agent group its own sessions, give its MCP client TLS verification
# off (NODE_TLS_REJECT_UNAUTHORIZED=0, because the dev server's certificate is self-signed;
# localhost only) and tell it to split the work in two.

set -euo pipefail

if [ "$#" -ne 2 ]; then
    echo "usage: $0 <db file> <tasks folder>" >&2
    exit 2
fi

cd "$(dirname "$0")/.."
exec ./gradlew -q :server:seedAgentsOverview -Plunula.toolkit.useArtifacts=true \
    --args="$(printf '%q %q' "$1" "$2")"
