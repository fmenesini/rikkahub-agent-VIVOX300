package me.rerere.ai.provider.providers

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import kotlinx.coroutines.delay
import com.google.mlkit.genai.common.FeatureStatus
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
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

private const val TAG = "AICoreProvider"

/**
 * On-device LLM provider backed by Google's AICore (Gemini Nano) via the ML Kit GenAI prompt
 * API. Stateless — every inference call resolves a fresh GenerativeModel client and lets ML
 * Kit handle caching internally. The user-visible install state ("downloadable / downloading
 * / available / unavailable") is exposed via [checkStatus] and consumed by the settings UI.
 *
 * Tool-calling is not wired through yet; ML Kit GenAI 1.0.0-beta2 documents function calling
 * but the surface is in flux. First cut runs prompt-only inference; tools fall through to
 * being ignored.
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
                Log.w(TAG, "checkStatus threw", t)
                error(translateAICoreError(t))
            }
            if (status != FeatureStatus.AVAILABLE) {
                error(unavailableMessage(status))
            }
            try {
                generativeModel.warmup()
            } catch (t: Throwable) {
                Log.w(TAG, "warmup threw", t)
                error(translateAICoreError(t))
            }

            // Gemini Nano has a small context window (~4k tokens). The full agent-core skill
            // bundle (~3k tokens of voice/posture/tool docs) used by the cloud providers
            // overflows it, so we build a MINI version specifically for AICore: terse tool
            // descriptions, no skill prose, no examples. Cloud providers continue to use the
            // full agent-core via the assistant's enabledSkills.
            val systemPrefix = buildAiCoreMiniSystemPrefix(params.tools)
            val prompt = formatPromptFromMessages(truncateForAiCore(messages))
            val temperature = (params.temperature ?: 0.7f).coerceIn(0f, 1f)
            val request = generateContentRequest(TextPart(prompt)) {
                this.temperature = temperature
                if (systemPrefix.isNotBlank()) {
                    this.promptPrefix = PromptPrefix(systemPrefix)
                }
                params.topP?.let { /* topP not exposed in ML Kit GenAI prompt API */ }
            }

            val streamId = "aicore-${System.currentTimeMillis()}"
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

            suspend fun FlowCollector<StreamChunk>.closeOpenParts() {
                textId?.let {
                    emit(StreamChunk.TextEnd(it))
                    textId = null
                }
                openToolIds.toList().forEach { emit(StreamChunk.ToolCallEnd(it)) }
                openToolIds.clear()
            }

            try {
                generativeModel.generateContentStream(request).collect { response ->
                    val candidate = response.candidates.firstOrNull()
                    val rawDelta = candidate?.text.orEmpty()
                    val rawFinish = candidate?.finishReason?.toString()
                    val parts = parser.feed(rawDelta)
                    if (parts.isNotEmpty()) {
                        emitParts(parts)
                    } else if (rawFinish != null) {
                        // No content in this chunk but stream closed. Flush any partial buffer
                        // as text so it isn't dropped.
                        emitParts(parser.flushPending())
                    }
                    if (rawFinish != null) {
                        closeOpenParts()
                        // If a tool tag was closed in this delta, signal tool_calls so the
                        // GenerationHandler dispatches the tool and resumes the conversation.
                        // Otherwise pass through whatever the model declared (usually null).
                        emit(
                            StreamChunk.Finish(
                                finishReason = parser.consumePendingFinishReason() ?: rawFinish,
                                responseId = streamId,
                                model = params.model.modelId,
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "generateContentStream threw", t)
                error(translateAICoreError(t))
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
        val collected = StringBuilder()
        var finishReason: String? = null
        streamText(providerSetting, messages, params).collect { chunk ->
            when (chunk) {
                is StreamChunk.TextDelta -> collected.append(chunk.text)
                is StreamChunk.Finish -> chunk.finishReason?.let { finishReason = it }
                else -> Unit
            }
        }
        return TextGenerationResult(
            id = "aicore-${System.currentTimeMillis()}",
            model = params.model.modelId,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text(collected.toString())),
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
            "AICore is not available on this device. Pixel 8/9/10 with AICore beta required."
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
