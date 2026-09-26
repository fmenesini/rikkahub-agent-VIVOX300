package me.rerere.rikkahub.data.ai.tools

/**
 * Closest tool names to one the model called but that does not exist. Small models guess
 * names ("write_file" for write_text_file) and, handed a flat list of 30+ tools, repeat the
 * guess instead of scanning it; naming the likely targets first lets them correct on the
 * next step. Ranked by shared name parts (split on '_'), then by edit distance.
 */
fun suggestToolNames(called: String, available: List<String>, max: Int = 3): List<String> {
    val want = called.lowercase().split('_', '-').filter { it.isNotEmpty() }.toSet()
    if (want.isEmpty()) return emptyList()
    return available.distinct()
        .map { name ->
            val parts = name.lowercase().split('_', '-').toSet()
            val shared = want.count { w -> parts.any { it == w || (w.length >= 4 && (it.startsWith(w) || w.startsWith(it))) } }
            Triple(name, shared, editDistance(called.lowercase(), name.lowercase()))
        }
        .filter { (_, shared, dist) -> shared > 0 || dist <= 3 }
        .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.third })
        .take(max)
        .map { it.first }
}

private fun editDistance(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        for (j in 1..b.length) {
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        prev = cur
    }
    return prev[b.length]
}
