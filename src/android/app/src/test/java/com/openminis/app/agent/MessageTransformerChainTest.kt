package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class MessageTransformerChainTest {

    // ── ReasoningTagStripper (existing, regression) ──

    @Test
    fun stripsNamedAndSpecialTokenBlocks() {
        val text = "answer<thinking>secret</thinking> tail\n<|thinking|>more<|/thinking|> end"
        val out = MessageTransformerChain.apply(text)
        assertTrue(!out.contains("secret"))
        assertTrue(!out.contains("<|thinking|>"))
        assertTrue(out.contains("answer") && out.contains("tail") && out.contains("end"))
    }

    // ── TimeReminder ──

    @Test
    fun timeReminderInjectsForTimeSensitiveText() {
        val ctx = UserTransformContext(
            nowMillis = java.time.LocalDateTime.of(2026, 10, 1, 14, 30)
                .atZone(TimeZone.getDefault().toZoneId())
                .toInstant().toEpochMilli(),
        )
        val out = MessageTransformerChain.applyUser("现在几点了？", ctx)
        assertTrue(out.contains("当前时间"))
        assertTrue(out.contains("14:30"))
    }

    @Test
    fun timeReminderSkipsCodeOnlyMessages() {
        val ctx = UserTransformContext(nowMillis = 1_000L)
        val out = MessageTransformerChain.applyUser("把 /tmp/a.py 第三行的 print 改掉", ctx)
        assertTrue(!out.contains("system-note"))
        assertEquals("把 /tmp/a.py 第三行的 print 改掉", out)
    }

    // ── WorkspaceReminder ──

    @Test
    fun workspaceReminderInjectsWhenHintPresent() {
        val ctx = UserTransformContext(workspaceHint = "/var/minis/workspace")
        val out = MessageTransformerChain.applyUser("改一下报告", ctx)
        assertTrue(out.contains("工作目录"))
        assertTrue(out.contains("/var/minis/workspace"))
    }

    @Test
    fun workspaceReminderNoopWithoutHint() {
        val out = MessageTransformerChain.applyUser("改一下报告", UserTransformContext())
        assertEquals("改一下报告", out)
    }

    // ── expandResourceReferences ──

    @Test
    fun expandsMinisLinksToReadInstructions() {
        val text = "看一下 minis://workspace/data.csv 的结果"
        val out = MessageTransformerChain.expandResourceReferences(text)
        assertTrue(out.contains("file_read"))
        assertTrue(out.contains("/var/minis/workspace/data.csv"))
    }

    @Test
    fun noLinksNoExpansion() {
        val text = "帮我算 1+1"
        assertEquals(text, MessageTransformerChain.expandResourceReferences(text))
    }

    // ── chain idempotence guard ──

    @Test
    fun assistantChainDoesNotTouchUserTransformers() {
        val text = "现在几点了？"
        // apply() 只跑 assistant 清洗，不做时间注入。
        assertEquals(text, MessageTransformerChain.apply(text))
    }
}