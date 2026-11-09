/**
 * Reads a Claude Code transcript (`~/.claude/projects/<folder>/<session>.jsonl`)
 * and pulls out what the agents overview needs: the words of the conversation,
 * for search, and the few facts that describe the session (folder, branch,
 * title, first prompt, start and end time).
 *
 * A transcript is mostly tool output; only the text the user typed and the text
 * the assistant wrote are kept, so a 700 KB transcript yields a few KB of
 * searchable text.
 *
 * This file contains [AgentTranscriptText] and its result type [TranscriptInfo].
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.time.Instant

/** What one transcript file says about its session. */
data class TranscriptInfo(
    val text: String,
    val sessionId: String?,
    val cwd: String?,
    val gitBranch: String?,
    val entrypoint: String?,
    val title: String?,
    val firstPrompt: String?,
    val startedAt: Long?,
    val endedAt: Long?,
)

/**
 * Extracts searchable text and session facts from Claude Code transcripts.
 *
 * Called by [AgentSearchIndexer] when a session ends and for the one-off backfill,
 * and by [ClaudeHistoryImport] for transcripts written before Lunamux recorded
 * sessions. Stateless; safe to call from any thread.
 */
object AgentTranscriptText {
    /** The most text kept per transcript; when there is more, the end is kept. */
    const val MAX_CHARS = 256 * 1024

    private const val PROMPT_MAX = 500

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The conversation text of one transcript.
     *
     * Called when a Claude record ends, to fill its `search_text`.
     */
    fun extract(path: String): String? = read(File(path))?.text

    /**
     * Read a whole transcript in one pass.
     *
     * Lines that are not JSON objects, side-chain lines (a sub-agent's own conversation) and
     * meta lines are skipped. From `user` and `assistant` lines only plain text parts are kept:
     * tool calls, tool results and thinking are dropped.
     */
    fun read(file: File, onlyEntrypoint: String? = null): TranscriptInfo? {
        if (!file.isFile) return null
        val text = StringBuilder()
        var sessionId: String? = null
        var cwd: String? = null
        var branch: String? = null
        var entrypoint: String? = null
        var title: String? = null
        var firstPrompt: String? = null
        var first: Long? = null
        var last: Long? = null
        try {
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val obj = parse(line) ?: continue
                    sessionId = sessionId ?: obj.str("sessionId")
                    cwd = cwd ?: obj.str("cwd")
                    branch = branch ?: obj.str("gitBranch")
                    entrypoint = entrypoint ?: obj.str("entrypoint")
                    if (onlyEntrypoint != null && entrypoint != null && entrypoint != onlyEntrypoint) break
                    obj.str("timestamp")?.let(::epoch)?.let { t ->
                        if (first == null) first = t
                        last = t
                    }
                    val type = obj.str("type")
                    if (type == "ai-title") {
                        obj.str("aiTitle")?.takeIf { it.isNotBlank() }?.let { title = it }
                        continue
                    }
                    if (type != "user" && type != "assistant") continue
                    if ((obj["isSidechain"] as? JsonPrimitive)?.booleanOrNull == true) continue
                    if ((obj["isMeta"] as? JsonPrimitive)?.booleanOrNull == true) continue
                    val said = textOf((obj["message"] as? JsonObject)?.get("content"))
                    if (said.isBlank()) continue
                    if (type == "user" && firstPrompt == null && !said.trimStart().startsWith("<")) {
                        firstPrompt = said.trim().take(PROMPT_MAX)
                    }
                    if (text.isNotEmpty()) text.append('\n')
                    text.append(said)
                    // Keep memory flat on a very long conversation: only the end is wanted.
                    if (text.length > MAX_CHARS * 2) text.delete(0, text.length - MAX_CHARS)
                }
            }
        } catch (_: java.io.IOException) {
            return null
        }
        val kept = if (text.length > MAX_CHARS) text.substring(text.length - MAX_CHARS) else text.toString()
        return TranscriptInfo(kept, sessionId, cwd, branch, entrypoint, title, firstPrompt, first, last)
    }

    private fun parse(line: String): JsonObject? {
        if (line.isBlank() || line[0] != '{') return null
        return runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
    }

    /**
     * The text parts of a message's `content`, which is a string or a list of typed parts.
     * Also read by [TranscriptTail] to recognise the interrupt marker.
     */
    internal fun textOf(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> if (content.isString) content.content else ""
        is JsonArray -> content.mapNotNull { part ->
            val o = part as? JsonObject
            if (o?.str("type") == "text") o.str("text") else null
        }.joinToString("\n")
        else -> ""
    }

    private fun epoch(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
