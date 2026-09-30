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
        var out = text
        val env = runCatching { System.getenv() }.getOrDefault(emptyMap())
        for (value in env.values) {
            if (value.length < 6) continue
            if (!value.any { it.isLetter() } || !value.any { it.isDigit() }) continue
            if (out.contains(value)) {
                out = out.replace(value, REDACTED)
            }
        }
        return out
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
