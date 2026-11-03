/**
 * MCP tools for the panel beside a task's own session.
 *
 * An agent that works on a task can show the person what it is doing in blocks next to the task's
 * own session: text, facts, links, a timeline, progress and buttons. `set_task_panel` replaces
 * all of them, `upsert_task_block` and `remove_task_block` change one, and `get_task_panel` reads
 * them back. This file turns an agent's JSON into [TaskPanelBlock]s and the stored blocks back
 * into JSON; the rules about sizes and shapes live in [se.soderbjorn.lunamux.TaskPanelLimits] and
 * are applied by [TaskPanelStore], and its refusals reach the agent as tool errors.
 *
 * @see registerMcpPanelTools
 * @see TaskPanelStore
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
import se.soderbjorn.lunamux.TaskButton
import se.soderbjorn.lunamux.TaskException
import se.soderbjorn.lunamux.TaskFact
import se.soderbjorn.lunamux.TaskLink
import se.soderbjorn.lunamux.TaskPanelBlock
import se.soderbjorn.lunamux.TaskPanelLimits
import se.soderbjorn.lunamux.TaskPanelStore
import se.soderbjorn.lunamux.TaskTimelineItem
import java.time.Instant
import java.time.OffsetDateTime

/** The block types an agent may write, in the order the tool descriptions list them. */
private val BLOCK_TYPES = listOf("text", "facts", "links", "timeline", "progress", "actions")

/**
 * Read a JSON value as the array of objects a block field expects.
 *
 * @receiver the block's JSON.
 * @param field the field's name, used in the refusal.
 * @param blockId the block's id, used in the refusal.
 * @return the objects in the field's array.
 * @throws McpArgumentException when the field is missing, not an array, or holds a non-object.
 */
private fun JsonObject.objects(field: String, blockId: String): List<JsonObject> {
    val array = this[field] as? JsonArray ?: throw McpArgumentException("Block '$blockId': '$field' must be an array.")
    return array.map { it as? JsonObject ?: throw McpArgumentException("Block '$blockId': every entry of '$field' must be an object.") }
}

/**
 * Read a string field that must be present, keeping empty text.
 *
 * @receiver the JSON object to read from.
 * @param field the field's name.
 * @param where words naming the item for the refusal.
 * @return the string.
 * @throws McpArgumentException when the field is absent or not a string.
 */
