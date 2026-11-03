#!/usr/bin/env bash
#
# Put live agent sessions in the tabs of a seeded scratch server, so the agents overview (⌘⇧A)
# can be looked at with something in NOW, and so a task's second window can be seen landing in the
# task's tab.
#
#   scripts/seed-agents-overview-live.sh [server url]        (default https://localhost:8471)
#
# Run scripts/seed-agents-overview.sh first and start the scratch server it describes: the seed
# turns the MCP endpoint on and adds the read+write token this script sends. The script talks to the
# scratch server only: it refuses any URL that is not on localhost or that names port 8443, the
# production port.
#
# It uses no real agent. Each "session" is a plain shell pane that posts the hook events a Claude
# Code session would post (`UserPromptSubmit`, `PreToolUse`, `Notification`), through the pane's own
# LUNAMUX_* variables, so the server tracks it as a hosted session. What it builds:
#
#   tab "Checkout"   a working session "Rate limiter" in a task of its own; then a second window
#                    made for that task with `create_window` and a `taskId` but no `tabId` while the
#                    tab "Docs" is the active one. The second window lands in "Checkout", beside the
#                    first, and its session joins the task: the script prints the tab it landed in.
#   tab "Docs"       a working session "Docs sweep" and a session that needs you ("Which branch?").
#   tab "Release notes"  a working session that owns a task of the same name and gives it a status line,
#                    and two agents that session started out of sight (create_window with background
#                    and the task's id), in background tabs that no
#                    tab strip lists: "Changelog check" (working) and "Screenshot refresh" (asks
#                    "Which release?"). The overview counts and shows both; ⌘↵ on one takes its tab over.
#                    The task also has a panel of blocks written by "release notes agent": in this tab ⌘I
#                    shows it (the agents block first, both helpers listed, one working), in any other tab
#                    there is no panel. "peek" on a helper opens its terminal read-only; ⌘↵ takes it over.
#
# What to look at (⌘⇧A):
#
#   NOW              the two Checkout sessions as one task panel, "Docs sweep", and the ◆ question.
#                    "Nightly sync" and "Billing cleanup" (task files whose members are not in any
#                    open tab) are in EARLIER, not NOW. "Access control" stays in NOW because one of
#                    its members needs you, wherever it runs.
#   Release notes    the task's row carries its status; ↵ on it jumps to the owning session. ↵ on a
#                    background row opens the read-only peek, ⌘↵ takes the tab over. Close the "Release
#                    notes" tab in the app to see the question that lists the two agents that keep running.
#   Close "Docs"     close the tab in the app: its panes close and their sessions end. A session that
#                    has just ended stays in NOW for ten minutes, so "Docs sweep" and the question are
#                    still listed (as ended) right after the close and the chip drops by one working and
#                    one needs-you; after the ten minutes they are in the first band of EARLIER. (A
#                    question from a session that is still alive but not in any open tab, like
#                    "Role model" in "Access control", stays in NOW.)

set -euo pipefail

BASE="${1:-https://localhost:8471}"
TOKEN="aov-seed-mcp-token"

case "$BASE" in
    https://localhost:*|https://127.0.0.1:*) ;;
    *) echo "refusing $BASE: this script drives a scratch server on localhost only" >&2; exit 2 ;;
esac
case "$BASE" in
    *:8443*) echo "refusing port 8443: that is the production server" >&2; exit 2 ;;
esac

HDR="$(mktemp)"
trap 'rm -f "$HDR"' EXIT
curl -sk -D "$HDR" -o /dev/null -H "X-Termtastic-Auth: $TOKEN" -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"seed-live","version":"1"}}}' \
    "$BASE/mcp"
MCP_SESSION="$(grep -i '^mcp-session-id' "$HDR" | awk '{print $2}' | tr -d '\r')"
[ -n "$MCP_SESSION" ] || { echo "no MCP session: is the scratch server up, seeded, and on $BASE?" >&2; exit 1; }

# call <tool> <arguments json> -> the tool's text result on stdout
call() {
    local body
    body="$(python3 -I -c 'import json,sys; print(json.dumps({"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":sys.argv[1],"arguments":json.loads(sys.argv[2])}}))' "$1" "$2")"
    curl -sk -H "X-Termtastic-Auth: $TOKEN" -H "Mcp-Session-Id: $MCP_SESSION" -H 'Content-Type: application/json' -d "$body" "$BASE/mcp" |
        python3 -I -c 'import json,sys; r=json.load(sys.stdin); print(r["result"]["content"][0]["text"] if "result" in r else json.dumps(r))'
}

