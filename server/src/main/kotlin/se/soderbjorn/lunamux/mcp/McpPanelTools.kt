/**
 * MCP tools for the panel beside a task's own session.
 *
 * An agent that works on a task can show the person what it is doing in blocks next to the task's
 * own session. The panel draws four things: the task's agents (it makes that block itself), a
 * progress block, links and a timeline. `set_task_panel` replaces
 * all of them, `upsert_task_block` and `remove_task_block` change one, and `get_task_panel` reads
 * them back. This file turns an agent's JSON into [TaskPanelBlock]s and the stored blocks back
 * into JSON; the rules about sizes and shapes live in [se.soderbjorn.lunamux.TaskPanelLimits] and
 * are applied by [TaskPanelStore], and its refusals reach the agent as tool errors.
 */
package se.soderbjorn.lunamux.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.AgentSessionTracker
import se.soderbjorn.lunamux.TaskBlockContent
import se.soderbjorn.lunamux.TaskException
import se.soderbjorn.lunamux.TaskLink
import se.soderbjorn.lunamux.TaskPanelBlock
import se.soderbjorn.lunamux.TaskPanelLimits
import se.soderbjorn.lunamux.TaskPanelStore
import se.soderbjorn.lunamux.TaskTimelineItem
import java.time.Instant
import java.time.OffsetDateTime

/** The block types an agent may write, in the order the tool descriptions list them. */
private val BLOCK_TYPES = listOf("links", "timeline", "progress")

/** Read a JSON value as the array of objects a block field expects. */
private fun JsonObject.objects(field: String, blockId: String): List<JsonObject> {
    val array = this[field] as? JsonArray ?: throw McpArgumentException("Block '$blockId': '$field' must be an array.")
    return array.map { it as? JsonObject ?: throw McpArgumentException("Block '$blockId': every entry of '$field' must be an object.") }
}

