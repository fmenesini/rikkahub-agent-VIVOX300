package me.rerere.ai.core

/**
 * Names of tools the agent runtime adds by itself (not chosen per assistant). Shared between
 * the providers, which may point the model at them, and the app, which builds them.
 */
object RuntimeTools {
    /**
     * Read-only access to the full text of an earlier tool result in the same conversation,
     * by page (`offset`) or by `query`. Lets small-context providers clip results in the
     * prompt without losing them: the clip marker names the call id and where to resume.
     */
    const val READ_TOOL_OUTPUT = "read_tool_output"
}
