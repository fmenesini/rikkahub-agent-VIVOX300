// Host-test stub: the real ProviderSetting pulls in Jetpack Compose.
package me.rerere.ai.provider
import kotlinx.serialization.Serializable
@Serializable sealed class ProviderSetting { abstract val models: List<Model>
  @Serializable data class AICore(override val models: List<Model> = emptyList(), var releaseStage: AICoreReleaseStage = AICoreReleaseStage.PREVIEW) : ProviderSetting() }
enum class AICoreReleaseStage { STABLE, PREVIEW }
val AICORE_NANO_FAST_MODEL = Model(modelId = "nano-fast")
val AICORE_NANO_FULL_MODEL = Model(modelId = "nano-full")
val AICORE_DEFAULT_MODELS = listOf(AICORE_NANO_FAST_MODEL, AICORE_NANO_FULL_MODEL)
