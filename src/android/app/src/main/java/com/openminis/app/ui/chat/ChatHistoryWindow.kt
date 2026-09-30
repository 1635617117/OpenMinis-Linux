package com.openminis.app.ui.chat

internal data class OlderWindowPlan(
    val nextVisibleCap: Int,
    val fetchOffset: Int? = null,
    val fetchCount: Int = 0,
)

internal data class HistoryEdgeAction(
    val loadOlder: Boolean,
    val loadNewer: Boolean,
)

/**
 * Fixed-capacity movement of a database window.
 *
 * [retained] is the subset that remains in memory after the requested page is
 * merged with the currently loaded rows. Its size never exceeds [capacity], so
 * repeatedly loading older history cannot reconstruct the whole session.
 */
internal data class BoundedWindowMove(
    val retainedStart: Int,
    val retainedCount: Int,
)

/** Pure planning logic for the bounded UI/database window. */
internal object ChatHistoryWindow {
    fun planOlderLoad(
        loadedVisible: Int,
        visibleCap: Int,
        loadedOffset: Int,
        step: Int,
    ): OlderWindowPlan {
        require(loadedVisible >= 0 && visibleCap >= 0 && loadedOffset >= 0 && step > 0)
        if (loadedVisible > visibleCap) {
            return OlderWindowPlan(nextVisibleCap = (visibleCap + step).coerceAtMost(loadedVisible))
        }
        if (loadedOffset == 0) return OlderWindowPlan(nextVisibleCap = visibleCap)
        val newOffset = (loadedOffset - step).coerceAtLeast(0)
        return OlderWindowPlan(
            nextVisibleCap = visibleCap + (loadedOffset - newOffset),
            fetchOffset = newOffset,
            fetchCount = loadedOffset - newOffset,
        )
    }

    /**
     * Auto-page only after the reader has left the opposite edge. A short list
     * that already shows both edges must not walk the database by itself.
     */
    fun historyEdgeAction(
        olderSentinelVisible: Boolean,
        newerSentinelVisible: Boolean,
        newestEdgeVisible: Boolean,
        oldestEdgeVisible: Boolean,
        hasOlder: Boolean,
        hasNewer: Boolean,
    ): HistoryEdgeAction {
        val spansBothEdges = newestEdgeVisible && oldestEdgeVisible
        return HistoryEdgeAction(
            loadOlder = hasOlder && olderSentinelVisible && !spansBothEdges,
            loadNewer = hasNewer && newerSentinelVisible && !spansBothEdges,
        )
    }

    /** Index in a reverseLayout list whose message items are newest-first. */
    fun lazyIndexOfOldestFirstKey(
        oldestFirstCount: Int,
        keyIndexInOldestFirst: Int,
        itemsBeforeMessages: Int,
    ): Int {
        require(oldestFirstCount > 0)
        require(keyIndexInOldestFirst in 0 until oldestFirstCount)
        require(itemsBeforeMessages >= 0)
        return itemsBeforeMessages + (oldestFirstCount - 1 - keyIndexInOldestFirst)
    }

    /**
     * Move a loaded range toward older rows while retaining at most [capacity]
     * rows. The newest rows beyond that capacity are intentionally evicted.
     */
    fun moveOlder(
        loadedStart: Int,
        loadedCount: Int,
        fetchStart: Int,
        fetchCount: Int,
        capacity: Int,
    ): BoundedWindowMove {
        require(loadedStart >= 0 && loadedCount >= 0 && fetchStart >= 0 && fetchCount >= 0 && capacity > 0)
        val combinedStart = minOf(loadedStart, fetchStart)
        val combinedEnd = maxOf(loadedStart + loadedCount, fetchStart + fetchCount)
        val combinedCount = (combinedEnd - combinedStart).coerceAtLeast(0)
        val retainedCount = minOf(capacity, combinedCount)
        return BoundedWindowMove(combinedStart, retainedCount)
    }

    /**
     * Append newly persisted rows to the tail. Once the fixed window is full,
     * the oldest loaded rows leave memory; the database remains the source of
     * truth and can be paged back in.
     */
    fun appendTail(
        totalBefore: Int,
        loadedStart: Int,
        loadedCount: Int,
        appended: Int,
        capacity: Int,
    ): BoundedWindowMove {
        require(totalBefore >= 0 && loadedStart >= 0 && loadedCount >= 0 && appended >= 0 && capacity > 0)
        val totalAfter = totalBefore + appended
        val oldEnd = loadedStart + loadedCount
        val contiguous = oldEnd == totalBefore
        val newEnd = if (contiguous) totalAfter else oldEnd
        val newStart = if (contiguous) loadedStart else loadedStart
        val retainedCount = minOf(capacity, (newEnd - newStart).coerceAtLeast(0))
        val retainedStart = (newEnd - retainedCount).coerceAtLeast(newStart)
        return BoundedWindowMove(retainedStart, retainedCount)
    }
}
