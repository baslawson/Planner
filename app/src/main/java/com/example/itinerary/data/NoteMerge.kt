package com.example.itinerary.data

/**
 * A note changed on both sides since the last sync (Planner and Nextcloud's Notes), merged instead of copied: against
 * the version both started from ([SentNote]), the text is merged line by line, as Git does. Lines changed on one side
 * only take that side's change; the same change on both is taken once. Only when both changed the same lines (or the
 * title differently) is there no merge: NoteSync then keeps Nextcloud's in the note and Planner's as a "(conflict copy)",
 * as before. A notebook or pin changed differently on both sides follows Nextcloud's (no copy for that alone).
 */
object NoteMerge {
    // The middle part two versions don't share (after their common start and end) is compared line against line: past
    // this many pairs (a huge rewrite on both sides) it's no merge.
    private const val MAX_CELLS = 4_000_000

    /** [mine] with both sides' changes, or null when they overlap (see above). [theirs]: Nextcloud's version as Planner keeps it. */
    fun merge(base: SentNote, mine: PlannerNote, theirs: PlannerNote): PlannerNote? {
        val title = pick(base.title, mine.title, theirs.title) ?: return null
        val content = text(base.content, mine.content, theirs.content) ?: return null
        return mine.copy(title = title, content = content,
            notebook = pick(base.notebook, mine.notebook, theirs.notebook) ?: theirs.notebook,
            pinned = pick(base.pinned, mine.pinned, theirs.pinned) ?: theirs.pinned,
            modified = maxOf(mine.modified, theirs.modified))
    }

    /** One value: the side that changed it, or the shared change; null when they changed it differently. */
    fun <T> pick(base: T, mine: T, theirs: T): T? = when {
        mine == base -> theirs
        theirs == base || mine == theirs -> mine
        else -> null
    }

    /** Three-way merge of text by lines (diff3); null when both sides changed the same lines differently. */
    fun text(base: String, mine: String, theirs: String): String? {
        pick(base, mine, theirs)?.let { return it }
        val b = base.split('\n'); val m = mine.split('\n'); val t = theirs.split('\n')
        val toMine = match(b, m) ?: return null
        val toTheirs = match(b, t) ?: return null
        val out = ArrayList<String>(maxOf(m.size, t.size))
        var bi = 0; var mi = 0; var ti = 0
        fun chunk(bEnd: Int, mEnd: Int, tEnd: Int): Boolean {
            val result = pick(b.subList(bi, bEnd), m.subList(mi, mEnd), t.subList(ti, tEnd)) ?: return false
            out.addAll(result); return true
        }
        // Lines unchanged on both sides anchor the merge; what lies between two anchors is merged as one piece.
        for (i in b.indices) {
            val mj = toMine[i]; val tj = toTheirs[i]
            if (mj < 0 || tj < 0) continue
            if (!chunk(i, mj, tj)) return null
            out.add(b[i]); bi = i + 1; mi = mj + 1; ti = tj + 1
        }
        if (!chunk(b.size, m.size, t.size)) return null
        return out.joinToString("\n")
    }

    /**
     * For each line of [a], the line of [b] it stays as (a longest common subsequence; -1: changed or removed), in order.
     * Null when the part that differs is too big to compare.
     */
    internal fun match(a: List<String>, b: List<String>): IntArray? {
        val result = IntArray(a.size) { -1 }
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) { result[start] = start; start++ }
        var endA = a.size; var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB--; result[endA] = endB }
        val n = endA - start; val m = endB - start
        if (n == 0 || m == 0) return result
        if (n.toLong() * m > MAX_CELLS) return null
        // lcs[i][j]: the longest common subsequence of a[start+i ..] and b[start+j ..].
        val width = m + 1
        val lcs = IntArray((n + 1) * width)
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            lcs[i * width + j] = if (a[start + i] == b[start + j]) lcs[(i + 1) * width + j + 1] + 1
                else maxOf(lcs[(i + 1) * width + j], lcs[i * width + j + 1])
        var i = 0; var j = 0
        while (i < n && j < m) when {
            a[start + i] == b[start + j] -> { result[start + i] = start + j; i++; j++ }
            lcs[(i + 1) * width + j] >= lcs[i * width + j + 1] -> i++
            else -> j++
        }
        return result
    }
}
