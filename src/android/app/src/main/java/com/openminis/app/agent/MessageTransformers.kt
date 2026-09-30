package com.openminis.app.agent

/**
 * [T-message-transformers] Send-path text sanitizers (RikkaHub
 * MessageTransformer, scoped down to what our own stream pipeline can
 * legitimately leak).
 *
 * Applied inside `effectiveAgentHistory` to assistant texts only — the
 * model never sees a previous reply's leaked thinking tags, so the
 * residue cannot be imitated and amplified turn over turn.
 */
interface MessageTransformer {
    val id: String
    fun transform(text: String): String
}

object MessageTransformerChain {
    val transformers: List<MessageTransformer> = listOf(
        ReasoningTagStripper,
    )

    fun apply(text: String): String = transformers.fold(text) { t, tr -> tr.transform(t) }

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
}
