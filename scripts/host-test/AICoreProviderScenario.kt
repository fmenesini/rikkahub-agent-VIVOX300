// Host scenario driver: runs the real AICoreProvider.streamText/generateText against the
// scripted fake GenerativeModel in stubs/MlKitPrompt.kt. Run via scripts/host-test/run.sh.
import com.google.mlkit.genai.prompt.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.AICoreProvider
import me.rerere.ai.ui.*

class Ctx : android.content.Context() {
    override val packageName = "x"; override val packageManager = android.content.PackageManager()
    override fun startActivity(i: android.content.Intent) {}; override fun getSystemService(n: String): Any? = null
}
fun chunks(vararg c: Pair<String, Int?>): (GenerateContentRequest) -> Flow<GenerateContentResponse> =
    { flowOf(*c.map { GenerateContentResponse(listOf(Candidate(it.first, it.second))) }.toTypedArray()) }
val tool = me.rerere.ai.core.Tool(name = "write_file", description = "Write file", execute = { emptyList() })
fun run(msgs: List<UIMessage>, stream: Boolean = true): Any = runBlocking {
    val p = AICoreProvider(Ctx())
    val params = TextGenerationParams(model = AICORE_NANO_FULL_MODEL, tools = listOf(tool))
    if (stream) p.streamText(ProviderSetting.AICore(), msgs, params).toList() else p.generateText(ProviderSetting.AICore(), msgs, params)
}
fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) failures++ }
var failures = 0
fun sc(name: String, b: () -> Unit) { FakeAICore.requests.clear(); FakeAICore.script.clear(); try { b() } catch (e: Throwable) { println("FAIL $name threw ${e.javaClass.simpleName}: ${e.message?.take(80)}"); failures++ } }
fun main() {
    val msgs = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("write hello to a.txt"))))
    sc("1. tool call cut at MAX_TOKENS resumes in round 2; the model repeats a bit of the tail") {
    FakeAICore.requests.clear()
    FakeAICore.script += chunks("Ok. <tool_call>{\"name\":\"write_file\",\"input\":{\"path\":\"a.txt\",\"content\":\"hel" to null, "" to Candidate.FinishReason.MAX_TOKENS)
    FakeAICore.script += chunks("\"path\":\"a.txt\",\"content\":\"hello\"}}</tool_call>" to Candidate.FinishReason.STOP)
    val out = run(msgs) as List<StreamChunk>
    val calls = out.filterIsInstance<StreamChunk.ToolCallDelta>()
    check("continuation completes cut tool call", calls.size == 1 && calls[0].inputDelta == """{"path":"a.txt","content":"hello"}""")
    check("two requests sent", FakeAICore.requests.size == 2)
    check("round 2 prompt carries prefill", FakeAICore.requests[1].prompt.endsWith("\"content\":\"hel"))
    check("finish is tool_calls", (out.last() as StreamChunk.Finish).finishReason == "tool_calls")
    println("   round1 prompt: " + FakeAICore.requests[0].prompt.replace("\n", "\\n"))
    }
    sc("2. plain text: last chunk carries text AND finish -> tail must not be lost") {
    FakeAICore.requests.clear()
    FakeAICore.script += chunks("Hello there, " to null, "all done now." to Candidate.FinishReason.STOP)
    val out2 = run(msgs) as List<StreamChunk>
    val text = out2.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text }
    check("full text delivered: '$text'", text == "Hello there, all done now.")
    check("finish stop", (out2.last() as StreamChunk.Finish).finishReason == "stop")
    }
    sc("3. text cut 3 times -> stops after 2 continuations with 'length'") {
    FakeAICore.requests.clear()
    repeat(3) { i -> FakeAICore.script += chunks("part$i-" + "w".repeat(70) to Candidate.FinishReason.MAX_TOKENS) }
    val out3 = run(msgs) as List<StreamChunk>
    check("max 3 requests, finish length", FakeAICore.requests.size == 3 && (out3.last() as StreamChunk.Finish).finishReason == "length")
    }
    sc("4. input overflow before output -> one retry with smaller prompt") {
    FakeAICore.requests.clear()
    FakeAICore.script += { _ -> flow { throw IllegalStateException("Request failed: input token count exceeds limit") } }
    FakeAICore.script += chunks("fine" to Candidate.FinishReason.STOP)
    val out4 = run(msgs) as List<StreamChunk>
    check("overflow retried once", FakeAICore.requests.size == 2 && out4.last() is StreamChunk.Finish)
    }
    sc("5. cancellation stays cancellation") {
    FakeAICore.script.clear()
    FakeAICore.script += { _ -> flow { emit(GenerateContentResponse(listOf(Candidate("a", null)))); throw CancellationException("stop pressed") } }
    val ex = runCatching { run(msgs) }.exceptionOrNull()
    check("cancellation not wrapped (${ex?.javaClass?.simpleName})", ex is CancellationException)
    }
    sc("6. non-streaming keeps tool calls") {
    FakeAICore.script.clear()
    FakeAICore.script += chunks("<tool_call>{\"name\":\"write_file\",\"input\":{\"path\":\"b\"}}</tool_call>" to Candidate.FinishReason.STOP)
    val res = run(msgs, stream = false) as TextGenerationResult
    check("generateText keeps tool part", res.message.parts.any { it is UIMessagePart.Tool } && res.finishReason == "tool_calls")
    }
    println(if (failures == 0) "ALL PASS" else "$failures FAILED")
}
