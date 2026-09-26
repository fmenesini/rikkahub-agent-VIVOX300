package me.rerere.locallm

/**
 * How a local model's context is split between the response and the prompt pieces
 * (system text, native tool declarations, conversation history). One place for every
 * on-device runtime, so a larger model actually gets a larger prompt instead of fixed
 * few-thousand-char caps.
 *
 * Chars are converted with [SAFE_CHARS_PER_TOKEN] = 3, lower than the ~4 of English prose:
 * Italian, JSON and code tokenise denser, and a prefill past the engine's context faults the
 * native executor (process gone) instead of returning an error. Overestimating tokens only
 * costs some history; underestimating costs a crash.
 */
object LocalContextBudget {

    /**
     * Upper bound for any local engine context. Past this the KV cache and the prefill time
     * on a phone stop being worth it (a 32k prefill already takes a long time on-device), and
     * the context manager keeps long tasks going within it.
     */
    const val MAX_CONTEXT_TOKENS = 32_768

    const val SAFE_CHARS_PER_TOKEN = 3

    /** Contexts below this cannot usefully hold tool declarations next to a conversation. */
    private const val MIN_CONTEXT_FOR_TOOLS = 2048

    class Plan(
        val contextTokens: Int,
        val outputReserveTokens: Int,
        /** Chars for everything that is not the response: system + tools + history. */
        val inputChars: Int,
        val systemChars: Int,
        val toolChars: Int,
    )

    fun plan(contextTokens: Int): Plan {
        val ctx = contextTokens.coerceIn(1, MAX_CONTEXT_TOKENS)
        // A quarter of the context for the answer, capped: tool calls and replies rarely need
        // more than 4k tokens, and every reserved token is one the history cannot use.
        val output = (ctx / 4).coerceIn(minOf(256, ctx / 2), 4096)
        val inputChars = (ctx - output) * SAFE_CHARS_PER_TOKEN
        val system = if (ctx <= 4096) minOf(500, inputChars / 4) else (inputChars / 10).coerceIn(500, 6000)
        val tools = if (ctx < MIN_CONTEXT_FOR_TOOLS) 0 else inputChars * 35 / 100
        return Plan(ctx, output, inputChars, system, tools)
    }

    /**
     * Budget for the conversation history, in the context manager's (conservative) token
     * units, once the system text and the declared tools have taken their share. What the
     * tools did not use goes to the history.
     */
    fun historyTokens(plan: Plan, systemCharsUsed: Int, toolCharsUsed: Int): Int =
        ((plan.inputChars - systemCharsUsed - toolCharsUsed) / SAFE_CHARS_PER_TOKEN).coerceAtLeast(0)

    /** The engine context actually used: requested, clamped to the file's ceiling and to [MAX_CONTEXT_TOKENS]. */
    fun engineContextTokens(requestedMaxTokens: Int, contextCeiling: Int?): Int =
        minOf(requestedMaxTokens, contextCeiling ?: Int.MAX_VALUE, MAX_CONTEXT_TOKENS)
}
