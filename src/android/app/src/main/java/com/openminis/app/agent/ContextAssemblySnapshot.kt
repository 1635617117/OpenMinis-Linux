package com.openminis.app.agent

import java.io.File

/**
 * [T-context-assembly-preview] Captures the assembled system prompt for
 * debugging "what did the model actually receive". Every capture keeps a
 * per-section size breakdown (first line of each paragraph as its label)
 * plus the full text, both in memory (ring of 8) and on disk under
 * `offloads/context-assembly/` so the agent itself can read it back with
 * shell tools.
 */
object ContextAssemblySnapshot {
    data class Snapshot(
        val capturedAtMs: Long,
        val sessionId: String,
        val totalChars: Int,
        val sections: List<Pair<String, Int>>,
        val fullText: String,
    )

    @Volatile
    var latest: Snapshot? = null
        private set

    private const val KEEP = 8
    private val ring = ArrayDeque<Snapshot>()
    private const val DIR = "/var/minis/workspace/offloads/context-assembly"

    @Synchronized
    fun capture(fullText: String, sessionId: String) {
        val sections = fullText
            .split(Regex("(?m)^#"))
            .mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) null
                else {
                    val head = trimmed.lineSequence().firstOrNull()
                        ?.take(60).orEmpty().trim().ifBlank { "(section)" }
                    head to trimmed.length
                }
            }
            .sortedByDescending { it.second }
        val snapshot = Snapshot(
            capturedAtMs = System.currentTimeMillis(),
            sessionId = sessionId,
            totalChars = fullText.length,
            sections = sections,
            fullText = fullText,
        )
        latest = snapshot
        ring.addLast(snapshot)
        while (ring.size > KEEP) ring.removeFirst()
        runCatching {
            val dir = File(DIR)
            dir.mkdirs()
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date(snapshot.capturedAtMs))
            val sb = StringBuilder()
            sb.append("# 上下文组装快照 ").append(stamp)
                .append(" | 会话 ").append(sessionId)
                .append(" | 总字符 ").append(fullText.length)
                .append("\n\n## 构成（按大小降序）\n\n")
            sections.forEach { (label, len) ->
                sb.append("- ").append(len).append(" 字符 — ").append(label).append("\n")
            }
            sb.append("\n## 全文\n\n").append(fullText)
            File(dir, "latest.md").writeText(sb.toString())
        }
    }

    fun recent(): List<Snapshot> = synchronized(this) { ring.toList() }
}
