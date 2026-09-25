// Host scenario driver for the context manager: a minimal agent loop (same order as
// GenerationLoop: prompt -> model -> parse -> execute -> virtualize -> next step) around the
// REAL AICoreProvider (budgeted prompt, tool-tag parser), ToolOutputStore and
// read_tool_output. The "model" is a scripted policy that may only use what is in the prompt
// it receives, so a fact it reports proves the runtime put it there. The fake ML Kit
// rejects any request over the AICore input limit, like the real API.
// Not covered here: GenerationLoop itself (approval, LoopGuard, Android glue).
package agentscenario

import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.FakeAICore
import com.google.mlkit.genai.prompt.GenerateContentResponse
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.AICORE_INPUT_TOKEN_LIMIT
import me.rerere.ai.provider.providers.AICoreProvider
import me.rerere.ai.provider.AICORE_NANO_FULL_MODEL
import me.rerere.ai.provider.providers.estimateAiCoreTokens
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolOutputStore
import me.rerere.rikkahub.data.ai.tools.buildReadToolOutputTool
import me.rerere.rikkahub.data.ai.tools.hasRetrievableToolOutput
import java.io.File
import java.nio.file.Files

class Ctx : android.content.Context() {
    override val packageName = "x"; override val packageManager = android.content.PackageManager()
    override fun startActivity(i: android.content.Intent) {}; override fun getSystemService(n: String): Any? = null
}

class Run(val answer: String?, val requests: Int, val maxTokens: Int, val prompts: List<String>, val toolCalls: List<String>)

fun tool(name: String, desc: String, body: (JsonObject) -> String) =
    Tool(name = name, description = desc, execute = { listOf(UIMessagePart.Text(body(it as JsonObject))) })

/** Agent loop around the real provider; [policy] sees exactly the prompt AICore would get. */
fun agent(task: String, baseTools: List<Tool>, maxSteps: Int, policy: (String) -> String): Run = runBlocking {
    val dir = Files.createTempDirectory("tool_outputs").toFile()
    val provider = AICoreProvider(Ctx())
    val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(task)))
    val parts = mutableListOf<UIMessagePart>()
    val prompts = mutableListOf<String>()
    val calls = mutableListOf<String>()
    var maxTokens = 0
    FakeAICore.requests.clear(); FakeAICore.script.clear()
    repeat(maxSteps) {
        val history = if (parts.isEmpty()) listOf(user) else listOf(user, UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()))
        val tools = baseTools + if (hasRetrievableToolOutput(history)) listOf(buildReadToolOutputTool(history, dir)) else emptyList()
        FakeAICore.script += { r ->
            val full = (r.prefix ?: "") + "\n" + r.prompt
            val tokens = estimateAiCoreTokens(r.prefix ?: "") + estimateAiCoreTokens(r.prompt)
            maxTokens = maxOf(maxTokens, tokens)
            prompts += full
            flow {
                if (tokens > AICORE_INPUT_TOKEN_LIMIT) error("Input exceeds the token limit ($tokens)")
                emit(GenerateContentResponse(listOf(Candidate(policy(full), Candidate.FinishReason.STOP))))
            }
        }
        val out = provider.streamText(ProviderSetting.AICore(), history, TextGenerationParams(model = AICORE_NANO_FULL_MODEL, tools = tools)).toList()
        val text = out.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text }.trim()
        val starts = out.filterIsInstance<StreamChunk.ToolCallStart>()
        if (starts.isEmpty()) return@runBlocking Run(text, FakeAICore.requests.size, maxTokens, prompts, calls)
        if (text.isNotEmpty()) parts += UIMessagePart.Text(text)
        for (s in starts) {
            val input = out.filterIsInstance<StreamChunk.ToolCallDelta>().filter { it.id == s.id }.joinToString("") { it.inputDelta }
            calls += "${s.toolName} $input"
            val t = tools.firstOrNull { it.name == s.toolName }
            val output = t?.execute(Json.parseToJsonElement(input))
                ?: listOf(UIMessagePart.Text("""{"error":"tool_not_found"}"""))
            parts += UIMessagePart.Tool(s.id, s.toolName, input, ToolOutputStore.virtualize(s.id, output, dir, shellCopyDir = null))
        }
    }
    Run(null, FakeAICore.requests.size, maxTokens, prompts, calls)
}

fun call(name: String, input: String) = "<tool_call>{\"name\":\"$name\",\"input\":$input}</tool_call>"

// Nano-like policy for "find a value in a big file": read, then follow whatever retrieval
// hint the runtime put in the prompt, then answer from what the prompt shows.
fun needlePolicy(key: String): (String) -> String = { p ->
    val transcript = p.substringAfter("\nuser:") // the system prefix mentions the tags too
    val found = Regex("$key=(\\w+)").find(transcript)
    val hint = Regex("read_tool_output (?:with )?id=([\\w.:-]+)").find(transcript)
    when {
        found != null -> "The $key is ${found.groupValues[1]}."
        "<tool_result>" !in transcript -> call("read_file", """{"path":"logs/app.log"}""")
        hint != null && "matching lines" !in transcript -> call("read_tool_output", """{"id":"${hint.groupValues[1]}","query":"$key"}""")
        else -> "I could not find $key."
    }
}

