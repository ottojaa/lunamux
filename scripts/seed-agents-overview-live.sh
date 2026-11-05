#!/usr/bin/env bash
#
# Put live agent sessions in the tabs of a seeded scratch server, so the agents overview (⌘⇧A),
# the waiting count under the Claude usage display and a task's panel can be looked at with
# something live, and so a task's second window can be seen landing in the task's tab.
#
#   scripts/seed-agents-overview-live.sh [server url] [moment]   (default https://localhost:8471, moment 1)
#
# Run scripts/seed-agents-overview.sh first and start the scratch server it describes: the seed
# turns the MCP endpoint on and adds the read+write token this script sends. The script talks to the
# scratch server only: it refuses any URL that is not on localhost or that names port 8443, the
# production port.
#
# It uses no real agent. Each "session" is a plain shell pane that posts the hook events a Claude
# Code session would post (`UserPromptSubmit`, `PreToolUse`, `Notification`, `Stop`), through the
# pane's own LUNAMUX_* variables, so the server tracks it as a hosted conversation. What it builds:
#
#   tab "Checkout"   a working conversation "Rate limiter" in a task of its own; then a second window
#                    made for that task with `create_window` and a `taskId` but no `tabId` while the
#                    tab "Docs" is the active one. The second window lands in "Checkout", beside the
#                    first, and its conversation joins the task: the script prints the tab it landed in.
#   tab "Docs"       a working conversation "Docs sweep", one that needs you ("Which branch?") and one
#                    that has finished its turn ("Fix the typo"), which is done.
#   tab "Release notes"  the main conversation of a task of the same name. It is working and gives the
#                    task a status line, and it started two agents out of sight (create_window with
#                    background and the task's id), in background tabs that no tab strip lists:
#                    "Changelog check" and "Screenshot refresh". The task has a panel (progress, links of
#                    every kind, recent activity) written by "release notes agent"; ⌘I in this tab shows
#                    it, in any other tab there is no panel.
#
# The moment (second argument) says what the background agents are doing:
#
#   1  (default)     both background agents work: the usual case.
#   4                "Screenshot refresh" stops on a permission prompt: it is a background agent that
#                    needs you. The footer reads "▲ 1 waiting"; ↵ on the task's row opens it as a tab
#                    marked "background agent · hides again once it works on"; when it works on (answer
#                    the prompt, or say No) the tab hides again.
#   5                the "Release notes" tab is closed, which ends the main conversation while both
#                    background agents keep working. The row reads "2 working" and ↵ on it asks
#                    "conversation closed / Resume it in a new tab?" before it resumes the conversation.
#
# What to look at (⌘⇧A):
#
#   NOW              one row per live conversation in a tab: "Rate limiter" ("2 working": its two
#                    Checkout panes are the same task), "Release notes" (its status as the detail, the two
#                    background agents counted as "2 working" and never rows of their own), "Docs sweep",
#                    the ◆ "needs you" row with its question in quotes, and "Fix the typo" as "done". Each
#                    working row says what the tool call does in words ("Editing TokenBucket.kt",
#                    "Reading CHANGELOG.md"); "Docs sweep" runs a shell command and shows the call's
#                    description, long enough that the row cuts it.
#   EARLIER          "Nightly sync" and "Billing cleanup" (task files whose members are in no open tab).
#   Footer           under the Claude usage display: "▲ N waiting" and the spinner with the working count.
#   Close "Docs"     close the tab in the app: its panes close and their conversations end, so they leave NOW
#                    at once and are in EARLIER as "finished".

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
# The task's panel (⌘I in the "Release notes" tab): what a writing agent would put there. Four
# blocks show: the agents (the server's own), the progress, the links and the recent activity. The
# links are several kinds (a Slack thread, pull requests, a Claude artifact, documents, a brief and
# the decisions, each under a block titled for its kind) and more than five, so the panel says
# "+ N more". One activity line wraps to three lines at the narrowest width and is followed by a
# one-line entry.
call set_task_panel "$(python3 -I -c 'import json,sys
import time
now = int(time.time() * 1000)
print(json.dumps({"taskId": sys.argv[1], "by": "release notes agent", "blocks": [
  {"id": "progress", "type": "progress", "title": "Progress", "done": 3, "total": 5, "label": "Matching the changelog entries to merged pull requests"},
  {"id": "links", "type": "links", "title": "Links", "items": [
    {"label": "Release 4.3 notes, first draft", "url": "https://example.com/notes/4.3"},
    {"label": "Release thread", "url": "https://example.slack.com/archives/C0RELEASE/p1730800000"},
    {"label": "Changelog check", "url": "https://github.com/example/lunamux/pull/412"},
    {"label": "Screenshot refresh", "url": "https://github.com/example/lunamux/pull/415"},
    {"label": "Notes preview", "url": "https://claude.ai/artifact/seed-release-notes"},
    {"label": "Upgrade guide", "url": "https://example.com/upgrade"},
    {"label": "Screenshot list", "url": "file:///tmp/release-4.3/screenshots.md"},
    {"label": "Known issues", "url": "https://github.com/example/lunamux/issues/398"}]},
  {"id": "brief", "type": "links", "title": "Brief", "items": [
    {"label": "Brief: release notes for 4.3", "url": "https://example.com/briefs/release-4.3"}]},
  {"id": "decisions", "type": "links", "title": "Decisions", "items": [
    {"label": "You decided: notes stay one page", "url": "https://example.com/decisions/one-page"},
    {"label": "You decided: screenshots are retaken", "url": "https://example.com/decisions/screenshots"}]},
  {"id": "activity", "type": "timeline", "title": "Recent activity", "items": [
    {"at": now - 120000, "text": "Changelog check found two gaps: the entry for the sidebar fix has no merged pull request, and the one for the search change points at a pull request that was closed without merging"},
    {"at": now - 300000, "text": "Draft sent for review"},
    {"at": now - 720000, "text": "Release thread opened for sign-off"},
    {"at": now - 1200000, "text": "Screenshot refresh started"},
    {"at": now - 2400000, "text": "Draft written"},
    {"at": now - 4200000, "text": "Upgrade guide linked"},
    {"at": now - 6000000, "text": "Notes outline agreed"},
    {"at": now - 7200000, "text": "Branch cut"}]},
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
