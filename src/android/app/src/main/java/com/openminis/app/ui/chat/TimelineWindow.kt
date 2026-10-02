package com.openminis.app.ui.chat

import com.openminis.app.data.db.MessageEntity
import kotlinx.coroutines.sync.Mutex

/**
 * The painted-window ledger — single owner of the two sort_order cursors
 * that define which slice of the session's transcript is loaded into
 * `_messages`.
 *
 * [T-android-timeline-ledger] The window used to be a scatter of plain
 * vars (`loadedOldestSortOrder`, `loadedNewestSortOrder`, a row-offset
 * counter pair, three tail-attach lock booleans) mutated from several
 * suspend paths with no serialization. That double bookkeeping is where
 * the "部分消息从会话页消失" bugs lived: a probe contradiction hard-fused
 * `hasOlder=false`, an empty hydrate consumed a gap that still had rows,
 * and load-older / tail-attach could interleave and splice `_messages`
 * twice. The rules now:
 *
 *  - The cursors are monotonic (oldest only moves down, newest only up).
 *  - The edge booleans (`hasOlder`/`hasNewer`) are derived from DB counts
 *    in [ChatViewModel.refreshHistoryEdges] — the database is the only
 *    authority. Nothing else may turn them off.
 *  - The two window-mutating operations (an older page, a tail-attach
 *    drain) hold [mutationMutex] around their fetch+splice so they cannot
 *    interleave; the offset/total pair below is diagnostics only.
 */
internal class TimelineWindow {
    /** Stable page cursors. Not an offset, not created_at. */
    var oldestSortOrder: Int? = null
        private set
    var newestSortOrder: Int? = null
        private set

    // Row counters derived in refreshHistoryEdges / seeded on session load.
    // Kept for diagnostics and the LLM-window code path; never used to
    // derive the edge booleans.
    var offset: Int = 0
        private set
    var total: Int = 0
        private set

    val mutationMutex = Mutex()

    /** Session (re)load: forget the old window and its counters. */
    fun reset() {
        oldestSortOrder = null
        newestSortOrder = null
        offset = 0
        total = 0
    }

    /**
     * Seed the diagnostics counters — from the session-tail summary on load,
     * or from the DB range counts in ChatViewModel.refreshHistoryEdges.
     * Never touches the cursors.
     */
    fun seedCounters(offset: Int, total: Int) {
        this.offset = offset
        this.total = total
    }

    /**
     * Widen the window to cover [rows]. Monotonic: a smaller oldest or a
     * larger newest is kept; rows inside the window change nothing.
     */
    fun noteBounds(rows: List<MessageEntity>) {
        if (rows.isEmpty()) return
        val oldest = rows.minOf { it.sortOrder }
        val newest = rows.maxOf { it.sortOrder }
        loadedOldest(oldest)
        loadedNewest(newest)
    }

    /**
     * Push the newest cursor to [sort], refusing to jump over an unpainted
     * gap (the caller checks the gap separately — see
     * ChatViewModel.notePersistedUiRow).
     */
    fun extendNewest(sort: Int) {
        loadedNewest(sort)
    }

    fun forceNewestAtLeast(sort: Int) {
        val current = newestSortOrder
        newestSortOrder = maxOf(current ?: sort, sort)
    }

    private fun loadedOldest(sort: Int) {
        val current = oldestSortOrder
        oldestSortOrder = minOf(current ?: sort, sort)
    }

    private fun loadedNewest(sort: Int) {
        val current = newestSortOrder
        newestSortOrder = maxOf(current ?: sort, sort)
    }
}
