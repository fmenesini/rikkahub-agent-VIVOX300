package me.rerere.ai.provider.providers

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.PromptPrefix
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.AICoreReleaseStage
import me.rerere.ai.provider.AICORE_DEFAULT_MODELS
import me.rerere.ai.provider.AICORE_NANO_FAST_MODEL
import me.rerere.ai.provider.AICORE_NANO_FULL_MODEL
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

private const val TAG = "AICoreProvider"

/**
 * On-device LLM provider backed by Google's AICore (Gemini Nano / Gemma 4 E2B-E4B on the
 * Developer Preview) via the ML Kit GenAI prompt API. Stateless — every inference call
 * resolves a fresh GenerativeModel client. The install state is exposed via [checkStatus].
 *
 * The prompt API has no native tool calling, a ~4k-token input limit and a 256-token output
 * cap, so prompt assembly, the `<tool_call>` text protocol and MAX_TOKENS continuation live
 * in AICorePrompt.kt (host-testable, see scripts/host-test).
 */
class AICoreProvider(private val context: Context) : Provider<ProviderSetting.AICore> {

    override suspend fun listModels(providerSetting: ProviderSetting.AICore): List<Model> =
        AICORE_DEFAULT_MODELS

    override suspend fun getBalance(providerSetting: ProviderSetting.AICore): String =
        "On-device — no API balance"

