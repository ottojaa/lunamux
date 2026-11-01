/**
 * Reads the agent group files other tools leave in a folder and turns them into
 * [AgentGroup]s for the agents overview.
 *
 * This file contains [AgentGroupsWatcher], which polls the folder, parses each
 * `<id>.json` into an [AgentGroupFile] and matches the members and the lead
 * session against the session tracker's records, plus the private file model
 * ([AgentGroupFile] and its parts). The format is described in
 * `docs/agent-groups.md`. Lunamux only ever reads these files.
 *
 * @see AgentSessionTracker
 * @see AgentGroup
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import java.io.File
import java.time.Instant

/**
 * One group file as written by the tool that owns it.
 *
 * Everything is nullable or defaulted so a file with a missing field fails the
 * explicit checks in [AgentGroupsWatcher] (one warning, group skipped) instead of
 * failing JSON decoding with a less helpful message. Unknown fields are ignored.
 *
 * @property version format version; only 1 exists.
 * @property id the group's id, also what [WindowCommand.OpenAgentGroupLead] names.
 * @property title the group's name.
 * @property tag short pill text such as `PROGRAMME`.
 * @property repo repository name shown beside the title.
 * @property link the header's link chip.
 * @property updatedAt ISO-8601 time the writer last changed the file (informational).
 * @property lead how and where to start the lead session.
 * @property members the group's members.
 */
@Serializable
internal data class AgentGroupFile(
    val version: Int = 1,
    val id: String? = null,
    val title: String? = null,
    val tag: String? = null,
    val repo: String? = null,
    val link: GroupFileLink? = null,
    val updatedAt: String? = null,
    val lead: GroupFileLead? = null,
    val members: List<GroupFileMember> = emptyList(),
)

/**
 * The link chip of a group file.
 *
 * @property label text of the chip.
 * @property url an `http://` or `https://` address.
 */
@Serializable
internal data class GroupFileLink(val label: String? = null, val url: String? = null)

/**
 * The lead session of a group file.
 *
 * @property command the line typed into a new tab's shell to start the lead session.
 * @property folder absolute folder the lead session runs in (a leading `~/` is expanded).
 */
@Serializable
internal data class GroupFileLead(val command: String? = null, val folder: String? = null)

/**
 * How a group file ties a member to a session Lunamux hosts.
 *
 * @property agentSessionId the agent's own session id (Claude's `session_id`).
 * @property folder absolute folder; an open session running in it or below it matches.
 */
@Serializable
internal data class GroupFileMatch(val agentSessionId: String? = null, val folder: String? = null)

/**
 * One member of a group file.
 *
 * @property title the member's name.
 * @property links ticket keys or other short labels.
 * @property match how to find the member's session, if Lunamux hosts it.
 * @property state `needs_you`, `working`, `idle` or `ended`.
 * @property label a state word for the summary line, such as "in review".
 * @property line one line about what the member is doing.
 * @property since ISO-8601 time the member entered [state].
 * @property sessionId the id `claude attach` takes for the member's background session; only the id
 *   is read from the file, the command is always built by the server.
 */
@Serializable
internal data class GroupFileMember(
    val title: String? = null,
    val links: List<String> = emptyList(),
    val match: GroupFileMatch? = null,
    val state: String? = null,
    val label: String? = null,
    val line: String? = null,
    val since: String? = null,
    val sessionId: String? = null,
)

/**
 * A parsed, validated group file: what [AgentGroupsWatcher] keeps per file between polls.
 *
 * Paths are already expanded and canonical, so matching is a plain string comparison.
 *
 * @property group the group as the clients get it, with member and lead fields still unresolved.
 * @property command the lead's start command; never leaves the server.
 * @property leadFolder canonical lead folder.
 * @property matches per member (same order as the group's members), the canonical match
 *   folder and agent session id the member asks for.
 */
private class ParsedGroup(
    val group: AgentGroup,
    val command: String,
    val leadFolder: String,
    val matches: List<GroupFileMatch?>,
)

/** What the watcher remembers about one file: enough to see that it changed, and what it parsed to. */
private class Seen(val modified: Long, val length: Long, val parsed: ParsedGroup?)