# field <json> <python expression over d> -> prints the expression's value
field() { python3 -I -c 'import json,sys; d=json.loads(sys.argv[1]); print(eval(sys.argv[2]))' "$1" "$2"; }

# hook <terminal session id> <agent session id> <event json fields> : post one hook event from inside the pane
hook() {
    local json
    json="$(python3 -I -c 'import json,sys; e=json.loads(sys.argv[3]); e["session_id"]=sys.argv[2]; print(json.dumps(e))' "$1" "$2" "$3")"
    call run_command "$(python3 -I -c 'import json,sys
cmd = "printf %s " + json.dumps(sys.argv[2]) + " | curl -sk -m 2 -X POST -H \"X-Lunamux-Events-Token: $LUNAMUX_EVENTS_TOKEN\" -H \"X-Lunamux-Session: $LUNAMUX_SESSION_ID\" --data-binary @- \"$LUNAMUX_EVENTS_URL\" >/dev/null 2>&1"
print(json.dumps({"sessionId": sys.argv[1], "command": cmd}))' "$1" "$json")" >/dev/null
}

# title <terminal session id> <text> : give the pane's window a custom name, which is what the
# session's row is called
title() {
    local win
    win="$(field "$(call list_layout '{}')" '[w for t in d["tabs"] for w in t["windows"] if w.get("sessionId")=="'"$1"'"][0]["windowId"]')"
    call rename_window "$(python3 -I -c 'import json,sys; print(json.dumps({"windowId": sys.argv[1], "title": sys.argv[2]}))' "$win" "$2")" >/dev/null
}

LAYOUT="$(call list_layout '{}')"
TAB1="$(field "$LAYOUT" 'd["tabs"][0]["tabId"]')"
WIN1="$(field "$LAYOUT" 'd["tabs"][0]["windows"][0]["windowId"]')"
SES1="$(field "$LAYOUT" 'd["tabs"][0]["windows"][0]["sessionId"]')"
call rename_tab "{\"tabId\":\"$TAB1\",\"title\":\"Checkout\"}" >/dev/null

# Checkout: the first session, and a task of its own so the second window has something to join.
title "$SES1" "Rate limiter"
hook "$SES1" live-ratelimiter '{"hook_event_name":"UserPromptSubmit","prompt":"Write the token bucket for the API gateway"}'
hook "$SES1" live-ratelimiter '{"hook_event_name":"PreToolUse","tool_name":"Edit","tool_input":{"file_path":"gateway/TokenBucket.kt"}}'
sleep 2
ARGS="{\"title\":\"Rate limiter\",\"sessionIds\":[\"$SES1\"]}"
TASK="$(field "$(call create_task "$ARGS")" 'd["taskId"]')"

# Docs: a second tab, made the active one, with a working session and one that needs you.
TAB2="$(field "$(call create_tab '{"title":"Docs"}')" 'd["tabId"]')"
call activate_tab "{\"tabId\":\"$TAB2\"}" >/dev/null
LAYOUT="$(call list_layout '{}')"
SES2="$(field "$LAYOUT" '[w for t in d["tabs"] if t["tabId"]=="'"$TAB2"'" for w in t["windows"]][0]["sessionId"]')"
title "$SES2" "Docs sweep"
hook "$SES2" live-docs '{"hook_event_name":"UserPromptSubmit","prompt":"Sweep the docs for stale flags"}'
hook "$SES2" live-docs '{"hook_event_name":"PreToolUse","tool_name":"Edit","tool_input":{"file_path":"docs/tasks.md"}}'
ARGS="{\"tabId\":\"$TAB2\"}"
DOCS_WIN="$(call create_window "$ARGS")"
SES3="$(field "$DOCS_WIN" 'd["sessionId"]')"
title "$SES3" "Which branch?"
hook "$SES3" live-question '{"hook_event_name":"UserPromptSubmit","prompt":"Cut the release branch"}'
hook "$SES3" live-question '{"hook_event_name":"Notification","message":"Claude needs your input","notification_type":"elicitation_dialog"}'

