/**
 * Tests for [ClaudeHooks]: connecting merges Lunamux's hook entries into a
 * Claude Code settings file without disturbing anything else, and
 * disconnecting removes exactly those entries. Every test works on a
 * throwaway file, never the user's real settings.
 *
 * @see ClaudeHooks
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeHooksTest {

    private val dir = File.createTempFile("lunamux-claude-hooks", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val file = File(dir, "settings.json")

    private fun parsed() = Json.parseToJsonElement(file.readText()).jsonObject

    private val userSettings = """
        {
          "model": "opus",
          "permissions": { "allow": ["Bash(ls)"] },
          "hooks": {
            "PreToolUse": [
              { "matcher": "Bash", "hooks": [ { "type": "command", "command": "echo mine" } ] }
            ],
            "Stop": [
              { "hooks": [ { "type": "command", "command": "say done" } ] }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `connect creates the file when there is none`() {
        val hooks = ClaudeHooks(file)
        assertFalse(hooks.connected.value)
        assertNull(hooks.connect())
        assertTrue(hooks.connected.value)
        val written = parsed()["hooks"]!!.jsonObject
        assertEquals(ClaudeHooks.EVENTS.toSet(), written.keys)
        assertEquals("*", written["PreToolUse"]!!.jsonArray.single().jsonObject["matcher"]!!.toString().trim('"'))
    }

    @Test
    fun `connect keeps other keys and other hooks and backs the old file up`() {
        file.writeText(userSettings)
        val hooks = ClaudeHooks(file)
        assertNull(hooks.connect())

        val root = parsed()
        assertEquals("\"opus\"", root["model"].toString())
        assertNotNull(root["permissions"])
        val pre = root["hooks"]!!.jsonObject["PreToolUse"]!!.jsonArray
        assertEquals(2, pre.size)
        assertTrue(pre.first().toString().contains("echo mine"))
        assertTrue(root["hooks"]!!.jsonObject["Stop"]!!.jsonArray.first().toString().contains("say done"))
        assertEquals(userSettings, File(file.path + ".bak").readText())
        assertTrue(hooks.connected.value)
    }

    @Test
    fun `the backup keeps the file from before Lunamux across connect and disconnect`() {
        file.writeText(userSettings)
        val hooks = ClaudeHooks(file)
        assertNull(hooks.connect())
        assertNull(hooks.disconnect())
        assertEquals(userSettings, File(file.path + ".bak").readText())
    }

    @Test
    fun `a symlinked settings file stays a symlink and the target receives the change`() {
        val real = File(dir, "dotfiles/claude-settings.json").apply { parentFile.mkdirs(); writeText(userSettings) }
        java.nio.file.Files.createSymbolicLink(file.toPath(), real.toPath())
        val hooks = ClaudeHooks(file)

        assertNull(hooks.connect())
        assertTrue(java.nio.file.Files.isSymbolicLink(file.toPath()))
        assertTrue(real.readText().contains("lunamux-agent-events"))
        assertTrue(hooks.connected.value)

        assertNull(hooks.disconnect())
        assertTrue(java.nio.file.Files.isSymbolicLink(file.toPath()))
        assertFalse(real.readText().contains("lunamux-agent-events"))
    }

    @Test
    fun `connecting twice does not duplicate the entries`() {
        file.writeText(userSettings)
        val hooks = ClaudeHooks(file)
        hooks.connect()
        val once = file.readText()
        hooks.connect()
        assertEquals(once, file.readText())
    }

    @Test
    fun `disconnect removes only the Lunamux entries`() {
        file.writeText(userSettings)
        val hooks = ClaudeHooks(file)
        hooks.connect()
        assertNull(hooks.disconnect())

        assertFalse(hooks.connected.value)
        assertEquals(Json.parseToJsonElement(userSettings), Json.parseToJsonElement(file.readText()))
    }

    @Test
    fun `disconnect drops the hooks key when nothing else was in it`() {
        file.writeText("""{"model":"opus"}""")
        val hooks = ClaudeHooks(file)
        hooks.connect()
        hooks.disconnect()
        assertEquals(Json.parseToJsonElement("""{"model":"opus"}"""), Json.parseToJsonElement(file.readText()))
    }

    @Test
    fun `a Lunamux command sharing a group with a foreign one is removed alone`() {
        file.writeText(
            """{"hooks":{"Stop":[{"hooks":[{"type":"command","command":"say done"},
            {"type":"command","command":"curl x ${ClaudeHooks.MARKER}"}]}]}}""",
        )
        val hooks = ClaudeHooks(file)
        assertNull(hooks.disconnect())
        val stop: JsonArray = parsed()["hooks"]!!.jsonObject["Stop"]!!.jsonArray
        assertEquals(1, stop.size)
        assertEquals(1, stop.single().jsonObject["hooks"]!!.jsonArray.size)
        assertTrue(stop.toString().contains("say done"))
    }

    @Test
    fun `disconnect with no file does nothing`() {
        assertNull(ClaudeHooks(file).disconnect())
        assertFalse(file.exists())
    }

    @Test
    fun `invalid JSON is refused and left alone`() {
        file.writeText("{ not json")
        val hooks = ClaudeHooks(file)
        assertNotNull(hooks.connect())
        assertNotNull(hooks.disconnect())
        assertEquals("{ not json", file.readText())
        assertFalse(File(file.path + ".bak").exists())
    }

    @Test
    fun `connected is false while any event is missing and refresh sees hand edits`() {
        val hooks = ClaudeHooks(file)
        hooks.connect()
        file.writeText(file.readText().replace(ClaudeHooks.MARKER, "# other"))
        assertFalse(hooks.refresh())
    }

    @Test
    fun `the hook command does nothing outside Lunamux`() {
        val out = ProcessBuilder("sh", "-c", ClaudeHooks.COMMAND)
            .redirectErrorStream(true)
            .apply { environment().remove("LUNAMUX_EVENTS_URL") }
            .start()
        out.outputStream.close()
        val text = out.inputStream.bufferedReader().readText()
        assertEquals(0, out.waitFor())
        assertEquals("", text)
    }

    @Test
    fun `events cover the whole lifecycle`() {
        assertTrue(ClaudeHooks.EVENTS.containsAll(listOf("SessionStart", "UserPromptSubmit", "PreToolUse", "Notification", "Stop", "SessionEnd")))
        assertTrue(JsonObject(emptyMap()).isEmpty())
    }
}
