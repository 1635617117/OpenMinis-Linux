package com.openminis.app.tools

import java.io.File

/**
 * [T-tool-output-policy] Unified post-processing for every tool result
 * before it reaches the model.
 *
 * 1. Environment-variable redaction (Kelivo-equivalent): any guest env var
 *    value of 6+ chars containing both letters and digits is replaced with
 *    `[REDACTED]` — the agent gets to *see* tool output without learning
 *    the API keys / tokens that happened to leak into it.
 * 2. Oversized output spill (RikkaHub-equivalent): instead of hard-truncating,
 *    the full text is written to `tool_outputs/<toolId>.txt` inside the
 *    sandbox workspace and the model is told how to page through it with
 *    cat/grep — context growth becomes model-driven retrieval, and the
 *    full content is never silently lost.
 */
object ToolOutputPolicy {
    const val TOOL_OUTPUT_CHAR_LIMIT = 12_000
    const val TOOL_OUTPUTS_DIR = "tool_outputs"
    private const val REDACTED = "[REDACTED]"
    private const val PREVIEW_CHARS = 2_000

    /** Sandbox workspace root as seen by shell_execute. */
    var workspaceRoot: String = "/var/minis/workspace"

    fun redactEnvVars(text: String): String {
        val env = runCatching { System.getenv() }.getOrDefault(emptyMap())
        val sensitive = env.values.filter { v ->
            v.length >= 6 && v.any { it.isLetter() } && v.any { it.isDigit() }
        }
        return redactWith(text, sensitive)
    }

    /** Testable core: apply redaction against an explicit sensitive list. */
    internal fun redactWith(text: String, sensitive: List<String>): String {
        if (sensitive.isEmpty() || text.isBlank()) return text
        // Kelivo 式结构化脱敏：JSON 里字符串值经转义后纯文本 replace 会漏
        // （引号逃逸），先解析再递归替换每个字符串值，命中面完整得多。
        runCatching {
            val node = org.json.JSONTokener(text).nextValue()
            if (node is org.json.JSONObject || node is org.json.JSONArray) {
                return redactNode(node, sensitive).toString()
            }
        }
        // 非 JSON 输出：退回纯文本替换。
        var out = text
        for (v in sensitive) {
            if (out.contains(v)) out = out.replace(v, REDACTED)
        }
        return out
    }

    private fun redactNode(node: Any?, sensitive: List<String>): Any? = when (node) {
        is org.json.JSONObject -> {
            val out = org.json.JSONObject()
            node.keys().forEach { k ->
                runCatching { out.put(k, redactNode(node.get(k), sensitive)) }
            }
            out
        }
        is org.json.JSONArray -> {
            val out = org.json.JSONArray()
            for (i in 0 until node.length()) {
                runCatching { out.put(redactNode(node.get(i), sensitive)) }
            }
            out
        }
        is String -> {
            var s: String = node
            for (v in sensitive) {
                if (s.contains(v)) s = s.replace(v, REDACTED)
            }
            s
        }
        else -> node
    }

    /**
     * Apply both policies. [toolId] is the tool-call id; blank ids fall back
     * to a timestamped file name so no content is lost.
     */
    fun apply(output: String, toolId: String): String {
        var text = redactEnvVars(output)
        if (text.length <= TOOL_OUTPUT_CHAR_LIMIT) return text
        val dir = File(workspaceRoot, TOOL_OUTPUTS_DIR)
        runCatching { dir.mkdirs() }
        val safeId = toolId.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "out" }
        val file = File(dir, "$safeId.txt")
        val written = runCatching {
            file.writeText(text)
            true
        }.getOrDefault(false)
        if (!written) return text
        val head = text.take(PREVIEW_CHARS)
        return buildString {
            append(head)
            append("\n\n[工具输出过长：全文 ")
            append(text.length)
            append(" 字符已写入沙箱 ")
            append("$TOOL_OUTPUTS_DIR/${file.name}")
            append("，可用 shell_execute 的 cat/grep 分页检索，不用一次性读完]")
        }
    }
}
