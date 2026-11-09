/**
 * MCP tools that let an agent group its own sessions into tasks.
 *
 * Every agent session belongs to one task, and the agents overview shows tasks. An agent that
 * splits its work over several sessions calls these tools, unprompted, so the person sees one
 * task with its sessions rather than a flat list: `create_task`, `add_to_task`, `remove_from_task`,
 * `rename_task`, `set_task_lead` and `set_task_status` change tasks, and `list_tasks` reads them.
 * Every task has one session that speaks for it (the one that made it, unless another is named) and
 * a one-line status. `create_window` in
 * [registerMcpWriteTools] also takes a `taskId`.
 *
 * The rules (what counts as a known task, what happens to a pane with no agent yet) live in
 * [TaskIndex]; this file only maps its answers to tool results and its [TaskException]s to
 * [McpArgumentException]s.
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
import se.soderbjorn.lunamux.TaskException
import se.soderbjorn.lunamux.TaskIndex
import se.soderbjorn.lunamux.TaskListing

/**
 * Run [block] and turn a [TaskException] into the [McpArgumentException] the MCP layer reports
 * as a tool error.
 *
 * Called by every tool here and by `create_window` when it is given a task.
 */
internal fun <T> taskCall(block: () -> T): T =
    try {
        block()
    } catch (e: TaskException) {
        throw McpArgumentException(e.message ?: "Task request refused")
    }

/** Read an optional array of strings argument. */
private fun JsonObject.stringList(name: String): List<String> {
    val el = this[name] ?: return emptyList()
    if (el is JsonNull) return emptyList()
    val array = el as? JsonArray ?: throw McpArgumentException("'$name' must be an array of strings")
    return array.map { (it as? JsonPrimitive)?.contentOrNull ?: throw McpArgumentException("'$name' must be an array of strings") }
}

/**
 * Register `create_task`, `add_to_task`, `remove_from_task`, `rename_task`, `set_task_lead`,
 * `set_task_status` and `list_tasks`.
 *
 * Called once at boot from [mcpRoutes], when the server has a [TaskIndex]. The tools that
 * change tasks need a `read+write` token; `list_tasks` needs only `read`.
 */
