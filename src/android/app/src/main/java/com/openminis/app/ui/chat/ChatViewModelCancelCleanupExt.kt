package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * After the user stops a streaming turn, reconcile UI + agentHistory so
 * the conversation is valid on the next API call and resumable via
 * [resume]. Mirrors iOS AIChatViewModel.handleUserCancelledCleanup
 * (Case 1: tool cancel, Case 2: text cancel).
 *
 *  - Case 1: any in-flight tool block is flipped to [ToolBlockStatus.CANCELLED]
 *    and a synthetic tool_result with [CANCELLED_MARKER] is persisted so
 *    tool_use/tool_result stays paired.
 *  - Case 2: if there was partial assistant text streamed (and no tool
 *    cancel), commit the partial text + a truncation `<system-reminder>`
 *    to agentHistory so the model knows the prior turn was cut short.
 *
 * Always sets [_canResume] = true when there is something to resume from.
 */
internal fun ChatViewModel.handleUserCancelledCleanup() {
    val msgs = _messages.value.toMutableList()
    val lastIdx = msgs.indexOfLast { it.role == "assistant" }
    if (lastIdx < 0) return
    var last = msgs[lastIdx]

    // T73: clear "Minis is thinking…" the moment the user taps Stop.
    // isAwaitingModelResponse is set true at runAgentLoop entry (≈ line
    // 2785) so the typing indicator shows during the initial request
    // gap before the first stream chunk. The cancel paths below didn't
    // reset it, so after Stop the indicator stayed live forever even
    // though the streamJob was already torn down. Reset before either
    // case runs so both tool-cancel and text-cancel paths benefit.
    if (last.isAwaitingModelResponse) {
        last = last.copy(isAwaitingModelResponse = false)
        msgs[lastIdx] = last
        _messages.value = msgs
    }

    // Case 1: cancel during tool execution. Flip in-flight tool blocks to
    // CANCELLED and persist matching tool_result rows.
    val cancelledIds = mutableListOf<Pair<String, String>>() // (toolUseId, toolName)
    val updatedBlocks = last.toolBlocks.map { b ->
        val s = b.toolStatus
        if (s == ToolBlockStatus.STREAMING || s == ToolBlockStatus.PENDING || s == ToolBlockStatus.RUNNING) {
            if (b.kind == "tool_use") cancelledIds.add(b.id to b.toolName)
            b.copy(toolStatus = ToolBlockStatus.CANCELLED)
        } else b
    }
    val hadInflightTools = cancelledIds.isNotEmpty()
    if (hadInflightTools) {
        msgs[lastIdx] = last.copy(toolBlocks = updatedBlocks)
        _messages.value = msgs
        val parts = cancelledIds.map { (id, name) ->
            AgentContentPart.ToolResult(
                id = id,
                name = name,
                content = ChatViewModel.CANCELLED_MARKER,
                isError = true,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            persistToolResultMessage(parts)
        }
        // [T-android-group-pause-badge-restamp] A LIVE interruption just
        // happened: this is a real entry into the paused state, so the
        // badge's 24h freshness stamp must be refreshed. Cancel any
        // unconsumed re-detection mark left by a prior load so it cannot
        // suppress the re-stamp here.
        markLiveInterruption()
        _canResume.value = true
    }

    // Preserve assistant text even when cancellation happened during a tool.
    // Tool blocks and their matching tool_result rows are handled above; the
    // text part is a separate assistant turn and must also survive reload.
    // Case 2: cancel during text streaming. If partial assistant text
    // exists and agentHistory does not already end with the assistant
    // turn we're on, commit the partial text + truncation marker so the
    // model sees an interrupted prior turn on the next call.
    val partialText = buildString {
        if (last.content.isNotEmpty()) append(last.content)
        for (b in last.toolBlocks) {
            if (b.kind == "text" && b.content.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(b.content)
            }
        }
    }
    val historyEndsWithAssistant =
        agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT

    // Case 0 (T-ios-stop-clear-thinking-and-partial — Android port):
    // Stop fired while still in the pre-first-chunk thinking gap (no
    // partial text, no tool_use emitted, no committed history for this
    // turn). The placeholder ChatMessage runAgentLoop pushed at L5248 is
    // not in the DB and would otherwise render as an empty "Minis Ultra" header
    // bubble with no body. Drop it so the UI snaps back to idle the
    // instant the user taps Stop. Mirrors the iOS #566/#569 boundary:
    // a candidate WITH real text or any emitted tool_use is kept (handled
    // by Case 1 / Case 2 below); a thinking-only placeholder is not.
    val hasAnyToolUse = last.toolBlocks.any { it.kind == "tool_use" }
    if (partialText.isEmpty() && !hasAnyToolUse && !historyEndsWithAssistant) {
        msgs.removeAt(lastIdx)
        _messages.value = msgs
        return
    }

    if (partialText.isNotEmpty() && !historyEndsWithAssistant) {
        val parts = listOf<AgentContentPart>(
            AgentContentPart.Text(partialText),
            AgentContentPart.Text(
                "<system-reminder>The user stopped this response. Content may be incomplete.</system-reminder>"
            ),
        )
        appendBoundedHistory(
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = partialText,
                contentParts = parts,
            )
        )
        viewModelScope.launch(Dispatchers.IO) {
            val partsJson = buildAssistantPartsJson(parts)
            chatRepository.appendMessage(activeSessionId, "assistant", partsJson)
        }
        // [T-android-group-pause-badge-restamp] A LIVE interruption just
        // happened: this is a real entry into the paused state, so the
        // badge's 24h freshness stamp must be refreshed. Cancel any
        // unconsumed re-detection mark left by a prior load so it cannot
        // suppress the re-stamp here.
        markLiveInterruption()
        _canResume.value = true
    } else if (historyEndsWithAssistant || hadInflightTools) {
        // Already committed or represented by the assistant tool-call turn;
        // still mark the live run as resumable.
        // [T-android-group-pause-badge-restamp] A LIVE interruption just
        // happened: this is a real entry into the paused state, so the
        // badge's 24h freshness stamp must be refreshed. Cancel any
        // unconsumed re-detection mark left by a prior load so it cannot
        // suppress the re-stamp here.
        markLiveInterruption()
        _canResume.value = true
    }
}
