#!/usr/bin/env bash
#
# Seed a scratch database and task folder for the agents overview (⌘⇧A).
#
#   scripts/seed-agents-overview.sh <db file> <tasks folder>
#
# Writes ended agent sessions last active within the hour and 2, 6, 12 and 30 days ago, three
# agent-made tasks, two task files, awkward rows (a home folder, a run-named branch, markdown,
# a bare code fence) and two sessions left open for a server start to end. It also turns MCP on
# with a read+write token for scripts/seed-agents-overview-live.sh, and the Claude usage poll
# off, because a server with it on launches the real `claude`. Run it again to refresh the
# ages. Seed while the scratch server is STOPPED. It refuses the real database, the real task
# folder and a database named termtastic.db or lunamux-dev.db.
#
# Start a scratch server on its own port, database, folders and home, so it reads none of the
# real ones, then open https://localhost:8471 and press ⌘⇧A:
#
#   ./gradlew :server:run -Plunula.toolkit.useArtifacts=true -Duser.home=/tmp/aov-seed/home \
#       -Dlunamux.port=8471 -Dlunamux.dbPath=/tmp/aov-seed/lunamux.db \
#       -Dlunamux.tasksDir=/tmp/aov-seed/tasks -Dlunamux.claudeProjects=/tmp/aov-seed/projects
#
# Search "invoice": two sessions found only through their task's title. Nothing is live until
# the live script puts conversations in tabs.

set -euo pipefail

if [ "$#" -ne 2 ]; then
    echo "usage: $0 <db file> <tasks folder>" >&2
    exit 2
fi

cd "$(dirname "$0")/.."
exec ./gradlew -q :server:seedAgentsOverview -Plunula.toolkit.useArtifacts=true \
    --args="$(printf '%q %q' "$1" "$2")"