# Release notes: the task's own session in its own tab, and two agents it started out of sight.
TAB3="$(field "$(call create_tab '{"title":"Release notes"}')" 'd["tabId"]')"
LAYOUT="$(call list_layout '{}')"
SES5="$(field "$LAYOUT" '[w for t in d["tabs"] if t["tabId"]=="'"$TAB3"'" for w in t["windows"]][0]["sessionId"]')"
title "$SES5" "Release notes"
hook "$SES5" live-notes '{"hook_event_name":"UserPromptSubmit","prompt":"Write the release notes for 4.3 and split the checks between two agents"}'
hook "$SES5" live-notes '{"hook_event_name":"PreToolUse","tool_name":"Edit","tool_input":{"file_path":"docs/release-notes.md"}}'
# The task first, from the tab that holds its own session; then the two helpers join it as
# background windows, the way an agent that fans work out would open them.
ARGS="{\"title\":\"Release notes\",\"sessionIds\":[\"$SES5\"]}"
NOTES="$(field "$(call create_task "$ARGS")" 'd["taskId"]')"
ARGS="{\"background\":true,\"taskId\":\"$NOTES\",\"title\":\"Changelog check\"}"
SES6="$(field "$(call create_window "$ARGS")" 'd["sessionId"]')"
title "$SES6" "Changelog check"
hook "$SES6" live-changelog '{"hook_event_name":"UserPromptSubmit","prompt":"Check every changelog entry against the merged pull requests"}'
hook "$SES6" live-changelog '{"hook_event_name":"PreToolUse","tool_name":"Read","tool_input":{"file_path":"CHANGELOG.md"}}'
ARGS="{\"background\":true,\"taskId\":\"$NOTES\",\"title\":\"Screenshot refresh\"}"
SES7="$(field "$(call create_window "$ARGS")" 'd["sessionId"]')"
title "$SES7" "Screenshot refresh"
hook "$SES7" live-screens '{"hook_event_name":"UserPromptSubmit","prompt":"Retake the screenshots the notes link to"}'
hook "$SES7" live-screens '{"hook_event_name":"Notification","message":"Claude needs your input","notification_type":"elicitation_dialog"}'
sleep 1
call set_task_lead "{\"taskId\":\"$NOTES\",\"sessionId\":\"$SES5\"}" >/dev/null
call set_task_status "{\"taskId\":\"$NOTES\",\"text\":\"Notes drafted, waiting on the changelog check\"}" >/dev/null
# The task's panel (⌘I in the "Release notes" tab): what a writing agent would put there. The
# second text block holds markup that must stay text.
call set_task_panel "$(python3 -I -c 'import json,sys
import time
now = int(time.time() * 1000)
print(json.dumps({"taskId": sys.argv[1], "by": "release notes agent", "blocks": [
  {"id": "status", "type": "text", "title": "Status", "text": "**Drafted**, waiting on the changelog check.\n\n- 14 entries checked\n- 2 still to match"},
  {"id": "checks", "type": "progress", "title": "Checks", "done": 3, "total": 5, "label": "changelog entries"},
  {"id": "history", "type": "timeline", "title": "Timeline", "items": [
    {"at": now - 7200000, "text": "Branch cut"}, {"at": now - 2400000, "text": "Draft written"}]},
  {"id": "links", "type": "links", "title": "Links", "items": [
    {"label": "Draft notes", "url": "https://example.com/notes/4.3"}, {"label": "Changelog", "url": "https://example.com/changelog"}]},
  {"id": "literal", "type": "text", "title": "Pasted from a log", "text": "Kept as text: <img src=x onerror=alert(1)>"},
  {"id": "actions", "type": "actions", "title": "Actions", "buttons": [
    {"id": "status", "label": "Ask for status", "text": "What is left to do?"}]},
]}))' "$NOTES")" >/dev/null
echo "task \"Release notes\" ($NOTES): own session in tab $TAB3, two background tabs, status and panel set"

# The second window of the task, made with no tabId while "Docs" is the active tab.
ARGS="{\"taskId\":\"$TASK\"}"
NEW="$(call create_window "$ARGS")"
LANDED="$(field "$NEW" 'd["tabId"]')"
SES4="$(field "$NEW" 'd["sessionId"]')"
title "$SES4" "Limiter tests"
hook "$SES4" live-limiter-tests '{"hook_event_name":"UserPromptSubmit","prompt":"Cover the limiter with tests"}'
if [ "$LANDED" = "$TAB1" ]; then
    echo "second window of task $TASK landed in the task's tab \"Checkout\" ($LANDED), not the active tab \"Docs\" ($TAB2)"
else
    echo "second window of task $TASK landed in $LANDED; the task's tab is $TAB1" >&2
    exit 1
fi
call activate_tab "{\"tabId\":\"$TAB1\"}" >/dev/null
echo "Open https://localhost:${BASE##*:} and press ⌘⇧A."
