/**
 * The one reader of Claude Code transcript files (`~/.claude/projects/<folder>/<id>.jsonl`).
 *
 * This file contains [TranscriptLines] (the single classifier that says what a line of the file is,
 * and the whole-file readers that build on it: the search text and facts of a conversation, and its
 * story of turns), [TranscriptFollower] (the incremental reader the tracker keeps per open
 * conversation, for the recap and the person's interrupt), and the two value types the readers
 * return. Every reader classifies with [TranscriptLines.classify], so "what is a prompt" is
 * decided in one place.
 *
 * @see AgentSessionTracker
 * @see AgentSearchIndexer
 * @see ClaudeHistoryImport
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Instant

/** What one line of a transcript is. */
internal enum class LineKind {
    /** The person asked something. */
    PROMPT,

    /** The agent said something. */
    REPLY,

    /** The person interrupted the turn. */
    INTERRUPT,

    /** Any other user or assistant line (a tool result, a tool call): it shows the turn went on. */
    ACTIVITY,

    /** Claude Code's away summary. */
    RECAP,

    /** The conversation's generated title. */
    TITLE,

    /** Anything else, or a line to skip (a helper's, a meta line, the compaction summary). */
    OTHER,
}

/**
 * One classified transcript line.
 *
 * @property kind what it is.
 * @property text its words: the prompt, the reply, the recap (already cut to its sentences) or the title.
 * @property at when it was written, in epoch milliseconds, when the line is stamped.
 * @property obj the parsed line, for the facts a reader takes from it (`cwd`, `sessionId`, ...).
 */
internal data class Line(val kind: LineKind, val text: String, val at: Long?, val obj: JsonObject)

/**
 * What a whole-file read of a conversation yields.
 *
 * @property text the words of the conversation, capped to its last [TranscriptLines.MAX_CHARS] characters.
 * @property sessionId Claude's conversation id.
 * @property cwd the folder it ran in.
 * @property gitBranch the branch checked out.
 * @property entrypoint how it was started (`cli`, `sdk-cli`, ...).
 * @property title Claude's generated title.
 * @property firstPrompt the first thing the person asked.
 * @property startedAt the first line's time.
 * @property endedAt the last line's time.
 */
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
 * A conversation told as turns.
 *
 * @property turns the turns, newest first.
 * @property cwd the folder it ran in.
 * @property startedAt the first line's time.
 */
data class TranscriptStory(val turns: List<AgentTurn> = emptyList(), val cwd: String? = null, val startedAt: Long? = null)

/** The classifier and the whole-file readers. */
object TranscriptLines {
    /** The most characters of a conversation kept as search text: its end. */
    const val MAX_CHARS = 256 * 1024

    private const val PROMPT_MAX = 500
    private const val TURNS_MAX = 30
    private const val TURN_PROMPT_MAX = 200
    private const val INTERRUPT_MARK = "[Request interrupted by user"
    private const val RECAP_SUBTYPE = "away_summary"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Say what [raw], one line of a transcript, is. Lines of helper agents (sidechains), meta lines
     * and the summary written when Claude Code compacts are [LineKind.OTHER]: the person did not
     * write them. A user line that starts with `<` is a command wrapper, not a prompt.
     *
     * @param raw the line as it stands in the file.
     * @return the classified line, or null when it is not a JSON object.
     */
    internal fun classify(raw: String): Line? {
        if (raw.isBlank() || raw[0] != '{') return null
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val at = obj.str("timestamp")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        fun line(kind: LineKind, text: String = "") = Line(kind, text, at, obj)
        when (obj.str("type")) {
            "ai-title" -> return obj.str("aiTitle")?.takeIf { it.isNotBlank() }?.let { line(LineKind.TITLE, it) } ?: line(LineKind.OTHER)
            "system" -> {
                if (obj.str("subtype") != RECAP_SUBTYPE) return line(LineKind.OTHER)
                val recap = AgentSessionText.recapOf(obj.str("content"))
                return if (recap == null) line(LineKind.OTHER) else line(LineKind.RECAP, recap)
            }
            "user", "assistant" -> Unit
            else -> return line(LineKind.OTHER)
        }
        if (obj.flag("isSidechain") || obj.flag("isMeta") || obj.flag("isCompactSummary")) return line(LineKind.OTHER)
        val said = textOf((obj["message"] as? JsonObject)?.get("content")).trim()
        return if (obj.str("type") == "user") when {
            said.startsWith(INTERRUPT_MARK) -> line(LineKind.INTERRUPT, said)
            said.isNotEmpty() && !said.startsWith("<") -> line(LineKind.PROMPT, said)
            else -> line(LineKind.ACTIVITY)
        } else if (said.isNotEmpty()) line(LineKind.REPLY, said) else line(LineKind.ACTIVITY)
    }

