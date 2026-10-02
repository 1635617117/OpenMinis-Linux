package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the long-session display path.
 *
 * The UI list is a contiguous oldest-to-newest model list and is reversed only
 * at the LazyColumn boundary. These tests deliberately exercise a middle row,
 * where a head/tail-only regression would otherwise pass visually.
 */
class ChatTimelineVisibilityRegressionTest {
    private fun user(index: Int) = ChatMessage(
        id = "user-$index",
        role = "user",
        content = "middle-user-$index",
    )

    private fun assistant(index: Int, withProcess: Boolean = false) = ChatMessage(
        id = "assistant-$index",
        role = "assistant",
        content = "",
        toolBlocks = buildList {
            if (withProcess) add(AssistantBlock("thinking-$index", "thinking", "reasoning"))
            add(AssistantBlock("text-$index", "text", "middle-assistant-$index"))
            if (withProcess) add(
                AssistantBlock(
                    id = "tool-$index",
                    kind = "tool_use",
                    toolName = "shell",
                    toolStatus = ToolBlockStatus.SUCCESS,
                ),
            )
        },
    )

    private fun session(count: Int = 180): List<ChatMessage> = buildList {
        repeat(count) { index ->
            add(user(index))
            add(assistant(index, withProcess = index % 11 == 0))
        }
    }

    @Test
    fun longSessionKeepsMiddleUserAndAssistantRows() {
        val messages = session()
        val rows = buildFlatChatItems(messages, foldAiProcess = true)
        val keys = rows.map { it.key }

        assertEquals(keys.size, keys.toSet().size)
        assertTrue(rows.any { it is FlatChatItem.UserBubble && it.message.id == "user-90" })
        assertTrue(rows.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
            .any { it.messageId == "assistant-90" && it.rawText.contains("middle-assistant-90") })
    }

    @Test
    fun middleRowKeysSurviveOlderPrependAndNewerAppend() {
        val base = session(80)
        val target = buildFlatChatItems(base, foldAiProcess = true)
            .first { it is FlatChatItem.UserBubble && it.message.id == "user-40" }
        val prepended = listOf(user(-1), assistant(-1)) + base
        val appended = base + listOf(user(80), assistant(80))

        val before = buildFlatChatItems(base, foldAiProcess = true)
        val afterPrepend = buildFlatChatItems(prepended, foldAiProcess = true)
        val afterAppend = buildFlatChatItems(appended, foldAiProcess = true)

        assertEquals(target.key, afterPrepend.first { it is FlatChatItem.UserBubble && it.message.id == "user-40" }.key)
        assertEquals(target.key, afterAppend.first { it is FlatChatItem.UserBubble && it.message.id == "user-40" }.key)
        // A new assistant turn contributes its header and markdown row in
        // addition to the new user bubble.
        assertEquals(before.size + 3, afterAppend.size)
    }

    @Test
    fun streamingOverlayChangesContentWithoutDroppingMiddleMessage() {
        val base = session(100)
        val middle = base[81] // assistant-40
        val delta = StreamingDelta(
            content = "streamed-middle",
            toolBlocks = middle.toolBlocks,
            isAwaitingModelResponse = false,
        )
        val overlaid = mergeStreamingOverlay(base, mapOf(middle.id to delta))
        val rows = buildFlatChatItems(overlaid, foldAiProcess = true)

        assertTrue(rows.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
            .any { it.messageId == middle.id && it.rawText == "streamed-middle" })
    }

    @Test
    fun modelHistoryMustUseTheMiddleSentinelAfterResidentWindowRebuild() {
        val messages = session(260)
        val sentinel = messages[80]
        val rebuilt = messages.map { message ->
            com.openminis.app.data.model.LLMMessage(
                role = if (message.role == "user") com.openminis.app.data.model.LLMMessage.Role.USER
                else com.openminis.app.data.model.LLMMessage.Role.ASSISTANT,
                content = message.content,
                dbMessageId = message.id,
                contentParts = message.toolBlocks.map { block ->
                    com.openminis.app.data.model.AgentContentPart.Text(block.content)
                },
            )
        }
        val middle = rebuilt.first { it.dbMessageId == sentinel.id }
        assertEquals("middle-user-40", middle.content)
        assertTrue(rebuilt.indexOf(middle) < rebuilt.lastIndex)
    }

    @Test
    fun foldingProcessKeepsEveryTextBlockInLongSession() {
        val messages = session(120)
        val rows = buildFlatChatItems(messages, showCompletedToolCards = false, foldAiProcess = true)
        val textIds = rows.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
            .map { it.messageId }
            .toSet()

        assertEquals((0 until 120).map { "assistant-$it" }.toSet(), textIds)
    }
}
