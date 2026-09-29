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
        val opinion = GroupChat.opinionPrompt("Gemini", "质疑", "分析这份财报", "DeepSeek：收入口径不一致", "")
        assertTrue(opinion.contains("分析这份财报"))
        assertTrue(opinion.contains("DeepSeek：收入口径不一致"))
        assertTrue(opinion.contains("质疑"))
        val summary = GroupChat.summaryPrompt("分析这份财报", opinion)
        assertTrue(summary.contains("共识"))
        assertTrue(summary.contains("这是讨论的结束汇报"))
        assertEquals("speaker", GroupChat.SPEAKER_PART)
    }

    @Test
    fun mentionAddressesOneSpeakerAndCloseIsExplicit() {
        assertEquals("DeepSeek V3", GroupChat.addressedName("@deepseek 你怎么看", listOf("Gemini", "DeepSeek V3")))
        assertEquals(null, GroupChat.addressedName("邮件是 a@b.com", listOf("Gemini")))
        assertTrue(GroupChat.isCloseRequest("总结一下"))
        assertTrue(GroupChat.isCloseRequest("请总结这场讨论"))
        assertFalse(GroupChat.isCloseRequest("先别总结，继续讨论风险"))
        assertEquals("主张", GroupChat.stance(0))
        assertEquals("质疑", GroupChat.stance(1))
        assertEquals("主张", GroupChat.stance(4))
    }

    @Test
    fun mentionUsesModelIdAndIgnoresSkillPaths() {
        val targets = listOf(
            GroupChat.Addressable("苏苏", listOf("苏苏", "mimo-v2.6-pro")),
            GroupChat.Addressable("DeepSeek V3", listOf("DeepSeek V3", "deepseek-chat")),
        )
        assertEquals("苏苏", GroupChat.resolveAddress("@mimo-v2.6-pro 你先说", targets))
        assertEquals("苏苏", GroupChat.resolveAddress("＠苏苏 继续", targets))
        assertEquals("DeepSeek V3", GroupChat.resolveAddress("先问一下 @DeepSeek V3 这个风险", targets))
        assertEquals(null, GroupChat.resolveAddress("@skills/逆向技能路由 看看", targets))
        assertEquals(null, GroupChat.resolveAddress("邮件是 a@b.com", targets))
    }

    @Test
    fun mentionPickerListsModelsNotSkills() {
        val roster = GroupChat.mentionCandidates(
            GroupChat.MentionCandidate("mimo-v2.6-pro", "mimo-v2.6-pro", "unknown", host = true),
            listOf(
                GroupChat.MentionCandidate("DeepSeek V3", "deepseek-chat", "deepseek", host = false),
                GroupChat.MentionCandidate("DeepSeek V3", "deepseek-reasoner", "deepseek", host = false),
            ),
        )
        assertEquals(listOf("mimo-v2.6-pro", "DeepSeek V3", "DeepSeek V3"), roster.map { it.name })
        assertEquals(emptyList<String>(), GroupChat.filterMentions(roster, "逆向").map { it.name })
        assertEquals(listOf("mimo-v2.6-pro"), GroupChat.filterMentions(roster, "mimo").map { it.name })
        val withSoul = roster.map { if (it.host) it.copy(extra = "苏苏") else it }
        assertEquals(listOf("mimo-v2.6-pro"), GroupChat.filterMentions(withSoul, "苏苏").map { it.name })
        assertEquals("mimo-v2.6-pro", GroupChat.mentionInsertToken(roster[0], roster))
        assertEquals("deepseek-reasoner", GroupChat.mentionInsertToken(roster[2], roster))
    }

    @Test
    fun collidingDisplayNamesStillAddressOneModel() {
        val targets = listOf(
            GroupChat.Addressable(
                "DeepSeek V3",
                aliases = listOf("DeepSeek V3", "deepseek-chat"),
                key = "deepseek-chat",
            ),
            GroupChat.Addressable(
                "DeepSeek V3",
                aliases = listOf("DeepSeek V3", "deepseek-reasoner"),
                key = "deepseek-reasoner",
            ),
        )
        assertEquals("deepseek-reasoner", GroupChat.resolveTarget("@deepseek-reasoner 你说", targets)?.key)
        assertEquals("deepseek-chat", GroupChat.resolveTarget("@DeepSeek V3 你说", targets)?.key)
    }
}