    /**
     * The text of a message's `content`, which is a string or a list of parts of which the `text`
     * ones count.
     *
     * @param content the `content` element.
     * @return the words, parts joined by newlines; empty when there are none.
     */
    internal fun textOf(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> if (content.isString) content.content else ""
        is JsonArray -> content.mapNotNull { part ->
            val o = part as? JsonObject
            if (o?.str("type") == "text") o.str("text") else null
        }.joinToString("\n")
        else -> ""
    }

    /**
     * Read a whole transcript for search and import: the words, the ids, the folder, the branch and
     * the times.
     *
     * Called by [AgentSearchIndexer] (text for search) and [ClaudeHistoryImport] (facts of a
     * conversation that never ran in Lunamux).
     *
     * @param file the transcript.
     * @param onlyEntrypoint when set, stop at once if the transcript was started another way.
     * @return what was read, or null when the file is missing or unreadable.
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
                for (raw in lines) {
                    val line = classify(raw) ?: continue
                    val obj = line.obj
                    sessionId = sessionId ?: obj.str("sessionId")
                    cwd = cwd ?: obj.str("cwd")
                    branch = branch ?: obj.str("gitBranch")
                    entrypoint = entrypoint ?: obj.str("entrypoint")
                    if (onlyEntrypoint != null && entrypoint != null && entrypoint != onlyEntrypoint) break
                    line.at?.let { t ->
                        if (first == null) first = t
                        last = t
                    }
                    when (line.kind) {
                        LineKind.TITLE -> title = line.text
                        LineKind.PROMPT, LineKind.REPLY, LineKind.INTERRUPT -> {
                            if (line.kind == LineKind.PROMPT && firstPrompt == null) firstPrompt = line.text.take(PROMPT_MAX)
                            if (text.isNotEmpty()) text.append('\n')
                            text.append(line.text)
                            // Keep memory flat on a very long conversation: only the end is wanted.
                            if (text.length > MAX_CHARS * 2) text.delete(0, text.length - MAX_CHARS)
                        }
                        else -> Unit
                    }
                }
            }
        } catch (_: IOException) {
            return null
        }
        val kept = if (text.length > MAX_CHARS) text.substring(text.length - MAX_CHARS) else text.toString()
        return TranscriptInfo(kept, sessionId, cwd, branch, entrypoint, title, firstPrompt, first, last)
    }

    /**
     * Tell a conversation as turns: each prompt, when it came, how long the turn took and the first
     * line of its last answer. Called by the tracker for the story of one record.
     *
     * @param file the transcript.
     * @return the newest [TURNS_MAX] turns newest first, with the folder and first time; empty when unreadable.
     */
    fun story(file: File): TranscriptStory {
        if (!file.isFile) return TranscriptStory()
        val out = ArrayList<AgentTurn>()
        var at: Long? = null
        var prompt = ""
        var last: Long? = null
        var reply: String? = null
        var cwd: String? = null
        var began0: Long? = null
        fun close() {
            val began = at ?: return
            out += AgentTurn(began, prompt, last?.takeIf { it > began }?.let { it - began }, reply)
        }
        try {
            file.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = classify(raw) ?: continue
                    if (line.kind == LineKind.OTHER || line.kind == LineKind.TITLE || line.kind == LineKind.RECAP) continue
                    line.obj.str("cwd")?.takeIf { it.isNotBlank() }?.let { cwd = it }
                    val time = line.at ?: continue
                    if (began0 == null) began0 = time
                    if (line.kind == LineKind.PROMPT) {
                        close()
                        at = time
                        prompt = line.text.lineSequence().first { it.isNotBlank() }.trim().take(TURN_PROMPT_MAX)
                        last = null
                        reply = null
                    } else if (at != null) {
                        last = time
                        // The last text of the turn is its answer; what came before it is the agent thinking aloud.
                        if (line.kind == LineKind.REPLY) {
                            line.text.lineSequence().map { it.trim() }
                                .firstOrNull { it.isNotEmpty() && !AgentSessionText.isFenceLine(it) }
                                ?.let { reply = it.take(TURN_PROMPT_MAX) }
                        }
                    }
                }
            }
        } catch (_: IOException) {
            return TranscriptStory()
        }
        close()
        return TranscriptStory(out.takeLast(TURNS_MAX).asReversed(), cwd, began0)
    }
}

