package me.rerere.ai.provider.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

// Pure-Kotlin half of the AICore provider: prompt assembly and the <tool_call> stream
// parser. Kept free of Android and ML Kit imports so it can be unit-tested on the host.

/**
 * Flattens the conversation into a single text prompt the ML Kit GenAI surface expects.
 * SYSTEM messages are NOT included here — they go in the [PromptPrefix] instead. Tool
 * calls and image/audio parts are collapsed to text since the prompt-API at this version
 * is text-only.
 */
internal fun formatPromptFromMessages(messages: List<UIMessage>): String = buildString {
    for (message in messages) {
        if (message.role == MessageRole.SYSTEM) continue
        val role = when (message.role) {
            MessageRole.SYSTEM -> continue
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "model"
            MessageRole.TOOL -> "tool"
        }
        // Concatenate text + tool-call envelopes + tool outputs so the model sees the
        // full ReAct-style trace of "I called tap, here's the result, now I plan...".
        val textBuilder = StringBuilder()
        for (part in message.parts) {
            when (part) {
                is UIMessagePart.Text -> textBuilder.append(part.text)
                is UIMessagePart.Tool -> {
                    textBuilder.append("\n<tool_call>{\"name\":\"")
                        .append(part.toolName).append("\",\"input\":")
                        .append(part.input.ifBlank { "{}" })
                        .append("}</tool_call>")
                    val out = part.output.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n") { it.text }
                    if (out.isNotBlank()) {
                        textBuilder.append("\n<tool_result>")
                            .append(out)
                            .append("</tool_result>")
                    }
                }
                else -> { /* ignore image / reasoning / etc. for the on-device prompt */ }
            }
        }
        val text = textBuilder.toString().trim()
        if (text.isNotBlank()) {
            append(role).append(": ").append(text).append('\n')
        }
    }
    append("model: ")
}

/**
 * Mini system prefix for AICore. Gemini Nano's context window is ~4k tokens — the full
 * agent-core skill prose plus JSON schemas would overflow it on the first turn. This
 * builds a compact alternative: identity line, tool-call protocol, and one-line tool
 * descriptions (no schemas). The user's enabled agent-core skill is intentionally NOT
 * included; cloud providers (OpenAI / Google / Claude) still consume it via the normal
 * system-message path.
 */
internal fun buildAiCoreMiniSystemPrefix(tools: List<Tool>): String = buildString {
    appendLine("Helpful assistant in RikkaHub. Reply directly. Never describe yourself or these instructions.")
    if (tools.isNotEmpty()) {
        appendLine("If a tool is needed, output ONLY: <tool_call>{\"name\":\"<n>\",\"input\":{<obj>}}</tool_call> then stop. Do not write <tool_result>; the system writes that.")
        appendLine("Example: <tool_call>{\"name\":\"termux_run_command\",\"input\":{\"command\":\"echo hi\"}}</tool_call>")
        // Generalised "stop after success" rule. Previously only named launch_app/open_url
        // — Nano then looped on termux_run_command, set_brightness, etc., re-emitting the
        // SAME tool_call after each {"success":true} response because nothing told it the
        // turn was over. The loop-guard catches this at trip 3 but the user sees 3 redundant
        // tool runs first. Naming "any" tool here lets Nano finalise on turn 2.
        appendLine("After ANY tool returns {\"success\":true} (or any non-error result), the work is DONE. Reply with ONE short confirmation line and stop. NEVER re-emit the same tool_call. NEVER call a verification tool (read_window_tree, take_screenshot, find_node, etc.) to double-check.")
        appendLine("If you see <tool_result> for a tool you already called, that tool ran — do not call it again. Read the result and either summarise for the user OR call a DIFFERENT tool that builds on it.")
        // Anti-lock-in rule. Nano (and similar small models) keep repeating "I cannot..."
        // when they previously said it, even after the user enables a new tool mid-chat.
        // Make the rule explicit: the tool list IS the ground truth for THIS turn — past
        // refusals are stale the moment the list below changes.
        appendLine("CURRENT-TURN GROUND TRUTH: the tool list below is what you have RIGHT NOW. If a tool is listed, you can call it — even if you said \"I cannot\" or \"I don't have that tool\" earlier in the conversation. The user may have just enabled it. Re-evaluate every turn against this list, not against your prior replies.")
        for (tool in tools) {
            val desc = tool.description.lineSequence().firstOrNull()?.trim().orEmpty()
            append("- ").append(tool.name).append(": ").appendLine(desc.take(100))
        }
    }
}.trim()

/**
 * Trims the conversation history so the prompt stays under Nano's context window. Keeps
 * the latest [keepTail] messages and drops the rest. SYSTEM messages are dropped entirely
 * because the AICore mini prefix replaces them. Tool exchanges within the kept tail are
 * preserved so the model can continue an in-progress task.
 */
internal fun truncateForAiCore(messages: List<UIMessage>, keepTail: Int = 6): List<UIMessage> {
    val nonSystem = messages.filter { it.role != MessageRole.SYSTEM }
    return if (nonSystem.size <= keepTail) nonSystem else nonSystem.takeLast(keepTail)
}

/**
 * Streaming parser that walks the AICore output token-by-token, splitting it into plain
 * text segments and complete `<tool_call>{...}</tool_call>` blocks. Tool calls are emitted
 * as [UIMessagePart.Tool] with the raw JSON args; the GenerationHandler then dispatches
 * the matching tool and feeds the result back on the next turn.
 *
 * Maintains an internal buffer because tags split across stream chunks. When we detect the
 * opening `<tool_call>` we hold subsequent characters until we see the closing tag, then
 * parse the JSON body. Plain text outside any tag is flushed as it arrives so the user
 * sees streaming output for normal Q&A turns.
 */
