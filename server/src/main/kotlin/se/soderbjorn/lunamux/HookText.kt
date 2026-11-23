/**
 * Small readers the agent tracker shares: the words for a Claude Code tool call, the process-tree
 * and screen-footer reads that find background shells, and the tidying of titles and screens.
 *
 * Everything here is a plain function of its arguments (the process reads aside), so the tracker's
 * pure core in `AgentTurn.kt` and its tests can use them without a tracker.
 *
 * @see AgentSessionTracker
 * @see step
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val TOOL_LINE_MAX = 160
private const val SCREEN_LINES = 200
private const val MAX_PROCESS_DEPTH = 32

/** The tool Claude Code uses to put a question to the person. */
private const val ASK_TOOL = "AskUserQuestion"

/** Marks a raw tool line that holds a question put to the person rather than a command. */
internal const val ASK_PREFIX = "Asks: "

/**
 * The part of the command line Claude Code gives every shell it starts for its Bash tool (it
 * sources a snapshot of the person's shell first), which tells such a shell from anything else the
 * `claude` process runs.
 */
internal const val CLAUDE_SHELL_MARK = "/shell-snapshots/snapshot-"

/** Claude Code's footer count of background shells: "· 1 shell ·", "2 shells", "1 background shell". */
private val FOOTER_SHELLS = Regex("""(?<![\w-])(\d+) (?:background )?shells?\b""")

/** How many of the screen's last non-blank lines count as its footer. */
private const val FOOTER_LINES = 2

/** The string value at [key], or null when absent or not a string. */
internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/**
 * Whether process [pid] runs underneath process [ancestor]: one of its parents, however far up, is [ancestor].
 *
 * @param pid the process to start from.
 * @param ancestor the process to find above it.
 * @return true when found within a bounded climb.
 */
internal fun processIsUnder(pid: Long, ancestor: Long): Boolean {
    var at = ProcessHandle.of(pid).flatMap { it.parent() }
    repeat(MAX_PROCESS_DEPTH) {
        val handle = at.orElse(null) ?: return false
        if (handle.pid() == ancestor) return true
        at = handle.parent()
    }
    return false
}

/**
 * The shells Claude Code started that still run directly under the `claude` process [pid]. Asked for
 * a conversation that sits at its prompt, where no shell of a turn is left, so every one found was
 * started in the background.
 *
 * @param pid the process id of `claude`, as its hooks report it.
 * @return how many there are and when the oldest began; none when the process is gone.
 */
internal fun backgroundShellsOf(pid: Long): BackgroundShells {
    val found = ProcessHandle.of(pid).map { claude ->
        claude.children().filter { it.info().commandLine().orElse("").contains(CLAUDE_SHELL_MARK) }.toList()
    }.orElse(emptyList())
    if (found.isEmpty()) return BackgroundShells.NONE
    return BackgroundShells(found.size, found.mapNotNull { it.info().startInstant().orElse(null)?.toEpochMilli() }.minOrNull())
}

/**
 * How many background shells Claude Code's footer counts, read from the last lines of [screen].
 * Used for a conversation whose process is not known.
 *
 * @param screen the pane's screen text, or null.
 * @return the count, 0 when the footer names none.
 */
