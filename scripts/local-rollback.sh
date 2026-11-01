#!/usr/bin/env bash
# Undo scripts/local-install.sh: put the backed-up app, database and settings back.
#
# Usage: scripts/local-rollback.sh [backup-dir]
#   With no argument the newest directory in the backup root that holds a manifest.env is used.
#
# What it does, in order:
#   1. Reads the backup's manifest and checks it holds what it should; changes nothing yet.
#   2. Saves the CURRENT state (app moved aside, database, tls, shell-init, userData) into
#      <backup root>/<timestamp>-before-rollback/, so a rollback is itself reversible.
#   3. Stops the running server (flushing state first) and the app.
#   4. Puts the backed-up app and data back and starts the app.
#
# Sessions the new build had running end at step 3 and return as restored scrollback.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=scripts/local-install-common.sh
source scripts/local-install-common.sh

BACKUP="${1:-}"
if [[ -z "$BACKUP" ]]; then
    BACKUP="$(ls -dt "$BACKUP_ROOT"/*/ 2>/dev/null | while read -r d; do
        [[ -f "${d}manifest.env" ]] && { echo "${d%/}"; break; }
    done)"
fi
[[ -n "$BACKUP" && -f "$BACKUP/manifest.env" ]] || die "no backup with a manifest.env found (looked in $BACKUP_ROOT)"
command -v sqlite3 >/dev/null || die "sqlite3 is required"
HAD_APP=0
# The manifest only decides whether an app was installed before; where things are comes from
# the environment, exactly as for the install.
HAD_APP="$(sed -n "s/^HAD_APP=//p" "$BACKUP/manifest.env")"
if [[ "$HAD_APP" == "1" ]]; then
    [[ -d "$BACKUP/Lunamux.app" ]] || die "$BACKUP has no Lunamux.app to restore"
fi
if [[ -f "$BACKUP/data/$DB_NAME" ]]; then
    [[ "$(sqlite3 "$BACKUP/data/$DB_NAME" 'PRAGMA integrity_check;')" == "ok" ]] \
        || die "the backed-up database fails its integrity check; nothing was changed"
fi

SAVED="$BACKUP_ROOT/$(stamp)-before-rollback"
log "Rolling back to $BACKUP"
log "Current state is kept in $SAVED"

snapshot_data "$SAVED"
stop_lunamux

if [[ -d "$APP_PATH" ]]; then
    mv "$APP_PATH" "$SAVED/Lunamux.app"
fi
if [[ "$HAD_APP" == "1" ]]; then
    ditto "$BACKUP/Lunamux.app" "$APP_PATH"
    log "Old app restored at $APP_PATH"
fi
restore_data "$BACKUP"

launch_lunamux
echo
echo "Rolled back. The state before the rollback is in $SAVED"
