package com.openminis.app.tools

/**
 * Same-page AI group chat. Each model speaks as itself. The host only frames
 * the question and writes the closing report; it does not impersonate the others.
 */
object GroupChat {
    const val SPEAKER_PART = "speaker"

    data class Line(val speaker: String, val text: String)

    fun transcript(lines: List<Line>): String =
        lines.joinToString("\n\n") { "${it.speaker}：${it.text.trim()}" }
            .ifBlank { "（还没有发言）" }

    fun isPass(text: String): Boolean {
        val normalized = text.trim().trimEnd('.', '。', '!', '！')
        return normalized.equals("PASS", ignoreCase = true) ||
            normalized == "无补充" ||
            normalized == "（无补充）"
    }

    /** Group utterances stay separate so each model keeps its own bubble. */
    fun shouldMergeAssistantTurns(prevSpeaker: String?, nextSpeaker: String?): Boolean =
        prevSpeaker.isNullOrBlank() && nextSpeaker.isNullOrBlank()

    fun framePrompt(userText: String, context: String): String = """
        请只整理这场讨论，不要替其他模型作答，也不要给出最终结论。
        用用户的语言写 4 到 8 句：问题是什么、已经知道什么、希望大家分别核对什么。
        不要调用工具。

        用户：
        $userText

        近期上下文：
        ${context.ifBlank { "（无）" }}
    """.trimIndent()

    fun bridgePrompt(userText: String, prior: String): String = """
        用户补充了新内容。请用 2 到 4 句转述给群聊，指出这次要大家回应的新问题。
        不要替其他模型作答，不要调用工具。

        用户补充：
        $userText

        已有讨论：
        $prior
    """.trimIndent()

    fun opinionPrompt(name: String, userText: String, brief: String, prior: String): String = """
        你是 $name。请针对下面的问题发表你自己的看法。
        可以同意或反对已有发言，但必须写出依据，以及你不确定的地方。
        不要扮演其他人，不要复述主持人的整理。
        需要查资料、读文件或检索时可以调用工具；工具过程不会展示，正文里不要描述工具调用。
        用用户的语言，控制在 180 到 420 字。

        用户：
        $userText

        主持人整理：
        $brief

        已有发言：
        ${prior.ifBlank { "（你是第一位发言者）" }}
    """.trimIndent()

    fun replyPrompt(name: String, userText: String, prior: String): String = """
        你是 $name。阅读其他人的发言，只补充一个他们没说到、或你认为说错了的点。
        如果没有新的观点，只回复 PASS。
        有新观点时用用户的语言写 60 到 180 字，不要调用工具，不要重复自己的上一轮。

        用户：
        $userText

        已有发言：
        $prior
    """.trimIndent()

    fun summaryPrompt(userText: String, prior: String, closed: Boolean): String = """
        你是主持人。请把这场群聊整理成给用户的汇报，不要编造没人说过的观点。
        用用户的语言，分成三段：共识、分歧、建议。
        ${if (closed) "这是讨论的结束汇报。" else "这是本轮汇报，用户还可以继续补充。"}
        不要调用工具。

        用户：
        $userText

        讨论记录：
        $prior
    """.trimIndent()

    const val HOST_SYSTEM = "你是 AI 群聊的主持人。只整理问题和汇报，不扮演其他模型，不编造他人没说过的话。"

    fun memberSystem(name: String): String = """
        你是 $name，正在和其他模型的同一场群聊里发言。
        只代表你自己。可以调用分析工具，但用户只能看到你的最终发言，看不到工具过程。
        正文不要出现工具名、参数或“正在调用”。没有新观点时只回复 PASS。
    """.trimIndent()
}
