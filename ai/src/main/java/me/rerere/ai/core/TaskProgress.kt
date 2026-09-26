package me.rerere.ai.core

/**
 * Which tools a task names ("1. web_fetch … 4. write_text_file …") are still uncalled, worked
 * out by the runtime from the calls actually made since the task message. Small on-device
 * models answer as soon as they can predict the result and skip the remaining side-effect
 * steps; the runtime keeps count instead of trusting the model to.
 */
object TaskProgress {

    /** Tools from [toolNames] named in [taskText], in order of first mention (whole names only). */
    fun namedTools(taskText: String, toolNames: List<String>): List<String> =
        toolNames.distinct()
            .filter { it != RuntimeTools.READ_TOOL_OUTPUT }
            .mapNotNull { name ->
                Regex("(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])")
                    .find(taskText)?.let { it.range.first to name }
            }
            .sortedBy { it.first }
            .map { it.second }

    /**
     * Named tools not called yet, empty when there is nothing to track: no call made yet (the
     * task itself is the plan), fewer than two tools named, or every named tool called.
     */
    fun pending(taskText: String, toolNames: List<String>, called: Set<String>): List<String> {
        if (called.isEmpty()) return emptyList()
        val named = namedTools(taskText, toolNames)
        if (named.size < 2) return emptyList()
        return named.filter { it !in called }
    }

    /** The line shown to the model before its turn, or null when [pending] is empty. */
    fun line(taskText: String, toolNames: List<String>, called: Set<String>): String? {
        val pending = pending(taskText, toolNames, called)
        if (pending.isEmpty()) return null
        val steps = namedTools(taskText, toolNames)
            .joinToString(", ") { if (it in called) "$it done" else "$it NOT DONE" }
        return "[runtime] Tools named in the task: $steps. If the task still needs them, " +
            "call ${pending.first()} now; do not give the final answer before."
    }
}
