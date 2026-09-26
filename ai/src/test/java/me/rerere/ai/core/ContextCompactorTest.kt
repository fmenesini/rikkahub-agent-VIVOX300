package me.rerere.ai.core

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactorTest {

    private fun user(t: String) = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(t)))
    private fun model(vararg p: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = p.toList())
    private fun system(t: String) = UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(t)))
    private fun step(i: Int, out: String, name: String = "list_dir") =
        UIMessagePart.Tool("c$i", name, """{"path":"dir_$i"}""", listOf(UIMessagePart.Text(out)))

    private fun allText(r: ContextCompactor.Result) = r.messages.joinToString("\n") { m ->
        m.parts.joinToString("\n") { p ->
            when (p) {
                is UIMessagePart.Text -> p.text
                is UIMessagePart.Tool -> p.toolName + p.input + p.output.filterIsInstance<UIMessagePart.Text>().joinToString { it.text }
                else -> ""
            }
        }
    }

    @Test
    fun `a conversation that fits comes back unchanged`() {
        val msgs = listOf(system("sys"), user("hi"), model(UIMessagePart.Text("hello")), user("list files"),
            model(step(1, "a.txt\nb.txt")))
        val r = ContextCompactor.compact(msgs, 12_000, retrievable = true)
        assertEquals(msgs, r.messages)
        assertEquals(0, r.droppedParts)
    }

    @Test
    fun `one assistant message holding a long tool loop is cut to the budget`() {
        // The local providers only dropped whole messages, and a running task is ONE message:
        // this history reached the native engine whole (~90k tokens) and could crash it.
        val loop = model(*(1..60).map { step(it, "R$it:" + "x".repeat(6000)) }.toTypedArray())
        val msgs = listOf(user("Scan every directory"), loop)
        val r = ContextCompactor.compact(msgs, 12_000, retrievable = true)
        assertTrue("${r.estimatedTokens}", r.estimatedTokens <= 12_000)
        val text = allText(r)
        assertTrue(text.contains("Scan every directory"))
        assertTrue("newest step kept", text.contains("R60:"))
        assertTrue(text.contains("[earlier steps omitted]"))
        assertTrue(text, Regex("""- list_dir x\d+: dir_1, """).containsMatchIn(text))
        assertTrue(r.droppedParts > 0)
    }

    @Test
    fun `clip sizes grow with the budget`() {
        val big = "HEAD" + "m".repeat(8000) + "MIDDLE" + "m".repeat(0) + "TAIL"
        val msgs = listOf(user("read it"), model(step(1, big)))
        val small = ContextCompactor.compact(msgs, 3600, retrievable = true)
        val large = ContextCompactor.compact(msgs, 16_000, retrievable = true)
        assertTrue("4k window clips the result", allText(small).contains("chars cut; read_tool_output id=c1 offset="))
        assertTrue("16k window keeps an 8k result whole", allText(large).contains("MIDDLE"))
        assertEquals(0, large.clippedParts)
    }

    @Test
    fun `system messages are kept and the result opens with the user`() {
        val msgs = listOf(system("You are X")) +
            (1..30).flatMap { listOf(user("q$it " + "y".repeat(3000)), model(UIMessagePart.Text("a$it " + "z".repeat(3000)))) } +
            user("final task")
        val r = ContextCompactor.compact(msgs, 4000, retrievable = false)
        assertEquals(MessageRole.SYSTEM, r.messages.first().role)
        assertEquals("You are X", (r.messages.first().parts.single() as UIMessagePart.Text).text)
        assertEquals(MessageRole.USER, r.messages[1].role)
        assertTrue(allText(r).contains("final task"))
        assertTrue(r.estimatedTokens <= 4000)
    }

    @Test
    fun `every step of a 200-step task stays inside budgets from 4k to 28k`() {
        for (budget in listOf(3600, 12_000, 28_000)) {
            val parts = mutableListOf<UIMessagePart>()
            for (i in 1..200) {
                parts += step(i, "R$i:" + "w".repeat(listOf(80, 900, 6000, 30_000)[i % 4]))
                if (i % 9 == 0) parts += UIMessagePart.Text("Note: found something at step $i")
                val r = ContextCompactor.compact(listOf(user("Audit the project"), model(*parts.toTypedArray())), budget, retrievable = true)
                assertTrue("budget $budget step $i: ${r.estimatedTokens}", r.estimatedTokens <= budget)
                val text = allText(r)
                assertTrue("budget $budget step $i lost the task", text.contains("Audit the project"))
                assertTrue("budget $budget step $i lost the newest result", text.contains("R$i:"))
            }
        }
    }

    @Test
    fun `the model's notes survive when their steps are dropped`() {
        val parts = mutableListOf<UIMessagePart>(step(1, "o".repeat(5000)), UIMessagePart.Text("Note: TODO is in dir_17"))
        parts += (2..40).map { step(it, "p".repeat(5000)) }
        val r = ContextCompactor.compact(listOf(user("find the TODO"), model(*parts.toTypedArray())), 6000, retrievable = true)
        assertTrue(allText(r), allText(r).contains("(your note) Note: TODO is in dir_17"))
        assertFalse(allText(r).contains("o".repeat(5000)))
    }
}