/**
 * Watches a folder of group files and publishes the groups they declare.
 *
 * A group file lists members and a lead session. The watcher re-reads a file only when
 * its modified time or size changes ([reload]), and re-matches members to session records
 * whenever the records change ([resolve]), so a member follows its session live. The result
 * is [groups]. A file that is not valid JSON, or lacks `id`, `title` or `lead`, is skipped
 * with a single warning until it changes again; nothing here throws.
 *
 * Created once by `main()` and handed to `windowRoutes`, which pushes [groups] to every
 * window connection and asks [find] for the lead command when a group's header is opened.
 *
 * @param dir the folder holding `<id>.json` files; it may not exist.
 * @param records reads the session records to match against (the tracker's [AgentSessionTracker.snapshot]).
 * @param recordChanges emits whenever the records change, so members are re-resolved
 *   (the tracker's summaries); collected by [start].
 * @param warn receives each warning about a file that was skipped; logs by default.
 * @see AgentGroup
 */
class AgentGroupsWatcher(
    private val dir: File,
    private val records: () -> List<AgentSessionRecord>,
    private val recordChanges: Flow<*>,
    private val warn: (String) -> Unit = { LoggerFactory.getLogger(AgentGroupsWatcher::class.java).warn(it) },
) {
    private val lock = Any()
    private val seen = HashMap<String, Seen>()
    private var parsed: List<ParsedGroup> = emptyList()
    private val _groups = MutableStateFlow<List<AgentGroup>>(emptyList())

    /**
     * The resolved groups, sorted by id. Collected per window socket by `WindowRoutes`.
     */
    val groups: StateFlow<List<AgentGroup>> = _groups.asStateFlow()

    /**
     * Start watching: poll the folder every 3 seconds and re-resolve on every record change.
     *
     * Called once from `main()` with the server's background scope.
     *
     * @param scope the scope the two loops run in; cancelling it stops the watcher.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                runCatching { reload() }.onFailure { warn("Agent group folder $dir could not be read: ${it.message}") }
                delay(POLL_MS)
            }
        }
        scope.launch { recordChanges.collect { resolve() } }
    }

    /**
     * Look up a group's lead command and folder from the files, never from a client.
     *
     * Called by the open-lead route.
     *
     * @param groupId the group's id.
     * @return the group as last resolved and the lead's start command, or null for an unknown id.
     */
    fun find(groupId: String): Pair<AgentGroup, String>? = synchronized(lock) {
        val p = parsed.firstOrNull { it.group.id == groupId } ?: return null
        val resolved = _groups.value.firstOrNull { it.id == groupId } ?: return null
        resolved to p.command
    }

    /**
     * Re-read the folder: parse files that are new or changed, forget removed ones, then [resolve].
     *
     * Called by the polling loop; tests call it directly.
     */
    fun reload() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.startsWith(".") }
            ?.sortedBy { it.name }.orEmpty()
        synchronized(lock) {
            // A duplicate id is reported only when the set of parsed files changed in this
            // reload; otherwise the same warning would repeat on every poll.
            var changed = seen.keys.retainAll(files.map { it.name }.toSet())
            for (f in files) {
                val modified = f.lastModified()
                val length = f.length()
                val before = seen[f.name]
                if (before != null && before.modified == modified && before.length == length) continue
                seen[f.name] = Seen(modified, length, parseFile(f, modified))
                changed = true
            }
            val byId = LinkedHashMap<String, Pair<String, ParsedGroup>>()
            // Oldest file first so that, for a repeated id, the newest file replaces it.
            for ((name, s) in seen.entries.sortedBy { it.value.modified }) {
                val p = s.parsed ?: continue
                val earlier = byId[p.group.id]
                if (earlier != null && changed) warn("Agent group file $name repeats the id of ${earlier.first}; using $name")
                byId[p.group.id] = name to p
            }
            parsed = byId.values.map { it.second }
        }
        resolve()
    }

    /**
     * Match the parsed groups' members and leads against the current session records and publish.
     *
     * Called after every [reload] and whenever [recordChanges] emits. Never touches the layout:
     * a record counts as open from its own state alone.
     */
    fun resolve() {
        // Compute and publish under the lock, so that the poll loop and the record collector
        // cannot interleave and leave the older result as the published one.
        synchronized(lock) {
            val recs = records()
            _groups.value = parsed.map { resolveGroup(it, recs) }.sortedBy { it.id }
        }
    }

    private fun parseFile(f: File, modified: Long): ParsedGroup? {
        val file = try {
            fileJson.decodeFromString(AgentGroupFile.serializer(), f.readText())
        } catch (e: Exception) {
            warn("Skipping agent group file ${f.name}: not a readable group file (${e.message?.lineSequence()?.firstOrNull()})")
            return null
        }
        fun bad(why: String): ParsedGroup? {
            warn("Skipping agent group file ${f.name}: $why")
            return null
        }
        val id = file.id?.takeIf { it.isNotBlank() } ?: return bad("missing id")
        val title = file.title?.takeIf { it.isNotBlank() } ?: return bad("missing title")
        val lead = file.lead ?: return bad("missing lead")
        val command = lead.command?.takeIf { it.isNotBlank() } ?: return bad("missing lead.command")
        val leadFolder = absolute(lead.folder) ?: return bad("lead.folder must be an absolute path")
        val matches = ArrayList<GroupFileMatch?>()
        val members = ArrayList<AgentGroupMember>()
        for ((i, m) in file.members.withIndex()) {
            val state = m.state
            if (state !in STATES) return bad("member ${i + 1} has state '$state'; use needs_you, working, idle or ended")
            val mTitle = m.title?.takeIf { it.isNotBlank() } ?: return bad("member ${i + 1} has no title")
            val folder = m.match?.folder?.let { absolute(it) ?: return bad("member ${i + 1} match.folder must be an absolute path") }
            matches += GroupFileMatch(m.match?.agentSessionId?.takeIf { it.isNotBlank() }, folder)
            members += AgentGroupMember(
                title = mTitle, links = m.links, state = state!!, label = m.label?.takeIf { it.isNotBlank() },
                line = m.line?.takeIf { it.isNotBlank() }, sinceMillis = instant(m.since) ?: modified,
                sessionId = m.sessionId?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        val url = file.link?.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val label = file.link?.label?.takeIf { it.isNotBlank() }
        val group = AgentGroup(
            id = id, title = title, tag = file.tag?.takeIf { it.isNotBlank() }, repo = file.repo?.takeIf { it.isNotBlank() },
            linkLabel = if (url != null) label ?: url else null, linkUrl = url,
            lead = AgentGroupLead(folder = leadFolder), members = members, refreshedAtMillis = modified,
        )
        return ParsedGroup(group, command, leadFolder, matches)
    }

    private fun resolveGroup(p: ParsedGroup, recs: List<AgentSessionRecord>): AgentGroup {
        val canon = HashMap<String, String>()
        fun cwdOf(r: AgentSessionRecord) = r.cwd?.let { canon.getOrPut(it) { canonical(it) } }
        val newestFirst = recs.sortedByDescending { it.createdAt }
        val leadRecs = newestFirst.filter { cwdOf(it) == p.leadFolder }
        val leadRec = leadRecs.firstOrNull { isOpen(it) } ?: leadRecs.firstOrNull()
        val lead = AgentGroupLead(
            folder = p.leadFolder,
            recordId = leadRec?.id,
            live = leadRec != null && isOpen(leadRec),
            lastSaid = leadRec?.lastSaid,
        )
        val claimed = HashSet<String>()
        val members = p.group.members.mapIndexed { i, m ->
            val want = p.matches[i]
            val candidates = newestFirst.filter { it.id !in claimed && cwdOf(it) != p.leadFolder }
            val rec = want?.agentSessionId?.let { sid -> candidates.firstOrNull { it.agentSessionId == sid } }
                ?: want?.folder?.let { folder ->
                    candidates.firstOrNull { r ->
                        val cwd = cwdOf(r)
                        isOpen(r) && cwd != null && (cwd == folder || cwd.startsWith(folder.trimEnd('/') + "/"))
                    }
                }
            if (rec == null) m else {
                claimed += rec.id
                if (!isOpen(rec)) m.copy(recordId = rec.id) else m.copy(
                    state = rec.state,
                    line = (if (rec.state == "needs_you") rec.question else rec.nowLine) ?: m.line,
                    sinceMillis = rec.stateSince,
                    recordId = rec.id,
                )
            }
        }
        return p.group.copy(lead = lead, members = members)
    }

    private fun isOpen(r: AgentSessionRecord) = r.state != "ended" && r.leafId != null

    private fun absolute(path: String?): String? {
        val raw = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val expanded = if (raw == "~" || raw.startsWith("~/")) System.getProperty("user.home") + raw.drop(1) else raw
        return if (expanded.startsWith("/")) canonical(expanded) else null
    }

    private fun canonical(path: String): String =
        try { File(path).canonicalPath } catch (_: Exception) { File(path).absolutePath }

    private fun instant(text: String?): Long? =
        text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private companion object {
        const val POLL_MS = 3_000L
        val STATES = setOf("needs_you", "working", "idle", "ended")
        val fileJson = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
