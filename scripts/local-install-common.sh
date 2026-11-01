#!/usr/bin/env bash
# Shared helpers for local-install.sh and local-rollback.sh. Sourced, never run.
#
# Every location the two scripts touch comes from an environment variable with the real
# default, so a dry run can point all of them at scratch copies and never go near a real install:
#
#   LUNAMUX_APP_PATH       the installed app            (default /Applications/Lunamux.app)
#   LUNAMUX_DATA_DIR       the server's data directory  (default ~/Library/Application Support/Termtastic)
#   LUNAMUX_USERDATA_DIR   Electron's userData directory (default ~/Library/Application Support/termtastic-electron)
#   LUNAMUX_BACKUP_ROOT    where backups are written    (default ~/Lunamux-backups)
#   LUNAMUX_PORT           the installed server's port  (default 8443)
#   LUNAMUX_SKIP_QUIT=1    do not stop the server or the app (scratch runs; logged)
#   LUNAMUX_NO_LAUNCH=1    do not start the app afterwards  (scratch runs; logged)
#
# The server database is copied with sqlite3's online backup, so a copy taken while the server
# runs is consistent and includes anything still in the write-ahead log.

APP_PATH="${LUNAMUX_APP_PATH:-/Applications/Lunamux.app}"
DATA_DIR="${LUNAMUX_DATA_DIR:-$HOME/Library/Application Support/Termtastic}"
# The packaged app pins Electron's userData to the directory the app had before it was renamed
# (device-auth token, preferences, cookies). lunamux-electron exists but stays empty.
USERDATA_DIR="${LUNAMUX_USERDATA_DIR:-$HOME/Library/Application Support/termtastic-electron}"
BACKUP_ROOT="${LUNAMUX_BACKUP_ROOT:-$HOME/Lunamux-backups}"
PORT="${LUNAMUX_PORT:-8443}"
DB_NAME="termtastic.db"

log() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# Refuse to go on when this shell runs inside Lunamux. Both scripts end the app, and a shell that
# lives in one of its terminals dies with it halfway through, leaving the old app moved away and
# the new one not yet in place. The process tree is walked upwards from this shell: any ancestor
# whose executable lives inside Lunamux.app means Lunamux is hosting it. Only the executable's
# path is looked at, not the whole command line, which can merely mention the app.
refuse_inside_lunamux() {
    local p=$$ cmd
    while [[ -n "$p" && "$p" != 0 && "$p" != 1 ]]; do
        cmd="$(ps -o comm= -p "$p" 2>/dev/null || true)"
        if [[ "$cmd" == *"Lunamux.app"* ]]; then
            die "this shell runs inside Lunamux, and quitting Lunamux would kill it halfway; nothing was changed. Run it from macOS Terminal.app"
        fi
        p="$(ps -o ppid= -p "$p" 2>/dev/null | tr -d ' ' || true)"
    done
}

# Wait up to $1 seconds for the port to stop listening. Returns 0 when it did.
wait_port_closed() {
    local i
    for i in $(seq 1 $(($1 * 2))); do
        lsof -tiTCP:"$PORT" -sTCP:LISTEN -n -P >/dev/null 2>&1 || return 0
        sleep 0.5
    done
    return 1
}

# Stop the installed server (so the next launch runs the new jar rather than reusing the old
# one, which outlives the app) and then the app. The server is asked to shut down through its
# loopback endpoint, which flushes window state and scrollback first; if that fails the
# listener is sent SIGTERM, whose shutdown hook does the same. Refuses to go on while something
# still listens on the port. The app is ended after the server, with SIGTERM and then SIGKILL:
# the app answers a quit request with its "quit Lunamux?" dialog and waits for a person.
stop_lunamux() {
    if [[ "${LUNAMUX_SKIP_QUIT:-}" == "1" ]]; then
        log "LUNAMUX_SKIP_QUIT=1: not stopping the server on port $PORT or the app"
        return 0
    fi
    if lsof -tiTCP:"$PORT" -sTCP:LISTEN -n -P >/dev/null 2>&1; then
        log "Asking the server on port $PORT to flush and shut down"
        curl -ks -m 10 -X POST "https://127.0.0.1:$PORT/admin/shutdown" >/dev/null || true
        if ! wait_port_closed 15; then
            local pids
            pids="$(lsof -tiTCP:"$PORT" -sTCP:LISTEN -n -P)"
            log "Still listening; sending SIGTERM to $pids"
            # shellcheck disable=SC2086
            kill $pids 2>/dev/null || true
            wait_port_closed 15 || die "something still listens on port $PORT; stopping here with nothing replaced"
        fi
    else
        log "No server is listening on port $PORT"
    fi
    if app_running; then
        log "Ending the app"
        # SIGTERM first. Electron turns it into a normal quit, and this app answers a quit by
        # opening its "quit Lunamux?" dialog instead of exiting, so it will usually still be
        # running afterwards. The server is already down and the data is copied only after this
        # returns, so ending the app hard is safe: the next step is SIGKILL.
        pkill -TERM -f "$APP_PATH/Contents/" || true
        wait_app_gone 5 && return 0
        log "The app is still running (it asks before quitting); sending SIGKILL"
        pkill -KILL -f "$APP_PATH/Contents/" || true
        wait_app_gone 5 && return 0
        die "the app is still running; stopping here with nothing replaced"
    fi
}

