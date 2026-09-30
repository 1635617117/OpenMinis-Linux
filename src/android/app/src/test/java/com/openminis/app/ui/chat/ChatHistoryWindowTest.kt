package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryWindowTest {
    private fun user(sort: Int) = ChatHistoryWindow.SortAnchor(sort, isUser = true)
    private fun other(sort: Int) = ChatHistoryWindow.SortAnchor(sort, isUser = false)

    @Test
    fun olderPageStopsOnTheNthUserAndKeepsTheTurn() {
        val anchors = listOf(
            other(90),
            other(80),
            user(70),
            other(60),
            user(50),
            other(40),
            user(30),
        )
        val probe = ChatHistoryWindow.absorbOlder(anchors, turnCount = 2)
        assertEquals(50, probe.startSortOrder)
        assertEquals(2, probe.usersIncluded)
        assertFalse(probe.needsMore)
    }

    @Test
    fun olderPageAsksForMoreWhenTheProbeEndsMidTurn() {
        val probe = ChatHistoryWindow.absorbOlder(listOf(other(12), other(11)), turnCount = 5)
        assertEquals(11, probe.startSortOrder)
        assertEquals(0, probe.usersIncluded)
        assertTrue(probe.needsMore)
    }

    @Test
    fun newerPageDoesNotStartTheNextTurn() {
        val anchors = listOf(
            user(10),
            other(11),
            other(12),
            user(13),
            other(14),
        )
        val probe = ChatHistoryWindow.absorbNewer(anchors, turnCount = 1)
        assertEquals(12, probe.endSortOrder)
        assertEquals(1, probe.usersIncluded)
        assertFalse(probe.needsMore)
    }

    @Test
    fun newerPageKeepsReadingRepliesAfterTheUserMessage() {
        val probe = ChatHistoryWindow.absorbNewer(listOf(user(10)), turnCount = 1)
        assertEquals(10, probe.endSortOrder)
        assertTrue(probe.needsMore)
    }

    @Test
    fun olderTurnCountContinuesAcrossAnchorProbes() {
        val first = ChatHistoryWindow.absorbOlder(
            listOf(other(100), user(90), other(80), user(70)),
            turnCount = 3,
        )
        assertEquals(2, first.usersIncluded)
        assertTrue(first.needsMore)
        val second = ChatHistoryWindow.absorbOlder(
            listOf(other(60), user(50), other(40)),
            turnCount = 3,
            usersAlready = first.usersIncluded,
        )
        assertEquals(50, second.startSortOrder)
        assertEquals(3, second.usersIncluded)
        assertFalse(second.needsMore)
    }

    @Test
    fun newerTurnCountStopsAtTheNextUserAcrossAnchorProbes() {
        val first = ChatHistoryWindow.absorbNewer(
            listOf(user(10), other(11), user(12)),
            turnCount = 2,
        )
        assertEquals(2, first.usersIncluded)
        assertTrue(first.needsMore)
        val second = ChatHistoryWindow.absorbNewer(
            listOf(other(13), user(14), other(15)),
            turnCount = 2,
            usersAlready = first.usersIncluded,
        )
        assertEquals(13, second.endSortOrder)
        assertEquals(2, second.usersIncluded)
        assertFalse(second.needsMore)
    }

    @Test
    fun prependCompensationDoesNotMoveTheVisibleIndex() {
        assertEquals(4, ChatHistoryWindow.compensatedLazyIndex(4, insertedBeforeAnchor = 0))
    }

    @Test
    fun appendCompensationShiftsByTheInsertedPrefix() {
        assertEquals(7, ChatHistoryWindow.compensatedLazyIndex(4, insertedBeforeAnchor = 3))
    }

    @Test
    fun shortListThatShowsBothEdgesDoesNotAutoPage() {
        val request = ChatHistoryWindow.historyEdgeAction(
            hasOlder = true,
            hasNewer = true,
            olderSentinelVisible = true,
            newerSentinelVisible = true,
            newestEdgeVisible = true,
            oldestEdgeVisible = true,
        )
        assertFalse(request.loadOlder)
        assertFalse(request.loadNewer)
    }

    @Test
    fun onlyTheOlderEdgePagesOlder() {
        val request = ChatHistoryWindow.historyEdgeAction(
            hasOlder = true,
            hasNewer = false,
            olderSentinelVisible = true,
            newerSentinelVisible = false,
            newestEdgeVisible = false,
            oldestEdgeVisible = true,
        )
        assertTrue(request.loadOlder)
        assertFalse(request.loadNewer)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sortRangeRejectsReversedBounds() {
        SortRange(8, 3)
    }

    @Test
    fun sortRangeUsesHalfOpenBounds() {
        val range = SortRange(3, 8)
        assertEquals(3, range.startInclusive)
        assertEquals(8, range.endExclusive)
    }

    @Test
    fun lazyIndexKeepsTheSameRowWhenOlderItemsArePrepended() {
        val before = ChatHistoryWindow.lazyIndexOfOldestFirstKey(
            oldestFirstCount = 6,
            keyIndexInOldestFirst = 4,
            itemsBeforeMessages = 1,
        )
        val after = ChatHistoryWindow.lazyIndexOfOldestFirstKey(
            oldestFirstCount = 9,
            keyIndexInOldestFirst = 7,
            itemsBeforeMessages = 1,
        )
        assertEquals(before, after)
    }
}
