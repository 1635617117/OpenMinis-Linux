package com.openminis.app.ui.chat

/**
 * Display-window paging. The database stays the full transcript.
 *
 * A page is a contiguous slice of complete user turns. Loading older prepends
 * that slice and keeps the newer side. The loaded window is always a suffix
 * of the session: rows after the newest loaded sort_order are attached, not
 * left behind a control. Nothing in this type drops a side to stay under a
 * capacity: that kick is what made the middle of a long chat unreachable.
 *
 * The cursor is [SortAnchor.sortOrder], not an offset and not created_at.
 * Offsets move when a row is inserted or deleted. created_at moves when a
 * row is replaced.
 */
internal object ChatHistoryWindow {
    const val TURN_PAGE_SIZE = 5
    const val TAIL_ATTACH_CHUNK = 50
    private const val ANCHOR_PROBE = 200

    /**
     * Rows with sort_order in `[loadedNewest + 1, sessionEndExclusive)`.
     * Null means the loaded cursor already covers the session tail.
     */
    fun missingTailRange(loadedNewestSortOrder: Int?, sessionEndExclusive: Int): SortRange? {
        val newest = loadedNewestSortOrder ?: return null
        if (newest == Int.MAX_VALUE) return null
        val start = newest + 1
        if (start >= sessionEndExclusive) return null
        return SortRange(start, sessionEndExclusive)
    }

    /**
     * Insertion index for the not-yet-painted rows of a contiguous tail
     * chunk. [chunkSourceIds] is the WHOLE chunk in DB (sort_order) order;
     * [freshStartIndex] is the first chunk row that is not painted yet. The
     * fresh block goes immediately before the first painted row that
     * represents any chunk id at or after [freshStartIndex] — those rows
     * sort after the fresh block. -1 means append.
     *
     * [T-android-timeline-ledger] Anchoring on the whole chunk regardless of
     * paint state (the old behavior) could place the fresh block before
     * painted rows that sort BEFORE it whenever an early chunk id was
     * already attached to a live painted row — reordering the transcript
     * and duplicating rows.
     */
    fun missingTailInsertIndex(
        currentSourceIds: List<List<String>>,
        chunkSourceIds: List<List<String>>,
        freshStartIndex: Int,
    ): Int {
        if (freshStartIndex >= chunkSourceIds.size) return -1
        val laterIds = chunkSourceIds.drop(freshStartIndex).flatten().toSet()
        if (laterIds.isEmpty()) return -1
        return currentSourceIds.indexOfFirst { ids -> ids.any(laterIds::contains) }
    }

    data class SortAnchor(val sortOrder: Int, val isUser: Boolean)

    /**
     * How far one newest-first probe moves the older cursor.
     *
     * [startSortOrder] is the oldest anchor that belongs in this page.
     * [needsMore] is true only when the probe ran out before [turnCount]
     * user turns and the caller still has older rows to inspect. The caller
     * must not stop mid-turn: if the oldest included row is not a user
     * message, it keeps probing until a user message or the session start.
     */
    data class OlderProbe(
        val startSortOrder: Int?,
        val usersIncluded: Int,
        val needsMore: Boolean,
    )

    data class NewerProbe(
        val endSortOrder: Int?,
        val usersIncluded: Int,
        val needsMore: Boolean,
    )

    fun absorbOlder(
        anchorsNewestFirst: List<SortAnchor>,
        turnCount: Int,
        usersAlready: Int = 0,
    ): OlderProbe {
        if (turnCount <= 0) return OlderProbe(null, usersAlready, false)
        var users = usersAlready
        var start: Int? = null
        for (anchor in anchorsNewestFirst) {
            start = anchor.sortOrder
            if (anchor.isUser) {
                users++
                if (users >= turnCount) {
                    return OlderProbe(start, users, needsMore = false)
                }
            }
        }
        val exhausted = anchorsNewestFirst.isEmpty()
        return OlderProbe(
            startSortOrder = start,
            usersIncluded = users,
            needsMore = !exhausted && users < turnCount,
        )
    }

    /**
     * Oldest-first anchors newer than the loaded cursor.
     * Stops before the user message that would start turn [turnCount] + 1,
     * so a question is not split from the replies already included.
     * If the probe ends on a user message, [needsMore] stays true: that
     * turn's replies may still be ahead.
     */
    fun absorbNewer(
        anchorsOldestFirst: List<SortAnchor>,
        turnCount: Int,
        usersAlready: Int = 0,
    ): NewerProbe {
        if (turnCount <= 0) return NewerProbe(null, usersAlready, false)
        var users = usersAlready
        var end: Int? = null
        for (anchor in anchorsOldestFirst) {
            if (anchor.isUser && users >= turnCount) {
                return NewerProbe(end, users, needsMore = false)
            }
            end = anchor.sortOrder
            if (anchor.isUser) users++
        }
        return NewerProbe(
            endSortOrder = end,
            usersIncluded = users,
            // True when this probe did not end on a user-turn boundary.
            // The caller continues only if the probe was also full; a short
            // probe means the session end, not a split turn.
            needsMore = anchorsOldestFirst.isNotEmpty(),
        )
    }

    fun historyEdgeAction(
        hasOlder: Boolean,
        hasNewer: Boolean,
        olderSentinelVisible: Boolean,
        newerSentinelVisible: Boolean,
        newestEdgeVisible: Boolean,
        oldestEdgeVisible: Boolean,
    ): HistoryPageRequest {
        val spansBothEdges = newestEdgeVisible && oldestEdgeVisible
        return HistoryPageRequest(
            loadOlder = hasOlder && olderSentinelVisible && !spansBothEdges,
            loadNewer = hasNewer && newerSentinelVisible && !spansBothEdges,
        )
    }

    /**
     * reverseLayout paints index 0 at the visual bottom. [flatItems] is
     * oldest-first, then reversed into the list. Prepending older rows adds
     * items after the visible ones, so their lazy indices do not move.
     * Appending newer rows inserts items before them and must be compensated
     * by [insertedBeforeAnchor].
     */
    fun compensatedLazyIndex(previousLazyIndex: Int, insertedBeforeAnchor: Int): Int {
        if (previousLazyIndex < 0) return previousLazyIndex
        return previousLazyIndex + insertedBeforeAnchor.coerceAtLeast(0)
    }

    fun lazyIndexOfOldestFirstKey(
        oldestFirstCount: Int,
        keyIndexInOldestFirst: Int,
        itemsBeforeMessages: Int,
    ): Int {
        if (oldestFirstCount <= 0 || keyIndexInOldestFirst !in 0 until oldestFirstCount) return -1
        return itemsBeforeMessages + (oldestFirstCount - 1 - keyIndexInOldestFirst)
    }

    fun probeLimit(): Int = ANCHOR_PROBE
}

internal data class HistoryPageRequest(
    val loadOlder: Boolean,
    val loadNewer: Boolean,
)
