package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingNamedToolsTest {

    private val tools = listOf("web_fetch", "get_time_info", "eval_javascript", "write_text_file", "read_file", "read_tool_output")
    private val task = "1. Con web_fetch leggi.\n2. Con get_time_info la data.\n3. Con eval_javascript calcola.\n" +
        "4. Con write_text_file salva.\n5. Con read_file rileggi."

    private fun user(text: String) = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))
    private fun assistant(vararg called: String, answer: String? = null) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = called.mapIndexed { i, n -> UIMessagePart.Tool("c$i", n, "{}", listOf(UIMessagePart.Text("{}"))) } +
            listOfNotNull(answer?.let { UIMessagePart.Text(it) }),
    )

    @Test
    fun `early answer on the Vivo leaves the side-effect steps pending`() {
        val msgs = listOf(user(task), assistant("web_fetch", "get_time_info", "eval_javascript", answer = "Paolo Lipparelli | 1650 | 376"))
        assertEquals(listOf("write_text_file", "read_file"), pendingNamedTools(msgs, tools))
    }

    @Test
    fun `nothing pending when every named tool ran, before any call, or without a task`() {
        assertEquals(emptyList<String>(), pendingNamedTools(listOf(user(task), assistant(*tools.dropLast(1).toTypedArray())), tools))
        assertEquals(emptyList<String>(), pendingNamedTools(listOf(user(task), assistant(answer = "Non posso.")), tools))
        assertEquals(emptyList<String>(), pendingNamedTools(emptyList(), tools))
    }

    @Test
    fun `calls made for an earlier message do not count`() {
        val msgs = listOf(user(task), assistant(*tools.toTypedArray()), user("Ora con write_text_file e read_file rifai il file."),
            assistant("write_text_file", answer = "Fatto"))
        assertEquals(listOf("read_file"), pendingNamedTools(msgs, tools))
    }
}
