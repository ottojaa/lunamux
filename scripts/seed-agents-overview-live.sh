#!/usr/bin/env bash
#
# Put live agent sessions in the tabs of a seeded scratch server, so the agents overview (⌘⇧A),
# the footer count and a task's panel can be looked at with something live.
#
#   scripts/seed-agents-overview-live.sh [server url] [moment]   (default https://localhost:8471, moment 1)
#
# Run scripts/seed-agents-overview.sh first and start the scratch server: the seed turns on the MCP
# endpoint and adds the token this script sends. It refuses any URL that is not on localhost or
# that names port 8443, the production port. It uses no real agent: each "session" is a plain shell
# pane that posts the hook events a Claude Code session would, through the pane's LUNAMUX_*
# variables. What it builds:
#
#   tab "Checkout"   a working conversation "Rate limiter" in a task of its own, then a second
#                    window made for that task with `create_window` and a `taskId` while the tab
#                    "Docs" is active: it must land in "Checkout" (the script fails if it does not).
#   tab "Docs"       a working conversation "Docs sweep" (its long command description is cut by
#                    the row), one that needs you ("Which branch?") and one that is done.
#   tab "Release notes"  the main conversation of a task of the same name, with a status line, a
#                    panel (⌘I in this tab) and two agents it started in background tabs.
#
# The moment (second argument) says what the background agents are doing:
#
#   1  both work.
#   4  "Screenshot refresh" stops on a permission prompt: the footer reads "▲ 1 waiting", and ↵ on
#      the task's row shows its tab, which hides again once the prompt is answered.
#   5  the "Release notes" tab is closed while both agents keep working: the row reads "2 working"
#      and ↵ asks "Resume it in a new tab?".
#
# Closing "Docs" ends its conversations: they leave NOW at once and are in EARLIER as "finished".

set -euo pipefail

BASE="${1:-https://localhost:8471}"
MOMENT="${2:-1}"
case "$MOMENT" in 1|4|5) ;; *) echo "moment must be 1, 4 or 5" >&2; exit 2 ;; esac
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
hook "$SES2" live-docs '{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"grep -rhoE -- \"--[a-z][a-z-]+\" docs | sort -u > /tmp/flags.txt; diff /tmp/flags.txt /tmp/known.txt","description":"Compare every flag in the docs against the ones the server reads and list the stale ones"}}'
ARGS="{\"tabId\":\"$TAB2\"}"
DOCS_WIN="$(call create_window "$ARGS")"
SES3="$(field "$DOCS_WIN" 'd["sessionId"]')"
title "$SES3" "Which branch?"
hook "$SES3" live-question '{"hook_event_name":"UserPromptSubmit","prompt":"Cut the release branch"}'
hook "$SES3" live-question '{"hook_event_name":"Notification","message":"Claude needs your input","notification_type":"elicitation_dialog"}'

# A conversation that has finished its turn: Stop means done.
DONE_WIN="$(call create_window "{\"tabId\":\"$TAB2\"}")"
SES8="$(field "$DONE_WIN" 'd["sessionId"]')"
title "$SES8" "Fix the typo"
hook "$SES8" live-done '{"hook_event_name":"UserPromptSubmit","prompt":"Fix the typo in the README"}'
hook "$SES8" live-done '{"hook_event_name":"Stop","last_assistant_message":"Fixed; the README reads correctly now."}'

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
hook "$SES7" live-screens '{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"scripts/take-screenshots.sh","description":"Retake the release screenshots"}}'
sleep 1
call set_task_lead "{\"taskId\":\"$NOTES\",\"sessionId\":\"$SES5\"}" >/dev/null
call set_task_status "{\"taskId\":\"$NOTES\",\"text\":\"Notes drafted, waiting on the changelog check\"}" >/dev/null
# The task's panel (⌘I in the "Release notes" tab), as a writing agent would set it: progress,
# eight links of several kinds (more than the five the panel shows, so it says "+ N more") and an
# activity list whose first line wraps to three lines at the narrowest width.
call set_task_panel "$(python3 -I -c 'import json,sys,time
now = int(time.time() * 1000)
def link(kind, label, url, ago): return {"kind": kind, "label": label, "url": url, "at": now - ago * 60000}
print(json.dumps({"taskId": sys.argv[1], "by": "release notes agent", "blocks": [
  {"id": "progress", "type": "progress", "title": "Progress", "done": 3, "total": 5, "label": "Matching the changelog entries to merged pull requests"},
  {"id": "links", "type": "links", "title": "Links", "items": [
    link("doc", "Release 4.3 notes, first draft", "https://example.com/notes/4.3", 40),
    link("slack", "Release thread", "https://example.slack.com/archives/C0RELEASE/p1730800000", 12),
    link("PR", "Changelog check", "https://github.com/example/lunamux/pull/412", 3),
    link("art", "Notes preview", "https://claude.ai/artifact/seed-release-notes", 5),
    link("doc", "Screenshot list", "file:///tmp/release-4.3/screenshots.md", 25),
    link("issue", "Known issues", "https://github.com/example/lunamux/issues/398", 180),
    link("brief", "Brief: release notes for 4.3", "https://example.com/briefs/release-4.3", 300),
    link("doc", "You decided: notes stay one page", "https://example.com/decisions/one-page", 240)]},
  {"id": "activity", "type": "timeline", "title": "Recent activity", "items": [
    {"at": now - 2 * 60000, "text": "Changelog check found two gaps: the entry for the sidebar fix has no merged pull request, and the one for the search change points at a pull request that was closed without merging"},
    {"at": now - 5 * 60000, "text": "Draft sent for review"},
    {"at": now - 12 * 60000, "text": "Release thread opened for sign-off"},
    {"at": now - 20 * 60000, "text": "Screenshot refresh started"},
    {"at": now - 40 * 60000, "text": "Draft written"},
    {"at": now - 70 * 60000, "text": "Upgrade guide linked"},
    {"at": now - 120 * 60000, "text": "Branch cut"}]},
]}))' "$NOTES")" >/dev/null
echo "task \"Release notes\" ($NOTES): main conversation in tab $TAB3, two background tabs, status and panel set"

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
case "$MOMENT" in
    4)
        hook "$SES7" live-screens '{"hook_event_name":"Notification","message":"Claude needs your permission to use Bash","notification_type":"permission_prompt"}'
        echo "moment 4: \"Screenshot refresh\" (a background agent) waits on a permission prompt" ;;
    5)
        call close_tab "{\"tabId\":\"$TAB3\"}" >/dev/null
        echo "moment 5: the \"Release notes\" tab is closed; its two background agents keep working" ;;
esac
echo "Open https://localhost:${BASE##*:} and press ⌘⇧A."