fun bigLog(lines: Int, needleAt: Int) = buildString {
    for (i in 1..lines) append(if (i == needleAt) "cfg db_password=hunter42 loaded\n" else "2026-09-25 INFO worker $i heartbeat ok\n")
}

var failures = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS " else "FAIL ") + name + if (!ok && detail.isNotEmpty()) " -- $detail" else "")
    if (!ok) failures++
}

fun main() {
    // A. 70 KB result: spilled to the store, preview in the message, needle only on disk.
    run {
        val log = bigLog(1800, 900)
        val r = agent("Find db_password in logs/app.log", listOf(tool("read_file", "Read a file") { log }), 6, needlePolicy("db_password"))
        check("A spilled 70KB: answer uses the value found on disk", r.answer == "The db_password is hunter42.", "${r.answer} calls=${r.toolCalls}")
        check("A three model requests (read, search, answer)", r.requests == 3, "requests=${r.requests}")
        check("A needle was not in the prompt before the search", r.prompts.size == 3 && "hunter42" !in r.prompts[1])
        check("A read_tool_output offered only once a result needs it", "read_tool_output(" !in r.prompts[0] && "read_tool_output(" in r.prompts[1])
        check("A every request inside the AICore limit", r.maxTokens <= AICORE_INPUT_TOKEN_LIMIT, "max=${r.maxTokens}")
    }
    // B. 20 KB result: kept in the message, clipped in the prompt, cut marker leads back.
    run {
        val log = bigLog(500, 260)
        val r = agent("Find db_password in logs/app.log", listOf(tool("read_file", "Read a file") { log }), 6, needlePolicy("db_password"))
        check("B clipped 20KB: cut marker names id + offset", r.prompts.getOrNull(1)?.contains(Regex("chars cut; read_tool_output id=\\S+ offset=\\d+")) == true)
        check("B answer uses the value from the clipped middle", r.answer == "The db_password is hunter42.", "${r.answer} calls=${r.toolCalls}")
        check("B every request inside the AICore limit", r.maxTokens <= AICORE_INPUT_TOKEN_LIMIT, "max=${r.maxTokens}")
    }
    // C. 45-step task with 5 KB results: history far beyond the window. The model must not
    // redo steps it can no longer see, and must still know what it concluded early on.
    run {
        val dirs = 45
        val listDir = tool("list_dir", "List a directory") { a ->
            val d = a["path"]!!.jsonPrimitive.content
            buildString { for (i in 1..120) append("$d/file_$i.kt ${if (d == "dir_17" && i == 60) "TODO" else "ok"}\n") }
        }
        // Everything after the task line: the task itself names dir_1 and dir_$dirs.
        fun seen(p: String, n: Int) = Regex("\\bdir_$n\\b").containsMatchIn(p.substringAfter("\nuser:").substringAfter("\n"))
        val r = agent("Scan dir_1 to dir_$dirs and tell me which one has a TODO", listOf(listDir), 80) { p ->
            val latest = p.substringAfter("\nuser:").substringAfterLast("<tool_result>", "")
            val todoDir = Regex("(dir_\\d+)/file_\\d+\\.kt TODO").find(latest)?.groupValues?.get(1)
            val next = (1..dirs).firstOrNull { !seen(p, it) }
            val note = if (todoDir != null) "Note: TODO in $todoDir.\n" else ""
            when {
                next != null -> note + call("list_dir", """{"path":"dir_$next"}""")
                else -> Regex("TODO in (dir_\\d+)").find(p)?.let { "Done: the TODO is in ${it.groupValues[1]}." } ?: "Done, no TODO found."
            }
        }
        val listed = r.toolCalls.map { Regex("dir_\\d+").find(it)!!.value }
        check("C no directory scanned twice (ledger keeps dropped steps visible)", listed.size == listed.toSet().size, "dupes=${listed.groupingBy { it }.eachCount().filter { it.value > 1 }}")
        check("C all $dirs directories scanned", listed.toSet().size == dirs, "scanned=${listed.size}")
        check("C finishes with the finding made 28 steps earlier", r.answer == "Done: the TODO is in dir_17.", "${r.answer}")
        check("C history was actually cut (steps omitted)", r.prompts.last().contains("[earlier steps omitted]"))
        check("C every request inside the AICore limit", r.maxTokens <= AICORE_INPUT_TOKEN_LIMIT, "max=${r.maxTokens}")
        println("   C: ${r.requests} requests, max prompt ${r.maxTokens} est. tokens")
        println("   C last prompt ledger: " + r.prompts.last().substringAfter("[earlier steps omitted]").substringBefore("\nmodel: <tool_call>").lines().take(4).joinToString(" | ").take(400))
    }
    println(if (failures == 0) "ALL PASS" else "$failures FAILED")
}
