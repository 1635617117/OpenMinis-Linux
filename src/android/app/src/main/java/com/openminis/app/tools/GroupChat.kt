package com.openminis.app.tools

/**
 * Same-page AI group chat. Each model speaks as itself. The host only frames
 * the question and writes the closing report; it does not impersonate the others.
 */
object GroupChat {
    const val SPEAKER_PART = "speaker"
    const val VENDOR_UNKNOWN = "unknown"

    /**
     * Model family, not the wire protocol. Matching is fuzzy: punctuation,
     * slashes, brackets and glued version numbers are stripped before alias
     * search, so `models/DeepSeek-V3:latest` and `gpt4o-mini` still resolve.
     * More specific families are checked before OpenAI, so an OpenAI-compatible
     * DeepSeek does not inherit the OpenAI mark.
     */
    fun vendorKey(modelId: String?, displayName: String?, providerType: String? = null): String {
        val compact = compactVendorText(modelId) + " " + compactVendorText(displayName)
        val segments = listOfNotNull(modelId, displayName)
            .flatMap { it.split(Regex("[^A-Za-z0-9\\u4e00-\\u9fff]+")) }
            .map { compactVendorText(it) }
            .filter { it.isNotEmpty() }
        if (compact.isNotBlank()) {
            VENDOR_ALIASES.firstOrNull { (_, aliases) ->
                aliases.any { matchesVendor(compact.replace(" ", ""), segments, it) }
            }?.first?.let { return it }
        }
        return when (providerType?.trim()) {
            "anthropic" -> "anthropic"
            "gemini" -> "gemini"
            "xAI" -> "xai"
            "kimiCode" -> "kimi"
            "openRouter" -> "openrouter"
            "openAI", "openAIResponses" -> "openai"
            else -> VENDOR_UNKNOWN
        }
    }

