package me.rerere.ai.core

import me.rerere.ai.provider.providers.LedgerEntry
import me.rerere.ai.provider.providers.clipMiddle
import me.rerere.ai.provider.providers.estimateAiCoreTokens
import me.rerere.ai.provider.providers.ledgerEntry
import me.rerere.ai.provider.providers.neutralizeTranscriptMarkers
import me.rerere.ai.provider.providers.renderLedgerRun
import me.rerere.ai.provider.providers.shorten
import me.rerere.ai.provider.providers.LEDGER_NOTE
import me.rerere.ai.provider.providers.LEDGER_NOTE_CHARS
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Provider-independent context manager: fits a conversation into a token budget and returns
 * it as messages again, so any provider that renders its own template (LiteRT, llama.cpp)
 * gets the same treatment as the AICore prompt builder:
 *  - the user message that started the current task is always kept;
 *  - newest steps first; the newest tool result gets the biggest slice, older ones less;
 *  - a clipped result says where to resume with read_tool_output (when that tool is offered);
 *  - dropped steps leave a ledger (runs of one tool merged, outcomes, the model's own notes)
 *    so the model does not redo work it can no longer see.
 * Clip sizes scale with the budget: tight for a 4k window, generous for 16-32k local models.
 * SYSTEM messages are returned untouched and not counted: callers budget them separately.
 */
object ContextCompactor {

    class Result(
        val messages: List<UIMessage>,
        /** Estimated tokens of the non-system content returned (conservative estimator). */
        val estimatedTokens: Int,
        /** Parts left out of the result (they may still be summarised in the ledger). */
        val droppedParts: Int,
        /** Parts whose text was clipped in the middle. */
        val clippedParts: Int,
    )

    /** Conservative token estimate (~3 chars/token Latin, 1/token CJK). */
    fun estimateTokens(text: CharSequence): Int = estimateAiCoreTokens(text)

    fun compact(
        messages: List<UIMessage>,
        tokenBudget: Int,
        retrievable: Boolean,
    ): Result {
        val caps = Caps.forBudget(tokenBudget)
        val taskIndex = messages.indexOfLast { it.role == MessageRole.USER }
        val lastToolPart = messages.asSequence().flatMap { it.parts.asSequence() }
            .filterIsInstance<UIMessagePart.Tool>().lastOrNull { it.isExecuted }

        // ---- flatten into units (one per text part / tool part) ----
        class Piece(
            val msg: Int,
            val part: UIMessagePart,
            val cost: Int,
            val pinned: Boolean,
            val ledger: LedgerEntry?,
            val clipped: Boolean,
        )
        val units = mutableListOf<Piece>()
        messages.forEachIndexed { mi, m ->
            if (m.role == MessageRole.SYSTEM) return@forEachIndexed
            for (p in m.parts) when (p) {
                is UIMessagePart.Text -> {
                    if (p.text.isBlank()) continue
                    val pinned = mi == taskIndex
                    val cap = if (pinned) caps.task else caps.otherText
                    val text = clipMiddle(p.text, cap)
                    val note = if (mi > taskIndex && m.role == MessageRole.ASSISTANT) {
                        LedgerEntry(LEDGER_NOTE, "", "", "", shorten(neutralizeTranscriptMarkers(p.text).replace('\n', ' '), LEDGER_NOTE_CHARS))
                    } else null
                    units += Piece(mi, p.copy(text = text), estimateTokens(text) + 4, pinned, note, text.length != p.text.length)
                }
                is UIMessagePart.Tool -> {
                    val latest = p === lastToolPart
                    val result = p.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val hint: ((Int) -> String)? = if (retrievable) {
                        { at -> "${RuntimeTools.READ_TOOL_OUTPUT} id=${p.toolCallId} offset=$at" }
                    } else null
                    val clippedResult = clipMiddle(result, if (latest) caps.latestResult else caps.oldResult, hint)
                    val clippedInput = clipMiddle(p.input.ifBlank { "{}" }, if (latest) caps.latestInput else caps.oldInput)
                    val newOutput = if (clippedResult === result) p.output else
                        listOf(UIMessagePart.Text(clippedResult)) + p.output.filter { it !is UIMessagePart.Text }
                    val part = p.copy(input = clippedInput, output = newOutput)
                    val cost = estimateTokens(p.toolName) + estimateTokens(clippedInput) + estimateTokens(clippedResult) + 12
                    units += Piece(mi, part, cost, false, ledgerEntry(p, result.trim(), retrievable),
                        clippedResult.length != result.length || clippedInput.length != p.input.length)
                }
                else -> Unit // images, reasoning, documents: not sent by the local text paths
            }
        }

        // ---- choose what to keep: pinned task, then newest first ----
        var remaining = tokenBudget
        val fullCost = units.sumOf { it.cost }
        val ledgerReserve = if (fullCost > remaining) {
            minOf(maxOf(360, tokenBudget / 12), tokenBudget * 15 / 100)
        } else 0
        remaining -= ledgerReserve
        val kept = BooleanArray(units.size)
        units.forEachIndexed { i, u -> if (u.pinned) { kept[i] = true; remaining -= u.cost } }
        for (i in units.indices.reversed()) {
            if (kept[i]) continue
            if (units[i].cost > remaining) break
            kept[i] = true
            remaining -= units[i].cost
        }
        remaining += ledgerReserve

        // ---- ledger lines for dropped runs (same rendering as the AICore prompt) ----
        val lines = arrayOfNulls<String>(units.size)
        run {
            var i = 0
            while (i < units.size) {
                val first = units[i].ledger
                if (kept[i] || first == null) { i++; continue }
                val runEntries = mutableListOf(first)
                var last = i
                var j = i + 1
                while (j < units.size && !kept[j]) {
                    val e = units[j].ledger
                    if (e != null) {
                        if (e.name != first.name || e.name == LEDGER_NOTE) break
                        runEntries += e
                        last = j
                    }
                    j++
                }
                val full = renderLedgerRun(runEntries, clipKeys = false)
                lines[last] = full
                i = last + 1
            }
        }
        // Each gap costs its header and a possible "(+N not listed)" line, on top of its lines.
        val gaps = units.indices.count { i -> !kept[i] && (i == 0 || kept[i - 1]) }
        remaining -= gaps * (estimateTokens("[earlier steps omitted]\n(+9999 older steps not listed)\n") + 4)
        val listed = BooleanArray(units.size)
        for (i in units.indices.reversed()) {
            val line = lines[i] ?: continue
            val cost = estimateTokens(line) + 1
            if (cost > remaining) break
            listed[i] = true
            remaining -= cost
        }

        // ---- rebuild the messages ----
        val out = mutableListOf<UIMessage>()
        var dropped = 0
        var clipped = 0
        var gap = StringBuilder()
        var gapUnlisted = 0
        fun flushGapInto(parts: MutableList<UIMessagePart>) {
            if (gap.isEmpty() && gapUnlisted == 0) return
            val text = buildString {
                append("[earlier steps omitted]\n").append(gap)
                if (gapUnlisted > 0) append("(+$gapUnlisted older steps not listed)\n")
            }.trimEnd()
            parts += UIMessagePart.Text(text)
            gap = StringBuilder()
            gapUnlisted = 0
        }
        var inGap = false
        val byMessage = units.withIndex().groupBy { it.value.msg }
        messages.forEachIndexed { mi, m ->
            if (m.role == MessageRole.SYSTEM) { out += m; return@forEachIndexed }
            val parts = mutableListOf<UIMessagePart>()
            for ((i, u) in byMessage[mi].orEmpty()) {
                if (!kept[i]) {
                    dropped++
                    inGap = true
                    val line = lines[i]
                    if (line != null) { if (listed[i]) gap.append(line).append('\n') else gapUnlisted++ }
                    continue
                }
                if (inGap) { flushGapInto(parts); inGap = false }
                if (u.clipped) clipped++
                parts += u.part
            }
            if (parts.isNotEmpty()) out += m.copy(parts = parts)
        }
        if (inGap) {
            // Trailing gap (only possible if the newest units did not fit): report it last.
            val parts = mutableListOf<UIMessagePart>()
            flushGapInto(parts)
            if (parts.isNotEmpty()) out += UIMessage(role = MessageRole.ASSISTANT, parts = parts)
        }
        // Chat templates want the conversation to open with the user: drop leading model turns
        // left over from a cut (the task itself is pinned, so a user turn always remains).
        val firstUser = out.indexOfFirst { it.role == MessageRole.USER }
        val result = if (firstUser > 0) {
            out.filterIndexed { idx, msg -> idx >= firstUser || msg.role == MessageRole.SYSTEM }
        } else out
        val tokens = result.filter { it.role != MessageRole.SYSTEM }.sumOf { msg ->
            msg.parts.sumOf { p ->
                when (p) {
                    is UIMessagePart.Text -> estimateTokens(p.text) + 4
                    is UIMessagePart.Tool -> estimateTokens(p.toolName) + estimateTokens(p.input) +
                        p.output.sumOf { o -> (o as? UIMessagePart.Text)?.let { estimateTokens(it.text) } ?: 0 } + 12
                    else -> 0
                }
            }
        }
        return Result(result, tokens, dropped, clipped)
    }

    /** Per-unit char caps, scaled to the budget (3 chars ≈ 1 token for the estimator). */
    internal class Caps(
        val latestResult: Int,
        val oldResult: Int,
        val latestInput: Int,
        val oldInput: Int,
        val task: Int,
        val otherText: Int,
    ) {
        companion object {
            fun forBudget(tokenBudget: Int): Caps {
                // A quarter of the budget for the newest result: what the model acts on next.
                val latest = (tokenBudget * 3 / 4).coerceIn(2400, 24_000)
                return Caps(
                    latestResult = latest,
                    oldResult = (latest / 6).coerceAtLeast(400),
                    latestInput = (latest / 2).coerceIn(1200, 8000),
                    oldInput = (latest / 10).coerceIn(240, 1600),
                    task = latest.coerceAtLeast(2400),
                    otherText = (latest / 2).coerceAtLeast(1200),
                )
            }
        }
    }
}