private fun JsonObject.str(field: String, where: String): String =
    (this[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw McpArgumentException("$where needs a text '$field'.")

/**
 * Turn the `at` of a timeline entry into epoch milliseconds.
 *
 * @param el an ISO-8601 instant or offset date-time string, or epoch milliseconds as a number.
 * @param where words naming the entry for the refusal.
 * @return epoch milliseconds.
 * @throws McpArgumentException when it is neither.
 */
private fun parseAt(el: JsonElement?, where: String): Long {
    val p = el as? JsonPrimitive ?: throw McpArgumentException("$where needs an 'at' time (ISO-8601 text or epoch milliseconds).")
    p.contentOrNull?.toLongOrNull()?.let { return it }
    val text = p.contentOrNull ?: throw McpArgumentException("$where needs an 'at' time (ISO-8601 text or epoch milliseconds).")
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        ?: throw McpArgumentException("$where has an 'at' of '${text.take(40)}' that is not ISO-8601 (like 2026-05-01T12:00:00Z) or epoch milliseconds.")
}

/**
 * Read one block the way an agent writes it.
 *
 * @param json the block: `id`, optional `title`, `type` and the fields of that type.
 * @return the block, with no writer and time yet (the store stamps them).
 * @throws McpArgumentException when a field is missing or has the wrong shape; size and content
 *   rules are checked later by [TaskPanelStore].
 */
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
        "text" -> TaskBlockContent.Text(json.str("text", "Block '$id'"))
        "facts" -> TaskBlockContent.Facts(json.objects("items", id).mapIndexed { i, o ->
            TaskFact(o.str("label", "Block '$id' fact ${i + 1}"), o.str("value", "Block '$id' fact ${i + 1}"))
        })
        "links" -> TaskBlockContent.Links(json.objects("items", id).mapIndexed { i, o ->
            TaskLink(o.str("label", "Block '$id' link ${i + 1}"), o.str("url", "Block '$id' link ${i + 1}"))
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
        "actions" -> TaskBlockContent.Actions(json.objects("buttons", id).mapIndexed { i, o ->
            val label = o.str("label", "Block '$id' button ${i + 1}")
            TaskButton(
                id = o.str("id", "Block '$id' button ${i + 1}"),
                label = label,
                text = (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: label,
            )
        })
        else -> throw McpArgumentException("Block '$id' has an unknown type '${type.take(40)}'. Use ${BLOCK_TYPES.joinToString(", ")}.")
    }
    return TaskPanelBlock(id = id, title = title, content = content, writtenAtMillis = 0L)
}

/**
 * Read an optional whole-number field of a progress block.
 *
 * @receiver the block's JSON.
 * @param field `done` or `total`.
 * @param blockId the block's id, used in the refusal.
 * @return the number, or null when the field is absent.
 * @throws McpArgumentException when it is present but not a whole number.
 */
private fun JsonObject.numberField(field: String, blockId: String): Int? {
    val el = this[field] ?: return null
    if (el is JsonNull) return null
    return (el as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        ?: throw McpArgumentException("Block '$blockId': '$field' must be a whole number.")
}

/**
 * Write a stored block the way an agent writes it, plus who wrote it and when.
 *
 * @param b the stored block.
 * @return JSON in the shape [parsePanelBlock] reads, with `writtenBy` and `writtenAt` added.
 */
internal fun panelBlockJson(b: TaskPanelBlock): JsonObject = buildJsonObject {
    put("id", b.id)
    put("type", b.content.kindName)
    b.title?.let { put("title", it) }
    when (val c = b.content) {
        is TaskBlockContent.Text -> put("text", c.markdown)
        is TaskBlockContent.Facts -> put("items", buildJsonArray {
            c.items.forEach { add(buildJsonObject { put("label", it.label); put("value", it.value) }) }
        })
        is TaskBlockContent.Links -> put("items", buildJsonArray {
            c.items.forEach { add(buildJsonObject { put("label", it.label); put("url", it.url) }) }
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
        is TaskBlockContent.Actions -> put("buttons", buildJsonArray {
            c.buttons.forEach { add(buildJsonObject { put("id", it.id); put("label", it.label); put("text", it.text) }) }
        })
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
 *
 * @param store holds and checks the blocks.
 * @param tracker names the agent running in the caller's session, for the "written by" name.
 * @see TaskPanelStore
 */
fun registerMcpPanelTools(store: TaskPanelStore, tracker: AgentSessionTracker) {
    val blockSchema = schemaObject(
        "id" to schemaString(
            "Your name for the block: 1-${TaskPanelLimits.ID_MAX} characters from letters, digits, '.', '_', ':' and '-'. " +
                "Other writers use the same panel, so start your ids with something of your own (like \"tests-status\"). " +
                "'agents' is reserved.",
        ),
        "type" to schemaString("What the block shows.", BLOCK_TYPES),
        "title" to schemaString("Heading above the block, up to ${TaskPanelLimits.TITLE_MAX} characters."),
        "text" to schemaString(
            "type text: up to ${TaskPanelLimits.TEXT_MAX} characters of markdown (paragraphs, # headings, - or 1. lists, " +
                "**bold**, *italic*, `code`, fenced code, [label](http url)). Anything else, HTML included, is shown as written.",
        ),
        "items" to schemaArray(
            "type facts: [{label, value}] (up to ${TaskPanelLimits.LIST_MAX}; label ${TaskPanelLimits.LABEL_MAX}, value ${TaskPanelLimits.VALUE_MAX} characters). " +
                "type links: [{label, url}] (up to ${TaskPanelLimits.LIST_MAX}; only http, https and file addresses). " +
                "type timeline: [{at, text}] where at is ISO-8601 or epoch milliseconds (text ${TaskPanelLimits.TIMELINE_TEXT_MAX} characters; " +
                "past ${TaskPanelLimits.TIMELINE_MAX} entries the newest are kept).",
            buildJsonObject { put("type", "object") },
        ),
        "done" to schemaInt("type progress: how many parts are finished (with total)."),
        "total" to schemaInt("type progress: how many parts there are (with done)."),
        "phase" to schemaString("type progress: the stage the work is in, instead of done and total."),
        "label" to schemaString("type progress: a short caption."),
        "buttons" to schemaArray(
            "type actions: up to ${TaskPanelLimits.BUTTONS_MAX} buttons [{id, label, text?}]. Pressing one types its text and Enter into the " +
                "task's own session; text defaults to the label. One plain line, no control characters, up to ${TaskPanelLimits.BUTTON_TEXT_MAX} characters.",
            buildJsonObject { put("type", "object") },
        ),
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
            "text, facts, links, a timeline, progress and buttons. This replaces every block of the task at once, in " +
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
