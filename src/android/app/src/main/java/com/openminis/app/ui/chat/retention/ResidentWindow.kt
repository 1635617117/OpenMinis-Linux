package com.openminis.app.ui.chat.retention

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * The model-context retention rule, and the reason the chat surface is not
 * part of it.
 *
 * The reported OOM was a 4,562-message session (5.4M chars, 4,288 tool
 * messages) whose oversized tool output sits in the model context. 2.0.10
 * also spilled the RENDERED messages, replacing terminal tool blocks with an
 * `[CONTEXT OFFLOADED] N chars at ...` stub on every send. Nothing renders
 * that stub back, so the user lost a chunk of the transcript on the next
 * message sent — a byte saving that is really silent data loss.
 */
internal object ResidentWindow {
    const val RESIDENT_BYTES = HotWindow.RESIDENT_BYTES

    fun bytesOf(history: List<LLMMessage>): Long = history.sumOf { msg ->
        msg.content.length.toLong() * 2 +
            (msg.reasoningContent?.length?.toLong() ?: 0L) * 2 +
            msg.contentParts.sumOf { part ->
                when (part) {
                    is AgentContentPart.Text -> part.text.length.toLong() * 2
                    is AgentContentPart.ToolResult ->
                        part.content.length.toLong() * 2 + (part.imageData?.size?.toLong() ?: 0L)
                    is AgentContentPart.ToolUse -> part.input.toString().length.toLong() * 2
                    is AgentContentPart.ImageData -> part.data.size.toLong()
                }
            }
    }

    /**
     * How many leading entries to drop so the history is at most
     * [maxMessages] long, without orphaning a tool result.
     */
    fun countCut(history: List<LLMMessage>, maxMessages: Int): Int {
        val overflow = history.size - maxMessages
        if (overflow <= 0) return 0
        return firstLegalCut(history, overflow) ?: 0
    }

    /**
     * How many leading entries to drop so the history fits [budget].
     *
     * The last entry is never dropped: that is the turn in flight, and
     * emptying it would leave the model with nothing to answer.
     *
     * Each round asks whether the CURRENT cut point is already legal before
     * advancing. Skipping straight to `cut + 1` would walk past a legal
     * boundary sitting at `cut` itself — a plain-text head followed by bare
     * tool results is the common shape, and it must be cuttable at the head.
     */
    fun byteCut(history: List<LLMMessage>, budget: Long = RESIDENT_BYTES): Int {
        if (history.size <= 1) return 0
        if (bytesOf(history) <= budget) return 0
        var cut = 0
        while (cut < history.size - 1) {
            if (isLegalBoundary(history, cut)) {
                if (bytesOf(history.subList(cut, history.size)) <= budget) return cut
            }
            val next = firstLegalCut(history, cut + 1) ?: break
            cut = next
        }
        // The last legal cut is the best that can be done: everything after
        // it is either over budget or uncuttable without orphaning.
        if (cut > 0 && isLegalBoundary(history, cut)) return cut
        return 0
    }

    /**
     * A boundary is legal when the first RETAINED entry does not carry a
     * tool result whose tool use it does not also carry. The agent loop
     * keeps a turn's use and result in the same message, so a self-contained
     * tool turn is a fine boundary — demanding "no result at all" made the
     * cap a no-op on exactly the tool-heavy sessions that need it.
     */
    private fun isLegalBoundary(history: List<LLMMessage>, index: Int): Boolean {
        if (index >= history.size) return false
        val parts = history[index].contentParts
        val hasResult = parts.any { it is AgentContentPart.ToolResult }
        val hasUse = parts.any { it is AgentContentPart.ToolUse }
        return !hasResult || hasUse
    }

    /** Smallest index >= [from] that is a legal cut point, or `null`. */
    private fun firstLegalCut(history: List<LLMMessage>, from: Int): Int? {
        var i = from.coerceIn(0, history.size)
        while (i < history.size) {
            if (isLegalBoundary(history, i)) return i
            i++
        }
        return null
    }
}