internal fun footerShellsOf(screen: String?): Int {
    val footer = screen?.lines()?.map { it.trimEnd() }?.filter { it.isNotEmpty() }?.takeLast(FOOTER_LINES) ?: return 0
    return footer.firstNotNullOfOrNull { line -> FOOTER_SHELLS.find(line)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
}

/**
 * The last 200 lines of [screen], trailing blank lines removed.
 *
 * @param screen the full screen text.
 * @return the tail kept with a closed record.
 */
internal fun lastLines(screen: String): String =
    screen.trimEnd().lines().takeLast(SCREEN_LINES).joinToString("\n")

/**
 * Tidy a program-set terminal title for display: strips the spinner or status glyph an agent
 * prefixes and drops titles that only name the agent.
 *
 * @param raw the title the program set, or null.
 * @return the tidied title, or null when nothing useful is left.
 */
internal fun cleanTitle(raw: String?): String? {
    val t = raw?.trim()?.dropWhile { !it.isLetterOrDigit() }?.trim().orEmpty()
    if (t.isEmpty()) return null
    return if (t.lowercase() in setOf("claude", "claude code", "codex", "gemini", "gemini cli")) null else t
}

/**
 * One line saying in words what a tool call does, for the row of a working session; null when the
 * call says nothing specific.
 *
 * @param event the `PreToolUse` event.
 * @return the line, at most 160 characters.
 */
internal fun toolLine(event: JsonObject): String? {
    val tool = event.str("tool_name") ?: return null
    val input = event["tool_input"] as? JsonObject
    askedLine(tool, input)?.let { return it }
    return wordsFor(tool, input)?.take(TOOL_LINE_MAX)
}

/**
 * The tool call as Claude Code made it: the tool name and its main argument (the command, file
 * path, pattern, URL or query), which is what a permission prompt asks the person to allow.
 *
 * @param event a tool or permission event.
 * @return the line, or null when the event names no tool.
 */
internal fun rawToolLine(event: JsonObject): String? {
    val tool = event.str("tool_name") ?: return null
    val input = event["tool_input"] as? JsonObject
    askedLine(tool, input)?.let { return it }
    val arg = listOf("command", "file_path", "pattern", "path", "url", "query")
        .firstNotNullOfOrNull { input?.str(it) }
        ?.let(::firstLine)
    return (if (arg == null) tool else "$tool $arg").take(TOOL_LINE_MAX)
}

/**
 * The question an `AskUserQuestion` call puts to the person.
 *
 * @param event a tool or permission event.
 * @return the first question, "A question is waiting" when the call carries none, null for any other tool.
 */
internal fun askedQuestionOf(event: JsonObject): String? {
    if (event.str("tool_name") != ASK_TOOL) return null
    val asked = (((event["tool_input"] as? JsonObject)?.get("questions") as? JsonArray)?.firstOrNull() as? JsonObject)
        ?.str("question")
    return asked?.trim()?.takeIf { it.isNotEmpty() } ?: "A question is waiting"
}

private fun askedLine(tool: String, input: JsonObject?): String? {
    if (tool != ASK_TOOL) return null
    val asked = ((input?.get("questions") as? JsonArray)?.firstOrNull() as? JsonObject)?.str("question")
    return if (asked.isNullOrBlank()) null else (ASK_PREFIX + asked.trim()).take(TOOL_LINE_MAX)
}

private fun firstLine(text: String): String? =
    text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

private fun fileNameOf(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

/** The words for one tool call, before the length cut; null when it names nothing a person could use. */
private fun wordsFor(tool: String, input: JsonObject?): String? {
    fun arg(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { input?.str(it)?.let(::firstLine) }
    fun file(vararg keys: String): String? = arg(*keys)?.let(::fileNameOf)?.takeIf { it.isNotEmpty() }
    return when (tool) {
        "Bash" -> arg("description")
        "Edit", "MultiEdit" -> file("file_path")?.let { "Editing $it" }
        "NotebookEdit" -> file("notebook_path", "file_path")?.let { "Editing $it" }
        "Write" -> file("file_path")?.let { "Writing $it" }
        "Read" -> file("file_path")?.let { "Reading $it" }
        "Grep" -> arg("pattern")?.let { "Searching for \"$it\"" }
        "Glob" -> arg("pattern")?.let { "Finding files matching \"$it\"" }
        "LS" -> file("path")?.let { "Listing $it" }
        "WebSearch" -> arg("query")?.let { "Searching the web for \"$it\"" }
        "WebFetch" -> arg("url")?.let { "Fetching ${hostOf(it)}" }
        "Task", "Agent" -> arg("description")?.let { "Running an agent: $it" }
        "Skill" -> arg("skill", "name")?.let { "Using the $it skill" }
        else -> mcpWords(tool)
    }
}

private fun hostOf(url: String): String =
    url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfter('@').ifEmpty { url }

/** `<server>: <tool in words>` for an MCP tool named `mcp__<server>__<tool>`, else null. */
private fun mcpWords(tool: String): String? {
    if (!tool.startsWith("mcp__")) return null
    val rest = tool.removePrefix("mcp__")
    val server = rest.substringBefore("__").removePrefix("claude_ai_")
    val name = rest.substringAfter("__", "")
    if (server.isEmpty() || name.isEmpty()) return null
    return "${server.replace('_', ' ').trim()}: ${name.replace('_', ' ').trim()}"
}
