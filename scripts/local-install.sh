#!/usr/bin/env bash
# Install a locally built Lunamux over the installed one, keeping a full way back.
#
# Usage: scripts/local-install.sh [path/to/Lunamux.app]
#   With no argument the newest electron/dist/mac*/Lunamux.app (from
#   scripts/build-electron-no-notarize.sh) is used.
#
# What it does, in order:
#   0. Refuses to run inside a Lunamux terminal (quitting the app would kill the script halfway);
#      run it from macOS Terminal.app.
#   1. Checks the new app and the sqlite3 tool exist; changes nothing yet.
#   2. Stops the installed server (flushing state first) and the app. This ENDS the running
#      terminal sessions: they come back as restored scrollback after the new server starts.
#   3. Makes a backup directory <backup root>/<timestamp>/ holding the database (taken with
#      sqlite3 .backup and integrity-checked), tls/, shell-init/ and Electron's userData.
#   4. Moves the installed app into the backup directory (nothing is deleted) and copies the
#      new app into its place.
#   5. Starts the app, and prints the command that undoes all of it.
#
# Undo with scripts/local-rollback.sh. See local-install-common.sh for the variables that
# redirect every path, which is how it is tried out on scratch copies.
set -euo pipefail

# A relative app path is the caller's, so it is made absolute before moving to the repo root.
NEW_APP="${1:-}"
if [[ -n "$NEW_APP" && -d "$NEW_APP" ]]; then NEW_APP="$(cd "$NEW_APP" && pwd)"; fi

cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=scripts/local-install-common.sh
source scripts/local-install-common.sh
refuse_inside_lunamux

if [[ -z "$NEW_APP" ]]; then
    NEW_APP="$(ls -dt electron/dist/mac*/Lunamux.app 2>/dev/null | head -1 || true)"
fi
[[ -n "$NEW_APP" && -x "$NEW_APP/Contents/MacOS/Lunamux" ]] \
    || die "no built app found; run scripts/build-electron-no-notarize.sh or pass the .app path"
command -v sqlite3 >/dev/null || die "sqlite3 is required"
[[ "$(cd "$(dirname "$NEW_APP")" && pwd)/$(basename "$NEW_APP")" != "$APP_PATH" ]] \
    || die "the new app and the installed app are the same path"

BACKUP="$BACKUP_ROOT/$(stamp)"
[[ ! -e "$BACKUP" ]] || die "$BACKUP already exists"
log "Installing $NEW_APP over $APP_PATH"
log "Backup: $BACKUP"

# Stop first, then copy: the server writes its last window state and scrollback while it shuts
# down, and the app's own files are only quiet once it has ended. Nothing has been replaced yet,
# so a failure to stop leaves the installation as it was.
stop_lunamux
snapshot_data "$BACKUP"
write_manifest "$BACKUP" "$([[ -d "$APP_PATH" ]] && echo 1 || echo 0)"

if [[ -d "$APP_PATH" ]]; then
    mv "$APP_PATH" "$BACKUP/Lunamux.app"
    log "Old app moved to $BACKUP/Lunamux.app"
fi
mkdir -p "$(dirname "$APP_PATH")"
ditto "$NEW_APP" "$APP_PATH"
xattr -dr com.apple.quarantine "$APP_PATH" 2>/dev/null || true
log "Installed $APP_PATH"

launch_lunamux
cat <<DONE

Installed. To undo it (restores the old app, database and settings; keeps this state too):
    LUNAMUX_BACKUP_ROOT='$BACKUP_ROOT' $(pwd)/scripts/local-rollback.sh '$BACKUP'
DONE