/** Read a string field that must be present, keeping empty text. */
private fun JsonObject.str(field: String, where: String): String =
    (this[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw McpArgumentException("$where needs a text '$field'.")

/** Turn the `at` time of a timeline entry or a link into epoch milliseconds. */
private fun parseAt(el: JsonElement?, where: String): Long {
    val p = el as? JsonPrimitive ?: throw McpArgumentException("$where needs an 'at' time (ISO-8601 text or epoch milliseconds).")
    p.contentOrNull?.toLongOrNull()?.let { return it }
    val text = p.contentOrNull ?: throw McpArgumentException("$where needs an 'at' time (ISO-8601 text or epoch milliseconds).")
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        ?: throw McpArgumentException("$where has an 'at' of '${text.take(40)}' that is not ISO-8601 (like 2026-05-01T12:00:00Z) or epoch milliseconds.")
}

/** Read the optional `kind` of a link: the short word shown before its label. */
private fun JsonObject.linkKind(where: String): String {
    val el = this["kind"]?.takeUnless { it is JsonNull } ?: return TaskLink.DEFAULT_KIND
    val p = el as? JsonPrimitive
    if (p == null || !p.isString) throw McpArgumentException("$where has a 'kind' that is not text.")
    return p.content.trim().ifEmpty { TaskLink.DEFAULT_KIND }
}

/** Read one block the way an agent writes it. */
internal fun parsePanelBlock(json: JsonObject): TaskPanelBlock {
    val id = (json["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw McpArgumentException("Every block needs an 'id'.")
    val type = (json["type"] as? JsonPrimitive)?.contentOrNull
        ?: throw McpArgumentException("Block '$id' needs a 'type': ${BLOCK_TYPES.joinToString(", ")}.")
    if (type == TaskPanelLimits.RESERVED_ID) {
        throw McpArgumentException("Block type 'agents' is reserved: the panel draws the task's sessions itself. Use ${BLOCK_TYPES.joinToString(", ")}.")
    }
    val title = (json["title"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    val content: TaskBlockContent = when (type) {
        "links" -> TaskBlockContent.Links(json.objects("items", id).mapIndexed { i, o ->
            val where = "Block '$id' link ${i + 1}"
            TaskLink(
                kind = o.linkKind(where),
                label = o.str("label", where),
                url = o.str("url", where),
                at = o["at"]?.takeUnless { it is JsonNull }?.let { parseAt(it, where) },
            )
        })
        "timeline" -> TaskBlockContent.Timeline(json.objects("items", id).mapIndexed { i, o ->
            TaskTimelineItem(parseAt(o["at"], "Block '$id' timeline entry ${i + 1}"), o.str("text", "Block '$id' timeline entry ${i + 1}"))
        })
        "progress" -> TaskBlockContent.Progress(
            done = json.numberField("done", id),
            total = json.numberField("total", id),
            phase = (json["phase"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            label = (json["label"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
        )
        else -> throw McpArgumentException("Block '$id' has an unknown type '${type.take(40)}'. Use ${BLOCK_TYPES.joinToString(", ")}.")
    }
    return TaskPanelBlock(id = id, title = title, content = content, writtenAtMillis = 0L)
}

/** Read an optional whole-number field of a progress block. */
private fun JsonObject.numberField(field: String, blockId: String): Int? {
    val el = this[field] ?: return null
    if (el is JsonNull) return null
    return (el as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        ?: throw McpArgumentException("Block '$blockId': '$field' must be a whole number.")
}

/** Write a stored block the way an agent writes it, plus who wrote it and when. */
internal fun panelBlockJson(b: TaskPanelBlock): JsonObject = buildJsonObject {
    put("id", b.id)
    put("type", b.content.kindName)
    b.title?.let { put("title", it) }
    when (val c = b.content) {
        is TaskBlockContent.Links -> put("items", buildJsonArray {
            c.items.forEach { l ->
                add(buildJsonObject {
                    put("kind", l.kind)
                    put("label", l.label)
                    put("url", l.url)
                    l.at?.let { put("at", Instant.ofEpochMilli(it).toString()) }
                })
            }
        })
        is TaskBlockContent.Timeline -> put("items", buildJsonArray {
            c.items.forEach {
                add(buildJsonObject { put("at", Instant.ofEpochMilli(it.atMillis).toString()); put("text", it.text) })
            }
        })
        is TaskBlockContent.Progress -> {
            c.done?.let { put("done", it) }
            c.total?.let { put("total", it) }
            c.phase?.let { put("phase", it) }
            c.label?.let { put("label", it) }
        }
    }
    put("writtenBy", b.writtenBy?.let(::JsonPrimitive) ?: JsonNull)
    put("writtenAt", Instant.ofEpochMilli(b.writtenAtMillis).toString())
}

/**
 * Register `set_task_panel`, `upsert_task_block`, `remove_task_block` and `get_task_panel`.
 *
 * Called once at boot from [mcpRoutes] next to [registerMcpTaskTools], when the server has a task
 * index and a panel store. The three tools that change the panel need a `read+write` token;
 * `get_task_panel` needs only `read`.
 */
fun registerMcpPanelTools(store: TaskPanelStore, tracker: AgentSessionTracker) {
    val blockSchema = schemaObject(
        "id" to schemaString(
            "Your name for the block: 1-${TaskPanelLimits.ID_MAX} characters from letters, digits, '.', '_', ':' and '-'. " +
                "Other writers use the same panel, so start your ids with something of your own (like \"tests-status\"). " +
                "'agents' is reserved.",
        ),
        "type" to schemaString("What the block holds.", BLOCK_TYPES),
        "title" to schemaString("Heading above the block, up to ${TaskPanelLimits.TITLE_MAX} characters."),
        "items" to schemaArray(
            "type links: [{kind?, label, url, at?}] (up to ${TaskPanelLimits.LIST_MAX}; kind is the short word shown before the label, " +
                "up to ${TaskPanelLimits.KIND_MAX} characters, and \"link\" when you give none; at is when the linked thing was made or changed, " +
                "ISO-8601 or epoch milliseconds, and a link without one is listed after the others; only http, https and file addresses). " +
                "type timeline: [{at, text}] where at is ISO-8601 or epoch milliseconds (text ${TaskPanelLimits.TIMELINE_TEXT_MAX} characters; " +
                "past ${TaskPanelLimits.TIMELINE_MAX} entries the newest are kept).",
            buildJsonObject { put("type", "object") },
        ),
        "done" to schemaInt("type progress: how many parts are finished (with total)."),
        "total" to schemaInt("type progress: how many parts there are (with done)."),
        "phase" to schemaString("type progress: the stage the work is in, instead of done and total."),
        "label" to schemaString("type progress: a short caption."),
        required = listOf("id", "type"),
    )
    val bySchema = schemaString(
        "Who is writing, shown next to the block (up to ${TaskPanelLimits.WRITER_MAX} characters). " +
            "Defaults to the title of your session.",
    )

    /** The name to show as the writer: the one given, else the caller's session title. */
    fun writerOf(ctx: McpCallContext, args: JsonObject): String? {
        args.optString("by")?.let {
            if (it.length > TaskPanelLimits.WRITER_MAX) {
                throw McpArgumentException("'by' is ${it.length} characters, the limit is ${TaskPanelLimits.WRITER_MAX}.")
            }
            return it.trim().ifEmpty { null }
        }
        val caller = ctx.callerSessionId ?: return null
        val title = tracker.openRecordOf(caller)?.title?.takeIf { it.isNotBlank() }
            ?: placementOf(caller).title?.takeIf { it.isNotBlank() }
        return title?.trim()?.take(TaskPanelLimits.WRITER_MAX)
    }

    /** Run a store call, turning its [TaskException] into a tool error. */
    fun <T> panelCall(block: () -> T): T = taskCall(block)

    writeTool(
        name = "set_task_panel",
        description = "Show the person what is going on in a task, in blocks beside the task's own session: " +
            "links, a timeline and progress (the panel also lists the task's agents itself). " +
            "This replaces every block of the task at once, in " +
            "the order you give (at most ${TaskPanelLimits.MAX_BLOCKS}). To change one block without touching the " +
            "rest use upsert_task_block. Call it when the picture changes, not on every step. Only you and other " +
            "agents write here; the person's list of the task's sessions is drawn by the panel itself.",
        inputSchema = schemaObject(
            "taskId" to schemaString("The task (from list_tasks)."),
            "blocks" to schemaArray("The blocks, in the order they are shown.", blockSchema),
            "by" to bySchema,
            required = listOf("taskId", "blocks"),
        ),
    ) { ctx, args ->
        val taskId = args.requireString("taskId")
        val raw = args["blocks"] as? JsonArray ?: throw McpArgumentException("'blocks' must be an array of blocks.")
        val blocks = raw.map { parsePanelBlock(it as? JsonObject ?: throw McpArgumentException("Every block must be an object.")) }
        val stored = panelCall { store.setPanel(taskId, blocks, writerOf(ctx, args)) }
        McpToolResult.json(buildJsonObject {
            put("taskId", taskId)
            put("blocks", stored.size)
        })
    }

    writeTool(
        name = "upsert_task_block",
        description = "Add one block to a task's panel, or replace the block that has the same id where it stands. " +
            "The other blocks are not touched. Use it to keep one block (a status, a progress bar) up to date. " +
            "Refused when the panel already has ${TaskPanelLimits.MAX_BLOCKS} blocks and the id is new.",
        inputSchema = schemaObject(
            "taskId" to schemaString("The task (from list_tasks)."),
            "block" to blockSchema,
            "by" to bySchema,
            required = listOf("taskId", "block"),
        ),
    ) { ctx, args ->
        val taskId = args.requireString("taskId")
        val block = parsePanelBlock(args["block"] as? JsonObject ?: throw McpArgumentException("'block' must be a block object."))
        val stored = panelCall { store.upsert(taskId, block, writerOf(ctx, args)) }
        McpToolResult.json(buildJsonObject {
            put("taskId", taskId)
            put("id", stored.id)
        })
    }

    writeTool(
        name = "remove_task_block",
        description = "Take one block out of a task's panel. The other blocks stay in order. Refused when the task has no block with that id.",
        inputSchema = schemaObject(
            "taskId" to schemaString("The task."),
            "id" to schemaString("The id of the block to remove."),
            required = listOf("taskId", "id"),
        ),
    ) { _, args ->
        val taskId = args.requireString("taskId")
        val id = args.requireString("id")
        panelCall { store.remove(taskId, id) }
        McpToolResult.text("Removed block $id from $taskId")
    }

    McpServer.register(
        McpTool(
            name = "get_task_panel",
            description = "Read the blocks in a task's panel, in the order they are shown, each with who wrote it " +
                "(writtenBy) and when (writtenAt). Call it before you write to a panel other agents also write to.",
            inputSchema = schemaObject(
                "taskId" to schemaString("The task (from list_tasks)."),
                required = listOf("taskId"),
            ),
            requiresWrite = false,
        ) { _, args ->
            val taskId = args.requireString("taskId")
            val blocks = panelCall { store.blocksOfKnown(taskId) }
            McpToolResult.json(buildJsonObject {
                put("taskId", taskId)
                put("blocks", buildJsonArray { blocks.forEach { add(panelBlockJson(it)) } })
            })
        },
    )
}
