/**
 * The sections agents write for the side panel, kept with the conversation that wrote them.
 *
 * This file contains [PanelSectionStore]. A section belongs to a conversation, not to a tab or a
 * task: a resume brings it back wherever the conversation lands, a fresh conversation starts with
 * none, and a closed conversation keeps its sections. Everything is held in memory and saved as
 * one JSON value in the settings table, so it needs no table of its own.
 *
 * @see se.soderbjorn.lunamux.PanelSection
 * @see AgentSessionTracker.setSection
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import se.soderbjorn.lunamux.persistence.SettingsRepository

/**
 * Holds every conversation's panel sections. Called by [AgentSessionTracker], under its lock, for
 * the `set_panel_section` and `remove_panel_section` tools and when it publishes its summaries.
 *
 * @param repo where the sections are saved; null keeps them in memory only (tests).
 * @param now the clock sections are dated with.
 */
class PanelSectionStore(private val repo: SettingsRepository?, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), ListSerializer(PanelSection.serializer()))
    private val byConversation: MutableMap<String, List<PanelSection>> = load()

    /** Reads the saved sections, leaving out conversations nobody has written to for [KEEP_MS]. */
    private fun load(): MutableMap<String, List<PanelSection>> {
        val saved = repo?.getString(SETTINGS_KEY)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
        val cutoff = now() - KEEP_MS
        return saved.filterValues { list -> list.any { it.writtenAtMillis >= cutoff } }.toMutableMap()
    }

    /** The sections of conversation [conversationId], in the order first written. */
    fun of(conversationId: String): List<PanelSection> = byConversation[conversationId].orEmpty()

    /**
     * Writes section [key] of [conversationId], replacing the one with that key where it stands or
     * adding it at the end.
     *
     * @return the section as kept.
     * @throws TaskException when a limit of [PanelSectionLimits] is passed; the message says which.
     */
    fun set(conversationId: String, key: String, heading: String, body: String): PanelSection {
        val k = key.trim()
        val h = heading.trim()
        val b = body.trim()
        if (k.isEmpty() || k.length > PanelSectionLimits.KEY_MAX) throw TaskException("'key' must be 1-${PanelSectionLimits.KEY_MAX} characters.")
        if (h.isEmpty() || h.length > PanelSectionLimits.HEADING_MAX || '\n' in h) throw TaskException("'heading' must be one line of 1-${PanelSectionLimits.HEADING_MAX} characters.")
        if (b.isEmpty()) throw TaskException("'body' is empty. To take a section away use remove_panel_section.")
        if (b.length > PanelSectionLimits.BODY_MAX) throw TaskException("'body' is ${b.length} characters, the limit is ${PanelSectionLimits.BODY_MAX}. Say less: the panel is read at a glance.")
        val had = of(conversationId)
        if (had.none { it.key == k } && had.size >= PanelSectionLimits.MAX_SECTIONS) {
            throw TaskException("This conversation already has ${PanelSectionLimits.MAX_SECTIONS} sections (${had.joinToString { it.key }}). Replace or remove one.")
        }
        val section = PanelSection(k, h, b, now())
        byConversation[conversationId] = if (had.any { it.key == k }) had.map { if (it.key == k) section else it } else had + section
        save()
        return section
    }

    /** Takes section [key] of [conversationId] away. @return false when it has none by that key. */
    fun remove(conversationId: String, key: String): Boolean {
        val had = of(conversationId)
        val left = had.filter { it.key != key.trim() }
        if (left.size == had.size) return false
        if (left.isEmpty()) byConversation.remove(conversationId) else byConversation[conversationId] = left
        save()
        return true
    }

    private fun save() {
        repo?.putString(SETTINGS_KEY, json.encodeToString(serializer, byConversation))
    }

    private companion object {
        const val SETTINGS_KEY = "panel.sections.v1"
        /** How long a conversation's sections are kept after the last one was written: a month, like closed work in the overview. */
        const val KEEP_MS = 31L * 24 * 60 * 60_000L
    }
}
