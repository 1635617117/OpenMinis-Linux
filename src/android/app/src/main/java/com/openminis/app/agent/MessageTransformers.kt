package com.openminis.app.agent

import java.io.File

/**
 * [T-message-transformers] Send-path text sanitizers (RikkaHub
 * MessageTransformer, scoped down to what our own stream pipeline can
 * legitimately leak) + user-message context injections.
 *
 * Two seams:
 *  - [transformAssistant] cleans assistant texts inside
 *    `effectiveAgentHistory` so leaked thinking tags and control artifacts
 *    cannot be imitated and amplified turn over turn.
 *  - [transformUser] enriches the LATEST user message with optional context
 *    the model otherwise forgets to check: the real current time, and a
 *    workspace pointer so long sessions keep finding their files.
 *    Both are designed to be prefix-cache-friendly: they only touch the
 *    final user message, never the static prompt head.
 */
interface MessageTransformer {
    val id: String
    fun transform(text: String): String
}

/** Optional per-message context enrichment hook (RikkaHub TimeReminder / WorkspaceReminder family). */
interface UserMessageTransformer {
    val id: String
    /** Returns null when nothing to inject (keeps the request byte-identical). */
    fun transformUser(text: String, context: UserTransformContext): String?
}

/** Call-site context for [UserMessageTransformer] — provider-free so the chain is pure-JVM testable. */
data class UserTransformContext(
    /** Epoch millis of "now". */
    val nowMillis: Long = System.currentTimeMillis(),
    /** Set when the session's user-made workspace path is resolvable on the host. */
    val workspaceHint: String? = null,
)

object MessageTransformerChain {
    val transformers: List<MessageTransformer> = listOf(
        ReasoningTagStripper,
    )

    val userTransformers: List<UserMessageTransformer> = listOf(
        TimeReminder,
        WorkspaceReminder,
    )

    /** Clean an assistant reply before it is replayed to the model. */
    fun apply(text: String): String = transformers.fold(text) { t, tr -> tr.transform(t) }

    /**
     * Enrich a user message before send. Returns the original text when no
     * transformer has anything to add — the empty-append case must not
     * grow the payload.
     */
    fun applyUser(text: String, context: UserTransformContext): String {
        val additions = userTransformers.mapNotNull { it.transformUser(text, context) }
        if (additions.isEmpty()) return text
        return text + "\n\n" + additions.joinToString("\n\n")
    }

    /**
     * Strips thinking/reasoning tag blocks that occasionally leak into the
     * visible answer body (QwQ/DeepSeek/Gemini variants): paired
     * `<thinking>…</thinking>` / `<思考>…</思考>` / special-token
     * `<|thinking|>…<|/thinking|>` forms, case-insensitive, across newlines.
     */
    private object ReasoningTagStripper : MessageTransformer {
        override val id = "reasoning-tag-strip"

        private val namedBlocks = Regex(
            """(?s)<\s*(thinking|think|reasoning|analysis|分析|思考)\s*>(.*?)</\s*\1\s*>""",
            RegexOption.IGNORE_CASE,
        )
        private val specialTokenBlocks = Regex(
            """(?s)<\|(thinking|think|reasoning)\|>(.*?)<\|/\1\|>""",
            RegexOption.IGNORE_CASE,
        )

        override fun transform(text: String): String =
            specialTokenBlocks.replace(namedBlocks.replace(text, ""), "")
    }

    /**
     * [T-time-reminder] Injects the wall-clock time into the LATEST user
     * message. Long agent turns (multi-tool, queued prompts, goal
     * continuations) can run far past the moment the user sent the text; a
     * model answering "what's the weather now" or "remind me in an hour"
     * without knowing the current time answers from the conversation's start.
     * The system prompt's Runtime context only updates when the prompt is
     * rebuilt, so this catches intra-loop drift.
     */
    private object TimeReminder : UserMessageTransformer {
        override val id = "time-reminder"

        private val format = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        override fun transformUser(text: String, context: UserTransformContext): String? {
            // Skip for messages that clearly aren't time-sensitive
            // (pure code/file operations don't benefit from a clock stamp).
            val needsTime = TIME_SENSITIVE_HINTS.any { hint -> text.contains(hint, ignoreCase = true) }
            if (!needsTime) return null
            val now = java.time.Instant.ofEpochMilli(context.nowMillis)
                .atZone(java.util.TimeZone.getDefault().toZoneId())
            return "<system-note>当前时间: ${format.format(now)}</system-note>"
        }

        private val TIME_SENSITIVE_HINTS = listOf(
            "现在", "now", "today", "今天", "几点", "时间", "weather", "天气",
            "remind", "提醒", "schedule", "安排", "分钟", "小时", "minute", "hour",
        )
    }

    /**
     * [T-workspace-reminder] When the caller resolves the user's workspace
     * (i.e. files actually live somewhere), remind the model where its own
     * outputs go. Prevents the classic long-session drift where the agent
     * keeps writing into a stale or wrong directory after offloads.
     */
    private object WorkspaceReminder : UserMessageTransformer {
        override val id = "workspace-reminder"

        override fun transformUser(text: String, context: UserTransformContext): String? {
            val hint = context.workspaceHint ?: return null
            return "<system-note>工作目录: $hint — 读取和写入用户文件时优先使用该目录。</system-note>"
        }
    }

    /**
     * [T-document-as-prompt] Rewrites minis:// resource links in a user
     * message into explicit read instructions. Some models silently ignore
     * bare `minis://…` URLs; telling them "this is a readable file, use
     * file_read with this path" converts a passive link into an actionable
     * prompt — the RikkaHub DocumentAsPrompt idea, but resolution stays on
     * the caller side (no inline file content, so no context blowup).
     */
    fun expandResourceReferences(text: String): String {
        val resourceLinks = Regex("""minis://(workspace|shared|attachments|offloads)/([A-Za-z0-9._%+/\-]+)""")
        val matches = resourceLinks.findAll(text).toList()
        if (matches.isEmpty()) return text
        val sb = StringBuilder()
        val additions = StringBuilder()
        matches.forEach { m ->
            val hostPath = "/var/minis/${m.groupValues[1]}/${m.groupValues[2]}"
            if (additions.isEmpty()) additions.append("\n\n<system-note>消息中引用了文件，读取时请用: file_read path=\"$hostPath\"")
            else additions.append("；另可读 $hostPath")
        }
        if (additions.isEmpty()) return text
        sb.append(text).append(additions).append("</system-note>")
        return sb.toString()
    }
}

/** Host-side workspace resolution hook (Android-specific; kept out of the pure chain). */
fun interface WorkspaceHintResolver {
    fun resolve(): String?
}
