package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.RuntimeTools
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.File

// Tool-result virtualization: large tool output lives outside the prompt (in the message,
// or spilled to <filesDir>/tool_outputs when very large) and the model pulls the part it
// needs through read_tool_output. Android-free so it runs in the host tests.

object ToolOutputStore {
    /** Chars per page: fits the AICore prompt's newest-result slice without a further cut. */
    const val READ_WINDOW_CHARS = 1500
    const val MAX_QUERY_HITS = 10
    private const val HIT_LINE_CHARS = 160

    /** Header the loop puts in front of a spilled result's preview. */
    const val SPILL_HEADER = "[Tool output truncated:"

    private val UNSAFE = Regex("[^A-Za-z0-9_.-]")

    /**
     * File for [toolCallId] inside [dir], or null when the id cannot name a file safely.
     * Call ids come from providers; they are sanitised and the result is checked to stay
     * directly inside [dir] so an id can never point the writer or reader elsewhere.
     */
    fun spillFile(dir: File, toolCallId: String): File? {
        val name = toolCallId.replace(UNSAFE, "_").take(120)
        if (name.isEmpty() || name.all { it == '.' }) return null
        val file = File(dir, "$name.txt")
        val root = dir.canonicalFile
        return file.takeIf { it.canonicalFile.parentFile == root }
    }

    /** Results above this many chars are spilled to the store and replaced by a preview. */
    const val MAX_INLINE_CHARS = 32 * 1024
    const val PREVIEW_CHARS = 4 * 1024

    /**
     * Keeps [output] as is when it is small; otherwise stores the full text in [dir] and
     * returns a preview that tells the model how to read the rest. When the text cannot be
     * stored the output is returned unchanged: cutting it then would lose data for good.
     * [shellCopyDir] (the shell-mounted /tool_outputs) also gets a copy, for chats that have
     * the workspace shell; [dir] itself must not be reachable from the shell or file tools.
     */
    fun virtualize(
        toolCallId: String,
        output: List<UIMessagePart>,
        dir: File,
        shellCopyDir: File?,
        maxInlineChars: Int = MAX_INLINE_CHARS,
        previewChars: Int = PREVIEW_CHARS,
    ): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val totalChars = textParts.sumOf { it.text.length }
        if (totalChars <= maxInlineChars) return output
        val fullText = textParts.joinToString("\n") { it.text }
        val file = spillFile(dir, toolCallId) ?: return output
        runCatching {
            dir.mkdirs()
            file.writeText(fullText)
        }.getOrElse { return output }
        val shellCopy = shellCopyDir?.let { spillFile(it, toolCallId) }?.takeIf {
            runCatching { shellCopyDir.mkdirs(); it.writeText(fullText) }.isSuccess
        }
        val preview = fullText.take(previewChars)
        val text = buildString {
            appendLine("$SPILL_HEADER $totalChars characters total]")
            appendLine("Read more: ${RuntimeTools.READ_TOOL_OUTPUT} with id=$toolCallId and offset=${preview.length}, or query=\"<text>\" to find lines.")
            if (shellCopy != null) appendLine("Shell copy: /tool_outputs/${shellCopy.name} (grep it; cat prints everything)")
            appendLine()
            append(preview)
        }
        return listOf(UIMessagePart.Text(text)) + output.filter { it !is UIMessagePart.Text }
    }

    /** Pages [text] from [offset]; the header says where the next page starts. */
    fun page(id: String, text: String, offset: Int, note: String? = null): String {
        val start = offset.coerceIn(0, text.length)
        val end = minOf(text.length, start + READ_WINDOW_CHARS)
        return buildString {
            append("[id=$id chars $start-$end of ${text.length}; ")
            append(if (end < text.length) "next: offset=$end]" else "end of output]")
            if (note != null) append("\n[$note]")
            append('\n').append(text, start, end)
        }
    }

    /** Case-insensitive line search; each hit carries its line number and char offset. */
    fun search(id: String, text: String, query: String, note: String? = null): String {
        val hits = mutableListOf<String>()
        var total = 0
        var lineStart = 0
        var lineNo = 1
        for (line in text.split('\n')) {
            if (line.contains(query, ignoreCase = true)) {
                total++
                if (hits.size < MAX_QUERY_HITS) {
                    val at = line.indexOf(query, ignoreCase = true)
                    val from = (at - HIT_LINE_CHARS / 3).coerceAtLeast(0)
                    val snippet = line.substring(from, minOf(line.length, from + HIT_LINE_CHARS))
                    hits += "L$lineNo @${lineStart + from}: $snippet"
                }
            }
            lineStart += line.length + 1
            lineNo++
        }
        return buildString {
            append("[id=$id query \"$query\": $total matching lines")
            if (total > hits.size) append(", first ${hits.size} shown")
            append("; read one with offset=<@value>]")
            if (note != null) append("\n[$note]")
            hits.forEach { append('\n').append(it) }
        }
    }
}

