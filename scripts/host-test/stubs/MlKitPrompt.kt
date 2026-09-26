// FAKE for host tests only. Mirrors the shape of the ML Kit GenAI Prompt API
// (1.0.0-beta2) as documented; it is NOT the real library and cannot prove the real
// API matches. The Gradle build against the real AAR remains the source of truth.
package com.google.mlkit.genai.prompt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
class TextPart(val text: String)
class PromptPrefix(val text: String)
class Candidate(val text: String, val finishReason: Int?) { object FinishReason { const val STOP = 0; const val MAX_TOKENS = 1; const val OTHER = 2 } }
class GenerateContentResponse(val candidates: List<Candidate>)
class GenerateContentRequest(val prompt: String, val prefix: String?)
class RequestBuilder { var temperature: Float? = null; var promptPrefix: PromptPrefix? = null; var maxOutputTokens: Int? = null }
fun generateContentRequest(p: TextPart, b: RequestBuilder.() -> Unit): GenerateContentRequest { val rb = RequestBuilder().apply(b); return GenerateContentRequest(p.text, rb.promptPrefix?.text) }
class CountTokensResponse(val totalTokens: Int)
class GenerativeModel { suspend fun checkStatus(): Int = 3; suspend fun warmup() {}; fun generateContentStream(r: GenerateContentRequest): Flow<GenerateContentResponse> { FakeAICore.requests += r; return (FakeAICore.script.removeFirstOrNull() ?: FakeAICore.responder ?: error("no scripted response"))(r) }; fun close() {}
  suspend fun getTokenLimit(): Int = FakeAICore.tokenLimit ?: error("getTokenLimit unsupported")
  suspend fun countTokens(r: GenerateContentRequest): CountTokensResponse { FakeAICore.counts++; return CountTokensResponse(FakeAICore.count(r)?.takeIf { FakeAICore.countApi } ?: error("countTokens unsupported")) } }
class ModelConfig; class GenerationConfig
class MCB { var preference = 0; var releaseStage = 0 }
class GCB { var modelConfig: ModelConfig? = null }
fun modelConfig(b: MCB.() -> Unit) = ModelConfig()
fun generationConfig(b: GCB.() -> Unit) = GenerationConfig()
object Generation { fun getClient(c: GenerationConfig) = GenerativeModel() }
object ModelPreference { const val FAST = 0; const val FULL = 1 }
object ModelReleaseStage { const val STABLE = 0; const val PREVIEW = 1 }

object FakeAICore { val requests = mutableListOf<GenerateContentRequest>(); val script = mutableListOf<(GenerateContentRequest) -> Flow<GenerateContentResponse>>()
  // Tokenizer model: null = API unavailable (throws). Default: the real tokenizer is not
  // modelled, counting is off, so older scenarios keep testing the char-estimate path.
  var responder: ((GenerateContentRequest) -> Flow<GenerateContentResponse>)? = null; var tokenLimit: Int? = null; var tokenizer: ((String) -> Int)? = null; var countApi = true; var counts = 0
  fun count(r: GenerateContentRequest): Int? = tokenizer?.let { it(r.prefix.orEmpty()) + it(r.prompt) }
  fun reset() { requests.clear(); script.clear(); responder = null; tokenLimit = null; tokenizer = null; countApi = true; counts = 0 } }