fun registerMcpTaskTools(taskIndex: TaskIndex) {
    val sessionIdsSchema = schemaArray(
        "Terminal session ids (\$LUNAMUX_SESSION_ID of the pane the session runs in). " +
            "Your own is in your environment; list_sessions shows the others.",
        schemaString("A session id."),
    )

    writeTool(
        name = "create_task",
        description = "Start a task: a named piece of work that several agent sessions share, shown " +
            "as one row in the agents overview. Call this when you split your work across a second " +
            "agent session, passing your own \$LUNAMUX_SESSION_ID in sessionIds, then start the other " +
            "session with create_window and the returned taskId (or add it with add_to_task). Returns " +
            "the taskId. The session that makes the task is the one that speaks for it in the overview " +
            "(change that with set_task_lead). Work that is one session needs no task.",
        inputSchema = schemaObject(
            "title" to schemaString("What the work is, in a few words; this is the row's name."),
            "sessionIds" to sessionIdsSchema,
            required = listOf("title"),
        ),
    ) { ctx, args ->
        val title = args.requireString("title")
        val sessionIds = args.stringList("sessionIds")
        val id = taskCall { taskIndex.createTask(title, sessionIds, ctx.callerSessionId) }
        McpToolResult.json(buildJsonObject {
            put("taskId", id)
            put("sessions", sessionIds.size)
            val listed = taskIndex.listing().firstOrNull { it.id == id }
            put("ownSession", listed?.ownSessionId?.let(::JsonPrimitive) ?: JsonNull)
            put("mainConversation", mainConversationJson(listed))
        })
    }

    writeTool(
        name = "add_to_task",
        description = "Put agent sessions into a task that exists (from create_task or list_tasks, " +
            "including a task a task file declares), taking them out of whatever task they were in. " +
            "Call this for a session you started yourself that belongs with the work you are doing. " +
            "A pane with no agent running yet is promised the task for the session that starts in it.",
        inputSchema = schemaObject(
            "taskId" to schemaString("The task to join."),
            "sessionIds" to sessionIdsSchema,
            required = listOf("taskId", "sessionIds"),
        ),
    ) { _, args ->
        val taskId = args.requireString("taskId")
        val sessionIds = args.stringList("sessionIds")
        if (sessionIds.isEmpty()) throw McpArgumentException("sessionIds must name at least one session")
        taskCall { taskIndex.addToTask(taskId, sessionIds) }
        McpToolResult.text("Added ${sessionIds.size} session(s) to $taskId")
    }

    writeTool(
        name = "remove_from_task",
        description = "Take agent sessions out of the task you put them in, so each shows as a task of " +
            "its own again. Call this when a session turns out to be unrelated work. A session that a " +
            "task file still matches stays in that file's task; the result says where each one ended up.",
        inputSchema = schemaObject(
            "sessionIds" to sessionIdsSchema,
            required = listOf("sessionIds"),
        ),
    ) { _, args ->
        val sessionIds = args.stringList("sessionIds")
        if (sessionIds.isEmpty()) throw McpArgumentException("sessionIds must name at least one session")
        val now = taskCall { taskIndex.removeFromTask(sessionIds) }
        McpToolResult.json(buildJsonObject {
            put("sessions", buildJsonObject {
                for ((sid, taskId) in now) put(sid, taskId?.let(::JsonPrimitive) ?: JsonNull)
            })
        })
    }

    writeTool(
        name = "rename_task",
        description = "Change the name a task shows in the agents overview. Call this when the work " +
            "has turned into something else than its title says. Tasks that a task file declares " +
            "are named by their file and cannot be renamed here.",
        inputSchema = schemaObject(
            "taskId" to schemaString("The task to rename."),
            "title" to schemaString("The new name."),
            required = listOf("taskId", "title"),
        ),
    ) { _, args ->
        val taskId = args.requireString("taskId")
        val title = args.requireString("title")
        taskCall { taskIndex.renameTask(taskId, title) }
        McpToolResult.text("Renamed $taskId")
    }

    writeTool(
        name = "set_task_lead",
        description = "Name the session that speaks for a task: the one the person is taken to when " +
            "they open the task in the agents overview. A task starts with the session that made it; " +
            "call this when another session is the one to talk to, for example the one that coordinates " +
            "the others. Any session of yours or another agent's will do. Give either sessionId (the " +
            "pane's \$LUNAMUX_SESSION_ID, turned into the conversation running in it now) or " +
            "conversationId (the agent's own session id, which a closed conversation still has). " +
            "Returns the taskId and the mainConversation now named.",
        inputSchema = schemaObject(
            "taskId" to schemaString("The task."),
            "sessionId" to schemaString("The pane whose conversation speaks for it (\$LUNAMUX_SESSION_ID)."),
            "conversationId" to schemaString("The conversation that speaks for it, as list_tasks shows it."),
            required = listOf("taskId"),
        ),
    ) { _, args ->
        val taskId = args.requireString("taskId")
        val sessionId = (args["sessionId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val conversationId = (args["conversationId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if ((sessionId == null) == (conversationId == null)) {
            throw McpArgumentException("Give exactly one of 'sessionId' and 'conversationId'")
        }
        taskCall {
            if (conversationId != null) taskIndex.setMainConversation(taskId, conversationId)
            else taskIndex.setOwnSession(taskId, sessionId!!)
        }
        val listed = taskIndex.listing().firstOrNull { it.id == taskId }
        McpToolResult.json(buildJsonObject {
            put("taskId", taskId)
            put("ownSession", listed?.ownSessionId?.let(::JsonPrimitive) ?: JsonNull)
            put("mainConversation", mainConversationJson(listed))
        })
    }

    writeTool(
        name = "set_task_status",
        description = "Say in one line where a task stands, shown on its row in the agents overview " +
            "(for example \"2 of 3 migrations done, tests running\"). Call it when something worth " +
            "knowing changes, not on every step. Line breaks become spaces and the text is cut at 120 " +
            "characters. An empty text clears the status. Pass questionWaits: true when a question waits " +
            "for the person: the task's row then stays among the live ones even while its main " +
            "conversation is closed, and resuming that conversation asks it. Each call sets the flag " +
            "afresh, so repeat it in a later status while the question still waits.",
        inputSchema = schemaObject(
            "taskId" to schemaString("The task."),
            "text" to schemaString("The status line; empty clears it."),
            "questionWaits" to schemaBool("True while a question waits for the person. Default false; an empty text clears it."),
            required = listOf("taskId", "text"),
        ),
    ) { _, args ->
        val taskId = args.requireString("taskId")
        val text = (args["text"] as? JsonPrimitive)?.contentOrNull
            ?: throw McpArgumentException("'text' must be a string")
        val questionWaits = args.optBool("questionWaits") == true
        val stored = taskCall { taskIndex.setStatus(taskId, text, questionWaits) }
        McpToolResult.json(buildJsonObject {
            put("taskId", taskId)
            put("status", stored?.let(::JsonPrimitive) ?: JsonNull)
            put("questionWaits", stored != null && questionWaits)
        })
    }

    McpServer.register(
        McpTool(
            name = "list_tasks",
            description = "List the tasks there are and the sessions in each, so you can join one " +
                "instead of making a duplicate. Call this before create_task when you may be continuing " +
                "work that already has a task. mainConversation is the conversation that speaks for the task: " +
                "its conversationId, and the sessionId of the pane it runs in now (null while it is closed). " +
                "ownSession is the same pane's session id, kept for callers that still read it; use mainConversation.",
            inputSchema = schemaObject(),
            requiresWrite = false,
        ) { _, _ ->
            McpToolResult.json(buildJsonArray {
                for (t in taskIndex.listing()) add(buildJsonObject {
                    put("taskId", t.id)
                    put("title", t.title)
                    put("source", t.source)
                    put("ownSession", t.ownSessionId?.let(::JsonPrimitive) ?: JsonNull)
                    put("mainConversation", mainConversationJson(t))
                    put("status", t.status?.let(::JsonPrimitive) ?: JsonNull)
                    put("questionWaits", t.questionWaits)
                    put("sessions", buildJsonArray {
                        for (m in t.sessions) add(buildJsonObject {
                            put("sessionId", m.sessionId?.let(::JsonPrimitive) ?: JsonNull)
                            put("title", m.title)
                            put("state", m.state)
                        })
                    })
                })
            })
        },
    )
}

/**
 * The `mainConversation` value of a task as the tools return it: `{conversationId, sessionId}`, with
 * a null sessionId while the conversation is closed, or null when no conversation was named.
 *
 * Called by `create_task`, `set_task_lead` and `list_tasks`.
 */
private fun mainConversationJson(listed: TaskListing?): JsonElement {
    val conversation = listed?.mainConversation ?: return JsonNull
    return buildJsonObject {
        put("conversationId", conversation)
        put("sessionId", listed.ownSessionId?.let(::JsonPrimitive) ?: JsonNull)
    }
}