/**
 * A tool result longer than this may be clipped in a small-context prompt (the AICore prompt
 * keeps 400 chars of older results), so the loop offers read_tool_output to get it back.
 */
private const val READ_TOOL_OUTPUT_MIN_CHARS = 400

/** True when some finished tool result in [messages] is long enough to be clipped. */
internal fun hasRetrievableToolOutput(messages: List<UIMessage>): Boolean = messages.any { m ->
    m.parts.any { p ->
        p is UIMessagePart.Tool && p.isExecuted && p.toolName != RuntimeTools.READ_TOOL_OUTPUT &&
            p.output.sumOf { (it as? UIMessagePart.Text)?.text?.length ?: 0 } > READ_TOOL_OUTPUT_MIN_CHARS
    }
}

/**
 * Builds read_tool_output for one step of the loop. It reads only results of tool calls in
 * [messages] (this conversation, this request): a call id from another chat, a sub-agent or
 * a guess is refused even if its spill file exists. Read-only and limited to text the model
 * already received in full or in part, so it needs no approval.
 */
fun buildReadToolOutputTool(messages: List<UIMessage>, spillDir: File?): Tool = Tool(
    name = RuntimeTools.READ_TOOL_OUTPUT,
    description = "Read an earlier tool result in full: by offset (pages of " +
        "${ToolOutputStore.READ_WINDOW_CHARS} chars) or find lines with query.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Tool call id shown in the cut marker or step list")
                })
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "Char offset to start reading from (default 0)")
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to find; returns matching lines with their offsets")
                })
            },
            required = listOf("id"),
        )
    },
    needsApproval = { false },
    execute = { input -> listOf(UIMessagePart.Text(readToolOutput(messages, spillDir, input))) },
)

internal fun readToolOutput(messages: List<UIMessage>, spillDir: File?, input: JsonElement): String {
    val args = input as? JsonObject ?: return envelope("invalid_input", "expected an object with id")
    val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (id.isEmpty()) return envelope("invalid_input", "id is required")
    val part = messages.asReversed().asSequence()
        .flatMap { it.parts.asSequence() }
        .filterIsInstance<UIMessagePart.Tool>()
        .firstOrNull { it.toolCallId == id && it.isExecuted }
        ?: return envelope("unknown_id", "no finished tool call with id '$id' in this conversation")
    if (part.toolName == RuntimeTools.READ_TOOL_OUTPUT) {
        return envelope("unknown_id", "'$id' is a read_tool_output result; read the original call id instead")
    }
    val inMessage = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    var note: String? = null
    val text = if (inMessage.startsWith(ToolOutputStore.SPILL_HEADER)) {
        val file = spillDir?.let { ToolOutputStore.spillFile(it, id) }
        val full = file?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        if (full == null) note = "full output no longer stored (app restarted); only the saved preview is available"
        full ?: inMessage
    } else inMessage

    val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (query.isNotEmpty()) {
        if (query.length < 2) return envelope("invalid_input", "query needs at least 2 characters")
        return ToolOutputStore.search(id, text, query, note)
    }
    val offset = args["offset"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0
    if (offset < 0 || offset > text.length) {
        return envelope("invalid_offset", "offset must be between 0 and ${text.length}")
    }
    return ToolOutputStore.page(id, text, offset, note)
}

private fun envelope(error: String, detail: String): String = buildJsonObject {
    put("error", error)
    put("detail", detail)
}.toString()
