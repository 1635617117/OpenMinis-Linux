package com.openminis.app.agent

import java.io.File

/**
 * [T-generation-run] Minimal generation-run ledger for crash recovery
 * (Kelivo GenerationRun, scoped down): every model turn stamps a run
 * record — session, turn anchor, model, status, timestamps — persisted
 * under `offloads/generation-runs/`. On a crash mid-generation the
 * half-written stream is discoverable afterwards (which session, which
 * model, how far it got), instead of vanishing silently.
 *
 * The streaming content itself is checkpointed by the existing per-turn
 * append pipeline; this ledger is the recovery index over it.
 */
object GenerationRunStore {
    enum class Status { RUNNING, DONE, FAILED, ABANDONED }

    data class Run(
        val runId: String,
        val sessionId: String,
        val modelId: String,
        val status: Status,
        val startedAtMs: Long,
        val finishedAtMs: Long? = null,
        val turnAnchor: String = "",
    )

    private const val DIR = "/var/minis/workspace/offloads/generation-runs"
    private const val KEEP = 40

    fun start(sessionId: String, modelId: String, turnAnchor: String = ""): String {
        val runId = java.util.UUID.randomUUID().toString().take(8)
        val run = Run(runId, sessionId, modelId, Status.RUNNING, System.currentTimeMillis(), null, turnAnchor)
        write(run)
        return runId
    }

    fun finish(runId: String, ok: Boolean) {
        val dir = File(DIR)
        val file = File(dir, "$runId.json")
        if (!file.exists()) return
        val run = read(file) ?: return
        write(run.copy(status = if (ok) Status.DONE else Status.FAILED, finishedAtMs = System.currentTimeMillis()))
        trim(dir)
    }

    /** Runs that were RUNNING when the process died — crash candidates. */
    fun abandoned(): List<Run> {
        val dir = File(DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.mapNotNull { read(it) }
            ?.filter { it.status == Status.RUNNING }
            ?.sortedBy { it.startedAtMs }
            ?: emptyList()
    }

    fun markAbandoned(runId: String) {
        val file = File(DIR, "$runId.json")
        val run = read(file) ?: return
        write(run.copy(status = Status.ABANDONED, finishedAtMs = System.currentTimeMillis()))
    }

    /**
     * Runs for one session that were marked abandoned by the most recent
     * launch scan — i.e. the previous process died mid-generation on this
     * session. The UI offers a recovery hint from this list.
     */
    fun recentlyAbandonedFor(sessionId: String, windowMs: Long = 10 * 60_000): List<Run> {
        val dir = File(DIR)
        if (!dir.exists()) return emptyList()
        val cutoff = System.currentTimeMillis() - windowMs
        return dir.listFiles()?.mapNotNull { read(it) }
            ?.filter { it.sessionId == sessionId && it.status == Status.ABANDONED && (it.finishedAtMs ?: 0L) >= cutoff }
            ?.sortedBy { it.startedAtMs }
            ?: emptyList()
    }

    private fun write(run: Run) {
        runCatching {
            val dir = File(DIR)
            dir.mkdirs()
            val json = org.json.JSONObject()
                .put("runId", run.runId)
                .put("sessionId", run.sessionId)
                .put("modelId", run.modelId)
                .put("status", run.status.name)
                .put("startedAtMs", run.startedAtMs)
                .put("finishedAtMs", run.finishedAtMs ?: 0L)
                .put("turnAnchor", run.turnAnchor)
            File(dir, "${run.runId}.json").writeText(json.toString(2))
        }
    }

    private fun read(file: File): Run? = runCatching {
        val obj = org.json.JSONObject(file.readText())
        Run(
            runId = obj.getString("runId"),
            sessionId = obj.getString("sessionId"),
            modelId = obj.getString("modelId"),
            status = runCatching { Status.valueOf(obj.getString("status")) }.getOrDefault(Status.ABANDONED),
            startedAtMs = obj.getLong("startedAtMs"),
            finishedAtMs = obj.optLong("finishedAtMs", 0L).takeIf { it > 0 },
            turnAnchor = obj.optString("turnAnchor"),
        )
    }.getOrNull()

    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP).forEach { runCatching { it.delete() } }
    }
}
