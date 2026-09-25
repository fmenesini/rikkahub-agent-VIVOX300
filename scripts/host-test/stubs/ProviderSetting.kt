package me.rerere.ai.provider

import kotlinx.serialization.Serializable

// Host-test stub. The real ProviderSetting pulls in Jetpack Compose, which cannot be
// compiled without the Android toolchain; the pure AICore code only needs the type name.
@Serializable
sealed class ProviderSetting
