package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupChatTest {
    @Test
    fun transcriptAttributesEverySpeaker() {
        val text = GroupChat.transcript(
            listOf(
                GroupChat.Line("Gemini", "先看需求"),
                GroupChat.Line("DeepSeek", "还要看风险"),
            ),
        )
        assertTrue(text.contains("Gemini：先看需求"))
        assertTrue(text.contains("DeepSeek：还要看风险"))
    }

    @Test
    fun passRepliesAreNotShown() {
        assertTrue(GroupChat.isPass("PASS"))
        assertTrue(GroupChat.isPass("pass。"))
        assertTrue(GroupChat.isPass("无补充"))
        assertFalse(GroupChat.isPass("我补充一个风险：样本太少。"))
    }

    @Test
    fun groupSpeakersAreNotMerged() {
        assertFalse(GroupChat.shouldMergeAssistantTurns("Gemini", "DeepSeek"))
        assertFalse(GroupChat.shouldMergeAssistantTurns(null, "Gemini"))
        assertTrue(GroupChat.shouldMergeAssistantTurns(null, null))
        assertTrue(GroupChat.shouldMergeAssistantTurns("", " "))
    }

    @Test
    fun vendorMatchIgnoresDirtyModelFields() {
        assertEquals("deepseek", GroupChat.vendorKey("models/DeepSeek-V3:latest", "DeepSeek（官方）", "openAI"))
        assertEquals("deepseek", GroupChat.vendorKey("deep-seek_chat", null))
        assertEquals("openai", GroupChat.vendorKey("gpt4o-mini", "【自定义】GPT-4o"))
        assertEquals("openai", GroupChat.vendorKey("o1preview", null))
        assertEquals("doubao", GroupChat.vendorKey("Doubao-pro-32k", "【豆包】"))
        assertEquals("gemini", GroupChat.vendorKey("gemini2.5pro", "models/gemini-2.5-flash"))
        assertEquals("anthropic", GroupChat.vendorKey("claude-3.5-sonnet（官方）", null))
        assertEquals("xai", GroupChat.vendorKey("x.ai/grok-3", null))
        assertEquals("qwen", GroupChat.vendorKey("qwen2.5-72b", "通义千问"))
        assertEquals("anthropic", GroupChat.vendorKey("custom-model", null, "anthropic"))
        assertEquals("unknown", GroupChat.vendorKey("metadata-exporter", null))
    }

    @Test
    fun promptsKeepTheUserQuestionAndPriorSpeakers() {
        val opinion = GroupChat.opinionPrompt("Gemini", "分析这份财报", "主持人：核对收入", "DeepSeek：收入口径不一致")
        assertTrue(opinion.contains("分析这份财报"))
        assertTrue(opinion.contains("DeepSeek：收入口径不一致"))
        val summary = GroupChat.summaryPrompt("分析这份财报", opinion, closed = true)
        assertTrue(summary.contains("共识"))
        assertTrue(summary.contains("这是讨论的结束汇报"))
        assertEquals("speaker", GroupChat.SPEAKER_PART)
    }
}
