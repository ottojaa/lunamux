/**
 * Reads the end of a Claude Code transcript to learn one thing the hooks never say: that the
 * person interrupted the turn.
 *
 * Claude Code sends no hook when a permission prompt is refused (No, or Esc) and none when a turn is
 * interrupted while it writes: there is no `Stop`, no `PostToolUse` and no failure event. What it
 * does write, as the last conversation line of the transcript, is a user message that reads
 * `[Request interrupted by user]` (or `... for tool use`, after a refused tool call). That line is
 * the evidence [AgentSessionTracker] settles such a record from.
 *
 * This file contains [TranscriptTail].
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

/**
 * Looks at the last conversation line of a transcript file.
 *
 * Called by [AgentSessionTracker.onPoll], outside its lock, for every open hook-fed record that
 * is working or asks the person something. Stateless; safe to call from any thread.
 */
object TranscriptTail {
    /**
     * How many bytes from the end of the file are looked at. The lines after the last
     * conversation line (cost and snapshot bookkeeping) are small, and an interrupt marker is a
     * line of its own, so a window this size holds it however large the tool output before it was.
     */
    private const val WINDOW_BYTES = 128 * 1024L

    /** The start of the text Claude Code writes for an interrupted turn. */
    private const val INTERRUPT_MARK = "[Request interrupted by user"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * When the person interrupted the turn, if that is the last thing the transcript says.
     *
     * Only the last `user` or `assistant` line counts, so a marker that a later prompt or reply
     * has followed is history and returns null. A tail whose last conversation line cannot be
     * read (it is larger than the window, or half written) returns null too: no evidence is
     * never taken as an interrupt.
     */
    fun interruptedAt(path: String): Long? {
        val file = File(path)
        if (!file.isFile) return null
        val text = try {
            RandomAccessFile(file, "r").use { f ->
                val start = maxOf(0L, f.length() - WINDOW_BYTES)
                f.seek(start)
                val bytes = ByteArray((f.length() - start).toInt())
                f.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            return null
        }
        for (line in text.lineSequence().toList().asReversed()) {
            if (line.isBlank()) continue
            val obj = try {
                json.parseToJsonElement(line) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return null
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
            if (type != "user" && type != "assistant") continue
            if (type != "user" || !isInterrupt(obj)) return null
            val stamp = (obj["timestamp"] as? JsonPrimitive)?.contentOrNull ?: return null
            return try {
                Instant.parse(stamp).toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    /** Whether a `user` line is the interrupt marker: its content is, or starts with, the marker text. */
    private fun isInterrupt(line: JsonObject): Boolean {
        val content = (line["message"] as? JsonObject)?.get("content")
        return AgentTranscriptText.textOf(content).startsWith(INTERRUPT_MARK)
    }
}
