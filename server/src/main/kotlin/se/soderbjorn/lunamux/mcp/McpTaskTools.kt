/**
 * MCP tools that let an agent group its own sessions into tasks and say what it is doing.
 *
 * Every agent session belongs to one task, and the agents overview shows tasks. An agent that
 * splits its work over several sessions calls these tools, unprompted, so the person sees one task
 * with its sessions rather than a flat list: `create_task`, `add_to_task`, `remove_from_task`,
 * `rename_task`, `set_task_lead` and `set_task_status` change tasks, and `list_tasks` reads them.
 * Every task has one conversation that speaks for it (the caller's, when it made the task, unless
 * another is named) and a one-line status. `set_status` is the per-conversation counterpart: what
 * one conversation is doing, or waits for. `create_window` and `run_command` in
 * [registerMcpWriteTools] also take a `taskId`.
 *
 * The rules (what counts as a known task, what happens to a pane with no agent yet) live in
 * [TaskIndex]; this file only maps its answers to tool results and its [TaskException]s to
 * [McpArgumentException]s.
 */
package se.soderbjorn.lunamux.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.AgentSessionTracker
import se.soderbjorn.lunamux.TaskException
import se.soderbjorn.lunamux.TaskIndex
import se.soderbjorn.lunamux.TaskListing

/**
 * Run [block] and turn a [TaskException] into the [McpArgumentException] the MCP layer reports as a
 * tool error. Called by every tool here and by the `taskId` handling of the window tools.
 *
 * @param block the work that may refuse.
 * @return what [block] returns.
 * @throws McpArgumentException carrying the refusal's text.
 */
internal fun <T> taskCall(block: () -> T): T =
    try {
        block()
    } catch (e: TaskException) {
        throw McpArgumentException(e.message ?: "Task request refused")
    }

/**
 * Read an optional array-of-strings argument.
 *
 * @param name the argument's name.
 * @return the strings; empty when the argument is absent or null.
 * @throws McpArgumentException when it is present but not an array of strings.
 */
private fun JsonObject.stringList(name: String): List<String> {
    val el = this[name] ?: return emptyList()
    if (el is JsonNull) return emptyList()
    val array = el as? JsonArray ?: throw McpArgumentException("'$name' must be an array of strings")
    return array.map { (it as? JsonPrimitive)?.contentOrNull ?: throw McpArgumentException("'$name' must be an array of strings") }
}

/**
 * Register `create_task`, `add_to_task`, `remove_from_task`, `rename_task`, `set_task_lead`,
 * `set_task_status`, `list_tasks` and `set_status`. Called by [mcpRoutes] when a [TaskIndex] is wired.
 *
 * @param taskIndex the owner of tasks, which every tool but `set_status` calls.
 * @param tracker the conversation records, for `set_status`.
 */
