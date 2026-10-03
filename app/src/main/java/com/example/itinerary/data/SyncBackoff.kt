package com.example.itinerary.data

/**
 * SY-2: how often AutoSync's minute check may repeat the full work (a full notes pass, retrying a calendar that failed,
 * sending a file Nextcloud refused) while the same thing stays out of step. The first repeat waits [first], each one
 * after twice as long, up to [most]. A different result, a settled one, or [reset] (a change in Planner, Sync now, the
 * connection back) starts again at once. The cheap "anything changed?" questions are not the caller's to hold back.
 */
class SyncBackoff(private val first: Long = 60_000L, private val most: Long = 15 * 60_000L) {
    private var last: Any? = null
    private var wait = 0L
    private var next = 0L

    // Whether a check at [now] may do the full work again. A check a little early (the minute's timer) still counts.
    @Synchronized fun due(now: Long) = now >= next - SLACK_MS

    // What [left] the full work just done left out of step (null: nothing, or it changed something). The same as last
    // time: wait longer before the next full work; anything else: no wait.
    @Synchronized fun after(left: Any?, now: Long) {
        if (left == null || left != last) { wait = 0; next = 0 }
        else { wait = if (wait == 0L) first else minOf(wait * 2, most); next = now + wait }
        last = left
    }

    // What a check that held the full work back found [left]: anything other than what the full work left (fixed, or
    // a new problem) ends the wait.
    @Synchronized fun light(left: Any?) { if (left != last) reset() }

    @Synchronized fun reset() { last = null; wait = 0; next = 0 }

    companion object { const val SLACK_MS = 2_000L }
}
