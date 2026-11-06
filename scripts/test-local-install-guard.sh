#!/usr/bin/env bash
# Checks that scripts/local-install.sh and scripts/local-rollback.sh refuse to run from a shell
# hosted by Lunamux, and say to use macOS Terminal.app, before they change anything.
#
# Usage: scripts/test-local-install-guard.sh   (from macOS Terminal.app, not from Lunamux)
#
# Lunamux is faked by a symlink to /bin/sh placed at <scratch>/Lunamux.app/Contents/MacOS/sh (a
# copy of the binary is killed by macOS): a shell started through that path has an executable
# inside Lunamux.app, which is all the guard looks for. Every location the two scripts use is pointed at the scratch directory, and the
# guard runs before the first of them is touched, so the real install is never involved.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$scratch/Lunamux.app/Contents/MacOS"
ln -s /bin/sh "$scratch/Lunamux.app/Contents/MacOS/sh"
fake_lunamux_shell="$scratch/Lunamux.app/Contents/MacOS/sh"

export LUNAMUX_APP_PATH="$scratch/installed/Lunamux.app"
export LUNAMUX_DATA_DIR="$scratch/data"
export LUNAMUX_USERDATA_DIR="$scratch/userdata"
export LUNAMUX_BACKUP_ROOT="$scratch/backups"
export LUNAMUX_PORT=59999
export LUNAMUX_SKIP_QUIT=1
export LUNAMUX_NO_LAUNCH=1

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

# shellcheck source=scripts/local-install-common.sh
source scripts/local-install-common.sh
if ( refuse_inside_lunamux ) >/dev/null 2>&1; then
    echo "ok: a shell outside Lunamux is let through"
else
    fail "refused although this shell is not inside Lunamux (run this from Terminal.app)"
fi

for script in local-install.sh local-rollback.sh; do
    status=0
    # The trailing "exit" keeps the shell from exec-ing bash, which would remove it from the tree.
    out="$("$fake_lunamux_shell" -c "bash scripts/$script; exit \$?" 2>&1)" || status=$?
    [[ "$status" -ne 0 ]] || fail "$script went on inside a fake Lunamux"
    [[ "$out" == *"Terminal.app"* ]] || fail "$script did not say to use Terminal.app: $out"
    [[ "$out" == *"nothing was changed"* ]] || fail "$script did not say nothing was changed: $out"
    [[ ! -e "$LUNAMUX_BACKUP_ROOT" ]] || fail "$script created a backup directory"
    echo "ok: $script refuses inside Lunamux and names Terminal.app"
done
