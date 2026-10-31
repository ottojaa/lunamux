/**
 * Connects Lunamux to Claude Code by adding its hook entries to the Claude
 * Code settings file, and removes them again.
 *
 * This file contains [ClaudeHooks]. Connecting adds one command hook per
 * event listed in [ClaudeHooks.EVENTS]; each is recognised later by the
 * [ClaudeHooks.MARKER] comment at the end of its command, so disconnecting
 * removes exactly those entries and nothing the user or another tool wrote.
 *
 * The hook command posts the event JSON to the Lunamux events endpoint using
 * the `LUNAMUX_*` variables every Lunamux pane exports, and does nothing
 * (exit 0, no output) when they are absent, so Claude Code runs outside
 * Lunamux are unaffected.
 *
 * @see AgentEventRoutes
 * @see AgentSessionTracker
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Reads and edits the `hooks` section of a Claude Code settings file.
 *
 * Every other key in the file is passed through untouched. The file is
 * parsed first and never written if it is not valid JSON; before a change the
 * old file is copied to `<name>.bak`, and the new content is written to a
 * temporary file and moved into place so a crash cannot leave a half-written
 * file.
 *
 * Created once in `main()`; the `/window` socket reads [connected] and calls
 * [connect] / [disconnect] for the App Settings row.
 *
 * @param file the settings file. Defaults to `~/.claude/settings.json`, or the
 *   path in the `lunamux.claudeSettings` system property when set (used to try
 *   the feature against a throwaway file).
 */
class ClaudeHooks(
    private val file: File = File(
        System.getProperty("lunamux.claudeSettings")
            ?: "${System.getProperty("user.home")}/.claude/settings.json",
    ),
) {
    private val _connected = MutableStateFlow(readConnected())

    /** Whether every event in [EVENTS] currently has a Lunamux hook in the file. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * Re-read the file and update [connected].
     *
     * Called when a client connects, since the file may have been edited by hand.
     *
     * @return the new value of [connected].
     */
    fun refresh(): Boolean = readConnected().also { _connected.value = it }

    /**
     * Add a Lunamux hook entry for each event in [EVENTS], replacing any
     * Lunamux entries already present so the call is idempotent.
     *
     * @return null on success, or a message for the user (invalid JSON, unwritable file).
     */
    fun connect(): String? = edit { root ->
        val hooks = (root["hooks"] as? JsonObject).orEmpty().withoutOurs()
        val updated = LinkedHashMap(hooks)
        for (event in EVENTS) {
            val existing = (updated[event] as? JsonArray).orEmpty()
            updated[event] = JsonArray(existing + groupFor(event))
        }
        JsonObject(LinkedHashMap(root).apply { put("hooks", JsonObject(updated)) })
    }

    /**
     * Remove every Lunamux hook entry, leaving other hooks and keys alone.
     * Event lists and the `hooks` object are dropped only if removing ours
     * left them empty.
     *
     * @return null on success, or a message for the user.
     */
    fun disconnect(): String? {
        if (!file.exists()) return null
        return edit { root ->
            val hooks = (root["hooks"] as? JsonObject) ?: return@edit root
            val rest = hooks.withoutOurs()
            val out = LinkedHashMap(root)
            if (rest.isEmpty()) out.remove("hooks") else out["hooks"] = rest
            JsonObject(out)
        }
    }

    /** Parse, transform, back up and atomically write the file; refresh [connected]. */
    private fun edit(transform: (JsonObject) -> JsonObject): String? {
        val root = if (file.exists()) {
            val text = file.readText()
            if (text.isBlank()) JsonObject(emptyMap()) else {
                val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
                parsed as? JsonObject
                    ?: return "${file.name} is not valid JSON; fix it by hand first"
            }
        } else JsonObject(emptyMap())
        val next = transform(root)
        return runCatching {
            file.absoluteFile.parentFile?.mkdirs()
            if (file.exists()) Files.copy(file.toPath(), File(file.path + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING)
            val tmp = File(file.path + ".lunamux-tmp")
            tmp.writeText(PRETTY.encodeToString(JsonElement.serializer(), next) + "\n")
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            _connected.value = readConnected()
            null
        }.getOrElse { "Could not write ${file.path}: ${it.message}" }
    }

    private fun readConnected(): Boolean {
        val hooks = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }
            .getOrNull()?.get("hooks") as? JsonObject ?: return false
        return EVENTS.all { event -> (hooks[event] as? JsonArray).orEmpty().any(::isOurs) }
    }

    companion object {
        /** The Claude Code hook events Lunamux listens to. */
        val EVENTS = listOf(
            "SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse", "Notification", "Stop", "SessionEnd",
        )

        /** Trailing shell comment that identifies a hook command as Lunamux's. */
        const val MARKER = "# lunamux-agent-events"

        /**
         * The shell command each hook runs: post stdin to the events URL when the
         * pane's `LUNAMUX_*` variables are set, otherwise do nothing; always exit 0
         * so a failing post never blocks Claude Code.
         */
        val COMMAND = "[ -n \"\$LUNAMUX_EVENTS_URL\" ] && curl -sk -m 2 -X POST " +
            "-H \"$EVENTS_TOKEN_HEADER: \$LUNAMUX_EVENTS_TOKEN\" " +
            "-H \"$EVENTS_SESSION_HEADER: \$LUNAMUX_SESSION_ID\" " +
            "--data-binary @- \"\$LUNAMUX_EVENTS_URL\" >/dev/null 2>&1; exit 0 $MARKER"

        private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }

        private val TOOL_EVENTS = setOf("PreToolUse", "PostToolUse")

        private fun groupFor(event: String): JsonObject = buildJsonObject {
            if (event in TOOL_EVENTS) put("matcher", "*")
            putJsonArray("hooks") {
                add(buildJsonObject {
                    put("type", "command")
                    put("command", COMMAND)
                })
            }
        }

        /** Whether [group] (one element of an event's list) is a Lunamux entry. */
        private fun isOurs(group: JsonElement): Boolean =
            ((group as? JsonObject)?.get("hooks") as? JsonArray).orEmpty().any {
                ((it as? JsonObject)?.get("command") as? JsonPrimitive)?.contentOrNull?.contains(MARKER) == true
            }

        /**
         * A copy of this `hooks` object with every Lunamux group removed. A group that
         * mixes Lunamux and foreign commands keeps the foreign ones.
         */
        private fun JsonObject.withoutOurs(): JsonObject {
            val out = LinkedHashMap<String, JsonElement>()
            for ((event, value) in this) {
                val groups = value as? JsonArray
                if (groups == null) { out[event] = value; continue }
                val kept = groups.mapNotNull { group ->
                    val inner = (group as? JsonObject)?.get("hooks") as? JsonArray ?: return@mapNotNull group
                    val foreign = inner.filterNot { h ->
                        ((h as? JsonObject)?.get("command") as? JsonPrimitive)?.contentOrNull?.contains(MARKER) == true
                    }
                    when {
                        foreign.size == inner.size -> group
                        foreign.isEmpty() -> null
                        else -> JsonObject(LinkedHashMap((group as JsonObject)).apply { put("hooks", JsonArray(foreign)) })
                    }
                }
                if (kept.isNotEmpty() || groups.isEmpty()) out[event] = JsonArray(kept)
            }
            return JsonObject(out)
        }

        private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
        private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
    }
}
