#!/usr/bin/env bash
# Shared helpers for local-install.sh and local-rollback.sh. Sourced, never run.
#
# Every location the two scripts touch comes from an environment variable with the real
# default, so a dry run can point all of them at scratch copies and never go near a real install:
#
#   LUNAMUX_APP_PATH       the installed app            (default /Applications/Lunamux.app)
#   LUNAMUX_DATA_DIR       the server's data directory  (default ~/Library/Application Support/Termtastic)
#   LUNAMUX_USERDATA_DIR   Electron's userData directory (default ~/Library/Application Support/lunamux-electron)
#   LUNAMUX_BACKUP_ROOT    where backups are written    (default ~/Lunamux-backups)
#   LUNAMUX_PORT           the installed server's port  (default 8443)
#   LUNAMUX_SKIP_QUIT=1    do not stop the server or the app (scratch runs; logged)
#   LUNAMUX_NO_LAUNCH=1    do not start the app afterwards  (scratch runs; logged)
#
# The server database is copied with sqlite3's online backup, so a copy taken while the server
# runs is consistent and includes anything still in the write-ahead log.

APP_PATH="${LUNAMUX_APP_PATH:-/Applications/Lunamux.app}"
DATA_DIR="${LUNAMUX_DATA_DIR:-$HOME/Library/Application Support/Termtastic}"
USERDATA_DIR="${LUNAMUX_USERDATA_DIR:-$HOME/Library/Application Support/lunamux-electron}"
BACKUP_ROOT="${LUNAMUX_BACKUP_ROOT:-$HOME/Lunamux-backups}"
PORT="${LUNAMUX_PORT:-8443}"
DB_NAME="termtastic.db"

log() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

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
# still listens on the port. The app is ended with SIGTERM after the server: asking it to quit
# would open its "quit Lunamux?" dialog and wait for a person.
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
    if pgrep -f "$APP_PATH/Contents/" >/dev/null 2>&1; then
        log "Ending the app"
        pkill -TERM -f "$APP_PATH/Contents/" || true
        local i
        for i in $(seq 1 20); do
            pgrep -f "$APP_PATH/Contents/" >/dev/null 2>&1 || return 0
            sleep 0.5
        done
        die "the app is still running; stopping here with nothing replaced"
    fi
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

# A UTC timestamp usable in a directory name.
stamp() { date -u +%Y%m%dT%H%M%SZ; }
