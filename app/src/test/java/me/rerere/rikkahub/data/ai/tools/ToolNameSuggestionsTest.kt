package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolNameSuggestionsTest {
    // The tool list of the Vivo test A run, plus write_text_file (now part of "Files").
    private val available = listOf(
        "memory_tool", "eval_javascript", "get_time_info", "clipboard_tool", "text_to_speech", "ask_user",
        "list_files", "read_file", "write_binary_file", "write_text_file", "delete_file", "move_file", "copy_file",
        "create_directory", "file_info", "find_files", "show_image", "open_file", "web_fetch", "web_extract",
        "read_tool_output",
    )

    @Test fun `a guessed name gets the real ones first`() {
        val s = suggestToolNames("write_file", available)
        assertTrue(s.toString(), s.take(2).toSet() == setOf("write_text_file", "write_binary_file"))
    }

    @Test fun `typos are caught by edit distance`() {
        assertEquals("web_fetch", suggestToolNames("web_fecth", available).first())
        assertEquals("read_file", suggestToolNames("readfile", available).first())
    }

    @Test fun `unrelated names get no suggestion`() {
        assertEquals(emptyList<String>(), suggestToolNames("launch_rocket", available))
        assertEquals(emptyList<String>(), suggestToolNames("", available))
    }
}
