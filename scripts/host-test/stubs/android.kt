// FAKE for host tests only: the minimum android.* surface AICoreProvider touches.
@file:Suppress("unused")
package android.util
object Log { fun i(t: String, m: String) = 0; fun d(t: String, m: String) = 0; fun w(t: String, m: String, e: Throwable? = null) = 0 }
