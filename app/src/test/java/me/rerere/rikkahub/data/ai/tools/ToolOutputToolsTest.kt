package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.RuntimeTools
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ToolOutputToolsTest {

    private fun call(id: String, output: String, name: String = "list_files") =
        UIMessagePart.Tool(id, name, "{}", listOf(UIMessagePart.Text(output)))

    private fun chat(vararg tools: UIMessagePart.Tool) = listOf(
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("task"))),
        UIMessage(role = MessageRole.ASSISTANT, parts = tools.toList()),
    )

    private fun args(s: String): JsonElement = Json.parseToJsonElement(s)

    private fun run(messages: List<UIMessage>, dir: File?, input: String): String = runBlocking {
        val out = buildReadToolOutputTool(messages, dir).execute(args(input))
        (out.single() as UIMessagePart.Text).text
    }

    private val big = buildString {
        for (i in 1..2000) append("line $i ").append(if (i == 1234) "SECRET_TOKEN_FOUND" else "filler").append('\n')
    }

    @Test
    fun `pages through a result in windows and says where the next one starts`() {
        val msgs = chat(call("c1", big))
        val first = run(msgs, null, """{"id":"c1"}""")
        assertTrue(first, first.startsWith("[id=c1 chars 0-${ToolOutputStore.READ_WINDOW_CHARS} of ${big.length}; next: offset=${ToolOutputStore.READ_WINDOW_CHARS}]"))
        assertEquals(big.substring(0, ToolOutputStore.READ_WINDOW_CHARS), first.substringAfter("]\n"))
        // Walking the pages rebuilds the whole text: nothing is lost between windows.
        val rebuilt = StringBuilder()
        var offset = 0
        while (true) {
            val page = run(msgs, null, """{"id":"c1","offset":$offset}""")
            rebuilt.append(page.substringAfter("]\n"))
            val next = Regex("next: offset=(\\d+)").find(page) ?: break
            offset = next.groupValues[1].toInt()
        }
        assertEquals(big, rebuilt.toString())
    }

    @Test
    fun `query finds lines with offsets that lead back to the text`() {
        val msgs = chat(call("c1", big))
        val res = run(msgs, null, """{"id":"c1","query":"secret_token"}""")
        assertTrue(res, res.contains("1 matching lines"))
        val hit = Regex("L1234 @(\\d+): ").find(res)!!
        val at = hit.groupValues[1].toInt()
        assertTrue(big.substring(at).startsWith(res.substringAfter(hit.value).take(20)))
        val many = run(msgs, null, """{"id":"c1","query":"filler"}""")
        assertTrue(many, many.contains("1999 matching lines, first ${ToolOutputStore.MAX_QUERY_HITS} shown"))
    }

    @Test
    fun `only call ids of this conversation can be read`() {
        val dir = Files.createTempDirectory("tool_outputs").toFile()
        // A spilled file from another chat exists on disk, but its id is not in these messages.
        File(dir, "other-chat-call.txt").writeText("other chat's private output")
        val res = run(chat(call("c1", "mine")), dir, """{"id":"other-chat-call"}""")
        assertTrue(res, res.contains("\"error\":\"unknown_id\""))
        assertFalse(res.contains("private output"))
    }

    @Test
    fun `hostile ids never name a file outside the store`() {
        val dir = Files.createTempDirectory("tool_outputs").toFile()
        for (id in listOf("../../databases/x", "..", ".", "/etc/passwd", "a/../../b", "")) {
            val f = ToolOutputStore.spillFile(dir, id)
            if (f != null) assertEquals("id '$id'", dir.canonicalFile, f.canonicalFile.parentFile)
        }
        assertNull(ToolOutputStore.spillFile(dir, ".."))
        assertNull(ToolOutputStore.spillFile(dir, ""))
        // Even when such an id is a real call in the chat, the read stays in the store.
        File(dir.parentFile, "outside.txt").writeText("OUTSIDE")
        val spilled = "${ToolOutputStore.SPILL_HEADER} 99 characters total]\npreview"
        val res = run(chat(call("../outside", spilled)), dir, """{"id":"../outside"}""")
        assertFalse(res, res.contains("OUTSIDE"))
    }

    @Test
    fun `spilled output is read from the store, and says so when it is gone`() {
        val dir = Files.createTempDirectory("tool_outputs").toFile()
        val preview = "${ToolOutputStore.SPILL_HEADER} ${big.length} characters total]\n" + big.take(100)
        ToolOutputStore.spillFile(dir, "c9")!!.writeText(big)
        val fromStore = run(chat(call("c9", preview)), dir, """{"id":"c9","query":"SECRET_TOKEN"}""")
        assertTrue(fromStore, fromStore.contains("L1234"))
        // App restarted: the store is wiped at startup. The model is told, not misled.
        ToolOutputStore.spillFile(dir, "c9")!!.delete()
        val gone = run(chat(call("c9", preview)), dir, """{"id":"c9"}""")
        assertTrue(gone, gone.contains("no longer stored"))
    }

    @Test
    fun `bad input gets an error envelope the model can act on`() {
        val msgs = chat(call("c1", "short output"))
        assertTrue(run(msgs, null, """{}""").contains("invalid_input"))
        assertTrue(run(msgs, null, """{"id":"c1","offset":999}""").contains("invalid_offset"))
        assertTrue(run(msgs, null, """{"id":"c1","offset":-1}""").contains("invalid_offset"))
        assertTrue(run(msgs, null, """{"id":"c1","query":"x"}""").contains("invalid_input"))
        assertTrue(run(msgs, null, """{"id":"c1","offset":"4"}""").startsWith("[id=c1 chars 4-12"))
    }

    @Test
    fun `reading a read result is refused and the tool needs no approval`() {
        val msgs = chat(call("r1", "[id=c1 chars 0-10 of 10; end of output]\nabc", name = RuntimeTools.READ_TOOL_OUTPUT))
        assertTrue(run(msgs, null, """{"id":"r1"}""").contains("unknown_id"))
        val tool = buildReadToolOutputTool(msgs, null)
        assertFalse(tool.needsApproval(args("""{"id":"r1"}""")))
        assertEquals(RuntimeTools.READ_TOOL_OUTPUT, tool.name)
    }

    @Test
    fun `virtualize spills big output to the private store and copies to the shell only when asked`() {
        val store = Files.createTempDirectory("store").toFile()
        val shell = Files.createTempDirectory("shell").toFile()
        val small = listOf(UIMessagePart.Text("small"))
        assertTrue(ToolOutputStore.virtualize("c1", small, store, shell) === small)

        val text = "x".repeat(ToolOutputStore.MAX_INLINE_CHARS + 10)
        val noShell = ToolOutputStore.virtualize("c2", listOf(UIMessagePart.Text(text)), store, null)
        val preview = (noShell.single() as UIMessagePart.Text).text
        assertTrue(preview.startsWith(ToolOutputStore.SPILL_HEADER))
        assertTrue(preview.contains("read_tool_output with id=c2 and offset=${ToolOutputStore.PREVIEW_CHARS}"))
        assertFalse("no shell hint without a shell copy", preview.contains("/tool_outputs/"))
        assertEquals(text, File(store, "c2.txt").readText())
        assertFalse(File(shell, "c2.txt").exists())

        val withShell = ToolOutputStore.virtualize("c3", listOf(UIMessagePart.Text(text)), store, shell)
        assertTrue((withShell.single() as UIMessagePart.Text).text.contains("/tool_outputs/c3.txt"))
        assertEquals(text, File(shell, "c3.txt").readText())
        // The whole round trip: the read tool finds the spilled text by id.
        val msgs = chat(call("c3", (withShell.single() as UIMessagePart.Text).text))
        assertTrue(run(msgs, store, """{"id":"c3","offset":${text.length - 5}}""").endsWith("xxxxx"))
    }

    @Test
    fun `virtualize keeps the output when it cannot be stored`() {
        val notADir = Files.createTempFile("store", ".bin").toFile() // a file: mkdirs/write fail
        val big = listOf(UIMessagePart.Text("y".repeat(ToolOutputStore.MAX_INLINE_CHARS + 1)))
        assertTrue(ToolOutputStore.virtualize("c1", big, notADir, null) === big)
    }

    @Test
    fun `read tool is offered only when a result is long enough to be clipped`() {
        assertFalse(hasRetrievableToolOutput(chat(call("c1", "short"))))
        assertTrue(hasRetrievableToolOutput(chat(call("c1", "z".repeat(500)))))
        // Its own results do not count, or it would keep offering itself.
        assertFalse(hasRetrievableToolOutput(chat(call("r1", "z".repeat(500), name = RuntimeTools.READ_TOOL_OUTPUT))))
    }

    @Test
    fun `reading an output its own tool truncated says so`() {
        val cut = """{"status":200,"extract_mode":"raw","body":"<!DOCTYPE html>...","body_truncated":true}"""
        val res = run(chat(call("w1", cut)), null, """{"id":"w1"}""")
        assertTrue(res, res.contains("cut it itself"))
        val whole = """{"status":200,"text":"hello","truncated":false}"""
        assertFalse(run(chat(call("w2", whole)), null, """{"id":"w2"}""").contains("cut it itself"))
    }

}