# True when a process of the installed app (main or helper) is running.
app_running() { pgrep -f "$APP_PATH/Contents/" >/dev/null 2>&1; }

# Wait up to $1 seconds for the app's processes to be gone. Returns 0 when they are.
wait_app_gone() {
    local i
    for i in $(seq 1 $(($1 * 2))); do
        app_running || return 0
        sleep 0.5
    done
    return 1
}

# Copy the server data and Electron userData into directory $1 (created). The database is
# taken with sqlite3 .backup; everything else is a plain copy. Missing sources are skipped
# and said so.
snapshot_data() {
    local dest="$1"
    mkdir -p "$dest/data" "$dest/userdata"
    if [[ -f "$DATA_DIR/$DB_NAME" ]]; then
        sqlite3 "$DATA_DIR/$DB_NAME" ".backup '$dest/data/$DB_NAME'"
        [[ "$(sqlite3 "$dest/data/$DB_NAME" 'PRAGMA integrity_check;')" == "ok" ]] \
            || die "the copy of $DATA_DIR/$DB_NAME failed its integrity check"
        log "Database copied: $dest/data/$DB_NAME"
    else
        log "No database at $DATA_DIR/$DB_NAME; nothing to copy"
    fi
    local item
    for item in tls shell-init; do
        if [[ -e "$DATA_DIR/$item" ]]; then ditto "$DATA_DIR/$item" "$dest/data/$item"; fi
    done
    if [[ -d "$USERDATA_DIR" ]]; then ditto "$USERDATA_DIR" "$dest/userdata"; fi
}

# Put the data saved in directory $1 back: the database (any stale -wal/-shm removed so the
# copy is not mixed with another database's log), tls, shell-init and Electron userData.
restore_data() {
    local src="$1"
    local data="${DATA_DIR:?}" userdata="${USERDATA_DIR:?}" db="${DB_NAME:?}"
    mkdir -p "$data"
    if [[ -f "$src/data/$db" ]]; then
        rm -f "$data/$db" "$data/$db-wal" "$data/$db-shm"
        cp -p "$src/data/$db" "$data/$db"
        log "Database restored"
    fi
    local item
    for item in tls shell-init; do
        if [[ -e "$src/data/$item" ]]; then
            rm -rf "$data/${item:?}"
            ditto "$src/data/$item" "$data/$item"
        fi
    done
    if [[ -d "$src/userdata" ]]; then
        rm -rf "$userdata"
        mkdir -p "$userdata"
        ditto "$src/userdata" "$userdata"
    fi
}

# Start the app, unless LUNAMUX_NO_LAUNCH=1.
launch_lunamux() {
    if [[ "${LUNAMUX_NO_LAUNCH:-}" == "1" ]]; then
        log "LUNAMUX_NO_LAUNCH=1: not starting the app"
    else
        log "Starting $APP_PATH"
        open "$APP_PATH"
    fi
}

# Write directory $1's manifest.env, which local-rollback.sh needs to accept it as a backup. $2 is
# 1 when an installed app was saved into the directory, otherwise 0.
write_manifest() {
    cat > "$1/manifest.env" <<MANIFEST
APP_PATH='$APP_PATH'
DATA_DIR='$DATA_DIR'
USERDATA_DIR='$USERDATA_DIR'
HAD_APP=$2
MANIFEST
}

# A UTC timestamp usable in a directory name.
stamp() { date -u +%Y%m%dT%H%M%SZ; }
