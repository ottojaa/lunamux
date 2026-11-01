/**
 * MCP tools that let an agent group its own sessions into tasks.
 *
 * Every agent session belongs to one task, and the agents overview shows tasks. An agent that
 * splits its work over several sessions calls these tools, unprompted, so the person sees one
 * task with its sessions rather than a flat list: `create_task`, `add_to_task`, `remove_from_task`
 * and `rename_task` change tasks, and `list_tasks` reads them. `create_window` in
 * [registerMcpWriteTools] also takes a `taskId`.
 *
 * The rules (what counts as a known task, what happens to a pane with no agent yet) live in
 * [TaskIndex]; this file only maps its answers to tool results and its [TaskException]s to
 * [McpArgumentException]s.
 *
 * @see registerMcpTaskTools
 * @see TaskIndex
 */
package se.soderbjorn.lunamux.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.TaskException
import se.soderbjorn.lunamux.TaskIndex

/**
 * Run [block] and turn a [TaskException] into the [McpArgumentException] the MCP layer reports
 * as a tool error.
 *
 * Called by every tool here and by `create_window` when it is given a task.
 *
 * @param block the [TaskIndex] call to make.
 * @return what [block] returned.
 * @throws McpArgumentException carrying the exception's message, which names the offender.
 */
internal fun <T> taskCall(block: () -> T): T =
    try {
        block()
    } catch (e: TaskException) {
        throw McpArgumentException(e.message ?: "Task request refused")
    }

/**
 * Read an optional array of strings argument.
 *
 * @param name the argument's name.
 * @return the strings, or an empty list when the argument is absent.
 * @throws McpArgumentException when the argument is not an array of strings.
 */
private fun JsonObject.stringList(name: String): List<String> {
    val el = this[name] ?: return emptyList()
    if (el is JsonNull) return emptyList()
    val array = el as? JsonArray ?: throw McpArgumentException("'$name' must be an array of strings")
    return array.map { (it as? JsonPrimitive)?.contentOrNull ?: throw McpArgumentException("'$name' must be an array of strings") }
}

/**
 * Register `create_task`, `add_to_task`, `remove_from_task`, `rename_task` and `list_tasks`.
 *
 * Called once at boot from [mcpRoutes], when the server has a [TaskIndex]. The four tools that
 * change tasks need a `read+write` token; `list_tasks` needs only `read`.
 *
 * @param taskIndex the index every tool reads and writes.
 * @see registerMcpWriteTools
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
            "the taskId. Work that is one session needs no task.",
        inputSchema = schemaObject(
            "title" to schemaString("What the work is, in a few words; this is the row's name."),
            "sessionIds" to sessionIdsSchema,
            required = listOf("title"),
        ),
    ) { _, args ->
        val title = args.requireString("title")
        val sessionIds = args.stringList("sessionIds")
        val id = taskCall { taskIndex.createTask(title, sessionIds) }
        McpToolResult.json(buildJsonObject {
            put("taskId", id)
            put("sessions", sessionIds.size)
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

    McpServer.register(
        McpTool(
            name = "list_tasks",
            description = "List the tasks there are and the sessions in each, so you can join one " +
                "instead of making a duplicate. Call this before create_task when you may be continuing " +
                "work that already has a task.",
            inputSchema = schemaObject(),
            requiresWrite = false,
        ) { _, _ ->
            McpToolResult.json(buildJsonArray {
                for (t in taskIndex.listing()) add(buildJsonObject {
                    put("taskId", t.id)
                    put("title", t.title)
                    put("source", t.source)
                    put("sessions", buildJsonArray {
                        for (m in t.members) add(buildJsonObject {
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
