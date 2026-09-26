// FAKE for host tests only. Mirrors the shape of the ML Kit GenAI Prompt API
// (1.0.0-beta2) as documented; it is NOT the real library and cannot prove the real
// API matches. The Gradle build against the real AAR remains the source of truth.
package com.google.mlkit.genai.common
object FeatureStatus { const val UNAVAILABLE = 0; const val DOWNLOADABLE = 1; const val DOWNLOADING = 2; const val AVAILABLE = 3 }
