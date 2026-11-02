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
#
# What to look at (⌘⇧A):
#
#   NOW              the two Checkout sessions as one task panel, "Docs sweep", and the ◆ question.
#                    "Nightly sync" and "Billing cleanup" (task files whose members are not in any
#                    open tab) are in EARLIER, not NOW. "Access control" stays in NOW because one of
#                    its members needs you, wherever it runs.
#   Close "Docs"     close the tab in the app: its panes close and their sessions end, so "Docs sweep"
#                    and the question leave NOW for the first band of EARLIER, and the chip drops by one
#                    working and one needs-you. (A question from a session that is still alive but not in
#                    any open tab, like "Role model" in "Access control", stays in NOW.)

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

LAYOUT="$(call list_layout '{}')"
TAB1="$(field "$LAYOUT" 'd["tabs"][0]["tabId"]')"
WIN1="$(field "$LAYOUT" 'd["tabs"][0]["windows"][0]["windowId"]')"
SES1="$(field "$LAYOUT" 'd["tabs"][0]["windows"][0]["sessionId"]')"
call rename_tab "{\"tabId\":\"$TAB1\",\"title\":\"Checkout\"}" >/dev/null

# Checkout: the first session, and a task of its own so the second window has something to join.
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
hook "$SES2" live-docs '{"hook_event_name":"UserPromptSubmit","prompt":"Sweep the docs for stale flags"}'
hook "$SES2" live-docs '{"hook_event_name":"PreToolUse","tool_name":"Edit","tool_input":{"file_path":"docs/tasks.md"}}'
ARGS="{\"tabId\":\"$TAB2\"}"
DOCS_WIN="$(call create_window "$ARGS")"
SES3="$(field "$DOCS_WIN" 'd["sessionId"]')"
hook "$SES3" live-question '{"hook_event_name":"UserPromptSubmit","prompt":"Cut the release branch"}'
hook "$SES3" live-question '{"hook_event_name":"Notification","message":"Claude needs your input","notification_type":"elicitation_dialog"}'

# The second window of the task, made with no tabId while "Docs" is the active tab.
ARGS="{\"taskId\":\"$TASK\"}"
NEW="$(call create_window "$ARGS")"
LANDED="$(field "$NEW" 'd["tabId"]')"
SES4="$(field "$NEW" 'd["sessionId"]')"
hook "$SES4" live-limiter-tests '{"hook_event_name":"UserPromptSubmit","prompt":"Cover the limiter with tests"}'
if [ "$LANDED" = "$TAB1" ]; then
    echo "second window of task $TASK landed in the task's tab \"Checkout\" ($LANDED), not the active tab \"Docs\" ($TAB2)"
else
    echo "second window of task $TASK landed in $LANDED; the task's tab is $TAB1" >&2
    exit 1
fi
call activate_tab "{\"tabId\":\"$TAB1\"}" >/dev/null
echo "Open https://localhost:${BASE##*:} and press ⌘⇧A."