    internal fun compactVendorText(raw: String?): String =
        raw.orEmpty().lowercase().replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "")

    private val VENDOR_ALIASES = listOf(
        "deepseek" to listOf("deepseek", "深度求索"),
        "qwen" to listOf("qwen", "qwq", "qvq", "tongyi", "通义", "千问"),
        "kimi" to listOf("kimi", "moonshot", "月之暗面"),
        "doubao" to listOf("doubao", "豆包"),
        "anthropic" to listOf("claude", "anthropic"),
        "gemini" to listOf("gemini", "gemma"),
        "xai" to listOf("grok", "xai"),
        "mistral" to listOf("mistral", "mixtral", "pixtral", "codestral"),
        "meta" to listOf("llama", "metallama"),
        "zhipu" to listOf("chatglm", "zhipu", "智谱", "glm"),
        "minimax" to listOf("minimax", "abab", "hailuo", "海螺"),
        "hunyuan" to listOf("hunyuan", "混元"),
        "ernie" to listOf("ernie", "wenxin", "文心"),
        "baichuan" to listOf("baichuan", "百川"),
        "stepfun" to listOf("stepfun"),
        "internlm" to listOf("internlm", "internvl"),
        "groq" to listOf("groq"),
        "cohere" to listOf("cohere", "commandr"),
        "perplexity" to listOf("perplexity", "pplx"),
        "openrouter" to listOf("openrouter"),
        "openai" to listOf("chatgpt", "openai", "gpt", "dalle", "o1", "o3", "o4"),
    )

    private fun matchesVendor(compact: String, segments: List<String>, alias: String): Boolean {
        val token = compactVendorText(alias)
        if (token.isEmpty()) return false
        val cjk = token.any { it.code > 127 }
        if (cjk || token.length >= 5) return compact.contains(token)
        return compact == token || compact.startsWith(token) ||
            segments.any { it == token || it.startsWith(token) }
    }

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

    /** Four lenses so parallel speakers do not write the same essay. */
    fun stance(index: Int): String = STANCES[index.mod(STANCES.size).let { if (it < 0) it + STANCES.size else it }]

    fun isCloseRequest(text: String): Boolean {
        val compact = compactVendorText(text)
        if (compact.isEmpty()) return false
        return CLOSE_REQUESTS.any { request ->
            val token = compactVendorText(request)
            compact == token || compact.startsWith(token)
        }
    }

    /**
     * One participant the composer can @. [name] is the speaker label used in
     * bubbles; [modelId] is an extra alias so `@mimo-v2.6-pro` still finds a
     * model whose display name is different.
     */
    data class MentionCandidate(
        val name: String,
        val modelId: String,
        val vendor: String,
        val host: Boolean,
        /** Visible identity that is not the model name, such as the host soul name. */
        val extra: String = "",
    )

    /**
     * Canonical speaker plus every string the user may type after @.
     * [key] distinguishes two models that share a display name.
     */
    data class Addressable(
        val name: String,
        val aliases: List<String> = listOf(name),
        val key: String = name,
    )

    /**
     * The first @ that names a participant, anywhere after whitespace.
     * Emails (`a@b.com`) and unmatched tokens, including skill paths, stay
     * ordinary text so the whole group still answers.
     */
    fun addressedName(text: String, names: List<String>): String? =
        resolveAddress(text, names.map { Addressable(it) })

    fun resolveAddress(text: String, targets: List<Addressable>): String? =
        resolveTarget(text, targets)?.name

    fun resolveTarget(text: String, targets: List<Addressable>): Addressable? {
        if (text.isEmpty() || targets.isEmpty()) return null
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if ((ch == '@' || ch == '＠') && (i == 0 || text[i - 1].isWhitespace())) {
                matchRest(text.substring(i + 1), targets)?.let { return it }
            }
            i++
        }
        return null
    }

    fun mentionCandidates(host: MentionCandidate, others: List<MentionCandidate>): List<MentionCandidate> {
        val hostName = host.name.trim().ifBlank { host.modelId.trim() }.ifBlank { "Host" }
        val hostRow = host.copy(name = hostName, host = true)
        val seen = mutableSetOf(hostRow.name to hostRow.modelId)
        val rest = others.mapNotNull { other ->
            val name = other.name.trim().ifBlank { other.modelId.trim() }
            if (name.isEmpty()) return@mapNotNull null
            val row = other.copy(name = name, host = false)
            if (!seen.add(row.name to row.modelId)) return@mapNotNull null
            row
        }
        return listOf(hostRow) + rest
    }

    fun filterMentions(candidates: List<MentionCandidate>, filter: String): List<MentionCandidate> {
        val query = compactVendorText(filter)
        if (query.isEmpty()) return candidates
        return candidates.filter { candidate ->
            compactVendorText(candidate.name).contains(query) ||
                compactVendorText(candidate.modelId).contains(query) ||
                compactVendorText(candidate.extra).contains(query)
        }
    }

    /** Unique display name when possible; model id only when names collide. */
    fun mentionInsertToken(candidate: MentionCandidate, roster: List<MentionCandidate>): String {
        val name = candidate.name.trim()
        if (name.isNotEmpty() && roster.count { it.name == candidate.name } == 1) return name
        val id = candidate.modelId.trim()
        if (id.isNotEmpty()) return id
        return name.ifBlank { "Host" }
    }

    private fun matchRest(rest: String, targets: List<Addressable>): Addressable? {
        if (rest.isEmpty()) return null
        var best: Addressable? = null
        var bestLen = 0
        for (target in targets) {
            for (alias in target.aliases) {
                val len = boundaryPrefixLength(rest, alias)
                if (len > bestLen) {
                    best = target
                    bestLen = len
                }
            }
        }
        if (best != null) return best
        val token = Regex("""^([^\s:：,，]{1,80})""").find(rest)?.groupValues?.getOrNull(1) ?: return null
        val compactToken = compactVendorText(token)
        if (compactToken.length < 2) return null
        return targets.maxByOrNull { target ->
            target.aliases.maxOfOrNull { aliasScore(compactToken, it) } ?: 0
        }?.takeIf { target ->
            target.aliases.any { aliasScore(compactToken, it) > 0 }
        }
    }

    private fun boundaryPrefixLength(rest: String, alias: String): Int {
        val name = alias.trim()
        if (name.length < 2 || rest.length < name.length) return 0
        if (!rest.regionMatches(0, name, 0, name.length, ignoreCase = true)) return 0
        val next = rest.getOrNull(name.length)
        if (next != null && !next.isWhitespace() && next !in ",，:：。.!！?？") return 0
        return name.length
    }

    private fun aliasScore(compactToken: String, alias: String): Int {
        val compactName = compactVendorText(alias)
        if (compactName.isEmpty() || compactToken.length < 2) return 0
        return when {
            compactName == compactToken -> 1000 + compactName.length
            compactName.startsWith(compactToken) -> 800 + compactToken.length
            compactToken.startsWith(compactName) -> 600 + compactName.length
            compactToken.length >= 3 && compactName.contains(compactToken) -> 400 + compactToken.length
            else -> 0
        }
    }

    fun opinionPrompt(name: String, stance: String, userText: String, prior: String, context: String): String = """
        你是 $name。本轮你的立场是「$stance」，不要改成和其他人一样的综述。
        ${stanceGuide(stance)}
        可以同意或反对已有发言，但必须写出依据。不要扮演其他人。
        需要查资料时可以调用工具；工具过程不会展示，正文里不要描述工具调用。
        用用户的语言，80 到 180 字。没有把握的地方直接说不确定。

        用户：
        $userText

        已有发言：
        ${prior.ifBlank { "（还没有其他人发言）" }}

        近期上下文：
        ${context.ifBlank { "（无）" }}
    """.trimIndent()

    fun directPrompt(name: String, userText: String, prior: String, context: String): String = """
        你是 $name。用户点名让你回答，其他模型本轮不发言。
        直接回答，不要写群聊总结，不要扮演别人。
        需要查资料可以调用工具，正文不要描述工具过程。
        用用户的语言，120 到 260 字。

        用户：
        $userText

        已有讨论：
        ${prior.ifBlank { "（无）" }}

        近期上下文：
        ${context.ifBlank { "（无）" }}
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

    fun summaryPrompt(userText: String, prior: String): String = """
        你是主持人。请把这场群聊整理成给用户的汇报，不要编造没人说过的观点。
        用用户的语言，分成三段：共识、分歧、建议。分歧要写清是谁和谁不同。
        这是讨论的结束汇报，不要再向其他模型提问。
        不要调用工具。

        用户：
        $userText

        讨论记录：
        $prior
    """.trimIndent()

    const val HOST_SYSTEM = "你是 AI 群聊的主持人。只在讨论结束时汇报，不扮演其他模型，不编造他人没说过的话。"

    private val STANCES = listOf("主张", "质疑", "补漏", "落地")

    private val CLOSE_REQUESTS = listOf(
        "结束讨论",
        "结束这场讨论",
        "总结一下",
        "请总结",
        "出个结论",
        "收束讨论",
    )

    private fun stanceGuide(stance: String): String = when (stance) {
        "质疑" -> "指出最危险的假设或错误，不要重复别人的结论。"
        "补漏" -> "只补别人没覆盖的边界、成本或失败场景。"
        "落地" -> "写出下一步可以执行的做法，不要再展开原则。"
        else -> "给出一个明确主张：你推荐什么，以及为什么。"
    }

    fun memberSystem(name: String): String = """
        你是 $name，正在和其他模型的同一场群聊里发言。
        只代表你自己。可以调用分析工具。界面会显示你当前的思考或工具，正文出来后这些状态会收起。
        正文不要出现工具名、参数或“正在调用”。只有补充轮明确要求时，才用 PASS 表示没有新观点。
    """.trimIndent()
}