/**
 * Follows one open conversation's transcript as it grows, keeping the two things the tracker needs
 * from it on every poll: the latest away summary (the recap) and whether the person's interrupt is
 * the last thing in it.
 *
 * It reads only what is new: the first look takes the last 4 MB, later ones start where the last
 * ended, a line still being written waits for the next look, and a file that shrank is read afresh.
 * Created by [AgentSessionTracker] for each open Claude record that has a transcript path; a follower
 * is not thread safe and is used from the tracker's lock.
 *
 * @param path the transcript file.
 */
internal class TranscriptFollower(val path: String) {
    private var offset = 0L
    private var size = -1L
    private var written = -1L

    /** The latest away summary, in its sentences. */
    var recap: String? = null
        private set

    /** When the person interrupted, if that is the last conversation line in the file. */
    var interruptedAt: Long? = null
        private set

    /**
     * Read what is new in the file.
     *
     * @return true when [recap] or [interruptedAt] changed.
     */
    fun refresh(): Boolean {
        val file = File(path)
        if (!file.isFile) {
            val had = recap != null || interruptedAt != null
            recap = null
            interruptedAt = null
            offset = 0
            size = -1
            return had
        }
        val nowSize = file.length()
        val nowWritten = file.lastModified()
        if (nowSize == size && nowWritten == written) return false
        val fresh = size < 0 || nowSize < offset
        val from = if (fresh) maxOf(0L, nowSize - FIRST_READ_BYTES) else offset
        val bytes = try {
            RandomAccessFile(file, "r").use { f ->
                f.seek(from)
                ByteArray((nowSize - from).toInt()).also { f.readFully(it) }
            }
        } catch (_: Exception) {
            return false
        }
        val beforeRecap = recap
        val beforeInterrupt = interruptedAt
        if (fresh) {
            recap = null
            interruptedAt = null
        }
        // Only whole lines are read: a line still being written is read next time, from its start.
        val end = bytes.lastIndexOf('\n'.code.toByte()) + 1
        if (end > 0) {
            String(bytes, 0, end, Charsets.UTF_8).lineSequence().forEach { raw ->
                val line = TranscriptLines.classify(raw) ?: return@forEach
                when (line.kind) {
                    LineKind.RECAP -> recap = line.text
                    LineKind.INTERRUPT -> interruptedAt = line.at
                    LineKind.PROMPT, LineKind.REPLY, LineKind.ACTIVITY -> interruptedAt = null
                    else -> Unit
                }
            }
        }
        offset = from + end
        size = nowSize
        written = nowWritten
        return recap != beforeRecap || interruptedAt != beforeInterrupt
    }

    private companion object {
        const val FIRST_READ_BYTES = 4L * 1024 * 1024
    }
}

private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