    override suspend fun streamText(
        providerSetting: ProviderSetting.AICore,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        // Google policy: AICore inference only runs while the calling app is in the
        // foreground (otherwise throws ErrorCode 30). When a turn fires from the Telegram
        // bot, a cron job, or any path where RikkaHub is backgrounded, briefly bring our
        // own UI to the foreground so the inference can proceed. Best-effort: if the OS
        // refuses (locked screen, recent BAL restrictions), the call will surface the
        // translated error.
        ensureAppForeground(context)

        val (preference, generativeModel) = openClient(providerSetting, params.model)
        try {
            val status: Int = try {
                generativeModel.checkStatus()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w(TAG, "checkStatus threw", t)
                error(translateAICoreError(t))
            }
            if (status != FeatureStatus.AVAILABLE) {
                error(unavailableMessage(status))
            }
            try {
                generativeModel.warmup()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w(TAG, "warmup threw", t)
                error(translateAICoreError(t))
            }

            // Real tokenizer limits, when the API gives them; the char estimate is the fallback.
            val inputLimit = aiCoreInputLimit(
                try { generativeModel.getTokenLimit() } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.w(TAG, "getTokenLimit threw, using the documented limit", t)
                    null
                }?.also { Log.i(TAG, "getTokenLimit=$it") }
            )
            var canCount = true

            val temperature = (params.temperature ?: 0.7f).coerceIn(0f, 1f)
            val streamId = "aicore-${System.currentTimeMillis()}"
            // One parser for all rounds: a tool call cut by the output cap resumes in the
            // continuation round instead of being lost.
            val parser = ToolTagParser(params.tools)
            var textId: String? = null
            val openToolIds = linkedSetOf<String>()

            suspend fun FlowCollector<StreamChunk>.emitParts(parts: List<UIMessagePart>) {
                parts.forEach { part ->
                    when (part) {
                        is UIMessagePart.Text -> if (part.text.isNotEmpty()) {
                            val id = textId ?: "$streamId:text".also {
                                textId = it
                                emit(StreamChunk.TextStart(it))
                            }
                            emit(StreamChunk.TextDelta(id, part.text))
                        }
                        // Emitted as a single self-contained Start/Delta/End sequence:
                        // ToolTagParser only hands back a Tool part once its closing tag has
                        // already been seen, so there is no partial input to stream deltas of.
                        is UIMessagePart.Tool -> {
                            if (openToolIds.add(part.toolCallId)) {
                                emit(StreamChunk.ToolCallStart(part.toolCallId, part.toolName))
                            }
                            emit(StreamChunk.ToolCallDelta(part.toolCallId, inputDelta = part.input))
                            emit(StreamChunk.ToolCallEnd(part.toolCallId))
                            openToolIds.remove(part.toolCallId)
                        }
                        else -> Unit
                    }
                }
            }

            // ML Kit caps every request at 256 output tokens and the input at ~4k, so the
            // prompt is rebuilt within budget each round (see buildAiCorePrompt) and an
            // answer cut by MAX_TOKENS is continued with the text generated so far as
            // prefill. Continuation stops as soon as a tool call is out.
            val generated = StringBuilder()
            var tokenBudget = AICORE_INPUT_TOKEN_BUDGET
            var overflowRetried = false
            var round = 0
            while (true) {
                var built = buildAiCorePrompt(messages, params.tools, tokenBudget, prefill = generated.toString())
                fun requestFor(p: AiCorePrompt) = generateContentRequest(TextPart(p.prompt)) {
                    this.temperature = temperature
                    if (p.systemPrefix.isNotBlank()) {
                        this.promptPrefix = PromptPrefix(p.systemPrefix)
                    }
                }
                var request = requestFor(built)
                // Measure before sending: rebuild with a calibrated budget until the counted
                // prompt fits (at most 3 counts per round; the estimate alone is the fallback).
                var counted: Int? = null
                var counts = 0
                while (canCount && counts < 3) {
                    counts++
                    counted = try {
                        generativeModel.countTokens(request).totalTokens
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        Log.w(TAG, "countTokens threw, falling back to the char estimate", t)
                        canCount = false
                        null
                    } ?: break
                    val next = calibratedAiCoreBudget(tokenBudget, built.estimatedTokens, counted, inputLimit, built.droppedUnits)
                        ?: break
                    Log.i(TAG, "counted=${counted}tok est=${built.estimatedTokens} limit=$inputLimit: budget $tokenBudget -> $next")
                    tokenBudget = next
                    built = buildAiCorePrompt(messages, params.tools, tokenBudget, prefill = generated.toString())
                    request = requestFor(built)
                    counted = null
                }
                Log.i(
                    TAG,
                    "prompt round=$round est=${built.estimatedTokens}tok counted=${counted ?: "-"} " +
                        "prefix=${built.systemPrefix.length}ch prompt=${built.prompt.length}ch " +
                        "tools=${built.toolsShown}/${built.toolsTotal} dropped=${built.droppedUnits}",
                )
                val joiner = ContinuationJoiner(generated.toString())
                val generatedBefore = generated.length
                var hitMaxTokens = false
                try {
                    generativeModel.generateContentStream(request).collect { response ->
                        val candidate = response.candidates.firstOrNull()
                        val delta = joiner.feed(candidate?.text.orEmpty())
                        generated.append(delta)
                        emitParts(parser.feed(delta))
                        if (candidate?.finishReason == Candidate.FinishReason.MAX_TOKENS) {
                            hitMaxTokens = true
                        }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    // Nothing generated yet and the error looks like an input overflow: the
                    // char-based estimate was too optimistic for this text. Retrying with a
                    // smaller budget cannot repeat any side effect.
                    if (!overflowRetried && generated.length == generatedBefore && looksLikeAiCoreInputOverflow(t)) {
                        Log.w(TAG, "input overflow at est=${built.estimatedTokens}tok, retrying smaller", t)
                        overflowRetried = true
                        tokenBudget = tokenBudget * 6 / 10
                        continue
                    }
                    Log.w(TAG, "generateContentStream threw", t)
                    error(translateAICoreError(t))
                }
                val held = joiner.finish()
                generated.append(held)
                emitParts(parser.feed(held))

                if (shouldContinueAiCore(hitMaxTokens, parser.emittedToolCall, round)) {
                    round++
                    Log.i(TAG, "MAX_TOKENS at ${generated.length}ch, continuation round $round")
                    continue
                }
                // Always flush: the parser holds back up to 10 chars that could start a tag.
                emitParts(parser.flushPending())
                textId?.let { emit(StreamChunk.TextEnd(it)) }
                openToolIds.forEach { emit(StreamChunk.ToolCallEnd(it)) }
                emit(
                    StreamChunk.Finish(
                        finishReason = aiCoreFinishReason(hitMaxTokens, parser.emittedToolCall),
                        responseId = streamId,
                        model = params.model.modelId,
                    )
                )
                break
            }
        } finally {
            try { generativeModel.close() } catch (t: Throwable) {
                Log.w(TAG, "close failed", t)
            }
            // Mark the preference as used so the inline cache is consistent
            require(preference.isNotEmpty())
        }
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.AICore,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        // Merge through StreamChunkHandler so tool calls survive non-streaming mode too;
        // collecting only TextDelta silently dropped every AICore tool call.
        val handler = StreamChunkHandler()
        var merged = listOf(UIMessage(role = MessageRole.USER, parts = emptyList()))
        var finishReason: String? = null
        streamText(providerSetting, messages, params).collect { chunk ->
            if (chunk is StreamChunk.Finish) chunk.finishReason?.let { finishReason = it }
            merged = handler.handle(merged, chunk)
        }
        val reply = merged.last().takeIf { it.role == MessageRole.ASSISTANT }
        return TextGenerationResult(
            id = "aicore-${System.currentTimeMillis()}",
            model = params.model.modelId,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = reply?.parts.orEmpty(),
            ),
            finishReason = finishReason ?: "stop",
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = error("AICore does not support image generation")

    /**
     * Live status of the AICore feature on this device. Surfaced by the settings UI so the
     * user knows whether to enrol in the AICore beta, wait for a download, or move on.
     */
    /** Returns one of [FeatureStatus.AVAILABLE], DOWNLOADABLE, DOWNLOADING, UNAVAILABLE. */
    suspend fun checkStatus(providerSetting: ProviderSetting.AICore): Int {
        val (_, generativeModel) = openClient(
            providerSetting,
            providerSetting.models.firstOrNull() ?: AICORE_NANO_FAST_MODEL,
        )
        return try {
            generativeModel.checkStatus()
        } catch (t: Throwable) {
            Log.w(TAG, "checkStatus failed", t)
            FeatureStatus.UNAVAILABLE
        } finally {
            try { generativeModel.close() } catch (_: Throwable) {}
        }
    }

    /** Builds the ML Kit GenerativeModel client for [model] given the provider settings. */
    private fun openClient(
        providerSetting: ProviderSetting.AICore,
        model: Model,
    ): Pair<String, GenerativeModel> {
        val preference: Int =
            if (model.modelId == AICORE_NANO_FULL_MODEL.modelId) ModelPreference.FULL
            else ModelPreference.FAST
        val release: Int = when (providerSetting.releaseStage) {
            AICoreReleaseStage.PREVIEW -> ModelReleaseStage.PREVIEW
            AICoreReleaseStage.STABLE -> ModelReleaseStage.STABLE
        }
        val generativeModel = Generation.getClient(
            generationConfig {
                modelConfig = modelConfig {
                    this.preference = preference
                    this.releaseStage = release
                }
            }
        )
        return preference.toString() to generativeModel
    }

    private fun unavailableMessage(status: Int): String = when (status) {
        FeatureStatus.UNAVAILABLE ->
            "AICore is not available on this device (needs an AICore-supported phone and the AICore beta / Developer Preview)."
        FeatureStatus.DOWNLOADABLE ->
            "AICore model not downloaded yet. Open Settings → Providers → AICore → Prepare model."
        FeatureStatus.DOWNLOADING ->
            "AICore model is still downloading. Wait for the download to complete and retry."
        else -> "AICore is unavailable (status=$status)."
    }

    /**
     * Maps AICore's raw error messages to a user-actionable hint. The most common one we hit
     * on launch is `ErrorCode 606 - FEATURE_NOT_FOUND` which means the device has the AICore
     * system app installed but is not enrolled in the GenAI Prompt-API early-access channel.
     */
    private fun translateAICoreError(t: Throwable): String {
        val msg = (t.message ?: t::class.java.simpleName)
        return when {
            msg.contains("606") || msg.contains("FEATURE_NOT_FOUND", ignoreCase = true) ->
                "AICore prompt-API not enrolled on this device. Install the AICore app from the Play Store, then enrol in the GenAI Prompt-API early-access program at https://goo.gle/aicore-prompt-eap and reboot. Raw: $msg"
            msg.contains("ErrorCode 30", ignoreCase = true) ||
            msg.contains("Background usage is blocked", ignoreCase = true) ->
                "AICore is foreground-only (Google policy). RikkaHub tried to bring its UI " +
                "forward but the system blocked it (probably because the screen is locked or " +
                "another app holds focus). Unlock and reopen RikkaHub, or pick a cloud model " +
                "for background tasks. Raw: $msg"
            msg.contains("PREPARATION_ERROR", ignoreCase = true) ->
                "AICore is still preparing the model. Wait 30s and retry, or open Settings → Apps → AICore → Storage and clear cache. Raw: $msg"
            else -> "AICore error: $msg"
        }
    }

    /**
     * AICore (Gemini Nano) refuses to run when the calling app is in the background. If we
     * detect that we're backgrounded — typical when the Telegram bot service or a cron
     * worker fires a turn — fire the launcher Intent for our own package and poll until the
     * process is back in the foreground. Bounded by [maxWaitMs] so we never block forever.
     *
     * Returns once the app is foreground, or after the timeout (in which case the inference
     * call will probably fail and surface the translated background-blocked error).
     */
    private suspend fun ensureAppForeground(ctx: Context, maxWaitMs: Long = 2500L) {
        if (isAppForeground(ctx)) return
        Log.i(TAG, "ensureAppForeground: launching ${ctx.packageName} to clear AICore background block")
        val launchIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: return
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        try {
            ctx.startActivity(launchIntent)
        } catch (t: Throwable) {
            Log.w(TAG, "ensureAppForeground: startActivity threw", t)
            return
        }
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            if (isAppForeground(ctx)) return
            delay(100)
        }
        Log.w(TAG, "ensureAppForeground: timed out waiting for foreground transition")
    }

    private fun isAppForeground(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val myPid = Process.myPid()
        val proc = am.runningAppProcesses?.firstOrNull { it.pid == myPid } ?: return false
        return proc.importance ==
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
