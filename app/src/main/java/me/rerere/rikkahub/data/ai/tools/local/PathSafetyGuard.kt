package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.rikkahub.BuildConfig
import java.io.File
import java.io.IOException

/**
 * Lightweight path-safety guard for file manager tools.
 *
 * We deliberately do NOT reuse HardlineCommandGuard (which is string-matching
 * on shell commands) — this guard is path-canonical and type-safe.
 *
 * It is a runtime floor, independent of the model and of approval: even an
 * "Always Allow"-ed file tool cannot reach system storage, other apps' sandboxes, or the
 * parts of our own sandbox that hold secrets (API keys, OAuth tokens, cookies, chats).
 */
object PathSafetyGuard {
    data class Violation(val code: String, val detail: String)

    /**
     * Check [raw] for safety. Returns null on success, a [Violation] otherwise.
     *
     * Callers must convert to a structured error envelope via [fmErrEnvelope].
     */
    fun check(raw: String?): Violation? = checkPathSafety(raw, BuildConfig.APPLICATION_ID)
}

/** Prefixes that are permanently blocked — system-owned, never user data. */
private val SYSTEM_PREFIXES = listOf(
    "/system",
    "/system_ext",
    "/vendor",
    "/proc",
    "/dev",
    "/sys",
    "/apex",
)

/**
 * Parts of our own sandbox that hold secrets or conversation data. Relative to the app
 * data dir. The model reaches these only through purpose-built tools, never by path:
 * datastore = provider API keys / Telegram token, no_backup = OAuth accounts,
 * shared_prefs = skill secrets, app_webview = browser cookies, databases = chats,
 * files/known_hosts = SSH trust store (writing it would enable MITM),
 * files/tool_output_store = full tool results of every chat (read_tool_output serves them
 * per conversation; a path would reach other chats' results).
 */
private val OWN_SECRET_SUBPATHS = listOf(
    "databases",
    "shared_prefs",
    "no_backup",
    "app_webview",
    "files/datastore",
    "files/known_hosts",
    "files/tool_output_store",
)

/**
 * Android app-data roots in canonical form. /data/data is a symlink to /data/user/0, so a
 * canonical path never starts with /data/data on a device; checking only that prefix
 * (as this guard used to) left every per-app rule dead there.
 */
private val APP_DATA_ROOT = Regex("^(/data/data|/data/user/\\d+|/data/user_de/\\d+)/([^/]+)(/.*)?$")

internal fun checkPathSafety(raw: String?, ownPackage: String): PathSafetyGuard.Violation? {
    if (raw.isNullOrEmpty()) {
        return PathSafetyGuard.Violation("path_blocked", "Path must not be empty.")
    }
    if (raw.contains('\u0000')) {
        return PathSafetyGuard.Violation("path_blocked", "Path must not contain null bytes.")
    }

    // Traversal is rejected on the raw string before anything is resolved.
    val rawNorm = raw.replace('\\', '/')
    if (rawNorm.contains("/../") || rawNorm.endsWith("/..") ||
        rawNorm == ".." || rawNorm.startsWith("../")
    ) {
        return PathSafetyGuard.Violation("path_blocked", "Path traversal sequences ('..') are not allowed.")
    }

    val canonical = try {
        File(raw).canonicalPath
    } catch (_: IOException) {
        return PathSafetyGuard.Violation("path_blocked", "Path could not be resolved.")
    }

    for (prefix in SYSTEM_PREFIXES) {
        if (canonical == prefix || canonical.startsWith("$prefix/")) {
            return PathSafetyGuard.Violation(
                "path_blocked",
                "Paths under $prefix are read-only system storage and cannot be accessed by this tool."
            )
        }
    }

    val appData = APP_DATA_ROOT.find(canonical) ?: return null
    val (_, pkg, rest) = appData.destructured
    if (pkg != ownPackage) {
        return PathSafetyGuard.Violation(
            "path_blocked",
            "Paths inside other apps' private sandboxes (/data/data/<other>) cannot be accessed."
        )
    }
    val inside = rest.trimStart('/')
    val secret = OWN_SECRET_SUBPATHS.firstOrNull { inside == it || inside.startsWith("$it/") }
    if (secret != null) {
        return PathSafetyGuard.Violation(
            "path_blocked",
            "'$secret' in RikkaHub's private storage holds credentials or chat data and cannot be accessed by file tools."
        )
    }
    return null
}