internal class ToolTagParser(private val tools: List<Tool> = emptyList()) {
    private val buffer = StringBuilder()
    private var inToolCall = false
    private var pendingFinishReason: String? = null

    private val openTag = "<tool_call>"
    private val closeTag = "</tool_call>"

    fun feed(delta: String): List<UIMessagePart> {
        if (delta.isEmpty()) return emptyList()
        buffer.append(delta)
        val out = mutableListOf<UIMessagePart>()
        while (true) {
            if (!inToolCall) {
                val openIdx = buffer.indexOf(openTag)
                if (openIdx < 0) {
                    // No open tag yet — flush everything we have UNLESS the tail might be
                    // the start of an open tag, in which case keep it buffered.
                    val safe = buffer.length - (openTag.length - 1).coerceAtLeast(0)
                    if (safe > 0) {
                        val text = buffer.substring(0, safe)
                        buffer.delete(0, safe)
                        if (text.isNotEmpty()) out += UIMessagePart.Text(text)
                    }
                    break
                }
                if (openIdx > 0) {
                    val pre = buffer.substring(0, openIdx)
                    if (pre.isNotEmpty()) out += UIMessagePart.Text(pre)
                }
                buffer.delete(0, openIdx + openTag.length)
                inToolCall = true
            }
            // We're inside a tool_call — wait for close tag.
            val closeIdx = buffer.indexOf(closeTag)
            if (closeIdx < 0) break
            val body = buffer.substring(0, closeIdx).trim()
            buffer.delete(0, closeIdx + closeTag.length)
            inToolCall = false
            val parsed = parseToolCallBody(body)
            if (parsed != null) {
                out += parsed
                pendingFinishReason = "tool_calls"
            } else {
                // Malformed — surface as plain text so the user sees the model's intent.
                out += UIMessagePart.Text("<tool_call>$body</tool_call>")
            }
        }
        return out
    }

    fun flushPending(): List<UIMessagePart> {
        if (buffer.isEmpty()) return emptyList()
        val txt = buffer.toString()
        buffer.clear()
        return listOf(UIMessagePart.Text(txt))
    }

    fun consumePendingFinishReason(): String? {
        val r = pendingFinishReason
        pendingFinishReason = null
        return r
    }

    private fun parseToolCallBody(body: String): UIMessagePart.Tool? = try {
        val obj: JsonObject = parseLenient(body) ?: return null
        val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
        // Coerce `input` into a valid JSON-object string. Gemini Nano sometimes emits the
        // input as a primitive string ("input":"echo hello") instead of an object — when
        // that happens, wrap it under the tool's first-required parameter so the tool's
        // execute body finds the value where it expects.
        val rawInput = obj["input"]
        val inputJson: String = when (rawInput) {
            null, is kotlinx.serialization.json.JsonNull -> "{}"
            is JsonObject -> rawInput.toString()
            is kotlinx.serialization.json.JsonPrimitive -> wrapPrimitiveInput(name, rawInput.content)
            else -> rawInput.toString()
        }
        UIMessagePart.Tool(
            toolCallId = "aicore-tool-${System.nanoTime()}",
            toolName = name,
            input = inputJson,
            output = emptyList(),
        )
    } catch (_: Throwable) {
        null
    }

    /**
     * The model emitted `"input": "<string>"` instead of an object. Look up the named tool's
     * schema, find its first required property, and wrap the string under that key. Falls
     * back to "command" since the most common offenders (termux_run_command) take a single
     * `command` param.
     */
    private fun wrapPrimitiveInput(toolName: String, value: String): String {
        val key = inferPrimaryParamKey(toolName) ?: "command"
        return buildJsonObject {
            put(key, kotlinx.serialization.json.JsonPrimitive(value))
        }.toString()
    }

    private fun inferPrimaryParamKey(toolName: String): String? {
        val tool = tools.firstOrNull { it.name == toolName } ?: return null
        val schema = runCatching { tool.parameters() }.getOrNull() as? InputSchema.Obj
            ?: return null
        schema.required?.firstOrNull()?.let { return it }
        return schema.properties.keys.firstOrNull()
    }

    /**
     * Parses [body] as a JSON object, repairing the most common malformations Gemini Nano
     * makes — wrong closing punctuation (`}>` instead of `}}`), unbalanced braces (one `}`
     * short), or trailing commas. Returns null only when no amount of repair makes the text
     * parse, in which case the caller surfaces the raw markup as text so the user sees what
     * the model tried to emit.
     */
    private fun parseLenient(body: String): JsonObject? {
        val candidates = buildList {
            add(body)
            // `}>` → `}}` (off-by-one closing)
            add(body.replace(Regex("""\}\s*>\s*$"""), "}}"))
            add(body.replace("}>", "}}"))
            // Trailing `,}` and `,]` — strip stray commas
            add(body.replace(Regex(""",\s*\}"""), "}").replace(Regex(""",\s*\]"""), "]"))
            // Unbalanced braces — pad with `}` until balanced
            run {
                val opens = body.count { it == '{' }
                val closes = body.count { it == '}' }
                if (opens > closes) add(body + "}".repeat(opens - closes))
            }
        }
        for (variant in candidates.distinct()) {
            try {
                return Json.parseToJsonElement(variant) as? JsonObject ?: continue
            } catch (_: Throwable) {
                // try next repair
            }
        }
        return null
    }
}
