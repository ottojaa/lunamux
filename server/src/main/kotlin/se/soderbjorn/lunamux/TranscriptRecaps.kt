/**
 * Reads the recaps Claude Code writes into its transcripts, so the agents overview can say what a
 * conversation is and where it stands in the conversation's own words.
 *
 * When the person comes back to a conversation, Claude Code writes a `system` line with the subtype
 * `away_summary` into the transcript: one short paragraph on the goal, where the work stands and
 * what comes next. The newest such line, through [AgentSessionText.recapOf], becomes the row's
 * description. No model is called: the recap is already on disk.
 *
 * This file contains [TranscriptRecaps].
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * The newest recap of each transcript, read incrementally: a file is read again only when it has
 * grown, and then only from where the last read stopped.
 *
 * [AgentSessionTracker] calls [refresh] from its screen poll, outside its lock, for every record it
 * holds, and reads the result with [known] when it publishes.
 *
 * @see AgentSessionText.recapOf
 */
class TranscriptRecaps {
    /** Where a file was read to (just past its last whole line), its size and last write then, and the recap found so far. */
    private data class Read(val offset: Long, val size: Long, val written: Long, val recap: String?)

    private val reads = ConcurrentHashMap<String, Read>()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Read what [path] has gained since the last call and keep its newest recap.
     *
     * The first read looks only at the last [FIRST_READ_BYTES] of the file: a recap further back
     * than that is about work long since moved on. A file that shrank (rewritten) is read afresh.
     *
     * @param path a transcript file.
     * @return whether the recap kept for [path] changed.
     */
    fun refresh(path: String): Boolean {
        val file = File(path)
        val before = reads[path]
        if (!file.isFile) return reads.remove(path)?.recap != null
        val size = file.length()
        val written = file.lastModified()
        if (before != null && before.size == size && before.written == written) return false
        val fresh = before == null || size < before.offset
        val from = if (fresh) maxOf(0L, size - FIRST_READ_BYTES) else before!!.offset
        val bytes = try {
            RandomAccessFile(file, "r").use { f ->
                f.seek(from)
                ByteArray((size - from).toInt()).also { f.readFully(it) }
            }
        } catch (_: Exception) {
            return false
        }
        // Only whole lines are read: a line still being written is read next time, from its start.
        val end = bytes.lastIndexOf(NEWLINE) + 1
        var recap = if (fresh) null else before!!.recap
        if (end > 0) {
            String(bytes, 0, end, Charsets.UTF_8).lineSequence()
                .filter { it.contains(MARK) }
                .forEach { line -> recapIn(line)?.let { recap = it } }
        }
        reads[path] = Read(from + end, size, written, recap)
        return recap != before?.recap
    }

    /** The recap last read for [path], without touching the file; null when it has none. */
    fun known(path: String?): String? = path?.let { reads[it]?.recap }

    /** Forget every file not in [paths], so records that left memory do not keep their reads. */
    fun keepOnly(paths: Set<String>) {
        reads.keys.retainAll(paths)
    }

    /** The description in one transcript line, when it is a recap that leaves something to say. */
    private fun recapIn(line: String): String? {
        val obj = try {
            json.parseToJsonElement(line) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        if ((obj["subtype"] as? JsonPrimitive)?.contentOrNull != SUBTYPE) return null
        return AgentSessionText.recapOf((obj["content"] as? JsonPrimitive)?.contentOrNull)
    }

    private companion object {
        /** How far back the first read of a file looks. */
        const val FIRST_READ_BYTES = 4L * 1024 * 1024

        /** The subtype of the `system` line Claude Code writes a recap as. */
        const val SUBTYPE = "away_summary"

        /** What a line has to contain before it is parsed at all. */
        const val MARK = "\"away_summary\""

        const val NEWLINE = '\n'.code.toByte()
    }
}