fun registerMcpTaskTools(taskIndex: TaskIndex, tracker: AgentSessionTracker) {
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
            "the taskId. The conversation that makes the task speaks for it in the overview, when an " +
            "agent runs in the calling pane (change that with set_task_lead). Work that is one session " +
            "needs no task.",
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
            putMain(taskIndex.listing().firstOrNull { it.id == id })
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
            "its own again. Call this when a session turns out to be unrelated work. A pane that was " +
            "only promised a task loses the promise. The result says which task each session is in " +
            "now (null for none).",
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
                for ((sid, taskId) in now) put(sid, taskId)
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
        description = "Name the conversation that speaks for a task: the one the person is taken to when " +
            "they open the task in the agents overview. Call this when another conversation is the one " +
            "to talk to, for example the one that coordinates the others. Any caller may name any " +
            "conversation. Give either sessionId (the pane's \$LUNAMUX_SESSION_ID, turned into the " +
            "conversation running in it now) or conversationId (the agent's own session id, which a " +
            "closed conversation still has). A conversation started inside another one (a helper) " +
            "cannot speak: its host does. Returns the taskId and the mainConversation now named.",
        inputSchema = schemaObject(
            "taskId" to schemaString("The task."),
            "sessionId" to schemaString("The pane whose conversation speaks for it (\$LUNAMUX_SESSION_ID)."),
            "conversationId" to schemaString("The conversation that speaks for it, as list_tasks shows it."),
            required = listOf("taskId"),
        ),
    ) { _, args ->
        val taskId = args.requireString("taskId")
        val sessionId = args.optString("sessionId")?.takeIf { it.isNotBlank() }
        val conversationId = args.optString("conversationId")?.takeIf { it.isNotBlank() }
        if ((sessionId == null) == (conversationId == null)) {
            throw McpArgumentException("Give exactly one of 'sessionId' and 'conversationId'")
        }
        taskCall {
            if (conversationId != null) taskIndex.setMainConversation(taskId, conversationId)
            else taskIndex.setOwnSession(taskId, sessionId!!)
        }
        McpToolResult.json(buildJsonObject {
            put("taskId", taskId)
            putMain(taskIndex.listing().firstOrNull { it.id == taskId })
        })
    }

    writeTool(
        name = "set_task_status",
        description = "Say in one line where a task stands, shown on its row in the agents overview " +
            "(for example \"2 of 3 migrations done, tests running\"). Call it when something worth " +
            "knowing changes, not on every step. Line breaks become spaces and the text is cut at 120 " +
            "characters. An empty text clears the status. A status that only counts states (\"2 working, " +
            "1 waiting\") is kept but not shown, since the overview counts them itself. Pass " +
            "questionWaits: true when a question waits for the person; it is kept only with a " +
            "non-empty text. Each call sets the flag afresh, so repeat it in a later status while the " +
            "question still waits. Pass paused: true while the work is stopped on purpose (the person " +
            "paused it, or it waits for a go): the row reads \"paused\" with your text as the reason. " +
            "It too is set afresh by each call, so a call without paused: true ends the pause, but an " +
            "empty text with paused: true keeps it. A repeated pause keeps the time it began.",
        inputSchema = schemaObject(
            "taskId" to schemaString("The task."),
            "text" to schemaString("The status line; empty clears it."),
            "questionWaits" to schemaBool("True while a question waits for the person. Default false; an empty text clears it."),
            "paused" to schemaBool("True while the work is paused on purpose. Default false."),
            required = listOf("taskId", "text"),
        ),
    ) { _, args ->
        val taskId = args.requireString("taskId")
        val text = (args["text"] as? JsonPrimitive)?.contentOrNull
            ?: throw McpArgumentException("'text' must be a string")
        val questionWaits = args.optBool("questionWaits") == true
        val paused = args.optBool("paused") == true
        val stored = taskCall { taskIndex.setStatus(taskId, text, questionWaits, paused) }
        McpToolResult.json(buildJsonObject {
            put("taskId", taskId)
            put("status", stored)
            put("questionWaits", stored != null && questionWaits)
            put("paused", paused)
        })
    }

    McpServer.register(
        McpTool(
            name = "list_tasks",
            description = "List the tasks there are and the sessions in each, so you can join one " +
                "instead of making a duplicate. Call this before create_task when you may be continuing " +
                "work that already has a task. mainConversation is the conversation that speaks for the " +
                "task: its conversationId, and the sessionId of the pane it runs in now (null while it " +
                "is closed). startedAt is when the task first appeared, in epoch milliseconds.",
            inputSchema = schemaObject(),
            requiresWrite = false,
        ) { _, _ ->
            McpToolResult.json(buildJsonArray {
                for (t in taskIndex.listing()) add(buildJsonObject {
                    put("taskId", t.id)
                    put("title", t.title)
                    put("source", t.source)
                    putMain(t)
                    put("status", t.status)
                    put("questionWaits", t.questionWaits)
                    put("paused", t.paused)
                    put("startedAt", t.startedAt)
                    put("sessions", buildJsonArray {
                        for (m in t.sessions) add(buildJsonObject {
                            put("sessionId", m.sessionId)
                            put("title", m.title)
                            put("state", m.state)
                        })
                    })
                })
            })
        },
    )

    writeTool(
        name = "set_status",
        description = "Say in one line what you are doing right now, in your own words (for example \"Reproducing the " +
            "failed resume on a copy of the data\"). It shows as your line in the agents overview while you work, in " +
            "place of the name of your last tool call, and it goes when the person next prompts you. " +
            "Call it when you start a distinct piece of work, not on every step. Line breaks become spaces and the text " +
            "is cut at 120 characters. An empty text clears it. A task's own one-line status is set_task_status.\n\n" +
            "With keep: true it says instead what the conversation waits for once its turn has ended, for example " +
            "\"in review · round 2\" or \"CI on #482\". The row then reads \"waiting\" in a calm colour instead of idle, " +
            "with this line and the time since it was written (do not put a time in the text). It is not cleared by the " +
            "next prompt: clear it with an empty text and keep: true, or it goes when the conversation ends. Pass " +
            "needsYou: true when what it waits for is the person (\"merge go\"): the row then asks for them " +
            "like a question does. Any caller may write it for another conversation by passing that " +
            "conversation's sessionId or conversationId. A conversation that left a background shell running at its prompt " +
            "reads \"waiting\" by itself; this line says what for.",
        inputSchema = schemaObject(
            "text" to schemaString("The status line; empty clears it."),
            "sessionId" to schemaString("Your pane: \$LUNAMUX_SESSION_ID from your environment. Defaults to the caller's."),
            "conversationId" to schemaString(
                "Your own conversation id (\$CLAUDE_CODE_SESSION_ID). Pass it when you are an agent started inside another " +
                    "conversation's pane, or the status lands on that conversation.",
            ),
            "keep" to schemaBool("True: what the conversation waits for after its turn; it stays until cleared. Default false: this turn only."),
            "needsYou" to schemaBool("With keep: true, whether what it waits for is the person. Default false."),
            required = listOf("text"),
        ),
    ) { ctx, args ->
        val text = (args["text"] as? JsonPrimitive)?.contentOrNull ?: throw McpArgumentException("'text' must be a string")
        val pane = args.optString("sessionId")?.takeIf { it.isNotBlank() } ?: ctx.callerSessionId
        val conversation = args.optString("conversationId")?.takeIf { it.isNotBlank() }
        val keep = args.optBool("keep") == true
        val needsYou = args.optBool("needsYou") == true
        if (needsYou && !keep) throw McpArgumentException("'needsYou' goes with 'keep: true': a line for this turn only cannot wait for the person.")
        val written = taskCall { tracker.setStatus(pane, conversation, text, keep = keep, needsYou = needsYou) }
        McpToolResult.json(buildJsonObject { put("status", written); put("keep", keep) })
    }
}

/**
 * Adds `mainConversation` for [listed]: the conversation that speaks for the task as
 * `{conversationId, sessionId}` (a null sessionId while it is closed), or null when none is named.
 *
 * @param listed the task's listing, or null for none.
 */
private fun JsonObjectBuilder.putMain(listed: TaskListing?) {
    put(
        "mainConversation",
        listed?.mainConversation?.let { buildJsonObject { put("conversationId", it); put("sessionId", listed.mainSessionId) } } ?: JsonNull,
    )
}
